package kyo.internal.email.mime

import kyo.*
import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.mime.Multipart
import scala.collection.mutable

/** The MIME tree of a message (RFC 2045, RFC 2046), in one pass over its lines with an explicit stack, so no structure of the message
  * recurses on any thread's stack.
  *
  * Each open multipart is a frame on the stack. A line inside an open multipart that starts with `--` is walked through a trie of the open
  * boundaries; the longest boundary that the rest of the line starts with wins, and among multiparts sharing it the innermost. Anything
  * after the boundary on the line is ignored, so transport padding and junk leave it a delimiter (RFC 2046 section 5.1.1). A delimiter
  * closes every multipart above its owner, and the line end before it belongs to it. A multipart none of whose delimiters ever occurs is
  * one leaf of its declared type holding its whole body, and parts of every `message` type are leaves: the parser never descends into an
  * encapsulated message. Preambles and epilogues are dropped.
  *
  * The tree holds ranges of the input, never copies. Parsing never fails except past [[NestingLimit]] nested multiparts.
  */
private[kyo] object MimeParser:

    /** One entity: its path (empty for the message, then 1-based part indexes), its header fields, and what they resolve to. */
    final case class Entity(
        path: Chunk[Int],
        fields: Chunk[HeaderCodec.Field],
        mediaType: Email.MediaType,
        disposition: Maybe[HeaderCodec.Disposition],
        encoding: Encoding,
        body: Body
    ) derives CanEqual

    /** The `Content-Transfer-Encoding` of an entity: one RFC 2045 names (7bit when there is no field), or the value as written. */
    enum Encoding derives CanEqual:
        case Known(kind: TransferEncoding.Kind)
        case Unknown(label: String)

    /** A leaf's encoded content as the range `[start, end)` of the input, or a multipart's parts. */
    enum Body derives CanEqual:
        case Leaf(start: Int, end: Int)
        case Multipart(children: Chunk[Entity])

    /** The deepest multipart nesting accepted, the message's own multipart at depth 1: Postfix's `mime_nesting_limit` and Dovecot's
      * default, so no mail those servers deliver is refused.
      */
    inline val NestingLimit = 100

    def parse(input: Span[Byte], defaults: MediaTypeDefaults)(using Frame): Result[EmailMimeException, Entity] =
        new Parser(input, defaults).run()

    final private case class Head(
        path: Chunk[Int],
        fields: Chunk[HeaderCodec.Field],
        mediaType: Email.MediaType,
        disposition: Maybe[HeaderCodec.Disposition],
        encoding: Encoding
    ):
        def entity(body: Body): Entity = Entity(path, fields, mediaType, disposition, encoding, body)
    end Head

    // A multipart's current part: none (the preamble, or just after a delimiter), a leaf being read, or a nested multipart already closed.
    private enum Current derives CanEqual:
        case Empty
        case Part(head: Head, start: Int)
        case Done(entity: Entity)
    end Current

    // One open multipart.
    final private class Open(val head: Head, val boundary: Array[Byte], val bodyStart: Int, val digest: Boolean):
        val children         = ChunkBuilder.init[Entity]
        var count            = 0
        var seen             = false
        var current: Current = Current.Empty
    end Open

    final private class TrieNode:
        val next   = mutable.HashMap.empty[Byte, TrieNode]
        val owners = mutable.ArrayBuffer.empty[Int]

    // The state of one parse: the stack, the trie of its boundaries, and the result. It lives for one call.
    final private class Parser(input: Span[Byte], defaults: MediaTypeDefaults)(using Frame):
        private val stack   = mutable.ArrayBuffer.empty[Open]
        private val trie    = new TrieNode
        private var root    = Maybe.empty[Entity]
        private var failure = Maybe.empty[EmailMimeException]

        def run(): Result[EmailMimeException, Entity] =
            val section = HeaderCodec.readSection(input, 0)
            val head    = headOf(Chunk.empty, section.fields, digest = false, defaults)
            if !open(head, section.bodyStart) then root = Present(head.entity(Body.Leaf(section.bodyStart, input.size)))
            scan(section.bodyStart)
            failure match
                case Present(ex) => Result.fail(ex)
                case Absent      =>
                    while stack.nonEmpty do closeTop(input.size)
                    Result.succeed(root.getOrElse(head.entity(Body.Leaf(section.bodyStart, input.size))))
            end match
        end run

        @scala.annotation.tailrec
        private def scan(lineStart: Int): Unit =
            if lineStart < input.size && stack.nonEmpty && failure.isEmpty then
                val lineEnd    = HeaderCodec.lineEndAt(input, lineStart)
                val contentEnd = HeaderCodec.contentEndOf(input, lineStart, lineEnd)
                val owner      = delimiterOwner(lineStart, contentEnd)
                if owner < 0 then scan(HeaderCodec.nextLine(input, lineEnd))
                else scan(delimiter(owner, lineStart, contentEnd, lineEnd))
            end if
        end scan

        // Closes what the delimiter line at `lineStart` ends, and answers where scanning goes on.
        private def delimiter(owner: Int, lineStart: Int, contentEnd: Int, lineEnd: Int): Int =
            val end = Multipart.partEndBefore(input, lineStart)
            while stack.size - 1 > owner do closeTop(end)
            val frame = stack(owner)
            frame.seen = true
            finishCurrent(frame, end)
            val boundaryEnd = lineStart + 2 + frame.boundary.length
            val next        = HeaderCodec.nextLine(input, lineEnd)
            if boundaryEnd + 2 <= contentEnd && input(boundaryEnd) == '-' && input(boundaryEnd + 1) == '-' then
                closeTop(end)
                next
            else
                frame.count += 1
                val section = HeaderCodec.readSection(input, next, (from, until) => delimiterOwner(from, until) >= 0)
                val head    = headOf(frame.head.path :+ frame.count, section.fields, frame.digest, defaults)
                if !open(head, section.bodyStart) && failure.isEmpty then frame.current = Current.Part(head, section.bodyStart)
                section.bodyStart
            end if
        end delimiter

        // Pushes a frame when `head` is a multipart with a boundary, and answers whether it did; past the limit it records the failure.
        private def open(head: Head, bodyStart: Int): Boolean =
            val boundary =
                if head.mediaType.mainType == "multipart" then head.mediaType.parameter("boundary").filter(_.nonEmpty) else Absent
            boundary match
                case Absent        => false
                case Present(text) =>
                    if stack.size + 1 > NestingLimit then
                        failure = Present(EmailMimeException(head.path, EmailMimeException.Problem.NestingTooDeep(NestingLimit)))
                        true
                    else
                        val frame = new Open(head, octets(text, head), bodyStart, head.mediaType.subType == "digest")
                        discard(stack += frame)
                        insert(frame.boundary, stack.size - 1)
                        true
                    end if
            end match
        end open

        // Pops the top frame at `end`: its entity becomes its parent's finished part, or the message.
        private def closeTop(end: Int): Unit =
            Maybe.fromOption(stack.lastOption).foreach { frame =>
                finishCurrent(frame, end)
                remove(frame.boundary, stack.size - 1)
                discard(stack.remove(stack.size - 1))
                val entity =
                    if frame.seen then frame.head.entity(Body.Multipart(frame.children.result()))
                    else frame.head.entity(Body.Leaf(frame.bodyStart, math.max(frame.bodyStart, end)))
                Maybe.fromOption(stack.lastOption) match
                    case Present(parent) => parent.current = Current.Done(entity)
                    case Absent          => root = Present(entity)
            }
        end closeTop

        private def finishCurrent(frame: Open, end: Int): Unit =
            frame.current match
                case Current.Part(head, start) => discard(frame.children.addOne(head.entity(Body.Leaf(start, math.max(start, end)))))
                case Current.Done(entity)      => discard(frame.children.addOne(entity))
                case Current.Empty             => ()
            end match
            frame.current = Current.Empty
        end finishCurrent

        // The stack index of the multipart owning the delimiter line `[lineStart, contentEnd)`, or -1.
        private def delimiterOwner(lineStart: Int, contentEnd: Int): Int =
            if stack.isEmpty || contentEnd - lineStart < 3 || input(lineStart) != '-' || input(lineStart + 1) != '-' then -1
            else
                @scala.annotation.tailrec
                def walk(node: TrieNode, at: Int, best: Int): Int =
                    if at >= contentEnd then best
                    else
                        Maybe.fromOption(node.next.get(input(at))) match
                            case Absent         => best
                            case Present(child) =>
                                walk(child, at + 1, if child.owners.nonEmpty then child.owners.max else best)
                walk(trie, lineStart + 2, -1)
            end if
        end delimiterOwner

        private def insert(boundary: Array[Byte], owner: Int): Unit =
            val node = boundary.foldLeft(trie)((n, b) => n.next.getOrElseUpdate(b, new TrieNode))
            discard(node.owners += owner)

        private def remove(boundary: Array[Byte], owner: Int): Unit =
            boundary.foldLeft(Present(trie): Maybe[TrieNode])((n, b) => n.flatMap(node => Maybe.fromOption(node.next.get(b))))
                .foreach(node => discard(node.owners -= owner))
    end Parser

    // A boundary's octets as the delimiter lines carry them: the field's text encoded back the way it was decoded (HeaderCodec.ValueText).
    private def octets(boundary: String, head: Head): Array[Byte] =
        val text =
            Maybe.fromOption(head.fields.find(f => Ascii.equalsIgnoreCase(f.name, "Content-Type"))).map(_.text)
                .getOrElse(HeaderCodec.ValueText.UsAscii)
        if text == HeaderCodec.ValueText.Windows1252 then
            boundary.map(c => (if c < 0x80 then c.toInt else HeaderCodec.windows1252Octet(c)).toByte).toArray
        else Utf8.encode(boundary).toArray
    end octets

    private def headOf(path: Chunk[Int], fields: Chunk[HeaderCodec.Field], digest: Boolean, defaults: MediaTypeDefaults)(using
        Frame
    ): Head =
        def first(name: String): Maybe[HeaderCodec.Field] = Maybe.fromOption(fields.find(f => Ascii.equalsIgnoreCase(f.name, name)))
        val mediaType                                     = first("Content-Type").flatMap(HeaderCodec.contentType).getOrElse {
            if digest then defaults.messageRfc822 else defaults.plainText
        }
        val encoding = first("Content-Transfer-Encoding") match
            case Present(field) => HeaderCodec.transferEncoding(field).fold(Encoding.Unknown(field.value))(Encoding.Known(_))
            case Absent         => Encoding.Known(TransferEncoding.Kind.SevenBit)
        Head(path, fields, mediaType, first("Content-Disposition").flatMap(HeaderCodec.contentDisposition), encoding)
    end headOf

end MimeParser
