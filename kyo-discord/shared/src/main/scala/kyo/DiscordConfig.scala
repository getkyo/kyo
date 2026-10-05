package kyo

/** Everything a bot needs to call Discord's REST API and to hold a Gateway connection: the token, the intents and shard it
  * identifies with, the reconnect policy, the interaction deadline, the rate limits, the retry policy and the request limits.
  *
  * `intents` chooses the events the Gateway sends; `shard` is the one shard this client serves, `Absent` for a bot that does not
  * shard. `reconnect` decides what happens when the Gateway closes a connection it lets the client resume.
  *
  * `interactionDeadline` bounds a handler answering an interaction. Discord invalidates the token when no answer arrives within 3
  * seconds of the event (`interactions/receiving-and-responding.mdx`, "Interaction Callback"); the default, 2.5 seconds, leaves half a
  * second for the callback to reach Discord. It must be positive and under 3 seconds.
  *
  * `globalRateLimit` is the client's request rate across every route other than the interaction routes. 50 per second is the limit
  * Discord documents for every bot ("All bots can make up to 50 requests per second", `topics/rate-limits.mdx`); a bot Discord granted
  * more raises it.
  *
  * `retry` is `Absent` by default, and then no failed call is retried. Set, it retries exactly what Discord documents as retryable,
  * a 429 after the wait Discord names and a `502 GATEWAY UNAVAILABLE`, never the Gateway handshake and never an interaction's
  * callback. A retried send can be delivered twice when the first attempt reached Discord and its answer did not. No retry waits
  * longer than `retryMaxDelay`, 60 seconds by default: a 429 naming a longer wait is not retried, and its leaf carries that wait.
  *
  * `requestTimeout` bounds every REST call, whole; `transferTimeout` bounds instead a send with attachments, which may upload up to
  * the 25 MiB a message allows. `connectTimeout` bounds opening a connection; the default, 10 seconds, leaves room for three
  * retransmitted SYNs. `maxResponseLength` bounds an answer's body; its default, 8 MiB, holds the largest page `messages` returns,
  * 100 messages of 2000 characters and 10 embeds within their 6000-character total, at 4 bytes per character of UTF-8. It is never
  * refused: kyo-http holds the bound as an `Int`, so the request config narrows it by kyo-core's buffer rule, zero to one byte and past
  * `Int.MaxValue` to `Int.MaxValue`.
  *
  * `tls` and `transport` are the settings of the module's own HTTP client and of the Gateway's connection. Nothing of the caller's
  * kyo-http configuration reaches a request that carries the token.
  *
  * IMPORTANT: `init` refuses a value Discord or kyo-http cannot use with a [[kyo.DiscordInvalidConfigException]] naming the first
  * such setting. The rendered config never shows the token, whose `toString` is redacted.
  */
final case class DiscordConfig private[kyo] (
    token: Discord.Token,
    intents: Discord.Intents,
    shard: Maybe[Discord.Shard],
    reconnect: DiscordConfig.Reconnect,
    interactionDeadline: Duration,
    globalRateLimit: Int,
    retry: Maybe[Schedule],
    retryMaxDelay: Duration,
    baseUrl: HttpUrl,
    requestTimeout: Duration,
    transferTimeout: Duration,
    connectTimeout: Duration,
    maxResponseLength: ByteSize,
    tls: HttpTlsConfig,
    transport: HttpTransportConfig
) derives CanEqual

object DiscordConfig:

    /** The config, or a [[kyo.DiscordInvalidConfigException]] naming the first setting it cannot use, in declaration order. */
    def init(
        token: Discord.Token,
        intents: Discord.Intents,
        shard: Maybe[Discord.Shard] = Absent,
        reconnect: Reconnect = Reconnect.Resume(Schedule.exponentialBackoff(1.second, 2.0, 60.seconds)),
        interactionDeadline: Duration = 2500.millis,
        globalRateLimit: Int = 50,
        retry: Maybe[Schedule] = Absent,
        retryMaxDelay: Duration = 60.seconds,
        baseUrl: HttpUrl = DiscordApi,
        requestTimeout: Duration = 10.seconds,
        transferTimeout: Duration = 120.seconds,
        connectTimeout: Duration = 10.seconds,
        maxResponseLength: ByteSize = 8.mib,
        tls: HttpTlsConfig = HttpTlsConfig.default,
        transport: HttpTransportConfig = HttpTransportConfig.default
    )(using Frame): Result[DiscordInvalidConfigException, DiscordConfig] =
        import DiscordInvalidConfigException.Problem
        def refuse(problem: Maybe[Problem]): Result[Problem, Unit] = problem.fold(Result.unit)(Result.fail(_))
        val checked: Result[Problem, DiscordConfig]                =
            for
                _ <- refuse(urlProblemOf(baseUrl).map(Problem.BaseUrl(_)))
                _ <- refuse(
                    if interactionDeadline > Duration.Zero && interactionDeadline < InteractionDeadlineMax then Absent
                    else Present(Problem.InteractionDeadline(interactionDeadline, InteractionDeadlineMax))
                )
                _        <- refuse(if globalRateLimit >= 1 then Absent else Present(Problem.GlobalRateLimit(globalRateLimit)))
                _        <- refuse(if positiveFinite(retryMaxDelay) then Absent else Present(Problem.RetryMaxDelay(retryMaxDelay)))
                request  <- timeLimit(requestTimeout)(Problem.RequestTimeout(_))
                transfer <- timeLimit(transferTimeout)(Problem.TransferTimeout(_))
                connect  <- timeLimit(connectTimeout)(Problem.ConnectTimeout(_))
            yield new DiscordConfig(
                token,
                intents,
                shard,
                reconnect,
                interactionDeadline,
                globalRateLimit,
                retry,
                retryMaxDelay,
                baseUrl,
                request,
                transfer,
                connect,
                maxResponseLength,
                tls,
                transport
            )
        checked.mapFailure(DiscordInvalidConfigException(_))
    end init

    /** What the client does when the Gateway closes a connection.
      *
      * `Resume` reconnects on every close Discord lets the client recover from (a Reconnect request, a resumable close code, a
      * connection that stopped acknowledging heartbeats, an Invalid Session marked resumable): it resumes the session, replaying the
      * events it missed, and identifies afresh when Discord refuses the resume. Each reconnection after the first waits by `backoff`.
      * The default backoff starts at 1 second, doubles, and caps at 60 seconds.
      *
      * `Off` ends `receive` cleanly on such a close. A close no reconnect can recover from, such as a refused token, ends it with its
      * failure under either policy.
      */
    enum Reconnect derives CanEqual:
        case Resume(backoff: Schedule)
        case Off
    end Reconnect

    /** Discord's API, version 10, `https://discord.com/api/v10`. */
    val DiscordApi: HttpUrl = HttpUrl(Present("https"), "discord.com", 443, "/api/v10", Absent)

    /** Discord's deadline for an interaction's first answer (`interactions/receiving-and-responding.mdx`, "Interaction Callback"). */
    val InteractionDeadlineMax: Duration = 3.seconds

    /** `d` when positive and finite: kyo-http's `HttpClientConfig` throws on a duration that is not positive, and Discord refuses no limit
      * at all.
      */
    private def timeLimit(d: Duration)(problem: Duration => DiscordInvalidConfigException.Problem)
        : Result[DiscordInvalidConfigException.Problem, Duration] =
        if positiveFinite(d) then Result.succeed(d) else Result.fail(problem(d))

    private def positiveFinite(d: Duration): Boolean = d > Duration.Zero && d.isFinite

    private def urlProblemOf(url: HttpUrl): Maybe[DiscordInvalidConfigException.UrlProblem] =
        import DiscordInvalidConfigException.UrlProblem
        def asciiLower(s: String): String = s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)
        if !url.scheme.map(asciiLower).exists(s => s == "http" || s == "https") then Present(UrlProblem.Scheme)
        else if url.host.isEmpty then Present(UrlProblem.Host)
        else if url.unixSocket.nonEmpty then Present(UrlProblem.UnixSocket)
        else if url.host.contains('@') then Present(UrlProblem.UserInfo)
        else if url.rawQuery.nonEmpty then Present(UrlProblem.Query)
        else if url.path != "/" && url.path.endsWith("/") then Present(UrlProblem.TrailingSlash)
        else
            // kyo-http refuses a request whose URL holds such a character, a failure no call could recover from.
            val at = url.full.indexWhere(c => c <= ' ' || c > '~')
            if at >= 0 then Present(UrlProblem.Character(at)) else Absent
        end if
    end urlProblemOf

end DiscordConfig
