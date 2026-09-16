package kyo.internal

import kyo.*
import kyo.test.HostFilter
import scala.scalajs.js as sjs

/** The Node child-process backend on a host without Node's `child_process` module: a browser page, which has no `process` global at all,
  * or a Node that predates `process.getBuiltinModule`.
  *
  * Spawning panics with an `UnsupportedOperationException` naming the module and the host. `CommandException` has no case for a host that
  * cannot spawn, and every case it has (a missing program, a denied permission) would misreport one.
  */
class ProcessPlatformSpecificJsTest extends kyo.test.Test[Any]:

    // Exercises the node:child_process backend, which a browser has not.
    override protected def hostFilters = Chunk(HostFilter.NotBrowser)

    import AllowUnsafe.embrace.danger

    override def config = super.config.sequential

    /** Runs `f` with `process` deleted from the global object. Restored in a `finally` because the test runner talks over `process.stdout`. */
    private def withoutProcessGlobal[A](f: => A): A =
        val global = sjs.Dynamic.global.globalThis
        val saved  = sjs.Dynamic.global.process
        discard(sjs.special.delete(global, "process"))
        try f
        finally global.updateDynamic("process")(saved)
        end try
    end withoutProcessGlobal

    /** Runs `f` with `process.getBuiltinModule` removed. */
    private def withoutGetBuiltinModule[A](f: => A): A =
        val process = sjs.Dynamic.global.process
        val saved   = process.getBuiltinModule
        discard(sjs.special.delete(process, "getBuiltinModule"))
        try f
        finally process.updateDynamic("getBuiltinModule")(saved)
        end try
    end withoutGetBuiltinModule

    /** What came back: the failure and its message, or a description of whatever arrived instead.
      *
      * A host with no process table is reported on the channel `Command` declares, not as a panic: the caller can be
      * told, and a page is a host where being told is the only useful outcome.
      */
    private def refusalOf(result: Result[CommandException, Process.Unsafe]): (String, String) =
        result match
            case Result.Failure(e: CommandUnsupportedOnHostException) => (e.operation, e.host)
            case other                                                => ("no typed refusal", other.toString)

    /** The operation and host the failure names. Its rendered message carries a frame under a development build, so the
      * fields are what a test can pin.
      */
    private def refusal(host: Platform.Host): (String, String) =
        ("spawn", host.toString)

    "with no process global" - {
        "spawning fails naming the host instead of throwing ReferenceError" in {
            val (result, host) = withoutProcessGlobal((Command("/bin/sh", "-c", "true").unsafe.spawn(), Platform.host))
            assert(refusalOf(result) == refusal(host))
        }

        "a pipeline fails the same way" in {
            val (result, host) =
                withoutProcessGlobal((Command("/bin/sh", "-c", "true").andThen(Command("/bin/cat")).unsafe.spawn(), Platform.host))
            assert(refusalOf(result) == refusal(host))
        }
    }

    "without process.getBuiltinModule, spawning fails naming the host" in {
        val result = withoutGetBuiltinModule(Command("/bin/sh", "-c", "true").unsafe.spawn())
        assert(refusalOf(result) == refusal(Platform.host))
    }

end ProcessPlatformSpecificJsTest
