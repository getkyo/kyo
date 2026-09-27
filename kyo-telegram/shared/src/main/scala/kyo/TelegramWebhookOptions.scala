package kyo

/** How `Telegram.setWebhook` registers the webhook, beside its URL and secret.
  *
  * `allowedUpdates` chooses the update kinds delivered; `Absent` keeps the previous choice, and an
  * empty list asks for every kind except `chat_member`, `message_reaction` and
  * `message_reaction_count`. `maxConnections` bounds the concurrent deliveries, 1 to 100 (Telegram's
  * default is 40). `dropPendingUpdates` discards the updates waiting for delivery. `ipAddress` makes
  * Telegram connect to that address instead of the one DNS gives.
  *
  * IMPORTANT: a `maxConnections` outside 1 to 100 is refused at construction with a
  * [[kyo.TelegramInvalidWebhookOptionsException]], as Telegram would refuse it.
  *
  * @see
  *   [[kyo.Telegram.setWebhook]] the operation
  */
final case class TelegramWebhookOptions(
    allowedUpdates: Maybe[Chunk[TelegramUpdate.Type]] = Absent,
    maxConnections: Maybe[Int] = Absent,
    dropPendingUpdates: Boolean = false,
    ipAddress: Maybe[String] = Absent
)(using Frame) derives CanEqual:
    maxConnections.filter(n => n < 1 || n > 100).foreach(n =>
        throw TelegramInvalidWebhookOptionsException(TelegramInvalidWebhookOptionsException.Problem.MaxConnections(n))
    )
end TelegramWebhookOptions

object TelegramWebhookOptions:
    /** Telegram's defaults, with the previous allowed updates kept. */
    // A constant of the kyo package has no caller Frame, and the defaults pass every check.
    val default: TelegramWebhookOptions = TelegramWebhookOptions()(using Frame.internal)
end TelegramWebhookOptions
