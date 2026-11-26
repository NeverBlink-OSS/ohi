package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.{LinkmlDateTime, Uri}
import eu.neverblink.ohi.model.generated.{
  Agent,
  ArtifactOrigin,
  Citation,
  Dependency,
  Format,
  GeneratedFile,
  IndexFile,
  PatchedFile,
  Provenance,
  RemoteFile,
  RevisionInfo,
  SearchHit,
}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CatalogOutputSpec extends AnyWordSpec, Matchers {
  private val upstreamUrl = "https://example.org/Z%20sensor+v2.ttl?revision=2"
  private val generatedUrl = "https://example.org/sensors.linkml.yaml"
  private val base = ProjectTestData.publication(
    "https://example.org/release/1/",
    Seq(upstreamUrl -> Format.OwlTurtle, generatedUrl -> Format.LinkmlYaml),
    Seq("https://example.org/dependency/"),
  )
  private val publication = base.copy(
    artifacts = Seq(
      base.artifacts.head.copy(roots = Seq(Uri(upstreamUrl)), sizeBytes = 12501),
      base.artifacts(1).copy(
        origin = ArtifactOrigin.Generated,
        roots = Seq(Uri("https://example.org/z.yaml"), Uri("https://example.org/a.yaml")),
      ),
    ),
    namespaceIri = Some(Uri("https://example.org/terms#")),
    preferredPrefix = Some("ex"),
    licenses = Seq("MIT"),
    keywords = Seq("sensors"),
    tags = Seq("internet-of-things", "units-and-measurement"),
    authors = Seq(
      Agent(
        name = "Ada Example",
        contact = Some("ada@example.org"),
        link = Some(Uri("https://example.org/ada")),
      ),
      Agent(name = "Sensors, Inc."),
    ),
  )
  private val expected = """Example ontology
    |  Ontology: test/example
    |  Version: 1
    |  Description: Synthetic selection metadata
    |  URL: https://example.org/release/1/
    |
    |Files
    |  Z sensor+v2.ttl
    |    Format: OWL (Turtle) · owl-turtle
    |    Size: 12,501 bytes
    |    Source: Upstream
    |    URL: https://example.org/Z%20sensor+v2.ttl?revision=2
    |    SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    |
    |  sensors.linkml.yaml
    |    Format: LinkML (YAML) · linkml-yaml
    |    Size: 4 bytes
    |    Source: Generated
    |    URL: https://example.org/sensors.linkml.yaml
    |    SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    |    Entry points
    |      https://example.org/z.yaml
    |      https://example.org/a.yaml
    |
    |Dependencies
    |  https://example.org/dependency/
    |
    |Release details
    |  Published to Hub: 2026-10-15T00:00:00Z
    |  Authors: Ada Example (ada@example.org, https://example.org/ada); Sensors, Inc.
    |  Publishers: Example publisher
    |  Original sources: https://example.org/source/
    |  RDF namespace: https://example.org/terms#
    |  Preferred prefix: ex
    |  License: MIT
    |  Keywords: sensors
    |  Tags: internet-of-things, units-and-measurement""".stripMargin

  private def release(version: String): SearchHit =
    SearchHit(
      baseUrl = Uri(s"https://example.org/release/$version/"),
      description = "Synthetic release",
      formats = Seq.empty,
      keywords = Seq.empty,
      licenses = Seq.empty,
      name = "example",
      namespace = "test",
      authors = Seq(Agent(name = "Example author")),
      publishers = Seq(Agent(name = "Example publisher")),
      originalSources = Seq(Uri("https://example.org/source/")),
      publishedAt = LinkmlDateTime("2026-10-15T00:00:00Z"),
      title = "Example ontology",
      version = version,
    )

  "CatalogOutput.publication" should {
    "show release fields in order and omit only entry points equal to the download URL" in {
      CatalogOutput.publication(publication, json = false) shouldBe expected
    }

    "show the revision, the hub version, the license files and where each file came from" in {
      val release = "https://example.org/release/1-revision-2/"
      val output = CatalogOutput.publication(
        publication.copy(
          version = "1-revision-2",
          revision = Some(
            RevisionInfo(
              id = "2",
              originalVersion = "1",
              revises = Uri("https://example.org/release/1/"),
              reason = Some("Derived again"),
            ),
          ),
          hubVersion = Some("v0.2.0"),
          licenseFiles = Seq(Uri(release + "LICENSE")),
          provenance = Some(
            Provenance(
              remote = Seq(
                RemoteFile(url = Uri(release + "a.ttl"), from = Uri("https://example.org/a.ttl")),
              ),
              index =
                Seq(IndexFile(url = Uri(release + "LICENSE"), commit = "0123abc", path = "L")),
              patched = Seq(
                PatchedFile(
                  url = Uri(release + "b.ttl"),
                  from = Uri(release + "a.ttl"),
                  convertedLineEndings = true,
                  addedBaseIri = Some(Uri("https://example.org/b#")),
                  jsonPatch = Some(Uri(release + "b.patch.json")),
                ),
                PatchedFile(url = Uri(release + "c.ttl"), from = Uri(release + "a.ttl")),
              ),
              generated = Seq(
                GeneratedFile(
                  url = Uri(release + "b.yaml"),
                  source = Uri(release + "b.ttl"),
                  using = "LinkML",
                ),
              ),
            ),
          ),
        ),
        json = false,
      )
      output should include(
        """  Version: 1-revision-2
          |  Revises: https://example.org/release/1/
          |  Revision reason: Derived again
          |  Description:""".stripMargin,
      )
      output should include(
        s"""  https://example.org/dependency/
           |
           |Provenance
           |  a.ttl
           |    Downloaded from: https://example.org/a.ttl
           |
           |  LICENSE
           |    From ohi-index: L at commit 0123abc
           |
           |  b.ttl
           |    Patched from: ${release}a.ttl
           |    Line endings: converted to LF
           |    Added base IRI: https://example.org/b#
           |    JSON Patch: ${release}b.patch.json
           |
           |  c.ttl
           |    Patched from: ${release}a.ttl
           |
           |  b.yaml
           |    Generated from: ${release}b.ttl
           |    Using: LinkML
           |
           |Release details
           |  Published to Hub: 2026-10-15T00:00:00Z
           |  Hub version: v0.2.0
           |  Authors:""".stripMargin,
      )
      output should include(s"  License: MIT\n  License files: ${release}LICENSE\n")
    }

    "leave out the provenance when it lists no files" in {
      CatalogOutput.publication(
        publication.copy(provenance = Some(Provenance())),
        json = false,
      ) shouldBe expected
    }

    "show the element counts after the description, and equal counts once" in {
      def render(native: Option[Int], linkml: Option[Int]): String =
        CatalogOutput.publication(
          publication.copy(nativeElementCount = native, elementCount = linkml),
          json = false,
        )
      render(Some(95), Some(120)) shouldBe expected.replaceFirst(
        "\n  URL:",
        "\n  Elements: 95 native, 120 in LinkML\n  URL:",
      )
      render(Some(120), Some(120)) should include(
        "  Description: Synthetic selection metadata\n  Elements: 120\n  URL:",
      )
      render(None, Some(120)) should include("\n  Elements: 120 in LinkML\n")
      render(Some(95), None) should include("\n  Elements: 95 native\n")
    }

    "leave out the elements line when neither element count is known" in {
      val output = CatalogOutput.publication(
        publication.copy(nativeElementCount = None, elementCount = None),
        json = false,
      )
      output shouldBe expected
      output should not include "Elements:"
    }

    "label empty collections without inventing absent optional metadata" in {
      val output = CatalogOutput.publication(
        base.copy(artifacts = Seq.empty, dependencies = Seq.empty),
        json = false,
      )
      output should include("\nFiles\n  None\n\nDependencies\n  None\n")
      output should endWith("  License: Not supplied\n  Keywords: Not supplied")
      output should not include "Tags:"
      output should include(
        "\n  Authors: Example author\n  Publishers: Example publisher\n" +
          "  Original sources: https://example.org/source/\n",
      )
      Seq(
        "RDF namespace",
        "Preferred prefix",
        "Contributors",
        "Maintainers",
        "Publications",
      ).foreach(output should not include _)
    }

    "show the optional attribution after the publishers" in {
      val output = CatalogOutput.publication(
        publication.copy(
          contributors = Seq(Agent(name = "Sensor Working Group")),
          maintainers = Seq(Agent(name = "Index Team", contact = Some("index@example.org"))),
          originalSources =
            Seq(Uri("https://example.org/sensors/"), Uri("https://example.org/git")),
          publications = Seq(
            Citation(title = "Sensors explained", link = Some(Uri("https://doi.org/10.0/s"))),
            Citation(title = "More sensors"),
          ),
        ),
        json = false,
      )
      output should include(
        """  Publishers: Example publisher
          |  Contributors: Sensor Working Group
          |  Maintainers: Index Team (index@example.org)
          |  Original sources: https://example.org/sensors/, https://example.org/git
          |  Publications: Sensors explained (https://doi.org/10.0/s); More sensors
          |  RDF namespace:""".stripMargin,
      )
    }

    "style headings and URLs without changing the plain result" in {
      val output = CatalogOutput.publication(publication, json = false, colored = true)
      output should startWith("\u001b[1mExample ontology\u001b[0m\n")
      output should include("\u001b[1mZ sensor+v2.ttl\u001b[0m")
      output should include("\u001b[36mtest/example\u001b[0m")
      output should include(s"\u001b[36m$upstreamUrl\u001b[0m")
      output.replaceAll("\u001b\\[[0-9;]*m", "") shouldBe expected
    }

    "wrap the description under its field while preserving complete long URLs" in {
      val url =
        "https://example.org/releases/temperature-and-humidity-sensors/2026-10-28/metadata.json?revision=2"
      val output = CatalogOutput.publication(
        base.copy(
          baseUrl = Uri(url),
          description =
            "This ontology describes measurements from temperature sensors and humidity sensors in rooms.",
        ),
        json = false,
      )
      output should include(
        "  Description: This ontology describes measurements from temperature sensors and\n" +
          "    humidity sensors in rooms.\n",
      )
      output should include(s"  URL: $url\n")
    }

    "link the release and its dependencies on ohi.dev to their pages" in {
      val hub = "https://api.ohi.dev/files/packages"
      val output = CatalogOutput.publication(
        base.copy(
          baseUrl = Uri(s"$hub/test/example/1/"),
          dependencies = Seq(Dependency(Uri(s"$hub/w3c/prov-o/2013/"))),
        ),
        json = false,
      )
      output should include("  URL: https://ohi.dev/o/test/example@1\n")
      output should include("\nDependencies\n  https://ohi.dev/o/w3c/prov-o@2013\n")
    }

    "list 10 versions after the release URL, then the count of the rest" in {
      val releases = (1 to 12).map(version => release(version.toString))
      val output = CatalogOutput.publication(
        base,
        json = false,
        versions = Some(CatalogOutput.Versions(None, releases)),
      )
      output should include(
        """  URL: https://example.org/release/1/
          |
          |Versions
          |  - test/example@12
          |  - test/example@11
          |  - test/example@10
          |  - test/example@9
          |  - test/example@8
          |  - test/example@7
          |  - test/example@6
          |  - test/example@5
          |  - test/example@4
          |  - test/example@3
          |  and 2 more
          |
          |Files
          |""".stripMargin,
      )
    }

    for url <- Seq("https://example.org/files/", "urn:example:artifact", "https://example.org/%") do
      s"preserve the metadata URL '$url' when it has no usable display name" in {
        val output = CatalogOutput.publication(
          base.copy(artifacts =
            Seq(base.artifacts.head.copy(url = Uri(url), roots = Seq(Uri(url)))),
          ),
          json = false,
        )
        output should include(s"\nFiles\n  $url\n")
        output should include(s"    URL: $url\n")
      }
  }
}
