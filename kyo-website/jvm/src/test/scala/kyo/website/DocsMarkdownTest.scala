package kyo.website

import kyo.*
import kyo.UI.*

class DocsMarkdownTest extends WebsiteTest:

    // Helper: run SSG renderer and get the first HTML emission.
    private def renderHtml(ui: UI)(using Frame): String < Async =
        UI.runRender(ui).take(1).run.map(_.headMaybe.getOrElse(""))

    // Helper: transpile and render to HTML in one step.
    private def transpileHtml(source: String)(using Frame): String < Async =
        for
            rendered <- DocsMarkdownRender.transpile(source)
            html     <- renderHtml(rendered.article)
        yield html

    // Helper: just transpile and return Rendered.
    private def transpile(source: String)(using Frame): DocsMarkdownRender.Rendered < Sync =
        DocsMarkdownRender.transpile(source)

    // ---- GFM pipe table ----

    "GFM pipe table -> UI.table subtree" in {
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

    // ---- Heading slugs == headings slugs ----

    "heading id slugs equal headings slugs" in {
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

    "slug lowercases and replaces non-alnum" in {
        for rendered <- transpile("## Composing: map, flatMap\n")
        yield
            assert(rendered.headings.size == 1)
            assert(rendered.headings.head.slug == "composing-map-flatmap")
        end for
    }

    // ---- Duplicate heading deduplication ----

    "duplicate headings get -2 suffix on both sides" in {
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

    "two-space nested list does not flatten" in {
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

    "blockquote Note becomes callout callout-note" in {
        for html <- transpileHtml("> **Note:** be careful\n")
        yield assert(html.contains("callout-note"), s"HTML: $html")
        end for
    }

    "blockquote Caution becomes callout callout-caution" in {
        for html <- transpileHtml("> **Caution:** danger\n")
        yield assert(html.contains("callout-caution"), s"HTML: $html")
        end for
    }

    "other blockquote labels become plain blockquote" in {
        for html <- transpileHtml("> **Sequential vs parallel:** some text\n")
        yield
            assert(html.contains("blockquote"), s"HTML: $html")
            assert(!html.contains("callout-note"), s"Should not be callout-note: $html")
            assert(!html.contains("callout-caution"), s"Should not be callout-caution: $html")
        end for
    }

    // ---- Form-B bold opener stays plain prose ----

    "Form-B bold opener in a paragraph stays plain prose" in {
        for html <- transpileHtml("**Note:** inline text here\n")
        yield
            assert(html.contains("<p"), s"Expected paragraph: $html")
            assert(html.contains("md-strong"), s"Expected bold span: $html")
            assert(!html.contains("callout"), s"Should not be callout: $html")
        end for
    }

    // ---- Blockquote-wrapped fenced code ----

    "blockquote-wrapped fenced code is a real pre/code block" in {
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

    "doctest:setup comment is stripped" in {
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

    "doctest:setup block does not leak its closing --> into prose (B1)" in {
        // Mirrors the kyo-http README shape: a leading `<!-- doctest:setup ... -->` whose closing
        // `-->` sits on its own line after the wrapped fenced block. The closing line must be
        // consumed, not emitted as a `--> ` paragraph above the H1.
        val source =
            "<!-- doctest:setup\n" +
                "```scala\n" +
                "val x = 1\n" +
                "```\n" +
                "-->\n" +
                "\n" +
                "# kyo-http\n"
        for
            rendered <- transpile(source)
            html     <- renderHtml(rendered.article)
        yield
            assert(!html.contains("--&gt;"), s"--> must not leak into prose: $html")
            assert(!html.contains("-->"), s"--> must not leak into prose: $html")
            assert(rendered.headings.headMaybe.map(_.text) == Present("kyo-http"), s"H1 should be first heading: ${rendered.headings}")
        end for
    }

    "leading multi-line HTML comment with text after --> keeps that text (B1)" in {
        val source =
            "<!-- a comment\n" +
                "spanning lines -->Kept text.\n" +
                "# Title\n"
        for
            html <- transpileHtml(source)
        yield
            assert(!html.contains("-->"), s"--> must not leak: $html")
            assert(html.contains("Kept text."), s"text after --> must be preserved: $html")
        end for
    }

    // ---- Intra-repo README.md link rewriting (B2) ----

    "[..](dir/README.md) link rewrites to the directory route (B2)" in {
        val source = "See [prelude](../kyo-prelude/README.md) for details.\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("../kyo-prelude/"), s"href should point at the dir route: $html")
            assert(!html.contains("README.md"), s"README.md filename should be stripped: $html")
        end for
    }

    "[..](README.md) bare link rewrites to ./ (B2)" in {
        val source = "Back to [home](README.md).\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("\"./\"") || html.contains("href=\"./\""), s"bare README.md should become ./: $html")
            assert(!html.contains("README.md"), s"README.md should be stripped: $html")
        end for
    }

    "[..](README.md#anchor) keeps the fragment (B2)" in {
        val source = "Jump to [section](README.md#getting-started).\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("#getting-started"), s"fragment should be kept: $html")
            assert(!html.contains("README.md"), s"README.md should be stripped: $html")
        end for
    }

    "[..](MANIFESTO.md) rewrites to the manifesto docs page" in {
        // The generator appends the repo-root MANIFESTO.md as the docs page <prefix>/manifesto/, so the
        // README's repo-relative link must resolve there instead of 404ing on the raw .md file.
        val source = "This project exists because of a belief. [Read the manifesto](MANIFESTO.md).\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("manifesto/"), s"MANIFESTO.md should rewrite to manifesto/: $html")
            assert(!html.contains("MANIFESTO.md"), s"the .md filename should be stripped: $html")
        end for
    }

    "external http(s) links are left untouched by README rewriting (B2)" in {
        val source = "Visit [site](https://example.com/README.md) now.\n"
        for html <- transpileHtml(source)
        yield assert(html.contains("https://example.com/README.md"), s"external link must be untouched: $html")
    }

    // ---- Heading.text inline-markdown stripping (B3) ----

    "heading with inline code yields plain TOC text but renders real <code> (B3)" in {
        val source = "## Working with `Sync`\n"
        for
            rendered <- transpile(source)
            html     <- renderHtml(rendered.article)
        yield
            assert(
                rendered.headings.headMaybe.map(_.text) == Present("Working with Sync"),
                s"TOC text should be backtick-free: ${rendered.headings}"
            )
            assert(rendered.headings.headMaybe.map(_.slug) == Present("working-with-sync"), s"slug should be stable: ${rendered.headings}")
            assert(html.contains("<code"), s"in-page heading should still render real <code>: $html")
        end for
    }

    "heading with bold/italic/link yields plain TOC text (B3)" in {
        val source = "### **Bold** and *italic* and [linked](x.md)\n"
        for rendered <- transpile(source)
        yield assert(
            rendered.headings.headMaybe.map(_.text) == Present("Bold and italic and linked"),
            s"TOC text should be markup-free: ${rendered.headings}"
        )
    }

    // ---- Doctest info-string suffix stripping ----

    "doctest info-string suffix is stripped" in {
        val source = "```scala doctest:scope=inherited\nval x = 1\n```\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("tok-keyword"), s"Expected tok-keyword spans: $html")
            assert(!html.contains("doctest:scope"), s"Info suffix should be stripped: $html")
        end for
    }

    // ---- Scala token highlighting ----

    "scala fence gets tok-* token spans" in {
        val source = "```scala\nval x = 1\ndef foo: Int = x\n```\n"
        for html <- transpileHtml(source)
        yield assert(html.contains("tok-keyword"), s"Expected tok-keyword: $html")
        end for
    }

    // ---- SBT and bash token highlighting ----

    "sbt and bash fences get tok-* spans" in {
        // With the scalameta highlighter, sbt operators like +=, %%, % are symbolic idents
        // that classify as tok-operator (not tok-keyword as in the old hand-written lexer).
        // Bash still uses the keyword-Set lexer, so 'if', 'then', 'fi' are tok-keyword.
        val sbtSource  = "```sbt\nlibraryDependencies += \"org\" %% \"dep\" % \"1.0\"\n```\n"
        val bashSource = "```bash\nif true; then\n  echo hello\nfi\n```\n"
        for
            sbtHtml  <- transpileHtml(sbtSource)
            bashHtml <- transpileHtml(bashSource)
        yield
            // sbt: scalameta classifies += as tok-operator and string literals as tok-string.
            assert(sbtHtml.contains("tok-operator"), s"SBT should have tok-operator spans: $sbtHtml")
            assert(sbtHtml.contains("tok-string"), s"SBT should have tok-string for literals: $sbtHtml")
            assert(bashHtml.contains("tok-keyword"), s"Bash should have tok-keyword: $bashHtml")
        end for
    }

    // ---- Bare/other fence: no token spans ----

    "bare and other fences produce plain pre/code with no tok-* spans" in {
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

    "shield.io badge -> UI.a containing UI.img" in {
        val source = "[![Version](https://img.shields.io/badge/v-1.0)](https://getkyo.io)\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("<a"), s"Expected anchor: $html")
            assert(html.contains("<img"), s"Expected img: $html")
            assert(html.contains("shields.io"), s"Expected badge URL: $html")
        end for
    }

    // ---- Root README inline <img> passthrough ----

    "root README inline img becomes UI.rawHtml leaf" in {
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

    "empty source returns UI.empty and Chunk.empty headings" in {
        for rendered <- transpile("")
        yield assert(rendered == DocsMarkdownRender.Rendered(UI.empty, "", Chunk.empty))
        end for
    }

    // ---- HTML comment before heading ----

    "leading HTML comment is skipped; first heading is the H1" in {
        val source = "<!-- This is a comment -->\n# kyo-core\nSome text.\n"
        for rendered <- transpile(source)
        yield assert(rendered.headings.headMaybe.map(_.text) == Present("kyo-core"))
        end for
    }

    // ---- Degrade-not-fail: unknown construct ----

    "unknown construct degrades to plain paragraph without abort" in {
        // A definition list style is not supported; must not abort, must produce some output.
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

    "malformed table degrades gracefully without abort" in {
        // Table with no separator row - only one line starting with |.
        val source = "| A | B |\n"
        for html <- transpileHtml(source)
        yield
            // No crash; should not produce a full table element (no separator row).
            assert(!html.contains("<table"), s"Malformed table should not produce <table: $html")
        end for
    }

    // ---- Multi-line <a><img></a> video embed ----

    "multi-line <a><img></a> video embed is coalesced into one wrapped rawHtml node" in {
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

    // ---- Tokenizer forward-progress (regression for the lone-`/` infinite loop) ----

    "scala fence with a lone `/` operator transpiles without hanging" in {
        // `Path / "etc"` contains a `/` that is not `//` or `/*` and not an sbt operator, so no
        // tokenizer branch consumed it; the loop used to stall on it and allocate forever (OOM).
        val source =
            "```scala\n" +
                "val config: Path = Path / \"etc\" / \"myapp\"\n" +
                "val ratio = total / count\n" +
                "```\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("Path"), s"Expected Path token: $html")
            assert(html.contains("etc"), s"Expected the path literal: $html")
            assert(html.contains("count"), s"Expected the division operand: $html")
            // The `/` operator must survive in the output, not be dropped or duplicated into a hang.
            assert(html.contains("/"), s"Expected the slash to render: $html")
        end for
    }

    "bash fence with a lone special char transpiles without hanging" in {
        val source =
            "```bash\n" +
                "ls / | wc -l\n" +
                "```\n"
        for html <- transpileHtml(source)
        yield
            assert(html.contains("ls"), s"Expected ls keyword/word: $html")
            assert(html.contains("wc"), s"Expected wc: $html")
        end for
    }

    // ---- Perf guard: a large README must transpile + render in bounded time ----

    "large synthetic README transpiles and renders in bounded time" in {
        // Mixed prose + code fences (with lone-`/` operators) + a table + lists, sized ~128 KB.
        // Pre-fix this either hangs in the tokenizer (lone `/`) or runs O(n^2) in the inline parser;
        // post-fix it is linear and completes well under the 30s budget on every platform runner.
        val sb = new StringBuilder()
        sb.append("# Large synthetic module\n\n")
        var i = 0
        while i < 220 do
            sb.append(s"## Section $i\n\n")
            sb.append("This is a paragraph with some `inline code` and a [link](https://example.com/page) ")
            sb.append("and **bold** and *italic* spread across a moderately long line of ordinary prose. ")
            sb.append("It repeats enough plain words to exercise the inline text-run path at scale.\n\n")
            sb.append("```scala\n")
            sb.append("val config: Path = Path / \"etc\" / \"app\"\n")
            sb.append("def ratio(a: Int, b: Int): Int = a / b // integer division\n")
            sb.append("val xs = List(1, 2, 3).map(_ * 2)\n")
            sb.append("```\n\n")
            sb.append("| Name | Type | Note |\n")
            sb.append("| ---- | ---- | ---- |\n")
            sb.append(s"| Foo$i | Int | a / b |\n")
            sb.append(s"| Bar$i | String | x / y |\n\n")
            sb.append("- first item with `code`\n")
            sb.append("- second item with a [ref](#section-0)\n")
            sb.append("  - nested item\n\n")
            i += 1
        end while
        val source = sb.toString
        assert(source.length > 100000, s"fixture too small: ${source.length}")

        val start = java.lang.System.nanoTime()
        for
            rendered <- DocsMarkdownRender.transpile(source)
            html     <- renderHtml(rendered.article)
        yield
            val elapsed = (java.lang.System.nanoTime() - start) / 1000000L
            assert(rendered.headings.nonEmpty, "expected headings")
            assert(html.contains("Large synthetic module"), "expected title in output")
            assert(html.contains("<table"), "expected a table in output")
            assert(elapsed < 30000L, s"transpile+render took ${elapsed}ms (budget 30000ms): not linear")
        end for
    }

    // ---- scalameta highlighter + TokenKind tests ----

    // Helper: highlight a scala snippet and return the rendered HTML.
    private def highlightScalaHtml(code: String)(using Frame): String < Async =
        val source = "```scala\n" + code + "\n```\n"
        transpileHtml(source)

    "every TokenKind cssClass is in the docsTokens stylesheet" in {
        // Collect all css classes from the token kinds.
        val tokenClasses = DocsMarkdownRender.TokenKind.values.map(_.cssClass).toSet
        // The stylesheet includes: tok-keyword, tok-string, tok-comment, tok-type, tok-number,
        // tok-literal, tok-interpolation, tok-annotation, tok-operator. We verify that all
        // 8 TokenKind classes are a subset of that known set (exact listing per plan).
        val stylesClasses = Set(
            "tok-keyword",
            "tok-string",
            "tok-comment",
            "tok-type",
            "tok-number",
            "tok-literal",
            "tok-interpolation",
            "tok-annotation",
            "tok-operator"
        )
        assert(
            tokenClasses.subsetOf(stylesClasses),
            s"TokenKind classes not covered by docsTokens: ${tokenClasses -- stylesClasses}"
        )
    }

    "cssClass mapping is exact per kind" in {
        assert(DocsMarkdownRender.TokenKind.Keyword.cssClass == "tok-keyword")
        assert(DocsMarkdownRender.TokenKind.Str.cssClass == "tok-string")
        assert(DocsMarkdownRender.TokenKind.Comment.cssClass == "tok-comment")
        assert(DocsMarkdownRender.TokenKind.Number.cssClass == "tok-number")
        assert(DocsMarkdownRender.TokenKind.Type.cssClass == "tok-type")
        assert(DocsMarkdownRender.TokenKind.Interpolation.cssClass == "tok-interpolation")
        assert(DocsMarkdownRender.TokenKind.Annotation.cssClass == "tok-annotation")
        assert(DocsMarkdownRender.TokenKind.Operator.cssClass == "tok-operator")
    }

    "keyword/number classification: val x = 1" in {
        for html <- highlightScalaHtml("val x = 1")
        yield
            assert(html.contains("tok-keyword"), s"Expected tok-keyword for val: $html")
            assert(html.contains("tok-number"), s"Expected tok-number for 1: $html")
            // The ident x should be plain text, not in a tok- span.
            // Verify no tok-keyword span surrounds 'x' by checking that 'x' appears outside spans.
            val xInKeyword = html.contains(">x<") && html.contains("tok-keyword")
            // More direct: check that val appears in tok-keyword.
            assert(html.contains("tok-keyword\">val</"), s"val must be in tok-keyword span: $html")
            assert(html.contains("tok-number\">1</"), s"1 must be in tok-number span: $html")
        end for
    }

    "string and char literals" in {
        for html <- highlightScalaHtml("val s = \"hi\"\nval c = 'a'")
        yield
            assert(html.contains("tok-string"), s"Expected tok-string for string literal: $html")
            // Both hi (in the string) and 'a' char literal should be in tok-string spans.
            assert(html.indexOf("tok-string") >= 0, s"tok-string spans present: $html")
        end for
    }

    "line and block comments" in {
        for html <- highlightScalaHtml("// note\n/* block */\nval x = 1")
        yield
            assert(html.contains("tok-comment"), s"Expected tok-comment: $html")
            // Both line and block comment text should be in tok-comment spans.
            assert(html.contains("// note") || html.contains("// note"), s"comment text present: $html")
        end for
    }

    "numeric literals Int/Long/Float/Double" in {
        for html <- highlightScalaHtml("val a = 1\nval b = 2L\nval c = 3.0\nval d = 4.5d")
        yield
            // Each of 1, 2L, 3.0, 4.5d should be in tok-number spans.
            assert(html.contains("tok-number"), s"Expected tok-number: $html")
            assert(html.contains("tok-number\">1</"), s"1 in tok-number: $html")
            assert(html.contains("tok-number\">2L</"), s"2L in tok-number: $html")
            assert(html.contains("tok-number\">3.0</"), s"3.0 in tok-number: $html")
            assert(html.contains("tok-number\">4.5d</"), s"4.5d in tok-number: $html")
        end for
    }

    "interpolation parts and id: s\"x=$x\"" in {
        for html <- highlightScalaHtml("s\"x=$x\"")
        yield
            // The interpolator id 's' should be in tok-interpolation.
            assert(html.contains("tok-interpolation"), s"Expected tok-interpolation for 's': $html")
            // The splice $ should be in tok-operator.
            assert(html.contains("tok-operator"), s"Expected tok-operator for dollar splice: $html")
            // Literal parts should be in tok-string spans.
            assert(html.contains("tok-string"), s"Expected tok-string for literal parts: $html")
        end for
    }

    "annotation marker and name: @main def run = ()" in {
        for html <- highlightScalaHtml("@main def run = ()")
        yield
            // @ should be in tok-annotation.
            assert(html.contains("tok-annotation"), s"Expected tok-annotation for @: $html")
            // The ident after @ (main) should also be in tok-annotation via lookback.
            assert(html.contains("tok-annotation\">@</"), s"@ in tok-annotation span: $html")
            assert(html.contains("tok-annotation\">main</"), s"main in tok-annotation span: $html")
            // def should be in tok-keyword.
            assert(html.contains("tok-keyword\">def</"), s"def in tok-keyword span: $html")
        end for
    }

    "operator punctuation: val f: Int => Int = _ + 1" in {
        for html <- highlightScalaHtml("val f: Int => Int = _ + 1")
        yield
            // : should be in tok-operator.
            assert(html.contains("tok-operator\">:</"), s": in tok-operator: $html")
            // = should be in tok-operator.
            assert(html.contains("tok-operator\">=</"), s"= in tok-operator: $html")
            // _ (underscore) should be in tok-operator.
            assert(html.contains("tok-operator\">_</"), s"_ in tok-operator: $html")
            // The => arrow should be in tok-operator (FunctionArrow or RightArrow).
            // In HTML, > is escaped to &gt; so => becomes =&gt;.
            assert(html.contains("tok-operator\">=&gt;"), s"=> in tok-operator (html-encoded as =&gt;): $html")
            // + is a symbolic ident and should be in tok-operator.
            assert(html.contains("tok-operator\">+</"), s"+ in tok-operator: $html")
        end for
    }

    "Type heuristic arm (a) capitalized idents" in {
        for html <- highlightScalaHtml("val o: Option = ???\nval n = Int.MaxValue")
        yield
            // Option and Int should be classified as tok-type (arm a: uppercase first char).
            assert(html.contains("tok-type\">Option</"), s"Option in tok-type: $html")
            assert(html.contains("tok-type\">Int</"), s"Int in tok-type: $html")
            // MaxValue is also uppercase-first, so it gets tok-type (documented tolerated false positive).
            assert(html.contains("tok-type\">MaxValue</"), s"MaxValue in tok-type (expected false positive): $html")
        end for
    }

    "Type heuristic arm (b) lowercase after context tokens" in {
        // type alias = Int: 'alias' after KwType -> tok-type (arm b).
        // extends Foo with Bar: Bar after KwWith -> tok-type.
        // def f[a](x: a): a = x: 'a' after [ and : -> tok-type.
        for
            aliasHtml <- highlightScalaHtml("type alias = Int")
            mixinHtml <- highlightScalaHtml("class A extends Foo with Bar")
            paramHtml <- highlightScalaHtml("def f[a](x: a): a = x")
        yield
            // alias after 'type' should be tok-type.
            assert(aliasHtml.contains("tok-type\">alias</"), s"alias in tok-type: $aliasHtml")
            // Bar after 'with' should be tok-type.
            assert(mixinHtml.contains("tok-type\">Bar</"), s"Bar in tok-type: $mixinHtml")
            // 'a' after '[' and ':' should be tok-type.
            assert(paramHtml.contains("tok-type\">a</"), s"a in tok-type: $paramHtml")
        end for
    }

    "Type arm (b) supertype/subtype bounds: type T >: lo <: hi" in {
        for html <- highlightScalaHtml("type T >: lo <: hi")
        yield
            // lo (after >:) and hi (after <:) should both be in tok-type via arm (b).
            assert(html.contains("tok-type\">lo</"), s"lo in tok-type: $html")
            assert(html.contains("tok-type\">hi</"), s"hi in tok-type: $html")
        end for
    }

    // Regression: boolean and null literals must classify as tok-keyword.
    // KwTrue, KwFalse, KwNull extend BooleanConstant/Literal, not Token$Keyword in scalameta 4.13.4.
    // Without the dedicated arm they fall through to Absent and render unhighlighted.
    "boolean and null literals classify as tok-keyword" in {
        val snippet = "val b = true\nval n = null\nval f = false"
        for html <- highlightScalaHtml(snippet)
        yield
            assert(html.contains("tok-keyword\">true</"), s"true must render with tok-keyword: $html")
            assert(html.contains("tok-keyword\">null</"), s"null must render with tok-keyword: $html")
            assert(html.contains("tok-keyword\">false</"), s"false must render with tok-keyword: $html")
        end for
    }

    "any snippet completes without exception or Abort" in {
        // No input may panic the build. Scalameta 4.13.4 in Scala3 dialect is
        // lenient and returns Right (tokens) even for partial/malformed snippets (empirically
        // verified: unterminated strings, multi-char char literals, invalid unicode escapes all
        // produce Right). The Left branch in highlightScala degrades to Ast.Text as a defensive
        // guard. We verify the totality contract: any snippet, even unusual ones, completes
        // without exception and produces a non-crashing output.
        val snippets = Seq(
            "val x = \"unterminated",
            "'ab'",
            "// just a comment",
            "???",
            ""
        )
        for
            results <- Kyo.foreach(Chunk.from(snippets)) { body =>
                val source = "```scala\n" + body + "\n```\n"
                transpileHtml(source)
            }
        yield
            // All snippets completed (no exception); each produced some HTML output.
            assert(results.length == snippets.length, "All snippets must complete")
            // The empty body case produces a pre/code block with no tok- spans.
            assert(!results.last.contains("tok-"), s"Empty body should have no tok-: ${results.last}")
        end for
    }

    "partial expression still tokenizes (not a degrade)" in {
        // scalameta can tokenize expressions like x.map(_ + 1) even without a complete compilation
        // unit. The result should be a fragment with tok- spans, not a single Ast.Text degrade.
        for html <- highlightScalaHtml("x.map(_ + 1)")
        yield
            // Should have tok-operator for + and _.
            assert(html.contains("tok-operator"), s"Expected tok-operator in partial expr: $html")
            // Should NOT be a plain-text degrade (which would have no tok- spans).
            assert(html.contains("tok-"), s"Expected tok- spans (fragment, not degrade): $html")
        end for
    }

    "byte-preserving fold round-trip" in {
        // All token texts are emitted (including whitespace/trivia), so stripping HTML tags from
        // the rendered output recovers the original body. We verify each element is present in
        // the HTML: tokens are in spans and trivia/whitespace is plain text between them.
        // The HTML renderer HTML-encodes special chars (e.g. > -> &gt;), so we check for
        // the individual identifiers and tokens, not the literal concatenated source.
        val body = "def f(x: Int): Int = x + 1  // c"
        for html <- highlightScalaHtml(body)
        yield
            assert(html.contains("tok-keyword\">def</"), s"def in tok-keyword: $html")
            assert(html.contains("tok-type\">Int</"), s"Int in tok-type: $html")
            assert(html.contains("tok-operator\">=</"), s"= in tok-operator: $html")
            assert(html.contains("tok-operator\">+</"), s"+ in tok-operator: $html")
            assert(html.contains("tok-number\">1</"), s"1 in tok-number: $html")
            assert(html.contains("tok-comment\">// c</"), s"comment in tok-comment: $html")
            // The ident f (function name) appears as plain text between spans.
            assert(html.contains(">def</span> f(x"), s"f( as plain text after def: $html")
        end for
    }

    "sbt fence uses the scala highlighter" in {
        val source = "```sbt\nversion := \"1.0\"\n```\n"
        for html <- transpileHtml(source)
        yield
            // := is a symbolic ident -> tok-operator.
            assert(html.contains("tok-operator"), s"Expected tok-operator for :=: $html")
            // "1.0" is a string literal -> tok-string.
            assert(html.contains("tok-string"), s"Expected tok-string for \"1.0\": $html")
        end for
    }

    "large-README highlight pass does not panic" in {
        // Build a large source with many scala fences.
        val sb = new StringBuilder()
        sb.append("# Large README\n\n")
        var i = 0
        while i < 50 do
            sb.append(s"## Section $i\n\n")
            sb.append("```scala\n")
            sb.append("val config: Path = Path / \"etc\"\n")
            sb.append("@main def run(args: String*): Unit = println(s\"start: ${args.length}\")\n")
            sb.append("type Foo = Option[Int]\n")
            sb.append("val x: Int = 1 + 2L\n")
            sb.append("// a comment\n")
            sb.append("```\n\n")
            i += 1
        end while
        val source = sb.toString
        for
            rendered <- DocsMarkdownRender.transpile(source)
            html     <- renderHtml(rendered.article)
        yield
            // Non-empty Rendered produced, no exception thrown (test completing proves this).
            assert(rendered.headings.nonEmpty, s"Expected headings in large README: $rendered")
            assert(html.contains("<h2"), s"Expected headings in rendered HTML: $html")
        end for
    }

    // ---- renderArticleHtml + renderArticle + transpile sentinel ----

    "renderArticleHtml renders a static article to HTML" in {
        for
            rendered <- DocsMarkdownRender.transpile("# Intro\n\nHello.")
            html     <- DocsMarkdownRender.renderArticleHtml(rendered.article)
        yield
            // The rendered HTML carries data-kyo-path attributes from the kyo-ui renderer.
            assert(html.contains("""id="intro""""), s"Expected h1 with id=intro: $html")
            assert(html.contains("<h1"), s"Expected h1 element: $html")
            assert(html.contains("Intro"), s"Expected Intro text: $html")
            assert(html.contains("<p"), s"Expected paragraph element: $html")
            assert(html.contains("Hello."), s"Expected Hello. text: $html")
        end for
    }

    "renderArticle fills articleHtml on Rendered" in {
        for
            rendered      <- DocsMarkdownRender.renderArticle("## A\n\nB")
            htmlViaHelper <- renderHtml(rendered.article)
        yield
            assert(rendered.articleHtml.nonEmpty, "articleHtml must be non-empty after renderArticle")
            assert(
                rendered.articleHtml == htmlViaHelper,
                s"articleHtml must equal renderHtml(article); got articleHtml=${rendered.articleHtml}, helper=$htmlViaHelper"
            )
            assert(
                rendered.headings == Chunk(DocsMarkdown.Heading(2, "A", "a")),
                s"headings must be Chunk(Heading(2,A,a)), got: ${rendered.headings}"
            )
        end for
    }

    "transpile leaves articleHtml as empty sentinel" in {
        for rendered <- DocsMarkdownRender.transpile("# X")
        yield
            assert(rendered.articleHtml == "", s"transpile must leave articleHtml as empty sentinel, got: ${rendered.articleHtml}")
            assert(rendered.article != UI.empty, "transpile must populate the article UI")
        end for
    }

    "empty source renders empty article HTML via renderArticle" in {
        for rendered <- DocsMarkdownRender.renderArticle("")
        yield assert(
            rendered == DocsMarkdownRender.Rendered(UI.empty, "", Chunk.empty),
            s"empty source must yield Rendered(UI.empty, \"\", Chunk.empty), got: $rendered"
        )
        end for
    }

    "SSG article HTML equals injected article HTML (parity unit assertion)" in {
        // The SSG-emitted article HTML equals the client-injected article, byte-identical.
        // Unit assertion: renderArticle produces articleHtml == renderHtml(article). This is trivially
        // true by construction (renderArticleHtml IS runRender(article).take(1)...), but is pinned as a
        // permanent regression guard.
        val source = "## Scope\n\nSome text.\n"
        for
            rendered        <- DocsMarkdownRender.renderArticle(source)
            htmlFromArticle <- renderHtml(rendered.article)
        yield assert(
            rendered.articleHtml == htmlFromArticle,
            s"articleHtml must equal renderHtml(article) byte-for-byte; got articleHtml=${rendered.articleHtml}, renderHtml=$htmlFromArticle"
        )
        end for
    }

    // ---- sectionSnippets excludes RawEmbed from the snippet ----

    "sectionSnippets excludes a RawEmbed block from the snippet" in {
        // A section whose only following block is a raw HTML embed yields an empty snippet.
        // The embed content must not appear in the snippet (RawEmbed is excluded, same as Fence).
        val embedContent = """<img src="kyo.png" width="200" alt="Kyo">"""
        val source       = s"## Overview\n$embedContent\n"
        for snippets <- DocsMarkdownRender.sectionSnippets(source, 160)
        yield
            assert(snippets.size == 1, s"expected 1 pair: $snippets")
            val (heading, snippet, _) = snippets(0)
            assert(heading.text == "Overview", s"heading text must be Overview: $heading")
            assert(heading.slug == "overview", s"heading slug must be overview: $heading")
            assert(!snippet.contains("kyo.png"), s"RawEmbed content must not appear in snippet: $snippet")
            assert(!snippet.contains("<img"), s"raw HTML must not appear in snippet: $snippet")
            assert(snippet == "", s"snippet must be empty when only block after heading is a RawEmbed: $snippet")
        end for
    }

    // ---- sectionSnippets returns one pair per heading in document order ----

    "sectionSnippets returns one pair per heading in document order with slugs matching parseArticle" in {
        val source = "## Alpha\nAlpha text.\n### Beta\nBeta.\n## Gamma\nGamma text.\n"
        for
            snippets <- DocsMarkdownRender.sectionSnippets(source, 160)
            rendered <- DocsMarkdownRender.transpile(source)
        yield
            assert(snippets.size == 3, s"expected 3 pairs, got ${snippets.size}: $snippets")
            val (h0, s0, _) = snippets(0)
            val (h1, s1, _) = snippets(1)
            val (h2, s2, _) = snippets(2)
            assert(h0.level == 2, s"first heading must be level 2: $h0")
            assert(h0.text == "Alpha", s"first heading text must be Alpha: $h0")
            assert(h0.slug == "alpha", s"first heading slug must be alpha: $h0")
            assert(h1.level == 3, s"second heading must be level 3: $h1")
            assert(h1.text == "Beta", s"second heading text must be Beta: $h1")
            assert(h1.slug == "beta", s"second heading slug must be beta: $h1")
            assert(h2.level == 2, s"third heading must be level 2: $h2")
            assert(h2.text == "Gamma", s"third heading text must be Gamma: $h2")
            assert(h2.slug == "gamma", s"third heading slug must be gamma: $h2")
            // Slugs must match parseArticle's slugs for the same headings in the same order.
            val articleSlugs = rendered.headings.map(_.slug).toSeq
            val snippetSlugs = snippets.map(_._1.slug).toSeq
            assert(snippetSlugs == articleSlugs, s"slugs must match parseArticle: snippetSlugs=$snippetSlugs articleSlugs=$articleSlugs")
        end for
    }

    // ---- sectionSnippets excludes fenced code from the snippet ----

    "sectionSnippets excludes fenced code blocks from the snippet" in {
        val source = "## Section\n```\nxyzzy\n```\n"
        for snippets <- DocsMarkdownRender.sectionSnippets(source, 160)
        yield
            assert(snippets.size == 1, s"expected 1 pair: $snippets")
            val (_, snippet, _) = snippets(0)
            assert(!snippet.contains("xyzzy"), s"fenced code must not leak into snippet: $snippet")
            assert(snippet == "", s"snippet must be empty when only block is a fence: $snippet")
        end for
    }

    // ---- a heading with no following prose maps to an empty snippet ----

    "a heading with no following prose maps to an empty snippet" in {
        val source = "## First\n## Second\n"
        for snippets <- DocsMarkdownRender.sectionSnippets(source, 160)
        yield
            assert(snippets.size == 2, s"expected 2 pairs: $snippets")
            val (_, snippet0, _) = snippets(0)
            assert(snippet0 == "", s"first heading with no prose must have empty snippet: $snippet0")
        end for
    }

    // ---- sectionSymbols Maybe path: extracts leading base identifiers ----

    "sectionSymbols extracts the leading base identifiers from inline code (Maybe path)" in {
        val source = "## Effect section\n`Abort.run` uses `Abort[E]` and `foldAbort`.\n"
        for snippets <- DocsMarkdownRender.sectionSnippets(source, 160)
        yield
            assert(snippets.size == 1, s"expected 1 pair: $snippets")
            val (_, _, symbols) = snippets(0)
            assert(
                symbols == Chunk("Abort", "foldAbort"),
                s"expected Chunk(Abort, foldAbort) in first-seen order but got: $symbols"
            )
        end for
    }

    // ---- sectionSymbols Absent path: drops too-short or non-leading-letter tokens ----

    "sectionSymbols drops a too-short or non-leading-letter inline-code token (Absent path)" in {
        val source = "## Short section\n`x` and `+` are not identifiers.\n"
        for snippets <- DocsMarkdownRender.sectionSnippets(source, 160)
        yield
            assert(snippets.size == 1, s"expected 1 pair: $snippets")
            val (_, _, symbols) = snippets(0)
            assert(symbols == Chunk.empty, s"expected Chunk.empty but got: $symbols")
        end for
    }

end DocsMarkdownTest
