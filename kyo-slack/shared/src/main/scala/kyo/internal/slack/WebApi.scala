package kyo.internal.slack

import kyo.*
import kyo.internal.charset.Utf8
import scala.reflect.TypeTest

/** The one canonical request path of a [[kyo.Slack]] client. Every Web API verb delegates to `request`,
  * `apps.connections.open` to `send`, and every `response_url` verb to `respond`. Each runs on the
  * client's own `HttpClient` under `SlackConfig.httpConfig`, which replaces the caller's config, so
  * nothing of the caller's kyo-http context reaches a request that carries a credential.
  *
  * Slack answers most failures as HTTP 200 + `{"ok":false,"error":code}`, so the path uses
  * `failOnError = false` and reads the body itself (`mapResponse`): a 429 is a rate limit, an
  * `ok:false` envelope is the leaf for its code on this operation, a non-2xx answer with no Slack
  * body is an unexpected status, and a body that does not decode is a decode failure. A kyo-http
  * failure becomes the flat `SlackTransportException` through `transportFailure`. No `HttpException`
  * reaches a row, and none is kept in a leaf.
  *
  * Every path is typed by the operation's failure trait `F`. Which code has a leaf on which
  * operation is written once, as the traits each leaf mixes in: `within` keeps the leaf a code names
  * when it carries `F`, and otherwise answers `SlackOtherApiException` carrying the code.
  */
private[kyo] object WebApi:

    /** The `ok` field every Slack response carries. */
    final private[kyo] case class StatusEnvelope(ok: Boolean) derives Schema

    final private[kyo] case class ResponseMetadata(messages: Chunk[String] = Chunk.empty) derives Schema

    /** The failure side of a Slack response: the code and what Slack sends beside it. */
    final private[kyo] case class FailureEnvelope(
        error: String,
        needed: Maybe[String] = Absent,
        provided: Maybe[String] = Absent,
        response_metadata: Maybe[ResponseMetadata] = Absent
    ) derives Schema:
        def messages: Chunk[String] = response_metadata.fold(Chunk.empty[String])(_.messages)
    end FailureEnvelope

    /** The leaf a Slack code names, on whichever operation answered it. */
    private[kyo] def leafFor(method: String, e: FailureEnvelope, retryAfter: Maybe[Duration])(using Frame): SlackException =
        val m = e.messages
        e.error match
            case "ratelimited" | "rate_limited" => SlackRateLimitException(method, retryAfter)
            case "invalid_auth"                 => SlackInvalidAuthException(method, m)
            case "not_authed"                   => SlackNotAuthedException(method, m)
            case "token_revoked"                => SlackTokenRevokedException(method, m)
            case "token_expired"                => SlackTokenExpiredException(method, m)
            case "account_inactive"             => SlackAccountInactiveException(method, m)
            case "not_allowed_token_type"       => SlackNotAllowedTokenTypeException(method, m)
            case "missing_scope"                => SlackMissingScopeException(method, scopes(e.needed), scopes(e.provided), m)
            case "invalid_arguments"            => SlackInvalidArgumentsException(method, m)
            case "channel_not_found"            => SlackChannelNotFoundException(method, m)
            case "not_in_channel"               => SlackNotInChannelException(method, m)
            case "is_archived"                  => SlackIsArchivedException(method, m)
            case "user_not_in_channel"          => SlackUserNotInChannelException(method, m)
            case "no_text"                      => SlackNoTextException(method, m)
            case "msg_too_long"                 => SlackMsgTooLongException(method, m)
            case "msg_blocks_too_long"          => SlackMsgBlocksTooLongException(method, m)
            case "invalid_blocks"               => SlackInvalidBlocksException(method, m)
            case "invalid_blocks_format"        => SlackInvalidBlocksFormatException(method, m)
            case "cannot_reply_to_message"      => SlackCannotReplyToMessageException(method, m)
            case "message_not_found"            => SlackMessageNotFoundException(method, m)
            case "cant_update_message"          => SlackCantUpdateMessageException(method, m)
            case "expired_trigger_id"           => SlackExpiredTriggerIdException(method, m)
            case "exchanged_trigger_id"         => SlackExchangedTriggerIdException(method, m)
            case "invalid_trigger_id"           => SlackInvalidTriggerIdException(method, m)
            case "view_too_large"               => SlackViewTooLargeException(method, m)
            case "not_found"                    => SlackNotFoundException(method, m)
            case other                          => SlackOtherApiException(method, other, m)
        end match
    end leafFor

    /** The leaf for an `ok:false` answer on an operation whose failures are `F`: the leaf the code
      * names when it carries `F`, else `SlackOtherApiException` carrying the code.
      */
    private[kyo] def within[F >: SlackOtherApiException](method: String, e: FailureEnvelope, retryAfter: Maybe[Duration])(using
        Frame,
        TypeTest[SlackException, F]
    ): F =
        leafFor(method, e, retryAfter) match
            case named: F => named
            case _        => SlackOtherApiException(method, e.error, e.messages)

    /** A Web API call with the bot token. */
    private[kyo] def request[In: Schema, Out: Schema, F >: SlackException.Common](
        client: Slack,
        method: String,
        body: In
    )(using Frame, TypeTest[SlackException, F]): Out < (Async & Abort[F]) =
        send[Out, F](client, method, client.config.bot.value, Json.encode(body))

    /** POST a JSON body to `{baseUrl}/{method}` with a bearer token and map Slack's answer. Shared by `request` and the
      * `apps.connections.open` call that opens the socket with the app-level token.
      */
    private[kyo] def send[Out: Schema, F >: SlackException.Common](
        client: Slack,
        method: String,
        bearer: String,
        body: String
    )(using Frame, TypeTest[SlackException, F]): Out < (Async & Abort[F]) =
        val target = SlackConfig.methodUrl(client.config.baseUrl, method)
        Abort.runWith[HttpException](
            onClient(client) {
                HttpClient.postTextResponse(
                    target,
                    body,
                    headers = Seq("Authorization" -> s"Bearer $bearer", "Content-Type" -> "application/json"),
                    failOnError = false
                )
            }
        ) {
            case Result.Success(response) =>
                mapResponse[Out, F](response.status, response.headers.get("Retry-After"), response.fields.body, method, tokens(client))
            case Result.Failure(e) => Abort.fail(transportFailure(method, target, e))
            case Result.Panic(e)   => Abort.panic(e)
        }
    end send

    /** Run `request` on the client's own `HttpClient` under the config that replaces the caller's. */
    private[kyo] def onClient[A, S](client: Slack)(request: => A < (S & Async & Abort[HttpException]))(using
        Frame
    ): A < (S & Async & Abort[HttpException]) =
        HttpClient.let(client.http)(HttpClient.withConfig(SlackConfig.httpConfig(client.config))(request))

    /** The client's two tokens, the secrets a peer answering a request could echo. */
    private def tokens(client: Slack): Chunk[String] =
        Chunk(client.config.appLevel.value, client.config.bot.value)

    /** `text` with each secret replaced by `<redacted>`: raw, and percent-encoded with upper- and lowercase hex, for a peer
      * that echoes the request's header or body into its answer.
      */
    private[kyo] def redact(secrets: Chunk[String], text: String): String =
        secrets.flatMap(s => Chunk(s, percentEncoded(s, "0123456789ABCDEF"), percentEncoded(s, "0123456789abcdef")))
            .foldLeft(text)(_.replace(_, "<redacted>"))

    /** RFC 3986 percent-encoding of every UTF-8 byte outside the unreserved set, with `hex` as the digits. */
    private def percentEncoded(s: String, hex: String): String =
        val sb = new StringBuilder
        Utf8.encode(s).foreach { b =>
            val c = (b & 0xff).toChar
            if (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~'
            then discard(sb.append(c))
            else discard(sb.append('%').append(hex.charAt((b >> 4) & 0xf)).append(hex.charAt(b & 0xf)))
            end if
        }
        sb.toString
    end percentEncoded

    /** The envelope with `clean` applied to every text Slack sent: the code, the scope lists and the messages. */
    private def cleaned(envelope: FailureEnvelope)(clean: String => String): FailureEnvelope =
        envelope.copy(
            error = clean(envelope.error),
            needed = envelope.needed.map(clean),
            provided = envelope.provided.map(clean),
            response_metadata = envelope.response_metadata.map(m => m.copy(messages = m.messages.map(clean)))
        )

    /** Map a Slack HTTP response to the typed `Out` or the operation's failure. Every text of an `ok:false` answer has
      * `secrets` redacted before it reaches a leaf.
      */
    private[kyo] def mapResponse[Out: Schema, F >: SlackException.Common](
        status: HttpStatus,
        retryAfterHeader: Maybe[String],
        body: String,
        method: String,
        secrets: Chunk[String]
    )(using Frame, TypeTest[SlackException, F]): Out < Abort[F] =
        val retryAfter = retryAfterHeader.flatMap(parseRetryAfter)
        if status == HttpStatus.TooManyRequests then Abort.fail(SlackRateLimitException(method, retryAfter))
        else
            Json.decode[StatusEnvelope](body) match
                case Result.Success(StatusEnvelope(true)) if !status.isSuccess =>
                    Abort.fail(SlackUnexpectedStatusException(method, status))
                case Result.Success(StatusEnvelope(true)) =>
                    Json.decode[Out](body) match
                        case Result.Success(out) => out
                        case Result.Failure(ex)  => Abort.fail(SlackDecodeException(method, SlackDecodeException.Part.Payload, ex))
                        case Result.Panic(ex)    => Abort.panic(ex)
                case Result.Success(StatusEnvelope(false)) =>
                    Json.decode[FailureEnvelope](body) match
                        case Result.Success(envelope) =>
                            Abort.fail(within[F](method, cleaned(envelope)(redact(secrets, _)), retryAfter))
                        case Result.Failure(ex) =>
                            if status.isSuccess then Abort.fail(SlackDecodeException(method, SlackDecodeException.Part.Envelope, ex))
                            else Abort.fail(SlackUnexpectedStatusException(method, status))
                        case Result.Panic(ex) => Abort.panic(ex)
                case Result.Failure(ex) =>
                    if status.isSuccess then Abort.fail(SlackDecodeException(method, SlackDecodeException.Part.Envelope, ex))
                    else Abort.fail(SlackUnexpectedStatusException(method, status))
                case Result.Panic(ex) => Abort.panic(ex)
        end if
    end mapResponse

    private def scopes(list: Maybe[String]): Chunk[String] =
        list.fold(Chunk.empty[String])(s => Chunk.from(s.split(',')).map(_.trim).filter(_.nonEmpty))

    /** Slack sends `Retry-After` as `delay-seconds`, which RFC 9110 defines as `1*DIGIT` with ASCII
      * digits, surrounded on the wire by optional SP or HTAB. Anything else (a sign, another digit
      * script, an HTTP date) carries no delay the module can honour, so it is `Absent` rather than a
      * guess. A delay longer than `Duration` holds (about 292 years) is `Duration.Infinity`, the
      * saturation `Duration` itself applies.
      */
    private[kyo] def parseRetryAfter(raw: String): Maybe[Duration] =
        val value = raw.dropWhile(isOws).reverse.dropWhile(isOws).reverse
        if value.isEmpty || !value.forall(c => c >= '0' && c <= '9') then Absent
        else
            Maybe.fromOption(value.toLongOption) match
                case Present(secs) => Present(secs.seconds)
                case Absent        => Present(Duration.Infinity)
        end if
    end parseRetryAfter

    private def isOws(c: Char): Boolean = c == ' ' || c == '\t'

    private[kyo] inline val ResponseUrlMethod = "response_url"

    /** POST `body` to a `response_url` and map the answer, for an operation whose failures are `F`.
      *
      * The url is the credential, so it is sent only when it is an absolute http or https url on a host, and refused as
      * `SlackRefusedUrlException` otherwise. The answer: 429 is a rate limit; a body
      * `{"ok":false,...}` at any status is its code's leaf; a non-2xx without one is an unexpected status; any other 2xx
      * is success, since Slack documents no body for it. A code or message that echoes the url or its path holds
      * `<response_url>` in its place.
      */
    private[kyo] def respond[F >: SlackException.ResponseUrl](client: Slack, url: SlackResponseUrl, body: String)(using
        Frame,
        TypeTest[SlackException, F]
    ): Unit < (Async & Abort[F]) =
        HttpUrl.parse(url.value) match
            case Result.Success(target) if SlackConfig.absoluteProblemOf(target).isEmpty =>
                Abort.runWith[HttpException](
                    onClient(client) {
                        HttpClient.postTextResponse(target, body, headers = Seq("Content-Type" -> "application/json"), failOnError = false)
                    }
                ) {
                    case Result.Success(response) =>
                        answer[F](url, target, response.status, response.headers.get("Retry-After"), response.fields.body, tokens(client))
                    case Result.Failure(e) => Abort.fail(transportFailure(ResponseUrlMethod, target, e))
                    case Result.Panic(e)   => Abort.panic(e)
                }
            case _ => Abort.fail(SlackRefusedUrlException(ResponseUrlMethod))
    end respond

    private def answer[F >: SlackException.ResponseUrl](
        url: SlackResponseUrl,
        target: HttpUrl,
        status: HttpStatus,
        retryAfterHeader: Maybe[String],
        body: String,
        secrets: Chunk[String]
    )(using Frame, TypeTest[SlackException, F]): Unit < Abort[F] =
        val retryAfter = retryAfterHeader.flatMap(parseRetryAfter)
        if status == HttpStatus.TooManyRequests then Abort.fail(SlackRateLimitException(ResponseUrlMethod, retryAfter))
        else
            Json.decode[StatusEnvelope](body) match
                case Result.Success(StatusEnvelope(false)) =>
                    Json.decode[FailureEnvelope](body) match
                        case Result.Success(envelope) =>
                            Abort.fail(within[F](ResponseUrlMethod, redacted(url, target, secrets, envelope), retryAfter))
                        case Result.Failure(ex) =>
                            if status.isSuccess then
                                Abort.fail(SlackDecodeException(ResponseUrlMethod, SlackDecodeException.Part.Envelope, ex))
                            else Abort.fail(SlackUnexpectedStatusException(ResponseUrlMethod, status))
                        case Result.Panic(ex) => Abort.panic(ex)
                case Result.Panic(ex) => Abort.panic(ex)
                case _                =>
                    if status.isSuccess then Kyo.unit else Abort.fail(SlackUnexpectedStatusException(ResponseUrlMethod, status))
        end if
    end answer

    /** The envelope with every occurrence of a token redacted, and of the url and its path replaced by `<response_url>`, in
      * every text Slack sent.
      */
    private def redacted(url: SlackResponseUrl, target: HttpUrl, tokens: Chunk[String], envelope: FailureEnvelope): FailureEnvelope =
        val urls = Chunk(url.value) ++ Chunk(target.path).filter(_.length > 1)
        cleaned(envelope)(text => urls.foldLeft(redact(tokens, text))((t, s) => t.replace(s, "<response_url>")))
    end redacted

    /** The leaf for a kyo-http failure of a call to `target`: its kind, the url's host and port, and for a connection
      * that could not be made the kyo-net cause, which names a host and port and never a path. The match names every
      * kyo-http leaf with no wildcard, so a leaf kyo-http adds fails to compile here instead of being classified by guess.
      *
      * A leaf no call can produce is a module defect and panics by its name only: a url leaf (every url is built from the
      * validated config or checked by `SlackConfig.absoluteProblemOf`, printable ASCII on a host, before it is sent), a
      * header leaf (the only header values the module sends are validated tokens), a unix-socket leaf, a redirect leaf
      * (redirects are off), a status leaf (the status is read, never raised), and a typed-body decode or server leaf.
      */
    private[kyo] def transportFailure(method: String, target: HttpUrl, e: HttpException)(using Frame): SlackTransportException =
        import SlackTransportException.Kind
        def unreachable(name: String): Nothing = bug(s"$name reached a Slack call")
        def leaf(kind: Kind, timeout: Maybe[Duration] = Absent, cause: Maybe[kyo.net.NetException] = Absent) =
            SlackTransportException(method, kind, target.host, target.port, timeout)(cause)
        def net(t: Throwable): Maybe[kyo.net.NetException] =
            t match
                case n: kyo.net.NetException => Present(n)
                case _                       => Absent
        e match
            case c: HttpConnectException =>
                c.cause match
                    case tls: kyo.net.NetTlsException => leaf(Kind.Tls, cause = Present(tls))
                    case other                        => leaf(Kind.Connect, cause = net(other))
            case d: HttpDnsResolutionException        => leaf(Kind.Dns, cause = net(d.cause))
            case t: HttpConnectTimeoutException       => leaf(Kind.ConnectTimeout, Present(t.timeout))
            case p: HttpPoolExhaustedException        => leaf(Kind.PoolExhausted(p.maxConnections))
            case t: HttpTimeoutException              => leaf(Kind.Timeout, Present(t.duration))
            case _: HttpConnectionClosedException     => leaf(Kind.ConnectionClosed)
            case _: HttpProtocolException             => leaf(Kind.Protocol)
            case _: HttpMalformedBodyException        => leaf(Kind.Protocol)
            case p: HttpPayloadTooLargeException      => leaf(Kind.PayloadTooLarge(p.bodySize.bytes, p.maxSize.bytes))
            case _: HttpWebSocketHandshakeException   => leaf(Kind.WebSocketHandshake)
            case _: HttpUrlParseException             => unreachable("HttpUrlParseException")
            case _: HttpNonAsciiException             => unreachable("HttpNonAsciiException")
            case _: HttpInvalidFieldException         => unreachable("HttpInvalidFieldException")
            case _: HttpUnixConnectException          => unreachable("HttpUnixConnectException")
            case _: HttpRedirectLoopException         => unreachable("HttpRedirectLoopException")
            case _: HttpStatusException               => unreachable("HttpStatusException")
            case _: HttpBindException                 => unreachable("HttpBindException")
            case _: HttpHandlerException              => unreachable("HttpHandlerException")
            case _: HttpFieldDecodeException          => unreachable("HttpFieldDecodeException")
            case _: HttpPathDecodeException           => unreachable("HttpPathDecodeException")
            case _: HttpMissingFieldException         => unreachable("HttpMissingFieldException")
            case _: HttpJsonDecodeException           => unreachable("HttpJsonDecodeException")
            case _: HttpFormDecodeException           => unreachable("HttpFormDecodeException")
            case _: HttpUnsupportedMediaTypeException => unreachable("HttpUnsupportedMediaTypeException")
            case _: HttpStreamingDecodeException      => unreachable("HttpStreamingDecodeException")
            case _: HttpMissingBoundaryException      => unreachable("HttpMissingBoundaryException")
        end match
    end transportFailure

end WebApi
