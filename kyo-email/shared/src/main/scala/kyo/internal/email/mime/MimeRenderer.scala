package kyo.internal.email.mime

import kyo.*
import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.internal.email.mime.HeaderCodec.DispositionKind
import kyo.internal.email.mime.HeaderCodec.WriteFailure
import kyo.internal.email.mime.TransferEncoding.Kind
import kyo.mime.Multipart

/** An [[Email.Message]] as MIME octets (RFC 5322, RFC 2045 to RFC 2049) that `MimeParser` and `MimeModel` read back as the same fields.
  *
  * The tree is the smallest that holds the model: a lone body is the message's only part; text and HTML are a `multipart/alternative`;
  * inline parts go in a `multipart/related` with the body they belong to; attachments follow in a `multipart/mixed`. The message's extra
  * headers come first, then the fields, then `MIME-Version` and the root part's headers.
  *
  * Each leaf takes the first encoding that carries its octets: `7bit`, then for text `quoted-printable` when it is not longer than
  * `base64`, then `base64`. A `message` or `multipart` leaf is `7bit` or `8bit`, the encodings RFC 2045 section 6.4 allows a composite
  * type, and `base64` only when neither can carry it.
  *
  * A multipart's boundary is `=_kyo<depth>_<k>`, with `k` the least number no enclosed line takes: no line of the enclosed octets, and no
  * line `--B` for a `boundary` B an enclosed leaf declares, starts with `--=_kyo<depth>_` followed by the digits of `k`. The parser matches
  * the longest boundary a line starts with, the innermost among equals, and opens a leaf's declared boundary while it reads that leaf, so
  * such a line would end the part early or be taken by the leaf.
  */
private[kyo] object MimeRenderer:

    /** Why a message cannot be rendered, with the header it concerns. */
    enum Failure derives CanEqual:
        case Header(name: String, problem: WriteFailure)
        case InvalidName(name: String)
        case ControlCharacter(name: String)
    end Failure

    def render(message: Email.Message)(using Frame): Result[Failure, Span[Byte]] =
        for
            extras <- all(message.headers.map(extraLine)).map(_.flatMap(_.toChunk))
            fields <- fieldLines(message)
            root   <- tree(message)
        yield
            val part    = rendered(root, 0)
            val closing = if part.multipart then Chunk(Crlf) else Chunk.empty
            assemble((extras ++ fields :+ "MIME-Version: 1.0\r\n").map(Utf8.encode) ++ part.headers ++ Chunk(Crlf) ++ part.body ++ closing)

    private val Crlf = Utf8.encode("\r\n")

    private val FieldNames = Chunk(
        "From",
        "Sender",
        "To",
        "Cc",
        "Bcc",
        "Reply-To",
        "Subject",
        "Date",
        "Message-ID",
        "In-Reply-To",
        "References",
        "MIME-Version",
        "Content-Type",
        "Content-Transfer-Encoding",
        "Content-Disposition",
        "Content-ID"
    )

    private def all[A](results: Chunk[Result[Failure, A]]): Result[Failure, Chunk[A]] =
        results.foldLeft(Result.succeed(Chunk.empty[A]): Result[Failure, Chunk[A]])((done, next) => done.flatMap(d => next.map(d.append)))

    private def line(name: String, units: Result[WriteFailure, Chunk[String]]): Result[Failure, String] =
        units.flatMap(HeaderCodec.fold(name, _)).mapFailure(Failure.Header(name, _))

    private def assemble(pieces: Chunk[Span[Byte]]): Span[Byte] =
        val out = new Array[Byte](pieces.foldLeft(0)(_ + _.size))
        discard(pieces.foldLeft(0)((at, piece) => at + piece.copyToArray(out, at)))
        // Unsafe: `out` is local and fully written; only the span escapes.
        Span.fromUnsafe(out)
    end assemble

    // The value is trimmed because the reader trims it (RFC 5322 section 2.2.3 unfolding), so a second render would write the trimmed one.
    private def extraLine(header: Email.Header): Result[Failure, Maybe[String]] =
        val name = header.name
        if FieldNames.exists(Ascii.equalsIgnoreCase(_, name)) then Result.succeed(Absent)
        else if name.isEmpty || !name.forall(c => c > ' ' && c < '\u007f' && c != ':') then Result.fail(Failure.InvalidName(name))
        else
            val value = header.value.substring(0, header.value.lastIndexWhere(!isWhiteSpace(_)) + 1).dropWhile(isWhiteSpace)
            if value.exists(c => c == '\r' || c == '\n') then Result.fail(Failure.Header(name, WriteFailure.LineBreakInValue))
            else if value.exists(isControl) then Result.fail(Failure.ControlCharacter(name))
            else line(name, Result.succeed(HeaderCodec.rawUnits(value))).map(Present(_))
            end if
        end if
    end extraLine

    private def isWhiteSpace(c: Char): Boolean = c == ' ' || c == '\t'

    // RFC 5322 section 2.2 and RFC 6532 section 3.2 allow printable US-ASCII, SP, HTAB and UTF-8 non-ASCII in a field body; C1 included
    // here.
    private def isControl(c: Char): Boolean = (c < ' ' && c != '\t') || (c >= '\u007f' && c <= '\u009f')

    private def fieldLines(m: Email.Message): Result[Failure, Chunk[String]] =
        def addresses(name: String, list: Chunk[Email.Address]): Maybe[Result[Failure, String]] =
            if list.isEmpty then Absent else Present(line(name, AddressCodec.render(list)))
        def ids(name: String, list: Chunk[Email.MessageId]): Maybe[Result[Failure, String]] =
            if list.isEmpty then Absent else Present(line(name, Result.succeed(MessageIdCodec.render(list))))
        all(Chunk(
            m.date.map(d => line("Date", DateCodec.render(d).map(Chunk(_)))),
            addresses("From", m.from),
            addresses("Sender", m.sender.toChunk),
            addresses("Reply-To", m.replyTo),
            addresses("To", m.to),
            addresses("Cc", m.cc),
            addresses("Bcc", m.bcc),
            if m.subject.isEmpty then Absent else Present(line("Subject", Result.succeed(HeaderCodec.unstructuredUnits(m.subject)))),
            ids("Message-ID", m.messageId.toChunk),
            ids("In-Reply-To", m.inReplyTo),
            ids("References", m.references)
        ).flatMap(_.toChunk))
    end fieldLines

    private enum Node:
        case Leaf(headers: Chunk[String], body: Span[Byte], eightBit: Boolean, declared: Maybe[String])
        case Multi(subType: String, children: Chunk[Node])

    private def tree(m: Email.Message)(using Frame): Result[Failure, Node] =
        for
            related <- all(m.inlineParts.map(p =>
                contentLeaf(p.contentType, p.fileName, p.content, DispositionKind.Inline, Present(p.contentId))
            ))
            attached <- all(m.attachments.map(a => contentLeaf(a.contentType, a.fileName, a.content, DispositionKind.Attachment, Absent)))
        yield
            val text = if m.text.isEmpty then Absent else Present(textLeaf("plain", m.text))
            val body =
                m.html match
                    case Present(html) =>
                        val shown =
                            if related.isEmpty then textLeaf("html", html) else Node.Multi("related", textLeaf("html", html) +: related)
                        Present(text.map(plain => Node.Multi("alternative", Chunk(plain, shown))).getOrElse(shown))
                    case Absent =>
                        if related.nonEmpty then Present(Node.Multi("related", textLeaf("plain", m.text) +: related)) else text
            if attached.nonEmpty then Node.Multi("mixed", body.toChunk ++ attached) else body.getOrElse(textLeaf("plain", ""))

    private def textLeaf(subType: String, text: String): Node =
        val octets          = Utf8.encode(text.replace("\n", "\r\n"))
        val (kind, encoded) =
            if isLineForm(octets, 76, high = false, plain = true) then (Kind.SevenBit, octets)
            else
                val quoted = TransferEncoding.encodeQuotedPrintable(octets)
                if quoted.size <= base64Size(octets.size) then (Kind.QuotedPrintable, quoted)
                else (Kind.Base64, TransferEncoding.encodeBase64(octets))
        Node.Leaf(Chunk(s"Content-Type: text/$subType; charset=utf-8\r\n", encodingLine(kind)), encoded, eightBit = false, Absent)
    end textLeaf

    private def contentLeaf(
        mediaType: Email.MediaType,
        fileName: Maybe[String],
        content: Span[Byte],
        disposition: DispositionKind.Inline.type | DispositionKind.Attachment.type,
        contentId: Maybe[Email.ContentId]
    )(using Frame): Result[Failure, Node] =
        val declared = if mediaType.mainType == "multipart" then mediaType.parameter("boundary").filter(_.nonEmpty) else Absent
        val kind     =
            if mediaType.mainType != "message" && mediaType.mainType != "multipart" then
                if isLineForm(content, 76, high = false, plain = true) then Kind.SevenBit else Kind.Base64
            // RFC 2045 section 6.4 forbids base64 for a composite type. A multipart leaf holding a delimiter of its own boundary is written
            // in base64 all the same: the parser opens that boundary whatever the encoding, so as it is the leaf would read back as a tree.
            else if declared.exists(b => startsSomeLine(content, Utf8.encode("--" + b))) then Kind.Base64
            else if isLineForm(content, 998, high = false, plain = false) then Kind.SevenBit
            else if isLineForm(content, 998, high = true, plain = false) then Kind.EightBit
            else Kind.Base64
        for
            typeLine        <- line("Content-Type", HeaderCodec.contentTypeUnits(mediaType))
            dispositionLine <- line("Content-Disposition", HeaderCodec.dispositionUnits(disposition, fileName.map("filename" -> _).toChunk))
            idLine          <- all(contentId.map(id => line("Content-ID", Result.succeed(Chunk("<" + id.value + ">")))).toChunk)
        yield Node.Leaf(
            Chunk(typeLine, encodingLine(kind), dispositionLine) ++ idLine,
            TransferEncoding.encode(kind, content),
            kind == Kind.EightBit,
            declared
        )
        end for
    end contentLeaf

    private def encodingLine(kind: Kind): String = s"Content-Transfer-Encoding: ${kind.label}\r\n"

    private def base64Size(octets: Int): Int =
        if octets == 0 then 0
        else
            val chars = (octets + 2) / 3 * 4
            chars + (chars + 75) / 76 * 2 - 2

    /** Whether `content` can be written as it is: no NUL, octets from 0x80 only with `high`, CR and LF only as CRLF, and lines of at most
      * `limit` octets. With `plain`, also no line ending in SP or HTAB and none starting `From ` or being `.`, which gateways change, and
      * no `=_`, which starts every boundary this renderer makes and never appears in quoted-printable or base64: so no part but a composite
      * leaf can hold a delimiter line.
      */
    private def isLineForm(content: Span[Byte], limit: Int, high: Boolean, plain: Boolean): Boolean =
        def lineEnds(start: Int, end: Int): Boolean =
            end - start <= limit &&
                !(plain && (
                    (end > start && (content(end - 1) == ' ' || content(end - 1) == '\t')) ||
                        (end - start == 1 && content(start) == '.') ||
                        (end - start >= 5 && content(start) == 'F' && content(start + 1) == 'r' && content(start + 2) == 'o' &&
                            content(start + 3) == 'm' && content(start + 4) == ' ')
                ))
        @scala.annotation.tailrec
        def loop(i: Int, lineStart: Int): Boolean =
            if i == content.size then lineEnds(lineStart, i)
            else
                val b = content(i)
                if b == '\r' then
                    if i + 1 < content.size && content(i + 1) == '\n' && lineEnds(lineStart, i) then loop(i + 2, i + 2) else false
                else if b == '\n' || b == 0 || (b < 0 && !high) then false
                else if plain && b == '=' && i + 1 < content.size && content(i + 1) == '_' then false
                else loop(i + 1, lineStart)
                end if
        loop(0, 0)
    end isLineForm

    private def startsSomeLine(content: Span[Byte], prefix: Span[Byte]): Boolean =
        def at(i: Int): Boolean = i + prefix.size <= content.size && (0 until prefix.size).forall(j => content(i + j) == prefix(j))
        (0 until content.size).exists(i => (i == 0 || content(i - 1) == '\n') && at(i))

    final private case class Part(
        headers: Chunk[Span[Byte]],
        body: Chunk[Span[Byte]],
        eightBit: Boolean,
        declared: Chunk[String],
        multipart: Boolean
    )

    private def rendered(node: Node, depth: Int): Part =
        node match
            case Node.Leaf(headers, body, eightBit, declared) =>
                Part(headers.map(Utf8.encode), Chunk(body), eightBit, declared.toChunk, multipart = false)
            case Node.Multi(subType, children) =>
                val parts    = children.map(rendered(_, depth + 1))
                val enclosed = parts.map(p => p.headers ++ Chunk(Crlf) ++ p.body)
                val declared = parts.flatMap(_.declared)
                val boundary = Multipart.boundary(s"=_kyo${depth}_", enclosed, declared)
                val first    = Utf8.encode(s"--$boundary\r\n")
                val next     = Utf8.encode(s"\r\n--$boundary\r\n")
                val body     = enclosed.zipWithIndex.flatMap((pieces, i) => (if i == 0 then first else next) +: pieces) :+
                    Utf8.encode(s"\r\n--$boundary--")
                val eightBit = parts.exists(_.eightBit)
                val headers  = Chunk(s"Content-Type: multipart/$subType; boundary=\"$boundary\"\r\n") ++
                    (if eightBit then Chunk(encodingLine(Kind.EightBit)) else Chunk.empty)
                Part(headers.map(Utf8.encode), body, eightBit, declared, multipart = true)

end MimeRenderer
