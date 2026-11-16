package eu.neverblink.linkml.generator.importwarnings

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
}
