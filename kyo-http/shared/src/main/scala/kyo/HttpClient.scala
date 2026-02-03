package kyo

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.ssl.SslContextBuilder
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kyo.internal.NettyTransport
import kyo.internal.NettyUtil
import scala.annotation.tailrec

final class HttpClient private (
    private val workerGroup: MultiThreadIoEventLoopGroup,
    private val bootstrap: Bootstrap,
    private val config: HttpClient.Config
):
    def send(request: HttpRequest)(using Frame): HttpResponse < (Async & Abort[HttpError]) =
        Sync.Unsafe.withLocal(HttpClient.local) { localConfig =>
            val effectiveConfig = if localConfig eq HttpClient.Config.default then config else localConfig
            val effectiveUrl    = HttpClient.buildEffectiveUrl(effectiveConfig, request)
            HttpClient.sendRaw(bootstrap, effectiveUrl, request, effectiveConfig)
        }

    def closeNow(using Frame): Unit < Async =
        close(Duration.Zero)

    def close(using Frame): Unit < Async =
        close(30.seconds)

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        val graceMs = gracePeriod.toMillis
        NettyUtil.future(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS)).unit
end HttpClient

object HttpClient:

    private[kyo] inline def HttpPort  = 80
    private[kyo] inline def HttpsPort = 443

    // --- Factory methods ---

    def init(config: Config)(using Frame): HttpClient < Sync =
        Sync.Unsafe {
            val workerGroup = new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)
            val bootstrap   = createBootstrap(workerGroup)
            new HttpClient(workerGroup, bootstrap, config)
        }

    def init(
        baseUrl: Maybe[String] = Config.default.baseUrl,
        timeout: Maybe[Duration] = Config.default.timeout,
        connectTimeout: Maybe[Duration] = Config.default.connectTimeout,
        followRedirects: Boolean = Config.default.followRedirects,
        maxRedirects: Int = Config.default.maxRedirects,
        retrySchedule: Maybe[Schedule] = Config.default.retrySchedule,
        retryOn: HttpResponse => Boolean = Config.default.retryOn
    )(using Frame): HttpClient < Sync =
        init(Config(baseUrl, timeout, connectTimeout, followRedirects, maxRedirects, retrySchedule, retryOn))

    // --- Convenience methods (use shared client) ---

    def get[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.get(url)).map(checkStatusAndParse[A])

    def post[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.post(url, body)).map(checkStatusAndParse[A])

    def put[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.put(url, body)).map(checkStatusAndParse[A])

    def delete[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.delete(url)).map(checkStatusAndParse[A])

    private def checkStatusAndParse[A: Schema](response: HttpResponse)(using Frame): A < Abort[HttpError] =
        if response.status.isError then
            Abort.fail(HttpError.InvalidResponse(s"HTTP error: ${response.status.code}"))
        else
            try response.bodyAs[A]
            catch case e: Throwable => Abort.fail(HttpError.InvalidResponse(s"Failed to parse response: ${e.getMessage}"))

    def send(request: HttpRequest)(using Frame): HttpResponse < (Async & Abort[HttpError]) =
        Sync.Unsafe.withLocal(HttpClient.local) { config =>
            val effectiveUrl = buildEffectiveUrl(config, request)
            sendRaw(sharedBootstrap, effectiveUrl, request, config)
        }

    // --- Context management ---

    def let[A, S](config: Config)(v: A < S)(using Frame): A < S =
        local.let(config)(v)

    def update[A, S](f: Config => Config)(v: A < S)(using Frame): A < S =
        local.use(c => local.let(f(c))(v))

    def baseUrl[A, S](url: String)(v: A < S)(using Frame): A < S =
        update(_.copy(baseUrl = Present(url)))(v)

    // --- Config ---

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

        def withTimeout(d: Duration): Config =
            require(d > Duration.Zero, s"timeout must be positive: $d")
            copy(timeout = Present(d))
        def withConnectTimeout(d: Duration): Config =
            require(d > Duration.Zero, s"connectTimeout must be positive: $d")
            copy(connectTimeout = Present(d))
        def withFollowRedirects(b: Boolean): Config =
            copy(followRedirects = b)
        def withMaxRedirects(n: Int): Config =
            require(n >= 0, s"maxRedirects must be non-negative: $n")
            copy(maxRedirects = n)
        def withRetry(schedule: Schedule): Config =
            copy(retrySchedule = Present(schedule))
        def withRetryWhen(f: HttpResponse => Boolean): Config =
            copy(retryOn = f)
    end Config

    object Config:
        val default: Config = Config()

        def apply(baseUrl: String): Config =
            require(baseUrl.nonEmpty, "baseUrl cannot be empty")
            try
                new java.net.URI(baseUrl)
            catch
                case e: Exception => throw new IllegalArgumentException(s"Invalid baseUrl: $baseUrl", e)
            end try
            Config(baseUrl = Present(baseUrl))
        end apply
    end Config

    // --- Private implementation ---

    private val sharedWorkerGroup = new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)
    private val sharedBootstrap   = createBootstrap(sharedWorkerGroup)(using AllowUnsafe.embrace.danger)

    private val local = Local.init[Config](Config.default)

    private def createBootstrap(workerGroup: MultiThreadIoEventLoopGroup)(using AllowUnsafe): Bootstrap =
        new Bootstrap()
            .group(workerGroup)
            .channel(NettyTransport.socketChannelClass)
            .option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)

    private def buildEffectiveUrl(config: Config, request: HttpRequest): String =
        config.baseUrl match
            case Present(base) =>
                val url = request.url
                if url.startsWith("http://") || url.startsWith("https://") then url
                else
                    val baseUri = new URI(base)
                    baseUri.resolve(url).toString
                end if
            case Absent =>
                val host = request.host
                val port = request.port
                val path = request.url
                if host.isEmpty then path
                else if port == HttpPort then s"http://$host$path"
                else if port == HttpsPort then s"https://$host$path"
                else s"http://$host:$port$path"
                end if

    private def sendRaw(
        bootstrap: Bootstrap,
        url: String,
        request: HttpRequest,
        config: Config,
        redirectCount: Int = 0
    )(using Frame, AllowUnsafe): HttpResponse < (Async & Abort[HttpError]) =
        // Kyo timeout provides request-level control; onComplete handles cleanup on both success and interrupt

        val promise = Promise.Unsafe.init[HttpResponse, Abort[HttpError]]()
        val channel = setupConnection(bootstrap, url, request, promise)

        config.timeout.foreach { duration =>
            val expire: Runnable = () =>
                if promise.complete(Result.fail(HttpError.Timeout(s"Request timed out after $duration"))) then
                    discard(channel.close())
            val timeoutTask = channel.eventLoop().schedule(expire, duration.toMillis, TimeUnit.MILLISECONDS)
            promise.onComplete(_ => discard(timeoutTask.cancel(true)))
        }

        promise.safe.use { response =>
            // Handle redirects
            if config.followRedirects && response.status.isRedirect then
                if redirectCount >= config.maxRedirects then
                    Abort.fail(HttpError.TooManyRedirects(redirectCount))
                else
                    response.header("Location") match
                        case Present(location) =>
                            val redirectUrl =
                                if location.startsWith("http://") || location.startsWith("https://") then
                                    location
                                else
                                    new URI(url).resolve(location).toString
                            sendRaw(bootstrap, redirectUrl, request, config, redirectCount + 1)
                        case Absent =>
                            response
            else
                response
        }
    end sendRaw

    private def setupConnection(
        bootstrap: Bootstrap,
        url: String,
        request: HttpRequest,
        promise: Promise.Unsafe[HttpResponse, Abort[HttpError]]
    )(using AllowUnsafe): Channel =
        val uri    = new URI(url)
        val scheme = if uri.getScheme == null then "http" else uri.getScheme
        val host   = uri.getHost
        val port =
            if uri.getPort > 0 then uri.getPort
            else if scheme == "https" then HttpsPort
            else HttpPort
        val path =
            val p = uri.getRawPath
            val q = uri.getRawQuery
            if p == null || p.isEmpty then
                if q == null then "/" else "/?" + q
            else if q == null then p
            else p + "?" + q
            end if
        end path

        val useSsl = scheme == "https"

        // Create the Netty request
        val bodyBytes = request.bodyBytes
        val nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.valueOf(request.method.name),
            path,
            Unpooled.wrappedBuffer(bodyBytes.toArrayUnsafe)
        )

        // Set headers
        discard(nettyRequest.headers().set(HttpHeaderNames.HOST, host))
        discard(nettyRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE))
        if bodyBytes.nonEmpty then
            discard(nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.size))
        val reqHeaders     = request.headers
        val reqHeaderCount = reqHeaders.size
        @tailrec def setHeaders(i: Int): Unit =
            if i < reqHeaderCount then
                val header = reqHeaders(i)
                val name   = header._1
                val value  = header._2
                // Filter out internal headers (host, connection) and kyo-specific headers
                val lowerName = name.toLowerCase
                if lowerName != "host" && lowerName != "connection" && !lowerName.startsWith("x-kyo-") then
                    discard(nettyRequest.headers().set(name, value))
                setHeaders(i + 1)
        setHeaders(0)

        // Configure the channel with response handler
        val configuredBootstrap = bootstrap.clone().handler(new ChannelInitializer[SocketChannel]:
            override def initChannel(ch: SocketChannel): Unit =
                val pipeline = ch.pipeline()
                if useSsl then
                    val sslContext = SslContextBuilder.forClient().build()
                    discard(pipeline.addLast(sslContext.newHandler(ch.alloc(), host, port)))
                end if
                discard(pipeline.addLast(new HttpClientCodec()))
                discard(pipeline.addLast(new HttpObjectAggregator(1048576))) // 1MB max
                discard(pipeline.addLast(new ResponseHandler(promise, host, port))))

        // Connect and send
        val connectFuture = configuredBootstrap.connect(host, port)
        discard {
            connectFuture.addListener((future: ChannelFuture) =>
                if future.isSuccess then
                    val channel     = future.channel()
                    val writeFuture = channel.writeAndFlush(nettyRequest)
                    discard {
                        writeFuture.addListener((wf: ChannelFuture) =>
                            if !wf.isSuccess then
                                discard(promise.complete(Result.fail(HttpError.fromThrowable(wf.cause(), host, port))))
                        )
                    }
                else
                    discard(promise.complete(Result.fail(HttpError.fromThrowable(future.cause(), host, port))))
            )
        }

        connectFuture.channel()
    end setupConnection

    // Handler to collect the response
    private class ResponseHandler(
        promise: Promise.Unsafe[HttpResponse, Abort[HttpError]],
        host: String,
        port: Int
    ) extends SimpleChannelInboundHandler[FullHttpResponse]:
        override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse): Unit =
            import AllowUnsafe.embrace.danger
            val status = HttpResponse.Status(msg.status().code())
            val body   = new Array[Byte](msg.content().readableBytes())
            msg.content().readBytes(body)

            // Extract headers using two-pass algorithm
            val nettyHeaders = msg.headers()
            val headerCount  = nettyHeaders.size()
            val headers      = new Array[(String, String)](headerCount)
            val iter         = nettyHeaders.iteratorAsString()

            @tailrec def fillHeaders(i: Int): Unit =
                if i < headerCount && iter.hasNext then
                    val entry = iter.next()
                    headers(i) = (entry.getKey, entry.getValue)
                    fillHeaders(i + 1)

            fillHeaders(0)

            // Build response with headers
            @tailrec def addHeaders(resp: HttpResponse, i: Int): HttpResponse =
                if i >= headerCount then resp
                else
                    val (name, value) = headers(i)
                    addHeaders(resp.addHeader(name, value), i + 1)

            val response = addHeaders(HttpResponse(status, new String(body, StandardCharsets.UTF_8)), 0)

            discard(promise.complete(Result.succeed(response)))
            discard(ctx.close())
        end channelRead0

        override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
            import AllowUnsafe.embrace.danger
            discard(promise.complete(Result.fail(HttpError.fromThrowable(cause, host, port))))
            discard(ctx.close())
        end exceptionCaught
    end ResponseHandler

end HttpClient
