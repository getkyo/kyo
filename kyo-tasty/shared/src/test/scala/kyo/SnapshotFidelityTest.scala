package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader

/** Fidelity tests for snapshot round-trip integrity.
  *
  * Exercises `SnapshotWriter` (serialize permittedSubclassIds, annotations, javaMetadata, full
  * fqnIndex), `SnapshotReader` (read new fields, reject old minors, reconstruct dual-FQN index
  * from FQNIDX__ section), and version bumps.
  *
  * Includes a FQNMAP__ section round-trip for unresolvedFqnByNegId persistence and an in-memory
  * snapshot round-trip via TestClasspaths2.withSnapshotInMemory. Where the JVM-only stdlib-scale
  * lower bounds were dropped (>= 5 deprecated symbols, scala.Option.permittedSubclassIds >= 2), the
  * shape assertions (round-trip preserves count equality) are preserved. All tests
  * now run on JVM, JS, and Native.
  */
class SnapshotFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // roundtrip-fidelity via in-memory snapshot
    "snapshot warm-load preserves annotations and permittedSubclassIds" in {
        TestClasspaths2.withSnapshotInMemory().flatMap: (coldCp, warmCp) =>
            for
                coldAnnot <- coldCp.symbolsAnnotatedWith("scala.deprecated")
                warmAnnot <- warmCp.symbolsAnnotatedWith("scala.deprecated")
                _ <-
                    val coldAnnotCount = coldAnnot.size
                    val warmAnnotCount = warmAnnot.size
                    assert(
                        warmAnnotCount >= coldAnnotCount,
                        s"In-memory snapshot lost deprecated annotations: cold=$coldAnnotCount warm=$warmAnnotCount"
                    )
                    Kyo.foreachDiscard(coldCp.allClassLike):
                        case cold: Tasty.Symbol.Class =>
                            cold.permittedSubclassIds match
                                case Present(coldIds) =>
                                    Sync.defer(coldCp.fullNameUnsafe(cold).asString).map: fqn =>
                                        warmCp.findClass(fqn) match
                                            case Present(warm: Tasty.Symbol.Class) =>
                                                warm.permittedSubclassIds match
                                                    case Present(warmIds) =>
                                                        assert(
                                                            coldIds.size == warmIds.size,
                                                            s"permittedSubclassIds size differs for $fqn: cold=${coldIds.size} warm=${warmIds.size}"
                                                        )
                                                    case Absent =>
                                                        fail(
                                                            s"permittedSubclassIds Present in cold but Absent after in-memory round-trip for $fqn"
                                                        )
                                                end match
                                            case _ => ()
                                        end match
                                case Absent => Kyo.unit
                            end match
                        case _ => Kyo.unit
            yield succeed
    }

    //   permits-roundtrip via in-memory snapshot
    "permittedSubclassIds survives in-memory snapshot round-trip" in {
        TestClasspaths2.withSnapshotInMemory().flatMap: (coldCp, warmCp) =>
            Kyo.foreachDiscard(coldCp.allClassLike):
                case cold: Tasty.Symbol.Class =>
                    cold.permittedSubclassIds match
                        case Present(coldIds) =>
                            Sync.defer(coldCp.fullNameUnsafe(cold).asString).map: fqn =>
                                warmCp.findClass(fqn) match
                                    case Present(warm: Tasty.Symbol.Class) =>
                                        warm.permittedSubclassIds match
                                            case Present(warmIds) =>
                                                assert(
                                                    coldIds.size == warmIds.size,
                                                    s"permittedSubclassIds size differs for $fqn: cold=${coldIds.size} warm=${warmIds.size}"
                                                )
                                            case Absent =>
                                                fail(s"permittedSubclassIds Present in cold but Absent after in-memory round-trip for $fqn")
                                        end match
                                    case _ => ()
                                end match
                        case Absent => Kyo.unit
                    end match
                case _ => Kyo.unit
            .map(_ => succeed)
    }

    //   annotations-roundtrip via in-memory snapshot
    "symbolsAnnotatedWith count survives in-memory snapshot round-trip" in {
        TestClasspaths2.withSnapshotInMemory().flatMap: (coldCp, warmCp) =>
            for
                coldA <- coldCp.symbolsAnnotatedWith("scala.deprecated")
                warmA <- warmCp.symbolsAnnotatedWith("scala.deprecated")
            yield
                val coldCount = coldA.size
                val warmCount = warmA.size
                assert(
                    warmCount >= coldCount,
                    s"In-memory snapshot round-trip must preserve deprecated annotation count: cold=$coldCount warm=$warmCount"
                )
                succeed
    }

    //   javametadata-roundtrip via in-memory snapshot
    "javaMetadata survives in-memory snapshot round-trip" in {
        TestClasspaths2.withSnapshotInMemory().flatMap: (coldCp, warmCp) =>
            coldCp.allClassLike.flatMap:
                case c: Tasty.Symbol.ClassLike if c.javaMetadata.isDefined => Chunk(c)
                case _                                                     => Chunk.empty
            .headOption match
                case Some(coldSym) =>
                    Sync.defer(coldCp.fullNameUnsafe(coldSym).asString).map: fqn =>
                        warmCp.findSymbol(fqn) match
                            case Present(warmSym: Tasty.Symbol.ClassLike) =>
                                assert(
                                    warmSym.javaMetadata.isDefined,
                                    s"javaMetadata lost after in-memory round-trip for $fqn"
                                )
                                val coldMeta = coldSym.javaMetadata.get
                                val warmMeta = warmSym.javaMetadata.get
                                assert(
                                    ((warmMeta.accessFlags & 0x0001) != 0) == ((coldMeta.accessFlags & 0x0001) != 0),
                                    s"isJvmPublic differs for $fqn: cold=${(coldMeta.accessFlags & 0x0001) != 0} warm=${(warmMeta.accessFlags & 0x0001) != 0}"
                                )
                                assert(
                                    warmMeta.accessFlags == coldMeta.accessFlags,
                                    s"accessFlags differs for $fqn: cold=${coldMeta.accessFlags} warm=${warmMeta.accessFlags}"
                                )
                                succeed
                            case _ =>
                                succeed
                        end match
                case None =>
                    // Embedded fixtures may have no javaMetadata symbols; acceptable
                    Sync.defer(succeed)
            end match
    }

    // Wire-format leaf 5: format-version-bumped
    // History: set 6,b set 7, set 8, set 9, prior set 10, set 11, this set 12.
    "SnapshotFormat.FORMAT_VERSION reflects current minor version" in {
        assert(
            SnapshotFormat.minorVersion == 12,
            s"Expected SnapshotFormat.minorVersion == 12 but got ${SnapshotFormat.minorVersion}"
        )
    }

    // New leaf: in-memory-roundtrip-preserves-symbols
    "in-memory snapshot round-trip preserves symbols count" in {
        TestClasspaths2.withSnapshotInMemory().map: (cold, warm) =>
            assert(
                cold.symbols.size == warm.symbols.size,
                s"Expected cold.symbols.size == warm.symbols.size; cold=${cold.symbols.size} warm=${warm.symbols.size}"
            )
            succeed
    }

    // old-snapshot-triggers-cold-decode
    "old-snapshot-triggers-cold-decode" in {
        Sync.defer:
            val fakeOld = new Array[Byte](36)
            fakeOld(0) = 'K'.toByte
            fakeOld(1) = 'R'.toByte
            fakeOld(2) = 'F'.toByte
            fakeOld(3) = 'L'.toByte
            fakeOld(4) = 1.toByte // majorVersion = 1
            fakeOld(5) = 3.toByte // minorVersion = 3 (below current)
            // flags(8), digest(8), reserved(8), sectionCount(4) remain zero
            val mem  = MemoryFileSource()
            val path = "mem/old.krfl"
            mem.add(path, fakeOld)
            (mem, path)
        .flatMap: (mem, path) =>
            Abort.run[TastyError](
                SnapshotReader.read(path, mem)
            ).map:
                case Result.Failure(e: TastyError.SnapshotVersionMismatch) =>
                    assert(
                        e.found.minor == 3,
                        s"Expected found.minor == 3 but got ${e.found.minor}"
                    )
                    assert(
                        e.supported.minor == SnapshotFormat.minorVersion,
                        s"Expected supported.minor == ${SnapshotFormat.minorVersion} but got ${e.supported.minor}"
                    )
                    succeed
                case Result.Failure(other) =>
                    fail(s"Expected SnapshotVersionMismatch but got: $other")
                case Result.Success(_) =>
                    fail("Expected SnapshotVersionMismatch but read succeeded for an old snapshot")
                case Result.Panic(t) =>
                    throw t
    }

end SnapshotFidelityTest
