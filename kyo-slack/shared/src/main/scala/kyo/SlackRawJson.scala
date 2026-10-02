package kyo

/** The raw JSON of a payload kyo-slack delivers without modelling: what the `Unknown` cases of
  * [[kyo.SlackEnvelope]], [[kyo.SlackInteraction]] and [[kyo.SlackEvent]] hold, and a modal
  * submission's `state`, whose shape Slack leaves open by element type.
  *
  * `json` is the payload as it arrived and `value` its JSON text. Its `Schema` is that JSON, so an
  * `Unknown` encodes back to what Slack sent.
  *
  * A raw payload is the caller's data, and it can hold credentials: an interaction payload carries its
  * `response_url`, whose path authorizes posting, and Slack's deprecated verification `token`. So
  * `toString` renders the text's length, never its content, and a logged or printed envelope shows no
  * part of it. Values compare by their JSON.
  */
final class SlackRawJson private (val json: Structure.Value, val value: String):
    override def equals(other: Any): Boolean =
        other match
            case that: SlackRawJson => json == that.json
            case _                  => false
    override def hashCode: Int    = json.hashCode
    override def toString: String = s"SlackRawJson(${value.length} characters)"
end SlackRawJson

object SlackRawJson:
    def apply(json: Structure.Value)(using Frame): SlackRawJson = new SlackRawJson(json, Json.encode(json))

    given Schema[SlackRawJson] = Schema.init[SlackRawJson](
        writeFn = (raw, writer) => Structure.Value.valueSchema.serializeWrite(raw.json, writer),
        readFn = reader => SlackRawJson(Structure.Value.valueSchema.serializeRead(reader))(using reader.frame)
    )

    given CanEqual[SlackRawJson, SlackRawJson] = CanEqual.derived
end SlackRawJson
