package kyo

import java.nio.charset.StandardCharsets

class StreamSystemExtensionsTest extends kyo.test.Test[Any]:

    // =========================================================================
    // Content
    // =========================================================================

    "Stream[Byte].writeTo creates file with correct byte content" in {
        val bytes = Array[Byte](10, 20, 30, 40, 50)
        for
            dir <- Path.tempDir("kyo-stream-sink-test")
            file = dir / "stream-byte-write.bin"
            _      <- Scope.run(Stream.init(Chunk.from(bytes)).writeTo(file))
            result <- file.readBytes
            _      <- dir.removeAll
        yield assert(result.toArray.toList == bytes.toList)
        end for
    }

    "Stream[String].writeTo writes concatenated strings" in {
        val parts = List("hello", ", ", "world")
        for
            dir <- Path.tempDir("kyo-stream-sink-test")
            file = dir / "stream-string-write.txt"
            _      <- Scope.run(Stream.init(Chunk.from(parts)).writeTo(file))
            result <- file.read
            _      <- dir.removeAll
        yield assert(result == "hello, world")
        end for
    }

    "Stream[String].writeLinesTo writes each element as a line" in {
        val lines = Chunk("alpha", "beta", "gamma")
        for
            dir <- Path.tempDir("kyo-stream-sink-test")
            file = dir / "stream-lines-write.txt"
            _      <- Scope.run(Stream.init(lines).writeLinesTo(file))
            result <- file.readLines
            _      <- dir.removeAll
        yield assert(result == lines)
        end for
    }

    "Stream[String].writeTo with ISO-8859-1 charset encodes correctly" in {
        val charset = StandardCharsets.ISO_8859_1
        val text    = "caf\u00e9"
        for
            dir <- Path.tempDir("kyo-stream-sink-test")
            file = dir / "stream-charset-write.txt"
            _      <- Scope.run(Stream.init(Chunk(text)).writeTo(file, charset))
            result <- file.read(charset)
            _      <- dir.removeAll
        yield assert(result == text)
        end for
    }

    "Stream[String].writeLinesTo with ISO-8859-1 charset encodes correctly" in {
        val charset = StandardCharsets.ISO_8859_1
        val lines   = Chunk("pr\u00e9", "deux\u00e8me")
        for
            dir <- Path.tempDir("kyo-stream-sink-test")
            file = dir / "stream-lines-charset-write.txt"
            _      <- Scope.run(Stream.init(lines).writeLinesTo(file, charset))
            result <- file.readLines(charset)
            _      <- dir.removeAll
        yield assert(result == lines)
        end for
    }

    // =========================================================================
    // append
    // =========================================================================

    "Stream[Byte].writeTo truncates existing content by default" in {
        for
            dir <- Path.tempDir("kyo-stream-append-test")
            file = dir / "truncated.bin"
            _      <- file.writeBytes(Span.from(Array[Byte](1, 2, 3, 4, 5)))
            _      <- Scope.run(Stream.init(Chunk[Byte](9)).writeTo(file))
            result <- file.readBytes
            _      <- dir.removeAll
        yield assert(result.toArray.toList == List[Byte](9))
        end for
    }

    "Stream[Byte].writeTo with append adds to existing content" in {
        for
            dir <- Path.tempDir("kyo-stream-append-test")
            file = dir / "appended.bin"
            _      <- file.writeBytes(Span.from(Array[Byte](1, 2, 3)))
            _      <- Scope.run(Stream.init(Chunk[Byte](4, 5)).writeTo(file, append = true))
            result <- file.readBytes
            _      <- dir.removeAll
        yield assert(result.toArray.toList == List[Byte](1, 2, 3, 4, 5))
        end for
    }

    "Stream[String].writeTo with append adds to existing content" in {
        for
            dir <- Path.tempDir("kyo-stream-append-test")
            file = dir / "appended.txt"
            _      <- file.write("first")
            _      <- Scope.run(Stream.init(Chunk("second")).writeTo(file, append = true))
            result <- file.read
            _      <- dir.removeAll
        yield assert(result == "firstsecond")
        end for
    }

    "Stream[String].writeLinesTo with append adds lines to existing content" in {
        for
            dir <- Path.tempDir("kyo-stream-append-test")
            file = dir / "appended-lines.txt"
            _      <- file.appendLines(Chunk("first"))
            _      <- Scope.run(Stream.init(Chunk("second", "third")).writeLinesTo(file, append = true))
            result <- file.readLines
            _      <- dir.removeAll
        yield assert(result == Chunk("first", "second", "third"))
        end for
    }

    "writeTo with append leaves existing content when the stream fails" in {
        for
            dir <- Path.tempDir("kyo-stream-append-test")
            file = dir / "append-failure.txt"
            _ <- file.write("keep me")
            result <- Abort.run[FileException] {
                Scope.run {
                    val failingStream: Stream[Byte, Abort[FileWriteException]] =
                        Stream[Byte, Abort[FileWriteException]](
                            Abort.fail(FileIOException(file, new java.io.IOException("stream error")))
                        )
                    failingStream.writeTo(file, append = true)
                }
            }
            exists  <- file.exists
            content <- file.read
            _       <- dir.removeAll
        yield
            assert(result.isFailure)
            assert(exists)
            assert(content == "keep me")
        end for
    }

    // =========================================================================
    // createFolders
    // =========================================================================

    "writeTo creates missing parent directories by default" in {
        for
            dir <- Path.tempDir("kyo-stream-folders-test")
            file = dir / "missing" / "nested" / "created.txt"
            _      <- Scope.run(Stream.init(Chunk("content")).writeTo(file))
            result <- file.read
            _      <- dir.removeAll
        yield assert(result == "content")
        end for
    }

    "writeTo with createFolders = false fails when the parent directory is missing" in {
        for
            dir <- Path.tempDir("kyo-stream-folders-test")
            file = dir / "missing" / "not-created.txt"
            result <- Abort.run[FileException] {
                Scope.run(Stream.init(Chunk("content")).writeTo(file, createFolders = false))
            }
            exists <- file.exists
            _      <- dir.removeAll
        yield
            assert(result.isFailure)
            assert(!exists)
        end for
    }

    "writeLinesTo with createFolders = false fails when the parent directory is missing" in {
        for
            dir <- Path.tempDir("kyo-stream-folders-test")
            file = dir / "missing" / "not-created-lines.txt"
            result <- Abort.run[FileException] {
                Scope.run(Stream.init(Chunk("a", "b")).writeLinesTo(file, createFolders = false))
            }
            exists <- file.exists
            _      <- dir.removeAll
        yield
            assert(result.isFailure)
            assert(!exists)
        end for
    }

    // =========================================================================
    // Empty streams
    // =========================================================================

    "writeTo with empty byte stream creates an empty file" in {
        for
            dir <- Path.tempDir("kyo-stream-sink-test")
            file = dir / "empty-byte-stream.bin"
            _      <- Scope.run(Stream.empty[Byte].writeTo(file))
            exists <- file.exists
            bytes  <- file.readBytes
            _      <- dir.removeAll
        yield assert(exists && bytes.isEmpty)
        end for
    }

    "writeLinesTo with empty stream creates an empty file" in {
        for
            dir <- Path.tempDir("kyo-stream-sink-test")
            file = dir / "empty-lines-stream.txt"
            _      <- Scope.run(Stream.empty[String].writeLinesTo(file))
            exists <- file.exists
            bytes  <- file.readBytes
            _      <- dir.removeAll
        yield assert(exists && bytes.isEmpty)
        end for
    }

    // =========================================================================
    // Failure cleanup (inspired by fs2 #3667)
    // =========================================================================

    // writeTo should not leave a file containing partial data when the input stream fails mid-flight.
    "writeTo does not leave file with partial data when stream fails mid-flight" in {
        for
            dir <- Path.tempDir("kyo-stream-sink-test")
            file = dir / "should-not-have-partial.txt"
            result <- Abort.run[FileWriteException] {
                Scope.run {
                    // A stream that emits one chunk then fails with a FileIOException
                    val badStream: Stream[Byte, Abort[FileWriteException]] =
                        Stream.init(Chunk[Byte](1, 2, 3)).concat(
                            Stream[Byte, Abort[FileWriteException]](
                                Abort.fail(FileIOException(file, new java.io.IOException("stream error")))
                            )
                        )
                    badStream.writeTo(file)
                }
            }
            exists <- file.exists
            bytes  <- Abort.run[FileReadException](file.readBytes)
            _      <- dir.removeAll
        yield
            assert(result.isFailure)
            // The file should either not exist or be empty — not contain partial data.
            // If this assertion fails it means partial data was written and left behind.
            bytes match
                case Result.Success(b) => assert(b.isEmpty, s"Partial data left in file: ${b.size} bytes")
                case Result.Failure(_) => () // file doesn't exist — also acceptable
        end for
    }

    "writeTo with failing stream does not leave corrupt partial file" in {
        for
            dir <- Path.tempDir("kyo-writeto-fail")
            file = dir / "partial.bin"
            result <- Abort.run[FileWriteException] {
                Scope.run {
                    val failingStream: Stream[Byte, Abort[FileWriteException]] =
                        Stream.init(Chunk[Byte](10, 20, 30)).concat(
                            Stream[Byte, Abort[FileWriteException]](
                                Abort.fail(FileIOException(file, new java.io.IOException("mid-stream error")))
                            )
                        )
                    failingStream.writeTo(file)
                }
            }
            bytes <- Abort.run[FileReadException](file.readBytes)
            _     <- dir.removeAll
        yield
            assert(result.isFailure)
            bytes match
                case Result.Success(b) => assert(b.isEmpty, s"Partial data found: ${b.size} bytes")
                case Result.Failure(_) => ()
        end for
    }

end StreamSystemExtensionsTest
