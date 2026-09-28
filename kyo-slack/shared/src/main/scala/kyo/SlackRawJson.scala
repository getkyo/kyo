package kyo

/** The raw JSON text of a payload kyo-slack delivers without modelling: what the `Unknown` cases of
  * [[kyo.SlackEnvelope]], [[kyo.SlackInteraction]] and [[kyo.SlackEvent]] hold, and a modal
  * submission's `stateJson`.
  *
  * A raw payload is the caller's data, and it can hold credentials: an interaction payload carries its
  * `response_url`, whose path authorizes posting, and Slack's deprecated verification `token`. So the
  * text is reachable only through `value`, and `toString` renders its length, never its content, so a
  * logged or printed envelope shows no part of it. Values compare by their text and carry no `Schema`.
  */
final class SlackRawJson private (val value: String):
    override def equals(other: Any): Boolean =
        other match
            case that: SlackRawJson => value == that.value
            case _                  => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = s"SlackRawJson(${value.length} characters)"
end SlackRawJson

object SlackRawJson:
    def apply(value: String): SlackRawJson     = new SlackRawJson(value)
    given CanEqual[SlackRawJson, SlackRawJson] = CanEqual.derived
end SlackRawJson
