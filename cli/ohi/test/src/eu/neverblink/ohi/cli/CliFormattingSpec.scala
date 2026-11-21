package eu.neverblink.ohi.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CliFormattingSpec extends AnyWordSpec, Matchers {
  "CliFormatting.releaseLink" should {
    "link a release on ohi.dev to its page" in {
      CliFormatting.releaseLink("https://api.ohi.dev/files/packages/w3c/sosa/2017/") shouldBe
        "https://ohi.dev/o/w3c/sosa@2017"
    }

    "decode the release identity before encoding it for the page" in {
      CliFormatting.releaseLink(
        "https://api.ohi.dev/files/packages/example/sensors/1.0.0+amendment.1%20%C3%A9/",
      ) shouldBe "https://ohi.dev/o/example/sensors@1.0.0%2Bamendment.1%20%C3%A9"
    }

    for url <- Seq(
        "https://hub.example/files/packages/w3c/sosa/2017/",
        "http://api.ohi.dev/files/packages/w3c/sosa/2017/",
        "https://api.ohi.dev/files/packages/w3c/sosa/",
        "https://api.ohi.dev/files/packages/w3c/sosa/2017/file.ttl",
      )
    do
      s"keep '$url', which is not a release on ohi.dev" in {
        CliFormatting.releaseLink(url) shouldBe url
      }
  }
}
