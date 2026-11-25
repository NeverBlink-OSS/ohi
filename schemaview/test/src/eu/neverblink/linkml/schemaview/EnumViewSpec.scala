package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import eu.neverblink.linkml.runtime.*

class EnumViewSpec extends AnyWordSpec, Matchers {
  "EnumView" should {
    val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))

    "provide meaning mappings" in {
      val pv_formula_options = sv.enums("relational_role_enum")
      pv_formula_options.toMeaning("SUBJECT") shouldBe Uri(
        "http://www.w3.org/1999/02/22-rdf-syntax-ns#subject",
      )
      pv_formula_options.fromMeaning(
        Uri("http://www.w3.org/1999/02/22-rdf-syntax-ns#subject"),
      ) shouldBe "SUBJECT"
    }
    "provide fallback with default prefix for missing meanings" in {
      val pv_formula_options = sv.enums("pv_formula_options")
      pv_formula_options.toMeaning("CODE") shouldBe Uri(
        "https://w3id.org/linkml/PvFormulaOptions.CODE",
      )
      pv_formula_options.fromMeaning(
        Uri("https://w3id.org/linkml/PvFormulaOptions.CODE"),
      ) shouldBe "CODE"
    }
    "give the meaning of a permissible value as it is read, without a copy" in {
      val subject = sv.enums("relational_role_enum").derivedValues.find(_.pv.text == "SUBJECT").get
      subject.meaning should be theSameInstanceAs subject.pv.meaning.get
    }
    "tell whether the values come from elsewhere than its permissible values" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString("""
        |id: https://example.org/test/
        |name: test
        |prefixes:
        |  test: https://example.org/test/
        |default_prefix: test
        |enums:
        |  Listed:
        |    permissible_values:
        |      a:
        |  Empty: {}
        |  Reachable:
        |    reachable_from:
        |      source_nodes:
        |        - test:Vehicle
        |  Inherited:
        |    inherits:
        |      - Listed
        |  Both:
        |    inherits:
        |      - Listed
        |    permissible_values:
        |      b:
        |""".stripMargin))
      sv.enums.values.filter(_.hasExternalValues).map(_.name).toSet shouldBe
        Set("Reachable", "Inherited")
    }
  }
}
