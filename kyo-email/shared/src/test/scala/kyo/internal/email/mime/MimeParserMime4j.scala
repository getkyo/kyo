package kyo.internal.email.mime

import kyo.*
import kyo.internal.email.mime.HeaderCodecStalwart.escape
import kyo.internal.email.mime.HeaderCodecStalwart.unescape
import kyo.internal.email.mime.MimeParser.Body
import kyo.internal.email.mime.MimeParser.Encoding
import kyo.internal.email.mime.MimeParser.Entity
import kyo.internal.email.vectors.EmbeddedMime4jSet

/** Where the module's MIME tree differs from mime4j's goldens: for each vendored message, the tree `_decoded.xml` describes and each leaf's
  * decoded octets from the files it names. The rows are compared with `mime4j-differences.tsv`, kept by hand from the rows a failing
  * test prints; a difference no reason explains fails.
  */
object MimeParserMime4j:

    private def latin1(bytes: Span[Byte]): String = new String(bytes.toArray, "ISO-8859-1")

    lazy val messages: Chunk[String] = Chunk.from(EmbeddedMime4jSet.files.keys.filter(_.endsWith(".msg")).toSeq.sorted)

    /** A node of either tree: its path, and `multipart`, `message` (mime4j descends into it) or `leaf`. */
    final case class Node(path: Chunk[Int], kind: String) derives CanEqual

    // The golden tree in document order, not descending into an encapsulated message, with each leaf's golden file.
    private def reference(message: String): Chunk[(Node, Maybe[String])] =
        val xml   = latin1(EmbeddedMime4jSet.files(message.stripSuffix(".msg") + "_decoded.xml")())
        val tags  = "<(/?)([a-z-]+)([^>]*?)(/?)>".r
        val nodes = ChunkBuilder.init[(Node, Maybe[String])]
        // Each open entity: its path and the number of body parts seen so far in its multipart.
        var entities = List.empty[(Chunk[Int], Int)]
        var skipping = 0
        tags.findAllMatchIn(xml).foreach { m =>
            val closing = m.group(1) == "/"
            val name    = m.group(2)
            if skipping > 0 then
                if name == "message" then skipping += (if closing then -1 else 1)
            else
                (closing, name) match
                    case (false, "message") =>
                        entities match
                            case Nil            => entities = List((Chunk.empty, 0))
                            case (path, _) :: _ =>
                                discard(nodes.addOne((Node(path, "message"), Absent)))
                                skipping = 1
                    case (false, "multipart") => discard(nodes.addOne((Node(entities.head._1, "multipart"), Absent)))
                    case (false, "body-part") =>
                        val (path, count) = entities.head
                        entities = (path :+ (count + 1), 0) :: (path, count + 1) :: entities.tail
                    case (true, "body-part")                  => entities = entities.tail
                    case (false, "text-body" | "binary-body") =>
                        val file = "name=\"([^\"]*)\"".r.findFirstMatchIn(m.group(3)).map(_.group(1)).getOrElse("")
                        discard(nodes.addOne((Node(entities.head._1, "leaf"), Present(file))))
                    case _ => ()
            end if
        }
        nodes.result()
    end reference

    def referenceLeaves(message: String): Chunk[(Chunk[Int], String)] =
        reference(message).collect { case (node, Present(file)) => (node.path, file) }

    // The module's tree in document order, with each leaf's decoded octets.
    private def module(message: String)(using Frame): Chunk[(Node, Maybe[Span[Byte]], Entity)] =
        val input = EmbeddedMime4jSet.files(message)()
        val out   = ChunkBuilder.init[(Node, Maybe[Span[Byte]], Entity)]
        @scala.annotation.tailrec
        def walk(pending: List[Entity]): Unit =
            pending match
                case Nil            => ()
                case entity :: rest =>
                    entity.body match
                        case Body.Multipart(children) =>
                            discard(out.addOne((Node(entity.path, "multipart"), Absent, entity)))
                            walk(children.toList ++ rest)
                        case Body.Leaf(start, end) =>
                            val content = entity.encoding match
                                case Encoding.Known(kind) => EmailLiterals.valid(TransferEncoding.decode(kind, input, start, end)).content
                                case Encoding.Unknown(_)  => input.slice(start, end)
                            discard(out.addOne((Node(entity.path, "leaf"), Present(content), entity)))
                            walk(rest)
        walk(List(EmailLiterals.valid(MimeParser.parse(input, EmailLiterals.valid(MediaTypeDefaults.built)))))
        out.result()
    end module

    /** Why the module and mime4j read a message differently. */
    enum Reason(val description: String) derives CanEqual:
        case EncapsulatedMessage
            extends Reason(
                "mime4j descends into a message/rfc822 part; the module keeps it as one leaf, which a caller parses when it wants the message inside"
            )
        case BoundaryTrailingWhiteSpace
            extends Reason(
                "the boundary parameter ends in white space, which RFC 2046 5.1.1 does not allow in a boundary; the module removes it, so a line with less of that white space after the boundary is a delimiter (the rest is transport padding), and the closing line, whose white space comes before its --, is an ordinary delimiter that starts one more part; mime4j matches the boundary as written"
            )
        case MultipartWithoutDelimiter
            extends Reason(
                "the multipart has no delimiter line at all; mime4j keeps it as a multipart holding only a preamble, the module as one leaf of its declared type holding the body, so the text is not lost"
            )
        case NonHeaderLineEndsSection
            extends Reason(
                "the message's header section holds a line with no field name before its empty line; the module ends the section at that line, which starts the body with every line after it (RFC 5322 3.6.8), where mime4j, in either of its two modes for such a line, still reads later lines as fields or drops lines, so its body is shorter"
            )
    end Reason

    final case class Row(file: String, path: String, aspect: String, module: String, reference: String, reason: Reason) derives CanEqual:
        def line: String = Seq(file, path, aspect, escape(module), escape(reference), reason.toString).mkString("\t")

    private def show(path: Chunk[Int]): String = if path.isEmpty then "message" else path.mkString(".")

    private def summary(bytes: Span[Byte]): String = s"${bytes.size} octets: ${latin1(bytes).take(60)}"

    private def classify(message: String, node: Chunk[Int], referenceKinds: Map[Chunk[Int], String], moduleKinds: Map[Chunk[Int], String])
        : Maybe[Reason] =
        val text = latin1(EmbeddedMime4jSet.files(message)())
        if referenceKinds.get(node).contains("message") then Present(Reason.EncapsulatedMessage)
        else if "boundary=\"[^\"]*[ \t]\"".r.findFirstIn(text).isDefined then Present(Reason.BoundaryTrailingWhiteSpace)
        else if referenceKinds.get(node).contains("multipart") && moduleKinds.get(node).contains("leaf") &&
            !referenceKinds.keys.exists(p => p.size == node.size + 1 && p.startsWith(node))
        then Present(Reason.MultipartWithoutDelimiter)
        else
            // RFC 5322 3.6.8 and 4.5: a field name of octets 33 to 126 but `:`, optional white space, then the colon.
            val headerLine = "[!-9;-~]+[ \t]*:.*".r
            val section    = text.split("\r?\n", -1).takeWhile(_.nonEmpty)
            if section.exists(line => !line.startsWith(" ") && !line.startsWith("\t") && !headerLine.matches(line)) then
                Present(Reason.NonHeaderLineEndsSection)
            else Absent
            end if
        end if
    end classify

    def observed(using Frame): Chunk[Row] =
        messages.flatMap { message =>
            val ref      = reference(message)
            val mod      = module(message)
            val refKinds = ref.map((n, _) => n.path -> n.kind).toMap
            val modKinds = mod.map((n, _, _) => n.path -> n.kind).toMap
            val refFiles = ref.collect { case (n, Present(file)) => n.path -> file }.toMap
            val modBytes = mod.collect { case (n, Present(bytes), _) => n.path -> bytes }.toMap
            val paths    = (refKinds.keySet ++ modKinds.keySet).toSeq.sortBy(_.mkString("."))
            val rows     = paths.flatMap { path =>
                val refKind    = refKinds.getOrElse(path, "absent")
                val modKind    = modKinds.getOrElse(path, "absent")
                val difference =
                    if refKind != modKind then Some(("structure", modKind, refKind))
                    else if modKind == "leaf" then
                        val expected = EmbeddedMime4jSet.files(refFiles(path))()
                        val actual   = modBytes(path)
                        if actual.is(expected) then None else Some(("content", summary(actual), summary(expected)))
                    else None
                difference.map { (aspect, moduleValue, referenceValue) =>
                    classify(message, path, refKinds, modKinds) match
                        case Present(reason) => Row(message, show(path), aspect, moduleValue, referenceValue, reason)
                        case Absent          =>
                            throw new IllegalStateException(
                                s"$message ${show(path)} $aspect: the module gives [${escape(moduleValue)}], mime4j [${escape(referenceValue)}], and no reason explains it"
                            )
                }
            }
            Chunk.from(rows)
        }

    def render(rows: Seq[Row]): String = rows.map(_.line).mkString("\n")

    def parse(text: String): Chunk[Row] =
        Chunk.from(text.split("\n").toSeq.filterNot(l => l.startsWith("#") || l.isEmpty)).map { line =>
            val fields = line.split("\t", -1)
            Row(fields(0), fields(1), fields(2), unescape(fields(3)), unescape(fields(4)), Reason.valueOf(fields(5)))
        }

end MimeParserMime4j
