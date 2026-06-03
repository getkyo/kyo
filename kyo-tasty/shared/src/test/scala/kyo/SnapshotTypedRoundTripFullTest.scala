package kyo

import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Plan-mandated tests for Phase 08 (leaf 173): full snapshot round-trip on a multi-file fixture.
  *
  * Uses all available embedded fixture TASTy files to produce a larger classpath than the Phase 02 single-file test. Verifies that typed
  * subtype tags, ids, names, and flags all survive a write-then-read snapshot cycle.
  *
  * Pins: INV-011, INV-012, INV-013.
  */
class SnapshotTypedRoundTripFullTest extends Test:

    import AllowUnsafe.embrace.danger

    final class MemoryFileSource(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty) extends FileSource:
        def add(p: String, b: Array[Byte]): Unit = files(p) = b
        def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(p) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(p))
        def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) = Sync.defer(files(p) = b)
        def rename(f: String, t: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(f) match
                case Some(b) => Sync.defer { files.remove(f); files(t) = b }
                case None    => Abort.fail(TastyError.SnapshotIoError(s"$f not found"))
        def mkdirs(p: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Kyo.unit
        def list(d: String, sfx: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer(Chunk.from(files.keys.filter(k => k.startsWith(d + "/") && sfx.exists(k.endsWith)).toSeq))
        def exists(p: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(p) || files.keys.exists(_.startsWith(p + "/")))
        def stat(p: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer(FileSource.FileStat(0L, files.get(p).map(_.length.toLong).getOrElse(0L)))
    end MemoryFileSource

    private def openMultiFileCp(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        val src = MemoryFileSource()
        // Load all available embedded fixture TASTy files for a richer classpath.
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.add("root/SomeObject.tasty", kyo.fixtures.Embedded.someObjectTasty)
        src.add("root/SomeTrait.tasty", kyo.fixtures.Embedded.someTraitTasty)
        src.add("root/GenericBox.tasty", kyo.fixtures.Embedded.genericBoxTasty)
        src.add("root/Outer.tasty", kyo.fixtures.Embedded.outerTasty)
        src.add("root/SomeCaseClass.tasty", kyo.fixtures.Embedded.someCaseClassTasty)
        src.add("root/Color.tasty", kyo.fixtures.Embedded.colorTasty)
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)
    end openMultiFileCp

    // ── Leaf 173: full-roundtrip-multi-jar ───────────────────────────────────
    // Given: multiple embedded fixture TASTy files loaded into a Classpath
    // When: write snapshot; reload; compare typed subtype, id, name, flags
    // Then: every pair matches; cp.symbols.size == reloaded.symbols.size
    // Pins: INV-011, INV-012, INV-013
    "Leaf 173: full snapshot round-trip preserves typed subtypes on multi-file fixture" in run {
        val cacheSrc = MemoryFileSource()
        val digest   = Array[Byte](0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x7e)
        Scope.run:
            Abort.run[TastyError](
                openMultiFileCp.flatMap: coldCp =>
                    SnapshotWriter.write(coldCp, "cache", digest, cacheSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"cache/$hex.krfl"
                        SnapshotReader.read(snapPath, cacheSrc).map: warmCp =>
                            (coldCp, warmCp)
            ).map:
                case Result.Success((coldCp, warmCp)) =>
                    val cold = coldCp.symbols
                    val warm = warmCp.symbols
                    assert(
                        cold.length == warm.length,
                        s"Symbol count mismatch: cold=${cold.length} warm=${warm.length}"
                    )
                    // Lower bound: TestClasspaths.withClasspath loads 70+ fixture files on JS/Native and
                    // additionally the JVM stdlib on JVM. A clean round-trip must produce at least one
                    // symbol per fixture file; values below the bound indicate fixture loading failed.
                    assert(
                        cold.length >= 70,
                        s"Round-trip produced too few symbols (expected >= 70 from fixtures): got ${cold.length}"
                    )
                    var i = 0
                    while i < cold.length do
                        val c = cold(i)
                        val w = warm(i)
                        assert(
                            c.getClass.getSimpleName == w.getClass.getSimpleName,
                            s"Symbol[$i] type mismatch: cold=${c.getClass.getSimpleName} warm=${w.getClass.getSimpleName}"
                        )
                        assert(c.id == w.id, s"Symbol[$i] id mismatch: cold=${c.id} warm=${w.id}")
                        assert(
                            c.name.asString == w.name.asString,
                            s"Symbol[$i] name mismatch: cold=${c.name.asString} warm=${w.name.asString}"
                        )
                        assert(
                            c.flags.bits == w.flags.bits,
                            s"Symbol[$i] flags mismatch: cold=${c.flags.bits} warm=${w.flags.bits}"
                        )
                        i += 1
                    end while
                    succeed
                case Result.Failure(e) => fail(s"Unexpected TastyError in round-trip test: $e")
                case Result.Panic(t)   => throw t
    }

end SnapshotTypedRoundTripFullTest
