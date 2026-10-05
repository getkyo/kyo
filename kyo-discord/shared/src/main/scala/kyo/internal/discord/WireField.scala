package kyo.internal.discord

import kyo.*

/** The field encodings Discord's JSON uses that no kyo-schema default writes. */
private[kyo] object WireField:

    /** Discord's epoch, the first millisecond of 2015, in Unix milliseconds ("Snowflake ID Format Structure", `reference.mdx`). */
    inline val DiscordEpochMillis = 1420070400000L

    /** The largest unsigned 64-bit value divided by 10, the bound past which one more decimal digit overflows. */
    private inline val MaxBeforeDigit = 1844674407370955161L

    /** A snowflake from its decimal text. Discord sends every id as a string ("you should always expect a string", `reference.mdx`,
      * "ID Serialization") and the value uses all 64 bits once the timestamp reaches bit 63, so the text is read as unsigned.
      */
    def parseSnowflake(text: String)(using Frame): Result[DiscordInvalidIdException, Long] =
        import DiscordInvalidIdException.Problem
        @scala.annotation.tailrec
        def loop(i: Int, acc: Long): Result[DiscordInvalidIdException, Long] =
            if i >= text.length then Result.succeed(acc)
            else
                val c = text.charAt(i)
                if c < '0' || c > '9' then Result.fail(DiscordInvalidIdException(Problem.Character(i)))
                else
                    val digit = c - '0'
                    // Unsigned: acc * 10 + digit fits in 64 bits only below MaxBeforeDigit, or at it with a last digit of at most 5.
                    if java.lang.Long.compareUnsigned(acc, MaxBeforeDigit) > 0 || (acc == MaxBeforeDigit && digit > 5) then
                        Result.fail(DiscordInvalidIdException(Problem.Overflow))
                    else loop(i + 1, acc * 10 + digit)
                end if
        if text.isEmpty then Result.fail(DiscordInvalidIdException(Problem.Empty))
        else loop(0, 0L)
    end parseSnowflake

    def renderSnowflake(value: Long): String = java.lang.Long.toUnsignedString(value)

    /** When Discord created the snowflake: bits 63 to 22 are milliseconds since Discord's epoch. */
    def snowflakeCreatedAt(value: Long): Instant =
        Instant.Epoch + ((value >>> 22) + DiscordEpochMillis).millis

    /** A snowflake as Discord's decimal string; text that is not one fails the decode. */
    val snowflakeSchema: Schema[Long] =
        Schema.stringSchema.transformVia(text => parseSnowflake(text))(renderSnowflake)

    /** Unix seconds, as `TYPING_START` sends its `timestamp`. */
    object UnixSeconds extends kyo.schema.Transformer.Of[Instant](
            Schema.longSchema.transform[Instant](s => Instant.Epoch + s.seconds)(i => i.toJava.getEpochSecond)
        )

    /** Milliseconds as an integer, as `session_start_limit.reset_after` sends them. */
    object Millis extends kyo.schema.Transformer.Of[Duration](Schema.longSchema.transform[Duration](_.millis)(_.toMillis))

    /** A snowflake whose kind the JSON leaves open (a context-menu command's `target_id`, a user or a message), absent or a
      * decimal string.
      */
    object MaybeSnowflake extends kyo.schema.Transformer.Of[Maybe[Long]](Schema.maybeSchema(using snowflakeSchema))

    /** A URL Discord supplies, such as an attachment's; text `HttpUrl` cannot parse fails the decode. */
    val urlSchema: Schema[HttpUrl] =
        Schema.stringSchema.transformVia(text => HttpUrl.parse(text).mapFailure(_.getMessage))(_.full)

    /** `urlSchema` for a field typed `HttpUrl`. */
    object Url extends kyo.schema.Transformer.Of[HttpUrl](urlSchema)

    /** `urlSchema` for a field typed `Maybe[HttpUrl]`. */
    object MaybeUrl extends kyo.schema.Transformer.Of[Maybe[HttpUrl]](Schema.maybeSchema(using urlSchema))

end WireField
