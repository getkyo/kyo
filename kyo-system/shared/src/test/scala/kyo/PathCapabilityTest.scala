package kyo

import java.nio.charset.StandardCharsets
import scala.compiletime.testing.typeCheckErrors

class PathCapabilityTest extends kyo.test.Test[Any]:

    val somePath  = Path("tmp", "cap-a.txt")
    val otherPath = Path("tmp", "cap-b.txt")

    // --- Compile-time capability laws: a green compile proves each row ascription below. ---

    val readOnly: String < PathRead                      = somePath.read
    val writer: Unit < PathWrite                         = somePath.write("x")
    val mixed: String < PathWrite                        = somePath.read.map(s => otherPath.write(s).andThen(s))
    val readRuns: String < (Sync & Abort[FileException]) = Path.runReadOnly(readOnly)
    val writeRuns: Unit < (Sync & Abort[FileException])  = Path.run(writer)

    // --- Combinator effect rows (transaction, sandbox, virtual): Sync must not be required in the residual. ---
    // S = Abort[String] does not subsume Sync; a widened Sync & PathWrite & S return would fail to ascribe.
    val sandboxRow: Unit < (PathWrite & Abort[String]) =
        Path.sandbox[Unit, Abort[String]](writer)
    val transactionRow: Unit < (PathWrite & Abort[CommitConflict] & Abort[String]) =
        Path.transaction[Unit, Abort[String]](writer)
    val virtualRow: (Unit, FileSystem.CommitHandle[Sync]) < (PathWrite & Abort[String]) =
        Path.virtual[Unit, Abort[String]](writer)
    val sandboxRowNoSync: Unit < PathWrite = Path.sandbox(writer)

    "the read-only runner leaves a write undischarged so its residual does not type-check" in {
        val errors = typeCheckErrors(
            """
            given kyo.Frame = kyo.Frame.internal
            val w: Unit < kyo.PathWrite = kyo.Path("x").write("y")
            val bad: Unit < (kyo.Sync & kyo.Abort[kyo.FileException]) = kyo.Path.runReadOnly(w)
            """
        )
        assert(errors.nonEmpty)
        assert(errors.exists(_.message.contains("PathWrite")))
    }

    // --- Row-guard block: the streaming, walk, tempDir, tail, and sink methods compile at exactly these rows. ---

    val g1: Stream[String, PathRead & Scope & Sync]     = somePath.readStream
    val g2: Stream[String, PathRead & Scope & Sync]     = somePath.readStream(StandardCharsets.UTF_8)
    val g3: Stream[String, PathRead & Scope & Sync]     = somePath.readStream(StandardCharsets.UTF_8, 8192)
    val g4: Stream[Byte, PathRead & Scope & Sync]       = somePath.readBytesStream
    val g5: Stream[Byte, PathRead & Scope & Sync]       = somePath.readBytesStream(8192)
    val g6: Stream[String, PathRead & Scope & Sync]     = somePath.readLinesStream
    val g7: Stream[String, PathRead & Scope & Sync]     = somePath.readLinesStream(StandardCharsets.UTF_8)
    val g8: Stream[Path, PathRead & Scope & Sync]       = somePath.walk
    val g9: Stream[Path, PathRead & Scope & Sync]       = somePath.walk(Int.MaxValue, followLinks = false)
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
                    Abort.run[FileException](Path.run(f.write("hello").andThen(f.read))).map(r =>
                        assert(r == Result.succeed("hello"))
                    )
                }
            }
        }
    }

    "a concrete FileException subtype survives the umbrella row" in {
        val missing = Path("does", "not", "exist-cap.txt")
        Abort.run[FileException](Path.run(missing.read)).map { r =>
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
