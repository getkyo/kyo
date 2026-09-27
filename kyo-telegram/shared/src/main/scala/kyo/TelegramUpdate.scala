package kyo

/** One event Telegram delivers to a bot, by long polling (`Telegram.run`) or by webhook
  * (`TelegramWebhook.handler`); both deliver this same type.
  *
  * `id` is Telegram's update id, on every case. Telegram redelivers an update it does not see confirmed
  * (long polling) or answered with a 2xx (webhook), so a handler that must not act twice deduplicates by it.
  *
  * An update of a kind the module does not model (inline queries, payments, polls, business messages,
  * and every kind Telegram adds later), or of a known kind that lacks a field that kind requires, is
  * `Unknown` with its type name and the update as Telegram sent it, so it is never silently lost.
  *
  * IMPORTANT: which kinds arrive is chosen by the allowed updates (`TelegramConfig.allowedUpdates`, or
  * the `setWebhook` option). By Telegram's default a bot receives every kind except `chat_member`,
  * `message_reaction` and `message_reaction_count`, which must be asked for; in groups, privacy mode
  * further limits a bot to commands, replies to it and mentions of it.
  *
  * @see
  *   [[kyo.Telegram.run]] long polling
  * @see
  *   [[kyo.TelegramWebhook]] webhook delivery
  */
sealed trait TelegramUpdate derives CanEqual:
    def id: TelegramId.UpdateId

object TelegramUpdate:

    final case class Message(id: TelegramId.UpdateId, message: TelegramMessage)           extends TelegramUpdate
    final case class EditedMessage(id: TelegramId.UpdateId, message: TelegramMessage)     extends TelegramUpdate
    final case class ChannelPost(id: TelegramId.UpdateId, message: TelegramMessage)       extends TelegramUpdate
    final case class EditedChannelPost(id: TelegramId.UpdateId, message: TelegramMessage) extends TelegramUpdate
    final case class CallbackQuery(id: TelegramId.UpdateId, query: TelegramCallbackQuery) extends TelegramUpdate

    /** The bot's own membership changed in a chat. */
    final case class MyChatMember(id: TelegramId.UpdateId, update: TelegramChatMemberUpdate) extends TelegramUpdate

    /** Another member's status changed, for an administrator bot that asked for it. */
    final case class ChatMember(id: TelegramId.UpdateId, update: TelegramChatMemberUpdate) extends TelegramUpdate

    final case class MessageReaction(id: TelegramId.UpdateId, update: TelegramReactionUpdate) extends TelegramUpdate

    /** An update the module does not model: `type` is the field naming its kind, and `payload` the update as Telegram sent it. */
    final case class Unknown(id: TelegramId.UpdateId, `type`: String, payload: TelegramRawJson) extends TelegramUpdate

    // Bounded rather than on `TelegramUpdate` alone, so the refusal also answers a summon of one case, such as `Message`.
    inline given [U <: TelegramUpdate]: Schema[U] =
        compiletime.error("TelegramUpdate has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** An update type, as named in `allowed_updates`. `Other` names one the module does not model. */
    enum Type derives CanEqual:
        case Message
        case EditedMessage
        case ChannelPost
        case EditedChannelPost
        case CallbackQuery
        case MyChatMember
        case ChatMember
        case MessageReaction
        case Other(name: String)
    end Type

    object Type:
        inline given Schema[Type] =
            compiletime.error("TelegramUpdate.Type has no Schema: kyo-telegram decodes and encodes Telegram's payloads itself")

end TelegramUpdate
