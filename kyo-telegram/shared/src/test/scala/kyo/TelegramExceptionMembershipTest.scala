package kyo

import TelegramExceptionMembershipTest.*

/** Which leaf each public operation can fail with. One table, `leaves`, states it, with one row per
  * leaf of the sealed hierarchy, and every (operation, leaf) pair it admits is produced through the
  * operation's real path.
  */
class TelegramExceptionMembershipTest extends kyo.test.Test[Any]:

    // --- Every pair the table admits, through the real path ---

    "each operation produces every leaf of its row through its real path" in {
        TelegramTest.withLocal { local =>
            withClosingPeer { closing =>
                val pairs =
                    for
                        op   <- Chunk.from(operations.toSeq.sorted)
                        leaf <- leaves.filter(_.ops.contains(op))
                    yield (op, leaf.kind)
                Kyo.foreach(pairs)((op, kind) => produce(local, closing, op, kind).map(r => (op, kind) -> r.mapFailure(observed))).map {
                    results =>
                        val expected = pairs.map((op, kind) => (op, kind) -> Result.fail(observed(expectedLeaf(op, kind, closing))))
                        val wrong    = results.zip(expected).filter((r, e) => r != e).map(_._1)
                        assert(
                            wrong == Chunk.empty,
                            s"got: ${wrong.map((k, r) => k -> r.fold(_ => "succeeded", brief, p => s"panic $p"))}"
                        )
                }
            }
        }
    }

    "an answer whose leaf is outside an operation's row is TelegramOtherApiException there" in {
        TelegramTest.withLocal { local =>
            withClosingPeer { closing =>
                val outside =
                    for
                        op   <- Chunk.from((api - "receive").toSeq.sorted)
                        kind <- answerKinds.filter(k => !leaves.exists(l => l.kind == k && l.ops.contains(op)))
                    yield (op, kind)
                Kyo.foreach(outside)((op, kind) => produce(local, closing, op, kind).map(r => (op, kind) -> r.mapFailure(observed))).map {
                    results =>
                        val expected = outside.map { (op, kind) =>
                            val (code, description) = answerOf(kind)
                            (op, kind) -> Result.fail(observed(TelegramOtherApiException(methodOf(op), code, description)))
                        }
                        assert(results == expected)
                }
            }
        }
    }

    "a failure holds no part of the token, in its message, its fields or its cause" in {
        TelegramTest.withLocal { local =>
            withClosingPeer { closing =>
                val secret = local.token.value.substring(local.token.value.indexOf(':') + 1)
                val pairs  =
                    for
                        op   <- Chunk.from(operations.toSeq.sorted)
                        leaf <- leaves.filter(_.ops.contains(op))
                    yield (op, leaf.kind)
                Kyo.foreach(pairs)((op, kind) => produce(local, closing, op, kind)).map { results =>
                    val rendered = results.flatMap(_.failure.toList).map(TelegramTest.rendered)
                    assert(rendered.size == pairs.size)
                    assert(rendered.filter(_.contains(secret)) == Chunk.empty)
                }
            }
        }
    }

    "a connection that cannot be made keeps kyo-net's cause" in {
        TelegramTest.withLocal { local =>
            Telegram.run(local.config.copy(baseUrl = local.base.copy(port = 1)))(Abort.run[TelegramMeFailure](Telegram.me)).map {
                result =>
                    assert(result ==
                        Result.fail(TelegramTransportException("getMe", TelegramTransportException.Kind.Connect, "127.0.0.1", 1, Absent)()))
                    val cause = result.failure.flatMap {
                        case t: TelegramTransportException => t.cause
                        case _: (TelegramUnexpectedStatusException | TelegramDecodeException | TelegramRateLimitException |
                                TelegramUnauthorizedException | TelegramOtherApiException) => Absent
                    }
                    assert(cause.map {
                        case c: kyo.net.NetConnectException => Present((c.host, c.port))
                        case _: (kyo.net.NetConnectionException | kyo.net.NetTlsException | kyo.net.NetCapabilityException |
                                kyo.net.NetConfigException) => Absent
                    } == Present(Present(("127.0.0.1", 1))))
            }
        }
    }

end TelegramExceptionMembershipTest

private object TelegramExceptionMembershipTest:

    // --- Every leaf of the hierarchy, named once ---

    enum Kind derives CanEqual:
        case Transport, RefusedUrl, UnexpectedStatus, Decode, NoFilePath, RateLimit
        case Migrated, Unauthorized, Forbidden, Conflict, ChatNotFound, MessageNotFound, MessageNotModified, FileTooBig, OtherApi
        case SecretTokenMissing, SecretTokenMismatch
        case InvalidToken, InvalidConfig, InvalidCallbackData, InvalidCommand, InvalidMethod, InvalidUrl
        case InvalidWebhookOptions, InvalidWebhookConfig, InvalidCallbackAnswer
    end Kind

    // --- How a real path produces each leaf ---

    import TelegramTest.Local
    import TelegramTest.Reply

    /** What a produced failure is compared by: the leaf itself, or for a decode failure its method and
      * part, since which kyo-schema failure a malformed body meets is kyo-schema's to choose.
      */
    enum Observed derives CanEqual:
        case Leaf(leaf: TelegramException)
        case DecodeOf(method: String, part: TelegramDecodeException.Part)

    /** A one-line rendering of an observed failure, for an assertion's clue. */
    def brief(o: Observed): String =
        o match
            case Observed.Leaf(leaf)             => leaf.toString
            case Observed.DecodeOf(method, part) => s"DecodeOf($method, $part)"

    def observed(e: TelegramException): Observed =
        e match
            case d: TelegramDecodeException => Observed.DecodeOf(d.method, d.part)
            case other: (TelegramTransportException | TelegramRefusedUrlException | TelegramUnexpectedStatusException |
                    TelegramNoFilePathException | TelegramRateLimitException | TelegramApiException |
                    TelegramSecretTokenMissingException | TelegramSecretTokenMismatchException | TelegramInvalidTokenException |
                    TelegramInvalidConfigException | TelegramInvalidCallbackDataException |
                    TelegramInvalidCommandException | TelegramInvalidMethodException | TelegramInvalidUrlException |
                    TelegramInvalidWebhookOptionsException | TelegramInvalidWebhookConfigException |
                    TelegramInvalidCallbackAnswerException) => Observed.Leaf(other)

    val methodOf: Map[String, String] = Map(
        "receive"        -> "getUpdates",
        "me"             -> "getMe",
        "send"           -> "sendMessage",
        "edit"           -> "editMessageText",
        "delete"         -> "deleteMessage",
        "answerCallback" -> "answerCallbackQuery",
        "sendChatAction" -> "sendChatAction",
        "setReaction"    -> "setMessageReaction",
        "setCommands"    -> "setMyCommands",
        "file"           -> "getFile",
        "download"       -> "download",
        "setWebhook"     -> "setWebhook",
        "deleteWebhook"  -> "deleteWebhook",
        "webhookInfo"    -> "getWebhookInfo",
        "custom"         -> "getChatMemberCount",
        "webhookVerify"  -> "webhook",
        "webhookDecode"  -> "webhook"
    )

    val answerKinds: Chunk[Kind] = Chunk(
        Kind.Migrated,
        Kind.Unauthorized,
        Kind.Forbidden,
        Kind.Conflict,
        Kind.ChatNotFound,
        Kind.MessageNotFound,
        Kind.MessageNotModified,
        Kind.FileTooBig
    )

    val migratedTo: Long = -1001234567890L

    val notModified =
        "Bad Request: message is not modified: specified new message content and reply markup are exactly the same as a current content and reply markup of the message"

    /** The error code and description of each answer kind, as the Bot API server writes them. */
    def answerOf(kind: Kind): (Int, String) =
        kind match
            case Kind.Migrated           => (400, "Bad Request: group chat was upgraded to a supergroup chat")
            case Kind.Unauthorized       => (401, "Unauthorized")
            case Kind.Forbidden          => (403, "Forbidden: bot was blocked by the user")
            case Kind.Conflict           => (409, "Conflict: terminated by other getUpdates request")
            case Kind.ChatNotFound       => (400, "Bad Request: chat not found")
            case Kind.MessageNotFound    => (400, "Bad Request: message to edit not found")
            case Kind.MessageNotModified => (400, notModified)
            case Kind.FileTooBig         => (400, "Bad Request: file is too big")
            case Kind.OtherApi           => (400, "Bad Request: message text is empty")
            case other @ (Kind.Transport | Kind.RefusedUrl | Kind.UnexpectedStatus | Kind.Decode | Kind.NoFilePath |
                Kind.RateLimit | Kind.SecretTokenMissing | Kind.SecretTokenMismatch | Kind.InvalidToken |
                Kind.InvalidConfig | Kind.InvalidCallbackData | Kind.InvalidCommand | Kind.InvalidMethod | Kind.InvalidUrl |
                Kind.InvalidWebhookOptions | Kind.InvalidWebhookConfig | Kind.InvalidCallbackAnswer) =>
                throw new IllegalStateException(s"$other is not a Bot API answer")

    def replyFor(kind: Kind): Reply =
        def answer(k: Kind): Reply =
            val (code, d) = answerOf(k)
            Reply(HttpStatus.init(code).getOrThrow, s"""{"ok":false,"error_code":$code,"description":"$d"}""")
        kind match
            case Kind.UnexpectedStatus => Reply(HttpStatus(502), "<html>bad gateway</html>")
            case Kind.Decode           => Reply(HttpStatus.OK, """{"ok":true,"result":"not what the method returns"}""")
            case Kind.RateLimit        =>
                Reply(
                    HttpStatus(429),
                    """{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 7","parameters":{"retry_after":7}}"""
                )
            case Kind.Migrated =>
                val (code, d) = answerOf(kind)
                Reply(
                    HttpStatus.init(code).getOrThrow,
                    s"""{"ok":false,"error_code":$code,"description":"$d","parameters":{"migrate_to_chat_id":$migratedTo}}"""
                )
            case Kind.Unauthorized | Kind.Forbidden | Kind.Conflict | Kind.ChatNotFound | Kind.MessageNotFound | Kind.MessageNotModified |
                Kind.FileTooBig | Kind.OtherApi => answer(kind)
            case other @ (Kind.Transport | Kind.RefusedUrl | Kind.NoFilePath | Kind.SecretTokenMissing | Kind.SecretTokenMismatch |
                Kind.InvalidToken | Kind.InvalidConfig | Kind.InvalidCallbackData | Kind.InvalidCommand |
                Kind.InvalidMethod | Kind.InvalidUrl | Kind.InvalidWebhookOptions | Kind.InvalidWebhookConfig |
                Kind.InvalidCallbackAnswer) =>
                throw new IllegalStateException(s"$other is not produced by a reply")
        end match
    end replyFor

    def expectedLeaf(op: String, kind: Kind, closingPort: Int)(using Frame): TelegramException =
        val method                = methodOf(op)
        def answer: (Int, String) = answerOf(kind)
        kind match
            case Kind.Transport =>
                TelegramTransportException(method, TelegramTransportException.Kind.NoResponseHead, "127.0.0.1", closingPort, Absent)()
            case Kind.RefusedUrl       => TelegramRefusedUrlException(method)
            case Kind.UnexpectedStatus => TelegramUnexpectedStatusException(method, HttpStatus(502))
            case Kind.Decode           =>
                val part = if op == "webhookDecode" then TelegramDecodeException.Part.Update else TelegramDecodeException.Part.Result
                TelegramDecodeException(method, part, TelegramDecodeException.Failure.Parse, Chunk.empty, Absent)
            case Kind.NoFilePath          => TelegramNoFilePathException(Telegram.FileId("F"))
            case Kind.RateLimit           => TelegramRateLimitException(method, Present(7.seconds))
            case Kind.Migrated            => TelegramMigratedException(method, answer._2, Telegram.ChatId(migratedTo))
            case Kind.Unauthorized        => TelegramUnauthorizedException(method, answer._2)
            case Kind.Forbidden           => TelegramForbiddenException(method, answer._2)
            case Kind.Conflict            => TelegramConflictException(method, answer._2)
            case Kind.ChatNotFound        => TelegramChatNotFoundException(method, answer._2)
            case Kind.MessageNotFound     => TelegramMessageNotFoundException(method, answer._2)
            case Kind.MessageNotModified  => TelegramMessageNotModifiedException(method, answer._2)
            case Kind.FileTooBig          => TelegramFileTooBigException(method, answer._2)
            case Kind.OtherApi            => TelegramOtherApiException(method, answer._1, answer._2)
            case Kind.SecretTokenMissing  => TelegramSecretTokenMissingException()
            case Kind.SecretTokenMismatch => TelegramSecretTokenMismatchException()
            case Kind.InvalidToken | Kind.InvalidConfig | Kind.InvalidCallbackData |
                Kind.InvalidCommand | Kind.InvalidMethod | Kind.InvalidUrl | Kind.InvalidWebhookOptions | Kind.InvalidWebhookConfig |
                Kind.InvalidCallbackAnswer =>
                throw new IllegalStateException(s"$kind is a panic, which no operation fails with")
        end match
    end expectedLeaf

    private val target    = Telegram.Chat.Target(Telegram.ChatId(5L))
    private val messageId = Telegram.MessageId(1)

    /** Calls `op` through a client on `config`, collecting its typed failure. */
    def call(op: String, config: TelegramConfig, file: Telegram.File = fileAt("a/b"), webhookUrl: HttpUrl = hookUrl)(using
        Frame
    ): Result[TelegramException, Unit] < Async =
        val noRetry = config.copy(retrySchedule = Schedule.done)
        Telegram.run(noRetry) {
            Abort.run[TelegramException] {
                op match
                    case "receive"        => Telegram.receive(TelegramTest.onMessage(_ => Kyo.unit))
                    case "me"             => Telegram.me.unit
                    case "send"           => Telegram.send(target, Telegram.Content.text("hi")).unit
                    case "edit"           => Telegram.edit(target, messageId, Telegram.Edit.Text(Telegram.Text("hi"))).unit
                    case "delete"         => Telegram.delete(target, messageId)
                    case "answerCallback" => Telegram.answerCallback(Telegram.CallbackQueryId("q"))
                    case "sendChatAction" => Telegram.sendChatAction(target, Telegram.ChatAction.Typing)
                    case "setReaction"    => Telegram.setReaction(target, messageId, Seq(Telegram.Reaction.Emoji("👍")))
                    case "setCommands"    => Telegram.setCommands(Telegram.Command.Menu.init(Telegram.Command.init(
                            "start",
                            "Start"
                        ).getOrThrow).getOrThrow)
                    case "file"          => Telegram.file(Telegram.FileId("F")).unit
                    case "download"      => Telegram.download(file).unit
                    case "setWebhook"    => Telegram.setWebhook(webhookUrl, webhook)
                    case "deleteWebhook" => Telegram.deleteWebhook()
                    case "webhookInfo"   => Telegram.webhookInfo.unit
                    case "custom"        =>
                        Telegram.custom[TelegramTest.ChatParam, Int](
                            Telegram.Method.init("getChatMemberCount").getOrThrow,
                            TelegramTest.ChatParam(1L)
                        )
                            .unit
                    case other => Abort.panic(new IllegalStateException(s"$other is not an operation of a client"))
            }
        }
    end call

    private def fileAt(path: String): Telegram.File =
        Telegram.File(Telegram.FileId("F"), Telegram.FileUniqueId("U"), path = Present(path))

    private val hookUrl = HttpUrl(Present("https"), "bot.example.com", 443, "/", Absent)

    private val webhook = Telegram.SecretToken.init("s3cret").flatMap(TelegramWebhookConfig.init(_)).getOrThrow

    /** Drives `op` through its real path with the local Bot API set up to produce `kind`. */
    def produce(local: Local, closingPort: Int, op: String, kind: Kind)(using Frame): Result[TelegramException, Unit] < Async =
        kind match
            case Kind.Transport =>
                call(op, local.config.copy(baseUrl = local.base.copy(port = closingPort)))
            case Kind.RefusedUrl =>
                call(op, local.config, file = fileAt("../bot"), webhookUrl = HttpUrl.fromUri("hooks/x"))
            case Kind.NoFilePath =>
                call(op, local.config, file = Telegram.File(Telegram.FileId("F"), Telegram.FileUniqueId("U")))
            case Kind.SecretTokenMissing =>
                Kyo.lift(Telegram.Webhook.verify(webhook, Absent))
            case Kind.SecretTokenMismatch =>
                Kyo.lift(Telegram.Webhook.verify(webhook, Present("wrong")))
            case Kind.Decode if op == "webhookDecode" =>
                Abort.run[TelegramException](Telegram.Webhook.decode(kyo.internal.charset.Utf8.encode("{}")).unit)
            case other @ (Kind.UnexpectedStatus | Kind.Decode | Kind.RateLimit | Kind.Migrated | Kind.Unauthorized |
                Kind.Forbidden | Kind.Conflict | Kind.ChatNotFound | Kind.MessageNotFound | Kind.MessageNotModified | Kind.FileTooBig |
                Kind.OtherApi) =>
                local.reply(methodOf(op), replyFor(other)).andThen(call(op, local.config))
            case other @ (Kind.InvalidToken | Kind.InvalidConfig | Kind.InvalidCallbackData | Kind.InvalidCommand |
                Kind.InvalidMethod | Kind.InvalidUrl | Kind.InvalidWebhookOptions | Kind.InvalidWebhookConfig | Kind.InvalidCallbackAnswer) =>
                Abort.panic(new IllegalStateException(s"$other is a panic, which no operation fails with"))

    /** A peer that accepts a connection and closes it before answering. */
    def withClosingPeer[A](test: Int => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        // Unsafe: kyo-net's raw listener is the one way to get a peer that accepts and closes before any HTTP answer.
        Sync.Unsafe.defer {
            kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16) { conn =>
                // Unsafe: the listener's accept callback runs outside the effect system, where the connection is closed at once.
                conn.close()
            }
        }.map { fiber =>
            fiber.safe.use { listener =>
                // Unsafe: the raw listener has no safe close; it is closed when the test's Scope ends.
                Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen(test(listener.port))
            }
        }

    // --- Operation sets ---

    val api: Set[String] = Set(
        "receive",
        "me",
        "send",
        "edit",
        "delete",
        "answerCallback",
        "sendChatAction",
        "setReaction",
        "setCommands",
        "file",
        "setWebhook",
        "deleteWebhook",
        "webhookInfo",
        "custom"
    )
    val chat: Set[String]       = Set("send", "edit", "delete", "sendChatAction", "setReaction")
    val operations: Set[String] = api ++ Set("download", "webhookVerify", "webhookDecode")

    /** One leaf: the operations it belongs to, and a value of it. */
    case class Leaf(kind: Kind, ops: Set[String], sample: TelegramException)

    def leaf(kind: Kind, ops: Set[String])(sample: TelegramException): Leaf = Leaf(kind, ops, sample)

    val leaves: Chunk[Leaf] = Chunk(
        leaf(Kind.Transport, api + "download")(
            TelegramTransportException("m", TelegramTransportException.Kind.ConnectionClosed, "h", 1, Absent)()
        ),
        leaf(Kind.RefusedUrl, Set("download", "setWebhook"))(TelegramRefusedUrlException("m")),
        leaf(Kind.UnexpectedStatus, api + "download")(TelegramUnexpectedStatusException("m", HttpStatus.BadGateway)),
        leaf(Kind.Decode, api + "webhookDecode")(
            TelegramDecodeException("m", TelegramDecodeException.Part.Result, TelegramDecodeException.Failure.Parse, Chunk.empty, Absent)
        ),
        leaf(Kind.NoFilePath, Set("download"))(TelegramNoFilePathException(Telegram.FileId("f"))),
        leaf(Kind.RateLimit, api)(TelegramRateLimitException("m", Absent)),
        leaf(Kind.Migrated, chat + "custom")(TelegramMigratedException("m", "d", Telegram.ChatId(-1L))),
        leaf(Kind.Unauthorized, api)(TelegramUnauthorizedException("m", "d")),
        leaf(Kind.Forbidden, chat + "custom")(TelegramForbiddenException("m", "d")),
        leaf(Kind.Conflict, Set("receive", "setWebhook"))(TelegramConflictException("m", "d")),
        leaf(Kind.ChatNotFound, chat)(TelegramChatNotFoundException("m", "d")),
        leaf(Kind.MessageNotFound, Set("edit", "delete", "setReaction"))(TelegramMessageNotFoundException("m", "d")),
        leaf(Kind.MessageNotModified, Set("edit"))(TelegramMessageNotModifiedException("m", "d")),
        leaf(Kind.FileTooBig, Set("file"))(TelegramFileTooBigException("m", "d")),
        leaf(Kind.OtherApi, api)(TelegramOtherApiException("m", 400, "d")),
        leaf(Kind.SecretTokenMissing, Set("webhookVerify"))(TelegramSecretTokenMissingException()),
        leaf(Kind.SecretTokenMismatch, Set("webhookVerify"))(TelegramSecretTokenMismatchException()),
        leaf(Kind.InvalidToken, Set.empty)(
            TelegramInvalidTokenException(TelegramInvalidTokenException.Token.Secret, TelegramInvalidTokenException.Problem.Empty)
        ),
        leaf(Kind.InvalidConfig, Set.empty)(TelegramInvalidConfigException(TelegramInvalidConfigException.Problem.PollLimit(0))),
        leaf(Kind.InvalidCallbackData, Set.empty)(TelegramInvalidCallbackDataException(0)),
        leaf(Kind.InvalidCommand, Set.empty)(TelegramInvalidCommandException(TelegramInvalidCommandException.Problem.NameLength(0))),
        leaf(Kind.InvalidMethod, Set.empty)(TelegramInvalidMethodException(TelegramInvalidMethodException.Problem.Empty)),
        leaf(Kind.InvalidUrl, Set.empty)(TelegramInvalidUrlException(TelegramInvalidUrlException.Problem.Scheme)),
        leaf(Kind.InvalidWebhookOptions, Set.empty)(
            TelegramInvalidWebhookOptionsException(TelegramInvalidWebhookOptionsException.Problem.MaxConnections(0))
        ),
        leaf(Kind.InvalidWebhookConfig, Set.empty)(
            TelegramInvalidWebhookConfigException(TelegramInvalidWebhookConfigException.Problem.PathCharacter(0))
        ),
        leaf(Kind.InvalidCallbackAnswer, Set.empty)(
            TelegramInvalidCallbackAnswerException(TelegramInvalidCallbackAnswerException.Problem.TextLength(201))
        )
    )

end TelegramExceptionMembershipTest
