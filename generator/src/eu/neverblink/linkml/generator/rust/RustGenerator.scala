package eu.neverblink.linkml.generator.rust

import eu.neverblink.linkml.generator.CharDocumentGenerator
import eu.neverblink.linkml.generator.options.RustGeneratorOptions
import eu.neverblink.linkml.rdf.io.CharSink
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable

/** Generates a Rust crate with a struct for each class, an enum for each enum and a type alias for
  * each type and slot, with serde and PyO3 support behind Cargo features. Each class also gets a
  * trait with a getter for each of its slots, which the structs of the class and its descendants
  * implement.
  *
  * This is a port of the Rust generator of LinkML-Py, written by Jonny Saunders and Frank Dekervel.
  * It generates mostly the same code, but some names of classes, enums and enum variants differ, as
  * [[RustRenamer]] makes them the same way as the other generators. It also has the changes of the
  * open pull requests to that generator, and more fixes, listed on the Rust page of the docs.
  *
  * @see
  *   https://github.com/linkml/linkml/tree/main/packages/linkml/src/linkml/generators/rustgen
  */
final class RustGenerator(using sv: SchemaView)
    extends CharDocumentGenerator[RustGeneratorOptions] {
  import RustGenerator.*

  override protected def defaultOptions: RustGeneratorOptions = RustGeneratorOptions()

  /** The files of the crate, by path: `Cargo.toml`, `pyproject.toml` and the sources in `src/`. */
  def generateFiles(options: RustGeneratorOptions = defaultOptions): Seq[(String, String)] =
    RustWriter(crate(options), options).crateFiles

  /** The whole crate as one `.rs` file, without the polymorphic traits. */
  override protected def writeChars(sink: CharSink, options: RustGeneratorOptions): Unit =
    sink.append(RustWriter(crate(options), options).singleFile)

  /** The crate name: the `crate_name` option, or a name made from the schema name. */
  def crateName(options: RustGeneratorOptions): String =
    options.crateName.fold(crateNameOf(sv.root.name)) { name =>
      if !isCrateName(name) then
        throw new IllegalArgumentException(
          s"Invalid crate name '$name': it must be an ASCII identifier that is not a Rust " +
            "keyword or the name of a Rust library or of a dependency, such as 'std' or 'serde'.",
        )
      name
    }

  private def crate(options: RustGeneratorOptions): Crate = {
    val crate = CrateBuilder(options).crate(crateName(options))
    val taken = itemNames(crate)
    checkFields(crate)
    // The alias of a slot is only a convenience, so a slot gets none if another item has its name,
    // such as a slot named like the type `date`
    crate.copy(slots = crate.slots.filterNot((_, a) => taken(a.name)).distinctBy(_._2.name))
  }

  /** The names of the items of the crate, except the aliases of slots.
    *
    * @throws IllegalArgumentException
    *   if two items have the same name, or an item has the name of something that the generated
    *   code uses
    */
  private def itemNames(crate: Crate): collection.Set[String] = {
    val seen = mutable.HashMap.empty[String, String]
    def claim(name: String, owner: String): Unit =
      seen.put(name, owner).foreach { other =>
        throw new IllegalArgumentException(
          s"$owner and $other both map to the Rust name '$name'. " +
            "Rename one of them to generate Rust.",
        )
      }
    reservedNames.foreach(claim(_, "the generated code"))
    crate.types.foreach((t, alias) => claim(alias.name, s"type '${t.name}'"))
    crate.enums.foreach { (e, model) =>
      val name = model match {
        case e: RustEnum => e.name
        case t: TypeAlias => t.name
      }
      claim(name, s"enum '${e.name}'")
    }
    crate.subpropertyEnums.foreach((s, model) => claim(model.name, s"the enum of slot '$s'"))
    crate.classes.foreach { (c, model) =>
      claim(model.name, s"class '${c.name}'")
      model match {
        case s: RustClass.Struct =>
          s.subtypes.foreach(e => claim(e.name, s"the subtype enum of class '${c.name}'"))
          if s.unions.nonEmpty then claim(s.unionModule, s"the union module of class '${c.name}'")
        case _ =>
      }
    }
    seen.keySet
  }

  /** Fails if two slots of a class have the same field name, such as `in` and `in_`. */
  private def checkFields(crate: Crate): Unit =
    crate.classes.foreach {
      case (c, s: RustClass.Struct) =>
        s.properties.groupBy(_.name).foreach { (name, properties) =>
          if properties.sizeIs > 1 then
            throw new IllegalArgumentException(
              s"Slots ${properties.map(p => s"'${p.slot}'").mkString(" and ")} of class " +
                s"'${c.name}' both map to the Rust field '$name'. Rename one of them to generate " +
                "Rust.",
            )
        }
      case _ =>
    }
}

object RustGenerator {
  import RustRenamer.{isIdentifierChar, keywords}

  /** Names that cargo refuses for a crate, as they are the names of Rust's own libraries, and the
    * names of the crates that the generated crate depends on.
    */
  private val reservedCrateNames: Set[String] = Set(
    "alloc",
    "core",
    "proc_macro",
    "std",
    "test",
    "chrono",
    "merge",
    "pyo3",
    "pyo3_stub_gen",
    "serde",
    "serde_path_to_error",
    "serde_value",
    "yaml_serde",
  )

  /** The crate name used when the schema's name gives no valid one. */
  val defaultCrateName: String = "example"

  /** Whether [[name]] can name a crate: an ASCII identifier that is neither a keyword nor reserved
    * by cargo. A hyphen, which cargo allows in package names, can't be in the `[lib]` name.
    */
  def isCrateName(name: String): Boolean =
    name != "_" && !keywords(name) && !reservedCrateNames(name) && name.nonEmpty &&
      (name.head.isLetter || name.head == '_') && name.forall(isIdentifierChar)

  /** A valid crate name made from [[schemaName]], or [[defaultCrateName]] if there is none. */
  def crateNameOf(schemaName: String): String = {
    val replaced = schemaName.map(c => if isIdentifierChar(c) then c else '_')
    val name =
      if keywords(replaced) || reservedCrateNames(replaced) then replaced + "_" else replaced
    if isCrateName(name) then name else defaultCrateName
  }

  /** Whether an enum becomes a Rust enum. An enum without permissible values becomes a `String`. */
  def definesEnum(ev: EnumView): Boolean = ev._enum.permissibleValues.nonEmpty

  /** Names that the generated code imports or uses without a path, so no item can have them. */
  private val reservedNames: Seq[String] = Seq(
    "String",
    "Vec",
    "Option",
    "Box",
    "Result",
    "bool",
    "f64",
    "isize",
    "str",
    "HashMap",
    "BTreeMap",
    "Value",
    "Serialize",
    "Deserialize",
    "IntoDeserializer",
    "FromPyObject",
    "Merge",
    "NaiveDate",
    "NaiveDateTime",
    RustSupport.localizedTextName,
    "serde_utils",
    "poly",
    "poly_containers",
    "stub_utils",
  )
}
