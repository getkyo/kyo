package kyo.website

import kyo.*

/** The bundle's hydration and client-side navigation, against the real generated site in a real Chrome.
  *
  * The SSG side of each comparison is the served page's own HTML, read from the bytes the server holds and parsed by the same Chrome with `DOMParser`, so both sides go through one
  * HTML serializer and differ only where the DOM differs.
  */
class SiteAppSpaTest extends SiteChromeTest:

    override def timeout = 5.minutes

    private val fromRoute = "/latest/kyo-core/"
    private val toRoute   = "/latest/kyo-data/"

    // The page wrapper appends the boot islands and the bundle's module script, whose tag sits after `</body>` between line breaks
    // the parser also moves into the body. The mount renders the view alone.
    private val pageWrapperScripts = """script[type="application/json"], script[type="module"]"""

    private def ssgPage(site: ServedSite.Site, route: String)(using Frame, kyo.test.AssertScope): String =
        site.text(route).getOrElse(fail(s"the site serves nothing at $route"))

    private def ssgDocument(html: String)(using Frame): String =
        s"new DOMParser().parseFromString(${Json.encode(html)}, 'text/html')"

    private def ssgChrome(html: String)(using Frame): String =
        s"""(() => {
            const doc = ${ssgDocument(html)};
            doc.body.querySelectorAll('$pageWrapperScripts').forEach(s => s.remove());
            let last = doc.body.lastChild;
            while (last && last.nodeType === Node.TEXT_NODE && last.textContent.trim() === '') {
                last.remove();
                last = doc.body.lastChild;
            }
            return doc.body.innerHTML;
        })()"""

    private def divergence(expected: String, actual: String): String =
        val at = expected.iterator.zip(actual.iterator).indexWhere(_ != _) match
            case -1 => math.min(expected.length, actual.length)
            case i  => i
        val from = math.max(0, at - 200)
        s"first difference at offset $at of ${expected.length} expected and ${actual.length} actual characters\n" +
            s"expected: ${expected.slice(from, at + 300)}\nactual:   ${actual.slice(from, at + 300)}"
    end divergence

    "post-mount chrome DOM equals the SSG chrome" in {
        inChrome(fromRoute) { site =>
            // A module page, the version overview, and the landing: the three bodies the bundle mounts.
            Kyo.foreach(Chunk(fromRoute, "/latest/", "/")) { route =>
                val ssgHtml = ssgPage(site, route)
                for
                    _    <- Browser.goto(s"${site.url}$route")
                    _    <- Browser.waitFor(mounted)
                    ssg  <- Browser.eval(ssgChrome(ssgHtml))
                    live <- Browser.eval("document.body.innerHTML")
                yield
                    assert(ssg.contains("site-header"), s"the SSG page of $route carries no site header: $ssg")
                    assert(live == ssg, s"the mounted body of $route diverges from the SSG body: ${divergence(ssg, live)}")
                end for
            }.map(_ => succeed)
        }
    }

    "click-to-navigate swaps content without reload" in {
        inChrome(fromRoute) { site =>
            val target = ssgPage(site, toRoute)
            for
                // The rail is a closed drawer below the desktop breakpoint.
                _ <- Browser.setViewport(1440, 900)
                _ <- Browser.waitFor(mounted)
                _ <- Browser.evalDiscard(
                    """(() => {
                        window.__spaProbe = true;
                        window.__spaHeader = document.querySelector('header.site-header');
                        window.__spaSheets = Array.from(document.head.querySelectorAll('style'));
                    })()"""
                )
                sheetText       <- Browser.eval("window.__spaSheets.map(s => s.textContent).join('')")
                _               <- Browser.click(Browser.Selector.css(s""".docs-sidebar a[href="$toRoute"]"""))
                _               <- Browser.waitForUrl(_.endsWith(toRoute))
                expectedArticle <- Browser.eval(
                    s"${ssgDocument(target)}.querySelector('main.docs-content').innerHTML"
                )
                expectedTitle <- Browser.eval(s"${ssgDocument(target)}.title")
                _             <- Abort.run[BrowserReadException](
                    Browser.waitFor(s"document.querySelector('main.docs-content').innerHTML === ${Json.encode(expectedArticle)}")
                )
                article        <- Browser.eval("document.querySelector('main.docs-content').innerHTML")
                path           <- Browser.eval("location.pathname + location.search")
                activeRail     <- Browser.attribute(Browser.Selector.css(".docs-sidebar .nav-item-active > a"), "href")
                title          <- Browser.title
                sameDocument   <- Browser.evalBoolean("window.__spaProbe === true")
                sameHeader     <- Browser.evalBoolean("window.__spaHeader === document.querySelector('header.site-header')")
                sheetsInPlace  <- Browser.evalBoolean("window.__spaSheets.every(s => s.isConnected)")
                sheetTextAfter <- Browser.eval("window.__spaSheets.map(s => s.textContent).join('')")
            yield
                assert(
                    article == expectedArticle,
                    s"the content area is not $toRoute's SSG content: ${divergence(expectedArticle, article)}"
                )
                assert(path == toRoute)
                assert(activeRail == toRoute)
                assert(title == expectedTitle)
                assert(sameDocument, "the click reloaded the page")
                assert(sameHeader, "the navigation remounted the site header")
                assert(sheetsInPlace, "a <head> stylesheet was removed by the content swap")
                assert(sheetText.contains(".docs-shell"), s"the <head> stylesheet carries no site rules: $sheetText")
                assert(sheetTextAfter == sheetText, "the <head> stylesheet changed across the content swap")
            end for
        }
    }

end SiteAppSpaTest
