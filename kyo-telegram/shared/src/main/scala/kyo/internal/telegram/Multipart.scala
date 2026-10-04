package kyo.internal.telegram

import kyo.*
import kyo.internal.charset.Utf8

/** A `multipart/form-data` body (RFC 7578) for a Bot API call that uploads a file: one part per
  * parameter, with a JSON object or array written as its JSON text, and one part per uploaded file.
  */
private[kyo] object Multipart:

    final case class Body(contentType: String, bytes: Span[Byte])

    /** `record`'s fields as parts, then `files`. A value other than a record has no fields to send. */
    def encode(record: Structure.Value, files: Chunk[(String, Telegram.InputFile.Upload)])(using Frame): Body < Sync =
        val params: Chunk[(String, Structure.Value)] =
            record match
                case Structure.Value.Record(fields) => Chunk.from(fields)
                case Structure.Value.VariantCase(_, _) | Structure.Value.Sequence(_) | Structure.Value.MapEntries(_) |
                    Structure.Value.Str(_) | Structure.Value.Bool(_) | Structure.Value.Integer(_) | Structure.Value.Decimal(_) |
                    Structure.Value.BigNum(_) | Structure.Value.Bytes(_) | Structure.Value.Instant(_) | Structure.Value.Duration(_) |
                    Structure.Value.Null => Chunk.empty
        val parts: Chunk[(String, Span[Byte])] =
            params.map((name, value) => header(name, Absent, Absent) -> Utf8.encode(fieldText(value))) ++
                files.map((name, upload) => header(name, Present(upload.name), upload.contentType) -> upload.bytes)
        this.boundary.map { boundary =>
            val pieces: Chunk[Span[Byte]] =
                parts.flatMap((head, content) => Chunk(Utf8.encode(s"--$boundary\r\n$head\r\n"), content, Utf8.encode("\r\n"))) :+
                    Utf8.encode(s"--$boundary--\r\n")
            Body(s"multipart/form-data; boundary=$boundary", Span.concat(pieces*))
        }
    end encode

    private def fieldText(value: Structure.Value)(using Frame): String =
        value match
            case Structure.Value.Str(s)     => s
            case Structure.Value.Integer(n) => n.toString
            case Structure.Value.Bool(b)    => b.toString
            case other @ (Structure.Value.Record(_) | Structure.Value.VariantCase(_, _) | Structure.Value.Sequence(_) |
                Structure.Value.MapEntries(_) | Structure.Value.Decimal(_) | Structure.Value.BigNum(_) | Structure.Value.Bytes(_) |
                Structure.Value.Instant(_) | Structure.Value.Duration(_) | Structure.Value.Null) => Json.encode(other)

    private def header(name: String, fileName: Maybe[String], contentType: Maybe[String]): String =
        val disposition = s"Content-Disposition: form-data; name=\"${quoted(name)}\"" +
            fileName.fold("")(f => s"; filename=\"${quoted(f)}\"")
        disposition + "\r\n" + contentType.fold("")(t => s"Content-Type: ${t.filter(c => c != '\r' && c != '\n')}\r\n")
    end header

    /** A name inside a quoted header parameter, with `"`, CR and LF percent-encoded as the HTML
      * standard's multipart/form-data encoding does, so no name can end the header.
      */
    private def quoted(s: String): String =
        s.flatMap {
            case '"'  => "%22"
            case '\r' => "%0D"
            case '\n' => "%0A"
            case c    => c.toString
        }

    // 32 random alphanumerics: RFC 2046 section 5.1.1's guarantee that the boundary occurs in no part rests on
    // randomness, as in every multipart client, rather than on scanning the content. 62^32 values make a collision
    // with content negligible, and with the 13-character prefix the boundary is 45 characters, under RFC 2046's 70.
    private def boundary(using Frame): String < Sync =
        Random.nextStringAlphanumeric(32).map(random => s"kyo-telegram-$random")

end Multipart
