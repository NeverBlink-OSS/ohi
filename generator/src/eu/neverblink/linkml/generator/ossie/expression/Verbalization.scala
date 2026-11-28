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
    parseWithRest(text).collect { case (v, "") => v }

  /** Read a verbalization that may go on after the second concept, such as `{Aircraft} has
    * {Integer} seats`, with the text after it.
    */
  def parseWithRest(text: String): Option[(Verbalization, String)] =
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

  /** The verbalization that [[plain]] made `alias`, if it starts with one of `names`, and has
    * another of them later as a whole word, with a phrase between them.
    */
  def fromPlain(alias: String, names: Seq[String]): Option[String] = {
    // Longest first, so that `Customer` doesn't match the start of `CustomerSalesArea`
    val longestFirst = names.distinct.sortBy(-_.length)
    longestFirst.find(n => alias.startsWith(n + " ")).flatMap { first =>
      val start = first.length + 1
      def isSecond(n: String, i: Int) = {
        val end = i + 1 + n.length
        (end == alias.length || alias(end) == ' ') && alias.substring(start, i).trim.nonEmpty
      }
      // The earliest whole-word match of a name after the phrase
      val second = longestFirst.flatMap { n =>
        Iterator.iterate(alias.indexOf(" " + n, start))(i => alias.indexOf(" " + n, i + 1))
          .takeWhile(_ >= 0)
          .find(isSecond(n, _))
          .map(_ -> n)
      }.minByOption(_._1)
      second.map { (i, n) =>
        val phrase = alias.substring(start, i).trim
        val rest = alias.substring(i + 1 + n.length).trim
        s"{$first} $phrase {$n}" + (if rest.isEmpty then "" else s" $rest")
      }
    }
  }

  private def unary[$: P]: P[String] = P(ws ~ "{" ~ name ~ "}" ~ phrase ~ End).map(_._2)

  private def verbalization[$: P]: P[(Verbalization, String)] =
    P(ws ~ "{" ~ name ~ "}" ~ phrase ~ "{" ~ name ~ (":" ~ role).? ~ "}" ~ rest ~ End).map {
      (concept, phrase, range, role, rest) =>
        (Verbalization(Ref(concept), phrase, Ref(range), role), rest)
    }

  /** Text after the second concept, without another one. */
  private def rest[$: P]: P[String] = P(CharsWhile(c => c != '{' && c != '}', 0).!).map(_.trim)

  private def name[$: P]: P[String] = P(CharsWhile(c => c != '{' && c != '}' && c != ':').!)

  private def phrase[$: P]: P[String] = P(CharsWhile(c => c != '{' && c != '}').!).map(_.trim)

  private def role[$: P]: P[String] = P(CharsWhile(_ != '}').!)

  private def ws[$: P]: P[Unit] = P(CharsWhileIn(" \t", 0))
}
