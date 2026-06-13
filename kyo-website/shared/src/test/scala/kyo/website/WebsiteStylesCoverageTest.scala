package kyo.website

import kyo.*

/** Guard test: every CSS class the website UI emits must have a matching rule in
  * `WebsiteStyles.sheet`.
  *
  * It renders the unified `SiteApp.view` shell around the landing body and around a fully-featured
  * docs body (an article carrying callouts, a blockquote, bold runs, a table, and a highlighted
  * code block so the `callout`/`blockquote`/`md-strong`/`tok-*` hooks all appear), so the merged
  * header chrome (`site-header`/`site-header-inner`/`search-input`/`search-results` plus the reused
  * `brand`/`links`/`right`/`btn`/`ver`) is harvested alongside the body classes. It then harvests
  * every `class="..."` token from the produced HTML and asserts each token is styled by some rule
  * in the sheet. A future `cssClass` with no rule fails the build with the offending class named.
  */
class WebsiteStylesCoverageTest extends WebsiteTest:

    // Classes emitted by kyo-ui's own built-in components (e.g. the div-based dropdown), not by the
    // website apps, so they are out of scope for the website stylesheet. None are known today; the
    // dropdown uses data-* attributes, not classes. Kept as an explicit, empty allow-list so a real
    // future kyo-ui-internal class can be documented here rather than silently passed.
    private val kyoUiInternalClasses: Set[String] = Set.empty

    // Every class name the sheet provides a rule for: scan each rule's selector (including nested
    // media blocks) for `.identifier` fragments. A rule like `.feat-grid .fcat` styles both
    // `feat-grid` and `fcat`; `.nav-item:hover` styles `nav-item`.
    private def styledClasses(sheet: Stylesheet): Set[String] =
        def fromSelector(css: String): Set[String] =
            val out = scala.collection.mutable.Set.empty[String]
            var i   = 0
            while i < css.length do
                if css.charAt(i) == '.' then
                    val start = i + 1
                    var j     = start
                    while j < css.length && (css.charAt(j).isLetterOrDigit || css.charAt(j) == '-' || css.charAt(j) == '_') do
                        j += 1
                    if j > start then out += css.substring(start, j)
                    i = j
                else i += 1
            end while
            out.toSet
        end fromSelector
        def walk(s: Stylesheet): Set[String] =
            s.entries.foldLeft(Set.empty[String]) { (acc, e) =>
                e match
                    case Stylesheet.Entry.Rule(sel, _) => acc ++ fromSelector(sel.css)
                    case Stylesheet.Entry.Media(_, n)  => acc ++ walk(n)
                    case _                             => acc
            }
        walk(sheet)
    end styledClasses

    // Harvest every class token from rendered HTML: each `class="a b c"` attribute, split on
    // whitespace into individual class names.
    private def emittedClasses(html: String): Set[String] =
        val out    = scala.collection.mutable.Set.empty[String]
        val marker = "class=\""
        var idx    = html.indexOf(marker)
        while idx >= 0 do
            val start = idx + marker.length
            val end   = html.indexOf('"', start)
            if end > start then
                html.substring(start, end).split("\\s+").foreach(c => if c.nonEmpty then out += c)
            idx = html.indexOf(marker, if end >= 0 then end + 1 else start)
        end while
        out.toSet
    end emittedClasses

    private val versions = Chunk(WebsiteVersion("v1.0.0", "1.0.0", true))

    // A populated heading index whose entries cover a title hit AND a heading hit, so the populated
    // .search-results dropdown renders search-result / search-result-active / search-result-sub /
    // search-result-title rows for the coverage harvest.
    private val searchIndex = DocsSearch.Index(Chunk(
        DocsSearch.Entry(
            "kyo-core",
            "kyo-core",
            "Effects",
            "latest",
            Chunk(DocsSearch.Section("Channels and queues", "channels-and-queues", 2, "channels and queues", Chunk.empty))
        )
    ))

    // Wrap a content body in the unified SiteApp shell so the merged header chrome is harvested too.
    // A NON-empty query against the populated index forces the search-results dropdown to render its
    // rows, so the search-result* classes are covered (otherwise the empty dropdown emits none).
    private def shell(home: String, body: UI)(using Frame): UI < Sync =
        for
            // "channels" matches the kyo-core heading "Channels and queues" (a heading hit), so the
            // dropdown renders search-result + search-result-title + search-result-sub rows.
            queryRef <- Signal.initRef("channels")
            view <- SiteApp.view(
                versions,
                home,
                Signal.initConst(searchIndex),
                queryRef,
                (_: String) => Kyo.unit,
                Kyo.unit,
                Kyo.unit,
                Signal.initConst(body)
            )
        yield view

    private def landingHtml(using Frame): String < Async =
        for
            body <- LandingApp.body("/latest/kyo-core/")
            view <- shell("/latest/kyo-core/", body)
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")

    private def docsHtml(using Frame): String < Async =
        val mod = WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true, true))
        val content = WebsiteContent(
            intro = "",
            groups = Chunk(WebsiteContent.Group("Foundation", Chunk(mod))),
            // a non-latest version so the version-banner hook is exercised too
            version = WebsiteVersion("v0.9.0", "0.9.0", false)
        )
        val toc = Chunk(
            DocsMarkdown.Heading(1, "Top", "top"),
            DocsMarkdown.Heading(2, "Mid", "mid"),
            DocsMarkdown.Heading(3, "Deep", "deep"),
            DocsMarkdown.Heading(4, "Deeper", "deeper")
        )
        // DocsMarkdown.transpile is JVM-only after the split; use a constructed UI article
        // so this shared test stays cross-platform.
        // The constructed article includes all prose-hook classes so the coverage assertion
        // and the non-vacuousness assertion remain meaningful.
        val article2 = UI.fragment(
            UI.h1.id("top")(UI.Ast.Text("Top")),
            UI.h2.id("mid")(UI.Ast.Text("Mid")),
            UI.h3.id("deep")(UI.Ast.Text("Deep")),
            UI.h4.id("deeper")(UI.Ast.Text("Deeper")),
            UI.p(UI.span.cssClass("md-strong")(UI.Ast.Text("bold"))),
            UI.div.cssClass("callout callout-note")(UI.p(UI.Ast.Text("a note"))),
            UI.div.cssClass("callout callout-caution")(UI.p(UI.Ast.Text("a caution"))),
            UI.div.cssClass("blockquote")(UI.p(UI.Ast.Text("a blockquote"))),
            UI.pre(UI.code(UI.span.cssClass("tok-keyword")(UI.Ast.Text("val"))))
        )
        for
            route    <- Signal.initRef[String]("/v0.9.0/kyo-core/")
            routeStr <- route.current
            body <- DocsApp.body(
                content,
                "v0.9.0",
                route,
                Map(routeStr -> toc),
                article2,
                Signal.initConst(false)
            )
            view <- shell("/v0.9.0/kyo-core/", body)
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")
        end for
    end docsHtml

    "every class LandingApp emits has a matching rule in WebsiteStyles.sheet" in {
        landingHtml.map { html =>
            val styled  = styledClasses(WebsiteStyles.sheet)
            val emitted = emittedClasses(html)
            val missing = emitted -- styled -- kyoUiInternalClasses
            assert(emitted.nonEmpty, "landing render produced no class attributes")
            assert(missing.isEmpty, s"landing classes with no stylesheet rule: ${missing.toList.sorted.mkString(", ")}")
        }
    }

    "every class DocsApp emits has a matching rule in WebsiteStyles.sheet" in {
        docsHtml.map { html =>
            val styled  = styledClasses(WebsiteStyles.sheet)
            val emitted = emittedClasses(html)
            val missing = emitted -- styled -- kyoUiInternalClasses
            assert(emitted.nonEmpty, "docs render produced no class attributes")
            assert(missing.isEmpty, s"docs classes with no stylesheet rule: ${missing.toList.sorted.mkString(", ")}")
        }
    }

    "the prose article exercised the callout/blockquote/md-strong/tok hooks" in {
        docsHtml.map { html =>
            // Confirm the fixture actually emits these classes, so the coverage assertion above is
            // meaningfully checking them rather than passing vacuously.
            val emitted = emittedClasses(html)
            assert(emitted.contains("callout-note"), s"fixture must emit callout-note: ${emitted.toList.sorted}")
            assert(emitted.contains("callout-caution"), "fixture must emit callout-caution")
            assert(emitted.contains("blockquote"), "fixture must emit blockquote")
            assert(emitted.contains("md-strong"), "fixture must emit md-strong")
            assert(emitted.exists(_.startsWith("tok-")), "fixture must emit at least one tok-* span")
        }
    }

    "the populated search-results dropdown emits the search-result* classes (coverage is non-vacuous)" in {
        landingHtml.map { html =>
            // The shell renders a non-empty query against the populated index, so the dropdown rows
            // are present: this confirms the search-result* classes the coverage assertion checks are
            // actually emitted (otherwise the empty dropdown would emit none and pass vacuously).
            val emitted = emittedClasses(html)
            assert(emitted.contains("search-results"), s"populated dropdown must emit search-results: ${emitted.toList.sorted}")
            assert(emitted.contains("search-result"), "populated dropdown must emit search-result rows")
            assert(emitted.contains("search-result-title"), "populated dropdown must emit search-result-title")
            assert(emitted.contains("search-result-sub"), "a heading hit must emit search-result-sub")
        }
    }

    // the .ver rule is present and is a positioning container (not the pill chrome)
    "the .ver rule is a relatively-positioned layout container" in {
        val css = WebsiteStyles.sheet.render
        // find the .ver { ... } block (terminated by the next newline after the opening brace)
        val verIdx = css.indexOf(".ver {")
        assert(verIdx >= 0, ".ver rule must be present in the rendered CSS")
        val blockEnd = css.indexOf('}', verIdx)
        val block    = if blockEnd >= 0 then css.substring(verIdx, blockEnd + 1) else css.substring(verIdx)
        assert(block.contains("position: relative"), s".ver rule must carry position: relative; got: $block")
    }

    // the option overlay rule is keyed on data-kyo-dropdown-options with position: absolute
    "the [data-kyo-dropdown-options] rule carries position: absolute (Position.dropdown card chrome)" in {
        val css    = WebsiteStyles.sheet.render
        val selStr = "[data-kyo-dropdown-options]"
        val selIdx = css.indexOf(selStr)
        assert(selIdx >= 0, s"$selStr rule must be present in the rendered CSS")
        val blockEnd = css.indexOf('}', selIdx)
        val block    = if blockEnd >= 0 then css.substring(selIdx, blockEnd + 1) else css.substring(selIdx)
        assert(block.contains("position: absolute"), s"$selStr rule must carry position: absolute; got: $block")
        assert(block.contains("var(--surface)"), s"$selStr rule must carry surface background; got: $block")
        assert(block.contains("var(--line)"), s"$selStr rule must carry line border; got: $block")
    }

    // the highlighted-option rule is keyed on data-kyo-dropdown-hl="true" with accent-ghost
    "the [data-kyo-dropdown-hl=true] rule carries the accent-ghost background" in {
        val css    = WebsiteStyles.sheet.render
        val selStr = """[data-kyo-dropdown-hl="true"]"""
        val selIdx = css.indexOf(selStr)
        assert(selIdx >= 0, s"""$selStr rule must be present in the rendered CSS""")
        val blockEnd = css.indexOf('}', selIdx)
        val block    = if blockEnd >= 0 then css.substring(selIdx, blockEnd + 1) else css.substring(selIdx)
        assert(block.contains("accent-ghost"), s"$selStr rule must carry accent-ghost background; got: $block")
    }

    // the version dropdown carries .id("site-version"), so the renderer emits
    // data-kyo-dropdown-trigger and data-kyo-dropdown-options, proving those sheet rules match real elements.
    "the version dropdown emits data-kyo-dropdown-trigger and data-kyo-dropdown-options" in {
        landingHtml.map { html =>
            assert(
                html.contains("data-kyo-dropdown-trigger=\"site-version\""),
                "landing HTML must contain data-kyo-dropdown-trigger=\"site-version\" (Rule 2 now matches a real element)"
            )
            assert(
                html.contains("data-kyo-dropdown-options=\"site-version\""),
                "landing HTML must contain data-kyo-dropdown-options=\"site-version\" (Rule 3 now matches a real element)"
            )
            assert(
                html.contains("data-kyo-dropdown-opt"),
                "landing HTML must contain data-kyo-dropdown-opt rows"
            )
        }
    }

end WebsiteStylesCoverageTest
