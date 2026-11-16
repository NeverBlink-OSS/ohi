package eu.neverblink.linkml.generator

import _root_.scala.collection.mutable

import eu.neverblink.linkml.generator.importwarnings.{ImportReport, ImportWarning}

/** What an importer could not handle during the import process, in the order it was found. */
final class ImportWarnings {

  private val warnings = mutable.ArrayBuffer.empty[ImportWarning]

  /** Adds a warning, with its `kind` and `detail` filled in. */
  def warn(warning: ImportWarning): Unit = warnings.addOne(warning.infer())

  /** The warnings so far, as a report. */
  def report: ImportReport = ImportReport(warnings.toVector)
}

object ImportWarnings {

  /** How many details of each kind [[lines]] shows. */
  private val shownDetails = 5

  /** One line per kind, in the order of each kind's first warning, with the first few distinct
    * details. Grouping by kind keeps the text short.
    *
    * The warnings must be inferred
    */
  def lines(report: ImportReport): Seq[String] = {
    val byKind = mutable.LinkedHashMap.empty[String, mutable.LinkedHashSet[String]]
    report.warnings.foreach { w =>
      byKind.getOrElseUpdate(w.kind.get, mutable.LinkedHashSet.empty).addOne(w.detail.get)
    }
    byKind.iterator.map { (kind, details) =>
      val line = new java.lang.StringBuilder(kind).append(" (").append(details.size).append("): ")
      var i = 0
      details.iterator.take(shownDetails).foreach { d =>
        if (i > 0) line.append("; ")
        line.append(d)
        i += 1
      }
      if (details.size > shownDetails) {
        line.append("; and ").append(details.size - shownDetails).append(" more")
      }
      line.toString
    }.toVector
  }
}
