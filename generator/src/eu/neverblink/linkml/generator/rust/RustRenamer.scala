package eu.neverblink.linkml.generator.rust

import eu.neverblink.linkml.generator.util.Renamer
import eu.neverblink.linkml.metamodel.{PermissibleValue, SlotDefinition}
import eu.neverblink.linkml.schemaview.*

/** Names in the generated Rust:
  *   - classes, enums and enum variants: PascalCase, such as `NamedThing` for `named thing`
  *   - slots, types and functions: the name with `_` for spaces and `-`, such as `has_part` for
  *     `has part`, as in LinkML-Py
  *
  * A name that is a Rust keyword gets a trailing `_`, such as `type_`. So does a slot named after a
  * Python keyword, as the Python bindings use the field names.
  *
  * Each name comes from the name of its element alone. So it differs from the generated code in two
  * cases, where the generator looks at the other names too:
  *   - a permissible value whose variant name an earlier value of the enum has, such as `m` after
  *     `M`, gets a `_` added in the code: `M_`
  *   - a slot gets no type alias if another item has its name, though [[slotName]] still gives the
  *     field name
  *
  * The generated crate has no functions, so [[functionName]] gives the name that a Rust function
  * would have.
  */
object RustRenamer extends Renamer {

  /** Strict and reserved Rust keywords of the 2021 edition, which the generated crate uses. `gen`
    * is reserved only from the 2024 edition.
    *
    * @see
    *   https://doc.rust-lang.org/reference/keywords.html
    */
  val keywords: Set[String] = Set(
    "as",
    "async",
    "await",
    "break",
    "const",
    "continue",
    "crate",
    "dyn",
    "else",
    "enum",
    "extern",
    "false",
    "fn",
    "for",
    "if",
    "impl",
    "in",
    "let",
    "loop",
    "match",
    "mod",
    "move",
    "mut",
    "pub",
    "ref",
    "return",
    "self",
    "Self",
    "static",
    "struct",
    "super",
    "trait",
    "true",
    "try",
    "type",
    "unsafe",
    "use",
    "where",
    "while",
    "abstract",
    "become",
    "box",
    "do",
    "final",
    "macro",
    "override",
    "priv",
    "typeof",
    "unsized",
    "virtual",
    "yield",
  )

  /** Python keywords, which can't be the names of fields in the Python bindings. */
  private val pythonKeywords: Set[String] = Set(
    "False",
    "None",
    "True",
    "and",
    "as",
    "assert",
    "async",
    "await",
    "break",
    "class",
    "continue",
    "def",
    "del",
    "elif",
    "else",
    "except",
    "finally",
    "for",
    "from",
    "global",
    "if",
    "import",
    "in",
    "is",
    "lambda",
    "nonlocal",
    "not",
    "or",
    "pass",
    "raise",
    "return",
    "try",
    "while",
    "with",
    "yield",
  )

  def className(el: ClassView): String = pascal(el.baseName)

  def classAttributeName(el: ClassView, attr: SlotDefinition): String = field(attr.name)

  def slotName(el: SlotView): String = field(el.name)

  def typeName(el: TypeView): String = snake(el.name)

  def enumName(el: EnumView): String = pascal(el.baseName)

  def permissibleValueName(el: EnumView, pv: PermissibleValue): String = variantName(pv.text)

  def functionName(el: FunctionView): String = snake(el.name)

  /** The name of an enum variant for a permissible value or a slot. */
  def variantName(text: String): String = pascal(Case.base(text))

  /** The key of a slot in serialized data: its name, even if the field got a trailing `_`. */
  def serdeName(slot: SlotView): String = underscore(slot.name)

  /** The name of the enum of the slots that a slot with `subproperty_of` can hold, such as
    * `PredicateEnum`.
    */
  def subpropertyEnumName(slot: SlotView): String = pascal(slot.baseName) + "Enum"

  /** The name of the `OrSubtype` enum of a class, such as `NamedThingOrSubtype`. */
  def subtypeEnumName(cls: ClassView): String = className(cls) + "OrSubtype"

  /** The name of the module with the union enums of a class's slots, such as `named_thing_utl`. */
  def unionModuleName(cls: ClassView): String = identifier(cls.baseName + "_utl")

  /** The name of the function that loads a YAML file into a class, such as `load_yaml_zoo`. */
  def loaderName(cls: ClassView): String = "load_yaml_" + cls.baseName

  /** LinkML-Py's `underscore`: whitespace becomes `_`, `-` becomes `_` and `,` is dropped. */
  def underscore(text: String): String =
    text.strip.replaceAll("\\s+", "_").replace(",", "").replace('-', '_')

  private def pascal(baseName: String): String = protect(identifier(Case.baseToPascal(baseName)))

  private def snake(name: String): String = protect(identifier(underscore(name)))

  private def field(name: String): String = {
    val field = identifier(underscore(name))
    protect(if pythonKeywords(field) then field + "_" else field)
  }

  /** [[name]] with every character that can't be in a Rust identifier replaced by `_`, a `_` in
    * front of a leading digit, and a `V` in front of a name of only `_`, which Rust reserves.
    */
  private def identifier(name: String): String = {
    val replaced = name.map(c => if isIdentifierChar(c) then c else '_')
    if replaced.forall(_ == '_') then "V" + replaced
    else if replaced.head.isDigit then "_" + replaced
    else replaced
  }

  private[rust] def isIdentifierChar(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'

  private def protect(name: String): String = if keywords(name) then name + "_" else name
}
