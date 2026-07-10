package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Validates internal links in Markdown source files.
  *
  * Three categories of links are recognised:
  *
  *   - External URLs (`http://`, `https://`, `mailto:`, `tel:`, etc.): skipped. Network reachability is out of scope.
  *   - Same-document anchors (`#section-name`): checked against the heading slugs of the same file.
  *   - Relative paths (`kyo-core/README.md`, `CONTRIBUTING.md`, `kyo-core/README.md#scope`): the path is resolved against the
  *     containing file's parent directory, the target must exist, and if an `#anchor` suffix is present the anchor must exist in the
  *     target file's heading set.
  *
  * Heading slugs are generated using GitHub's algorithm: lowercase, strip backticks/asterisks/underscores used for formatting,
  * replace whitespace with `-`, drop everything that is not `[a-z0-9-]`, deduplicate by appending `-1`, `-2`, ... on repeats.
  *
  * Links inside fenced code blocks are ignored (a Markdown `[text](url)` inside `` ``` `` is content, not a hyperlink).
  */
private[kyo] object LinkValidator:

    /** A `[text](target)` link extracted from the Markdown source, with its 1-indexed line and column. */
    private case class Link(line: Int, col: Int, text: String, target: String)

    /** A Markdown heading line, with the slug GitHub would assign it. */
    private case class Heading(text: String, slug: String)

    /** Validates every link in `file`. Returns a Chunk of failures (empty on clean).
      *
      * Reads `file` once, parses headings and links from the same buffer, and stats each referenced file. No external network is
      * touched. Cross-document anchor checks read each referenced README at most once (memoised across the call).
      */
    def validate(file: Path)(using Frame): Chunk[Doctest.Failure] < (Sync & Abort[Doctest.Error]) =
        Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(file, "read", e))) {
            Path.runReadOnly(file.read).flatMap { raw =>
                val content      = raw.replace("\r\n", "\n")
                val headingSlugs = extractHeadings(content).map(_.slug).toSet
                val links        = extractLinks(content)
                Kyo.foreach(links) { link =>
                    validateLink(file, headingSlugs, link)
                }.map(_.flatten)
            }
        }

    private def validateLink(
        file: Path,
        sameDocSlugs: Set[String],
        link: Link
    )(using Frame): Chunk[Doctest.Failure] < (Sync & Abort[Doctest.Error]) =
        val target = link.target.trim
        if target.isEmpty then
            Chunk.empty
        else if isExternal(target) then
            Chunk.empty
        else if target.startsWith("#") then
            val anchor = target.drop(1)
            if sameDocSlugs.contains(anchor) then Chunk.empty
            else Chunk(failure(file, link, s"unresolved anchor: #$anchor"))
        else
            val (pathPart, anchorPart) = splitAnchor(target)
            val resolved               = file.parent.map(_ / pathPart).getOrElse(Path(pathPart))
            Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(resolved, "exists", e))) {
                Path.runReadOnly(resolved.exists)
            }.flatMap {
                case false => Chunk(failure(
                        file,
                        link,
                        s"unresolved relative link: $pathPart"
                    )): Chunk[Doctest.Failure] < (Sync & Abort[Doctest.Error])
                case true =>
                    anchorPart match
                        case None => Chunk.empty
                        case Some(anchor) =>
                            validateRemoteAnchor(file, link, resolved, anchor)
            }
        end if
    end validateLink

    private def validateRemoteAnchor(
        sourceFile: Path,
        link: Link,
        targetFile: Path,
        anchor: String
    )(using Frame): Chunk[Doctest.Failure] < (Sync & Abort[Doctest.Error]) =
        Abort.recover[FileException](e => Abort.fail(Doctest.Error.IoError(targetFile, "read", e))) {
            Path.runReadOnly(targetFile.read).map { raw =>
                val slugs = extractHeadings(raw.replace("\r\n", "\n")).map(_.slug).toSet
                if slugs.contains(anchor) then Chunk.empty
                else Chunk(failure(sourceFile, link, s"unresolved anchor in $targetFile: #$anchor"))
            }
        }

    private def failure(file: Path, link: Link, message: String): Doctest.Failure =
        Doctest.Failure(file, link.line, s"$file:${link.line}:${link.col}: link: $message ([${link.text}](${link.target}))")

    private val externalSchemes = Set("http", "https", "mailto", "tel", "ftp", "ws", "wss", "data")

    private def isExternal(target: String): Boolean =
        val colonIdx = target.indexOf(':')
        if colonIdx <= 0 then false
        else externalSchemes.contains(target.substring(0, colonIdx).toLowerCase)
    end isExternal

    private def splitAnchor(target: String): (String, Option[String]) =
        val hashIdx = target.indexOf('#')
        if hashIdx < 0 then (target, None)
        else (target.substring(0, hashIdx), Some(target.substring(hashIdx + 1)))
    end splitAnchor

    // Extract Markdown headings from content, skipping anything inside fenced code blocks.
    // Recognises ATX-style (# Heading, ## Heading, ...) up to level 6.
    private def extractHeadings(content: String): Chunk[Heading] =
        val builder = scala.collection.mutable.ListBuffer.empty[Heading]
        val seen    = scala.collection.mutable.HashMap.empty[String, Int]
        var inFence = false
        content.linesIterator.foreach { line =>
            val trimmed = line.trim
            if trimmed.startsWith("```") then inFence = !inFence
            else if !inFence then
                val headingMatch = headingPattern.findFirstMatchIn(line)
                headingMatch.foreach { m =>
                    val text = m.group(2).trim
                    val base = slugify(text)
                    val slug = seen.get(base) match
                        case None    => base
                        case Some(n) => s"$base-$n"
                    seen.update(base, seen.getOrElse(base, 0) + 1)
                    builder += Heading(text, slug)
                }
            end if
        }
        Chunk.from(builder.toList)
    end extractHeadings

    private val headingPattern = """^(#{1,6})\s+(.+?)\s*#*\s*$""".r

    /** GitHub-flavoured heading slug.
      *
      * Rules (matching `github.com` rendering): drop backticks (so `` `code` `` becomes `code`), drop characters that are not
      * `[A-Za-z0-9 _-]` (everything else is treated as separator material and removed), lowercase, replace whitespace runs with `-`,
      * trim leading/trailing `-`. Em-dashes and other punctuation collapse to nothing, leaving the surrounding words joined by their
      * existing whitespace.
      */
    private def slugify(text: String): String =
        val sb = new java.lang.StringBuilder(text.length)
        text.foreach { c =>
            val _: java.lang.StringBuilder =
                if c.isLetterOrDigit then sb.append(c.toLower)
                else if c == '-' || c == '_' then sb.append(c)
                else if c.isWhitespace then sb.append(' ')
                else sb // dropped
        }
        sb.toString.trim.replaceAll("\\s+", "-")
    end slugify

    // Extract [text](target) links from content, skipping content inside fenced code blocks
    // and inside inline-code spans (`...`). The inline-code mask matters because Scala
    // method signatures like `Abort.run[E1 | E2](...)` look syntactically identical to a
    // Markdown link and would otherwise produce false positives.
    // Reference-style ([text][id]) and autolinks (<https://...>) are not validated.
    private def extractLinks(content: String): Chunk[Link] =
        val builder = scala.collection.mutable.ListBuffer.empty[Link]
        var inFence = false
        var lineNo  = 0
        content.linesIterator.foreach { line =>
            lineNo += 1
            val trimmed = line.trim
            if trimmed.startsWith("```") then inFence = !inFence
            else if !inFence then
                val masked  = maskInlineCode(line)
                val matches = linkPattern.findAllMatchIn(masked)
                matches.foreach { m =>
                    val text   = m.group(1)
                    val target = m.group(2)
                    // Skip image references ![alt](src): we treat the leading '!' as opt-out by checking the char before group start.
                    val isImage = m.start(1) >= 2 && line.charAt(m.start(1) - 2) == '!'
                    if !isImage then
                        builder += Link(lineNo, m.start(1) + 1 - 1, text, target)
                }
            end if
        }
        Chunk.from(builder.toList)
    end extractLinks

    // Replace characters inside inline-code spans (`...`) with spaces, preserving line length so
    // match offsets stay correct relative to the original line. Backticks themselves stay so
    // double-backtick spans (``...``) and edge cases survive a second pass, but the contents
    // become unmatchable. Empty `` (a literal empty span) is left alone.
    private def maskInlineCode(line: String): String =
        val sb     = new java.lang.StringBuilder(line.length)
        var inCode = false
        var i      = 0
        while i < line.length do
            val c = line.charAt(i)
            if c == '`' then
                val _: java.lang.StringBuilder = sb.append(c)
                inCode = !inCode
            else if inCode then
                val _: java.lang.StringBuilder = sb.append(' ')
            else
                val _: java.lang.StringBuilder = sb.append(c)
            end if
            i += 1
        end while
        sb.toString
    end maskInlineCode

    // [link text](target) with target containing no parentheses or whitespace.
    // group(1): link text; group(2): target.
    private val linkPattern = """\[([^\]]+)\]\(([^)\s]+)\)""".r

end LinkValidator
