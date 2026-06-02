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
  * The grammar is bounded to the construct set enumerated in RI-002. Unknown block constructs degrade
  * gracefully to `UI.p` containing the verbatim line text; the effect row is `Sync` only (no
  * `Abort`). The transpiler never raises an exception for malformed input.
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
      * The effect row is `Sync` only. The function never aborts: unknown constructs are degraded to
      * plain `UI.p` / `UI.text` nodes, and an empty source returns `Rendered(UI.empty, Chunk.empty)`
      * without raising any error.
      *
      * @param source
      *   The Markdown text to transpile. May be empty.
      */
    def transpile(source: String)(using Frame): Rendered < Sync =
        Sync.defer {
            if source.isBlank then Rendered(UI.empty, Chunk.empty)
            else parseArticle(stripDoctest(source))
        }

    // ---- private helpers ----

    /** Remove `<!-- doctest:setup ... -->` comment blocks (and the fenced scala block they wrap)
      * and normalize fenced info-strings to the first whitespace-delimited token.
      */
    private def stripDoctest(source: String): String =
        // Remove <!-- doctest:setup ... --> blocks, including any trailing fenced block.
        val noComments = removeDocTestBlocks(source)
        // Normalize fenced info-strings: "scala doctest:scope=inherited" -> "scala"
        normalizeInfoStrings(noComments)
    end stripDoctest

    private def removeDocTestBlocks(source: String): String =
        val lines  = source.linesIterator.toArray
        val result = new mutable.ArrayBuffer[String]()
        var i      = 0
        while i < lines.length do
            val line = lines(i)
            if line.trim.startsWith("<!--") && line.contains("doctest") then
                // Skip the comment line (possibly multi-line, but RI-002 uses single-line <!-- ... -->).
                i += 1
                // Skip any immediately following fenced block.
                if i < lines.length && lines(i).trim.startsWith("```") then
                    i += 1
                    while i < lines.length && !lines(i).trim.startsWith("```") do
                        i += 1
                    if i < lines.length then i += 1 // consume closing ```
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
                // Normalize: take only the first token of the info string.
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

    /** Parse the pre-processed Markdown string into a [[Rendered]] value.
      *
      * Block-level grammar processes lines sequentially. Inline grammar is applied per text run.
      * Heading slugs are tracked in a mutable map local to this call; duplicate slugs receive `-2`
      * (then `-3`, etc.) suffixes.
      */
    private def parseArticle(cleaned: String)(using Frame): Rendered =
        val lines      = cleaned.linesIterator.toArray
        val blocks     = new mutable.ArrayBuffer[UI]()
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

        var i = 0

        // Skip leading HTML comments (RI-002 trap 5).
        while i < lines.length && lines(i).trim.startsWith("<!--") do
            // Skip until comment close.
            while i < lines.length && !lines(i).contains("-->") do i += 1
            if i < lines.length then i += 1
        end while

        while i < lines.length do
            val line    = lines(i)
            val trimmed = line.trim

            // Blank line: skip.
            if trimmed.isEmpty then
                i += 1

            // ATX heading: # H1 through #### H4.
            else if trimmed.startsWith("# ") || trimmed.startsWith("## ") ||
                trimmed.startsWith("### ") || trimmed.startsWith("#### ")
            then
                val level =
                    if trimmed.startsWith("#### ") then 4
                    else if trimmed.startsWith("### ") then 3
                    else if trimmed.startsWith("## ") then 2
                    else 1
                val hashes      = "#" * level + " "
                val text        = trimmed.drop(hashes.length).trim
                val slug        = makeSlug(text)
                val inlineNodes = parseInline(text)
                val heading: UI = level match
                    case 1 => UI.h1.id(slug)(inlineNodes*)
                    case 2 => UI.h2.id(slug)(inlineNodes*)
                    case 3 => UI.h3.id(slug)(inlineNodes*)
                    case _ => UI.h4.id(slug)(inlineNodes*)
                blocks += heading
                headings += Heading(level, text, slug)
                i += 1

            // Fenced code block: ``` or ```lang
            else if trimmed.startsWith("```") then
                val lang = trimmed.drop(3).trim
                i += 1
                val codeLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && !lines(i).trim.startsWith("```") do
                    codeLines += lines(i)
                    i += 1
                if i < lines.length then i += 1 // consume closing ```
                val body        = codeLines.mkString("\n")
                val codeContent = highlight(lang, body)
                blocks += UI.pre(UI.code(codeContent))

            // Blockquote lines.
            else if trimmed.startsWith("> ") || trimmed == ">" then
                // Collect all consecutive blockquote lines.
                val bqLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && (lines(i).trim.startsWith("> ") || lines(i).trim == ">") do
                    val l = lines(i).trim
                    bqLines += (if l == ">" then "" else l.drop(2))
                    i += 1
                end while
                val bqContent = bqLines.mkString("\n")
                blocks += parseBlockquote(bqContent)

            // HTML comment: skip.
            else if trimmed.startsWith("<!--") then
                while i < lines.length && !lines(i).contains("-->") do i += 1
                if i < lines.length then i += 1

            // GFM pipe table: lines starting with |.
            else if trimmed.startsWith("|") then
                val tableLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && lines(i).trim.startsWith("|") do
                    tableLines += lines(i).trim
                    i += 1
                blocks += parseTable(tableLines.toSeq)

            // Unordered list: - or  - (two-space indent for sub-items).
            else if trimmed.startsWith("- ") || line.startsWith("  - ") then
                val listBlock = parseUnorderedList(lines, i)
                blocks += listBlock._1
                i = listBlock._2

            // Ordered list: N.
            else if trimmed.length > 2 && trimmed.head.isDigit && trimmed.drop(1).startsWith(". ") then
                val listItems = new mutable.ArrayBuffer[Ast.Li]()
                while i < lines.length &&
                    lines(i).trim.nonEmpty &&
                    lines(i).trim.head.isDigit &&
                    lines(i).trim.drop(1).startsWith(". ")
                do
                    val text = lines(i).trim.dropWhile(_.isDigit).drop(2)
                    listItems += UI.li(parseInline(text)*)
                    i += 1
                end while
                blocks += UI.ol(listItems.toSeq*)

            // Block-level raw HTML embed: lines starting with <img or <a only (root README corpus).
            // <img is self-contained (single line). <a may wrap <img on subsequent lines; coalesce
            // until and including the closing </a> line. The embed is wrapped in UI.p so the article
            // body stays a real UI node and the <a><img></a> nesting is preserved as one unit.
            else if trimmed.startsWith("<img") || trimmed.startsWith("<a") then
                if trimmed.startsWith("<img") then
                    blocks += UI.p(UI.rawHtml(trimmed))
                    i += 1
                else
                    // Opener is <a ...; consume lines until </a>.
                    val embedLines = new mutable.ArrayBuffer[String]()
                    embedLines += trimmed
                    i += 1
                    while i < lines.length && !embedLines.last.trim.contains("</a>") do
                        embedLines += lines(i).trim
                        i += 1
                    end while
                    blocks += UI.p(UI.rawHtml(embedLines.mkString("\n")))
                end if

            // Paragraph: accumulate non-blank lines.
            else
                val paraLines = new mutable.ArrayBuffer[String]()
                while i < lines.length &&
                    lines(i).trim.nonEmpty &&
                    !lines(i).trim.startsWith("#") &&
                    !lines(i).trim.startsWith("```") &&
                    !lines(i).trim.startsWith("> ") &&
                    !lines(i).trim.startsWith("- ") &&
                    !lines(i).startsWith("  - ") &&
                    !lines(i).trim.startsWith("|") &&
                    !lines(i).trim.startsWith("<!--") &&
                    !(lines(i).trim.nonEmpty && lines(i).trim.head.isDigit && lines(i).trim.drop(1).startsWith(". "))
                do
                    paraLines += lines(i).trim
                    i += 1
                end while
                val text = paraLines.mkString(" ")
                blocks += UI.p(parseInline(text)*)
            end if
        end while

        val article: UI =
            if blocks.isEmpty then UI.empty
            else UI.fragment(blocks.toSeq*)
        Rendered(article, Chunk.from(headings.toSeq))
    end parseArticle

    private def parseBlockquote(content: String)(using Frame): UI =
        val lines = content.linesIterator.toArray
        // Detect if it starts with a fenced code block inside the blockquote.
        // Detect callout or generic blockquote.
        val firstNonEmpty = content.linesIterator.find(_.trim.nonEmpty).getOrElse("")
        if firstNonEmpty.trim.startsWith("**Note:**") then
            val bqBlocks = parseBlockquoteContent(content)
            UI.div.cssClass("callout callout-note")(bqBlocks*)
        else if firstNonEmpty.trim.startsWith("**Caution:**") then
            val bqBlocks = parseBlockquoteContent(content)
            UI.div.cssClass("callout callout-caution")(bqBlocks*)
        else
            // Generic blockquote: check for other **Label:** patterns.
            val bqBlocks = parseBlockquoteContent(content)
            UI.div.cssClass("blockquote")(bqBlocks*)
        end if
    end parseBlockquote

    private def parseBlockquoteContent(content: String)(using Frame): Seq[UI] =
        val lines  = content.linesIterator.toArray
        val result = new mutable.ArrayBuffer[UI]()
        var i      = 0
        while i < lines.length do
            val trimmed = lines(i).trim
            if trimmed.isEmpty then
                i += 1
            else if trimmed.startsWith("```") then
                // Fenced code inside blockquote.
                val lang = trimmed.drop(3).trim
                i += 1
                val codeLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && !lines(i).trim.startsWith("```") do
                    codeLines += lines(i)
                    i += 1
                if i < lines.length then i += 1
                val body = codeLines.mkString("\n")
                result += UI.pre(UI.code(highlight(lang, body)))
            else
                // Collect paragraph lines.
                val paraLines = new mutable.ArrayBuffer[String]()
                while i < lines.length && lines(i).trim.nonEmpty && !lines(i).trim.startsWith("```") do
                    paraLines += lines(i).trim
                    i += 1
                val text = paraLines.mkString(" ")
                result += UI.p(parseInline(text)*)
            end if
        end while
        result.toSeq
    end parseBlockquoteContent

    /** Parse a GFM pipe table. The first row is the header; the second is the separator; remaining
      * rows are body rows. Cell content is re-parsed with parseInline.
      */
    private def parseTable(tableLines: Seq[String])(using Frame): UI =
        if tableLines.length < 2 then
            // Malformed table (missing separator): degrade to paragraph.
            UI.p(Ast.Text(tableLines.headOption.getOrElse("")))
        else
            def parseCells(line: String): Seq[String] =
                line.stripPrefix("|").stripSuffix("|").split("\\|", -1).map(_.trim).toSeq

            val headerRow = tableLines.head
            val bodyRows  = if tableLines.length > 2 then tableLines.drop(2) else Seq.empty

            val headerCells = parseCells(headerRow)
            val headerTr    = UI.tr(headerCells.map(cell => UI.th(parseInline(cell)*))*)

            val bodyTrs = bodyRows.map { row =>
                val cells = parseCells(row)
                UI.tr(cells.map(cell => UI.td(parseInline(cell)*))*)
            }

            UI.table(headerTr +: bodyTrs*)
        end if
    end parseTable

    /** Parse an unordered list starting at line `start`, handling two-space sub-indented items.
      * Returns the `UI.ul` node and the index of the next unprocessed line.
      */
    private def parseUnorderedList(lines: Array[String], start: Int)(using Frame): (UI, Int) =
        val items = new mutable.ArrayBuffer[Ast.Li]()
        var i     = start
        while i < lines.length &&
            (lines(i).trim.startsWith("- ") || lines(i).startsWith("  - "))
        do
            val line = lines(i)
            if line.startsWith("  - ") then
                // Sub-item: skip here; handled by the parent item's sub-list collection.
                // This branch handles lines already-matched as sub-items but not yet consumed.
                i += 1
            else
                // Top-level item.
                val text = line.trim.drop(2)
                i += 1
                // Collect sub-items (two-space indent).
                val subItems = new mutable.ArrayBuffer[Ast.Li]()
                while i < lines.length && lines(i).startsWith("  - ") do
                    val subText = lines(i).trim.drop(2)
                    subItems += UI.li(parseInline(subText)*)
                    i += 1
                end while
                if subItems.isEmpty then
                    items += UI.li(parseInline(text)*)
                else
                    items += UI.li((parseInline(text) :+ UI.ul(subItems.toSeq*))*)
                end if
            end if
        end while
        (UI.ul(items.toSeq*), i)
    end parseUnorderedList

    /** Parse inline Markdown to a sequence of UI nodes.
      *
      * Handles: bold (`**text**`), italic (`*text*`), inline code (`` `text` ``), links (`[text](url)`),
      * badge images (`![alt](url)`), linked badges (`[![alt](img)](link)`), inline raw HTML (`<...>`),
      * and plain text runs.
      */

    /** Convert a raw URL string to a `Href`. Treats `#id` as Fragment, `http(s)://...` as External,
      * and everything else as Path.
      */
    private def toHref(url: String): Href =
        if url.startsWith("#") then Href.Fragment(url.drop(1))
        else if url.startsWith("https://") then Href.External("https", url.drop(6))
        else if url.startsWith("http://") then Href.External("http", url.drop(5))
        else Href.Path(url)
    end toHref

    private def parseInline(text: String)(using Frame): Seq[UI] =
        if text.isEmpty then Seq(Ast.Text(""))
        else
            val result = new mutable.ArrayBuffer[UI]()
            var pos    = 0

            while pos < text.length do
                val ch = text.charAt(pos)

                // Linked badge: [![alt](img)](link)
                if ch == '[' && pos + 1 < text.length && text.charAt(pos + 1) == '!' &&
                    pos + 2 < text.length && text.charAt(pos + 2) == '['
                then
                    // Linked badge: [![alt](img-url)](link-url)
                    // pos=0: `[`, pos+1: `!`, pos+2: `[`, alt starts at pos+3
                    val altStart = pos + 3
                    val altEnd   = text.indexOf(']', altStart)
                    if altEnd > altStart && altEnd + 1 < text.length && text.charAt(altEnd + 1) == '(' then
                        val alt      = text.substring(altStart, altEnd)
                        val imgStart = altEnd + 2
                        val imgEnd   = text.indexOf(')', imgStart)
                        // After img-url ')' must come '](' for the outer link wrapper.
                        if imgEnd > imgStart && imgEnd + 2 < text.length &&
                            text.charAt(imgEnd + 1) == ']' && text.charAt(imgEnd + 2) == '('
                        then
                            val imgUrl    = text.substring(imgStart, imgEnd)
                            val linkStart = imgEnd + 3
                            val linkEnd   = text.indexOf(')', linkStart)
                            if linkEnd > linkStart then
                                val linkUrl = text.substring(linkStart, linkEnd)
                                result += UI.a.href(toHref(linkUrl))(UI.img(ImgSrc.Path(imgUrl), alt))
                                pos = linkEnd + 1
                            else
                                result += Ast.Text(ch.toString)
                                pos += 1
                            end if
                        else
                            result += Ast.Text(ch.toString)
                            pos += 1
                        end if
                    else
                        result += Ast.Text(ch.toString)
                        pos += 1
                    end if

                // Badge image: ![alt](url)
                else if ch == '!' && pos + 1 < text.length && text.charAt(pos + 1) == '[' then
                    val closeBracket = text.indexOf("](", pos + 2)
                    if closeBracket > 0 then
                        val alt      = text.substring(pos + 2, closeBracket)
                        val urlStart = closeBracket + 2
                        val urlEnd   = text.indexOf(')', urlStart)
                        if urlEnd > 0 then
                            val url = text.substring(urlStart, urlEnd)
                            result += UI.img(ImgSrc.Path(url), alt)
                            pos = urlEnd + 1
                        else
                            result += Ast.Text("!")
                            pos += 1
                        end if
                    else
                        result += Ast.Text("!")
                        pos += 1
                    end if

                // Link: [text](url)
                else if ch == '[' then
                    val closeBracket = text.indexOf("](", pos + 1)
                    if closeBracket > 0 then
                        val linkText = text.substring(pos + 1, closeBracket)
                        val urlStart = closeBracket + 2
                        val urlEnd   = text.indexOf(')', urlStart)
                        if urlEnd > 0 then
                            val url        = text.substring(urlStart, urlEnd)
                            val innerNodes = parseInline(linkText)
                            result += UI.a.href(toHref(url))(innerNodes*)
                            pos = urlEnd + 1
                        else
                            result += Ast.Text(ch.toString)
                            pos += 1
                        end if
                    else
                        result += Ast.Text(ch.toString)
                        pos += 1
                    end if

                // Bold: **text**
                else if ch == '*' && pos + 1 < text.length && text.charAt(pos + 1) == '*' then
                    val closePos = text.indexOf("**", pos + 2)
                    if closePos > pos + 2 then
                        val inner = text.substring(pos + 2, closePos)
                        result += UI.span.cssClass("md-strong")(Ast.Text(inner))
                        pos = closePos + 2
                    else
                        result += Ast.Text("*")
                        pos += 1
                    end if

                // Italic: *text*
                else if ch == '*' then
                    val closePos = text.indexOf("*", pos + 1)
                    if closePos > pos then
                        val inner = text.substring(pos + 1, closePos)
                        result += UI.span.style(Style.italic)(Ast.Text(inner))
                        pos = closePos + 1
                    else
                        result += Ast.Text(ch.toString)
                        pos += 1
                    end if

                // Inline code: `text`
                else if ch == '`' then
                    val closePos = text.indexOf('`', pos + 1)
                    if closePos > pos then
                        val code = text.substring(pos + 1, closePos)
                        result += UI.code(Ast.Text(code))
                        pos = closePos + 1
                    else
                        result += Ast.Text(ch.toString)
                        pos += 1
                    end if

                // Inline raw HTML: <...>
                else if ch == '<' then
                    val closePos = text.indexOf('>', pos + 1)
                    if closePos > 0 then
                        val snippet = text.substring(pos, closePos + 1)
                        result += UI.rawHtml(snippet)
                        pos = closePos + 1
                    else
                        result += Ast.Text(ch.toString)
                        pos += 1
                    end if

                // Plain text: accumulate until next special character.
                else
                    val start = pos
                    while pos < text.length &&
                        text.charAt(pos) != '*' &&
                        text.charAt(pos) != '`' &&
                        text.charAt(pos) != '[' &&
                        text.charAt(pos) != '!' &&
                        text.charAt(pos) != '<'
                    do
                        pos += 1
                    end while
                    result += Ast.Text(text.substring(start, pos))
                end if
            end while

            result.toSeq
        end if
    end parseInline

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

            // Triple-quoted string.
            if pos + 2 < src.length && src.startsWith("\"\"\"", pos) then
                val end      = src.indexOf("\"\"\"", pos + 3)
                val closeEnd = if end >= 0 then end + 3 else src.length
                tokens += UI.span.cssClass("tok-string")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd

            // Line comment.
            else if pos + 1 < src.length && src.charAt(pos) == '/' && src.charAt(pos + 1) == '/' then
                val end      = src.indexOf('\n', pos)
                val closeEnd = if end >= 0 then end else src.length
                tokens += UI.span.cssClass("tok-comment")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd

            // Block comment.
            else if pos + 1 < src.length && src.charAt(pos) == '/' && src.charAt(pos + 1) == '*' then
                val end      = src.indexOf("*/", pos + 2)
                val closeEnd = if end >= 0 then end + 2 else src.length
                tokens += UI.span.cssClass("tok-comment")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd

            // Double-quoted string.
            else if ch == '"' then
                var p2 = pos + 1
                while p2 < src.length && src.charAt(p2) != '"' && src.charAt(p2) != '\n' do
                    if src.charAt(p2) == '\\' then p2 += 1
                    p2 += 1
                end while
                val closeEnd = if p2 < src.length then p2 + 1 else src.length
                tokens += UI.span.cssClass("tok-string")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd

            // SBT operators.
            else if sbtOps.exists(op => src.startsWith(op, pos)) then
                val op = sbtOps.filter(op => src.startsWith(op, pos)).maxBy(_.length)
                tokens += UI.span.cssClass("tok-keyword")(Ast.Text(op))
                pos += op.length

            // Identifier or keyword.
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

            // Plain character.
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

            // Hash comment: # to end of line.
            if ch == '#' then
                val end      = src.indexOf('\n', pos)
                val closeEnd = if end >= 0 then end else src.length
                tokens += UI.span.cssClass("tok-comment")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd

            // Double-quoted string.
            else if ch == '"' then
                var p2 = pos + 1
                while p2 < src.length && src.charAt(p2) != '"' do
                    if src.charAt(p2) == '\\' then p2 += 1
                    p2 += 1
                end while
                val closeEnd = if p2 < src.length then p2 + 1 else src.length
                tokens += UI.span.cssClass("tok-string")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd

            // Single-quoted string.
            else if ch == '\'' then
                var p2 = pos + 1
                while p2 < src.length && src.charAt(p2) != '\'' do
                    p2 += 1
                val closeEnd = if p2 < src.length then p2 + 1 else src.length
                tokens += UI.span.cssClass("tok-string")(Ast.Text(src.substring(pos, closeEnd)))
                pos = closeEnd

            // Identifier or keyword.
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

            // Plain character(s).
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
                tokens += Ast.Text(src.substring(start, pos))
            end if
        end while

        UI.fragment(tokens.toSeq*)
    end tokenizeBash

end DocsMarkdown
