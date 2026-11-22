package millbuild

import mill.scalanativelib.ScalaNativeModule
import millbuild.Version
import mill.*

/** Scala Native modules must be compiled one at a time. Otherwise, race conditions in scalac cause
  * random crashes.
  */
trait CommonScalaNativeModule extends ScalaNativeModule, JvmDocsOnly, mill.api.DynamicModule {
  def scalaNativeVersion = Version.scalaNative

  override def javaHome = Task {
    None
  }

  /** The MSVC headers refuse clangs older than they expect (STL1000), and the Windows runners ship
    * a newer MSVC than clang. Only the Scala Native runtime's `eh.cpp` includes them.
    */
  override def nativeCompileOptions = Task {
    val stlMismatch =
      if scala.util.Properties.isWin then Seq("-D_ALLOW_COMPILER_AND_STL_VERSION_MISMATCH")
      else Seq.empty
    super.nativeCompileOptions() ++ stlMismatch
  }

  override def moduleDirectChildren: Seq[mill.api.Module] =
    super.moduleDirectChildren.filterNot(_.moduleSegments.last.value == "scoverage")
}
