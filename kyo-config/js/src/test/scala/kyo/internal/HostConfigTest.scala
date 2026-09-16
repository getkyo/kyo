package kyo.internal

import org.scalatest.freespec.AnyFreeSpec
import scala.scalajs.js

/** Scala.js resolution: the host's sources, then the `globalThis.KYO_CONFIG` seed.
  *
  * `java.lang.System.getenv` always returns null under Scala.js, so only a real `process.env` read resolves a variable on Node. Node's
  * `process.env` is mutable at run time (unlike the JVM's process environment), so these leaves set the variables they read. A page has no
  * `process`: the leaves that set or replace `process.env` cancel there, and the ones that remove `process` run as they are, in the state they
  * otherwise simulate.
  */
class HostConfigTest extends AnyFreeSpec {

    /** Runs `f` with `process` deleted from the global object, so it is an undeclared identifier: the state a browser is in, and the one where
      * a bare read throws while `typeof process` still answers "undefined". Restored in a `finally` because the test runner talks over
      * `process.stdout`. On a host that has no `process` to begin with, a page, `f` runs as it is.
      */
    private def withoutProcessGlobal[A](f: => A): A = {
        // `globalThis`, not `js.Dynamic.global`, which Scala.js allows only left of a `.`-selection.
        val global = js.Dynamic.global.globalThis
        PlatformJs.jsGlobal("process").fold(f) { saved =>
            js.special.delete(global, "process")
            try f
            finally global.updateDynamic("process")(saved)
        }
    }

    private def assumeNodeEnv(): Unit =
        assume(Platform.isNodeLike, "sets or replaces Node's process.env, which a page has not")

    private def withSeed[A](seed: js.Any)(f: => A): A = {
        val global = js.Dynamic.global.globalThis
        global.updateDynamic(HostConfig.SeedGlobal)(seed)
        try f
        finally js.special.delete(global, HostConfig.SeedGlobal)
    }

    /** `process.env` replaced by a proxy that throws from every trap, as Deno's does without `--allow-env`. */
    private def withEnvThatThrows[A](f: => A): A = {
        val process                   = js.Dynamic.global.process
        val saved                     = process.env
        val refuse: js.Function0[Any] = () => js.special.`throw`(js.Dynamic.newInstance(js.Dynamic.global.Error)("NotCapable"))
        val traps                     = js.Dynamic.literal(get = refuse, has = refuse, ownKeys = refuse, getOwnPropertyDescriptor = refuse)
        process.updateDynamic("env")(js.Dynamic.newInstance(js.Dynamic.global.Proxy)(js.Dynamic.literal(), traps))
        try f
        finally process.updateDynamic("env")(saved)
    }

    "env" - {
        "reads a variable set in Node process.env" in {
            assumeNodeEnv()
            js.Dynamic.global.process.env.updateDynamic("KYO_HOSTCONFIG_PROBE")("enabled")
            // The stdlib read is the control: it returns null on Scala.js even though the variable is set, which is exactly the defect the
            // platform-specific resolver exists to fix.
            assert(java.lang.System.getenv("KYO_HOSTCONFIG_PROBE") eq null)
            assert(HostConfig.env("KYO_HOSTCONFIG_PROBE") == "enabled")
        }

        "returns null for a name that is not set in Node process.env" in {
            assert(HostConfig.env("KYO_HOSTCONFIG_UNSET") eq null)
        }

        "returns null with no process global, instead of throwing ReferenceError" in {
            // On Node the variable is set first, so a null proves the read left process.env alone rather than finding nothing there.
            PlatformJs.jsGlobal("process").foreach(_.env.updateDynamic("KYO_HOSTCONFIG_PROBE")("enabled"))
            assert(withoutProcessGlobal(HostConfig.env("KYO_HOSTCONFIG_PROBE")) eq null)
        }

        "returns null when every process.env read throws" in {
            assumeNodeEnv()
            assert(withEnvThatThrows(HostConfig.env("HOME")) eq null)
        }

        "takes a variable process.env does not set from the seed" in {
            val value = withSeed(js.Dynamic.literal(env = js.Dynamic.literal(KYO_HOSTCONFIG_SEEDED = "seeded"))) {
                HostConfig.env("KYO_HOSTCONFIG_SEEDED")
            }
            assert(value == "seeded")
        }

        "prefers process.env to the seed" in {
            assumeNodeEnv()
            js.Dynamic.global.process.env.updateDynamic("KYO_HOSTCONFIG_BOTH")("host")
            val value = withSeed(js.Dynamic.literal(env = js.Dynamic.literal(KYO_HOSTCONFIG_BOTH = "seeded"))) {
                HostConfig.env("KYO_HOSTCONFIG_BOTH")
            }
            assert(value == "host")
        }

        "takes the seed with no process global" in {
            val seed = js.Dynamic.literal(env = js.Dynamic.literal(HOME = "/seeded-home"))
            assert(withSeed(seed)(withoutProcessGlobal(HostConfig.env("HOME"))) == "/seeded-home")
        }

        "takes the seed when process.env throws" in {
            assumeNodeEnv()
            val seed = js.Dynamic.literal(env = js.Dynamic.literal(HOME = "/seeded-home"))
            assert(withSeed(seed)(withEnvThatThrows(HostConfig.env("HOME"))) == "/seeded-home")
        }
    }

    "envNames" - {
        "lists the names Node process.env carries" in {
            assumeNodeEnv()
            js.Dynamic.global.process.env.updateDynamic("KYO_HOSTCONFIG_NAMES_PROBE")("1")
            assert(HostConfig.envNames.exists(_ == "KYO_HOSTCONFIG_NAMES_PROBE"))
        }

        "is empty with no process global, instead of throwing ReferenceError" in {
            assert(withoutProcessGlobal(HostConfig.envNames).isEmpty)
        }

        "adds the seed's names once, and lists only them when process.env throws" in {
            assumeNodeEnv()
            js.Dynamic.global.process.env.updateDynamic("KYO_HOSTCONFIG_NAMES_BOTH")("1")
            val seed  = js.Dynamic.literal(env = js.Dynamic.literal(KYO_HOSTCONFIG_NAMES_BOTH = "2", KYO_HOSTCONFIG_NAMES_SEEDED = "3"))
            val names = withSeed(seed)(HostConfig.envNames).toList
            assert(names.count(_ == "KYO_HOSTCONFIG_NAMES_BOTH") == 1)
            assert(names.contains("KYO_HOSTCONFIG_NAMES_SEEDED"))
            assert(withSeed(seed)(withEnvThatThrows(HostConfig.envNames)).toSet == Set(
                "KYO_HOSTCONFIG_NAMES_BOTH",
                "KYO_HOSTCONFIG_NAMES_SEEDED"
            ))
        }
    }

    "property" - {
        "takes a property the host does not set from the seed" in {
            val value = withSeed(js.Dynamic.literal(properties = js.Dynamic.literal("kyo.hostconfig.seeded" -> "seeded"))) {
                HostConfig.property("kyo.hostconfig.seeded")
            }
            assert(value == "seeded")
        }

        "prefers a property set at run time to the seed" in {
            java.lang.System.setProperty("kyo.hostconfig.both", "host")
            try {
                val value = withSeed(js.Dynamic.literal(properties = js.Dynamic.literal("kyo.hostconfig.both" -> "seeded"))) {
                    HostConfig.property("kyo.hostconfig.both")
                }
                assert(value == "host")
            } finally {
                java.lang.System.clearProperty("kyo.hostconfig.both")
                ()
            }
        }

        "lists the seed's names with the host's" in {
            val names = withSeed(js.Dynamic.literal(properties = js.Dynamic.literal("kyo.hostconfig.listed" -> "1"))) {
                HostConfig.propertyNames.toList
            }
            assert(names.contains("kyo.hostconfig.listed"))
            assert(names.contains("line.separator"))
        }

        "is null when neither sets it" in {
            assert(HostConfig.property("kyo.hostconfig.unset") eq null)
        }
    }

    "the seed's values" - {
        "a number or boolean reads as its string form" in {
            val seed = js.Dynamic.literal(properties = js.Dynamic.literal("kyo.hostconfig.number" -> 8, "kyo.hostconfig.boolean" -> true))
            assert(withSeed(seed)(HostConfig.property("kyo.hostconfig.number")) == "8")
            assert(withSeed(seed)(HostConfig.property("kyo.hostconfig.boolean")) == "true")
        }

        "an object, array, null or function is unset" in {
            val seed = js.Dynamic.literal(properties =
                js.Dynamic.literal(
                    "kyo.hostconfig.object"   -> js.Dynamic.literal(),
                    "kyo.hostconfig.array"    -> js.Array(1),
                    "kyo.hostconfig.null"     -> null,
                    "kyo.hostconfig.function" -> ((() => 1): js.Function0[Int])
                )
            )
            List("object", "array", "null", "function").foreach { kind =>
                assert(withSeed(seed)(HostConfig.property(s"kyo.hostconfig.$kind")) eq null)
            }
        }

        "a getter that throws is unset" in {
            val properties = js.Dynamic.literal()
            js.Dynamic.global.Object.defineProperty(
                properties,
                "kyo.hostconfig.throwing",
                js.Dynamic.literal(get = ((() => js.special.`throw`("refused")): js.Function0[Any]), enumerable = true)
            )
            assert(withSeed(js.Dynamic.literal(properties = properties))(HostConfig.property("kyo.hostconfig.throwing")) eq null)
        }
    }

    "a malformed seed is no seed" - {
        "not an object" in {
            assert(withSeed("env=HOME")(HostConfig.env("KYO_HOSTCONFIG_MALFORMED")) eq null)
            assert(withSeed("env=HOME")(HostConfig.envNames).toSet == HostConfig.envNames.toSet)
        }

        "sections that are not objects" in {
            val seed = js.Dynamic.literal(env = "KYO_HOSTCONFIG_MALFORMED=1", properties = 7)
            assert(withSeed(seed)(HostConfig.env("KYO_HOSTCONFIG_MALFORMED")) eq null)
            assert(withSeed(seed)(HostConfig.property("kyo.hostconfig.malformed")) eq null)
        }
    }

}
