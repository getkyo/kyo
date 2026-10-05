package kyo.internal.discord

import java.nio.charset.StandardCharsets.UTF_8
import kyo.*
import kyo.mime.Disposition
import kyo.mime.Parameters

/** A `multipart/form-data` body (RFC 7578) for a message with files, as Discord takes it (`reference.mdx`, "Uploading Files"): the
  * JSON in a `payload_json` part, then each file as `files[n]`.
  */
private[kyo] object Multipart:

    final case class Body(contentType: String, bytes: Span[Byte])

    def encode(payloadJson: String, files: Chunk[Discord.File])(using Frame): Body =
        val parts: Chunk[(String, Span[Byte])] = (head("payload_json", Absent, Present("application/json")) -> utf8(payloadJson)) +:
            files.zipWithIndex.map((file, n) =>
                head(s"files[$n]", Present(file.name), file.contentType.map(contentTypeOf)) -> file.bytes
            )
        // kyo-mime picks the least `kyo-discord-<k>` no line of any part starts with, so no part can hold the delimiter and the same
        // message always gets the same boundary.
        val boundary                  = kyo.mime.Multipart.boundary(Prefix, parts.map((head, content) => Chunk(utf8(head), content)))
        val pieces: Chunk[Span[Byte]] =
            parts.flatMap((head, content) => Chunk(utf8(s"--$boundary\r\n$head\r\n"), content, utf8("\r\n"))) :+ utf8(s"--$boundary--\r\n")
        val out = new Array[Byte](pieces.foldLeft(0)(_ + _.size))
        discard(pieces.foldLeft(0)((at, piece) => at + piece.copyToArray(out, at)))
        // Unsafe: `out` is allocated here and no reference to it escapes but the span, so wrapping it without a copy is safe.
        Body(s"multipart/form-data; boundary=$boundary", Span.fromUnsafe(out))
    end encode

    private inline val Prefix = "kyo-discord-"

    private def utf8(s: String): Span[Byte] = Span.from(s.getBytes(UTF_8))

    /** A part's head: its disposition and, when known, its type, each line ended, before the blank line. */
    private def head(name: String, fileName: Maybe[String], contentType: Maybe[String])(using Frame): String =
        val parameters  = Seq("name" -> name) ++ fileName.fold(Seq.empty)(f => Seq("filename" -> f))
        val disposition = Disposition.init("form-data", parameters*).flatMap(_.render(Parameters.Style.FormData)) match
            case Result.Success(text) => text
            // The type and parameter names are this object's constants, which kyo-mime accepts.
            case _ => bug(s"kyo-mime refused the disposition of the part $name")
        s"Content-Disposition: $disposition\r\n" + contentType.fold("")(t => s"Content-Type: $t\r\n")
    end head

    private def contentTypeOf(mediaType: kyo.mime.MediaType)(using Frame): String =
        mediaType.render match
            case Result.Success(text) => text
            // A MediaType is built by kyo-mime's own parser or init, whose values its renderer writes.
            case _ => bug("kyo-mime refused to render a media type it built")

end Multipart
