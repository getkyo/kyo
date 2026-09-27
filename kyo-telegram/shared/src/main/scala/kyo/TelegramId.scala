package kyo

/** The Bot API's identifiers, each an opaque type of its own so one cannot be passed for another.
  *
  * Telegram assigns every id, so none is validated. Their underlying types follow the Bot API's
  * documentation rather than a single choice:
  *
  *   - `ChatId` and `UserId` wrap `Long`: Telegram notes they "may have more than 32 significant
  *     bits" and have "at most 52". A chat id is negative for groups, supergroups and channels.
  *   - `MessageId` and `MessageThreadId` wrap `Int`: the documentation types them Integer with no
  *     such note, and states that 32-bit signed integers are safe for every Integer not noted.
  *   - `UpdateId` wraps `Long`: it is an Integer, but the offset that confirms an update is
  *     `update_id + 1`, which a `Long` holds for every `Int`.
  *   - `CallbackQueryId`, `FileId` and `FileUniqueId` wrap `String`. A `FileId` resends or downloads
  *     a file and differs per bot; a `FileUniqueId` is stable across bots and time and can do
  *     neither.
  *
  * Each has `apply`, a `value` extension, a `Schema` that reads and writes the bare number or
  * string, and a `CanEqual`.
  */
object TelegramId:

    /** A chat: private, group, supergroup or channel. */
    opaque type ChatId = Long
    object ChatId:
        def apply(value: Long): ChatId           = value
        extension (self: ChatId) def value: Long = self
        given Schema[ChatId]                     = Schema.longSchema.transform[ChatId](apply)(_.value)
        given CanEqual[ChatId, ChatId]           = CanEqual.derived
    end ChatId

    /** A user or a bot. */
    opaque type UserId = Long
    object UserId:
        def apply(value: Long): UserId           = value
        extension (self: UserId) def value: Long = self
        given Schema[UserId]                     = Schema.longSchema.transform[UserId](apply)(_.value)
        given CanEqual[UserId, UserId]           = CanEqual.derived
    end UserId

    /** A message, unique inside its chat. */
    opaque type MessageId = Int
    object MessageId:
        def apply(value: Int): MessageId           = value
        extension (self: MessageId) def value: Int = self
        given Schema[MessageId]                    = Schema.intSchema.transform[MessageId](apply)(_.value)
        given CanEqual[MessageId, MessageId]       = CanEqual.derived
    end MessageId

    /** A message thread or forum topic of a supergroup or a private chat. */
    opaque type MessageThreadId = Int
    object MessageThreadId:
        def apply(value: Int): MessageThreadId           = value
        extension (self: MessageThreadId) def value: Int = self
        given Schema[MessageThreadId]                    = Schema.intSchema.transform[MessageThreadId](apply)(_.value)
        given CanEqual[MessageThreadId, MessageThreadId] = CanEqual.derived
    end MessageThreadId

    /** An update, increasing across the updates of one bot. */
    opaque type UpdateId = Long
    object UpdateId:
        def apply(value: Long): UpdateId           = value
        extension (self: UpdateId) def value: Long = self
        given Schema[UpdateId]                     = Schema.longSchema.transform[UpdateId](apply)(_.value)
        given CanEqual[UpdateId, UpdateId]         = CanEqual.derived
    end UpdateId

    /** A callback query from an inline keyboard button, answered with `answerCallbackQuery`. */
    opaque type CallbackQueryId = String
    object CallbackQueryId:
        def apply(value: String): CallbackQueryId           = value
        extension (self: CallbackQueryId) def value: String = self
        given Schema[CallbackQueryId]                       = Schema.stringSchema.transform[CallbackQueryId](apply)(_.value)
        given CanEqual[CallbackQueryId, CallbackQueryId]    = CanEqual.derived
    end CallbackQueryId

    /** A file as this bot can resend or download it. */
    opaque type FileId = String
    object FileId:
        def apply(value: String): FileId           = value
        extension (self: FileId) def value: String = self
        given Schema[FileId]                       = Schema.stringSchema.transform[FileId](apply)(_.value)
        given CanEqual[FileId, FileId]             = CanEqual.derived
    end FileId

    /** A file, the same across bots and over time. */
    opaque type FileUniqueId = String
    object FileUniqueId:
        def apply(value: String): FileUniqueId           = value
        extension (self: FileUniqueId) def value: String = self
        given Schema[FileUniqueId]                       = Schema.stringSchema.transform[FileUniqueId](apply)(_.value)
        given CanEqual[FileUniqueId, FileUniqueId]       = CanEqual.derived
    end FileUniqueId

end TelegramId
