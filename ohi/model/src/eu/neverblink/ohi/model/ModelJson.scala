package eu.neverblink.ohi.model

import eu.neverblink.linkml.yaml.{DecodeError, LinkmlYamlCodec, NodeJson}
import org.virtuslab.yaml.{Node, ParseError, Range, ScannerError, Tag, YamlError, parseYaml}
import scala.util.control.NonFatal

/** JSON text through the codecs of the generated classes. */
private[ohi] object ModelJson {

  /** @throws IllegalArgumentException if `text` cannot be parsed */
  def parse(text: String): Node = {
    // YAML ignores a leading byte order mark, but JSON text must not have one.
    require(!text.startsWith("﻿"), "JSON text must not start with a byte order mark")
    parseYaml(text).fold(
      error => throw new IllegalArgumentException(message(error), error),
      identity,
    )
  }

  /** @throws IllegalArgumentException if `text` does not decode as a `T` */
  def decode[T](codec: LinkmlYamlCodec[T], text: String): T = decode(codec, parse(text))

  /** @throws IllegalArgumentException if `node` does not decode as a `T` */
  def decode[T](codec: LinkmlYamlCodec[T], node: Node): T =
    try codec.decode(node)
    catch {
      case cause: DecodeError =>
        val expected = cause.expected.fold(shortened(cause.getMessage))("expected " + _)
        throw new IllegalArgumentException(expected + cause.position.fold("")(at), cause)
      case cause: IllegalArgumentException => throw cause
      case NonFatal(cause) => throw new IllegalArgumentException(shortened(cause.getMessage), cause)
    }

  // The messages of the parser and codecs quote the source line, which is the whole text when it
  // is JSON on one line, as a service usually returns it.
  private def message(error: YamlError): String = error match {
    case ParseError.ExpectedTokenKind(expected, got) =>
      s"expected $expected but got ${got.kind}${at(got.range)}"
    case ScannerError.Obtained(obtained, got) => s"unexpected $obtained${at(got.range)}"
    case ScannerError.AtRange(range, msg) => msg + at(range)
    case other => shortened(other.msg)
  }

  private def at(range: Range): String =
    s" at line ${range.start.line + 1}, column ${range.start.column + 1}"

  private def shortened(message: String): String = {
    val line = message.linesIterator.nextOption().getOrElse("")
    if line.length <= 200 then line else line.take(200) + "…"
  }

  def encode[T](codec: LinkmlYamlCodec[T], value: T): String = NodeJson.write(codec.encode(value))

  /** Whether `node` is text outside the enum of `codec`, so a value added to it after this build.
    */
  def isNew[T](codec: LinkmlYamlCodec[T], node: Node): Boolean = node match {
    case scalar: Node.ScalarNode if scalar.tag == Tag.str =>
      try {
        codec.decode(scalar)
        false
      } catch { case _: DecodeError => true }
    case _ => false
  }

  /** The value of `key`, if `node` is a mapping with it. */
  def get(node: Node, key: String): Option[Node] = node match {
    case Node.MappingNode(mappings, _) =>
      mappings.collectFirst { case (Node.ScalarNode(`key`, _), value) => value }
    case _ => None
  }

  /** `node` with the value of `key` replaced, if it is a mapping with that key. */
  def updated(node: Node, key: String)(f: Node => Node): Node = node match {
    case Node.MappingNode(mappings, _) =>
      Node.MappingNode(mappings.map {
        case (name @ Node.ScalarNode(`key`, _), value) => name -> f(value)
        case entry => entry
      })
    case _ => node
  }
}
