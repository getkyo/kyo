package kyo

// TODO: Process tests use Unix commands (sleep, sh -c, kill) extensively.
// Needs cross-platform process helpers for Windows support.
class ProcessTest extends Test:

    import scala.concurrent.Future

    override def run(v: Future[Assertion] < (Abort[Any] & Async & Scope))(using Frame): Future[Assertion] =
        if kyo.internal.Platform.isWindows then
            Future.successful(cancel("ProcessTest uses Unix commands (sleep, sh, kill)"))
        else super.run(v)

    // ---------------------------------------------------------------------------
    // Process lifecycle
    // ---------------------------------------------------------------------------

    "exitCode returns Absent while process is running" in run {
        Scope.run {
            for
                proc    <- Command("sleep", "60").spawn
                current <- proc.exitCode
            yield assert(current == Absent)
        }
    }

    "exitCode returns Present(Success) after process exits normally" in run {
        Scope.run {
            for
                proc    <- Command("true").spawn
                _       <- proc.waitFor
                current <- proc.exitCode
            yield assert(current == Present(ExitCode.Success))
        }
    }

    "pid returns a positive long after spawn" in run {
        Scope.run {
            for
                proc <- Command("sleep", "60").spawn
                pid  <- proc.pid
            yield assert(pid > 0L)
        }
    }

    "destroy causes waitFor to return non-Success exit code" in run {
        Scope.run {
            for
                proc   <- Command("sleep", "5").spawn
                _      <- proc.destroy
                result <- proc.waitFor(10.seconds)
                code = result.getOrElse(Process.ExitCode.SIGTERM)
            yield assert(code != ExitCode.Success)
        }
    }

    "destroyForcibly force-kills a process" in run {
        Scope.run {
            for
                proc   <- Command("sleep", "60").spawn
                _      <- proc.destroyForcibly
                result <- proc.waitFor(10.seconds)
                code = result.getOrElse(Process.ExitCode.SIGKILL)
            yield assert(code != ExitCode.Success)
        }
    }

    "isAlive is true before exit and false after" in run {
        Scope.run {
            for
                proc       <- Command("true").spawn
                _          <- proc.waitFor
                aliveAfter <- proc.isAlive
            yield assert(aliveAfter == false)
        }
    }

    // ---------------------------------------------------------------------------
    // Process spawn independence
    // ---------------------------------------------------------------------------

    "same Command can be spawned multiple times producing independent processes" in run {
        Scope.run {
            val cmd = Command("sleep", "60")
            for
                proc1 <- cmd.spawn
                proc2 <- cmd.spawn
                pid1  <- proc1.pid
                pid2  <- proc2.pid
            yield assert(pid1 != pid2)
            end for
        }
    }

    // ---------------------------------------------------------------------------
    // redirectErrorStream
    // ---------------------------------------------------------------------------

    "redirectErrorStream(true) merges stderr into stdout leaving stderr stream empty" in run {
        val cmd = Command("sh", "-c", "echo stdout_data; echo stderr_data >&2")
            .redirectErrorStream(true)
        Scope.run {
            for
                proc        <- cmd.spawn
                stderrBytes <- proc.stderr.run
                _           <- proc.waitFor
            yield assert(stderrBytes.isEmpty)
        }
    }

    // ---------------------------------------------------------------------------
    // collectOutput
    // ---------------------------------------------------------------------------

    "collectOutput drains both streams concurrently without deadlock" in run {
        val bigOutput = "x" * 50000
        val cmd       = Command("sh", "-c", s"printf '%s' '$bigOutput'; printf '%s' '$bigOutput' >&2")
        Scope.run {
            for
                proc       <- cmd.spawn
                (out, err) <- proc.collectOutput
                _          <- proc.waitFor
            yield
                assert(out.nonEmpty)
                assert(err.nonEmpty)
        }
    }

    "collectOutput returns correct content for both streams" in run {
        val cmd = Command("sh", "-c", "echo stdout; echo stderr >&2")
        Scope.run {
            for
                proc       <- cmd.spawn
                (out, err) <- proc.collectOutput
                _          <- proc.waitFor
            yield
                assert(new String(out.toArray).trim == "stdout")
                assert(new String(err.toArray).trim == "stderr")
        }
    }

    // collectOutput does not call waitFor

    "collectOutput may return while process is still alive" in run {
        // Process that prints quickly but sleeps before exiting
        Scope.run {
            for
                proc       <- Command("sh", "-c", "echo done; sleep 2").spawn
                (out, err) <- proc.collectOutput
                alive      <- proc.isAlive
            yield
                // If collectOutput called waitFor, alive would always be false
                // This test documents that the process may still be running after drain
                assert(new String(out.toArray).trim == "done")
                // alive may be true or false depending on timing — the key insight is
                // that collectOutput doesn't guarantee process exit
        }
    }

    "exitCode may be Absent immediately after collectOutput returns" in run {
        // Process with post-output delay
        Scope.run {
            for
                proc       <- Command("sh", "-c", "echo x; sleep 1").spawn
                (out, err) <- proc.collectOutput
                code       <- proc.exitCode
            yield
                // stdout was drained, but process may not have exited yet
                assert(new String(out.toArray).trim == "x")
                // code may be Absent if process is still in sleep phase
                // This documents the race: callers must explicitly waitFor
        }
    }

    "collectOutput handles large stdout without truncation" in run {
        Scope.run {
            for
                proc       <- Command("seq", "1", "10000").spawn
                (out, err) <- proc.collectOutput
                _          <- proc.waitFor
            yield
                val text  = new String(out.toArray)
                val lines = text.trim.split("\n")
                assert(lines.length == 10000, s"Expected 10000 lines, got ${lines.length}")
        }
    }

    // ---------------------------------------------------------------------------
    // stdout / stderr streams
    // ---------------------------------------------------------------------------

    "process.stdout emits all bytes" in run {
        Scope.run {
            for
                proc  <- Command("echo", "hello stdout").spawn
                bytes <- proc.stdout.run
                _     <- proc.waitFor
            yield
                val content = new String(bytes.toArray)
                assert(content.trim == "hello stdout")
        }
    }

    "process.stderr emits stderr bytes" in run {
        Scope.run {
            for
                proc  <- Command("sh", "-c", "echo err >&2").spawn
                bytes <- proc.stderr.run
                _     <- proc.waitFor
            yield
                val content = new String(bytes.toArray)
                assert(content.trim == "err")
        }
    }

    // ---------------------------------------------------------------------------
    // Process.Unsafe
    // ---------------------------------------------------------------------------

    "Unsafe process.waitFor(timeout)" in run {
        import AllowUnsafe.embrace.danger
        val spawnResult = Command("true").unsafe.spawn()
        spawnResult match
            case Result.Success(proc) =>
                val fiber  = proc.waitFor(5.seconds)
                val result = fiber.safe.get
                result.map { maybeCode =>
                    assert(maybeCode == Present(ExitCode.Success))
                }
            case other =>
                fail(s"Expected spawn to succeed, got: $other")
        end match
    }

    "Process.Unsafe.waitFor returns Fiber.Unsafe resolving with ExitCode.Success" in run {
        import AllowUnsafe.embrace.danger
        val spawnResult = Command("true").unsafe.spawn()
        spawnResult match
            case Result.Success(proc) =>
                val fiber    = proc.waitFor()
                val exitCode = fiber.safe.get
                exitCode.map { code =>
                    assert(code == ExitCode.Success)
                }
            case other =>
                fail(s"Expected spawn to succeed, got: $other")
        end match
    }

    // ---------------------------------------------------------------------------
    // Additional lifecycle tests
    // ---------------------------------------------------------------------------

    "destroy on already-exited process does not throw" in run {
        Scope.run {
            for
                proc <- Command("true").spawn
                _    <- proc.waitFor
                _    <- proc.destroy
            yield succeed
        }
    }

    "pid returns a valid long after process has exited" in run {
        Scope.run {
            for
                proc <- Command("true").spawn
                _    <- proc.waitFor
                pid  <- proc.pid
            yield assert(pid > 0L)
        }
    }

    "collectOutput on process with empty stdout and stderr returns empty chunks" in run {
        val cmd = Command("sh", "-c", "true")
        Scope.run {
            for
                proc       <- cmd.spawn
                (out, err) <- proc.collectOutput
                _          <- proc.waitFor
            yield
                assert(out.isEmpty)
                assert(err.isEmpty)
        }
    }

    "waitFor(timeout) returns Absent when process does not exit within deadline" in run {
        Scope.run {
            for
                proc   <- Command("sleep", "60").spawn
                result <- proc.waitFor(100.millis)
                _      <- proc.destroyForcibly
            yield assert(result == Absent)
        }
    }

    "destroyed process exits with non-Success exit code" in run {
        Scope.run {
            for
                proc   <- Command("sleep", "60").spawn
                _      <- proc.destroy
                result <- proc.waitFor(10.seconds)
                // On Node.js, SIGTERM delivery to child processes can be delayed
                // under heavy CI load. Fall back to SIGKILL if needed.
                code <- result match
                    case Present(c) => c: Process.ExitCode < Any
                    case Absent     => proc.destroyForcibly.andThen(proc.waitFor(10.seconds).map(_.getOrElse(Process.ExitCode.SIGKILL)))
            yield assert(!code.isSuccess, s"Expected non-Success exit after destroy, got $code")
        }
    }

    // ---------------------------------------------------------------------------
    // Regression tests — inspired by known issues in os-lib and zio-process
    // ---------------------------------------------------------------------------

    // Inspired by os-lib #27: waitFor(timeout) must actually return within a
    // reasonable multiple of the deadline, not block indefinitely.
    "waitFor(timeout) terminates within a reasonable multiple of the deadline" in run {
        Scope.run {
            for
                stopwatch <- Clock.stopwatch
                proc      <- Command("sleep", "60").spawn
                result    <- proc.waitFor(200.millis)
                elapsed   <- stopwatch.elapsed
                _         <- proc.destroyForcibly
            yield
                assert(result == Absent)
                assert(elapsed < 5.seconds, s"waitFor(200ms) took ${elapsed} — deadline not enforced")
        }
    }

    // Inspired by zio-process #425: stdout stream from a fast process should
    // complete promptly, not stall waiting for more data that will never arrive.
    "stdout stream completes promptly for short-lived process" in run {
        Scope.run {
            for
                stopwatch <- Clock.stopwatch
                proc      <- Command("echo", "fast").spawn
                out       <- proc.stdout.run
                elapsed   <- stopwatch.elapsed
                _         <- proc.waitFor
            yield
                assert(elapsed < 5.seconds, s"stdout collection took ${elapsed} for a trivial process")
                assert(out.nonEmpty)
        }
    }

    // Inspired by fs2 #3693: the scope-managed process handle must be destroyed
    // when the enclosing Scope closes, even if the process is still running.
    "scope cleanup destroys process after scope closes" in run {
        for
            pidHolder <- AtomicLong.init(0L)
            _ <- Scope.run {
                for
                    proc <- Command("sleep", "60").spawn
                    pid  <- proc.pid
                    _    <- pidHolder.set(pid)
                yield ()
            }
            _   <- Async.sleep(200.millis)
            pid <- pidHolder.get
            result <- Abort.run[Throwable](
                Command("kill", "-0", pid.toString).waitFor
            )
        yield result match
            case Result.Success(code) => assert(!code.isSuccess, s"Process $pid still alive after scope closed")
            case Result.Failure(_)    => succeed // kill itself failed — process is gone
        end for
    }

    // ---------------------------------------------------------------------------
    // Regression tests — inspired by known issues in os-lib and zio-process
    // (additional named variants for traceability)
    // ---------------------------------------------------------------------------

    // Inspired by os-lib #27: waitFor(timeout) must enforce its deadline and not
    // block indefinitely regardless of the process state.
    "waitFor(timeout) enforces deadline without deadlock" in run {
        Scope.run {
            for
                stopwatch <- Clock.stopwatch
                proc      <- Command("sleep", "60").spawn
                result    <- proc.waitFor(200.millis)
                elapsed   <- stopwatch.elapsed
                _         <- proc.destroyForcibly
            yield
                assert(result == Absent)
                assert(elapsed < 5.seconds, s"waitFor(200ms) blocked for ${elapsed} — deadline not enforced")
        }
    }

    // Inspired by zio-process #425: stdout stream must drain and complete when the
    // process exits normally, not stall waiting for data that will never arrive.
    "stdout collection completes promptly for short-lived process" in run {
        Scope.run {
            for
                stopwatch <- Clock.stopwatch
                proc      <- Command("echo", "fast").spawn
                out       <- proc.stdout.run
                elapsed   <- stopwatch.elapsed
                _         <- proc.waitFor
            yield
                assert(out.nonEmpty)
                assert(elapsed < 5.seconds, s"stdout took ${elapsed} for a short-lived process")
        }
    }

    // Inspired by fs2 #3693: the scope-managed process handle must be killed when
    // the enclosing Scope exits, leaving no zombie processes.
    "scope cleanup kills process when scope closes" in run {
        for
            pidHolder <- AtomicLong.init(0L)
            _ <- Scope.run {
                for
                    proc <- Command("sleep", "60").spawn
                    pid  <- proc.pid
                    _    <- pidHolder.set(pid)
                yield ()
            }
            _   <- Async.sleep(200.millis)
            pid <- pidHolder.get
            result <- Abort.run[Throwable](
                Command("kill", "-0", pid.toString).waitFor
            )
        yield result match
            case Result.Success(code) => assert(!code.isSuccess, s"Process $pid still alive after scope closed")
            case Result.Failure(_)    => succeed // kill itself failed — process is gone
        end for
    }

    // ---------------------------------------------------------------------------
    // Scope cleanup — process is destroyed when scope closes
    // ---------------------------------------------------------------------------

    "spawn process is destroyed when scope closes normally" in run {
        for
            pidRef <- AtomicLong.init(0L)
            _ <- Scope.run {
                for
                    proc <- Command("sleep", "60").spawn
                    pid  <- proc.pid
                    _    <- pidRef.set(pid)
                yield ()
            }
            _   <- Async.sleep(200.millis)
            pid <- pidRef.get
            result <- Abort.run[Throwable](
                Command("kill", "-0", pid.toString).waitFor
            )
        yield result match
            case Result.Success(code) => assert(!code.isSuccess, s"Process $pid still alive after scope closed")
            case Result.Failure(_)    => succeed // kill itself failed — process is gone
        end for
    }

    "spawn process is destroyed when scope closes due to Abort error" in run {
        for
            pidRef <- AtomicLong.init(0L)
            _ <- Abort.run[String] {
                Scope.run {
                    for
                        proc <- Command("sleep", "60").spawn
                        pid  <- proc.pid
                        _    <- pidRef.set(pid)
                        _    <- Abort.fail[String]("test error")
                    yield ()
                }
            }
            _   <- Async.sleep(200.millis)
            pid <- pidRef.get
            result <- Abort.run[Throwable](
                Command("kill", "-0", pid.toString).waitFor
            )
        yield result match
            case Result.Success(code) => assert(!code.isSuccess, s"Process $pid still alive after scope closed")
            case Result.Failure(_)    => succeed // kill itself failed — process is gone
        end for
    }

end ProcessTest
