package kyo

/** The configuration a [[kyo.Slack]] client is built from: the app-level and bot tokens, the
  * WebSocket keepalive interval, the per-envelope ack deadline, the reconnect policy, the Web API's
  * base url, and the bounds of every request. One value passed into `Slack.run`, `Slack.init` or
  * `Slack.initUnscoped`.
  *
  * `baseUrl` is Slack's Web API by default; every method goes to `{baseUrl}/{method}`, and
  * `apps.connections.open` answers the Socket Mode url. It must be an absolute http or https
  * url on a host in printable ASCII, with no user info, no query and no trailing slash, since the
  * token-bearing requests go to it and the config's rendering shows it.
  *
  * `requestTimeout` bounds each Web API call and `response_url` POST, `connectTimeout` each
  * connection the client opens (the socket's included), and `maxResponseLength` the body of an
  * answer the client reads. `maxResponseLength` is not refused: the client narrows it where it is used, zero to
  * one byte and past `Int.MaxValue` bytes to `Int.MaxValue`, the rule kyo-core applies to its own buffer sizes.
  *
  * `keepAliveInterval` maps to `HttpWebSocket.Config.autoPingInterval`: Socket Mode
  * defines no application-level keepalive distinct from WS ping/pong, so
  * this is the complete keepalive mechanism.
  *
  * `ackDeadline` is the per-envelope window within which the framework acks each
  * ackable envelope. Slack re-delivers an envelope it does not see acked within 3 seconds,
  * measured at Slack, so the default of 2.5 seconds leaves 500 ms for the frame's delivery
  * and the ack's return; a deadline of 3 seconds or more lets an ack sent on time arrive late.
  * The handler must return its `SlackAck` within this window: if
  * it does, the framework emits that ack; if it does not, the framework emits the bare
  * ack (`SlackAck.Ack`) when the deadline fires and race-cancels the still-running
  * handler, so no late payload ack is sent (the bare ack already went out). Exactly one
  * ack is emitted per ackable envelope. Long work therefore belongs on a fiber forked
  * from the handler, not inline in it.
  *
  * Every duration must be positive and finite. `init` checks every field and fails with
  * [[kyo.SlackInvalidConfigException]] naming the first problem, so an invalid config never reaches a
  * client. A setter named after a field returns the config with that field replaced: through the same
  * check, as a `Result`, for a bounded field, and directly for the tokens, `reconnect` and
  * `maxResponseLength`, which have no bounds.
  */
final case class SlackConfig private (
    appLevel: SlackToken.AppLevel,
    bot: SlackToken.Bot,
    keepAliveInterval: Maybe[Duration],
    ackDeadline: Duration,
    reconnect: SlackConfig.Reconnect,
    baseUrl: HttpUrl,
    requestTimeout: Duration,
    connectTimeout: Duration,
    maxResponseLength: ByteSize
) derives CanEqual:

    /** This config opening the socket with `appLevel`. */
    def appLevel(appLevel: SlackToken.AppLevel): SlackConfig = copy(appLevel = appLevel)

    /** This config calling the Web API with `bot`. */
    def bot(bot: SlackToken.Bot): SlackConfig = copy(bot = bot)

    /** This config with `keepAliveInterval`, or the problem. */
    def keepAliveInterval(keepAliveInterval: Maybe[Duration])(using Frame): Result[SlackInvalidConfigException, SlackConfig] =
        SlackConfig.checked(copy(keepAliveInterval = keepAliveInterval))

    /** This config with `ackDeadline`, or the problem. */
    def ackDeadline(ackDeadline: Duration)(using Frame): Result[SlackInvalidConfigException, SlackConfig] =
        SlackConfig.checked(copy(ackDeadline = ackDeadline))

    /** This config reconnecting on `reconnect`. */
    def reconnect(reconnect: SlackConfig.Reconnect): SlackConfig = copy(reconnect = reconnect)

    /** This config calling the Web API at `baseUrl`, or the problem. */
    def baseUrl(baseUrl: HttpUrl)(using Frame): Result[SlackInvalidConfigException, SlackConfig] =
        SlackConfig.checked(copy(baseUrl = baseUrl))

    /** This config with `requestTimeout`, or the problem. */
    def requestTimeout(requestTimeout: Duration)(using Frame): Result[SlackInvalidConfigException, SlackConfig] =
        SlackConfig.checked(copy(requestTimeout = requestTimeout))

    /** This config with `connectTimeout`, or the problem. */
    def connectTimeout(connectTimeout: Duration)(using Frame): Result[SlackInvalidConfigException, SlackConfig] =
        SlackConfig.checked(copy(connectTimeout = connectTimeout))

    /** This config reading answers of up to `maxResponseLength`. */
    def maxResponseLength(maxResponseLength: ByteSize): SlackConfig = copy(maxResponseLength = maxResponseLength)
end SlackConfig

object SlackConfig:

    /** A config for the two tokens, or the first problem among its fields. */
    def init(
        appLevel: SlackToken.AppLevel,
        bot: SlackToken.Bot,
        keepAliveInterval: Maybe[Duration] = Present(30.seconds),
        ackDeadline: Duration = 2500.millis,
        reconnect: Reconnect = Reconnect.Overlap,
        baseUrl: HttpUrl = SlackApi,
        requestTimeout: Duration = 10.seconds,
        connectTimeout: Duration = 10.seconds,
        maxResponseLength: ByteSize = 16.mb
    )(using Frame): Result[SlackInvalidConfigException, SlackConfig] =
        checked(new SlackConfig(
            appLevel,
            bot,
            keepAliveInterval,
            ackDeadline,
            reconnect,
            baseUrl,
            requestTimeout,
            connectTimeout,
            maxResponseLength
        ))

    private def checked(config: SlackConfig)(using Frame): Result[SlackInvalidConfigException, SlackConfig] =
        problemOf(config) match
            case Present(problem) => Result.fail(SlackInvalidConfigException(problem))
            case Absent           => Result.succeed(config)

    /** Slack's Web API, `https://slack.com/api`. */
    val SlackApi: HttpUrl = HttpUrl(Present("https"), "slack.com", 443, "/api", Absent)

    private def problemOf(config: SlackConfig): Maybe[SlackInvalidConfigException.Problem] =
        import SlackInvalidConfigException.Problem
        def invalid(value: Duration): Boolean                                    = value == Duration.Zero || value == Duration.Infinity
        def check(value: Duration, problem: Duration => Problem): Maybe[Problem] =
            if invalid(value) then Present(problem(value)) else Absent
        config.keepAliveInterval.filter(invalid).map(Problem.KeepAliveInterval(_))
            .orElse(check(config.ackDeadline, Problem.AckDeadline(_)))
            .orElse(check(config.requestTimeout, Problem.RequestTimeout(_)))
            .orElse(check(config.connectTimeout, Problem.ConnectTimeout(_)))
            .orElse(urlProblemOf(config.baseUrl).map(Problem.BaseUrl(_)))
    end problemOf

    private def urlProblemOf(url: HttpUrl): Maybe[SlackInvalidConfigException.UrlProblem] =
        import SlackInvalidConfigException.UrlProblem
        absoluteProblemOf(url)
            .orElse(if url.host.contains('@') then Present(UrlProblem.UserInfo) else Absent)
            .orElse(if url.rawQuery.nonEmpty then Present(UrlProblem.Query) else Absent)
            .orElse(if url.path != "/" && url.path.endsWith("/") then Present(UrlProblem.TrailingSlash) else Absent)
    end urlProblemOf

    /** Why `url` is not an absolute http or https url on a TCP host written in printable ASCII, the only urls the module sends to.
      * kyo-http resolves a url with no scheme against the client's configured base url and sends a unix-socket url to a local socket,
      * so either would reach a place the url does not name. kyo-http's parser accepts a host or path outside ASCII that its client then
      * refuses or resolves as written, so the url a peer supplies is checked here, before anything is sent.
      */
    private[kyo] def absoluteProblemOf(url: HttpUrl): Maybe[SlackInvalidConfigException.UrlProblem] =
        import SlackInvalidConfigException.UrlProblem
        def asciiLower(s: String): String = s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)
        if !url.scheme.map(asciiLower).exists(s => s == "http" || s == "https") then Present(UrlProblem.Scheme)
        else if url.host.isEmpty then Present(UrlProblem.Host)
        else if url.unixSocket.nonEmpty then Present(UrlProblem.UnixSocket)
        else
            val at = url.full.indexWhere(c => c < '!' || c > '~')
            if at >= 0 then Present(UrlProblem.Character(at)) else Absent
        end if
    end absoluteProblemOf

    /** `baseUrl` followed by `/method`. `method` is a validated [[kyo.SlackMethod]] or a name of the module's own, so it
      * adds one path segment and nothing else.
      */
    private[kyo] def methodUrl(baseUrl: HttpUrl, method: String): HttpUrl =
        baseUrl.copy(path = s"${baseUrl.path.stripSuffix("/")}/$method")

    /** The kyo-http config of every call a client makes, replacing the caller's. Every field is stated, so nothing of
      * a caller's config (a filter, a base url, a relaxed TLS, retries, redirects) reaches a request that carries a
      * credential. `tls = HttpTlsConfig.default` resolves to the client's own default TLS, never a caller's.
      *
      * Every value meets kyo-http's own requirements, so building it never throws: `init` admits only positive, finite
      * timeouts, and the response length is narrowed by kyo-core's buffer rule, zero to one byte and past `Int.MaxValue`
      * bytes to `Int.MaxValue`.
      */
    private[kyo] def httpConfig(config: SlackConfig): HttpClientConfig =
        HttpClientConfig(
            baseUrl = Absent,
            timeout = config.requestTimeout,
            connectTimeout = config.connectTimeout,
            followRedirects = false,
            maxRedirects = 0,
            retrySchedule = Absent,
            retryOn = _.isServerError,
            transportConfig = HttpTransportConfig.default,
            tls = HttpTlsConfig.default,
            maxResponseLength = readBufferCapacity(config.maxResponseLength),
            autoFilters = false,
            clientFilter = HttpFilter.noop
        )

    /** Reconnect policy on a routine `disconnect`.
      *   - `Overlap`: open the fresh connection and confirm it is live BEFORE
      *     stopping the old one, so no inbound envelope is lost across the rollover.
      *   - `Immediate`: close the old connection, then open the fresh one (a brief
      *     gap is accepted).
      *   - `Off`: do not reconnect; the loop ends cleanly on a routine disconnect, once the envelopes the connection
      *     already received are delivered and acked.
      *
      * `link_disabled` is terminal under every policy.
      */
    enum Reconnect derives CanEqual:
        case Overlap
        case Immediate
        case Off
    end Reconnect

end SlackConfig
