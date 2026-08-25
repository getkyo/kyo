package kyo

import scala.scalajs.js

/** Installs a real DOM into the Node.js test process via jsdom.
  *
  * The test environment is plain Node.js with no DOM globals. This helper loads jsdom lazily through the CommonJS
  * `require` in scope for the test bundle and copies the window's DOM constructors and globals onto `globalThis`, so
  * production code compiled against `org.scalajs.dom` (global `document`, `window`, and `instanceof Element` checks)
  * runs unmodified. The load is lazy and idempotent: suites that never touch the DOM never require jsdom, and repeated
  * calls reuse the first window.
  *
  * jsdom must be resolvable from the repo root: `npm install --no-save --no-fund --no-audit jsdom@^30`, the same
  * command CI runs in its setup action. The repo ignores *.json, so there is no package.json to install from, and a
  * fresh checkout has no node_modules; the load below restates Node's resolution failure with that command in it.
  */
private[kyo] object DomTestEnv:

    lazy val install: Unit =
        if js.typeOf(js.Dynamic.global.document) == "undefined" then
            val jsdom =
                try js.Dynamic.global.require("jsdom")
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

end DomTestEnv
