package kyo.internal

import scala.scalajs.js

/** Environment-variable resolution for [[kyo.Flag]], [[kyo.Rollout]] and [[kyo.DynamicFlag]] on Scala.js
  * (Node and Wasm).
  *
  * Scala.js makes `java.lang.System.getenv` always return `null`, so an environment-backed flag can never
  * resolve on Node through the shared path. Read Node's real `process.env` instead, mirroring kyo's
  * established `js.Dynamic.global.process.env` bypass. A host with no `process` global (a browser) or a
  * missing variable falls back to the stdlib read, so [[kyo.Flag]]'s resolution branch sees the same absent
  * value it sees on the JVM.
  */
private[kyo] object FlagPlatform {
    def env(name: String): String = {
        val process = js.Dynamic.global.process
        if (js.typeOf(process) == "undefined" || js.typeOf(process.env) == "undefined") java.lang.System.getenv(name)
        else {
            val raw = process.env.selectDynamic(name)
            if (js.isUndefined(raw) || raw == null) java.lang.System.getenv(name)
            else raw.asInstanceOf[String]
        }
    }

    /** Every environment-variable name the host exposes, for [[kyo.Flag]]'s near-miss scan. A host with no
      * `process` global (a browser) exposes none, the same empty result the stdlib read yields on Scala.js.
      */
    def envNames(): Seq[String] = {
        val process = js.Dynamic.global.process
        if (js.typeOf(process) == "undefined" || js.typeOf(process.env) == "undefined") Seq.empty
        else {
            val keys = js.Object.keys(process.env.asInstanceOf[js.Object])
            Seq.tabulate(keys.length)(i => keys(i))
        }
    }
}
