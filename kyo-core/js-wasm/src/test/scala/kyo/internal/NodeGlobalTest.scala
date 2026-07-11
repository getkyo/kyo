package kyo.internal

import kyo.*
import kyo.System.Arch
import kyo.System.OS
import scala.scalajs.js

/** Manipulates the `process` global for [[NodeGlobalTest]].
  *
  * Kept outside the suite so `js` resolves to the `scala.scalajs.js` package rather than the `js` platform-tag method the test base inherits.
  * `globalThis` (declared since ES2020) is the object handle used to add and remove the `process` property that Scala.js otherwise reads as a
  * bare identifier.
  */
private object NodeGlobalHarness:
    private val globalObj = js.Dynamic.global.globalThis

    /** Runs `body` with `globalThis.process` deleted, reproducing a browser where the global is undeclared and a bare `process` read throws.
      * The delete/restore pair brackets a fully synchronous `body`, so the single-threaded runtime never observes the missing global from any
      * other code.
      */
    def withoutProcess[A](body: => A): A =
        val saved = globalObj.process
        discard(js.Dynamic.global.Reflect.deleteProperty(globalObj, "process"))
        try body
        finally globalObj.updateDynamic("process")(saved)
    end withoutProcess

    /** Runs `body` with `process.env[name] = value` set, clearing it afterward. */
    def withEnv[A](name: String, value: String)(body: => A): A =
        globalObj.process.env.updateDynamic(name)(value)
        try body
        finally discard(js.Dynamic.global.Reflect.deleteProperty(globalObj.process.env, name))
    end withEnv

    /** True when `NodeGlobal.env` reports the variable as absent (an empty object reads back `undefined`). */
    def envAbsent(name: String): Boolean = js.isUndefined(NodeGlobal.env.selectDynamic(name))

    /** The value `NodeGlobal.env` exposes for `name`, as a plain string. */
    def envValue(name: String): String = NodeGlobal.env.selectDynamic(name).asInstanceOf[String]
end NodeGlobalHarness

/** Browser-safety regression guard for [[NodeGlobal]] and the JS platform shims that read Node's `process` global (SystemPlatformSpecific,
  * PathPlatformSpecific, ProcessPlatformSpecific).
  *
  * Scala.js emits `js.Dynamic.global.process` as a bare `process` read, which throws `ReferenceError: process is not defined` in a browser
  * where the global is undeclared. [[NodeGlobalHarness.withoutProcess]] reproduces that browser condition inside the Node test runner: a bare
  * read then throws while `typeof` stays safe. These leaves assert every shim degrades to its documented fallback instead of crashing when the
  * global is absent, and still returns the real Node value when it is present.
  */
class NodeGlobalTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Deleting the process global mutates shared runtime state for the duration of a leaf; keep the suite sequential so no other leaf reads
    // the global while it is removed.
    override def config = super.config.sequential

    "process absent (browser)" - {
        "NodeGlobal reports the global as absent and yields an empty env" in NodeGlobalHarness.withoutProcess {
            assert(NodeGlobal.process.isEmpty)
            assert(NodeGlobalHarness.envAbsent("PATH"))
        }

        "SystemPlatformSpecific degrades without throwing" in NodeGlobalHarness.withoutProcess {
            assert(SystemPlatformSpecific.env("PATH") == null)
            assert(SystemPlatformSpecific.osName() == "")
            assert(SystemPlatformSpecific.osArch() == "")
        }

        "System.live falls back to absent env and Unknown os/arch" in NodeGlobalHarness.withoutProcess {
            val u = System.live.unsafe
            assert(u.env("PATH").isEmpty)
            assert(u.operatingSystem() == OS.Unknown)
            assert(u.architecture() == Arch.Unknown)
        }
    }

    "process present (Node)" - {
        "NodeGlobal and System route through to the real process env" in NodeGlobalHarness.withEnv("KYO_NODEGLOBAL_TEST", "node-value") {
            assert(NodeGlobal.process.isDefined)
            assert(NodeGlobalHarness.envValue("KYO_NODEGLOBAL_TEST") == "node-value")
            assert(SystemPlatformSpecific.env("KYO_NODEGLOBAL_TEST") == "node-value")
            assert(System.live.unsafe.env("KYO_NODEGLOBAL_TEST").contains("node-value"))
        }

        "os detection returns concrete Node values" in {
            assert(SystemPlatformSpecific.osName().nonEmpty)
            assert(SystemPlatformSpecific.osArch().nonEmpty)
            assert(System.live.unsafe.operatingSystem() != OS.Unknown)
        }
    }
end NodeGlobalTest
