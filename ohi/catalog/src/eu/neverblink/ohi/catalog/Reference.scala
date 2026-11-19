package eu.neverblink.ohi.catalog

/** Ontology references `namespace/name[@version]`, shared by the SDK and the ohi CLI. */
private[ohi] object Reference {
  private val Pattern = "(?U)([^/@\\s`]+)/([^/@\\s`]+)(?:@([^/@\\s+`]+))?".r

  /** Namespace, name and optional version when the whole input is a reference, else None. */
  def parse(input: String): Option[(String, String, Option[String])] =
    input match {
      case Pattern(namespace, name, version) => Some((namespace, name, Option(version)))
      case _ => None
    }

  /** Typesense filter for the releases of one ontology. */
  def filter(namespace: String, name: String): String =
    s"namespace:=`$namespace` && name:=`$name`"

  /** What separates a revision's original version from its id, as in `1.0-revision-2026-12-31`. */
  val RevisionSeparator = "-revision-"

  /** Whether the version is a revision of another, `{original_version}-revision-{id}`. */
  def isRevision(version: String): Boolean = version.contains(RevisionSeparator)

  /** The version first published: the version itself, or the part before `-revision-` of a
    * revision.
    */
  def originalVersion(version: String): String = {
    val at = version.indexOf(RevisionSeparator)
    if at < 0 then version else version.substring(0, at)
  }
}
