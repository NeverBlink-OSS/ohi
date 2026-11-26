package eu.neverblink.linkml.validation

import eu.neverblink.linkml.yaml.{LinkmlYamlCodec, LinkmlYamlEncoder}

/** Codec for serializing and deserializing a [[SchemaValidationReport]].
  *
  * The `issue_type` field tells which kind of issue it is. The codec picks the issue class with the
  * `@typeDesignator` annotation on [[SchemaIssue]].
  */
object Codec {

  /** Lazy, to keep the decoder out of code that only uses [[encoder]]. */
  implicit lazy val codec: LinkmlYamlCodec[SchemaValidationReport] = LinkmlYamlCodec.derived

  /** For code that only writes reports. */
  val encoder: LinkmlYamlEncoder[SchemaValidationReport] = LinkmlYamlCodec.derivedEncoder
}
