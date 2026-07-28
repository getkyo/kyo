package kyo

import kyo.UI.*

class MarkdownTest extends kyo.test.Test[Any]:

    // Render a UI subtree to its first (static) HTML emission.
    private def renderHtml(ui: UI)(using Frame): String < Async =
        UI.runRender(ui).take(1).run.map(_.headMaybe.getOrElse(""))

    // Render a Markdown source to HTML in one step.
    private def renderMd(source: String)(using Frame): String < Async =
        for
            rendered <- Kyo.lift(Markdown.render(source))
            html     <- renderHtml(rendered.article)
        yield html

    // ---- GFM pipe table ----

    "GFM pipe table -> UI.table subtree" in {
        val source =
            "| Name | Type |\n" +
                "| ---- | ---- |\n" +
                "| Foo  | Int  |\n" +
                "| Bar  | String |\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("<table"), s"Expected table: $html")
            assert(html.contains("<th"), s"Expected th: $html")
            assert(html.contains("<td"), s"Expected td: $html")
            assert(html.contains("Name"), s"Expected Name: $html")
            assert(html.contains("Foo"), s"Expected Foo: $html")
        end for
    }

    // ---- Heading ids ----

    "heading id attributes equal the outline ids" in {
        val source =
            "# Alpha\n" +
                "## Beta\n" +
                "### Gamma\n" +
                "#### Delta\n" +
                "## Beta\n"
        for
            rendered <- Kyo.lift(Markdown.render(source))
            html     <- renderHtml(rendered.article)
        yield
            val ids = rendered.headings.map(_.id)
            for id <- ids.toSeq do
                assert(html.contains(s"""id="$id""""), s"Missing id=$id in HTML")
            val betaIds = ids.filter(s => s == "beta" || s == "beta-2")
            assert(betaIds.size == 2, s"Expected two beta ids, got: $betaIds")
        end for
    }

    "id lowercases text and replaces non-alphanumeric runs with a single dash" in {
        for rendered <- Kyo.lift(Markdown.render("## Composing: map, flatMap\n"))
        yield
            assert(rendered.headings.size == 1)
            assert(rendered.headings.head.id == "composing-map-flatmap")
        end for
    }

    "duplicate headings get a -2 suffix on both the id attribute and the outline" in {
        val source = "## Note\n## Note\n"
        for
            rendered <- Kyo.lift(Markdown.render(source))
            html     <- renderHtml(rendered.article)
        yield
            val ids = rendered.headings.map(_.id).toSeq
            assert(ids == Seq("note", "note-2"), s"ids: $ids")
            assert(html.contains("""id="note""""), s"Missing id=note: $html")
            assert(html.contains("""id="note-2""""), s"Missing id=note-2: $html")
        end for
    }

    "heading level maps to h1..h4" in {
        for rendered <- Kyo.lift(Markdown.render("# A\n## B\n### C\n#### D\n"))
        yield assert(
            rendered.headings.map(_.level).toSeq == Seq(1, 2, 3, 4),
            s"levels: ${rendered.headings}"
        )
    }

    // ---- Lists ----

    "two-space nested list stays nested rather than flattening" in {
        val source = "- Exception\n  - FileException\n  - TimeoutException\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("<ul"), s"Expected ul: $html")
            assert(html.contains("FileException"), s"Expected sub-item: $html")
            // Two <ul> tags means the list is nested.
            assert(html.indexOf("<ul") != html.lastIndexOf("<ul"), s"Expected nested ul: $html")
        end for
    }

    "ordered list strips the N. marker and renders an ol" in {
        val source = "1. first\n2. second\n3. third\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("<ol"), s"Expected ol: $html")
            assert(html.contains("<li"), s"Expected li: $html")
            assert(html.contains(">first</li>") || html.contains("first"), s"Expected first item text: $html")
            assert(!html.contains("1. "), s"marker must be stripped: $html")
        end for
    }

    // ---- Inline emphasis ----

    "bold opener in a paragraph renders a md-strong span" in {
        for html <- renderMd("**Note:** inline text here\n")
        yield
            assert(html.contains("<p"), s"Expected paragraph: $html")
            assert(html.contains("md-strong"), s"Expected bold span: $html")
            assert(html.contains("Note:"), s"Expected bold text: $html")
        end for
    }

    // ---- Blockquotes ----

    "a blockquote renders a blockquote element" in {
        for html <- renderMd("> some quoted text\n")
        yield
            assert(html.contains("<blockquote"), s"Expected blockquote: $html")
            assert(html.contains("some quoted text"), s"Expected quoted text: $html")
        end for
    }

    "a blockquote-wrapped fenced code block is a real pre/code block inside the blockquote" in {
        val source =
            "> intro line\n" +
                ">\n" +
                "> ```scala\n" +
                "> val x = 1\n" +
                "> ```\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("<blockquote"), s"Expected blockquote wrapper: $html")
            assert(html.contains("<pre"), s"Expected pre block: $html")
            assert(html.contains("<code"), s"Expected code block: $html")
            assert(html.contains("val x = 1"), s"Expected code body: $html")
        end for
    }

    // ---- Fenced code (nesting + info string) ----

    // A fence LONGER than the minimum lets shorter fences appear verbatim in its body. A fence-length
    // unaware parser closes the outer block on its first inner ``` line and shatters the content.
    "a 4-backtick fence wrapping 3-backtick blocks renders as one verbatim code block" in {
        val source =
            "### Wrapping example\n" +
                "\n" +
                "````markdown\n" +
                "# A document\n" +
                "\n" +
                "```scala\n" +
                "val x = 1\n" +
                "```\n" +
                "````\n" +
                "\n" +
                "After the block.\n"
        for html <- renderMd(source)
        yield
            // One outer code block, not several fragments from an early close.
            assert(html.split("<pre", -1).length - 1 == 1, s"the 4-backtick block must be ONE pre, not split: $html")
            // The outer info string's first token becomes the language class.
            assert(html.contains("language-markdown"), s"outer fence language class: $html")
            // The inner literal ```scala fence survives (it is content, shown to the reader).
            assert(html.contains("```scala"), s"the inner fence must render verbatim: $html")
            assert(html.contains("val x = 1"), s"the inner body must survive: $html")
            // The block is closed by the ```` line, so prose after it renders normally.
            assert(html.contains("After the block."), s"content after the outer fence must render: $html")
        end for
    }

    "a fenced code block exposes only the info string's first token as a language class" in {
        val source = "```scala extra=modifier\nval x = 1\n```\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("language-scala"), s"Expected language-scala class: $html")
            assert(!html.contains("extra=modifier"), s"info-string suffix must be dropped: $html")
            assert(html.contains("val x = 1"), s"body must render verbatim: $html")
        end for
    }

    "a scala fence renders plain monospace code with no highlight token spans" in {
        val source = "```scala\nval x = 1\ndef foo: Int = x\n```\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("<pre") && html.contains("<code"), s"Expected pre/code: $html")
            assert(html.contains("language-scala"), s"Expected language-scala class: $html")
            assert(!html.contains("tok-"), s"there is no syntax highlighting in kyo-markdown: $html")
            assert(html.contains("val x = 1"), s"body verbatim: $html")
        end for
    }

    "a bare fence renders pre/code with no language class" in {
        val source = "```\nsome plain text\n```\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("<pre") && html.contains("<code"), s"Expected pre/code: $html")
            assert(!html.contains("language-"), s"bare fence must have no language class: $html")
            assert(html.contains("some plain text"), s"body verbatim: $html")
        end for
    }

    // ---- Inline code spans (CommonMark backtick-run rules) ----

    "a plain single-backtick inline code span renders as one chip" in {
        for html <- renderMd("Use `Sync.defer` to suspend.\n")
        yield assert(html.contains(">Sync.defer</code>"), s"single-backtick code span must render as one chip: $html")
    }

    // A code span may be delimited by N backticks, letting a shorter run appear inside it.
    "a multi-backtick inline code span renders the literal inner backticks as one chip" in {
        val source = "A plain ```` ```scala ```` fenced block, or a `<details>` element.\n"
        for html <- renderMd(source)
        yield
            // The 4-backtick span becomes ONE <code> chip whose text is the literal ```scala (one
            // padding space stripped each side), not stray backticks leaking into the prose.
            assert(html.contains(">```scala</code>"), s"the ```scala token must be one inline code chip: $html")
            // The trailing single-backtick span still works alongside the multi-backtick one.
            assert(html.contains(">&lt;details&gt;</code>"), s"the <details> token must be its own chip: $html")
            // The prose around the chips is intact.
            assert(html.contains("fenced block, or a"), s"prose around the chip must be clean: $html")
            assert(!html.contains("```` "), s"no raw 4-backtick run may leak into the rendered prose: $html")
        end for
    }

    // An unmatched backtick run in the MIDDLE of prose is literal and parsing resumes after it.
    "an unmatched backtick run in prose is literal and does not swallow following inline code" in {
        val source = "extracts every ```scala block, and opts out with `.disable` per project.\n"
        for html <- renderMd(source)
        yield
            // The bare ```scala renders as literal backticks in the prose, not a code chip.
            assert(html.contains("```scala block"), s"bare ```scala must render literally in prose: $html")
            // The real single-backtick chip after it is its own clean chip.
            assert(html.contains(">.disable</code>"), s"the later inline-code chip must render as one chip: $html")
            // The word before that chip keeps its space.
            assert(html.contains("opts out with "), s"the space before the chip must survive: $html")
            // Exactly ONE code chip on the line (the bare run did not open a spurious span).
            assert(html.split("<code", -1).length - 1 == 1, s"only the real chip is a <code>, not the bare run: $html")
        end for
    }

    // ---- Links and images ----

    "an external http(s) link keeps its full URL" in {
        for html <- renderMd("Visit [site](https://example.com/page) now.\n")
        yield
            assert(html.contains("<a"), s"Expected anchor: $html")
            assert(html.contains("https://example.com/page"), s"external URL must be preserved: $html")
        end for
    }

    "a fragment link renders as #id" in {
        for html <- renderMd("Jump to [section](#getting-started).\n")
        yield assert(html.contains("#getting-started"), s"fragment link must render as #id: $html")
    }

    "a relative path link is left untouched" in {
        for html <- renderMd("See [prelude](../kyo-prelude/) for details.\n")
        yield assert(html.contains("../kyo-prelude/"), s"relative path must be preserved: $html")
    }

    "a linked image renders an anchor wrapping an img" in {
        val source = "[![Version](https://img.shields.io/badge/v-1.0)](https://getkyo.io)\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("<a"), s"Expected anchor: $html")
            assert(html.contains("<img"), s"Expected img: $html")
            assert(html.contains("shields.io"), s"Expected image URL: $html")
            assert(html.contains("getkyo.io"), s"Expected link URL: $html")
        end for
    }

    "an inline image renders an img with the given src and alt" in {
        for html <- renderMd("An image ![logo](kyo.png) inline.\n")
        yield
            assert(html.contains("<img"), s"Expected img: $html")
            assert(html.contains("kyo.png"), s"Expected src: $html")
            assert(html.contains("logo"), s"Expected alt text: $html")
        end for
    }

    // ---- Heading inline-markdown stripping for the outline ----

    "a heading with inline code yields plain outline text but renders a real code element" in {
        val source = "## Working with `Sync`\n"
        for
            rendered <- Kyo.lift(Markdown.render(source))
            html     <- renderHtml(rendered.article)
        yield
            assert(
                rendered.headings.headMaybe.map(_.text) == Present("Working with Sync"),
                s"outline text should be backtick-free: ${rendered.headings}"
            )
            assert(rendered.headings.headMaybe.map(_.id) == Present("working-with-sync"), s"id should be stable: ${rendered.headings}")
            assert(html.contains("<code"), s"in-page heading should still render a real code element: $html")
        end for
    }

    "a heading with bold, italic, and a link yields plain outline text" in {
        val source = "### **Bold** and *italic* and [linked](x.md)\n"
        for rendered <- Kyo.lift(Markdown.render(source))
        yield assert(
            rendered.headings.headMaybe.map(_.text) == Present("Bold and italic and linked"),
            s"outline text should be markup-free: ${rendered.headings}"
        )
    }

    // ---- Raw HTML passthrough ----

    "an inline img on its own line becomes a verbatim rawHtml leaf inside a paragraph" in {
        val snippet = """<img src="kyo.png" width="200" alt="Kyo">"""
        val source  = snippet + "\nSome regular text here.\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("""<img src="kyo.png""""), s"Expected verbatim img: $html")
            assert(html.contains("Some regular text here"), s"Expected text: $html")
            assert(!html.contains("&lt;img"), s"img must not be escaped: $html")
        end for
    }

    "a multi-line <a><img></a> embed is coalesced into one wrapped rawHtml node" in {
        val source =
            "<a href=\"http://www.youtube.com/watch?v=uA2_TWP5WF4\" title=\"Tour\">\n" +
                "    <img src=\"https://img.youtube.com/vi/uA2_TWP5WF4/maxresdefault.jpg\" alt=\"Kyo\" width=\"500\" height=\"300\">\n" +
                "</a>\n"
        for html <- renderMd(source)
        yield
            assert(html.contains("<a href=\"http://www.youtube.com/watch?v=uA2_TWP5WF4\""), s"Expected verbatim <a: $html")
            assert(html.contains("<img src=\"https://img.youtube.com/vi/uA2_TWP5WF4/maxresdefault.jpg\""), s"Expected verbatim <img: $html")
            assert(html.contains("</a>"), s"Expected </a>: $html")
            assert(!html.contains("&lt;a"), s"<a must not be escaped: $html")
            assert(!html.contains("&lt;img"), s"<img must not be escaped: $html")
            // The <img must appear BETWEEN the <a and the </a> (nesting preserved as one unit).
            val aIdx     = html.indexOf("<a href")
            val imgIdx   = html.indexOf("<img src=")
            val closeIdx = html.indexOf("</a>")
            assert(aIdx >= 0, s"<a not found: $html")
            assert(imgIdx > aIdx, s"<img must follow <a: html=$html aIdx=$aIdx imgIdx=$imgIdx")
            assert(imgIdx < closeIdx, s"<img must precede </a>: html=$html imgIdx=$imgIdx closeIdx=$closeIdx")
            assert(html.contains("<p") && (html.contains("<p>") || html.contains("<p ")), s"Expected <p> wrapper: $html")
        end for
    }

    // ---- HTML comments ----

    "a leading HTML comment is skipped and the first heading is the H1" in {
        val source = "<!-- This is a comment -->\n# kyo-core\nSome text.\n"
        for rendered <- Kyo.lift(Markdown.render(source))
        yield assert(rendered.headings.headMaybe.map(_.text) == Present("kyo-core"))
    }

    "a leading multi-line HTML comment keeps the text after the closing -->" in {
        val source =
            "<!-- a comment\n" +
                "spanning lines -->Kept text.\n" +
                "# Title\n"
        for html <- renderMd(source)
        yield
            assert(!html.contains("-->"), s"--> must not leak: $html")
            assert(html.contains("Kept text."), s"text after --> must be preserved: $html")
        end for
    }

    // ---- Totality: degrade, do not fail ----

    "empty source returns an empty article and no headings" in {
        for rendered <- Kyo.lift(Markdown.render(""))
        yield assert(rendered == Markdown.Rendered(UI.empty, Chunk.empty))
    }

    "blank (whitespace-only) source returns an empty article and no headings" in {
        for rendered <- Kyo.lift(Markdown.render("   \n\n  \n"))
        yield assert(rendered == Markdown.Rendered(UI.empty, Chunk.empty))
    }

    "an unknown construct degrades to a plain paragraph without aborting" in {
        // A definition-list style is not supported; it must not abort and must produce output.
        val source = "term\n:   definition\n"
        for
            rendered <- Kyo.lift(Markdown.render(source))
            html     <- renderHtml(rendered.article)
        yield
            assert(rendered.headings.isEmpty, s"Expected no headings, got: ${rendered.headings}")
            assert(html.contains("<p"), s"Expected paragraph element: $html")
            assert(html.contains("term"), s"Expected literal 'term' in output: $html")
            assert(html.contains("definition"), s"Expected literal 'definition' in output: $html")
            assert(rendered.article != UI.empty, s"Expected non-empty article: $rendered")
        end for
    }

    "a malformed table (no separator row) degrades without producing a table" in {
        val source = "| A | B |\n"
        for html <- renderMd(source)
        yield assert(!html.contains("<table"), s"Malformed table should not produce <table: $html")
    }

    // ---- Paragraph flow ----

    "consecutive non-blank lines coalesce into one paragraph; a blank line starts a new one" in {
        val source = "line one\nline two\n\nsecond paragraph\n"
        for
            rendered <- Kyo.lift(Markdown.render(source))
            html     <- renderHtml(rendered.article)
        yield
            // Two <p> elements: the first coalesces line one + line two, the second is separate.
            assert(html.split("<p", -1).length - 1 == 2, s"expected exactly two paragraphs: $html")
            assert(html.contains("line one line two"), s"lines must coalesce with a space: $html")
            assert(html.contains("second paragraph"), s"second paragraph must be present: $html")
        end for
    }

    // ---- Perf guard: a large document renders in bounded time ----

    "a large synthetic document renders in bounded time" in {
        val sb = new StringBuilder()
        sb.append("# Large synthetic module\n\n")
        var i = 0
        while i < 220 do
            sb.append(s"## Section $i\n\n")
            sb.append("This is a paragraph with some `inline code` and a [link](https://example.com/page) ")
            sb.append("and **bold** and *italic* spread across a moderately long line of ordinary prose. ")
            sb.append("It repeats enough plain words to exercise the inline text-run path at scale.\n\n")
            sb.append("```scala\n")
            sb.append("val config = load(\"etc\", \"app\")\n")
            sb.append("val xs = List(1, 2, 3).map(_ * 2)\n")
            sb.append("```\n\n")
            sb.append("| Name | Type | Note |\n")
            sb.append("| ---- | ---- | ---- |\n")
            sb.append(s"| Foo$i | Int | a b |\n")
            sb.append(s"| Bar$i | String | x y |\n\n")
            sb.append("- first item with `code`\n")
            sb.append("- second item with a [ref](#section-0)\n")
            sb.append("  - nested item\n\n")
            i += 1
        end while
        val source = sb.toString
        assert(source.length > 100000, s"fixture too small: ${source.length}")

        val start = java.lang.System.nanoTime()
        for
            rendered <- Kyo.lift(Markdown.render(source))
            html     <- renderHtml(rendered.article)
        yield
            val elapsed = (java.lang.System.nanoTime() - start) / 1000000L
            assert(rendered.headings.nonEmpty, "expected headings")
            assert(html.contains("Large synthetic module"), "expected title in output")
            assert(html.contains("<table"), "expected a table in output")
            assert(elapsed < 30000L, s"render took ${elapsed}ms (budget 30000ms): not linear")
        end for
    }

end MarkdownTest
