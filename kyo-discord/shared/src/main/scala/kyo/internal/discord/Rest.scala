package kyo.internal.discord

import java.nio.charset.StandardCharsets.UTF_8
import kyo.*
import scala.reflect.TypeTest

/** The one request path of Discord's REST API, on the config and HTTP client of a `Discord`.
  *
  * Every call is read with `failOnError = false`, so the module maps the status, the rate-limit headers and the body itself and no
  * kyo-http status failure is raised. Every request runs on the client's own `HttpClient` under a complete `HttpClientConfig` that
  * replaces the caller's: a caller's filter would see the token, a caller's TLS setting would govern the token's connection, and a
  * caller's redirect or retry setting would send the request again or elsewhere.
  *
  * An interaction route holds the interaction token in its path, so a kyo-http failure, whose URL holds the path, is never kept: it is
  * described by a [[kyo.DiscordTransportException]]'s fields and dropped. A failure names the call by its route template, never its URL.
  *
  * Which leaf an error answer becomes on which operation is written once, as the traits the leaves mix in: `within` keeps the leaf a
  * code names when it carries the operation's trait `F`, and otherwise answers [[kyo.DiscordOtherApiException]].
  */
private[kyo] object Rest:

    /** The leaves every REST call can fail with, whatever its route. */
    type Common = DiscordTransportException | DiscordUnexpectedStatusException | DiscordDecodeException | DiscordRateLimitException |
        DiscordUnauthorizedException | DiscordOtherApiException

    /** What a call sends: nothing, a JSON body, or a message with files as `multipart/form-data`. */
    enum Body derives CanEqual:
        case Empty
        case Json(text: String)
        case Form(payloadJson: String, files: Chunk[Discord.File])
    end Body

    /** One call: its HTTP method, its route template (which names it in every failure), its path under the base URL, its query and body,
      * the interaction token its path holds, and whether `DiscordConfig.retry` applies to it.
      */
    final case class Call(
        method: HttpMethod,
        route: String,
        path: String,
        query: Seq[(String, String)] = Seq.empty,
        body: Body = Body.Empty,
        interaction: Maybe[Discord.InteractionToken] = Absent,
        retried: Boolean = true
    ):
        def name: String = s"${method.name} $route"
    end Call

    // --- Calls ---

    /** Sends `call` with the client in the environment and decodes a 2xx answer as `Out`; an empty answer decodes as JSON `null`. */
    def call[Out: Schema, F >: Common](call: Call)(using Frame, TypeTest[DiscordException, F]): Out < (Async & Abort[F] & Env[Discord]) =
        Env.use[Discord](discord =>
            run[Out, F](discord, call)(text =>
                if text.isEmpty then Structure.decode[Out](Structure.Value.Null) else kyo.Json.decode[Out](text)
            )
        )

    /** Sends `call` and ignores a 2xx answer's body. */
    def acknowledged[F >: Common](call: Call)(using Frame, TypeTest[DiscordException, F]): Unit < (Async & Abort[F] & Env[Discord]) =
        Env.use[Discord](discord => run[Unit, F](discord, call)(_ => Result.unit))

    /** The answer to an interaction, `POST /interactions/{id}/{token}/callback`. Never retried: Discord takes one answer, within three
      * seconds of the event (`interactions/receiving-and-responding.mdx`, "Interaction Callback").
      */
    def callback(interaction: Discord.Interaction.Ref, response: Discord.InteractionResponse)(using
        Frame
    ): Unit < (Async & Abort[DiscordException] & Env[Discord]) =
        acknowledged[DiscordException](Call(
            HttpMethod.POST,
            "/interactions/{interaction.id}/{interaction.token}/callback",
            path("interactions", WireField.renderSnowflake(interaction.id.value), interaction.token.value, "callback"),
            body = answerBody(response),
            interaction = Present(interaction.token),
            retried = false
        ))
    end callback

    /** An interaction's answer as a body: JSON, or the multipart form when the answer is a message with files. */
    def answerBody(response: Discord.InteractionResponse)(using Frame): Body =
        val files = response match
            case Discord.InteractionResponse.Message(create) => create.files
            case _                                           => Chunk.empty
        messageBody(response, files, inData = true)
    end answerBody

    /** A message body: JSON, or with files the multipart form whose `payload_json` names each file under `attachments` (in `data` for
      * an interaction's answer), which carries its description.
      */
    def messageBody[A: Schema](value: A, files: Chunk[Discord.File], inData: Boolean = false)(using Frame): Body =
        val json = kyo.Json.encode(value)
        if files.isEmpty then Body.Json(json)
        else
            val attachments = Structure.Value.Sequence(files.zipWithIndex.map((file, n) =>
                Structure.Value.Record(
                    Chunk("id" -> Structure.Value.Integer(n.toLong), "filename" -> Structure.Value.Str(file.name)) ++
                        file.description.fold(Chunk.empty)(d => Chunk("description" -> Structure.Value.Str(d)))
                )
            ))
            def withAttachments(tree: Structure.Value): Structure.Value =
                tree match
                    case Structure.Value.Record(fields) =>
                        Structure.Value.Record(fields.filter(_._1 != "attachments") :+ ("attachments" -> attachments))
                    case other => other
            val tree = kyo.Json.decode[Structure.Value](json) match
                case Result.Success(t) => t
                // `json` is what kyo-schema's JSON writer just wrote.
                case _ => bug("the JSON the module wrote did not read back")
            val payload = tree match
                case Structure.Value.Record(fields) if inData =>
                    Structure.Value.Record(fields.map((k, v) => if k == "data" then k -> withAttachments(v) else k -> v))
                case other => withAttachments(other)
            Body.Form(kyo.Json.encode(payload), files)
        end if
    end messageBody

    // --- Paths ---

    /** The route path of `segments`, each already safe in a path. An empty segment would name a different route (`reactions//@me` read
      * as `reactions/@me`), and every value spliced into a path is non-empty by its type, so one is a module bug.
      */
    def path(segments: String*): String =
        if segments.exists(_.isEmpty) then bug("an empty path segment would name a different route")
        else segments.mkString("/", "/", "")

    /** `text` as one path segment: every UTF-8 byte outside RFC 3986's unreserved characters percent-encoded, so no character of it can
      * end the segment, start a query, or escape.
      */
    def encodeSegment(text: String): String =
        text.getBytes(UTF_8).iterator.map { b =>
            val c = (b & 0xff).toChar
            if (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~'
            then c.toString
            else s"%${Hex.charAt((b >> 4) & 0xf)}${Hex.charAt(b & 0xf)}"
            end if
        }.mkString
    end encodeSegment

    private inline val Hex = "0123456789ABCDEF"

    /** `path` under `base`, whose own path is the API's prefix. */
    def under(base: HttpUrl, path: String): HttpUrl =
        base.copy(path = (if base.path == "/" then "" else base.path) + path, rawQuery = Absent)

    // --- The request ---

    private def run[A, F >: Common](discord: Discord, call: Call)(decode: String => Result[DecodeException, A])(using
        Frame,
        TypeTest[DiscordException, F]
    ): A < (Async & Abort[F]) =
        val config  = discord.config
        val base    = under(config.baseUrl, call.path)
        val url     = if call.query.isEmpty then base else base.copy(rawQuery = Present(HttpQueryParams.init(call.query*).toQueryString))
        val secrets = Chunk(config.token.value) ++ call.interaction.map(_.value).toChunk
        val form: Maybe[Multipart.Body] = call.body match
            case Body.Form(json, files)    => Present(Multipart.encode(json, files))
            case Body.Empty | Body.Json(_) => Absent
        val content: Maybe[Span[Byte]] = call.body match
            case Body.Empty      => Absent
            case Body.Json(text) => Present(Span.from(text.getBytes(UTF_8)))
            case Body.Form(_, _) => form.map(_.bytes)
        val contentType: Maybe[String] = call.body match
            case Body.Empty      => Absent
            case Body.Json(_)    => Present("application/json")
            case Body.Form(_, _) => form.map(_.contentType)
        val timeout = if form.nonEmpty then config.transferTimeout else config.requestTimeout
        val headers = contentType.fold(HttpHeaders.empty)(t => HttpHeaders.empty.add("Content-Type", t))
            .add("Authorization", s"Bot ${config.token.value}")
            .add("User-Agent", UserAgent)
        val key = RateLimits.Key(call.method.name, call.route, RateLimits.resourceOf(call.path))
        def attempt(schedule: Maybe[Schedule]): A < (Async & Abort[F]) =
            discord.limits.acquire(key, global = call.interaction.isEmpty, timeout, call.name).andThen {
                transport(config, discord.http, call.name, timeout)(send(call.method, url, headers, content))
            }.map { response =>
                val text = new String(response.fields.body.toArray, UTF_8)
                discord.limits.record(key, RateLimits.Answer.of(response.headers)).andThen {
                    if response.status.isSuccess then
                        decode(text) match
                            case Result.Success(a)       => a
                            case Result.Failure(failure) =>
                                Abort.fail(DiscordDecodeException(call.name, DiscordDecodeException.Part.Response, failure))
                            case Result.Panic(ex) => Abort.panic(ex)
                    else
                        failureOf[F](call, secrets, response.status, response.headers, text).map { (failure, retryAfter) =>
                            retryWait(schedule, config.retryMaxDelay, response.status, retryAfter).map {
                                case Present(next) => attempt(Present(next))
                                case Absent        => Abort.fail(failure)
                            }
                        }
                    end if
                }
            }
        attempt(if call.retried then config.retry else Absent)
    end run

    /** Discord requires `DiscordBot ($url, $versionNumber)` on every request (`reference.mdx`, "User Agent"). */
    private[kyo] val UserAgent: String = s"DiscordBot (https://github.com/getkyo/kyo, ${DiscordVersion.value})"

    private def send(method: HttpMethod, url: HttpUrl, headers: HttpHeaders, content: Maybe[Span[Byte]])(using
        Frame
    ): HttpResponse["body" ~ Span[Byte]] < (Async & Abort[HttpException]) =
        val route = HttpRoute[Any, Any, Nothing](method, HttpRoute.RequestDef[Any]("")).response(_.bodyBinary)
        HttpClient.use { client =>
            content match
                case Present(bytes) =>
                    client.sendWith(
                        route.request(_.bodyBinary),
                        HttpRequest(method, url, headers, Record.empty).addField("body", bytes)
                    )(identity)
                case Absent =>
                    client.sendWith(route, HttpRequest(method, url, headers, Record.empty))(identity)
        }
    end send

    // --- Retry ---

    /** Waits before the next attempt and answers its schedule, when `status` is one Discord documents as retryable, `schedule` has a
      * step left, and the answer names no wait beyond `maxDelay`. The wait is the schedule's delay or the named wait, whichever is
      * longer, and at most `maxDelay`.
      *
      * Retryable: a 429 naming a wait ("Your application should rely on the `Retry-After` header or `retry_after` field to determine
      * when to retry the request", `topics/rate-limits.mdx`), and a 502 ("There was not a gateway available to process your request.
      * Wait a bit and retry.", `topics/opcodes-and-status-codes.mdx`, "HTTP Response Codes"). A 429 naming no wait gives nothing to
      * wait on; every other status carries no retry advice.
      */
    def retryWait(schedule: Maybe[Schedule], maxDelay: Duration, status: HttpStatus, retryAfter: Maybe[Duration])(using
        Frame
    ): Maybe[Schedule] < Async =
        val retryable = (status.code == 429 && retryAfter.nonEmpty) || status.code == 502
        schedule match
            case Present(s) if retryable && !retryAfter.exists(_ > maxDelay) =>
                Clock.now.map { now =>
                    s.next(now) match
                        case Present((delay, next)) =>
                            val wait = retryAfter.filter(_ > delay).getOrElse(delay)
                            Async.sleep(if wait > maxDelay then maxDelay else wait).andThen(Present(next))
                        case Absent => Absent
                }
            case _ => Absent
        end match
    end retryWait

    /** A 429's `retry_after`, float seconds, to the microsecond; a negative or non-finite value names no wait. */
    def retryAfterSeconds(seconds: Double): Maybe[Duration] =
        if seconds >= 0 && !seconds.isInfinite then Present(Math.round(seconds * 1e6).micros) else Absent

    /** `Retry-After` as `delay-seconds` (RFC 9110): one to nine ASCII digits, with optional whitespace around them. Anything else, an
      * HTTP date included, carries no delay the module reads. RFC 9110 bounds `delay-seconds` nowhere, so the parse does: nine digits
      * fit an `Int` and reach about 31 years.
      */
    def parseRetryAfter(raw: String): Maybe[Duration] =
        val value = raw.trim
        if value.isEmpty || value.length > 9 || !value.forall(c => c >= '0' && c <= '9') then Absent
        else Present(value.toLong.seconds)
    end parseRetryAfter

    // --- Answers ---

    /** The failure a non-2xx answer names, and the wait it names. */
    private def failureOf[F >: Common](
        call: Call,
        secrets: Chunk[String],
        status: HttpStatus,
        headers: HttpHeaders,
        body: String
    )(using Frame, TypeTest[DiscordException, F]): (F, Maybe[Duration]) < Abort[Nothing] =
        val method      = call.name
        val retryHeader = headers.get("Retry-After").flatMap(parseRetryAfter)
        // Discord's bodies are JSON objects; anything else (a Cloudflare page, an empty body) is not one of them.
        val fields: Maybe[Chunk[(String, Structure.Value)]] =
            kyo.Json.decode[Structure.Value](body) match
                case Result.Success(Structure.Value.Record(fields)) => Present(fields)
                case _                                              => Absent
        def holds(key: String): Boolean                                   = fields.exists(_.exists(_._1 == key))
        def undecodable(failure: DecodeException): DiscordDecodeException =
            DiscordDecodeException(method, DiscordDecodeException.Part.Error, failure)
        def tree: Structure.Value = Structure.Value.Record(fields.getOrElse(Chunk.empty))
        if status.code == 429 then
            if !holds("retry_after") then (DiscordBlockedException(method, retryHeader), retryHeader)
            else
                Structure.decode[Frames.RateLimitBody](tree) match
                    case Result.Success(limit) =>
                        val retryAfter = retryAfterSeconds(limit.retryAfter).orElse(retryHeader)
                        val code       = limit.code.map(Discord.Code(_))
                        val scope      = headers.get("X-RateLimit-Scope")
                        val bucket     = headers.get("X-RateLimit-Bucket")
                        val global     = limit.global || scope.contains("global") ||
                            headers.get("X-RateLimit-Global").exists(_.equalsIgnoreCase("true"))
                        val leaf =
                            if global then DiscordGlobalRateLimitException(method, retryAfter, code)
                            else if scope.contains("shared") then DiscordSharedRateLimitException(method, retryAfter, code, bucket)
                            else DiscordRouteRateLimitException(method, retryAfter, code, bucket)
                        (leaf, retryAfter)
                    case Result.Failure(failure) => (undecodable(failure), retryHeader)
                    case Result.Panic(ex)        => Abort.panic(ex)
            end if
        else if !holds("code") then (DiscordUnexpectedStatusException(method, status), retryHeader)
        else
            Structure.decode[Frames.ErrorBody](tree) match
                case Result.Success(error) =>
                    val description = redact(secrets, error.message)
                    val leaf        =
                        leafFor(call, status, error.code, description, error.errors.fold(Chunk.empty)(fieldErrors(_, redact(secrets, _))))
                    (within[F](method, status, error.code, description, leaf), retryHeader)
                case Result.Failure(failure) => (undecodable(failure), retryHeader)
                case Result.Panic(ex)        => Abort.panic(ex)
        end if
    end failureOf

    /** The leaf Discord's error code names (1.3 of the design note); a code without one is the catch-all. */
    private def leafFor(
        call: Call,
        status: HttpStatus,
        code: Int,
        d: String,
        fieldErrors: => Chunk[DiscordInvalidFormBodyException.FieldError]
    )(
        using Frame
    ): DiscordException =
        val m = call.name
        // "Interaction tokens are valid for 15 minutes": these codes mean an expired token only on a route that holds one. Checked
        // before the status, since Discord answers 50027 "Invalid Webhook Token" with 401, which otherwise means the bot token.
        if (code == 10015 || code == 50027) && call.interaction.nonEmpty then DiscordInteractionExpiredException(m, Discord.Code(code), d)
        else if status.code == 401 then DiscordUnauthorizedException(m, Discord.Code(code), d)
        else
            code match
                case 50013                                  => DiscordMissingPermissionsException(m, d)
                case 50001                                  => DiscordMissingAccessException(m, d)
                case 10003                                  => DiscordUnknownChannelException(m, d)
                case 10008                                  => DiscordUnknownMessageException(m, d)
                case 10014                                  => DiscordUnknownEmojiException(m, d)
                case 10013                                  => DiscordUnknownUserException(m, d)
                case 10007                                  => DiscordUnknownMemberException(m, d)
                case 10004                                  => DiscordUnknownGuildException(m, d)
                case 50007                                  => DiscordCannotMessageUserException(m, d)
                case 50035                                  => DiscordInvalidFormBodyException(m, d, fieldErrors)
                case 200000 | 200001 | 240000               => DiscordBlockedByModerationException(m, Discord.Code(code), d)
                case 30010 | 30015 | 30032 | 30034 | 160006 => DiscordMaximumReachedException(m, Discord.Code(code), d)
                case 160004                                 => DiscordThreadAlreadyExistsException(m, d)
                case 50083 | 160005                         => DiscordThreadClosedException(m, Discord.Code(code), d)
                case 40005 | 50045                          => DiscordPayloadTooLargeException(m, Discord.Code(code), d)
                case _                                      => DiscordOtherApiException(m, status, Discord.Code(code), d)
        end if
    end leafFor

    private def within[F >: DiscordOtherApiException](
        method: String,
        status: HttpStatus,
        code: Int,
        description: String,
        leaf: DiscordException
    )(
        using
        Frame,
        TypeTest[DiscordException, F]
    ): F =
        leaf match
            case named: F => named
            case _        => DiscordOtherApiException(method, status, Discord.Code(code), description)

    /** Discord's `errors` tree flattened to one entry per refusal: each `_errors` array's `{code, message}` objects, with the path of keys
      * to it. Anything of another shape is not a refusal.
      */
    def fieldErrors(errors: Structure.Value, redact: String => String): Chunk[DiscordInvalidFormBodyException.FieldError] =
        def walk(value: Structure.Value, path: Chunk[String]): Chunk[DiscordInvalidFormBodyException.FieldError] =
            value match
                case Structure.Value.Record(fields) =>
                    fields.flatMap {
                        case ("_errors", Structure.Value.Sequence(refusals)) =>
                            refusals.flatMap {
                                case Structure.Value.Record(entry) =>
                                    val code    = Maybe.fromOption(entry.collectFirst { case ("code", Structure.Value.Str(c)) => c })
                                    val message = Maybe.fromOption(entry.collectFirst { case ("message", Structure.Value.Str(m)) => m })
                                    code.flatMap(c => message.map(m => DiscordInvalidFormBodyException.FieldError(path, c, redact(m))))
                                        .fold(Chunk.empty)(Chunk(_))
                                case _ => Chunk.empty
                            }
                        case ("_errors", _) => Chunk.empty
                        case (key, child)   => walk(child, path :+ key)
                    }
                case _ => Chunk.empty
        walk(errors, Chunk.empty)
    end fieldErrors

    /** `text` with every non-empty secret replaced, for a server that echoes the request's path or headers. */
    def redact(secrets: Chunk[String], text: String): String =
        secrets.filter(_.nonEmpty).foldLeft(text)(_.replace(_, "<redacted>"))

    // --- Transport ---

    /** The whole kyo-http configuration of a request that carries the token; nothing is inherited from the caller. TLS and transport
      * come from `DiscordConfig`, never from the caller's kyo-http configuration.
      */
    private[kyo] def requestConfig(config: DiscordConfig, timeout: Duration): HttpClientConfig =
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
    private def transport[A](config: DiscordConfig, http: HttpClient, method: String, timeout: Duration)(
        request: => A < (Async & Abort[HttpException])
    )(using Frame): A < (Async & Abort[DiscordTransportException]) =
        val host = config.baseUrl.host
        val port = config.baseUrl.port
        Abort.runWith[HttpException](HttpClient.let(http)(HttpClient.withConfig(requestConfig(config, timeout))(request))) {
            case Result.Success(a) => a
            case Result.Failure(e) =>
                describe(e) match
                    case Present((kind, timeout, cause)) => Abort.fail(DiscordTransportException(method, kind, host, port, timeout)(cause))
                    // The class name only: the exception's URL may hold an interaction token.
                    case Absent => bug(s"${e.getClass.getSimpleName} reached a Discord REST call")
            case Result.Panic(e) => Abort.panic(e)
        }
    end transport

    /** The kind, the timeout that ran out, and kyo-net's cause, of every kyo-http leaf a REST call can receive. The cause is kept only
      * for a connection that could not be made, whose kyo-http leaf holds a host and a port and no URL. `Absent` is a leaf a REST call
      * cannot produce (a URL or header the module built that kyo-http refused, a redirect, a status or typed-body leaf, a server or
      * WebSocket leaf): a module bug. No arm is a wildcard, so a new kyo-http leaf does not compile until it is placed.
      */
    private[kyo] def describe(e: HttpException): Maybe[(DiscordTransportException.Kind, Maybe[Duration], Maybe[kyo.net.NetException])] =
        import DiscordTransportException.Kind
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
            // kyo-http bounds only the TCP connect by `connectTimeout`; a TLS handshake that gets no answer is a `Timeout` under
            // `requestTimeout`. This arm needs a SYN that gets no answer, which no local test produces, so it has no test.
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

end Rest
