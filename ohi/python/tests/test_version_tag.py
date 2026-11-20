import unittest

from ohi._version_tag import VersionTag, parse_version_tag


class VersionTagTests(unittest.TestCase):
    def test_a_version_requests_its_latest_revision(self):
        self.assertEqual(parse_version_tag("1.0"), VersionTag("1.0"))

    def test_a_revision_requests_exactly_that_revision(self):
        self.assertEqual(parse_version_tag("1.0-revision-2026-12-31"),
                         VersionTag("1.0", "1.0-revision-2026-12-31"))

    def test_no_revision_requests_the_version_without_its_revisions(self):
        self.assertEqual(parse_version_tag("1.0-no-revision"), VersionTag("1.0", "1.0"))

    def test_a_version_that_is_only_a_suffix_is_kept_as_it_is(self):
        self.assertEqual(parse_version_tag("-no-revision"), VersionTag("-no-revision"))
        self.assertEqual(parse_version_tag("-revision-1"), VersionTag("-revision-1"))


if __name__ == "__main__":
    unittest.main()
