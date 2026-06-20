// PUBLIC JVM-only build-time render/transpile/highlight object, consumed by WebsiteGenerator
package kyo.website

import kyo.*
import kyo.UI.*
import scala.collection.mutable
import scala.language.implicitConversions
import scala.meta.*
import scala.meta.tokens.Token as MetaToken

/** JVM-only Markdown transpiler and syntax highlighter for the kyo docs site.
  *
  * Converts a single README Markdown source string to a [[DocsMarkdownRender.Rendered]] value
  * containing a `kyo-ui` UI article subtree and a heading outline. The article is a real `UI`
  * tree (headings, paragraphs, lists, tables, fenced code with token highlighting, callout divs,
  * inline images and links) ready to embed directly into a page rendered by `UI.runRenderPage`.
  *
  * The grammar is bounded to a fixed construct set and is expressed with kyo-parse
  * `Parse[Char]` combinators. A thin line-oriented splitter groups raw lines into block segments;
  * the inline content of each block, and the block-level recognizers (headings, list markers, table
  * cells, fence info-strings, badge/link/image inline tokens) are genuine kyo-parse parsers run via
  * `Parse.runResult`.
  *
  * This object lives in `kyo-website/jvm/` so scalameta never reaches the JS link classpath.
  * The shared [[DocsMarkdown]] object holds only the cross-platform
  * [[DocsMarkdown.Heading]] carrier type.
  *
  * @see
  *   [[DocsMarkdown]] for the shared heading carrier
  * @see
  *   [[DocsMarkdownRender.Rendered]] for the build-time render output
  */
object DocsMarkdownRender:

    private def html(cs: Seq[UI]): Seq[UI.Ast.HtmlChildVal] =
        cs.map(n => UI.Ast.HtmlChildVal.lift(n))
    private def html(cs: kyo.Chunk[UI]): Seq[UI.Ast.HtmlChildVal] =
        cs.toSeq.map(n => UI.Ast.HtmlChildVal.lift(n))

    // A 24x24 line-icon frame: the shapes inherit `stroke = currentColor` from the `<g>` so the icon
    // follows the button's text color. Mirrors the SiteApp icon idiom.
    private def iconFrame(body: Svg.SvgElement*)(using Frame): UI =
        Svg.svg.viewBox(Svg.ViewBox(0, 0, 24, 24))(
            Svg.g
                .fill(Svg.Paint.None)
                .stroke(Svg.Paint.CurrentColor)
                .strokeWidth(2.0)
                .strokeLinecap(Svg.StrokeLinecap.Round)
                .strokeLinejoin(Svg.StrokeLinejoin.Round)(body*)
        )

    // The "copy" glyph: two overlapping documents. The front is a rounded rect; the back is an open
    // rounded-corner L so nothing draws inside the overlap.
    private def copyIcon(using Frame): UI =
        iconFrame(
            Svg.rect.x(8).y(8).width(14).height(14).rx(2).ry(2),
            Svg.path.d(
                Svg.PathData.from(4, 16)
                    .cubicTo(2.9, 16, 2, 15.1, 2, 14)
                    .vLineTo(4)
                    .cubicTo(2, 2.9, 2.9, 2, 4, 2)
                    .hLineTo(14)
                    .cubicTo(15.1, 2, 16, 2.9, 16, 4)
            )
        )

    // A checkmark, shown for the brief "copied" confirmation state.
    private def checkIcon(using Frame): UI =
        iconFrame(Svg.path.d(Svg.PathData.from(20, 6).lineTo(9, 17).lineTo(4, 12)))

    /** Wrap a fenced-code `pre` in a framed panel with an icon-only Copy button floating in the top-right
      * corner (no header bar). Build-time only (the highlighted `pre` is already a string by the time the
      * bundle consumes it). The CSS for `.code-block` / `.code-copy` lives in `WebsiteStyles.docsContent`.
      */
    private def codeBlock(pre: UI)(using Frame): UI =
        UI.div.cssClass("code-block")(
            // A plain icon <button> wired by one delegated bundle handler (no per-block JS): it copies the
            // panel's <pre> text and flips data-copied, which the CSS reads to swap the copy glyph for a
            // check. The CSS floats it absolutely in the panel's top-right corner; the pre's top padding
            // clears it so it never sits over the first line of code. The aria-label names the icon button.
            UI.button.cssClass("code-copy").aria("label", "Copy code to clipboard")(
                UI.div.cssClass("code-copy-idle")(copyIcon),
                UI.div.cssClass("code-copy-done")(checkIcon)
            ),
            pre
        )

    /** The article subtree, pre-rendered HTML string, and heading outline produced by [[transpile]]
      * and [[renderArticle]].
      *
      * `article` is a `UI` value ready to embed into a `UI.runRenderPage` document. `articleHtml`
      * is the pre-rendered HTML string of the article subtree; it is `""` (the build-path-internal
      * sentinel) when produced by [[transpile]] alone, and filled with the full rendered HTML by
      * [[renderArticle]]. `headings` is the ordered outline; each entry carries the same `slug`
      * that was set as the `id` attribute on the corresponding `UI.h1`..`UI.h4` element, so
      * `article` id attributes and `headings` slugs are always consistent. Duplicate
      * heading texts are disambiguated with a `-2` suffix on both sides.
      */
    final case class Rendered(article: UI, articleHtml: String, headings: Chunk[DocsMarkdown.Heading])
        derives CanEqual

    /** The repo location and git ref a README is rendered from, used to rewrite intra-repo file links
      * (demo sources, `CONTRIBUTING.md`, `LICENSE.txt`, ...) to absolute GitHub URLs. The docs site
      * hosts only the rendered README pages, never the source tree, so a README-relative file link like
      * `shared/src/test/scala/demo/ChatRoom.scala` would 404 if left same-origin (it would resolve under
      * the page route, e.g. `/latest/kyo-http/shared/...`); rewritten against the README's location it
      * points at the file on GitHub instead.
      *
      * `repoSubdir` is the README's directory relative to the repo root: `""` for the root README and
      * the manifesto, `"kyo-http"` for a module README. `ref` is the git ref the rendered content came
      * from: a release tag (`"v1.0.0-RC2"`) for a versioned build, or `"main"` when no tag is known.
      *
      * Only intra-repo FILE links are redirected to GitHub. Links that map to a docs route (a sibling
      * `README.md`, the repo-root `MANIFESTO.md`), in-page anchors, and external `http(s)` URLs are left
      * to their existing handling.
      */
    final case class LinkBase(repoSubdir: String, ref: String) derives CanEqual

    /** Transpile a single README Markdown source to a [[Rendered]] value.
      *
      * The effect row is `Sync` only (no `Abort`). Malformed input degrades rather than failing:
      * unknown inline constructs become plain `UI.text` (via kyo-parse `recoverWith`), unknown
      * block constructs become a `UI.p` carrying the verbatim line, and an empty source returns
      * `Rendered(UI.empty, "", Chunk.empty)`. The bounded grammar plus those total fall-throughs
      * are designed so no malformed-corpus path reaches an undefined evaluation; the row stays
      * `Sync`.
      *
      * @param source
      *   The Markdown text to transpile. May be empty.
      * @param linkBase
      *   The README's repo location and git ref. `Present` rewrites intra-repo file links to absolute
      *   GitHub URLs (see [[LinkBase]]); `Absent` (the default, used by heading-only transpiles and unit
      *   tests, where the article is discarded or links are not under test) leaves them same-origin.
      */
    def transpile(source: String, linkBase: Maybe[LinkBase] = Absent)(using Frame): Rendered < Sync =
        given Maybe[LinkBase] = linkBase
        Sync.defer {
            if source.isBlank then Rendered(UI.empty, "", Chunk.empty)
            else parseArticle(stripDoctest(source))
        }
    end transpile

    /** Render a `UI` article subtree to an HTML string by draining the first emission of
      * `UI.runRender`. This is the one-shot idiom: `.take(1).run.map(_.headMaybe.getOrElse(""))`
      * captures the initial (and for a static article, only) render and releases the underlying
      * channel resource.
      *
      * @param article
      *   The article `UI` value produced by [[transpile]]. Must be 100% static (no `Signal`/
      *   `Reactive` nodes), which is guaranteed by the bounded grammar.
      */
    def renderArticleHtml(article: UI)(using Frame): String < Async =
        UI.runRender(article).take(1).run.map(_.headMaybe.getOrElse(""))

    /** Transpile a Markdown source and render the article to HTML in one step.
      *
      * Calls [[transpile]] (`< Sync`) then [[renderArticleHtml]] (`< Async`), yielding a
      * [[Rendered]] whose `articleHtml` is fully populated. The effect row is `< Async` because
      * `renderArticleHtml` uses `UI.runRender` internally.
      *
      * @param source
      *   The Markdown text to transpile. May be empty.
      * @param linkBase
      *   The README's repo location and git ref, threaded to [[transpile]] (see [[LinkBase]]). `Absent`
      *   (the default) leaves intra-repo file links same-origin.
      */
    def renderArticle(source: String, linkBase: Maybe[LinkBase] = Absent)(using Frame): Rendered < Async =
        for
            t    <- transpile(source, linkBase)
            html <- renderArticleHtml(t.article)
        yield t.copy(articleHtml = html)

    /** Walk the block list of a README source and return one `(Heading, snippet)` pair per heading.
      *
      * Each heading accumulates the stripped prose text from the blocks that follow it up to the
      * next heading. Fenced code blocks and raw HTML embeds are excluded from the snippet. The
      * snippet is word-boundary truncated at `maxChars` with no ellipsis appended.
      *
      * The slug deduplication follows the same algorithm as `parseArticle` (local `slugCounts` map,
      * count 0 yields raw, count N >= 1 yields `$raw-${count+1}`), so the returned slugs match the
      * article anchor ids for the same source string.
      *
      * @param source
      *   The Markdown source. May be empty; returns `Chunk.empty` for blank or heading-less input.
      * @param maxChars
      *   Maximum snippet length in characters; truncates at the last word boundary at or before this
      *   limit.
      */
    private[website] def sectionSnippets(source: String, maxChars: Int)(using
        Frame
    ): Chunk[(DocsMarkdown.Heading, String, Chunk[String])] < Sync =
        Sync.defer {
            val cleaned    = stripDoctest(source)
            val blocks     = splitBlocks(cleaned)
            val out        = new mutable.ArrayBuffer[(DocsMarkdown.Heading, String, Chunk[String])]()
            val slugCounts = new mutable.HashMap[String, Int]()
            def uniqueSlug(raw: String): String =
                val count = slugCounts.getOrElse(raw, 0)
                slugCounts(raw) = count + 1
                if count == 0 then raw else s"$raw-${count + 1}"
            end uniqueSlug
            def makeSlug(text: String): String =
                val base = text.toLowerCase.map(c => if c.isLetterOrDigit then c else '-').mkString
                    .replaceAll("-+", "-").stripPrefix("-").stripSuffix("-")
                uniqueSlug(base)
            end makeSlug
            var i = 0
            while i < blocks.length do
                blocks(i) match
                    case Block.Heading(line) =>
                        val (level, text) = parseHeading(line)
                        val slug          = makeSlug(text)
                        val heading       = DocsMarkdown.Heading(level, stripInlineMarkdown(text), slug)
                        val prose         = new mutable.ArrayBuffer[String]()
                        // The raw (un-stripped) text of the heading + its blocks, kept so the inline-code
                        // spans survive for symbol extraction (stripInlineMarkdown drops the backticks).
                        val raw = new mutable.ArrayBuffer[String]()
                        raw += text
                        var j = i + 1
                        // Justified: isInstanceOf is the loop guard "stop at the next Heading"; the body
                        // already pattern-matches the non-Heading cases, so a match-inversion guard needs a
                        // break (Scala 3 has none) or a recursive restructure, strictly less readable for an
                        // identical, byte-pinned result.
                        while j < blocks.length && !blocks(j).isInstanceOf[Block.Heading] do
                            blocks(j) match
                                case Block.Paragraph(t)  => prose += stripInlineMarkdown(t); raw += t
                                case Block.Quote(c)      => prose += stripInlineMarkdown(c); raw += c
                                case Block.Unordered(ls) => prose += ls.map(stripInlineMarkdown).mkString(" "); raw ++= ls
                                case Block.Ordered(ls)   => prose += ls.map(stripInlineMarkdown).mkString(" "); raw ++= ls
                                case Block.Table(ls)     => prose += ls.map(stripInlineMarkdown).mkString(" "); raw ++= ls
                                case _                   => ()
                            end match
                            j += 1
                        end while
                        out += ((heading, snippetOf(prose.mkString(" "), maxChars), sectionSymbols(raw.toSeq)))
                    case _ => ()
                end match
                i += 1
            end while
            Chunk.from(out.toSeq)
        }
    end sectionSnippets

    private val InlineCodeRe = "`([^`]+)`".r

    /** The distinct base API identifiers an section references in inline code, in first-seen order.
      * Each inline `code` span contributes its leading identifier path head: `Abort.run` and `Abort[E]`
      * both yield `Abort`, `foldAbort` yields `foldAbort`. Used as the high-boost exact-match field so a
      * type-name query resolves to the section that features it.
      */
    private def sectionSymbols(rawBlocks: Seq[String]): Chunk[String] =
        val syms = scala.collection.mutable.LinkedHashSet.empty[String]
        rawBlocks.foreach { t =>
            InlineCodeRe.findAllMatchIn(t).foreach { m =>
                baseIdent(m.group(1)).foreach(syms += _)
            }
        }
        Chunk.from(syms.toSeq)
    end sectionSymbols

    private def baseIdent(code: String): Maybe[String] =
        val afterLead = code.dropWhile(c => !(c.isLetter || c == '_'))
        val id        = afterLead.takeWhile(c => c.isLetterOrDigit || c == '_')
        if id.length >= 2 && (id.head.isLetter || id.head == '_') then Present(id) else Absent
    end baseIdent

    /** Collapse whitespace and truncate `prose` at the last word boundary at or before `maxChars`.
      * Returns the trimmed prefix with no trailing ellipsis.
      */
    private def snippetOf(prose: String, maxChars: Int): String =
        val collapsed = prose.replaceAll("\\s+", " ").trim
        if collapsed.length <= maxChars then collapsed
        else
            val cut    = collapsed.substring(0, maxChars)
            val lastWs = cut.lastIndexOf(' ')
            (if lastWs > 0 then cut.substring(0, lastWs) else cut).trim
        end if
    end snippetOf

    // ---- doctest pre-pass (string cleanup, not parsing) ----

    /** Remove `<!-- doctest:setup ... -->` comment blocks (and the fenced scala block they wrap)
      * and normalize fenced info-strings to the first whitespace-delimited token.
      */
    private def stripDoctest(source: String): String =
        normalizeInfoStrings(removeDocTestBlocks(source))

    private def removeDocTestBlocks(source: String): String =
        val lines  = source.linesIterator.toArray
        val result = new mutable.ArrayBuffer[String]()
        var i      = 0
        while i < lines.length do
            val line = lines(i)
            val open = fenceLen(line.trim)
            if open > 0 then
                // Copy a fenced block verbatim, including any `<!-- doctest ... -->` lines inside it:
                // a ````markdown example documenting the DSL must keep its literal content, so the
                // top-level setup-comment stripping below must not reach inside a fence.
                result += line
                i += 1
                while i < lines.length && !isFenceClose(lines(i).trim, open) do
                    result += lines(i)
                    i += 1
                if i < lines.length then
                    result += lines(i)
                    i += 1
            else if line.trim.startsWith("<!--") && line.contains("doctest") then
                // A doctest comment block runs from this `<!--` line through the line that closes
                // the comment with `-->` (which may sit on its own line after a wrapped fenced
                // block). Consume the whole block, including the closing `-->` line, and preserve
                // any text that follows `-->` on that same line.
                if line.contains("-->") then
                    val after = line.substring(line.indexOf("-->") + 3)
                    if after.trim.nonEmpty then result += after
                    i += 1
                else
                    i += 1
                    while i < lines.length && !lines(i).contains("-->") do
                        i += 1
                    if i < lines.length then
                        val closing = lines(i)
                        val after   = closing.substring(closing.indexOf("-->") + 3)
                        if after.trim.nonEmpty then result += after
                        i += 1
                    end if
                end if
            else
                result += line
                i += 1
            end if
        end while
        result.mkString("\n")
    end removeDocTestBlocks

    private def normalizeInfoStrings(source: String): String =
        val lines  = source.linesIterator.toArray
        val result = new Array[String](lines.length)
        var i      = 0
        while i < lines.length do
            val line = lines(i)
            val m    = line.trim
            val open = fenceLen(m)
            if open > 0 then
                // Normalize the OPENING fence's info string to its first whitespace-delimited token,
                // keeping the fence length. Then copy the body and the closing fence verbatim, so a
                // longer fence wrapping shorter ones (a ````markdown example showing ```scala blocks)
                // keeps its inner fences and their info strings intact.
                val info          = m.drop(open).trim
                val firstToken    = info.takeWhile(c => !c.isWhitespace)
                val leadingSpaces = line.takeWhile(_ == ' ')
                result(i) = leadingSpaces + ("`" * open) + firstToken
                i += 1
                while i < lines.length && !isFenceClose(lines(i).trim, open) do
                    result(i) = lines(i)
                    i += 1
                if i < lines.length then
                    result(i) = lines(i)
                    i += 1
            else
                result(i) = line
                i += 1
            end if
        end while
        result.mkString("\n")
    end normalizeInfoStrings

    // ---- block grouping (thin line splitter, the permitted structuring step) ----

    /** A block segment grouped by the line splitter. Each segment carries the raw text that the
      * corresponding kyo-parse block parser consumes; the splitter only groups lines, it does not
      * interpret their inline content.
      */
    private enum Block:
        case Heading(line: String)
        case Fence(info: String, body: String)
        case Quote(content: String)
        case Table(lines: Chunk[String])
        case Unordered(lines: Chunk[String])
        case Ordered(lines: Chunk[String])
        case RawEmbed(snippet: String)
        case Paragraph(text: String)
    end Block

    private def isHeadingLine(t: String): Boolean =
        t.startsWith("# ") || t.startsWith("## ") || t.startsWith("### ") || t.startsWith("#### ")

    private def isOrderedItem(t: String): Boolean =
        t.length > 2 && t.head.isDigit && t.drop(1).startsWith(". ")

    // The number of leading backticks when `t` (already trimmed) opens a fenced code block (>= 3
    // backticks), else 0. CommonMark allows a fence of N >= 3 backticks, and a fence LONGER than the
    // minimum lets shorter fences appear verbatim in its body: that is how the kyo-doctest README
    // documents the DSL, wrapping literal ```scala examples in a ````markdown block. Every fence pass
    // must track this length, or a 4-backtick block closes on its first inner 3-backtick line.
    private def fenceLen(t: String): Int =
        val n = t.takeWhile(_ == '`').length
        if n >= 3 then n else 0

    // True when `t` (already trimmed) closes a fence opened with `open` backticks: at least `open`
    // backticks and nothing but whitespace after them (the CommonMark closing-fence rule).
    private def isFenceClose(t: String, open: Int): Boolean =
        val n = t.takeWhile(_ == '`').length
        n >= open && t.drop(n).trim.isEmpty

    /** Group cleaned source lines into [[Block]] segments. Leading HTML comments are skipped
      * Leading HTML comments are skipped. This is line-level structuring only; the content of each block is parsed
      * by the kyo-parse block/inline parsers.
      */
    private def splitBlocks(cleaned: String): Chunk[Block] =
        val lines  = cleaned.linesIterator.toArray
        val blocks = new mutable.ArrayBuffer[Block]()
        var i      = 0

        // Advance past an HTML comment that starts at `lines(i)`, consuming every line through and
        // including the one that closes the comment with `-->`. Returns the index of the first line
        // after the comment. Any text after `-->` on the closing line is appended to `blocks` as a
        // paragraph so it is not silently dropped.
        def skipComment(): Int =
            // First line that holds the closing `-->`.
            var j = i
            while j < lines.length && !lines(j).contains("-->") do j += 1
            if j < lines.length then
                val closing = lines(j)
                val after   = closing.substring(closing.indexOf("-->") + 3)
                if after.trim.nonEmpty then blocks += Block.Paragraph(after.trim)
                j + 1
            else j
            end if
        end skipComment

        // Skip leading HTML comments.
        while i < lines.length && lines(i).trim.startsWith("<!--") do
            i = skipComment()
        end while

        while i < lines.length do
            val line    = lines(i)
            val trimmed = line.trim

            if trimmed.isEmpty then
                i += 1
            else if isHeadingLine(trimmed) then
                blocks += Block.Heading(trimmed)
                i += 1
            else if fenceLen(trimmed) > 0 then
                val open = fenceLen(trimmed)
                val info = trimmed.drop(open).trim
                i += 1
                val codeLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && !isFenceClose(lines(i).trim, open) do
                    codeLines += lines(i)
                    i += 1
                if i < lines.length then i += 1
                blocks += Block.Fence(info, codeLines.mkString("\n"))
            else if trimmed.startsWith("> ") || trimmed == ">" then
                val bqLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && (lines(i).trim.startsWith("> ") || lines(i).trim == ">") do
                    val l = lines(i).trim
                    bqLines += (if l == ">" then "" else l.drop(2))
                    i += 1
                end while
                blocks += Block.Quote(bqLines.mkString("\n"))
            else if trimmed.startsWith("<!--") then
                i = skipComment()
            else if trimmed.startsWith("|") then
                val tableLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && lines(i).trim.startsWith("|") do
                    tableLines += lines(i).trim
                    i += 1
                blocks += Block.Table(Chunk.from(tableLines))
            else if trimmed.startsWith("- ") || line.startsWith("  - ") then
                val listLines = new mutable.ArrayBuffer[String]()
                while i < lines.length &&
                    (lines(i).trim.startsWith("- ") || lines(i).startsWith("  - "))
                do
                    listLines += lines(i)
                    i += 1
                end while
                blocks += Block.Unordered(Chunk.from(listLines))
            else if isOrderedItem(trimmed) then
                val listLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && isOrderedItem(lines(i).trim) do
                    listLines += lines(i).trim
                    i += 1
                blocks += Block.Ordered(Chunk.from(listLines))

            // Block-level raw HTML embed: lines starting with <img or <a only (root README corpus).
            // <img is single line. <a may wrap <img on subsequent lines; coalesce until the closing
            // </a>. The embed is wrapped in UI.p so the article body stays a real UI node and the
            // <a><img></a> nesting is preserved as one unit (decision 12).
            else if trimmed.startsWith("<img") || trimmed.startsWith("<a") then
                if trimmed.startsWith("<img") then
                    blocks += Block.RawEmbed(trimmed)
                    i += 1
                else
                    val embedLines = new mutable.ArrayBuffer[String]()
                    embedLines += trimmed
                    i += 1
                    while i < lines.length && !embedLines.last.trim.contains("</a>") do
                        embedLines += lines(i).trim
                        i += 1
                    end while
                    blocks += Block.RawEmbed(embedLines.mkString("\n"))
                end if
            else
                val paraLines = new mutable.ArrayBuffer[String]()
                while i < lines.length &&
                    lines(i).trim.nonEmpty &&
                    !isHeadingLine(lines(i).trim) &&
                    !lines(i).trim.startsWith("```") &&
                    !lines(i).trim.startsWith("> ") &&
                    !lines(i).trim.startsWith("- ") &&
                    !lines(i).startsWith("  - ") &&
                    !lines(i).trim.startsWith("|") &&
                    !lines(i).trim.startsWith("<!--") &&
                    !isOrderedItem(lines(i).trim)
                do
                    paraLines += lines(i).trim
                    i += 1
                end while
                blocks += Block.Paragraph(paraLines.mkString(" "))
            end if
        end while

        Chunk.from(blocks)
    end splitBlocks

    // ---- article assembly ----

    /** Parse the pre-processed Markdown string into a [[Rendered]] value.
      *
      * Lines are grouped into [[Block]] segments by [[splitBlocks]]; each segment's content is then
      * parsed by a kyo-parse `Parse[Char]` parser. Heading slugs are tracked in a mutable map local
      * to this call; duplicate slugs receive `-2` (then `-3`, etc.) suffixes.
      */
    private def parseArticle(cleaned: String)(using Frame, Maybe[LinkBase]): Rendered =
        val blocks     = splitBlocks(cleaned)
        val uiBlocks   = new mutable.ArrayBuffer[UI]()
        val headings   = new mutable.ArrayBuffer[DocsMarkdown.Heading]()
        val slugCounts = new mutable.HashMap[String, Int]()

        def uniqueSlug(raw: String): String =
            val count = slugCounts.getOrElse(raw, 0)
            slugCounts(raw) = count + 1
            if count == 0 then raw else s"$raw-${count + 1}"
        end uniqueSlug

        def makeSlug(text: String): String =
            val base = text.toLowerCase
                .map(c => if c.isLetterOrDigit then c else '-')
                .mkString
                .replaceAll("-+", "-")
                .stripPrefix("-")
                .stripSuffix("-")
            uniqueSlug(base)
        end makeSlug

        blocks.foreach {
            case Block.Heading(line) =>
                val (level, text) = parseHeading(line)
                val slug          = makeSlug(text)
                val inlineNodes   = parseInline(text)
                // The parser returns Chunk[UI]; convert to Seq[HtmlChildVal] for the spread operator.
                val inlineChildren = html(inlineNodes)
                val heading: UI = level match
                    case 1 => UI.h1.id(slug)(inlineChildren*)
                    case 2 => UI.h2.id(slug)(inlineChildren*)
                    case 3 => UI.h3.id(slug)(inlineChildren*)
                    case _ => UI.h4.id(slug)(inlineChildren*)
                uiBlocks += heading
                // The in-page heading keeps the rich inline render (real <code>, bold, italic); the
                // outline entry stores the plain-text form so the TOC and search show clean labels
                // without literal backticks/asterisks. The slug is derived from the raw text so
                // the heading `id` and the TOC anchor stay byte-identical to the rendered HTML.
                headings += DocsMarkdown.Heading(level, stripInlineMarkdown(text), slug)

            case Block.Fence(info, body) =>
                uiBlocks += codeBlock(UI.pre(UI.code(highlight(info, body))))

            case Block.Quote(content) =>
                uiBlocks += parseBlockquote(content)

            case Block.Table(lines) =>
                uiBlocks += parseTable(lines)

            case Block.Unordered(lines) =>
                uiBlocks += parseUnorderedList(lines)

            case Block.Ordered(lines) =>
                val items = lines.map(l => UI.li(html(parseInline(parseOrderedItem(l)))*))
                uiBlocks += UI.ol(html(items)*)

            case Block.RawEmbed(snippet) =>
                uiBlocks += UI.p(UI.rawHtml(snippet))

            case Block.Paragraph(text) =>
                uiBlocks += UI.p(html(parseInline(text))*)
        }

        val article: UI =
            if uiBlocks.isEmpty then UI.empty
            else UI.fragment(uiBlocks.toSeq*)
        Rendered(article, "", Chunk.from(headings.toSeq))
    end parseArticle

    // ---- block-level kyo-parse parsers ----

    /** Strip inline Markdown markers from a heading's raw text for plain-text display in the TOC and
      * search index. Removes inline-code backticks, bold/italic asterisks, and unwraps
      * `[text](url)` links to their visible `text`. The in-page heading still renders the real inline
      * markup; only the outline/search label is flattened. Idempotent and total.
      */
    private[website] def stripInlineMarkdown(raw: String): String =
        val sb  = new mutable.StringBuilder()
        val s   = raw
        var i   = 0
        val len = s.length
        while i < len do
            val c = s.charAt(i)
            if c == '`' then
                // Skip the backtick; the inner text is kept verbatim.
                i += 1
            else if c == '*' then
                // Skip one or two asterisks (italic or bold opener/closer).
                if i + 1 < len && s.charAt(i + 1) == '*' then i += 2
                else i += 1
            else if c == '[' then
                // `[text](url)` -> keep `text`, drop the bracket and the `(url)` target. If the
                // construct is not a well-formed link, keep the `[` literally.
                val closeBracket = s.indexOf(']', i + 1)
                if closeBracket >= 0 && closeBracket + 1 < len && s.charAt(closeBracket + 1) == '(' then
                    val closeParen = s.indexOf(')', closeBracket + 2)
                    if closeParen >= 0 then
                        sb.append(stripInlineMarkdown(s.substring(i + 1, closeBracket)))
                        i = closeParen + 1
                    else
                        sb.append(c)
                        i += 1
                    end if
                else
                    sb.append(c)
                    i += 1
                end if
            else
                sb.append(c)
                i += 1
            end if
        end while
        sb.toString
    end stripInlineMarkdown

    /** Parse an ATX heading line via a `Parse[Char]` grammar: 1..4 `#`, a space, then the rest of
      * the line as the heading text. Returns the level and the trimmed text.
      */
    private def parseHeading(line: String)(using Frame): (Int, String) =
        val parser: (Int, String) < Parse[Char] =
            for
                hashes <- Parse.readWhile[Char](_ == '#')
                _      <- Parse.literal(' ')
                rest   <- Parse.readWhile[Char](_ => true)
            yield (hashes.length, rest.mkString.trim)
        runParser(line)(parser).getOrElse {
            // Total fall-through: a leading `#` always matches one of the H1..H4 openers.
            (1, line.dropWhile(_ == '#').trim)
        }
    end parseHeading

    /** Strip the ordered-list marker (`N. `) from a line via a `Parse[Char]` grammar, returning the
      * item text.
      */
    private def parseOrderedItem(line: String)(using Frame): String =
        val parser: String < Parse[Char] =
            for
                _    <- Parse.readWhile[Char](_.isDigit)
                _    <- Parse.literal('.')
                _    <- Parse.literal(' ')
                rest <- Parse.readWhile[Char](_ => true)
            yield rest.mkString
        runParser(line)(parser).getOrElse(line)
    end parseOrderedItem

    /** Parse a GFM pipe-table row into trimmed cell strings via a `Parse[Char]` grammar. A GFM row is
      * `| c1 | c2 |`: a leading `|` followed by a run of `cell` then `|` pairs. A cell is a run of
      * characters up to the next pipe, with a `\|` escape contributing a literal `|` to the cell
      * (addressing the naive-split limitation: an escaped pipe stays inside the cell rather
      * than splitting it).
      */
    private def parseRowCells(line: String)(using Frame): Chunk[String] =
        val cellChar: String < Parse[Char] =
            Parse.firstOf(
                Parse.literal("\\|").andThen("|"),
                Parse.anyIf[Char](c => c != '|')(c => s"Unexpected $c").map(_.toString)
            )
        val cell: String < Parse[Char] =
            for
                chars <- Parse.repeat(cellChar)
                _     <- Parse.literal('|')
            yield chars.mkString.trim
        val row: Chunk[String] < Parse[Char] =
            for
                _     <- Parse.literal('|')
                cells <- Parse.repeat(cell)
            yield Chunk.from(cells)
        runParser(line)(row).getOrElse {
            // Total fall-through for a row that is not pipe-delimited.
            Chunk(line.stripPrefix("|").stripSuffix("|").trim)
        }
    end parseRowCells

    /** Parse a GFM pipe table. The first row is the header; the second is the separator; remaining
      * rows are body rows. Cell content is re-parsed with [[parseInline]].
      */
    private def parseTable(tableLines: Chunk[String])(using Frame, Maybe[LinkBase]): UI =
        if tableLines.length < 2 then
            // Malformed table (missing separator): degrade to paragraph.
            UI.p(Ast.Text(tableLines.headOption.getOrElse("")))
        else
            val headerCells = parseRowCells(tableLines.head)
            val bodyRows    = if tableLines.length > 2 then tableLines.drop(2) else Chunk.empty[String]
            val headerTr    = UI.tr(html(headerCells.map(cell => UI.th(html(parseInline(cell))*)))*)
            val bodyTrs = bodyRows.map { row =>
                val cells = parseRowCells(row)
                UI.tr(html(cells.map(cell => UI.td(html(parseInline(cell))*)))*)
            }
            UI.table(html(headerTr +: bodyTrs)*)
        end if
    end parseTable

    /** Parse an unordered list from its grouped lines, handling two-space sub-indented items. The
      * `- ` / `  - ` markers are recognized with a `Parse[Char]` parser per line.
      */
    private def parseUnorderedList(lines: Chunk[String])(using Frame, Maybe[LinkBase]): UI =
        val arr   = lines.toArray
        val items = new mutable.ArrayBuffer[Ast.Li]()
        var i     = 0
        while i < arr.length do
            val line = arr(i)
            if line.startsWith("  - ") then
                // Orphan sub-item with no preceding top-level item; treat as a top-level item.
                items += UI.li(html(parseInline(parseListItem(line.trim)))*)
                i += 1
            else
                val text = parseListItem(line.trim)
                i += 1
                val subItems = new mutable.ArrayBuffer[Ast.Li]()
                while i < arr.length && arr(i).startsWith("  - ") do
                    subItems += UI.li(html(parseInline(parseListItem(arr(i).trim)))*)
                    i += 1
                end while
                if subItems.isEmpty then items += UI.li(html(parseInline(text))*)
                else items += UI.li(html(parseInline(text) :+ UI.ul(html(subItems.toSeq)*))*)
            end if
        end while
        UI.ul(html(items.toSeq)*)
    end parseUnorderedList

    /** Strip the `- ` marker from a (trimmed) unordered-list line via a `Parse[Char]` grammar. */
    private def parseListItem(trimmed: String)(using Frame): String =
        val parser: String < Parse[Char] =
            for
                _    <- Parse.literal("- ")
                rest <- Parse.readWhile[Char](_ => true)
            yield rest.mkString
        runParser(trimmed)(parser).getOrElse(trimmed.stripPrefix("- "))
    end parseListItem

    /** Parse a blockquote run into a callout/blockquote div. The leading `> ` is already stripped by
      * the splitter. A `> **Note:**` opener becomes a note callout, `> **Caution:**` a caution
      * callout, everything else a generic blockquote.
      */
    private def parseBlockquote(content: String)(using Frame, Maybe[LinkBase]): UI =
        val firstNonEmpty = content.linesIterator.find(_.trim.nonEmpty).getOrElse("").trim
        val bqBlocks      = parseBlockquoteContent(content)
        if firstNonEmpty.startsWith("**Note:**") then UI.div.cssClass("callout callout-note")(html(bqBlocks)*)
        else if firstNonEmpty.startsWith("**Caution:**") then UI.div.cssClass("callout callout-caution")(html(bqBlocks)*)
        else UI.div.cssClass("blockquote")(html(bqBlocks)*)
    end parseBlockquote

    private def parseBlockquoteContent(content: String)(using Frame, Maybe[LinkBase]): Chunk[UI] =
        val lines  = content.linesIterator.toArray
        val result = new mutable.ArrayBuffer[UI]()
        var i      = 0
        while i < lines.length do
            val trimmed = lines(i).trim
            if trimmed.isEmpty then
                i += 1
            else if fenceLen(trimmed) > 0 then
                val open = fenceLen(trimmed)
                val info = trimmed.drop(open).trim
                i += 1
                val codeLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && !isFenceClose(lines(i).trim, open) do
                    codeLines += lines(i)
                    i += 1
                if i < lines.length then i += 1
                result += codeBlock(UI.pre(UI.code(html(Chunk(highlight(info, codeLines.mkString("\n"))))*)))
            else
                val paraLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && lines(i).trim.nonEmpty && !lines(i).trim.startsWith("```") do
                    paraLines += lines(i).trim
                    i += 1
                result += UI.p(html(parseInline(paraLines.mkString(" ")))*)
            end if
        end while
        Chunk.from(result)
    end parseBlockquoteContent

    // ---- inline kyo-parse grammar ----

    /** Convert a raw URL string to a `Href`. Treats `#id` as Fragment and `http(s)://...` as External.
      * A docs-route link (a sibling `README.md` or the repo-root `MANIFESTO.md`) is rewritten to the
      * same-origin Path the docs site serves (see [[rewriteReadmePath]]). Any other intra-repo link is
      * a file the site does not host: with a [[LinkBase]] in scope (`Present`) it is rewritten to an
      * absolute GitHub URL so it resolves to the source on GitHub (see [[gitHubHref]]); with no base
      * (`Absent`, the heading-only / unit-test default) it stays a same-origin Path, preserving the
      * prior behavior.
      */
    private def toHref(url: String)(using base: Maybe[LinkBase]): Href =
        if url.startsWith("#") then Href.Fragment(url.drop(1))
        else if url.startsWith("https://") then Href.External("https", url.drop(6))
        else if url.startsWith("http://") then Href.External("http", url.drop(5))
        else if isDocRouteLink(url) then Href.Path(rewriteReadmePath(url))
        else
            base match
                case Present(b) => gitHubHref(url, b)
                case Absent     => Href.Path(url)
        end if
    end toHref

    /** True when `url` targets a documentation route the site actually serves: a sibling `README.md`
      * (mapped to its directory route) or the repo-root `MANIFESTO.md` (mapped to the manifesto page).
      * These are the links [[rewriteReadmePath]] turns into a same-origin Path; every other intra-repo
      * link is a source file the site does not host. A `#fragment` is ignored for the test.
      */
    private def isDocRouteLink(url: String): Boolean =
        val path = url.takeWhile(_ != '#')
        path == "README.md" || path.endsWith("/README.md") || path == "MANIFESTO.md"
    end isDocRouteLink

    /** Rewrite an intra-repo link that targets a `README.md` file to the directory route it lives in.
      *
      *   - `../kyo-prelude/README.md` -> `../kyo-prelude/` (strip the filename, keep the trailing slash)
      *   - `README.md` -> `./` (the current directory)
      *   - `README.md#anchor` (or `dir/README.md#anchor`) -> `#anchor` / `dir/#anchor` (keep the fragment)
      *   - `MANIFESTO.md` -> `manifesto/` (the manifesto docs page: the generator appends the repo-root
      *     MANIFESTO.md as the final docs page at `<prefix>/manifesto/`, so the README's repo-relative
      *     link resolves to that page on the site instead of 404ing on the raw `.md` file)
      *
      * Any other path is returned unchanged.
      */
    private def rewriteReadmePath(url: String): String =
        val hashIdx  = url.indexOf('#')
        val path     = if hashIdx >= 0 then url.substring(0, hashIdx) else url
        val fragment = if hashIdx >= 0 then url.substring(hashIdx) else ""
        if path == "MANIFESTO.md" then "manifesto/" + fragment
        else if path == "README.md" then
            if fragment.isEmpty then "./" else fragment
        else if path.endsWith("/README.md") then
            path.dropRight("README.md".length) + fragment
        else url
        end if
    end rewriteReadmePath

    // The kyo repository, as a scheme-relative authority+path so `Href.External("https", _)` renders
    // `https://github.com/getkyo/kyo/...` (the External case prints `scheme:value`, see UI.Href).
    private val GitHubRepo = "//github.com/getkyo/kyo"

    /** Rewrite an intra-repo file link to an absolute GitHub URL against `base`. The README-relative
      * `target` is resolved to a repo-root-relative path (a leading `./` dropped, `../` segments popped
      * against `base.repoSubdir`), then joined as `https://github.com/getkyo/kyo/<kind>/<ref>/<path>`
      * where `<kind>` is `blob` for a file and `tree` for a directory. A `#fragment` (e.g. a GitHub line
      * anchor) is preserved. Examples, with `base = LinkBase("kyo-http", "v1.0.0-RC2")`:
      *
      *   - `shared/src/test/scala/demo/ChatRoom.scala`
      *     -> `.../blob/v1.0.0-RC2/kyo-http/shared/src/test/scala/demo/ChatRoom.scala`
      *   - `shared/src/test/scala/demo` (a directory)
      *     -> `.../tree/v1.0.0-RC2/kyo-http/shared/src/test/scala/demo`
      *
      * and with `base = LinkBase("kyo-case-app", "main")`, a `../` link escapes the module directory:
      *
      *   - `../kyo-core/shared/src/main/scala/kyo/internal/KyoAppRunner.scala`
      *     -> `.../blob/main/kyo-core/shared/src/main/scala/kyo/internal/KyoAppRunner.scala`
      */
    private def gitHubHref(target: String, base: LinkBase): Href =
        val hashIdx  = target.indexOf('#')
        val rawPath  = if hashIdx >= 0 then target.substring(0, hashIdx) else target
        val fragment = if hashIdx >= 0 then target.substring(hashIdx) else ""
        val repoPath = resolveRepoPath(base.repoSubdir, rawPath)
        val kind     = if isDirectoryTarget(rawPath, repoPath) then "tree" else "blob"
        Href.External("https", s"$GitHubRepo/$kind/${base.ref}/$repoPath$fragment")
    end gitHubHref

    /** Resolve a README-relative link `target` to a repo-root-relative path against `repoSubdir` (the
      * README's own directory). A leading `./` is dropped, `.` and empty segments are skipped, and each
      * `..` pops one segment (never above the repo root). `repoSubdir = ""` (the root README) resolves
      * the target as-is.
      */
    private def resolveRepoPath(repoSubdir: String, target: String): String =
        val baseSegs   = repoSubdir.split("/").iterator.filter(_.nonEmpty).toList
        val targetSegs = target.stripPrefix("./").split("/").toList
        val resolved = targetSegs.foldLeft(baseSegs.reverse) { (stack, seg) =>
            seg match
                case "" | "." => stack
                case ".."     => if stack.isEmpty then stack else stack.tail
                case s        => s :: stack
        }
        resolved.reverse.mkString("/")
    end resolveRepoPath

    /** Whether the link targets a directory (`tree` on GitHub) rather than a file (`blob`): a trailing
      * slash on the raw target, or a final resolved-path segment with no `.` extension (`shared/.../demo`,
      * `.../examples/ledger`). The kyo README corpus uses an extension on every file link and a bare name
      * on every directory link, so this split is exact for it; a misclassified target still resolves,
      * since GitHub redirects `blob` <-> `tree` for the other kind.
      */
    private def isDirectoryTarget(rawTarget: String, repoPath: String): Boolean =
        rawTarget.endsWith("/") || !repoPath.split("/").lastOption.getOrElse("").contains(".")
    end isDirectoryTarget

    /** Parse inline Markdown to a sequence of UI nodes with a kyo-parse `Parse[Char]` grammar.
      *
      * Handles, in PEG ordered-choice precedence: linked badges (`[![alt](img)](link)`), badge
      * images (`![alt](url)`), links (`[text](url)`), bold (`**text**`), italic (`*text*`), inline
      * code (`` `text` ``), inline raw HTML (`<...>`), and plain text runs. Any inline token that
      * does not parse degrades to a single literal character via `recoverWith` +
      * `RecoverStrategy`, so the row never aborts.
      */
    private def parseInline(text: String)(using Frame, Maybe[LinkBase]): Chunk[UI] =
        if text.isEmpty then Chunk(Ast.Text(""))
        else runParser(text)(inlineNodes).getOrElse(Chunk(Ast.Text(text)))

    /** One result of the inline grammar: either a single literal character (a degrade unit, coalesced
      * into `Ast.Text` runs) or a fully parsed inline `UI` node.
      */
    private enum Token:
        case Lit(char: Char)
        case Node(ui: UI)

    /** The inline grammar: repeat a single inline token until end of input, then coalesce adjacent
      * literal characters into `Ast.Text` runs.
      */
    private def inlineNodes(using Frame, Maybe[LinkBase]): Chunk[UI] < Parse[Char] =
        Parse.repeat(inlineToken).map(tokens => coalesceText(Chunk.from(tokens)))

    /** A single inline token, as a `Token`: `Lit(char)` is one literal character (a degrade unit);
      * `Node(ui)` is a parsed inline node. Unknown markup degrades to one literal character via the
      * `firstOf` last branch, and any fatal failure is recovered to one literal character via
      * `recoverWith` + `RecoverStrategy`, so the inline row never aborts.
      */
    private def inlineToken(using Frame, Maybe[LinkBase]): Token < Parse[Char] =
        def node(p: UI < Parse[Char]): Token < Parse[Char] = p.map(Token.Node(_))
        val literalChar: Token < Parse[Char]               = Parse.any[Char].map(Token.Lit(_))
        Parse.recoverWith(
            Parse.firstOf(
                node(linkedBadge),
                node(badgeImage),
                node(link),
                node(bold),
                node(italic),
                node(inlineCode),
                node(inlineHtml),
                literalChar
            ),
            RecoverStrategy.viaParser[Char, Token](Parse.any[Char].map(Token.Lit(_)))
        )
    end inlineToken

    /** Coalesce a token stream of literal-char runs and parsed nodes into a UI sequence, merging
      * adjacent literal characters into single `Ast.Text` leaves.
      */
    private def coalesceText(tokens: Chunk[Token])(using Frame): Chunk[UI] =
        val out = new mutable.ArrayBuffer[UI]()
        val buf = new mutable.StringBuilder()
        def flush(): Unit =
            if buf.nonEmpty then
                out += Ast.Text(buf.toString)
                buf.clear()
        tokens.foreach {
            case Token.Lit(c)   => buf.append(c)
            case Token.Node(ui) => flush(); out += ui
        }
        flush()
        Chunk.from(out)
    end coalesceText

    /** Read characters until (but not including) the next occurrence of `stop`, as a String. */
    private def readUntilChar(stop: Char)(using Frame): String < Parse[Char] =
        Parse.readWhile[Char](_ != stop).map(_.mkString)

    /** Read characters until (but not including) the next occurrence of `stop`, as a String. */
    private def readUntilString(stop: String)(using Frame): String < Parse[Char] =
        // stop is one or two characters in the inline grammar; read char-by-char with a not-lookahead.
        Parse.repeat(
            for
                _ <- Parse.not(Parse.literal(stop))
                c <- Parse.any[Char]
            yield c
        ).map(_.mkString)

    /** `[![alt](img)](link)` -> `UI.a.href(link)(UI.img(img, alt))`. */
    private def linkedBadge(using Frame, Maybe[LinkBase]): UI < Parse[Char] =
        for
            _   <- Parse.literal("[![")
            alt <- readUntilChar(']')
            _   <- Parse.literal("](")
            img <- readUntilChar(')')
            _   <- Parse.literal(")](")
            lnk <- readUntilChar(')')
            _   <- Parse.literal(')')
        yield UI.a.href(toHref(lnk))(UI.img(ImgSrc.Path(img), alt))

    /** `![alt](url)` -> `UI.img(url, alt)`. */
    private def badgeImage(using Frame): UI < Parse[Char] =
        for
            _   <- Parse.literal("![")
            alt <- readUntilChar(']')
            _   <- Parse.literal("](")
            url <- readUntilChar(')')
            _   <- Parse.literal(')')
        yield UI.img(ImgSrc.Path(url), alt)

    /** `[text](url)` -> `UI.a.href(url)(parseInline(text)*)`. */
    private def link(using Frame, Maybe[LinkBase]): UI < Parse[Char] =
        for
            _    <- Parse.literal('[')
            body <- readUntilString("](")
            _    <- Parse.literal("](")
            url  <- readUntilChar(')')
            _    <- Parse.literal(')')
        yield UI.a.href(toHref(url))(html(parseInline(body))*)

    /** `**text**` -> a bold `md-strong` span. */
    private def bold(using Frame): UI < Parse[Char] =
        for
            _     <- Parse.literal("**")
            inner <- nonEmptyUntilString("**")
            _     <- Parse.literal("**")
        yield UI.span.cssClass("md-strong")(Ast.Text(inner))

    /** `*text*` -> an italic span. */
    private def italic(using Frame): UI < Parse[Char] =
        for
            _     <- Parse.literal('*')
            inner <- nonEmptyUntilChar('*')
            _     <- Parse.literal('*')
        yield UI.span.style(Style.italic)(Ast.Text(inner))

    /** `` `text` `` -> an inline `UI.code` node. */
    // A CommonMark inline code span: a run of N backticks, then content up to the next run of N
    // backticks. The delimiter length matters: a span opened with N backticks lets runs of OTHER
    // lengths appear verbatim inside it, which is how prose shows a literal ```scala by writing it as
    // ```` ```scala ````. A single backtick (N=1) is the common case.
    //
    // An UNMATCHED opening run (no closing run of the same length) is LITERAL text, consumed here so
    // parsing resumes AFTER the run. This is the CommonMark rule and the load-bearing detail: without
    // it, a bare ```scala in prose would leave the failed branch to backtrack one char and re-enter on
    // the next backtick, and the leftover single backtick would open a span that swallows the prose up
    // to the next real inline-code backtick (eating chip delimiters and word spacing).
    private def inlineCode(using Frame): UI < Parse[Char] =
        for
            ticks <- Parse.readWhile[Char](_ == '`').map(_.mkString).map { run =>
                if run.isEmpty then Parse.fail[Char]("no opening backtick run") else run
            }
            node <- Parse.firstOf(
                for
                    inner <- readUntilString(ticks)
                    _     <- Parse.literal(ticks)
                yield UI.code(Ast.Text(stripInlineCodeSpan(inner))),
                // No matching close: the opening run is literal backticks (already consumed above).
                (Ast.Text(ticks): UI)
            )
        yield node

    // CommonMark: when a code span's content has a non-space character and begins AND ends with a
    // space, strip exactly one space from each end (so ```` ```scala ```` shows as ```scala, not as
    // a chip padded with stray spaces). Content that is all spaces, or padded on one side only, is
    // left untouched.
    private def stripInlineCodeSpan(s: String): String =
        if s.length >= 2 && s.startsWith(" ") && s.endsWith(" ") && s.exists(_ != ' ') then s.substring(1, s.length - 1)
        else s

    /** `<...>` -> a verbatim `UI.rawHtml` leaf for inline HTML snippets. */
    private def inlineHtml(using Frame): UI < Parse[Char] =
        for
            _     <- Parse.literal('<')
            inner <- nonEmptyUntilChar('>')
            _     <- Parse.literal('>')
        yield UI.rawHtml("<" + inner + ">")

    /** Read at least one character up to (not including) `stop`; drops the branch if the run is empty
      * so an unmatched opener falls through to a literal character.
      */
    private def nonEmptyUntilChar(stop: Char)(using Frame): String < Parse[Char] =
        Parse.readWhile[Char](_ != stop).map(_.mkString).map { run =>
            if run.isEmpty then Parse.fail[Char]("empty span")
            else run
        }

    /** Read at least one character up to (not including) the `stop` string; drops the branch on an
      * empty run so an unmatched opener falls through to a literal character.
      */
    private def nonEmptyUntilString(stop: String)(using Frame): String < Parse[Char] =
        readUntilString(stop).map { run =>
            if run.isEmpty then Parse.fail[Char]("empty span")
            else run
        }

    // ---- kyo-parse runner ----

    /** Run a `Parse[Char]` parser over `input`, requiring it to consume the entire input, and return
      * its result as a `Maybe`. A failed or incomplete parse yields `Absent`, which the callers turn
      * into their total fall-through. The `Parse[Char]` effect is fully discharged here (effect row
      * `Any`), so the pure result is evaluated synchronously.
      */
    private def runParser[A](input: String)(parser: A < Parse[Char])(using Frame): Maybe[A] =
        Parse.runResult(input)(Parse.entireInput(parser)).map(_.out).eval

    // ---- syntax-highlighting tokenizer ----

    /** Token kinds for scalameta-backed Scala highlighting. Each case maps to one CSS class in
      * `WebsiteStyles.docsTokens`. The closed set is fixed; the `cssClass` accessor is the single
      * source of truth for the class name string.
      */
    enum TokenKind derives CanEqual:
        case Keyword
        case Str
        case Comment
        case Number
        case Type
        case Interpolation
        case Annotation
        case Operator
        def cssClass: String = this match
            case Keyword       => "tok-keyword"
            case Str           => "tok-string"
            case Comment       => "tok-comment"
            case Number        => "tok-number"
            case Type          => "tok-type"
            case Interpolation => "tok-interpolation"
            case Annotation    => "tok-annotation"
            case Operator      => "tok-operator"
    end TokenKind

    /** Shared scala/sbt/bash tokenizer that emits `.tok-*` UI.span runs for keyword/string/comment
      * tokens and plain `UI.text` for the rest. The classes are styled by `WebsiteStyles.sheet`.
      *
      * For other/bare fences, returns a plain `Ast.Text(body)` leaf with no token spans.
      */
    private def highlight(lang: String, body: String)(using Frame): UI =
        lang match
            case "scala" | "sbt" => highlightScala(body)
            case "bash" | "sh"   => tokenizeBash(body)
            case _               => Ast.Text(body)
    end highlight

    /** Tokenize a Scala/SBT snippet using scalameta and emit `tok-*` UI.span nodes. On a lex error
      * (`Tokenized.Error`) the whole body degrades to a single `Ast.Text` leaf. All token
      * `.text` values are emitted (including trivia), so the output is byte-preserving.
      *
      * A `foldLeft` threads `prev` (the last non-trivia token) as accumulator state for the one-token
      * lookback in `classify`. No mutable state escapes the method.
      *
      * @param body
      *   The raw source text of the fenced code block.
      */
    private def highlightScala(body: String)(using Frame): UI =
        dialects.Scala3(body).tokenize match
            case success: Tokenized.Success =>
                val (spans, _) = success.tokens.foldLeft((Chunk.empty[UI], Maybe.empty[MetaToken])) {
                    case ((acc, prev), tok) =>
                        val span = classify(tok, prev) match
                            case Present(kind) => UI.span.cssClass(kind.cssClass)(Ast.Text(tok.text))
                            case Absent        => Ast.Text(tok.text)
                        val nextPrev = if isTrivia(tok) then prev else Present(tok)
                        (acc.append(span), nextPrev)
                }
                UI.fragment(spans.toSeq*)
            // A lex error, or any non-success tokenizer outcome, degrades the whole body to a text leaf.
            case _ => Ast.Text(body)
    end highlightScala

    // Returns true for whitespace and BOF/EOF tokens; these are emitted as plain Ast.Text
    // and do NOT update the prev lookback so that `@ main` (space between them) still
    // classifies `main` as Annotation.
    private def isTrivia(tok: MetaToken): Boolean = tok match
        case _: MetaToken.HSpace | _: MetaToken.Space | _: MetaToken.Tab | _: MetaToken.CR |
            _: MetaToken.LF | _: MetaToken.FF | _: MetaToken.EOL |
            _: MetaToken.BOF | _: MetaToken.EOF => true
        case _ => false

    // Classifier: maps a scalameta MetaToken to a TokenKind, or Absent for structural/plain tokens.
    // Arms are ordered from most-specific to least-specific; the At-annotation arm fires before
    // the Type heuristic so `@Deprecated` reads as Annotation, not Type.
    private def classify(tok: MetaToken, prev: Maybe[MetaToken]): Maybe[TokenKind] = tok match
        case _: MetaToken.Comment => Present(TokenKind.Comment)
        case _: MetaToken.Constant.Int | _: MetaToken.Constant.Long |
            _: MetaToken.Constant.Float | _: MetaToken.Constant.Double =>
            Present(TokenKind.Number)
        case _: MetaToken.Constant.String | _: MetaToken.Constant.Char |
            _: MetaToken.Constant.Symbol =>
            Present(TokenKind.Str)
        case _: MetaToken.Interpolation.Id => Present(TokenKind.Interpolation)
        case _: MetaToken.Interpolation.Start | _: MetaToken.Interpolation.Part |
            _: MetaToken.Interpolation.End => Present(TokenKind.Str)
        case _: MetaToken.Interpolation.SpliceStart | _: MetaToken.Interpolation.SpliceEnd =>
            Present(TokenKind.Operator)
        case _: MetaToken.At => Present(TokenKind.Annotation)
        // KwTrue, KwFalse, and KwNull extend BooleanConstant/Literal, not Token$Keyword in scalameta
        // 4.13.4, so they do not match the Keyword arm below. Place this arm first.
        case _: MetaToken.KwTrue | _: MetaToken.KwFalse | _: MetaToken.KwNull =>
            Present(TokenKind.Keyword)
        case _: MetaToken.Keyword => Present(TokenKind.Keyword)
        case _: MetaToken.Colon | _: MetaToken.Equals | _: MetaToken.RightArrow |
            _: MetaToken.FunctionArrow | _: MetaToken.Underscore | _: MetaToken.Hash |
            _: MetaToken.Subtype | _: MetaToken.Supertype =>
            Present(TokenKind.Operator)
        case id: MetaToken.Ident if isOperatorIdent(id.text) => Present(TokenKind.Operator)
        case _: MetaToken.Ident if prev.exists { case _: MetaToken.At => true; case _ => false } =>
            Present(TokenKind.Annotation)
        case id: MetaToken.Ident if isTypeIdent(id.text, prev) => Present(TokenKind.Type)
        case _                                                 => Absent

    // Returns true when the ident text is a symbolic operator (starts with a non-letter,
    // non-underscore, non-backtick character, e.g. `+`, `:=`, `%%`).
    private def isOperatorIdent(text: String): Boolean =
        text.nonEmpty && !(Character.isLetter(text.head) || text.head == '_' || text.head == '`')

    // Returns true when `text` should be classified as a Type in a Scala snippet.
    // Arm (a): first character is uppercase (e.g. Option, Int, MyClass).
    // Arm (b): the preceding non-trivia token is a type-context token (after `:`, `extends`,
    // `<:`, `>:`, `new`, `with`, `[`, or `type`), covering lowercase type aliases and parameters.
    // The lookback is satisfied only for non-symbolic alphanumeric idents (symbolic idents are
    // dispatched to Operator before this method is called).
    private def isTypeIdent(text: String, prev: Maybe[MetaToken]): Boolean =
        if text.nonEmpty && Character.isUpperCase(text.head) then true
        else
            prev.exists {
                case _: MetaToken.Colon | _: MetaToken.KwExtends | _: MetaToken.Subtype |
                    _: MetaToken.Supertype | _: MetaToken.KwNew | _: MetaToken.KwWith |
                    _: MetaToken.LeftBracket | _: MetaToken.KwType => true
                case _ => false
            }

    private def tokenizeBash(body: String)(using Frame): UI =
        val keywords = Set(
            "if",
            "then",
            "fi",
            "else",
            "elif",
            "for",
            "do",
            "done",
            "while",
            "echo",
            "export",
            "source",
            "exit",
            "return",
            "function",
            "local",
            "case",
            "esac"
        )

        val tokens = new mutable.ArrayBuffer[UI]()
        val src    = body
        var pos    = 0

        while pos < src.length do
            val ch = src.charAt(pos)

            if ch == '#' then
                val end      = src.indexOf('\n', pos)
                val closeEnd = if end >= 0 then end else src.length
                tokens += UI.span.cssClass("tok-comment")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd
            else if ch == '"' then
                var p2 = pos + 1
                while p2 < src.length && src.charAt(p2) != '"' do
                    if src.charAt(p2) == '\\' then p2 += 1
                    p2 += 1
                end while
                val closeEnd = if p2 < src.length then p2 + 1 else src.length
                tokens += UI.span.cssClass("tok-string")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd
            else if ch == '\'' then
                var p2 = pos + 1
                while p2 < src.length && src.charAt(p2) != '\'' do
                    p2 += 1
                val closeEnd = if p2 < src.length then p2 + 1 else src.length
                tokens += UI.span.cssClass("tok-string")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd
            else if ch.isLetter || ch == '_' then
                val start = pos
                while pos < src.length && (src.charAt(pos).isLetterOrDigit || src.charAt(pos) == '_' || src.charAt(pos) == '-') do
                    pos += 1
                val word = src.substring(start, pos)
                if keywords.contains(word) then
                    tokens += UI.span.cssClass("tok-keyword")(Ast.Text(word))
                else
                    tokens += Ast.Text(word)
                end if
            else
                val start = pos
                while pos < src.length &&
                    src.charAt(pos) != '#' &&
                    src.charAt(pos) != '"' &&
                    src.charAt(pos) != '\'' &&
                    !src.charAt(pos).isLetter &&
                    src.charAt(pos) != '_'
                do
                    pos += 1
                end while
                // Forward-progress guard: never leave `pos` unmoved on a stop char no branch consumed.
                if pos == start then pos += 1
                tokens += Ast.Text(src.substring(start, pos))
            end if
        end while

        UI.fragment(tokens.toSeq*)
    end tokenizeBash

end DocsMarkdownRender
