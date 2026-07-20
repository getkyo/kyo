package kyo

import scala.jdk.CollectionConverters.*
import scala.scalajs.js

private[kyo] object FlagPlatform {

    def property(name: String): String =
        java.lang.System.getProperty(name)

    def properties: Iterable[String] =
        java.lang.System.getProperties.propertyNames().asScala.map(_.toString).toList

    // A host with no `process` global (a browser, or a Wasm host with no Node shim) or a missing variable
    // falls back to the stdlib read: `java.lang.System.getenv` always returns null under Scala.js-Node, but
    // a Wasm host may resolve it through its own environment binding, so the fallback still gives the
    // caller its best answer instead of a hardcoded null.
    def env(name: String): String = {
        val proc = js.Dynamic.global.process
        if (js.typeOf(proc) == "undefined" || js.typeOf(proc.env) == "undefined") java.lang.System.getenv(name)
        else {
            val value = proc.env.selectDynamic(name)
            if (js.isUndefined(value) || value == null) java.lang.System.getenv(name)
            else value.asInstanceOf[String]
        }
    }

    def envNames: Iterable[String] = {
        val proc = js.Dynamic.global.process
        if (js.typeOf(proc) == "undefined" || js.typeOf(proc.env) == "undefined") Iterable.empty
        else js.Object.keys(proc.env.asInstanceOf[js.Object]).toSeq
    }

}
