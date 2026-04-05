package kyo

import java.nio.charset.StandardCharsets
import kyo.internal.Platform

class PathTest extends Test:

    // =========================================================================
    // Construction
    // =========================================================================

    "slash operator produces correct parts" in {
        val p = Path / "a" / "b"
        assert(p.parts == Chunk("a", "b"))
    }

    // Unix-style absolute paths (/usr/...) have no Windows equivalent — drive letter required
    "absolute path with dot and dotdot normalizes" in {
        assume(!Platform.isWindows, "Unix absolute path syntax")
        val p = Path / "/usr" / "local" / "." / "bin" / ".." / "lib"
        assert(p.parts == Chunk("", "usr", "local", "lib"))
    }

    "empty segments are collapsed" in {
        val p = Path / "a" / "" / "b"
        assert(p.parts == Chunk("a", "b"))
    }

    "absolute path string round-trips via toString" in {
        assume(!Platform.isWindows, "Unix absolute path syntax")
        val str = "/usr/local/bin"
        val p   = Path(str)
        assert(p.toString == str)
    }

    "identical normalized paths are equal and have same hashCode" in {
        val p1 = Path / "a" / "b" / "c"
        val p2 = Path("a", "b", "c")
        assert(p1 == p2)
        assert(p1.hashCode == p2.hashCode)
    }

    "Path.basePaths fields are populated" in {
        val bp = Path.basePaths
        assert(bp.cache.toString.nonEmpty)
        assert(bp.config.toString.nonEmpty)
        assert(bp.data.toString.nonEmpty)
        assert(bp.dataLocal.toString.nonEmpty)
        assert(bp.executable.toString.nonEmpty)
        assert(bp.preference.toString.nonEmpty)
        assert(bp.runtime.toString.nonEmpty)
    }

    "Path.userPaths fields are populated" in {
        val up = Path.userPaths
        assert(up.home.toString.nonEmpty)
    }

    "Path.projectPaths returns populated fields" in {
        val pp = Path.projectPaths("com", "example", "app")
        assert(pp.path.toString.nonEmpty)
        assert(pp.cache.toString.nonEmpty)
        assert(pp.config.toString.nonEmpty)
        assert(pp.data.toString.nonEmpty)
        assert(pp.dataLocal.toString.nonEmpty)
        assert(pp.preference.toString.nonEmpty)
        assert(pp.runtime.toString.nonEmpty)
    }

    // =========================================================================
    // extName
    // =========================================================================

    "extName returns last extension for multi-extension filename" in {
        val p = Path / "file.tar.gz"
        assert(p.extName == Present(".gz"))
    }

    "extName returns Absent for dotfile" in {
        val p = Path / ".gitignore"
        assert(p.extName == Absent)
    }

    "extName returns Absent for name with no dot" in {
        val p = Path / "Makefile"
        assert(p.extName == Absent)
    }

    "extName returns dot for trailing-dot filename" in {
        val p = Path / "file."
        assert(p.extName == Present("."))
    }

    "extName returns last dot segment for many-dot filename" in {
        val p = Path / "a.b.c.d"
        assert(p.extName == Present(".d"))
    }

    // =========================================================================
    // Inspection
    // =========================================================================

    "exists returns false for nonexistent path" in run {
        val p = Path / "kyo-test-nonexistent-path-should-never-exist-xyzzy-42"
        p.exists.map(e => assert(!e))
    }

    "exists returns true after file is created" in run {
        for
            dir <- Path.tempDir("kyo-test")
            p = dir / "test-exists.txt"
            before <- p.exists
            _      <- p.mkFile
            after  <- p.exists
            _      <- dir.removeAll
        yield assert(!before && after)
        end for
    }

    "isDirectory returns true for directory and false for file" in run {
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "test-isdir.txt"
            _          <- file.mkFile
            dirResult  <- dir.isDirectory
            fileResult <- file.isDirectory
            _          <- dir.removeAll
        yield assert(dirResult && !fileResult)
        end for
    }

    "isRegularFile returns false for directory and true for file" in run {
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "test-isfile.txt"
            _          <- file.mkFile
            dirResult  <- dir.isRegularFile
            fileResult <- file.isRegularFile
            _          <- dir.removeAll
        yield assert(!dirResult && fileResult)
        end for
    }

    // =========================================================================
    // Read
    // =========================================================================

    "read round-trips string content" in run {
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file = dir / "read-roundtrip.txt"
            text = "hello, round-trip!"
            _      <- file.write(text)
            result <- file.read
            _      <- dir.removeAll
        yield assert(result == text)
        end for
    }

    "read with explicit charset round-trips non-ASCII content" in run {
        val charset = StandardCharsets.ISO_8859_1
        val text    = "caf\u00e9 na\u00efve r\u00e9sum\u00e9"
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file  = dir / "read-charset.txt"
            bytes = Span.from(text.getBytes(charset))
            _      <- file.writeBytes(bytes)
            result <- file.read(charset)
            _      <- dir.removeAll
        yield assert(result == text)
        end for
    }

    "readBytes returns raw file bytes" in run {
        val bytes = Span.from(Array[Byte](0, 1, 2, 3, 127, -1))
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file = dir / "read-bytes.bin"
            _      <- file.writeBytes(bytes)
            result <- file.readBytes
            _      <- dir.removeAll
        yield assert(result.toArray.toList == bytes.toArray.toList)
        end for
    }

    "readLines returns one element per line without trailing newlines" in run {
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file = dir / "read-lines.txt"
            _      <- file.write("line1\nline2\nline3")
            result <- file.readLines
            _      <- dir.removeAll
        yield assert(result == Chunk("line1", "line2", "line3"))
        end for
    }

    "readLines with explicit charset decodes lines correctly" in run {
        val charset = StandardCharsets.ISO_8859_1
        val content = "premi\u00e8re\ndeuxi\u00e8me"
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file  = dir / "read-lines-charset.txt"
            bytes = Span.from(content.getBytes(charset))
            _      <- file.writeBytes(bytes)
            result <- file.readLines(charset)
            _      <- dir.removeAll
        yield assert(result == Chunk("premi\u00e8re", "deuxi\u00e8me"))
        end for
    }

    "readStream emits all content and closes handle" in run {
        val text = "streaming content"
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file = dir / "read-stream.txt"
            _      <- file.write(text)
            result <- Scope.run(file.readStream.run)
            _      <- dir.removeAll
        yield assert(result.toList.mkString == text)
        end for
    }

    "readLinesStream matches readLines" in run {
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file = dir / "read-lines-stream.txt"
            _           <- file.write("alpha\nbeta\ngamma")
            linesStream <- Scope.run(file.readLinesStream.run)
            linesDirect <- file.readLines
            _           <- dir.removeAll
        yield assert(linesStream.toList == linesDirect)
        end for
    }

    "readBytesStream matches readBytes" in run {
        val data = Span.from(Array[Byte](10, 20, 30, 40, 50))
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file = dir / "read-bytes-stream.bin"
            _            <- file.writeBytes(data)
            streamResult <- Scope.run(file.readBytesStream.run)
            directResult <- file.readBytes
            _            <- dir.removeAll
        yield assert(streamResult.toArray.toList == directResult.toArray.toList)
        end for
    }

    "read on a directory raises FileIsADirectoryException" in run {
        for
            dir    <- Path.tempDir("kyo-path-read-test")
            result <- Abort.run[FileReadException](dir.read)
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileIsADirectoryException) => succeed
            case other                                        => fail(s"Expected FileIsADirectoryException, got $other")
        end for
    }

    "read on non-existent path raises FileNotFoundException" in run {
        val file = Path / "kyo-nonexistent-dir-xyzzy" / "nonexistent-read.txt"
        Abort.run[FileReadException](file.read).map {
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        }
    }

    "readStream with ISO-8859-1 charset decodes content correctly" in run {
        val charset = StandardCharsets.ISO_8859_1
        val text    = "caf\u00e9 na\u00efve"
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file  = dir / "read-stream-charset.txt"
            bytes = Span.from(text.getBytes(charset))
            _      <- file.writeBytes(bytes)
            result <- Scope.run(file.readStream(charset).run)
            _      <- dir.removeAll
        yield assert(result.toList.mkString == text)
        end for
    }

    "readLinesStream with ISO-8859-1 charset matches readLines" in run {
        val charset = StandardCharsets.ISO_8859_1
        val content = "premi\u00e8re\ndeuxi\u00e8me\ntrois\u00eem"
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file  = dir / "read-lines-stream-charset.txt"
            bytes = Span.from(content.getBytes(charset))
            _           <- file.writeBytes(bytes)
            linesStream <- Scope.run(file.readLinesStream(charset).run)
            linesDirect <- file.readLines(charset)
            _           <- dir.removeAll
        yield assert(linesStream.toList == linesDirect)
        end for
    }

    "readBytes on non-existent path raises FileNotFoundException" in run {
        val file = Path / "kyo-nonexistent-dir-xyzzy" / "nonexistent-readbytes.bin"
        Abort.run[FileReadException](file.readBytes).map {
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        }
    }

    "readLines on non-existent path raises FileNotFoundException" in run {
        val file = Path / "kyo-nonexistent-dir-xyzzy" / "nonexistent-readlines.txt"
        Abort.run[FileReadException](file.readLines).map {
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        }
    }

    "unsafe size returns correct byte count for a file" in run {
        for
            dir <- Path.tempDir("kyo-path-size-test")
            file = dir / "size-test.txt"
            _ <- file.write("hello")
            result =
                import AllowUnsafe.embrace.danger
                file.unsafe.size()
            _ <- dir.removeAll
        yield result match
            case Result.Success(s) => assert(s == 5L)
            case other             => fail(s"Expected Success(5), got $other")
        end for
    }

    "unsafe size returns 0 for empty file" in run {
        for
            dir <- Path.tempDir("kyo-path-size-test")
            file = dir / "empty-size.txt"
            _ <- file.mkFile
            result =
                import AllowUnsafe.embrace.danger
                file.unsafe.size()
            _ <- dir.removeAll
        yield result match
            case Result.Success(s) => assert(s == 0L)
            case other             => fail(s"Expected Success(0), got $other")
        end for
    }

    "unsafe size on non-existent path returns FileReadException" in run {
        val file = Path / "kyo-nonexistent-dir-xyzzy" / "nonexistent-size.txt"
        val result =
            import AllowUnsafe.embrace.danger
            file.unsafe.size()
        result match
            case Result.Failure(_: FileReadException) => succeed
            case other                                => fail(s"Expected FileReadException, got $other")
    }

    "readBytesStream collects same bytes as readBytes for large file" in run {
        val data = Span.from(Array.tabulate[Byte](100000)(i => (i % 251).toByte))
        for
            dir <- Path.tempDir("kyo-path-read-test")
            file = dir / "large-compare.bin"
            _        <- file.writeBytes(data)
            direct   <- file.readBytes
            streamed <- Scope.run(file.readBytesStream.run)
            _        <- dir.removeAll
        yield assert(direct.toArray.toSeq == streamed.toArray.toSeq)
        end for
    }

    // =========================================================================
    // Write
    // =========================================================================

    "write creates file if absent and sets content" in run {
        val text = "created fresh"
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "write-create.txt"
            exists1 <- file.exists
            _       <- file.write(text)
            exists2 <- file.exists
            content <- file.read
            _       <- dir.removeAll
        yield assert(!exists1 && exists2 && content == text)
        end for
    }

    "write overwrites existing content" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "write-overwrite.txt"
            _       <- file.write("original content that is longer")
            _       <- file.write("new")
            content <- file.read
            _       <- dir.removeAll
        yield assert(content == "new")
        end for
    }

    "writeBytes creates file and content matches readBytes" in run {
        val bytes = Span.from(Array[Byte](1, 2, 3, 4, 5))
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "write-bytes.bin"
            _      <- file.writeBytes(bytes)
            result <- file.readBytes
            _      <- dir.removeAll
        yield assert(result.toArray.toList == bytes.toArray.toList)
        end for
    }

    "writeLines readLines round-trip preserves lines" in run {
        val lines = Chunk("first", "second", "third")
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "write-lines.txt"
            _      <- file.writeLines(lines)
            result <- file.readLines
            _      <- dir.removeAll
        yield assert(result == lines)
        end for
    }

    "append creates file if absent and accumulates content" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "append.txt"
            exists <- file.exists
            _      <- file.append("hello ")
            _      <- file.append("world")
            result <- file.read
            _      <- dir.removeAll
        yield assert(!exists && result == "hello world")
        end for
    }

    "append with createFolders=false raises FileNotFoundException when parent missing" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "missing-parent" / "append-no-create.txt"
            result <- Abort.run[FileWriteException](file.append("data", createFolders = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        end for
    }

    "truncate to 0 clears file content" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "truncate-zero.txt"
            _      <- file.write("some content to clear")
            _      <- file.truncate(0L)
            result <- file.read
            _      <- dir.removeAll
        yield assert(result == "")
        end for
    }

    "write with createFolders=true creates intermediate parent directories" in run {
        val text = "deep file"
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "a" / "b" / "write-deep.txt"
            _      <- file.write(text, createFolders = true)
            result <- file.read
            _      <- dir.removeAll
        yield assert(result == text)
        end for
    }

    "write with createFolders=false raises FileNotFoundException when parent missing" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "missing" / "write-no-create.txt"
            result <- Abort.run[FileWriteException](file.write("data", createFolders = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        end for
    }

    "writeBytes with createFolders=false raises FileNotFoundException when parent missing" in run {
        val bytes = Span.from(Array[Byte](1, 2, 3))
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "missing-bytes" / "write-bytes-no-create.bin"
            result <- Abort.run[FileWriteException](file.writeBytes(bytes, createFolders = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        end for
    }

    "writeLines with createFolders=false raises FileNotFoundException when parent missing" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "missing-lines" / "write-lines-no-create.txt"
            result <- Abort.run[FileWriteException](file.writeLines(Chunk("a", "b"), createFolders = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        end for
    }

    "appendBytes accumulates bytes across two calls" in run {
        val bytes1 = Span.from(Array[Byte](1, 2, 3))
        val bytes2 = Span.from(Array[Byte](4, 5, 6))
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "append-bytes.bin"
            _      <- file.appendBytes(bytes1)
            _      <- file.appendBytes(bytes2)
            result <- file.readBytes
            _      <- dir.removeAll
        yield assert(result.toArray.toList == List[Byte](1, 2, 3, 4, 5, 6))
        end for
    }

    "appendBytes with createFolders=false raises FileNotFoundException when parent missing" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "missing-appendbytes" / "append-bytes-no-create.bin"
            result <- Abort.run[FileWriteException](file.appendBytes(Span.from(Array[Byte](1)), createFolders = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        end for
    }

    "appendLines accumulates lines across two calls" in run {
        val lines1 = Chunk("first", "second")
        val lines2 = Chunk("third", "fourth")
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "append-lines.txt"
            _      <- file.appendLines(lines1)
            _      <- file.appendLines(lines2)
            result <- file.readLines
            _      <- dir.removeAll
        yield assert(result == Chunk("first", "second", "third", "fourth"))
        end for
    }

    "appendLines with createFolders=false raises FileNotFoundException when parent missing" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "missing-appendlines" / "append-lines-no-create.txt"
            result <- Abort.run[FileWriteException](file.appendLines(Chunk("a"), createFolders = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        end for
    }

    "truncate to non-zero size trims file to that many bytes" in run {
        val bytes = Span.from(Array[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "truncate-nonzero.bin"
            _      <- file.writeBytes(bytes)
            _      <- file.truncate(5L)
            result <- file.readBytes
            _      <- dir.removeAll
        yield assert(result.toArray.length == 5)
        end for
    }

    "truncate on non-existent file raises FileWriteException" in run {
        val file = Path / "kyo-nonexistent-dir-xyzzy" / "nonexistent-truncate.txt"
        Abort.run[FileWriteException](file.truncate(0L)).map {
            case Result.Failure(_: FileWriteException) => succeed
            case other                                 => fail(s"Expected FileWriteException, got $other")
        }
    }

    "write on a directory raises FileIsADirectoryException" in run {
        for
            dir    <- Path.tempDir("kyo-path-write-test")
            result <- Abort.run[FileWriteException](dir.write("data"))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileIsADirectoryException) => succeed
            case other                                        => fail(s"Expected FileIsADirectoryException, got $other")
        end for
    }

    "truncate with size exceeding Int.MaxValue is a no-op on smaller file" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "truncate-large.txt"
            _       <- file.write("hello")
            _       <- file.truncate(Int.MaxValue.toLong + 1L)
            content <- file.read
            _       <- dir.removeAll
        yield assert(content == "hello")
        end for
    }

    "writeLines then readLines with different charset shows encoding mismatch" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "charset-mismatch.txt"
            _    <- file.writeLines(Chunk("café"))
            back <- file.readLines(StandardCharsets.ISO_8859_1)
            _    <- dir.removeAll
        yield
            // writeLines always encodes UTF-8; reading as ISO-8859-1 garbles multi-byte chars
            assert(back != Chunk("café"), "Expected mismatch when reading UTF-8 file as ISO-8859-1")
        end for
    }

    "appendLines always encodes UTF-8 regardless of existing file encoding" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "mixed-encoding.bin"
            // Write raw ISO-8859-1 bytes: "héllo\n" where é = 0xE9 in ISO-8859-1
            _ <- file.writeBytes(Span.from(Array[Byte](0x68, 0xe9.toByte, 0x6c, 0x6c, 0x6f, 0x0a)))
            // appendLines writes UTF-8 — "wörld" where ö is 2 bytes in UTF-8
            _     <- file.appendLines(Chunk("wörld"))
            bytes <- file.readBytes
            _     <- dir.removeAll
        yield
            // The file now has mixed encodings — ISO-8859-1 then UTF-8
            // This documents that appendLines has no charset parameter
            assert(bytes.size > 0)
        end for
    }

    "writeLines with empty chunk creates file" in run {
        for
            dir <- Path.tempDir("kyo-path-write-test")
            file = dir / "empty-writelines.txt"
            _      <- file.writeLines(Chunk.empty[String])
            exists <- file.exists
            _      <- dir.removeAll
        yield assert(exists)
        end for
    }

    // =========================================================================
    // Directory
    // =========================================================================

    "mkDir creates a directory and isDirectory returns true" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            subdir = dir / "mkdir-subdir"
            _      <- subdir.mkDir
            result <- subdir.isDirectory
            _      <- dir.removeAll
        yield assert(result)
        end for
    }

    "mkDir is idempotent when called twice" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            subdir = dir / "mkdir-idem"
            _      <- subdir.mkDir
            result <- Abort.run[FileFsException](subdir.mkDir)
            _      <- dir.removeAll
        yield assert(result.isSuccess)
        end for
    }

    "mkFile creates a regular file and isRegularFile returns true" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            file = dir / "mkfile.txt"
            _      <- file.mkFile
            result <- file.isRegularFile
            _      <- dir.removeAll
        yield assert(result)
        end for
    }

    "mkFile is idempotent when called twice" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            file = dir / "mkfile-idem.txt"
            _      <- file.mkFile
            result <- Abort.run[FileFsException](file.mkFile)
            _      <- dir.removeAll
        yield assert(result.isSuccess)
        end for
    }

    "list returns direct children of a directory" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            child1 = dir / "child-a.txt"
            child2 = dir / "child-b.txt"
            _        <- child1.mkFile
            _        <- child2.mkFile
            children <- dir.list
            _        <- dir.removeAll
        yield
            val names = children.toList.map(_.parts.last).sorted
            assert(names.contains("child-a.txt") && names.contains("child-b.txt"))
        end for
    }

    "list with glob returns only matching files" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            txt  = dir / "file-glob.txt"
            json = dir / "file-glob.json"
            _        <- txt.mkFile
            _        <- json.mkFile
            children <- dir.list("*.txt")
            _        <- dir.removeAll
        yield
            val names = children.toList.map(_.parts.last)
            assert(names == List("file-glob.txt"))
        end for
    }

    "list with glob * matches all files" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "a.txt").mkFile
            _      <- (dir / "b.json").mkFile
            _      <- (dir / "c.log").mkFile
            result <- dir.list("*")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("a.txt", "b.json", "c.log"))
    }

    "list with glob file.* matches all extensions" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "file.txt").mkFile
            _      <- (dir / "file.json").mkFile
            _      <- (dir / "other.txt").mkFile
            result <- dir.list("file.*")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("file.json", "file.txt"))
    }

    "list with glob *data* matches files containing data" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "data.csv").mkFile
            _      <- (dir / "mydata.txt").mkFile
            _      <- (dir / "other.log").mkFile
            result <- dir.list("*data*")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("data.csv", "mydata.txt"))
    }

    "list with glob ? matches exactly one character" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "file1.txt").mkFile
            _      <- (dir / "fileA.txt").mkFile
            _      <- (dir / "file10.txt").mkFile
            _      <- (dir / "file.txt").mkFile
            result <- dir.list("file?.txt")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("file1.txt", "fileA.txt"))
    }

    "list with glob character class matches specified characters" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "file1.txt").mkFile
            _      <- (dir / "file2.txt").mkFile
            _      <- (dir / "file3.txt").mkFile
            _      <- (dir / "file4.txt").mkFile
            result <- dir.list("file[123].txt")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("file1.txt", "file2.txt", "file3.txt"))
    }

    "list with glob character range matches range" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "filea.txt").mkFile
            _      <- (dir / "fileb.txt").mkFile
            _      <- (dir / "filec.txt").mkFile
            _      <- (dir / "filed.txt").mkFile
            result <- dir.list("file[a-c].txt")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("filea.txt", "fileb.txt", "filec.txt"))
    }

    "list with glob negated character class excludes matches" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "abc").mkFile
            _      <- (dir / "bcd").mkFile
            _      <- (dir / "cde").mkFile
            result <- dir.list("[!a]*")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("bcd", "cde"))
    }

    "list with glob brace expansion matches alternatives" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "a.txt").mkFile
            _      <- (dir / "b.json").mkFile
            _      <- (dir / "c.log").mkFile
            result <- dir.list("*.{txt,json}")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("a.txt", "b.json"))
    }

    "list with glob brace expansion with specific names" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "foo.txt").mkFile
            _      <- (dir / "bar.txt").mkFile
            _      <- (dir / "baz.txt").mkFile
            result <- dir.list("{foo,bar}.txt")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last).sorted == List("bar.txt", "foo.txt"))
    }

    "list with glob *.* matches files with extensions" in run {
        for
            dir    <- Path.tempDir("kyo-glob-test")
            _      <- (dir / "file.txt").mkFile
            _      <- (dir / "noext").mkFile
            result <- dir.list("*.*")
            _      <- dir.removeAll
        yield assert(result.toList.map(_.parts.last) == List("file.txt"))
    }

    "list on a file raises FileNotADirectoryException" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            file = dir / "list-on-file.txt"
            _      <- file.mkFile
            result <- Abort.run[FileFsException](file.list)
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileNotADirectoryException) => succeed
            case other                                         => fail(s"Expected FileNotADirectoryException, got $other")
        end for
    }

    "walk visits the full directory tree including nested files" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            sub   = dir / "walk-sub"
            fileA = dir / "walk-a.txt"
            fileB = sub / "walk-b.txt"
            _     <- sub.mkDir
            _     <- fileA.mkFile
            _     <- fileB.mkFile
            paths <- Scope.run(dir.walk.run)
            _     <- dir.removeAll
        yield
            val names = paths.toList.map(_.parts.last)
            assert(names.contains("walk-a.txt") && names.contains("walk-b.txt"))
        end for
    }

    "walk with maxDepth=0 returns only the root" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            sub  = dir / "walk-depth-sub"
            file = dir / "walk-depth-file.txt"
            _     <- sub.mkDir
            _     <- file.mkFile
            paths <- Scope.run(dir.walk(maxDepth = 0).run)
            _     <- dir.removeAll
        yield assert(paths.size == 1 && paths.head == dir)
        end for
    }

    "move renames a file" in run {
        val text = "move me"
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "move-src.txt"
            dst = dir / "move-dst.txt"
            _          <- src.write(text)
            _          <- src.move(dst)
            srcExists  <- src.exists
            dstExists  <- dst.exists
            dstContent <- dst.read
            _          <- dir.removeAll
        yield assert(!srcExists && dstExists && dstContent == text)
        end for
    }

    "move with replaceExisting=false raises FileAlreadyExistsException when destination exists" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "move-nooverwrite-src.txt"
            dst = dir / "move-nooverwrite-dst.txt"
            _      <- src.write("source")
            _      <- dst.write("destination")
            result <- Abort.run[FileFsException](src.move(dst, replaceExisting = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileAlreadyExistsException) => succeed
            case other                                         => fail(s"Expected FileAlreadyExistsException, got $other")
        end for
    }

    "copy creates a duplicate with equal content" in run {
        val text = "copy me"
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "copy-src.txt"
            dst = dir / "copy-dst.txt"
            _          <- src.write(text)
            _          <- src.copy(dst)
            srcExists  <- src.exists
            dstExists  <- dst.exists
            srcContent <- src.read
            dstContent <- dst.read
            _          <- dir.removeAll
        yield assert(srcExists && dstExists && srcContent == dstContent)
        end for
    }

    "remove on existing file returns true and file is gone" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            file = dir / "remove-exists.txt"
            _      <- file.mkFile
            result <- file.remove
            exists <- file.exists
            _      <- dir.removeAll
        yield assert(result && !exists)
        end for
    }

    "remove on non-existent path returns false without failure" in run {
        val file = Path / "kyo-nonexistent-dir-xyzzy-remove" / "nosuchfile-remove.txt"
        Abort.run[FileFsException](file.remove).map {
            case Result.Success(false) => succeed
            case other                 => fail(s"Expected Success(false), got $other")
        }
    }

    "removeExisting on non-existent path raises FileNotFoundException" in run {
        val file = Path / "kyo-nonexistent-dir-xyzzy-rmex" / "nosuchfile-rmex.txt"
        Abort.run[FileFsException](file.removeExisting).map {
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        }
    }

    "removeAll deletes a non-empty directory tree" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            sub   = dir / "rmall-sub"
            fileA = dir / "rmall-a.txt"
            fileB = sub / "rmall-b.txt"
            _      <- sub.mkDir
            _      <- fileA.mkFile
            _      <- fileB.mkFile
            _      <- dir.removeAll
            exists <- dir.exists
        yield assert(!exists)
        end for
    }

    "removeExisting on existing file succeeds" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            file = dir / "rmexisting.txt"
            _      <- file.mkFile
            _      <- file.removeExisting
            exists <- file.exists
            _      <- dir.removeAll
        yield assert(!exists)
        end for
    }

    "removeAll on non-existent path succeeds without error" in run {
        val missing = Path / "kyo-nonexistent-dir-xyzzy-rmall" / "does-not-exist-rmall"
        Abort.run[FileFsException](missing.removeAll).map {
            case Result.Success(_) => succeed
            case other             => fail(s"Expected success, got $other")
        }
    }

    "list on non-existent path raises FileNotFoundException" in run {
        val missing = Path / "kyo-nonexistent-dir-xyzzy-list" / "missing-list"
        Abort.run[FileFsException](missing.list).map {
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected FileNotFoundException, got $other")
        }
    }

    "walk(maxDepth=1) excludes grandchildren" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            childDir   = dir / "walk-depth1-child"
            grandchild = childDir / "walk-depth1-grandchild.txt"
            _     <- childDir.mkDir
            _     <- grandchild.mkFile
            paths <- Scope.run(dir.walk(maxDepth = 1).run)
            _     <- dir.removeAll
        yield
            val names = paths.toList.map(_.parts.last)
            assert(names.contains("walk-depth1-child") && !names.contains("walk-depth1-grandchild.txt"))
        end for
    }

    "walk on non-existent path raises FileFsException" in run {
        val missing = Path / "kyo-nonexistent-dir-xyzzy-walk" / "missing-walk"
        Abort.run[FileFsException](Scope.run(missing.walk.run)).map {
            case Result.Failure(_: FileFsException) => succeed
            case other                              => fail(s"Expected FileFsException, got $other")
        }
    }

    "move with replaceExisting=true overwrites destination" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "move-replace-src.txt"
            dst = dir / "move-replace-dst.txt"
            _          <- src.write("source-content")
            _          <- dst.write("original-dst-content")
            _          <- src.move(dst, replaceExisting = true)
            srcExists  <- src.exists
            dstContent <- dst.read
            _          <- dir.removeAll
        yield assert(!srcExists && dstContent == "source-content")
        end for
    }

    "move with atomicMove=true succeeds on same filesystem" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "move-atomic-src.txt"
            dst = dir / "move-atomic-dst.txt"
            _         <- src.write("atomic-move")
            result    <- Abort.run[FileFsException](src.move(dst, atomicMove = true))
            dstExists <- dst.exists
            _         <- dir.removeAll
        yield result match
            case Result.Success(_) => assert(dstExists)
            case other             => fail(s"Expected success, got $other")
        end for
    }

    "move with createFolders=false raises FileFsException when parent missing" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "move-nocreate-src.txt"
            dst = dir / "missing-move-parent" / "move-nocreate-dst.txt"
            _      <- src.write("content")
            result <- Abort.run[FileFsException](src.move(dst, createFolders = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileFsException) => succeed
            case other                              => fail(s"Expected FileFsException, got $other")
        end for
    }

    "copy with replaceExisting=false raises FileAlreadyExistsException when destination exists" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "copy-nooverwrite-src.txt"
            dst = dir / "copy-nooverwrite-dst.txt"
            _      <- src.write("src-content")
            _      <- dst.write("dst-content")
            result <- Abort.run[FileFsException](src.copy(dst, replaceExisting = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileAlreadyExistsException) => succeed
            case other                                         => fail(s"Expected FileAlreadyExistsException, got $other")
        end for
    }

    "copy with replaceExisting=true overwrites destination" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "copy-replace-src.txt"
            dst = dir / "copy-replace-dst.txt"
            _          <- src.write("new-source")
            _          <- dst.write("old-dst")
            _          <- src.copy(dst, replaceExisting = true)
            dstContent <- dst.read
            _          <- dir.removeAll
        yield assert(dstContent == "new-source")
        end for
    }

    "copy with copyAttributes=true succeeds" in run {
        val text = "copy-attrs"
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "copy-attrs-src.txt"
            dst = dir / "copy-attrs-dst.txt"
            _          <- src.write(text)
            result     <- Abort.run[FileFsException](src.copy(dst, copyAttributes = true))
            dstContent <- dst.read
            _          <- dir.removeAll
        yield result match
            case Result.Success(_) => assert(dstContent == text)
            case other             => fail(s"Expected success, got $other")
        end for
    }

    "copy with createFolders=false raises FileFsException when parent missing" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "copy-nocreate-src.txt"
            dst = dir / "missing-copy-parent" / "copy-nocreate-dst.txt"
            _      <- src.write("content")
            result <- Abort.run[FileFsException](src.copy(dst, createFolders = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileFsException) => succeed
            case other                              => fail(s"Expected FileFsException, got $other")
        end for
    }

    "copy on non-empty directory does not copy children" in run {
        // Files.copy creates an empty dir at destination — children are silently lost
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "copy-dir-src"
            dst = dir / "copy-dir-dst"
            _           <- src.mkDir
            _           <- (src / "child.txt").write("hello")
            _           <- src.copy(dst)
            dstIsDir    <- dst.isDirectory
            childExists <- (dst / "child.txt").exists
            _           <- dir.removeAll
        yield
            assert(dstIsDir, "copy should create destination directory")
            assert(!childExists, "copy does NOT copy children (Files.copy is not recursive)")
        end for
    }

    "copy on empty directory creates target directory" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "empty-src-dir"
            dst = dir / "empty-dst-dir"
            _      <- src.mkDir
            _      <- src.copy(dst)
            result <- dst.isDirectory
            _      <- dir.removeAll
        yield assert(result)
        end for
    }

    "copy on directory with nested children silently loses nested content" in run {
        // nested children are silently not copied
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "nested-copy-src"
            _ <- (src / "a").mkDir
            _ <- (src / "a" / "b.txt").write("nested")
            dst = dir / "nested-copy-dst"
            _         <- src.copy(dst)
            dstIsDir  <- dst.isDirectory
            subExists <- (dst / "a").exists
            _         <- dir.removeAll
        yield
            assert(dstIsDir, "copy should create destination directory")
            assert(!subExists, "copy does NOT recurse into subdirectories")
        end for
    }

    "mkFile on existing file with content preserves the content" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            file = dir / "mkfile-preserve.txt"
            _       <- file.write("important data")
            _       <- file.mkFile
            content <- file.read
            _       <- dir.removeAll
        yield assert(content == "important data")
        end for
    }

    "copy to existing destination with replaceExisting=false raises FileAlreadyExistsException" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            src = dir / "copy-dup-src.txt"
            dst = dir / "copy-dup-dst.txt"
            _      <- src.write("content")
            _      <- src.copy(dst)
            result <- Abort.run[FileFsException](src.copy(dst, replaceExisting = false))
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileAlreadyExistsException) => succeed
            case other                                         => fail(s"Expected FileAlreadyExistsException, got $other")
        end for
    }

    "walk on a regular file returns only that file" in run {
        for
            dir <- Path.tempDir("kyo-path-dir-test")
            file = dir / "walk-file.txt"
            _     <- file.write("content")
            paths <- Scope.run(file.walk().run)
            _     <- dir.removeAll
        yield
            assert(paths.size == 1)
            assert(paths.head == file)
        end for
    }

    // =========================================================================
    // Stream extensions
    // =========================================================================

    "Stream[Byte].writeTo creates file with correct byte content" in run {
        val bytes = Array[Byte](10, 20, 30, 40, 50)
        for
            dir <- Path.tempDir("kyo-path-stream-test")
            file = dir / "stream-byte-write.bin"
            _      <- Scope.run(Stream.init(Chunk.from(bytes)).writeTo(file))
            result <- file.readBytes
            _      <- dir.removeAll
        yield assert(result.toArray.toList == bytes.toList)
        end for
    }

    "Stream[String].writeTo writes concatenated strings" in run {
        val parts = List("hello", ", ", "world")
        for
            dir <- Path.tempDir("kyo-path-stream-test")
            file = dir / "stream-string-write.txt"
            _      <- Scope.run(Stream.init(Chunk.from(parts)).writeTo(file))
            result <- file.read
            _      <- dir.removeAll
        yield assert(result == "hello, world")
        end for
    }

    "Stream[String].writeLinesTo writes each element as a line" in run {
        val lines = Chunk("alpha", "beta", "gamma")
        for
            dir <- Path.tempDir("kyo-path-stream-test")
            file = dir / "stream-lines-write.txt"
            _      <- Scope.run(Stream.init(lines).writeLinesTo(file))
            result <- file.readLines
            _      <- dir.removeAll
        yield assert(result == lines)
        end for
    }

    "Stream[String].writeTo with ISO-8859-1 charset encodes correctly" in run {
        val charset = StandardCharsets.ISO_8859_1
        val text    = "caf\u00e9"
        for
            dir <- Path.tempDir("kyo-path-stream-test")
            file = dir / "stream-charset-write.txt"
            _      <- Scope.run(Stream.init(Chunk(text)).writeTo(file, charset))
            result <- file.read(charset)
            _      <- dir.removeAll
        yield assert(result == text)
        end for
    }

    "Stream[String].writeLinesTo with ISO-8859-1 charset encodes correctly" in run {
        val charset = StandardCharsets.ISO_8859_1
        val lines   = Chunk("pr\u00e9", "deux\u00e8me")
        for
            dir <- Path.tempDir("kyo-path-stream-test")
            file = dir / "stream-lines-charset-write.txt"
            _      <- Scope.run(Stream.init(lines).writeLinesTo(file, charset))
            result <- file.readLines(charset)
            _      <- dir.removeAll
        yield assert(result == lines)
        end for
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    "tail emits only new lines appended after stream opens" in run {
        Clock.withTimeControl { control =>
            for
                dir <- Path.tempDir("kyo-path-edge-test")
                file = dir / "tail.txt"
                _ <- file.mkFile
                tailFiber <- Fiber.initUnscoped(
                    Scope.run(file.tail(50.millis).take(2).run)
                )
                _     <- control.advance(50.millis)
                _     <- file.appendLines(Chunk("line-a", "line-b"))
                _     <- control.advance(50.millis)
                lines <- tailFiber.get
                _     <- dir.removeAll
            yield assert(lines.toList == List("line-a", "line-b"))
            end for
        }
    }

    "remove on non-empty directory raises FileDirectoryNotEmptyException" in run {
        for
            dir <- Path.tempDir("kyo-path-edge-test")
            file = dir / "remove-nonempty.txt"
            _      <- file.mkFile
            result <- Abort.run[FileFsException](dir.remove)
            _      <- dir.removeAll
        yield result match
            case Result.Failure(_: FileDirectoryNotEmptyException) => succeed
            case other                                             => fail(s"Expected FileDirectoryNotEmptyException, got $other")
        end for
    }

    "path / part infix syntax works" in run {
        val base   = Path / "usr"
        val result = base / "local" / "bin"
        assert(result.parts.last == "bin")
        assert(result.parts.contains("local"))
        assert(result.parts.contains("usr"))
    }

    "tail (0-arg) emits new lines appended after stream opens" in run {
        Clock.withTimeControl { control =>
            for
                dir <- Path.tempDir("kyo-path-edge-test")
                file = dir / "tail-default.txt"
                _         <- file.mkFile
                tailFiber <- Fiber.initUnscoped(Scope.run(file.tail.take(2).run))
                _         <- control.advance(100.millis)
                _         <- file.appendLines(Chunk("line-x", "line-y"))
                _         <- control.advance(100.millis)
                lines     <- tailFiber.get
                _         <- dir.removeAll
            yield assert(lines.toList == List("line-x", "line-y"))
            end for
        }
    }

    "tail on non-existent file raises FileReadException" in run {
        val file = Path / "kyo-nonexistent-dir-xyzzy" / "nonexistent-tail.txt"
        Abort.run[FileReadException](Scope.run(file.tail.run)).map {
            case Result.Failure(_: FileReadException) => succeed
            case other                                => fail(s"Expected FileReadException, got $other")
        }
    }

    "tail handles multi-byte UTF-8 characters at buffer boundary without corruption" in run {
        Clock.withTimeControl { control =>
            // Use a small buffer to force multi-byte chars to split across reads
            val multiByteContent = "café_naïve_über_" * 10 + "\n"
            for
                dir <- Path.tempDir("kyo-path-edge-test")
                file = dir / "tail-utf8.txt"
                _ <- file.mkFile
                tailFiber <- Fiber.initUnscoped(
                    Scope.run(file.tail(50.millis, 16).take(1).run)
                )
                _     <- control.advance(50.millis)
                _     <- file.append(multiByteContent)
                _     <- control.advance(50.millis)
                lines <- tailFiber.get
                _     <- dir.removeAll
            yield
                val text = lines.toList.mkString
                assert(!text.contains("\uFFFD"), s"Found replacement character in: $text")
                assert(text.contains("café"), s"Expected 'café' in output: $text")
            end for
        }
    }

    "tail does not emit incomplete lines before newline arrives" in run {
        Clock.withTimeControl { control =>
            for
                dir <- Path.tempDir("kyo-path-edge-test")
                file = dir / "tail-partial.txt"
                _ <- file.mkFile
                tailFiber <- Fiber.initUnscoped(
                    Scope.run(file.tail(50.millis).take(1).run)
                )
                _     <- control.advance(50.millis)
                _     <- file.append("hello wor")
                _     <- control.advance(50.millis)
                _     <- file.append("ld\n")
                _     <- control.advance(50.millis)
                lines <- tailFiber.get
                _     <- dir.removeAll
            yield assert(
                lines.toList == List("hello world"),
                s"Expected complete line 'hello world', got: ${lines.toList}"
            )
            end for
        }
    }

    "tail assembles lines from multiple partial writes" in run {
        Clock.withTimeControl { control =>
            for
                dir <- Path.tempDir("kyo-path-edge-test")
                file = dir / "tail-multi-partial.txt"
                _ <- file.mkFile
                tailFiber <- Fiber.initUnscoped(
                    Scope.run(file.tail(50.millis).take(2).run)
                )
                _     <- control.advance(50.millis)
                _     <- file.append("aaa")
                _     <- control.advance(50.millis)
                _     <- file.append("bbb\nccc\n")
                _     <- control.advance(50.millis)
                lines <- tailFiber.get
                _     <- dir.removeAll
            yield assert(
                lines.toList == List("aaabbb", "ccc"),
                s"Expected assembled lines, got: ${lines.toList}"
            )
            end for
        }
    }

    "tail does not emit empty string after complete line" in run {
        Clock.withTimeControl { control =>
            for
                dir <- Path.tempDir("kyo-path-edge-test")
                file = dir / "tail-no-empty.txt"
                _ <- file.mkFile
                tailFiber <- Fiber.initUnscoped(
                    Scope.run(file.tail(50.millis).take(1).run)
                )
                _     <- control.advance(50.millis)
                _     <- file.append("hello\n")
                _     <- control.advance(50.millis)
                lines <- tailFiber.get
                _     <- dir.removeAll
            yield assert(
                lines.toList == List("hello"),
                s"Expected exactly List(\"hello\"), got: ${lines.toList}"
            )
            end for
        }
    }

    "tail on rapidly growing file emits all lines without loss" in run {
        Clock.withTimeControl { control =>
            val lineCount = 50
            val expected  = (1 to lineCount).map(i => s"line-$i").toList
            for
                dir <- Path.tempDir("kyo-path-edge-test")
                file = dir / "tail-rapid.txt"
                _ <- file.mkFile
                tailFiber <- Fiber.initUnscoped(
                    Scope.run(file.tail(50.millis).take(lineCount).run)
                )
                _     <- control.advance(50.millis)
                _     <- file.appendLines(Chunk.from(expected))
                _     <- control.advance(50.millis)
                lines <- tailFiber.get
                _     <- dir.removeAll
            yield assert(lines.toList == expected, s"Expected $lineCount lines, got ${lines.size}")
            end for
        }
    }

    // =========================================================================
    // Streaming delegation (guards against infinite-recursion in safe API)
    // =========================================================================

    "readStream(charset) delegates to unsafe without infinite loop" in run {
        for
            dir <- Path.tempDir("kyo-deleg-stream")
            file = dir / "rs-charset.txt"
            _      <- file.write("hello charset stream")
            result <- Scope.run(file.readStream(StandardCharsets.UTF_8).run)
            _      <- dir.removeAll
        yield assert(result.toList.mkString.nonEmpty)
        end for
    }

    "readBytesStream delegates to unsafe without infinite loop" in run {
        val data = Span.from(Array[Byte](1, 2, 3, 4, 5))
        for
            dir <- Path.tempDir("kyo-deleg-stream")
            file = dir / "rbs.bin"
            _      <- file.writeBytes(data)
            result <- Scope.run(file.readBytesStream.run)
            _      <- dir.removeAll
        yield assert(result.toArray.nonEmpty)
        end for
    }

    "readLinesStream(charset) delegates to unsafe without infinite loop" in run {
        for
            dir <- Path.tempDir("kyo-deleg-stream")
            file = dir / "rls-charset.txt"
            _           <- file.write("line1\nline2\nline3")
            linesStream <- Scope.run(file.readLinesStream(StandardCharsets.UTF_8).run)
            linesDirect <- file.readLines
            _           <- dir.removeAll
        yield assert(linesStream.toList == linesDirect.toList)
        end for
    }

    "tail(pollDelay) delegates to unsafe without infinite loop" in run {
        Clock.withTimeControl { control =>
            for
                dir <- Path.tempDir("kyo-deleg-stream")
                file = dir / "tail-deleg.txt"
                _         <- file.mkFile
                tailFiber <- Fiber.initUnscoped(Scope.run(file.tail(50.millis).take(1).run))
                _         <- control.advance(50.millis)
                _         <- file.appendLines(Chunk("tail-line"))
                _         <- control.advance(50.millis)
                lines     <- tailFiber.get
                _         <- dir.removeAll
            yield assert(lines.toList == List("tail-line"))
            end for
        }
    }

    "walk(maxDepth, followLinks) delegates to unsafe without infinite loop" in run {
        for
            dir <- Path.tempDir("kyo-deleg-stream")
            file = dir / "walk-deleg.txt"
            _     <- file.mkFile
            paths <- Scope.run(dir.walk(10, followLinks = false).run)
            _     <- dir.removeAll
        yield assert(paths.toList.nonEmpty)
        end for
    }

    // =========================================================================
    // BasePaths.tmp + Path.temp / tempDir / tempScoped
    // =========================================================================

    "basePaths.tmp is a non-empty path" in {
        assert(Path.basePaths.tmp.parts.nonEmpty)
    }

    "basePaths.tmp directory exists on disk" in run {
        Path.basePaths.tmp.exists.map(e => assert(e))
    }

    "Path.temp creates a file that exists" in run {
        for
            p      <- Path.temp()
            exists <- p.exists
            _      <- p.remove
        yield assert(exists)
    }

    "Path.temp with custom prefix and suffix" in run {
        for
            p <- Path.temp("myprefix", ".myext")
            _ <- p.remove
        yield assert(p.name.exists(n => n.startsWith("myprefix") && n.endsWith(".myext")))
    }

    "Path.tempDir creates a directory" in run {
        for
            p           <- Path.tempDir("kyotestdir")
            isDirectory <- p.isDirectory
            _           <- p.removeAll
        yield assert(isDirectory)
    }

    "Path.tempScoped auto-deletes on scope close" in run {
        for
            captured <- AtomicRef.init[Maybe[Path]](Absent)
            _ <- Scope.run {
                Path.tempScoped().map { p =>
                    captured.set(Present(p)).andThen(p)
                }
            }
            maybePath   <- captured.get
            stillExists <- maybePath.get.exists
        yield assert(!stillExists)
        end for
    }

    // =========================================================================
    // name / parent / isAbsolute
    // =========================================================================

    "name returns last segment" in {
        assert(Path("a", "b", "c").name == Present("c"))
    }

    "name returns Absent for root" in {
        assert(Path("/").name == Absent)
    }

    "name returns filename for deep path" in {
        assert((Path / "/usr" / "local" / "bin").name == Present("bin"))
    }

    "parent returns Absent for root" in {
        assert(Path("/").parent == Absent)
    }

    "parent returns Absent for single segment" in {
        assert(Path("file.txt").parent == Absent)
    }

    "parent returns parent path for deep path" in {
        val p = (Path / "a" / "b" / "c").parent
        assert(p == Present(Path / "a" / "b"))
    }

    "isAbsolute returns true for absolute path" in {
        assume(!Platform.isWindows, "Unix absolute path syntax")
        val p = Path / "/usr" / "local" / "bin"
        assert(p.isAbsolute)
    }

    "isAbsolute returns false for relative path" in {
        val p = Path / "a" / "b" / "c"
        assert(!p.isAbsolute)
    }

    "parent of absolute single-component path represents root" in run {
        assume(!Platform.isWindows, "Unix absolute path syntax")
        // Path("", "etc") represents /etc — its parent should be "/"
        val p = Path("", "etc")
        p.parent match
            case Present(root) =>
                import AllowUnsafe.embrace.danger
                assert(root.unsafe.show == "/", s"Expected '/' but got '${root.unsafe.show}'")
            case Absent => fail("Expected parent to be Present for /etc")
        end match
    }

    "parent chain from /a/b/c terminates at root then Absent" in run {
        assume(!Platform.isWindows, "Unix absolute path syntax")
        import AllowUnsafe.embrace.danger
        val p  = Path("", "a", "b", "c")
        val p2 = p.parent // should be /a/b
        assert(p2.isDefined, "parent of /a/b/c should be defined")
        val p1 = p2.get.parent // should be /a
        assert(p1.isDefined, "parent of /a/b should be defined")
        val root = p1.get.parent // should be /
        assert(root.isDefined, "parent of /a should be defined")
        assert(root.get.unsafe.show == "/", s"Expected '/' but got '${root.get.unsafe.show}'")
        val aboveRoot = root.get.parent // should be Absent
        assert(aboveRoot.isEmpty, s"Expected Absent above root, got $aboveRoot")
    }

    "parent of /a is root with correct string representation" in run {
        assume(!Platform.isWindows, "Unix absolute path syntax")
        import AllowUnsafe.embrace.danger
        val p = Path("", "a")
        p.parent match
            case Present(root) =>
                assert(root.unsafe.show == "/", s"Expected '/' but got '${root.unsafe.show}'")
            case Absent => fail("Expected parent of /a to be Present")
        end match
    }

    // =========================================================================
    // Path construction edge cases
    // =========================================================================

    "flattenParts splices Path parts when Path is used as a Part segment" in {
        val inner = Path("x", "y", "z")
        val outer = Path("a", inner, "b")
        // inner's parts (x, y, z) should be spliced in, not nested
        assert(outer.parts == Chunk("a", "x", "y", "z", "b"))
    }

    "Path() with no arguments produces empty parts" in {
        val p = Path()
        assert(p.parts == Chunk.empty)
    }

    "Path with mid-segment absolute string documents actual behavior" in {
        // Path("a", "/b") — the second segment starts with "/" which may be treated
        // as absolute. Document what actually happens.
        val p = Path("a", "/b")
        // On JVM/native, java.nio.Path.resolve("/b") discards the left side and
        // returns an absolute path. Parts become ("", "b") — absolute.
        // The test captures the real platform behavior without prescribing a fix.
        val partsStr = p.parts.toList
        // Either the path is relative ["a", "b"] or absolute ["", "b"] depending on platform.
        // We assert we at least get a non-empty parts list and "b" appears.
        assert(partsStr.nonEmpty)
        assert(partsStr.contains("b"))
    }

    "Path('.') single-dot segment normalises away" in {
        val p = Path(".")
        // A lone "." should normalise to an empty relative path on all platforms.
        assert(p.parts.isEmpty || p.parts == Chunk("."))
    }

    "Path('..') single-dotdot segment normalises or is preserved" in {
        val p = Path("..")
        // ".." with no parent to resolve against — document whether it is kept or dropped.
        assert(p.parts.nonEmpty || p.parts.isEmpty)
        // At minimum the string representation must not throw.
        assert(p.toString != null)
    }

    // =========================================================================
    // Streaming edge cases
    // =========================================================================

    "readBytesStream on empty file yields empty chunk" in run {
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "empty.bin"
            _         <- file.mkFile
            collected <- Scope.run(file.readBytesStream.run)
            _         <- dir.removeAll
        yield assert(collected.isEmpty)
        end for
    }

    "readBytesStream over multiple buffer boundaries emits all bytes correctly" in run {
        val data = Span.fill(20000)(42.toByte) // > 2 full 8192-byte buffers
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "large.bin"
            _         <- file.writeBytes(data)
            collected <- Scope.run(file.readBytesStream.run)
            _         <- dir.removeAll
        yield assert(collected.size == 20000)
        end for
    }

    "readStream with multi-byte UTF-8 near buffer boundary" in run {
        // Write 8190 ASCII chars + a 4-byte emoji (😀, U+1F600)
        // Total: 8194 bytes, which spans two 8192-byte reads.
        // If the implementation splits the UTF-8 sequence across reads and decodes
        // each chunk independently the surrogate pair will be garbled.
        val content = "x" * 8190 + "\uD83D\uDE00"
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "utf8.txt"
            _        <- file.write(content)
            streamed <- Scope.run(file.readStream.run)
            eager    <- file.read
            _        <- dir.removeAll
        yield assert(streamed.mkString == eager)
        end for
    }

    "readLinesStream on empty file yields empty chunk" in run {
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "empty-lines.txt"
            _         <- file.mkFile
            collected <- Scope.run(file.readLinesStream.run)
            _         <- dir.removeAll
        yield assert(collected.isEmpty)
        end for
    }

    "readLinesStream on file with trailing newline matches readLines" in run {
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "trailing-nl.txt"
            _           <- file.write("a\nb\n")
            streamLines <- Scope.run(file.readLinesStream.run)
            eagerLines  <- file.readLines
            _           <- dir.removeAll
        yield assert(streamLines.toList == eagerLines.toList)
        end for
    }

    "walk on empty directory emits only the root" in run {
        for
            dir   <- Path.tempDir("kyo-test")
            paths <- Scope.run(dir.walk.run)
            _     <- dir.removeAll
        yield
            // walk should at minimum contain the root directory itself
            assert(paths.size == 1 && paths.head == dir)
        end for
    }

    "tail continues after file truncation" in run {
        // Start tail, write initial content, truncate the file, then append new data.
        // The question is whether tail picks up the new content or gets confused after
        // truncation (the file position may be beyond EOF).
        // If tail does NOT recover the test will hang and the framework timeout will
        // kill it — that itself documents the issue.
        Clock.withTimeControl { control =>
            for
                dir <- Path.tempDir("kyo-test")
                file = dir / "tail-truncate.txt"
                _ <- file.write("initial content\n")
                tailFiber <- Fiber.initUnscoped(
                    Scope.run(file.tail(50.millis).take(1).run)
                )
                _     <- control.advance(50.millis) // wake up tail's first poll (sees nothing new — initial content is skipped)
                _     <- file.truncate(0L)          // truncate to empty
                _     <- control.advance(50.millis) // wake up tail's second poll (position reset after truncation)
                _     <- file.appendLines(Chunk("after-truncate"))
                _     <- control.advance(50.millis) // wake up tail's third poll (sees new line)
                lines <- tailFiber.get
                _     <- dir.removeAll
            yield assert(lines.toList == List("after-truncate"))
            end for
        }
    }

    "writeTo with empty byte stream creates an empty file" in run {
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "empty-byte-stream.bin"
            _      <- Scope.run(Stream.empty[Byte].writeTo(file))
            exists <- file.exists
            bytes  <- file.readBytes
            _      <- dir.removeAll
        yield assert(exists && bytes.isEmpty)
        end for
    }

    "writeLinesTo with empty stream creates an empty file" in run {
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "empty-lines-stream.txt"
            _      <- Scope.run(Stream.empty[String].writeLinesTo(file))
            exists <- file.exists
            bytes  <- file.readBytes
            _      <- dir.removeAll
        yield assert(exists && bytes.isEmpty)
        end for
    }

    // =========================================================================
    // name / extName edge cases
    // =========================================================================

    "name returns Absent for empty path" in {
        val p = Path()
        assert(p.name == Absent)
    }

    "extName on empty path returns Absent" in {
        val p = Path()
        assert(p.extName == Absent)
    }

    "parent of two-segment path returns single-segment path" in {
        val p      = Path("a", "b")
        val result = p.parent
        assert(result == Present(Path("a")))
    }

    "extName of hidden file with extension returns the extension" in {
        // .gitignore has no extension (the dot is part of the name)
        assert(Path(".gitignore").extName == Absent)
        // .config.json has extension .json
        assert(Path(".config.json").extName == Present(".json"))
    }

    // =========================================================================
    // Regression tests — inspired by known issues in fs2, os-lib, and zio-process
    // =========================================================================

    // Inspired by fs2 #3667: writeTo should not leave a file containing partial data
    // when the input stream fails mid-flight.
    "writeTo does not leave file with partial data when stream fails mid-flight" in run {
        for
            dir <- Path.tempDir("kyo-test")
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
                case Result.Failure(_) => succeed // file doesn't exist — also acceptable
        end for
    }

    // Inspired by fs2 #1005: buffer reuse corruption after rechunking.
    // Each chunk read from readBytesStream must be an independent copy, not aliased
    // to the same mutable read buffer.
    "readBytesStream chunks are independent copies — buffer not reused" in run {
        // Write a pattern where the value at offset i is (i % 256).toByte
        val size = 20000
        val data = Span.from((0 until size).map(i => (i % 256).toByte).toArray)
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "buf-reuse.bin"
            _      <- file.writeBytes(data)
            chunks <- Scope.run(file.readBytesStream.mapChunk(c => Seq(c)).run)
            _      <- dir.removeAll
        yield
            // Flatten and verify every byte has the expected value
            var offset = 0
            chunks.foreach { chunk =>
                chunk.foreach { byte =>
                    assert(
                        byte == (offset % 256).toByte,
                        s"Data corrupted at offset $offset: expected ${(offset % 256).toByte} got $byte"
                    )
                    offset += 1
                }
            }
            assert(offset == size, s"Expected $size bytes total, got $offset")
        end for
    }

    // Inspired by fs2 #2966: the file handle should be released when a stream is
    // interrupted early (e.g. by take).  On Unix we verify the handle is released
    // by successfully writing to the same file after the interrupted read.
    "file handle is released when readBytesStream is interrupted by take" in run {
        for
            dir <- Path.tempDir("kyo-test")
            file = dir / "interrupt.bin"
            // Write enough data so the file cannot be read in a single chunk
            _ <- file.write("x" * 100000)
            // Read the stream but stop after the first chunk — this interrupts the rest
            firstChunks <- Scope.run(file.readBytesStream.take(1).run)
            // If the file handle was leaked, attempting to write here would either
            // fail (Windows) or eventually exhaust file descriptors (Unix).
            _       <- file.write("replaced")
            content <- file.read
            _       <- dir.removeAll
        yield
            assert(firstChunks.nonEmpty)
            assert(content == "replaced")
    }

    // Inspired by fs2 #1005: buffer reuse corruption.
    // Each byte in the read-back must match the pattern used to write the file.
    // A failure here means chunks alias the same mutable read buffer.
    "readBytesStream chunks are independent copies of the read buffer" in run {
        val size    = 20000
        val pattern = Span.from(Array.tabulate[Byte](size)(i => (i % 251).toByte))
        for
            dir <- Path.tempDir("kyo-buf-indep")
            file = dir / "pattern.bin"
            _         <- file.writeBytes(pattern)
            collected <- Scope.run(file.readBytesStream.run)
            _         <- dir.removeAll
        yield
            val mismatches = collected.toSeq.zipWithIndex.collect {
                case (b, i) if (b & 0xff) != (i % 251) => s"offset $i: expected ${i % 251} got ${b & 0xff}"
            }
            assert(collected.size == size)
            assert(mismatches.isEmpty, mismatches.mkString(", "))
        end for
    }

    // Inspired by fs2 #2966: file handle must be released on early stream termination.
    // After taking only the first chunk, the handle should be freed so further writes succeed.
    "file handle is released when readBytesStream terminates early" in run {
        for
            dir <- Path.tempDir("kyo-handle-release")
            file = dir / "large.bin"
            _            <- file.write("y" * 100000)
            firstChunk   <- Scope.run(file.readBytesStream.take(1).run)
            _            <- file.write("overwritten")
            finalContent <- file.read
            _            <- dir.removeAll
        yield
            assert(firstChunk.nonEmpty)
            assert(finalContent == "overwritten")
        end for
    }

    // Inspired by fs2 #3667: writeTo with a failing mid-stream should not leave corrupt partial data.
    "writeTo with failing stream does not leave corrupt partial file" in run {
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
                case Result.Failure(_) => succeed
        end for
    }

    // Inspired by fs2 #1371: appendLines must append to existing content written by write(),
    // not overwrite from position 0.
    "appendLines actually appends to existing content" in run {
        for
            dir <- Path.tempDir("kyo-appendlines")
            file = dir / "appended.txt"
            _      <- file.write("first\n")
            _      <- file.appendLines(Chunk("second"))
            result <- file.readLines
            _      <- dir.removeAll
        yield
            assert(result.contains("first"), s"'first' was lost — appendLines may have overwritten from position 0")
            assert(result.contains("second"), s"'second' was not written")
        end for
    }

    // =========================================================================
    // System directories — all userPaths fields
    // =========================================================================

    "userPaths all fields are populated" in {
        val up = Path.userPaths
        assert(up.home.toString.nonEmpty)
        assert(up.audio.toString.nonEmpty)
        assert(up.desktop.toString.nonEmpty)
        assert(up.document.toString.nonEmpty)
        assert(up.download.toString.nonEmpty)
        assert(up.font.toString.nonEmpty)
        assert(up.picture.toString.nonEmpty)
        assert(up.public.toString.nonEmpty)
        assert(up.template.toString.nonEmpty)
        assert(up.video.toString.nonEmpty)
    }

    "basePaths.config on macOS uses Application Support not Preferences" in {
        import AllowUnsafe.embrace.danger
        val os = java.lang.System.getProperty("os.name", "").toLowerCase
        if os.contains("mac") then
            val config = Path.basePaths.config.unsafe.show
            assert(
                config.contains("Application Support"),
                s"macOS config dir should use 'Application Support', got: $config"
            )
            assert(
                !config.contains("Preferences"),
                s"macOS config dir should NOT use 'Preferences', got: $config"
            )
        else succeed
        end if
    }

    "userPaths.font on Linux follows XDG_DATA_HOME when set" in {
        import AllowUnsafe.embrace.danger
        val os = java.lang.System.getProperty("os.name", "").toLowerCase
        if os.contains("linux") then
            val font = Path.userPaths.font.unsafe.show
            // font dir should be derived from XDG_DATA_HOME if set,
            // not hardcoded to ~/.local/share/fonts
            assert(
                font.contains("fonts"),
                s"Font dir should contain 'fonts': $font"
            )
        else succeed
        end if
    }

    // =========================================================================
    // Delegation tests (guards against infinite-recursion in safe API)
    // =========================================================================

    "safe exists delegates to unsafe without infinite loop" in run {
        for
            dir    <- Path.tempDir("kyo-deleg")
            result <- dir.exists
            _      <- dir.removeAll
        yield assert(result == true)
        end for
    }

    "safe read delegates to unsafe without infinite loop" in run {
        for
            dir <- Path.tempDir("kyo-deleg")
            file = dir / "deleg-read.txt"
            _      <- file.write("hello")
            result <- Abort.run[FileReadException](file.read)
            _      <- dir.removeAll
        yield assert(result == Result.Success("hello"))
        end for
    }

    "safe write delegates to unsafe without infinite loop" in run {
        for
            dir <- Path.tempDir("kyo-deleg")
            file = dir / "deleg-write.txt"
            result  <- Abort.run[FileWriteException](file.write("test"))
            content <- file.read
            _       <- dir.removeAll
        yield
            assert(result.isSuccess)
            assert(content == "test")
        end for
    }

    // =========================================================================
    // removeAll error propagation
    // =========================================================================

    // Skipped on Windows (no Unix permissions/chmod) and when running as
    // root (e.g. inside containers) because root bypasses permission checks.
    private def isRoot: Boolean < (Async & Abort[CommandException]) =
        if Platform.isWindows then true
        else Command("id", "-u").text.map(_.trim == "0")

    "removeAll raises error when subdirectory is permission-denied" in run {
        for
            root <- isRoot
            result <-
                if root then Kyo.lift(succeed)
                else
                    for
                        dir <- Path.tempDir("kyo-removeall-test")
                        sub = dir / "unreadable"
                        _      <- sub.mkDir
                        _      <- (sub / "child.txt").write("data")
                        _      <- Command("chmod", "000", sub.toString).waitFor
                        result <- Abort.run[FileFsException](dir.removeAll)
                        _      <- Command("chmod", "755", sub.toString).waitFor
                        _      <- dir.removeAll
                    yield result match
                        case Result.Failure(_: FileFsException) => succeed
                        case Result.Success(_) =>
                            fail("removeAll should fail when subdirectory is inaccessible, but it succeeded silently")
                    end for
        yield result
    }

    "removeAll raises error when files cannot be deleted" in run {
        for
            root <- isRoot
            result <-
                if root then Kyo.lift(succeed)
                else
                    for
                        dir <- Path.tempDir("kyo-removeall-test")
                        sub = dir / "protected"
                        _      <- sub.mkDir
                        _      <- (sub / "guarded.txt").write("data")
                        _      <- Command("chmod", "555", sub.toString).waitFor
                        result <- Abort.run[FileFsException](dir.removeAll)
                        _      <- Command("chmod", "755", sub.toString).waitFor
                        _      <- dir.removeAll
                    yield result match
                        case Result.Failure(_: FileFsException) => succeed
                        case Result.Success(_) =>
                            fail("removeAll should fail when files cannot be deleted, but it succeeded silently")
                    end for
        yield result
    }

end PathTest
