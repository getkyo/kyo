package kyo.internal

import kyo.*
import kyo.internal.tasty.query.FileSource

/** INV-009 verification probe. A FileSource that throws on every IO method,
  * carrying an A1-probe sentinel message. Used by Inv009BehavioralTest to
  * prove that pure Tasty.* query methods perform zero IO and that the four
  * named effectful sites are the ONLY surfaces that read/write the probe.
  *
  * Every method raises a RuntimeException (not Abort.fail) so the sentinel
  * surfaces as Result.Panic at effect boundaries, bypassing any Abort.run
  * wrappers inside ClasspathOrchestrator that catch TastyError failures.
  *
  * Scaladoc: 8-35 lines.
  */
final private[kyo] class TestProbeFileSource extends FileSource:

    private val sentinel: String = "A1 probe: no IO permitted"

    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (read $path)")

    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (write $path)")

    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (rename $from -> $to)")

    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (mkdirs $path)")

    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (list $dir)")

    def exists(path: String)(using Frame): Boolean < Sync =
        throw new RuntimeException(s"$sentinel (exists $path)")

    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (stat $path)")

end TestProbeFileSource
