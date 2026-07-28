package kyo

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class CommandTest extends kyo.test.Test[Any]:

    // Sequential leaves: these spawn and pipe between OS processes; concurrent leaves race the multi-process pipe (notably on Node).
    override def config = super.config.sequential

    private val isWindows = kyo.internal.Platform.isWindows

    // Platform-aware command helpers
    private def echo(text: String)  = if isWindows then Command("cmd", "/c", s"echo $text") else Command("echo", text)
    private def echoN(text: String) = if isWindows then Command("cmd", "/c", s"<nul set /p=$text") else Command("printf", "%s", text)
    private def cat                 = if isWindows then Command("findstr", ".*") else Command("cat")
    private def exitWith(code: Int) = if isWindows then Command("cmd", "/c", s"exit $code") else Command("sh", "-c", s"exit $code")
    private def trueCmd             = exitWith(0)
    private def falseCmd            = exitWith(1)
    private def pwd                 = if isWindows then Command("cmd", "/c", "cd") else Command("pwd")

    // Some tests use commands with no Windows equivalent (wc, seq, sort -r, head).
    // These are skipped on Windows.
    private def assumeUnix(msg: String = "No Windows equivalent") =
        assume(!isWindows, msg)

    // Normalize line endings for cross-platform comparison
    private def normalize(s: String) = s.replace("\r\n", "\n").replace("\r", "")

    // ---------------------------------------------------------------------------
    // Command execution
    // ---------------------------------------------------------------------------

    "echo text returns hello newline" in {
        echo("hello").text.map { result =>
            assert(normalize(result).trim == "hello")
        }
    }

    "waitFor returns ExitCode.Success for exit 0" in {
        trueCmd.waitFor.map { code =>
            assert(code == ExitCode.Success)
        }
    }

    "waitFor returns ExitCode.Failure for non-zero exit" in {
        exitWith(3).waitFor.map { code =>
            assert(code == ExitCode.Failure(3))
        }
    }

    "waitForSuccess completes without error for zero-exit" in {
        trueCmd.waitForSuccess.map(_ => succeed("no exception means zero-exit succeeded"))
    }

    "waitForSuccess raises Abort[ExitCode] for non-zero exit" in {
        Abort.run[CommandException | ExitCode] {
            falseCmd.waitForSuccess
        }.map {
            case Result.Failure(code: ExitCode) =>
                assert(code == ExitCode.Failure(1))
            case other =>
                fail(s"Expected Failure(ExitCode.Failure(1)), got: $other")
        }
    }

    "stream emits stdout bytes and cleans up when scope closes" in {
        Scope.run {
            echo("streamdata").stream.run.map { bytes =>
                val result = new String(bytes.toArray)
                assert(result.trim == "streamdata")
            }
        }
    }

    "stdin string feeds content to process stdin" in {
        cat.stdin("hello from stdin\n").text.map { result =>
            assert(normalize(result).trim == "hello from stdin")
        }
    }

    // TODO: andThen pipe mechanism deadlocks on Windows — needs investigation in ProcessPlatformSpecific
    "andThen pipes stdout of first command into stdin of second" in {
        assumeUnix("pipe deadlock on Windows");
        {
            echo("hello pipe").andThen(cat).text.map { result =>
                assert(normalize(result).trim == "hello pipe")
            }
        }
    }

    "cwd sets working directory for the process" in {
        Path.run {
            for
                tmpDir <- Path.tempDir("kyo-cmd-cwd-test")
                result <- pwd.cwd(tmpDir).text
                _      <- tmpDir.removeAll
            yield
                val pwdPath = normalize(result).trim
                val dirName = tmpDir.name.getOrElse("")
                assert(pwdPath.contains(dirName))
            end for
        }
    }

    "non-existent program raises ProgramNotFoundException" in {
        Abort.run[CommandException] {
            Command("__kyo_test_nonexistent_8f3a__").waitFor
        }.map {
            case Result.Failure(ProgramNotFoundException(cmd)) =>
                assert(cmd == "__kyo_test_nonexistent_8f3a__")
            case other =>
                fail(s"Expected ProgramNotFoundException, got: $other")
        }
    }

    "missing cwd raises WorkingDirectoryNotFoundException" in {
        val missingDir = Path / "kyo-nonexistent-cwd-xyzzy-99" / "sub"
        Abort.run[CommandException] {
            echo("hi").cwd(missingDir).waitFor
        }.map {
            case Result.Failure(WorkingDirectoryNotFoundException(path)) =>
                assert(path == missingDir)
            case other =>
                fail(s"Expected WorkingDirectoryNotFoundException, got: $other")
        }
    }

    "CommandException cases are exhaustively matchable" in {
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

    "stdin(Span[Byte]) feeds raw bytes" in {
        val bytes: Array[Byte] = "input\n".getBytes(StandardCharsets.UTF_8)
        val span: Span[Byte]   = Span.from(bytes)
        cat.stdin(span).text.map { result =>
            assert(normalize(result).trim == "input")
        }
    }

    "stdin(Stream[Byte, Sync]) feeds stream" in {
        val bytes: Array[Byte] = "stream input\n".getBytes(StandardCharsets.UTF_8)
        val stream             = Stream.init(bytes.toSeq)
        cat.stdin(stream).text.map { result =>
            assert(normalize(result).trim == "stream input")
        }
    }

    "stdin(Process.Input.FromStream) feeds content" in {
        val bytes = "fromstream input\n".getBytes(StandardCharsets.UTF_8)
        val input = Process.Input.FromStream(new ByteArrayInputStream(bytes))
        cat.stdin(input).text.map { result =>
            assert(normalize(result).trim == "fromstream input")
        }
    }

    "stdin(String, charset) with explicit charset" in {
        val str     = "hello charset"
        val charset = java.nio.charset.Charset.forName("ISO-8859-1")
        cat.stdin(str, charset).text.map { result =>
            assert(normalize(result).trim == "hello charset")
        }
    }

    // ---------------------------------------------------------------------------
    // IO routing
    // ---------------------------------------------------------------------------

    "stdoutToFile writes stdout to file" in {
        Path.run {
            for
                path    <- Path.temp("kyo-stdout-file-test", ".txt")
                _       <- echo("stdout line").stdoutToFile(path).waitFor
                content <- path.read
                _       <- path.remove
            yield assert(normalize(content).trim == "stdout line")
            end for
        }
    }

    // ProcessBuilder.Redirect.appendTo is broken on Scala Native Windows ARM64
    "stdoutToFile with append=true appends" in {
        assume(!(isWindows && kyo.internal.Platform.isNative), "ProcessBuilder append broken on Native Windows")
        {
            Path.run {
                for
                    path    <- Path.temp("kyo-stdout-append-test", ".txt")
                    _       <- path.write("line1\n")
                    _       <- echo("line2").stdoutToFile(path, append = true).waitFor
                    content <- path.read
                    _       <- path.remove
                yield
                    assert(content.contains("line1"))
                    assert(content.contains("line2"))
                end for
            }
        }
    }

    "stderrToFile writes stderr to file" in {
        assumeUnix("stderr redirect");
        {
            Path.run {
                for
                    path    <- Path.temp("kyo-stderr-file-test", ".txt")
                    _       <- Command("sh", "-c", "echo err >&2").stderrToFile(path).waitFor
                    content <- path.read
                    _       <- path.remove
                yield assert(normalize(content).trim == "err")
                end for
            }
        }
    }

    "stderrToFile with append=true appends" in {
        assumeUnix("stderr redirect");
        {
            Path.run {
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
        }
    }

    "redirectErrorStream(false) leaves stderr separate" in {
        assumeUnix("stderr redirect");
        {
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
    }

    "redirectErrorStream(true) merges stderr into stdout" in {
        assumeUnix("stderr redirect");
        {
            val cmd = Command("sh", "-c", "echo stdout_data; echo stderr_data >&2")
                .redirectErrorStream(true)
            cmd.text.map { output =>
                assert(output.contains("stdout_data"), s"Expected stdout_data in: $output")
                assert(output.contains("stderr_data"), s"Expected stderr_data merged into stdout: $output")
            }
        }
    }

    "inheritIO returns success" in {
        trueCmd.inheritIO.waitFor.map { code =>
            assert(code == ExitCode.Success)
        }
    }

    "spawnUnscoped returns a live process the caller owns and must close" in {
        for
            proc <- trueCmd.spawnUnscoped
            code <- proc.waitFor
            _    <- proc.destroyForcibly
        yield assert(code == ExitCode.Success)
        end for
    }

    // ---------------------------------------------------------------------------
    // Command.Unsafe
    // ---------------------------------------------------------------------------

    "Unsafe command.text()" in {
        import AllowUnsafe.embrace.danger
        val fiber  = echo("hi").unsafe.text()
        val result = fiber.safe.get
        result.map { content =>
            assert(content.trim == "hi")
        }
    }

    "Command.unsafe.spawn returns Success for valid command" in {
        import AllowUnsafe.embrace.danger
        val result = trueCmd.unsafe.spawn()
        result match
            case Result.Success(proc) =>
                discard(proc.waitFor().safe.get)
                succeed("spawn returned Success and waitFor completed")
            case other =>
                fail(s"Expected Success(Process.Unsafe), got: $other")
        end match
    }

    "Command.unsafe.spawn returns Failure(ProgramNotFoundException) for unknown program" in {
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

    "PermissionDeniedException type-level check" in {
        val err: CommandException = PermissionDeniedException("/bin/denied-test")
        err match
            case PermissionDeniedException(cmd) => assert(cmd == "/bin/denied-test")
            case other                          => fail(s"Expected PermissionDeniedException, got: $other")
    }

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
                case WorkingDirectoryNotFoundException(path) => ()
        }
        ()
    }

    // ---------------------------------------------------------------------------
    // textWithExitCode
    // ---------------------------------------------------------------------------

    "textWithExitCode returns stdout and Success for echo" in {
        echo("hello").textWithExitCode.map { case (text, code) =>
            assert(normalize(text).trim == "hello")
            assert(code == ExitCode.Success)
        }
    }

    "textWithExitCode returns stdout and Failure for failing command" in {
        assumeUnix("sh -c");
        {
            Command("sh", "-c", "echo output; exit 42").textWithExitCode.map { case (text, code) =>
                assert(text.trim == "output")
                assert(code == ExitCode.Failure(42))
            }
        }
    }

    "textWithExitCode raises CommandException for missing program" in {
        Abort.run[CommandException] {
            Command("__nonexistent__").textWithExitCode
        }.map {
            case Result.Failure(_: CommandException) => succeed("expected CommandException for missing program")
            case other                               => fail(s"Expected CommandException, got $other")
        }
    }

    // ---------------------------------------------------------------------------
    // Path.Unsafe via Command tests
    // ---------------------------------------------------------------------------

    "Path.unsafe.read returns Success for readable file" in {
        val text = "unsafe read content"
        Path.run {
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
    }

    "Path.unsafe.read returns Failure(FileNotFoundException) for absent file" in {
        val absent = Path / "kyo-nonexistent-unsafe-test-xyzzy" / "missing.txt"
        val result =
            import AllowUnsafe.embrace.danger
            absent.unsafe.read()
        result match
            case Result.Failure(_: FileNotFoundException) => succeed("expected FileNotFoundException for absent file")
            case other                                    => fail(s"Expected Failure(FileNotFoundException), got: $other")
    }

    // ---------------------------------------------------------------------------
    // Additional command tests
    // ---------------------------------------------------------------------------

    "textWithExitCode on process with no output returns empty string and Success" in {
        trueCmd.textWithExitCode.map { case (text, code) =>
            assert(text == "")
            assert(code == ExitCode.Success)
        }
    }

    "Command with no arguments raises ProgramNotFoundException" in {
        Abort.run[CommandException](Command().waitFor).map { result =>
            assert(result.isFailure)
        }
    }

    "Command passes empty string argument unchanged" in {
        assumeUnix("printf");
        {
            Command("sh", "-c", "printf '%s' \"$1\"", "--", "").text.map { result =>
                assert(result == "")
            }
        }
    }

    "envClear followed by envAppend produces only appended vars" in {
        assumeUnix("env command");
        {
            Command("env").envClear.envAppend(Map("KTEST_ONLY" -> "yes")).text.map { output =>
                assert(output.contains("KTEST_ONLY=yes"))
                assert(!output.contains("HOME="))
            }
        }
    }

    "andThen chains three commands in a pipeline" in {
        assumeUnix("sort/head");
        {
            // Looped to guard a stdout-capture race: the JS backend used to drain the final stage
            // on the process 'exit' event, which can fire before Node delivers the stdout 'data'/
            // 'end' events, truncating short pipeline output to "". Repeating makes that ordering
            // race, if reintroduced, fail reliably instead of flaking on a single run.
            Kyo.foreach(1 to 30) { _ =>
                val step1 = Command("sort", "-r").andThen(Command("head", "-1"))
                Command("printf", "a\\nb\\nc\\n").andThen(step1).text
            }.map { results =>
                assert(
                    results.forall(_.trim == "c"),
                    s"expected every run to yield 'c', got: ${results.map(_.trim).distinct}"
                )
            }
        }
    }

    "andThen pipe transforms data through second command" in {
        assumeUnix("wc");
        {
            Command("echo", "hello world").andThen(Command("wc", "-w")).text.map { result =>
                val count = result.trim
                assert(count == "2" || count == "       2", s"Expected word count 2, got: '$count'")
            }
        }
    }

    "andThen second command receives complete stdin from first" in {
        assumeUnix("seq/wc");
        {
            Command("seq", "1", "100").andThen(Command("wc", "-l")).text.map { result =>
                val count = result.trim
                assert(count == "100" || count == "     100", s"Expected line count 100, got: '$count'")
            }
        }
    }

    "stdin with empty string produces empty output without hanging" in {
        cat.stdin("").text.map { result =>
            assert(normalize(result) == "")
        }
    }

    "stdin stream delivers all bytes correctly to child process" in {
        assumeUnix("wc");
        {
            val data = (1 to 100).map(i => s"line$i").mkString("\n") + "\n"
            Command("wc", "-l").stdin(data).text.map { result =>
                val count = result.trim
                assert(count == "100" || count == "     100", s"Expected 100 lines, got: '$count'")
            }
        }
    }

    "stdin with multi-chunk stream is fully consumed by child" in {
        assumeUnix("wc");
        {
            val content = "x" * 10000
            Command("wc", "-c").stdin(content).text.map { result =>
                val count = result.trim
                assert(count == "10000" || count == "   10000", s"Expected 10000 bytes, got: '$count'")
            }
        }
    }

    "stdin with empty byte Stream closes stdin immediately" in {
        cat.stdin(Stream.empty[Byte]).text.map { result =>
            assert(normalize(result) == "")
        }
    }

    "andThen with stdin on first command pipes through pipeline" in {
        assumeUnix("wc");
        {
            Command("cat").stdin("input line\n").andThen(Command("wc", "-l")).text.map { result =>
                val count = result.trim
                assert(count == "1" || count == "       1", s"Expected 1 line, got: '$count'")
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Regression tests
    // ---------------------------------------------------------------------------

    "stdoutToFile with append=true preserves existing content" in {
        assume(!(isWindows && kyo.internal.Platform.isNative), "ProcessBuilder append broken on Native Windows")
        {
            Path.run {
                for
                    path    <- Path.temp("kyo-stdout-append-preserve-test", ".txt")
                    _       <- path.write("line1\n")
                    _       <- echo("line2").stdoutToFile(path, append = true).waitFor
                    content <- path.read
                    _       <- path.remove
                yield
                    assert(content.contains("line1"))
                    assert(content.contains("line2"))
                end for
            }
        }
    }

    "text on command with only stderr output returns empty string" in {
        assumeUnix("sh -c stderr");
        {
            Command("sh", "-c", "echo error >&2").text.map { result =>
                assert(result == "", s"Expected empty stdout, got: '$result'")
            }
        }
    }

    "textWithExitCode captures stdout while stderr goes to separate stream" in {
        assumeUnix("sh -c stderr");
        {
            Command("sh", "-c", "echo out; echo err >&2").textWithExitCode.map { case (text, code) =>
                assert(text.trim == "out")
                assert(code == ExitCode.Success)
            }
        }
    }

end CommandTest
