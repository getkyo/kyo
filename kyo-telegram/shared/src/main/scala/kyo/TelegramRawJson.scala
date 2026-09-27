package kyo

/** A JSON value exactly as Telegram sent it, held by the `Unknown` cases of the model.
  *
  * An update or a message whose kind the module does not model, or whose known kind lacks a field that
  * kind requires, is not dropped: it arrives as an `Unknown` case carrying this value, so a caller can
  * still read it (with `Json.decode` on `value`) and still deduplicate by the id beside it.
  *
  * Note: the payload is a user's message or a chat's state, so `toString` renders only its length,
  * `TelegramRawJson(<n> characters)`. A log line or an assertion message that prints a model value never
  * prints what someone wrote. `value` is the only way to read it.
  *
  * @see
  *   [[kyo.TelegramUpdate]] where an unknown update arrives
  * @see
  *   [[kyo.TelegramMessage]] where an unknown message content arrives
  */
final class TelegramRawJson private (val value: String):
    override def equals(other: Any): Boolean =
        other match
            case that: TelegramRawJson => value == that.value
            case _                     => false
    override def hashCode: Int    = value.hashCode
    override def toString: String = s"TelegramRawJson(${value.length} characters)"
end TelegramRawJson

object TelegramRawJson:
    /** JSON text the module carries unparsed. */
    def apply(value: String): TelegramRawJson        = new TelegramRawJson(value)
    given CanEqual[TelegramRawJson, TelegramRawJson] = CanEqual.derived
end TelegramRawJson
