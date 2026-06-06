package kyo

import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Tests for Phase 11: round-trip encoding of TastyError variants added in Phase 11.
  *
  * Covers 4 plan-driven test leaves:
  *   1. Round-trip TastyError.UnhandledSubtypingCase with Type.Any and Type.Nothing.
  *   2. Round-trip TastyError.UnresolvedReference, TastyError.UnknownType, TastyError.MissingDeclaredType.
  *   3. Minor version is 11 in freshly written snapshot.
  *   4. Old minor=10 snapshot is rejected with TastyError.SnapshotVersionMismatch.
  */
class TastyErrorRoundTripTest extends Test:

    import AllowUnsafe.embrace.danger
    import kyo.Tasty.SymbolId

    /** Build a minimal Classpath carrying only the given errors, then serialize it. */
    private def snapshotBytesWithErrors(errors: Chunk[TastyError]): Array[Byte] =
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val cp = Tasty.Classpath.make(
            symbols = Chunk(rootSym),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(rootSym.id),
            fqnIndex = Dict.empty,
            packageIndex = Dict.empty,
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = errors
        )
        val digest = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7)
        SnapshotWriter.serializeToBytes(cp, digest)
    end snapshotBytesWithErrors

    /** An in-memory FileSource for round-trip tests. */
    class MemoryFileSource(val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty)
        extends FileSource:

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
                    Sync.defer { files.remove(from); files(to) = bytes }
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))
    end MemoryFileSource

    // Leaf 3 (plan leaf 1 of this file): round-trip TastyError.UnhandledSubtypingCase.
    // Given: an instance with shape="Applied-TermRef-_", lhs=Type.Any, rhs=Type.Nothing, file="X.tasty".
    // When: written via SnapshotWriter and read back via SnapshotReader.
    // Then: decoded value equals original.
    // Pins: INV-TASTYERROR-WIRE.
    "round-trip TastyError.UnhandledSubtypingCase with Type.Any and Type.Nothing" in run {
        val err    = TastyError.UnhandledSubtypingCase("Applied-TermRef-_", Tasty.Type.Any, Tasty.Type.Nothing, "X.tasty")
        val errors = Chunk[TastyError](err)
        assert(errors.size == 1)

        val snapshotBytes = snapshotBytesWithErrors(errors)
        val cacheSrc      = MemoryFileSource()
        val hex           = DigestComputer.toHexString(Array[Byte](0, 1, 2, 3, 4, 5, 6, 7))
        val snapPath      = s"cache/$hex.krfl"
        cacheSrc.add(snapPath, snapshotBytes)

        Abort.run[TastyError]:
            SnapshotReader.read(snapPath, cacheSrc).map: loadedCp =>
                loadedCp.errors
        .map:
            case Result.Success(loaded) =>
                assert(loaded.size == 1, s"Expected 1 error after round-trip, got ${loaded.size}")
                assert(loaded(0) == err, s"Round-trip mismatch: expected $err, got ${loaded(0)}")
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // Leaf 4 (plan leaf 2 of this file): round-trip UnresolvedReference, UnknownType, MissingDeclaredType.
    // Given: instances of UnresolvedReference("x.Y", 7), UnknownType("X.tasty", 42L, "fail"), MissingDeclaredType(SymbolId(3), "X.tasty").
    // When: each is written and read back.
    // Then: every decoded value equals its original.
    // Pins: INV-TASTYERROR-WIRE.
    "round-trip UnresolvedReference, UnknownType, and MissingDeclaredType" in run {
        val e1     = TastyError.UnresolvedReference("x.Y", 7)
        val e2     = TastyError.UnknownType("X.tasty", 42L, "fail")
        val e3     = TastyError.MissingDeclaredType(Tasty.SymbolId(3), "X.tasty")
        val errors = Chunk[TastyError](e1, e2, e3)
        assert(errors.size == 3)

        val snapshotBytes = snapshotBytesWithErrors(errors)
        val cacheSrc      = MemoryFileSource()
        val hex           = DigestComputer.toHexString(Array[Byte](0, 1, 2, 3, 4, 5, 6, 7))
        val snapPath      = s"cache/$hex.krfl"
        cacheSrc.add(snapPath, snapshotBytes)

        Abort.run[TastyError]:
            SnapshotReader.read(snapPath, cacheSrc).map: loadedCp =>
                loadedCp.errors
        .map:
            case Result.Success(loaded) =>
                assert(loaded.size == 3, s"Expected 3 errors after round-trip, got ${loaded.size}")
                assert(loaded(0) == e1, s"UnresolvedReference mismatch: expected $e1, got ${loaded(0)}")
                assert(loaded(1) == e2, s"UnknownType mismatch: expected $e2, got ${loaded(1)}")
                assert(loaded(2) == e3, s"MissingDeclaredType mismatch: expected $e3, got ${loaded(2)}")
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
    }

    // Leaf 1 (plan leaf 1 of SnapshotDigestTest): minor version bumped to 11.
    // Given: a freshly written snapshot.
    // When: the minor version byte at offset 5 is read.
    // Then: value is 11; SnapshotFormat.minorVersion is 11.
    // Pins: INV-TASTYERROR-WIRE.
    "minor version is 11 in freshly written snapshot" in run {
        val snapshotBytes = snapshotBytesWithErrors(Chunk.empty)
        val minor         = snapshotBytes(5) & 0xff
        assert(minor == 11, s"Expected snapshot minor version 11, got $minor")
        assert(
            SnapshotFormat.minorVersion == 11,
            s"SnapshotFormat.minorVersion must be 11, got ${SnapshotFormat.minorVersion}"
        )
    }

    // Leaf 2 (plan leaf 2): old minor=10 snapshot is rejected with SnapshotVersionMismatch.
    // Given: a valid minor=11 snapshot with byte at offset 5 patched to 10.
    // When: loaded via SnapshotReader.read.
    // Then: raises TastyError.SnapshotVersionMismatch with found=(1,10,0) and supported=(1,11,0).
    // Pins: INV-TASTYERROR-WIRE.
    "snapshot with minorVersion=10 is rejected with SnapshotVersionMismatch" in run {
        val freshBytes   = snapshotBytesWithErrors(Chunk.empty)
        val patchedBytes = freshBytes.clone()
        patchedBytes(5) = 10.toByte

        val cacheSrc = MemoryFileSource()
        val snapPath = "cache/minor10-reject-test.krfl"
        cacheSrc.add(snapPath, patchedBytes)

        Abort.run[TastyError]:
            SnapshotReader.read(snapPath, cacheSrc)
        .map:
            case Result.Success(_) =>
                fail("Expected SnapshotVersionMismatch for minor=10 snapshot, but read succeeded")
            case Result.Failure(e) =>
                e match
                    case vm: TastyError.SnapshotVersionMismatch =>
                        assert(
                            vm.found.major == 1 && vm.found.minor == 10,
                            s"Expected found version (1,10,0), got ${vm.found}"
                        )
                        assert(
                            vm.supported.major == 1 && vm.supported.minor == 11,
                            s"Expected supported version (1,11,0), got ${vm.supported}"
                        )
                    case other =>
                        fail(s"Expected TastyError.SnapshotVersionMismatch, got: $other")
            case Result.Panic(t) => throw t
    }

end TastyErrorRoundTripTest
