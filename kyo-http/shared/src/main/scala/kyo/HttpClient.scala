package kyo

opaque type HttpClient = io.netty.channel.Channel

object HttpClient:

    case class Config(
        baseUrl: Maybe[String] = Absent,
        timeout: Maybe[Duration] = Absent,
        connectTimeout: Maybe[Duration] = Absent,
        followRedirects: Boolean = true,
        maxRedirects: Int = 10,
        retrySchedule: Maybe[Schedule] = Absent,
        retryOn: HttpResponse => Boolean = _.status.isServerError
    ):
        require(maxRedirects >= 0, s"maxRedirects must be non-negative: $maxRedirects")
        timeout.foreach(d => require(d > Duration.Zero, s"timeout must be positive: $d"))
        connectTimeout.foreach(d => require(d > Duration.Zero, s"connectTimeout must be positive: $d"))
    end Config

    object Config:
        val default: Config = Config()

        def apply(baseUrl: String): Config =
            require(baseUrl.nonEmpty, "baseUrl cannot be empty")
            // Validate URL format
            try
                new java.net.URI(baseUrl)
            catch
                case e: Exception => throw new IllegalArgumentException(s"Invalid baseUrl: $baseUrl", e)
            end try
            Config(baseUrl = Present(baseUrl))
        end apply

        extension (config: Config)
            def timeout(d: Duration): Config =
                require(d > Duration.Zero, s"timeout must be positive: $d")
                config.copy(timeout = Present(d))
            def connectTimeout(d: Duration): Config =
                require(d > Duration.Zero, s"connectTimeout must be positive: $d")
                config.copy(connectTimeout = Present(d))
            def followRedirects(b: Boolean): Config =
                config.copy(followRedirects = b)
            def maxRedirects(n: Int): Config =
                require(n >= 0, s"maxRedirects must be non-negative: $n")
                config.copy(maxRedirects = n)
            def retry(schedule: Schedule): Config =
                config.copy(retrySchedule = Present(schedule))
            def retryWhen(f: HttpResponse => Boolean): Config =
                config.copy(retryOn = f)
        end extension
    end Config

    def init(config: Config)(using Frame): HttpClient < Async = ???

    def init(
        baseUrl: Maybe[String] = Config.default.baseUrl,
        timeout: Maybe[Duration] = Config.default.timeout,
        connectTimeout: Maybe[Duration] = Config.default.connectTimeout,
        followRedirects: Boolean = Config.default.followRedirects,
        maxRedirects: Int = Config.default.maxRedirects,
        retrySchedule: Maybe[Schedule] = Config.default.retrySchedule,
        retryOn: HttpResponse => Boolean = Config.default.retryOn
    )(using Frame): HttpClient < Async =
        init(Config(baseUrl, timeout, connectTimeout, followRedirects, maxRedirects, retrySchedule, retryOn))

    def let[A, S](config: Config)(v: A < S)(using Frame): A < S         = ???
    def update[A, S](f: Config => Config)(v: A < S)(using Frame): A < S = ???

    extension (client: HttpClient)
        def send(request: HttpRequest)(using Frame): HttpResponse < Async = ???
        def close(using Frame): Unit < Async                              = ???
    end extension

    def get[A: Schema](url: String)(using Frame): A < Async                      = ???
    def post[A: Schema, B: Schema](url: String, body: B)(using Frame): A < Async = ???
    def put[A: Schema, B: Schema](url: String, body: B)(using Frame): A < Async  = ???
    def delete[A: Schema](url: String)(using Frame): A < Async                   = ???

    def send(request: HttpRequest)(using Frame): HttpResponse < Async = ???

    def baseUrl[A, S](url: String)(v: A < S)(using Frame): A < S = ???

end HttpClient
