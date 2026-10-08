package kyo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kyo.internal.email.mime.HeaderCodecStalwart.escape
import kyo.internal.email.mime.HeaderCodecStalwart.unescape
import kyo.internal.email.vectors.EmbeddedCpythonSet
import kyo.internal.email.vectors.EmbeddedMime4jSet
import kyo.internal.email.vectors.EmbeddedStalwartSet
import kyo.internal.email.vectors.EmbeddedThunderbirdSet
import org.apache.james.mime4j.dom.Entity as M4Entity
import org.apache.james.mime4j.dom.Message as M4Message
import org.apache.james.mime4j.dom.Multipart as M4Multipart
import org.apache.james.mime4j.dom.SingleBody
import org.apache.james.mime4j.dom.address.AddressList
import org.apache.james.mime4j.dom.address.Group
import org.apache.james.mime4j.dom.address.Mailbox
import org.apache.james.mime4j.dom.field.ContentDispositionField
import org.apache.james.mime4j.dom.field.ContentTypeField
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.message.DefaultMessageWriter
import org.apache.james.mime4j.stream.MimeConfig
import scala.jdk.CollectionConverters.*

/** Where the module's model differs from one built from Apache James mime4j 0.8.15's DOM by the same classification rules, written here
  * against mime4j's types and the JDK's charsets, over every vendored message of the CPython, Stalwart, mime4j and Thunderbird corpora. The
  * rows are compared with `mime4j-model-differences.tsv`, kept by hand from the rows a failing test prints; a difference no reason
  * explains fails.
  */
object EmailMessageReference:

    /** Each vendored message, named by its set and file. */
    lazy val messages: Seq[(String, Array[Byte])] =
        def from(set: String, files: Map[String, () => Span[Byte]], keep: String => Boolean) =
            files.toSeq.filter((name, _) => keep(name)).sortBy(_._1).map((name, bytes) => (s"$set/$name", bytes().toArray))
        from("cpython", EmbeddedCpythonSet.files, _.startsWith("msg_")) ++
            from("stalwart", EmbeddedStalwartSet.files, _.startsWith("rfc-")) ++
            from("mime4j", EmbeddedMime4jSet.files, _.endsWith(".msg")) ++
            from("thunderbird", EmbeddedThunderbirdSet.files, _ => true)
    end messages

    /** The aspects both models are compared on, each rendered as text. */
    final case class Model(fields: Seq[(String, String)])

    def module(bytes: Array[Byte])(using Frame): Model =
        val message                               = Abort.run[EmailParseFailure](Email.Message.parse(Span.from(bytes))).eval.getOrThrow
        def addresses(list: Chunk[Email.Address]) = list.map(a => a.address + a.name.fold("")(n => s" [$n]")).mkString(", ")
        Model(Seq(
            "from"      -> addresses(message.from),
            "to"        -> addresses(message.to),
            "cc"        -> addresses(message.cc),
            "subject"   -> message.subject,
            "date"      -> message.date.fold("")(d => kyo.internal.email.mime.DateCodec.epochSecondOf(d).toString),
            "messageId" -> message.messageId.fold("")(_.value),
            "text"      -> message.text,
            "html"      -> message.html.getOrElse("<absent>")
        ) ++ message.attachments.zipWithIndex.map((a, i) =>
            s"attachment ${i + 1}" -> part(a.contentType.baseType, a.fileName.getOrElse(""), a.content.toArray)
        ) ++ message.inlineParts.zipWithIndex.map((p, i) =>
            s"inline ${i + 1}" -> (s"<${p.contentId.value}> " + part(p.contentType.baseType, p.fileName.getOrElse(""), p.content.toArray))
        ))
    end module

    // The content's hash stands for all its octets, so two parts that differ only past the shown prefix still differ.
    private def part(mediaType: String, fileName: String, content: Array[Byte]): String =
        val hash = f"${java.util.Arrays.hashCode(content)}%08x"
        s"$mediaType [$fileName] ${content.length} octets #$hash: ${new String(content, StandardCharsets.ISO_8859_1).take(40)}"

    private def config: MimeConfig =
        new MimeConfig.Builder().setMaxLineLen(-1).setMaxHeaderCount(-1).setMaxHeaderLen(-1).setMaxContentLen(-1).setMaxPartCount(-1)
            .setMaxNestingDepth(100).build()

    def reference(bytes: Array[Byte]): Model =
        val builder = new DefaultMessageBuilder
        builder.setMimeEntityConfig(config)
        val message                              = builder.parseMessage(new ByteArrayInputStream(bytes))
        def mailboxes(list: AddressList): String =
            if list == null then ""
            else
                list.asScala.flatMap {
                    case m: Mailbox => Seq(m)
                    case g: Group   => g.getMailboxes.asScala
                    case _          => Seq.empty
                }.map(m => m.getAddress + Maybe(m.getName).fold("")(n => s" [$n]")).mkString(", ")
        val from = Maybe(message.getFrom).fold("")(l =>
            l.asScala.map(m => m.getAddress + Maybe(m.getName).fold("")(n => s" [$n]")).mkString(", ")
        )
        val walk = new Walk
        walk.visit(message)
        Model(Seq(
            "from"      -> from,
            "to"        -> mailboxes(message.getTo),
            "cc"        -> mailboxes(message.getCc),
            "subject"   -> Maybe(message.getSubject).getOrElse("").trim,
            "date"      -> Maybe(message.getDate).fold("")(d => (d.getTime / 1000).toString),
            "messageId" -> Maybe(message.getMessageId).fold("")(_.trim.stripPrefix("<").stripSuffix(">")),
            "text"      -> walk.text.getOrElse(""),
            "html"      -> walk.html.getOrElse("<absent>")
        ) ++ walk.attachments.result().zipWithIndex.map((a, i) => s"attachment ${i + 1}" -> a) ++
            walk.inlineParts.result().zipWithIndex.map((p, i) => s"inline ${i + 1}" -> p))
    end reference

    // The classification rules of the module's scaladoc (MimeModel), written against mime4j's entities.
    final private class Walk:
        var text: Maybe[String]   = Absent
        var html: Maybe[String]   = Absent
        val attachments           = Seq.newBuilder[String]
        val inlineParts           = Seq.newBuilder[String]
        private val identityKinds = Set("7bit", "8bit", "binary", "quoted-printable", "base64")

        def visit(root: M4Entity): Unit =
            var pending = List[(M4Entity, Boolean, Boolean)]((root, true, false))
            while pending.nonEmpty do
                val (entity, candidate, related) = pending.head
                pending = pending.tail
                entity.getBody match
                    case multipart: M4Multipart =>
                        val children = multipart.getBodyParts.asScala.toList
                        val steps    = multipart.getSubType.toLowerCase match
                            case "alternative" =>
                                val lastText = children.lastIndexWhere(yields(_, "text/plain"))
                                val lastHtml = children.lastIndexWhere(yields(_, "text/html"))
                                children.zipWithIndex.map((c, i) => (c, candidate && (i == lastText || i == lastHtml), false))
                            case "related" =>
                                val root = relatedRoot(entity, children)
                                children.zipWithIndex.map((c, i) => (c, candidate && i == root, i != root))
                            case "encrypted" => children.map(c => (c, false, false))
                            case _           => children.zipWithIndex.map((c, i) => (c, candidate && i == 0, false))
                        pending = steps ++ pending
                    case _ => leaf(entity, candidate, related)
                end match
            end while
        end visit

        private def leaf(entity: M4Entity, candidate: Boolean, related: Boolean): Unit =
            val mimeType = mediaType(entity)
            val isText   = mimeType == "text/plain" || mimeType == "text/html"
            if isText && isCandidate(entity) then
                val decoded = decodeText(entity)
                if candidate && mimeType == "text/plain" && text.isEmpty then text = Present(decoded)
                else if candidate && mimeType == "text/html" && html.isEmpty then html = Present(decoded)
                else attach(entity)
            else
                val inline = related || Maybe(entity.getDispositionType).exists(_.equalsIgnoreCase("inline"))
                contentId(entity).filter(_ => inline).fold(attach(entity))(id => discard(inlineParts += s"<$id> " + describe(entity)))
            end if
        end leaf

        private def attach(entity: M4Entity): Unit = attachments += describe(entity)

        private def describe(entity: M4Entity): String =
            val declared = if identityKinds.contains(encoding(entity)) then mediaType(entity) else "application/octet-stream"
            part(declared, fileName(entity).getOrElse(""), octets(entity))

        private def mediaType(entity: M4Entity): String = entity.getMimeType.toLowerCase

        private def encoding(entity: M4Entity): String = Maybe(entity.getContentTransferEncoding).fold("7bit")(_.trim.toLowerCase)

        private def isCandidate(entity: M4Entity): Boolean =
            Maybe(entity.getDispositionType).forall(_.equalsIgnoreCase("inline")) && identityKinds.contains(encoding(entity)) &&
                declaredCharset(entity).forall(c => Result.catching[IllegalArgumentException](Charset.forName(c)).isSuccess)

        private def yields(entity: M4Entity, mimeType: String): Boolean =
            var current = entity
            var result  = Maybe.empty[Boolean]
            while result.isEmpty do
                current.getBody match
                    case multipart: M4Multipart if multipart.getCount > 0 && multipart.getSubType.equalsIgnoreCase("related") =>
                        current = multipart.getBodyParts.get(relatedRoot(current, multipart.getBodyParts.asScala.toList))
                    case multipart: M4Multipart if multipart.getCount > 0 && multipart.getSubType.equalsIgnoreCase("mixed") =>
                        current = multipart.getBodyParts.get(0)
                    case _: M4Multipart => result = Present(false)
                    case _              => result = Present(mediaType(current) == mimeType && isCandidate(current))
            end while
            result.contains(true)
        end yields

        private def relatedRoot(entity: M4Entity, children: List[M4Entity]): Int =
            contentTypeField(entity).flatMap(f => Maybe(f.getParameter("start"))).map(idOf)
                .fold(0)(start => math.max(0, children.indexWhere(c => contentId(c).contains(start))))

        private def contentTypeField(entity: M4Entity): Maybe[ContentTypeField] =
            Maybe(entity.getHeader).flatMap(h => Maybe(h.getField("Content-Type"))).collect { case f: ContentTypeField => f }

        private def declaredCharset(entity: M4Entity): Maybe[String] =
            contentTypeField(entity).filter(f => f.getParseException == null).flatMap(f => Maybe(f.getParameter("charset")))

        private def fileName(entity: M4Entity): Maybe[String] =
            Maybe(entity.getHeader).flatMap(h => Maybe(h.getField("Content-Disposition")))
                .fold(contentTypeField(entity).flatMap(f => Maybe(f.getParameter("name")))) {
                    case field: ContentDispositionField => Maybe(field.getFilename)
                    case _                              => Absent
                }

        private def idOf(text: String): String = text.filterNot(c => c == ' ' || c == '\t').stripPrefix("<").stripSuffix(">")

        private def contentId(entity: M4Entity): Maybe[String] =
            Maybe(entity.getHeader).flatMap(h => Maybe(h.getField("Content-ID"))).map(f => idOf(f.getBody))
                .filter(id => id.nonEmpty && !id.exists(c => c == '<' || c == '>' || c.isControl))

        private def octets(entity: M4Entity): Array[Byte] =
            val out = new ByteArrayOutputStream
            entity.getBody match
                case single: SingleBody     => single.writeTo(out)
                case message: M4Message     => new DefaultMessageWriter().writeMessage(message, out)
                case multipart: M4Multipart => new DefaultMessageWriter().writeMultipart(multipart, out)
            end match
            out.toByteArray
        end octets

        private def decodeText(entity: M4Entity): String =
            val bytes   = octets(entity)
            val charset = declaredCharset(entity) match
                case Present(name) => Charset.forName(name)
                case Absent        =>
                    val strict = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    if Result.catching[CharacterCodingException](strict.decode(ByteBuffer.wrap(bytes))).isSuccess then
                        StandardCharsets.UTF_8
                    else Charset.forName("windows-1252")
            new String(bytes, charset).replace("\r\n", "\n")
        end decodeText
    end Walk

    /** Why the module and the reference read a message differently. */
    enum Reason(val description: String) derives CanEqual:
        case EncapsulatedMessageRewritten
            extends Reason(
                "a message/rfc822 part: mime4j parses it and the reference takes the message DefaultMessageWriter writes back, which is not the octets as they arrived; the module keeps those octets"
            )
        case CommentAsDisplayName
            extends Reason(
                "an address with no display name is followed by a comment, which the module takes as the name (the RFC 822 form, RFC 5322 appendix A.1.2's reading of it); mime4j drops comments"
            )
        case DuplicateAddressFields
            extends Reason(
                "the message has more than one To or Cc field; the module concatenates them as RFC 5322 section 4.5.3 says, mime4j takes the first"
            )
        case ObsoleteZoneName
            extends Reason(
                "the date's zone is one of the eight North American names, which RFC 5322 section 4.3 gives offsets; the module applies them, mime4j reads them as UTC"
            )
        case BoundaryTrailingWhiteSpace
            extends Reason(
                "a boundary parameter ends in white space, which the module removes before matching (RFC 2046 section 5.1.1), so lines mime4j reads as text are delimiters"
            )
        case Rfc2231ContentType
            extends Reason(
                "a Content-Type field holds a parameter in RFC 2231's form (name*=), the boundary or charset among them; the module reads it, mime4j does not"
            )
        case BoundaryReused
            extends Reason(
                "two nested multiparts declare the same boundary, which RFC 2046 section 5.1 forbids a composer; the innermost open one owns its delimiters in the module, mime4j splits them otherwise"
            )
        case OuterDelimiterInsidePart
            extends Reason(
                "a part of an inner multipart holds a line starting with an enclosing multipart's delimiter, which RFC 2046 section 5.1 forbids a composer; the module ends the inner multiparts there (RFC 2046 section 5.1.2), mime4j reads the line as text"
            )
        case MultipartWithoutDelimiter
            extends Reason(
                "a multipart has no boundary parameter, or none of its delimiters occurs; the module keeps it as one attachment of its declared type holding its body, mime4j as an empty multipart or as text"
            )
        case NonHeaderLineEndsSection
            extends Reason(
                "a header section holds a line with no field name before its empty line; the module ends the section there and that line starts the body (RFC 5322 section 3.6.8), mime4j reads later lines as fields or drops lines"
            )
        case UnpaddedFinalQuantum
            extends Reason(
                "a base64 part ends with two or three characters and no padding; the module decodes the octets they hold, mime4j drops them"
            )
    end Reason

    final case class Row(message: String, aspect: String, module: String, reference: String, reason: Reason) derives CanEqual:
        def line: String = Seq(message, aspect, escape(module), escape(reference), reason.toString).mkString("\t")

    final case class Unexplained(message: String, aspect: String, module: String, reference: String)

    // A long value as its length and first characters, so a row stays one short line.
    private def shown(value: String): String = if value.length <= 80 then value else s"${value.length} chars: ${value.take(60)}"

    private val headerLine = "[!-9;-~]+[ \t]*:.*".r

    private val zoneName = "(?i).*\\b(EST|EDT|CST|CDT|MST|MDT|PST|PDT)\\s*$".r

    private def boundaries(text: String): Seq[String] =
        "(?i);\\s*boundary=\"([^\"]*)\"|(?i);\\s*boundary=([^;\\s\"]+)".r.findAllMatchIn(text).map(m =>
            Maybe(m.group(1)).getOrElse(m.group(2))
        ).toSeq

    private def classify(bytes: Array[Byte], aspect: String, module: String, reference: String, moduleModel: Model): Maybe[Reason] =
        val text                      = new String(bytes, StandardCharsets.ISO_8859_1)
        val lines                     = text.split("\r?\n", -1).toSeq
        val topFields                 = lines.takeWhile(_.nonEmpty)
        def fieldCount(name: String)  = topFields.count(_.toLowerCase.startsWith(name.toLowerCase + ":"))
        val declared                  = boundaries(text)
        def outerInsideInner: Boolean =
            declared.indices.exists { i =>
                declared.indices.exists { j =>
                    j > i && declared(i) != declared(j) && {
                        val open  = text.indexOf("\n--" + declared(j))
                        val close = if open < 0 then -1 else text.indexOf("\n--" + declared(j) + "--", open + 1)
                        close > open && open >= 0 && text.substring(open + 1, close).contains("\n--" + declared(i))
                    }
                }
            }
        def nonHeaderLine: Boolean =
            val starts = -1 +: lines.indices.filter(i => declared.exists(b => lines(i).startsWith("--" + b)))
            starts.exists { s =>
                lines.drop(s + 1).takeWhile(l => l.nonEmpty && !declared.exists(b => l.startsWith("--" + b)))
                    .exists(l => !l.startsWith(" ") && !l.startsWith("\t") && !headerLine.matches(l))
            }
        end nonHeaderLine
        if aspect.startsWith("attachment") && module.startsWith("message/") && reference.startsWith("message/") then
            Present(Reason.EncapsulatedMessageRewritten)
        else if Seq("from", "to", "cc").contains(aspect) && module.replaceAll(" \\[[^\\]]*\\]", "") == reference then
            Present(Reason.CommentAsDisplayName)
        else if Seq("to", "cc").contains(aspect) && fieldCount(aspect) > 1 then Present(Reason.DuplicateAddressFields)
        else if aspect == "date" && topFields.exists(l => l.toLowerCase.startsWith("date:") && zoneName.matches(l)) then
            Present(Reason.ObsoleteZoneName)
        else if declared.exists(b => b.endsWith(" ") || b.endsWith("\t")) then Present(Reason.BoundaryTrailingWhiteSpace)
        else if "(?i)content-type:[^\\n]*(\\n[ \\t][^\\n]*)*\\*=".r.findFirstIn(text).isDefined then Present(Reason.Rfc2231ContentType)
        else if declared.distinct.size < declared.size then Present(Reason.BoundaryReused)
        else if outerInsideInner then Present(Reason.OuterDelimiterInsidePart)
        else if moduleModel.fields.exists((a, v) => a.startsWith("attachment") && v.startsWith("multipart/")) then
            Present(Reason.MultipartWithoutDelimiter)
        else if nonHeaderLine then Present(Reason.NonHeaderLineEndsSection)
        else if text.toLowerCase.contains("content-transfer-encoding: base64") &&
            (aspect.startsWith("attachment") && {
                val size = "(\\d+) octets".r
                (Maybe.fromOption(size.findFirstMatchIn(module)), Maybe.fromOption(size.findFirstMatchIn(reference))) match
                    case (Present(m), Present(r)) => Set(1, 2).contains(m.group(1).toInt - r.group(1).toInt)
                    case _                        => false
            } || Seq("text", "html").contains(aspect) && module.startsWith(reference) && Set(1, 2).contains(module.length -
                reference.length))
        then Present(Reason.UnpaddedFinalQuantum)
        else Absent
        end if
    end classify

    def compared(using Frame): (Seq[Row], Seq[Unexplained]) =
        val rows        = Seq.newBuilder[Row]
        val unexplained = Seq.newBuilder[Unexplained]
        messages.foreach { (name, bytes) =>
            val moduleModel = module(bytes)
            val mod         = moduleModel.fields.toMap
            val ref         = reference(bytes).fields.toMap
            val aspects     = (mod.keySet ++ ref.keySet).toSeq.sorted
            aspects.foreach { aspect =>
                val m = mod.getOrElse(aspect, "<absent>")
                val r = ref.getOrElse(aspect, "<absent>")
                if m != r then
                    classify(bytes, aspect, m, r, moduleModel) match
                        case Present(reason) => rows += Row(name, aspect, shown(m), shown(r), reason)
                        case Absent          => unexplained += Unexplained(name, aspect, shown(m), shown(r))
                end if
            }
        }
        (rows.result(), unexplained.result())
    end compared

    def render(rows: Seq[Row]): String = rows.map(_.line).mkString("\n")

    def parse(text: String): Seq[Row] =
        text.split("\n").toSeq.filterNot(l => l.startsWith("#") || l.isEmpty).map { line =>
            val fields = line.split("\t", -1)
            Row(fields(0), fields(1), unescape(fields(2)), unescape(fields(3)), Reason.valueOf(fields(4)))
        }

end EmailMessageReference
