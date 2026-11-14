package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.generator.options.{ScalaErrorHandling, ScalaGeneratorOptions}
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks that the code emitted by [[ScalaGenerator]] actually compiles, by running the Scala 3
  * compiler over it. JVM-only, as it needs the compiler and the file system.
  */
class ScalaGeneratorCompileSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {

  /** Compile the given Scala sources with the Scala 3 compiler. The test classpath is reused, so
    * the generated code is checked against the real `runtime` module it depends on.
    *
    * @return
    *   The compiler output if compilation failed, or None if it succeeded.
    */
  def compileScala(sources: Seq[os.Path]): Option[String] = {
    val out = os.temp.dir(prefix = "linkml-scala-out")
    val args = Array(
      "-classpath",
      System.getProperty("java.class.path"),
      "-d",
      out.toString,
    ) ++ sources.map(_.toString)
    val log = java.io.ByteArrayOutputStream()
    val reporter = Console.withErr(log) {
      Console.withOut(log)(dotty.tools.dotc.Driver().process(args))
    }
    Option.when(reporter.hasErrors)(log.toString)
  }

  /** Generate [[sv]] with [[options]] and compile the result. */
  private def compileModel(sv: SchemaView, options: ScalaGeneratorOptions): Unit = {
    val dir = os.temp.dir(prefix = "linkml-scala-src")
    val sources =
      ScalaGenerator(using sv).generate(options).map { (name, content) =>
        val file = dir / name
        os.write(file, content)
        file
      }.toSeq
    sources should not be empty
    val failure = compileScala(sources)
    withClue(s"compiler output:\n${failure.getOrElse("")}\n")(failure shouldBe None)
  }

  "ScalaGenerator" should {
    for
      entry <- ModelCatalogue.all :+ ModelCatalogue.kitchenSink
      skipLeafInterfaces <- Seq(true, false)
    do
      val variant = if skipLeafInterfaces then "" else " with leaf interfaces"
      s"generate compilable code for model '${entry.name}'$variant" in {
        processSkip(entry.name, "")
        compileModel(
          entry.model,
          ScalaGeneratorOptions(`package` = "generated", skipLeafInterfaces = skipLeafInterfaces),
        )
      }

    // Only the models with functions
    for entry <- ModelCatalogue.all.filter(_.model.root.functions.nonEmpty) do
      s"generate compilable code for model '${entry.name}' with errors as Either" in {
        processSkip(entry.name, "")
        compileModel(
          entry.model,
          ScalaGeneratorOptions(`package` = "generated", errorHandling = ScalaErrorHandling.Either),
        )
      }

    // A subclass, a mixin, cardinality, and an inlined class range
    for skipLeafInterfaces <- Seq(true, false) do
      val variant = if skipLeafInterfaces then "" else " with leaf interfaces"
      s"generate compilable code for slots that don't narrow the parent's$variant" in {
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
              |  Part: {}
              |  Base:
              |    slots:
              |      - count
              |      - label
              |      - ref
              |  Named:
              |    mixin: true
              |    slots:
              |      - label
              |  Middle:
              |    is_a: Base
              |    slot_usage:
              |      count:
              |        range: string
              |      label:
              |        range: Kind
              |        multivalued: true
              |      ref:
              |        range: Part
              |  Leaf:
              |    is_a: Middle
              |  Tagged:
              |    is_a: Base
              |    mixins:
              |      - Named
              |    slot_usage:
              |      label:
              |        range: Kind
              |      ref:
              |        range: Unrelated
              |enums:
              |  Kind:
              |    permissible_values:
              |      a: {}
              |slots:
              |  count:
              |    range: integer
              |  label:
              |    range: string
              |  ref:
              |    range: Other
              |""".stripMargin,
          ),
        )
        sv.validationProblems should not be empty
        compileModel(
          sv,
          ScalaGeneratorOptions(`package` = "generated", skipLeafInterfaces = skipLeafInterfaces),
        )
      }
  }
}
