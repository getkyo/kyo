package kyo

/** Everything a bot needs to call the Bot API and to poll it for updates: the token, the server's base
  * URL, the long-polling parameters, the request limits, and the schedule `run` retries on.
  *
  * `baseUrl` is Telegram's server by default. A self-hosted Bot API server replaces it, which lifts
  * some limits (file sizes, webhook ports). Every call goes to `{baseUrl}/bot{token}/{method}`.
  *
  * `pollTimeout` is how long one `getUpdates` waits on the server for an update before it answers
  * empty. Telegram takes it in whole seconds and asks for a positive value, so a timeout of zero, of
  * a fraction of a second or beyond `Int.MaxValue` seconds is refused rather than rounded.
  * `pollLimit` is the most updates one `getUpdates` returns, 1 to 100. `allowedUpdates` chooses the
  * update kinds `run` receives; `Absent` keeps the bot's previous choice. `requestTimeout` bounds every
  * other call and is added to `pollTimeout` for a long poll, so a poll that ends normally never times
  * out. `connectTimeout` bounds opening a connection. `maxResponseLength` bounds an answer's body; its
  * default, 20 MiB, covers the largest file the Bot API serves for download (20 MB). `retrySchedule` paces the
  * retries of `run` after a transport failure, a server error or a rate limit; a delay Telegram sent is
  * waited instead.
  *
  * The module's HTTP client uses these settings and nothing of the caller's kyo-http configuration:
  * no ambient filter, TLS setting, retry or redirect applies to a request that carries the token.
  *
  * IMPORTANT: a config that holds a value Telegram cannot use is a programming mistake, so building one
  * panics with a [[kyo.TelegramInvalidConfigException]] naming the setting. The rendered config never
  * shows the token, whose `toString` is redacted.
  *
  * @see
  *   [[kyo.TelegramToken]] the token held here
  */
final case class TelegramConfig(
    token: TelegramToken,
    baseUrl: HttpUrl = TelegramConfig.TelegramServer,
    pollTimeout: Duration = 30.seconds,
    pollLimit: Int = 100,
    allowedUpdates: Maybe[Chunk[TelegramUpdate.Type]] = Absent,
    requestTimeout: Duration = 10.seconds,
    connectTimeout: Duration = 10.seconds,
    maxResponseLength: ByteSize = 20.mib,
    retrySchedule: Schedule = Schedule.exponentialBackoff(1.second, 2.0, 60.seconds)
)(using Frame) derives CanEqual:
    TelegramConfig.problemOf(this).foreach(problem => throw TelegramInvalidConfigException(problem))
end TelegramConfig

object TelegramConfig:

    /** Telegram's own Bot API server, `https://api.telegram.org`. */
    val TelegramServer: HttpUrl = HttpUrl(Present("https"), "api.telegram.org", 443, "/", Absent)

    /** The largest `maxResponseLength`, the most kyo-http can hold. */
    val MaxResponseLength: ByteSize = Int.MaxValue.bytes

    private def problemOf(config: TelegramConfig): Maybe[TelegramInvalidConfigException.Problem] =
        import TelegramInvalidConfigException.Problem
        urlProblemOf(config.baseUrl).map(Problem.BaseUrl(_))
            .orElse(if validPollTimeout(config.pollTimeout) then Absent else Present(Problem.PollTimeout(config.pollTimeout)))
            .orElse(if config.pollLimit >= 1 && config.pollLimit <= 100 then Absent else Present(Problem.PollLimit(config.pollLimit)))
            .orElse(if positiveFinite(config.requestTimeout) then Absent else Present(Problem.RequestTimeout(config.requestTimeout)))
            .orElse(if positiveFinite(config.connectTimeout) then Absent else Present(Problem.ConnectTimeout(config.connectTimeout)))
            .orElse(
                if config.maxResponseLength.toBytes >= 1 && config.maxResponseLength.toBytes <= MaxResponseLength.toBytes then Absent
                else Present(Problem.MaxResponseLength(config.maxResponseLength, MaxResponseLength))
            )
    end problemOf

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
