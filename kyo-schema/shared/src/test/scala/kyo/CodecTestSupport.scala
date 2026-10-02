package kyo

/** Shared helpers for the codec test suites.
  *
  * Centralizes the round-trip and byte-comparison boilerplate the format suites previously
  * re-declared. Suites needing multi-path or custom-compare round trips keep their own helper; this
  * object covers the plain cases.
  */
object CodecTestSupport:

    /** Structural equality of two byte spans. `Span[Byte]` equality is not element-wise, so the
      * suites open-code `a.toArray.toSeq == b.toArray.toSeq`; this is that check in one place.
      */
    def sameBytes(actual: Span[Byte], expected: Span[Byte]): Boolean =
        java.util.Arrays.equals(actual.toArray, expected.toArray)

    /** Lowercase hex of a byte span, two digits per byte, the form the binary-format wire pins are written in. */
    def hex(bytes: Span[Byte]): String =
        bytes.toArray.map(b => f"${b & 0xff}%02x").mkString

    /** The bytes a [[hex]] string spells. */
    def unhex(text: String): Span[Byte] =
        Span.from(text.grouped(2).map(pair => Integer.parseInt(pair, 16).toByte).toArray)

    /** Encode then decode a value through codec `C`, returning the decoded value or throwing on
      * decode failure. The plain round-trip suites share this; suites that assert extra paths or a
      * custom comparison keep their own helper.
      */
    def roundTrip[A, C <: Codec](value: A)(using schema: Schema[A], codec: C, frame: Frame): A =
        schema.decode[C](schema.encode[C](value)).getOrThrow

    /** The simple class name of a decode failure, or the unexpected outcome when the result is not one. */
    def failureKind(result: Result[DecodeException, Any]): String =
        result match
            case Result.Failure(e) => e.getClass.getSimpleName
            case other             => s"not a failure: $other"

    /** The failure kinds of a `Short` field written through codec `C` as 99999 and as 1.5, first read directly into the record, then
      * read as a captured `Structure.Value` and converted from it.
      */
    def shortNarrowing[C <: Codec](using codec: C, frame: Frame): (Chunk[String], Chunk[String]) =
        val inputs   = Chunk(Schema[WCShortWide].encode[C](WCShortWide(99999)), Schema[WCShortFraction].encode[C](WCShortFraction(1.5)))
        val direct   = inputs.map(bytes => failureKind(Schema[WCShort].decode[C](bytes)))
        val captured = inputs.map(bytes => failureKind(Schema[Structure.Value].decode[C](bytes).flatMap(Structure.decode[WCShort](_))))
        (direct, captured)
    end shortNarrowing

    /** The failure kind of a record written through codec `C` and cut off two bytes before its end. */
    def truncation[C <: Codec](using codec: C, frame: Frame): String =
        val bytes = Schema[WCRecord].encode[C](WCValues.record)
        failureKind(Schema[WCRecord].decode[C](bytes.slice(0, bytes.size - 2)))
    end truncation
end CodecTestSupport
