package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.ohi.catalog.Reference
import eu.neverblink.ohi.model.generated.*
import scala.collection.mutable

private[cli] object ProjectLock {

  /** Reconstructs direct requirements in selection order from a validated lock, without metadata
    * requests. Hub identities must be canonical. Declaration validation remains the caller's job.
    */
  def requirements(complete: Lock): Seq[ProjectDependency] = {
    val files = complete.files.map(file => (file.baseUrl, file.url) -> file).toMap
    complete.selections.map { selection =>
      val identity = HubArtifactPath.publication(selection.baseUrl.original, ".")
      ProjectDependency(
        format = files((selection.baseUrl, selection.url)).format,
        ontology = s"${identity.namespace}/${identity.name}@${identity.version}",
      )
    }
  }

  /** Whether a locked requirement, of an exact release, satisfies a declared one: the same ontology
    * and format, and the same version or a revision of it. A declared version that is not a
    * revision, `ns/name@1.0`, stands for its latest revision, so the lock can hold any of its
    * revisions, `ns/name@1.0-revision-2026-12-31`, and is never upgraded by checking it.
    */
  def satisfies(declared: ProjectDependency, locked: ProjectDependency): Boolean =
    declared.format == locked.format && (declared.ontology == locked.ontology ||
      ((Reference.parse(declared.ontology), Reference.parse(locked.ontology)) match {
        case (
              Some((namespace, name, Some(version))),
              Some((lockedNamespace, lockedName, Some(exact))),
            ) =>
          namespace == lockedNamespace && name == lockedName && !Reference.isRevision(version) &&
          Reference.originalVersion(exact) == version
        case _ => false
      }))

  /** The selection of the lock that satisfies each declared requirement, each selection used for
    * one requirement at most, an equal requirement before a revision of it. None for a requirement
    * no unused selection satisfies.
    */
  def locked(
      declared: Seq[ProjectDependency],
      complete: Lock,
  ): Seq[Option[ArtifactSelectionImpl]] = {
    val unused = mutable.ArrayBuffer.from(requirements(complete).zip(complete.selections))
    def take(matching: ProjectDependency => Boolean): Option[ArtifactSelectionImpl] =
      Some(unused.indexWhere((requirement, _) => matching(requirement))).filter(_ >= 0)
        .map(unused.remove(_)._2)
    val exact = declared.map(requirement => take(_ == requirement))
    declared.zip(exact).map { (requirement, selection) =>
      selection.orElse(take(satisfies(requirement, _)))
    }
  }

  /** Whether the lock's direct selections are exactly the declared requirements, in any order, each
    * selection satisfying one requirement.
    */
  def matches(declared: Seq[ProjectDependency], complete: Lock): Boolean =
    declared.size == complete.selections.size && locked(declared, complete).forall(_.nonEmpty)

  /** Builds a version-2 lock for selections and same-format dependencies using cached metadata.
    * Propagates inspection failures. Throws [[ProjectException]] for invalid selections or missing
    * or ambiguous dependency artifacts, including invalid Hub URLs.
    */
  def build(
      selections: Seq[ArtifactSelectionImpl],
      metadata: Inspection,
  ): Lock = {
    val direct = metadata.selected(selections)
    direct.foreach { (result, _) =>
      result.dependencies.foreach(dependency => metadata.publication(dependency.baseUrl.original))
    }

    val files = direct.flatMap { (result, artifact) =>
      (result -> artifact) +: result.dependencies.map { dependency =>
        val required = metadata(dependency.baseUrl.original)
        val matches = required.artifacts.filter(_.format == artifact.format)
        if matches.size != 1 then
          throw new ProjectException(
            ProjectErrorCode.InvalidSelection,
            s"Dependency ${dependency.baseUrl.original} has ${matches.size} artifacts in format ${stringify(artifact.format)} required by ${artifact.url.original}. Choose another direct artifact format or use ohi get for explicit files.",
          )
        required -> matches.head
      }
    }.distinctBy { (result, artifact) =>
      (result.baseUrl.original, artifact.url.original)
    }.map { (result, artifact) =>
      file(result, artifact)
    }
    Lock(files = files, formatVersion = 2, selections = selections)
  }

  /** Requires an artifact selected from `publication.artifacts`. Validates Hub identity and URL
    * ancestry, retaining its URL and checksum. Throws [[ProjectException]] for invalid URLs or
    * metadata disagreement, preserving URL failures.
    */
  def file(publication: Publication, artifact: Artifact): LockedFile = try {
    val identity = HubArtifactPath.publication(publication.baseUrl.original, ".")
    if identity.namespace != publication.namespace || identity.name != publication.name ||
      identity.version != publication.version
    then
      throw new ProjectException(
        ProjectErrorCode.InvalidSelection,
        s"Publication '${publication.baseUrl.original}' does not match its namespace, name and version. Correct the publication metadata and retry.",
      )
    if HubArtifactPath.artifact(artifact.url.original, ".") != identity then
      throw new ProjectException(
        ProjectErrorCode.InvalidSelection,
        s"Artifact '${artifact.url.original}' is outside publication '${publication.baseUrl.original}'. Choose an advertised artifact beneath its publication URL.",
      )
    LockedFile(
      publication.baseUrl,
      artifact.format,
      artifact.url,
      Some(artifact.sha256),
    )
  } catch {
    case cause: CliFileException =>
      throw new ProjectException(ProjectErrorCode.InvalidSelection, cause.getMessage, Some(cause))
  }

  /** Caches publication metadata and validates exact direct selections. Reuse one instance across
    * reference resolution and lock construction to avoid repeated HTTP requests.
    */
  final class Inspection(inspect: String => Publication) extends (String => Publication) {
    private val publications = mutable.Map.empty[String, Publication]

    /** Returns cached metadata without checking its identity, leaving reference diagnostics to the
      * caller.
      */
    override def apply(url: String): Publication = publications.getOrElse(
      url, {
        val result = inspect(url)
        publications(url) = result
        // A reference resolves to a base URL that lock construction inspects again.
        publications(result.baseUrl.original) = result
        result
      },
    )

    /** Requires the response's exact base URL to match the request or throws [[ProjectException]].
      */
    def publication(url: String): Publication = {
      val result = apply(url)
      if result.baseUrl.original != url then
        throw new ProjectException(
          ProjectErrorCode.InvalidSelection,
          s"Publication $url returned identity ${result.baseUrl.original}. Correct the publication metadata and retry.",
        )
      result
    }

    /** Resolves exact direct selections or throws [[ProjectException]] for inconsistent metadata.
      */
    def selected(selections: Seq[ArtifactSelectionImpl]): Seq[(Publication, Artifact)] =
      selections.map { selection =>
        val result = publication(selection.baseUrl.original)
        val artifact = result.artifacts.find(_.url.original == selection.url.original).getOrElse {
          throw new ProjectException(
            ProjectErrorCode.InvalidSelection,
            s"Publication ${selection.baseUrl.original} does not advertise artifact ${selection.url.original}. Choose an advertised artifact URL and retry.",
          )
        }
        result -> artifact
      }
  }
}
