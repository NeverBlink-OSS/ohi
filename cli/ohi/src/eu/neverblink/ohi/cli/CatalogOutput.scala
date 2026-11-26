package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.model.generated.{
  Agent,
  ArtifactOrigin,
  CliFileError,
  DeliveryResultImpl,
  Publication,
  SearchHit,
  SearchResult,
  Tag,
  TagList,
}

import java.net.URI
import scala.collection.mutable.ArrayBuffer

import CliFormatting.{clean, styled}

private[cli] object CatalogOutput {
  private val searchCodec: LinkmlYamlCodec[SearchResult] = LinkmlYamlCodec.derived
  private val publicationCodec: LinkmlYamlCodec[Publication] = LinkmlYamlCodec.derived
  private val deliveryCodec: LinkmlYamlCodec[DeliveryResultImpl] = LinkmlYamlCodec.derived
  private val fileErrorCodec: LinkmlYamlCodec[CliFileError] = LinkmlYamlCodec.derived
  private val tagListCodec: LinkmlYamlCodec[TagList] = LinkmlYamlCodec.derived

  def search(
      result: SearchResult,
      json: Boolean,
      width: Int,
      colored: Boolean,
      allReleases: Boolean,
  ): String =
    if json then JsonUtil.yamlToJson(searchCodec.encode(result))
    else SearchOutput.render(result, width, colored, allReleases)

  /** The ontology's releases and the base URL of its latest version, if there is one. */
  final case class Versions(latest: Option[String], releases: Seq[SearchHit])

  /** Render JSON or normalized human release details with descriptions wrapped at 80 columns. Human
    * output also lists up to 10 `versions`, the latest first.
    */
  def publication(
      result: Publication,
      json: Boolean,
      colored: Boolean = false,
      versions: Option[Versions] = None,
  ): String =
    if json then JsonUtil.yamlToJson(publicationCodec.encode(result))
    else {
      val output = ArrayBuffer.empty[String]
      def field(label: String, value: String, indent: String = "  ", code: String = "0"): Unit =
        output += styled(s"$indent$label: ", "2", colored) + styled(clean(value), code, colored)

      def section(title: String): Unit = {
        output += ""
        output += styled(title, "1", colored)
      }

      output += styled(clean(result.title), "1", colored)
      field("Ontology", s"${result.namespace}/${result.name}", code = "36")
      field("Version", result.version)
      result.revision.foreach { revision =>
        field("Revises", CliFormatting.releaseLink(revision.revises.original), code = "36")
        revision.reason.foreach(field("Revision reason", _))
      }
      SearchOutput.wrap(result.description, 80, "  Description: ", "    ").zipWithIndex.foreach {
        (line, index) =>
          val prefix = if index == 0 then 15 else 4
          output += styled(line.take(prefix), "2", colored) + line.drop(prefix)
      }
      CliFormatting.elements(result.nativeElementCount, result.elementCount).foreach(
        field("Elements", _),
      )
      field("URL", CliFormatting.releaseLink(result.baseUrl.original), code = "36")

      versions.foreach { case Versions(latest, releases) =>
        section("Versions")
        val (latestHits, others) = releases.partition(hit => latest.contains(hit.baseUrl.original))
        val entries = latestHits ++ others.sortBy(_.version)(using VersionOrder.reverse)
        if entries.isEmpty then output += "  None"
        entries.take(10).foreach { hit =>
          val reference = clean(s"${hit.namespace}/${hit.name}@${hit.version}")
          val mark = if latest.contains(hit.baseUrl.original) then " (latest)" else ""
          output += s"  - ${styled(reference, "36", colored)}$mark"
        }
        if entries.size > 10 then output += s"  and ${entries.size - 10} more"
      }

      section("Files")
      if result.artifacts.isEmpty then output += "  None"
      result.artifacts.zipWithIndex.foreach { (artifact, index) =>
        if index > 0 then output += ""
        val url = artifact.url.original
        output += "  " + styled(clean(fileName(url)), "1", colored)
        field(
          "Format",
          s"${CliFormatting.formats.toMap.apply(artifact.format)} · ${stringify(artifact.format)}",
          "    ",
        )
        field(
          "Size",
          s"${String.format(java.util.Locale.ROOT, "%,d", Int.box(artifact.sizeBytes))} bytes",
          "    ",
        )
        field(
          "Source",
          artifact.origin match {
            case ArtifactOrigin.Upstream => "Upstream"
            case ArtifactOrigin.Generated => "Generated"
          },
          "    ",
        )
        field("URL", url, "    ", "36")
        field("SHA-256", artifact.sha256, "    ")
        if artifact.roots.map(_.original) != Seq(url) then {
          output += styled("    Entry points", "2", colored)
          artifact.roots.foreach(root =>
            output += "      " + styled(clean(root.original), "36", colored),
          )
        }
      }

      section("Dependencies")
      if result.dependencies.isEmpty then output += "  None"
      result.dependencies.foreach(dependency =>
        output += "  " + styled(
          clean(CliFormatting.releaseLink(dependency.baseUrl.original)),
          "36",
          colored,
        ),
      )

      result.provenance.foreach { provenance =>
        // In the order the hub makes them: downloads, then patches, then conversions
        val files = provenance.remote.map(file =>
          file.url -> Seq(("Downloaded from", file.from.original, "36")),
        ) ++ provenance.index.map(file =>
          file.url -> Seq(("From ohi-index", s"${file.path} at commit ${file.commit}", "0")),
        ) ++ provenance.patched.map(file =>
          file.url -> (Seq(("Patched from", file.from.original, "36")) ++
            Option.when(file.convertedLineEndings)(("Line endings", "converted to LF", "0")) ++
            file.addedBaseIri.map(iri => ("Added base IRI", iri.original, "36")) ++
            file.jsonPatch.map(patch => ("JSON Patch", patch.original, "36"))),
        ) ++ provenance.generated.map(file =>
          file.url -> Seq(
            ("Generated from", file.source.original, "36"),
            ("Using", file.using, "0"),
          ),
        )
        if files.nonEmpty then {
          section("Provenance")
          files.zipWithIndex.foreach { case ((url, details), index) =>
            if index > 0 then output += ""
            output += "  " + styled(clean(fileName(url.original)), "1", colored)
            details.foreach((label, value, code) => field(label, value, "    ", code))
          }
        }
      }

      section("Release details")
      field("Published to Hub", stringify(result.publishedAt))
      result.hubVersion.foreach(field("Hub version", _))
      field("Authors", agents(result.authors))
      field("Publishers", agents(result.publishers))
      if result.contributors.nonEmpty then field("Contributors", agents(result.contributors))
      if result.maintainers.nonEmpty then field("Maintainers", agents(result.maintainers))
      field("Original sources", result.originalSources.map(_.original).mkString(", "), code = "36")
      if result.publications.nonEmpty then
        field(
          "Publications",
          result.publications
            .map(citation => withDetails(citation.title, citation.link.map(_.original).toSeq))
            .mkString("; "),
        )
      result.namespaceIri.foreach(value => field("RDF namespace", value.original, code = "36"))
      result.preferredPrefix.foreach(value => field("Preferred prefix", value))
      field("License", reported(result.licenses))
      if result.licenseFiles.nonEmpty then
        field("License files", result.licenseFiles.map(_.original).mkString(", "), code = "36")
      field("Keywords", reported(result.keywords))
      if result.tags.nonEmpty then field("Tags", result.tags.mkString(", "))
      output.mkString("\n")
    }

  /** Render JSON or the groups and their tags, in the list's order. Tags in no group, or in an
    * unknown group, come last under Other. By default, each group has its title, and each
    * tag a line with its id and title, marked if it is deprecated. Verbose output also has the
    * ids and descriptions of the groups, the descriptions of the tags and why a tag is retired,
    * with each tag's id on a line of its own and the rest wrapped at 80 columns below it.
    */
  def tags(
      result: TagList,
      json: Boolean,
      colored: Boolean = false,
      verbose: Boolean = false,
  ): String =
    if json then JsonUtil.yamlToJson(tagListCodec.encode(result))
    else if result.tags.isEmpty then "No tags."
    else {
      def wrapped(text: String, indent: String): Seq[String] =
        SearchOutput.wrap(text, 80, indent, indent)

      val width = result.tags.map(tag => clean(tag.id).length).max
      def tag(value: Tag): Seq[String] =
        if verbose then
          Seq("  " + styled(clean(value.id), "36", colored)) ++
            wrapped(value.title, "    ") ++
            value.description.toSeq.flatMap(wrapped(_, "    ")) ++
            value.deprecated.toSeq.flatMap(reason =>
              wrapped(s"Deprecated: $reason", "    ").map(styled(_, "2", colored)),
            )
        else
          Seq(
            "  " + styled(clean(value.id).padTo(width, ' '), "36", colored) + "  " +
              clean(value.title) +
              (if value.deprecated.isDefined then styled(" (deprecated)", "2", colored) else ""),
          )

      val groupIds = result.groups.map(_.id).toSet
      val sections = result.groups.map(group =>
        (
          styled(clean(group.title), "1", colored) +
            (if verbose then styled(s" (${clean(group.id)})", "2", colored) else ""),
          group.description.filter(_ => verbose),
          result.tags.filter(_.group.contains(group.id)),
        ),
      ) :+ (styled("Other", "1", colored), None, result.tags.filterNot(_.group.exists(groupIds)))
      val groups = sections.filter(_._3.nonEmpty).map { (heading, description, tags) =>
        val about = description.toSeq.flatMap(wrapped(_, "  "))
        ((heading +: about) ++ (if about.nonEmpty then Seq("") else Seq()) ++ tags.flatMap(tag))
          .mkString("\n")
      }
      (groups :+ (styled("Search by a tag: ", "2", colored) +
        styled("ohi search \"*\" --filter-by tags:=<id>", "36", colored))).mkString("\n\n")
    }

  def delivery(result: DeliveryResultImpl): String =
    JsonUtil.yamlToJson(deliveryCodec.encode(result))

  def fileError(error: CliFileError): String =
    JsonUtil.yamlToJson(fileErrorCodec.encode(error))

  /** The last segment of a URL's path, or the whole URL if it has none. */
  private def fileName(url: String): String =
    try
      Option(URI.create(url).getPath).map(_.split("/", -1).last).filter(_.nonEmpty).getOrElse(url)
    catch { case _: IllegalArgumentException => url }

  private def reported(values: Seq[String]): String =
    if values.isEmpty then "Not supplied" else values.mkString(", ")

  /** Each person or organization as its name, then its contact and link in parentheses. Names can
    * hold commas, so they are separated with semicolons.
    */
  private def agents(values: Seq[Agent]): String =
    values
      .map(agent => withDetails(agent.name, agent.contact.toSeq ++ agent.link.map(_.original)))
      .mkString("; ")

  private def withDetails(text: String, details: Seq[String]): String =
    if details.isEmpty then text else s"$text (${details.mkString(", ")})"
}
