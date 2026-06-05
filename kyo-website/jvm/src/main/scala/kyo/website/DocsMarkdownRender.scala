// PUBLIC JVM-only build-time render/transpile/highlight object, consumed by WebsiteGenerator
package kyo.website

import kyo.*
import kyo.UI.*
import scala.collection.mutable
import scala.meta.*
import scala.meta.tokens.Token as MetaToken

/** JVM-only Markdown transpiler and syntax highlighter for the kyo docs site.
  *
  * Converts a single README Markdown source string to a [[DocsMarkdownRender.Rendered]] value
  * containing a `kyo-ui` UI article subtree and a heading outline. The article is a real `UI`
  * tree (headings, paragraphs, lists, tables, fenced code with token highlighting, callout divs,
  * inline images and links) ready to embed directly into a page rendered by `UI.runRenderPage`.
  *
  * The grammar is bounded to the construct set enumerated in RI-002 and is expressed with kyo-parse
  * `Parse[Char]` combinators. A thin line-oriented splitter groups raw lines into block segments;
  * the inline content of each block, and the block-level recognizers (headings, list markers, table
  * cells, fence info-strings, badge/link/image inline tokens) are genuine kyo-parse parsers run via
  * `Parse.runResult`.
  *
  * This object lives in `kyo-website/jvm/` so scalameta never reaches the JS link classpath
  * (D2, INV-001). The shared [[DocsMarkdown]] object holds only the cross-platform
  * [[DocsMarkdown.Heading]] carrier type.
  *
  * @see
  *   [[DocsMarkdown]] for the shared heading carrier
  * @see
  *   [[DocsMarkdownRender.Rendered]] for the build-time render output
  */
object DocsMarkdownRender:

    /** The article subtree, pre-rendered HTML string, and heading outline produced by [[transpile]]
      * and [[renderArticle]].
      *
      * `article` is a `UI` value ready to embed into a `UI.runRenderPage` document. `articleHtml`
      * is the pre-rendered HTML string of the article subtree; it is `""` (the build-path-internal
      * sentinel) when produced by [[transpile]] alone, and filled with the full rendered HTML by
      * [[renderArticle]]. `headings` is the ordered outline; each entry carries the same `slug`
      * that was set as the `id` attribute on the corresponding `UI.h1`..`UI.h4` element, so
      * `article` id attributes and `headings` slugs are always consistent (INV-004). Duplicate
      * heading texts are disambiguated with a `-2` suffix on both sides.
      */
    final case class Rendered(article: UI, articleHtml: String, headings: Chunk[DocsMarkdown.Heading])
        derives CanEqual

    /** Transpile a single README Markdown source to a [[Rendered]] value.
      *
      * The effect row is `Sync` only (no `Abort`). Malformed input degrades rather than failing:
      * unknown inline constructs become plain `UI.text` (via kyo-parse `recoverWith`), unknown
      * block constructs become a `UI.p` carrying the verbatim line, and an empty source returns
      * `Rendered(UI.empty, "", Chunk.empty)`. The bounded RI-002 grammar plus those total fall-throughs
      * are designed so no malformed-corpus path reaches an undefined evaluation; the row stays
      * `Sync`.
      *
      * @param source
      *   The Markdown text to transpile. May be empty.
      */
    def transpile(source: String)(using Frame): Rendered < Sync =
        Sync.defer {
            if source.isBlank then Rendered(UI.empty, "", Chunk.empty)
            else parseArticle(stripDoctest(source))
        }

    /** Render a `UI` article subtree to an HTML string by draining the first emission of
      * `UI.runRender`. This is the one-shot idiom: `.take(1).run.map(_.headMaybe.getOrElse(""))`
      * captures the initial (and for a static article, only) render and releases the underlying
      * channel resource.
      *
      * @param article
      *   The article `UI` value produced by [[transpile]]. Must be 100% static (no `Signal`/
      *   `Reactive` nodes), which is guaranteed by the bounded RI-002 grammar (D3).
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
      */
    def renderArticle(source: String)(using Frame): Rendered < Async =
        for
            t    <- transpile(source)
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
    private[website] def sectionSnippets(source: String, maxChars: Int)(using Frame): Chunk[(DocsMarkdown.Heading, String)] < Sync =
        Sync.defer {
            val cleaned    = stripDoctest(source)
            val blocks     = splitBlocks(cleaned)
            val out        = new mutable.ArrayBuffer[(DocsMarkdown.Heading, String)]()
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
                        var j             = i + 1
                        // isInstanceOf is used here for the loop guard; a match-inversion would be less readable.
                        while j < blocks.length && !blocks(j).isInstanceOf[Block.Heading] do
                            blocks(j) match
                                case Block.Paragraph(t)  => prose += stripInlineMarkdown(t)
                                case Block.Quote(c)      => prose += stripInlineMarkdown(c)
                                case Block.Unordered(ls) => prose += ls.map(stripInlineMarkdown).mkString(" ")
                                case Block.Ordered(ls)   => prose += ls.map(stripInlineMarkdown).mkString(" ")
                                case Block.Table(ls)     => prose += ls.map(stripInlineMarkdown).mkString(" ")
                                case _                   => ()
                            end match
                            j += 1
                        end while
                        out += (heading -> snippetOf(prose.mkString(" "), maxChars))
                    case _ => ()
                end match
                i += 1
            end while
            Chunk.from(out.toSeq)
        }
    end sectionSnippets

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
            if line.trim.startsWith("<!--") && line.contains("doctest") then
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
            if m.startsWith("```") && m.length > 3 then
                val info          = m.drop(3).trim
                val firstToken    = info.takeWhile(c => !c.isWhitespace)
                val leadingSpaces = line.takeWhile(_ == ' ')
                result(i) = leadingSpaces + "```" + firstToken
            else
                result(i) = line
            end if
            i += 1
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

    /** Group cleaned source lines into [[Block]] segments. Leading HTML comments are skipped
      * (RI-002 trap 5). This is line-level structuring only; the content of each block is parsed
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

        // Skip leading HTML comments (RI-002 trap 5).
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
            else if trimmed.startsWith("```") then
                val info = trimmed.drop(3).trim
                i += 1
                val codeLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && !lines(i).trim.startsWith("```") do
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
    private def parseArticle(cleaned: String)(using Frame): Rendered =
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
                val heading: UI = level match
                    case 1 => UI.h1.id(slug)(inlineNodes*)
                    case 2 => UI.h2.id(slug)(inlineNodes*)
                    case 3 => UI.h3.id(slug)(inlineNodes*)
                    case _ => UI.h4.id(slug)(inlineNodes*)
                uiBlocks += heading
                // The in-page heading keeps the rich inline render (real <code>, bold, italic); the
                // outline entry stores the plain-text form so the TOC and search show clean labels
                // without literal backticks/asterisks (B3). The slug is derived from the raw text so
                // the heading `id` and the TOC anchor stay byte-identical to the rendered HTML.
                headings += DocsMarkdown.Heading(level, stripInlineMarkdown(text), slug)

            case Block.Fence(info, body) =>
                uiBlocks += UI.pre(UI.code(highlight(info, body)))

            case Block.Quote(content) =>
                uiBlocks += parseBlockquote(content)

            case Block.Table(lines) =>
                uiBlocks += parseTable(lines)

            case Block.Unordered(lines) =>
                uiBlocks += parseUnorderedList(lines)

            case Block.Ordered(lines) =>
                val items = lines.map(l => UI.li(parseInline(parseOrderedItem(l))*))
                uiBlocks += UI.ol(items*)

            case Block.RawEmbed(snippet) =>
                uiBlocks += UI.p(UI.rawHtml(snippet))

            case Block.Paragraph(text) =>
                uiBlocks += UI.p(parseInline(text)*)
        }

        val article: UI =
            if uiBlocks.isEmpty then UI.empty
            else UI.fragment(uiBlocks.toSeq*)
        Rendered(article, "", Chunk.from(headings.toSeq))
    end parseArticle

    // ---- block-level kyo-parse parsers ----

    /** Strip inline Markdown markers from a heading's raw text for plain-text display in the TOC and
      * search index (B3). Removes inline-code backticks, bold/italic asterisks, and unwraps
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
    private def parseTable(tableLines: Chunk[String])(using Frame): UI =
        if tableLines.length < 2 then
            // Malformed table (missing separator): degrade to paragraph.
            UI.p(Ast.Text(tableLines.headOption.getOrElse("")))
        else
            val headerCells = parseRowCells(tableLines.head)
            val bodyRows    = if tableLines.length > 2 then tableLines.drop(2) else Chunk.empty[String]
            val headerTr    = UI.tr(headerCells.map(cell => UI.th(parseInline(cell)*))*)
            val bodyTrs = bodyRows.map { row =>
                val cells = parseRowCells(row)
                UI.tr(cells.map(cell => UI.td(parseInline(cell)*))*)
            }
            UI.table(headerTr +: bodyTrs*)
        end if
    end parseTable

    /** Parse an unordered list from its grouped lines, handling two-space sub-indented items. The
      * `- ` / `  - ` markers are recognized with a `Parse[Char]` parser per line.
      */
    private def parseUnorderedList(lines: Chunk[String])(using Frame): UI =
        val arr   = lines.toArray
        val items = new mutable.ArrayBuffer[Ast.Li]()
        var i     = 0
        while i < arr.length do
            val line = arr(i)
            if line.startsWith("  - ") then
                // Orphan sub-item with no preceding top-level item; treat as a top-level item.
                items += UI.li(parseInline(parseListItem(line.trim))*)
                i += 1
            else
                val text = parseListItem(line.trim)
                i += 1
                val subItems = new mutable.ArrayBuffer[Ast.Li]()
                while i < arr.length && arr(i).startsWith("  - ") do
                    subItems += UI.li(parseInline(parseListItem(arr(i).trim))*)
                    i += 1
                end while
                if subItems.isEmpty then items += UI.li(parseInline(text)*)
                else items += UI.li((parseInline(text) :+ UI.ul(subItems.toSeq*))*)
            end if
        end while
        UI.ul(items.toSeq*)
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
    private def parseBlockquote(content: String)(using Frame): UI =
        val firstNonEmpty = content.linesIterator.find(_.trim.nonEmpty).getOrElse("").trim
        val bqBlocks      = parseBlockquoteContent(content)
        if firstNonEmpty.startsWith("**Note:**") then UI.div.cssClass("callout callout-note")(bqBlocks*)
        else if firstNonEmpty.startsWith("**Caution:**") then UI.div.cssClass("callout callout-caution")(bqBlocks*)
        else UI.div.cssClass("blockquote")(bqBlocks*)
    end parseBlockquote

    private def parseBlockquoteContent(content: String)(using Frame): Chunk[UI] =
        val lines  = content.linesIterator.toArray
        val result = new mutable.ArrayBuffer[UI]()
        var i      = 0
        while i < lines.length do
            val trimmed = lines(i).trim
            if trimmed.isEmpty then
                i += 1
            else if trimmed.startsWith("```") then
                val info = trimmed.drop(3).trim
                i += 1
                val codeLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && !lines(i).trim.startsWith("```") do
                    codeLines += lines(i)
                    i += 1
                if i < lines.length then i += 1
                result += UI.pre(UI.code(highlight(info, codeLines.mkString("\n"))))
            else
                val paraLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && lines(i).trim.nonEmpty && !lines(i).trim.startsWith("```") do
                    paraLines += lines(i).trim
                    i += 1
                result += UI.p(parseInline(paraLines.mkString(" "))*)
            end if
        end while
        Chunk.from(result)
    end parseBlockquoteContent

    // ---- inline kyo-parse grammar ----

    /** Convert a raw URL string to a `Href`. Treats `#id` as Fragment, `http(s)://...` as External,
      * and everything else as Path. Intra-repo Markdown links to a sibling README (e.g.
      * `../kyo-prelude/README.md`) are rewritten to the directory route they map to under the docs
      * site so they resolve to a real page instead of 404ing on the raw `.md` file (B2).
      */
    private def toHref(url: String): Href =
        if url.startsWith("#") then Href.Fragment(url.drop(1))
        else if url.startsWith("https://") then Href.External("https", url.drop(6))
        else if url.startsWith("http://") then Href.External("http", url.drop(5))
        else Href.Path(rewriteReadmePath(url))
    end toHref

    /** Rewrite an intra-repo link that targets a `README.md` file to the directory route it lives in.
      *
      *   - `../kyo-prelude/README.md` -> `../kyo-prelude/` (strip the filename, keep the trailing slash)
      *   - `README.md` -> `./` (the current directory)
      *   - `README.md#anchor` (or `dir/README.md#anchor`) -> `#anchor` / `dir/#anchor` (keep the fragment)
      *
      * Any other path is returned unchanged.
      */
    private def rewriteReadmePath(url: String): String =
        val hashIdx  = url.indexOf('#')
        val path     = if hashIdx >= 0 then url.substring(0, hashIdx) else url
        val fragment = if hashIdx >= 0 then url.substring(hashIdx) else ""
        if path == "README.md" then
            if fragment.isEmpty then "./" else fragment
        else if path.endsWith("/README.md") then
            path.dropRight("README.md".length) + fragment
        else url
        end if
    end rewriteReadmePath

    /** Parse inline Markdown to a sequence of UI nodes with a kyo-parse `Parse[Char]` grammar.
      *
      * Handles, in PEG ordered-choice precedence: linked badges (`[![alt](img)](link)`), badge
      * images (`![alt](url)`), links (`[text](url)`), bold (`**text**`), italic (`*text*`), inline
      * code (`` `text` ``), inline raw HTML (`<...>`), and plain text runs. Any inline token that
      * does not parse degrades to a single literal character via `recoverWith` +
      * `RecoverStrategy`, so the row never aborts.
      */
    private def parseInline(text: String)(using Frame): Chunk[UI] =
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
    private def inlineNodes(using Frame): Chunk[UI] < Parse[Char] =
        Parse.repeat(inlineToken).map(tokens => coalesceText(Chunk.from(tokens)))

    /** A single inline token, as a `Token`: `Lit(char)` is one literal character (a degrade unit);
      * `Node(ui)` is a parsed inline node. Unknown markup degrades to one literal character via the
      * `firstOf` last branch, and any fatal failure is recovered to one literal character via
      * `recoverWith` + `RecoverStrategy`, so the inline row never aborts.
      */
    private def inlineToken(using Frame): Token < Parse[Char] =
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
    private def linkedBadge(using Frame): UI < Parse[Char] =
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
    private def link(using Frame): UI < Parse[Char] =
        for
            _    <- Parse.literal('[')
            body <- readUntilString("](")
            _    <- Parse.literal("](")
            url  <- readUntilChar(')')
            _    <- Parse.literal(')')
        yield UI.a.href(toHref(url))(parseInline(body)*)

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
    private def inlineCode(using Frame): UI < Parse[Char] =
        for
            _     <- Parse.literal('`')
            inner <- nonEmptyUntilChar('`')
            _     <- Parse.literal('`')
        yield UI.code(Ast.Text(inner))

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
      * `WebsiteStyles.docsTokens`. The closed set is defined by `design/02-public-api.md` note 4;
      * the `cssClass` accessor is the single source of truth for the class name string (INV-006).
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
      * (`Tokenized.Error`) the whole body degrades to a single `Ast.Text` leaf (INV-008). All token
      * `.text` values are emitted (including trivia), so the output is byte-preserving.
      *
      * A `foldLeft` threads `prev` (the last non-trivia token) as accumulator state for the one-token
      * lookback in `classify` (INV-G2). No mutable state escapes the method.
      *
      * @param body
      *   The raw source text of the fenced code block.
      */
    private def highlightScala(body: String)(using Frame): UI =
        dialects.Scala3(body).tokenize match
            case _: Tokenized.Error => Ast.Text(body)
            case Tokenized.Success(tokens) =>
                val (spans, _) = tokens.foldLeft((Chunk.empty[UI], Maybe.empty[MetaToken])) {
                    case ((acc, prev), tok) =>
                        val span = classify(tok, prev) match
                            case Present(kind) => UI.span.cssClass(kind.cssClass)(Ast.Text(tok.text))
                            case Absent        => Ast.Text(tok.text)
                        val nextPrev = if isTrivia(tok) then prev else Present(tok)
                        (acc.append(span), nextPrev)
                }
                UI.fragment(spans.toSeq*)
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
        // 4.13.4, so they do not match the Keyword arm below. Place this arm first (INV-007).
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
