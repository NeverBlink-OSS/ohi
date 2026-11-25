package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import sangria.ast
import sangria.schema.Schema
import sangria.parser.{ParserConfig, QueryParser}

class GraphQlGeneratorSyntaxSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  // dummy query object to make the generated types a valid graphql schema
  val dummyQuery: String =
    """type Query {
      |  test: String
      |}
      |""".stripMargin

  def parseOrThrow(schema: String): Schema[Any, Any] = {
    val doc = QueryParser.parse(schema, ParserConfig()).get
    checkInterfaceFields(doc)
    checkTransitiveInterfaces(doc)
    Schema.buildFromAst(doc)
  }

  /** Checks that each type and interface has all fields of the interfaces it implements, with the
    * same type or a subtype of it, as GraphQL requires. Sangria adds a missing field from the
    * interface, so it doesn't report it.
    */
  private def checkInterfaceFields(doc: ast.Document): Unit = {
    val interfaceFields = doc.definitions.collect { case i: ast.InterfaceTypeDefinition =>
      i.name -> i.fields
    }.toMap
    val implemented = doc.definitions.collect {
      case o: ast.ObjectTypeDefinition => o.name -> o.interfaces.map(_.name)
      case i: ast.InterfaceTypeDefinition => i.name -> i.interfaces.map(_.name)
    }.toMap
    def implements(name: String, interface: String): Boolean =
      name == interface || implemented.getOrElse(name, Nil).exists(implements(_, interface))
    // A subtype adds `!`, or is a type that implements the interface's type
    def isSubtype(tpe: ast.Type, of: ast.Type): Boolean = tpe match {
      case t: ast.NotNullType =>
        of match {
          case o: ast.NotNullType => isSubtype(t.ofType, o.ofType)
          case _ => isSubtype(t.ofType, of)
        }
      case t: ast.ListType =>
        of match {
          case o: ast.ListType => isSubtype(t.ofType, o.ofType)
          case _ => false
        }
      case t: ast.NamedType =>
        of match {
          case o: ast.NamedType => implements(t.name, o.name)
          case _ => false
        }
    }
    def show(tpe: ast.Type): String = tpe match {
      case t: ast.NotNullType => show(t.ofType).concat("!")
      case t: ast.ListType => s"[${show(t.ofType)}]"
      case t: ast.NamedType => t.name
    }
    def check(name: String, interfaces: Seq[ast.NamedType], fields: Seq[ast.FieldDefinition]) =
      interfaces.foreach { i =>
        interfaceFields.getOrElse(i.name, Vector.empty).foreach { inherited =>
          withClue(s"$name.${inherited.name} of ${i.name}: ") {
            val field = fields.find(_.name == inherited.name)
            field.map(_.name) shouldBe Some(inherited.name)
            withClue(s"${show(field.get.fieldType)} of ${show(inherited.fieldType)}: ") {
              isSubtype(field.get.fieldType, inherited.fieldType) shouldBe true
            }
          }
        }
      }
    doc.definitions.foreach {
      case o: ast.ObjectTypeDefinition => check(o.name, o.interfaces, o.fields)
      case i: ast.InterfaceTypeDefinition => check(i.name, i.interfaces, i.fields)
      case _ =>
    }
  }

  /** Checks that each type and interface also implements the interfaces of its interfaces, as
    * GraphQL requires. Sangria doesn't check this, but graphql-js and graphql-core do.
    */
  private def checkTransitiveInterfaces(doc: ast.Document): Unit = {
    val implemented = doc.definitions.collect {
      case o: ast.ObjectTypeDefinition => o.name -> o.interfaces.map(_.name)
      case i: ast.InterfaceTypeDefinition => i.name -> i.interfaces.map(_.name)
    }.toMap
    implemented.foreach { (name, interfaces) =>
      interfaces.foreach { i =>
        withClue(s"$name implements $i: ") {
          interfaces should contain allElementsOf implemented.getOrElse(i, Nil)
        }
      }
    }
  }

  /** A schema with the given classes, slots, types and enums. */
  private def schema(body: String): SchemaView = SchemaIssues.orThrow(
    SchemaView.loadSchemaViewFromString(
      """id: https://example.org/test/
        |name: test
        |prefixes:
        |  linkml: https://w3id.org/linkml/
        |  test: https://example.org/test/
        |default_prefix: test
        |imports:
        |  - linkml:types
        |""".stripMargin + body,
    ),
  )

  private val nestedMixins =
    """classes:
      |  Base:
      |    mixin: true
      |    attributes:
      |      base_name:
      |  Nested:
      |    mixin: true
      |    mixins:
      |      - Base
      |    attributes:
      |      nested_name:
      |  Consumer:
      |    mixins:
      |      - Nested
      |    attributes:
      |      own:
      |""".stripMargin

  private val plainMixin =
    """classes:
      |  Plain:
      |    attributes:
      |      plain_name:
      |  User:
      |    mixins:
      |      - Plain
      |    attributes:
      |      name:
      |""".stripMargin

  private val builtInNamedType =
    """classes:
      |  Person:
      |    attributes:
      |      age:
      |        range: Int
      |types:
      |  Int:
      |    typeof: uri
      |""".stripMargin

  private val ontologyEnum =
    """classes:
      |  Vehicle:
      |    attributes:
      |      kind:
      |        range: VehicleKind
      |enums:
      |  VehicleKind:
      |    reachable_from:
      |      source_nodes:
      |        - test:Vehicle
      |""".stripMargin

  /** Checks that no name starts with `__`, which GraphQL reserves for introspection. */
  private def checkNames(doc: ast.Document): Unit = {
    val names = doc.definitions.flatMap {
      case o: ast.ObjectTypeDefinition if o.name != "Query" =>
        o.name +: o.fields.map(_.name)
      case i: ast.InterfaceTypeDefinition => i.name +: i.fields.map(_.name)
      case e: ast.EnumTypeDefinition => e.name +: e.values.map(_.name)
      case s: ast.ScalarTypeDefinition => Seq(s.name)
      case _ => Nil
    }
    names.filter(_.startsWith("__")) shouldBe empty
  }

  "GraphQlGenerator" should {
    for entry <- ModelCatalogue.all :+ ModelCatalogue.kitchenSink do
      s"generate a valid schema for model '${entry.name}'" in {
        processSkip(entry.name, "")
        val schema = {
          dummyQuery +
            GraphQlGenerator(using entry.model).serialize()
        }
        val result = parseOrThrow(schema)

        result.typeList.map(_.name) should contain(
          GraphQlRenamer.className(entry.model.treeRoot.get),
        )
      }

    "give an implementation the stub field of an interface without fields" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://example.org/stub/
            |name: stub
            |prefixes:
            |  linkml: https://w3id.org/linkml/
            |default_prefix: stub
            |imports:
            |  - linkml:types
            |classes:
            |  Empty:
            |    abstract: true
            |  Filled:
            |    is_a: Empty
            |    slots:
            |      - labels
            |slots:
            |  labels:
            |    range: string
            |""".stripMargin,
        ),
      )
      val result = parseOrThrow(dummyQuery + GraphQlGenerator(using sv).serialize())
      result.typeList.map(_.name) should contain("Filled")
    }

    "give an implementation the parent's field type where a slot_usage doesn't narrow it" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://example.org/narrowing/
            |name: narrowing
            |default_prefix: https://example.org/narrowing/
            |imports:
            |  - linkml:types
            |classes:
            |  Other:
            |    attributes:
            |      id:
            |        identifier: true
            |  Unrelated:
            |    attributes:
            |      id:
            |        identifier: true
            |  Base:
            |    abstract: true
            |    slots:
            |      - label
            |      - ref
            |  Middle:
            |    is_a: Base
            |    abstract: true
            |    slot_usage:
            |      label:
            |        multivalued: true
            |      ref:
            |        range: Unrelated
            |  Leaf:
            |    is_a: Middle
            |slots:
            |  label:
            |    range: string
            |  ref:
            |    range: Other
            |""".stripMargin,
        ),
      )
      val result = parseOrThrow(dummyQuery + GraphQlGenerator(using sv).serialize())
      result.typeList.map(_.name) should contain("Leaf")
    }

    // The defects and known gaps listed in https://github.com/linkml/linkml/pull/4246
    "for the defects of the LinkML-Python generator" should {
      for (name, body) <- Seq(
          "a class without slots" ->
            """classes:
              |  Empty: {}
              |""".stripMargin,
          "a type of the schema" ->
            """classes:
              |  City:
              |    attributes:
              |      code:
              |        range: city_code
              |types:
              |  city_code:
              |    typeof: uri
              |""".stripMargin,
          "a mixin without slots" ->
            """classes:
              |  BareMixin:
              |    mixin: true
              |  User:
              |    mixins:
              |      - BareMixin
              |    attributes:
              |      name:
              |""".stripMargin,
          "a mixin of a mixin" -> nestedMixins,
          "a class used as a mixin without being one" -> plainMixin,
          "a linkml:Any class with another name" ->
            """classes:
              |  AnyObject:
              |    class_uri: linkml:Any
              |  Holder:
              |    attributes:
              |      value:
              |        range: AnyObject
              |""".stripMargin,
          "an enum with values from an ontology" -> ontologyEnum,
          "names that are not GraphQL names" ->
            """classes:
              |  Person:
              |    attributes:
              |      has-part:
              |      a b:
              |      __private:
              |      _1:
              |      status:
              |        range: Status
              |enums:
              |  Status:
              |    permissible_values:
              |      a+b:
              |      __hidden:
              |""".stripMargin,
        )
      do
        s"generate a valid schema for $name" in {
          val generated = dummyQuery + GraphQlGenerator(using schema(body)).serialize()
          parseOrThrow(generated)
          checkNames(QueryParser.parse(generated).get)
        }

      "separate implemented interfaces with &" in {
        val result = GraphQlGenerator(using schema(nestedMixins)).serialize()
        result.linesIterator.filter(_.contains("implements ")).foreach(_ should not include ",")
        result should include("type Consumer implements Nested & Base ")
      }

      "make a mixin of a mixin an interface" in {
        val result = GraphQlGenerator(using schema(nestedMixins)).serialize()
        result should include("interface Nested implements Base ")
      }

      "make a class used as a mixin an interface" in {
        val result = GraphQlGenerator(using schema(plainMixin)).serialize()
        result should include("type User implements PlainInterface ")
      }

      // An enum type has to list its values, so the values from an ontology need a scalar
      "generate a scalar for an enum with values from an ontology" in {
        GraphQlGenerator(using schema(ontologyEnum)).serialize() should include(
          "scalar VehicleKind",
        )
      }

      for (name, body, message) <- Seq(
          (
            "a type with the name of a class",
            """classes:
              |  Person:
              |    attributes:
              |      id:
              |        range: person
              |types:
              |  person:
              |    typeof: uri
              |""".stripMargin,
            "type 'person' and class 'Person' both map to the GraphQL name 'Person'",
          ),
          (
            "a type with the name of an enum",
            """classes:
              |  Person:
              |    attributes:
              |      status:
              |        range: Status
              |      code:
              |        range: status
              |enums:
              |  Status:
              |    permissible_values:
              |      active:
              |types:
              |  status:
              |    typeof: uri
              |""".stripMargin,
            "type 'status' and enum 'Status' both map to the GraphQL name 'Status'",
          ),
          (
            "a class with the name of an enum",
            """classes:
              |  Status:
              |    attributes:
              |      kind:
              |        range: status
              |enums:
              |  status:
              |    permissible_values:
              |      active:
              |""".stripMargin,
            "enum 'status' and class 'Status' both map to the GraphQL name 'Status'",
          ),
          (
            "a class with the name of the interface of another",
            """classes:
              |  Person:
              |    attributes:
              |      name:
              |  Student:
              |    is_a: Person
              |  PersonInterface:
              |    attributes:
              |      id:
              |""".stripMargin,
            "both map to the GraphQL name 'PersonInterface'",
          ),
          (
            "a class with the name of the linkml:Any scalar",
            """classes:
              |  AnyObject:
              |    class_uri: linkml:Any
              |  any:
              |    attributes:
              |      value:
              |        range: AnyObject
              |""".stripMargin,
            "class 'any' and the scalar of linkml:Any both map to the GraphQL name 'Any'",
          ),
          (
            "a type with the name of a built-in scalar",
            builtInNamedType,
            "type 'Int' maps to 'Int', a built-in GraphQL scalar",
          ),
        )
      do
        s"fail on $name" in {
          val e = the[IllegalArgumentException] thrownBy
            GraphQlGenerator(using schema(body)).serialize()
          e.getMessage should include(message)
        }
    }

    "generate the metamodel" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))
      val schema = {
        dummyQuery +
          GraphQlGenerator(using sv).serialize()
      }

      val result = parseOrThrow(schema)

      result.typeList.map(_.name) should contain(GraphQlRenamer.className(sv.treeRoot.get))
    }
  }
}
