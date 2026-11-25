package millbuild

import mill.*
import mill.javalib.PublishModule

/** Where the checked out commit is relative to the `v*` release tags. */
enum GitVersion derives upickle.default.ReadWriter {

  /** A tagged commit, like `0.13.2` for `v0.13.2`. */
  case Release(tag: String)

  /** `commits` commits after the release `tag`, at the commit `hash`. */
  case Snapshot(tag: String, commits: Int, hash: String)

  /** No release tag to start from, as in a shallow clone, or `git describe` output we cannot read.
    */
  case Unknown(base: String)

  /** The Maven version, like `0.13.2-3-abc1234-SNAPSHOT`. */
  def mavenVersion: String = this match {
    case Release(tag) => tag
    case Snapshot(tag, commits, hash) => s"$tag-$commits-$hash-SNAPSHOT"
    case Unknown(base) => s"$base-SNAPSHOT"
  }
}

/** A published module whose version comes from the git tags: the tag on a release build, and
  * otherwise a snapshot version made from `git describe`.
  */
trait GitVersionPublishModule extends PublishModule {

  /** An input, so it runs again on every build: new commits and tags change it, which Mill
    * cannot see.
    */
  final def gitVersion: T[GitVersion] = Task.Input {
    // On a tag build, use the tag that triggered the release rather than
    // `git describe`, which is ambiguous when several v* tags point at the same
    // commit and can otherwise publish the wrong version.
    val tagVersion =
      if sys.env.get("GITHUB_REF_TYPE").contains("tag") then
        sys.env.get("GITHUB_REF_NAME").filter(_.startsWith("v")).map(_.stripPrefix("v"))
      else None
    tagVersion.map(GitVersion.Release(_)).getOrElse(describeVersion())
  }

  final def publishVersion = Task { gitVersion().mavenVersion }

  private def describeVersion(): GitVersion =
    try describeVersionUnsafe()
    catch {
      // No tags, shallow clone or not a git checkout
      case _: os.SubprocessException => GitVersion.Unknown("0.0.0")
    }

  private def describeVersionUnsafe(): GitVersion = {
    val gitOutput = os.proc("git", "describe", "--tags", "--long", "--match", "v*")
      .call(cwd = mill.api.BuildCtx.workspaceRoot, stderr = os.Pipe)
      .out
      .trim()
    val GitDescribeRegex = """v(.*)-(\d+)-g([0-9a-fA-F]+)""".r
    gitOutput match {
      case GitDescribeRegex(tag, commitCount, shortHash) =>
        if (commitCount == "0") GitVersion.Release(tag)
        else {
          val targetCommit = os.proc("git", "rev-parse", s"HEAD~$commitCount")
            .call(cwd = mill.api.BuildCtx.workspaceRoot).out.trim()
          val tagsAtCommit = os.proc("git", "tag", "--points-at", targetCommit)
            .call(cwd = mill.api.BuildCtx.workspaceRoot).out.trim().linesIterator.toList
          val latestTag = tagsAtCommit
            .filter(_.startsWith("v"))
            .map(_.stripPrefix("v"))
            .sorted(using Ordering.String.reverse)
            .headOption
            .getOrElse(tag)
          GitVersion.Snapshot(latestTag, commitCount.toInt, shortHash)
        }
      case other =>
        GitVersion.Unknown(other)
    }
  }
}
