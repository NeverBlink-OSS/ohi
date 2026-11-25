package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.generator.javagen.JavaRenamer
import eu.neverblink.linkml.generator.scala.ScalaWords

/** Check of the `package` option of the Scala and Java generators. */
object PackageName {

  /** The language of the generated code.
    *
    * @param backticks
    *   whether a part may be any text in backticks, such as `` `type` ``
    */
  enum Language(val keywords: Set[String], val backticks: Boolean) {
    case Scala extends Language(ScalaWords.keywords, true)
    case Java extends Language(JavaRenamer.keywords, false)
  }

  /** Fails unless [[name]] is identifiers separated by dots, none of them a keyword.
    *
    * @throws IllegalArgumentException
    *   if the name is not a valid package name
    */
  def check(name: String, language: Language): Unit = {
    def fail(reason: String): Nothing =
      throw new IllegalArgumentException(s"Invalid $language package name '$name': $reason")
    if name.isEmpty then fail("it is empty.")
    name.split("\\.", -1).foreach { part =>
      val quoted = language.backticks && part.length > 2 && part.head == '`' && part.last == '`'
      if part.isEmpty then fail("parts between dots can't be empty.")
      else if quoted then {
        if part.view.slice(1, part.length - 1).exists(c => c == '`' || c == '\n' || c == '\r') then
          fail(s"$part can't contain backticks or line breaks.")
      } else if language.keywords(part) || part == "_" then
        fail(
          if language.backticks then
            s"'$part' is a $language keyword. Write it in backticks: `$part`."
          else s"'$part' is a $language keyword.",
        )
      else {
        val c = part.head
        if !(Character.isLetter(c) || c == '_') ||
          !part.forall(c => Character.isLetterOrDigit(c) || c == '_')
        then
          fail(s"'$part' must start with a letter or '_' and contain only letters, digits and '_'.")
      }
    }
  }
}
