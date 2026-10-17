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

    "a program path to a file without execute permission raises PermissionDeniedException" in {
        assumeUnix("No execute permission bit on Windows")
        Path.run {
            for
                dir <- Path.tempDir("kyo-cmd-denied")
                script = dir / "script.sh"
                _      <- script.write("#!/bin/sh\necho no\n")
                result <- Abort.run[CommandException](Command(script.toString).waitFor)
                _      <- dir.removeAll
            yield result match
                case Result.Failure(PermissionDeniedException(cmd)) => assert(cmd == script.toString)
                case other                                          => fail(s"Expected PermissionDeniedException, got: $other")
        }
    }

    "a program path to a folder raises PermissionDeniedException" in {
        assumeUnix("A folder path is resolved differently on Windows")
        Path.run {
            for
                dir    <- Path.tempDir("kyo-cmd-folder")
                result <- Abort.run[CommandException](Command(dir.toString).waitFor)
                _      <- dir.removeAll
            yield result match
                case Result.Failure(PermissionDeniedException(cmd)) => assert(cmd == dir.toString)
                case other                                          => fail(s"Expected PermissionDeniedException, got: $other")
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
                path    <- Path.tempUnscoped("kyo-stdout-file-test", ".txt")
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
                    path    <- Path.tempUnscoped("kyo-stdout-append-test", ".txt")
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
                    path    <- Path.tempUnscoped("kyo-stderr-file-test", ".txt")
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
                    path    <- Path.tempUnscoped("kyo-stderr-append-test", ".txt")
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

    // The spawner is
    // stopped once the operating system shows the process, by a unique argv, and the process must then be gone.
    "a process whose spawner is interrupted does not outlive it".times(80) in {
        assumeUnix() // sleep / pgrep / kill have no Windows equivalent
        val seconds                                                  = 300 + scala.util.Random.nextInt(100000)
        val cmd                                                      = Command("sleep", seconds.toString)
        def alive: Chunk[String] < (Async & Abort[CommandException]) =
            Command("pgrep", "-f", s"^sleep $seconds$$").textWithExitCode.map((out, _) =>
                Chunk.from(out.linesIterator.map(_.trim).filter(_.nonEmpty).toSeq)
            )
        // A process this leaf orphans would otherwise sit on the host for the lifetime of its `sleep`.
        def killLeftovers: Unit < Async =
            Abort.run[CommandException](alive.map(left => Kyo.foreachDiscard(left)(pid => Command("kill", "-9", pid).waitFor))).unit
        Scope.run {
            for
                _      <- Scope.ensure(killLeftovers)
                fiber  <- Fiber.initUnscoped(Scope.run(cmd.spawn.andThen(Async.never)))
                _      <- assertEventually(alive.map(_.nonEmpty))
                _      <- fiber.interrupt
                result <- fiber.getResult
                _      <- assertEventually(alive.map(_.isEmpty))
            yield assert(result.isPanic)
        }
    }

    // A command that forks a child holding its stdout, records both pids, and then signals `ready` over a named pipe.
    private def forking(dir: Path): Command =
        Command("sh", "-c", s"sleep 100000 & echo $$! > '$dir/child'; echo $$$$ > '$dir/root'; echo ready > '$dir/ready'; wait")

    private def withForking[A](test: (
        Command,
        Unit < (Async & Abort[CommandException]),
        Chunk[Long] < (Async & Abort[CommandException])
    ) => A < (Async & Abort[CommandException] & Scope))(
        using Frame
    ): A < (Async & Abort[CommandException | FileSystemException] & Scope) =
        Path.run(Path.tempDir("kyo-cmd-tree")).map { dir =>
            val ready = Command("cat", s"$dir/ready").text.unit
            val pids  = Kyo.foreach(Chunk("root", "child"))(name => Command("cat", s"$dir/$name").text.map(_.trim.toLong))
            Command("mkfifo", s"$dir/ready").waitFor.andThen(test(forking(dir), ready, pids))
        }

    // The pids still running; a zombie awaiting its reaper counts as gone.
    private def running(pids: Chunk[Long])(using Frame): Chunk[Long] < (Async & Abort[CommandException]) =
        Kyo.filter(pids) { pid =>
            Command("ps", "-o", "stat=", "-p", pid.toString).textWithExitCode.map { (out, code) =>
                code.isSuccess && out.trim.nonEmpty && !out.trim.startsWith("Z")
            }
        }

    "closing the scope of a spawned command kills every process the command started" in {
        assumeUnix()
        withForking { (command, ready, pids) =>
            for
                started <- Scope.run(command.spawn.andThen(ready).andThen(pids))
                left    <- running(started)
            yield assert(left.isEmpty, s"processes left running: $left of $started")
        }
    }

    "interrupting a wait on a command kills every process the command started" in {
        assumeUnix()
        withForking { (command, ready, pids) =>
            for
                started <- Scope.run(Fiber.init(command.waitFor).andThen(ready).andThen(pids))
                left    <- running(started)
            yield assert(left.isEmpty, s"processes left running: $left of $started")
        }
    }

    // The forked child holds the stdout the wait's readers block on, so the readers can only finish once the tree is gone.
    "interrupting textWithExitCode on a command kills every process the command started" in {
        assumeUnix()
        withForking { (command, ready, pids) =>
            for
                started <- Scope.run(Fiber.init(command.textWithExitCode).andThen(ready).andThen(pids))
                left    <- running(started)
            yield assert(left.isEmpty, s"processes left running: $left of $started")
        }
    }

    // A spawn must not hand its pipes to a child another spawn forks at the same time: a long-lived child holding a short command's
    // stdout keeps that command's read from ever reaching EOF. Each round forks a long-lived child while a short command's output is
    // read; every read must complete, and one that does not is counted within its bound rather than left to hang the leaf.
    "commands spawned concurrently do not hold each other's pipes" in {
        assumeUnix() // sleep has no Windows equivalent
        val rounds = 80
        Loop.indexed { i =>
            if i >= rounds then Loop.done(succeed)
            else
                for
                    holder <- Fiber.initUnscoped(Scope.run(Command("sleep", "300").spawn.andThen(Async.never)))
                    read   <- Abort.run[Timeout](Async.timeout(5.seconds)(Command("echo", "x").text))
                    _      <- holder.interrupt
                    _      <- holder.getResult
                yield
                    assert(read.isSuccess, s"round $i: the short command's output never reached EOF while another spawn was in flight")
                    Loop.continue
                end for
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

    "text drains output larger than the pipe buffer" in {
        assumeUnix("sh -c");
        {
            // An OS pipe buffers roughly 64KB. Past that the child blocks on write until the parent
            // reads, so an implementation that waits for exit before reading deadlocks: the child
            // cannot exit, so the wait never completes, so the read that would release it never
            // starts. Nothing smaller than the buffer can detect that, and every other case in this
            // file stays well under it.
            //
            // seq 1 100000 emits roughly 580KB, comfortably past the boundary on any platform.
            Command("sh", "-c", "seq 1 100000").text.map { out =>
                val lines = out.trim.linesIterator.toIndexedSeq
                assert(lines.length == 100000, s"expected 100000 lines, got ${lines.length}")
                assert(lines.head == "1")
                assert(lines.last == "100000")
            }
        }
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
                    path    <- Path.tempUnscoped("kyo-stdout-append-preserve-test", ".txt")
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

    // A writer to a pipe whose reader is gone dies of SIGPIPE, status 141 in the shell, only while the signal is at its default; an
    // ignored disposition survives exec, so a child of a process that ignores it gets EPIPE instead.
    "a spawned program sees SIGPIPE at its default, with and without processGroup" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-sigpipe")).map { dir =>
            val writer                   = Command("sh", "-c", s"(yes; echo $$? > '$dir/status') | head -1 > /dev/null")
            def status(command: Command) = command.waitFor.andThen(Command("cat", s"$dir/status").text.map(_.trim))
            for
                plain   <- status(writer)
                grouped <- status(writer.processGroup)
            yield assert(plain == "141" && grouped == "141", s"yes exited $plain without processGroup and $grouped with it")
            end for
        }
    }

    // ---------------------------------------------------------------------------
    // processGroup
    // ---------------------------------------------------------------------------

    // A program that starts a child holding none of its output and exits at once; the child's pid is in `dir/child` once it has exited.
    private def leaving(dir: Path): Command =
        Command("sh", "-c", s"sleep 100000 > /dev/null 2>&1 & echo $$! > '$dir/child'")

    private def pidIn(file: String)(using Frame): Long < (Async & Abort[CommandException]) =
        Command("cat", file).text.map(_.trim.toLong)

    // The process state `ps` reports, empty once no process has the pid.
    private def stateOf(pid: Long)(using Frame): String < (Async & Abort[CommandException]) =
        Command("ps", "-o", "stat=", "-p", pid.toString).text.map(_.trim)

    private def utf8(bytes: Chunk[Byte]): String = new String(bytes.toArray, StandardCharsets.UTF_8)

    "processGroup: the program leads a group of its own, and without it runs in its caller's group" in {
        assumeUnix()
        val group = Command("sh", "-c", "ps -o pgid= -p $$")
        Scope.run {
            for
                grouped    <- group.processGroup.spawn
                groupedPid <- grouped.pid
                groupedOut <- grouped.stdout.run
                plain      <- group.spawn
                plainPid   <- plain.pid
                plainOut   <- plain.stdout.run
            yield
                assert(utf8(groupedOut).trim.toLong == groupedPid)
                assert(utf8(plainOut).trim.toLong != plainPid)
        }
    }

    "processGroup: destroyTree kills a child the program left running when it exited" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            Scope.run {
                for
                    proc  <- leaving(dir).processGroup.spawn
                    code  <- proc.waitFor
                    child <- pidIn(s"$dir/child")
                    alive <- running(Chunk(child))
                    _     <- proc.destroyTree
                    left  <- running(Chunk(child))
                yield
                    assert(code == ExitCode.Success)
                    assert(alive == Chunk(child), "the child should outlive the program until destroyTree")
                    assert(left.isEmpty, s"the child $child is still running")
            }
        }
    }

    "processGroup: closing the spawn's scope kills a child the program left running when it exited" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            for
                child <- Scope.run(leaving(dir).processGroup.spawn.map(_.waitFor).andThen(pidIn(s"$dir/child")))
                left  <- running(Chunk(child))
            yield assert(left.isEmpty, s"the child $child is still running")
        }
    }

    // Without the group, the read waits for the child, which holds the program's stdout.
    "processGroup: a read of stdout ends once destroyTree kills the child that holds it" in {
        assumeUnix()
        Scope.run {
            for
                proc   <- Command("sh", "-c", "echo hi; sleep 100000 &").processGroup.spawn
                reader <- Fiber.init(Scope.run(proc.stdout.run))
                _      <- proc.waitFor
                _      <- proc.destroyTree
                out    <- reader.get
            yield assert(utf8(out) == "hi\n")
        }
    }

    "processGroup: an exited program keeps its exit code through destroyTree" in {
        assumeUnix()
        Scope.run {
            for
                proc   <- Command("sh", "-c", "exit 3").processGroup.spawn
                code   <- proc.waitFor
                alive  <- proc.isAlive
                polled <- proc.exitCode
                _      <- proc.destroyTree
                same   <- proc.exitCode
            yield
                assert(code == ExitCode.Failure(3))
                assert(!alive)
                assert(polled == Present(ExitCode.Failure(3)) && same == Present(ExitCode.Failure(3)))
        }
    }

    // Node reaps every child before its 'exit' event, so on JS no exited leader is held.
    "processGroup: an exited program stays unreaped until destroyTree, holding its group's id" in {
        assumeUnix()
        assume(!kyo.internal.Platform.isJS, "Node reaps a child at its exit")
        Scope.run {
            for
                proc  <- Command("sh", "-c", "exit 3").processGroup.spawn
                _     <- proc.waitFor
                pid   <- proc.pid
                held  <- stateOf(pid)
                _     <- proc.destroyTree
                after <- stateOf(pid)
            yield
                assert(held.startsWith("Z"), s"expected the exited program held unreaped, ps says '$held'")
                assert(after.isEmpty, s"expected the program reaped after destroyTree, ps says '$after'")
        }
    }

    // destroyTree stops the tree before it kills it; macOS's waitid reports the stop as an exit, which must not be taken for one.
    "processGroup: destroyTree on a running program reports the kill, not the stop before it, and reaps the program" in {
        assumeUnix()
        Scope.run {
            for
                proc  <- Command("sleep", "100000").processGroup.spawn
                pid   <- proc.pid
                _     <- proc.destroyTree
                code  <- proc.exitCode
                after <- stateOf(pid)
            yield
                assert(code == Present(ExitCode.SIGKILL))
                assert(after.isEmpty, s"expected the program reaped after destroyTree, ps says '$after'")
        }
    }

    "processGroup: a program killed by a signal reports the signal" in {
        assumeUnix()
        Command("sh", "-c", "kill -9 $$").processGroup.waitFor.map(code => assert(code == ExitCode.Signaled(9)))
    }

    "processGroup: destroy stops a running program with SIGTERM" in {
        assumeUnix()
        Scope.run {
            for
                proc <- Command("sleep", "100000").processGroup.spawn
                _    <- proc.destroy
                code <- proc.waitFor
            yield assert(code == ExitCode.SIGTERM)
        }
    }

    "processGroup: the working folder, the environment, stdin and redirectErrorStream reach the program" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            Path.run((dir / "marker").write("x")).andThen {
                Command("sh", "-c", "ls; echo $KYO_GROUP; cat; echo err >&2")
                    .cwd(dir)
                    .envAppend(Map("KYO_GROUP" -> "value"))
                    .stdin("in\n")
                    .redirectErrorStream(true)
                    .processGroup
                    .text
                    .map(out => assert(out == "marker\nvalue\nin\nerr\n"))
            }
        }
    }

    "processGroup: envRemove and envClear shape the program's environment" in {
        assumeUnix()
        for
            removed <- Command("sh", "-c", "echo ${HOME:-none}").envRemove(Set("HOME")).processGroup.text
            cleared <- Command("env").envClear.processGroup.text
            only    <- Command("env").envClear.envAppend(Map("KYO_ONLY" -> "1")).processGroup.text
        yield
            assert(removed == "none\n")
            assert(cleared == "")
            assert(only == "KYO_ONLY=1\n")
        end for
    }

    "processGroup: stdin from a stream and from a pipe" in {
        assumeUnix()
        for
            streamed <- Command("cat").stdin(Stream.init(Chunk.from("streamed".getBytes(StandardCharsets.UTF_8)))).processGroup.text
            piped    <- Scope.run {
                for
                    proc <- Command("cat").pipeStdin.processGroup.spawn
                    // Unsafe: a piped stdin is reachable only as the child's java.io.OutputStream.
                    _ <- Sync.Unsafe.defer {
                        val stdin = proc.unsafe.stdinJava
                        stdin.write("piped".getBytes(StandardCharsets.UTF_8))
                        stdin.close()
                    }
                    out <- proc.stdout.run
                yield utf8(out)
            }
        yield
            assert(streamed == "streamed")
            assert(piped == "piped")
        end for
    }

    "processGroup: stdoutToFile and stderrToFile, truncating and appending" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            val both = Command("sh", "-c", "echo out; echo err >&2")
            for
                _   <- both.stdoutToFile(dir / "out").stderrToFile(dir / "err").processGroup.waitFor
                _   <- both.stdoutToFile(dir / "out").stderrToFile(dir / "err", append = true).processGroup.waitFor
                out <- Path.run((dir / "out").read)
                err <- Path.run((dir / "err").read)
            yield
                assert(out == "out\n")
                assert(err == "err\nerr\n")
            end for
        }
    }

    "processGroup: closing the scope kills every stage of a pipeline and what each left running" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            val first = Command("sh", "-c", s"sleep 100000 > /dev/null 2>&1 & echo $$! > '$dir/child'; echo x")
            for
                out <- Scope.run(first.andThen(cat).processGroup.spawn.map(proc =>
                    proc.stdout.run.map(bytes => proc.waitFor.andThen(utf8(bytes)))
                ))
                child <- pidIn(s"$dir/child")
                left  <- running(Chunk(child))
            yield
                assert(out == "x\n")
                assert(left.isEmpty, s"the child $child is still running")
            end for
        }
    }

    "processGroup: a missing program, a program the host will not run and a missing folder raise their exceptions" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            val script = dir / "script.sh"
            for
                _        <- Path.run(script.write("#!/bin/sh\necho no\n"))
                missing  <- Abort.run[CommandException](Command("kyo-no-such-program").processGroup.waitFor)
                denied   <- Abort.run[CommandException](Command(script.toString).processGroup.waitFor)
                noFolder <- Abort.run[CommandException](Command("true").cwd(dir / "gone").processGroup.waitFor)
            yield
                missing match
                    case Result.Failure(ProgramNotFoundException(cmd)) => assert(cmd == "kyo-no-such-program")
                    case other                                         => fail(s"Expected ProgramNotFoundException, got: $other")
                denied match
                    case Result.Failure(PermissionDeniedException(cmd)) => assert(cmd == script.toString)
                    case other                                          => fail(s"Expected PermissionDeniedException, got: $other")
                noFolder match
                    case Result.Failure(WorkingDirectoryNotFoundException(path)) => assert(path == dir / "gone")
                    case other => fail(s"Expected WorkingDirectoryNotFoundException, got: $other")
            end for
        }
    }

    // On macOS the spawn closes every descriptor no file action names, so an inherited one must be named to stay open.
    "processGroup: inherited stdin, stdout and stderr are open in the program" in {
        assumeUnix()
        Command("sh", "-c", "exec 3<&0 && exec 4>&1 && exec 5>&2").inheritIO.processGroup.waitFor.map(code =>
            assert(code == ExitCode.Success)
        )
    }

    "processGroup: destroyTree run again, or beside another, releases the group once" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            for
                child <- Scope.run {
                    for
                        proc   <- leaving(dir).processGroup.spawn
                        _      <- proc.waitFor
                        first  <- Fiber.init(proc.destroyTree)
                        second <- Fiber.init(proc.destroyTree)
                        _      <- first.get
                        _      <- second.get
                        _      <- proc.destroyTree
                        groups <- Sync.Unsafe.defer(proc.unsafe.processGroups())
                        child  <- pidIn(s"$dir/child")
                    yield
                        assert(groups.isEmpty)
                        child
                }
                left <- running(Chunk(child))
            yield assert(left.isEmpty, s"the child $child is still running")
        }
    }

    "processGroup: text answers at the program's exit, killing what it left holding stdout and reaping it" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            for
                out   <- Command("sh", "-c", s"echo $$$$; sleep 100000 & echo $$! > '$dir/child'").processGroup.text
                child <- pidIn(s"$dir/child")
                left  <- running(Chunk(child))
                pid = out.trim.toLong
                // text answers at the end of the output, which the kill brings; the reap that follows it is awaited.
                _ <- assertEventually(stateOf(pid).map(_.isEmpty))
            yield assert(left.isEmpty, s"the child $child is still running")
        }
    }

    "processGroup: textWithExitCode answers at the program's exit though a process it left holds stdout" in {
        assumeUnix()
        Command("sh", "-c", "echo hi; sleep 100000 &").processGroup.textWithExitCode.map { (out, code) =>
            assert(out == "hi\n" && code == ExitCode.Success)
        }
    }

    "processGroup: the unsafe waitFor answers once the group is killed" in {
        assumeUnix()
        Path.run(Path.tempDir("kyo-cmd-group")).map { dir =>
            for
                // Unsafe: the unscoped wait is what is under test.
                code  <- Sync.Unsafe.defer(leaving(dir).processGroup.unsafe.waitFor().safe.get)
                child <- pidIn(s"$dir/child")
                left  <- running(Chunk(child))
            yield
                assert(code == ExitCode.Success)
                assert(left.isEmpty, s"the child $child is still running")
        }
    }

    "processGroup: a program from spawnUnscoped is held until destroyTree reaps it" in {
        assumeUnix()
        assume(!kyo.internal.Platform.isJS, "Node reaps a child at its exit")
        for
            proc  <- Command("sh", "-c", "exit 0").processGroup.spawnUnscoped
            _     <- proc.waitFor
            pid   <- proc.pid
            held  <- stateOf(pid)
            _     <- proc.destroyTree
            after <- stateOf(pid)
        yield
            assert(held.startsWith("Z"), s"expected the exited program held unreaped, ps says '$held'")
            assert(after.isEmpty, s"expected the program reaped after destroyTree, ps says '$after'")
        end for
    }

    "processGroup: the unsafe timed wait answers Absent while the program runs and its code once it exited" in {
        assumeUnix()
        Scope.run {
            for
                running <- Command("sleep", "100000").processGroup.spawn
                early   <- Sync.Unsafe.defer(running.unsafe.waitFor(1.millis).safe.get)
                exited  <- Command("sh", "-c", "exit 4").processGroup.spawn
                _       <- exited.waitFor
                late    <- Sync.Unsafe.defer(exited.unsafe.waitFor(1.hour).safe.get)
            yield
                assert(early == Absent)
                assert(late == Present(ExitCode.Failure(4)))
        }
    }

    // A pipe end the parent holds that leaked into another child would keep this program's `read` from ever seeing end of input.
    "processGroup: a program spawned later does not hold an earlier one's pipes" in {
        assumeUnix()
        Scope.run {
            for
                reader <- Command("sh", "-c", "read x; echo done").pipeStdin.processGroup.spawn
                _      <- Command("sleep", "100000").processGroup.spawn
                _      <- Command("sleep", "100000").spawn
                // Unsafe: a piped stdin is reachable only as the child's java.io.OutputStream.
                _    <- Sync.Unsafe.defer(reader.unsafe.stdinJava.close())
                code <- reader.waitFor
                out  <- reader.stdout.run
            yield assert(code == ExitCode.Success && utf8(out) == "done\n")
        }
    }

end CommandTest
