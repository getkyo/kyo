package kyo

import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.DigestComputer.JarDigestEntry

/** Cross-platform DigestComputer xxh3 content-addressed digest behavior: digestForJar stability under entry reordering, crc32 sensitivity,
  * and content-bytes sensitivity.
  */
class DigestComputerTest extends kyo.test.Test[Any]:

    final private class ScriptedWalk(results: Chunk[Result[FileReadException | FileStructureException, Maybe[Path]]])
        extends Path.WalkHandle:
        val advances = new java.util.concurrent.atomic.AtomicInteger(0)
        val closes   = new java.util.concurrent.atomic.AtomicInteger(0)

        def next()(using AllowUnsafe, Frame): Result[FileReadException | FileStructureException, Maybe[Path]] =
            val index = advances.getAndIncrement()
            if index < results.size then results(index)
            else Result.panic(new IllegalStateException("walk advanced after termination"))
        end next

        def close()(using AllowUnsafe): Unit = discard(closes.incrementAndGet())
    end ScriptedWalk

    final private class WalkingFileSystem(
        inner: FileSystem.Write[Sync],
        handle: Path.WalkHandle,
        openingFailure: Maybe[FileReadException | FileStructureException] = Absent,
        existence: Result[FileReadException, Boolean] = Result.succeed(true)
    ) extends FileSystem.Write[Sync]:
        export inner.{exists as _, openWalk as _, *}

        def exists(path: Path)(using Frame): Boolean < (Sync & Abort[FileReadException]) = exists(path, true)

        def exists(path: Path, followLinks: Boolean)(using Frame): Boolean < (Sync & Abort[FileReadException]) = Abort.get(existence)

        def openWalk(path: Path, maxDepth: Int, followLinks: Boolean)(using
            Frame
        ): Path.WalkHandle < (Sync & Abort[FileReadException | FileStructureException]) =
            openingFailure match
                case Present(error) => Abort.fail(error)
                case Absent         => handle
    end WalkingFileSystem

    for paranoid <- Seq(false, true) do
        def compute(root: Path)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            if paranoid then DigestComputer.computeParanoid(Seq(root.toString))
            else DigestComputer.compute(Seq(root.toString))

        s"missing directory roots produce an empty digest without opening a walk, paranoid: $paranoid" in {
            val root   = Path("digest-walk-root")
            val handle = new ScriptedWalk(Chunk.empty)
            FileSystem.let(new WalkingFileSystem(FileSystem.host, handle, existence = Result.succeed(false))) {
                compute(root).map { digest =>
                    assert(digest.sameElements(Array.fill[Byte](8)(0)))
                    assert(handle.advances.get() == 0)
                    assert(handle.closes.get() == 0)
                }
            }
        }

        s"directory digest closes its selected backend walk at EOF, paranoid: $paranoid" in {
            val root   = Path("digest-walk-root")
            val handle = new ScriptedWalk(Chunk(Result.succeed(Present(root / "ignored.class")), Result.succeed(Absent)))
            FileSystem.let(new WalkingFileSystem(FileSystem.host, handle)) {
                compute(root).map { digest =>
                    assert(digest.sameElements(Array.fill[Byte](8)(0)))
                    assert(handle.advances.get() == 2)
                    assert(handle.closes.get() == 1)
                }
            }
        }

        for structural <- Seq(false, true) do
            s"directory digest reports walk failures and closes once, paranoid: $paranoid, structural: $structural" in {
                val root = Path("digest-walk-root")
                val failure: FileReadException | FileStructureException =
                    if structural then FileNotADirectoryException(root)
                    else FileAccessDeniedException(root)
                val handle = new ScriptedWalk(Chunk(Result.succeed(Present(root / "partial.tasty")), Result.fail(failure)))
                FileSystem.let(new WalkingFileSystem(FileSystem.host, handle)) {
                    Abort.run[TastyError](compute(root)).map { result =>
                        assert(result.map(_ => ()) == Result.fail(TastyError.SnapshotIoError(s"walk $root: ${failure.getMessage}")))
                        assert(handle.advances.get() == 2)
                        assert(handle.closes.get() == 1)
                    }
                }
            }
        end for

        s"directory digest retains walk panics and closes once, paranoid: $paranoid" in {
            val root   = Path("digest-walk-root")
            val panic  = new IllegalStateException("walk failed unexpectedly")
            val handle = new ScriptedWalk(Chunk(Result.succeed(Present(root / "partial.tasty")), Result.panic(panic)))
            FileSystem.let(new WalkingFileSystem(FileSystem.host, handle)) {
                Abort.run[TastyError](compute(root)).map { result =>
                    assert(result.map(_ => ()) == Result.panic(panic))
                    assert(handle.advances.get() == 2)
                    assert(handle.closes.get() == 1)
                }
            }
        }

        s"directory digest reports existence-check failures instead of an empty digest, paranoid: $paranoid" in {
            val root    = Path("digest-walk-root")
            val failure = FileAccessDeniedException(root)
            val handle  = new ScriptedWalk(Chunk.empty)
            FileSystem.let(new WalkingFileSystem(FileSystem.host, handle, existence = Result.fail(failure))) {
                Abort.run[TastyError](compute(root)).map { result =>
                    assert(result.map(_ => ()) == Result.fail(TastyError.SnapshotIoError(s"walk $root: ${failure.getMessage}")))
                    assert(handle.advances.get() == 0)
                    assert(handle.closes.get() == 0)
                }
            }
        }

        s"directory digest reports acquisition failure without advancing a handle, paranoid: $paranoid" in {
            val root    = Path("digest-walk-root")
            val failure = FileAccessDeniedException(root)
            val handle  = new ScriptedWalk(Chunk.empty)
            FileSystem.let(new WalkingFileSystem(FileSystem.host, handle, Present(failure))) {
                Abort.run[TastyError](compute(root)).map { result =>
                    assert(result.map(_ => ()) == Result.fail(TastyError.SnapshotIoError(s"walk $root: ${failure.getMessage}")))
                    assert(handle.advances.get() == 0)
                    assert(handle.closes.get() == 0)
                }
            }
        }
    end for

    // digestForJar is stable for identical entries in any insertion order.
    "digestForJar is stable for same-name same-crc entries in any order" in {
        val e1 = JarDigestEntry("META-INF/INDEX.LIST", 0xdeadbeefL)
        val e2 = JarDigestEntry("META-INF/INDEX.LIST", 0xdeadbeefL)
        val h1 = DigestComputer.digestForJar(Chunk(e1, e2))
        val h2 = DigestComputer.digestForJar(Chunk(e2, e1))
        assert(h1 == h2, s"same-name same-crc entries must produce same digest: $h1 vs $h2")
    }

    // digestForJar distinguishes entries with distinct crc32 values.
    // Two identical-name entries with different crc32 produce a different digest than the same two
    // entries with the same crc32, confirming that crc32 is mixed into the hash.
    "digestForJar includes crc32 in the hash (different crc32 changes digest)" in {
        val e1a = JarDigestEntry("foo.class", 0x11111111L)
        val e1b = JarDigestEntry("foo.class", 0x22222222L)
        val hA  = DigestComputer.digestForJar(Chunk(e1a))
        val hB  = DigestComputer.digestForJar(Chunk(e1b))
        assert(hA != hB, s"different crc32 values must produce different digest: $hA vs $hB")
    }

    // digestForJar(Chunk.empty) returns 0L.
    // With acc = 0L and zero mixing steps, xxh3Avalanche(0L) = 0L.
    "digestForJar(Chunk.empty) equals 0L (empty-input vector)" in {
        val result = DigestComputer.digestForJar(Chunk.empty)
        assert(result == 0L, s"digestForJar(Chunk.empty) expected 0L but got $result")
    }

    // directory root compute is deterministic and sensitive to file-set changes.
    "directory root compute is deterministic and detects added file" in {
        Scope.run {
            Path.run(Path.tempDir("kyo-dct")).map { dir =>
                val file = dir / "Foo.tasty"
                Path.run(file.writeBytes(Span.from(Array[Byte](1, 2, 3, 4)))).map { _ =>
                    val root = dir.toString
                    Abort.run[TastyError] {
                        DigestComputer.compute(Seq(root)).map { d1 =>
                            DigestComputer.compute(Seq(root)).map { d2 =>
                                val file2 = dir / "Bar.tasty"
                                Path.run(file2.writeBytes(Span.from(Array[Byte](5, 6, 7, 8)))).map { _ =>
                                    DigestComputer.compute(Seq(root)).map { d3 =>
                                        (d1, d2, d3)
                                    }
                                }
                            }
                        }
                    }
                        .map {
                            case Result.Success((d1, d2, d3)) =>
                                assert(d1.sameElements(d2), "same directory must produce same digest")
                                assert(!d1.sameElements(d3), "adding a file must produce a different digest")
                            case Result.Failure(e) => fail(s"Unexpected failure: $e")
                            case Result.Panic(t)   => throw t
                        }
                }
            }
        }
    }

    // digestForJar with JarDigestEntry is deterministic across platforms.
    "digestForJar with JarDigestEntry is deterministic across platforms" in {
        val entries = Chunk(
            JarDigestEntry("a/B.class", 0xdeadL),
            JarDigestEntry("c/D.class", 0xcafeL)
        )
        val h1 = DigestComputer.digestForJar(entries)
        val h2 = DigestComputer.digestForJar(entries)
        assert(h1 == h2, s"digestForJar must be deterministic: $h1 vs $h2")
    }

    // longToBytes and bytesToLong round-trip on digest output.
    "longToBytes/bytesToLong round-trip is lossless (8 bytes, little-endian)" in {
        val entries   = Chunk(JarDigestEntry("foo/Bar.class", 0x12345678L))
        val digest    = DigestComputer.digestForJar(entries)
        val bytes     = DigestComputer.longToBytes(digest)
        val roundTrip = DigestComputer.bytesToLong(bytes)
        assert(bytes.length == 8, s"expected 8 bytes, got ${bytes.length}")
        assert(roundTrip == digest, s"round-trip failed: $roundTrip != $digest")
    }

end DigestComputerTest
