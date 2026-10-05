package kyo.internal.discord

import kyo.*

/** The JSON Discord sends that no public type represents. */
private[kyo] object Frames:

    /** Discord's error body (`topics/opcodes-and-status-codes.mdx`, "JSON"): its code, its message, and for 50035 the tree of refused
      * fields.
      */
    final case class ErrorBody(code: Int, message: String, errors: Maybe[Structure.Value] = Absent)

    object ErrorBody:
        given Schema[ErrorBody] = Schema.derived[ErrorBody]

    /** A 429's body (`topics/rate-limits.mdx`, "Exceeding A Rate Limit"): the wait in float seconds, whether the limit is the global one,
      * and the error code when Discord sends one.
      */
    final case class RateLimitBody(retryAfter: Double, global: Boolean = false, code: Maybe[Int] = Absent)

    object RateLimitBody:
        given Schema[RateLimitBody] = Schema.derived[RateLimitBody].renameAllFields(Schema.NameCase.SnakeCase)

    /** The fields of `GET /applications/@me` the client keeps (`resources/application.mdx`, "Get Current Application"). */
    final case class CurrentApplication(id: Discord.ApplicationId)

    object CurrentApplication:
        given Schema[CurrentApplication] = Schema.derived[CurrentApplication]

    /** The body of "Create DM" (`resources/user.mdx`). */
    final case class CreateDm(recipientId: Discord.UserId)

    object CreateDm:
        given Schema[CreateDm] = Schema.derived[CreateDm].renameAllFields(Schema.NameCase.SnakeCase)

    // --- The Gateway (`events/gateway.mdx`, "Gateway Events": `{op, d, s, t}`) ---

    /** A frame's opcode, sequence and dispatch name, with its payload kept as JSON for the opcode to read. */
    final case class Envelope(op: Int, d: Maybe[Structure.Value] = Absent, s: Maybe[Long] = Absent, t: Maybe[String] = Absent)

    object Envelope:
        given Schema[Envelope] = Schema.derived[Envelope]

    /** Hello's payload (op 10). */
    final case class Hello(heartbeatInterval: Long)

    object Hello:
        given Schema[Hello] = Schema.derived[Hello].renameAllFields(Schema.NameCase.SnakeCase)

    /** The fields of `READY` the session keeps to resume. */
    final case class ReadyInfo(sessionId: String, resumeGatewayUrl: String)

    object ReadyInfo:
        given Schema[ReadyInfo] = Schema.derived[ReadyInfo].renameAllFields(Schema.NameCase.SnakeCase)

    /** A frame the client sends: an opcode and its payload. */
    final case class Outbound[A](op: Int, d: A)

    object Outbound:
        given [A: Schema]: Schema[Outbound[A]] = Schema.derived[Outbound[A]]

    /** Identify's connection properties, which Discord shows nowhere and does not check. */
    final case class Properties(os: String, browser: String, device: String)

    object Properties:
        given Schema[Properties] = Schema.derived[Properties]

    /** Identify's payload (op 2); `shard` is `[shard_id, num_shards]`. */
    final case class Identify(token: Discord.Token, intents: Discord.Intents, properties: Properties, shard: Maybe[Chunk[Int]] = Absent)

    object Identify:
        given Schema[Identify] = Schema.derived[Identify]

    /** Resume's payload (op 6). */
    final case class Resume(token: Discord.Token, sessionId: String, seq: Maybe[Long])

    object Resume:
        given Schema[Resume] = Schema.derived[Resume].renameAllFields(Schema.NameCase.SnakeCase)

end Frames
