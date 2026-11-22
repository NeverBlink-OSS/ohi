package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.cli.TerminalOutput
import eu.neverblink.ohi.catalog.OhiPages
import eu.neverblink.ohi.model.generated.Format

import java.io.PrintStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.util.Try

private[cli] object CliFormatting {
  val formats: Seq[(Format, String)] = Seq(
    Format.OwlTurtle -> "OWL (Turtle)",
    Format.OwlRdfxml -> "OWL (RDF/XML)",
    Format.RdfsTurtle -> "RDFS (Turtle)",
    Format.LinkmlYaml -> "LinkML (YAML)",
    Format.OssieYaml -> "Apache Ossie (YAML)",
    Format.ShaclTurtle -> "SHACL (Turtle)",
  )

  def formatsHelp(colored: Boolean = false): String =
    styled("Supported formats", "1", colored) + "\n" + formats.map { (format, label) =>
      "  " + styled(f"${stringify(format)}%-14s", "36", colored) + label
    }.mkString("\n")

  /** Describe a publication's element counts, or `None` when neither is known. Equal counts, as for
    * an ontology published in LinkML, are shown once.
    */
  def elements(native: Option[Int], linkml: Option[Int]): Option[String] =
    (native, linkml) match {
      case (Some(native), Some(linkml)) if native == linkml => Some(native.toString)
      case (None, None) => None
      case (native, linkml) =>
        Some(
          (native.map(count => s"$count native") ++ linkml.map(count => s"$count in LinkML"))
            .mkString(", "),
        )
    }

  /** Link to a release for people: its page on ohi.dev when it is published there, otherwise its
    * base URL, as for releases on other hubs.
    */
  def releaseLink(baseUrl: String): String =
    if !baseUrl.startsWith("https://api.ohi.dev/files/packages/") then baseUrl
    else
      Try(HubArtifactPath.publication(baseUrl, ".")).toOption.fold(baseUrl) { identity =>
        // Path segments use %2B for a plus sign, while URLDecoder reads a plain + as a space.
        def decode(segment: String) = URLDecoder.decode(segment.replace("+", "%2B"), UTF_8)
        OhiPages.releasePage(
          decode(identity.namespace),
          decode(identity.name),
          decode(identity.version),
        )
      }

  def enabled(stream: PrintStream): Boolean =
    TerminalOutput.colored(stream)

  /** Apply an ANSI SGR code with a trailing reset, or return the text unchanged when disabled. */
  def styled(text: String, code: String, colored: Boolean): String =
    if colored && text.nonEmpty then s"\u001b[${code}m$text\u001b[0m" else text

  def action(verb: String, path: String, colored: Boolean): String =
    styled(verb, "32", colored) + " " + styled(path, "36", colored)

  /** Displays paths relative to cwd when their filesystem roots permit it. */
  def displayPath(path: Path): String = {
    val current = Path.of("").toAbsolutePath.normalize()
    val absolute = path.toAbsolutePath.normalize()
    // Windows paths on different drives cannot be relativized.
    val display = if absolute.getRoot == current.getRoot then current.relativize(absolute).toString
    else absolute.toString
    if display.isEmpty then "." else display
  }

  /** Normalize untrusted display fields without changing stored or structured values. */
  def clean(value: String): String =
    TerminalOutput.withoutAnsi(value)
      .replaceAll("(?U)[\\p{Cc}\\p{Cf}&&[^\\s]]", "")
      .replaceAll("(?U)\\s+", " ")
      .trim

  /** Quote one shell argument, leaving safe, readable tokens unquoted. */
  def quote(value: String, always: Boolean = false): String =
    if !always && value.nonEmpty && value.forall(character =>
        character.isLetterOrDigit || "_@%+=:,./-".contains(character),
      )
    then value
    else "'" + value.replace("'", "'\\''") + "'"
}
