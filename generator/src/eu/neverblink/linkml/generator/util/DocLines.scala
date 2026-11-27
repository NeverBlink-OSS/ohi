package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.metamodel.CommonMetadata
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.MetadataLanguage
import eu.neverblink.linkml.runtime.StringUtils.splitLines
import eu.neverblink.linkml.schemaview.{AttributeView, ClassReferenceAttributeView, ClassView}

/** The text of a doc comment on a generated element, for the code generators. */
object DocLines {

  /** The element's title and description in the requested language, or their fallback text, as
    * `title: description` or whichever of the two it has, split into lines. Empty if it has
    * neither.
    */
  def apply(element: CommonMetadata)(using MetadataLanguage): Seq[String] = {
    val title = element.title.localized
    val description = element.description.localized
    val text = title match {
      case Some(t) =>
        description match {
          case Some(d) => Some(s"$t: $d")
          case _ => title
        }
      case _ => description
    }
    text.foldFast(Nil: Seq[String])(t => splitLines(t.strip))
  }

  /** The doc of a field that refers to objects by their identifiers, such as `Reference to Person
    * or Organization`, or None if the field holds no references.
    */
  def reference(attribute: AttributeView, className: ClassView => String): Option[String] = {
    val alternatives = attribute.rangeAlternatives
    val classes = (if alternatives.isEmpty then Seq(attribute) else alternatives).collect {
      case ref: ClassReferenceAttributeView => className(ref.classView)
    }
    Option.when(classes.nonEmpty)(classes.mkString("Reference to ", " or ", ""))
  }
}
