package eu.neverblink.ohi.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VersionTagTest {
    @Test
    void requestsTheLatestRevisionOfAVersion() {
        assertEquals(new VersionTag("1.0", null), VersionTag.parse("1.0"));
    }

    @Test
    void requestsExactlyARevision() {
        assertEquals(new VersionTag("1.0", "1.0-revision-2026-12-31"), VersionTag.parse("1.0-revision-2026-12-31"));
    }

    @Test
    void requestsAVersionWithoutItsRevisions() {
        assertEquals(new VersionTag("1.0", "1.0"), VersionTag.parse("1.0-no-revision"));
    }

    @Test
    void keepsAVersionThatIsOnlyASuffix() {
        assertEquals(new VersionTag("-no-revision", null), VersionTag.parse("-no-revision"));
        assertEquals(new VersionTag("-revision-1", null), VersionTag.parse("-revision-1"));
    }
}
