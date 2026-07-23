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

    /** Encode then decode a value through codec `C`, returning the decoded value or throwing on
      * decode failure. The plain round-trip suites share this; suites that assert extra paths or a
      * custom comparison keep their own helper.
      */
    def roundTrip[A, C <: Codec](value: A)(using schema: Schema[A], codec: C, frame: Frame): A =
        schema.decode[C](schema.encode[C](value)).getOrThrow
end CodecTestSupport
