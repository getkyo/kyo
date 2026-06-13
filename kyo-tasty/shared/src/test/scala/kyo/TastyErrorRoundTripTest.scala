package kyo

import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Tests for round-trip encoding of new TastyError variants.
  *
  * Coverage:
  *   1. Round-trip TastyError.UnhandledSubtypingCase with Type.Any and Type.Nothing.
  *   2. Round-trip TastyError.UnresolvedReference, TastyError.UnknownType, TastyError.MissingDeclaredType.
  *   3. Minor version is 12 in freshly written snapshot.
  *   4. Old minor=10 snapshot is rejected with TastyError.SnapshotVersionMismatch.
  */
class TastyErrorRoundTripTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger
    import kyo.Tasty.SymbolId

    /** Build a minimal Classpath carrying only the given errors, then serialize it. */
    private def snapshotBytesWithErrors(errors: Chunk[TastyError]): Array[Byte] =
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val classpath = Tasty.Classpath.make(
            symbols = Chunk(rootSym),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(rootSym.id),
            fullNameIndex = Dict.empty,
            packageIndex = Dict.empty,
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = errors
        )
        val digest = Array[Byte](0, 1, 2, 3, 4, 5, 6, 7)
        SnapshotWriter.serializeToBytes(classpath, digest)
    end snapshotBytesWithErrors

    // round-trip TastyError.UnhandledSubtypingCase.
    "round-trip TastyError.UnhandledSubtypingCase with Type.Any and Type.Nothing" in {
        val err    = TastyError.UnhandledSubtypingCase("Applied-TermRef-_", Tasty.Type.Any, Tasty.Type.Nothing, "X.tasty")
        val errors = Chunk[TastyError](err)
        assert(errors.size == 1)

        val snapshotBytes = snapshotBytesWithErrors(errors)
        val snapPath      = s"cache/${DigestComputer.toHexString(Array[Byte](0, 1, 2, 3, 4, 5, 6, 7))}.krfl"

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(snapshotBytes, snapPath).map { loadedCp =>
                loadedCp.errors
            }
        }.map {
            case Result.Success(loaded) =>
                assert(loaded.size == 1, s"Expected 1 error after round-trip, got ${loaded.size}")
                assert(loaded(0) == err, s"Round-trip mismatch: expected $err, got ${loaded(0)}")
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // round-trip UnresolvedReference, UnknownType, MissingDeclaredType.
    "round-trip UnresolvedReference, UnknownType, and MissingDeclaredType" in {
        val e1     = TastyError.UnresolvedReference("x.Y", 7)
        val e2     = TastyError.UnknownType("X.tasty", 42L, "fail")
        val e3     = TastyError.MissingDeclaredType(Tasty.SymbolId(3), "X.tasty")
        val errors = Chunk[TastyError](e1, e2, e3)
        assert(errors.size == 3)

        val snapshotBytes = snapshotBytesWithErrors(errors)
        val snapPath      = s"cache/${DigestComputer.toHexString(Array[Byte](0, 1, 2, 3, 4, 5, 6, 7))}.krfl"

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(snapshotBytes, snapPath).map { loadedCp =>
                loadedCp.errors
            }
        }.map {
            case Result.Success(loaded) =>
                assert(loaded.size == 3, s"Expected 3 errors after round-trip, got ${loaded.size}")
                assert(loaded(0) == e1, s"UnresolvedReference mismatch: expected $e1, got ${loaded(0)}")
                assert(loaded(1) == e2, s"UnknownType mismatch: expected $e2, got ${loaded(1)}")
                assert(loaded(2) == e3, s"MissingDeclaredType mismatch: expected $e3, got ${loaded(2)}")
                succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    // minor version bumped to 12 (added the PLISTS__ section).
    "minor version is 12 in freshly written snapshot" in {
        val snapshotBytes = snapshotBytesWithErrors(Chunk.empty)
        val minor         = snapshotBytes(5) & 0xff
        assert(minor == 12, s"Expected snapshot minor version 12, got $minor")
        assert(
            SnapshotFormat.minorVersion == 12,
            s"SnapshotFormat.minorVersion must be 12, got ${SnapshotFormat.minorVersion}"
        )
    }

    // old minor=10 snapshot is rejected with SnapshotVersionMismatch.
    "snapshot with minorVersion=10 is rejected with SnapshotVersionMismatch" in {
        val freshBytes   = snapshotBytesWithErrors(Chunk.empty)
        val patchedBytes = freshBytes.clone()
        patchedBytes(5) = 10.toByte

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(patchedBytes, "cache/minor10-reject-test.krfl")
        }.map {
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
                            vm.supported.major == 1 && vm.supported.minor == 12,
                            s"Expected supported version (1,12,0), got ${vm.supported}"
                        )
                    case other =>
                        fail(s"Expected TastyError.SnapshotVersionMismatch, got: $other")
            case Result.Panic(t) => throw t
        }
    }

    // tag-255 round-trip path in writeType/readType.
    // The tag-255 catch-all in SnapshotWriter.writeType serializes complex types (Refinement,
    // Rec, Skolem, etc.) as an opaque show string. The reader falls back to Type.Nothing on
    // any unknown tag including 255. This is a known semantic limitation documented in the
    // writer comments; the critical invariant is that the bytes are consumed cleanly without
    // crashing or corrupting the stream so the rest of the snapshot remains readable.
    //   and rhs is Type.Any (tag-1; decoded faithfully).
    //   (c) lhs decodes to Type.Nothing (the documented opaque-tag fallback, not data corruption).
    "tag-255 opaque catch-all round-trips cleanly; lhs falls back to Type.Nothing" in {
        val refinementType: Tasty.Type = Tasty.Type.Refinement(
            Tasty.Type.Named(Tasty.SymbolId(0)),
            Tasty.Name("x"),
            Tasty.Type.Any
        )
        val err    = TastyError.UnhandledSubtypingCase("Refinement", refinementType, Tasty.Type.Any, "sample.tasty")
        val errors = Chunk[TastyError](err)

        val snapshotBytes = snapshotBytesWithErrors(errors)
        val snapPath      = s"cache/${kyo.internal.tasty.snapshot.DigestComputer.toHexString(Array[Byte](0, 1, 2, 3, 4, 5, 6, 7))}.krfl"

        Abort.run[TastyError] {
            SnapshotReader.readFromBytes(snapshotBytes, snapPath).map { loadedCp =>
                loadedCp.errors
            }
        }.map {
            case Result.Success(loaded) =>
                assert(loaded.size == 1, s"Expected 1 error after tag-255 round-trip, got ${loaded.size}")
                loaded(0) match
                    case TastyError.UnhandledSubtypingCase(shape, lhs, rhs, file) =>
                        assert(shape == "Refinement", s"shape mismatch: expected 'Refinement', got '$shape'")
                        assert(file == "sample.tasty", s"file mismatch: expected 'sample.tasty', got '$file'")
                        // lhs (Refinement) goes through tag-255; reader falls back to Type.Nothing.
                        assert(
                            lhs == Tasty.Type.Nothing,
                            s"tag-255 opaque lhs should decode to Type.Nothing, got $lhs"
                        )
                        // rhs (Type.Any) is tag-1; round-trips faithfully.
                        assert(rhs == Tasty.Type.Any, s"rhs mismatch: expected Type.Any, got $rhs")
                    case other =>
                        fail(s"Expected UnhandledSubtypingCase, got: $other")
                end match
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

end TastyErrorRoundTripTest
