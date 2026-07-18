package kyo

import org.scalatest.freespec.AnyFreeSpec
import scala.scalajs.js

/** Browser-safety regression guard for [[FlagPlatform]]'s Node `process` reads.
  *
  * `StaticFlag` resolves its value at class load through `FlagPlatform.env`. Scala.js emits `js.Dynamic.global.process` as a bare `process`
  * read, which throws `ReferenceError: process is not defined` in a browser where the global is undeclared, so a flag initialized in a browser
  * bundle would crash the module at load. Deleting `globalThis.process` reproduces that browser condition inside the Node test runner: a bare
  * read then throws while `typeof` stays safe. These leaves assert `env`/`envNames` degrade to null/empty instead of crashing when the global is
  * absent, and still return the real Node value when it is present.
  */
class FlagPlatformTest extends AnyFreeSpec {

    // `globalThis` (declared since ES2020) is a safe object handle for adding and removing the `process` property that Scala.js otherwise reads
    // as a bare identifier.
    private val globalObj = js.Dynamic.global.globalThis

    /** Runs `body` with `globalThis.process` deleted, reproducing a browser where the global is undeclared and a bare `process` read throws.
      * The delete/restore pair brackets a fully synchronous `body`, so the single-threaded runtime never observes the missing global from any
      * other code.
      */
    private def withoutProcess[A](body: => A): A = {
        val saved = globalObj.process
        val _     = js.Dynamic.global.Reflect.deleteProperty(globalObj, "process")
        try body
        finally { val _ = globalObj.updateDynamic("process")(saved) }
    }

    /** Runs `body` with `process.env[name] = value` set, clearing it afterward. */
    private def withEnv[A](name: String, value: String)(body: => A): A = {
        globalObj.process.env.updateDynamic(name)(value)
        try body
        finally { val _ = js.Dynamic.global.Reflect.deleteProperty(globalObj.process.env, name) }
    }

    "FlagPlatform browser safety" - {
        "env returns null without throwing when process is absent" in withoutProcess {
            assert(FlagPlatform.env("PATH") == null)
        }

        "envNames returns empty without throwing when process is absent" in withoutProcess {
            assert(FlagPlatform.envNames.isEmpty)
        }

        "env returns the Node value when process is present" in withEnv("KYO_FLAGPLATFORM_TEST", "flag-value") {
            assert(FlagPlatform.env("KYO_FLAGPLATFORM_TEST") == "flag-value")
        }

        "envNames includes a present Node variable" in withEnv("KYO_FLAGPLATFORM_TEST", "flag-value") {
            assert(FlagPlatform.envNames.exists(_ == "KYO_FLAGPLATFORM_TEST"))
        }
    }
}
