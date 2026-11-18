package eu.neverblink.linkml.generator.stats

import eu.neverblink.linkml.generator.options.{JsonOutputFormat, StatsGeneratorOptions}
import eu.neverblink.linkml.generator.DocumentGenerator
import eu.neverblink.linkml.generator.stats.model.*
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.metamodel.CommonMetadata
import eu.neverblink.linkml.runtime.FastUtils.foreachFast
import eu.neverblink.linkml.runtime.{LocalizedText, MultilingualText, PlainText}
import eu.neverblink.linkml.schemaview.{ElementView, SchemaView}
import eu.neverblink.linkml.yaml.LinkmlYamlCodec

import java.io.OutputStream
import scala.collection.immutable.VectorMap
import scala.collection.mutable

/** Statistics about a schema: how many elements of each kind it has, how many of them are
  * documented, deprecated or mapped, which languages its text is in, and how many schemas it
  * imports. Follows the `schema-stats.yaml` model.
  *
  * Which elements it counts is set by `onlyClassesFromRootSchema`, the same as in the SHACL and
  * RDFS generators. The `pruningMode` option is deprecated and ignored.
  */
final class StatsGenerator(using sv: SchemaView) extends DocumentGenerator[StatsGeneratorOptions] {
  import StatsGenerator.*

  override protected def defaultOptions: StatsGeneratorOptions = StatsGeneratorOptions()

  def generate(options: StatsGeneratorOptions = StatsGeneratorOptions()): SchemaStats = {
    val root = sv.root
    val onlyRoot = options.onlyClassesFromRootSchema
    // Like the SHACL and RDFS generators: an element is in scope if the root schema defines it,
    // or always if the option is off. `pruningMode` and `treeRoot` are deprecated and ignored.
    def inScope(view: ElementView[?, ?]): Boolean = !onlyRoot || view.definingSchema.eq(root)
    val languages = mutable.HashMap.empty[String, Int]

    def count(kind: Kind, cm: CommonMetadata): Unit = {
      kind.add(cm)
      countText(cm, languages)
    }

    val classes = Kind()
    val slots = Kind()
    val attributes = Kind()
    val enums = Kind()
    val permissibleValues = Kind()
    val types = Kind()
    val subsets = Kind()
    val functions = Kind()

    sv.classes.foreachEntry { (_, cv) =>
      if (!cv.isAny && inScope(cv)) {
        count(classes, cv.cls)
        cv.cls.attributes.foreachEntry((_, attribute) => count(attributes, attribute))
      }
    }
    sv.slotDefinitions.foreachEntry { (_, slot) =>
      if (inScope(slot)) count(slots, slot.slot)
    }
    sv.enums.foreachEntry { (_, ev) =>
      if (inScope(ev)) {
        count(enums, ev._enum)
        ev._enum.permissibleValues.foreachEntry { (_, pv) =>
          count(permissibleValues, pv)
          if (pv.meaning.isDefined) permissibleValues.withMeaning += 1
        }
      }
    }
    sv.types.foreachEntry { (_, tv) =>
      if (inScope(tv)) count(types, tv._type)
    }
    sv.functions.foreachEntry { (_, fv) =>
      if (inScope(fv)) count(functions, fv.function)
    }
    sv.subsets.foreachEntry { (_, sub) =>
      if (inScope(sub)) count(subsets, sub.subset)
    }
    countText(root, languages)

    SchemaStats(
      elements = ElementStats(
        classes = classes.result(),
        slots = slots.result(),
        attributes = attributes.result(),
        enums = enums.result(),
        permissibleValues = permissibleValues.result(withMeaning = true),
        types = types.result(),
        subsets = subsets.result(),
        functions = functions.result(),
      ),
      imports = ImportStats(direct = root.imports.size, transitive = sv.schemas.size - 1),
      languages = VectorMap.from(
        languages.toSeq.sortBy(_._1).map((tag, n) => tag -> LanguageCount(n, tag)),
      ),
      metadata = MetadataStats(
        hasDescription = hasText(root.description),
        hasLicense = root.license.exists(hasText),
        hasTitle = hasText(root.title),
        hasVersion = root.version.exists(hasText),
      ),
      onlyClassesFromRootSchema = onlyRoot,
      schemaId = root.id,
      schemaName = root.name.toString,
    )
  }

  /** The statistics as YAML. */
  override def serialize(options: StatsGeneratorOptions = StatsGeneratorOptions()): String =
    serialize(options, JsonOutputFormat.Yaml)

  def serialize(options: StatsGeneratorOptions, format: JsonOutputFormat): String =
    JsonUtil.write(StatsGenerator.codec.encode(generate(options)), format)

  /** Write the statistics as YAML. */
  override def writeTo(
      out: OutputStream,
      options: StatsGeneratorOptions = StatsGeneratorOptions(),
  ): Unit =
    writeTo(out, options, JsonOutputFormat.Yaml)

  def writeTo(out: OutputStream, options: StatsGeneratorOptions, format: JsonOutputFormat): Unit =
    JsonUtil.write(StatsGenerator.codec.encode(generate(options)), format, out)
}

object StatsGenerator {

  given codec: LinkmlYamlCodec[SchemaStats] = LinkmlYamlCodec.derived

  /** Counts for one kind of element. */
  private final class Kind {
    var count = 0
    var withTitle = 0
    var withDescription = 0
    var deprecated = 0
    var withMappings = 0
    var exact = 0
    var close = 0
    var broad = 0
    var narrow = 0
    var related = 0
    var generic = 0
    var withMeaning = 0

    def add(cm: CommonMetadata): Unit = {
      count += 1
      if (hasText(cm.title)) withTitle += 1
      if (hasText(cm.description)) withDescription += 1
      if (cm.deprecated.isDefined) deprecated += 1
      if (cm.exactMappings.nonEmpty) exact += 1
      if (cm.closeMappings.nonEmpty) close += 1
      if (cm.broadMappings.nonEmpty) broad += 1
      if (cm.narrowMappings.nonEmpty) narrow += 1
      if (cm.relatedMappings.nonEmpty) related += 1
      if (cm.mappings.nonEmpty) generic += 1
      if (
        cm.exactMappings.nonEmpty || cm.closeMappings.nonEmpty || cm.broadMappings.nonEmpty ||
        cm.narrowMappings.nonEmpty || cm.relatedMappings.nonEmpty || cm.mappings.nonEmpty
      ) withMappings += 1
    }

    def result(withMeaning: Boolean = false): ElementKindStats = ElementKindStats(
      count = count,
      deprecated = deprecated,
      mappings = MappingStats(
        broad = broad,
        close = close,
        exact = exact,
        generic = generic,
        narrow = narrow,
        related = related,
      ),
      withDescription = withDescription,
      withMappings = withMappings,
      withMeaning = if withMeaning then Some(this.withMeaning) else None,
      withTitle = withTitle,
    )
  }

  /** True if [[s]] is not empty or only whitespace. */
  private def hasText(s: String): Boolean = !s.isBlank

  /** True if [[text]] has non-blank text in at least one language. */
  private def hasText(text: LocalizedText): Boolean = text match {
    case PlainText(value) => hasText(value)
    case MultilingualText(mapping) => mapping.exists((_, value) => hasText(value))
  }

  /** True if [[text]] is set and is not blank: an empty string counts as missing. */
  private def hasText(text: Option[LocalizedText]): Boolean = text.exists(hasText)

  /** Add the non-blank strings of [[cm]] to [[counts]], by language. */
  private def countText(cm: CommonMetadata, counts: mutable.HashMap[String, Int]): Unit = {
    def add(text: LocalizedText): Unit = text match {
      case PlainText(value) =>
        if (hasText(value)) counts.updateWith("")(n => Some(n.getOrElse(0) + 1))
      case MultilingualText(mapping) =>
        mapping.foreachEntry { (tag, value) =>
          if (hasText(value)) counts.updateWith(tag)(n => Some(n.getOrElse(0) + 1))
        }
    }
    cm.title.foreachFast(add)
    cm.description.foreachFast(add)
    cm.deprecated.foreachFast(add)
    cm.aliases.foreach(add)
    cm.comments.foreach(add)
    cm.keywords.foreach(add)
    cm.notes.foreach(add)
    cm.altDescriptions.foreachEntry((_, alt) => add(alt.altDescriptionText))
    cm.examples.foreach(_.valueDescription.foreachFast(add))
    cm.structuredAliases.foreach(countText(_, counts))
  }
}
