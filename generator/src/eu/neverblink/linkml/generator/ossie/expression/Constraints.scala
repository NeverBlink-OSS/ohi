package eu.neverblink.linkml.generator.ossie.expression

import eu.neverblink.linkml.generator.ossie.expression.Expression.{
  AtLeast,
  AtMost,
  GreaterThan,
  LengthAtLeast,
  LengthAtMost,
  LessThan,
  RegexpLike,
}
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.LinkmlAny

/** The value constraints used in Ossie in the `requires` field, for one specific [[Ref]].
  *
  * This is the subset of the constraints that can be expressed in LinkML currently.
  */
private[ossie] final case class Constraints(
    minimumValue: Option[LinkmlAny] = None,
    maximumValue: Option[LinkmlAny] = None,
    minimumLength: Option[Int] = None,
    maximumLength: Option[Int] = None,
    pattern: Option[String] = None,
) {

  /** As expressions over [[ref]]. A bound that cannot be written as an Ossie value is left out. */
  def render(ref: Ref): Seq[Expression] = {
    val out = Seq.newBuilder[Expression]
    minimumValue.flatMap(Literal.fromLinkml).foreachFast(v => out += AtLeast(ref, v))
    maximumValue.flatMap(Literal.fromLinkml).foreachFast(v => out += AtMost(ref, v))
    minimumLength.foreachFast(n => out += LengthAtLeast(ref, n))
    maximumLength.foreachFast(n => out += LengthAtMost(ref, n))
    pattern.foreachFast(p => out += RegexpLike(ref, Literal.Text(p)))
    out.result()
  }
}

private[ossie] object Constraints {

  /** Gather constraints [[expressions]] place on [[ref]] into [[Constraints]].
    *
    * @param integer
    *   whether [[ref]] is an integer, so that `> 0` and `< 10` are the bounds `>= 1` and `<= 9`
    * @return
    *   the constraints, and the expressions that did not become one: those about another name,
    *   those that LinkML cannot express, and a second bound, length or pattern
    */
  def from(
      ref: Ref,
      expressions: Seq[Expression],
      integer: Boolean = false,
  ): (Constraints, Seq[Expression]) = {
    def step(value: Literal, by: Int) = value match {
      case n: Literal.Number if integer => n.plus(by)
      case _ => None
    }
    val unused = Seq.newBuilder[Expression]
    val constraints = expressions.foldLeft(Constraints()) { (acc, expression) =>
      expression match {
        case AtLeast(r, v) if r == ref && acc.minimumValue.isEmpty =>
          acc.copy(minimumValue = Some(v.toLinkml))
        case AtMost(r, v) if r == ref && acc.maximumValue.isEmpty =>
          acc.copy(maximumValue = Some(v.toLinkml))
        case GreaterThan(r, v) if r == ref && acc.minimumValue.isEmpty && step(v, 1).isDefined =>
          acc.copy(minimumValue = step(v, 1).map(_.toLinkml))
        case LessThan(r, v) if r == ref && acc.maximumValue.isEmpty && step(v, -1).isDefined =>
          acc.copy(maximumValue = step(v, -1).map(_.toLinkml))
        case LengthAtLeast(r, n) if r == ref && acc.minimumLength.isEmpty =>
          acc.copy(minimumLength = Some(n))
        case LengthAtMost(r, n) if r == ref && acc.maximumLength.isEmpty =>
          acc.copy(maximumLength = Some(n))
        case RegexpLike(r, p) if r == ref && acc.pattern.isEmpty =>
          acc.copy(pattern = Some(p.value))
        case _ =>
          unused += expression
          acc
      }
    }
    (constraints, unused.result())
  }
}
