package eu.neverblink.linkml.generator.importwarnings

import eu.neverblink.linkml.runtime.LinkmlAny
import eu.neverblink.linkml.yaml.{LinkmlYamlCodec, NodeJson}

/** Codec for serializing and deserializing an [[ImportReport]].
  *
  * The `warning_type` field tells which kind of warning it is. The codec picks the warning class
  * with the `@typeDesignator` annotation on [[ImportWarning]].
  */
object Codec {
  implicit val codec: LinkmlYamlCodec[ImportReport] = LinkmlYamlCodec.derived

  /** The report as pretty-printed JSON. */
  def toJson(report: ImportReport): String = NodeJson.write(codec.encode(report))

  /** The report as a `linkml:Any` value, for the `report` of an `ImportResult`.
    *
    * The value is JSON, which reads back as YAML unchanged. As block YAML, an empty `warnings`
    * would read back as `null`.
    */
  def toAny(report: ImportReport): LinkmlAny = LinkmlAny(toJson(report))
}
