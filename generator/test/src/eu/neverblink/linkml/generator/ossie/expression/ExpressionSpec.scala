package eu.neverblink.linkml.generator.ossie.expression

import eu.neverblink.linkml.runtime.LinkmlAny
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Tests for the Ossie expression language parsing and rendering. */
class ExpressionSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {

  import Expression.*

  // How many times to run each property test (scalacheck)
  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 1000)

  private def parsed(text: String): Expression =
    Expression.parse(text).getOrElse(fail(s"did not parse: '$text'"))

  "rendering" should {
    "write a member as a dotted pair" in {
      Member(Ref("Person"), "name").render shouldBe "Person.name"
    }

    "quote a name that is not a bare identifier" in {
      Member(Ref("Familial relationship"), "related to").render shouldBe
        "\"Familial relationship\".\"related to\""
      Member(Ref("Person"), "say \"hi\"").render shouldBe "Person.\"say \"\"hi\"\"\""
      AtLeast(Ref("2nd"), Literal.Number("5")).render shouldBe "\"2nd\" >= 5"
    }

    "write an IN list with the values quoted" in {
      InList(Ref("Status"), Seq(Literal.Text("ALIVE"), Literal.Text("DEAD"))).render shouldBe
        "Status IN ('ALIVE', 'DEAD')"
    }

    "double an apostrophe inside a value" in {
      InList(Ref("Kind"), Seq(Literal.Text("it's"))).render shouldBe "Kind IN ('it''s')"
    }

    "write a bare number and a quoted string differently" in {
      AtLeast(Ref("Integer"), Literal.Number("5")).render shouldBe "Integer >= 5"
      AtLeast(Ref("Date"), Literal.Text("2020-01-01")).render shouldBe "Date >= '2020-01-01'"
    }

    "write a pattern as a REGEXP_LIKE call" in {
      RegexpLike(Ref("String"), Literal.Text("^[A-Z]$")).render shouldBe
        "REGEXP_LIKE(String, '^[A-Z]$')"
    }
  }

  "parsing" should {
    "read each shape back" in {
      parsed("Person.name") shouldBe Member(Ref("Person"), "name")
      parsed("Status IN ('ALIVE', 'DEAD')") shouldBe
        InList(Ref("Status"), Seq(Literal.Text("ALIVE"), Literal.Text("DEAD")))
      parsed("Integer >= 5") shouldBe AtLeast(Ref("Integer"), Literal.Number("5"))
      parsed("Integer <= -1") shouldBe AtMost(Ref("Integer"), Literal.Number("-1"))
      parsed("REGEXP_LIKE(S, '^x')") shouldBe RegexpLike(Ref("S"), Literal.Text("^x"))
      parsed("LENGTH(S) >= 2") shouldBe LengthAtLeast(Ref("S"), 2)
      parsed("length( S ) <= 10") shouldBe LengthAtMost(Ref("S"), 10)
    }

    "read a bound with the value first" in {
      parsed("1 <= Integer") shouldBe AtLeast(Ref("Integer"), Literal.Number("1"))
      parsed("'2020-01-01' >= Date") shouldBe AtMost(Ref("Date"), Literal.Text("2020-01-01"))
      parsed("0 < Integer") shouldBe GreaterThan(Ref("Integer"), Literal.Number("0"))
    }

    "read a strict bound" in {
      parsed("Integer > 0") shouldBe GreaterThan(Ref("Integer"), Literal.Number("0"))
      parsed("Integer < 10") shouldBe LessThan(Ref("Integer"), Literal.Number("10"))
      parsed("10 > Integer") shouldBe LessThan(Ref("Integer"), Literal.Number("10"))
    }

    "make a strict bound on an integer an inclusive one, and keep one on anything else" in {
      val strict = Seq(
        GreaterThan(Ref("N"), Literal.Number("0")),
        LessThan(Ref("N"), Literal.Number("-10")),
      )
      Constraints.from(Ref("N"), strict, integer = true) shouldBe
        (Constraints(Some(LinkmlAny("1")), Some(LinkmlAny("-11"))), Nil)
      Constraints.from(Ref("N"), strict) shouldBe (Constraints(), strict)
      val fraction = Seq(GreaterThan(Ref("N"), Literal.Number("0.5")))
      Constraints.from(Ref("N"), fraction, integer = true) shouldBe (Constraints(), fraction)
    }

    "read equality comparisons joined by OR as an IN list" in {
      parsed("Code == 'A' OR Code == 'B' OR Code == 'C'") shouldBe
        InList(Ref("Code"), Seq(Literal.Text("A"), Literal.Text("B"), Literal.Text("C")))
      parsed("Code == 'A'") shouldBe InList(Ref("Code"), Seq(Literal.Text("A")))
    }

    "refuse equality comparisons on different names" in {
      Expression.parse("Code == 'A' OR Other == 'B'") shouldBe None
    }

    "unescape a doubled apostrophe" in {
      parsed("Kind IN ('it''s')") shouldBe InList(Ref("Kind"), Seq(Literal.Text("it's")))
    }

    "read an empty IN list" in {
      parsed("Kind IN ()") shouldBe InList(Ref("Kind"), Nil)
    }

    "tell a quoted bound from a bare one" in {
      parsed("Date >= '2020-01-01'") shouldBe AtLeast(Ref("Date"), Literal.Text("2020-01-01"))
      parsed("Integer >= 5") shouldBe AtLeast(Ref("Integer"), Literal.Number("5"))
    }

    "report the name it actually found" in {
      parsed("IntegerX >= 5").ref shouldBe Ref("IntegerX")
      val other = parsed("IntegerX >= 5")
      Constraints.from(Ref("Integer"), Seq(other)) shouldBe (Constraints(), Seq(other))
    }

    "read keywords in any case" in {
      parsed("Status in ('A')") shouldBe InList(Ref("Status"), Seq(Literal.Text("A")))
      parsed("Code == 'A' or Code == 'B'") shouldBe
        InList(Ref("Code"), Seq(Literal.Text("A"), Literal.Text("B")))
      parsed("regexp_like(S, '^x')") shouldBe RegexpLike(Ref("S"), Literal.Text("^x"))
    }

    "read SQL's = like ==" in {
      parsed("Code = 'A' OR Code == 'B'") shouldBe
        InList(Ref("Code"), Seq(Literal.Text("A"), Literal.Text("B")))
    }

    "refuse a shape it does not write" in {
      Expression.parse("Integer <> 3") shouldBe None
      Expression.parse("NOT (Status IN ('A'))") shouldBe None
      Expression.parse("Integer == 3") shouldBe None
      Expression.parse("COUNT(Integer) > 1") shouldBe None
      Expression.parse("Integer >= ") shouldBe None
      Expression.parse("") shouldBe None
    }

    "refuse an unterminated literal" in {
      Expression.parse("Kind IN ('a)") shouldBe None
      Expression.parse("REGEXP_LIKE(S, 'x)") shouldBe None
    }

    "refuse a bound that is neither a number nor a literal" in {
      Expression.parse("Integer >= abc") shouldBe None
    }

    "read a quoted name, with a doubled quote as an escaped one" in {
      parsed("Person.\"related to\"") shouldBe Member(Ref("Person"), "related to")
      parsed("\"say \"\"hi\"\"\" >= 5") shouldBe AtLeast(Ref("say \"hi\""), Literal.Number("5"))
    }

    "refuse a name that is neither a bare identifier nor quoted" in {
      Expression.parse("Order.line.nr") shouldBe None
      Expression.parse("'Order'.nr") shouldBe None
      Expression.parse("Order line >= 5") shouldBe None
      Expression.parse("\"Order.nr") shouldBe None
    }

    "not be fooled by REGEXP_LIKE used as a name" in {
      parsed("REGEXP_LIKE.x") shouldBe Member(Ref("REGEXP_LIKE"), "x")
    }
  }

  "constraints" should {
    "keep the first of two bounds or patterns, and give back the rest" in {
      val in = parsed("Code IN ('A')")
      val regex = parsed("REGEXP_LIKE(Code, 'x')")
      val regex2 = parsed("REGEXP_LIKE(Code, 'y')")
      val min = parsed("Code >= 1")
      val min2 = parsed("Code >= 2")
      Constraints.from(Ref("Code"), Seq(in, min, regex, min2, regex2)) shouldBe
        (
          Constraints(minimumValue = Some(LinkmlAny("1")), pattern = Some("x")),
          Seq(in, min2, regex2),
        )
    }
  }

  "a LinkML value" should {
    "go in bare when it is a number, quoted when it is not" in {
      Literal.fromLinkml(LinkmlAny("5")) shouldBe Some(Literal.Number("5"))
      Literal.fromLinkml(LinkmlAny("abc")) shouldBe Some(Literal.Text("abc"))
    }

    "go in bare even when the YAML had it quoted" in {
      Literal.fromLinkml(LinkmlAny("\"5\"")) shouldBe Some(Literal.Number("5"))
    }

    "unescape the YAML rather than just trimming the quotes" in {
      Literal.fromLinkml(LinkmlAny("\"a\\\"b\"")) shouldBe Some(Literal.Text("a\"b"))
      Literal.fromLinkml(LinkmlAny("\"a\\\\b\"")) shouldBe Some(Literal.Text("a\\b"))
    }

    "fold a wrapped scalar the way YAML does" in {
      Literal.fromLinkml(LinkmlAny("a\nb")) shouldBe Some(Literal.Text("a b"))
    }

    "be refused when it cannot be written as one" in {
      Literal.fromLinkml(LinkmlAny("")) shouldBe None
      Literal.fromLinkml(LinkmlAny("\"a\\nb\"")) shouldBe None
      Literal.fromLinkml(LinkmlAny("[1, 2]")) shouldBe None
    }
  }

  "the round trip" should {
    "parse back every expression it wrote" in {
      forAll(ExpressionGen.expression) { e =>
        Expression.parse(e.render) shouldBe Some(e)
      }
    }

    "accept whitespace it would not have written itself" in {
      forAll(ExpressionGen.expression) { e =>
        Expression.parse(ExpressionGen.spaced(e)) shouldBe Some(e)
      }
    }

    "parse back every literal that survives LinkML" in {
      forAll(ExpressionGen.linkmlSafeLiteral) { l =>
        Literal.fromLinkml(l.toLinkml) shouldBe Some(l)
      }
    }

    "keep constraints attached to the name they were written over" in {
      forAll(ExpressionGen.ref, ExpressionGen.ref, ExpressionGen.linkmlSafeLiteral) { (a, b, v) =>
        val written = Constraints(minimumValue = Some(v.toLinkml)).render(a)
        val (read, _) = Constraints.from(a, written.map(_.render).flatMap(Expression.parse))
        read.minimumValue.map(_.value) shouldBe Some(v.toLinkml.value)
        if a != b then
          Constraints.from(b, written.map(_.render).flatMap(Expression.parse))._1 shouldBe
            Constraints()
      }
    }

    "answer rather than throw, whatever it is handed" in {
      forAll(Gen.oneOf(Gen.asciiStr, Gen.alphaStr, Gen.identifier)) { s =>
        noException should be thrownBy Expression.parse(s)
      }
    }
  }
}
