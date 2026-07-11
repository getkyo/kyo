package kyo

import scala.jdk.CollectionConverters.*
import scala.scalajs.js

private[kyo] object FlagPlatform {

    def property(name: String): String =
        java.lang.System.getProperty(name)

    def properties: Iterable[String] =
        java.lang.System.getProperties.propertyNames().asScala.map(_.toString).toList

    def env(name: String): String =
        processEnv.toOption match {
            case Some(e) =>
                val value = e.selectDynamic(name)
                if (js.isUndefined(value) || value == null) null
                else value.asInstanceOf[String]
            case None => null
        }

    def envNames: Iterable[String] =
        processEnv.toOption match {
            case Some(e) => js.Object.keys(e.asInstanceOf[js.Object]).toSeq
            case None    => Iterable.empty
        }

    // Browser-safe access to `process.env`. Scala.js emits `js.Dynamic.global.process` as a bare `process` read, which throws
    // `ReferenceError: process is not defined` in a browser where the global is undeclared. A `typeof` on the bare identifier is the one form
    // that does not throw, so the global is read only after `typeof` confirms it is declared. In a browser the env is absent, so `env` reads
    // back null and `envNames` empty, matching the JVM behavior when a variable is unset.
    private def processEnv: js.UndefOr[js.Dynamic] =
        if (js.typeOf(js.Dynamic.global.process) == "undefined") js.undefined
        else {
            val proc = js.Dynamic.global.process
            if (js.typeOf(proc.env) == "undefined") js.undefined
            else proc.env
        }

}
