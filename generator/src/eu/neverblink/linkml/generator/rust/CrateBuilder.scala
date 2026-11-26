package eu.neverblink.linkml.generator.rust

import eu.neverblink.linkml.generator.options.RustGeneratorOptions
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable

/** Builds the model of a crate from a schema. */
private[rust] final class CrateBuilder(options: RustGeneratorOptions)(using sv: SchemaView) {
  import CrateBuilder.*

  def crate(name: String): Crate = {
    val types = sv.types.values.toSeq.sortBy(t => sv.elementOrder(t._type))
      .map(t => t -> TypeAlias(RustRenamer.typeName(t), aliasTarget(t)))
    val slotAliases = slots.map { s =>
      val target = rustType(s.derivedRange.value)
      s -> TypeAlias(
        RustRenamer.slotName(s),
        if s.slot.multivalued then s"Vec<$target>" else target,
      )
    }
    val classes = sv.sortedClasses.map(c => c -> rustClass(c))
    val traits = sv.sortedClasses.map(polyTrait)
    // `subproperties` is filled while the classes and traits are built
    val subpropertyEnums = subproperties.toSeq.flatMap((s, e) => e.map(s -> _))
    // Every identifier in the alias targets and field types, to find out which imports the crate
    // needs
    val used = ((types ++ slotAliases).map(_._2.target) ++ classes.flatMap(_._2 match {
      case s: RustClass.Struct => s.properties.map(_.range.fieldType)
      case _ => Nil
    })).flatMap(_.split("\\W+")).toSet
    val chronoTypes = Seq("NaiveDate", "NaiveDateTime").filter(used)
    val merge = classes.exists(_._1.cls.annotations.contains(mergeAnnotation))
    Crate(
      name,
      crateVersion(sv.root.version),
      types,
      slotAliases,
      sv.enums.values.toSeq.sortBy(e => sv.elementOrder(e._enum)).map(e => e -> rustEnum(e)),
      subpropertyEnums,
      classes,
      traits,
      baseImports(options.stubgen, merge) ++
        Option.when(chronoTypes.nonEmpty)(chrono.copy(objects = chronoTypes)),
      rootStruct,
      used(RustSupport.localizedTextName),
    )
  }

  /** The struct of the `tree_root` class of the main schema. With `handwritten_lib`, `lib.rs` gets
    * a function that loads a YAML file into it.
    */
  private def rootStruct: Option[RootStruct] =
    sv.sortedClasses
      .find(c => c.definingSchema == sv.root && !c.cls.mixin && c.cls.treeRoot)
      .map(c => RootStruct(RustRenamer.className(c), RustRenamer.loaderName(c)))

  /** The top-level slots, then the attributes of the classes that aren't top-level slots, as they
    * are defined, without the changes of `slot_usage`. An attribute of more than one class is
    * defined by the first of them.
    */
  private lazy val slots: Seq[SlotView] = {
    def sorted(slots: Iterable[SlotView]) = slots.toSeq.sortBy(s => sv.elementOrder(s.slot))
    val attributes = sv.sortedClasses.flatMap(c => c.cls.attributes.keys.map(_ -> c))
      .distinctBy(_._1)
      .filterNot((name, _) => sv.slotDefinitions.contains(name))
      .map((name, c) => c.definedSlot(name).get)
    sorted(sv.slotDefinitions.values.map(_.unchangedDerived)) ++ sorted(attributes)
  }

  // Types

  /** The Rust type of a value of [[t]]: the Rust type of its `base`, or the alias named after [[t]]
    * if the `base` has no Rust type, or the Rust type of its `typeof` parent, or `String`.
    */
  private def typeTarget(t: TypeView): String = t._type.base match {
    case Some(base) => baseTypes.getOrElse(base, RustRenamer.typeName(t))
    case None => t._type.typeof.flatMap(p => sv.types.get(p.value)).fold("String")(typeTarget)
  }

  /** The target of the alias `pub type <t> = ...`: the Rust type of its `base`, or the Rust type of
    * its `typeof` parent, or `String`. For example `pub type integer = isize;`.
    */
  private def aliasTarget(t: TypeView): String = t._type.base.flatMap(baseTypes.get).getOrElse {
    t._type.typeof.flatMap(p => sv.types.get(p.value)).fold("String")(typeTarget)
  }

  /** The Rust type of a value of [[range]], which names a type, an enum or a class. */
  private def rustType(range: String): String =
    sv.types.get(range).map(typeTarget)
      .orElse(sv.enums.get(range).map(RustRenamer.enumName))
      .orElse(sv.classes.get(range).map(RustRenamer.className))
      .getOrElse("String")

  // Enums

  /** An enum without permissible values becomes `pub type <enum> = String;`, as its values are
    * defined elsewhere, for example with `reachable_from`.
    */
  private def rustEnum(e: EnumView): RustEnum | TypeAlias =
    if !RustGenerator.definesEnum(e) then TypeAlias(RustRenamer.enumName(e), "String")
    else
      RustEnum(
        RustRenamer.enumName(e),
        unique(e._enum.permissibleValues.values.toSeq.map { pv =>
          RustRenamer.permissibleValueName(e, pv) -> pv.text
        }),
      )

  /** Appends `_` to a variant name until it differs from the names before it. For example, `m`
    * after `M` becomes `M_`.
    */
  private def unique(variants: Seq[(String, String)]): Seq[(String, String)] = {
    val names = mutable.HashSet.empty[String]
    variants.map { (name, text) =>
      var unique = name
      while !names.add(unique) do unique += "_"
      unique -> text
    }
  }

  /** The enums of the slots with `subproperty_of`, by slot name. `None` for a slot whose enum would
    * have no variants.
    */
  private val subproperties = mutable.LinkedHashMap.empty[String, Option[RustEnum]]

  /** The enum of a slot with `subproperty_of: X`: a variant for `X` and for each descendant of `X`
    * by `is_a` and mixins. In the data, a variant is the CURIE of its slot if the range of [[slot]]
    * is `uri`, `curie`, `uriorcurie` or a type derived from them, and the slot name otherwise.
    */
  private def subpropertyEnum(slot: SlotView): Option[RustEnum] =
    slot.slot.subpropertyOf.filter(_ => options.expandSubpropertyOf).flatMap { root =>
      subproperties.getOrElseUpdate(
        slot.name, {
          def withDescendants(s: SlotView): Seq[SlotView] = s +: slots
            .filter(c => (c.slot.isA ++ c.slot.mixins).exists(_.value == s.name))
            .flatMap(withDescendants)
          val uriRange = sv.types.get(slot.derivedRange.value)
            .exists(_.ancestorsWithSelf.exists(t => Set("uri", "curie", "uriorcurie")(t.name)))
          val values = slots.filter(_.name == root.value).flatMap(withDescendants).sortBy(_.name)
            .map { s =>
              val text =
                if !uriRange then RustRenamer.underscore(s.name)
                else Option(s.definingPrefixes.compactOrNull(s.uriStr)).getOrElse(s.uriStr)
              RustRenamer.variantName(s.name) -> text
            }.distinctBy(_._1)
          Option.when(values.nonEmpty)(RustEnum(RustRenamer.subpropertyEnumName(slot), values))
        },
      )
    }

  // Classes

  /** The classes with the `linkml:Any` URI and no slots. One of them becomes a struct that wraps a
    * `serde_value::Value`, so that it reads any YAML or JSON value, and the others become aliases
    * of it.
    */
  private lazy val anyClasses: Seq[ClassView] =
    sv.sortedClasses.filter(c => c.isAny && classSlots(c).isEmpty)

  /** The class that gets the struct: `Anything` if the schema has it, as the metamodel has, or else
    * the first one.
    */
  private lazy val anyClass: Option[ClassView] =
    anyClasses.sortBy(_.name != "Anything").headOption

  /** The slots of a class, own and inherited. */
  private def classSlots(cls: ClassView): Seq[SlotView] = cls.sortedAttributeViews.map(_.slotView)

  /** The descendants of a class, by `is_a` and mixins, depth first. */
  private def descendants(cls: ClassView): Seq[ClassView] =
    cls.children.toSeq.flatMap(c => c +: descendants(c)).distinctBy(_.name)

  /** The classes in the `OrSubtype` enum of a class: its descendants, and the class itself if it
    * can have instances. The class comes last, as serde and the Python bindings try the variants in
    * turn, and the class would also accept the data of a subclass, dropping its extra fields.
    */
  private def subtypeVariants(cls: ClassView): Seq[ClassView] =
    if cls.isConcrete then descendants(cls) :+ cls else descendants(cls)

  /** The ranges of a slot: its `range`, then the ranges of its `exactly_one_of` and `any_of`. */
  private def ranges(slot: SlotView): Seq[String] =
    (slot.derivedRange.value +:
      (slot.slot.exactlyOneOf ++ slot.slot.anyOf).flatMap(_.range.map(_.value))).distinct

  /** The identifier of a class, or else its key. */
  private def keyOrIdentifier(cls: ClassView): Option[SlotView] =
    cls.identifier.orElse(classSlots(cls).find(_.slot.key))

  /** A class from [[anyClasses]] becomes the struct of [[anyClass]] or an alias of it. Every other
    * class, including a `linkml:Any` class with slots, becomes a struct with a field for each slot.
    */
  private def rustClass(cls: ClassView): RustClass =
    if anyClasses.contains(cls) then {
      val any = anyClass.get
      if any.name == cls.name then RustClass.Any(RustRenamer.className(cls))
      else RustClass.AnyAlias(RustRenamer.className(cls), RustRenamer.className(any))
    } else {
      val slots = classSlots(cls)
      val merge = cls.cls.annotations.contains(mergeAnnotation)
      val className = RustRenamer.className(cls)
      RustClass.Struct(
        className,
        slots.map(property(_, cls, merge)),
        merge,
        slots.flatMap(slotUnion(_, cls)),
        RustRenamer.unionModuleName(cls),
        keyValue(cls),
        subtypeEnum(cls),
      )
    }

  /** The enum `<slot>_range` in the module of [[cls]]: a variant for each Rust type among the
    * ranges of the slot in [[cls]] and its descendants, and for each type that the trait getter of
    * the slot returns. `None` if that is only one type.
    */
  private def slotUnion(slot: SlotView, cls: ClassView): Option[SlotUnion] = {
    val slotRanges = (ranges(slot) ++ descendants(cls).flatMap { d =>
      d.derivedAttributes.get(slot.name).toSeq.flatMap(ranges)
    }).distinct
    // Ranges with the same Rust type, such as `integer` and a type derived from it, share a
    // variant. The variant of a class with subclasses contains its `OrSubtype` enum, as the fields
    // with that class as their range do.
    val variants = slotRanges.map(r => rustType(r) -> sv.classes.get(r).exists(_.children.nonEmpty))
      .map((t, subtypes) => t -> (if subtypes then t + "OrSubtype" else t))
    val getterVariants =
      if !traitSlots(cls).exists(_.name == slot.name) then Nil
      else traitRange(cls, slot).members.map(m => m.typeName -> m.valueType)
    val all = (variants ++ getterVariants).distinctBy(_._1)
    Option.when(all.sizeIs > 1)(SlotUnion(RustRenamer.slotName(slot), all))
  }

  private def property(slot: SlotView, cls: ClassView, merge: Boolean): RustProperty = {
    val (container, inlining) = slotMode(slot)
    val name = RustRenamer.slotName(slot)
    val (key, otherKey) = keys(slot)
    RustProperty(
      slot.name,
      name,
      Option.when(key != name)(key),
      otherKey,
      inlining,
      container,
      rangeWithSubproperty(cls, slot),
      slot.slot.required,
      slot.slot.multivalued,
      sv.classes.get(slot.derivedRange.value).exists(inlinedAsDict),
      merge,
    )
  }

  /** The key of a slot in serialized data, and another key that is accepted when reading. If the
    * slot has an `alias`, the alias is the key, and the slot name is also accepted.
    */
  private def keys(slot: SlotView): (String, Option[String]) = {
    val name = RustRenamer.serdeName(slot)
    slot.slot.alias.filter(_ != name).fold((name, None))(alias => (alias, Some(name)))
  }

  /** The container and the inlining of a slot. A class without an identifier is always inlined. A
    * multivalued, inlined class with a key or identifier becomes a `HashMap`, unless the slot has
    * `inlined_as_list`.
    */
  private def slotMode(slot: SlotView): (Option[Container], SlotInline) =
    sv.classes.get(slot.derivedRange.value) match {
      case None =>
        (Option.when(slot.slot.multivalued)(Container.List), SlotInline.Primitive)
      case Some(_) if slot.slot.multivalued && slot.slot.inlinedAsList =>
        (Some(Container.List), SlotInline.Inline)
      case Some(range) =>
        val inlined = slot.slot.inlined || range.identifier.isEmpty
        val inlining = if inlined then SlotInline.Inline else SlotInline.Reference
        if !slot.slot.multivalued then (None, inlining)
        else if !inlined then (Some(Container.List), SlotInline.Reference)
        else if keyOrIdentifier(range).isDefined then (Some(Container.Mapping), SlotInline.Inline)
        else (Some(Container.List), SlotInline.Inline)
    }

  private def rangeWithSubproperty(cls: ClassView, slot: SlotView): RustRange =
    subpropertyEnum(slot).fold(rangeOf(cls, slot)) { e =>
      RustRange(e.name, !slot.slot.required, slotMode(slot)._1)
    }

  /** The Rust type of the field of [[slot]] in the struct of [[cls]]. */
  private def rangeOf(cls: ClassView, slot: SlotView): RustRange = {
    val (container, inlining) = slotMode(slot)
    val reference = inlining == SlotInline.Reference
    val slotRanges = ranges(slot)
    def classWithSubtypes(range: String) = sv.classes.get(range).exists(_.children.nonEmpty)
    val single = slotRanges.sizeIs == 1
    // A reference is the identifier of the object, so a `String`, also when the slot has several
    // class ranges
    val union = !single && !reference
    RustRange(
      tpe =
        if union then unionType(cls, slot)
        else if reference then "String"
        else rustType(slotRanges.head),
      optional = !slot.slot.required,
      container = container,
      isClassRange = single && sv.classes.contains(slotRanges.head),
      isReference = reference,
      boxNeeded = boxNeeded(cls, slot),
      hasClassSubtypes = single && classWithSubtypes(slotRanges.head),
      members =
        if !union then Nil
        else
          slotRanges.map { r =>
            RustRange(
              rustType(r),
              isClassRange = sv.classes.contains(r),
              hasClassSubtypes = classWithSubtypes(r),
            )
          },
    )
  }

  /** The path of the union enum of a slot, in the module of its class. */
  private def unionType(cls: ClassView, slot: SlotView): String =
    RustRenamer.unionModuleName(cls) + "::" + SlotUnion.name(RustRenamer.slotName(slot))

  private val reachable = mutable.HashMap.empty[String, Set[String]]

  /** Whether the field of [[slot]] in the struct of [[cls]] needs a `Box`: when its value can
    * contain an object of [[cls]], through the ranges of the slots of the classes in between, or
    * through a subclass in an `OrSubtype` enum.
    */
  private def boxNeeded(cls: ClassView, slot: SlotView): Boolean =
    slotMode(slot)._2 == SlotInline.Inline &&
      (canReach(ranges(slot).head, cls.name) || containsItself(cls, slot))

  /** Whether a value of [[range]] can contain an object of [[target]], through the ranges of the
    * slots of the classes in between.
    */
  private def canReach(range: String, target: String): Boolean =
    reachable.getOrElseUpdate(
      range, {
        val seen = mutable.LinkedHashSet.empty[String]
        val todo = mutable.Stack(range)
        while todo.nonEmpty do {
          val next = todo.pop()
          if seen.add(next) then
            sv.classes.get(next).foreach(c => todo.pushAll(classSlots(c).map(_.derivedRange.value)))
        }
        seen.toSet
      },
    )(target)

  /** Whether a value of [[slot]] can contain an object of [[cls]] through fields that have a
    * single, inlined, unboxed value. Such a chain makes the struct infinitely large. A `Vec` or
    * `HashMap` keeps its items on the heap, so it doesn't count.
    */
  private def containsItself(cls: ClassView, slot: SlotView): Boolean = {
    def held(slot: SlotView): Seq[String] =
      ranges(slot).flatMap(sv.classes.get).flatMap(c => c +: descendants(c))
        .map(_.name)
    val seen = mutable.HashSet.empty[String]
    val todo = mutable.Stack.from(if slotMode(slot)._1.isEmpty then held(slot) else Nil)
    while todo.nonEmpty && !seen(cls.name) do {
      val next = todo.pop()
      if seen.add(next) then
        sv.classes.get(next).foreach { c =>
          classSlots(c).foreach { s =>
            val direct = slotMode(s) == (None, SlotInline.Inline)
            if direct && !canReach(ranges(s).head, c.name) then todo.pushAll(held(s))
          }
        }
    }
    seen(cls.name)
  }

  private val keyValues = mutable.HashMap.empty[String, Option[KeyValue]]

  /** The `InlinedPair` implementation of a class, which lets it be inlined as a dict. `None` unless
    * the class has exactly one key or identifier slot.
    */
  private def keyValue(cls: ClassView): Option[KeyValue] =
    keyValues.getOrElseUpdate(
      cls.name, {
        val (keySlots, others) =
          classSlots(cls).partition(s => s.slot.identifier || s.slot.key)
        Option.when(keySlots.sizeIs == 1) {
          val key = keySlots.head
          val (keyName, keyAlias) = keys(key)
          // The value of the short form `key: value`: the only other slot, or else the only one
          // with the `simple_dict_value` annotation, or else the only required one. Its range
          // must be a type, an enum or a class from `anyClasses`.
          val simple = Seq(
            others,
            others.filter(_.slot.annotations.contains("simple_dict_value")),
            others.filter(_.slot.required),
          ).collectFirst { case Seq(v) => v }.filter { v =>
            !v.slot.multivalued && sv.classes.get(v.derivedRange.value).forall(anyClasses.contains)
          }
          KeyValue(
            RustRenamer.slotName(key),
            keyName,
            rustType(key.derivedRange.value),
            keyAlias.toSeq,
            simple.map { v =>
              SimpleValue(
                RustRenamer.slotName(v),
                keys(v)._1,
                rustType(v.derivedRange.value),
                !v.slot.required,
              )
            },
            !others.exists(s => !s.slot.multivalued && s.slot.required),
          )
        }
      },
    )

  private def subtypeEnum(cls: ClassView): Option[SubtypeEnum] =
    Option.when(cls.children.nonEmpty) {
      val variants = subtypeVariants(cls)
      val designator = cls.typeDesignator
      SubtypeEnum(
        RustRenamer.subtypeEnumName(cls),
        variants.map(RustRenamer.className),
        designator.map(d => keys(d)._1),
        designator.fold(Map.empty)(_ =>
          variants.map(v => RustRenamer.className(v) -> v.typeDesignatorValues).toMap,
        ),
        keyOrIdentifier(cls).filter(_ => inlinedAsDict(cls))
          .map(k => rustType(k.derivedRange.value)),
      )
    }

  /** Whether a class can be inlined as a dict: it has a [[keyValue]], and so has every variant of
    * its `OrSubtype` enum, if it has subclasses.
    */
  private def inlinedAsDict(cls: ClassView): Boolean =
    keyValue(cls).isDefined &&
      (cls.children.isEmpty || subtypeVariants(cls).forall(keyValue(_).isDefined))

  // Traits

  private def supers(cls: ClassView): Seq[ClassView] =
    (cls.cls.isA.toSeq ++ cls.cls.mixins).map(p => sv.classes(p.value))

  /** The slots that the trait of [[cls]] has getters for: the slots that its `is_a` parent and its
    * mixins don't have.
    */
  private def traitSlots(cls: ClassView): Seq[SlotView] = {
    val inherited = supers(cls).flatMap(s => classSlots(s).map(_.name)).toSet
    classSlots(cls).filterNot(s => inherited(s.name))
  }

  private def polyTrait(cls: ClassView): PolyTrait = {
    val slots = traitSlots(cls)
    val names = slots.map(RustRenamer.slotName)
    val promoted = slots.map(traitRange(cls, _))
    val classes = cls +: descendants(cls)
    PolyTrait(
      RustRenamer.className(cls),
      supers(cls).map(RustRenamer.className),
      names.zip(promoted.map(_.getterType)),
      classes.map { d =>
        val own = slots.map(s => rangeWithSubproperty(d, d.derivedAttributes(s.name)))
        PolyImpl(RustRenamer.className(d), names.lazyZip(own).lazyZip(promoted).toSeq)
      },
      classes.filter(_.children.nonEmpty).map { d =>
        PolySubtypeImpl(
          RustRenamer.subtypeEnumName(d),
          subtypeVariants(d).map(RustRenamer.className),
          names.zip(promoted),
        )
      },
    )
  }

  private val traitRanges = mutable.HashMap.empty[(String, String), RustRange]

  /** The return type of the trait getter of [[slot]]: [[promotedRange]], or a union of the field
    * types of [[slot]] in [[cls]] and its descendants, if [[promotedRange]] can't return all of
    * them.
    */
  private def traitRange(cls: ClassView, slot: SlotView): RustRange =
    traitRanges.getOrElseUpdate(
      (cls.name, slot.name), {
        val promoted = promotedRange(cls, slot)
        val fields = (cls +: descendants(cls))
          .flatMap(d => d.derivedAttributes.get(slot.name).map(rangeWithSubproperty(d, _)))
        if fields.forall(holds(promoted, _)) then promoted
        else
          promoted.copy(
            tpe = unionType(cls, slot),
            isClassRange = false,
            isReference = false,
            boxNeeded = false,
            hasClassSubtypes = false,
            members =
              (unionMembers(promoted) ++ fields.flatMap(unionMembers)).distinctBy(_.typeName),
          )
      },
    )

  /** Whether a getter that returns [[getter]] can return a field of type [[field]]. A field with
    * another container (none, `Vec` or `HashMap`) is ignored, as no return type fits both.
    */
  private def holds(getter: RustRange, field: RustRange): Boolean =
    field.container != getter.container || {
      val names = getter.members.map(_.typeName).toSet
      if getter.isUnion then unionMembers(field).forall(m => names(m.typeName))
      else !field.isUnion && field.valueType == getter.valueType
    }

  /** The variants of a union type, or the type itself if it is not a union. */
  private def unionMembers(range: RustRange): Seq[RustRange] =
    if range.isUnion then range.members
    else
      Seq(
        RustRange(
          range.tpe,
          isClassRange = range.isClassRange && !range.isReference,
          hasClassSubtypes = range.hasClassSubtypes && !range.isReference,
        ),
      )

  /** The return type of the trait getter of [[slot]] as LinkML-Py computes it: the Rust types of
    * the ranges of [[slot]] in [[cls]], its descendants, and the classes that have [[cls]] as a
    * mixin and their descendants, as a union if there is more than one. Optional unless all of them
    * require the slot.
    */
  private def promotedRange(cls: ClassView, slot: SlotView): RustRange =
    if subpropertyEnum(slot).isDefined then rangeWithSubproperty(cls, slot)
    else {
      val users = sv.sortedClasses.filter(_.cls.mixins.exists(_.value == cls.name))
      val holders = (cls +: descendants(cls)) ++
        users.flatMap(u => u +: descendants(u))
      val definitions = holders.flatMap(_.derivedAttributes.get(slot.name))
      // A class range counts only if the slot has `inlined: true`, or if the class is from
      // `anyClasses`
      val types = definitions.flatMap { d =>
        ranges(d).flatMap { r =>
          sv.classes.get(r) match {
            case Some(c) if anyClasses.contains(c) => Some(r -> rustType(r))
            case Some(_) if d.slot.inlined => Some(r -> rustType(r))
            case Some(_) => None
            case None => Some(r -> rustType(r))
          }
        }
      }.distinctBy(_._2)
      val container = slotMode(slot)._1
      val optional = !definitions.forall(_.slot.required)
      if types.sizeIs > 1 then
        RustRange(
          unionType(cls, slot),
          optional,
          container,
          members = types.map((_, t) => RustRange(t, isClassRange = !primitives(t))),
        )
      else
        types.headOption.fold(rangeOf(cls, slot)) { (range, t) =>
          RustRange(
            t,
            optional,
            container,
            isClassRange = !primitives(t),
            hasClassSubtypes = sv.classes.get(range).exists(_.children.nonEmpty),
          )
        }
    }
}

private object CrateBuilder {

  /** The annotation of a class that makes its struct derive `Merge`. */
  private val mergeAnnotation = "rust.linkml.io/generate/merge"

  /** Rust types of LinkML-Py's types, by the `base` of a type. */
  private val baseTypes: Map[String, String] = Map(
    "int" -> "isize",
    "float" -> "f64",
    "str" -> "String",
    "String" -> "String",
    "bool" -> "bool",
    "Bool" -> "bool",
    "XSDDate" -> "NaiveDate",
    "date" -> "NaiveDate",
    "XSDDateTime" -> "NaiveDateTime",
    "datetime" -> "NaiveDateTime",
    "Decimal" -> "f64",
    "LocalizedText" -> RustSupport.localizedTextName,
  )

  private val chrono = RustImport("chrono", version = Some("0.4.41"), features = Seq("serde"))

  /** The `use` declarations of every generated crate, and its dependencies in `Cargo.toml`. */
  private def baseImports(stubgen: Boolean, merge: Boolean): Seq[RustImport] =
    Seq(
      RustImport("std::collections", Seq("HashMap")),
      RustImport(
        "pyo3",
        Seq("prelude::*", "FromPyObject"),
        Some("0.29.3"),
        Seq("chrono"),
        Some("pyo3"),
      ),
      RustImport(
        "serde",
        Seq("Serialize", "Deserialize", "de::IntoDeserializer"),
        Some("1.0"),
        Seq("derive"),
        Some("serde"),
      ),
      RustImport("serde-value", Seq("Value"), Some("0.7.0")),
      RustImport("yaml_serde", version = Some("0.10"), feature = Some("serde"), alias = Some("_")),
      RustImport("serde_path_to_error", version = Some("0.1.17"), feature = Some("serde")),
    ) ++ Option.when(stubgen)(
      RustImport(
        "pyo3-stub-gen",
        Seq(
          "define_stub_info_gatherer",
          "derive::gen_stub_pyclass",
          "derive::gen_stub_pymethods",
        ),
        Some("0.23.1"),
        feature = Some("stubgen"),
        featureDependencies = Seq("pyo3"),
      ),
    ) ++ Option.when(merge)(RustImport("merge", Seq("Merge"), Some("0.2.0")))

  /** The crate version: the schema version if it is a semantic version such as `1.2.3`, as cargo
    * requires, or else `0.0.0`.
    */
  private def crateVersion(version: Option[String]): String =
    version.filter(_.matches("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?(\+[0-9A-Za-z.-]+)?""")).getOrElse(
      "0.0.0",
    )

  /** The Rust types that the trait getters don't treat as classes. */
  private val primitives: Set[String] = Set("String", "bool", "f64", "isize")
}
