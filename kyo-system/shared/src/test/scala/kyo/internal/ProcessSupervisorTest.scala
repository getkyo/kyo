package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class ProcessSupervisorTest extends kyo.test.Test[Any]:

    // Unsafe: the runtime owns the helper; the tested process owns only its supervisor channel and transferred descriptors.
    import AllowUnsafe.embrace.danger

    private def init(script: String)(using Frame, kyo.test.AssertScope): ProcessSupervisor < (Async & Scope & Abort[CommandException]) =
        if Platform.isWindows then cancel("Process groups require a Unix host")
        ProcessSupervisor.init("sh", 7) { (executable, endpoint) =>
            Command(executable, endpoint, "", "7", "/bin/sh", "-c", script).unsafe.spawnRaw()
        }
    end init

    "process output and status come from the command rather than the helper" in {
        Scope.run {
            init("printf output; printf diagnostic >&2; exit 200").map { process =>
                for
                    result <- process.safe.collectOutput
                    code   <- process.safe.waitFor
                    _      <- process.close()
                yield
                    assert(new String(result._1.toArray, StandardCharsets.UTF_8) == "output")
                    assert(new String(result._2.toArray, StandardCharsets.UTF_8) == "diagnostic")
                    assert(code == ExitCode.Failure(200))
            }
        }
    }

    "group release preserves the buffered output prefix until the caller reads it" in {
        Scope.run {
            init("printf output; printf diagnostic >&2; exit 0").map { process =>
                for
                    code   <- process.safe.waitFor
                    _      <- process.safe.destroyTree
                    result <- process.safe.collectOutput
                    _      <- process.close()
                yield
                    assert(code == ExitCode.Success)
                    assert(new String(result._1.toArray, StandardCharsets.UTF_8) == "output")
                    assert(new String(result._2.toArray, StandardCharsets.UTF_8) == "diagnostic")
            }
        }
    }

    "concurrent terminal cleanup joins the supervisor once" in {
        Scope.run {
            init("exec cat").map { process =>
                for
                    a     <- Fiber.init(process.close())
                    b     <- Fiber.init(process.close())
                    _     <- a.get
                    _     <- b.get
                    code  <- process.safe.waitFor
                    alive <- process.safe.isAlive
                yield
                    assert(code == ExitCode.Signaled(9))
                    assert(!alive)
            }
        }
    }
end ProcessSupervisorTest
