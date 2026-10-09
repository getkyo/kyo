package kyo

import kyo.internal.Platform
import scala.reflect.TypeTest

/** The module against a Bot API: the real one when `TELEGRAM_BOT_TOKEN` is set, a [[TelegramLiveServer]] container otherwise. The
  * variables are read through `kyo.System.env` so the suite runs on every platform. CI sets none, so it runs the container target on
  * every platform but Windows, whose container daemon cannot serve the Linux image.
  *
  * On the real Bot API, the leaves that send need a private chat with the bot, which the bot may message because a person pressed
  * Start in it. `TELEGRAM_CHAT_ID` names it. Without it, and with `TELEGRAM_INTERACTIVE` set, the suite finds the chat through
  * `Telegram.receive`: the first such leaf waits for a message a person sends the bot, and every later leaf uses that message's chat.
  * Without either variable, those leaves are cancelled with a message naming what is missing; the leaves that need no chat still run.
  * On the container, the leaves share one server, with the chat of its one person.
  *
  * The suite deletes every message it sends, and the person's messages it asked for. The leaves after the `Interactive` separator each
  * send the person an instruction and wait through `Telegram.receive` for the update it produces: on the real Bot API a person acts,
  * and those leaves run only when `TELEGRAM_INTERACTIVE` is set; on the container the suite injects what the person does.
  *
  * A leaf whose behaviour the emulator does not reproduce asserts the real Bot API's, and is cancelled on the container with the
  * difference. The failure leaves assert whole values, descriptions included: those descriptions are what the module matches to
  * choose a leaf, and the documentation does not list them.
  */
class TelegramLiveTest extends kyo.test.Test[Any]:

    // The real target shares one bot and one chat, and container leaves contend on one daemon, so leaves run one at a time across
    // every suite of the process.
    override def config = super.config.sequential.globallySequential(true)

    // deviation: a leaf waits on a Bot API over the network, a container starting, or a person acting, which no virtual clock can
    // stand in for. This per-leaf bound is the only real-clock limit in the module's tests; no assertion reads elapsed time.
    override def timeout: Duration = 10.minutes

    private enum Target derives CanEqual:
        case Real(config: TelegramConfig)
        case Emulator(server: TelegramLiveServer)

        def config(using Frame): TelegramConfig =
            this match
                case Real(config)     => config
                case Emulator(server) => server.config
    end Target

    /** What starting the container target can fail with. */
    private type Setup = ContainerException | FileSystemException | HttpException | DecodeException

    private case class Live(target: Target, config: TelegramConfig, chat: Telegram.Chat.Target, chatId: Telegram.ChatId)

    private def chatIdOf(text: String): Maybe[Long] =
        val digits = if text.startsWith("-") then text.drop(1) else text
        if digits.nonEmpty && digits.length <= 18 && digits.forall(c => c >= '0' && c <= '9') then Present(text.toLong) else Absent

    /** The Bot API a leaf runs against. `realOnly` is why the emulator cannot stand in for this leaf, which is then cancelled before a
      * container starts. A leaf that ends in error prints the container's log.
      */
    private def target(realOnly: Maybe[String])(using
        Frame
    ): Target < (Async & Scope & Abort[Setup]) =
        System.env[String]("TELEGRAM_BOT_TOKEN").map {
            case Present(t) => Target.Real(Telegram.Token.init(t).flatMap(TelegramConfig.init(_)).getOrThrow)
            case Absent     =>
                realOnly match
                    case Present(reason) => cancel(s"telegram-mock-ai differs from the Bot API: $reason; set TELEGRAM_BOT_TOKEN to run it")
                    case Absent if Platform.isWindows =>
                        cancel("telegram-mock-ai does not run on Windows: its container daemon cannot serve the Linux image")
                    case Absent =>
                        TelegramLiveServer.init.map { server =>
                            Scope.ensure {
                                case Present(error) =>
                                    server.postMortem.map(log =>
                                        Console.printLineErr(s"the leaf ended with $error; telegram-mock-ai log:\n$log")
                                    )
                                case Absent => Kyo.unit
                            }.andThen(Target.Emulator(server))
                        }
        }

    /** Runs `v` with a client on the bot. */
    private def withBot[A, S](realOnly: Maybe[String] = Absent)(v: TelegramConfig => A < (S & Env[Telegram]))(using
        Frame
    ): A < (S & Async & Scope & Abort[Setup]) =
        target(realOnly).map(t => Telegram.run(t.config)(v(t.config)))

    /** Runs `v` with a client on the bot and the chat it talks in. */
    private def inChat[A, S](realOnly: Maybe[String] = Absent)(v: Live => A < (S & Env[Telegram]))(using
        Frame
    ): A < (S & Async & Scope & Abort[TelegramReceiveFailure | Setup]) =
        target(realOnly).map { t =>
            chatOf(t).map { c =>
                val live = Live(t, t.config, Telegram.Chat.Target(Telegram.ChatId(c)), Telegram.ChatId(c))
                Telegram.run(live.config)(v(live))
            }
        }

    private def chatOf(target: Target)(using Frame): Long < (Async & Abort[TelegramReceiveFailure]) =
        target match
            case Target.Emulator(_)  => TelegramLiveServer.PersonId
            case Target.Real(config) => realChatOf(config)

    private def realChatOf(config: TelegramConfig)(using Frame): Long < (Async & Abort[TelegramReceiveFailure]) =
        System.env[String]("TELEGRAM_CHAT_ID").map(_.flatMap(chatIdOf)).map {
            case Present(c) => c
            case Absent     =>
                System.env[String]("TELEGRAM_INTERACTIVE").map {
                    case Absent =>
                        cancel("TELEGRAM_CHAT_ID or TELEGRAM_INTERACTIVE must be set: the live Telegram suite needs a chat with the bot")
                    case Present(_) =>
                        TelegramLiveTest.discovered.get.map {
                            case Present(c) => c
                            case Absent     =>
                                Console.printLine("kyo-telegram live: send any message to the bot in a private chat").andThen {
                                    nextMessage(config)(m => m.chat.kind == Telegram.Chat.Type.Private && m.from.exists(!_.isBot))
                                }.map(m => TelegramLiveTest.discovered.set(Present(m.chat.id.value)).andThen(m.chat.id.value))
                        }
                }
        }

    /** On the real Bot API, an interactive leaf waits for a person, so it runs only when one is there. On the shared emulator it starts
      * from an empty queue.
      */
    private def interactive(l: Live)(using Frame): Unit < (Async & Abort[HttpException | DecodeException]) =
        l.target match
            case Target.Emulator(server) => server.drain
            case Target.Real(_)          =>
                System.env[String]("TELEGRAM_INTERACTIVE").map {
                    case Present(_) => ()
                    case Absent     => cancel("TELEGRAM_INTERACTIVE not set: this leaf waits for a person to write to the bot")
                }

    /** What the person does after an instruction: on the real Bot API the instruction asks for it, on the container it is injected. */
    private enum Act derives CanEqual:
        case Says(text: String)
        case Replies(text: String)
        case Reacts(emoji: String)
        case Presses(data: String)
    end Act

    private case object Found

    /** `message_reaction` arrives only when named in `allowed_updates`, so the interactive leaves name every kind they wait for. */
    private val waitedFor: Chunk[Telegram.Update.Type] = Chunk(
        Telegram.Update.Type.Message,
        Telegram.Update.Type.EditedMessage,
        Telegram.Update.Type.CallbackQuery,
        Telegram.Update.Type.MessageReaction,
        Telegram.Update.Type.MyChatMember
    )

    /** Polls with `Telegram.receive` until an update of case `U` passes `accept`. The handler ends `receive` by failing,
      * so that update stays unconfirmed and arrives again on the next `receive`.
      */
    private def nextOf[U <: Telegram.Update](config: TelegramConfig)(accept: U => Boolean)(using
        test: TypeTest[Telegram.Update, U],
        frame: Frame
    ): U < (Async & Abort[TelegramReceiveFailure]) =
        AtomicRef.init(Maybe.empty[U]).map { selected =>
            val poll = config.copy(pollTimeout = 10.seconds, allowedUpdates = Present(waitedFor), retrySchedule = Schedule.done)
            Abort.run[Found.type](Telegram.run(poll)(Telegram.receive(update =>
                Maybe.fromOption(test.unapply(update)).filter(accept)
                    .fold(Kyo.unit)(found => selected.set(Present(found)).andThen(Abort.fail(Found)))
            ))).map {
                case Result.Failure(_) =>
                    selected.get.map {
                        case Present(found) => found
                        case Absent         =>
                            Abort.panic(new IllegalStateException("receive ended by the handler's failure with nothing selected"))
                    }
                case Result.Success(()) => Abort.panic(new IllegalStateException("receive completed without a failure"))
                case Result.Panic(e)    => Abort.panic(e)
            }
        }

    private def nextMessage(config: TelegramConfig)(accept: Telegram.Message => Boolean)(using
        Frame
    ): Telegram.Message < (Async & Abort[TelegramReceiveFailure]) =
        nextOf[Telegram.Update.Message](config)(u => accept(u.message)).map(_.message)

    private def fromPerson(l: Live, date: Instant)(m: Telegram.Message): Boolean =
        m.chat.id == l.chatId && m.from.exists(!_.isBot) && m.date >= date

    /** Sends `text` as an instruction to the person, answering the message sent. */
    private def ask(l: Live, text: String, options: Telegram.SendOptions = Telegram.SendOptions.default)(using
        Frame
    ): Telegram.Message < (Async & Abort[TelegramSendFailure] & Env[Telegram]) =
        Telegram.send(l.chat, Telegram.Content.text(s"kyo-telegram live: $text"), options)

    /** Sends `text` as an instruction to the person, who then does `act`, answering the instruction sent. */
    private def ask(l: Live, text: String, act: Act, options: Telegram.SendOptions)(using
        Frame
    ): Telegram.Message < (Async & Abort[TelegramSendFailure | HttpException | DecodeException] & Env[Telegram]) =
        ask(l, text, options).map { prompt =>
            val acted = l.target match
                case Target.Real(_)          => Kyo.unit
                case Target.Emulator(server) =>
                    act match
                        case Act.Says(text)    => server.says(text).unit
                        case Act.Replies(text) => server.replies(prompt, text)
                        case Act.Reacts(emoji) => server.reacts(prompt, emoji)
                        case Act.Presses(data) => server.presses(prompt, data)
            acted.andThen(prompt)
        }

    private def ask(l: Live, text: String, act: Act)(using
        Frame
    ): Telegram.Message < (Async & Abort[TelegramSendFailure | HttpException | DecodeException] & Env[Telegram]) =
        ask(l, text, act, Telegram.SendOptions.default)

    private def cleanUp(l: Live, messages: Telegram.MessageId*)(using
        Frame
    ): Unit < (Async & Abort[TelegramDeleteFailure] & Env[Telegram]) =
        Kyo.foreachDiscard(messages)(Telegram.delete(l.chat, _))

    private val NoStoredFiles = "it stores no uploaded file: getFile answers no file_size, and the download is a placeholder"

    /** A 1x1 PNG. */
    private val png: Span[Byte] = Span.from(Array(
        0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
        0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, 0xc4, 0x89, 0x00, 0x00, 0x00, 0x0d, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9c, 0x63,
        0xf8, 0xcf, 0xc0, 0xf0, 0x1f, 0x00, 0x05, 0x00, 0x01, 0xff, 0x89, 0x99, 0x3d, 0x1d, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
        0xae, 0x42, 0x60, 0x82
    ).map(_.toByte))

    // --- Token only ---

    "me answers the bot" in {
        withBot() { config =>
            val botId = config.token.value.takeWhile(_ != ':').toLong
            Telegram.me.map(me => assert((me.id, me.isBot) == (Telegram.UserId(botId), true)))
        }
    }

    "sending to a chat that does not exist is TelegramChatNotFoundException" in {
        withBot(realOnly = Present("it creates any chat a message is sent to")) { _ =>
            Abort.run[TelegramSendFailure](Telegram.send(
                Telegram.Chat.Target(Telegram.ChatId(-1000000000001L)),
                Telegram.Content.text("x")
            )).map {
                result => assert(result == Result.fail(TelegramChatNotFoundException("sendMessage", "Bad Request: chat not found")))
            }
        }
    }

    "commands are set, and no webhook is set while polling works" in {
        withBot() { _ =>
            Telegram.setCommands(Telegram.Command.Menu.init(
                Telegram.Command.init("start", "Start the bot").getOrThrow,
                Telegram.Command.init("help", "What the bot does").getOrThrow
            ).getOrThrow)
                .andThen(Telegram.deleteWebhook())
                .andThen(Telegram.webhookInfo)
                .map(info => assert(info.url == Absent, s"got: $info"))
        }
    }

    "a token Telegram does not know is TelegramUnauthorizedException, and no failure holds the token" in {
        val secretPart = Seq("kyoLiveTest", "NotAToken").mkString
        withBot(realOnly = Present("it registers every token it is called with")) { _ =>
            Telegram.run(
                Telegram.Token.init(s"1:$secretPart").flatMap(TelegramConfig.init(_)).getOrThrow
            )(Abort.run[TelegramMeFailure](Telegram.me)).map { result =>
                assert(result == Result.fail(TelegramUnauthorizedException("getMe", "Unauthorized")))
                assert(!result.failure.fold("")(TelegramTest.rendered).contains(secretPart))
            }
        }
    }

    // --- In the chat ---

    "a text message is sent, edited, reacted to and deleted" in {
        inChat() { l =>
            for
                sent <- Telegram.send(l.chat, Telegram.Content.text("kyo-telegram live: send"))
                id = sent.id
                _      <- Telegram.sendChatAction(l.chat, Telegram.ChatAction.Typing)
                edited <- Telegram.edit(l.chat, id, Telegram.Edit.Text(Telegram.Text("kyo-telegram live: edited")))
                _      <- Telegram.setReaction(l.chat, id, Seq(Telegram.Reaction.Emoji("👍")))
                _      <- Telegram.delete(l.chat, id)
            yield assert(
                (sent.chat.id, sent.text, edited.id, edited.text) ==
                    (l.chatId, Present("kyo-telegram live: send"), id, Present("kyo-telegram live: edited"))
            )
        }
    }

    "Telegram accepts the rendered MarkdownV2 and HTML of text holding every reserved character, and shows it as written" in {
        import Telegram.Markup.*
        val raw    = "_*[]()~`>#+-=|{}.!\\ <&>"
        val markup = of(
            Bold(Text("bold ")),
            Text(raw),
            Code(" `code` "),
            Link(Text(" link."), Telegram.Url.init("https://getkyo.io/a_(b)").getOrThrow)
        )
        inChat(realOnly = Present("it ignores parse_mode and answers the text as sent")) { l =>
            Kyo.foreach(Chunk(Telegram.Text.MarkdownV2(markup), Telegram.Text.Html(markup))) { text =>
                Telegram.send(l.chat, Telegram.Content.Text(text, linkPreview = false)).map { m =>
                    Telegram.delete(l.chat, m.id).andThen(m.text)
                }
            }.map { texts =>
                assert(texts == Chunk.fill(2)(Present(s"bold $raw `code`  link.")), s"got: $texts")
            }
        }
    }

    "an edit that changes nothing is TelegramMessageNotModifiedException" in {
        inChat(realOnly = Present("it accepts an edit that changes nothing")) { l =>
            Telegram.send(l.chat, Telegram.Content.text("kyo-telegram live: same")).map { sent =>
                Abort.run[TelegramEditFailure](Telegram.edit(l.chat, sent.id, Telegram.Edit.Text(Telegram.Text("kyo-telegram live: same"))))
                    .map(result => Telegram.delete(l.chat, sent.id).andThen(result))
            }.map { result =>
                assert(result == Result.fail(TelegramMessageNotModifiedException(
                    "editMessageText",
                    "Bad Request: message is not modified: specified new message content and reply markup are exactly the same as a current content and reply markup of the message"
                )))
            }
        }
    }

    "deleting a message twice is TelegramMessageNotFoundException the second time" in {
        inChat(realOnly = Present("it answers \"message not found\" where the Bot API answers \"message to delete not found\"")) { l =>
            Telegram.send(l.chat, Telegram.Content.text("kyo-telegram live: delete twice")).map { sent =>
                Telegram.delete(l.chat, sent.id).andThen(Abort.run[TelegramDeleteFailure](Telegram.delete(l.chat, sent.id)))
            }.map { result =>
                assert(result ==
                    Result.fail(TelegramMessageNotFoundException("deleteMessage", "Bad Request: message to delete not found")))
            }
        }
    }

    "a photo is uploaded as multipart, and its file is looked up and downloaded" in {
        inChat(realOnly = Present(NoStoredFiles)) { l =>
            for
                sent <- Telegram.send(
                    l.chat,
                    Telegram.Content.Photo(Telegram.InputFile.Upload("dot.png", png, Present("image/png")), Present(Telegram.Text("dot")))
                )
                sizes <- sent.content match
                    case Telegram.Message.Content.Photo(sizes, _) => Abort.get(sizes.lastMaybe)
                    case other                                    => Abort.fail(s"not a photo: $other")
                file  <- Telegram.file(sizes.fileId)
                bytes <- Telegram.download(file)
                _     <- Telegram.delete(l.chat, sent.id)
            yield assert(
                (sent.caption.map(_.text), file.id, file.size) == (Present("dot"), sizes.fileId, Present(bytes.size.bytes)),
                s"file: $file, downloaded ${bytes.size} bytes"
            )
        }
    }

    "a message with an inline keyboard is accepted" in {
        inChat() { l =>
            val keyboard = Telegram.Keyboard.inline(
                Seq(
                    Telegram.Keyboard.InlineButton.callback("Yes", "yes").getOrThrow,
                    Telegram.Keyboard.InlineButton.Url("Docs", Telegram.Url.init("https://getkyo.io").getOrThrow)
                )
            )
            Telegram.send(l.chat, Telegram.Content.text("kyo-telegram live: keyboard"), Telegram.SendOptions(keyboard = Present(keyboard)))
                .map(sent => Telegram.delete(l.chat, sent.id).andThen(sent.chat.id))
                .map(chat => assert(chat == l.chatId))
        }
    }

    // --- Interactive: a person acts in the chat, and run delivers what they did ---

    "run delivers a text a person sends after the prompt, and a handler's failure ends run with it" in {
        inChat() { l =>
            interactive(l).andThen {
                for
                    prompt  <- ask(l, "send me the word kyo", Act.Says("kyo"))
                    message <- nextMessage(l.config)(fromPerson(l, prompt.date))
                    _       <- cleanUp(l, prompt.id, message.id)
                yield assert((message.chat.id, message.from.map(_.isBot), message.text) == (l.chatId, Present(false), Present("kyo")))
            }
        }
    }

    "a reply arrives with the message it replies to" in {
        inChat() { l =>
            interactive(l).andThen {
                for
                    prompt <- ask(l, "reply to this message with the word yes", Act.Replies("yes"))
                    reply  <- nextMessage(l.config)(m => fromPerson(l, prompt.date)(m) && m.replyTo.nonEmpty)
                    _      <- cleanUp(l, prompt.id, reply.id)
                yield assert((reply.text, reply.replyTo.map(_.id)) == (Present("yes"), Present(prompt.id)))
            }
        }
    }

    "an edit a person makes arrives as EditedMessage with its new text and an edit date" in {
        inChat(realOnly = Present("its message model has no edit_date, so an edit it delivers carries none")) { l =>
            interactive(l).andThen {
                for
                    prompt <- ask(l, "send the word before, then edit that message to say after")
                    edited <- nextOf[Telegram.Update.EditedMessage](l.config)(u => fromPerson(l, prompt.date)(u.message)).map(_.message)
                    _      <- cleanUp(l, prompt.id, edited.id)
                yield assert((edited.text, edited.editDate.isDefined) == (Present("after"), true))
            }
        }
    }

    "a reaction a person sets on the bot's message arrives as MessageReaction with the emoji" in {
        inChat() { l =>
            interactive(l).andThen {
                for
                    prompt   <- ask(l, "react to this message with ❤ (double-tap it)", Act.Reacts("❤"))
                    reaction <- nextOf[Telegram.Update.MessageReaction](l.config)(_.update.message == prompt.id).map(_.update)
                    _        <- cleanUp(l, prompt.id)
                    got = (reaction.chat.id, reaction.oldReaction, reaction.newReaction, reaction.user.map(_.isBot))
                yield assert(got == (l.chatId, Chunk.empty, Chunk(Telegram.Reaction.Emoji("❤")), Present(false)), s"got: $got")
            }
        }
    }

    "a button press arrives as CallbackQuery with its data, and is answered and its message edited" in {
        val keyboard = Telegram.Keyboard.inline(
            Seq(
                Telegram.Keyboard.InlineButton.callback("Yes", "live:yes").getOrThrow,
                Telegram.Keyboard.InlineButton.callback("No", "live:no").getOrThrow
            )
        )
        def under(id: Telegram.MessageId)(q: Telegram.CallbackQuery): Boolean =
            q.message match
                case Present(Telegram.CallbackQuery.Source.Accessible(m))      => m.id == id
                case Present(Telegram.CallbackQuery.Source.Inaccessible(_, _)) => false
                case Absent                                                    => false
        inChat() { l =>
            interactive(l).andThen {
                for
                    prompt <- ask(l, "press Yes", Act.Presses("live:yes"), Telegram.SendOptions(keyboard = Present(keyboard)))
                    id = prompt.id
                    query  <- nextOf[Telegram.Update.CallbackQuery](l.config)(u => under(id)(u.query)).map(_.query)
                    edited <-
                        Telegram.answerCallback(
                            query.id,
                            Telegram.CallbackAnswer.init(text = Present("kyo-telegram live: recorded")).getOrThrow
                        ).andThen(
                            Telegram.edit(l.chat, id, Telegram.Edit.Text(Telegram.Text("kyo-telegram live: you pressed Yes")))
                        )
                    _ <- cleanUp(l, prompt.id)
                yield assert(
                    (query.data, query.from.isBot, query.chat.map(_.id), edited.text) ==
                        (Present("live:yes"), false, Present(l.chatId), Present("kyo-telegram live: you pressed Yes"))
                )
            }
        }
    }

    "a photo a person sends arrives as Photo, and its largest size is looked up and downloaded" in {
        def largestOf(m: Telegram.Message): Maybe[Telegram.Media.PhotoSize] =
            m.content match
                case Telegram.Message.Content.Photo(sizes, _) => sizes.lastMaybe
                case Telegram.Message.Content.Text(_, _) | Telegram.Message.Content.Document(_, _) | Telegram.Message.Content.Audio(_, _) |
                    Telegram.Message.Content.Video(_, _) | Telegram.Message.Content.Voice(_, _) | Telegram.Message.Content.Location(_) |
                    Telegram.Message.Content.Unknown(_) => Absent
        inChat(realOnly = Present(NoStoredFiles)) { l =>
            interactive(l).andThen {
                for
                    prompt  <- ask(l, "send me a photo")
                    message <- nextMessage(l.config)(m => fromPerson(l, prompt.date)(m) && largestOf(m).nonEmpty)
                    largest <- Abort.get(largestOf(message))
                    file    <- Telegram.file(largest.fileId)
                    bytes   <- Telegram.download(file)
                    _       <- cleanUp(l, prompt.id, message.id)
                yield assert(
                    (file.id, file.uniqueId, file.size, largest.fileSize) ==
                        (largest.fileId, largest.fileUniqueId, Present(bytes.size.bytes), Present(bytes.size.bytes))
                )
            }
        }
    }

    "blocking the bot arrives as MyChatMember Kicked and sends fail Forbidden, and unblocking arrives as Member" in {
        inChat(realOnly = Present("it keeps no blocked state, so a send to a chat that blocked the bot succeeds")) { l =>
            interactive(l).andThen {
                for
                    prompt <- ask(l, "block this bot, wait about 10 seconds, then unblock it")
                    kicked <- nextOf[Telegram.Update.MyChatMember](l.config)(u =>
                        u.update.chat.id == l.chatId && u.update.date >= prompt.date &&
                            u.update.newChatMember.status == Telegram.ChatMember.Status.Kicked
                    ).map(_.update)
                    refused <-
                        Abort.run[TelegramSendFailure](Telegram.send(l.chat, Telegram.Content.text("kyo-telegram live: while blocked")))
                    back <- nextOf[Telegram.Update.MyChatMember](l.config)(u =>
                        u.update.chat.id == l.chatId && u.update.date >= kicked.date &&
                            u.update.newChatMember.status == Telegram.ChatMember.Status.Member
                    ).map(_.update)
                    _ <- cleanUp(l, prompt.id)
                yield assert(
                    (kicked.oldChatMember.status, kicked.newChatMember.user.isBot, back.oldChatMember.status, refused.map(_.id)) ==
                        (
                            Telegram.ChatMember.Status.Member,
                            true,
                            Telegram.ChatMember.Status.Kicked,
                            Result.fail(TelegramForbiddenException("sendMessage", "Forbidden: bot was blocked by the user"))
                        )
                )
            }
        }
    }

end TelegramLiveTest

private object TelegramLiveTest:

    // Unsafe: kyo-test builds a fresh suite instance for every leaf, so the chat found once lives on the companion, which no leaf's
    // effects can create earlier. An interactive leaf confirms the messages it skips, so a later lookup would find nothing pending.
    val discovered: AtomicRef[Maybe[Long]] =
        import AllowUnsafe.embrace.danger
        AtomicRef.Unsafe.init(Maybe.empty[Long]).safe

end TelegramLiveTest
