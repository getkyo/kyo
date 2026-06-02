package kyo.website

import kyo.*
import kyo.UI.*
import scala.collection.mutable

/** Cross-platform Markdown transpiler for the RI-002 dialect used in kyo module READMEs.
  *
  * Converts a single README Markdown source string to a [[DocsMarkdown.Rendered]] value containing a
  * `kyo-ui` UI article subtree and a heading outline. The article is a real `UI` tree (headings,
  * paragraphs, lists, tables, fenced code with token highlighting, callout divs, inline images and
  * links) ready to embed directly into a page rendered by `UI.runRenderPage`.
  *
  * The grammar is bounded to the construct set enumerated in RI-002 and is expressed with kyo-parse
  * `Parse[Char]` combinators (D6, "kyo all the way down"). A thin line-oriented splitter groups raw
  * lines into block segments; the inline content of each block, and the block-level recognizers
  * (headings, list markers, table cells, fence info-strings, badge/link/image inline tokens) are
  * genuine kyo-parse parsers run via `Parse.runResult`.
  *
  * Unknown inline constructs degrade gracefully to plain text via kyo-parse `recoverWith` +
  * `RecoverStrategy`; unknown block constructs degrade to `UI.p` containing the verbatim line text.
  * The effect row is `Sync` only (no `Abort`): malformed input degrades to plain text rather than
  * failing the row. A genuinely undefined evaluation (an exception thrown inside `Sync.defer`) would
  * still surface as a `Sync` panic; the design intent is that the bounded grammar plus the total
  * fall-throughs leave no malformed-corpus path that reaches one.
  *
  * Inline HTML snippets (such as `<img>` or `<a><img></a>` tags found in the root README) are
  * emitted as [[kyo.UI.Ast.RawHtml]] leaves via `UI.rawHtml`. All other text is HTML-escaped by the
  * renderer.
  *
  * @see
  *   [[DocsMarkdown.transpile]] for the main entry point
  * @see
  *   [[DocsMarkdown.Rendered]] for the output value type
  */
object DocsMarkdown:

    /** The article subtree and heading outline produced by [[transpile]].
      *
      * `article` is a `UI` value ready to embed into a `UI.runRenderPage` document. `headings` is the
      * ordered outline; each entry carries the same `slug` that was set as the `id` attribute on the
      * corresponding `UI.h1`..`UI.h4` element, so `article` id attributes and `headings` slugs are
      * always consistent (INV-004). Duplicate heading texts are disambiguated with a `-2` suffix on
      * both sides.
      */
    final case class Rendered(article: UI, headings: Chunk[Heading]) derives CanEqual

    /** A single heading entry in the document outline produced by [[transpile]].
      *
      * `level` is 1..4 (matching `# ` through `#### `). `text` is the plain-text heading content
      * (markup stripped). `slug` is the URL-safe anchor derived from `text`: lowercased, non-alphanumeric
      * characters replaced with `-`, runs of `-` collapsed, leading and trailing `-` removed, and
      * duplicate slugs disambiguated with a `-2` (then `-3`, etc.) suffix.
      */
    final case class Heading(level: Int, text: String, slug: String) derives CanEqual

    /** Transpile a single README Markdown source to a [[Rendered]] value.
      *
      * The effect row is `Sync` only (no `Abort`). Malformed input degrades rather than failing:
      * unknown inline constructs become plain `UI.text` (via kyo-parse `recoverWith`), unknown block
      * constructs become a `UI.p` carrying the verbatim line, and an empty source returns
      * `Rendered(UI.empty, Chunk.empty)`. The bounded RI-002 grammar plus those total fall-throughs
      * are designed so no malformed-corpus path reaches an undefined evaluation; the row stays `Sync`.
      *
      * @param source
      *   The Markdown text to transpile. May be empty.
      */
    def transpile(source: String)(using Frame): Rendered < Sync =
        Sync.defer {
            if source.isBlank then Rendered(UI.empty, Chunk.empty)
            else parseArticle(stripDoctest(source))
        }

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
                i += 1
                if i < lines.length && lines(i).trim.startsWith("```") then
                    i += 1
                    while i < lines.length && !lines(i).trim.startsWith("```") do
                        i += 1
                    if i < lines.length then i += 1
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

        // Skip leading HTML comments (RI-002 trap 5).
        while i < lines.length && lines(i).trim.startsWith("<!--") do
            while i < lines.length && !lines(i).contains("-->") do i += 1
            if i < lines.length then i += 1
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
                while i < lines.length && !lines(i).contains("-->") do i += 1
                if i < lines.length then i += 1
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
                val (level, text) = parseHeading(line)
                val slug          = makeSlug(text)
                val inlineNodes   = parseInline(text)
                val heading: UI = level match
                    case 1 => UI.h1.id(slug)(inlineNodes*)
                    case 2 => UI.h2.id(slug)(inlineNodes*)
                    case 3 => UI.h3.id(slug)(inlineNodes*)
                    case _ => UI.h4.id(slug)(inlineNodes*)
                uiBlocks += heading
                headings += Heading(level, text, slug)

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
        Rendered(article, Chunk.from(headings.toSeq))
    end parseArticle

    // ---- block-level kyo-parse parsers ----

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
      * (addressing the NOTE-1 naive-split limitation: an escaped pipe stays inside the cell rather
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
      * and everything else as Path.
      */
    private def toHref(url: String): Href =
        if url.startsWith("#") then Href.Fragment(url.drop(1))
        else if url.startsWith("https://") then Href.External("https", url.drop(6))
        else if url.startsWith("http://") then Href.External("http", url.drop(5))
        else Href.Path(url)
    end toHref

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

    /** Shared scala/sbt/bash tokenizer that emits `.tok-*` UI.span runs for keyword/string/comment
      * tokens and plain `UI.text` for the rest. The classes are styled by `WebsiteStyles.sheet`.
      *
      * For other/bare fences, returns a plain `Ast.Text(body)` leaf with no token spans.
      */
    private def highlight(lang: String, body: String)(using Frame): UI =
        lang match
            case "scala" | "sbt" => tokenizeScala(body)
            case "bash" | "sh"   => tokenizeBash(body)
            case _               => Ast.Text(body)
    end highlight

    private def tokenizeScala(body: String)(using Frame): UI =
        val keywords = Set(
            "def",
            "val",
            "var",
            "class",
            "object",
            "trait",
            "extends",
            "with",
            "import",
            "if",
            "else",
            "for",
            "yield",
            "match",
            "case",
            "return",
            "sealed",
            "final",
            "abstract",
            "override",
            "new",
            "type",
            "package",
            "given",
            "using",
            "inline",
            "opaque",
            "enum",
            "end",
            "then",
            "throws",
            "lazy",
            "private",
            "protected",
            "true",
            "false",
            "null",
            "this",
            "super"
        )
        val sbtOps = Set(":=", "+=", "++=", "%%", "%")

        val tokens = new mutable.ArrayBuffer[UI]()
        val src    = body
        var pos    = 0

        while pos < src.length do
            val ch = src.charAt(pos)

            if pos + 2 < src.length && src.startsWith("\"\"\"", pos) then
                val end      = src.indexOf("\"\"\"", pos + 3)
                val closeEnd = if end >= 0 then end + 3 else src.length
                tokens += UI.span.cssClass("tok-string")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd
            else if pos + 1 < src.length && src.charAt(pos) == '/' && src.charAt(pos + 1) == '/' then
                val end      = src.indexOf('\n', pos)
                val closeEnd = if end >= 0 then end else src.length
                tokens += UI.span.cssClass("tok-comment")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd
            else if pos + 1 < src.length && src.charAt(pos) == '/' && src.charAt(pos + 1) == '*' then
                val end      = src.indexOf("*/", pos + 2)
                val closeEnd = if end >= 0 then end + 2 else src.length
                tokens += UI.span.cssClass("tok-comment")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd
            else if ch == '"' then
                var p2 = pos + 1
                while p2 < src.length && src.charAt(p2) != '"' && src.charAt(p2) != '\n' do
                    if src.charAt(p2) == '\\' then p2 += 1
                    p2 += 1
                end while
                val closeEnd = if p2 < src.length then p2 + 1 else src.length
                tokens += UI.span.cssClass("tok-string")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd
            else if sbtOps.exists(op => src.startsWith(op, pos)) then
                val op = sbtOps.filter(op => src.startsWith(op, pos)).maxBy(_.length)
                tokens += UI.span.cssClass("tok-keyword")(Ast.Text(op))
                pos += op.length
            else if ch.isLetter || ch == '_' then
                val start = pos
                while pos < src.length && (src.charAt(pos).isLetterOrDigit || src.charAt(pos) == '_') do
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
                    src.charAt(pos) != '"' &&
                    src.charAt(pos) != '/' &&
                    !src.charAt(pos).isLetter &&
                    src.charAt(pos) != '_' &&
                    !sbtOps.exists(op => src.startsWith(op, pos))
                do
                    pos += 1
                end while
                // Forward-progress guard: a stop char that no specific branch consumed (a lone `/`,
                // a `:`/`+`/`%` that is not a full sbt operator) would leave `pos` unmoved and loop
                // forever. Emit it as one literal char and advance.
                if pos == start then pos += 1
                tokens += Ast.Text(src.substring(start, pos))
            end if
        end while

        UI.fragment(tokens.toSeq*)
    end tokenizeScala

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

end DocsMarkdown
