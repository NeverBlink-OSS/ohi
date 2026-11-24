package millbuild

/** Versions of the ohi Python and TypeScript SDK snapshots, published by snapshot-sdks.yml.
  *
  * They sort after the last release and before the next one, and grow with each commit:
  *
  * {{{
  * last tag     commits  Python (PEP 440)  npm (semver)
  * v0.18.7      7        0.18.8.dev7       0.18.8-snapshot.7.g249e1f3b
  * v0.19.0-rc1  3        0.19.0rc2.dev3    0.19.0-rc1.snapshot.3.g249e1f3b
  * }}}
  *
  * Both are `None` on a release, which release.yml publishes.
  */
object OhiSdk {
  private val Release = """(\d+)\.(\d+)\.(\d+)""".r

  /** The PEP 440 snapshot version of the Python SDK. PyPI rejects local versions (`+...`), so it
    * has no commit hash. The commit count keeps it unique, as long as main has a linear history.
    */
  def pythonSnapshotVersion(version: GitVersion): Option[String] =
    snapshot(version).map { case GitVersion.Snapshot(tag, commits, _) =>
      tag match {
        case Release(major, minor, patch) =>
          s"$major.$minor.${patch.toInt + 1}.dev$commits"
        case PyPackage.PreRelease(base, kind, number) =>
          s"$base${PyPackage.marker(kind)}${number.toInt + 1}.dev$commits"
        case _ => unsupportedTag(tag)
      }
    }

  /** The semver snapshot version of the npm package. The `g` keeps an all-digit hash from being
    * read as a number, which semver forbids to start with 0.
    */
  def npmSnapshotVersion(version: GitVersion): Option[String] =
    snapshot(version).map { case GitVersion.Snapshot(tag, commits, hash) =>
      tag match {
        case Release(major, minor, patch) =>
          s"$major.$minor.${patch.toInt + 1}-snapshot.$commits.g$hash"
        case PyPackage.PreRelease(_, _, _) =>
          s"$tag.snapshot.$commits.g$hash"
        case _ => unsupportedTag(tag)
      }
    }

  private def snapshot(version: GitVersion): Option[GitVersion.Snapshot] = version match {
    case s: GitVersion.Snapshot => Some(s)
    case GitVersion.Release(_) => None
    case GitVersion.Unknown(_) =>
      sys.error("Found no release tag to make a snapshot version from. Are the tags fetched?")
  }

  private def unsupportedTag(tag: String): Nothing =
    sys.error(s"Cannot make a snapshot version from the tag v$tag")
}
