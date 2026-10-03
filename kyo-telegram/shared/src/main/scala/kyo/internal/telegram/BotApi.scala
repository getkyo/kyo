package kyo.internal.telegram

import kyo.*
import kyo.internal.charset.Utf8
import scala.reflect.TypeTest

/** The one request path of the Bot API, on the config and HTTP client of a `Telegram`.
  *
  * Every call is a POST to `{baseUrl}/bot{token}/{method}` read with `failOnError = false`, so the
  * module maps the status, `Retry-After` and the body itself and no kyo-http status failure is raised.
  *
  * Every request runs on the client's own `HttpClient` under a complete `HttpClientConfig` that replaces
  * the caller's: a caller's filter would log the path, a caller's TLS setting would govern the token's
  * connection, and a caller's redirect or retry setting would send the path again or elsewhere.
  *
  * The token is in the path of every URL, so a kyo-http failure, whose URL holds the path, is never
  * kept: it is described by a [[kyo.TelegramTransportException]]'s fields and dropped.
  *
  * Which leaf a failure answer becomes on which operation is written once, as the traits the leaves
  * mix in: `within` keeps the leaf an answer names when it carries the operation's trait `F`, and
  * otherwise answers [[kyo.TelegramOtherApiException]].
  */
private[kyo] object BotApi:

    /** The leaves every Bot API operation can fail with, whatever it calls. */
    type Common = TelegramTransportException | TelegramUnexpectedStatusException | TelegramDecodeException |
        TelegramRateLimitException | TelegramUnauthorizedException | TelegramOtherApiException

    // --- Calls ---

    /** Runs with the length of a flood-control wait once its timer is armed and before the call parks on it. A test sets it to
      * advance a controlled clock by exactly the wait: run before the timer exists, the advance could land first and the wait
      * would end that much later.
      */
    private[kyo] val floodWaitArmed: Local[Duration => Unit < Sync] = Local.init((_: Duration) => Kyo.unit)

    /** A call's parameters: a method's body record, written through its schema as JSON, or as multipart with its files
      * when it uploads any; or a JSON body the caller encoded (`custom`).
      */
    enum Payload:
        /** `secrets` are the secret values the body holds (a webhook's `secret_token`), redacted like the token. `uploads`
          * are the files sent as parts named by their field, beside the record's fields.
          */
        case Body[B](
            value: B,
            schema: Schema[B],
            secrets: Chunk[String] = Chunk.empty,
            uploads: Chunk[(String, Telegram.InputFile.Upload)] = Chunk.empty
        )
        case Json(body: String)
    end Payload

    object Payload:
        /** `value` written through its schema. */
        def of[B](value: B, secrets: Chunk[String] = Chunk.empty)(using schema: Schema[B]): Payload = Body(value, schema, secrets)

        /** `value` written through its schema, with `uploads` as multipart parts when there are any. */
        def withUploads[B](value: B, uploads: Chunk[(String, Telegram.InputFile.Upload)])(using schema: Schema[B]): Payload =
            Body(value, schema, uploads = uploads)
    end Payload

    /** A value the module's own check refused, at `path`: an update with no `update_id`, a result other than the `true` a
      * method documents.
      */
    final case class Rejected(
        path: Chunk[String],
        failure: TelegramDecodeException.Failure = TelegramDecodeException.Failure.ConstructorRejected
    ) derives CanEqual

    /** A decode that fails with kyo-schema's failure, or with the module's own refusal. */
    type Decoded[A] = Result[DecodeException | Rejected, A]

    /** Calls `method` on the client in the environment, with no timeout beyond the config's. The result decodes through
      * `R`'s schema, and `check` turns it into the answer or refuses it.
      */
    def call[R: Schema, A, F >: Common](method: String, payload: Payload)(
        check: R => Decoded[A]
    )(using Frame, TypeTest[TelegramException, F]): A < (Async & Abort[F] & Env[Telegram]) =
        Env.use[Telegram](telegram =>
            callWith[R, A, F](telegram.config, telegram.http, method, payload, Duration.Zero, telegram.config.retry)(check)
        )

    /** Calls a method documented to return `True`; any other result does not decode. */
    def acknowledged[F >: Common](method: String, payload: Payload)(
        using
        Frame,
        TypeTest[TelegramException, F]
    ): Unit < (Async & Abort[F] & Env[Telegram]) =
        call[Boolean, Unit, F](method, payload)(value =>
            if value then Result.unit
            else Result.fail(Rejected(Chunk.empty, TelegramDecodeException.Failure.TypeMismatch))
        )

    /** Calls `method` through `http`, bounded by `transferTimeout` when the payload uploads a file and by `requestTimeout`
      * otherwise; `extraTimeout` extends the bound for a long poll. With `retry`, a flood-control answer is sent again after
      * the `retry_after` it names, while the schedule allows another attempt.
      */
    def callWith[R: Schema, A, F >: Common](
        config: TelegramConfig,
        http: HttpClient,
        method: String,
        payload: Payload,
        extraTimeout: Duration,
        retry: Maybe[Schedule]
    )(
        check: R => Decoded[A]
    )(using Frame, TypeTest[TelegramException, F]): A < (Async & Abort[F]) =
        val url = TelegramConfig.under(config.baseUrl, s"/bot${config.token.value}/$method")
        def json(body: String): (HttpStatus, Maybe[String], String) < (Async & Abort[HttpException]) =
            HttpClient.postBinaryResponse(url, Utf8.encode(body), headers = Seq("Content-Type" -> "application/json"), failOnError = false)
                .map(r => (r.status, r.headers.get("Retry-After"), Utf8.decode(r.fields.body)))
        val request: (HttpStatus, Maybe[String], String) < (Async & Abort[HttpException]) =
            payload match
                case Payload.Body(value, schema, _, uploads) if uploads.isEmpty => json(kyo.Json.encode(value)(using schema))
                case Payload.Json(body)                                         => json(body)
                case Payload.Body(value, schema, _, uploads)                    =>
                    Multipart.encode(Structure.encode(value)(using schema), uploads).map { body =>
                        HttpClient.postBinaryResponse(
                            url,
                            body.bytes,
                            headers = Seq("Content-Type" -> body.contentType),
                            failOnError = false
                        )
                            .map(r => (r.status, r.headers.get("Retry-After"), Utf8.decode(r.fields.body)))
                    }
        val secrets = payload match
            case Payload.Body(_, _, secrets, _) => config.token.value +: secrets
            case Payload.Json(_)                => Chunk(config.token.value)
        val bound = payload match
            case Payload.Body(_, _, _, uploads) if uploads.nonEmpty => config.transferTimeout
            case Payload.Body(_, _, _, _) | Payload.Json(_)         => config.requestTimeout
        Loop(retry) { remaining =>
            transport(config, http, method, bound + extraTimeout)(request).map { (status, retryAfter, body) =>
                Clock.now.map { now =>
                    nextAttempt(remaining, config.retryMaxDelay, body, now) match
                        case Present((wait, next)) =>
                            val waited =
                                if wait == Duration.Zero then Kyo.unit
                                else Clock.sleep(wait).map(timer => floodWaitArmed.use(_(wait)).andThen(timer.get))
                            waited.andThen(Loop.continue(Present(next)))
                        case Absent =>
                            mapResponse[R, A, F](
                                secrets,
                                method,
                                status,
                                retryAfter.flatMap(parseRetryAfter),
                                body
                            )(check).map(Loop.done(_))
                }
            }
        }
    end callWith

    /** Whether to send a call again, and after how long: only when `body` is an `ok:false` answer naming `parameters.retry_after`,
      * the one retry the Bot API documents ("In case of exceeding flood control, the number of seconds left to wait before the
      * request can be repeated", `ResponseParameters`), it is at most `maxDelay`, and `remaining` allows another attempt. The
      * wait is Telegram's, not the schedule's.
      */
    private[kyo] def nextAttempt(remaining: Maybe[Schedule], maxDelay: Duration, body: String, now: Instant)(using
        Frame
    ): Maybe[(Duration, Schedule)] =
        val flood = Json.decode[Envelope](body) match
            case Result.Success(envelope) if !envelope.ok => envelope.parameters.flatMap(_.retryAfter).filter(_ >= 0).map(_.seconds)
            case _                                        => Absent
        flood.filter(_ <= maxDelay).flatMap(wait => remaining.flatMap(_.next(now)).map((_, next) => (wait, next)))
    end nextAttempt

    /** Downloads the file at `file.path` from the file endpoint, which answers bytes. */
    def download(file: Telegram.File)(using Frame): Span[Byte] < (Async & Abort[TelegramDownloadFailure] & Env[Telegram]) =
        Env.use[Telegram] { telegram =>
            file.path match
                case Absent                               => Abort.fail(TelegramNoFilePathException(file.id))
                case Present(path) if !safeFilePath(path) => Abort.fail(TelegramRefusedUrlException(DownloadMethod))
                case Present(path)                        =>
                    val config = telegram.config
                    val url    = TelegramConfig.under(config.baseUrl, s"/file/bot${config.token.value}/$path")
                    transport(config, telegram.http, DownloadMethod, config.transferTimeout)(
                        HttpClient.getBinaryResponse(url, failOnError = false)
                    ).map { response =>
                        if response.status.isSuccess then response.fields.body
                        else Abort.fail(TelegramUnexpectedStatusException(DownloadMethod, response.status))
                    }
        }

    inline val DownloadMethod = "download"

    /** A `file_path` Telegram sent is appended after the token, so only relative segments of
      * `[A-Za-z0-9._-]` joined by single slashes pass: no scheme, no leading slash, no `.` or `..`
      * segment, no query, fragment, space or non-ASCII character that would move the request.
      */
    private def safeFilePath(path: String): Boolean =
        val segments = path.split("/", -1)
        path.nonEmpty && segments.forall(s =>
            s.nonEmpty && s != "." && s != ".." &&
                s.forall(c =>
                    (c >= 'a' && c <= 'z') ||
                        (c >= 'A' && c <= 'Z') ||
                        (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'
                )
        )
    end safeFilePath

    // --- Transport ---

    /** The whole kyo-http configuration of a request that carries the token; nothing is inherited from the caller. TLS and
      * transport come from `TelegramConfig`, never from the caller's kyo-http configuration.
      */
    private[kyo] def requestConfig(config: TelegramConfig, timeout: Duration): HttpClientConfig =
        HttpClientConfig(
            baseUrl = Absent,
            timeout = timeout,
            connectTimeout = config.connectTimeout,
            followRedirects = false,
            maxRedirects = 0,
            retrySchedule = Absent,
            // Inert: with no retry schedule nothing is retried. HttpClientConfig requires the field.
            retryOn = _.isServerError,
            transportConfig = config.transport,
            tls = config.tls,
            maxResponseLength = config.maxResponseLength.toBytes.toInt,
            autoFilters = false,
            clientFilter = HttpFilter.noop
        )

    /** Runs a kyo-http request on `http` and describes its failure without keeping it. */
    private def transport[A](config: TelegramConfig, http: HttpClient, method: String, timeout: Duration)(
        request: => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[TelegramTransportException]) =
        val host = config.baseUrl.host
        val port = config.baseUrl.port
        Abort.runWith[HttpException](HttpClient.let(http)(HttpClient.withConfig(requestConfig(config, timeout))(request))) {
            case Result.Success(a) => a
            case Result.Failure(e) =>
                describe(e) match
                    case Present((kind, timeout, cause)) => Abort.fail(TelegramTransportException(method, kind, host, port, timeout)(cause))
                    // The class name only: the exception's URL holds the token.
                    case Absent => bug(s"${e.getClass.getSimpleName} reached a Telegram call")
            case Result.Panic(e) => Abort.panic(e)
        }
    end transport

    /** The kind, the timeout that ran out, and kyo-net's cause, of every kyo-http leaf a call of this module can
      * receive. The cause is kept only for a connection that could not be made, whose kyo-http leaf holds a host and
      * a port and no URL. `Absent` is a leaf the module's calls cannot produce (a URL or header the module built that
      * kyo-http refused, a redirect, a status or typed-body leaf, a server leaf): a module bug. No arm is a wildcard,
      * so a new kyo-http leaf does not compile until it is placed.
      */
    private[kyo] def describe(e: HttpException): Maybe[(TelegramTransportException.Kind, Maybe[Duration], Maybe[kyo.net.NetException])] =
        import TelegramTransportException.Kind
        e match
            case e: HttpConnectException =>
                e.cause match
                    case tls: kyo.net.NetTlsException => Present((Kind.Tls, Absent, Present(tls)))
                    case net: kyo.net.NetException    => Present((Kind.Connect, Absent, Present(net)))
                    case _                            => Present((Kind.Connect, Absent, Absent))
            case e: HttpDnsResolutionException =>
                e.cause match
                    case net: kyo.net.NetException => Present((Kind.Dns, Absent, Present(net)))
                    case _                         => Present((Kind.Dns, Absent, Absent))
            case e: HttpConnectTimeoutException   => Present((Kind.ConnectTimeout, Present(e.timeout), Absent))
            case e: HttpPoolExhaustedException    => Present((Kind.PoolExhausted(e.maxConnections), Absent, Absent))
            case e: HttpTimeoutException          => Present((Kind.Timeout, Present(e.duration), Absent))
            case e: HttpConnectionClosedException =>
                e.phase match
                    case HttpConnectionClosedException.Phase.BeforeHead => Present((Kind.NoResponseHead, Absent, Absent))
                    case HttpConnectionClosedException.Phase.BodyTruncated | HttpConnectionClosedException.Phase.TlsTruncated =>
                        Present((Kind.ConnectionClosed, Absent, Absent))
            case _: HttpProtocolException        => Present((Kind.Protocol, Absent, Absent))
            case _: HttpMalformedBodyException   => Present((Kind.Protocol, Absent, Absent))
            case e: HttpPayloadTooLargeException => Present((Kind.PayloadTooLarge(e.bodySize.bytes, e.maxSize.bytes), Absent, Absent))
            case _: (HttpUrlParseException | HttpNonAsciiException | HttpInvalidFieldException | HttpWebSocketHandshakeException |
                    HttpUnixConnectException | HttpRedirectLoopException | HttpStatusException | HttpBindException | HttpHandlerException |
                    HttpFieldDecodeException | HttpPathDecodeException | HttpMissingFieldException |
                    HttpJsonDecodeException | HttpFormDecodeException | HttpUnsupportedMediaTypeException | HttpStreamingDecodeException |
                    HttpMissingBoundaryException) => Absent
        end match
    end describe

    // --- Answers ---

    private[kyo] def mapResponse[R: Schema, A, F >: Common](
        secrets: Chunk[String],
        method: String,
        status: HttpStatus,
        retryAfter: Maybe[Duration],
        body: String
    )(
        check: R => Decoded[A]
    )(using Frame, TypeTest[TelegramException, F]): A < Abort[F] =
        import TelegramDecodeException.Part
        def decodeFailure(part: Part, failure: DecodeException | Rejected): TelegramDecodeException =
            TelegramDecodeException.ofDecoded(method, part, failure)
        def withoutEnvelope(failure: DecodeException | Rejected): Nothing < Abort[F] =
            if status == HttpStatus.TooManyRequests then Abort.fail(TelegramRateLimitException(method, retryAfter))
            else if status.isSuccess then Abort.fail(decodeFailure(Part.Envelope, failure))
            else Abort.fail(TelegramUnexpectedStatusException(method, status))
        // The result decodes on its own, after the envelope, so its failure is told from the envelope's even when the JSON
        // reader reports it without a path (a string where the method returns an object).
        def answer: A < Abort[F] =
            val decoded: Result[DecodeException | Rejected, A] =
                Json.decode[Answered[R]](body) match
                    case Result.Success(answered) =>
                        // An answer without `result` is read as `null` through the method's schema, which decides whether that is one.
                        answered.result.fold(Structure.decode[R](Structure.Value.Null))(Result.succeed).flatMap(check)
                    case Result.Failure(ex) =>
                        val leaf = decodeFailure(Part.Result, ex)
                        val path = if leaf.path.headMaybe.contains(ResultField) then leaf.path.drop(1) else leaf.path
                        Result.fail(Rejected(path, leaf.failure))
                    case Result.Panic(ex) => Result.panic(ex)
            decoded match
                case Result.Success(a)       => a
                case Result.Failure(failure) => Abort.fail(decodeFailure(Part.Result, failure))
                case Result.Panic(ex)        => Abort.panic(ex)
            end match
        end answer
        Json.decode[Envelope](body) match
            case Result.Success(envelope) if envelope.ok =>
                if !status.isSuccess then Abort.fail(TelegramUnexpectedStatusException(method, status)) else answer
            case Result.Success(envelope) =>
                envelope.errorCode match
                    case Absent        => withoutEnvelope(Rejected(Chunk("error_code")))
                    case Present(code) =>
                        envelope.description match
                            case Absent               => withoutEnvelope(Rejected(Chunk("description")))
                            case Present(description) =>
                                val failure = Failure(code, redact(secrets, description), envelope.parameters)
                                Abort.fail(within[F](method, failure, leafFor(method, status, failure, retryAfter)))
            case Result.Failure(ex) => withoutEnvelope(ex)
            case Result.Panic(ex)   => Abort.panic(ex)
        end match
    end mapResponse

    private inline val ResultField = "result"

    /** Telegram's `ResponseParameters`: why a request failed and what to do about it. */
    final private[kyo] case class ResponseParameters(migrateToChatId: Maybe[Long] = Absent, retryAfter: Maybe[Long] = Absent)

    private[kyo] object ResponseParameters:
        given Schema[ResponseParameters] = WireField.snakeCase(Schema.derived[ResponseParameters])

    /** Every Bot API answer's envelope; kyo-schema skips its `result`, which [[Answered]] reads. */
    final private[kyo] case class Envelope(
        ok: Boolean,
        errorCode: Maybe[Int] = Absent,
        description: Maybe[String] = Absent,
        parameters: Maybe[ResponseParameters] = Absent
    )

    private[kyo] object Envelope:
        given Schema[Envelope] = WireField.snakeCase(Schema.derived[Envelope])

    /** A successful answer's `result`, of the method's own type. */
    final private[kyo] case class Answered[R](result: Maybe[R] = Absent) derives Schema

    /** An `ok:false` answer's fields, with the request's secrets already redacted from `description`. */
    final private[kyo] case class Failure(code: Int, description: String, parameters: Maybe[ResponseParameters])

    /** `description` with every secret the request carried replaced, raw and with `:` percent-encoded in either case, for a
      * server that echoes the request's path or body.
      */
    private[kyo] def redact(secrets: Chunk[String], description: String): String =
        secrets.flatMap(s => Chunk(s, s.replace(":", "%3A"), s.replace(":", "%3a"))).foldLeft(description)(_.replace(_, "<redacted>"))

    /** The leaf an `ok:false` answer names, on whichever operation received it. */
    private[kyo] def leafFor(method: String, status: HttpStatus, f: Failure, retryAfterHeader: Maybe[Duration])(using
        Frame
    ): TelegramException =
        val retryAfter = f.parameters.flatMap(_.retryAfter).filter(_ >= 0).map(_.seconds).orElse(retryAfterHeader)
        val code       = f.code
        val d          = f.description
        if f.parameters.exists(_.retryAfter.nonEmpty) || code == 429 || status == HttpStatus.TooManyRequests then
            TelegramRateLimitException(method, retryAfter)
        else
            // `Client::check_chat_access` sends `migrate_to_chat_id` only with a 400, the code the leaf fixes.
            f.parameters.flatMap(_.migrateToChatId).filter(_ => code == 400).fold(byCode(method, code, d))(chat =>
                TelegramMigratedException(method, d, Telegram.ChatId(chat))
            )
        end if
    end leafFor

    private def byCode(method: String, code: Int, d: String)(using Frame): TelegramException =
        code match
            case 401 => TelegramUnauthorizedException(method, d)
            case 403 => TelegramForbiddenException(method, d)
            case 409 => TelegramConflictException(method, d)
            // The descriptions are written by the Bot API server (github.com/tdlib/telegram-bot-api, Client.cpp at
            // e3e9dd8e5b), which the documentation does not list: `fail_query_with_error` prefixes "Bad Request: " to a 400.
            case 400 =>
                d match
                    // `TdOnCheckChatCallback`, the chat lookup.
                    case "Bad Request: chat not found" => TelegramChatNotFoundException(method, d)
                    // `TdOnEditMessageCallback` and `TdOnStopPollCallback` write "message not found"; `Client::check_message`
                    // writes "<target> not found" for the target of an edit, a delete and a reaction.
                    case "Bad Request: message not found" | "Bad Request: message to edit not found" |
                        "Bad Request: message to delete not found" | "Bad Request: message to react not found" =>
                        TelegramMessageNotFoundException(method, d)
                    // TDLib's MESSAGE_NOT_MODIFIED, as `Client::fail_query_with_error` rewrites it.
                    case "Bad Request: message is not modified: specified new message content and reply markup are exactly the same as a current content and reply markup of the message" =>
                        TelegramMessageNotModifiedException(method, d)
                    // `Client::on_update_file`, a file over the download limit.
                    case "Bad Request: file is too big" => TelegramFileTooBigException(method, d)
                    case _                              => TelegramOtherApiException(method, code, d)
            case _ => TelegramOtherApiException(method, code, d)
    end byCode

    private def within[F >: TelegramOtherApiException](method: String, f: Failure, leaf: TelegramException)(using
        Frame,
        TypeTest[TelegramException, F]
    ): F =
        leaf match
            case named: F => named
            case _        => TelegramOtherApiException(method, f.code, f.description)

    /** `Retry-After` as `delay-seconds` (RFC 9110): one to nine ASCII digits, with optional whitespace around them.
      * Anything else, an HTTP date included, carries no delay the module reads, so it is `Absent`. RFC 9110 bounds
      * `delay-seconds` nowhere, so the parse does: nine digits fit an `Int` and reach about 31 years.
      */
    private[kyo] def parseRetryAfter(raw: String): Maybe[Duration] =
        val value = raw.trim
        if value.isEmpty || value.length > 9 || !value.forall(c => c >= '0' && c <= '9') then Absent
        else Present(value.toLong.seconds)
    end parseRetryAfter

end BotApi
