package kyo.internal

import scala.jdk.CollectionConverters.*
import scala.scalajs.js

/** The process-wide environment variables and system properties that flags, rollouts, and `System.live` read.
  *
  * A JS program gets no system properties at launch and a browser has no environment, so an application can seed both through an object
  * assigned before the program loads:
  * {{{
  * globalThis.KYO_CONFIG = {
  *   env: { MYAPP_DB_POOLSIZE: "8" },
  *   properties: { "kyo.scheduler.cores": "2" }
  * }
  * }}}
  * The host answers first and the seed fills what it leaves unset:
  *   - a variable comes from `process.env` on a host that has one (Node, Bun, Deno), then from the seed's `env`;
  *   - a property comes from `java.lang.System.getProperty` (its built-in keys and anything set with `setProperty`), then from the seed's
  *     `properties`.
  *
  * The seed is read on every lookup, as `process.env` is. A string value is used as is and a number or boolean as `String(value)`; anything
  * else is unset, and a missing or malformed seed is no seed.
  *
  * Every read is total, because flags resolve inside class initializers, where a throw is fatal. A read the host refuses is unset and a
  * listing it refuses contributes no names: Deno throws from every `process.env` read made without `--allow-env`.
  */
object HostConfig {

    /** The `globalThis` property the seed is read from. */
    final val SeedGlobal = "KYO_CONFIG"

    /** The environment variable `name`, or `null` when neither the host nor the seed sets it. */
    def env(name: String): String = {
        val host = processEnv.fold(null: String)(env => attempt(null: String)(stringValue(env.selectDynamic(name))))
        if (host ne null) host else seed("env").fold(null: String)(values => attempt(null: String)(stringValue(values.selectDynamic(name))))
    }

    /** The names of the environment variables the host and the seed set. */
    def envNames: Iterable[String] = (processEnv.toList.flatMap(keys) ++ seed("env").toList.flatMap(keys)).distinct

    /** The system property `name`, or `null` when neither the host nor the seed sets it. */
    def property(name: String): String = {
        val host = java.lang.System.getProperty(name)
        if (host ne null) host
        else seed("properties").fold(null: String)(values => attempt(null: String)(stringValue(values.selectDynamic(name))))
    }

    /** The names of the system properties the host and the seed set. */
    def propertyNames: Iterable[String] =
        (java.lang.System.getProperties.propertyNames().asScala.map(_.toString).toList ++ seed("properties").toList.flatMap(keys)).distinct

    private def processEnv: js.UndefOr[js.Dynamic] =
        PlatformJs.jsGlobal("process").flatMap(process => attempt(js.undefined: js.UndefOr[js.Dynamic])(objectValue(process.env)))

    /** The seed's `section` object, or `undefined` when the seed or the section is missing or not an object. */
    private def seed(section: String): js.UndefOr[js.Dynamic] =
        PlatformJs.jsGlobal(SeedGlobal).flatMap(config =>
            attempt(js.undefined: js.UndefOr[js.Dynamic])(objectValue(config.selectDynamic(section)))
        )

    private def keys(obj: js.Dynamic): List[String] =
        attempt(List.empty[String])(js.Object.keys(obj.asInstanceOf[js.Object]).toList)

    private def objectValue(value: js.Dynamic): js.UndefOr[js.Dynamic] =
        if (js.typeOf(value) == "object" && value != null) value else js.undefined

    private def stringValue(value: js.Dynamic): String =
        js.typeOf(value) match {
            case "string"             => value.asInstanceOf[String]
            case "number" | "boolean" => js.Dynamic.global.String(value).asInstanceOf[String]
            case _                    => null
        }

    /** `read`, or `refused` when the host throws: a Deno permission check, or a hostile getter on a seed. */
    private def attempt[A](refused: A)(read: => A): A =
        try read
        catch { case _: js.JavaScriptException => refused }
}
