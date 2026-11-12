package kyo

import kyo.UI.*
import scala.collection.mutable
import scala.language.implicitConversions

/** Cross-platform Markdown-to-UI renderer.
  *
  * Converts a single Markdown source string into a [[Markdown.Rendered]] value: a `kyo-ui` `UI`
  * article subtree (headings, paragraphs, ordered and unordered lists, GFM pipe tables, fenced
  * code blocks, blockquotes, inline images and links) plus the document's heading outline. The
  * article is a real `UI` tree ready to embed into a page and render with any `kyo-ui` runner.
  *
  * The grammar is bounded to a fixed construct set and is expressed with kyo-parse `Parse[Char]`
  * combinators. A thin line-oriented splitter groups raw lines into block segments; the inline
  * content of each block and the block-level recognizers (headings, list markers, table cells,
  * fence info-strings, link and image tokens) are genuine kyo-parse parsers run via
  * `Parse.runResult`.
  *
  * Rendering is total: malformed input degrades rather than aborting. An unknown inline construct
  * becomes plain text, an unrecognized block becomes a paragraph carrying its verbatim line, and
  * an empty source yields `Rendered(UI.empty, Chunk.empty)`. Fenced code renders as a plain
  * `pre`/`code` pair with the info string's first token exposed as a `language-<lang>` CSS class;
  * syntax highlighting is left to the consumer.
  *
  * @see
  *   [[Markdown.render]] for the single entry point
  * @see
  *   [[Markdown.Rendered]] for the render output
  */
object Markdown:

    /** The article subtree and heading outline produced by [[render]].
      *
      * `article` is a `UI` value ready to embed into a document. `headings` is the ordered outline;
      * each entry carries the same `id` that was set as the `id` attribute on the corresponding
      * `UI.h1`..`UI.h4` element, so `article` id attributes and `headings` ids are always
      * consistent. Duplicate heading texts are disambiguated with a `-2` (then `-3`, etc.) suffix
      * on both sides.
      */
    final case class Rendered(article: UI, headings: Chunk[Heading]) derives CanEqual

    /** A single heading entry in the document outline produced by [[render]].
      *
      * `level` is 1..4 (matching `# ` through `#### `). `text` is the plain-text heading content
      * with inline markup stripped, suitable for a table of contents. `id` is the URL-safe anchor
      * derived from `text`: lowercased, non-alphanumeric characters replaced with `-`, runs of `-`
      * collapsed, leading and trailing `-` removed, and duplicate ids disambiguated with a `-2`
      * (then `-3`, etc.) suffix. Under `Options(html = false)` the id is `user-content-` before that
      * anchor.
      */
    final case class Heading(level: Int, text: String, id: String) derives CanEqual

    /** Render a single Markdown source to a [[Rendered]] value.
      *
      * Pure and total: the grammar is bounded and every construct degrades rather than failing
      * (unknown inline constructs become plain `UI.text` via kyo-parse `recoverWith`, unknown
      * block constructs become a `UI.p` carrying the verbatim line, and a blank source returns
      * `Rendered(UI.empty, Chunk.empty)`), so the result is a plain value usable anywhere a UI
      * subtree is built, including inside reactive regions, which take pure producers.
      *
      * @param source
      *   The Markdown text to render. May be empty.
      */
    def render(source: String)(using Frame): Rendered =
        render(source, Options())

    /** Render with explicit [[Options]]; `Options(html = false)` is the form for text from untrusted authors. */
    def render(source: String, options: Options)(using Frame): Rendered =
        if source.isBlank then Rendered(UI.empty, Chunk.empty)
        else parseArticle(source, options)

    /** How [[render]] treats markup that reaches past the Markdown grammar.
      *
      * `html = true` (the default) passes raw HTML through verbatim and turns every link and image URL into an `href` or `src`, which is
      * right for trusted documents such as a project's own README.
      *
      * `html = false` is for text whose author the page does not trust, such as a chat message: raw HTML and HTML comments render as
      * escaped text where they were written, and a link or image URL renders only when it is `http`, `https`, a `#fragment` or a path
      * with no scheme. Any other scheme (`javascript:`, `data:`, ...) is refused after removing what a browser ignores in a URL (tabs,
      * newlines, leading spaces and control characters) and ignoring case; a refused link renders as its text, a refused image as its alt
      * text, and a linked image with a refused link as the image alone. No image is fetched: an image renders as a link to its URL,
      * named by its alt text or, when that is empty, by the URL, and a linked image as one link to the link's URL. Every link but a
      * fragment opens in a new tab. Heading ids and fragments use a `user-content-` prefix that the embedding page can reserve for
      * untrusted content; links within the document still reach its headings. The other Markdown constructs render the same in both modes.
      */
    final case class Options(html: Boolean = true) derives CanEqual

    private val idPrefix = "user-content-"

    private def html(cs: Seq[UI]): Seq[UI.Ast.HtmlChildVal] =
        cs.map(n => UI.Ast.HtmlChildVal.lift(n))
    private def html(cs: Chunk[UI]): Seq[UI.Ast.HtmlChildVal] =
        cs.toSeq.map(n => UI.Ast.HtmlChildVal.lift(n))

    /** Render a fenced code block to a `pre`/`code` pair. The info string's first whitespace-
      * delimited token, when present, becomes a `language-<token>` CSS class on the `code` element
      * (the CommonMark convention); the body is emitted verbatim as a single text leaf. Syntax
      * highlighting is left to the consumer.
      */
    private def renderFence(info: String, body: String)(using Frame): UI =
        val lang = info.takeWhile(c => !c.isWhitespace)
        val code =
            if lang.isEmpty then UI.code(Ast.Text(body))
            else UI.code.cssClass("language-" + lang)(Ast.Text(body))
        UI.pre(code)
    end renderFence

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
    // minimum lets shorter fences appear verbatim in its body. Every fence pass must track this
    // length, or a 4-backtick block closes on its first inner 3-backtick line.
    private def fenceLen(t: String): Int =
        val n = t.takeWhile(_ == '`').length
        if n >= 3 then n else 0

    // True when `t` (already trimmed) closes a fence opened with `open` backticks: at least `open`
    // backticks and nothing but whitespace after them (the CommonMark closing-fence rule).
    private def isFenceClose(t: String, open: Int): Boolean =
        val n = t.takeWhile(_ == '`').length
        n >= open && t.drop(n).trim.isEmpty

    /** Group source lines into [[Block]] segments. With `html`, HTML comments are skipped and
      * `<img`/`<a` lines become raw embeds; without it, both are ordinary paragraph text. This is
      * line-level structuring only; the content of each block is parsed by the kyo-parse
      * block/inline parsers.
      */
    private def splitBlocks(cleaned: String, html: Boolean): Chunk[Block] =
        val lines  = cleaned.linesIterator.toArray
        val blocks = new mutable.ArrayBuffer[Block]()
        var i      = 0

        // Advance past an HTML comment that starts at `lines(i)`, consuming every line through and
        // including the one that closes the comment with `-->`. Returns the index of the first line
        // after the comment. Any text after `-->` on the closing line is appended to `blocks` as a
        // paragraph so it is not silently dropped.
        def skipComment(): Int =
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
        while html && i < lines.length && lines(i).trim.startsWith("<!--") do
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
            else if html && trimmed.startsWith("<!--") then
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

            // Block-level raw HTML embed: lines starting with <img or <a only. <img is single line.
            // <a may wrap <img on subsequent lines; coalesce until the closing </a>. The embed is
            // wrapped in UI.p so the article body stays a real UI node and the <a><img></a> nesting
            // is preserved as one unit.
            else if html && (trimmed.startsWith("<img") || trimmed.startsWith("<a")) then
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
                    !(html && lines(i).trim.startsWith("<!--")) &&
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

    /** Parse the Markdown string into a [[Rendered]] value. Lines are grouped into [[Block]]
      * segments by [[splitBlocks]]; each segment's content is then parsed by a kyo-parse
      * `Parse[Char]` parser. Heading ids are tracked in a mutable map local to this call; duplicate
      * ids receive `-2` (then `-3`, etc.) suffixes.
      */
    private def parseArticle(cleaned: String, options: Options)(using Frame): Rendered =
        val blocks     = splitBlocks(cleaned, options.html)
        val uiBlocks   = new mutable.ArrayBuffer[UI]()
        val headings   = new mutable.ArrayBuffer[Heading]()
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
                val (level, text)  = parseHeading(line)
                val slug           = if options.html then makeSlug(text) else idPrefix + makeSlug(text)
                val inlineNodes    = parseInline(text, options)
                val inlineChildren = html(inlineNodes)
                val heading: UI    = level match
                    case 1 => UI.h1.id(slug)(inlineChildren*)
                    case 2 => UI.h2.id(slug)(inlineChildren*)
                    case 3 => UI.h3.id(slug)(inlineChildren*)
                    case _ => UI.h4.id(slug)(inlineChildren*)
                uiBlocks += heading
                // The in-page heading keeps the rich inline render (real <code>, bold, italic); the
                // outline entry stores the plain-text form so a table of contents shows clean labels
                // without literal backticks/asterisks. The id is derived from the raw text so the
                // heading `id` and the outline anchor stay byte-identical to the rendered HTML.
                headings += Heading(level, stripInlineMarkdown(text), slug)

            case Block.Fence(info, body) =>
                uiBlocks += renderFence(info, body)

            case Block.Quote(content) =>
                uiBlocks += parseBlockquote(content, options)

            case Block.Table(lines) =>
                uiBlocks += parseTable(lines, options)

            case Block.Unordered(lines) =>
                uiBlocks += parseUnorderedList(lines, options)

            case Block.Ordered(lines) =>
                val items = lines.map(l => UI.li(html(parseInline(parseOrderedItem(l), options))*))
                uiBlocks += UI.ol(html(items)*)

            case Block.RawEmbed(snippet) =>
                uiBlocks += UI.p(UI.rawHtml(snippet))

            case Block.Paragraph(text) =>
                uiBlocks += UI.p(html(parseInline(text, options))*)
        }

        val article: UI =
            if uiBlocks.isEmpty then UI.empty
            else UI.fragment(uiBlocks.toSeq*)
        Rendered(article, Chunk.from(headings.toSeq))
    end parseArticle

    // ---- block-level kyo-parse parsers ----

    /** Strip inline Markdown markers from a heading's raw text for plain-text display in an
      * outline. Removes inline-code backticks, bold/italic asterisks, and unwraps `[text](url)`
      * links to their visible `text`. The in-page heading still renders the real inline markup;
      * only the outline label is flattened. Idempotent and total.
      */
    private def stripInlineMarkdown(raw: String): String =
        val sb      = new mutable.StringBuilder()
        val s       = raw
        val closers = Closers(s)
        var i       = 0
        val len     = s.length
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
                val target = closers.next("]", i + 1)
                    .filter(closeBracket => closers.next("](", closeBracket).contains(closeBracket))
                    .flatMap(closeBracket => closers.next(")", closeBracket + 2).map(closeParen => (closeBracket, closeParen)))
                target match
                    case Present((closeBracket, closeParen)) =>
                        sb.append(stripInlineMarkdown(s.substring(i + 1, closeBracket)))
                        i = closeParen + 1
                    case Absent =>
                        sb.append(c)
                        i += 1
                end match
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

    /** Parse a GFM pipe-table row into trimmed cell strings via a `Parse[Char]` grammar. A GFM row
      * is `| c1 | c2 |`: a leading `|` followed by a run of `cell` then `|` pairs. A cell is a run
      * of characters up to the next pipe, with a `\|` escape contributing a literal `|` to the cell.
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
    private def parseTable(tableLines: Chunk[String], options: Options)(using Frame): UI =
        if tableLines.length < 2 then
            // Malformed table (missing separator): degrade to paragraph.
            UI.p(Ast.Text(tableLines.headOption.getOrElse("")))
        else
            val headerCells = parseRowCells(tableLines.head)
            val bodyRows    = if tableLines.length > 2 then tableLines.drop(2) else Chunk.empty[String]
            val headerTr    = UI.tr(html(headerCells.map(cell => UI.th(html(parseInline(cell, options))*)))*)
            val bodyTrs     = bodyRows.map { row =>
                val cells = parseRowCells(row)
                UI.tr(html(cells.map(cell => UI.td(html(parseInline(cell, options))*)))*)
            }
            UI.table(html(headerTr +: bodyTrs)*)
        end if
    end parseTable

    /** Parse an unordered list from its grouped lines, handling two-space sub-indented items. The
      * `- ` / `  - ` markers are recognized with a `Parse[Char]` parser per line.
      */
    private def parseUnorderedList(lines: Chunk[String], options: Options)(using Frame): UI =
        val arr   = lines.toArray
        val items = new mutable.ArrayBuffer[Ast.Li]()
        var i     = 0
        while i < arr.length do
            val line = arr(i)
            if line.startsWith("  - ") then
                // Orphan sub-item with no preceding top-level item; treat as a top-level item.
                items += UI.li(html(parseInline(parseListItem(line.trim), options))*)
                i += 1
            else
                val text = parseListItem(line.trim)
                i += 1
                val subItems = new mutable.ArrayBuffer[Ast.Li]()
                while i < arr.length && arr(i).startsWith("  - ") do
                    subItems += UI.li(html(parseInline(parseListItem(arr(i).trim), options))*)
                    i += 1
                end while
                if subItems.isEmpty then items += UI.li(html(parseInline(text, options))*)
                else items += UI.li(html(parseInline(text, options) :+ UI.ul(html(subItems.toSeq)*))*)
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

    /** Parse a blockquote run into a `blockquote` element. The leading `> ` is already stripped by
      * the splitter. Nested paragraphs and fenced code blocks are recognized inside the quote.
      */
    private def parseBlockquote(content: String, options: Options)(using Frame): UI =
        UI.blockquote(html(parseBlockquoteContent(content, options))*)

    private def parseBlockquoteContent(content: String, options: Options)(using Frame): Chunk[UI] =
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
                result += renderFence(info, codeLines.mkString("\n"))
            else
                val paraLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && lines(i).trim.nonEmpty && !lines(i).trim.startsWith("```") do
                    paraLines += lines(i).trim
                    i += 1
                result += UI.p(html(parseInline(paraLines.mkString(" "), options))*)
            end if
        end while
        Chunk.from(result)
    end parseBlockquoteContent

    // ---- inline kyo-parse grammar ----

    /** Convert a raw URL string to a `Href`. Treats `#id` as Fragment, `http(s)://...` as
      * External, and everything else as Path. Without `options.html` a fragment's id is
      * `user-content-` before the id it names, as a heading's is.
      */
    private def toHref(url: String, options: Options): Href =
        if url.startsWith("#") then Href.Fragment(if options.html then url.drop(1) else idPrefix + url.drop(1))
        else if url.startsWith("https://") then Href.External("https", url.drop(6))
        else if url.startsWith("http://") then Href.External("http", url.drop(5))
        else Href.Path(url)
    end toHref

    /** The URL as `options` allows it: always with `html`, otherwise only an `http`/`https` URL, a
      * fragment or a scheme-less path. The scheme is read the way a browser reads it, after removing
      * tabs and newlines anywhere and control characters and spaces in front, and ignoring case.
      */
    private def allowed(url: String, options: Options): Maybe[String] =
        if options.html then Present(url)
        else
            val cleaned = url.filterNot(c => c == '\t' || c == '\n' || c == '\r').dropWhile(_ <= ' ')
            val scheme  = cleaned.takeWhile(c => c.isLetterOrDigit || c == '+' || c == '-' || c == '.')
            val schemed = scheme.nonEmpty && scheme.head.isLetter && cleaned.drop(scheme.length).startsWith(":")
            if !schemed || scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https") then Present(cleaned)
            else Absent
    end allowed

    /** Parse inline Markdown to a sequence of UI nodes with a kyo-parse `Parse[Char]` grammar.
      *
      * Handles, in PEG ordered-choice precedence: linked images (`[![alt](img)](link)`), images
      * (`![alt](url)`), links (`[text](url)`), bold (`**text**`), italic (`*text*`), inline code
      * (`` `text` ``), inline raw HTML (`<...>`), and plain text runs. Any inline token that does
      * not parse degrades to a single literal character via `recoverWith` + `RecoverStrategy`, so
      * the row never aborts.
      */
    private def parseInline(text: String, options: Options)(using Frame): Chunk[UI] =
        if text.isEmpty then Chunk(Ast.Text(""))
        else runParser(text)(inlineNodes(options, Closers(text))).getOrElse(Chunk(Ast.Text(text)))

    /** Where each closer next starts in one text, so that an opener learns whether what completes
      * its construct is ahead before it scans for it. A scan per opener takes time quadratic in a
      * text of openers left unclosed. Each closer's positions are found once, in one pass of the
      * text, and an opener's question is a binary search over them; a run of backticks is answered
      * from the text's runs of backticks, through a tree holding the longest run under each node, so
      * locating the next matching run takes O(log n).
      */
    final private class Closers(val text: String):

        private val positions = mutable.HashMap.empty[String, Array[Int]]

        // each maximal run of backticks, by where it starts, and its length
        private lazy val runStarts: Array[Int] =
            text.indices.filter(offset => text.charAt(offset) == '`' && (offset == 0 || text.charAt(offset - 1) != '`')).toArray
        private lazy val runLengths: Array[Int] =
            runStarts.map(offset =>
                text.indexWhere(_ != '`', offset) match
                    case -1       => text.length - offset
                    case boundary => boundary - offset
            )

        // the runs' lengths as the leaves of a binary tree from `leaves` on, each node holding the longest run under it
        private lazy val leaves: Int         = Integer.highestOneBit(math.max(runStarts.length, 1) * 2 - 1)
        private lazy val longest: Array[Int] =
            val tree = new Array[Int](2 * leaves)
            runLengths.indices.foreach(index => tree(leaves + index) = runLengths(index))
            (leaves - 1 to 1 by -1).foreach(node => tree(node) = math.max(tree(2 * node), tree(2 * node + 1)))
            tree
        end longest

        /** The first position at or after `from` where `closer` starts, absent when none does. */
        def next(closer: String, from: Int): Maybe[Int] =
            if closer.nonEmpty && closer.forall(_ == '`') then
                val nextRun = runStarts.search(from).insertionPoint
                // a run that starts before `from` holds the closer at `from` when it reaches far enough past it
                if nextRun > 0 && runStarts(nextRun - 1) + runLengths(nextRun - 1) - from >= closer.length then Present(from)
                else
                    val longRun = firstRun(nextRun, closer.length, 1, 0, leaves)
                    if longRun < runStarts.length then Present(runStarts(longRun)) else Absent
                end if
            else
                val offsets = positions.getOrElseUpdate(closer, occurrences(closer))
                val index   = offsets.search(from).insertionPoint
                if index < offsets.length then Present(offsets(index)) else Absent

        private def occurrences(closer: String): Array[Int] =
            Iterator.iterate(text.indexOf(closer))(offset => text.indexOf(closer, offset + 1)).takeWhile(_ >= 0).toArray

        def hasLinkTail(from: Int): Boolean =
            next("](", from).flatMap(bracket => next(")", bracket + 2)).nonEmpty

        def hasImageTail(from: Int): Boolean =
            next("]", from)
                .filter(bracket => next("](", bracket).contains(bracket))
                .flatMap(bracket => next(")", bracket + 2))
                .nonEmpty

        def hasNonEmptySpan(closer: String, from: Int): Boolean =
            next(closer, from).exists(_ > from)

        /** The end of a literal run, using the grammar's closure checks to skip unusable openers. Backticks stay with the grammar:
          * even an unmatched run produces its own text node rather than coalescing with adjacent literal text.
          */
        def literalEnd(from: Int): Int =
            @scala.annotation.tailrec
            def loop(offset: Int): Int =
                if offset == text.length then offset
                else
                    text.charAt(offset) match
                        case '[' if hasLinkTail(offset + 1)                                   => offset
                        case '!' if text.startsWith("![", offset) && hasImageTail(offset + 2) => offset
                        case '<' if hasNonEmptySpan(">", offset + 1)                          => offset
                        case '*'
                            if (text.startsWith("**", offset) && hasNonEmptySpan("**", offset + 2)) ||
                                hasNonEmptySpan("*", offset + 1) => offset
                        case '`' => offset
                        case _   => loop(offset + 1)
            loop(from)
        end literalEnd

        // the index of the first run at index `from` or after that is at least `length` long, among the runs from `low` until
        // `high` under `node`; the number of runs when none is
        private def firstRun(from: Int, length: Int, node: Int, low: Int, high: Int): Int =
            if high <= from || longest(node) < length then runStarts.length
            else if node >= leaves then low
            else
                val middle  = (low + high) >>> 1
                val leftRun = firstRun(from, length, 2 * node, low, middle)
                if leftRun < runStarts.length then leftRun else firstRun(from, length, 2 * node + 1, middle, high)
    end Closers

    /** One result of the inline grammar: either literal text (coalesced into `Ast.Text` runs) or a fully parsed inline `UI` node.
      */
    private enum Token:
        case Lit(text: String)
        case Node(ui: UI)

    /** The inline grammar: repeat a single inline token until end of input, then coalesce adjacent
      * literal characters into `Ast.Text` runs.
      */
    private def inlineNodes(options: Options, closers: Closers)(using Frame): Chunk[UI] < Parse[Char] =
        val token = inlineToken(options, closers)
        Parse.repeat(token).map(tokens => coalesceText(tokens))

    /** A single inline token: a literal run or a parsed inline node. Unknown markup degrades to one literal character via the
      * `firstOf` last branch, and any fatal failure is recovered to one literal character via
      * `recoverWith` + `RecoverStrategy`, so the inline row never aborts.
      */
    private def inlineToken(options: Options, closers: Closers)(using Frame): Token < Parse[Char] =
        def node(p: UI < Parse[Char]): Token < Parse[Char] = p.map(Token.Node(_))
        val literalChar: Token < Parse[Char]               = Parse.any[Char].map(char => Token.Lit(char.toString))
        val literalText: Token < Parse[Char]               = Parse.read { (input: ParseInput[Char]) =>
            val end = closers.literalEnd(input.position)
            if end == input.position then Result.fail(Chunk(ParseFailure("inline markup", input.position)))
            else Result.succeed((input.advance(end - input.position), Token.Lit(closers.text.substring(input.position, end))))
        }
        Parse.recoverWith(
            Parse.firstOf(
                literalText,
                Parse.firstOf(
                    node(linkedImage(options, closers)),
                    node(image(options, closers)),
                    node(link(options, closers)),
                    node(bold(closers)),
                    node(italic(closers)),
                    node(inlineCode(closers)),
                    node(inlineHtml(options, closers)),
                    literalChar
                )
            ),
            RecoverStrategy.viaParser[Char, Token](literalChar)
        )
    end inlineToken

    /** Coalesce a token stream of literal runs and parsed nodes into a UI sequence, merging
      * adjacent literal text into single `Ast.Text` leaves.
      */
    private def coalesceText(tokens: Chunk[Token])(using Frame): Chunk[UI] =
        val out           = new mutable.ArrayBuffer[UI]()
        val buf           = new mutable.StringBuilder()
        def flush(): Unit =
            if buf.nonEmpty then
                out += Ast.Text(buf.toString)
                buf.clear()
        tokens.foreach {
            case Token.Lit(text) => buf.append(text)
            case Token.Node(ui)  => flush(); out += ui
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

    /** `[![alt](img)](link)` -> `UI.a.href(link)(UI.img(img, alt))`; a link `options` refuses
      * leaves the image alone. Without `options.html` an image is a link to its URL, so a linked
      * image is that link to the link's URL, never an anchor inside an anchor.
      */
    private def linkedImage(options: Options, closers: Closers)(using Frame): UI < Parse[Char] =
        for
            _ <- Parse.literal("[![")
            _ <- Parse.position[Char].map { offset =>
                if closers.next("]", offset)
                        .filter(bracket => closers.next("](", bracket).contains(bracket))
                        .flatMap(bracket => closers.next(")", bracket + 2))
                        .filter(paren => closers.next(")](", paren).contains(paren))
                        .flatMap(paren => closers.next(")", paren + 3))
                        .isEmpty
                then Parse.fail[Char]("unclosed linked image")
                else ()
            }
            alt <- readUntilChar(']')
            _   <- Parse.literal("](")
            img <- readUntilChar(')')
            _   <- Parse.literal(")](")
            lnk <- readUntilChar(')')
            _   <- Parse.literal(')')
        yield
            if !options.html then imageOf(if allowed(lnk, options).isEmpty then img else lnk, alt, options)
            else
                val picture = imageOf(img, alt, options)
                allowed(lnk, options).fold(picture)(url => UI.a.href(toHref(url, options))(html(Seq(picture))*))

    /** `![alt](url)` -> `UI.img(url, alt)`. */
    private def image(options: Options, closers: Closers)(using Frame): UI < Parse[Char] =
        for
            _ <- Parse.literal("![")
            _ <- Parse.position[Char].map { offset =>
                if !closers.hasImageTail(offset) then Parse.fail[Char]("unclosed image")
                else ()
            }
            alt <- readUntilChar(']')
            _   <- Parse.literal("](")
            url <- readUntilChar(')')
            _   <- Parse.literal(')')
        yield imageOf(url, alt, options)

    /** The image, or its alt text when `options` refuses the URL. Without `options.html` nothing is
      * fetched: the image is a link to its URL, named by the alt text or, when that is empty, by the
      * URL, opening in a new tab unless it is a fragment.
      */
    private def imageOf(url: String, alt: String, options: Options)(using Frame): UI =
        allowed(url, options).fold(Ast.Text(alt): UI) { src =>
            if options.html then UI.img(ImgSrc.Path(src), alt)
            else
                val name = Ast.Text(if alt.isEmpty then src else alt)
                if src.startsWith("#") then UI.a.href(toHref(src, options))(name)
                else UI.a.href(toHref(src, options), Target.Blank)(name)
        }

    /** `[text](url)` -> `UI.a.href(url)(parseInline(text)*)`; the text alone when `options`
      * refuses the URL. Without `options.html` every link but a fragment opens in a new tab.
      */
    private def link(options: Options, closers: Closers)(using Frame): UI < Parse[Char] =
        for
            _ <- Parse.literal('[')
            _ <- Parse.position[Char].map { offset =>
                if !closers.hasLinkTail(offset) then Parse.fail[Char]("unclosed link")
                else ()
            }
            body <- readUntilString("](")
            _    <- Parse.literal("](")
            url  <- readUntilChar(')')
            _    <- Parse.literal(')')
        yield
            val text = parseInline(body, options)
            allowed(url, options).fold(UI.fragment(text.toSeq*): UI) { href =>
                if options.html || href.startsWith("#") then UI.a.href(toHref(href, options))(html(text)*)
                else UI.a.href(toHref(href, options), Target.Blank)(html(text)*)
            }

    /** `**text**` -> a bold `md-strong` span. */
    private def bold(closers: Closers)(using Frame): UI < Parse[Char] =
        for
            _ <- Parse.literal("**")
            _ <- Parse.position[Char].map { offset =>
                if !closers.hasNonEmptySpan("**", offset) then Parse.fail[Char]("unclosed bold") else ()
            }
            inner <- nonEmptyUntilString("**")
            _     <- Parse.literal("**")
        yield UI.span.cssClass("md-strong")(Ast.Text(inner))

    /** `*text*` -> an italic span. */
    private def italic(closers: Closers)(using Frame): UI < Parse[Char] =
        for
            _ <- Parse.literal('*')
            _ <- Parse.position[Char].map { offset =>
                if !closers.hasNonEmptySpan("*", offset) then Parse.fail[Char]("unclosed italic") else ()
            }
            inner <- nonEmptyUntilChar('*')
            _     <- Parse.literal('*')
        yield UI.span.style(Style.italic)(Ast.Text(inner))

    // A CommonMark inline code span: a run of N backticks, then content up to the next run of N
    // backticks. The delimiter length matters: a span opened with N backticks lets runs of OTHER
    // lengths appear verbatim inside it. A single backtick (N=1) is the common case.
    //
    // An UNMATCHED opening run (no closing run of the same length) is LITERAL text, consumed here so
    // parsing resumes AFTER the run. This is the CommonMark rule: without it, a bare ```scala in
    // prose would leave the failed branch to backtrack one char and re-enter on the next backtick,
    // and the leftover single backtick would open a span that swallows the prose up to the next real
    // inline-code backtick.
    //
    // The content is read to the closing run in one step: a scan that tries the closing run at each
    // character costs the run's length at each, quadratic for a long run over content of shorter runs.
    private def inlineCode(closers: Closers)(using Frame): UI < Parse[Char] =
        for
            ticks <- Parse.readWhile[Char](_ == '`').map(_.mkString).map { run =>
                if run.isEmpty then Parse.fail[Char]("no opening backtick run") else run
            }
            offset <- Parse.position[Char]
            node   <- closers.next(ticks, offset) match
                // No matching close: the opening run is literal backticks (already consumed above).
                case Absent           => Kyo.lift(Ast.Text(ticks): UI)
                case Present(closing) =>
                    for
                        inner <- Parse.read((input: ParseInput[Char]) =>
                            Result.succeed((input.advance(closing - offset), input.remaining.take(closing - offset).mkString))
                        )
                        _ <- Parse.literal(ticks)
                    yield UI.code(Ast.Text(stripInlineCodeSpan(inner)))
        yield node

    // CommonMark: when a code span's content has a non-space character and begins AND ends with a
    // space, strip exactly one space from each end. Content that is all spaces, or padded on one
    // side only, is left untouched.
    private def stripInlineCodeSpan(s: String): String =
        if s.length >= 2 && s.startsWith(" ") && s.endsWith(" ") && s.exists(_ != ' ') then s.substring(1, s.length - 1)
        else s

    /** `<...>` -> a verbatim `UI.rawHtml` leaf for inline HTML snippets, or escaped text when
      * `options` does not allow HTML.
      */
    private def inlineHtml(options: Options, closers: Closers)(using Frame): UI < Parse[Char] =
        for
            _ <- Parse.literal('<')
            _ <- Parse.position[Char].map { offset =>
                if !closers.hasNonEmptySpan(">", offset) then Parse.fail[Char]("unclosed inline HTML") else ()
            }
            inner <- nonEmptyUntilChar('>')
            _     <- Parse.literal('>')
        yield
            val snippet = "<" + inner + ">"
            if options.html then UI.rawHtml(snippet) else Ast.Text(snippet)

    /** Read at least one character up to (not including) `stop`; drops the branch if the run is
      * empty so an unmatched opener falls through to a literal character.
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

    /** Run a `Parse[Char]` parser over `input`, requiring it to consume the entire input, and
      * return its result as a `Maybe`. A failed or incomplete parse yields `Absent`, which the
      * callers turn into their total fall-through. The `Parse[Char]` effect is fully discharged here
      * (effect row `Any`), so the pure result is evaluated synchronously.
      */
    private def runParser[A](input: String)(parser: A < Parse[Char])(using Frame): Maybe[A] =
        Parse.runResult(input)(Parse.entireInput(parser)).map(_.out).eval

end Markdown
