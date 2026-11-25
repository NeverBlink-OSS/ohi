package eu.neverblink.linkml.generator.rust

import eu.neverblink.linkml.generator.options.RustGeneratorOptions

/** Small schemas for specific cases of the Rust generator, each with a Rust test file for its
  * crate. Most come from the tests of LinkML-Py's Rust generator and of the open pull requests to
  * it.
  */
private object Features {

  /** A test crate for [[RustCompileSpec]]: the crate `name`, generated from `schema` with
    * `options`, with the Rust tests in `test`.
    */
  final case class Feature(
      name: String,
      schema: String,
      test: String,
      options: RustGeneratorOptions = RustGeneratorOptions(),
  )

  /** Inlined classes as dicts and lists, simple dicts and lists of primitives, from
    * `test_rustgen_special_cases_roundtrip`.
    */
  private val inlining = Feature(
    "inlining",
    """classes:
      |  InlineEntry:
      |    attributes:
      |      identifier:
      |        identifier: true
      |      text:
      |  PrefixEntry:
      |    attributes:
      |      prefix:
      |        identifier: true
      |      expansion:
      |        range: uri
      |  PrefixEntryWithMeta:
      |    attributes:
      |      prefix:
      |        identifier: true
      |      expansion:
      |        range: uri
      |      source:
      |  SpecialCaseRoot:
      |    tree_root: true
      |    attributes:
      |      inline_map:
      |        range: InlineEntry
      |        inlined: true
      |        multivalued: true
      |      inline_list:
      |        range: InlineEntry
      |        inlined: true
      |        inlined_as_list: true
      |        multivalued: true
      |      primitive_values:
      |        multivalued: true
      |      primitive_values_optional:
      |        multivalued: true
      |      event_date:
      |        range: date
      |      prefix_bindings:
      |        range: PrefixEntry
      |        inlined: true
      |        multivalued: true
      |      prefix_bindings_compact:
      |        range: PrefixEntryWithMeta
      |        inlined: true
      |        multivalued: true
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use inlining::SpecialCaseRoot;
      |
      |const INPUT: &str = r#"
      |inline_map:
      |  orange:
      |    identifier: orange
      |    text: Orange map value
      |  cyan:
      |    text: Cyan map value
      |inline_list:
      |  alpha:
      |    text: Alpha list value
      |primitive_values:
      |  - red
      |  - blue
      |primitive_values_optional: optional-scalar
      |event_date: 2024-02-29
      |prefix_bindings:
      |  ex: https://example.org/
      |  foaf:
      |    expansion: http://xmlns.com/foaf/0.1/
      |prefix_bindings_compact:
      |  ex:
      |    expansion: https://example.org/
      |    source: Schema
      |"#;
      |
      |fn yaml(text: &str) -> yaml_serde::Value {
      |    yaml_serde::from_str(text).unwrap()
      |}
      |
      |#[test]
      |fn inlining_round_trip() {
      |    let root: SpecialCaseRoot = yaml_serde::from_str(INPUT).expect("load");
      |    let inline_map = root.inline_map.as_ref().unwrap();
      |    // The dict key is set as the identifier, whether the entry has the identifier or not
      |    assert_eq!(inline_map["cyan"].identifier, "cyan");
      |    assert_eq!(root.inline_list.as_ref().unwrap()[0].identifier, "alpha");
      |    assert_eq!(
      |        root.event_date,
      |        Some(chrono::NaiveDate::from_ymd_opt(2024, 2, 29).unwrap())
      |    );
      |    assert_eq!(
      |        root.prefix_bindings.as_ref().unwrap()["ex"].expansion.as_deref(),
      |        Some("https://example.org/")
      |    );
      |
      |    let dumped = yaml_serde::to_string(&root).expect("dump");
      |    let again: SpecialCaseRoot = yaml_serde::from_str(&dumped).expect("load the dump");
      |    assert_eq!(root, again);
      |    let out = yaml(&dumped);
      |    // An entry is written without its identifier, and in the short form `key: value` if
      |    // the class has only one other slot
      |    assert_eq!(out["inline_map"]["orange"], yaml("Orange map value"));
      |    assert_eq!(out["prefix_bindings"]["foaf"], yaml("http://xmlns.com/foaf/0.1/"));
      |    // A slot with `inlined_as_list` is written as a list
      |    assert_eq!(out["inline_list"], yaml("[{identifier: alpha, text: Alpha list value}]"));
      |    assert_eq!(
      |        out["prefix_bindings_compact"]["ex"],
      |        yaml("{expansion: 'https://example.org/', source: Schema}")
      |    );
      |    // A multivalued slot with one value is written as that value
      |    assert_eq!(out["primitive_values_optional"], yaml("optional-scalar"));
      |}
      |""".stripMargin,
  )

  /** The key of an inlined dict entry with an alias, from
    * `test_rustgen_aliased_key_preserves_explicit_value_in_inlined_dict`.
    */
  private val aliasedKey = Feature(
    "aliased_key",
    """classes:
      |  AnnotationLike:
      |    attributes:
      |      ann_tag:
      |        identifier: true
      |        alias: tag
      |      ann_value:
      |        required: true
      |  Root:
      |    tree_root: true
      |    attributes:
      |      annotations:
      |        range: AnnotationLike
      |        inlined: true
      |        multivalued: true
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use aliased_key::Root;
      |
      |fn entry(yaml: &str) -> aliased_key::AnnotationLike {
      |    let root: Root = yaml_serde::from_str(yaml).expect("load");
      |    root.annotations.unwrap().remove("foo").expect("the entry")
      |}
      |
      |#[test]
      |fn aliased_key_in_the_entry() {
      |    let entry = entry("annotations:\n  foo:\n    tag: foo\n    ann_value: bar\n");
      |    assert_eq!(entry.ann_tag, "foo");
      |    assert_eq!(entry.ann_value, "bar");
      |}
      |
      |#[test]
      |fn slot_name_of_the_key_in_the_entry() {
      |    assert_eq!(entry("annotations:\n  foo:\n    ann_tag: foo\n    ann_value: bar\n").ann_tag, "foo");
      |}
      |
      |#[test]
      |fn key_from_the_dict() {
      |    assert_eq!(entry("annotations:\n  foo:\n    ann_value: bar\n").ann_tag, "foo");
      |}
      |
      |#[test]
      |fn aliased_key_written_as_the_alias() {
      |    let dumped = yaml_serde::to_string(&entry("annotations:\n  foo:\n    ann_value: bar\n")).unwrap();
      |    assert!(dumped.contains("tag: foo"), "{dumped}");
      |    assert!(!dumped.contains("ann_tag"), "{dumped}");
      |}
      |""".stripMargin,
  )

  /** A simple dict of a class that also has a multivalued slot of its own class, as `Annotation` in
    * the metamodel has, from `test_rustgen_simple_dict_with_recursive_multivalued_sibling`.
    */
  private val simpleDict = Feature(
    "simple_dict",
    """classes:
      |  Anything:
      |    class_uri: linkml:Any
      |  AnnotationLike:
      |    attributes:
      |      tag:
      |        identifier: true
      |      value:
      |        required: true
      |        range: Anything
      |        annotations:
      |          simple_dict_value: true
      |      annotations:
      |        range: AnnotationLike
      |        multivalued: true
      |        inlined: true
      |  Root:
      |    tree_root: true
      |    attributes:
      |      annotations:
      |        range: AnnotationLike
      |        inlined: true
      |        multivalued: true
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use simple_dict::{Anything, Root};
      |
      |#[test]
      |fn simple_dict_of_recursive_class() {
      |    let yaml = "annotations:\n  foo: bar\n  baz:\n    value: 1\n    annotations:\n      qux: quux\n";
      |    let root: Root = yaml_serde::from_str(yaml).expect("load");
      |    let annotations = root.annotations.as_ref().unwrap();
      |    assert_eq!(annotations["foo"].value, Anything(serde_value::Value::String("bar".into())));
      |    let nested = annotations["baz"].annotations.as_ref().unwrap();
      |    assert_eq!(nested["qux"].tag, "qux");
      |    let dumped = yaml_serde::to_string(&root).unwrap();
      |    assert_eq!(yaml_serde::from_str::<Root>(&dumped).unwrap(), root);
      |}
      |""".stripMargin,
  )

  /** Slots named after Rust and Python keywords, from `test_rustgen_keyword_slots_roundtrip` of
    * linkml/linkml#4092 and `test_rustgen_protected_name_slot_roundtrip` of linkml/linkml#3919.
    */
  private val keywords = Feature(
    "keywords",
    """classes:
      |  Root:
      |    tree_root: true
      |    attributes:
      |      windows:
      |        range: Window
      |        multivalued: true
      |        inlined: true
      |      events:
      |        range: Event
      |        multivalued: true
      |        inlined_as_list: true
      |  Window:
      |    attributes:
      |      in:
      |        key: true
      |      fn:
      |      match:
      |      type:
      |      from:
      |      class:
      |  Event:
      |    attributes:
      |      type:
      |        designates_type: true
      |      name:
      |  Meeting:
      |    is_a: Event
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use keywords::{EventOrSubtype, Root};
      |
      |#[test]
      |fn keyword_slots_keep_their_names_in_the_data() {
      |    let yaml = "windows:\n  w1:\n    fn: f\n    match: m\n    type: t\n    from: x\n    class: c\nevents:\n  - type: Meeting\n    name: standup\n";
      |    let root: Root = yaml_serde::from_str(yaml).expect("load");
      |    let window = &root.windows.as_ref().unwrap()["w1"];
      |    assert_eq!(window.in_, "w1");
      |    assert_eq!(window.fn_.as_deref(), Some("f"));
      |    assert_eq!(window.type_.as_deref(), Some("t"));
      |    assert_eq!(window.from_.as_deref(), Some("x"));
      |    assert_eq!(window.class_.as_deref(), Some("c"));
      |    match &root.events.as_ref().unwrap()[0] {
      |        EventOrSubtype::Meeting(m) => assert_eq!(m.type_.as_deref(), Some("Meeting")),
      |        other => panic!("not a meeting: {other:?}"),
      |    }
      |    let dumped = yaml_serde::to_string(&root).unwrap();
      |    assert!(dumped.contains("fn: f") && dumped.contains("type: Meeting"), "{dumped}");
      |    assert!(!dumped.contains("fn_") && !dumped.contains("type_"), "{dumped}");
      |    assert_eq!(yaml_serde::from_str::<Root>(&dumped).unwrap(), root);
      |}
      |""".stripMargin,
  )

  /** Type designators, from `test_rustgen_type_designator_preserves_designator_on_variant` and
    * `test_rustgen_type_designator_tagged_roundtrip`, and the concrete base class in its
    * `OrSubtype` enum, from `test_rustgen_orsubtype_base_roundtrip` of linkml/linkml#3662.
    */
  private val typeDesignator = Feature(
    "type_designator",
    """classes:
      |  Event:
      |    attributes:
      |      category:
      |        designates_type: true
      |      name:
      |  EmploymentEvent:
      |    is_a: Event
      |  MedicalEvent:
      |    is_a: Event
      |    attributes:
      |      diagnosis:
      |  Record:
      |    tree_root: true
      |    attributes:
      |      events:
      |        range: Event
      |        multivalued: true
      |        inlined_as_list: true
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use type_designator::{EventOrSubtype, MedicalEvent, Record};
      |
      |#[test]
      |fn designator_picks_the_variant_and_stays_on_it() {
      |    let yaml = "category: MedicalEvent\nname: x\ndiagnosis: flu\n";
      |    let event: EventOrSubtype = yaml_serde::from_str(yaml).expect("load");
      |    match &event {
      |        EventOrSubtype::MedicalEvent(inner) => {
      |            assert_eq!(inner.category.as_deref(), Some("MedicalEvent"));
      |            assert_eq!(inner.diagnosis.as_deref(), Some("flu"));
      |        }
      |        other => panic!("not a medical event: {other:?}"),
      |    }
      |    let dumped = yaml_serde::to_string(&event).unwrap();
      |    assert_eq!(dumped.matches("category:").count(), 1, "{dumped}");
      |    assert_eq!(yaml_serde::from_str::<EventOrSubtype>(&dumped).unwrap(), event);
      |}
      |
      |#[test]
      |fn variant_struct_writes_its_designator() {
      |    let event = MedicalEvent {
      |        category: Some("MedicalEvent".to_string()),
      |        name: None,
      |        diagnosis: Some("flu".to_string()),
      |    };
      |    assert!(yaml_serde::to_string(&event).unwrap().contains("category: MedicalEvent"));
      |}
      |
      |#[test]
      |fn concrete_base_class_is_a_variant() {
      |    let event: EventOrSubtype = yaml_serde::from_str("category: Event\nname: x\n").expect("load");
      |    assert!(matches!(event, EventOrSubtype::Event(_)), "{event:?}");
      |}
      |
      |#[test]
      |fn unknown_and_missing_designators_are_rejected() {
      |    assert!(yaml_serde::from_str::<EventOrSubtype>("category: Party\n").is_err());
      |    assert!(yaml_serde::from_str::<EventOrSubtype>("name: x\n").is_err());
      |}
      |
      |#[test]
      |fn list_of_variants() {
      |    let yaml = "events:\n  - category: EmploymentEvent\n  - category: MedicalEvent\n    diagnosis: flu\n";
      |    let record: Record = yaml_serde::from_str(yaml).expect("load");
      |    let events = record.events.as_ref().unwrap();
      |    assert!(matches!(events[0], EventOrSubtype::EmploymentEvent(_)));
      |    assert!(matches!(events[1], EventOrSubtype::MedicalEvent(_)));
      |}
      |""".stripMargin,
  )

  /** Merging structs, from `test_rustgen_merge_strategy_per_key_for_optional_maps`. */
  private val merge = Feature(
    "merging",
    """classes:
      |  Setting:
      |    attributes:
      |      key:
      |        key: true
      |      value:
      |  Config:
      |    tree_root: true
      |    annotations:
      |      rust.linkml.io/generate/merge: true
      |    attributes:
      |      name:
      |        required: true
      |      label:
      |      settings:
      |        range: Setting
      |        multivalued: true
      |        inlined: true
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use merging::Config;
      |
      |#[test]
      |fn merge_keeps_what_the_other_lacks() {
      |    let mut config: Config =
      |        yaml_serde::from_str("name: a\nlabel: A\nsettings:\n  x: one\n  y: two\n").unwrap();
      |    let other: Config = yaml_serde::from_str("name: b\nsettings:\n  y: three\n  z: four\n").unwrap();
      |    config.merge_with(&other);
      |    assert_eq!(config.name, "a");
      |    assert_eq!(config.label.as_deref(), Some("A"));
      |    let settings = config.settings.as_ref().unwrap();
      |    assert_eq!(settings.len(), 3);
      |    assert_eq!(settings["y"].value.as_deref(), Some("three"));
      |}
      |""".stripMargin,
  )

  /** Slots with a union, `linkml:Any` and `langstring` as their range, with plain values in the
    * data.
    */
  private val values = Feature(
    "values",
    """classes:
      |  Any:
      |    class_uri: linkml:Any
      |  Measure:
      |    tree_root: true
      |    attributes:
      |      count:
      |        range: Any
      |        any_of:
      |          - range: integer
      |          - range: string
      |      amount:
      |        any_of:
      |          - range: integer
      |          - range: boolean
      |      label:
      |        range: langstring
      |      extra:
      |        range: Any
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use values::{measure_utl, LocalizedText, Measure};
      |
      |#[test]
      |fn plain_values_of_unions() {
      |    let measure: Measure = yaml_serde::from_str("count: 3\namount: true\nextra: {a: [1, 2]}\n").expect("load");
      |    assert_eq!(measure.amount, Some(measure_utl::amount_range::bool(true)));
      |    let dumped = yaml_serde::to_string(&measure).unwrap();
      |    assert!(dumped.contains("count: 3") && dumped.contains("amount: true"), "{dumped}");
      |    assert_eq!(yaml_serde::from_str::<Measure>(&dumped).unwrap(), measure);
      |}
      |
      |#[test]
      |fn localized_text() {
      |    let plain: Measure = yaml_serde::from_str("label: plain\n").unwrap();
      |    assert_eq!(plain.label, Some(LocalizedText::Plain("plain".to_string())));
      |    let localized: Measure = yaml_serde::from_str("label: {de: Hallo, en: Hello}\n").unwrap();
      |    assert_eq!(localized.label.as_ref().unwrap().text(), "Hello");
      |    let dumped = yaml_serde::to_string(&localized).unwrap();
      |    assert_eq!(yaml_serde::from_str::<Measure>(&dumped).unwrap(), localized);
      |}
      |""".stripMargin,
  )

  /** A slot with `subproperty_of`, from `test_subproperty_of_cargo_check`. */
  private val subproperty = Feature(
    "subproperty",
    """slots:
      |  related_to:
      |    range: uriorcurie
      |  causes:
      |    is_a: related_to
      |  predicate:
      |    range: uriorcurie
      |    subproperty_of: related_to
      |  relation:
      |    subproperty_of: related_to
      |classes:
      |  Association:
      |    tree_root: true
      |    slots:
      |      - predicate
      |      - relation
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use subproperty::{Association, PredicateEnum, RelationEnum};
      |
      |#[test]
      |fn subproperty_values() {
      |    let a: Association = yaml_serde::from_str("predicate: ex:causes\nrelation: related_to\n").unwrap();
      |    assert_eq!(a.predicate, Some(PredicateEnum::Causes));
      |    assert_eq!(a.relation, Some(RelationEnum::RelatedTo));
      |    assert!(yaml_serde::from_str::<Association>("predicate: causes\n").is_err());
      |}
      |""".stripMargin,
  )

  /** The trait getters, called on a struct and on an `OrSubtype` enum. */
  private val traits = Feature(
    "traits",
    """classes:
      |  Named:
      |    mixin: true
      |    attributes:
      |      name:
      |        required: true
      |  Labeled:
      |    mixin: true
      |    attributes:
      |      name:
      |        required: true
      |  Thing:
      |    attributes:
      |      id:
      |        identifier: true
      |      parts:
      |        range: Part
      |        multivalued: true
      |        inlined_as_list: true
      |  Person:
      |    is_a: Thing
      |    mixins: [Named, Labeled]
      |    attributes:
      |      age:
      |        range: integer
      |  Part:
      |    attributes:
      |      size:
      |        range: integer
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use traits::poly::{Named, Thing};
      |use traits::poly_containers::SeqRef;
      |use traits::ThingOrSubtype;
      |
      |#[test]
      |fn getters_of_subtypes() {
      |    let yaml = "id: p1\nname: Ada\nage: 36\nparts:\n  - size: 2\n";
      |    let thing: ThingOrSubtype = yaml_serde::from_str(yaml).expect("load");
      |    assert!(matches!(thing, ThingOrSubtype::Person(_)), "{thing:?}");
      |    assert_eq!(Thing::id(&thing), "p1");
      |    assert_eq!(Thing::parts(&thing).unwrap().len(), 1);
      |    if let ThingOrSubtype::Person(person) = &thing {
      |        assert_eq!(Named::name(person), "Ada");
      |    }
      |}
      |""".stripMargin,
  )

  /** The trait getter of a slot to which a subclass adds a range with `any_of`, as in the cdm
    * benchmark schema.
    */
  private val traitUnions = Feature(
    "trait_unions",
    """enums:
      |  Currency:
      |    permissible_values:
      |      EUR:
      |  Unit:
      |    permissible_values:
      |      kg:
      |classes:
      |  Criteria:
      |    attributes:
      |      code:
      |        range: Currency
      |  WideCriteria:
      |    is_a: Criteria
      |    slot_usage:
      |      code:
      |        any_of:
      |          - range: Currency
      |          - range: Unit
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use trait_unions::poly::Criteria;
      |use trait_unions::{criteria_utl, CriteriaOrSubtype, Currency, Unit};
      |
      |#[test]
      |fn getter_of_a_union_in_a_subclass() {
      |    let wide: CriteriaOrSubtype = yaml_serde::from_str("code: kg\n").expect("load");
      |    assert_eq!(Criteria::code(&wide), Some(criteria_utl::code_range::Unit(Unit::Kg)));
      |    let plain: trait_unions::Criteria = yaml_serde::from_str("code: EUR\n").expect("load");
      |    assert_eq!(Criteria::code(&plain), Some(criteria_utl::code_range::Currency(Currency::Eur)));
      |}
      |""".stripMargin,
  )

  /** The crate as a Python module, with its code in `src/generated`. */
  private val python = Feature(
    "python",
    """classes:
      |  Container:
      |    tree_root: true
      |    attributes:
      |      items:
      |        range: Item
      |        multivalued: true
      |        inlined_as_list: true
      |  Item:
      |    attributes:
      |      name:
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |#[test]
      |fn loader_of_the_tree_root() {
      |    let path = std::env::temp_dir().join("linkml_rust_python_container.yaml");
      |    std::fs::write(&path, "items:\n  - name: a\n").unwrap();
      |    let container = python::load_yaml_container(&path).expect("load");
      |    assert_eq!(container.items.unwrap()[0].name.as_deref(), Some("a"));
      |}
      |""".stripMargin,
    RustGeneratorOptions(pyo3 = true, handwrittenLib = true),
  )

  /** The example of the Rust page of the docs. */
  private val docs = Feature(
    "zoo",
    """classes:
      |  Zoo:
      |    tree_root: true
      |    attributes:
      |      name:
      |        required: true
      |      animals:
      |        range: Animal
      |        multivalued: true
      |        inlined_as_list: true
      |      keepers:
      |        range: Keeper
      |        multivalued: true
      |        inlined: true
      |  Animal:
      |    abstract: true
      |    attributes:
      |      kind:
      |        designates_type: true
      |        required: true
      |      name:
      |        required: true
      |      keeper:
      |        range: Keeper
      |  Lion:
      |    is_a: Animal
      |    attributes:
      |      mane_length:
      |        range: float
      |  Parrot:
      |    is_a: Animal
      |    attributes:
      |      words:
      |        multivalued: true
      |  Keeper:
      |    description: A person who looks after animals.
      |    attributes:
      |      id:
      |        identifier: true
      |      name:
      |      since:
      |        range: date
      |      shift:
      |        range: Shift
      |enums:
      |  Shift:
      |    permissible_values:
      |      day:
      |      night:
      |""".stripMargin,
    """#![cfg(feature = "serde")]
      |
      |use zoo::poly::Animal;
      |use zoo::{AnimalOrSubtype, Zoo};
      |
      |fn names(zoo: &zoo::Zoo) -> Vec<&str> {
      |    zoo.animals.iter().flatten().map(|a| Animal::name(a)).collect()
      |}
      |
      |#[test]
      |fn docs_example() {
      |    let json = r#"{"name": "Z", "animals": [{"kind": "Lion", "name": "Leo"}]}"#;
      |    let zoo: Zoo = yaml_serde::from_str(json).expect("load");
      |    assert!(matches!(zoo.animals.as_ref().unwrap()[0], AnimalOrSubtype::Lion(_)));
      |    assert_eq!(names(&zoo), vec!["Leo"]);
      |}
      |""".stripMargin,
  )

  val all: Seq[Feature] =
    Seq(
      inlining,
      aliasedKey,
      simpleDict,
      keywords,
      typeDesignator,
      merge,
      values,
      subproperty,
      traits,
      traitUnions,
      python,
      docs,
    )
}
