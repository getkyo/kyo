package kyo.internal

import java.io.IOException
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import scala.scalajs.js as sjs

/** `Console.readLine` on a Scala.js host that cannot read standard input.
  *
  * The read reaches Node's `fs` through `process.getBuiltinModule` at the call. A host without it, such as a browser page or a Node older
  * than 20.16, has no standard input to read, and the read has to fail as the `IOException` `Console.readLine` declares rather than throw a
  * `TypeError` from a missing function or a `ReferenceError` from a missing `process`.
  */
class ConsolePlatformSpecificTest extends kyo.test.Test[Any]:

    // The leaves swap process-global state around synchronous reads.
    override def config = super.config.sequential

    /** Runs `f` with `process` deleted from the global object, the state of a browser page. Restored in a `finally` because the test runner
      * talks over `process.stdout`. A page is in that state already, and reading the global there to save it would throw the
      * ReferenceError the leaf is about, so `f` runs as it is.
      */
    private def withoutProcessGlobal[A](f: => A): A =
        if sjs.typeOf(sjs.Dynamic.global.selectDynamic("process")) == "undefined" then f
        else
            val global = sjs.Dynamic.global.globalThis
            val saved  = sjs.Dynamic.global.process
            discard(sjs.special.delete(global, "process"))
            try f
            finally global.updateDynamic("process")(saved)
    end withoutProcessGlobal

    /** Runs `f` with `process.getBuiltinModule` removed, the state of a Node that predates it. A host with no `process` at all
      * reaches the same read path, so there is nothing to remove there.
      */
    private def withoutGetBuiltinModule[A](f: => A): A =
        if sjs.typeOf(sjs.Dynamic.global.selectDynamic("process")) == "undefined" then f
        else
            val process = sjs.Dynamic.global.process
            val saved   = process.getBuiltinModule
            discard(sjs.special.delete(process, "getBuiltinModule"))
            try f
            finally process.updateDynamic("getBuiltinModule")(saved)
    end withoutGetBuiltinModule

    /** The failure's exact class and message, so a leaf pins both: an `EOFException` is also an `IOException`, and would mean the read ran. */
    private def failure(result: Result[IOException, String]): Maybe[(String, String)] =
        result match
            case Result.Failure(error) => Present((error.getClass.getName, error.getMessage))
            case _                     => Absent

    "readLine" - {
        "fails with an IOException naming the host when there is no process global" in {
            val (result, host) = withoutProcessGlobal((ConsolePlatformSpecific.readLine(), Platform.host))
            assert(
                failure(result) == Present((
                    "java.io.IOException",
                    s"Console.readLine needs Node's fs module to read standard input (Node, Bun or Deno); this host is $host"
                ))
            )
        }

        "fails with an IOException when process has no getBuiltinModule" in {
            val result = withoutGetBuiltinModule(ConsolePlatformSpecific.readLine())
            assert(
                failure(result) == Present((
                    "java.io.IOException",
                    s"Console.readLine needs Node's fs module to read standard input (Node, Bun or Deno); this host is ${Platform.host}"
                ))
            )
        }
    }

end ConsolePlatformSpecificTest
