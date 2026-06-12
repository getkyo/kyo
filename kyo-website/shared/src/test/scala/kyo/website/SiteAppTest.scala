package kyo.website

import kyo.*

/** Tests for the unified `SiteApp` shell: the persistent header structure, the relocated version
  * dropdown, and the always-present inert search-results region (the hydration-parity contract).
  */
class SiteAppTest extends WebsiteTest:

    private val v1        = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
    private val v0        = WebsiteVersion("v0.9.3", "0.9.3", false)
    private val versions2 = Chunk(v1, v0)

    private val versions5 = Chunk(
        v1,
        v0,
        WebsiteVersion("v0.9.2", "0.9.2", false),
        WebsiteVersion("v0.9.1", "0.9.1", false),
        WebsiteVersion("v0.9.0", "0.9.0", false)
    )

    private val home = "/latest/kyo-core/"

    /** Render `SiteApp.view` with an empty query/index and a trivial content body to its first HTML
      * emission. The body marker lets a test confirm the content slot renders the caller's body.
      */
    private def render(versions: Chunk[WebsiteVersion], docsHome: String)(using Frame): String < Async =
        for
            queryRef <- Signal.initRef("")
            body = UI.div.id("content-marker")
            view <- SiteApp.view(
                versions,
                docsHome,
                Signal.initConst(DocsSearch.Index(Chunk.empty)),
                queryRef,
                (_: String) => Kyo.unit,
                Kyo.unit,
                Kyo.unit,
                Signal.initConst(body)
            )
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")

    /** Render `SiteApp.view` with a NON-empty query against a populated heading index, so the
      * `search-results` dropdown renders real `search-result` rows (plus a `search-result-sub` label
      * on a heading hit). Confirms the populated dropdown structure, hrefs, and sub-labels.
      */
    private def renderWithQuery(query: String)(using Frame): String < Async =
        val index = DocsSearch.Index(Chunk(
            DocsSearch.Entry(
                "kyo-core",
                "kyo-core",
                "Effects",
                "latest",
                Chunk(DocsSearch.Section("Channels and queues", "channels-and-queues", 2, "channels and queues", Chunk.empty))
            ),
            DocsSearch.Entry("kyo-stream", "kyo-stream", "Effects", "latest", Chunk.empty)
        ))
        for
            queryRef <- Signal.initRef(query)
            body = UI.div.id("content-marker")
            view <- SiteApp.view(
                versions2,
                home,
                Signal.initConst(index),
                queryRef,
                (_: String) => Kyo.unit,
                Kyo.unit,
                Kyo.unit,
                Signal.initConst(body)
            )
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")
        end for
    end renderWithQuery

    "header carries the unified site-header chrome with the brand linking to /" in {
        render(versions2, home).map { html =>
            assert(html.contains("class=\"site-header\""), s"site-header bar missing: $html")
            assert(html.contains("site-header-inner"), s"site-header-inner row missing: $html")
            // Brand links to the landing root.
            assert(html.contains("data-role=\"logo\""), s"brand logo hook missing: $html")
            assert(html.contains("href=\"/\""), s"brand must link to / : $html")
            assert(html.contains("/kyo.svg"), s"brand logo image (vector mark) missing: $html")
        }
    }

    "Docs and Get started both target the overview intro route (the main README), not a module page" in {
        render(versions2, home).map { html =>
            // Both the Docs link and the Get started button land on the overview /latest/ (the root-README
            // intro): the reader starts at the main README rather than being dropped into the first
            // module's page. The overview auto-opens and its sidebar IS the module list, so a separate
            // Modules link is redundant and was removed.
            val overviewHome     = "/latest/"
            val overviewRefCount = countOccurrences(html, s"""href="$overviewHome"""")
            assert(overviewRefCount >= 2, s"expected Docs + Get started to both target $overviewHome, found $overviewRefCount: $html")
            // docsHome (the first module) and the overview must differ for the check to be meaningful.
            assert(home != overviewHome, "docsHome and the overview route must differ for the test to be meaningful")
            assert(html.contains("Docs"), s"Docs link text missing: $html")
            assert(!html.contains("Modules"), s"Modules link should be removed from the header: $html")
            assert(html.contains("Get started"), s"Get started button missing: $html")
        }
    }

    "API and GitHub are external links opening in a new tab (Target.Blank)" in {
        render(versions2, home).map { html =>
            assert(html.contains("javadoc.io/doc/io.getkyo/kyo-core_3"), s"API javadoc link missing: $html")
            assert(html.contains("github.com/getkyo/kyo"), s"GitHub link missing: $html")
            // Both external links open in a new tab.
            assert(html.contains("target=\"_blank\""), s"external links must open in a new tab: $html")
        }
    }

    "header carries a theme toggle with both sun and moon icons" in {
        render(versions2, home).map { html =>
            assert(html.contains("class=\"theme-toggle\""), s"theme-toggle button missing: $html")
            assert(html.contains("class=\"sun\""), s"sun icon slot missing: $html")
            assert(html.contains("class=\"moon\""), s"moon icon slot missing: $html")
            // Icons are rendered with the kyo-ui Svg DSL (inline <svg>), not a raster or raw string.
            assert(html.contains("<svg"), s"toggle should render inline SVG icons: $html")
            assert(html.contains("aria-label=\"Toggle dark mode\""), s"toggle should carry an aria-label: $html")
        }
    }

    "version dropdown lists one option per version (INV-010), relocated from the apps" in {
        render(versions2, home).map { html =>
            assert(html.contains("1.0.0-RC2"), "first version label must appear")
            assert(html.contains("0.9.3"), "second version label must appear")
            // kyo-ui dropdown renders option slots as data-kyo-dropdown-opt="N" divs.
            val optionCount = countOccurrences(html, "data-kyo-dropdown-opt=")
            assert(optionCount == 2, s"expected 2 dropdown options, got $optionCount")
        }
    }

    "version dropdown option count equals the number of versions (5)" in {
        render(versions5, home).map { html =>
            val optionCount = countOccurrences(html, "data-kyo-dropdown-opt=")
            assert(optionCount == 5, s"expected 5 dropdown options, got $optionCount")
        }
    }

    "empty versions yields a header with an empty dropdown" in {
        render(Chunk.empty, home).map { html =>
            assert(html.contains("class=\"site-header\""), "header must render with empty versions")
            val optionCount = countOccurrences(html, "data-kyo-dropdown-opt=")
            assert(optionCount == 0, s"expected 0 dropdown options, got $optionCount")
        }
    }

    "the inert empty search-results region is always present (hydration parity)" in {
        render(versions2, home).map { html =>
            // The search input is present (inert until the search-wiring phase) and the
            // search-results container is rendered empty, so the SSG shell and the bundle's first
            // render are structurally identical (INV-003 hydration parity).
            assert(html.contains("class=\"search-input\""), s"search input must be present: $html")
            assert(html.contains("search-results"), s"empty search-results region must always be present: $html")
            // Empty query -> no result rows.
            assert(!html.contains("search-result\""), s"no search-result rows at the empty query: $html")
        }
    }

    "the content slot renders the caller's body in a reactive boundary" in {
        render(versions2, home).map { html =>
            assert(html.contains("data-kyo-reactive"), s"content slot must be a reactive boundary: $html")
            assert(html.contains("content-marker"), s"content slot must render the caller's body: $html")
        }
    }

    "a title query renders one search-result row per hit with the module route href" in {
        renderWithQuery("kyo").map { html =>
            // Both module titles contain "kyo": two title hits, each a search-result row.
            val rowCount = countOccurrences(html, "class=\"search-result\"")
            assert(rowCount == 2, s"expected 2 search-result rows for 'kyo', got $rowCount: $html")
            assert(html.contains("href=\"/latest/kyo-core/\""), s"kyo-core row must link to its module route: $html")
            assert(html.contains("href=\"/latest/kyo-stream/\""), s"kyo-stream row must link to its module route: $html")
            // Title-only hits carry no heading sub-label.
            assert(!html.contains("search-result-sub"), s"title hits must not render a heading sub-label: $html")
        }
    }

    "a heading query renders a heading hit with a #anchor href and a sub-label" in {
        // "channels" matches the kyo-core heading text "Channels and queues" but no module title.
        renderWithQuery("channels").map { html =>
            val rowCount = countOccurrences(html, "class=\"search-result\"")
            assert(rowCount == 1, s"expected 1 heading hit for 'channels', got $rowCount: $html")
            assert(
                html.contains("href=\"/latest/kyo-core/#channels-and-queues\""),
                s"heading hit href must include the #<heading-slug> anchor: $html"
            )
            assert(html.contains("search-result-sub"), s"heading hit must render a search-result-sub label: $html")
            assert(html.contains("Channels and queues"), s"heading hit sub-label must carry the heading text: $html")
        }
    }

    // Helper: count non-overlapping occurrences of a substring.
    private def countOccurrences(haystack: String, needle: String): Int =
        var count = 0
        var idx   = haystack.indexOf(needle)
        while idx >= 0 do
            count += 1
            idx = haystack.indexOf(needle, idx + 1)
        count
    end countOccurrences

end SiteAppTest
