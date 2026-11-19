/** The release the version of a reference `namespace/name@version` requests. A published version can be fixed in
 * revisions, `{original}-revision-{id}`:
 * - `1.0` requests the latest revision of 1.0, or 1.0 itself when it has none
 * - `1.0-revision-2026-12-31` requests exactly that revision
 * - `1.0-no-revision` requests the 1.0 version without its revisions
 */
export interface VersionTag {
  /** The version first published. */
  original: string;
  /** The version to use as it is, or undefined for the latest revision of `original`. */
  exact?: string;
}

/** What separates a revision's original version from its id. */
export const revisionSeparator = "-revision-";
/** What requests a version without its revisions. */
export const noRevisionSuffix = "-no-revision";

export function parseVersionTag(version: string): VersionTag {
  if (version.endsWith(noRevisionSuffix) && version.length > noRevisionSuffix.length) {
    const original = version.slice(0, -noRevisionSuffix.length);
    return { original, exact: original };
  }
  const at = version.indexOf(revisionSeparator);
  return at > 0 ? { original: version.slice(0, at), exact: version } : { original: version };
}
