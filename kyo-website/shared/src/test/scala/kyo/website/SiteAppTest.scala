package kyo.website

import kyo.*

/** Tests for the unified `SiteApp` shell: the persistent header structure, the relocated version
  * dropdown, and the always-present inert search-results region (the hydration-parity contract).
  */
class SiteAppTest extends WebsiteTest:

    private val v1 = WebsiteVersion("v1.0.0-RC2", "1.0.0-RC2", true)
    private val v0 = WebsiteVersion("v0.9.3", "0.9.3", false)
    // SiteApp.versionSelect reverses the GIVEN order to render newest-first, so the caller supplies
    // versions oldest-first (the generator's git-timestamp order): the oldest tag leads, the latest
    // (newest by date) is last.
    private val versions2 = Chunk(v0, v1)

    private val versions5 = Chunk(
        WebsiteVersion("v0.9.0", "0.9.0", false),
        WebsiteVersion("v0.9.1", "0.9.1", false),
        WebsiteVersion("v0.9.2", "0.9.2", false),
        v0,
        v1
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
                (_: String) => Kyo.unit,
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
                (_: String) => Kyo.unit,
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

    "API, GitHub, and Community are external links opening in a new tab (Target.Blank)" in {
        render(versions2, home).map { html =>
            assert(html.contains("javadoc.io/doc/io.getkyo/kyo-core_3"), s"API javadoc link missing: $html")
            assert(html.contains("github.com/getkyo/kyo"), s"GitHub link missing: $html")
            // The Community link points to the Discord invite.
            assert(html.contains("//discord.gg/KxxkBbW8bq"), s"Community Discord link missing: $html")
            assert(html.contains("Community"), s"Community link text missing: $html")
            // All three external links open in a new tab.
            assert(html.contains("target=\"_blank\""), s"external links must open in a new tab: $html")
        }
    }

    "the nav GitHub link carries the inline octocat brand glyph (currentColor)" in {
        render(versions2, home).map { html =>
            // The nav GitHub link reuses the landing brand-glyph helper: a `.soc` row with a `.brand-ic`
            // span wrapping the filled octocat path, painted with currentColor so it tracks the nav color.
            assert(html.contains("class=\"soc\""), s"nav GitHub link must use the .soc glyph row: $html")
            assert(html.contains("class=\"brand-ic\""), s"nav GitHub link must carry a .brand-ic glyph: $html")
            assert(html.contains("M12 .297"), s"the GitHub octocat path must render in the nav: $html")
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

    "version selector is a native <select> with one <option> per version" in {
        render(versions2, home).map { html =>
            assert(html.contains("1.0.0-RC2"), "first version label must appear")
            assert(html.contains("0.9.3"), "second version label must appear")
            // The selector is a native <select> (expands natively, no JS toggle), tagged .ver / #site-version.
            assert(html.contains("<select"), s"version selector must be a native <select>: $html")
            assert(html.contains("class=\"ver\""), s"version selector must carry the .ver class: $html")
            assert(html.contains("id=\"site-version\""), s"version selector must carry id site-version: $html")
            // One <option> per version.
            val optionCount = countOccurrences(html, "<option")
            assert(optionCount == 2, s"expected 2 <option> elements, got $optionCount: $html")
        }
    }

    "version selector lists versions newest-first with the current version selected" in {
        render(versions5, home).map { html =>
            val optionCount = countOccurrences(html, "<option")
            assert(optionCount == 5, s"expected 5 <option> elements, got $optionCount: $html")
            // Newest-first ordering: the latest label appears before the oldest in the document.
            val newestIdx = html.indexOf("1.0.0-RC2")
            val oldestIdx = html.indexOf(">0.9.0<")
            assert(newestIdx >= 0, s"newest label must render: $html")
            assert(oldestIdx >= 0, s"oldest label must render: $html")
            assert(newestIdx < oldestIdx, s"newest version must list before oldest (newest-first): $html")
            // The latest version's option carries value="latest" and is the selected one (currentPrefix
            // is "latest"); the oldest option is NOT selected, so the control shows the current version,
            // not the oldest v0.9.0.
            assert(html.contains("value=\"latest\""), s"latest option value must be 'latest': $html")
            assert(html.contains("value=\"v0.9.0\""), s"oldest option value must be its tag: $html")
            // The renderer emits value then the selected boolean attribute on the chosen option.
            assert(html.contains("value=\"latest\" selected"), s"latest option must be selected: $html")
            assert(!html.contains("value=\"v0.9.0\" selected"), s"oldest option must NOT be selected: $html")
        }
    }

    "a single version hides the selector (a one-option dropdown reads as broken)" in {
        // The initial post-launch state has only the current release. A dropdown with one entry looks
        // broken, so the selector renders nothing until a second version exists. The header still renders.
        render(Chunk(v0), home).map { html =>
            assert(html.contains("class=\"site-header\""), "header must render with a single version")
            assert(!html.contains("id=\"site-version\""), s"the version selector must be hidden for a single version: $html")
            assert(!html.contains("<select"), s"no <select> renders for a single version: $html")
        }
    }

    "empty versions hides the selector but still renders the header" in {
        render(Chunk.empty, home).map { html =>
            assert(html.contains("class=\"site-header\""), "header must render with empty versions")
            assert(!html.contains("id=\"site-version\""), s"the version selector must be hidden with no versions: $html")
            val optionCount = countOccurrences(html, "<option")
            assert(optionCount == 0, s"expected 0 <option> elements, got $optionCount: $html")
        }
    }

    "the inert empty search-results region is always present (hydration parity)" in {
        render(versions2, home).map { html =>
            // The search input is present (inert until the search-wiring phase) and the
            // search-results container is rendered empty, so the SSG shell and the bundle's first
            // render are structurally identical (hydration parity).
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
