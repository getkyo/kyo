package kyo.internal

import kyo.*
import kyo.AllowUnsafe.embrace.danger
import scala.scalajs.js as sjs

/** `System.live` on Scala.js hosts: the host's environment and properties first, then the `globalThis.KYO_CONFIG` seed, and a user name that
  * is never `null`.
  */
class SystemPlatformSpecificJsWasmTest extends kyo.test.Test[Any]:

    // The leaves swap process-global state (the process global, process.env, the seed) around synchronous reads.
    override def config = super.config.sequential

    /** Runs `f` with `process` deleted from the global object, so it is an undeclared identifier: the state a
      * browser is in, and the one where a bare read throws while `typeof process` still answers "undefined".
      * Restored in a `finally` because the test runner talks over `process.stdout`.
      *
      * In a browser the host is in that state already, so `f` runs as it is: reading the global to save it would
      * throw the very ReferenceError these leaves are about.
      */
    private def withoutProcessGlobal[A](f: => A): A =
        // `globalThis`, not `sjs.Dynamic.global`, which Scala.js allows only left of a `.`-selection.
        val global = sjs.Dynamic.global.globalThis
        if sjs.typeOf(sjs.Dynamic.global.selectDynamic("process")) == "undefined" then f
        else
            val saved = sjs.Dynamic.global.process
            sjs.special.delete(global, "process")
            try f
            finally global.updateDynamic("process")(saved)
            end try
        end if
    end withoutProcessGlobal

    private def withSeed[A](seed: sjs.Any)(f: => A): A =
        val global = sjs.Dynamic.global.globalThis
        global.updateDynamic("KYO_CONFIG")(seed)
        try f
        finally discard(sjs.special.delete(global, "KYO_CONFIG"))
    end withSeed

    /** `process.env` replaced by a proxy that throws from every trap, as Deno's does without `--allow-env`. */
    private def withEnvThatThrows[A](f: => A): A =
        val process                    = sjs.Dynamic.global.process
        val saved                      = process.env
        val refuse: sjs.Function0[Any] = () => sjs.special.`throw`(sjs.Dynamic.newInstance(sjs.Dynamic.global.Error)("NotCapable"))
        val traps = sjs.Dynamic.literal(get = refuse, has = refuse, ownKeys = refuse, getOwnPropertyDescriptor = refuse)
        process.updateDynamic("env")(sjs.Dynamic.newInstance(sjs.Dynamic.global.Proxy)(sjs.Dynamic.literal(), traps))
        try f
        finally process.updateDynamic("env")(saved)
        end try
    end withEnvThatThrows

    private def live = System.live.unsafe

    "env" - {
        // Writes into `process.env`, which only a Node-like host has.
        "resolves a variable set in process.env".notBrowser in {
            sjs.Dynamic.global.process.env.updateDynamic("KYO_SYSTEMPLATFORM_PROBE")("enabled")
            assert(live.env("KYO_SYSTEMPLATFORM_PROBE") == Present("enabled"))
        }

        "is Absent for a name that is not set" in {
            assert(live.env("KYO_SYSTEMPLATFORM_UNSET") == Absent)
        }

        "is Absent with no process global, instead of throwing ReferenceError" in {
            assert(withoutProcessGlobal(live.env("PATH")) == Absent)
        }

        // Replaces `process.env` with a throwing proxy, which needs a `process` global to replace it on.
        "is Absent when every process.env read throws, instead of throwing".notBrowser in {
            assert(withEnvThatThrows(live.env("PATH")) == Absent)
        }

        "comes from the seed on a host with no process global" in {
            val seed = sjs.Dynamic.literal(env = sjs.Dynamic.literal(APP_ENV = "page"))
            assert(withSeed(seed)(withoutProcessGlobal(live.env("APP_ENV"))) == Present("page"))
        }
    }

    "property" - {
        "comes from the seed when nothing set it" in {
            val seed = sjs.Dynamic.literal(properties = sjs.Dynamic.literal("app.port" -> "8080"))
            assert(withSeed(seed)(live.property("app.port")) == Present("8080"))
        }
    }

    "userName" - {
        "is the OS user on Node".notBrowser in {
            val expected = sjs.Dynamic.global.process.getBuiltinModule("node:os").userInfo().username.asInstanceOf[String]
            assert(live.userName() == expected)
        }

        "is a seeded user.name property when one is set" in {
            val seed = sjs.Dynamic.literal(properties = sjs.Dynamic.literal("user.name" -> "seeded-user"))
            assert(withSeed(seed)(live.userName()) == "seeded-user")
        }

        "is empty on a host with no process global and no seed" in {
            assert(withoutProcessGlobal(live.userName()) == "")
        }
    }

end SystemPlatformSpecificJsWasmTest
