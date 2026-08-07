package kyo

import java.nio.charset.StandardCharsets
import scala.compiletime.testing.typeCheckErrors

class PathCapabilityTest extends kyo.test.Test[Any]:

    val somePath  = Path("tmp", "cap-a.txt")
    val otherPath = Path("tmp", "cap-b.txt")

    // --- Compile-time capability laws: a green compile proves each row ascription below. ---

    val readOnly: String < PathRead                            = somePath.read
    val writableRead: String < PathWrite                       = somePath.read
    val write: Unit < PathWrite                                = somePath.write("x")
    val mixed: String < PathWrite                              = somePath.read.map(s => otherPath.write(s).andThen(s))
    val readRuns: String < (Sync & Abort[FileSystemException]) = Path.runReadOnly(readOnly)
    val writeRuns: Unit < (Sync & Abort[FileSystemException])  = Path.run(write)
    val tryLockRow: Maybe[Path.Lock] < (PathRead & Sync & Scope) =
        somePath.tryLock(Path.LockMode.Shared)
    val lockRow: Path.Lock < (PathRead & Async & Scope) =
        somePath.lock(Path.LockMode.Exclusive, Path.LockWait.UntilAvailable)

    // --- Staged-write combinator effect rows: Sync must not be required in the residual. ---
    // S = Abort[String] does not subsume Sync; a widened Sync & PathWrite & S return would fail to ascribe.
    val discardRow: Unit < (PathWrite & Scope & Abort[String]) =
        Path.discardWrites[Unit, Abort[String]](write)
    val commitRow: Unit < (PathWrite & Scope & Abort[CommitConflict] & Abort[String]) =
        Path.commitWritesOnSuccess[Unit, Abort[String]](write)
    val stagedRow: (Unit, FileSystem.StagedChanges[Sync]) < (PathWrite & Scope & Abort[String]) =
        Path.stageWrites[Unit, Abort[String]](write)
    val discardRowNoSync: Unit < (PathWrite & Scope) = Path.discardWrites(write)

    "the read-only runner leaves a write undischarged so its residual does not type-check" in {
        val errors = typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val w: Unit < kyo.PathWrite = kyo.Path("x").write("y")
            val bad: Unit < (kyo.Sync & kyo.Abort[kyo.FileSystemException]) = kyo.Path.runReadOnly(w)
            """
        )
        assert(errors.nonEmpty)
        assert(errors.exists(_.message.contains("PathWrite")))
    }

    "the removed FileLock and Boolean lock APIs do not type-check" in {
        val fileLockErrors = typeCheckErrors("val lock: kyo.Path.FileLock = ???")
        val booleanErrors = typeCheckErrors("""
            given kyo.Frame = kyo.Frame.internal
            kyo.Path("x").lock(true)
            """)
        assert(fileLockErrors.nonEmpty)
        assert(booleanErrors.nonEmpty)
    }

    "internal lock suspensions retain their required handlers" in {
        val tryLockErrors = typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val bad: kyo.Maybe[kyo.Path.Lock] < (kyo.PathRead & kyo.Scope) =
                kyo.Path.suspendTryLock(kyo.Path("x"), kyo.Path.LockMode.Shared)
            """
        )
        val lockErrors = typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val bad: kyo.Path.Lock < (kyo.PathRead & kyo.Scope) =
                kyo.Path.suspendLock(
                    kyo.Path("x"),
                    kyo.Path.LockMode.Exclusive,
                    kyo.Path.LockWait.UntilAvailable
                )
            """
        )
        assert(tryLockErrors.nonEmpty)
        assert(lockErrors.nonEmpty)
    }

    "an explicit backend effect remains in the runner residual" in {
        val positive = typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            sealed trait BackendEffect
            val backend = null.asInstanceOf[kyo.FileSystem.Write[BackendEffect]]
            val read: String < (BackendEffect & kyo.Abort[kyo.FileSystemException]) =
                kyo.Path.runReadOnlyWith(backend)(kyo.Path("x").read)
            val write: Unit < (BackendEffect & kyo.Abort[kyo.FileSystemException]) =
                kyo.Path.runWith(backend)(kyo.Path("x").write("value"))
            """
        )
        val errors = typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            sealed trait BackendEffect
            val backend = null.asInstanceOf[kyo.FileSystem.Write[BackendEffect]]
            val read = kyo.Path.runReadOnlyWith(backend)(kyo.Path("x").read)
            val bad: String < kyo.Abort[kyo.FileSystemException] = read
            """
        )
        assert(positive.isEmpty)
        assert(errors.nonEmpty)
        assert(errors.exists(_.message.contains("BackendEffect")))
    }

    "concurrent child operations have isolated Path handlers" in {
        FileSystem.inMemory.map { service =>
            val first  = Path("isolate-first.txt")
            val second = Path("isolate-second.txt")
            FileSystem.let(service) {
                Path.run {
                    Async.zip(
                        first.write("first").andThen(first.read),
                        second.write("second").andThen(second.read)
                    )
                }
            }.map(values => assert(values == ("first", "second")))
        }
    }

    "a child Path failure is restored through the parent handler" in {
        FileSystem.inMemory.map { service =>
            val existing = Path("isolate-existing.txt")
            val missing  = Path("isolate-missing.txt")
            Path.runWith(service)(existing.write("value")).andThen {
                Abort.run[FileSystemException] {
                    FileSystem.let(service) {
                        Path.runReadOnly(Async.zip(missing.read, existing.read))
                    }
                }.map { result =>
                    assert(result.isFailure)
                    assert(result.failure.exists(_.isInstanceOf[FileNotFoundException]))
                }
            }
        }
    }

    "a child PathRead panic is restored with the same throwable" in {
        FileSystem.inMemory.map { service =>
            val sentinel = new RuntimeException("path-read-child-panic")
            val existing = Path("isolate-read-panic.txt")
            Path.runWith(service)(existing.write("value")).andThen {
                Abort.run[FileSystemException] {
                    FileSystem.let(service) {
                        Path.runReadOnly {
                            Async.zip(
                                existing.read.andThen(Abort.panic[FileSystemException](sentinel)),
                                existing.read
                            )
                        }
                    }
                }.map {
                    case Result.Panic(actual) => assert(actual eq sentinel)
                    case other                => fail(s"expected child PathRead panic, got: $other")
                }
            }
        }
    }

    "a child PathWrite panic is restored with the same throwable" in {
        FileSystem.inMemory.map { service =>
            val sentinel = new RuntimeException("path-write-child-panic")
            val first    = Path("isolate-write-panic-first.txt")
            val second   = Path("isolate-write-panic-second.txt")
            Abort.run[FileSystemException] {
                FileSystem.let(service) {
                    Path.run {
                        Async.zip(
                            first.write("first").andThen(Abort.panic[FileSystemException](sentinel)),
                            second.write("second")
                        )
                    }
                }
            }.map {
                case Result.Panic(actual) => assert(actual eq sentinel)
                case other                => fail(s"expected child PathWrite panic, got: $other")
            }
        }
    }

    "a failing child PathWrite restores its concrete typed error" in {
        FileSystem.inMemory.map { service =>
            val missingParent = Path("isolate-missing-parent", "child.txt")
            val successful    = Path("isolate-write-success.txt")
            Abort.run[FileSystemException] {
                FileSystem.let(service) {
                    Path.run {
                        Async.zip(
                            missingParent.write("value", Path.WriteOptions(createFolders = false)),
                            successful.write("value")
                        )
                    }
                }
            }.map {
                case Result.Failure(error: FileNotFoundException) => assert(error.path == missingParent)
                case other                                        => fail(s"expected typed FileNotFoundException, got: $other")
            }
        }
    }

    // --- Row-guard block: the streaming, walk, tempDir, tail, and sink methods compile at exactly these rows. ---

    val g1: Stream[String, PathRead & Scope & Sync] = somePath.readStream
    val g2: Stream[String, PathRead & Scope & Sync] = somePath.readStream(StandardCharsets.UTF_8)
    val g3: Stream[String, PathRead & Scope & Sync] = somePath.readStream(StandardCharsets.UTF_8, 8192)
    val g4: Stream[Byte, PathRead & Scope & Sync]   = somePath.readBytesStream
    val g5: Stream[Byte, PathRead & Scope & Sync]   = somePath.readBytesStream(8192)
    val g6: Stream[String, PathRead & Scope & Sync] = somePath.readLinesStream
    val g7: Stream[String, PathRead & Scope & Sync] = somePath.readLinesStream(StandardCharsets.UTF_8)
    val g8: Stream[Path, PathRead & Scope & Sync]   = somePath.walk
    val g9: Stream[Path, PathRead & Scope & Sync]   = somePath.walk(Int.MaxValue, followLinks = false)
    val gList: Chunk[Path] < PathRead               = somePath.list(glob"*.txt")
    val gListCase: Chunk[Path] < PathRead =
        somePath.list(glob"*.txt", Glob.CaseSensitivity.Insensitive)
    val gWalkGlob: Stream[Path, PathRead & Scope & Sync] = somePath.walk(glob"**/*.txt")
    val gWalkGlobCase: Stream[Path, PathRead & Scope & Sync] =
        somePath.walk(glob"**/*.txt", Glob.CaseSensitivity.Sensitive)
    val gWriteOptions: Unit < PathWrite =
        somePath.write("x", Path.WriteOptions(createFolders = false))
    val gMoveOptions: Unit < PathWrite =
        somePath.move(
            otherPath,
            Path.MoveOptions(
                replace = Path.Replace.Existing,
                atomicity = Path.Atomicity.Required,
                createFolders = false
            )
        )
    val gCopyOptions: Unit < PathWrite =
        somePath.copy(
            otherPath,
            Path.CopyOptions(
                followLinks = false,
                replace = Path.Replace.Existing,
                copyAttributes = true,
                createFolders = false
            )
        )
    val gTempDir: Path < (PathWrite & Sync & Scope)     = Path.tempDir()
    val gTail: Stream[String, PathRead & Async & Scope] = somePath.tail
    // sink rows guarded via a byte and a string stream
    val gSinkB: Unit < (Scope & PathWrite & Sync) = Stream.init(Chunk[Byte](1)).writeTo(somePath)
    val gSinkS: Unit < (Scope & PathWrite & Sync) = Stream.init(Chunk("a")).writeTo(somePath)
    val gSinkL: Unit < (Scope & PathWrite & Sync) = Stream.init(Chunk("a")).writeLinesTo(somePath)

    // --- Runtime checks against the host service: returned values, concrete exceptions, streaming, scoped tempDir. ---

    "a read returns its value and a write completes under Path.run" in {
        Scope.run {
            Path.run {
                Path.tempDir().map { dir =>
                    val f = dir / "hello.txt"
                    Abort.run[FileSystemException](Path.run(f.write("hello").andThen(f.read))).map(r =>
                        assert(r == Result.succeed("hello"))
                    )
                }
            }
        }
    }

    "a concrete FileSystemException subtype survives the umbrella row" in {
        val missing = Path("does", "not", "exist-cap.txt")
        Abort.run[FileSystemException](Path.run(missing.read)).map { r =>
            assert(r.isFailure)
            assert(r.failure.exists(_.isInstanceOf[FileNotFoundException]))
        }
    }

    "streaming read yields exact content and releases the handle at Scope exit" in {
        Scope.run {
            Path.run {
                Path.tempDir().map { dir =>
                    val f = dir / "lines.txt"
                    f.writeLines(Chunk("a", "b", "c")).andThen {
                        Scope.run(Path.run(f.readLinesStream.run)).map { lines =>
                            assert(lines == Chunk("a", "b", "c"))
                            // second open succeeds -> the first handle was released
                            Scope.run(Path.run(f.readLinesStream.run)).map(l2 => assert(l2 == Chunk("a", "b", "c")))
                        }
                    }
                }
            }
        }
    }

    "writeTo removes the partial file on failure and keeps it on success" in {
        Scope.run {
            Path.run {
                Path.tempDir().map { dir =>
                    val ok                             = dir / "ok.bin"
                    val bad                            = dir / "bad.bin"
                    val cleanStream: Stream[Byte, Any] = Stream.init(Chunk[Byte](1, 2, 3))
                    val failStream: Stream[Byte, Abort[Throwable]] =
                        Stream.init(Chunk[Byte](1)).concat(Stream.init(Abort.fail(new RuntimeException("boom")).map(_ =>
                            Chunk.empty[Byte]
                        )))
                    Scope.run(Path.run(cleanStream.writeTo(ok))).andThen {
                        Abort.run[Throwable](Scope.run(Path.run(failStream.writeTo(bad)))).andThen {
                            Path.runReadOnly(ok.readBytes).map(bytes =>
                                assert(bytes.toArray sameElements Array(1.toByte, 2.toByte, 3.toByte))
                            ).andThen(
                                Path.runReadOnly(bad.exists).map(e => assert(!e))
                            )
                        }
                    }
                }
            }
        }
    }

    "tempDir creates a directory and removes it recursively at Scope exit" in {
        var created: Option[Path] = None
        Scope.run {
            Path.run {
                Path.tempDir().map { dir =>
                    created = Some(dir)
                    (dir / "child.txt").write("x").andThen(dir.exists.map(e => assert(e)))
                }
            }
        }.andThen(Path.run(Path(created.get.parts*).exists).map(e => assert(!e)))
    }
end PathCapabilityTest
