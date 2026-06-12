package demo

import kyo.*

/** Minimal runnable Quickstart that mirrors the README's first code block.
  *
  * Run via `sbt 'kyo-browserJVM/runMain demo.QuickstartApp'`. On first invocation it downloads Chrome-for-Testing (cached under
  * `~/Library/Caches/kyo-browser/` thereafter), navigates to a self-contained HTML page (a `data:` URL, no third-party site needed), types
  * a query into an input, clicks a button to populate a result heading, asserts the heading became visible with the expected text, prints
  * the page title, and shuts everything down on scope exit. Demonstrates the five most common operations (`goto`, `fill`, `click`,
  * `assertText`, `title`) against a deterministic, dependency-free target.
  */
object QuickstartApp extends KyoApp:

    private val page =
        """<!doctype html><html><head><title>Kyo Browser Quickstart</title></head><body>""" +
            """<input id="q" type="text">""" +
            """<button id="go" onclick="document.getElementById('result').textContent='Hello, '+document.getElementById('q').value">Go</button>""" +
            """<h1 id="result"></h1></body></html>"""

    run {
        Scope.run {
            Browser.run {
                for
                    _ <- Browser.goto(Browser.dataUrl(page))
                    _ <- Browser.fill(Browser.Selector.css("#q"), "kyo browser")
                    _ <- Browser.click(Browser.Selector.css("#go"))
                    _ <- Browser.assertText(Browser.Selector.css("#result"), "Hello, kyo browser")
                    t <- Browser.title
                    _ <- Console.printLine(s"Page title: $t")
                yield ()
            }
        }
    }
end QuickstartApp
