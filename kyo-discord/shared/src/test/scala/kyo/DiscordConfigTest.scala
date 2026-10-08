package kyo

class DiscordConfigTest extends kyo.test.Test[Any]:

    import DiscordInvalidConfigException.Problem
    import DiscordInvalidConfigException.UrlProblem

    // Built from parts so the whole secret appears nowhere in this file's source text.
    private val secret = Seq("MTA0OTI3NjU0MzIxMDk4NzY1", "cfgTEST", "secretPartForConfig").mkString(".")
    private val token  = Discord.Token.init(secret).getOrThrow

    private def url(text: String)(using Frame): HttpUrl = HttpUrl.parse(text).getOrThrow

    private def rejected(config: Result[DiscordInvalidConfigException, DiscordConfig]): Maybe[DiscordInvalidConfigException] =
        config.failure

    "the defaults are Discord's documented values" in {
        val config = DiscordConfig.init(token, Discord.Intents.Guilds).getOrThrow
        assert((
            config.shard,
            config.interactionDeadline,
            config.globalRateLimit,
            config.retry,
            config.retryMaxDelay,
            config.baseUrl,
            config.requestTimeout,
            config.transferTimeout,
            config.connectTimeout,
            config.maxResponseLength
        ) == (
            Absent,
            2500.millis,
            50,
            Absent,
            60.seconds,
            url("https://discord.com/api/v10"),
            10.seconds,
            120.seconds,
            10.seconds,
            8.mib
        ))
        assert(config.reconnect.isInstanceOf[DiscordConfig.Reconnect.Resume])
    }

    "retry is set by the caller and held as given" in {
        val schedule = Schedule.fixed(2.seconds).take(3)
        assert(DiscordConfig.init(token, Discord.Intents.Guilds, retry = Present(schedule)).map(_.retry) ==
            Result.succeed(Present(schedule)))
    }

    "a rendered config shows the token redacted and not its value" in {
        val rendered = DiscordConfig.init(token, Discord.Intents.Guilds).getOrThrow.toString
        assert(rendered.contains("Discord.Token(<redacted>)"))
        assert(!rendered.contains("secretPartForConfig"))
    }

    "the bounds Discord gives are accepted" in {
        val config = DiscordConfig.init(
            token,
            Discord.Intents.Guilds,
            baseUrl = url("http://127.0.0.1:8081/api"),
            interactionDeadline = 2999.millis,
            globalRateLimit = 1,
            requestTimeout = 1.nano,
            maxResponseLength = 1.bytes
        )
        assert(config.map(c =>
            (c.baseUrl, c.interactionDeadline, c.globalRateLimit, c.requestTimeout, c.maxResponseLength)
        ) ==
            Result.succeed((url("http://127.0.0.1:8081/api"), 2999.millis, 1, 1.nano, 1.bytes)))
    }

    private def heldAndPassed(bound: ByteSize): Result[DiscordInvalidConfigException, (ByteSize, ByteSize)] =
        DiscordConfig.init(token, Discord.Intents.Guilds, maxResponseLength = bound).map(c =>
            (c.maxResponseLength, internal.discord.Rest.requestConfig(c, c.requestTimeout).maxResponseLength)
        )

    "a zero response bound is held as given and reaches the request config unchanged, for kyo-http to narrow" in {
        assert(heldAndPassed(ByteSize.Zero) == Result.succeed((ByteSize.Zero, ByteSize.Zero)))
    }

    "a one-byte response bound is held and reaches the request config unchanged" in {
        assert(heldAndPassed(1.bytes) == Result.succeed((1.bytes, 1.bytes)))
    }

    "a response bound past Int.MaxValue bytes is held as given and reaches the request config unchanged, for kyo-http to narrow" in {
        val over = (Int.MaxValue.toLong + 1).bytes
        assert(heldAndPassed(over) == Result.succeed((over, over)))
    }

    "init fails with the setting and what is wrong" - {

        "a base URL that is not http or https on a host, or that holds user info, a query or a trailing slash" in {
            val found = Chunk(
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, baseUrl = url("wss://discord.com/api/v10"))),
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, baseUrl = HttpUrl(Present("https"), "", 443, "/", Absent))),
                rejected(DiscordConfig.init(
                    token,
                    Discord.Intents.Guilds,
                    baseUrl = HttpUrl(Present("https"), "me@discord.com", 443, "/", Absent)
                )),
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, baseUrl = url("https://discord.com/api?v=10"))),
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, baseUrl = url("https://discord.com/api/v10/")))
            )
            assert(found == Chunk(
                Present(DiscordInvalidConfigException(Problem.BaseUrl(UrlProblem.Scheme))),
                Present(DiscordInvalidConfigException(Problem.BaseUrl(UrlProblem.Host))),
                Present(DiscordInvalidConfigException(Problem.BaseUrl(UrlProblem.UserInfo))),
                Present(DiscordInvalidConfigException(Problem.BaseUrl(UrlProblem.Query))),
                Present(DiscordInvalidConfigException(Problem.BaseUrl(UrlProblem.TrailingSlash)))
            ))
            assert(found.head.exists(_.getMessage.contains("DiscordConfig.baseUrl must be an http or https URL.")))
        }

        "a base URL with a character kyo-http refuses to send, at its position" in {
            val withSpace = HttpUrl(Present("https"), "discord.com", 443, "/api v10", Absent)
            val position  = withSpace.full.indexOf(' ')
            assert(rejected(DiscordConfig.init(token, Discord.Intents.Guilds, baseUrl = withSpace)) ==
                Present(DiscordInvalidConfigException(Problem.BaseUrl(UrlProblem.Character(position)))))
        }

        "an interaction deadline that is not positive or not under Discord's 3 seconds" in {
            assert(Chunk(Duration.Zero, 3.seconds, 4.seconds).map(d =>
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, interactionDeadline = d))
            ) == Chunk(Duration.Zero, 3.seconds, 4.seconds).map(d =>
                Present(DiscordInvalidConfigException(Problem.InteractionDeadline(d, 3.seconds)))
            ))
            assert(rejected(DiscordConfig.init(token, Discord.Intents.Guilds, interactionDeadline = 3.seconds)).exists(
                _.getMessage.contains("DiscordConfig.interactionDeadline must be positive and less than 3.seconds; got 3.seconds.")
            ))
        }

        "a global rate limit under 1 request per second" in {
            assert(Chunk(0, -1).map(n => rejected(DiscordConfig.init(token, Discord.Intents.Guilds, globalRateLimit = n))) ==
                Chunk(0, -1).map(n => Present(DiscordInvalidConfigException(Problem.GlobalRateLimit(n)))))
        }

        "a retry wait cap that is not positive and finite" in {
            assert(Chunk(Duration.Zero, Duration.Infinity).map(d =>
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, retryMaxDelay = d))
            ) == Chunk(Duration.Zero, Duration.Infinity).map(d => Present(DiscordInvalidConfigException(Problem.RetryMaxDelay(d)))))
            assert(rejected(DiscordConfig.init(token, Discord.Intents.Guilds, retryMaxDelay = Duration.Zero)).exists(
                _.getMessage.contains("DiscordConfig.retryMaxDelay must be positive and finite; got Duration.Zero.")
            ))
        }

        "a timeout that is not positive and finite" in {
            assert(Chunk(
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, requestTimeout = Duration.Zero)),
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, transferTimeout = Duration.Infinity)),
                rejected(DiscordConfig.init(token, Discord.Intents.Guilds, connectTimeout = Duration.Zero))
            ) == Chunk(
                Present(DiscordInvalidConfigException(Problem.RequestTimeout(Duration.Zero))),
                Present(DiscordInvalidConfigException(Problem.TransferTimeout(Duration.Infinity))),
                Present(DiscordInvalidConfigException(Problem.ConnectTimeout(Duration.Zero)))
            ))
        }

        "the first setting in declaration order is the one reported" in {
            assert(rejected(DiscordConfig.init(token, Discord.Intents.Guilds, globalRateLimit = 0, requestTimeout = Duration.Zero)) ==
                Present(DiscordInvalidConfigException(Problem.GlobalRateLimit(0))))
        }
    }

end DiscordConfigTest
