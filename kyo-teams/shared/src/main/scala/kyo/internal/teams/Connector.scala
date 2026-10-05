package kyo.internal.teams

import kyo.*
import scala.reflect.TypeTest

/** The one request path of the Bot Connector, on the config, HTTP client and token cache of a `Teams`.
  *
  * Every call is checked against `TeamsConfig.serviceHosts` before a token is attached, carries the token in `Authorization`, and is
  * read with `failOnError = false`, so the module maps the status, `Retry-After` and the body itself and no kyo-http status failure is
  * raised. Every request runs on the client's own `HttpClient` under a complete `HttpClientConfig` that replaces the caller's.
  *
  * Which leaf an error answer becomes on which operation is written once, as the traits the leaves mix in: `within` keeps the leaf an
  * answer names when it carries the operation's trait `F`, and otherwise answers [[kyo.TeamsOtherApiException]].
  */
private[kyo] object Connector:

    /** The leaves every Bot Connector call can fail with, whatever its route. */
    type Common = TokenCache.Failure | TeamsRefusedUrlException | TeamsBotNotRegisteredException | TeamsInvalidBotApiHostException |
        TeamsNotEnoughPermissionsException | TeamsOtherApiException

    /** Sends `body` with `method` to `segments` under `serviceUrl`, with `query`, and answers the 2xx body. `route` names the call in
      * every failure.
      */
    def call[F >: Common](
        teams: Teams,
        route: String,
        method: Teams.Method,
        serviceUrl: Teams.ServiceUrl,
        segments: Chunk[String],
        query: HttpQueryParams = HttpQueryParams.empty,
        body: Maybe[String] = Absent
    )(using Frame, TypeTest[TeamsException, F]): String < (Async & Abort[F]) =
        val config = teams.config
        if !ServiceUrls.allowed(config.serviceHosts, serviceUrl.url) then Abort.fail(TeamsRefusedUrlException(route))
        else
            val url                                                             = ServiceUrls.join(serviceUrl.url, segments)
            def attempt(schedule: Maybe[Schedule]): String < (Async & Abort[F]) =
                teams.tokens.get.map { token =>
                    val headers = HttpHeaders.empty.add("Authorization", s"Bearer ${token.value}").add("Content-Type", "application/json")
                    transport(config, teams.http, route, url, config.requestTimeout)(send(method, url, headers, query, body)).map {
                        response =>
                            val status     = response.status
                            val retryAfter = response.headers.get("Retry-After").flatMap(parseRetryAfter)
                            if status.isSuccess then response.fields.body
                            else
                                retry(schedule, config.retryMaxDelay, status, retryAfter).map {
                                    case Present(next) => attempt(Present(next))
                                    case Absent        =>
                                        val operationId =
                                            response.headers.get("X-Correlating-OperationId").map(TeamsException.bounded(_, 128))
                                        Abort.fail(failure[F](teams, token, route, status, retryAfter, operationId, response.fields.body))
                                }
                            end if
                    }
                }
            attempt(config.retry)
        end if
    end call

    /** Waits before the next attempt and answers its schedule, when `status` is one Microsoft says to retry, `schedule` has a step left,
      * and the answer asks for no wait beyond `maxDelay`. The wait is the schedule's delay or `Retry-After`, whichever is longer, and at
      * most `maxDelay`.
      */
    private[teams] def retry(schedule: Maybe[Schedule], maxDelay: Duration, status: HttpStatus, retryAfter: Maybe[Duration])(using
        Frame
    ): Maybe[Schedule] < Async =
        schedule match
            case Present(s) if Retried.contains(status.code) && !retryAfter.exists(_ > maxDelay) =>
                Clock.now.map { now =>
                    s.next(now) match
                        case Present((delay, next)) =>
                            val wait = retryAfter.filter(_ > delay).getOrElse(delay)
                            Async.sleep(if wait > maxDelay then maxDelay else wait).andThen(Present(next))
                        case Absent => Absent
                }
            case _ => Absent

    /** The statuses Microsoft says to retry: 412, 429, 502 and 504 in the rate-limit page, 503 in the conversational API's table. */
    private val Retried: Set[Int] = Set(412, 429, 502, 503, 504)

    private def send(
        method: Teams.Method,
        url: HttpUrl,
        headers: HttpHeaders,
        query: HttpQueryParams,
        body: Maybe[String]
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpException]) =
        method match
            case Teams.Method.Get    => HttpClient.getTextResponse(url, headers, query, failOnError = false)
            case Teams.Method.Delete => HttpClient.deleteTextResponse(url, headers, query, failOnError = false)
            case Teams.Method.Post   => HttpClient.postTextResponse(url, body.getOrElse(""), headers, query, failOnError = false)
            case Teams.Method.Put    => HttpClient.putTextResponse(url, body.getOrElse(""), headers, query, failOnError = false)
            case Teams.Method.Patch  => HttpClient.patchTextResponse(url, body.getOrElse(""), headers, query, failOnError = false)

    private def failure[F >: Common](
        teams: Teams,
        token: AccessToken,
        route: String,
        status: HttpStatus,
        retryAfter: Maybe[Duration],
        operationId: Maybe[String],
        body: String
    )(using Frame, TypeTest[TeamsException, F]): F =
        val secrets = Chunk(token.value) ++
            (teams.config.credential match
                case TeamsConfig.Credential.Secret(secret)             => Chunk(secret.value)
                case TeamsConfig.Credential.ManagedIdentity(_, header) => Chunk(header.value)
                // A federated assertion is computed per fetch and held nowhere, so there is no text to compare against.
                case _: TeamsConfig.Credential.Federated => Chunk.empty)
        Json.decode[Wire.ErrorResponse](body) match
            case Result.Success(answer) =>
                val description = redact(secrets, answer.error.message.getOrElse(""))
                if status == HttpStatus.TooManyRequests then
                    TeamsRateLimitException(route, retryAfter, Present(TeamsException.bounded(answer.error.code, 64)), operationId)
                else within[F](route, status, answer.error.code, description, operationId)
            case _ =>
                if status == HttpStatus.TooManyRequests then TeamsRateLimitException(route, retryAfter, Absent, operationId)
                else
                    blocked(body) match
                        case Present(message) if status.code == 403 =>
                            within[F](route, status, MessageWritesBlocked, redact(secrets, message), operationId)
                        case _ => TeamsUnexpectedStatusException(route, status)
        end match
    end failure

    private inline val MessageWritesBlocked = "MessageWritesBlocked"

    /** The text of a `MessageWritesBlocked` answer, whose `message` is itself JSON holding the `subCode`. */
    private def blocked(body: String)(using Frame): Maybe[String] =
        Json.decode[Wire.BlockedResponse](body) match
            case Result.Success(answer) =>
                Json.decode[Wire.BlockedMessage](answer.message) match
                    case Result.Success(inner) if inner.subCode == MessageWritesBlocked => Present(inner.message.getOrElse(""))
                    case _                                                              => Absent
            case _ => Absent

    /** The leaf an `ErrorResponse` names, kept when the operation `F` can receive it and otherwise the catch-all. */
    private def within[F >: Common](route: String, status: HttpStatus, code: String, description: String, operationId: Maybe[String])(
        using
        Frame,
        TypeTest[TeamsException, F]
    ): F =
        val leaf: TeamsException = (status.code, code) match
            case (400, "BadArgument" | "Bad Argument")   => TeamsBadArgumentException(route, description, operationId)
            case (401, "BotNotRegistered")               => TeamsBotNotRegisteredException(route, description, operationId)
            case (403, "BotDisabledByAdmin")             => TeamsBotDisabledByAdminException(route, description, operationId)
            case (403, "BotNotInConversationRoster")     => TeamsBotNotInConversationException(route, description, operationId)
            case (403, "ConversationBlockedByUser")      => TeamsConversationBlockedByUserException(route, description, operationId)
            case (403, "ForbiddenOperationException")    => TeamsNotInstalledException(route, description, operationId)
            case (403, "InvalidBotApiHost")              => TeamsInvalidBotApiHostException(route, description, operationId)
            case (403, "NotEnoughPermissions")           => TeamsNotEnoughPermissionsException(route, description, operationId)
            case (403, MessageWritesBlocked)             => TeamsMessageWritesBlockedException(route, description, operationId)
            case (404, "ActivityNotFoundInConversation") => TeamsActivityNotFoundException(route, description, operationId)
            case (404, "ConversationNotFound")           => TeamsConversationNotFoundException(route, description, operationId)
            case (412, "PreconditionFailed")             => TeamsPreconditionFailedException(route, description, operationId)
            case (413, "MessageSizeTooBig")              => TeamsMessageTooLargeException(route, description, operationId)
            case _ => TeamsOtherApiException(route, status, TeamsException.bounded(code, 128), description, operationId)
        leaf match
            case named: F => named
            case _        => TeamsOtherApiException(route, status, TeamsException.bounded(code, 128), description, operationId)
    end within

    /** `text` with every secret the request carried replaced, raw and percent-encoded, for a server that echoes the request. */
    def redact(secrets: Chunk[String], text: String): String =
        secrets.filter(_.nonEmpty).flatMap(s => Chunk(s, ServiceUrls.encode(s))).foldLeft(text)(_.replace(_, "<redacted>"))

    /** `Retry-After` as `delay-seconds` (RFC 9110): one to nine ASCII digits, with optional whitespace around them. Anything else,
      * an HTTP date included, carries no delay the module reads, so it is `Absent`.
      */
    def parseRetryAfter(raw: String): Maybe[Duration] =
        val value = raw.trim
        if value.isEmpty || value.length > 9 || !value.forall(c => c >= '0' && c <= '9') then Absent
        else Present(value.toLong.seconds)
    end parseRetryAfter

    /** The whole kyo-http configuration of a request that carries a credential; nothing is inherited from the caller. */
    def requestConfig(config: TeamsConfig, timeout: Duration): HttpClientConfig =
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
            maxResponseLength = StreamCoreExtensions.readBufferCapacity(config.maxResponseLength),
            autoFilters = false,
            clientFilter = HttpFilter.noop
        )

    /** Runs a kyo-http request on `http` and describes its failure without keeping it. */
    def transport[A](config: TeamsConfig, http: HttpClient, route: String, url: HttpUrl, timeout: Duration)(
        request: => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[TeamsTransportException]) =
        transportWith(requestConfig(config, timeout), http, route, url)(request)

    /** `transport` under `httpConfig`. */
    def transportWith[A](httpConfig: HttpClientConfig, http: HttpClient, route: String, url: HttpUrl)(
        request: => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[TeamsTransportException]) =
        val host = url.host
        val port = url.port
        Abort.runWith[HttpException](HttpClient.let(http)(HttpClient.withConfig(httpConfig)(request))) {
            case Result.Success(a) => a
            case Result.Failure(e) =>
                describe(e) match
                    case Present((kind, timeout, cause)) => Abort.fail(TeamsTransportException(route, kind, host, port, timeout)(cause))
                    // The class name only: a kyo-http failure carries the request's URL.
                    case Absent => bug(s"${e.getClass.getSimpleName} reached a Teams call")
            case Result.Panic(e) => Abort.panic(e)
        }
    end transportWith

    /** The kind, the timeout that ran out, and kyo-net's cause, of every kyo-http leaf a call of this module can receive. The cause is
      * kept only for a connection that could not be made, whose kyo-http leaf holds a host and a port and no URL. `Absent` is a leaf
      * the module's calls cannot produce (a URL or header the module built that kyo-http refused, a redirect, a status or typed-body
      * leaf, a server leaf): a module bug. No arm is a wildcard, so a new kyo-http leaf does not compile until it is placed.
      */
    def describe(e: HttpException): Maybe[(TeamsTransportException.Kind, Maybe[Duration], Maybe[kyo.net.NetException])] =
        import TeamsTransportException.Kind
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
            case _: HttpConnectionClosedException => Present((Kind.ConnectionClosed, Absent, Absent))
            case _: HttpProtocolException         => Present((Kind.Protocol, Absent, Absent))
            case _: HttpMalformedBodyException    => Present((Kind.Protocol, Absent, Absent))
            case e: HttpPayloadTooLargeException  => Present((Kind.PayloadTooLarge(e.bodySize.bytes, e.maxSize.bytes), Absent, Absent))
            case _: (HttpUrlParseException | HttpNonAsciiException | HttpInvalidFieldException | HttpWebSocketHandshakeException |
                    HttpUnixConnectException | HttpRedirectLoopException | HttpStatusException | HttpBindException | HttpHandlerException |
                    HttpFieldDecodeException | HttpPathDecodeException | HttpMissingFieldException |
                    HttpJsonDecodeException | HttpFormDecodeException | HttpUnsupportedMediaTypeException | HttpStreamingDecodeException |
                    HttpMissingBoundaryException) => Absent
        end match
    end describe

end Connector
