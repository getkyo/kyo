package kyo

import org.scalatest.freespec.AnyFreeSpec
import scala.scalajs.js

object FlagSourceTestFlags {
    object seededProperty   extends StaticFlag[Int](1)
    object underThrowingEnv extends StaticFlag[String]("default")
}

/** Where a flag's value comes from on Scala.js: the host's sources first, then the `globalThis.KYO_CONFIG` seed.
  *
  * A JS program has no launch-time system properties and a browser has no environment, so an application seeds both through an object it
  * assigns before the program loads. The host still answers first: `process.env` for variables, `java.lang.System` for properties set at run
  * time.
  *
  * Deno refuses `process.env` reads without `--allow-env` by throwing from every trap of the object (`NotCapable`), and flags read the
  * environment inside class initializers, where a throw is fatal. The leaves reproduce that host by installing a proxy with the same traps
  * as `process.env`.
  */
class FlagSourceTest extends AnyFreeSpec {

    private def withSeed[A](env: js.Dictionary[Any], properties: js.Dictionary[Any])(f: => A): A = {
        val global = js.Dynamic.global.globalThis
        global.updateDynamic("KYO_CONFIG")(js.Dynamic.literal(env = env, properties = properties))
        try f
        finally js.special.delete(global, "KYO_CONFIG")
    }

    private def withEnvThatThrows[A](f: => A): A = {
        val process = js.Dynamic.global.process
        val saved   = process.env
        val refuse: js.Function = () => {
            val error = js.Dynamic.newInstance(js.Dynamic.global.Error)("Requires env access, run again with the --allow-env flag")
            error.updateDynamic("name")("NotCapable")
            js.special.`throw`(error)
        }
        val traps = js.Dynamic.literal(get = refuse, has = refuse, ownKeys = refuse, getOwnPropertyDescriptor = refuse)
        process.updateDynamic("env")(js.Dynamic.newInstance(js.Dynamic.global.Proxy)(js.Dynamic.literal(), traps))
        try f
        finally process.updateDynamic("env")(saved)
    }

    /** Runs `f` with `process` deleted from the global object. On a host that has no `process` to begin with, a page, `f` runs as it is. */
    private def withoutProcessGlobal[A](f: => A): A = {
        val global = js.Dynamic.global.globalThis
        kyo.internal.PlatformJs.jsGlobal("process").fold(f) { saved =>
            js.special.delete(global, "process")
            try f
            finally global.updateDynamic("process")(saved)
        }
    }

    private def assumeNodeEnv(): Unit = {
        val _ = assume(kyo.internal.Platform.isNodeLike, "sets or replaces Node's process.env, which a page has not")
    }

    "the KYO_CONFIG seed" - {
        "supplies a system property" in {
            val value = withSeed(js.Dictionary(), js.Dictionary("kyo.flagsourcetest.property" -> "seeded")) {
                Flag("kyo.flagsourcetest.property", "default")
            }
            assert(value == "seeded")
        }

        "makes a StaticFlag resolve from the system property source" in {
            val flag = withSeed(js.Dictionary(), js.Dictionary("kyo.FlagSourceTestFlags.seededProperty" -> 7)) {
                FlagSourceTestFlags.seededProperty
            }
            assert(flag.source == Flag.Source.SystemProperty)
            assert(flag() == 7)
        }

        "supplies an environment variable process.env does not set" in {
            val value = withSeed(js.Dictionary("KYO_FLAGSOURCETEST_ENV" -> "seeded"), js.Dictionary()) {
                Flag("kyo.flagsourcetest.env", "default")
            }
            assert(value == "seeded")
        }

        "supplies the environment on a host with no process global" in {
            val value = withSeed(js.Dictionary("KYO_FLAGSOURCETEST_NOPROCESS" -> "seeded"), js.Dictionary()) {
                withoutProcessGlobal(Flag("kyo.flagsourcetest.noprocess", "default"))
            }
            assert(value == "seeded")
        }

        "yields to process.env" in {
            assumeNodeEnv()
            js.Dynamic.global.process.env.updateDynamic("KYO_FLAGSOURCETEST_HOSTENV")("host")
            val value = withSeed(js.Dictionary("KYO_FLAGSOURCETEST_HOSTENV" -> "seeded"), js.Dictionary()) {
                Flag("kyo.flagsourcetest.hostenv", "default")
            }
            assert(value == "host")
        }

        "yields to a property set at run time" in {
            java.lang.System.setProperty("kyo.flagsourcetest.hostproperty", "host")
            try {
                val value = withSeed(js.Dictionary(), js.Dictionary("kyo.flagsourcetest.hostproperty" -> "seeded")) {
                    Flag("kyo.flagsourcetest.hostproperty", "default")
                }
                assert(value == "host")
            } finally {
                java.lang.System.clearProperty("kyo.flagsourcetest.hostproperty")
                ()
            }
        }
    }

    "a host whose process.env throws on every read (Deno without --allow-env)" - {
        "resolves a flag to its default instead of throwing" in {
            assumeNodeEnv()
            assert(withEnvThatThrows(Flag("kyo.flagsourcetest.throwing", "default")) == "default")
        }

        "initializes a StaticFlag, whose registration lists the environment, instead of throwing" in {
            assumeNodeEnv()
            val flag = withEnvThatThrows(FlagSourceTestFlags.underThrowingEnv)
            assert(flag.source == Flag.Source.Default)
            assert(flag() == "default")
        }

        "still reads the seed" in {
            assumeNodeEnv()
            val value = withSeed(js.Dictionary("KYO_FLAGSOURCETEST_THROWINGSEED" -> "seeded"), js.Dictionary()) {
                withEnvThatThrows(Flag("kyo.flagsourcetest.throwingseed", "default"))
            }
            assert(value == "seeded")
        }
    }

}
