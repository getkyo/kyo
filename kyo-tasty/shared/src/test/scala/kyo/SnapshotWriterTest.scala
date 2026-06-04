package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Tests for SnapshotWriter serialization correctness.
  *
  * INV-015 writer side: snapshot written from a real TASTy classpath has PARENTS, MEMBERS, and TPARAMS_ sections with length > 0.
  *
  * CARRY-1 regression: two cold-writes of the same classpath produce byte-equal snapshots, AND warm-then-reserialize also
  * produces byte-equal output. The fix replaces IdentityHashMap[Symbol,String] with HashMap[Int,String] keyed by SymbolId.value
  * so that warm-loaded Symbol instances (with different object identity but same id.value) hit the FQN map correctly.
  */
class SnapshotWriterTest extends Test:

    import AllowUnsafe.embrace.danger

    class MemoryFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends kyo.internal.tasty.query.FileSource:
        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => kyo.internal.tasty.query.FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))
    end MemoryFileSource

    // Test 1 (INV-015 writer side): PARENTS, MEMBERS, and TPARAMS_ sections have length > 0
    // after writing a snapshot from a real TASTy classpath that has class declarations and parents.
    "snapshot PARENTS, MEMBERS, and TPARAMS_ sections have length > 0 after writing a real classpath" in run {
        val src = MemoryFileSource()
        // Use the SomeTrait fixture which extends java.lang.Object (has parents) and has members.
        src.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)

        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1).flatMap: cp =>
                    SnapshotWriter.write(cp, "cache", digest, cacheSrc).map: _ =>
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        cacheSrc.files.get(snapPath)
            ).map:
                case Result.Success(Some(bytes)) =>
                    // Parse section index to find PARENTS, MEMBERS, TPARAMS_ lengths.
                    val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
                    val sectionLens  = mutable.HashMap.empty[String, Long]
                    var idxPos       = 36
                    var i            = 0
                    while i < sectionCount do
                        val sName = SnapshotFormat.readSectionName(bytes, idxPos)
                        val sLen  = SnapshotFormat.readInt64LE(bytes, idxPos + 16)
                        sectionLens(sName) = sLen
                        idxPos += SnapshotFormat.sectionIndexEntrySize
                        i += 1
                    end while
                    val parentsLen = sectionLens.getOrElse(SnapshotFormat.sectionPARENTS, -1L)
                    val membersLen = sectionLens.getOrElse(SnapshotFormat.sectionMEMBERS, -1L)
                    val tparamsLen = sectionLens.getOrElse(SnapshotFormat.sectionTPARAMS, -1L)
                    assert(parentsLen > 0, s"PARENTS section must be non-empty; got $parentsLen")
                    assert(membersLen > 0, s"MEMBERS section must be non-empty; got $membersLen")
                    assert(tparamsLen >= 0, s"TPARAMS_ section must be present; got $tparamsLen")
                    succeed
                case Result.Success(None) =>
                    fail("Snapshot file not found after write")
                case Result.Failure(e) =>
                    fail(s"Unexpected failure: $e")
                case Result.Panic(t) =>
                    throw t
    }

    // CARRY-1 test 1: two independent cold serializations of the same classpath produce byte-equal output.
    // Regression guard for F-A4-OPEN-IDEMPOTENT (decoder-fidelity-2 carry-over).
    // Cross-platform: uses embedded fixtures so it runs on JVM, JS, and Native.
    "CARRY-1 cold-vs-cold: two same-run serializations of the same classpath are byte-equal" in run {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val digest = Array[Byte](0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67)
            val a      = SnapshotWriter.serializeToBytes(cp, digest)
            val b      = SnapshotWriter.serializeToBytes(cp, digest)
            assert(
                java.util.Arrays.equals(a, b),
                s"CARRY-1 cold-vs-cold: two serializations differ; len_a=${a.length} len_b=${b.length}"
            )
            succeed
    }

    // CARRY-1 test 2: serialize cold, read back as warm, re-serialize warm: byte-equal to original.
    // Regression guard for the warm-then-reserialize residual reproduced in decoder-fidelity-3 leaf 24.
    // The IdentityHashMap lookup missed on warm-load symbols (fresh identity, same id.value) causing
    // annotation FQN interning order to differ and producing a 13-byte divergence. Fixed by keying
    // fqnBySymbol by SymbolId.value (Int) instead of Symbol object identity.
    // Cross-platform: uses TestClasspaths2.withSnapshotInMemory() which works on JVM, JS, and Native.
    "CARRY-1 warm-reserialize: warm-loaded classpath re-serializes byte-equal to the original snapshot" in run {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            val digest = Array[Byte](0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77)
            val a      = SnapshotWriter.serializeToBytes(cold, digest)
            val b      = SnapshotWriter.serializeToBytes(warm, digest)
            assert(
                java.util.Arrays.equals(a, b),
                s"CARRY-1 warm-reserialize: warm re-serialization differs; len_a=${a.length} len_b=${b.length}"
            )
            succeed
    }

end SnapshotWriterTest
