package eu.neverblink.linkml.generator.rust

import eu.neverblink.linkml.generator.options.RustGeneratorOptions
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec, Resources}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

/** Compiles the generated Rust with cargo, with all its features, and runs Rust tests on it:
  *   - a crate for each catalogue model, which loads the valid JSON data of the model, dumps it,
  *     and loads the dump again
  *   - the crate of the metamodel, which loads the schemas of the catalogue
  *   - crates of small schemas for specific cases, mostly taken from the tests of LinkML-Py's Rust
  *     generator and of the open pull requests to it
  *
  * All crates are in one cargo workspace, so they are built together. Checking them needs a Python
  * interpreter for PyO3, but not its library, as the tests are built without PyO3.
  */
class RustCompileSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  import RustCompileSpec.*

  private val cargo: Option[String] =
    (Option(System.getenv("CARGO")).toSeq ++ Seq(
      "cargo",
      s"${System.getProperty("user.home")}/.cargo/bin/cargo",
    ))
      .find(c => Try(os.proc(c, "--version").call(check = false).exitCode == 0).getOrElse(false))

  private val inCi: Boolean = Option(System.getenv("CI")).exists(_.nonEmpty)

  private def requireCargo(): Unit =
    if cargo.isEmpty then {
      if inCi then fail("cargo is not installed")
      else cancel("Install Rust with cargo (https://rustup.rs) to enable this test")
    }

  private val document =
    "Not supported: a document of a tree root with `tree_root_as` that isn't one object"

  /** Valid instances that the generated code can't load. Invalid instances aren't tested, as the
    * structs only check the JSON types and the required slots.
    */
  override val skipInstances: Map[(String, String), String] = Map(
    "treeRootAsCompactDict" -> "empty" -> document,
    "treeRootAsCompactDict" -> "provided" -> document,
    "treeRootAsList" -> "empty" -> document,
    "treeRootAsList" -> "provided" -> document,
    "treeRootAsOptional" -> "empty" -> document,
    "treeRootAsSimpleDict" -> "empty" -> document,
    "treeRootAsSimpleDict" -> "provided" -> document,
  )

  /** A test crate: its files, and the names of its Rust test functions. It is written to the
    * directory named after the crate.
    */
  private final case class Crate(
      name: String,
      files: Try[Seq[(String, String)]],
      tests: Seq[String],
  )

  private def crate(
      name: String,
      sv: => SchemaView,
      tests: Seq[(String, String)],
      options: RustGeneratorOptions = RustGeneratorOptions(),
  ): Crate = {
    val files = Try(
      RustGenerator(using sv).generateFiles(options.copy(crateName = Some(name), serde = true)) ++
        tests,
    )
    val testNames = tests.flatMap((_, code) => testPattern.findAllMatchIn(code).map(_.group(1)))
    Crate(name, files, testNames)
  }

  private def catalogueCrateName(model: String): String = "m_" + identifier(model)

  private lazy val catalogueCrates: Seq[Crate] = ModelCatalogue.all.map { entry =>
    val name = catalogueCrateName(entry.name)
    val root = entry.model.treeRoot.map(RustRenamer.className)
    val instances = entry.validInstances.distinct.flatMap(i => i.json.map(i.name -> _))
    val tests = root.filter(_ => instances.nonEmpty).toSeq.flatMap { root =>
      val functions = instances.map { (instance, _) =>
        s"""#[test]
           |fn ${name}__${identifier(instance)}() {
           |    round_trip(include_str!("data/${identifier(instance)}.json"));
           |}
           |""".stripMargin
      }
      ("tests/data.rs" -> (dataTestHeader(name, root) + functions.mkString("\n"))) +:
        instances.map((instance, json) => s"tests/data/${identifier(instance)}.json" -> json)
    }
    crate(name, entry.model, tests)
  }

  /** The crate of the metamodel, with a test that loads the schema of each catalogue model. */
  private lazy val metamodelCrate: Crate = {
    val functions = ModelCatalogue.all.map { entry =>
      s"""#[test]
         |fn schema_${identifier(entry.name)}() {
         |    round_trip(include_str!("schemas/${identifier(entry.name)}.yaml"));
         |}
         |""".stripMargin
    }
    val schemas = ModelCatalogue.all.map { entry =>
      s"tests/schemas/${identifier(entry.name)}.yaml" -> Resources.read(entry.path + "model.yaml")
    }
    crate(
      "linkml_meta",
      SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta")),
      ("tests/schemas.rs" -> (dataTestHeader("linkml_meta", "SchemaDefinition") +
        functions.mkString("\n"))) +: schemas,
    )
  }

  private lazy val featureCrates: Seq[Crate] = Features.all.map { feature =>
    crate(
      feature.name,
      load(feature.schema),
      Seq(s"tests/${feature.name}.rs" -> feature.test),
      feature.options,
    )
  }

  private lazy val crates: Seq[Crate] = catalogueCrates ++ (metamodelCrate +: featureCrates)

  /** The errors of `cargo check` by crate, the result of each Rust test by name, and the output of
    * both commands.
    */
  private final case class Results(
      errors: Map[String, Seq[String]],
      tests: Map[String, Option[String]],
      output: String,
  )

  private lazy val results: Results = {
    val dir = os.temp.dir(prefix = "linkml-rust-")
    val written = crates.filter(_.files.isSuccess)
    written.foreach { c =>
      c.files.get.foreach((path, text) =>
        os.write.over(dir / c.name / os.RelPath(path), text, createFolders = true),
      )
    }
    os.write.over(
      dir / "Cargo.toml",
      written.map(c => s"\"${c.name}\"").mkString(
        "[workspace]\nmembers = [",
        ", ",
        "]\nresolver = \"2\"\n",
      ),
    )
    val env = Map("CARGO_TERM_COLOR" -> "never")
    val check = os.proc(
      cargo.get,
      "check",
      "--workspace",
      "--all-targets",
      "--all-features",
      "--keep-going",
      "--message-format=short",
    ).call(cwd = dir, env = env, check = false, stderr = os.Pipe, mergeErrIntoOut = true)
    val checkOutput = check.out.text()
    val errors = checkOutput.linesIterator.collect { case errorPattern(crateDir, message) =>
      crateDir -> message
    }.toSeq.groupMap(_._1)(_._2)
    val broken = errors.keySet ++ written.map(_.name).filter(n =>
      checkOutput.contains(s"could not compile `$n`"),
    )
    // Without PyO3, as linking a test program with PyO3 needs the Python library
    val test = os.proc(
      cargo.get,
      "test",
      "--workspace",
      "--no-fail-fast",
      "--no-default-features",
      "--features",
      "serde",
      broken.toSeq.flatMap(b => Seq("--exclude", b)),
    ).call(cwd = dir, env = env, check = false, stderr = os.Pipe, mergeErrIntoOut = true)
    val testOutput = test.out.text()
    val failures =
      failureSection.findAllMatchIn(testOutput).map(m => m.group(1) -> m.group(2)).toMap
    val tests = testLine.findAllMatchIn(testOutput).map { m =>
      m.group(1) -> Option.when(m.group(2) != "ok")(failures.getOrElse(m.group(1), m.group(2)))
    }.toMap
    Results(
      errors ++ broken.filterNot(errors.contains).map(_ -> Seq("could not compile")),
      tests,
      checkOutput + testOutput,
    )
  }

  private def checkCompiles(c: Crate): Unit = {
    requireCargo()
    c.files.get
    withClue(s"cargo output:\n${results.output.takeRight(5000)}\n") {
      results.errors.getOrElse(c.name, Nil) shouldBe empty
    }
  }

  private def checkTest(c: Crate, test: String): Unit = {
    checkCompiles(c)
    results.tests.get(test) match {
      case None => fail(s"The test did not run. cargo output:\n${results.output.takeRight(5000)}")
      case Some(Some(failure)) => fail(failure)
      case Some(None) =>
    }
  }

  "The Rust generator" should {
    for (entry, c) <- ModelCatalogue.all.zip(catalogueCrates) do
      s"generate compiling Rust for model '${entry.name}'" when {
        "the model itself" should {
          "compile" in {
            assume(!skipModels.contains(entry.name), skipModels.getOrElse(entry.name, ""))
            checkCompiles(c)
          }
        }
        for instance <- entry.validInstances.distinct.filter(_.json.isDefined) do
          s"valid instance '${instance.name}'" should {
            "load, dump and load again" in {
              processSkip(entry, instance)
              checkTest(c, s"${c.name}__${identifier(instance.name)}")
            }
          }
      }

    "generate a compiling crate of the metamodel" which {
      "compiles" in checkCompiles(metamodelCrate)
      for test <- metamodelCrate.tests do s"passes $test" in checkTest(metamodelCrate, test)
    }

    for c <- featureCrates do
      s"generate a compiling crate for '${c.name}'" which {
        "compiles" in checkCompiles(c)
        for test <- c.tests do s"passes $test" in checkTest(c, test)
      }
  }
}

object RustCompileSpec {
  private val testPattern = """#\[test\]\s*fn (\w+)""".r

  /** A `path:line:column: error...` line of `cargo check --message-format=short`. */
  private val errorPattern = """^([A-Za-z0-9_]+)/[^:]+:\d+:\d+: error(.*)$""".r

  private val testLine = """(?m)^test (\S+) \.\.\. (ok|FAILED|ignored)""".r

  private val failureSection = """(?s)---- (\S+) stdout ----\n(.*?)(?=\n---- |\n\nfailures:\n)""".r

  /** [[name]] with the characters that can't be in a Rust identifier replaced by `_`. */
  def identifier(name: String): String =
    name.map(c => if c.isLetterOrDigit && c < 128 then c.toLower else '_')

  def load(body: String): SchemaView =
    SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(
        s"""id: https://example.org/rust/
           |name: test
           |prefixes:
           |  linkml: https://w3id.org/linkml/
           |  ex: https://example.org/rust/
           |default_prefix: ex
           |imports:
           |  - linkml:types
           |$body""".stripMargin,
      ),
    )

  /** The start of a test file with a function that loads data into a [[root]] struct, dumps it, and
    * loads the dump again.
    */
  private def dataTestHeader(crate: String, root: String): String =
    s"""#![cfg(feature = "serde")]
       |#![allow(non_snake_case)]
       |
       |use $crate::$root;
       |
       |fn round_trip(text: &str) {
       |    let data: $root = yaml_serde::from_str(text).expect("load");
       |    let dumped = yaml_serde::to_string(&data).expect("dump");
       |    let again: $root = yaml_serde::from_str(&dumped)
       |        .unwrap_or_else(|e| panic!("load the dump: {e}\\n{dumped}"));
       |    assert_eq!(data, again, "the dump:\\n{dumped}");
       |}
       |
       |""".stripMargin
}
