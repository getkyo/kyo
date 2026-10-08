package kyo.internal.email.mime

import kyo.*
import kyo.charset.Charset
import kyo.internal.Ascii
import kyo.internal.email.charset.Charsets
import kyo.internal.email.mime.MimeParser.Body
import kyo.internal.email.mime.MimeParser.Encoding
import kyo.internal.email.mime.MimeParser.Entity

/** A message's MIME tree as the model: the message's own fields, then its parts classified into bodies, inline parts and attachments by
  * structure alone (types, dispositions, `Content-ID`s and positions), never by content.
  *
  * The tree is walked with an explicit work list, each entity carrying whether it may still give a body. The first `text/plain` and the
  * first `text/html` part reached where a body may be, not explicitly an attachment and in a charset that decodes, are the bodies. In
  * `multipart/alternative` only the last child that yields each kind may give it (RFC 2046 section 5.1.4); in `multipart/related` the root
  * (the `start` part, else the first) may, and the other children with a `Content-ID` are inline parts (RFC 2387); in
  * `multipart/encrypted` no child may; in every other multipart only the first child may (RFC 2046 section 5.1.3). Any other leaf is an
  * inline part when it has a `Content-ID` and an `inline` disposition, and an attachment otherwise.
  *
  * A text body has each CRLF made LF. A part in a charset no decoder takes stays an attachment of its declared type; a part in an unknown
  * transfer encoding is an `application/octet-stream` attachment of its octets as they arrived.
  */
private[kyo] object MimeModel:

    def toModel(input: Span[Byte], root: Entity, defaults: MediaTypeDefaults)(using
        Frame
    ): Result[TransferEncoding.PieceRefused, Email.Message] =
        val fields                                        = root.fields
        def first(name: String): Maybe[HeaderCodec.Field] = Maybe.fromOption(fields.find(f => Ascii.equalsIgnoreCase(f.name, name)))
        def every(name: String): Chunk[HeaderCodec.Field] = fields.filter(f => Ascii.equalsIgnoreCase(f.name, name))
        classify(input, root, defaults.octetStream).map(parts => message(fields, parts, first, every))
    end toModel

    private def message(
        fields: Chunk[HeaderCodec.Field],
        parts: Parts,
        first: String => Maybe[HeaderCodec.Field],
        every: String => Chunk[HeaderCodec.Field]
    ): Email.Message =
        Email.Message(
            from = first("From").map(AddressCodec.parse).getOrElse(Chunk.empty),
            sender = first("Sender").flatMap(f => Maybe.fromOption(AddressCodec.parse(f).headOption)),
            to = every("To").flatMap(AddressCodec.parse),
            cc = every("Cc").flatMap(AddressCodec.parse),
            bcc = every("Bcc").flatMap(AddressCodec.parse),
            replyTo = first("Reply-To").map(AddressCodec.parse).getOrElse(Chunk.empty),
            subject = first("Subject").map(f => HeaderCodec.decodeUnstructured(f.value)).getOrElse(""),
            date = first("Date").flatMap(f => DateCodec.parse(f.value)),
            messageId = first("Message-ID").flatMap(f => Maybe.fromOption(MessageIdCodec.parse(f.value).headOption)),
            inReplyTo = first("In-Reply-To").map(f => MessageIdCodec.parse(f.value)).getOrElse(Chunk.empty),
            references = first("References").map(f => MessageIdCodec.parse(f.value)).getOrElse(Chunk.empty),
            text = parts.text.getOrElse(""),
            html = parts.html,
            attachments = parts.attachments,
            inlineParts = parts.inlineParts,
            headers = fields.map(f => Email.Header(f.name, f.value))
        )

    final private case class Parts(
        text: Maybe[String],
        html: Maybe[String],
        attachments: Chunk[Email.Attachment],
        inlineParts: Chunk[Email.InlinePart]
    )

    // One pending step of the walk: an entity to visit, and whether it may give a body; or a non-root child of `multipart/related`. The
    // work list is a `List` because it is a stack: a multipart's children go on its front in order, and each step is popped in constant
    // time.
    private enum Step:
        case Visit(entity: Entity, candidate: Boolean)
        case RelatedPart(entity: Entity)

    private def classify(input: Span[Byte], root: Entity, octetStream: Email.MediaType)(using
        Frame
    ): Result[TransferEncoding.PieceRefused, Parts] =
        var text                                                                = Maybe.empty[String]
        var html                                                                = Maybe.empty[String]
        val attachments                                                         = ChunkBuilder.init[Email.Attachment]
        val inlineParts                                                         = ChunkBuilder.init[Email.InlinePart]
        def attach(entity: Entity): Result[TransferEncoding.PieceRefused, Unit] =
            attachment(input, entity, octetStream).map(part => discard(attachments.addOne(part)))
        def otherLeaf(entity: Entity, related: Boolean): Result[TransferEncoding.PieceRefused, Unit] =
            val inline = related || entity.disposition.exists(_.kind == HeaderCodec.DispositionKind.Inline)
            contentId(entity).filter(_ => inline).fold(attach(entity)) { id =>
                inlinePart(input, entity, id, octetStream).map(part => discard(inlineParts.addOne(part)))
            }
        end otherLeaf
        def leaf(entity: Entity, candidate: Boolean): Result[TransferEncoding.PieceRefused, Unit] =
            if !isBodyCandidate(entity) then otherLeaf(entity, related = false)
            else if candidate && isType(entity, "text", "plain") && text.isEmpty then bodyText(input, entity).map(read => text = read)
            else if candidate && isType(entity, "text", "html") && html.isEmpty then bodyText(input, entity).map(read => html = read)
            else attach(entity)
        @scala.annotation.tailrec
        def loop(pending: List[Step]): Result[TransferEncoding.PieceRefused, Unit] =
            pending match
                case Nil                              => Result.succeed(())
                case Step.RelatedPart(entity) :: rest =>
                    entity.body match
                        case Body.Leaf(_, _) =>
                            otherLeaf(entity, related = true) match
                                case Result.Success(_) => loop(rest)
                                case refused           => refused
                        case Body.Multipart(_) => loop(Step.Visit(entity, false) :: rest)
                case Step.Visit(entity, candidate) :: rest =>
                    entity.body match
                        case Body.Leaf(_, _) =>
                            leaf(entity, candidate) match
                                case Result.Success(_) => loop(rest)
                                case refused           => refused
                        case Body.Multipart(children) =>
                            loop(childSteps(entity, children, candidate) ++ rest)
                    end match
        loop(List(Step.Visit(root, true))).map(_ => Parts(text, html, attachments.result(), inlineParts.result()))
    end classify

    private def childSteps(entity: Entity, children: Chunk[Entity], candidate: Boolean)(using Frame): List[Step] =
        entity.mediaType.subType match
            case "alternative" =>
                val lastText = children.lastIndexWhere(yields(_, "plain"))
                val lastHtml = children.lastIndexWhere(yields(_, "html"))
                children.zipWithIndex.map((c, i) => Step.Visit(c, candidate && (i == lastText || i == lastHtml))).toList
            case "related" =>
                val root = relatedRoot(entity, children)
                children.zipWithIndex.map((c, i) => if i == root then Step.Visit(c, candidate) else Step.RelatedPart(c)).toList
            case "encrypted" => children.map(c => Step.Visit(c, false)).toList
            case _           => children.zipWithIndex.map((c, i) => Step.Visit(c, candidate && i == 0)).toList

    // RFC 2387 section 3.2: the part whose Content-ID the start parameter names, else the first.
    private def relatedRoot(entity: Entity, children: Chunk[Entity]): Int =
        entity.mediaType.parameter("start").flatMap(s => Maybe.fromOption(MessageIdCodec.parse(s).headOption))
            .fold(0)(start => math.max(0, children.indexWhere(c => contentId(c).exists(_.value == start.value))))

    // Whether `entity` gives a body of `subType` where it stands: itself, or through the first child of related and mixed, in a loop.
    private def yields(entity: Entity, subType: String)(using Frame): Boolean =
        @scala.annotation.tailrec
        def loop(current: Entity): Boolean =
            current.body match
                case Body.Leaf(_, _)          => isType(current, "text", subType) && isBodyCandidate(current)
                case Body.Multipart(children) =>
                    current.mediaType.subType match
                        case "related" if children.nonEmpty => loop(children(relatedRoot(current, children)))
                        case "mixed" if children.nonEmpty   => loop(children(0))
                        case _                              => false
        loop(entity)
    end yields

    private def isType(entity: Entity, mainType: String, subType: String): Boolean =
        entity.mediaType.mainType == mainType && entity.mediaType.subType == subType

    // A text/plain or text/html leaf that is not explicitly an attachment, in a known transfer encoding and a charset that decodes.
    private def isBodyCandidate(entity: Entity)(using Frame): Boolean =
        (isType(entity, "text", "plain") || isType(entity, "text", "html")) &&
            !entity.disposition.exists(_.kind != HeaderCodec.DispositionKind.Inline) &&
            (entity.encoding match
                case Encoding.Known(_)   => true
                case Encoding.Unknown(_) => false) &&
            declaredCharset(entity).forall(label => Charsets.resolve(label).nonEmpty)

    private def bodyText(input: Span[Byte], entity: Entity)(using Frame): Result[TransferEncoding.PieceRefused, Maybe[String]] =
        content(input, entity).map(octets => charsetOf(entity, octets).map(charset => withoutCr(charset.decode(octets))))

    // The charset the part's own Content-Type declares. The us-ascii of the default type (RFC 2045 section 5.2) declares nothing, so
    // octets from 0x80 in a part with no readable Content-Type read as UTF-8 when well-formed, not as the windows-1252 us-ascii maps to.
    private def declaredCharset(entity: Entity)(using Frame): Maybe[String] =
        Maybe.fromOption(entity.fields.find(f => Ascii.equalsIgnoreCase(f.name, "Content-Type")))
            .flatMap(HeaderCodec.contentType)
            .flatMap(_.charset)

    // The declared charset; with none, UTF-8 for well-formed UTF-8 and windows-1252 otherwise, so every octet is a character.
    private def charsetOf(entity: Entity, octets: Span[Byte])(using Frame): Maybe[Charset] =
        declaredCharset(entity) match
            case Present(label) => Charsets.resolve(label)
            case Absent         =>
                if HeaderCodec.octetText(octets, 0, octets.size) == HeaderCodec.ValueText.Windows1252 then Present(Charset.Windows1252)
                else Present(Charset.Utf8)

    // Each CRLF as LF; a CR elsewhere stays.
    private def withoutCr(text: String): String =
        val crlfs = (0 until text.length - 1).count(i => text.charAt(i) == '\r' && text.charAt(i + 1) == '\n')
        if crlfs == 0 then text
        else
            val out = new java.lang.StringBuilder(text.length - crlfs)
            var i   = 0
            while i < text.length do
                val c = text.charAt(i)
                if !(c == '\r' && i + 1 < text.length && text.charAt(i + 1) == '\n') then discard(out.append(c))
                i += 1
            end while
            out.toString
        end if
    end withoutCr

    private def content(input: Span[Byte], entity: Entity)(using Frame): Result[TransferEncoding.PieceRefused, Span[Byte]] =
        entity.body match
            case Body.Leaf(start, end) =>
                entity.encoding match
                    case Encoding.Known(kind) => TransferEncoding.decode(kind, input, start, end).map(_.content)
                    case Encoding.Unknown(_)  => Result.succeed(input.slice(start, end))
            case Body.Multipart(_) => Result.succeed(Span.empty[Byte])

    private def contentType(entity: Entity, octetStream: Email.MediaType): Email.MediaType =
        entity.encoding match
            case Encoding.Known(_)   => entity.mediaType
            case Encoding.Unknown(_) => octetStream

    // Content-Disposition's filename; Content-Type's name only when the part has no Content-Disposition.
    private def fileName(entity: Entity): Maybe[String] =
        entity.disposition match
            case Present(disposition) => Maybe.fromOption(disposition.parameters.find(_._1 == "filename")).map(_._2)
            case Absent               => entity.mediaType.parameter("name")

    private def contentId(entity: Entity): Maybe[Email.ContentId] =
        Maybe.fromOption(entity.fields.find(f => Ascii.equalsIgnoreCase(f.name, "Content-ID")))
            .flatMap(f => Maybe.fromOption(MessageIdCodec.parse(f.value).headOption))
            .flatMap(id => Email.ContentId.read(id.value))

    private def attachment(
        input: Span[Byte],
        entity: Entity,
        octetStream: Email.MediaType
    )(using Frame): Result[TransferEncoding.PieceRefused, Email.Attachment] =
        content(input, entity).map(octets => Email.Attachment(contentType(entity, octetStream), fileName(entity), octets))

    private def inlinePart(
        input: Span[Byte],
        entity: Entity,
        id: Email.ContentId,
        octetStream: Email.MediaType
    )(using Frame): Result[TransferEncoding.PieceRefused, Email.InlinePart] =
        content(input, entity).map(octets => Email.InlinePart(id, contentType(entity, octetStream), fileName(entity), octets))

end MimeModel
