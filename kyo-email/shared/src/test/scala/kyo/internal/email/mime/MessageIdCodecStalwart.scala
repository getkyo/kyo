package kyo.internal.email.mime

import kyo.*
import kyo.internal.email.mime.HeaderCodecStalwart.escape
import kyo.internal.email.mime.HeaderCodecStalwart.unescape
import kyo.internal.email.vectors.EmbeddedId

/** Where the module's reading of message ids differs from Stalwart mail-parser's, over every vector of its `id.json`. The rows are
  * compared with `stalwart-id-differences.tsv`, kept by hand from the rows a failing test prints; a difference no reason explains fails.
  */
object MessageIdCodecStalwart:

    final case class IdVector(header: String, expected: Maybe[Chunk[String]]) derives Schema, CanEqual

    def vectors(using Frame): Chunk[IdVector] =
        EmailLiterals.valid(Json.decode[Chunk[IdVector]](EmbeddedId.text))

    def referenceReading(vector: IdVector): Chunk[String] = vector.expected.getOrElse(Chunk.empty)

    def moduleReading(vector: IdVector): Chunk[String] = MessageIdCodec.parse(HeaderCodecStalwart.field(vector.header).value).map(_.value)

    /** Why the module and Stalwart read an id vector differently. */
    enum Reason(val description: String) derives CanEqual:
        case CfwsInsideId
            extends Reason(
                "Stalwart keeps the white space and comments inside an obsolete msg-id; RFC 5322 section 4.5.4 says they are not part of the id, and the module removes them"
            )
    end Reason

    final case class Row(index: Int, header: String, module: String, reference: String, reason: Reason) derives CanEqual:
        def line: String = Seq(index.toString, escape(header), escape(module), escape(reference), reason.toString).mkString("\t")

    def observed(using Frame): Chunk[Row] =
        Chunk.from(vectors.zipWithIndex).flatMap { (vector, index) =>
            val module    = moduleReading(vector)
            val reference = referenceReading(vector)
            if module == reference then Chunk.empty
            else
                classify(module, reference) match
                    case Present(reason) => Chunk(Row(index, vector.header, module.mkString(" "), reference.mkString(" "), reason))
                    case Absent          =>
                        throw new IllegalStateException(
                            s"vector $index [${escape(vector.header)}]: the module gives [${module.mkString(" ")}], Stalwart " +
                                s"[${reference.mkString(" ")}], and no reason explains it"
                        )
            end if
        }

    private def withoutCfws(id: String): String = id.replaceAll("\\([^()]*\\)", "").replaceAll("[ \t]+", "")

    def classify(module: Chunk[String], reference: Chunk[String]): Maybe[Reason] =
        if reference.map(withoutCfws) == module then Present(Reason.CfwsInsideId) else Absent

    def render(rows: Seq[Row]): String = rows.map(_.line).mkString("\n")

    def parse(text: String): Chunk[Row] =
        Chunk.from(text.split("\n").toSeq.filterNot(l => l.startsWith("#") || l.isEmpty)).map { line =>
            val fields = line.split("\t", -1)
            Row(fields(0).toInt, unescape(fields(1)), unescape(fields(2)), unescape(fields(3)), Reason.valueOf(fields(4)))
        }

end MessageIdCodecStalwart
