package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

/** Installs a real DOM into the Node.js test process via jsdom.
  *
  * The JS and Wasm test environments are plain Node.js processes with no DOM globals. This helper imports jsdom and
  * copies the window's DOM constructors and globals onto `globalThis`, so production code compiled against
  * `org.scalajs.dom` (global `document`, `window`, and `instanceof Element` checks) runs unmodified. Installation is
  * lazy and idempotent: suites that never touch the DOM do not initialize jsdom, and repeated calls reuse the first
  * window.
  *
  * jsdom must be resolvable from the repo root: `npm install --no-save jsdom@^30` (CI does this in the setup action;
  * the repo ignores *.json, so there is no package.json to install from).
  */
private[kyo] object DomTestEnv:

    @js.native
    @JSImport("jsdom", "JSDOM")
    private class JSDOM(html: String, options: js.Object) extends js.Object:
        val window: js.Dynamic = js.native
    end JSDOM

    lazy val install: Unit =
        if js.typeOf(js.Dynamic.global.document) == "undefined" then
            // pretendToBeVisual enables requestAnimationFrame, which DomBackend uses to start SMIL animations.
            // jsdom still performs no layout: getBoundingClientRect stays zeroed, so measurement behavior belongs
            // in the real-Chrome suites, not here.
            val dom = new JSDOM(
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
