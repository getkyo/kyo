package kyo.website

import kyo.*
import kyo.UI.*

class DocsMarkdownTest extends Test:

    // Helper: run SSG renderer and get the first HTML emission.
    private def renderHtml(ui: UI)(using Frame): String < Async =
        UI.runRender(ui).take(1).run.map(_.headMaybe.getOrElse(""))

    // Helper: transpile and render to HTML in one step.
    private def transpileHtml(source: String)(using Frame): String < Async =
        for
            rendered <- DocsMarkdown.transpile(source)
            html     <- renderHtml(rendered.article)
        yield html

    // Helper: just transpile and return Rendered.
    private def transpile(source: String)(using Frame): DocsMarkdown.Rendered < Sync =
        DocsMarkdown.transpile(source)

    // ---- GFM pipe table ----

    "GFM pipe table -> UI.table subtree (leaf 1)" in run {
        val source =
            "| Name | Type |\n" +
                "| ---- | ---- |\n" +
                "| Foo  | Int  |\n" +
                "| Bar  | String |\n"
        for
            html <- transpileHtml(source)
        yield
            assert(html.contains("<table"), s"Expected table: $html")
            assert(html.contains("<th"), s"Expected th: $html")
            assert(html.contains("<td"), s"Expected td: $html")
            assert(html.contains("Name"), s"Expected Name: $html")
            assert(html.contains("Foo"), s"Expected Foo: $html")
        end for
    }

    // ---- Heading slugs == headings slugs (INV-004) ----

    "heading id slugs equal headings slugs (INV-004) (leaf 2)" in run {
        val source =
            "# Alpha\n" +
                "## Beta\n" +
                "### Gamma\n" +
                "#### Delta\n" +
                "## Beta\n"
        for
            rendered <- transpile(source)
            html     <- renderHtml(rendered.article)
        yield
            val slugs = rendered.headings.map(_.slug)
            for slug <- slugs.toSeq do
                assert(html.contains(s"""id="$slug""""), s"Missing id=$slug in HTML")
            val betaSlugs = slugs.filter(s => s == "beta" || s == "beta-2")
            assert(betaSlugs.size == 2, s"Expected two beta slugs, got: $betaSlugs")
        end for
    }

    // ---- Slug rule ----

    "slug lowercases and replaces non-alnum (leaf 3)" in run {
        for rendered <- transpile("## Composing: map, flatMap\n")
        yield
            assert(rendered.headings.size == 1)
            assert(rendered.headings.head.slug == "composing-map-flatmap")
        end for
    }

    // ---- Duplicate heading deduplication ----

    "duplicate headings get -2 suffix on both sides (leaf 4)" in run {
        val source = "## Note\n## Note\n"
        for
            rendered <- transpile(source)
            html     <- renderHtml(rendered.article)
        yield
            val slugs = rendered.headings.map(_.slug).toSeq
            assert(slugs == Seq("note", "note-2"), s"Slugs: $slugs")
            assert(html.contains("""id="note""""), s"Missing id=note: $html")
            assert(html.contains("""id="note-2""""), s"Missing id=note-2: $html")
        end for
    }

    // ---- Two-space nested list ----

    "two-space nested list does not flatten (leaf 5)" in run {
        val source = "- Exception\n  - FileException\n  - TimeoutException\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("<ul"), s"Expected ul: $html")
            assert(html.contains("FileException"), s"Expected sub-item: $html")
            // Two nested <ul> tags means the list is nested.
            assert(html.indexOf("<ul") != html.lastIndexOf("<ul"), s"Expected nested ul: $html")
        end for
    }

    // ---- Callouts ----

    "blockquote Note becomes callout callout-note (leaf 6)" in run {
        for html <- transpileHtml("> **Note:** be careful\n")
        yield assert(html.contains("callout-note"), s"HTML: $html")
        end for
    }

    "blockquote Caution becomes callout callout-caution (leaf 7)" in run {
        for html <- transpileHtml("> **Caution:** danger\n")
        yield assert(html.contains("callout-caution"), s"HTML: $html")
        end for
    }

    "other blockquote labels become plain blockquote (leaf 8)" in run {
        for html <- transpileHtml("> **Sequential vs parallel:** some text\n")
        yield
            assert(html.contains("blockquote"), s"HTML: $html")
            assert(!html.contains("callout-note"), s"Should not be callout-note: $html")
            assert(!html.contains("callout-caution"), s"Should not be callout-caution: $html")
        end for
    }

    // ---- Form-B bold opener stays plain prose ----

    "Form-B bold opener in a paragraph stays plain prose (leaf 9)" in run {
        for html <- transpileHtml("**Note:** inline text here\n")
        yield
            assert(html.contains("<p"), s"Expected paragraph: $html")
            assert(html.contains("md-strong"), s"Expected bold span: $html")
            assert(!html.contains("callout"), s"Should not be callout: $html")
        end for
    }

    // ---- Blockquote-wrapped fenced code ----

    "blockquote-wrapped fenced code is a real pre/code block (leaf 10)" in run {
        val source =
            "> **Note:** see below\n" +
                ">\n" +
                "> ```scala\n" +
                "> val x = 1\n" +
                "> ```\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("<pre"), s"Expected pre block: $html")
            assert(html.contains("<code"), s"Expected code block: $html")
            assert(html.contains("callout"), s"Expected callout wrapper: $html")
        end for
    }

    // ---- Doctest comment stripping ----

    "doctest:setup comment is stripped (leaf 11)" in run {
        val source =
            "<!-- doctest:setup\n" +
                "```scala\n" +
                "val x = 1\n" +
                "```\n" +
                "-->\n" +
                "# kyo-core\n"
        for html <- transpileHtml(source)
        yield
            assert(!html.contains("doctest"), s"doctest should be stripped: $html")
            assert(!html.contains("val x = 1"), s"setup block should be stripped: $html")
            assert(html.contains("kyo-core"), s"Heading should be present: $html")
        end for
    }

    // ---- Doctest info-string suffix stripping ----

    "doctest info-string suffix is stripped (leaf 12)" in run {
        val source = "```scala doctest:scope=inherited\nval x = 1\n```\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("tok-keyword"), s"Expected tok-keyword spans: $html")
            assert(!html.contains("doctest:scope"), s"Info suffix should be stripped: $html")
        end for
    }

    // ---- Scala token highlighting ----

    "scala fence gets tok-* token spans (leaf 13)" in run {
        val source = "```scala\nval x = 1\ndef foo: Int = x\n```\n"
        for html <- transpileHtml(source)
        yield assert(html.contains("tok-keyword"), s"Expected tok-keyword: $html")
        end for
    }

    // ---- SBT and bash token highlighting ----

    "sbt and bash fences get tok-* spans (leaf 14)" in run {
        val sbtSource  = "```sbt\nlibraryDependencies += \"org\" %% \"dep\" % \"1.0\"\n```\n"
        val bashSource = "```bash\nif true; then\n  echo hello\nfi\n```\n"
        for
            sbtHtml  <- transpileHtml(sbtSource)
            bashHtml <- transpileHtml(bashSource)
        yield
            assert(sbtHtml.contains("tok-keyword"), s"SBT should have tok-keyword: $sbtHtml")
            assert(bashHtml.contains("tok-keyword"), s"Bash should have tok-keyword: $bashHtml")
        end for
    }

    // ---- Bare/other fence: no token spans ----

    "bare and other fences produce plain pre/code with no tok-* spans (leaf 15)" in run {
        val bareSource = "```\nsome plain text\n```\n"
        val textSource = "```text\nsome plain text\n```\n"
        for
            bareHtml <- transpileHtml(bareSource)
            textHtml <- transpileHtml(textSource)
        yield
            assert(!bareHtml.contains("tok-"), s"Bare fence should have no tok-* spans: $bareHtml")
            assert(!textHtml.contains("tok-"), s"Text fence should have no tok-* spans: $textHtml")
            assert(bareHtml.contains("<pre") && bareHtml.contains("<code"), s"Expected pre/code: $bareHtml")
        end for
    }

    // ---- Shield.io badge (linked image) ----

    "shield.io badge -> UI.a containing UI.img (leaf 16)" in run {
        val source = "[![Version](https://img.shields.io/badge/v-1.0)](https://getkyo.io)\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("<a"), s"Expected anchor: $html")
            assert(html.contains("<img"), s"Expected img: $html")
            assert(html.contains("shields.io"), s"Expected badge URL: $html")
        end for
    }

    // ---- Root README inline <img> passthrough ----

    "root README inline img becomes UI.rawHtml leaf (leaf 17)" in run {
        val snippet = """<img src="kyo.png" width="200" alt="Kyo">"""
        val source  = snippet + "\nSome regular text here.\n"
        for html <- transpileHtml(source)
        yield
            // The raw img tag must appear verbatim (not escaped).
            assert(html.contains("""<img src="kyo.png""""), s"Expected verbatim img: $html")
            // Regular text must be present.
            assert(html.contains("Some regular text here"), s"Expected text: $html")
            // The img tag must NOT be HTML-escaped.
            assert(!html.contains("&lt;img"), s"img must not be escaped: $html")
        end for
    }

    // ---- Empty source edge case ----

    "empty source returns UI.empty and Chunk.empty headings (leaf 18)" in run {
        for rendered <- transpile("")
        yield assert(rendered == DocsMarkdown.Rendered(UI.empty, Chunk.empty))
        end for
    }

    // ---- HTML comment before heading (RI-002 trap 5) ----

    "leading HTML comment is skipped; first heading is the H1 (leaf 19)" in run {
        val source = "<!-- This is a comment -->\n# kyo-core\nSome text.\n"
        for rendered <- transpile(source)
        yield assert(rendered.headings.headMaybe.map(_.text) == Present("kyo-core"))
        end for
    }

    // ---- Degrade-not-fail: unknown construct ----

    "unknown construct degrades to plain paragraph without abort" in run {
        // A definition list style is not in RI-002; must not abort, must produce some output.
        val source = "term\n:   definition\n"
        for
            rendered <- transpile(source)
            html     <- renderHtml(rendered.article)
        yield
            // No abort: transpile completed and returned a Rendered value.
            // No headings: unknown constructs produce no heading entries.
            assert(rendered.headings.isEmpty, s"Expected no headings, got: ${rendered.headings}")
            // The unknown lines degrade to a plain paragraph containing the literal text.
            assert(html.contains("<p"), s"Expected paragraph element: $html")
            assert(html.contains("term"), s"Expected literal 'term' in output: $html")
            assert(html.contains("definition"), s"Expected literal 'definition' in output: $html")
            // The article is non-empty: degrade means produce output, not suppress it.
            assert(rendered.article != UI.empty, s"Expected non-empty article: $rendered")
        end for
    }

    // ---- Degrade-not-fail: malformed table ----

    "malformed table degrades gracefully without abort" in run {
        // Table with no separator row - only one line starting with |.
        val source = "| A | B |\n"
        for html <- transpileHtml(source)
        yield
            // No crash; should not produce a full table element (no separator row).
            assert(!html.contains("<table"), s"Malformed table should not produce <table: $html")
        end for
    }

    // ---- Multi-line <a><img></a> video embed (R4 regression, leaf 20) ----

    "multi-line <a><img></a> video embed is coalesced into one wrapped rawHtml node (leaf 20)" in run {
        val source =
            "<a href=\"http://www.youtube.com/watch?v=uA2_TWP5WF4\" title=\"Kyo Tour\">\n" +
                "    <img src=\"https://img.youtube.com/vi/uA2_TWP5WF4/maxresdefault.jpg\" alt=\"Kyo\" width=\"500\" height=\"300\">\n" +
                "</a>\n"
        for html <- transpileHtml(source)
        yield
            // The anchor and image must appear verbatim (not HTML-escaped).
            assert(html.contains("<a href=\"http://www.youtube.com/watch?v=uA2_TWP5WF4\""), s"Expected verbatim <a: $html")
            assert(html.contains("<img src=\"https://img.youtube.com/vi/uA2_TWP5WF4/maxresdefault.jpg\""), s"Expected verbatim <img: $html")
            assert(html.contains("</a>"), s"Expected </a>: $html")
            // The embed must not be HTML-escaped.
            assert(!html.contains("&lt;a"), s"<a must not be escaped: $html")
            assert(!html.contains("&lt;img"), s"<img must not be escaped: $html")
            // The <img must appear BETWEEN the <a and the </a> (nesting preserved as one unit).
            val aIdx     = html.indexOf("<a href")
            val imgIdx   = html.indexOf("<img src=")
            val closeIdx = html.indexOf("</a>")
            assert(aIdx >= 0, s"<a not found: $html")
            assert(imgIdx > aIdx, s"<img must follow <a: html=$html aIdx=$aIdx imgIdx=$imgIdx")
            assert(imgIdx < closeIdx, s"<img must precede </a>: html=$html imgIdx=$imgIdx closeIdx=$closeIdx")
            // The embed must be wrapped in a paragraph (real UI node, not bare top-level rawHtml).
            // The rendered <p> may carry data-kyo-path attributes, so check for the tag prefix only.
            assert(html.contains("<p") && (html.contains("<p>") || html.contains("<p ")), s"Expected <p> wrapper: $html")
        end for
    }

end DocsMarkdownTest
