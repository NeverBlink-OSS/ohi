package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.LinkmlAny
import eu.neverblink.linkml.runtime.StringUtils.hexDigit

import scala.util.control.NonFatal

/** Literals in the generated code, for the generators of languages with C-like literals. */
object Literals {

  /** A double-quoted string literal. `"`, `\` and line breaks get a backslash, and each code point
    * that `unicodeEscape` accepts is written as `\uXXXX`, one per UTF-16 unit.
    */
  def string(value: String, unicodeEscape: Int => Boolean): String = {
    val sb = new java.lang.StringBuilder(value.length + 2)
    sb.append('"')
    var i = 0
    while (i < value.length) {
      val cp = value.codePointAt(i)
      cp match {
        case '"' => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case _ if unicodeEscape(cp) =>
          Character.toChars(cp).foreach { c =>
            sb.append('\\').append('u').append(hexDigit(c >> 12)).append(hexDigit(c >> 8))
              .append(hexDigit(c >> 4)).append(hexDigit(c))
          }
        case _ => sb.appendCodePoint(cp)
      }
      i += Character.charCount(cp)
    }
    sb.append('"').toString
  }

  /** A bound as a decimal number, or None if it is not a number. */
  def decimal(value: Option[LinkmlAny]): Option[String] =
    try value.mapFast(v => BigDecimal(v.value.trim).bigDecimal.toPlainString)
    catch {
      case ex if NonFatal(ex) => None
    }
}
