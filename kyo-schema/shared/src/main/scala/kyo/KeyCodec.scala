package kyo

/** String-key codec for map keys.
  *
  * A `KeyCodec[K]` lets the generic `Map[K, V]` schema encode maps as JSON objects keyed by `K`'s string form. Without a `KeyCodec[K]`,
  * `Map[K, V]` falls back to the array-of-pairs encoding (Phase 8).
  *
  * Built-in instances cover `String`, `Int`, `Long`, and `java.util.UUID`. Implement this trait to make a custom key type eligible for the
  * JSON-object encoding.
  */
trait KeyCodec[K]:
    /** Encode a key to its canonical string form. Must round-trip with `decode`. */
    def encode(k: K): String

    /** Decode a key from its string form. Returns `Result.Failure(DecodeException)` if the input does not parse. */
    def decode(s: String)(using Frame): Result[DecodeException, K]
end KeyCodec

object KeyCodec:

    /** Summons the `KeyCodec[K]` instance in scope. */
    inline def apply[K](using kc: KeyCodec[K]): KeyCodec[K] = kc

    /** Shared Json codec used by built-in decoders for ParseException's `format` slot.
      * Pre-instantiated so each failed decode does not allocate a throwaway codec.
      */
    private val jsonCodec: Codec = Json()

    /** Identity codec — keys round-trip verbatim as their own string form. */
    given stringKeyCodec: KeyCodec[String] with
        def encode(k: String): String                                       = k
        def decode(s: String)(using Frame): Result[DecodeException, String] = Result.succeed(s)

    /** Integer key codec — encodes as base-10 string, fails with `ParseException` on non-integer input. */
    given intKeyCodec: KeyCodec[Int] with
        def encode(k: Int): String = k.toString
        def decode(s: String)(using Frame): Result[DecodeException, Int] =
            s.toIntOption match
                case Some(n) => Result.succeed(n)
                case None    => Result.fail(ParseException(jsonCodec, s, "Int"))
    end intKeyCodec

    /** Long key codec — encodes as base-10 string, fails with `ParseException` on non-long input. */
    given longKeyCodec: KeyCodec[Long] with
        def encode(k: Long): String = k.toString
        def decode(s: String)(using Frame): Result[DecodeException, Long] =
            s.toLongOption match
                case Some(n) => Result.succeed(n)
                case None    => Result.fail(ParseException(jsonCodec, s, "Long"))
    end longKeyCodec

    /** UUID key codec — encodes as RFC 4122 canonical form, fails with `ParseException` on malformed input. */
    given uuidKeyCodec: KeyCodec[java.util.UUID] with
        def encode(k: java.util.UUID): String = k.toString
        def decode(s: String)(using Frame): Result[DecodeException, java.util.UUID] =
            try Result.succeed(java.util.UUID.fromString(s))
            catch case _: IllegalArgumentException => Result.fail(ParseException(jsonCodec, s, "UUID"))
    end uuidKeyCodec

end KeyCodec
