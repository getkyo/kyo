package kyo

/** The state of the bot's webhook, as `Telegram.getWebhookInfo` answers it.
  *
  * `url` is `Absent` when no webhook is set, which is when long polling works. `pendingUpdateCount` is
  * how many updates wait for delivery. `lastDeliveryFailureDate` and `lastDeliveryFailureMessage`
  * describe the most recent failure to deliver to the webhook, which is where a webhook that never
  * receives anything shows why.
  *
  * @see
  *   [[kyo.Telegram.setWebhook]] how a webhook is set
  */
final case class TelegramWebhookInfo(
    url: Maybe[HttpUrl],
    hasCustomCertificate: Boolean,
    pendingUpdateCount: Int,
    ipAddress: Maybe[String] = Absent,
    lastDeliveryFailureDate: Maybe[Instant] = Absent,
    lastDeliveryFailureMessage: Maybe[String] = Absent,
    maxConnections: Maybe[Int] = Absent,
    allowedUpdates: Chunk[TelegramUpdate.Type] = Chunk.empty
) derives CanEqual

object TelegramWebhookInfo:
    inline given Schema[TelegramWebhookInfo] =
        compiletime.error("TelegramWebhookInfo has no Schema: kyo-telegram decodes Telegram's payloads itself")
