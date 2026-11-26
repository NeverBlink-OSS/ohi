package eu.neverblink.linkml.nativelib

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.SchemaImporter
import eu.neverblink.linkml.generator.options.*
import eu.neverblink.linkml.generator.options.Codec.given
import eu.neverblink.linkml.yaml.LinkmlYamlDecoder
import org.virtuslab.yaml.{Node, parseYaml}

import scala.util.control.NonFatal

/** Something the caller got wrong: an unknown handle, an option that does not exist, a value out of
  * range.
  */
private final case class BadRequest(reason: String) extends RuntimeException(reason)

/** Reads the options JSON of the C API into the generators' own option types.
  *
  * The options of the generators follow `model/converter/all_generators.yaml`, so they are read
  * with the same codec as every other LinkML model. A null or empty string means "all defaults".
  */
private object Options {

  /** Parse an options JSON, or return [[defaults]] when there is nothing to parse. */
  private def model[T](json: String, defaults: T)(using decoder: LinkmlYamlDecoder[T]): T =
    if (json eq null) || json.isEmpty then defaults
    else
      try
        parseYaml(json) match {
          case Right(node) => decoder.decode(node)
          case Left(error) => throw BadRequest(s"malformed options: ${error.msg}")
        }
      catch {
        case e: BadRequest => throw e
        case ex if NonFatal(ex) => throw BadRequest(s"malformed options: ${ex.getMessage}")
      }

  def jsonSchema(json: String): JsonSchemaGeneratorOptions =
    model(json, JsonSchemaGeneratorOptions())

  def shacl(json: String): ShaclGeneratorOptions = model(json, ShaclGeneratorOptions())

  def rdfs(json: String): RdfsGeneratorOptions = model(json, RdfsGeneratorOptions())

  def linkml(json: String): LinkMlGeneratorOptions = model(json, LinkMlGeneratorOptions())

  def frictionless(json: String): FrictionlessGeneratorOptions =
    model(json, FrictionlessGeneratorOptions())

  def graphQl(json: String): GraphQlGeneratorOptions = model(json, GraphQlGeneratorOptions())

  def typeScript(json: String): TypeScriptGeneratorOptions =
    model(json, TypeScriptGeneratorOptions())

  def pydantic(json: String): PydanticGeneratorOptions = model(json, PydanticGeneratorOptions())

  def erDiagram(json: String): ErDiagramGeneratorOptions = model(json, ErDiagramGeneratorOptions())

  def docs(json: String): DocsGeneratorOptions = model(json, DocsGeneratorOptions())

  def ossie(json: String): OssieGeneratorOptions = model(json, OssieGeneratorOptions())

  def stats(json: String): StatsGeneratorOptions = model(json, StatsGeneratorOptions())

  def owl(json: String): OwlGeneratorOptions = model(json, OwlGeneratorOptions())

  def scala(json: String): ScalaGeneratorOptions = model(json, ScalaGeneratorOptions())

  def java(json: String): JavaGeneratorOptions = model(json, JavaGeneratorOptions())

  def rust(json: String): RustGeneratorOptions = model(json, RustGeneratorOptions())

  def translation(json: String): TranslationGeneratorOptions =
    model(json, TranslationGeneratorOptions())

  def fromOssie(json: String): OssieImporterOptions = model(json, OssieImporterOptions())

  def fromOwl(json: String): OwlImporterOptions = model(json, OwlImporterOptions())

  def loadOssie(json: String): OssieImporterOptions = withoutText(json, fromOssie)

  def loadOwl(json: String): OwlImporterOptions = withoutText(json, fromOwl)

  def serialize(json: String): SerializeOptions = model(json, SerializeOptions())

  /** The options of an importer, for loading what it imports. Those that only shape the schema's
    * text are refused, since loading writes none.
    */
  private def withoutText[T](json: String, read: String => T): T = {
    if json ne null then
      parseYaml(json) match {
        case Right(Node.MappingNode(entries, _)) =>
          entries.keys
            .collectFirst {
              case Node.ScalarNode(key, _) if SchemaImporter.textOptions.contains(key) => key
            }
            .foreach { key =>
              throw BadRequest(s"loading takes no `$key` option: pass it to linkml_serialize")
            }
        case _ => ()
      }
    read(json)
  }

  // The options below are not part of the model, and are read with jsoniter. Unknown fields are
  // rejected rather than skipped, and the names are in snake case like the generator options. The
  // macro takes the config only as a literal.
  private given loadOptions: JsonValueCodec[LoadOptions] = JsonCodecMaker.make(
    CodecMakerConfig
      .withSkipUnexpectedFields(false)
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case),
  )

  private val readable = ReaderConfig.withAppendHexDumpToParseException(false)

  /** Parse an options JSON, or return [[defaults]] when there is nothing to parse. */
  private def apply[T](json: String, defaults: T)(using JsonValueCodec[T]): T =
    if (json eq null) || json.isEmpty then defaults
    else
      try readFromString(json, readable)
      catch {
        case ex if NonFatal(ex) => throw BadRequest(s"malformed options: ${ex.getMessage}")
      }

  def load(json: String): LoadOptions = apply(json, LoadOptions())
}

/** @param inferMessages
  *   Whether to fill in each issue's human-readable `message` and `details`.
  * @param importMap
  *   Where to load imports from: each key is an import and each value is the location to load it
  *   from (see `ImportMap` in `schemaview`).
  * @param readFiles
  *   Whether loading from strings reads the schemas that are not among the given texts from the
  *   file system.
  * @param baseDir
  *   The directory to join the relative imports of a root schema given as text to.
  */
private final case class LoadOptions(
    inferMessages: Boolean = true,
    importMap: Map[String, String] = Map.empty,
    readFiles: Boolean = false,
    baseDir: String = "",
)
