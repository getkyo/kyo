package kyo.internal

import scala.scalajs.js

/** The Scala.js-only companion of [[Platform]]: host capabilities that have no meaning on the JVM or Scala Native.
  *
  * Every read goes through properties of `globalThis`. A bare identifier such as `process` throws a `ReferenceError` on a host that does not
  * declare it, before any guard can run; a property of `globalThis` evaluates to `undefined` instead, so nothing here can throw on any host.
  *
  * `nodeBuiltin` is the synchronous way to reach a Node built-in module without a static import. A static `@JSImport` of `node:fs` is
  * hoisted and resolved when the module graph loads, so a bundle that reaches one fails to load in a browser before any code runs;
  * `process.getBuiltinModule` resolves at the call instead, and only on hosts that provide it (Node 22.3 and 20.16, Deno 2.1, Bun 1.2.6,
  * and later).
  *
  * [[detectHost]] is a pure function of a global object, so the classification can be tested against fabricated globals for every host.
  *
  * The members that depend on how the application is linked, such as `moduleRequire`, come from [[PlatformJsStatic]], which each Scala
  * release line declares on its own.
  */
object PlatformJs extends PlatformJsStatic {

    /** `globalThis[name]`, or `undefined` when the host does not define it or defines it as `null`. */
    def jsGlobal(name: String): js.UndefOr[js.Dynamic] =
        defined(js.Dynamic.global.globalThis.selectDynamic(name))

    /** The Node built-in module `id` (for example `"node:fs"`), or `undefined` when the host has no `process.getBuiltinModule` or no such
      * module.
      */
    def nodeBuiltin(id: String): js.UndefOr[js.Dynamic] =
        jsGlobal("process").flatMap { process =>
            if (js.typeOf(process.getBuiltinModule) != "function") js.undefined
            else defined(process.getBuiltinModule(id))
        }

    /** Classifies the host that owns `global`. Deno and Bun are tested before Node because both also define `process`, and a browser is
      * recognized by `window` and `document` because Node defines `navigator` too.
      */
    def detectHost(global: js.Dynamic): Platform.Host = {
        val process = global.process
        if (defined(global.Deno).isDefined) Platform.Host.Deno
        else if (defined(global.Bun).isDefined) Platform.Host.Bun
        else if (
            defined(process).isDefined && defined(process.versions).isDefined &&
            js.typeOf(process.versions.node) == "string"
        ) Platform.Host.Node
        else if (
            js.typeOf(global.WorkerGlobalScope) == "function" &&
            js.special.instanceof(global, global.WorkerGlobalScope)
        ) Platform.Host.BrowserWorker
        else if (defined(global.window).isDefined && defined(global.document).isDefined) Platform.Host.BrowserMain
        else Platform.Host.OtherJs
    }

    /** `process[name]` when it is a string, and `""` otherwise, including on hosts without `process`. */
    private[kyo] def processString(name: String): String =
        jsGlobal("process").fold("") { process =>
            val value = process.selectDynamic(name)
            if (js.typeOf(value) == "string") value.asInstanceOf[String] else ""
        }

    private def defined(value: js.Dynamic): js.UndefOr[js.Dynamic] =
        if (js.isUndefined(value) || value == null) js.undefined else value
}
