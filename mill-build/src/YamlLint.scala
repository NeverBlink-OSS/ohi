package millbuild

/** Runs https://github.com/adrienverge/yamllint with the rules in `.yamllint.yaml`.
  */
object YamlLint {
  val version = "1.38.0"

  /** @param python
    *   path to an interpreter with yamllint installed
    * @param files
    *   list of files to check, relative to `repoRoot`. When empty, every YAML file in the
    *   repository.
    */
  def run(repoRoot: os.Path, python: os.Path, files: Seq[String]): Unit = {
    val toCheck = if files.nonEmpty then files else allYamlFiles(repoRoot)
    val result = os.call(
      Seq(python.toString, "-m", "yamllint", "--strict", "--config-file", ".yamllint.yaml") ++
        toCheck,
      cwd = repoRoot,
      stdout = os.Inherit,
      stderr = os.Inherit,
      check = false,
    )
    if result.exitCode != 0 then throw new Exception("yamllint found problems")
    println(s"yamllint: ${toCheck.size} OK")
  }

  /** Tracked files and new files git doesn't ignore, so out/, .venv/ and node_modules/ are skipped.
    * Files deleted in the working tree are still listed by git, hence the `exists` check.
    */
  private def allYamlFiles(repoRoot: os.Path): Seq[String] =
    os.call(
      ("git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*.yml", "*.yaml"),
      cwd = repoRoot,
    ).out.lines().filter(file => os.exists(repoRoot / os.RelPath(file)))
}
