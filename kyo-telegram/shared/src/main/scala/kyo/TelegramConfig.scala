package kyo

/** Everything a bot needs to call the Bot API and to poll it for updates: the token, the server's base
  * URL, the long-polling parameters, the request limits, and the schedule `receive` retries on.
  *
  * `baseUrl` is Telegram's server by default. A self-hosted Bot API server replaces it, which lifts
  * some limits (file sizes, webhook ports). Every call goes to `{baseUrl}/bot{token}/{method}`.
  *
  * `pollTimeout` is how long one `getUpdates` waits on the server for an update before it answers
  * empty. Telegram takes it in whole seconds and asks for a positive value, so a timeout of zero, of
  * a fraction of a second or beyond `Int.MaxValue` seconds is refused rather than rounded. Telegram
  * documents no default beyond short polling; the default, 30 seconds, keeps a poll with `requestTimeout`
  * added at 40 seconds, under the 60-second idle timeout that proxies and load balancers commonly
  * default to (nginx's `proxy_read_timeout`, an AWS Application Load Balancer), while an idle bot makes
  * two requests a minute. `pollLimit` is the most updates one `getUpdates` returns, 1 to 100.
  * `allowedUpdates` chooses the update kinds `receive` gets; `Absent` keeps the bot's previous choice.
  *
  * `requestTimeout` bounds every API call, whole, and is added to `pollTimeout` for a long poll, so a
  * poll that ends normally never times out. `transferTimeout` bounds instead the calls that move a
  * file's bytes: `download`, and a send that uploads a file (a file sent by id or by URL is fetched by
  * Telegram and stays an API call). Its default, 120 seconds, carries the largest download the Bot API
  * serves, 20 MB, at about 1.4 Mbit/s, and the largest upload it accepts for a document, 50 MB, at about
  * 3.4 Mbit/s; a bot moving large files over a slower link raises it. `connectTimeout` bounds opening a
  * connection; the default, 10 seconds, leaves room for three retransmitted SYNs (RFC 6298's 1-second
  * initial retransmission timeout, doubling: sent at 0, 1, 3 and 7 seconds). `maxResponseLength` bounds
  * an answer's body; its default, 20 MiB, covers the largest file the Bot API serves for download (20 MB).
  *
  * `retrySchedule` paces the retries of `receive` after a transport failure, a server error or a rate limit
  * without a delay; a delay Telegram sent is waited instead. The default starts at 1 second so a brief
  * outage costs about a second, doubles so a long one costs few requests (five retries in the first
  * minute), and caps at 60 seconds so the bot resumes within a minute of Telegram recovering.
  *
  * `retry` is the verbs' own retry, off by default. When set, a verb whose answer is Telegram's flood control
  * (`parameters.retry_after`, the one retry the Bot API documents) is sent again after the delay Telegram named, for
  * as many attempts as the schedule allows; the schedule's own delays are not used. Nothing else is retried. A retried
  * send whose first attempt Telegram did carry out is delivered twice, so a bot that sets it accepts duplicates.
  * `receive`'s polling never uses it. `retryMaxDelay` is the longest delay `retry` waits: a flood-control answer naming a
  * longer one fails the call with [[kyo.TelegramRateLimitException]] and its `retryAfter`, so one call never blocks for
  * the hours a flood wait can reach. The default, 60 seconds, is the cap of `retrySchedule`.
  *
  * `tls` and `transport` are the TLS and byte-transport settings of the module's own HTTP clients, fixed
  * when a client is built; `tls` is how a bot trusts a self-hosted Bot API server whose certificate the
  * platform does not. The module's HTTP client uses these settings and nothing of the caller's kyo-http
  * configuration: no ambient filter, TLS setting, transport setting, retry or redirect applies to a
  * request that carries the token.
  *
  * IMPORTANT: `init` refuses a config that holds a value Telegram cannot use with a
  * [[kyo.TelegramInvalidConfigException]] naming the first such setting. The rendered config never shows
  * the token, whose `toString` is redacted.
  *
  * @see
  *   [[kyo.Telegram.Token]] the token held here
  */
final case class TelegramConfig private[kyo] (
    token: Telegram.Token,
    baseUrl: HttpUrl,
    pollTimeout: Duration,
    pollLimit: Int,
    allowedUpdates: Maybe[Chunk[Telegram.Update.Type]],
    requestTimeout: HttpClientConfig.TimeLimit,
    transferTimeout: HttpClientConfig.TimeLimit,
    connectTimeout: HttpClientConfig.TimeLimit,
    maxResponseLength: HttpClientConfig.ResponseLimit,
    retrySchedule: Schedule,
    retry: Maybe[Schedule],
    retryMaxDelay: Duration,
    tls: HttpTlsConfig,
    transport: HttpTransportConfig
) derives CanEqual

object TelegramConfig:

    /** The config, or a [[kyo.TelegramInvalidConfigException]] naming the first setting Telegram cannot use, in declaration order. */
    def init(
        token: Telegram.Token,
        baseUrl: HttpUrl = TelegramServer,
        pollTimeout: Duration = 30.seconds,
        pollLimit: Int = 100,
        allowedUpdates: Maybe[Chunk[Telegram.Update.Type]] = Absent,
        requestTimeout: Duration = 10.seconds,
        transferTimeout: Duration = 120.seconds,
        connectTimeout: Duration = 10.seconds,
        maxResponseLength: ByteSize = 20.mib,
        retrySchedule: Schedule = Schedule.exponentialBackoff(1.second, 2.0, 60.seconds),
        retry: Maybe[Schedule] = Absent,
        retryMaxDelay: Duration = 60.seconds,
        tls: HttpTlsConfig = HttpTlsConfig.default,
        transport: HttpTransportConfig = HttpTransportConfig.default
    )(using Frame): Result[TelegramInvalidConfigException, TelegramConfig] =
        import TelegramInvalidConfigException.Problem
        def refuse(problem: Maybe[Problem]): Result[Problem, Unit] = problem.fold(Result.unit)(Result.fail(_))
        val checked: Result[Problem, TelegramConfig]               =
            for
                _        <- refuse(urlProblemOf(baseUrl).map(Problem.BaseUrl(_)))
                _        <- refuse(if validPollTimeout(pollTimeout) then Absent else Present(Problem.PollTimeout(pollTimeout)))
                _        <- refuse(if pollLimit >= 1 && pollLimit <= 100 then Absent else Present(Problem.PollLimit(pollLimit)))
                request  <- timeLimit(requestTimeout)(Problem.RequestTimeout(_))
                transfer <- timeLimit(transferTimeout)(Problem.TransferTimeout(_))
                connect  <- timeLimit(connectTimeout)(Problem.ConnectTimeout(_))
                response <- responseLimit(maxResponseLength)(Problem.MaxResponseLength(_, MaxResponseLength))
                _        <- refuse(if positiveFinite(retryMaxDelay) then Absent else Present(Problem.RetryMaxDelay(retryMaxDelay)))
            yield new TelegramConfig(
                token,
                baseUrl,
                pollTimeout,
                pollLimit,
                allowedUpdates,
                request,
                transfer,
                connect,
                response,
                retrySchedule,
                retry,
                retryMaxDelay,
                tls,
                transport
            )
        checked.mapFailure(TelegramInvalidConfigException(_))
    end init

    /** Telegram's own Bot API server, `https://api.telegram.org`. */
    val TelegramServer: HttpUrl = HttpUrl(Present("https"), "api.telegram.org", 443, "/", Absent)

    /** The largest `maxResponseLength`, the most kyo-http can hold. */
    val MaxResponseLength: ByteSize = Int.MaxValue.bytes

    /** `d` as kyo-http's limit, which refuses zero; an unbounded call is refused too, since no call to the Bot API should hang forever. */
    private def timeLimit(d: Duration)(problem: Duration => TelegramInvalidConfigException.Problem)(using
        Frame
    ): Result[TelegramInvalidConfigException.Problem, HttpClientConfig.TimeLimit] =
        if d.isFinite then HttpClientConfig.TimeLimit.init(d).mapFailure(_ => problem(d)) else Result.fail(problem(d))

    /** `size` as kyo-http's limit, which refuses less than a byte; past `MaxResponseLength` no `Int` holds it. */
    private def responseLimit(size: ByteSize)(problem: ByteSize => TelegramInvalidConfigException.Problem)(using
        Frame
    ): Result[TelegramInvalidConfigException.Problem, HttpClientConfig.ResponseLimit] =
        if size.toBytes > MaxResponseLength.toBytes then Result.fail(problem(size))
        else HttpClientConfig.ResponseLimit.init(size.toBytes.toInt).mapFailure(_ => problem(size))

    private def positiveFinite(d: Duration): Boolean = d > Duration.Zero && d.isFinite

    private def urlProblemOf(url: HttpUrl): Maybe[TelegramInvalidConfigException.UrlProblem] =
        import TelegramInvalidConfigException.UrlProblem
        absoluteProblemOf(url)
            .orElse(if url.host.contains('@') then Present(UrlProblem.UserInfo) else Absent)
            .orElse(if url.rawQuery.nonEmpty then Present(UrlProblem.Query) else Absent)
            .orElse(if url.path != "/" && url.path.endsWith("/") then Present(UrlProblem.TrailingSlash) else Absent)
            .orElse {
                // kyo-http refuses a request whose URL holds such a character, a failure no call could recover from.
                val at = url.full.indexWhere(c => c <= ' ' || c > '~')
                if at >= 0 then Present(UrlProblem.Character(at)) else Absent
            }
    end urlProblemOf

    /** Why `url` is not an absolute http or https URL on a TCP host, the only URLs the module sends to. */
    private[kyo] def absoluteProblemOf(url: HttpUrl): Maybe[TelegramInvalidConfigException.UrlProblem] =
        import TelegramInvalidConfigException.UrlProblem
        def asciiLower(s: String): String = s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)
        if !url.scheme.map(asciiLower).exists(s => s == "http" || s == "https") then Present(UrlProblem.Scheme)
        else if url.host.isEmpty then Present(UrlProblem.Host)
        else if url.unixSocket.nonEmpty then Present(UrlProblem.UnixSocket)
        else Absent
        end if
    end absoluteProblemOf

    /** `baseUrl` with `segments` appended to its path, where the Bot API's own paths start. */
    private[kyo] def under(baseUrl: HttpUrl, segments: String): HttpUrl =
        baseUrl.copy(path = (if baseUrl.path == "/" then "" else baseUrl.path) + segments)

    private def validPollTimeout(value: Duration): Boolean =
        value >= 1.second && wholeSeconds(value)

    /** Telegram takes durations as whole seconds in an `Int`: from zero to `Int.MaxValue` seconds, with no fraction. */
    private[kyo] def wholeSeconds(value: Duration): Boolean =
        value >= Duration.Zero && value <= Int.MaxValue.toLong.seconds && value.toNanos % 1.second.toNanos == 0

end TelegramConfig
