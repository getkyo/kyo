package kyo

import scala.scalajs.js

/** Installs a real DOM into the Node.js test process via jsdom.
  *
  * The JS and Wasm test environments are plain Node.js processes with no DOM globals. This helper loads jsdom lazily
  * and copies the window's DOM constructors and globals onto `globalThis`, so production code compiled against
  * `org.scalajs.dom` (global `document`, `window`, and `instanceof Element` checks) runs unmodified. The load is lazy
  * and idempotent: suites that never touch the DOM never load jsdom, and repeated calls reuse the first window.
  *
  * The load goes through a runtime `require` rather than an `@JSImport`: an import fails at bundle load with Node's
  * own resolution error, before any test code can restate it. The JS test bundle is a CommonJS module and has
  * `require` in scope; the Wasm test bundle is an ES module and builds one with
  * `process.getBuiltinModule("module").createRequire`.
  *
  * jsdom must be resolvable from the repo root: `npm install --no-save --no-fund --no-audit jsdom@^30`, the same
  * command CI runs in its setup action. The repo ignores *.json, so there is no package.json to install from, and a
  * fresh checkout has no node_modules; the load below restates Node's resolution failure with that command in it.
  */
private[kyo] object DomTestEnv:

    lazy val install: Unit =
        if js.typeOf(js.Dynamic.global.document) == "undefined" then
            val require =
                if js.typeOf(js.Dynamic.global.require) == "function" then js.Dynamic.global.require
                else
                    js.Dynamic.global.process
                        .getBuiltinModule("module")
                        .createRequire(js.Dynamic.global.process.cwd().asInstanceOf[String] + "/")
            val jsdom =
                try require("jsdom")
                catch
                    case ex: js.JavaScriptException =>
                        // Node's own message is "Cannot find module 'jsdom'", which names neither the suite that
                        // needs it nor the command that supplies it. Restate it so a local run is self-explaining;
                        // CI installs jsdom in its setup action, so this path is the local-checkout one.
                        throw new IllegalStateException(
                            "the DOM-backed kyo-ui tests need jsdom, which is not resolvable from the repository root. " +
                                "Install it with: npm install --no-save --no-fund --no-audit jsdom@^30",
                            ex
                        )
            // pretendToBeVisual enables requestAnimationFrame, which DomBackend uses to start SMIL animations.
            // jsdom still performs no layout: getBoundingClientRect stays zeroed, so measurement behavior belongs
            // in the real-Chrome suites, not here.
            val dom = js.Dynamic.newInstance(jsdom.JSDOM)(
                "<!doctype html><html><head></head><body></body></html>",
                js.Dynamic.literal(pretendToBeVisual = true)
            )
            val window = dom.window
            js.Dynamic.global.globalThis.window = window
            js.Dynamic.global.globalThis.document = window.document
            js.Dynamic.global.globalThis.Element = window.Element
            js.Dynamic.global.globalThis.HTMLElement = window.HTMLElement
            js.Dynamic.global.globalThis.Node = window.Node
        end if
    end install

    /** Mount diagnostics whose [[MountReady.installed]] flips once a `DomBackend` mount has finished wiring itself up.
      *
      * `DomBackend.mountInto` writes the container's HTML early, then subscribes the reactive tree, opens the event
      * channel, forks the drain fiber, and only then installs event delegation and the drag runtime. Waiting for a
      * rendered node therefore does NOT mean a dispatched event will be seen: a click sent in that window reaches no
      * listener and is lost, and a test waiting for its effect waits forever. `dragRuntimeInstalled` is the last hook
      * `mountInto` calls before it parks, so it is the barrier a test must clear before dispatching anything.
      */
    final class MountReady extends kyo.internal.DomBackend.MountDiagnostics:
        private var ready                                                                    = false
        def installed: Boolean                                                               = ready
        def channelClosed(): Unit                                                            = ()
        def drainInterrupting(): Unit                                                        = ()
        def drainJoined(): Unit                                                              = ()
        override def dragRuntimeInstalled(runtime: kyo.internal.DomDragRuntime.Handle): Unit = ready = true
    end MountReady

end DomTestEnv
