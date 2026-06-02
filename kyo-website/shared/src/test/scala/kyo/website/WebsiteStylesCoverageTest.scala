package kyo.website

import kyo.*

/** Guard test: every CSS class the website UI emits must have a matching rule in
  * `WebsiteStyles.sheet`.
  *
  * It renders `LandingApp.view` and a fully-featured `DocsApp.view` (an article carrying callouts,
  * a blockquote, bold runs, a table, and a highlighted code block so the `callout`/`blockquote`/
  * `md-strong`/`tok-*` hooks all appear), harvests every `class="..."` token from the produced
  * HTML, and asserts each token is styled by some rule in the sheet. A future `cssClass` with no
  * rule fails the build with the offending class named.
  */
class WebsiteStylesCoverageTest extends Test:

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

    private def landingHtml(using Frame): String < Async =
        for
            view <- LandingApp.view(Chunk(WebsiteVersion("v1.0.0", "1.0.0", true)))
            html <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")

    // A docs article that exercises every prose hook: a note callout, a caution callout, a plain
    // blockquote, a bold run, a table, and a fenced Scala block (for the tok-* spans).
    private val article =
        """## Heading
          |
          |A paragraph with **bold** text and `inline code`.
          |
          |> **Note:** this is a note callout.
          |
          |> **Caution:** this is a caution callout.
          |
          |> A plain blockquote line.
          |
          || Col A | Col B |
          || ----- | ----- |
          || one   | two   |
          |
          |```scala
          |val x: Int = 42 // a comment
          |def greet(name: String): String = "hello " + name
          |```
          |""".stripMargin

    private def docsHtml(using Frame): String < Async =
        val mod = WebsiteModule("kyo-core", "Foundation", "kyo-core", "", WebsiteModule.Platforms(true, true, true))
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
        for
            route    <- Signal.initRef[String]("/v0.9.0/kyo-core/")
            rendered <- DocsMarkdown.transpile(article)
            view     <- DocsApp.view(content, Chunk(WebsiteVersion("v0.9.0", "0.9.0", false)), "v0.9.0", route, toc, rendered.article)
            html     <- UI.runRender(view).take(1).run
        yield html.headMaybe.getOrElse("")
        end for
    end docsHtml

    "every class LandingApp emits has a matching rule in WebsiteStyles.sheet" in run {
        landingHtml.map { html =>
            val styled  = styledClasses(WebsiteStyles.sheet)
            val emitted = emittedClasses(html)
            val missing = emitted -- styled -- kyoUiInternalClasses
            assert(emitted.nonEmpty, "landing render produced no class attributes")
            assert(missing.isEmpty, s"landing classes with no stylesheet rule: ${missing.toList.sorted.mkString(", ")}")
        }
    }

    "every class DocsApp emits has a matching rule in WebsiteStyles.sheet" in run {
        docsHtml.map { html =>
            val styled  = styledClasses(WebsiteStyles.sheet)
            val emitted = emittedClasses(html)
            val missing = emitted -- styled -- kyoUiInternalClasses
            assert(emitted.nonEmpty, "docs render produced no class attributes")
            assert(missing.isEmpty, s"docs classes with no stylesheet rule: ${missing.toList.sorted.mkString(", ")}")
        }
    }

    "the prose article exercised the callout/blockquote/md-strong/tok hooks" in run {
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

end WebsiteStylesCoverageTest
