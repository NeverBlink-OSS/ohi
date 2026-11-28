package eu.neverblink.linkml.generator.ossie.expression

import fastparse.*
import fastparse.NoWhitespace.given

/** How a relationship is said out loud: `{Concept} phrase {Range}`, or `{Concept} phrase
  * {Range:role}`, where `role` needs disambiguation.
  */
private[ossie] final case class Verbalization(
    concept: Ref,
    phrase: String,
    range: Ref,
    role: Option[String],
) {
  def render: String = {
    val target = role.fold(s"{${range.name}}")(r => s"{${range.name}:$r}")
    s"{${concept.name}} $phrase $target"
  }
}

private[ossie] object Verbalization {

  /** Read a verbalization, or None if it is not a supported shape. */
  def parse(text: String): Option[Verbalization] =
    fastparse.parse(text, verbalization(using _)) match {
      case Parsed.Success(v, _) => Some(v)
      case _: Parsed.Failure => None
    }

  /** The phrase of a unary verbalization, `{Concept} phrase`, or None if it is not one. */
  def unaryPhrase(text: String): Option[String] =
    fastparse.parse(text, unary(using _)) match {
      case Parsed.Success(phrase, _) => Some(phrase).filter(_.nonEmpty)
      case _: Parsed.Failure => None
    }

  /** A verbalization as plain text, without its braces. The Ossie importer keeps those after the
    * first in `aliases`, since a slot has one title.
    */
  def plain(text: String): String = text.filter(c => c != '{' && c != '}')

  /** The verbalization that [[plain]] made `alias`, if it starts and ends with one of `names`, with
    * a phrase between them.
    */
  def fromPlain(alias: String, names: Seq[String]): Option[String] = {
    // Longest first, so that `Customer` doesn't match the start of `CustomerSalesArea`
    val longestFirst = names.distinct.sortBy(-_.length)
    for
      first <- longestFirst.find(n => alias.startsWith(n + " "))
      last <- longestFirst.find(n => alias.endsWith(" " + n))
      if alias.length > first.length + last.length + 2
      phrase = alias.substring(first.length + 1, alias.length - last.length - 1).trim
      if phrase.nonEmpty
    yield s"{$first} $phrase {$last}"
  }

  private def unary[$: P]: P[String] = P(ws ~ "{" ~ name ~ "}" ~ phrase ~ End).map(_._2)

  private def verbalization[$: P]: P[Verbalization] =
    P(ws ~ "{" ~ name ~ "}" ~ phrase ~ "{" ~ name ~ (":" ~ role).? ~ "}" ~ ws ~ End).map {
      (concept, phrase, range, role) =>
        Verbalization(Ref(concept), phrase, Ref(range), role)
    }

  private def name[$: P]: P[String] = P(CharsWhile(c => c != '{' && c != '}' && c != ':').!)

  private def phrase[$: P]: P[String] = P(CharsWhile(c => c != '{' && c != '}').!).map(_.trim)

  private def role[$: P]: P[String] = P(CharsWhile(_ != '}').!)

  private def ws[$: P]: P[Unit] = P(CharsWhileIn(" \t", 0))
}
