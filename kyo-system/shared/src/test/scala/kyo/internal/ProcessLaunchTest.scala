package kyo.internal

import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.ffi.*

class ProcessLaunchTest extends kyo.test.Test[Any]:

    // Unsafe: exercises the supervisor acquisition owner through the runtime's ordinary process API.
    import AllowUnsafe.embrace.danger

    private def supervisor(executable: String, endpoint: String, program: String)(using Frame): Result[CommandException, Process.Unsafe] =
        Command(executable, endpoint, "", "7", program).unsafe.spawnRaw()

    "unscoped handover releases the acquisition diagnostic buffer" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Sync.defer {
            val actual     = Ffi.load[ProcessLaunchBindings]
            val diagnostic = new AtomicReference[Maybe[Buffer[Int]]](Absent)
            val bindings   = new ProcessLaunchBindings:
                export actual.accept
                export actual.await
                export actual.cancel
                export actual.disconnect
                export actual.endpoint
                export actual.executable
                export actual.helper_exited
                export actual.release
                export actual.take_control
                export actual.take_listener
                export actual.take_pipe
                export actual.take_stream
                def init(directory: String, error: Buffer[Int])(using
                    AllowUnsafe
                ): Fiber.Unsafe[Maybe[Ffi.Handle[ProcessLaunchHandle]], Any] =
                    diagnostic.set(Present(error))
                    actual.init(directory, error)
                end init
            Scope.run {
                for
                    _      <- Scope.ensure(Sync.defer(diagnostic.get().foreach(_.close())))
                    launch <- Scope.acquireRelease(Scope.runUnowned(ProcessLaunch.init("true", bindings)))(_.close())
                    _      <- launch.close()
                yield assert(diagnostic.get().get.isClosed)
            }
        }
    }

    "scope release joins the helper and removes its private executable" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Sync.defer {
            val helper = new AtomicReference[Maybe[Process.Unsafe]](Absent)
            val image  = new AtomicReference[Maybe[Path]](Absent)
            Scope.run {
                ProcessLaunch.init("cat").map { launch =>
                    launch.start("cat", 7) { (executable, endpoint) =>
                        image.set(Present(Path(executable)))
                        supervisor(executable, endpoint, "cat").map { process =>
                            helper.set(Present(process))
                            process
                        }
                    }.map(pid => assert(pid > 0))
                }
            }.map { _ =>
                assert(helper.get().get.exitCode() == Present(ExitCode.Success))
                assert(!image.get().get.unsafe.exists().getOrThrow)
            }
        }
    }

    "a helper that exits before connecting fails acquisition and is reaped" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Sync.defer {
            val helper = new AtomicReference[Maybe[Process.Unsafe]](Absent)
            Abort.run[CommandException] {
                Scope.run {
                    ProcessLaunch.init("true").map { launch =>
                        launch.start("true", 7) { (_, _) =>
                            Command("true").unsafe.spawnRaw().map { process =>
                                helper.set(Present(process))
                                process
                            }
                        }
                    }
                }
            }.map { result =>
                result match
                    case Result.Failure(ProcessLaunchException("true", 10)) => ()
                    case other => fail(s"Expected the helper's early exit to report ECHILD, got $other")
                assert(helper.get().get.exitCode() == Present(ExitCode.Success))
            }
        }
    }

    "an exec failure retains its typed error even when the helper has already exited" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Abort.run[CommandException] {
            Scope.run {
                ProcessLaunch.init("/kyo/missing/executable").map { launch =>
                    launch.start("/kyo/missing/executable", 7) { (executable, endpoint) =>
                        supervisor(executable, endpoint, "/kyo/missing/executable")
                    }
                }
            }
        }.map { result =>
            result match
                case Result.Failure(error) => assert(error == ProgramNotFoundException("/kyo/missing/executable"))
                case other                 => fail(s"Expected the command's ENOENT, got $other")
        }
    }

    "interrupting the owner joins the acquired supervisor before the scope ends" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Sync.defer {
            val helper = new AtomicReference[Maybe[Process.Unsafe]](Absent)
            Scope.run {
                for
                    ready    <- Latch.init(1)
                    consumer <- Fiber.init {
                        Scope.run {
                            ProcessLaunch.init("cat").map { launch =>
                                launch.start("cat", 7) { (executable, endpoint) =>
                                    supervisor(executable, endpoint, "cat").map { process =>
                                        helper.set(Present(process))
                                        process
                                    }
                                }.andThen(ready.release).andThen(Async.never)
                            }
                        }
                    }
                    _      <- ready.await
                    _      <- consumer.interrupt
                    result <- consumer.getResult
                yield assert(result.isPanic)
            }.map { _ =>
                assert(helper.get().get.exitCode() == Present(ExitCode.Success))
            }
        }
    }

    "concurrent explicit closes and the scope finalizer join one cleanup" in {
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Scope.run {
            ProcessLaunch.init("cat").map { launch =>
                launch.start("cat", 7)((executable, endpoint) => supervisor(executable, endpoint, "cat")).map { _ =>
                    for
                        first  <- Fiber.init(launch.close())
                        second <- Fiber.init(launch.close())
                        a      <- first.getResult
                        b      <- second.getResult
                        _      <- launch.close()
                    yield
                        assert(a == Result.succeed(()))
                        assert(b == Result.succeed(()))
                }
            }
        }
    }
end ProcessLaunchTest
