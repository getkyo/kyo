package kyo

import kyo.crypto.ConstantTime
import kyo.internal.charset.Utf8
import kyo.internal.telegram.BotApi
import kyo.internal.telegram.BotApi.Payload
import kyo.internal.telegram.Poller
import kyo.internal.telegram.Request
import kyo.internal.telegram.WireField
import kyo.schema.catchAll
import kyo.schema.discriminator
import kyo.schema.omit
import kyo.schema.rename
import kyo.schema.tagOnly
import kyo.schema.transform
import kyo.schema.untagged

/** The client of the Telegram Bot API that the module builds from a [[kyo.TelegramConfig]]: the config
  * and the module's own HTTP client. A caller never builds one or calls a method on one; the verbs on
  * the companion require it as `Env[Telegram]`, and `Telegram.run` or `Telegram.let` provide it for a
  * region, closing its HTTP client when the region ends.
  *
  * A verb's row names the client, so a program that calls `Telegram.send` outside `run` or `let` does
  * not compile where it is run. Each verb fails with its own sealed trait (such as
  * [[kyo.TelegramSendFailure]]), whose leaves are exactly what that verb can meet. `custom` calls any
  * method the module does not model.
  *
  * There are two ways to receive updates, and a bot uses one at a time. `run` polls with `getUpdates`,
  * which needs no public endpoint; [[kyo.Telegram.Webhook]] serves Telegram's POSTs on the bot's own
  * `HttpServer`. `setWebhook`, `deleteWebhook` and `getWebhookInfo` switch between them: polling fails
  * with [[kyo.TelegramConflictException]] while a webhook is set.
  *
  * The companion also holds the module's types: the ids (`Telegram.ChatId`, ...), what arrives
  * (`Telegram.Update`, `Telegram.Message`, ...) and what is sent (`Telegram.Content`, `Telegram.Keyboard`, ...).
  *
  * IMPORTANT: Telegram puts the bot token in every request path. The client's HTTP client is its own,
  * with a complete configuration: no redirect is followed, no filter runs, TLS is verified, and nothing
  * of the caller's kyo-http configuration applies. No failure of this module holds the token.
  *
  * @see
  *   [[kyo.TelegramConfig]] the config
  * @see
  *   [[kyo.Telegram.Update]] what arrives
  * @see
  *   [[kyo.Telegram.Webhook]] receiving by webhook
  * @see
  *   [[kyo.TelegramException]] the failures
  */
final class Telegram private[kyo] (private[kyo] val config: TelegramConfig, private[kyo] val http: HttpClient)

object Telegram:

    // --- Regions ---

    /** Polls for updates and runs `handler` on each, in order, until a failure or interruption ends it.
      *
      * The handler runs with the client provided, so it calls the verbs directly, and may carry effects
      * `S` of its own.
      *
      * An update is confirmed to Telegram only after `handler` returns for it. A typed failure of the
      * handler ends `run` with that failure, and a panic of the handler ends it with that panic; either
      * way the update stays unconfirmed, and Telegram delivers it again on the next `run`, together with
      * the updates of its batch that were handled before it. A handler that must not act twice
      * deduplicates by `update.id`.
      *
      * Transport failures, server errors and rate limits are retried under `config.retrySchedule`,
      * waiting the delay Telegram sent when it sent one; other failures end `run`. The client and the
      * poll's own connection are closed when `run` ends, so interrupting `run` ends the long poll.
      *
      * `run` ends only by interruption or by a failure on its row. To poll in the background, fork it
      * with `Fiber.init`: the fiber ends with the loop's failure, and interrupting it stops polling.
      */
    def run[E, S](config: TelegramConfig)(
        handler: Update => Unit < (Async & Abort[E] & Env[Telegram] & S)
    )(using Frame): Unit < (Async & Abort[TelegramRunFailure | E] & S) =
        Scope.run {
            client(config).map { telegram =>
                // One poll is in flight at a time, but kyo-net's connection pool refuses fewer than 2 connections per host.
                HttpClient.init(maxConnectionsPerHost = 2, defaultTlsConfig = config.tls, transportConfig = config.transport).map { poll =>
                    Env.run(telegram)(Poller.run(telegram, poll)(handler))
                }
            }
        }

    /** Builds a client from `config` for the duration of `v`, closing its HTTP client afterwards. */
    def let[A, S](config: TelegramConfig)(v: A < (S & Env[Telegram]))(using Frame): A < (S & Async) =
        Scope.run(client(config).map(telegram => Env.run(telegram)(v)))

    private[kyo] def client(config: TelegramConfig)(using Frame): Telegram < (Async & Scope) =
        // kyo-http's pool defaults stay: 100 connections to one host is more concurrency than a bot's calls reach unless it
        // forks that many at once, and the 60-second idle close matches the idle timeout proxies and load balancers default to.
        HttpClient.init(defaultTlsConfig = config.tls, transportConfig = config.transport).map(new Telegram(config, _))

    // --- Messages ---

    /** Sends `content` to `chat` and answers the message Telegram created. */
    def send(chat: Chat.Target, content: Content, options: SendOptions = SendOptions.default)(using
        Frame
    ): Message < (Async & Abort[TelegramSendFailure] & Env[Telegram]) =
        import Content.*
        def media[B: Schema](field: String, file: InputFile, request: Maybe[String] => B): Payload =
            Payload.withUploads(request(fileParam(file)), fileUpload(field, file))
        def caption(text: Maybe[Telegram.Text]): Maybe[Request.Caption] = text.map(Request.Caption.of)
        val (method, payload)                                           = content match
            case Content.Text(text, linkPreview) =>
                (
                    "sendMessage",
                    Payload.of(Request.SendMessage(chat, Request.Rendered.of(text), Request.LinkPreviewOptions.of(linkPreview), options))
                )
            case Photo(file, text)    => ("sendPhoto", media("photo", file, Request.SendPhoto(chat, _, caption(text), options)))
            case Document(file, text) => ("sendDocument", media("document", file, Request.SendDocument(chat, _, caption(text), options)))
            case Audio(file, text)    => ("sendAudio", media("audio", file, Request.SendAudio(chat, _, caption(text), options)))
            case Video(file, text)    => ("sendVideo", media("video", file, Request.SendVideo(chat, _, caption(text), options)))
            case Voice(file, text)    => ("sendVoice", media("voice", file, Request.SendVoice(chat, _, caption(text), options)))
            case Location(latitude, longitude) =>
                ("sendLocation", Payload.of(Request.SendLocation(chat, latitude, longitude, options)))
        BotApi.call[Message, Message, TelegramSendFailure](method, payload)(Result.succeed(_))
    end send

    /** Changes the text, the caption or the inline keyboard of a message the bot sent, and answers the edited message. */
    def edit(chat: Chat.Target, message: MessageId, change: Edit)(using
        Frame
    ): Message < (Async & Abort[TelegramEditFailure] & Env[Telegram]) =
        val (method, payload) = change match
            case Edit.Text(text, kb, linkPreview) =>
                (
                    "editMessageText",
                    Payload.of(
                        Request.EditMessageText(chat, message, Request.Rendered.of(text), Request.LinkPreviewOptions.of(linkPreview), kb)
                    )
                )
            case Edit.Caption(caption, kb) =>
                ("editMessageCaption", Payload.of(Request.EditMessageCaption(chat, message, caption.map(Request.Caption.of), kb)))
            case Edit.Keyboard(kb) => ("editMessageReplyMarkup", Payload.of(Request.EditMessageReplyMarkup(chat, message, kb)))
        BotApi.call[Message, Message, TelegramEditFailure](method, payload)(Result.succeed(_))
    end edit

    /** Deletes a message. Telegram allows it within 48 hours of sending, with the rights its documentation lists. */
    def delete(chat: Chat.Target, message: MessageId)(using
        Frame
    ): Unit < (Async & Abort[TelegramDeleteFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramDeleteFailure]("deleteMessage", Payload.of(Request.DeleteMessage(chat, message)))

    /** Answers a callback query, which stops the progress indicator on the user's button. */
    def answerCallback(query: CallbackQueryId, answer: CallbackAnswer = CallbackAnswer.empty)(using
        Frame
    ): Unit < (Async & Abort[TelegramAnswerCallbackFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramAnswerCallbackFailure]("answerCallbackQuery", Payload.of(Request.AnswerCallbackQuery(query, answer)))

    /** Shows `action` (such as typing) in `chat` for up to 5 seconds or until the bot's next message. */
    def sendChatAction(chat: Chat.Target, action: ChatAction, thread: Maybe[MessageThreadId] = Absent)(using
        Frame
    ): Unit < (Async & Abort[TelegramSendChatActionFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramSendChatActionFailure]("sendChatAction", Payload.of(Request.SendChatAction(chat, action, thread)))

    /** Sets the bot's reactions on a message; an empty list removes them. `big` plays the reaction's animation large. */
    def setReaction(
        chat: Chat.Target,
        message: MessageId,
        reactions: Seq[Reaction.Sendable],
        big: Boolean = false
    )(using Frame): Unit < (Async & Abort[TelegramSetReactionFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramSetReactionFailure](
            "setMessageReaction",
            Payload.of(Request.SetMessageReaction(chat, message, Chunk.from(reactions), big))
        )

    // --- Bot ---

    /** The bot itself, which also checks that the token works. */
    def getMe(using Frame): User < (Async & Abort[TelegramGetMeFailure] & Env[Telegram]) =
        BotApi.call[User, User, TelegramGetMeFailure]("getMe", Payload.of(Request.NoParameters()))(Result.succeed(_))

    /** Sets the bot's command menu for `scope`, or for Telegram's default scope when it is absent, in `language` or for every
      * language without a menu of its own.
      */
    def setCommands(
        menu: Command.Menu,
        scope: Maybe[Command.Scope] = Absent,
        language: Maybe[String] = Absent
    )(using Frame): Unit < (Async & Abort[TelegramSetCommandsFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramSetCommandsFailure](
            "setMyCommands",
            Payload.of(Request.SetMyCommands(menu.commands, scope, language))
        )

    // --- Files ---

    /** Looks up a file's download path. */
    def getFile(file: FileId)(using Frame): File < (Async & Abort[TelegramGetFileFailure] & Env[Telegram]) =
        BotApi.call[File, File, TelegramGetFileFailure]("getFile", Payload.of(Request.GetFile(file)))(Result.succeed(_))

    /** Downloads a file whose path `getFile` answered, from `/file/bot<token>/<path>` under `baseUrl`.
      *
      * The Bot API serves files of at most 20 MB this way, and a path stays valid for at least an hour, so
      * call `getFile` again for an older one. A file without a path fails with `TelegramNoFilePathException`,
      * and a path that is not relative segments of letters, digits, `.`, `_` and `-` fails with
      * `TelegramRefusedUrlException`, since it is appended after the token.
      *
      * A local Bot API server answers `getFile` with an absolute path on its own disk, which this method
      * cannot fetch over HTTP; read that path from the server's file system instead.
      */
    def download(file: File)(using Frame): Span[Byte] < (Async & Abort[TelegramDownloadFailure] & Env[Telegram]) =
        BotApi.download(file)

    // --- Webhook ---

    /** Registers `url` to receive updates by POST, with Telegram sending `webhook.secret` in every request's
      * `X-Telegram-Bot-Api-Secret-Token` header. Long polling fails while a webhook is set. A `url` that is
      * not an absolute http or https URL on a host fails with `TelegramRefusedUrlException`, and nothing is sent.
      */
    def setWebhook(url: HttpUrl, webhook: TelegramWebhookConfig, options: WebhookOptions = WebhookOptions.default)(using
        Frame
    ): Unit < (Async & Abort[TelegramSetWebhookFailure] & Env[Telegram]) =
        if TelegramConfig.absoluteProblemOf(url).nonEmpty then Abort.fail(TelegramRefusedUrlException("setWebhook"))
        else
            BotApi.acknowledged[TelegramSetWebhookFailure](
                "setWebhook",
                Payload.of(Request.SetWebhook(url, webhook.secret, options), secrets = Chunk(webhook.secret.value))
            )

    /** Removes the webhook, so long polling works again; `dropPendingUpdates` discards the waiting updates. */
    def deleteWebhook(dropPendingUpdates: Boolean = false)(using
        Frame
    ): Unit < (Async & Abort[TelegramDeleteWebhookFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramDeleteWebhookFailure]("deleteWebhook", Payload.of(Request.DeleteWebhook(dropPendingUpdates)))

    /** The webhook's state, `url` absent when none is set. */
    def getWebhookInfo(using Frame): WebhookInfo < (Async & Abort[TelegramGetWebhookInfoFailure] & Env[Telegram]) =
        BotApi.call[WebhookInfo, WebhookInfo, TelegramGetWebhookInfoFailure]("getWebhookInfo", Payload.of(Request.NoParameters()))(
            Result.succeed(_)
        )

    // --- Escape hatch ---

    /** Calls any Bot API method: `payload` is encoded as the JSON body, and the answer's `result` decoded as `Out`. */
    def custom[In: Schema, Out: Schema](method: Method, payload: In)(using
        Frame
    ): Out < (Async & Abort[TelegramCustomFailure] & Env[Telegram]) =
        BotApi.call[Out, Out, TelegramCustomFailure](method.value, Payload.Json(Json.encode(payload)))(Result.succeed(_))

    private def checked[P, A](problem: Maybe[P], value: => A): Result[P, A] =
        problem match
            case Present(p) => Result.fail(p)
            case Absent     => Result.succeed(value)

    /** The file's field value: its id or its URL, or absent for an upload, which is sent as a part instead. */
    private def fileParam(file: InputFile): Maybe[String] =
        file match
            case InputFile.Id(id)    => Present(id.value)
            case InputFile.Url(url)  => Present(url.full)
            case _: InputFile.Upload => Absent

    private def fileUpload(field: String, file: InputFile): Chunk[(String, InputFile.Upload)] =
        file match
            case upload: InputFile.Upload           => Chunk(field -> upload)
            case _: InputFile.Id | _: InputFile.Url => Chunk.empty

    // --- Ids ---
    //
    // Telegram assigns every id, so none is validated. Each is an opaque type so one cannot be passed for another, with
    // `apply`, a `value` extension, a `Schema` of the bare number or string, and a `CanEqual`.
    //
    // Each opaque type is declared in an object of its own, the one scope where it is transparent. Declared in this object,
    // it would make `Long`, `Int` and `String` refuse every `Tag` derived here ([Tag.opaque.collapsed]), and
    // `Schema.derived` of a record in this object would then lose its field list and decode none of its renames.

    /** A chat: private, group, supergroup or channel. Wraps `Long`: Telegram notes chat ids "may have more than 32 significant
      * bits" and have "at most 52". A chat id is negative for groups, supergroups and channels.
      */
    type ChatId = ChatId.Value
    object ChatId:
        opaque type Value = Long
        def apply(value: Long): ChatId           = value
        extension (self: ChatId) def value: Long = self
        given Schema[ChatId]                     = Schema.longSchema.transform[ChatId](apply)(_.value)
        given CanEqual[ChatId, ChatId]           = CanEqual.derived
    end ChatId

    /** A user or a bot. Wraps `Long`, for the same reason as [[kyo.Telegram.ChatId]]. */
    type UserId = UserId.Value
    object UserId:
        opaque type Value = Long
        def apply(value: Long): UserId           = value
        extension (self: UserId) def value: Long = self
        given Schema[UserId]                     = Schema.longSchema.transform[UserId](apply)(_.value)
        given CanEqual[UserId, UserId]           = CanEqual.derived
    end UserId

    /** A message, unique inside its chat. Wraps `Int`: the documentation types it Integer with no note, and states that 32-bit
      * signed integers are safe for every Integer not noted.
      */
    type MessageId = MessageId.Value
    object MessageId:
        opaque type Value = Int
        def apply(value: Int): MessageId           = value
        extension (self: MessageId) def value: Int = self
        given Schema[MessageId]                    = Schema.intSchema.transform[MessageId](apply)(_.value)
        given CanEqual[MessageId, MessageId]       = CanEqual.derived
    end MessageId

    /** A message thread or forum topic of a supergroup or a private chat. Wraps `Int`, as [[kyo.Telegram.MessageId]] does. */
    type MessageThreadId = MessageThreadId.Value
    object MessageThreadId:
        opaque type Value = Int
        def apply(value: Int): MessageThreadId           = value
        extension (self: MessageThreadId) def value: Int = self
        given Schema[MessageThreadId]                    = Schema.intSchema.transform[MessageThreadId](apply)(_.value)
        given CanEqual[MessageThreadId, MessageThreadId] = CanEqual.derived
    end MessageThreadId

    /** An update, increasing across the updates of one bot. Wraps `Long`: it is an Integer, but the offset that confirms an
      * update is `update_id + 1`, which a `Long` holds for every `Int`.
      */
    type UpdateId = UpdateId.Value
    object UpdateId:
        opaque type Value = Long
        def apply(value: Long): UpdateId           = value
        extension (self: UpdateId) def value: Long = self
        given Schema[UpdateId]                     = Schema.longSchema.transform[UpdateId](apply)(_.value)
        given CanEqual[UpdateId, UpdateId]         = CanEqual.derived
    end UpdateId

    /** A callback query from an inline keyboard button, answered with `answerCallbackQuery`. */
    type CallbackQueryId = CallbackQueryId.Value
    object CallbackQueryId:
        opaque type Value = String
        def apply(value: String): CallbackQueryId           = value
        extension (self: CallbackQueryId) def value: String = self
        given Schema[CallbackQueryId]                       = Schema.stringSchema.transform[CallbackQueryId](apply)(identity)
        given CanEqual[CallbackQueryId, CallbackQueryId]    = CanEqual.derived
    end CallbackQueryId

    /** A file as this bot can resend or download it. It differs per bot, where a [[kyo.Telegram.FileUniqueId]] does not. */
    type FileId = FileId.Value
    object FileId:
        opaque type Value = String
        def apply(value: String): FileId           = value
        extension (self: FileId) def value: String = self
        given Schema[FileId]                       = Schema.stringSchema.transform[FileId](apply)(identity)
        given CanEqual[FileId, FileId]             = CanEqual.derived
    end FileId

    /** A file, the same across bots and over time. It can neither resend nor download the file. */
    type FileUniqueId = FileUniqueId.Value
    object FileUniqueId:
        opaque type Value = String
        def apply(value: String): FileUniqueId           = value
        extension (self: FileUniqueId) def value: String = self
        given Schema[FileUniqueId]                       = Schema.stringSchema.transform[FileUniqueId](apply)(identity)
        given CanEqual[FileUniqueId, FileUniqueId]       = CanEqual.derived
    end FileUniqueId

    // --- Secrets ---

    /** The bot token that authenticates every Bot API call, as issued by BotFather.
      *
      * Telegram puts the token in the URL path of every call (`/bot<token>/<method>`) and of every file
      * download (`/file/bot<token>/<file_path>`), so it is a secret that travels in a path. The class keeps
      * it from printing: `toString` renders `Telegram.Token(<redacted>)`, so a `TelegramConfig`, a log line
      * or an assertion message that renders one never shows it. `value` is the only way to read it.
      * Tokens compare by value. The `Schema` reads and writes the token's text, so encoding one is an explicit act
      * that writes the secret; reading text that cannot be a token fails the decode.
      *
      * IMPORTANT: `init` checks the token's shape and fails with a
      * [[kyo.TelegramInvalidTokenException]] when it cannot be one: empty, longer than 80 characters (the
      * Bot API server's own bound), without the `:` between the bot id and the secret part, or holding a
      * character outside `A-Z a-z 0-9 _ - :`. Any other character would change the URL the token is put
      * in (`/` adds a segment, `?` starts a query, `#` a fragment, `%` an escape), so a malformed token is
      * refused before it can address another path. A token of the right shape that Telegram does not
      * know is that call's [[kyo.TelegramUnauthorizedException]].
      *
      * @see
      *   [[kyo.TelegramConfig]] where the token is held
      * @see
      *   [[kyo.Telegram.SecretToken]] the webhook's secret, a different kind
      */
    final class Token private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: Token => value == that.value
                case _           => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "Telegram.Token(<redacted>)"
    end Token

    object Token:

        /** The token `value`, or a [[kyo.TelegramInvalidTokenException]] when the text cannot be one. */
        def init(value: String)(using Frame): Result[TelegramInvalidTokenException, Token] =
            problemOf(value) match
                case Present(problem) => Result.fail(TelegramInvalidTokenException(TelegramInvalidTokenException.Token.Bot, problem))
                case Absent           => Result.succeed(new Token(value))

        given CanEqual[Token, Token] = CanEqual.derived

        given Schema[Token] =
            Schema.stringSchema.transformVia(text => checked(problemOf(text), new Token(text)))(_.value)

        /** The Bot API server refuses a token longer than this (`ClientManager.cpp`, `send`). */
        inline val MaxLength = 80

        private def problemOf(value: String): Maybe[TelegramInvalidTokenException.Problem] =
            if value.isEmpty then Present(TelegramInvalidTokenException.Problem.Empty)
            else if value.length > MaxLength then Present(TelegramInvalidTokenException.Problem.TooLong(value.length, MaxLength))
            else
                val bad = value.indexWhere(c => !isTokenChar(c))
                if bad >= 0 then Present(TelegramInvalidTokenException.Problem.InvalidCharacter(bad))
                else if value.indexOf(':') < 0 then Present(TelegramInvalidTokenException.Problem.NoColon)
                else Absent
        end problemOf

        private def isTokenChar(c: Char): Boolean =
            (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '_' || c == '-' || c == ':'

    end Token

    /** The secret a bot chooses when it registers a webhook, which Telegram then sends in the
      * `X-Telegram-Bot-Api-Secret-Token` header of every webhook request.
      *
      * It is how a webhook tells Telegram's requests from anyone else's: the bot passes it to
      * `setWebhook`, and the webhook handler compares the header with it in constant time. It is a
      * different kind of secret from the [[kyo.Telegram.Token]], and a type of its own, so one cannot be
      * passed where the other belongs. `toString` renders `Telegram.SecretToken(<redacted>)`, `value` is
      * the only way to read it, and it compares by value. The `Schema` reads and writes the secret's text, as
      * `setWebhook` sends it; reading text Telegram would refuse fails the decode.
      *
      * IMPORTANT: Telegram accepts 1 to 256 characters from `A-Z a-z 0-9 _ -` (the `secret_token`
      * parameter of `setWebhook`). `init` checks this and fails with a
      * [[kyo.TelegramInvalidTokenException]] otherwise, so a secret Telegram would refuse at
      * registration is refused where it is written.
      *
      * @see
      *   [[kyo.Telegram.Token]] the bot token, a different kind
      */
    final class SecretToken private (val value: String):
        override def equals(other: Any): Boolean =
            other match
                case that: SecretToken => value == that.value
                case _                 => false
        override def hashCode: Int    = value.hashCode
        override def toString: String = "Telegram.SecretToken(<redacted>)"
    end SecretToken

    object SecretToken:

        /** The secret `value`, or a [[kyo.TelegramInvalidTokenException]] when Telegram would refuse it. */
        def init(value: String)(using Frame): Result[TelegramInvalidTokenException, SecretToken] =
            problemOf(value) match
                case Present(problem) => Result.fail(TelegramInvalidTokenException(TelegramInvalidTokenException.Token.Secret, problem))
                case Absent           => Result.succeed(new SecretToken(value))

        given CanEqual[SecretToken, SecretToken] = CanEqual.derived

        given Schema[SecretToken] =
            Schema.stringSchema.transformVia(text => checked(problemOf(text), new SecretToken(text)))(_.value)

        /** The longest secret `setWebhook` accepts. */
        inline val MaxLength = 256

        private def problemOf(value: String): Maybe[TelegramInvalidTokenException.Problem] =
            if value.isEmpty then Present(TelegramInvalidTokenException.Problem.Empty)
            else if value.length > MaxLength then Present(TelegramInvalidTokenException.Problem.TooLong(value.length, MaxLength))
            else
                val bad = value.indexWhere(c => !isSecretChar(c))
                if bad >= 0 then Present(TelegramInvalidTokenException.Problem.InvalidCharacter(bad))
                else Absent
        end problemOf

        private def isSecretChar(c: Char): Boolean =
            (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '_' || c == '-'

    end SecretToken

    // --- What arrives ---

    /** One event Telegram delivers to a bot, by long polling (`Telegram.run`) or by webhook
      * (`Telegram.Webhook.handler`); both deliver this same type.
      *
      * `id` is Telegram's update id, on every case. Telegram redelivers an update it does not see confirmed
      * (long polling) or answered with a 2xx (webhook), so a handler that must not act twice deduplicates by it.
      *
      * An update of a kind the module does not model (inline queries, payments, polls, business messages,
      * and every kind Telegram adds later), or of a known kind whose content does not decode, is `Unknown`
      * with its type name and the update as Telegram sent it, so it is never silently lost. An update that
      * carries nothing but its id is `Unknown` with no type name.
      *
      * IMPORTANT: which kinds arrive is chosen by the allowed updates (`TelegramConfig.allowedUpdates`, or
      * the `setWebhook` option). By Telegram's default a bot receives every kind except `chat_member`,
      * `message_reaction` and `message_reaction_count`, which must be asked for; in groups, privacy mode
      * further limits a bot to commands, replies to it and mentions of it.
      *
      * @see
      *   [[kyo.Telegram.run]] long polling
      * @see
      *   [[kyo.Telegram.Webhook]] webhook delivery
      */
    @untagged() sealed trait Update derives CanEqual:
        def id: UpdateId

    object Update:

        final case class Message(@rename("update_id") id: UpdateId, message: Telegram.Message) extends Update
        final case class EditedMessage(@rename("update_id") id: UpdateId, @rename("edited_message") message: Telegram.Message)
            extends Update
        final case class ChannelPost(@rename("update_id") id: UpdateId, @rename("channel_post") message: Telegram.Message)
            extends Update
        final case class EditedChannelPost(
            @rename("update_id") id: UpdateId,
            @rename("edited_channel_post") message: Telegram.Message
        ) extends Update
        final case class CallbackQuery(@rename("update_id") id: UpdateId, @rename("callback_query") query: Telegram.CallbackQuery)
            extends Update

        /** The bot's own membership changed in a chat. */
        final case class MyChatMember(@rename("update_id") id: UpdateId, @rename("my_chat_member") update: ChatMemberUpdate)
            extends Update

        /** Another member's status changed, for an administrator bot that asked for it. */
        final case class ChatMember(@rename("update_id") id: UpdateId, @rename("chat_member") update: ChatMemberUpdate) extends Update

        final case class MessageReaction(@rename("update_id") id: UpdateId, @rename("message_reaction") update: ReactionUpdate)
            extends Update

        /** An update the module does not model: `type` is the field naming its kind, absent when the update carries nothing but
          * its id, and `payload` the update as Telegram sent it.
          */
        final case class Unknown(id: UpdateId, `type`: Maybe[String], payload: RawJson) extends Update

        object Unknown:
            // The last variant tried: any object with an `update_id` reads as one, so an update whose kind the module does not
            // model, or whose content does not decode, still carries the id the poller confirms it by.
            given Schema[Unknown] = Structure.Value.valueSchema.transformVia((value: Structure.Value) =>
                value match
                    case Structure.Value.Record(fields) =>
                        Maybe.fromOption(fields.collectFirst { case ("update_id", Structure.Value.Integer(id)) => id }) match
                            case Present(id) =>
                                val kind = Maybe.fromOption(fields.map(_._1).find(_ != "update_id"))
                                Result.succeed(Unknown(UpdateId(id), kind, RawJson(value)))
                            case Absent => Result.fail("an update without an integer update_id")
                    case _ => Result.fail("an update that is not an object")
            )((unknown: Unknown) => unknown.payload.json)
        end Unknown

        given Schema[Update] = Schema.derived[Update]

        /** An update type, as named in `allowed_updates`. `Other` names one the module does not model. */
        @tagOnly() enum Type derives CanEqual:
            case Message
            case EditedMessage
            case ChannelPost
            case EditedChannelPost
            case CallbackQuery
            case MyChatMember
            case ChatMember
            case MessageReaction
            @catchAll() case Other(name: String)
        end Type

        object Type:
            given Schema[Type] = Schema.derived[Type].renameAllVariants(Schema.NameCase.SnakeCase)

    end Update

    /** A message in a chat, as it arrives in an update or as Telegram answers a send or an edit.
      *
      * `content` is what the message holds: text, one kind of media with its caption, a location, or
      * `Unknown` for anything the module does not model (a sticker, a poll, a service message such as a
      * member joining). An `Unknown` keeps the whole message as Telegram sent it.
      *
      * `id` is the message's id within its chat. `from` is absent in channels, where `senderChat` says
      * who posted. `thread` is the forum topic or reply thread. `replyTo` is the message this one
      * replies to, as Telegram sent it, without its own `replyTo`.
      *
      * @see
      *   [[kyo.Telegram.Update]] where messages arrive
      * @see
      *   [[kyo.Telegram.send]] which answers the message it sent
      */
    final case class Message(
        @rename("message_id") id: MessageId,
        chat: Chat,
        @transform(WireField.Date) date: Instant,
        content: Message.Content,
        @omit from: Maybe[User] = Absent,
        @omit senderChat: Maybe[Chat] = Absent,
        @rename("message_thread_id") @omit thread: Maybe[MessageThreadId] = Absent,
        @transform(WireField.MaybeDate) @omit editDate: Maybe[Instant] = Absent,
        @rename("reply_to_message") @omit replyTo: Maybe[Message] = Absent
    ) derives CanEqual:
        /** The message's text or its media's caption. */
        def text: Maybe[String] =
            import Message.Content.*
            content match
                case Message.Content.Text(text, _)                                                                     => Present(text)
                case Photo(_, _) | Document(_, _) | Audio(_, _) | Video(_, _) | Voice(_, _) | Location(_) | Unknown(_) =>
                    caption.map(_.text)
            end match
        end text

        /** The caption of a media message. */
        def caption: Maybe[Message.Caption] =
            import Message.Content.*
            content match
                case Photo(_, caption)                                     => caption
                case Document(_, caption)                                  => caption
                case Audio(_, caption)                                     => caption
                case Video(_, caption)                                     => caption
                case Voice(_, caption)                                     => caption
                case Message.Content.Text(_, _) | Location(_) | Unknown(_) => Absent
            end match
        end caption
    end Message

    object Message:

        given Schema[Message] = WireField.snakeCase(Schema[Message].flatten(_.content))

        /** The text of a caption and its entities. */
        final case class Caption(
            @rename("caption") text: String,
            @rename("caption_entities") @omit entities: Chunk[Entity] = Chunk.empty
        ) derives CanEqual, Schema

        /** What a message holds. A message carries its content's keys beside its own, and Telegram also sets an older key for
          * some newer kinds (a venue's `location`, an animation's `document`, a live photo's `photo`), so the cases are tried in
          * order and such a message reads as the older kind.
          */
        @untagged() enum Content derives CanEqual:
            case Text(text: String, @omit entities: Chunk[Entity] = Chunk.empty)
            case Photo(@rename("photo") sizes: Chunk[Media.PhotoSize], caption: Maybe[Caption] = Absent)
            case Document(document: Media.Document, caption: Maybe[Caption] = Absent)
            case Audio(audio: Media.Audio, caption: Maybe[Caption] = Absent)
            case Video(video: Media.Video, caption: Maybe[Caption] = Absent)
            case Voice(voice: Media.Voice, caption: Maybe[Caption] = Absent)
            case Location(location: Media.Location)

            /** Content the module does not model, with the whole message as Telegram sent it. */
            @catchAll() case Unknown(raw: RawJson)
        end Content

        object Content:
            // A caption and its entities are the message's own keys, beside the media's.
            given Schema[Photo]    = Schema[Photo].flatten(_.caption)
            given Schema[Document] = Schema[Document].flatten(_.caption)
            given Schema[Audio]    = Schema[Audio].flatten(_.caption)
            given Schema[Video]    = Schema[Video].flatten(_.caption)
            given Schema[Voice]    = Schema[Voice].flatten(_.caption)
        end Content

    end Message

    /** A Telegram chat: a private chat with one user, a group, a supergroup, or a channel.
      *
      * `title` is set for groups, supergroups and channels; `firstName` and `lastName` for private chats;
      * `username` for private chats, supergroups and channels that have one. `isForum` marks a supergroup
      * with topics, whose messages carry a [[kyo.Telegram.MessageThreadId]].
      *
      * A chat to send to is a [[kyo.Telegram.Chat.Target]]: its id, or the `@username` of a public
      * supergroup or channel (or of a bot). Every outbound verb takes a target, so a chat read from an
      * update is passed back as `chat.target`.
      *
      * IMPORTANT: when a group becomes a supergroup it gets a new id, and calls to the old id fail with
      * [[kyo.TelegramMigratedException]], which carries the new one.
      *
      * @see
      *   [[kyo.Telegram.ChatId]] the id
      * @see
      *   [[kyo.Telegram.Message]] where a chat appears
      */
    final case class Chat(
        id: ChatId,
        @rename("type") kind: Chat.Type,
        title: Maybe[String] = Absent,
        username: Maybe[String] = Absent,
        firstName: Maybe[String] = Absent,
        lastName: Maybe[String] = Absent,
        @omit(omit.WhenDefault) isForum: Boolean = false
    ) derives CanEqual:
        /** This chat as a destination for the outbound verbs. */
        def target: Chat.Target = Chat.Target.Id(id)
    end Chat

    object Chat:

        given Schema[Chat] = WireField.snakeCase(Schema.derived[Chat])

        /** The kind of a chat. `Other` holds a kind Telegram added after this module, by its wire name. */
        @tagOnly() enum Type derives CanEqual:
            case Private
            case Group
            case Supergroup
            case Channel
            @catchAll() case Other(name: String)
        end Type

        object Type:
            given Schema[Type] = Schema.derived[Type].renameAllVariants(Schema.NameCase.SnakeCase)

        /** Where an outbound verb sends: a chat by id, or a public supergroup, channel or bot by `@username`. */
        enum Target derives CanEqual:
            case Id(chat: ChatId)

            /** `name` is the username without the leading `@`, which the module adds. */
            case Username(name: String)
        end Target

        object Target:
            /** The chat with this id. */
            def apply(chat: ChatId): Target = Id(chat)

            // Telegram's `chat_id` is "Integer or String": the id itself, or the username with its `@`.
            object Id:
                given Schema[Target.Id] = summon[Schema[ChatId]].transform[Target.Id](new Target.Id(_))(_.chat)

            object Username:
                given Schema[Target.Username] =
                    Schema.stringSchema.transformVia((text: String) =>
                        if text.startsWith("@") then Result.succeed(new Target.Username(text.drop(1)))
                        else Result.fail("a username without its @")
                    )((target: Target.Username) => "@" + target.name)
            end Username

            given Schema[Target] = Schema.derived[Target].untagged
        end Target

    end Chat

    /** A Telegram user or bot, as it appears on a message, a callback query, a membership change, or the
      * answer of `getMe`.
      *
      * Only `id`, `isBot` and `firstName` are always present. Telegram omits the others when the user has
      * not set them or the bot may not see them, so they are `Maybe`. `languageCode` is the IETF tag of the
      * user's client language and is sent only on what the user did themselves.
      *
      * @see
      *   [[kyo.Telegram.UserId]] the id
      * @see
      *   [[kyo.Telegram.Message]] where a user sends
      */
    final case class User(
        id: UserId,
        isBot: Boolean,
        firstName: String,
        lastName: Maybe[String] = Absent,
        username: Maybe[String] = Absent,
        languageCode: Maybe[String] = Absent
    ) derives CanEqual

    object User:
        given Schema[User] = WireField.snakeCase(Schema.derived[User])

    /** One formatted or recognized span of a message's text or caption: a mention, a link, bold text, a
      * code block.
      *
      * Telegram describes formatting as entities beside plain text rather than as markup inside it.
      * Inbound messages always carry entities; outbound, [[kyo.Telegram.Text.Plain]] sends them with the
      * text, which avoids escaping entirely.
      *
      * IMPORTANT: `offset` and `length` count UTF-16 code units, as Telegram does. A Scala `String`'s
      * `length` and `substring` count the same units, so `text.substring(offset, offset + length)` is the
      * entity's text; a count of code points or bytes is not.
      *
      * @see
      *   [[kyo.Telegram.Text]] the outbound choice between entities and markup
      */
    final case class Entity(kind: Entity.Kind, offset: Int, length: Int) derives CanEqual:
        /** The part of `text` this entity covers, when it lies inside it. */
        def of(text: String): Maybe[String] =
            if offset >= 0 && length >= 0 && offset.toLong + length <= text.length then Present(text.substring(offset, offset + length))
            else Absent
    end Entity

    object Entity:

        given Schema[Entity] = Schema[Entity].flatten(_.kind)

        /** What the span is: the entity types of Bot API 10.3. `Other` holds a type Telegram added after that version, by its wire
          * name, with the whole entity as Telegram sent it.
          */
        @discriminator("type") enum Kind derives CanEqual:
            case Mention
            case Hashtag
            case Cashtag
            case BotCommand
            case Url
            case Email
            case PhoneNumber
            case Bold
            case Italic
            case Underline
            case Strikethrough
            case Spoiler
            case Blockquote
            case ExpandableBlockquote
            case Code

            /** A code block, with its programming language when one was given. */
            case Pre(@omit language: Maybe[String] = Absent)

            /** Text linked to `url`. */
            case TextLink(url: Telegram.Url)

            /** A mention of a user who has no username. */
            case TextMention(user: User)

            /** A custom emoji, by the id of its sticker. */
            case CustomEmoji(@rename("custom_emoji_id") id: String)

            /** A date and time each client shows in its user's own format: `time`, and the format string when one was given. */
            case DateTime(
                @rename("unix_time") @transform(WireField.Date) time: Instant,
                @rename("date_time_format") @omit format: Maybe[String] = Absent
            )

            @catchAll() case Other(name: String, raw: RawJson)
        end Kind

        object Kind:
            given Schema[Kind] = Schema.derived[Kind].renameAllVariants(Schema.NameCase.SnakeCase)

    end Entity

    /** The media a message can carry, as Telegram describes each file it stores.
      *
      * Every file has a [[kyo.Telegram.FileId]], which resends it (as `Telegram.InputFile.Id`) or reads its
      * download path (`Telegram.getFile`), and a [[kyo.Telegram.FileUniqueId]], which is the same file
      * for every bot and over time but can do neither. Sizes are `ByteSize`, which holds more than 2^31
      * bytes; a size is `Absent` when Telegram omits it, and a negative one fails the decode, since Telegram
      * documents sizes as non-negative. Durations are whole seconds.
      *
      * A photo arrives as several sizes of one image, smallest first, so a message's photo is a `Chunk` of
      * [[kyo.Telegram.Media.PhotoSize]].
      *
      * @see
      *   [[kyo.Telegram.Message.Content]] where media arrives
      * @see
      *   [[kyo.Telegram.InputFile]] how media is sent
      */
    object Media:

        /** One size of a photo. */
        final case class PhotoSize(
            fileId: FileId,
            fileUniqueId: FileUniqueId,
            width: Int,
            height: Int,
            @transform(WireField.FileSize) @omit fileSize: Maybe[ByteSize] = Absent
        ) derives CanEqual

        object PhotoSize:
            given Schema[PhotoSize] = WireField.snakeCase(Schema.derived[PhotoSize])

        /** A general file. */
        final case class Document(
            fileId: FileId,
            fileUniqueId: FileUniqueId,
            fileName: Maybe[String] = Absent,
            mimeType: Maybe[String] = Absent,
            @transform(WireField.FileSize) @omit fileSize: Maybe[ByteSize] = Absent
        ) derives CanEqual

        object Document:
            given Schema[Document] = WireField.snakeCase(Schema.derived[Document])

        /** A music file, which Telegram clients play as audio. */
        final case class Audio(
            fileId: FileId,
            fileUniqueId: FileUniqueId,
            @transform(WireField.Seconds) duration: Duration,
            performer: Maybe[String] = Absent,
            title: Maybe[String] = Absent,
            fileName: Maybe[String] = Absent,
            mimeType: Maybe[String] = Absent,
            @transform(WireField.FileSize) @omit fileSize: Maybe[ByteSize] = Absent
        ) derives CanEqual

        object Audio:
            given Schema[Audio] = WireField.snakeCase(Schema.derived[Audio])

        /** A video file. */
        final case class Video(
            fileId: FileId,
            fileUniqueId: FileUniqueId,
            width: Int,
            height: Int,
            @transform(WireField.Seconds) duration: Duration,
            fileName: Maybe[String] = Absent,
            mimeType: Maybe[String] = Absent,
            @transform(WireField.FileSize) @omit fileSize: Maybe[ByteSize] = Absent
        ) derives CanEqual

        object Video:
            given Schema[Video] = WireField.snakeCase(Schema.derived[Video])

        /** A voice note. */
        final case class Voice(
            fileId: FileId,
            fileUniqueId: FileUniqueId,
            @transform(WireField.Seconds) duration: Duration,
            mimeType: Maybe[String] = Absent,
            @transform(WireField.FileSize) @omit fileSize: Maybe[ByteSize] = Absent
        ) derives CanEqual

        object Voice:
            given Schema[Voice] = WireField.snakeCase(Schema.derived[Voice])

        /** A point on the map, in degrees. */
        final case class Location(latitude: Double, longitude: Double) derives CanEqual, Schema

    end Media

    /** A press of an inline keyboard button whose button carries callback data.
      *
      * Telegram shows a progress indicator on the user's button until the bot answers the query with
      * `Telegram.answerCallback`, so a bot answers every query, even with no text.
      *
      * `message` is the message the button was on. Telegram sends it as a full message when the bot can
      * still read it, and as only its chat and id when it cannot (it was deleted, or is too old); the second
      * case is [[kyo.Telegram.CallbackQuery.Source.Inaccessible]]. A button on an inline-mode message has
      * no message, only `inlineMessageId`. `chatInstance` identifies the chat the message was in across
      * bots, for games. `data` is the button's callback data.
      *
      * @see
      *   [[kyo.Telegram.Keyboard]] where callback buttons are built
      * @see
      *   [[kyo.Telegram.answerCallback]] the answer
      */
    final case class CallbackQuery(
        id: CallbackQueryId,
        from: User,
        chatInstance: String,
        @omit message: Maybe[CallbackQuery.Source] = Absent,
        @omit inlineMessageId: Maybe[String] = Absent,
        @omit data: Maybe[String] = Absent
    ) derives CanEqual:
        /** The chat of the message the button was on. */
        def chat: Maybe[Chat] =
            message.map {
                case CallbackQuery.Source.Accessible(m)         => m.chat
                case CallbackQuery.Source.Inaccessible(chat, _) => chat
            }
    end CallbackQuery

    object CallbackQuery:

        given Schema[CallbackQuery] = WireField.snakeCase(Schema.derived[CallbackQuery])

        /** The message a pressed button was on. Telegram tells the two apart by `date`, which is always 0 for a message the bot
          * can no longer read.
          */
        @untagged() enum Source derives CanEqual:
            case Accessible(message: Message)

            /** A message the bot can no longer read: only where it was. */
            case Inaccessible(chat: Chat, message: MessageId)
        end Source

        object Source:
            given Schema[Accessible] = summon[Schema[Message]].transformVia((message: Message) =>
                if message.date.toDuration == Duration.Zero then Result.fail("an inaccessible message, whose date is 0")
                else Result.succeed(new Accessible(message))
            )((accessible: Accessible) => accessible.message)

            given Schema[Inaccessible] = summon[Schema[InaccessibleMessage]].transformVia((m: InaccessibleMessage) =>
                if m.date == 0 then Result.succeed(new Inaccessible(m.chat, m.message))
                else Result.fail("a message whose date is not 0, which only an accessible message has")
            )((inaccessible: Inaccessible) => InaccessibleMessage(inaccessible.chat, inaccessible.message, 0))

            final private case class InaccessibleMessage(chat: Chat, @rename("message_id") message: MessageId, date: Long)
                derives Schema
        end Source

    end CallbackQuery

    /** A member of a chat and their status in it, as Telegram's `ChatMember` object carries them.
      *
      * Telegram's object has more fields for some statuses (an administrator's rights, a restriction's end);
      * the model reads the status and the user.
      *
      * @see
      *   [[kyo.Telegram.ChatMemberUpdate]] where a member's change arrives
      */
    final case class ChatMember(status: ChatMember.Status, user: User) derives CanEqual, Schema

    object ChatMember:

        /** A member's status. `Other` holds a status Telegram added after this module, by its wire name. */
        @tagOnly() enum Status derives CanEqual:
            case Creator
            case Administrator
            case Member
            case Restricted
            case Left
            case Kicked
            @catchAll() case Other(name: String)
        end Status

        object Status:
            given Schema[Status] = Schema.derived[Status].renameAllVariants(Schema.NameCase.SnakeCase)

    end ChatMember

    /** A change of one member's status in a chat: the bot's own (added, removed, blocked by a user) or,
      * when the bot is an administrator that asked for them, any member's.
      *
      * `oldChatMember` and `newChatMember` are the member before and after, each with its status; `from` is
      * who made the change. A private chat reports only that the user blocked or unblocked the bot: the
      * bot's status becomes `Kicked` or `Member`. After `Kicked` in a private chat, sending there fails with
      * [[kyo.TelegramForbiddenException]].
      *
      * @see
      *   [[kyo.Telegram.Update.MyChatMember]] the bot's own membership
      */
    final case class ChatMemberUpdate(
        chat: Chat,
        from: User,
        @transform(WireField.Date) date: Instant,
        oldChatMember: ChatMember,
        newChatMember: ChatMember
    ) derives CanEqual

    object ChatMemberUpdate:
        given Schema[ChatMemberUpdate] = WireField.snakeCase(Schema.derived[ChatMemberUpdate])

    /** A reaction on a message: one emoji, a custom emoji, or a paid reaction.
      *
      * Telegram accepts only a fixed set of emoji as reactions; `setReaction` with another fails with
      * [[kyo.TelegramOtherApiException]]. `Other` holds a reaction type Telegram added after this module, with
      * its wire name and the reaction as Telegram sent it, on reactions that arrive. It cannot be sent:
      * `setReaction` takes a [[kyo.Telegram.Reaction.Sendable]], which has no `Other`.
      *
      * @see
      *   [[kyo.Telegram.setReaction]] how a bot reacts
      * @see
      *   [[kyo.Telegram.ReactionUpdate]] a user's reactions arriving
      */
    @discriminator("type") enum Reaction derives CanEqual:
        case Emoji(emoji: String)
        case CustomEmoji(@rename("custom_emoji_id") id: String)
        case Paid
        @catchAll() case Other(name: String, raw: RawJson)
    end Reaction

    object Reaction:
        /** The reactions a bot can set: an emoji or a custom emoji ("Paid reactions can't be used by bots", `setMessageReaction`). */
        type Sendable = Emoji | CustomEmoji

        given Schema[Reaction] = Schema.derived[Reaction].renameAllVariants(Schema.NameCase.SnakeCase)

        given sendableSchema: Schema[Sendable] =
            summon[Schema[Reaction]].transformVia((reaction: Reaction) =>
                reaction match
                    case sendable: Sendable => Result.succeed(sendable)
                    case Paid               => Result.fail("a paid reaction, which a bot cannot set")
                    case Other(name, _)     => Result.fail(s"a $name reaction, which a bot cannot set")
            )((sendable: Sendable) => sendable)
    end Reaction

    /** A user changed their reactions to a message.
      *
      * Telegram sends it only to a bot that is an administrator of the chat and that asked for
      * `message_reaction` in its allowed updates, and never for reactions set by bots. `user` is absent
      * when the reaction was anonymous, and `actorChat` then says which chat reacted.
      *
      * @see
      *   [[kyo.Telegram.Reaction]] the reactions
      * @see
      *   [[kyo.Telegram.Update.Type.MessageReaction]] the allowed-update type that enables it
      */
    final case class ReactionUpdate(
        chat: Chat,
        @rename("message_id") message: MessageId,
        @transform(WireField.Date) date: Instant,
        oldReaction: Chunk[Reaction],
        newReaction: Chunk[Reaction],
        @omit user: Maybe[User] = Absent,
        @omit actorChat: Maybe[Chat] = Absent
    ) derives CanEqual

    object ReactionUpdate:
        given Schema[ReactionUpdate] = WireField.snakeCase(Schema.derived[ReactionUpdate])

    /** A file ready to download, as `Telegram.getFile` answers it.
      *
      * `path` is where `Telegram.download` fetches it from. Telegram guarantees the path works for at least
      * an hour; after that `getFile` gives a new one. A bot may download files of up to 20 MB from
      * Telegram's servers: `getFile` for a larger one fails with [[kyo.TelegramFileTooBigException]].
      *
      * Note: on a self-hosted Bot API server `path` is an absolute path on that server's disk, which
      * `download` cannot fetch over HTTP; read the file from that disk instead.
      *
      * @see
      *   [[kyo.Telegram.getFile]] how a file is looked up
      * @see
      *   [[kyo.Telegram.download]] how it is fetched
      */
    final case class File(
        @rename("file_id") id: FileId,
        @rename("file_unique_id") uniqueId: FileUniqueId,
        @rename("file_size") @transform(WireField.FileSize) @omit size: Maybe[ByteSize] = Absent,
        @rename("file_path") path: Maybe[String] = Absent
    ) derives CanEqual, Schema

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
    final case class WebhookInfo(
        @transform(WireField.WebhookUrl) url: Maybe[HttpUrl],
        hasCustomCertificate: Boolean,
        pendingUpdateCount: Int,
        ipAddress: Maybe[String] = Absent,
        @rename("last_error_date") @transform(WireField.MaybeDate) @omit lastDeliveryFailureDate: Maybe[Instant] = Absent,
        @rename("last_error_message") lastDeliveryFailureMessage: Maybe[String] = Absent,
        maxConnections: Maybe[Int] = Absent,
        @omit allowedUpdates: Chunk[Update.Type] = Chunk.empty
    ) derives CanEqual

    object WebhookInfo:
        given Schema[WebhookInfo] = WireField.snakeCase(Schema.derived[WebhookInfo])

    /** A JSON value exactly as Telegram sent it, held by the `Unknown` cases of the model.
      *
      * An update or a message whose kind the module does not model, or whose known kind lacks a field that
      * kind requires, is not dropped: it arrives as an `Unknown` case carrying this value, so a caller can
      * still read it (`json` as a tree, `value` as JSON text) and still deduplicate by the id beside it.
      *
      * Note: the payload is a user's message or a chat's state, so `toString` renders only the length of its
      * text, `Telegram.RawJson(<n> characters)`. A log line or an assertion message that prints a model value
      * never prints what someone wrote.
      *
      * Its `Schema` is the JSON value itself, not a string holding it, so an `Unknown` encodes back to what
      * Telegram sent.
      *
      * @see
      *   [[kyo.Telegram.Update]] where an unknown update arrives
      * @see
      *   [[kyo.Telegram.Message]] where an unknown message content arrives
      */
    final class RawJson private (val json: Structure.Value, val value: String):

        override def equals(other: Any): Boolean =
            other match
                case that: RawJson => json == that.json
                case _             => false
        override def hashCode: Int    = json.hashCode
        override def toString: String = s"Telegram.RawJson(${value.length} characters)"
    end RawJson

    object RawJson:
        /** The payload `json`, with `value` as its JSON text. */
        def apply(json: Structure.Value)(using Frame): RawJson = new RawJson(json, Json.encode(json)(using Structure.Value.valueSchema))
        given CanEqual[RawJson, RawJson]                       = CanEqual.derived

        // Dynamic by definition: this is the payload of a kind the model does not declare, so its shape is whatever Telegram sent.
        given Schema[RawJson] = Structure.Value.valueSchema.transformVia((json: Structure.Value) => RawJson(json))(_.json)
    end RawJson

    // --- What is sent ---

    /** What `Telegram.send` sends: text, one kind of media with an optional caption, or a location.
      *
      * Each case is one Bot API method (`sendMessage`, `sendPhoto`, `sendDocument`, `sendAudio`,
      * `sendVideo`, `sendVoice`, `sendLocation`), and they share their failures, so one operation sends
      * them all. A media file is a [[kyo.Telegram.InputFile]]: an id, a URL, or bytes uploaded as multipart.
      *
      * `linkPreview = false` asks Telegram not to show a preview of the first link in the text.
      *
      * @see
      *   [[kyo.Telegram.send]] the operation
      * @see
      *   [[kyo.Telegram.Text]] how text is formatted
      */
    enum Content derives CanEqual:
        case Text(text: Telegram.Text, linkPreview: Boolean = true)
        case Photo(file: InputFile, caption: Maybe[Telegram.Text] = Absent)
        case Document(file: InputFile, caption: Maybe[Telegram.Text] = Absent)
        case Audio(file: InputFile, caption: Maybe[Telegram.Text] = Absent)
        case Video(file: InputFile, caption: Maybe[Telegram.Text] = Absent)
        case Voice(file: InputFile, caption: Maybe[Telegram.Text] = Absent)
        case Location(latitude: Double, longitude: Double)
    end Content

    object Content:
        /** Plain text. */
        def text(text: String): Content = Text(Telegram.Text(text))
    end Content

    /** The text of a message or a caption, with the way its formatting is sent.
      *
      * Telegram offers three ways to format, and this type is the choice among them:
      *   - `Plain`: the text as it is, with formatting given as [[kyo.Telegram.Entity]] spans. Nothing is
      *     parsed, so nothing needs escaping.
      *   - `MarkdownV2` and `Html`: a [[kyo.Telegram.Markup]] tree, rendered to the mode's markup with every
      *     piece of text escaped, so a caller cannot send malformed markup by accident.
      *
      * `Telegram.Text("hi")` is plain text with no entities. Telegram's limits are 4096 characters for a
      * message and 1024 for a caption, counted after parsing; longer text is refused by Telegram with a
      * [[kyo.TelegramOtherApiException]].
      *
      * @see
      *   [[kyo.Telegram.Content]] where text is sent
      * @see
      *   [[kyo.Telegram.Markup]] the formatted tree
      */
    enum Text derives CanEqual:
        case Plain(text: String, entities: Chunk[Entity] = Chunk.empty)
        case MarkdownV2(markup: Markup)
        case Html(markup: Markup)
    end Text

    object Text:
        /** Plain text with no entities. */
        def apply(text: String): Text = Plain(text)
    end Text

    /** Formatted text as a tree, rendered to MarkdownV2 or HTML by [[kyo.Telegram.Text]] with every piece of
      * text escaped for the chosen mode.
      *
      * Telegram's markup modes each reserve characters: MarkdownV2 eighteen of them outside code, HTML `<`,
      * `>` and `&`. A message whose text holds one unescaped is refused, or worse, formatted differently from
      * what was meant. Building the message as this tree leaves no raw markup to get wrong: `Text` is always
      * literal text, and the renderer writes the markers.
      *
      * `Mention` links to a user by id, which works for users without a username. `Pre`'s language is the
      * text up to its first white space. `Blockquote` quotes each line of its content.
      *
      * @see
      *   [[kyo.Telegram.Text]] the choice of mode
      * @see
      *   [[kyo.Telegram.Entity]] the alternative that needs no escaping
      */
    enum Markup derives CanEqual:
        case Text(value: String)
        case Bold(content: Markup)
        case Italic(content: Markup)
        case Underline(content: Markup)
        case Strikethrough(content: Markup)
        case Spoiler(content: Markup)
        case Code(value: String)
        case Pre(value: String, language: Maybe[String] = Absent)
        case Link(content: Markup, url: Url)
        case Mention(content: Markup, user: UserId)
        case Blockquote(content: Markup)
        case Concat(parts: Chunk[Markup])
    end Markup

    object Markup:
        /** The pieces in order. */
        def of(parts: Markup*): Markup = Concat(Chunk.from(parts))
    end Markup

    /** A file to send, in one of the three ways Telegram accepts one.
      *
      *   - `Id`: a file already on Telegram's servers, by the id a message or `getFile` gave. No size limit,
      *     but the kind cannot change: a video's id cannot be sent as a photo.
      *   - `Url`: Telegram downloads it. Up to 5 MB for photos and 20 MB for other kinds, and the server must
      *     answer the right MIME type; `sendDocument` by URL works only for PDF and ZIP.
      *   - `Upload`: the bytes, sent as multipart form data. Up to 10 MB for photos and 50 MB for other
      *     kinds on Telegram's servers. `name` is the file name Telegram shows.
      *
      * The limits are Telegram's (Bot API, "Sending files"); a file over them is refused by Telegram.
      *
      * An `Upload` compares its bytes element by element, since `Span` itself compares by reference, so two
      * uploads of the same content are equal, and so are the messages that carry them.
      *
      * @see
      *   [[kyo.Telegram.Content]] the media kinds that take a file
      */
    sealed trait InputFile derives CanEqual

    object InputFile:

        final case class Id(file: FileId) extends InputFile

        final case class Url(url: HttpUrl) extends InputFile

        final case class Upload(name: String, bytes: Span[Byte], contentType: Maybe[String] = Absent) extends InputFile:
            override def equals(other: Any): Boolean =
                other match
                    case that: Upload => that.name == name && that.contentType == contentType && that.bytes.is(bytes)
                    case _            => false

            override def hashCode: Int = (name, contentType, bytes.hash).##
        end Upload
    end InputFile

    /** How `Telegram.send` places and presents a message, beside what it holds.
      *
      * `thread` sends into a forum topic or a reply thread. `replyTo` makes the message a reply to another
      * in the same chat. `keyboard` attaches buttons. `silent` delivers without a notification sound.
      * `protect` stops the message from being forwarded or saved.
      *
      * @see
      *   [[kyo.Telegram.send]] the operation
      * @see
      *   [[kyo.Telegram.Keyboard]] the buttons
      */
    final case class SendOptions(
        @rename("message_thread_id") @omit thread: Maybe[MessageThreadId] = Absent,
        @rename("reply_parameters") @transform(WireField.ReplyTo) @omit replyTo: Maybe[MessageId] = Absent,
        @rename("reply_markup") @omit keyboard: Maybe[Keyboard] = Absent,
        @rename("disable_notification") @omit(omit.WhenDefault) silent: Boolean = false,
        @rename("protect_content") @omit(omit.WhenDefault) protect: Boolean = false
    ) derives CanEqual, Schema

    object SendOptions:
        /** No thread, no reply, no keyboard, with a notification, forwardable. */
        val default: SendOptions = SendOptions()
    end SendOptions

    /** A keyboard attached to a message: buttons under the message (`Inline`), buttons in place of the
      * user's keyboard (`Reply`), an instruction to remove a reply keyboard (`Remove`), or to open a reply
      * to the message (`ForceReply`).
      *
      * An inline button either opens a URL or sends callback data back to the bot as a
      * [[kyo.Telegram.CallbackQuery]]. A reply button sends its text as a message, or shares the user's
      * contact or location.
      *
      * IMPORTANT: callback data is 1 to 64 bytes in UTF-8. [[kyo.Telegram.Keyboard.CallbackData]]'s `init` checks
      * this and fails with [[kyo.TelegramInvalidCallbackDataException]] otherwise, so a keyboard Telegram would
      * refuse is refused where it is written.
      *
      * Only an inline keyboard can be set on an edited message ([[kyo.Telegram.Edit]]).
      *
      * @see
      *   [[kyo.Telegram.SendOptions]] where a keyboard is attached
      * @see
      *   [[kyo.Telegram.CallbackQuery]] a press of a callback button
      */
    @untagged() enum Keyboard derives CanEqual:
        case Inline(@rename("inline_keyboard") rows: Chunk[Chunk[Keyboard.InlineButton]])
        case Reply(
            @rename("keyboard") rows: Chunk[Chunk[Keyboard.ReplyButton]],
            @rename("resize_keyboard") @omit(omit.WhenDefault) resize: Boolean = false,
            @rename("one_time_keyboard") @omit(omit.WhenDefault) oneTime: Boolean = false,
            @rename("is_persistent") @omit(omit.WhenDefault) persistent: Boolean = false,
            @rename("input_field_placeholder") placeholder: Maybe[String] = Absent
        )
        case Remove(@rename("remove_keyboard") remove: Keyboard.True)
        case ForceReply(
            @rename("force_reply") force: Keyboard.True,
            @rename("input_field_placeholder") placeholder: Maybe[String] = Absent
        )
    end Keyboard

    object Keyboard:

        /** An inline keyboard with these rows. */
        def inline(rows: Seq[InlineButton]*): Inline = Inline(Chunk.from(rows).map(Chunk.from(_)))

        /** A reply keyboard with these rows, resized to fit. */
        def reply(rows: Seq[ReplyButton]*): Reply = Reply(Chunk.from(rows).map(Chunk.from(_)), resize = true)

        /** Removes the reply keyboard. */
        val remove: Keyboard = Remove(True)

        /** Opens a reply to the bot's message, with `placeholder` in the input field. */
        def forceReply(placeholder: Maybe[String] = Absent): Keyboard = ForceReply(True, placeholder)

        /** Telegram's `True` type: a field whose only value is `true`, which marks what kind of object holds it. It has no
          * default, so a decode tells the objects apart by which marker is present.
          */
        type True = True.type

        case object True:
            given Schema[True] =
                Schema.booleanSchema.transformVia((value: Boolean) => if value then Result.succeed(True) else Result.fail("false"))(_ =>
                    true
                )
            given CanEqual[True, True] = CanEqual.derived
        end True

        /** A button under a message, drawn in its `style` when it has one. `Other` keeps a kind of button the module does not
          * model, as Telegram sent it.
          */
        @untagged() enum InlineButton derives CanEqual:
            case Callback(text: String, @rename("callback_data") data: CallbackData, @omit style: Maybe[Style] = Absent)
            case Url(text: String, url: Telegram.Url, @omit style: Maybe[Style] = Absent)
            @catchAll() case Other(raw: RawJson)
        end InlineButton

        object InlineButton:
            /** A button that sends `data` back as a callback query, or a failure when `data` is not 1 to 64 bytes. */
            def callback(text: String, data: String, style: Maybe[Style] = Absent)(using
                Frame
            ): Result[TelegramInvalidCallbackDataException, InlineButton] =
                CallbackData.init(data).map(Callback(text, _, style))
        end InlineButton

        /** A button's color: red (`Danger`), green (`Success`) or blue (`Primary`). `Other` names one the module does not
          * model.
          */
        @tagOnly() enum Style derives CanEqual:
            case Danger
            case Success
            case Primary
            @catchAll() case Other(name: String)
        end Style

        object Style:
            given Schema[Style] = Schema.derived[Style].renameAllVariants(Schema.NameCase.SnakeCase)

        /** A button in place of the user's keyboard. A decode tries the two request kinds first, by their marker, and reads
          * any other button as `Text`.
          */
        @untagged() enum ReplyButton derives CanEqual:
            case RequestContact(text: String, @rename("request_contact") requestContact: True)
            case RequestLocation(text: String, @rename("request_location") requestLocation: True)
            case Text(text: String)
        end ReplyButton

        object ReplyButton:
            /** A button that shares the user's phone number. */
            def requestContact(text: String): ReplyButton = RequestContact(text, True)

            /** A button that shares the user's location. */
            def requestLocation(text: String): ReplyButton = RequestLocation(text, True)
        end ReplyButton

        /** Callback data, 1 to 64 bytes in UTF-8. */
        type CallbackData = CallbackData.Value

        object CallbackData:
            opaque type Value = String

            /** The callback data `value`, or a [[kyo.TelegramInvalidCallbackDataException]] outside 1 to 64 bytes. */
            def init(value: String)(using Frame): Result[TelegramInvalidCallbackDataException, CallbackData] =
                checked(problemOf(value).map(TelegramInvalidCallbackDataException(_)), value)

            extension (self: CallbackData) def value: String = self

            given CanEqual[CallbackData, CallbackData] = CanEqual.derived

            given Schema[CallbackData] = Schema.stringSchema.transformVia(text => checked(problemOf(text), text))(_.value)

            /** The UTF-8 length of `value` when it is outside what Telegram accepts. */
            private def problemOf(value: String): Maybe[Int] =
                val length = Utf8.encode(value).size
                if length < 1 || length > MaxBytes then Present(length) else Absent

            /** The most bytes Telegram accepts. */
            inline val MaxBytes = 64
        end CallbackData

    end Keyboard

    /** A change to a message the bot sent: its text, its media's caption, or its inline keyboard.
      *
      * Each case is one Bot API method (`editMessageText`, `editMessageCaption`, `editMessageReplyMarkup`).
      * A keyboard that is `Absent` removes the one the message had. Only an inline keyboard can be set on
      * an edit.
      *
      * IMPORTANT: an edit that would leave the message exactly as it is fails with
      * [[kyo.TelegramMessageNotModifiedException]], which a bot that re-renders a message on every button
      * press usually recovers.
      *
      * @see
      *   [[kyo.Telegram.edit]] the operation
      */
    enum Edit derives CanEqual:
        case Text(text: Telegram.Text, keyboard: Maybe[Telegram.Keyboard.Inline] = Absent, linkPreview: Boolean = true)
        case Caption(caption: Maybe[Telegram.Text], keyboard: Maybe[Telegram.Keyboard.Inline] = Absent)
        case Keyboard(keyboard: Maybe[Telegram.Keyboard.Inline])
    end Edit

    /** The answer to a callback query: what the user sees after pressing the button.
      *
      * With no `text`, the answer only stops the button's progress indicator. `text` shows a short
      * notification at the top of the chat (up to 200 characters), or an alert the user must dismiss when
      * `showAlert` is set. `url` opens a game or a `t.me` link to the bot. `cacheTime` lets Telegram
      * clients reuse this answer for that long without asking the bot again.
      *
      * IMPORTANT: `init` refuses a `text` over 200 characters, and a `cacheTime` that is not a whole number of
      * seconds up to `Int.MaxValue`, with a [[kyo.TelegramInvalidCallbackAnswerException]]: Telegram takes the
      * cache time in whole seconds, and a fraction is refused rather than rounded.
      *
      * @see
      *   [[kyo.Telegram.answerCallback]] the operation
      */
    final case class CallbackAnswer private[kyo] (
        @omit text: Maybe[String],
        showAlert: Boolean,
        @omit url: Maybe[Url],
        @transform(WireField.MaybeSeconds) @omit cacheTime: Maybe[Duration]
    ) derives CanEqual

    object CallbackAnswer:
        /** No text: only stops the progress indicator. */
        val empty: CallbackAnswer = new CallbackAnswer(Absent, false, Absent, Absent)

        /** The answer, or a [[kyo.TelegramInvalidCallbackAnswerException]] naming the first value Telegram would refuse. */
        def init(
            text: Maybe[String] = Absent,
            showAlert: Boolean = false,
            url: Maybe[Url] = Absent,
            cacheTime: Maybe[Duration] = Absent
        )(using Frame): Result[TelegramInvalidCallbackAnswerException, CallbackAnswer] =
            checked(
                problemOf(text, cacheTime).map(TelegramInvalidCallbackAnswerException(_)),
                new CallbackAnswer(text, showAlert, url, cacheTime)
            )

        given Schema[CallbackAnswer] =
            WireField.snakeCase(Schema.derivedVia((text: Maybe[String], showAlert: Boolean, url: Maybe[Url], cacheTime: Maybe[Duration]) =>
                checked(problemOf(text, cacheTime), new CallbackAnswer(text, showAlert, url, cacheTime))
            ))

        private def problemOf(text: Maybe[String], cacheTime: Maybe[Duration]): Maybe[TelegramInvalidCallbackAnswerException.Problem] =
            import TelegramInvalidCallbackAnswerException.Problem
            text.filter(_.length > MaxTextLength).map(t => Problem.TextLength(t.length))
                .orElse(cacheTime.filter(t => !TelegramConfig.wholeSeconds(t)).map(Problem.CacheTime(_)))
        end problemOf

        /** The longest notification text Telegram shows. */
        inline val MaxTextLength = 200
    end CallbackAnswer

    /** What the bot tells a chat it is doing, shown as a status such as "typing..." for up to 5 seconds or
      * until the bot's next message arrives.
      *
      * Telegram recommends sending one only when the answer will take noticeable time. Each case names what
      * the user is about to receive: `Typing` for text, `UploadPhoto` for a photo, `RecordVoice` or
      * `UploadVoice` for a voice note, and so on.
      *
      * @see
      *   [[kyo.Telegram.sendChatAction]] the operation
      */
    @tagOnly() enum ChatAction derives CanEqual:
        case Typing
        case UploadPhoto
        case RecordVideo
        case UploadVideo
        case RecordVoice
        case UploadVoice
        case UploadDocument
        case ChooseSticker
        case FindLocation
        case RecordVideoNote
        case UploadVideoNote
    end ChatAction

    object ChatAction:
        given Schema[ChatAction] = Schema.derived[ChatAction].renameAllVariants(Schema.NameCase.SnakeCase)

    /** A command in the bot's menu, as `Telegram.setCommands` registers it: `/name` with its description.
      *
      * IMPORTANT: Telegram accepts a name of 1 to 32 characters from `a-z`, `0-9` and `_`, and a
      * description of 1 to 256 characters. `init` checks both and fails with
      * [[kyo.TelegramInvalidCommandException]] otherwise.
      *
      * A [[kyo.Telegram.Command.Scope]] chooses who sees a list: everyone, all private chats, all groups,
      * their administrators, one chat, its administrators, or one member of it. Telegram shows a user the
      * list of the narrowest scope that applies.
      *
      * @see
      *   [[kyo.Telegram.setCommands]] the operation
      */
    final case class Command private[kyo] (@rename("command") name: String, description: String) derives CanEqual

    object Command:

        /** The command `/name`, or a [[kyo.TelegramInvalidCommandException]] naming what Telegram would refuse. */
        def init(name: String, description: String)(using Frame): Result[TelegramInvalidCommandException, Command] =
            checked(problemOf(name, description).map(TelegramInvalidCommandException(_)), new Command(name, description))

        given Schema[Command] =
            Schema.derivedVia((name: String, description: String) => checked(problemOf(name, description), new Command(name, description)))

        /** Who sees a list of commands. */
        @discriminator("type") enum Scope derives CanEqual:
            case Default
            case AllPrivateChats
            case AllGroupChats
            case AllChatAdministrators
            case Chat(@rename("chat_id") chat: Telegram.Chat.Target)
            case ChatAdministrators(@rename("chat_id") chat: Telegram.Chat.Target)
            case ChatMember(@rename("chat_id") chat: Telegram.Chat.Target, @rename("user_id") user: UserId)
        end Scope

        object Scope:
            given Schema[Scope] = Schema.derived[Scope].renameAllVariants(Schema.NameCase.SnakeCase)

        /** The commands `setCommands` sets, at most 100 ("At most 100 commands can be specified", `setMyCommands`). */
        type Menu = Menu.Value

        object Menu:
            opaque type Value = Chunk[Command]

            /** The menu of `commands`, or a [[kyo.TelegramInvalidCommandException]] when there are more than 100. */
            def init(commands: Command*)(using Frame): Result[TelegramInvalidCommandException, Menu] =
                val all = Chunk.from(commands)
                checked(
                    if all.size > MaxCommands then
                        Present(TelegramInvalidCommandException(TelegramInvalidCommandException.Problem.TooMany(all.size)))
                    else Absent,
                    all
                )
            end init

            extension (self: Menu) def commands: Chunk[Command] = self

            given CanEqual[Menu, Menu] = CanEqual.derived

            /** The most commands Telegram accepts in one menu. */
            inline val MaxCommands = 100
        end Menu

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

    end Command

    /** How `Telegram.setWebhook` registers the webhook, beside its URL and secret.
      *
      * `allowedUpdates` chooses the update kinds delivered; `Absent` keeps the previous choice, and an
      * empty list asks for every kind except `chat_member`, `message_reaction` and
      * `message_reaction_count`. `maxConnections` bounds the concurrent deliveries, 1 to 100 (Telegram's
      * default is 40). `dropPendingUpdates` discards the updates waiting for delivery. `ipAddress` makes
      * Telegram connect to that address instead of the one DNS gives.
      *
      * IMPORTANT: `init` refuses a `maxConnections` outside 1 to 100 with a
      * [[kyo.TelegramInvalidWebhookOptionsException]], as Telegram would refuse it.
      *
      * @see
      *   [[kyo.Telegram.setWebhook]] the operation
      */
    final case class WebhookOptions private[kyo] (
        @omit allowedUpdates: Maybe[Chunk[Update.Type]],
        @omit maxConnections: Maybe[Int],
        dropPendingUpdates: Boolean,
        @omit ipAddress: Maybe[String]
    ) derives CanEqual

    object WebhookOptions:
        /** Telegram's defaults, with the previous allowed updates kept. */
        val default: WebhookOptions = new WebhookOptions(Absent, Absent, false, Absent)

        /** The options, or a [[kyo.TelegramInvalidWebhookOptionsException]] when Telegram would refuse one. */
        def init(
            allowedUpdates: Maybe[Chunk[Update.Type]] = Absent,
            maxConnections: Maybe[Int] = Absent,
            dropPendingUpdates: Boolean = false,
            ipAddress: Maybe[String] = Absent
        )(using Frame): Result[TelegramInvalidWebhookOptionsException, WebhookOptions] =
            checked(
                problemOf(maxConnections).map(TelegramInvalidWebhookOptionsException(_)),
                new WebhookOptions(allowedUpdates, maxConnections, dropPendingUpdates, ipAddress)
            )

        given Schema[WebhookOptions] =
            WireField.snakeCase(Schema.derivedVia(
                (
                    allowedUpdates: Maybe[Chunk[Update.Type]],
                    maxConnections: Maybe[Int],
                    dropPendingUpdates: Boolean,
                    ipAddress: Maybe[String]
                ) =>
                    checked(problemOf(maxConnections), new WebhookOptions(allowedUpdates, maxConnections, dropPendingUpdates, ipAddress))
            ))

        private def problemOf(maxConnections: Maybe[Int]): Maybe[TelegramInvalidWebhookOptionsException.Problem] =
            maxConnections.filter(n => n < 1 || n > 100).map(TelegramInvalidWebhookOptionsException.Problem.MaxConnections(_))
    end WebhookOptions

    /** The name of a Bot API method, as `Telegram.custom` calls it: `sendMessage`, `getChatMemberCount`.
      *
      * The name is placed in the request path after the bot token, `/bot<token>/<method>`, so a `/`, `?`, `#`
      * or `%` in it would send the token to a different path or into a query, and a name of only dots would
      * be a `.` or `..` segment. `init` accepts ASCII letters, digits, `.` and `_`, and refuses a name of only
      * dots, failing with [[kyo.TelegramInvalidMethodException]].
      *
      * @see
      *   [[kyo.Telegram.custom]] the operation that takes it
      */
    type Method = Method.Value

    object Method:

        opaque type Value = String

        /** The method named `name` (one or more of ASCII letters, digits, `.` and `_`, not only dots), or a
          * [[kyo.TelegramInvalidMethodException]] naming why it is not one.
          */
        def init(name: String)(using Frame): Result[TelegramInvalidMethodException, Method] =
            checked(problemOf(name).map(TelegramInvalidMethodException(_)), name)

        extension (self: Method) def value: String = self

        given CanEqual[Method, Method] = CanEqual.derived

        private def problemOf(name: String): Maybe[TelegramInvalidMethodException.Problem] =
            import TelegramInvalidMethodException.Problem
            if name.isEmpty then Present(Problem.Empty)
            else
                val bad = name.indexWhere(c =>
                    !((c >= 'a' && c <= 'z') ||
                        (c >= 'A' && c <= 'Z') ||
                        (c >= '0' && c <= '9') || c == '.' || c == '_')
                )
                if bad >= 0 then Present(Problem.Character(bad))
                else if name.forall(_ == '.') then Present(Problem.Dots)
                else Absent
            end if
        end problemOf

    end Method

    /** A link Telegram opens for a user: an inline button's URL, a callback answer's URL, a text link in a
      * message.
      *
      * Telegram accepts `tg://` links here as well as `http` and `https` (`tg://user?id=<id>` opens a
      * profile), which `HttpUrl` does not parse, so these fields take this type rather than `HttpUrl`. The
      * module never sends a request to it: Telegram's clients open it.
      *
      * IMPORTANT: `init` accepts text that starts with `http://`, `https://` or `tg://` in any ASCII case,
      * followed by one or more printable ASCII characters other than space, and fails with
      * [[kyo.TelegramInvalidUrlException]] otherwise; the `Schema` refuses the same text as a decode
      * failure. A message holding a text link whose URL does not pass has the content `Message.Content.Unknown`, with the
      * whole message as Telegram sent it.
      *
      * @see
      *   [[kyo.Telegram.Keyboard.InlineButton]] a button that opens a link
      */
    type Url = Url.Value

    object Url:

        opaque type Value = String

        /** The link `value`, or a [[kyo.TelegramInvalidUrlException]] when it is not an http, https or tg URL. */
        def init(value: String)(using Frame): Result[TelegramInvalidUrlException, Url] =
            checked(problemOf(value).map(TelegramInvalidUrlException(_)), value)

        extension (self: Url) def value: String = self

        /** `value` as a link, when it is one; how a link that arrives in a message is read. */
        private[kyo] def parse(value: String): Maybe[Url] = if problemOf(value).isEmpty then Present(value) else Absent

        given CanEqual[Url, Url] = CanEqual.derived

        given Schema[Url] = Schema.stringSchema.transformVia(text => checked(problemOf(text), text))(_.value)

        /** Why `value` is not a link this type holds. */
        private[kyo] def problemOf(value: String): Maybe[TelegramInvalidUrlException.Problem] =
            import TelegramInvalidUrlException.Problem
            Schemes.filter(s => value.length >= s.length && asciiLower(value.substring(0, s.length)) == s).headMaybe match
                case Absent          => Present(Problem.Scheme)
                case Present(scheme) =>
                    if value.length == scheme.length then Present(Problem.Empty)
                    else
                        val bad = value.indexWhere(c => c <= ' ' || c > '~')
                        if bad >= 0 then Present(Problem.Character(bad)) else Absent
            end match
        end problemOf

        private val Schemes: Chunk[String] = Chunk("http://", "https://", "tg://")

        private def asciiLower(s: String): String = s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

    end Url

    // --- Webhook delivery ---

    /** Receiving updates by webhook: an `HttpHandler` the bot mounts on its own `HttpServer`, which Telegram
      * POSTs every update to once `Telegram.setWebhook` registered its URL.
      *
      * `handler` checks the `X-Telegram-Bot-Api-Secret-Token` header against the [[kyo.TelegramWebhookConfig]]'s
      * secret, the one `setWebhook` registered, decodes the body into a [[kyo.Telegram.Update]], and runs the
      * callback with a client built from the [[kyo.TelegramConfig]], so the callback calls the verbs
      * directly. The comparison is in constant time, so the time of a refusal does not reveal how much of
      * the secret matched. `verify` and `decode` are the two steps on their own, for a bot that serves the
      * endpoint itself.
      *
      * Building the handler is an effect: it opens the client once, in the enclosing `Scope` (the one the
      * `HttpServer` runs in), and every delivery's callback runs on that client. Its connections close when
      * the `Scope` does.
      *
      * What the handler answers decides what Telegram does next. A request without the right secret gets
      * 403 and is not decoded. A body that is not an update gets 200 and a log line, since Telegram would
      * redeliver it forever otherwise. A callback that fails or panics gets 500, and Telegram redelivers the
      * update a bounded number of times, so the callback deduplicates by `update.id`.
      *
      * IMPORTANT: Telegram keeps at most `maxConnections` deliveries open at a time (40 by default, set by
      * `Telegram.WebhookOptions`), so callbacks can run concurrently and a slow one holds a delivery open.
      *
      * @see
      *   [[kyo.Telegram.setWebhook]] registering the URL
      * @see
      *   [[kyo.Telegram.run]] the alternative, long polling
      */
    object Webhook:

        /** The header Telegram sends the secret in. */
        inline val SecretHeader = "X-Telegram-Bot-Api-Secret-Token"

        /** An `HttpHandler` for POSTs at `webhook.path` that verifies, decodes and runs `f` on each update, with the
          * client built from `config` provided to `f`. The client is built once, here, and closes with the enclosing `Scope`.
          */
        def handler[E](config: TelegramConfig, webhook: TelegramWebhookConfig)(
            f: Update => Unit < (Async & Abort[E] & Env[Telegram])
        )(using Frame): HttpHandler["body" ~ Span[Byte], Any, E] < (Async & Scope) =
            Telegram.client(config).map { telegram =>
                HttpRoute.postRaw(webhook.path).request(_.bodyBinary).handler[E] { req =>
                    verify(webhook, req.headers.get(SecretHeader)) match
                        case Result.Success(_) =>
                            // The callback runs after the decode result is matched, outside this Abort.run, so a failure of the caller's own is
                            // never taken for the module's decode failure.
                            Abort.run[TelegramWebhookDecodeFailure](decode(req.fields.body)).map {
                                case Result.Success(update)                     => Env.run(telegram)(f(update)).andThen(HttpResponse.ok)
                                case Result.Failure(e: TelegramDecodeException) =>
                                    Log.warn(s"Telegram webhook acknowledged a body that is not an ${e.part.show}").andThen(HttpResponse.ok)
                                case Result.Panic(ex) => Abort.panic(ex)
                            }
                        case Result.Failure(_: TelegramWebhookVerifyFailure) => HttpResponse.halt(HttpResponse.forbidden)
                        case Result.Panic(ex)                                => Abort.panic(ex)
                }
            }

        /** Checks the secret header against `webhook.secret`, in constant time over the UTF-8 bytes. */
        def verify(webhook: TelegramWebhookConfig, header: Maybe[String])(using Frame): Result[TelegramWebhookVerifyFailure, Unit] =
            header match
                case Absent         => Result.fail(TelegramSecretTokenMissingException())
                case Present(value) =>
                    if ConstantTime.isEqual(Utf8.encode(value), Utf8.encode(webhook.secret.value)) then Result.unit
                    else Result.fail(TelegramSecretTokenMismatchException())

        /** Decodes a webhook body into the update it carries. */
        def decode(body: Span[Byte])(using Frame): Update < Abort[TelegramWebhookDecodeFailure] =
            Json.decodeBytes[Update](body) match
                case Result.Success(update)  => update
                case Result.Failure(failure) =>
                    Abort.fail(TelegramDecodeException.ofDecoded(DecodeMethod, TelegramDecodeException.Part.Update, failure))
                case Result.Panic(ex) => Abort.panic(ex)
            end match
        end decode

        private inline val DecodeMethod = "webhook"

    end Webhook

end Telegram
