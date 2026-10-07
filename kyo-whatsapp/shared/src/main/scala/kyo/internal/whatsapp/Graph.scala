package kyo.internal.whatsapp

import kyo.*
import kyo.internal.PercentEncoding
import scala.reflect.TypeTest

/** The one request path of the Cloud API, on the config and HTTP client of a `WhatsApp`.
  *
  * Every call reads its answer with `failOnError = false`, so the module maps status and body itself and no kyo-http status failure is
  * raised. Every request runs on the client's own `HttpClient` under the complete `HttpClientConfig` of `WhatsAppConfig.httpConfig`, which
  * replaces the caller's: a caller's filter would see the token, a caller's base url would resolve a relative url, and a caller's TLS
  * setting would govern the token's connection.
  *
  * No kyo-http failure is kept, since each names the request's url: it is described by a [[kyo.WhatsAppTransportException]]'s fields and
  * dropped. Which leaf a Graph error becomes on which operation is written once, as the traits the leaves mix in: `within` keeps the leaf a
  * code names when it carries the operation's trait `F`, and otherwise answers [[kyo.WhatsAppOtherApiException]].
  */
private[kyo] object Graph:

    /** The leaves every HTTP operation can fail with, whatever it calls. */
    type Common = WhatsAppTransportException | WhatsAppUnexpectedStatusException | WhatsAppOtherApiException

    /** The Graph error codes Meta's error-code reference (developers.facebook.com/docs/whatsapp/cloud-api/support/error-codes) tells a
      * caller to try again on:
      *   - 2: "Temporary due to downtime or due to being overloaded." ... "before trying again."
      *   - 4: "The app has reached its API call rate limit." ... "try again later"
      *   - 80007: "The WhatsApp Business Account has reached its rate limit." ... "Try again later"
      *   - 130429: "Cloud API message throughput has been reached." ... "Try again later"
      *   - 131000: "Message failed to send due to an unknown error." ... "Try again."
      *   - 131016: "A service is temporarily unavailable." ... "before trying again."
      *   - 131056: "Too many messages sent from the sender phone number to the same recipient phone number in a short period of
      *     time." ... "Wait and retry the operation"
      *   - 133004: "Server is temporarily unavailable." ... "before trying again."
      */
    private[kyo] val RetryableCodes: Set[Int] = Set(2, 4, 80007, 130429, 131000, 131016, 131056, 133004)

    /** Runs `request` to `target` on the client and answers the body of a 2xx; a non-2xx is the Graph error's leaf within `F`, or the
      * status leaf when the body is not a Graph error. With the config's `retry` set, an answer whose Graph error is one of
      * `RetryableCodes` is sent again after the schedule's delay or the answer's `Retry-After`, whichever is longer, until the schedule
      * ends; a wait past `retryMaxDelay` is not taken, and the leaf is the answer.
      */
    def call[F >: Common](client: WhatsApp, method: String, target: HttpUrl)(
        request: HttpClient => HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException])
    )(using Frame, TypeTest[WhatsAppException, F]): Span[Byte] < (Async & Abort[F]) =
        val config                                                              = client.config
        def attempt(schedule: Maybe[Schedule]): Span[Byte] < (Async & Abort[F]) =
            transport(client, method, target)(request).map { response =>
                if response.status.isSuccess then response.fields.body
                else
                    Abort.get(graphError(response.fields.body)).map { error =>
                        val retryAfter                            = retryAfterOf(response)
                        def fail: Span[Byte] < (Async & Abort[F]) =
                            Abort.fail(failureOf[F](config.token, method, response.status, error, retryAfter))
                        if !error.exists(e => RetryableCodes.contains(e.code)) then fail
                        else
                            Clock.now.map { now =>
                                schedule.flatMap(_.next(now)).fold(fail) { (delay, rest) =>
                                    val wait = retryAfter.fold(delay)(after => if after > delay then after else delay)
                                    if wait > config.retryMaxDelay then fail
                                    else Async.sleep(wait).andThen(attempt(Present(rest)))
                                }
                            }
                        end if
                    }
            }
        attempt(config.retry)
    end call

    /** The answer's `Retry-After` as delta-seconds, the form a rate-limited Graph answer would use. */
    private def retryAfterOf(response: HttpResponse[?]): Maybe[Duration] =
        response.headers.get("Retry-After").flatMap { text =>
            if text.nonEmpty && text.forall(c => c >= '0' && c <= '9') then Maybe.fromOption(text.toLongOption).map(_.seconds)
            else Absent
        }

    /** Runs a kyo-http request on the client under the module's own config, and describes its failure without keeping it. */
    def transport[A](client: WhatsApp, method: String, target: HttpUrl)(
        request: HttpClient => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[WhatsAppTransportException]) =
        Abort.runWith[HttpException](
            HttpClient.let(client.http)(HttpClient.withConfig(client.config.httpConfig)(request(client.http)))
        ) {
            case Result.Success(a) => a
            case Result.Failure(e) =>
                val (kind, timeout, cause) = describe(e)
                Abort.fail(WhatsAppTransportException(method, kind, target.host, target.port, timeout)(cause))
            case Result.Panic(e) => Abort.panic(e)
        }

    /** The kind, the timeout that ran out, and kyo-net's cause, of every kyo-http leaf a call of this module can meet. The cause is kept
      * only for a connection that could not be made, whose kyo-http leaf holds a host and a port and no url.
      *
      * The match is exhaustive with no wildcard, so a kyo-http leaf added upstream fails to compile here. A leaf no call can produce is a
      * module defect and panics by its name only: a status leaf (every call reads with `failOnError = false`), a redirect leaf (redirects
      * are off), a typed-body decode or server leaf (the calls read raw bytes and serve nothing), a unix-socket leaf (every url is refused
      * before one is sent to), a url or header leaf (every url and header the module sends is built from validated parts, and a media
      * url is refused unless it is printable ASCII on a host), a cookie leaf (the module sends no cookie), a config leaf (every kyo-http
      * limit is checked once, by `WhatsAppConfig.init`), and an invalid-status leaf (only `HttpStatus.init` raises it, and the module never
      * calls it).
      */
    private[kyo] def describe(e: HttpException): (WhatsAppTransportException.Kind, Maybe[Duration], Maybe[kyo.net.NetException]) =
        import WhatsAppTransportException.Kind
        def unreachable(leaf: String): Nothing = bug(s"$leaf reached a kyo-whatsapp call")
        e match
            case e: HttpConnectException =>
                e.cause match
                    case tls: kyo.net.NetTlsException => (Kind.Tls, Absent, Present(tls))
                    case net: kyo.net.NetException    => (Kind.Connect, Absent, Present(net))
                    case _                            => (Kind.Connect, Absent, Absent)
            case e: HttpDnsResolutionException =>
                e.cause match
                    case net: kyo.net.NetException => (Kind.Dns, Absent, Present(net))
                    case _                         => (Kind.Dns, Absent, Absent)
            case e: HttpConnectTimeoutException   => (Kind.ConnectTimeout, Present(e.timeout), Absent)
            case e: HttpPoolExhaustedException    => (Kind.PoolExhausted(e.maxConnections), Absent, Absent)
            case e: HttpTimeoutException          => (Kind.Timeout, Present(e.duration), Absent)
            case e: HttpConnectionClosedException =>
                e.phase match
                    case HttpConnectionClosedException.Phase.BeforeHead => (Kind.NoResponseHead, Absent, Absent)
                    case _                                              => (Kind.ConnectionClosed, Absent, Absent)
            case _: HttpProtocolException             => (Kind.Protocol, Absent, Absent)
            case _: HttpMalformedBodyException        => (Kind.Protocol, Absent, Absent)
            case e: HttpPayloadTooLargeException      => (Kind.PayloadTooLarge(e.bodySize, e.maxSize), Absent, Absent)
            case _: HttpUrlParseException             => unreachable("HttpUrlParseException")
            case _: HttpNonAsciiException             => unreachable("HttpNonAsciiException")
            case _: HttpInvalidFieldException         => unreachable("HttpInvalidFieldException")
            case _: HttpWebSocketHandshakeException   => unreachable("HttpWebSocketHandshakeException")
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
            case _: HttpCookieException               => unreachable("HttpCookieException")
            case _: HttpRouteException                => unreachable("HttpRouteException")
            case _: HttpInvalidStatusException        => unreachable("HttpInvalidStatusException")
        end match
    end describe

    /** A non-2xx answer as `F`: its Graph error's leaf, or the status leaf when the body is not a Graph error. A panic while decoding the
      * body is a defect and stays a panic.
      */
    private[kyo] def statusFailure[F >: Common](token: WhatsAppToken, method: String, status: HttpStatus, body: Span[Byte])(using
        Frame,
        TypeTest[WhatsAppException, F]
    ): Result[Nothing, F] =
        graphError(body).map(failureOf[F](token, method, status, _, Absent))

    private def failureOf[F >: Common](
        token: WhatsAppToken,
        method: String,
        status: HttpStatus,
        error: Maybe[Methods.GraphError.Detail],
        retryAfter: Maybe[Duration]
    )(using Frame, TypeTest[WhatsAppException, F]): F =
        error match
            case Present(err) => within[F](method, leafFor(token, method, err, retryAfter))
            case Absent       => WhatsAppUnexpectedStatusException(method, status)

    /** The Graph error a body holds, `Absent` when it is not one. A panic while decoding is a defect and stays a panic. */
    private def graphError(body: Span[Byte])(using Frame): Result[Nothing, Maybe[Methods.GraphError.Detail]] =
        Json.decodeBytes[Methods.GraphError](body) match
            case Result.Success(answer) => Result.succeed(Present(answer.error))
            case Result.Failure(_)      => Result.succeed(Absent)
            case Result.Panic(t)        => Result.panic(t)

    /** The leaf a Graph error names, on whichever operation received it, with the token redacted from Meta's text. */
    private[kyo] def leafFor(token: WhatsAppToken, method: String, err: Methods.GraphError.Detail, retryAfter: Maybe[Duration] = Absent)(
        using Frame
    ): WhatsAppApiException =
        val description = redact(token.value, err.message)
        val details     = err.error_data.flatMap(_.details).map(redact(token.value, _))
        val subcode     = err.error_subcode
        val traceId     = err.fbtrace_id
        err.code match
            case 190                                => WhatsAppTokenExpiredException(method, subcode, description, details, traceId)
            case code @ (0 | 3 | 10 | 131005)       => WhatsAppAccessDeniedException(method, code, subcode, description, details, traceId)
            case code if code >= 200 && code <= 299 => WhatsAppAccessDeniedException(method, code, subcode, description, details, traceId)
            case 4      => WhatsAppAppRateLimitException(method, subcode, description, details, traceId, retryAfter)
            case 80007  => WhatsAppBusinessAccountRateLimitException(method, subcode, description, details, traceId, retryAfter)
            case 130429 => WhatsAppThroughputRateLimitException(method, subcode, description, details, traceId, retryAfter)
            case 131056 => WhatsAppRecipientPairRateLimitException(method, subcode, description, details, traceId, retryAfter)
            case 131026 => WhatsAppUndeliverableException(method, subcode, description, details, traceId)
            case 131021 => WhatsAppSenderIsRecipientException(method, subcode, description, details, traceId)
            case 131047 => WhatsAppWindowClosedException(method, subcode, description, details, traceId)
            case 132000 => WhatsAppTemplateParameterCountException(method, subcode, description, details, traceId)
            case 132001 => WhatsAppTemplateNotFoundException(method, subcode, description, details, traceId)
            case 132005 => WhatsAppTemplateTextTooLongException(method, subcode, description, details, traceId)
            case 132007 => WhatsAppTemplateContentPolicyException(method, subcode, description, details, traceId)
            case 132012 => WhatsAppTemplateParameterFormatException(method, subcode, description, details, traceId)
            case 132015 => WhatsAppTemplatePausedException(method, subcode, description, details, traceId)
            case 131053 => WhatsAppMediaUploadException(method, subcode, description, details, traceId)
            case code @ (100 | 131008 | 131009 | 135000) =>
                WhatsAppInvalidParameterException(method, code, subcode, description, details, traceId)
            case code @ (131000 | 131016) => WhatsAppServiceUnavailableException(method, code, subcode, description, details, traceId)
            case code                     => WhatsAppOtherApiException(method, code, subcode, description, details, traceId)
        end match
    end leafFor

    /** Meta's text with every occurrence of `secret` replaced, raw and percent-encoded as a URL component with hex in either case, for an
      * answer that echoes the request. An empty secret is skipped because `replace` would match it between every character.
      */
    private[kyo] def redact(secret: String, text: String): String =
        if secret.isEmpty then text
        else
            val upper = PercentEncoding.encode(secret, PercentEncoding.Mode.Component)
            Chunk(secret, upper, PercentEscape.replaceAllIn(upper, _.matched.toLowerCase))
                .distinct.sortBy(-_.length).foldLeft(text)(_.replace(_, "<redacted>"))
    end redact

    private val PercentEscape = "%[0-9A-F]{2}".r

    private def within[F >: WhatsAppOtherApiException](method: String, leaf: WhatsAppApiException)(using
        Frame,
        TypeTest[WhatsAppException, F]
    ): F =
        leaf match
            case named: F => named
            case _        =>
                WhatsAppOtherApiException(method, leaf.code, leaf.subcode, leaf.description, leaf.details, leaf.traceId)

end Graph
