package eu.neverblink.ohi.catalog

/** The release the version of a reference `namespace/name@version` requests, shared by the SDK and
  * the ohi CLI. A published version can be fixed in revisions, `{original}-revision-{id}`:
  *   - `1.0` requests the latest revision of 1.0, or 1.0 itself when it has none
  *   - `1.0-revision-2026-12-31` requests exactly that revision
  *   - `1.0-no-revision` requests the 1.0 version without its revisions
  *
  * @param original
  *   the version first published
  * @param exact
  *   the version to use as it is, or None for the latest revision of `original`
  */
private[ohi] final case class VersionTag(original: String, exact: Option[String])

private[ohi] object VersionTag {

  /** What separates a revision's original version from its id. */
  val RevisionSeparator = "-revision-"

  /** What requests a version without its revisions. */
  val NoRevisionSuffix = "-no-revision"

  def parse(version: String): VersionTag = {
    val original = version.stripSuffix(NoRevisionSuffix)
    if original != version && original.nonEmpty then VersionTag(original, Some(original))
    else
      version.indexOf(RevisionSeparator) match {
        case at if at > 0 => VersionTag(version.substring(0, at), Some(version))
        case _ => VersionTag(version, None)
      }
  }
}
