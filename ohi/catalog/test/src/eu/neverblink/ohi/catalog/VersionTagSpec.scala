package eu.neverblink.ohi.catalog

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class VersionTagSpec extends AnyWordSpec, Matchers {
  "VersionTag.parse" should {
    "request the latest revision of a version" in {
      VersionTag.parse("1.0") shouldBe VersionTag("1.0", None)
    }

    "request exactly a revision" in {
      VersionTag.parse("1.0-revision-2026-12-31") shouldBe
        VersionTag("1.0", Some("1.0-revision-2026-12-31"))
    }

    "request a version without its revisions" in {
      VersionTag.parse("1.0-no-revision") shouldBe VersionTag("1.0", Some("1.0"))
    }

    "keep a version that is only a suffix as it is" in {
      VersionTag.parse("-no-revision") shouldBe VersionTag("-no-revision", None)
      VersionTag.parse("-revision-1") shouldBe VersionTag("-revision-1", None)
    }
  }
}
