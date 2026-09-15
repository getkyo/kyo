package kyo.test.snapshot.internal

import kyo.internal.Platform
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite
import scala.scalajs.js as sjs

/** The snapshot store on a Scala.js host without Node's modules: the call fails naming the host, instead of a `TypeError` from a missing
  * `process.getBuiltinModule` or a `ReferenceError` from a missing `process`.
  */
class SnapshotStorePlatformTest extends AnyFunSuite with NonImplicitAssertions:

    /** Runs `f` with `process.getBuiltinModule` removed, the state of a Node that predates it. */
    private def withoutGetBuiltinModule[A](f: => A): A =
        val process = sjs.Dynamic.global.process
        val saved   = process.getBuiltinModule
        sjs.special.delete(process, "getBuiltinModule"): Unit
        try f
        finally process.updateDynamic("getBuiltinModule")(saved)
    end withoutGetBuiltinModule

    /** Runs `f` with `process` deleted from the global object, the state of a browser page. */
    private def withoutProcessGlobal[A](f: => A): A =
        val global = sjs.Dynamic.global.globalThis
        val saved  = sjs.Dynamic.global.process
        sjs.special.delete(global, "process"): Unit
        try f
        finally global.updateDynamic("process")(saved)
    end withoutProcessGlobal

    test("read without getBuiltinModule fails naming the host") {
        val error = withoutGetBuiltinModule(intercept[UnsupportedOperationException](SnapshotStorePlatform.read("target/any.txt")))
        assert(error.getMessage == s"Snapshot file I/O needs Node's node:fs module (Node, Bun or Deno); this host is ${Platform.host}")
    }

    test("write with no process global fails naming the host") {
        val (error, host) = withoutProcessGlobal {
            (intercept[UnsupportedOperationException](SnapshotStorePlatform.write("target/any.txt", "content")), Platform.host)
        }
        assert(error.getMessage == s"Snapshot file I/O needs Node's node:fs module (Node, Bun or Deno); this host is $host")
    }

end SnapshotStorePlatformTest
