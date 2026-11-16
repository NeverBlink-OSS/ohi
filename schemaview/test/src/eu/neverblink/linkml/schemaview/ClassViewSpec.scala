package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ClassViewSpec extends AnyWordSpec, Matchers {
  private def load(classes: String): SchemaView = SchemaIssues.orThrow(
    SchemaView.loadSchemaViewFromString(
      """id: https://example.org/td/
        |name: td
        |prefixes:
        |  ex: https://example.org/
        |  linkml: https://w3id.org/linkml/
        |imports:
        |  - linkml:types
        |default_prefix: ex
        |enums:
        |  Kinds:
        |    permissible_values:
        |      A:
        |classes:
        |""".stripMargin + classes,
    ),
  )

  // A designated base class with one concrete subclass, for each designator range
  private def withRange(range: String, classUri: String = "ex:Sub"): SchemaView = load(
    s"""  Base:
       |    abstract: true
       |    attributes:
       |      kind:
       |        designates_type: true
       |        range: $range
       |  Sub:
       |    is_a: Base
       |    class_uri: $classUri
       |""".stripMargin,
  )

  private def values(range: String, classUri: String = "ex:Sub"): Seq[String] =
    withRange(range, classUri).classes("Sub").typeDesignatorValues

  "ClassView.typeDesignator" should {
    "find the designator slot, also when it is inherited" in {
      given sv: SchemaView = withRange("string")
      sv.classes("Base").typeDesignator.map(_.slot.name) shouldBe Some("kind")
      sv.classes("Sub").typeDesignator.map(_.slot.name) shouldBe Some("kind")
    }
    "be empty for a class without a designator" in {
      given sv: SchemaView = load("  Plain:\n    attributes:\n      x:\n        range: string\n")
      sv.classes("Plain").typeDesignator shouldBe None
    }
  }

  "ClassView.typeDesignatorValues" should {
    "be the class name for a string range" in {
      values("string") shouldBe Seq("Sub")
    }
    "be the class URI for a uri range" in {
      values("uri") shouldBe Seq("https://example.org/Sub")
    }
    "be the class CURIE for a curie range" in {
      values("curie") shouldBe Seq("ex:Sub")
    }
    "be the class URI and CURIE for a uriorcurie range" in {
      values("uriorcurie") shouldBe Seq("https://example.org/Sub", "ex:Sub")
    }
    "have one value for a uriorcurie range when no prefix matches the class URI" in {
      values("uriorcurie", "urn:other:Foo") shouldBe Seq("urn:other:Foo")
    }
    "put a class URI without a colon after the default prefix" in {
      values("uriorcurie", "Foo") shouldBe Seq("https://example.org/Foo", "ex:Foo")
    }
    "be empty for a type range other than a string or a URI" in {
      values("integer") shouldBe empty
    }
    "be empty for an enum range" in {
      values("Kinds") shouldBe empty
    }
    "be empty for a class without a designator" in {
      given sv: SchemaView = load("  Plain:\n    attributes:\n      x:\n        range: string\n")
      sv.classes("Plain").typeDesignatorValues shouldBe empty
    }
  }

  "ClassView.typeDesignatorMembers and isTypeDesignatorUnion" should {
    given sv: SchemaView = load(
      """  Abstract:
        |    abstract: true
        |    attributes:
        |      kind:
        |        designates_type: true
        |        range: string
        |  Mixin:
        |    mixin: true
        |  Middle:
        |    is_a: Abstract
        |  Leaf:
        |    is_a: Middle
        |    mixins:
        |      - Mixin
        |  AbstractLeaf:
        |    is_a: Middle
        |    abstract: true
        |  Empty:
        |    abstract: true
        |    attributes:
        |      kind:
        |        designates_type: true
        |        range: string
        |  Plain:
        |    attributes:
        |      x:
        |        range: string
        |""".stripMargin,
    )

    def members(name: String): Seq[String] = sv.classes(name).typeDesignatorMembers.map(_.name)

    "list the concrete descendants of an abstract class, skipping abstract ones" in {
      members("Abstract") shouldBe Seq("Leaf", "Middle")
      sv.classes("Abstract").isTypeDesignatorUnion shouldBe true
    }
    "list a concrete class together with its concrete descendants" in {
      members("Middle") shouldBe Seq("Leaf", "Middle")
      sv.classes("Middle").isTypeDesignatorUnion shouldBe true
    }
    "list only itself for a concrete class without descendants" in {
      members("Leaf") shouldBe Seq("Leaf")
      sv.classes("Leaf").isTypeDesignatorUnion shouldBe false
    }
    "be empty for an abstract class without concrete descendants" in {
      members("Empty") shouldBe empty
      sv.classes("Empty").isTypeDesignatorUnion shouldBe false
    }
    "be empty for a class without a designator" in {
      members("Plain") shouldBe empty
      members("Mixin") shouldBe empty
      sv.classes("Plain").isTypeDesignatorUnion shouldBe false
    }
  }

  "ClassView.allowsExtraSlots" should {
    "follow the class' own extra_slots, where an explicit allowed wins" in {
      val sv = load(
        """  Open:
          |    extra_slots:
          |      allowed: true
          |    attributes:
          |      a: {}
          |  Typed:
          |    extra_slots:
          |      range_expression:
          |        range: string
          |    attributes:
          |      a: {}
          |  ClosedTyped:
          |    extra_slots:
          |      allowed: false
          |      range_expression:
          |        range: string
          |    attributes:
          |      a: {}
          |  OpenChild:
          |    is_a: Open
          |  Plain:
          |    attributes:
          |      a: {}
          |""".stripMargin,
      )
      sv.classes("Open").allowsExtraSlots shouldBe true
      sv.classes("Typed").allowsExtraSlots shouldBe true
      sv.classes("ClosedTyped").allowsExtraSlots shouldBe false
      sv.classes("OpenChild").allowsExtraSlots shouldBe false
      sv.classes("Plain").allowsExtraSlots shouldBe false
    }
  }

  "ClassView.definedSlot" should {
    val sv = load(
      """  Base:
        |    slots:
        |      - title
        |    attributes:
        |      note:
        |        range: string
        |  Child:
        |    is_a: Base
        |    slot_usage:
        |      title:
        |        required: true
        |      note:
        |        required: true
        |slots:
        |  title:
        |    range: string
        |""".stripMargin,
    )
    "be the top-level slot, without the slot_usage of any class" in {
      sv.classes("Child").derivedAttributes("title").slot.required shouldBe true
      sv.classes("Child").definedSlot("title").get.slot.required shouldBe false
    }
    "be the attribute of the class that declares it, without the slot_usage of any class" in {
      sv.classes("Child").derivedAttributes("note").slot.required shouldBe true
      val note = sv.classes("Child").definedSlot("note").get
      note.slot.required shouldBe false
      note.slot.range.map(_.value) shouldBe Some("string")
    }
    "be empty for a slot that the class doesn't have" in {
      sv.classes("Base").definedSlot("other") shouldBe None
    }
  }

  "ClassView.narrowedAttributes" should {
    val sv = load(
      """  Other:
        |    attributes:
        |      id:
        |        identifier: true
        |  SpecialOther:
        |    is_a: Other
        |  Unrelated:
        |    attributes:
        |      id:
        |        identifier: true
        |  Base:
        |    slots:
        |      - count
        |      - label
        |      - ref
        |      - tags
        |  Middle:
        |    is_a: Base
        |    slot_usage:
        |      count:
        |        range: string
        |        required: true
        |      label:
        |        range: Kinds
        |        multivalued: true
        |      ref:
        |        range: Unrelated
        |      tags:
        |        minimum_cardinality: 1
        |  Leaf:
        |    is_a: Middle
        |    slot_usage:
        |      ref:
        |        range: SpecialOther
        |slots:
        |  count:
        |    range: integer
        |  label:
        |    range: string
        |  ref:
        |    range: Other
        |  tags:
        |    range: string
        |    multivalued: true
        |    minimum_cardinality: 2
        |""".stripMargin,
    )
    def narrowed(cls: String, slot: String): SlotView = sv.classes(cls).narrowedAttributes(slot)

    "be the derived attributes of a class whose slots narrow its parents' slots" in {
      val base = sv.classes("Base")
      base.narrowedAttributes shouldBe theSameInstanceAs(base.derivedAttributes)
      base.sortedNarrowedAttributeViews shouldBe theSameInstanceAs(base.sortedAttributeViews)
    }
    "give a slot the parent's range where its range is not derived from the parent's" in {
      narrowed("Middle", "count").derivedRange.value shouldBe "integer"
      narrowed("Middle", "label").derivedRange.value shouldBe "string"
      narrowed("Middle", "ref").derivedRange.value shouldBe "Other"
      sv.classes("Middle").derivedAttributes("count").derivedRange.value shouldBe "string"
    }
    "keep a narrower number of values where only the range is replaced" in {
      narrowed("Middle", "count").slot.required shouldBe true
    }
    "give a slot the parent's number of values where it allows more values" in {
      narrowed("Middle", "label").slot.multivalued shouldBe false
      narrowed("Middle", "tags").slot.minimumCardinality shouldBe Some(2)
    }
    "compare a slot with the parent's narrowed slot" in {
      narrowed("Leaf", "count").derivedRange.value shouldBe "integer"
      // Narrows `Other`, not `Unrelated`
      narrowed("Leaf", "ref").derivedRange.value shouldBe "SpecialOther"
      narrowed("Leaf", "label").slot.multivalued shouldBe false
    }
    "give attribute views of the narrowed slots" in {
      val views = sv.classes("Middle").sortedNarrowedAttributeViews
      views.map(_.slotView.name) shouldBe Seq("count", "label", "ref", "tags")
      views.collectFirst { case v: ClassReferenceAttributeView => v.classView.name } shouldBe
        Some("Other")
      sv.classes("Middle").narrowedAttributeViews("label") shouldBe a[TypeAttributeView]
    }
  }
}
