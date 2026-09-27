package kyo

/** How `Telegram.send` places and presents a message, beside what it holds.
  *
  * `thread` sends into a forum topic or a reply thread. `replyTo` makes the message a reply to another
  * in the same chat. `keyboard` attaches buttons. `silent` delivers without a notification sound.
  * `protect` stops the message from being forwarded or saved.
  *
  * @see
  *   [[kyo.Telegram.send]] the operation
  * @see
  *   [[kyo.TelegramKeyboard]] the buttons
  */
final case class TelegramSendOptions(
    thread: Maybe[TelegramId.MessageThreadId] = Absent,
    replyTo: Maybe[TelegramId.MessageId] = Absent,
    keyboard: Maybe[TelegramKeyboard] = Absent,
    silent: Boolean = false,
    protect: Boolean = false
) derives CanEqual

object TelegramSendOptions:
    /** No thread, no reply, no keyboard, with a notification, forwardable. */
    val default: TelegramSendOptions = TelegramSendOptions()
end TelegramSendOptions
