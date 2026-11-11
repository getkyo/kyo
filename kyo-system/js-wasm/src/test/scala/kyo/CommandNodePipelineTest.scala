package kyo

import java.nio.charset.StandardCharsets

class CommandNodePipelineTest extends kyo.test.Test[Any]:

    "failed pipeline acquisition reports the stage's launch error and closes acquired stages" in {
        if internal.Platform.isWindows then cancel("Uses Unix script interpreters")
        Scope.run {
            for
                dir <- Scope.acquireRelease(Path.run(Path.tempDir("kyo-pipeline-exec-error")))(path => Path.run(path.removeAll))
                script = dir / "command"
                _ <- Path.run(script.write("#!/__kyo_missing_interpreter_7319__\n"))
                _ <- Command("chmod", "+x", script.toString).waitForSuccess
                _ <- Kyo.foreachDiscard(Chunk(
                    Command(script.toString).andThen(Command("cat")),
                    Command("cat").andThen(Command(script.toString))
                )) { command =>
                    Abort.run[CommandException](command.spawn).map {
                        case Result.Failure(ProgramNotFoundException(program)) => assert(program == script.toString)
                        case other => fail(s"Expected the failed stage's ProgramNotFoundException, got $other")
                    }
                }
            yield ()
        }
    }

    "idle output readers leave command acquisition runnable and preserve later output" in {
        if internal.Platform.isWindows then cancel("Uses Unix cat")
        Scope.run {
            for
                processes <- Kyo.foreach(Chunk(false, true))(grouped =>
                    val command = Command("cat").pipeStdin
                    (if grouped then command.processGroup else command).spawn
                )
                readers <- Kyo.foreach(processes)(process => Fiber.init(Scope.run(process.stdout.run)))
                code    <- Command("true").processGroup.waitFor
                _       <- Kyo.foreachDiscard(processes)(process =>
                    Sync.Unsafe.defer {
                        process.unsafe.stdinJava.write("ready\n".getBytes(StandardCharsets.UTF_8))
                        process.unsafe.stdinJava.close()
                    }
                )
                output <- Kyo.foreach(readers)(_.get)
            yield
                assert(code == ExitCode.Success)
                assert(output.map(bytes => new String(bytes.toArray, StandardCharsets.UTF_8)) == Chunk("ready\n", "ready\n"))
        }
    }

    "owned pipeline stages retain distinct environments and working directories" in {
        if internal.Platform.isWindows then cancel("Process groups require a Unix host")
        Scope.run {
            for
                firstDir <- Scope.acquireRelease(Path.run(Path.tempDir("kyo-stage-one")))(path => Path.run(path.removeAll))
                lastDir  <- Scope.acquireRelease(Path.run(Path.tempDir("kyo-stage-two")))(path => Path.run(path.removeAll))
                _        <- Path.run((firstDir / "marker").write("first-folder\n"))
                _        <- Path.run((lastDir / "marker").write("last-folder"))
                output   <- Command("sh", "-c", "printf '%s:' \"$VALUE\"; cat marker")
                    .cwd(firstDir).envAppend(Map("VALUE" -> "first"))
                    .andThen(Command("sh", "-c", "IFS= read -r input; printf '%s:%s:%s' \"$VALUE\" \"$(cat marker)\" \"$input\"")
                        .cwd(lastDir).envAppend(Map("VALUE" -> "last")))
                    .processGroup.text
            yield assert(output == "last:last-folder:first:first-folder")
        }
    }

    "owned pipelines drain every stage's stderr while OS pipes carry stdout" in {
        if internal.Platform.isWindows then cancel("Process groups require a Unix host")
        Scope.run {
            for
                process <-
                    Command("python3", "-c", "import sys; sys.stderr.write('e'*262144); sys.stderr.flush(); sys.stdout.write('payload')")
                        .andThen(Command(
                            "python3",
                            "-c",
                            "import sys; value=sys.stdin.read(); sys.stderr.write('tail'); sys.stdout.write(value)"
                        ))
                        .processGroup.spawn
                output <- process.collectOutput
                code   <- process.waitFor
            yield
                assert(new String(output._1.toArray, StandardCharsets.UTF_8) == "payload")
                assert(new String(output._2.toArray, StandardCharsets.UTF_8) == ("e" * 262144) + "tail")
                assert(code == ExitCode.Success)
        }
    }
end CommandNodePipelineTest
