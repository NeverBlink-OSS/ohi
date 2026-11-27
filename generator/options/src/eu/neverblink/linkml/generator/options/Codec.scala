package eu.neverblink.linkml.generator.options

import eu.neverblink.linkml.yaml.{LinkmlYamlCodec, LinkmlYamlDecoder}

/** Decoders of the options, which are only read. The enums that several options have are derived
  * first, so the decoders of the options share them.
  */
object Codec {
  given LinkmlYamlDecoder[PruningMode] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[JsonOutputFormat] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[RdfFormat] = LinkmlYamlCodec.derivedDecoder

  given LinkmlYamlDecoder[JsonSchemaGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[ShaclGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[RdfsGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[LinkMlGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[FrictionlessGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[GraphQlGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[TypeScriptGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[PydanticGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[ErDiagramGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[DocsGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[OssieGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[StatsGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[OwlGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[ScalaGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[JavaGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[RustGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[TranslationGeneratorOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[OssieImporterOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[OwlImporterOptions] = LinkmlYamlCodec.derivedDecoder
  given LinkmlYamlDecoder[SerializeOptions] = LinkmlYamlCodec.derivedDecoder
}
