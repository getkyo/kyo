package kyo

import scala.jdk.CollectionConverters.*
import scala.scalajs.js

private[kyo] object FlagPlatform {

    def property(name: String): String =
        java.lang.System.getProperty(name)

    def properties: Iterable[String] =
        java.lang.System.getProperties.propertyNames().asScala.map(_.toString).toList

    /** Must stay INLINE on the global selection: only then does Scala.js emit a plain `typeof process`, which
      * JS semantics make safe on an undeclared identifier. Binding the selection to a val first emits a bare
      * `process` read, which throws `ReferenceError` before any guard can run.
      */
    private def hasProcess: Boolean =
        js.typeOf(js.Dynamic.global.process) != "undefined"

    // A host with no `process` global (a browser, or a Wasm host with no Node shim) or a missing variable
    // falls back to the stdlib read: `java.lang.System.getenv` always returns null under Scala.js-Node, but
    // a Wasm host may resolve it through its own environment binding, so the fallback still gives the
    // caller its best answer instead of a hardcoded null.
    def env(name: String): String = {
        if (!hasProcess) java.lang.System.getenv(name)
        else {
            val proc = js.Dynamic.global.process
            if (js.typeOf(proc.env) == "undefined") java.lang.System.getenv(name)
            else {
                val value = proc.env.selectDynamic(name)
                if (js.isUndefined(value) || value == null) java.lang.System.getenv(name)
                else value.asInstanceOf[String]
            }
        }
    }

    def envNames: Iterable[String] = {
        if (!hasProcess) Iterable.empty
        else {
            val proc = js.Dynamic.global.process
            if (js.typeOf(proc.env) == "undefined") Iterable.empty
            else js.Object.keys(proc.env.asInstanceOf[js.Object]).toSeq
        }
    }

}
