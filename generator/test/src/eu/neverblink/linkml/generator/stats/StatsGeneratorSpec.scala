package eu.neverblink.linkml.generator.stats

import eu.neverblink.linkml.generator.options.{
  JsonOutputFormat,
  PruningMode as Pruning,
  StatsGeneratorOptions,
}
import eu.neverblink.linkml.generator.stats.model.*
import eu.neverblink.linkml.schemaview.{MapImporter, SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StatsGeneratorSpec extends AnyWordSpec, Matchers {
  private val other =
    """id: https://example.org/other
      |name: other
      |imports:
      |  - linkml:types
      |classes:
      |  Imported:
      |    description: Imported.
      |  NotUsed:
      |    description: Not used.
      |enums:
      |  OtherEnum:
      |    permissible_values:
      |      A:
      |""".stripMargin

  private val root =
    """id: https://example.org/root
      |name: root
      |title:
      |  en: Root
      |  pl: Korzeń
      |license: https://creativecommons.org/publicdomain/zero/1.0/
      |version: 1.0.0
      |prefixes:
      |  linkml: https://w3id.org/linkml/
      |  ex: https://example.org/
      |  schema: http://schema.org/
      |default_prefix: ex
      |imports:
      |  - linkml:types
      |  - other
      |subsets:
      |  Core:
      |    description: Core elements.
      |  Spare:
      |classes:
      |  Book:
      |    description: A book.
      |    exact_mappings: [schema:Book]
      |    in_subset: [Core]
      |    slots: [name, status]
      |    attributes:
      |      pages:
      |        range: integer
      |        deprecated: Use page_count.
      |  Chapter:
      |    tree_root: true
      |    is_a: Imported
      |    notes:
      |      - A note.
      |slots:
      |  name:
      |    title: Name
      |    related_mappings: [schema:name]
      |  status:
      |    range: Status
      |  unused_slot:
      |    description: Nobody uses this.
      |enums:
      |  Status:
      |    permissible_values:
      |      DRAFT:
      |        meaning: ex:Draft
      |        description:
      |          en: Draft
      |          de: Entwurf
      |      DONE:
      |        mappings: [ex:Done]
      |functions:
      |  write:
      |    description: Writes a book.
      |    parameters: Book
      |    returns: Chapter
      |  tidy:
      |""".stripMargin

  private given SchemaView =
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(root, MapImporter("other" -> other)))

  private def stats(onlyRoot: Boolean = true): SchemaStats =
    StatsGenerator().generate(StatsGeneratorOptions(onlyClassesFromRootSchema = onlyRoot))

  "StatsGenerator" should {
    "count only what the root schema defines, by default" in {
      val e = stats().elements
      e.classes.count shouldBe 2 // Book and Chapter, but not its parent Imported
      e.slots.count shouldBe 3
      e.attributes.count shouldBe 1
      e.enums.count shouldBe 1
      e.permissibleValues.count shouldBe 2
      e.types.count shouldBe 0 // `string` and `integer` come from linkml:types
      e.subsets.count shouldBe 2
      e.functions.count shouldBe 2
    }

    "count the functions of every catalogue model" in {
      ModelCatalogue.all.foreach { entry =>
        withClue(s"${entry.name}: ") {
          val counted = StatsGenerator(using entry.model).generate().elements.functions
          counted.count shouldBe entry.model.root.functions.size
          counted.withDescription shouldBe
            entry.model.root.functions.values.count(_.description.isDefined)
        }
      }
    }

    "count every element of every schema when not only the root schema" in {
      val e = stats(onlyRoot = false).elements
      e.classes.count shouldBe 4
      e.slots.count shouldBe 3
      e.enums.count shouldBe 2
      e.permissibleValues.count shouldBe 3
      e.types.count should be > 2
      e.functions.count shouldBe 2
    }

    "ignore the deprecated pruning mode and tree root" in {
      val default = stats()
      Seq(Pruning.TreeRoot, Pruning.Schema, Pruning.Skip).foreach { mode =>
        StatsGenerator().generate(
          StatsGeneratorOptions(pruningMode = mode, treeRoot = Some("Book")),
        ) shouldBe default
      }
    }

    "count documentation, deprecation and mappings" in {
      val e = stats().elements
      e.classes.withDescription shouldBe 1
      e.classes.withMappings shouldBe 1
      e.classes.mappings.exact shouldBe 1
      e.slots.withTitle shouldBe 1
      e.slots.mappings.related shouldBe 1
      e.attributes.deprecated shouldBe 1
      e.permissibleValues.withMeaning shouldBe Some(1)
      e.permissibleValues.withMappings shouldBe 1
      e.permissibleValues.mappings.generic shouldBe 1
      e.classes.withMeaning shouldBe None
      e.functions.withDescription shouldBe 1
    }

    "count the strings in each language of the elements in scope" in {
      def counts(s: SchemaStats) = s.languages.view.mapValues(_.count).toSeq
      counts(stats()) shouldBe Seq("" -> 7, "de" -> 1, "en" -> 2, "pl" -> 1)
      val all = counts(stats(onlyRoot = false))
      all.tail shouldBe Seq("de" -> 1, "en" -> 2, "pl" -> 1)
      all.head._2 should be > 9 // also `Imported`, `NotUsed` and every type
    }

    "count each imported schema once, and describe the root schema" in {
      val s = stats()
      s.imports shouldBe ImportStats(direct = 2, transitive = 2)
      s.metadata shouldBe MetadataStats(
        hasDescription = false,
        hasLicense = true,
        hasTitle = true,
        hasVersion = true,
      )
      s.schemaName shouldBe "root"
      s.onlyClassesFromRootSchema shouldBe true
      s.pruningMode shouldBe None
      stats(onlyRoot = false).onlyClassesFromRootSchema shouldBe false
    }

    "count empty or blank text as missing" in {
      val blank =
        """id: https://example.org/blank
          |name: blank
          |title: ""
          |description: "  "
          |license: ""
          |version: " "
          |classes:
          |  Empty:
          |    title: ""
          |    description: ""
          |  Spaces:
          |    title: "   "
          |    description: "\t"
          |  NoLanguage:
          |    description:
          |      en: ""
          |      pl: " "
          |  SomeLanguage:
          |    description:
          |      en: ""
          |      pl: Opis
          |  Real:
          |    title: Real
          |    description: A real one.
          |""".stripMargin
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(blank))
      val s = StatsGenerator(using sv).generate()
      s.elements.classes.count shouldBe 5
      s.elements.classes.withTitle shouldBe 1
      s.elements.classes.withDescription shouldBe 2
      s.metadata shouldBe MetadataStats(
        hasDescription = false,
        hasLicense = false,
        hasTitle = false,
        hasVersion = false,
      )
      s.languages.view.mapValues(_.count).toMap shouldBe Map("" -> 2, "pl" -> 1)
    }

    "write the languages as a map from tag to count" in {
      val generator = StatsGenerator()
      val yaml = generator.serialize()
      yaml should include("languages:\n  \"\": 7\n  de: 1\n")
      val json = generator.serialize(StatsGeneratorOptions(), JsonOutputFormat.Json)
      json should include(""""languages": {
                             |    "": 7,""".stripMargin)
    }
  }
}
