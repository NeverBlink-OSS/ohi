package eu.neverblink.linkml.generator.importwarnings

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.*

/** Round-trips an [[ImportReport]] to YAML and back. */
class CodecRoundTripSpec extends AnyWordSpec, Matchers {

  private val report = ImportReport(
    warnings = Seq(
      UnmappedImport(importIri = "http://example.org/other").infer(),
      SeveralRanges(property = "knows", ranges = Seq("Person", "Agent")).infer(),
      NonOwlRdf(predicate = "http://example.org/p", tripleCount = 3).infer(),
    ),
  )

  "Codec" should {
    "write the warning_type and the inferred kind and detail of every warning as JSON" in {
      val json = Codec.toJson(report)
      json.should(include("\"warning_type\": \"UnmappedImport\""))
      json.should(include("\"warning_type\": \"SeveralRanges\""))
      json.should(include("\"warning_type\": \"NonOwlRdf\""))
      json.should(include("\"detail\": \"knows: Person, Agent\""))
      json.should(include("\"detail\": \"3 triples with http://example.org/p\""))
      json.should(include("\"kind\": \"RDF that is not OWL\""))
    }

    "recover the concrete warning types when reading a report back" in {
      val yaml = Codec.codec.encode(report).asYaml
      val decoded = Codec.codec.decode(parseYaml(yaml).toOption.get)
      decoded.shouldBe(report)
      decoded.warnings.map(_.getClass).shouldBe(
        Seq(classOf[UnmappedImport], classOf[SeveralRanges], classOf[NonOwlRdf]),
      )
    }

    "keep an empty list of warnings as a list in a linkml:Any value" in {
      val any = Codec.toAny(ImportReport(Nil))
      val node = eu.neverblink.linkml.yaml.LinkmlYamlCodec.anythingCodec.encode(any)
      Codec.codec.decode(node).shouldBe(ImportReport(Nil))
      eu.neverblink.linkml.yaml.NodeJson.write(node).replaceAll("\\s", "").should(include("[]"))
    }

    "reject a report whose warning has an unknown warning_type" in {
      val yaml =
        """warnings:
          |  - warning_type: NoSuchWarning
          |""".stripMargin
      an[Exception].should(be).thrownBy(Codec.codec.decode(parseYaml(yaml).toOption.get))
    }
  }
}
