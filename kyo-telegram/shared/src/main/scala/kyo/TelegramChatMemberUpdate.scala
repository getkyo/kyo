package kyo

/** A change of one member's status in a chat: the bot's own (added, removed, blocked by a user) or,
  * when the bot is an administrator that asked for them, any member's.
  *
  * A private chat reports only that the user blocked or unblocked the bot: the bot's status becomes
  * `Kicked` or `Member`. After `Kicked` in a private chat, sending there fails with
  * [[kyo.TelegramForbiddenException]].
  *
  * @see
  *   [[kyo.TelegramUpdate.MyChatMember]] the bot's own membership
  */
final case class TelegramChatMemberUpdate(
    chat: TelegramChat,
    from: TelegramUser,
    date: Instant,
    member: TelegramUser,
    oldStatus: TelegramChatMemberUpdate.Status,
    newStatus: TelegramChatMemberUpdate.Status
) derives CanEqual

object TelegramChatMemberUpdate:

    inline given Schema[TelegramChatMemberUpdate] =
        compiletime.error("TelegramChatMemberUpdate has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** A member's status. `Other` holds a status Telegram added after this module, by its wire name. */
    enum Status derives CanEqual:
        case Creator
        case Administrator
        case Member
        case Restricted
        case Left
        case Kicked
        case Other(name: String)
    end Status

    object Status:
        inline given Schema[Status] =
            compiletime.error("TelegramChatMemberUpdate.Status has no Schema: kyo-telegram decodes Telegram's payloads itself")

end TelegramChatMemberUpdate
