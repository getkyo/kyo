package kyo

/** A Telegram chat: a private chat with one user, a group, a supergroup, or a channel.
  *
  * `title` is set for groups, supergroups and channels; `firstName` and `lastName` for private chats;
  * `username` for private chats, supergroups and channels that have one. `isForum` marks a supergroup
  * with topics, whose messages carry a `TelegramId.MessageThreadId`.
  *
  * A chat to send to is a [[kyo.TelegramChat.Target]]: its id, or the `@username` of a public
  * supergroup or channel (or of a bot). Every outbound verb takes a target, so a chat read from an
  * update is passed back as `chat.target`.
  *
  * IMPORTANT: when a group becomes a supergroup it gets a new id, and calls to the old id fail with
  * [[kyo.TelegramMigratedException]], which carries the new one.
  *
  * @see
  *   [[kyo.TelegramId.ChatId]] the id
  * @see
  *   [[kyo.TelegramMessage]] where a chat appears
  */
final case class TelegramChat(
    id: TelegramId.ChatId,
    kind: TelegramChat.Type,
    title: Maybe[String] = Absent,
    username: Maybe[String] = Absent,
    firstName: Maybe[String] = Absent,
    lastName: Maybe[String] = Absent,
    isForum: Boolean = false
) derives CanEqual:
    /** This chat as a destination for the outbound verbs. */
    def target: TelegramChat.Target = TelegramChat.Target.Id(id)
end TelegramChat

object TelegramChat:

    inline given Schema[TelegramChat] = compiletime.error("TelegramChat has no Schema: kyo-telegram decodes Telegram's payloads itself")

    /** The kind of a chat. `Other` holds a kind Telegram added after this module, by its wire name. */
    enum Type derives CanEqual:
        case Private
        case Group
        case Supergroup
        case Channel
        case Other(name: String)
    end Type

    object Type:
        inline given Schema[Type] = compiletime.error("TelegramChat.Type has no Schema: kyo-telegram decodes Telegram's payloads itself")
    end Type

    /** Where an outbound verb sends: a chat by id, or a public supergroup, channel or bot by `@username`. */
    enum Target derives CanEqual:
        case Id(chat: TelegramId.ChatId)

        /** `name` is the username without the leading `@`, which the module adds. */
        case Username(name: String)
    end Target

    object Target:
        /** The chat with this id. */
        def apply(chat: TelegramId.ChatId): Target = Id(chat)
    end Target

end TelegramChat
