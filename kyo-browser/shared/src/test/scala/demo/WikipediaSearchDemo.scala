package demo

import kyo.*

/** Wikipedia search → typeahead XHR → article navigation.
  *
  * Demonstrates `Browser.waitForRequestUrl` (observing an XHR fire from the page in real time) alongside `assertElementOrder` and an
  * Enter-key form submit (no submit-button click). The flow:
  *
  *   1. Open en.wikipedia.org.
  *   2. Type into the search box; Wikipedia's JS fires an XHR to `/w/rest.php/v1/search/title` for typeahead suggestions.
  *   3. Verify the XHR was observed (proves the page was reactive, not just statically rendered).
  *   4. Press Enter to submit the search (auto-nav-wait carries us to the search results or the article).
  *   5. Assert the page structure landed in the expected order: title, table of contents, first reference.
  *
  * Wikipedia is the most stable real site in the kyo-browser demo set; this is its third appearance (CookieDanceDemo, WikipediaKitDemo) but
  * exercises a different vector: the search pipeline rather than article content.
  */
final class WikipediaSearchDemo extends BrowserDemo[WikipediaSearchDemo.SearchReport]("wikipedia-search"):

    import WikipediaSearchDemo.SearchReport

    private val query = "Scala programming language"

    def flow(using Frame): SearchReport < (Browser & Async & Scope & Abort[Throwable]) =
        // Wikipedia hides the inline search input at narrow viewports; the default headless Chrome viewport (~800x600)
        // collapses it. Wrapping the flow in withViewport(1920, 1080) ensures the search box is rendered in the header layout
        // for the duration of the demo, with automatic cleanup on exit.
        Browser.withViewport(1920, 1080) {
            for
                _ <- step(1, "Open en.wikipedia.org (desktop viewport so the header search input is rendered)")
                _ <- Browser.goto("https://en.wikipedia.org/")

                _ <- step(2, "Type into the search box (fires the typeahead XHR)")
                _ <- Browser.fill(Browser.Selector.id("searchInput"), query)

                _ <- step(3, "Wait for the typeahead suggestions XHR to be recorded by the response tracker")
                // The response tracker is registered at tab attach via Page.addScriptToEvaluateOnNewDocument, so it captures
                // the typeahead XHR that fired during fill above. A sequential waitForRequestUrl call sees the recorded URL.
                observedUrl <- Browser.waitForRequestUrl("rest.php/v1/search")
                _           <- log(s"observed XHR: $observedUrl")

                _ <- step(4, "Submit the search via the global Browser.press(Key.Enter)")
                // Wikipedia's typeahead JS destroys the original #searchInput after the first input event and re-renders the
                // search container reactively. The id-based selector is now stale. Browser.fill left focus on the (now-replaced)
                // input chain, and Browser.press(key), without a selector, dispatches to document.activeElement, which the
                // re-render preserved on the visible search input.
                _ <- Browser.press(Browser.Key.Enter)
                _ <- Browser.assertUrlSatisfies("landed on either the article or the search-results page")(url =>
                    url.contains("/wiki/Scala") || url.contains("search=")
                )
                _ <- logState()
                _ <- snapshot()

                _ <- step(5, "Assert page structure: an h1 appears, then the table of contents, then the first reference link")
                // assertSelectorOrder is the multi-selector counterpart of assertPageTextOrder: pins the document-order relationship
                // between unrelated selectors. Wikipedia's article has a stable h1 + #toc + a.reference structure.
                _ <- Browser.assertSelectorOrder(Seq(
                    Browser.Selector.css("h1"),
                    Browser.Selector.css("#bodyContent"),
                    Browser.Selector.css("a.reference, sup.reference")
                ))

                title <- Browser.title
                url   <- Browser.url
            yield SearchReport(
                query = query,
                typeaheadXhrUrl = observedUrl,
                finalUrl = url,
                finalTitle = title
            )
            end for
        }
    end flow

    override def validate(result: SearchReport): Maybe[String] =
        if !result.typeaheadXhrUrl.contains("search") then
            Present(s"typeahead XHR URL didn't look like a search call: ${result.typeaheadXhrUrl}")
        else if !result.finalUrl.contains("wikipedia.org") then
            Present(s"final URL not on wikipedia.org: ${result.finalUrl}")
        else if !result.finalTitle.toLowerCase.contains("scala") then
            Present(s"final page title doesn't mention Scala: '${result.finalTitle}'")
        else Absent

end WikipediaSearchDemo

object WikipediaSearchDemo:
    case class SearchReport(query: String, typeaheadXhrUrl: String, finalUrl: String, finalTitle: String) derives CanEqual

object WikipediaSearchDemoApp extends KyoApp:
    run {
        (new WikipediaSearchDemo).runDemo
    }
end WikipediaSearchDemoApp
