package eu.neverblink.linkml.generator.ossie.expression

import org.scalacheck.Gen

/** Scalacheck generators for Ossie expressions. */
private[ossie] object ExpressionGen {

  import Expression.*

  val ident: Gen[String] = for {
    first <- Gen.oneOf(Gen.alphaChar, Gen.const('_'))
    rest <- Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.numChar, Gen.const('_')))
  } yield (first +: rest).mkString

  /** Any name, including those an expression has to quote. */
  val name: Gen[String] = Gen.frequency(
    3 -> ident,
    1 -> Gen.listOf(Gen.oneOf('a', ' ', '"', '.', '\'')).map(_.mkString),
    1 -> Gen.asciiPrintableStr,
  )

  val ref: Gen[Ref] = name.map(Ref.apply)

  /** Text that goes inside a quoted literal. */
  val text: Gen[String] = Gen.frequency(
    3 -> Gen.asciiPrintableStr,
    3 -> Gen.listOf(Gen.oneOf('\'', 'a', ' ', ',', ')', '(')).map(_.mkString),
    1 -> Gen.const(""),
    1 -> Gen.const("it's"),
    1 -> Gen.const("'"),
    1 -> Gen.const("''"),
    2 -> Gen.asciiStr,
  )

  /** A number, as the writer would produce one - anything [[Literal.isNumber]] accepts. */
  val number: Gen[Literal.Number] = Gen.oneOf(
    Gen.chooseNum(Int.MinValue, Int.MaxValue).map(_.toString),
    Gen.chooseNum(-1.0e9, 1.0e9).map(_.toString),
    Gen.chooseNum(Long.MinValue, Long.MaxValue).map(_.toString),
  ).filter(Literal.isNumber).map(Literal.Number.apply)

  val literalText: Gen[Literal.Text] = text.map(Literal.Text.apply)

  val literal: Gen[Literal] = Gen.oneOf(number, literalText)

  /** A literal that survives a trip through LinkML.
    *
    * TODO: consider trying to fix some of the cases that are excluded on the side of LinkML.
    */
  val linkmlSafeLiteral: Gen[Literal] = Gen.oneOf(
    number,
    text.map { s =>
      val oneLine = s.filter(c => c != '\n' && c != '\r')
      if oneLine.isEmpty || Literal.isNumber(oneLine) then oneLine + "x" else oneLine
    }.map(Literal.Text.apply),
  )

  val member: Gen[Member] = for { r <- ref; rel <- name } yield Member(r, rel)

  val inList: Gen[InList] = for { r <- ref; vs <- Gen.listOf(literalText) } yield InList(r, vs)

  val atLeast: Gen[AtLeast] = for { r <- ref; v <- literal } yield AtLeast(r, v)

  val atMost: Gen[AtMost] = for { r <- ref; v <- literal } yield AtMost(r, v)

  val greaterThan: Gen[GreaterThan] = for { r <- ref; v <- literal } yield GreaterThan(r, v)

  val lessThan: Gen[LessThan] = for { r <- ref; v <- literal } yield LessThan(r, v)

  val length: Gen[Int] = Gen.chooseNum(0, 999999999)

  val lengthAtLeast: Gen[LengthAtLeast] = for { r <- ref; n <- length } yield LengthAtLeast(r, n)

  val lengthAtMost: Gen[LengthAtMost] = for { r <- ref; n <- length } yield LengthAtMost(r, n)

  val regexpLike: Gen[RegexpLike] =
    for { r <- ref; p <- literalText } yield RegexpLike(r, p)

  val expression: Gen[Expression] =
    Gen.oneOf(
      member,
      inList,
      atLeast,
      atMost,
      greaterThan,
      lessThan,
      lengthAtLeast,
      lengthAtMost,
      regexpLike,
    )

  /** The same expression written with the whitespace Ossie allows but [[Expression.render]] does
    * not produce.
    */
  def spaced(e: Expression): String = e match {
    case Member(r, rel) => s"  ${r.written}.${Ref.quoted(rel)}\t"
    case InList(r, vs) =>
      val body = vs.map(_.render).mkString(" ,\t")
      s"\t${r.written}  \t IN \t( $body\t)  "
    case AtLeast(r, v) => s" ${r.written}\t>=  ${v.render} "
    case AtMost(r, v) => s"  ${r.written}  <=\t${v.render}\t"
    case GreaterThan(r, v) => s"\t${r.written}  >  ${v.render}"
    case LessThan(r, v) => s" ${r.written}<\t${v.render} "
    case LengthAtLeast(r, n) => s" LENGTH ( ${r.written}\t) >=\t$n "
    case LengthAtMost(r, n) => s"\tlength(${r.written} )  <= $n"
    case RegexpLike(r, p) => s" REGEXP_LIKE\t( ${r.written} ,\t${p.render}  ) "
  }
}
