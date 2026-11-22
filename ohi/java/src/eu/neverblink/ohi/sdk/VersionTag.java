package eu.neverblink.ohi.sdk;

/**
 * The release the version of a reference {@code namespace/name@version} requests. A published
 * version can be fixed in revisions, {@code {original}-revision-{id}}:
 * <ul>
 * <li>{@code 1.0} requests the latest revision of 1.0, or 1.0 itself when it has none</li>
 * <li>{@code 1.0-revision-2026-12-31} requests exactly that revision</li>
 * <li>{@code 1.0-no-revision} requests the 1.0 version without its revisions</li>
 * </ul>
 *
 * @param original the version first published
 * @param exact the version to use as it is, or null for the latest revision of {@code original}
 */
record VersionTag(String original, String exact) {
    /** What separates a revision's original version from its id. */
    static final String REVISION_SEPARATOR = "-revision-";
    /** What requests a version without its revisions. */
    static final String NO_REVISION_SUFFIX = "-no-revision";

    static VersionTag parse(String version) {
        if (version.endsWith(NO_REVISION_SUFFIX) && version.length() > NO_REVISION_SUFFIX.length()) {
            String original = version.substring(0, version.length() - NO_REVISION_SUFFIX.length());
            return new VersionTag(original, original);
        }
        int at = version.indexOf(REVISION_SEPARATOR);
        return at > 0 ? new VersionTag(version.substring(0, at), version) : new VersionTag(version, null);
    }
}
