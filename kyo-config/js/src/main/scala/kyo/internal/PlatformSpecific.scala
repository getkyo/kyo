package kyo.internal

import scala.scalajs.js

/** The Scala.js facts behind [[Platform]], for both JS and WasmGC links.
  *
  * The host is detected on each call from properties of `globalThis`, which cannot throw a `ReferenceError` the way a bare identifier does
  * on a host that lacks it. The operating system and architecture come from `process.platform` and `process.arch` where the host exposes
  * them, and are `Unknown` elsewhere, such as in a browser.
  */
abstract class PlatformSpecific extends PlatformStatic with PlatformOsFromValues {
    final val isJVM          = false
    final val isJS           = true
    final val isNative       = false
    final val isDebugEnabled = false

    /** The host this code runs on, detected from the global object. */
    def host: Platform.Host = PlatformJs.detectHost(js.Dynamic.global.globalThis)

    /** Node, Bun, or Deno with a `process` global: after this check, reading `process` cannot throw. */
    def isNodeLike: Boolean = {
        val h = host
        ((h eq Platform.Host.Node) || (h eq Platform.Host.Bun) || (h eq Platform.Host.Deno)) && PlatformJs.jsGlobal("process").isDefined
    }

    /** A browser main thread or a browser worker. */
    def isBrowser: Boolean = {
        val h = host
        (h eq Platform.Host.BrowserMain) || (h eq Platform.Host.BrowserWorker)
    }

    val os: Platform.Os = {
        val token = PlatformJs.processString("platform")
        if (token.isEmpty) Platform.Os.Unknown else Platform.Os.fromNodePlatform(token)
    }

    val arch: Platform.Arch = {
        val token = PlatformJs.processString("arch")
        if (token.isEmpty) Platform.Arch.Unknown else Platform.Arch.fromToken(token)
    }

    /** Records `code` as the process exit status on Node-like hosts, which exit once the event loop drains. A browser page has no exit status,
      * so elsewhere this does nothing.
      */
    def exit(code: Int): Unit =
        PlatformJs.jsGlobal("process").foreach(_.updateDynamic("exitCode")(code))
}
