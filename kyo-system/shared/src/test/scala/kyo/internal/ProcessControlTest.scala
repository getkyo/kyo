package kyo.internal

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kyo.*

class ProcessControlTest extends kyo.test.Test[Any]:

    // Unsafe: exercises the native supervisor protocol and its owned runtime transport.
    import AllowUnsafe.embrace.danger

    private def withControl[A, S](script: String)(body: ProcessControl => A < S)(using
        Frame,
        kyo.test.AssertScope
    ): A < (Async & Abort[CommandException] & S) =
        if Platform.isWindows then cancel("Process groups require a Unix host")
        Scope.run {
            ProcessLaunch.init("sh").map { launch =>
                launch.start("sh", 7) { (executable, endpoint) =>
                    Command(executable, endpoint, "", "7", "/bin/sh", "-c", script).unsafe.spawnRaw()
                }.andThen(ProcessControlPlatform.init(launch)).map(body)
            }
        }
    end withControl

    "normal exit retains the exact exit code until acknowledged release" in {
        withControl("exit 200") { control =>
            for
                code <- control.waitFor.safe.get
                _    <- control.releaseGroup()
                _    <- control.releaseGroup()
            yield assert(code == ExitCode.Failure(200))
        }
    }

    "release terminates and reaps a live command before acknowledging cleanup" in {
        withControl("exec cat") { control =>
            for
                first  <- Fiber.init(control.releaseGroup())
                second <- Fiber.init(control.releaseGroup())
                _      <- first.get
                _      <- second.get
                code   <- control.waitFor.safe.get
            yield assert(code == ExitCode.Signaled(9))
        }
    }

    "signal status is distinct from an ordinary numeric exit" in {
        withControl("exec cat") { control =>
            Sync.defer(control.terminate()).andThen(control.waitFor.safe.get).map { code =>
                control.releaseGroup().andThen(assert(code == ExitCode.Signaled(15)))
            }
        }
    }

    "losing the transport never reports successful group cleanup" in {
        withControl("exec cat") { control =>
            control.close().andThen(control.waitFor.safe.getResult).map { result =>
                assert(result.isPanic)
            }
        }
    }

    "a truncated release acknowledgement fails instead of accepting EOF" in {
        Sync.defer {
            val bytes  = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putInt(6).putInt(0).array()
            var offset = 0
            Scope.run {
                Scope.acquireRelease(Sync.defer {
                    ProcessControl.init(
                        (target, start, length) =>
                            Sync.defer {
                                if offset == 7 then -1
                                else
                                    target(start) = bytes(offset)
                                    offset += 1
                                    1
                            },
                        _ => (),
                        () => Kyo.unit
                    )
                })(_.close()).map { control =>
                    Abort.run[IOException](control.releaseGroup()).map { result =>
                        assert(result.panic.map(_.getMessage) == Present("Process supervisor closed before acknowledging release"))
                    }
                }
            }
        }
    }
end ProcessControlTest
