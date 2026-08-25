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
  * jsdom must be resolvable from the repo root: `npm install --no-save jsdom@^30` (CI does this in the setup action;
  * the repo ignores *.json, so there is no package.json to install from).
  */
private[kyo] object DomTestEnv:

    lazy val install: Unit =
        if js.typeOf(js.Dynamic.global.document) == "undefined" then
            val jsdom = js.Dynamic.global.require("jsdom")
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
