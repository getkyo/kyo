package kyo

/** A command in the bot's menu, as `Telegram.setCommands` registers it: `/name` with its description.
  *
  * IMPORTANT: Telegram accepts a name of 1 to 32 characters from `a-z`, `0-9` and `_`, and a
  * description of 1 to 256 characters. Construction checks both and panics with
  * [[kyo.TelegramInvalidCommandException]] otherwise.
  *
  * A [[kyo.TelegramCommand.Scope]] chooses who sees a list: everyone, all private chats, all groups,
  * their administrators, one chat, its administrators, or one member of it. Telegram shows a user the
  * list of the narrowest scope that applies.
  *
  * @see
  *   [[kyo.Telegram.setCommands]] the operation
  */
final case class TelegramCommand(name: String, description: String)(using Frame) derives CanEqual:
    TelegramCommand.problemOf(name, description).foreach(problem => throw TelegramInvalidCommandException(problem))
end TelegramCommand

object TelegramCommand:

    /** Who sees a list of commands. */
    enum Scope derives CanEqual:
        case Default
        case AllPrivateChats
        case AllGroupChats
        case AllChatAdministrators
        case Chat(chat: TelegramChat.Target)
        case ChatAdministrators(chat: TelegramChat.Target)
        case ChatMember(chat: TelegramChat.Target, user: TelegramId.UserId)
    end Scope

    private def problemOf(name: String, description: String): Maybe[TelegramInvalidCommandException.Problem] =
        import TelegramInvalidCommandException.Problem
        if name.isEmpty || name.length > 32 then Present(Problem.NameLength(name.length))
        else
            val bad = name.indexWhere(c => !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_'))
            if bad >= 0 then Present(Problem.NameCharacter(bad))
            else if description.isEmpty || description.length > 256 then Present(Problem.DescriptionLength(description.length))
            else Absent
        end if
    end problemOf

end TelegramCommand
