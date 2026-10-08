package kyo.internal.email.mime

import kyo.*
import kyo.internal.charset.Utf8
import kyo.internal.email.vectors.EmbeddedContentType
import kyo.internal.email.vectors.EmbeddedUnstructured

/** Where the module's reading of header fields differs from Stalwart mail-parser's, over every vector of its `unstructured.json` and
  * `content_type.json`.
  *
  * The tests compare these rows with the difference lists under `resources/kyo/internal/email/mime`, which are kept by hand from the
  * rows a failing test prints. Each row's reason comes from a classifier whose rules are tried in order; a difference no rule explains is not a
  * row but a module defect, and computing the rows fails on it.
  */
object HeaderCodecStalwart:

    final case class UnstructuredVector(header: String, expected: String) derives Schema, CanEqual

    def vectors(using Frame): Chunk[UnstructuredVector] =
        EmailLiterals.valid(Json.decode[Chunk[UnstructuredVector]](EmbeddedUnstructured.text))

    /** Why the module and Stalwart read a vector differently. */
    enum Reason(val description: String) derives CanEqual:
        case WhiteSpaceInEncodedText
            extends Reason(
                "Stalwart decodes a =?...?= span whose encoded text holds white space, written in or left by a fold; RFC 2047 sections 2 and 5 forbid it, and the module keeps each piece as text"
            )
        case SpaceInsertedBesideWord
            extends Reason(
                "Both decode an encoded word joined to other text with no white space between them; Stalwart rebuilds the white space around it as single spaces, which inserts one there, and the module keeps the text as written, as CPython's email.policy.default parser does"
            )
        case FoldWhiteSpaceCollapsed
            extends Reason(
                "Stalwart replaces a fold and the white space around it with one space; RFC 5322 section 2.2.3 removes only the line end, and the module keeps the white space"
            )
    end Reason

    final case class Row(index: Int, header: String, module: String, reference: String, reason: Reason) derives CanEqual:
        def line: String = Seq(index.toString, escape(header), escape(module), escape(reference), reason.toString).mkString("\t")

    /** The field the module reads from a vector: a name, a colon and the UTF-8 octets of the header, as Stalwart's own tests feed them. */
    def field(header: String): HeaderCodec.Field =
        HeaderCodec.readSection(Utf8.encode("X:" + header), 0).fields(0)

    def field(vector: UnstructuredVector): HeaderCodec.Field = field(vector.header)

    /** Each vector the module reads differently, with its reason. A difference no rule explains fails. */
    def observed(using Frame): Chunk[Row] =
        Chunk.from(vectors.zipWithIndex).flatMap { (vector, index) =>
            val value  = field(vector).value
            val result = HeaderCodec.decodeUnstructured(value)
            if result == vector.expected then Chunk.empty
            else
                classify(value, result, vector.expected) match
                    case Present(reason) => Chunk(Row(index, vector.header, result, vector.expected, reason))
                    case Absent          =>
                        throw new IllegalStateException(
                            s"vector $index [${escape(vector.header)}]: the module gives [${escape(result)}], Stalwart " +
                                s"[${escape(vector.expected)}], and no reason explains it"
                        )
            end if
        }

    private val word = "=\\?[^? \t]+\\?[^? \t]+\\?[^? \t]*\\?="

    private val wordWithWhiteSpace = "=\\?[^? \t]+\\?[^? \t]+\\?[^?]*[ \t][^?]*\\?=".r

    private val words = s"($word)+".r

    private val anyWord = word.r

    /** The first rule that explains a difference, over the unfolded value the module read and both results. */
    def classify(value: String, module: String, reference: String): Maybe[Reason] =
        if wordWithWhiteSpace.findFirstIn(value).isDefined then Present(Reason.WhiteSpaceInEncodedText)
        else if tokens(value).exists(t => !words.matches(t) && anyWord.findFirstIn(t).isDefined) && strip(module) == strip(reference) then
            Present(Reason.SpaceInsertedBesideWord)
        else if collapse(module) == collapse(reference) then Present(Reason.FoldWhiteSpaceCollapsed)
        else Absent

    private def tokens(value: String): Seq[String] = value.split("[ \t]+").toSeq.filter(_.nonEmpty)

    private def collapse(text: String): String = text.replaceAll("[ \t]+", " ")

    private def strip(text: String): String = text.replaceAll("[ \t]+", "")

    def render(rows: Seq[Row]): String = rows.map(_.line).mkString("\n")

    def parse(text: String): Chunk[Row] =
        Chunk.from(text.split("\n").toSeq.filterNot(l => l.startsWith("#") || l.isEmpty)).map { line =>
            val fields = line.split("\t", -1)
            Row(fields(0).toInt, unescape(fields(1)), unescape(fields(2)), unescape(fields(3)), Reason.valueOf(fields(4)))
        }

    def escape(text: String): String =
        text.flatMap {
            case '\\'             => "\\\\"
            case '\t'             => "\\t"
            case '\r'             => "\\r"
            case '\n'             => "\\n"
            case c if c.isControl => f"\\u${c.toInt}%04X"
            case c                => c.toString
        }

    final case class ContentTypeExpected(c_type: String, c_subtype: Maybe[String], attributes: Maybe[Chunk[Chunk[String]]])
        derives Schema, CanEqual

    final case class ContentTypeVector(header: String, expected: Maybe[ContentTypeExpected]) derives Schema, CanEqual

    def contentTypeVectors(using Frame): Chunk[ContentTypeVector] =
        EmailLiterals.valid(Json.decode[Chunk[ContentTypeVector]](EmbeddedContentType.text))

    /** A reading in Stalwart's shape: the type, the subtype when there is one, and the parameters in order. */
    final case class Reading(mainType: String, subType: Maybe[String], parameters: Chunk[(String, String)]) derives CanEqual:
        def render: String = (mainType + subType.map("/" + _).getOrElse("")) +
            parameters.map((n, v) => s"; $n=\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
                .mkString
    end Reading

    def referenceReading(vector: ContentTypeVector): Maybe[Reading] =
        vector.expected.map(e => Reading(e.c_type, e.c_subtype, e.attributes.getOrElse(Chunk.empty).map(p => (p(0), p(1)))))

    /** The module's reading: `contentType`, or `contentDisposition` when that answers nothing. */
    def moduleReading(vector: ContentTypeVector)(using Frame): Maybe[Reading] =
        val value = field(vector.header)
        HeaderCodec.contentType(value) match
            case Present(t) => Present(Reading(t.mainType, Present(t.subType), t.parameters.map(p => (p.name, p.value))))
            case Absent     => HeaderCodec.contentDisposition(value).map(d => Reading(d.kind.label, Absent, d.parameters))
    end moduleReading

    private def renderReading(reading: Maybe[Reading]): String = reading.map(_.render).getOrElse("null")

    /** Why the module and Stalwart read a content-type vector differently. */
    enum ContentTypeReason(val description: String) derives CanEqual:
        case InvalidContentType
            extends ContentTypeReason(
                "Stalwart reads a value that is not a type, a subtype and parameters (no subtype, other text after the subtype, a character outside the token grammar) as far as it goes; the module answers no media type and no disposition, and the parser applies the default of RFC 2045 section 5.2"
            )
        case NameNotRfc2231
            extends ContentTypeReason(
                "a parameter name holds a * outside RFC 2231's attribute [*section] [*] shape (*, 3**, 1 * 999); Stalwart reads such names by its own rules, and the module reads each as a plain parameter of that whole name, or skips it when white space splits it"
            )
        case LanguageAttribute
            extends ContentTypeReason(
                "Stalwart reports the RFC 2231 language as a parameter named <name>-language; the module drops the language"
            )
        case WhiteSpaceBetweenAdjacentWords
            extends ContentTypeReason(
                "Stalwart keeps the white space between adjacent encoded words in a quoted value; RFC 2047 section 6.2 ignores it, and the module drops it"
            )
        case FieldEndWhiteSpace
            extends ContentTypeReason(
                "the value ends inside an unclosed quoted string; Stalwart keeps the white space at the end of the field, which the module's header reader removes"
            )
        case CharsetOnLaterSegment
            extends ContentTypeReason(
                "a continuation segment after the first carries a charset'language' prefix; Stalwart removes it, and the module reads it as data, since RFC 2231 section 4.1 puts that information only at the beginning of the value"
            )
    end ContentTypeReason

    final case class ContentTypeRow(index: Int, header: String, module: String, reference: String, reason: ContentTypeReason)
        derives CanEqual:
        def line: String = Seq(index.toString, escape(header), escape(module), escape(reference), reason.toString).mkString("\t")

    /** Each vector the module reads differently, with its reason. A difference no rule explains fails. */
    def observedContentType(using Frame): Chunk[ContentTypeRow] =
        Chunk.from(contentTypeVectors.zipWithIndex).flatMap { (vector, index) =>
            val module    = moduleReading(vector)
            val reference = referenceReading(vector)
            if module == reference then Chunk.empty
            else
                classifyContentType(vector.header, module, reference) match
                    case Present(reason) =>
                        Chunk(ContentTypeRow(index, vector.header, renderReading(module), renderReading(reference), reason))
                    case Absent =>
                        throw new IllegalStateException(
                            s"vector $index [${escape(vector.header)}]: the module gives [${renderReading(module)}], Stalwart " +
                                s"[${renderReading(reference)}], and no reason explains it"
                        )
            end if
        }

    private val laterSegmentCharset = "\\*[1-9][0-9]*\\*=\"?[A-Za-z0-9_.:-]+'".r

    private val adjacentWords = "\\?=[ \t\r\n]+=\\?".r

    private val rfc2231Name = "[^*\\s]+(\\*[0-9]{1,9})?\\*?".r

    private def nameOutsideRfc2231(name: String): Boolean = name.contains('*') && !rfc2231Name.matches(name)

    /** The first rule that explains a difference. */
    def classifyContentType(header: String, module: Maybe[Reading], reference: Maybe[Reading]): Maybe[ContentTypeReason] =
        def withoutLanguage(r: Reading): Reading = Reading(r.mainType, r.subType, r.parameters.filterNot(_._1.endsWith("-language")))
        def mapValues(r: Reading)(f: String => String): Reading = Reading(r.mainType, r.subType, r.parameters.map((n, v) => (n, f(v))))
        (module, reference) match
            case (Absent, Present(_))     => Present(ContentTypeReason.InvalidContentType)
            case (Present(m), Present(r)) =>
                if header.split(";").exists(part => nameOutsideRfc2231(part.takeWhile(_ != '=').trim.split("\\s+").last)) then
                    Present(ContentTypeReason.NameNotRfc2231)
                else if laterSegmentCharset.findFirstIn(header).isDefined then Present(ContentTypeReason.CharsetOnLaterSegment)
                else if r.parameters.exists(_._1.endsWith("-language")) && withoutLanguage(r) == m then
                    Present(ContentTypeReason.LanguageAttribute)
                else if mapValues(r)(_.reverse.dropWhile(c => c == ' ' || c == '\t').reverse) == m then
                    Present(ContentTypeReason.FieldEndWhiteSpace)
                else if adjacentWords.findFirstIn(header).isDefined && mapValues(r)(strip) == mapValues(m)(strip) then
                    Present(ContentTypeReason.WhiteSpaceBetweenAdjacentWords)
                else Absent
            case _ => Absent
        end match
    end classifyContentType

    def renderContentType(rows: Seq[ContentTypeRow]): String = rows.map(_.line).mkString("\n")

    def parseContentType(text: String): Chunk[ContentTypeRow] =
        Chunk.from(text.split("\n").toSeq.filterNot(l => l.startsWith("#") || l.isEmpty)).map { line =>
            val fields = line.split("\t", -1)
            ContentTypeRow(
                fields(0).toInt,
                unescape(fields(1)),
                unescape(fields(2)),
                unescape(fields(3)),
                ContentTypeReason.valueOf(fields(4))
            )
        }

    def unescape(text: String): String =
        val out = new StringBuilder
        var i   = 0
        while i < text.length do
            if text.charAt(i) == '\\' && i + 5 < text.length && text.charAt(i + 1) == 'u' then
                out.append(Integer.parseInt(text.substring(i + 2, i + 6), 16).toChar)
                i += 6
            else if text.charAt(i) == '\\' && i + 1 < text.length then
                out.append(text.charAt(i + 1) match
                    case 't'   => '\t'
                    case 'r'   => '\r'
                    case 'n'   => '\n'
                    case other => other)
                i += 2
            else
                out.append(text.charAt(i))
                i += 1
        end while
        out.toString
    end unescape

end HeaderCodecStalwart
