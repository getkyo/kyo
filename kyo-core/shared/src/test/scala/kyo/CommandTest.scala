package kyo

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class CommandTest extends Test:

    // ---------------------------------------------------------------------------
    // Command execution
    // ---------------------------------------------------------------------------

    "echo text returns hello newline" in run {
        Command("echo", "hello").text.map { result =>
            assert(result == "hello\n")
        }
    }

    "waitFor returns ExitCode.Success for exit 0" in run {
        Command("true").waitFor.map { code =>
            assert(code == ExitCode.Success)
        }
    }

    "waitFor returns ExitCode.Failure for non-zero exit" in run {
        Command("sh", "-c", "exit 3").waitFor.map { code =>
            assert(code == ExitCode.Failure(3))
        }
    }

    "waitForSuccess completes without error for zero-exit" in run {
        Command("true").waitForSuccess.map(_ => succeed)
    }

    "waitForSuccess raises Abort[ExitCode] for non-zero exit" in run {
        Abort.run[CommandException | ExitCode] {
            Command("false").waitForSuccess
        }.map {
            case Result.Failure(code: ExitCode) =>
                assert(code == ExitCode.Failure(1))
            case other =>
                fail(s"Expected Failure(ExitCode.Failure(1)), got: $other")
        }
    }

    "stream emits stdout bytes and cleans up when scope closes" in run {
        Scope.run {
            Command("echo", "streamdata").stream.run.map { bytes =>
                val result = new String(bytes.toArray)
                assert(result.trim == "streamdata")
            }
        }
    }

    "stdin string feeds content to process stdin" in run {
        Command("cat").stdin("hello from stdin\n").text.map { result =>
            assert(result.trim == "hello from stdin")
        }
    }

    "andThen pipes stdout of first command into stdin of second" in run {
        val pipeline = Command("echo", "hello pipe").andThen(Command("cat"))
        pipeline.text.map { result =>
            assert(result.trim == "hello pipe")
        }
    }

    "cwd sets working directory for the process" in run {
        for
            tmpDir <- Path.tempDir("kyo-cmd-cwd-test")
            result <- Command("pwd").cwd(tmpDir).text
            _      <- tmpDir.removeAll
        yield
            // `pwd` may return the symlink-resolved path on some platforms (e.g. macOS
            // /tmp -> /private/tmp).  We check that the unique directory name created
            // by tempDir appears somewhere in the pwd output — that is sufficient to
            // verify the working directory was set correctly.
            val pwdPath = result.trim
            val dirName = tmpDir.name.getOrElse("")
            assert(pwdPath.contains(dirName))
        end for
    }

    "non-existent program raises ProgramNotFoundException" in run {
        Abort.run[CommandException] {
            Command("__kyo_test_nonexistent_8f3a__").waitFor
        }.map {
            case Result.Failure(ProgramNotFoundException(cmd)) =>
                assert(cmd == "__kyo_test_nonexistent_8f3a__")
            case other =>
                fail(s"Expected ProgramNotFoundException, got: $other")
        }
    }

    "missing cwd raises WorkingDirectoryNotFoundException" in run {
        val missingDir = Path / "kyo-nonexistent-cwd-xyzzy-99" / "sub"
        Abort.run[CommandException] {
            Command("echo", "hi").cwd(missingDir).waitFor
        }.map {
            case Result.Failure(WorkingDirectoryNotFoundException(path)) =>
                assert(path == missingDir)
            case other =>
                fail(s"Expected WorkingDirectoryNotFoundException, got: $other")
        }
    }

    "CommandException cases are exhaustively matchable" in run {
        Abort.run[CommandException] {
            Command("__kyo_test_nonexistent_8f3a2__").waitFor
        }.map { result =>
            result match
                case Result.Failure(err) =>
                    val msg = err match
                        case ProgramNotFoundException(cmd)        => s"not found: $cmd"
                        case PermissionDeniedException(cmd)       => s"denied: $cmd"
                        case WorkingDirectoryNotFoundException(p) => s"missing cwd: $p"
                    assert(msg.startsWith("not found:"))
                case Result.Success(_) =>
                    fail("Expected failure, got success")
                case Result.Panic(ex) =>
                    fail(s"Unexpected panic: $ex")
        }
    }

    // ---------------------------------------------------------------------------
    // Command.stdin variants
    // ---------------------------------------------------------------------------

    "stdin(Span[Byte]) feeds raw bytes" in run {
        val bytes: Array[Byte] = "input\n".getBytes(StandardCharsets.UTF_8)
        val span: Span[Byte]   = Span.from(bytes)
        Command("cat").stdin(span).text.map { result =>
            assert(result.trim == "input")
        }
    }

    "stdin(Stream[Byte, Sync]) feeds stream" in run {
        val bytes: Array[Byte] = "stream input\n".getBytes(StandardCharsets.UTF_8)
        val stream             = Stream.init(bytes.toSeq)
        Command("cat").stdin(stream).text.map { result =>
            assert(result.trim == "stream input")
        }
    }

    "stdin(Process.Input.FromStream) feeds content" in run {
        val bytes = "fromstream input\n".getBytes(StandardCharsets.UTF_8)
        val input = Process.Input.FromStream(new ByteArrayInputStream(bytes))
        Command("cat").stdin(input).text.map { result =>
            assert(result.trim == "fromstream input")
        }
    }

    "stdin(String, charset) with explicit charset" in run {
        // ASCII-safe string; both UTF-8 and ISO-8859-1 encode it identically.
        val str     = "hello charset"
        val charset = java.nio.charset.Charset.forName("ISO-8859-1")
        Command("cat").stdin(str, charset).text.map { result =>
            assert(result.trim == "hello charset")
        }
    }

    // ---------------------------------------------------------------------------
    // IO routing
    // ---------------------------------------------------------------------------

    "stdoutToFile writes stdout to file" in run {
        for
            path    <- Path.temp("kyo-stdout-file-test", ".txt")
            _       <- Command("echo", "stdout line").stdoutToFile(path).waitFor
            content <- path.read
            _       <- path.remove
        yield assert(content.trim == "stdout line")
        end for
    }

    "stdoutToFile with append=true appends" in run {
        for
            path    <- Path.temp("kyo-stdout-append-test", ".txt")
            _       <- path.write("line1\n")
            _       <- Command("echo", "line2").stdoutToFile(path, append = true).waitFor
            content <- path.read
            _       <- path.remove
        yield
            assert(content.contains("line1"))
            assert(content.contains("line2"))
        end for
    }

    "stderrToFile writes stderr to file" in run {
        for
            path    <- Path.temp("kyo-stderr-file-test", ".txt")
            _       <- Command("sh", "-c", "echo err >&2").stderrToFile(path).waitFor
            content <- path.read
            _       <- path.remove
        yield assert(content.trim == "err")
        end for
    }

    "stderrToFile with append=true appends" in run {
        for
            path    <- Path.temp("kyo-stderr-append-test", ".txt")
            _       <- path.write("first\n")
            _       <- Command("sh", "-c", "echo second >&2").stderrToFile(path, append = true).waitFor
            content <- path.read
            _       <- path.remove
        yield
            assert(content.contains("first"))
            assert(content.contains("second"))
        end for
    }

    "redirectErrorStream(false) leaves stderr separate" in run {
        val cmd = Command("sh", "-c", "echo stdout_data; echo stderr_data >&2")
            .redirectErrorStream(false)
        Scope.run {
            for
                proc        <- cmd.spawn
                stderrBytes <- proc.stderr.run
                _           <- proc.waitFor
            yield assert(stderrBytes.nonEmpty)
        }
    }

    "inheritIO returns success" in run {
        Command("true").inheritIO.waitFor.map { code =>
            assert(code == ExitCode.Success)
        }
    }

    // ---------------------------------------------------------------------------
    // Command.Unsafe
    // ---------------------------------------------------------------------------

    "Unsafe command.text()" in run {
        import AllowUnsafe.embrace.danger
        val fiber  = Command("echo", "hi").unsafe.text()
        val result = fiber.safe.get
        result.map { content =>
            assert(content.trim == "hi")
        }
    }

    "Command.unsafe.spawn returns Success for valid command" in run {
        import AllowUnsafe.embrace.danger
        val result = Command("true").unsafe.spawn()
        result match
            case Result.Success(proc) =>
                discard(proc.waitFor().safe.get)
                succeed
            case other =>
                fail(s"Expected Success(Process.Unsafe), got: $other")
        end match
    }

    "Command.unsafe.spawn returns Failure(ProgramNotFoundException) for unknown program" in run {
        import AllowUnsafe.embrace.danger
        val result = Command("__kyo_test_nonexistent_8f3a3__").unsafe.spawn()
        result match
            case Result.Failure(ProgramNotFoundException(cmd)) =>
                assert(cmd == "__kyo_test_nonexistent_8f3a3__")
            case other =>
                fail(s"Expected Failure(ProgramNotFoundException), got: $other")
        end match
    }

    // ---------------------------------------------------------------------------
    // CommandException type checks
    // ---------------------------------------------------------------------------

    "PermissionDeniedException type-level check" in run {
        val err: CommandException = PermissionDeniedException("/bin/denied-test")
        err match
            case PermissionDeniedException(cmd) => assert(cmd == "/bin/denied-test")
            case other                          => fail(s"Expected PermissionDeniedException, got: $other")
    }

    // CommandException subtypes should be exhaustive

    "CommandException sealed hierarchy covers all known error categories" in {
        val errors: List[CommandException] = List(
            ProgramNotFoundException("test"),
            PermissionDeniedException("test"),
            WorkingDirectoryNotFoundException(Path / "nonexistent")
        )
        errors.foreach { err =>
            err match
                case ProgramNotFoundException(cmd)           => assert(cmd == "test")
                case PermissionDeniedException(cmd)          => assert(cmd == "test")
                case WorkingDirectoryNotFoundException(path) => succeed
        }
        succeed
    }

    // ---------------------------------------------------------------------------
    // textWithExitCode
    // ---------------------------------------------------------------------------

    "textWithExitCode returns stdout and Success for echo" in run {
        Command("echo", "hello").textWithExitCode.map { case (text, code) =>
            assert(text.trim == "hello")
            assert(code == ExitCode.Success)
        }
    }

    "textWithExitCode returns stdout and Failure for failing command" in run {
        Command("sh", "-c", "echo output; exit 42").textWithExitCode.map { case (text, code) =>
            assert(text.trim == "output")
            assert(code == ExitCode.Failure(42))
        }
    }

    "textWithExitCode raises CommandException for missing program" in run {
        Abort.run[CommandException] {
            Command("__nonexistent__").textWithExitCode
        }.map {
            case Result.Failure(_: CommandException) => succeed
            case other                               => fail(s"Expected CommandException, got $other")
        }
    }

    // ---------------------------------------------------------------------------
    // Path.Unsafe via Command tests
    // ---------------------------------------------------------------------------

    "Path.unsafe.read returns Success for readable file" in run {
        val text = "unsafe read content"
        for
            dir <- Path.tempDir("kyo-unsafe-test")
            file = dir / "unsafe-read.txt"
            _ <- file.write(text)
            result =
                import AllowUnsafe.embrace.danger
                file.unsafe.read()
            _ <- dir.removeAll
        yield result match
            case Result.Success(content) => assert(content == text)
            case other                   => fail(s"Expected Success, got: $other")
        end for
    }

    "Path.unsafe.read returns Failure(FileNotFoundException) for absent file" in run {
        val absent = Path / "kyo-nonexistent-unsafe-test-xyzzy" / "missing.txt"
        val result =
            import AllowUnsafe.embrace.danger
            absent.unsafe.read()
        result match
            case Result.Failure(_: FileNotFoundException) => succeed
            case other                                    => fail(s"Expected Failure(FileNotFoundException), got: $other")
    }

    // ---------------------------------------------------------------------------
    // Additional command tests
    // ---------------------------------------------------------------------------

    "textWithExitCode on process with no output returns empty string and Success" in run {
        Command("true").textWithExitCode.map { case (text, code) =>
            assert(text == "")
            assert(code == ExitCode.Success)
        }
    }

    "Command with no arguments raises ProgramNotFoundException" in run {
        Abort.run[CommandException](Command().waitFor).map { result =>
            assert(result.isFailure)
        }
    }

    "Command passes empty string argument unchanged" in run {
        Command("sh", "-c", "printf '%s' \"$1\"", "--", "").text.map { result =>
            assert(result == "")
        }
    }

    "envClear followed by envAppend produces only appended vars" in run {
        Command("env").envClear.envAppend(Map("KTEST_ONLY" -> "yes")).text.map { output =>
            assert(output.contains("KTEST_ONLY=yes"))
            // Should NOT contain HOME, PATH, etc. — only the appended var
            assert(!output.contains("HOME="))
        }
    }

    "andThen chains three commands in a pipeline" in run {
        // Build the pipeline right-to-left so that each andThen nests correctly:
        //   step1 = sort -r | head -1
        //   step2 = printf ... | step1
        val step1 = Command("sort", "-r").andThen(Command("head", "-1"))
        Command("printf", "a\\nb\\nc\\n")
            .andThen(step1)
            .text
            .map(t => assert(t.trim == "c"))
    }

    // andThen pipe verification — use transforming commands (wc) so a broken pipe can't accidentally pass

    "andThen pipe transforms data through second command" in run {
        Command("echo", "hello world").andThen(Command("wc", "-w")).text.map { result =>
            val count = result.trim
            assert(count == "2" || count == "       2", s"Expected word count 2, got: '$count'")
        }
    }

    "andThen second command receives complete stdin from first" in run {
        Command("seq", "1", "100").andThen(Command("wc", "-l")).text.map { result =>
            val count = result.trim
            assert(count == "100" || count == "     100", s"Expected line count 100, got: '$count'")
        }
    }

    "stdin with empty string produces empty output without hanging" in run {
        Command("cat").stdin("").text.map { result =>
            assert(result == "")
        }
    }

    // feedStream correctness — stdin stream delivery

    "stdin stream delivers all bytes correctly to child process" in run {
        val data = (1 to 100).map(i => s"line$i").mkString("\n") + "\n"
        Command("wc", "-l").stdin(data).text.map { result =>
            val count = result.trim
            assert(count == "100" || count == "     100", s"Expected 100 lines, got: '$count'")
        }
    }

    "stdin with multi-chunk stream is fully consumed by child" in run {
        val content = "x" * 10000
        Command("wc", "-c").stdin(content).text.map { result =>
            val count = result.trim
            assert(count == "10000" || count == "   10000", s"Expected 10000 bytes, got: '$count'")
        }
    }

    "stdin with empty byte Stream closes stdin immediately" in run {
        Command("cat").stdin(Stream.empty[Byte]).text.map { result =>
            assert(result == "")
        }
    }

    "andThen with stdin on first command pipes through pipeline" in run {
        Command("cat").stdin("input line\n").andThen(Command("wc", "-l")).text.map { result =>
            val count = result.trim
            assert(count == "1" || count == "       1", s"Expected 1 line, got: '$count'")
        }
    }

    // ---------------------------------------------------------------------------
    // Regression tests — inspired by known issues in fs2, os-lib, and zio-process
    // ---------------------------------------------------------------------------

    // Inspired by fs2 #1371: stdoutToFile with append=true must not truncate the
    // file before writing; existing content must be preserved.
    "stdoutToFile with append=true preserves existing content" in run {
        for
            path    <- Path.temp("kyo-stdout-append-preserve-test", ".txt")
            _       <- path.write("line1\n")
            _       <- Command("echo", "line2").stdoutToFile(path, append = true).waitFor
            content <- path.read
            _       <- path.remove
        yield
            assert(content.contains("line1"), s"'line1' was lost — stdoutToFile(append=true) may have overwritten from position 0")
            assert(content.contains("line2"), s"'line2' was not written")
        end for
    }

    "text on command with only stderr output returns empty string" in run {
        Command("sh", "-c", "echo error >&2").text.map { result =>
            assert(result == "", s"Expected empty stdout, got: '$result'")
        }
    }

    "textWithExitCode captures stdout while stderr goes to separate stream" in run {
        Command("sh", "-c", "echo out; echo err >&2").textWithExitCode.map { case (text, code) =>
            assert(text.trim == "out")
            assert(code == ExitCode.Success)
        }
    }

end CommandTest
