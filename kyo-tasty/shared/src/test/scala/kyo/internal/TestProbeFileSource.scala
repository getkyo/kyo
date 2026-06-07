package kyo.internal

import kyo.*
import kyo.internal.tasty.query.FileSource

/** IO-isolation verification probe: a FileSource that throws on every IO method.
  *
  * Used to prove that pure Tasty.* query methods perform zero IO. Every method raises a
  * RuntimeException (not Abort.fail) so the sentinel surfaces as Result.Panic at effect
  * boundaries, bypassing any Abort.run wrappers inside ClasspathOrchestrator.
  */
final private[kyo] class TestProbeFileSource extends FileSource:

    private val sentinel: String = "A1 probe: no IO permitted"

    def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (read $path)")

    def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (write $path)")

    def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (rename $from -> $to)")

    override def delete(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        // behavioural guard. After the evictOlderThan path issues `delete`,
        // not `rename`. The probe surfaces the call as a sentinel exception so Inv009BehavioralTest can
        // assert site-4 reaches exactly delete and zero rename calls.
        throw new RuntimeException(s"$sentinel (delete $path)")

    def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (mkdirs $path)")

    def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (list $dir)")

    def exists(path: String)(using Frame): Boolean < Sync =
        throw new RuntimeException(s"$sentinel (exists $path)")

    def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
        throw new RuntimeException(s"$sentinel (stat $path)")

end TestProbeFileSource
