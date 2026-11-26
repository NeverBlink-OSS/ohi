package eu.neverblink.linkml.generator.importwarnings

import eu.neverblink.linkml.runtime.LinkmlAny
import eu.neverblink.linkml.yaml.{LinkmlYamlCodec, LinkmlYamlEncoder, NodeJson}

/** Codec for serializing and deserializing an [[ImportReport]].
  *
  * The `warning_type` field tells which kind of warning it is. The codec picks the warning class
  * with the `@typeDesignator` annotation on [[ImportWarning]].
  */
object Codec {

  /** Lazy, to keep the decoder out of code that only uses [[encoder]]. */
  implicit lazy val codec: LinkmlYamlCodec[ImportReport] = LinkmlYamlCodec.derived

  /** For code that only writes reports. */
  val encoder: LinkmlYamlEncoder[ImportReport] = LinkmlYamlCodec.derivedEncoder

  /** The report as pretty-printed JSON. */
  def toJson(report: ImportReport): String = NodeJson.write(encoder.encode(report))

  /** The report as a `linkml:Any` value, for the `report` of an `ImportResult`.
    *
    * The value is JSON, which reads back as YAML unchanged. As block YAML, an empty `warnings`
    * would read back as `null`.
    */
  def toAny(report: ImportReport): LinkmlAny = LinkmlAny(toJson(report))
}
