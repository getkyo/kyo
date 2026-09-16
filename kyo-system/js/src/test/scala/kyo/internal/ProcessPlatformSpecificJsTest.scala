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

    private def message(host: Platform.Host): String =
        s"kyo-system needs Node's node:child_process module for this operation (Node 20.16 or 22.3, Bun 1.2.6, Deno 2.1, or later); this host is $host"

    /** The panic's class name and message, or a description of what came back instead. */
    private def panicOf(result: Result[CommandException, Process.Unsafe]): (String, String) =
        result match
            case Result.Panic(error) => (error.getClass.getName, error.getMessage)
            case other               => ("no panic", other.toString)

    "with no process global" - {
        "spawning panics naming the module and the host instead of throwing ReferenceError" in {
            val (result, host) = withoutProcessGlobal((Command("/bin/sh", "-c", "true").unsafe.spawn(), Platform.host))
            assert(panicOf(result) == ("java.lang.UnsupportedOperationException", message(host)))
        }

        "a pipeline panics the same way" in {
            val (result, host) =
                withoutProcessGlobal((Command("/bin/sh", "-c", "true").andThen(Command("/bin/cat")).unsafe.spawn(), Platform.host))
            assert(panicOf(result) == ("java.lang.UnsupportedOperationException", message(host)))
        }
    }

    "without process.getBuiltinModule, spawning panics naming the module and the host" in {
        val result = withoutGetBuiltinModule(Command("/bin/sh", "-c", "true").unsafe.spawn())
        assert(panicOf(result) == ("java.lang.UnsupportedOperationException", message(Platform.host)))
    }

end ProcessPlatformSpecificJsTest
