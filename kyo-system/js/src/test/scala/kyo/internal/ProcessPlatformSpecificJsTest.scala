package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

/** The Node child-process backend on a host whose `process` global is gone, the state a browser is in.
  *
  * A child's environment is built from `process.env` for the modes that inherit it. With no `process` there is nothing to inherit, so the
  * child sees exactly the variables the command sets. Each leaf spawns with `process` deleted and restores it before waiting, because the
  * test runner talks over `process.stdout`. The shell runs by absolute path, so the program lookup is not what is under test.
  */
class ProcessPlatformSpecificJsTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    override def config = super.config.sequential

    private def withoutProcessGlobal[A](f: => A): A =
        val global = scala.scalajs.js.Dynamic.global.globalThis
        val saved  = scala.scalajs.js.Dynamic.global.process
        scala.scalajs.js.special.delete(global, "process")
        try f
        finally global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    /** Spawns `command` with no `process` global and returns what the child printed. */
    private def outputWithoutProcess(command: Command)(using Frame): String < (Async & Abort[CommandException]) =
        withoutProcessGlobal(command.unsafe.spawn()) match
            case Result.Success(child) =>
                Scope.run(child.safe.collectOutput).map((out, _) => new String(out.toArray, StandardCharsets.UTF_8))
            case Result.Failure(error) => Abort.fail(error)
            case Result.Panic(error)   => Abort.panic(error)

    private val printVars = "printf %s \"${KYO_W1_APPENDED-unset}:${HOME-unset}\""

    "with no process global" - {
        "envAppend gives the child only the appended variables" in {
            assume(!Platform.isWindows, "POSIX shell")
            outputWithoutProcess(Command("/bin/sh", "-c", printVars).envAppend(Map("KYO_W1_APPENDED" -> "yes"))).map { out =>
                assert(out == "yes:unset")
            }
        }

        "envRemove gives the child an empty environment" in {
            assume(!Platform.isWindows, "POSIX shell")
            outputWithoutProcess(Command("/bin/sh", "-c", printVars).envRemove(Seq("KYO_W1_APPENDED"))).map { out =>
                assert(out == "unset:unset")
            }
        }

        "envAppend then envRemove applies both to nothing inherited" in {
            assume(!Platform.isWindows, "POSIX shell")
            val command = Command("/bin/sh", "-c", printVars)
                .envAppend(Map("KYO_W1_APPENDED" -> "yes", "HOME" -> "/appended-home"))
                .envRemove(Seq("HOME"))
            outputWithoutProcess(command).map(out => assert(out == "yes:unset"))
        }

        "a pipeline stage's envAppend gives it only the appended variables" in {
            assume(!Platform.isWindows, "POSIX shell")
            val command = Command("/bin/sh", "-c", printVars).envAppend(Map("KYO_W1_APPENDED" -> "yes"))
                .andThen(Command("/bin/cat"))
            outputWithoutProcess(command).map(out => assert(out == "yes:unset"))
        }

        "a bare program name is still spawned" in {
            assume(!Platform.isWindows, "POSIX shell")
            outputWithoutProcess(Command("sh", "-c", "printf %s found")).map(out => assert(out == "found"))
        }
    }

end ProcessPlatformSpecificJsTest
