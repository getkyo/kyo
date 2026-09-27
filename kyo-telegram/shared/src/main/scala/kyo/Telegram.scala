package kyo

import kyo.internal.telegram.BotApi
import kyo.internal.telegram.BotApi.Payload
import kyo.internal.telegram.Poller
import kyo.internal.telegram.WireCodec

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
  * which needs no public endpoint; [[kyo.TelegramWebhook]] serves Telegram's POSTs on the bot's own
  * `HttpServer`. `setWebhook`, `deleteWebhook` and `getWebhookInfo` switch between them: polling fails
  * with [[kyo.TelegramConflictException]] while a webhook is set.
  *
  * IMPORTANT: Telegram puts the bot token in every request path. The client's HTTP client is its own,
  * with a complete configuration: no redirect is followed, no filter runs, TLS is verified, and nothing
  * of the caller's kyo-http configuration applies. No failure of this module holds the token.
  *
  * @see
  *   [[kyo.TelegramConfig]] the config
  * @see
  *   [[kyo.TelegramUpdate]] what arrives
  * @see
  *   [[kyo.TelegramWebhook]] receiving by webhook
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
        handler: TelegramUpdate => Unit < (Async & Abort[E] & Env[Telegram] & S)
    )(using Frame): Unit < (Async & Abort[TelegramRunFailure | E] & S) =
        Scope.run {
            client(config).map { telegram =>
                // One poll is in flight at a time, but kyo-net's connection pool refuses fewer than 2 connections per host.
                HttpClient.init(maxConnectionsPerHost = 2, defaultTlsConfig = HttpTlsConfig.default).map { poll =>
                    Env.run(telegram)(Poller.run(telegram, poll)(handler))
                }
            }
        }

    /** Builds a client from `config` for the duration of `v`, closing its HTTP client afterwards. */
    def let[A, S](config: TelegramConfig)(v: A < (S & Env[Telegram]))(using Frame): A < (S & Async) =
        Scope.run(client(config).map(telegram => Env.run(telegram)(v)))

    private[kyo] def client(config: TelegramConfig)(using Frame): Telegram < (Async & Scope) =
        HttpClient.init(defaultTlsConfig = HttpTlsConfig.default).map(new Telegram(config, _))

    // --- Messages ---

    /** Sends `content` to `chat` and answers the message Telegram created. */
    def send(chat: TelegramChat.Target, content: TelegramContent, options: TelegramSendOptions = TelegramSendOptions.default)(using
        Frame
    ): TelegramMessage < (Async & Abort[TelegramSendFailure] & Env[Telegram]) =
        import TelegramContent.*
        def media(field: String, file: TelegramInputFile, caption: Maybe[TelegramText]): Payload.Fields =
            val (params, uploads) = fileParam(field, file)
            Payload.Fields(params ++ caption.fold(Chunk.empty)(WireCodec.text("caption", "caption_entities", _)), uploads)
        val call: (String, Payload.Fields) = content match
            case Text(text, linkPreview) =>
                ("sendMessage", Payload.Fields(WireCodec.text("text", "entities", text) ++ WireCodec.linkPreview(linkPreview)))
            case Photo(file, caption)          => ("sendPhoto", media("photo", file, caption))
            case Document(file, caption)       => ("sendDocument", media("document", file, caption))
            case Audio(file, caption)          => ("sendAudio", media("audio", file, caption))
            case Video(file, caption)          => ("sendVideo", media("video", file, caption))
            case Voice(file, caption)          => ("sendVoice", media("voice", file, caption))
            case Location(latitude, longitude) =>
                (
                    "sendLocation",
                    Payload.Fields(Chunk(
                        "latitude"  -> Structure.Value.Decimal(latitude),
                        "longitude" -> Structure.Value.Decimal(longitude)
                    ))
                )
        val (method, payload) = call
        val fields            = Payload.Fields(
            Chunk("chat_id" -> WireCodec.target(chat)) ++ payload.params ++ WireCodec.sendOptions(options),
            payload.uploads
        )
        BotApi.call[TelegramMessage, TelegramSendFailure](method, fields)(WireCodec.message)
    end send

    /** Changes the text, the caption or the inline keyboard of a message the bot sent, and answers the edited message. */
    def edit(chat: TelegramChat.Target, message: TelegramId.MessageId, change: TelegramEdit)(using
        Frame
    ): TelegramMessage < (Async & Abort[TelegramEditFailure] & Env[Telegram]) =
        def keyboard(k: Maybe[TelegramKeyboard.Inline]): WireCodec.Params =
            k.fold(Chunk.empty)(kb => Chunk("reply_markup" -> WireCodec.keyboard(kb)))
        val (method, params) = change match
            case TelegramEdit.Text(text, kb, linkPreview) =>
                ("editMessageText", WireCodec.text("text", "entities", text) ++ WireCodec.linkPreview(linkPreview) ++ keyboard(kb))
            case TelegramEdit.Caption(caption, kb) =>
                ("editMessageCaption", caption.fold(Chunk.empty)(WireCodec.text("caption", "caption_entities", _)) ++ keyboard(kb))
            case TelegramEdit.Keyboard(kb) => ("editMessageReplyMarkup", keyboard(kb))
        BotApi.call[TelegramMessage, TelegramEditFailure](method, Payload.Fields(WireCodec.messageRef(chat, message) ++ params))(
            WireCodec.message
        )
    end edit

    /** Deletes a message. Telegram allows it within 48 hours of sending, with the rights its documentation lists. */
    def delete(chat: TelegramChat.Target, message: TelegramId.MessageId)(using
        Frame
    ): Unit < (Async & Abort[TelegramDeleteFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramDeleteFailure]("deleteMessage", Payload.Fields(WireCodec.messageRef(chat, message)))

    /** Answers a callback query, which stops the progress indicator on the user's button. */
    def answerCallback(query: TelegramId.CallbackQueryId, answer: TelegramCallbackAnswer = TelegramCallbackAnswer.empty)(using
        Frame
    ): Unit < (Async & Abort[TelegramAnswerCallbackFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramAnswerCallbackFailure](
            "answerCallbackQuery",
            Payload.Fields(
                Chunk("callback_query_id" -> WireCodec.str(query.value)) ++
                    answer.text.fold(Chunk.empty)(t => Chunk("text" -> WireCodec.str(t))) ++
                    (if answer.showAlert then Chunk("show_alert" -> WireCodec.bool(true)) else Chunk.empty) ++
                    answer.url.fold(Chunk.empty)(u => Chunk("url" -> WireCodec.str(u.value))) ++
                    answer.cacheTime.fold(Chunk.empty)(d => Chunk("cache_time" -> WireCodec.long(d.toSeconds)))
            )
        )

    /** Shows `action` (such as typing) in `chat` for up to 5 seconds or until the bot's next message. */
    def sendChatAction(chat: TelegramChat.Target, action: TelegramChatAction, thread: Maybe[TelegramId.MessageThreadId] = Absent)(using
        Frame
    ): Unit < (Async & Abort[TelegramSendChatActionFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramSendChatActionFailure](
            "sendChatAction",
            Payload.Fields(
                Chunk("chat_id" -> WireCodec.target(chat), "action" -> WireCodec.str(WireCodec.chatActionName(action))) ++
                    thread.fold(Chunk.empty)(t => Chunk("message_thread_id" -> WireCodec.long(t.value.toLong)))
            )
        )

    /** Sets the bot's reactions on a message; an empty list removes them. `big` plays the reaction's animation large. */
    def setReaction(
        chat: TelegramChat.Target,
        message: TelegramId.MessageId,
        reactions: Seq[TelegramReaction.Sendable],
        big: Boolean = false
    )(using Frame): Unit < (Async & Abort[TelegramSetReactionFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramSetReactionFailure](
            "setMessageReaction",
            Payload.Fields(
                WireCodec.messageRef(chat, message) ++
                    Chunk("reaction" -> WireCodec.seq(
                        (Chunk.from(reactions): Chunk[TelegramReaction.Sendable]).map(r => WireCodec.record(WireCodec.reactionValue(r)))
                    )) ++
                    (if big then Chunk("is_big" -> WireCodec.bool(true)) else Chunk.empty)
            )
        )

    // --- Bot ---

    /** The bot itself, which also checks that the token works. */
    def getMe(using Frame): TelegramUser < (Async & Abort[TelegramGetMeFailure] & Env[Telegram]) =
        BotApi.call[TelegramUser, TelegramGetMeFailure]("getMe", Payload.Fields(Chunk.empty))(WireCodec.userOf)

    /** Sets the bot's command menu for `scope`, in `language` or for every language without a list of its own. */
    def setCommands(
        commands: Seq[TelegramCommand],
        scope: TelegramCommand.Scope = TelegramCommand.Scope.Default,
        language: Maybe[String] = Absent
    )(using Frame): Unit < (Async & Abort[TelegramSetCommandsFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramSetCommandsFailure](
            "setMyCommands",
            Payload.Fields(
                Chunk(
                    "commands" -> WireCodec.seq(Chunk.from(commands).map(c =>
                        WireCodec.record(Chunk("command" -> WireCodec.str(c.name), "description" -> WireCodec.str(c.description)))
                    )),
                    "scope" -> WireCodec.commandScope(scope)
                ) ++ language.fold(Chunk.empty)(l => Chunk("language_code" -> WireCodec.str(l)))
            )
        )

    // --- Files ---

    /** Looks up a file's download path. */
    def getFile(file: TelegramId.FileId)(using Frame): TelegramFile < (Async & Abort[TelegramGetFileFailure] & Env[Telegram]) =
        BotApi.call[TelegramFile, TelegramGetFileFailure]("getFile", Payload.Fields(Chunk("file_id" -> WireCodec.str(file.value))))(
            WireCodec.file
        )

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
    def download(file: TelegramFile)(using Frame): Span[Byte] < (Async & Abort[TelegramDownloadFailure] & Env[Telegram]) =
        BotApi.download(file)

    // --- Webhook ---

    /** Registers `url` to receive updates by POST, with Telegram sending `webhook.secret` in every request's
      * `X-Telegram-Bot-Api-Secret-Token` header. Long polling fails while a webhook is set. A `url` that is
      * not an absolute http or https URL on a host fails with `TelegramRefusedUrlException`, and nothing is sent.
      */
    def setWebhook(url: HttpUrl, webhook: TelegramWebhookConfig, options: TelegramWebhookOptions = TelegramWebhookOptions.default)(using
        Frame
    ): Unit < (Async & Abort[TelegramSetWebhookFailure] & Env[Telegram]) =
        if TelegramConfig.absoluteProblemOf(url).nonEmpty then Abort.fail(TelegramRefusedUrlException("setWebhook"))
        else
            BotApi.acknowledged[TelegramSetWebhookFailure](
                "setWebhook",
                Payload.Fields(
                    Chunk("url" -> WireCodec.str(url.full), "secret_token" -> WireCodec.str(webhook.secret.value)) ++
                        options.allowedUpdates.fold(Chunk.empty)(t => Chunk("allowed_updates" -> WireCodec.allowedUpdates(t))) ++
                        options.maxConnections.fold(Chunk.empty)(m => Chunk("max_connections" -> WireCodec.long(m.toLong))) ++
                        (if options.dropPendingUpdates then Chunk("drop_pending_updates" -> WireCodec.bool(true)) else Chunk.empty) ++
                        options.ipAddress.fold(Chunk.empty)(ip => Chunk("ip_address" -> WireCodec.str(ip))),
                    secrets = Chunk(webhook.secret.value)
                )
            )

    /** Removes the webhook, so long polling works again; `dropPendingUpdates` discards the waiting updates. */
    def deleteWebhook(dropPendingUpdates: Boolean = false)(using
        Frame
    ): Unit < (Async & Abort[TelegramDeleteWebhookFailure] & Env[Telegram]) =
        BotApi.acknowledged[TelegramDeleteWebhookFailure](
            "deleteWebhook",
            Payload.Fields(if dropPendingUpdates then Chunk("drop_pending_updates" -> WireCodec.bool(true)) else Chunk.empty)
        )

    /** The webhook's state, `url` absent when none is set. */
    def getWebhookInfo(using Frame): TelegramWebhookInfo < (Async & Abort[TelegramGetWebhookInfoFailure] & Env[Telegram]) =
        BotApi.call[TelegramWebhookInfo, TelegramGetWebhookInfoFailure]("getWebhookInfo", Payload.Fields(Chunk.empty))(
            WireCodec.webhookInfo
        )

    // --- Escape hatch ---

    /** Calls any Bot API method: `payload` is encoded as the JSON body, and the answer's `result` decoded as `Out`. */
    def custom[In: Schema, Out: Schema](method: TelegramMethod, payload: In)(using
        Frame
    ): Out < (Async & Abort[TelegramCustomFailure] & Env[Telegram]) =
        BotApi.call[Out, TelegramCustomFailure](method.value, Payload.Json(Json.encode(payload)))(Structure.decode[Out](_))

    private def fileParam(field: String, file: TelegramInputFile): (WireCodec.Params, Chunk[(String, TelegramInputFile.Upload)]) =
        file match
            case TelegramInputFile.Id(id)         => (Chunk(field -> WireCodec.str(id.value)), Chunk.empty)
            case TelegramInputFile.Url(url)       => (Chunk(field -> WireCodec.str(url.full)), Chunk.empty)
            case upload: TelegramInputFile.Upload => (Chunk.empty, Chunk(field -> upload))

end Telegram
