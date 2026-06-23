package kyo.internal.mcp

import kyo.*

/** Shared total decoder for MCP closed-vocabulary wire enums.
  *
  * Builds a `Schema[E]` whose decode path is total: an unknown wire string throws
  * `TypeMismatchException` (a `DecodeException`), which `Structure.decode` (defined as
  * `Result.catching[DecodeException](...)`) converts to `Result.Failure`. The encode path
  * is the supplied total case-to-wire table. This is the in-module sanctioned mechanism
  * (the discriminator schemas in `McpContentSchema` throw the same exception type); there is
  * no `Result`-returning `Schema.transform` variant to use instead.
  *
  * Typed-failure totality matters here: any other decode approach (valueOf, pattern match on
  * capitalized strings) throws a JVM exception type that is NOT a `DecodeException`, so the
  * receive loop's `Result.catching[DecodeException]` would not catch it and a hostile peer
  * string would panic the dispatch fiber. Routing through `closed` makes every such decode
  * surface a typed `Result.Failure` instead of an uncaught exception.
  *
  * The decoder is built with `Schema.init` so its `readFn` receives the `Codec.Reader`. The
  * reader carries the `Frame` of the user's `decode` call (`Codec.Reader.frame`), so the
  * `TypeMismatchException` raised on an unknown wire string is attached to the user's decode
  * site, not to a synthetic internal frame. This matches `StructureValueReader`, which raises
  * every `TypeMismatchException` under the same propagated frame. The wire shape is identical
  * to `Schema.stringSchema`: the encode path emits a plain JSON string (a `Role` encodes to
  * `"user"`), the decode path reads a plain JSON string, and `structure` is the string schema's
  * structure.
  */
private[kyo] object McpEnumSchema:

    /** Builds a total `Schema[E]` from a bidirectional wire/case table.
      *
      * @param pairs the `(wireString, case)` table; both directions are derived from it
      */
    def closed[E](pairs: (String, E)*)(using CanEqual[E, E]): Schema[E] =
        val toCase: Map[String, E] = pairs.toMap
        val toWire: E => String =
            val byCase = pairs.map((w, e) => (e, w)).toMap
            e => byCase(e)
        val expected = pairs.iterator.map(_._1).mkString("|")
        Schema.init[E](
            writeFn = (e, w) => Schema.stringSchema.serializeWrite(toWire(e), w),
            readFn = reader =>
                given Frame = reader.frame
                val s       = Schema.stringSchema.serializeRead(reader)
                toCase.get(s) match
                    case Some(e) => e
                    case None    => throw TypeMismatchException(Seq.empty, expected, s)
            ,
            structure = Schema.stringSchema.structure
        )
    end closed

    /** Raises the discriminator-mismatch failure for a hand-rolled multi-arm wire schema.
      *
      * The reader-driven discriminator schemas (`McpSamplingContentSchema`,
      * `McpCompletionRefSchema`) read a `"type"` tag and reject an unknown value. This helper
      * owns the `given Frame = reader.frame`, so the rejection is attached to the user's decode
      * site: the caller supplies the `Codec.Reader`, never a `Frame`, and a decode frame cannot
      * be supplied at the call site. The thrown `TypeMismatchException` is a `DecodeException`
      * the receive loop's `Result.catching[DecodeException]` converts to `Result.Failure`.
      *
      * @param reader   the in-flight decode reader, whose `frame` carries the user's decode site
      * @param expected the pipe-joined wire tags the schema accepts (for the diagnostic)
      * @param actual   the unrecognized wire tag that was read
      */
    def discriminatorMismatch(reader: Codec.Reader, expected: String, actual: String): Nothing =
        given Frame = reader.frame
        throw TypeMismatchException(Seq.empty, expected, actual)

end McpEnumSchema
