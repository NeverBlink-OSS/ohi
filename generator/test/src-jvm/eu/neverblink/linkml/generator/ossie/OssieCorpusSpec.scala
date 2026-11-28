package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.options.{OwlGeneratorOptions, RdfFormat}
import eu.neverblink.linkml.generator.ossie.expression.Expression
import eu.neverblink.linkml.generator.owl.{OwlCorpusSpec, OwlGenerator, OwlImporter}
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml

/** Converts the ontologies in the `ossie` folder of linkml-benchmark-schemas to LinkML and back,
  * directly and through OWL, and checks what they keep.
  */
class OssieCorpusSpec extends AnyWordSpec, Matchers {
  import OssieCorpusSpec.*

  for name <- ontologies do
    s"The Ossie ontology $name" when {
      lazy val original = normalized(decode(text(name)))
      lazy val schema = load(OssieImporter().serializeFromString(text(name)))
      lazy val viaOwl = {
        val nt = OwlGenerator(using schema).serialize(OwlGeneratorOptions(format = RdfFormat.Nt))
        val imported = OwlImporter().importTriples(OwlCorpusSpec.triples(nt))
        load(OwlCorpusSpec.yamlOf(imported.schema))
      }

      val conversions: Seq[(String, () => OssieOntology)] = Seq(
        "converted to LinkML and back" -> (() => OssieGenerator(using schema).generate()),
        "converted to LinkML, then to OWL, then to LinkML and back" ->
          (() => OssieGenerator(using viaOwl).generate()),
      )

      for (conversion, convert) <- conversions do
        conversion should {
          lazy val result = normalized(convert())
          def keep(part: Concept => Any): Unit = same(original, result, part)

          "keep the concepts and their types" in keep(_.conceptType)
          "keep the concepts each concept extends" in keep(_.extendsConcepts)
          "keep the relationships that identify each concept" in keep(_.identifyBy)
          "keep the relationships of each concept" in keep(_.relationships.map(_.name).sorted)
          "keep the roles and multiplicity of each relationship" in
            keep(relationships(_)(r => (r.roles, r.multiplicity)))
          "keep the constraints" in keep(c => (c.requires, relationships(c)(_.requires)))
          "keep the verbalizations" in keep(relationships(_)(_.verbalizes))
          "keep the descriptions" in keep(c => (c.description, relationships(c)(_.description)))
          "give the same ontology" in {
            val problems = OwlCorpusSpec.differences("ontology", whole(original), whole(result))
            if problems.nonEmpty then fail(problems.take(10).mkString("\n"))
          }
        }
    }

  /** Fails with the concepts whose [[part]] differs. */
  private def same(original: OssieOntology, result: OssieOntology, part: Concept => Any): Unit = {
    def parts(o: OssieOntology) = o.ontology.map(c => c.concept -> part(c)).toMap
    val problems = OwlCorpusSpec.differences("concept", parts(original), parts(result))
    if problems.nonEmpty then fail(problems.take(10).mkString("\n"))
  }
}

object OssieCorpusSpec {
  val ontologies: Seq[String] = Seq("flights", "sap-business-partner")

  /** The `ossie` folder of linkml-benchmark-schemas. */
  private val corpus: Option[os.Path] =
    Option(System.getenv("LINKML_OSSIE_CORPUS")).filter(_.nonEmpty).map(os.Path(_, os.pwd))

  private val inCi: Boolean = Option(System.getenv("CI")).exists(_.nonEmpty)

  /** The YAML of an ontology. Cancels the test if the corpus is missing, and fails it in CI. */
  def text(name: String): String =
    corpus.map(_ / name / "ontology.yaml").filter(os.exists).map(os.read(_)).getOrElse {
      val hint = "Set LINKML_OSSIE_CORPUS to the `ossie` folder of linkml-benchmark-schemas"
      if inCi then Assertions.fail(s"$name is missing. $hint") else Assertions.cancel(hint)
    }

  private def load(yaml: String): SchemaView =
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))

  private def decode(document: String): OssieOntology =
    OssieOntology.codec.decode(
      parseYaml(document).getOrElse(throw AssertionError(s"not readable YAML:\n$document")),
    )

  /** [[o]] with the changes a trip through LinkML may make. Expressions are written as the
    * generator writes them, such as `IN (...)` for `==` joined with `OR`, and in any order, as all
    * of them have to hold. A relationship without roles becomes one to `Boolean`, as it is a
    * `boolean` slot in LinkML.
    */
  private def normalized(o: OssieOntology): OssieOntology = {
    def rewritten(es: Seq[String]) = es.map(e => Expression.parse(e).fold(e)(_.render)).sorted
    def boolean(r: Relationship) =
      if r.roles.nonEmpty then r
      else
        r.copy(
          roles = Seq(Role(BuiltInConcept.boolean)),
          multiplicity = Some(Multiplicity.ManyToOne),
          verbalizes = r.verbalizes.take(1).map(v => s"$v {${BuiltInConcept.boolean}}"),
        )
    o.copy(ontology = o.ontology.map { c =>
      c.copy(
        requires = rewritten(c.requires),
        relationships = c.relationships.map { r =>
          val b = boolean(r)
          b.copy(requires = rewritten(b.requires))
        },
      )
    })
  }

  /** [[part]] of each relationship of [[c]], by name. */
  private def relationships(c: Concept)(part: Relationship => Any): Map[String, Any] =
    c.relationships.map(r => r.name -> part(r)).toMap

  /** The ontology with its concepts and their relationships by name, so that the order they are in
    * doesn't matter. Without `derived_by`, which LinkML can't express.
    */
  private def whole(o: OssieOntology): Map[String, Any] = Map(
    "header" -> o.copy(ontology = Nil),
    "concepts" -> o.ontology.map(c =>
      c.concept -> c.copy(relationships = Nil, derivedBy = Nil),
    ).toMap,
    "relationships" ->
      o.ontology.map(c => c.concept -> relationships(c)(_.copy(derivedBy = Nil))).toMap,
  )
}
