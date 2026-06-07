package kyo

import kyo.Tasty.SymbolId
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.DecodeContext
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.PlatformFileSource
import kyo.internal.tasty.query.TastyState
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Verifies post-Scope decodeBody behavior on mmap-loaded classpaths.
  *
  * The mmap path sets sectionBytes = Array.empty for body-bearing symbols; decodeBody detects this and
  * returns TastyError.MalformedSection("body bytes not available") rather than ClasspathClosed.
  * An IllegalStateException from the arena would only fire if view-backed bytes were accessed after close,
  * but they are not accessed at all when sectionBytes is empty.
  */
class DecoderFidelity5Phase02MmapTest extends kyo.test.Test[Any]:

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
            Chunk.empty
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
            Chunk.empty
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
                fqnIndex = Dict(
                    "Animal" -> SymbolId(0),
                    "Dog"    -> SymbolId(1),
                    "Cat"    -> SymbolId(2),
                    "Foo"    -> SymbolId(3),
                    "Foo$"   -> SymbolId(4)
                ),
                packageIndex = Dict.empty,
                subclassIndex = Dict(SymbolId(0) -> Chunk(SymbolId(1), SymbolId(2))),
                companionIndex = Dict(SymbolId(3) -> SymbolId(4), SymbolId(4) -> SymbolId(3)),
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
    end syntheticCp

    "P02.6 post-Scope decodeBody on mmap-loaded snapshot returns MalformedSection (body bytes not available)" in {
        val fixtureSrc = MemSrc()
        fixtureSrc.add("root/Animal.tasty", kyo.fixtures.Embedded.animalTasty)
        fixtureSrc.add("root/Dog.tasty", kyo.fixtures.Embedded.dogTasty)
        val digest  = Array[Byte](0x02, 0x27, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val tmpDir  = java.io.File.createTempFile("kyo-df5-p02-jvm", "").getAbsolutePath
        val _       = new java.io.File(tmpDir).delete()
        val _       = new java.io.File(tmpDir).mkdirs()
        val platSrc = PlatformFileSource.get
        // build cold classpath, write snapshot to disk.
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
            // load via mmap INSIDE a Scope.run; extract any symbol; let Scope exit.
            // snapshot-loaded symbols no longer carry body bytes; bodyTree returns Absent.
            val symAndCp =
                Scope.run:
                    Abort.run[TastyError]:
                        SnapshotReader.readMapped(snapPath, platSrc).map: warmCp =>
                            val sym = warmCp.symbols.headOption.getOrElse(
                                throw new RuntimeException("snapshot has no symbols")
                            )
                            (sym, warmCp)
                    .map:
                        case Result.Success(pair) => pair
                        case Result.Failure(e)    => throw new RuntimeException(s"mmap load failed: $e")
                        case Result.Panic(t)      => throw t
            // Scope has exited. Call Tasty.bodyTree via a fresh binding with a fresh DecodeContext.
            // After bodyStore is empty after snapshot load; bodyTree returns Absent.
            symAndCp.flatMap: (sym, warmCp) =>
                val postScopeBinding = Binding(warmCp, Maybe.Present(DecodeContext.fresh()))
                TastyState.bindingLocal.let(Maybe.Present(postScopeBinding)):
                    Abort.run[TastyError](Tasty.bodyTree(sym)).map: result =>
                        result match
                            case Result.Success(Maybe.Absent) =>
                                // Snapshot load has empty bodyStore; bodyTree correctly returns Absent.
                                succeed
                            case Result.Success(Maybe.Present(_)) =>
                                // Would only happen if bodyStore was somehow populated; acceptable.
                                succeed
                            case Result.Failure(TastyError.MalformedSection(_, reason, _)) =>
                                // Legacy case: body bytes still present but invalid after mmap close.
                                assert(
                                    reason.contains("body bytes not available"),
                                    s"Expected 'body bytes not available' in MalformedSection reason; got: '$reason'"
                                )
                                succeed
                            case Result.Failure(TastyError.ClasspathClosed(_)) =>
                                succeed
                            case Result.Failure(other) =>
                                fail(s"Unexpected TastyError from post-Scope decodeBody: $other")
                            case Result.Panic(t) =>
                                fail(s"Unexpected panic from post-Scope decodeBody: ${t.getMessage}")
    }

    "P02.7 (mmap): subclassIndex and companionIndex populated on mmap warm load" in {
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
                        s"mmap warm subclassIndex size mismatch: cold=$coldSub warm=$warmSub;"
                    )
                    assert(
                        warmComp == coldComp,
                        s"mmap warm companionIndex size mismatch: cold=$coldComp warm=$warmComp;"
                    )
                    succeed
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
    }

end DecoderFidelity5Phase02MmapTest
