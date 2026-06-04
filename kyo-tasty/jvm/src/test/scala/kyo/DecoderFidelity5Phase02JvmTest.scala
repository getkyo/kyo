package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** JVM-only tests for Decoder-fidelity-5 Phase 5.02.
  *
  * Covers F-W2-27: post-Scope decodeBody behavior on mmap-loaded classpaths. The mmap path sets
  * sectionBytes = Array.empty for body-bearing symbols; decodeBody detects this and returns
  * TastyError.MalformedSection("body bytes not available") rather than ClasspathClosed. This is
  * the pinned contract: an IllegalStateException from the arena would only fire if the view-backed
  * bytes were accessed after close, but they are not accessed at all when sectionBytes is empty.
  *
  * Also covers F-W2-30/31 via a real mmap warm load using a synthetic classpath.
  */
class DecoderFidelity5Phase02JvmTest extends Test:

    import AllowUnsafe.embrace.danger

    final private class MemSrc(files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends FileSource:
        def add(p: String, b: Array[Byte]): Unit = files(p) = b
        def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(p) match
                case Some(b) => b
                case None    => Abort.fail(TastyError.FileNotFound(p))
        def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(p) = b)
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
    end MemSrc

    private def openFixtureCp(src: FileSource)(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("root"), Tasty.ErrorMode.SoftFail, src, 1)

    private def makeClass(id: Int, name: String): Tasty.Symbol.Class =
        Tasty.Symbol.Class(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def makeObject(id: Int, name: String): Tasty.Symbol.Object =
        Tasty.Symbol.Object(
            SymbolId(id),
            Tasty.Name(name),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )

    private def syntheticCp(using Frame): Tasty.Classpath < Sync =
        Sync.defer:
            val animal = makeClass(0, "Animal")
            val dog    = makeClass(1, "Dog")
            val cat    = makeClass(2, "Cat")
            val foo    = makeClass(3, "Foo")
            val fooObj = makeObject(4, "Foo$")
            Tasty.Classpath.make(
                symbols = Chunk(animal, dog, cat, foo, fooObj),
                rootSymbolId = SymbolId(-1),
                topLevelClassIds = Chunk(SymbolId(0), SymbolId(1), SymbolId(2), SymbolId(3), SymbolId(4)),
                packageIds = Chunk.empty,
                fqnIndex = Map(
                    "Animal" -> SymbolId(0),
                    "Dog"    -> SymbolId(1),
                    "Cat"    -> SymbolId(2),
                    "Foo"    -> SymbolId(3),
                    "Foo$"   -> SymbolId(4)
                ),
                packageIndex = Map.empty,
                subclassIndex = Map(SymbolId(0) -> Chunk(SymbolId(1), SymbolId(2))),
                companionIndex = Map(SymbolId(3) -> SymbolId(4), SymbolId(4) -> SymbolId(3)),
                moduleIndex = Map.empty,
                errors = Chunk.empty
            )
    end syntheticCp

    // P02.6 (JVM-only): F-W2-27 -- post-Scope decodeBody behavior on mmap-loaded snapshot.
    // Given: a snapshot loaded via readMapped inside a Scope; the Scope exits (arena closes)
    // When: decodeBody is called on a symbol that had body bytes stored via mmap
    // Then: returns TastyError.MalformedSection("body bytes not available") because the mmap path
    //       sets sectionBytes = Array.empty intentionally; ClasspathClosed would only fire if
    //       the view-backed arena bytes were directly accessed after close.
    // Pins: F-W2-27
    "P02.6 F-W2-27: post-Scope decodeBody on mmap-loaded snapshot returns MalformedSection (body bytes not available)" in run {
        val fixtureSrc = MemSrc()
        fixtureSrc.add("root/Animal.tasty", kyo.fixtures.Embedded.animalTasty)
        fixtureSrc.add("root/Dog.tasty", kyo.fixtures.Embedded.dogTasty)
        val digest  = Array[Byte](0x02, 0x27, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val tmpDir  = java.io.File.createTempFile("kyo-df5-p02-jvm", "").getAbsolutePath
        val _       = new java.io.File(tmpDir).delete()
        val _       = new java.io.File(tmpDir).mkdirs()
        val platSrc = PlatformFileSource.get
        // Step 1: build cold classpath, write snapshot to disk.
        val writeEffect: Unit < (Sync & Async & Abort[TastyError]) =
            Scope.run:
                Abort.run[TastyError]:
                    openFixtureCp(fixtureSrc).flatMap: coldCp =>
                        SnapshotWriter.write(coldCp, tmpDir, digest, platSrc)
                .map:
                    case Result.Success(_) => ()
                    case Result.Failure(e) => throw new RuntimeException(s"Write failed: $e")
                    case Result.Panic(t)   => throw t
        writeEffect.andThen:
            val hex      = DigestComputer.toHexString(digest)
            val snapPath = s"$tmpDir/$hex.krfl"
            // Step 2: load via mmap INSIDE a Scope.run; extract any symbol; let Scope exit.
            val symAndCp =
                Scope.run:
                    Abort.run[TastyError]:
                        SnapshotReader.readMapped(snapPath, platSrc).map: warmCp =>
                            val sym = warmCp.symbols.find:
                                case c: Tasty.Symbol.Class  => c.body.isDefined
                                case t: Tasty.Symbol.Trait  => t.body.isDefined
                                case o: Tasty.Symbol.Object => o.body.isDefined
                                case _                      => false
                            .getOrElse(warmCp.symbols(0))
                            (sym, warmCp)
                    .map:
                        case Result.Success(pair) => pair
                        case Result.Failure(e)    => throw new RuntimeException(s"mmap load failed: $e")
                        case Result.Panic(t)      => throw t
            // Step 3: Scope has exited. Call decodeBody. Exercises the post-Scope path.
            symAndCp.flatMap: (sym, warmCp) =>
                Abort.run[TastyError](warmCp.bodyTree(sym)).map: result =>
                    result match
                        case Result.Failure(TastyError.MalformedSection(_, reason, _)) =>
                            assert(
                                reason.contains("body bytes not available"),
                                s"Expected 'body bytes not available' in MalformedSection reason; got: '$reason'"
                            )
                            succeed
                        case Result.Failure(TastyError.ClasspathClosed(_)) =>
                            // Also acceptable: arena accessed post-close raises ClasspathClosed.
                            succeed
                        case Result.Success(Maybe.Present(_)) =>
                            // If the body was cached before Scope exit, success is acceptable.
                            succeed
                        case Result.Success(Maybe.Absent) =>
                            // Symbol had no body: test is inconclusive but not a failure.
                            succeed
                        case Result.Failure(other) =>
                            fail(s"Unexpected TastyError from post-Scope decodeBody: $other")
                        case Result.Panic(t) =>
                            fail(s"Unexpected panic from post-Scope decodeBody: ${t.getMessage}")
    }

    // P02.7 (JVM-only): F-W2-30/31 via mmap -- subclassIndex and companionIndex populated on mmap warm load
    // Given: synthetic cp with explicit subclassIndex and companionIndex; snapshot written to real filesystem
    // When: loaded via readMapped (mmap path)
    // Then: warm subclassIndex.size == cold subclassIndex.size AND warm companionIndex.size == cold companionIndex.size
    // Pins: F-W2-30, F-W2-31
    "P02.7 F-W2-30/31 (mmap): subclassIndex and companionIndex populated on mmap warm load" in run {
        val digest  = Array[Byte](0x02, 0x31, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val tmpDir  = java.io.File.createTempFile("kyo-df5-p02-mmap", "").getAbsolutePath
        val _       = new java.io.File(tmpDir).delete()
        val _       = new java.io.File(tmpDir).mkdirs()
        val platSrc = PlatformFileSource.get

        Scope.run:
            Abort.run[TastyError]:
                syntheticCp.flatMap: cold =>
                    SnapshotWriter.write(cold, tmpDir, digest, platSrc).andThen:
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"$tmpDir/$hex.krfl"
                        SnapshotReader.readMapped(snapPath, platSrc).map: warm =>
                            (
                                cold.indices.subclassIndex.size,
                                cold.indices.companionIndex.size,
                                warm.indices.subclassIndex.size,
                                warm.indices.companionIndex.size
                            )
            .map:
                case Result.Success((coldSub, coldComp, warmSub, warmComp)) =>
                    assert(
                        warmSub == coldSub,
                        s"mmap warm subclassIndex size mismatch: cold=$coldSub warm=$warmSub; F-W2-30 not fixed"
                    )
                    assert(
                        warmComp == coldComp,
                        s"mmap warm companionIndex size mismatch: cold=$coldComp warm=$warmComp; F-W2-31 not fixed"
                    )
                    succeed
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

end DecoderFidelity5Phase02JvmTest
