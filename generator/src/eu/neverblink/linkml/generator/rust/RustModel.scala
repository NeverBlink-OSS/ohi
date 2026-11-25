package eu.neverblink.linkml.generator.rust

import eu.neverblink.linkml.schemaview.{ClassView, EnumView, SlotView, TypeView}

/** The collection type of a multivalued slot. */
enum Container {
  case List, Mapping
}

/** How the value of a slot is stored: as an object inside the parent object, as the identifier of
  * an object stored elsewhere, or as a value of a type or an enum.
  */
enum SlotInline {
  case Inline, Primitive, Reference
}

/** The Rust type of a slot.
  *
  * @param tpe
  *   the type of one value, or of the union enum when the slot has several ranges
  * @param optional
  *   whether the field is an `Option`
  * @param container
  *   whether the field holds a `Vec` or a `HashMap` of values
  * @param isClassRange
  *   whether the slot has a single range, which is a class
  * @param isReference
  *   whether the values are identifiers of objects, which are strings
  * @param boxNeeded
  *   whether a value is in a `Box`, as it can contain an object of the struct's own class
  * @param hasClassSubtypes
  *   whether the range is a class with subclasses, so a value is its `OrSubtype` enum
  * @param members
  *   the members of the union, if the slot has more than one range
  */
final case class RustRange(
    tpe: String,
    optional: Boolean = false,
    container: Option[Container] = None,
    isClassRange: Boolean = false,
    isReference: Boolean = false,
    boxNeeded: Boolean = false,
    hasClassSubtypes: Boolean = false,
    members: Seq[RustRange] = Nil,
) {
  import RustRange.*

  def isUnion: Boolean = members.sizeIs > 1

  /** The type without its module path. */
  def typeName: String = tpe.split("::").last

  /** Whether the values are `Copy`, so getters return them by value. */
  def isCopy: Boolean = copyTypes(tpe)

  /** Whether the values are objects of a class, inlined. */
  private def isInlineClass: Boolean = isClassRange && !isReference

  /** The type of one value: [[tpe]], or its `OrSubtype` enum if it is a class with subclasses. */
  def valueType: String = if hasClassSubtypes && !isReference then tpe + "OrSubtype" else tpe

  /** The type of the struct field. */
  def fieldType: String = withOption(fieldTypeWithoutOption)

  private def fieldTypeWithoutOption: String =
    withContainer(if boxNeeded then s"Box<$valueType>" else valueType)

  /** The type of the parameter of the PyO3 constructor. A class is read with serde, so that a
    * Python dict works as well as an object of the class.
    */
  def constructorType: String =
    if isInlineClass then withOption(s"serde_utils::PyValue<$fieldTypeWithoutOption>")
    else fieldType

  /** The expression that turns the constructor parameter [[name]] into the field value, if it is
    * not the parameter itself.
    */
  def constructorConversion(name: String): Option[String] =
    Option.when(isInlineClass) {
      if optional then s"$name.map(|v| v.into_inner())" else s"$name.into_inner()"
    }

  /** The return type of the trait getter. A union and a `Copy` value are returned by value, a `Vec`
    * or `HashMap` as a `SeqRef` or `MapRef` view, and other values by reference.
    */
  def getterType: String = if isUnion then valueGetterType else borrowedGetterType

  private def borrowedGetterType: String = {
    val value =
      if isInlineClass && !hasClassSubtypes then s"crate::$tpe"
      else if hasClassSubtypes && !isReference then tpe + "OrSubtype"
      else tpe
    val viewed = container match {
      case Some(Container.List) => s"impl poly_containers::SeqRef<'a, $value>"
      case Some(Container.Mapping) => s"impl poly_containers::MapRef<'a, String,$value>"
      case None => value
    }
    val borrowed =
      if container.isEmpty && !isCopy && (!optional || isInlineClass) then s"&'a $viewed"
      else viewed
    val result =
      if !optional then borrowed
      else if container.isDefined || isInlineClass then s"Option<$borrowed>"
      else if borrowed == "String" then "Option<&'a str>"
      else if isCopy then s"Option<$borrowed>"
      else s"Option<&'a $borrowed>"
    if result == "&'a String" then "&'a str" else result
  }

  private def valueGetterType: String = {
    val value =
      if isUnion || !isInlineClass then tpe
      else if hasClassSubtypes then tpe + "OrSubtype"
      else s"crate::$tpe"
    withOption(withContainer(value))
  }

  private def withContainer(value: String): String = container match {
    case Some(Container.List) => s"Vec<$value>"
    case Some(Container.Mapping) => s"HashMap<String, $value>"
    case None => value
  }

  private def withOption(value: String): String = if optional then s"Option<$value>" else value
}

object RustRange {
  private val copyTypes: Set[String] =
    Set("i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64", "bool", "isize")

  /** Whether a getter with this return type borrows from `self`, and so needs a lifetime. */
  def needsLifetime(getterType: String): Boolean =
    getterType.contains("&") || getterType.contains("SeqRef") || getterType.contains("MapRef")
}

/** A field of a struct.
  *
  * @param slot
  *   the name of the slot in the schema
  * @param name
  *   the field name
  * @param rename
  *   the key in serialized data, if it is not the field name
  * @param alias
  *   another key that is accepted when reading: the slot name, if [[rename]] is the slot's `alias`
  * @param isKeyValue
  *   whether the range is a class that can be inlined as a dict
  * @param merge
  *   whether the struct derives `Merge`
  */
final case class RustProperty(
    slot: String,
    name: String,
    rename: Option[String],
    alias: Option[String],
    inlining: SlotInline,
    container: Option[Container],
    range: RustRange,
    required: Boolean,
    multivalued: Boolean,
    isKeyValue: Boolean,
    merge: Boolean,
)

/** The `serde_utils::InlinedPair` implementation of a class, which converts between an entry
  * `key: {...}` of a dict and an object of the class.
  *
  * @param simpleValue
  *   the slot that holds the value of an entry in the short form `key: value`, if the class has one
  * @param fromEmpty
  *   whether an entry without content, `key: {}` or `key:`, is a valid object, as no other
  *   single-valued slot is required
  */
final case class KeyValue(
    keyName: String,
    keySerdeName: String,
    keyType: String,
    keyAliases: Seq[String],
    simpleValue: Option[SimpleValue],
    fromEmpty: Boolean,
)

/** The slot that holds the value of an entry in the short form `key: value`. */
final case class SimpleValue(name: String, serdeName: String, tpe: String, optional: Boolean)

/** The `XOrSubtype` enum of a class with subclasses: a variant for each subclass, and one for the
  * class itself if it isn't abstract or a mixin.
  *
  * @param designator
  *   the key of the type designator slot in serialized data, if the class has one. Its value
  *   decides the variant when reading.
  * @param designatorValues
  *   the type designator values of each variant
  * @param keyType
  *   the type of the key, if the class can be inlined as a dict
  */
final case class SubtypeEnum(
    name: String,
    variants: Seq[String],
    designator: Option[String],
    designatorValues: Map[String, Seq[String]],
    keyType: Option[String],
)

/** The union enum of the ranges of a slot, in the module of its class.
  *
  * @param ranges
  *   the name of each variant, with the type of its content
  */
final case class SlotUnion(slotName: String, ranges: Seq[(String, String)]) {
  def name: String = SlotUnion.name(slotName)
}

object SlotUnion {

  /** The name of the union enum of a slot, such as `value_range`. */
  def name(slotName: String): String = slotName + "_range"
}

/** The Rust item that a class becomes. */
sealed trait RustClass {
  def name: String
}

object RustClass {

  /** A struct with a field for each slot. */
  final case class Struct(
      name: String,
      properties: Seq[RustProperty],
      merge: Boolean,
      unions: Seq[SlotUnion],
      unionModule: String,
      keyValue: Option[KeyValue],
      subtypes: Option[SubtypeEnum],
  ) extends RustClass

  /** A class with the `linkml:Any` URI and no slots: a struct that wraps a `serde_value::Value`. */
  final case class Any(name: String) extends RustClass

  /** Another class with the `linkml:Any` URI and no slots: a type alias of the [[Any]] struct. */
  final case class AnyAlias(name: String, target: String) extends RustClass
}

/** A Rust enum of the permissible values of an enum, or of the slots that a slot with
  * `subproperty_of` can hold.
  *
  * @param values
  *   the variant names, with the text of each in serialized data
  */
final case class RustEnum(name: String, values: Seq[(String, String)])

/** A type alias, for a type or a slot. */
final case class TypeAlias(name: String, target: String)

/** The trait of a class, with a getter for each slot that the class adds to its parents. */
final case class PolyTrait(
    name: String,
    supertraits: Seq[String],
    getters: Seq[(String, String)],
    impls: Seq[PolyImpl],
    subtypeImpls: Seq[PolySubtypeImpl],
)

/** The implementation of a trait for the struct of the trait's class or of one of its descendants.
  *
  * @param getters
  *   the name of each getter, with the range of the slot in this struct and the return range of the
  *   getter in the trait
  */
final case class PolyImpl(struct: String, getters: Seq[(String, RustRange, RustRange)])

/** The implementation of a trait for an `OrSubtype` enum, which calls the getter of the struct in
  * the variant.
  */
final case class PolySubtypeImpl(
    subtypeEnum: String,
    variants: Seq[String],
    getters: Seq[(String, RustRange)],
)

/** A dependency of the crate, and what the code imports from it.
  *
  * @param module
  *   the module path of the `use`, which starts with the crate name
  * @param version
  *   the version in `Cargo.toml`, `None` for the standard library
  * @param feature
  *   the crate feature that turns the dependency on, if it is optional
  * @param featureDependencies
  *   other features that the feature turns on
  */
final case class RustImport(
    module: String,
    objects: Seq[String] = Nil,
    version: Option[String] = None,
    features: Seq[String] = Nil,
    feature: Option[String] = None,
    featureDependencies: Seq[String] = Nil,
    alias: Option[String] = None,
) {

  /** The crate name, as in `Cargo.toml`. */
  def crate: String = module.takeWhile(_ != ':')
}

/** The struct of the `tree_root` class, and the function that loads a YAML file into it. */
final case class RootStruct(name: String, loader: String)

/** The model of a crate, with the schema element of each item. */
final case class Crate(
    name: String,
    version: String,
    types: Seq[(TypeView, TypeAlias)],
    slots: Seq[(SlotView, TypeAlias)],
    enums: Seq[(EnumView, RustEnum | TypeAlias)],
    subpropertyEnums: Seq[(String, RustEnum)],
    classes: Seq[(ClassView, RustClass)],
    traits: Seq[PolyTrait],
    imports: Seq[RustImport],
    rootStruct: Option[RootStruct],
    usesLocalizedText: Boolean,
)
