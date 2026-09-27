package kyo

/** A keyboard attached to a message: buttons under the message (`Inline`), buttons in place of the
  * user's keyboard (`Reply`), an instruction to remove a reply keyboard (`Remove`), or to open a reply
  * to the message (`ForceReply`).
  *
  * An inline button either opens a URL or sends callback data back to the bot as a
  * [[kyo.TelegramCallbackQuery]]. A reply button sends its text as a message, or shares the user's
  * contact or location.
  *
  * IMPORTANT: callback data is 1 to 64 bytes in UTF-8. [[kyo.TelegramKeyboard.CallbackData]] checks this
  * when it is built and panics with [[kyo.TelegramInvalidCallbackDataException]] otherwise, so a
  * keyboard Telegram would refuse is refused where it is written.
  *
  * Only an inline keyboard can be set on an edited message ([[kyo.TelegramEdit]]).
  *
  * @see
  *   [[kyo.TelegramSendOptions]] where a keyboard is attached
  * @see
  *   [[kyo.TelegramCallbackQuery]] a press of a callback button
  */
enum TelegramKeyboard derives CanEqual:
    case Inline(rows: Chunk[Chunk[TelegramKeyboard.InlineButton]])
    case Reply(
        rows: Chunk[Chunk[TelegramKeyboard.ReplyButton]],
        resize: Boolean = true,
        oneTime: Boolean = false,
        persistent: Boolean = false,
        placeholder: Maybe[String] = Absent
    )
    case Remove
    case ForceReply(placeholder: Maybe[String] = Absent)
end TelegramKeyboard

object TelegramKeyboard:

    /** An inline keyboard with these rows. */
    def inline(rows: Seq[InlineButton]*): Inline = Inline(Chunk.from(rows).map(Chunk.from(_)))

    /** A reply keyboard with these rows, resized to fit. */
    def reply(rows: Seq[ReplyButton]*): Reply = Reply(Chunk.from(rows).map(Chunk.from(_)))

    /** A button under a message. */
    enum InlineButton derives CanEqual:
        case Callback(text: String, data: CallbackData)
        case Url(text: String, url: TelegramUrl)
    end InlineButton

    object InlineButton:
        /** A button that sends `data` back as a callback query; panics when `data` is not 1 to 64 bytes. */
        def callback(text: String, data: String)(using Frame): InlineButton = Callback(text, CallbackData(data))
    end InlineButton

    /** A button in place of the user's keyboard. */
    enum ReplyButton derives CanEqual:
        case Text(text: String)
        case RequestContact(text: String)
        case RequestLocation(text: String)
    end ReplyButton

    /** Callback data, 1 to 64 bytes in UTF-8. */
    opaque type CallbackData = String

    object CallbackData:
        /** Builds callback data, panicking with [[kyo.TelegramInvalidCallbackDataException]] outside 1 to 64 bytes. */
        def apply(value: String)(using Frame): CallbackData =
            val length = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            if length < 1 || length > MaxBytes then throw TelegramInvalidCallbackDataException(length)
            value
        end apply

        extension (self: CallbackData) def value: String = self

        given CanEqual[CallbackData, CallbackData] = CanEqual.derived

        /** The most bytes Telegram accepts. */
        inline val MaxBytes = 64
    end CallbackData

end TelegramKeyboard
