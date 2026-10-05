package kyo

import TelegramDecodeException.Failure
import TelegramDecodeException.Part
import TelegramTransportException.Kind

class TelegramExceptionTest extends kyo.test.Test[Any]:

    private def decodeFailure(input: String): DecodeException =
        Json.decode[Int](input) match
            case Result.Failure(ex) => ex
            case other              => throw new IllegalStateException(s"expected a decode failure, got $other")

    private case class Wrapper(n: Int) derives Schema

    private def transport(method: String, kind: Kind, timeout: Maybe[Duration] = Absent): TelegramTransportException =
        TelegramTransportException(method, kind, "api.telegram.org", 443, timeout)()

    "each leaf builds its message from its own fields" in {
        val leaves: Chunk[(TelegramException, String)] = Chunk(
            transport("sendMessage", Kind.ConnectionClosed) ->
                "Telegram sendMessage failed at the transport to api.telegram.org:443: the connection closed before the response body.",
            transport("getFile", Kind.Timeout, Present(40.seconds)) ->
                s"Telegram getFile failed at the transport to api.telegram.org:443: no answer arrived after ${40.seconds.show}.",
            TelegramRefusedUrlException("download") ->
                "Telegram download refused to send to a URL outside the ones the module sends to.",
            TelegramUnexpectedStatusException("getMe", HttpStatus.BadGateway) ->
                "Telegram getMe answered HTTP 502 without a Bot API response body.",
            TelegramDecodeException("getUpdates", Part.Envelope, Failure.Parse, Chunk.empty, Present(0)) ->
                "Telegram getUpdates response envelope did not decode: the JSON does not parse, position 0.",
            TelegramDecodeException("getMe", Part.Result, Failure.MissingField, Chunk("id"), Absent) ->
                "Telegram getMe result did not decode: a required field is missing at id.",
            TelegramDecodeException("webhook", Part.Update, Failure.TypeMismatch, Chunk("a", "b"), Absent) ->
                "Telegram webhook update did not decode: a value has the wrong type at a.b.",
            TelegramNoFilePathException(Telegram.FileId("AgAD")) ->
                "Telegram file AgAD has no file_path to download.",
            TelegramRateLimitException("sendMessage", Present(7.seconds)) ->
                s"Telegram rate-limited sendMessage; retry after ${7.seconds.show}.",
            TelegramRateLimitException("sendMessage", Absent) ->
                "Telegram rate-limited sendMessage; Telegram sent no retry delay.",
            TelegramMigratedException(
                "sendMessage",
                "Bad Request: group chat was upgraded to a supergroup chat",
                Telegram.ChatId(-1001234567890L)
            ) ->
                "Telegram sendMessage answered 400: Bad Request: group chat was upgraded to a supergroup chat; the group is now the supergroup -1001234567890.",
            TelegramUnauthorizedException("getMe", "Unauthorized") ->
                "Telegram getMe answered 401: Unauthorized",
            TelegramForbiddenException("sendMessage", "Forbidden: bot was blocked by the user") ->
                "Telegram sendMessage answered 403: Forbidden: bot was blocked by the user",
            TelegramConflictException("getUpdates", "Conflict: terminated by other getUpdates request") ->
                "Telegram getUpdates answered 409: Conflict: terminated by other getUpdates request",
            TelegramChatNotFoundException("sendMessage", "Bad Request: chat not found") ->
                "Telegram sendMessage answered 400: Bad Request: chat not found",
            TelegramMessageNotFoundException("deleteMessage", "Bad Request: message to delete not found") ->
                "Telegram deleteMessage answered 400: Bad Request: message to delete not found",
            TelegramMessageNotModifiedException("editMessageText", "Bad Request: message is not modified") ->
                "Telegram editMessageText answered 400: Bad Request: message is not modified",
            TelegramFileTooBigException("getFile", "Bad Request: file is too big") ->
                "Telegram getFile answered 400: Bad Request: file is too big",
            TelegramOtherApiException("sendMessage", 400, "Bad Request: message text is empty") ->
                "Telegram sendMessage answered 400: Bad Request: message text is empty",
            TelegramSecretTokenMissingException() ->
                "Telegram webhook request carries no X-Telegram-Bot-Api-Secret-Token header.",
            TelegramSecretTokenMismatchException() ->
                "Telegram webhook request's X-Telegram-Bot-Api-Secret-Token does not match the secret.",
            TelegramInvalidCallbackDataException(65) ->
                "Telegram callback data must be 1 to 64 bytes in UTF-8; got 65.",
            TelegramInvalidCommandException(TelegramInvalidCommandException.Problem.NameLength(33)) ->
                "Telegram.Command is not usable: the name must have 1 to 32 characters; got 33.",
            TelegramInvalidCommandException(TelegramInvalidCommandException.Problem.NameCharacter(2)) ->
                "Telegram.Command is not usable: the name's character at position 2 is not one of a-z 0-9 _.",
            TelegramInvalidCommandException(TelegramInvalidCommandException.Problem.DescriptionLength(0)) ->
                "Telegram.Command is not usable: the description must have 1 to 256 characters; got 0.",
            TelegramInvalidMethodException(TelegramInvalidMethodException.Problem.Empty) ->
                "Telegram.Method is not usable: the name is empty.",
            TelegramInvalidMethodException(TelegramInvalidMethodException.Problem.Dots) ->
                "Telegram.Method is not usable: the name is only dots.",
            TelegramInvalidMethodException(TelegramInvalidMethodException.Problem.Character(3)) ->
                "Telegram.Method is not usable: the character at position 3 is not one of A-Z a-z 0-9 . _.",
            TelegramInvalidUrlException(TelegramInvalidUrlException.Problem.Scheme) ->
                "Telegram.Url is not usable: it does not start with http://, https:// or tg://.",
            TelegramInvalidUrlException(TelegramInvalidUrlException.Problem.Empty) ->
                "Telegram.Url is not usable: nothing follows the scheme.",
            TelegramInvalidUrlException(TelegramInvalidUrlException.Problem.Character(8)) ->
                "Telegram.Url is not usable: the character at position 8 is not printable ASCII other than space.",
            TelegramInvalidWebhookOptionsException(TelegramInvalidWebhookOptionsException.Problem.MaxConnections(101)) ->
                "Telegram.WebhookOptions is not usable: maxConnections must be between 1 and 100; got 101.",
            TelegramInvalidWebhookConfigException(TelegramInvalidWebhookConfigException.Problem.PathCharacter(5)) ->
                "TelegramWebhookConfig is not usable: the path's character at position 5 cannot appear in a request path.",
            TelegramInvalidCallbackAnswerException(TelegramInvalidCallbackAnswerException.Problem.TextLength(201)) ->
                "Telegram.CallbackAnswer is not usable: the text must have at most 200 characters; got 201."
        )
        val missing = leaves.filterNot((ex, msg) => ex.getMessage.contains(msg)).map((ex, _) => ex.getMessage)
        assert(missing == Chunk.empty)
    }

    "a kyo-http leaf the module's calls cannot produce has no transport kind: it is a module bug" in {
        assert(kyo.internal.telegram.BotApi.describe(HttpBindException("127.0.0.1", 1, new Exception("in use"))) == Absent)
    }

    "a decode leaf names kyo-schema's failure, its path and its position, and quotes nothing of the input" in {
        // Built from parts: a development-mode message quotes the source lines around the construction site.
        val marker                                   = Seq("body", "marker").mkString("-")
        val parse                                    = decodeFailure(marker)
        def failureOf(json: String): DecodeException =
            Json.decode[Wrapper](json) match
                case Result.Failure(ex) => ex
                case other              => throw new IllegalStateException(s"expected a decode failure, got $other")
        val leaves = Chunk(parse, failureOf(s"""{"n": $marker}"""), failureOf(s"""{"n":"$marker"}"""), failureOf("""{"m":1}""")).map(
            TelegramDecodeException.of("getMe", TelegramDecodeException.Part.Result, _)
        )
        assert(leaves.map(l => (l.failure, l.path, l.position)) == Chunk(
            (Failure.Parse, Chunk.empty[String], Present(0)),
            (Failure.Parse, Chunk("n"), Present(6)),
            (Failure.TypeMismatch, Chunk("n"), Absent),
            (Failure.MissingField, Chunk.empty[String], Absent)
        ))
        assert(leaves.map(l => Maybe(l.getCause()).isDefined) == Chunk(false, false, false, false))
        assert(leaves.map(_.getMessage).forall(!_.contains(marker)))
    }

    "a value the module's own check refused is a decode leaf of a constructor rejection at its path" in {
        val leaf = TelegramDecodeException.ofDecoded("getWebhookInfo", Part.Result, kyo.internal.telegram.BotApi.Rejected(Chunk("url")))
        assert(leaf == TelegramDecodeException("getWebhookInfo", Part.Result, Failure.ConstructorRejected, Chunk("url"), Absent))
    }

    "a transport leaf's kyo-net cause is its getCause and stays out of equality" in {
        val cause = kyo.net.NetConnectException("api.telegram.org", 443, "refused")
        val leaf  = TelegramTransportException("getMe", Kind.Connect, "api.telegram.org", 443, Absent)(Present(cause))
        assert(leaf == transport("getMe", Kind.Connect))
        assert(leaf.getCause() eq cause)
        assert(leaf.cause == Present(cause))
    }

    "no leaf other than a transport leaf with a kyo-net cause has a cause" in {
        val leaves: Chunk[TelegramException] = Chunk(
            transport("m", Kind.Protocol),
            TelegramRefusedUrlException("download"),
            TelegramDecodeException("m", Part.Result, Failure.Parse, Chunk.empty, Absent),
            TelegramUnexpectedStatusException("m", HttpStatus.BadGateway),
            TelegramRateLimitException("m", Absent),
            TelegramOtherApiException("m", 400, "d"),
            TelegramSecretTokenMismatchException(),
            TelegramInvalidTokenException(TelegramInvalidTokenException.Token.Bot, TelegramInvalidTokenException.Problem.Empty)
        )
        assert(leaves.map(l => Maybe(l.getCause()).isDefined) == Chunk.fill(leaves.size)(false))
    }

end TelegramExceptionTest
