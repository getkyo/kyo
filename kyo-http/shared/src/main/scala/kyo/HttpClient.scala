package kyo

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.pool.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.util.concurrent.Future as NettyFuture
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kyo.internal.NettyTransport
import kyo.internal.NettyUtil
import scala.annotation.tailrec

final class HttpClient private (
    private val workerGroup: MultiThreadIoEventLoopGroup,
    private val bootstrap: Bootstrap,
    private val poolMap: ConcurrentHashMap[HttpClient.PoolKey, ChannelPool],
    private val sslContext: SslContext,
    private val maxConnectionsPerHost: Maybe[Int],
    private val connectionAcquireTimeout: Duration
):
    def send(request: HttpRequest)(using Frame): HttpResponse < (Async & Abort[HttpError]) =
        HttpClient.configLocal.use { config =>
            Sync.Unsafe {
                val effectiveUrl = HttpClient.buildEffectiveUrl(config, request)
                HttpClient.sendRaw(
                    bootstrap,
                    poolMap,
                    sslContext,
                    maxConnectionsPerHost,
                    connectionAcquireTimeout,
                    effectiveUrl,
                    request,
                    config
                )
            }
        }

    def closeNow(using Frame): Unit < Async =
        close(Duration.Zero)

    def close(using Frame): Unit < Async =
        close(30.seconds)

    def close(gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.Unsafe {
            import AllowUnsafe.embrace.danger
            import scala.jdk.CollectionConverters.*
            poolMap.values().asScala.foreach(_.close())
            poolMap.clear()
        }.andThen {
            val graceMs = gracePeriod.toMillis
            NettyUtil.future(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS)).unit
        }
end HttpClient

object HttpClient:

    private[kyo] inline def HttpPort  = 80
    private[kyo] inline def HttpsPort = 443

    // --- Factory methods ---

    def init(
        maxConnectionsPerHost: Maybe[Int] = Absent,
        connectionAcquireTimeout: Duration = 30.seconds
    )(using Frame): HttpClient < Sync =
        maxConnectionsPerHost.foreach(n => require(n > 0, s"maxConnectionsPerHost must be positive: $n"))
        require(connectionAcquireTimeout > Duration.Zero, s"connectionAcquireTimeout must be positive: $connectionAcquireTimeout")
        Sync.Unsafe {
            val workerGroup = new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)
            val bootstrap   = createBootstrap(workerGroup)
            val poolMap     = new ConcurrentHashMap[PoolKey, ChannelPool]()
            val sslContext  = SslContextBuilder.forClient().build()
            new HttpClient(workerGroup, bootstrap, poolMap, sslContext, maxConnectionsPerHost, connectionAcquireTimeout)
        }
    end init

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
        clientLocal.use(_.send(request))

    // --- Context management ---

    def let[A, S](client: HttpClient)(v: A < S)(using Frame): A < S =
        clientLocal.let(client)(v)

    def withConfig[A, S](f: Config => Config)(v: A < S)(using Frame): A < S =
        configLocal.use(c => configLocal.let(f(c))(v))

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

        def withBaseUrl(url: String): Config =
            copy(baseUrl = Present(url))
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

    private case class PoolKey(host: String, port: Int, ssl: Boolean)

    // Shared client uses daemon threads so JVM can exit
    private lazy val sharedClient: HttpClient =
        import AllowUnsafe.embrace.danger
        val workerGroup = new MultiThreadIoEventLoopGroup(
            0,                                                                          // default thread count
            new io.netty.util.concurrent.DefaultThreadFactory("kyo-http-client", true), // daemon = true
            NettyTransport.ioHandlerFactory
        )
        val bootstrap  = createBootstrap(workerGroup)
        val poolMap    = new ConcurrentHashMap[PoolKey, ChannelPool]()
        val sslContext = SslContextBuilder.forClient().build()
        new HttpClient(workerGroup, bootstrap, poolMap, sslContext, Absent, 30.seconds)
    end sharedClient

    private val clientLocal      = Local.init(sharedClient)
    private[kyo] val configLocal = Local.init(Config.default)

    private def createBootstrap(workerGroup: MultiThreadIoEventLoopGroup)(using AllowUnsafe): Bootstrap =
        new Bootstrap()
            .group(workerGroup)
            .channel(NettyTransport.socketChannelClass)
            .option(ChannelOption.SO_KEEPALIVE, java.lang.Boolean.TRUE)
            .option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)

    private def getOrCreatePool(
        bootstrap: Bootstrap,
        poolMap: ConcurrentHashMap[PoolKey, ChannelPool],
        sslContext: SslContext,
        maxConnectionsPerHost: Maybe[Int],
        connectionAcquireTimeout: Duration,
        key: PoolKey,
        connectTimeout: Maybe[Duration]
    )(using AllowUnsafe): ChannelPool =
        poolMap.computeIfAbsent(
            key,
            _ =>
                val handler = new AbstractChannelPoolHandler:
                    override def channelCreated(ch: Channel): Unit =
                        val pipeline = ch.pipeline()
                        if key.ssl then
                            discard(pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), key.host, key.port)))
                        discard(pipeline.addLast("codec", new HttpClientCodec()))
                        discard(pipeline.addLast("aggregator", new HttpObjectAggregator(1048576)))
                    end channelCreated
                val remoteBootstrap = bootstrap.clone().remoteAddress(new InetSocketAddress(key.host, key.port))
                connectTimeout.foreach { timeout =>
                    discard(remoteBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Integer.valueOf(timeout.toMillis.toInt)))
                }
                maxConnectionsPerHost match
                    case Present(maxConnections) =>
                        // Fixed pool with connection limit and acquire timeout
                        new FixedChannelPool(
                            remoteBootstrap,
                            handler,
                            ChannelHealthChecker.ACTIVE,
                            FixedChannelPool.AcquireTimeoutAction.FAIL,
                            connectionAcquireTimeout.toMillis,
                            maxConnections,
                            Int.MaxValue, // maxPendingAcquires
                            true,         // releaseHealthCheck - validate before returning to pool
                            true          // lastRecentUsed - LIFO for better cache locality
                        )
                    case Absent =>
                        // Unlimited pool - no connection limit
                        new SimpleChannelPool(
                            remoteBootstrap,
                            handler,
                            ChannelHealthChecker.ACTIVE,
                            true, // releaseHealthCheck
                            true  // lastRecentUsed
                        )
                end match
        )

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
        poolMap: ConcurrentHashMap[PoolKey, ChannelPool],
        sslContext: SslContext,
        maxConnectionsPerHost: Maybe[Int],
        connectionAcquireTimeout: Duration,
        url: String,
        request: HttpRequest,
        config: Config,
        redirectCount: Int = 0,
        retrySchedule: Maybe[Schedule] = Absent
    )(using Frame, AllowUnsafe): HttpResponse < (Async & Abort[HttpError]) =
        // Use provided retry schedule or get from config on first call
        val currentSchedule = retrySchedule.orElse(config.retrySchedule)
        val uri             = new URI(url)
        val scheme          = if uri.getScheme == null then "http" else uri.getScheme
        val host            = uri.getHost
        val port =
            if uri.getPort > 0 then uri.getPort
            else if scheme == "https" then HttpsPort
            else HttpPort
        val useSsl = scheme == "https"

        val key = PoolKey(host, port, useSsl)
        val pool =
            getOrCreatePool(bootstrap, poolMap, sslContext, maxConnectionsPerHost, connectionAcquireTimeout, key, config.connectTimeout)

        // Acquire channel from pool
        val acquirePromise = Promise.Unsafe.init[Channel, Any]()
        val acquireFuture  = pool.acquire()
        discard {
            acquireFuture.addListener { (future: NettyFuture[Channel]) =>
                import AllowUnsafe.embrace.danger
                if future.isSuccess then
                    discard(acquirePromise.complete(Result.succeed(future.getNow)))
                else
                    discard(acquirePromise.complete(Result.panic(future.cause())))
                end if
            }
        }

        acquirePromise.safe.getResult.map {
            case Result.Success(channel) =>
                // Got a channel - use it with guaranteed release
                // Track whether request completed normally to decide cleanup behavior
                val completed = new java.util.concurrent.atomic.AtomicBoolean(false)
                Sync.ensure {
                    // If request didn't complete normally (timeout/interrupt), close channel
                    // This signals to the server that the client is gone
                    if !completed.get() then
                        discard(channel.close())
                    // Release to pool - pool checks health via releaseHealthCheck
                    discard(pool.release(channel))
                } {
                    sendOnChannel(channel, uri, request, host, port, config)
                }.map { resp =>
                    completed.set(true)
                    resp
                }.map { resp =>
                    // Handle redirects
                    if config.followRedirects && resp.status.isRedirect then
                        if redirectCount >= config.maxRedirects then
                            Abort.fail(HttpError.TooManyRedirects(redirectCount))
                        else
                            resp.header("Location") match
                                case Present(location) =>
                                    val redirectUrl =
                                        if location.startsWith("http://") || location.startsWith("https://") then
                                            location
                                        else
                                            new URI(url).resolve(location).toString
                                    sendRaw(
                                        bootstrap,
                                        poolMap,
                                        sslContext,
                                        maxConnectionsPerHost,
                                        connectionAcquireTimeout,
                                        redirectUrl,
                                        request,
                                        config,
                                        redirectCount + 1
                                    )
                                case Absent =>
                                    resp
                    else
                        resp
                }.map { finalResp =>
                    // Handle retry based on response
                    currentSchedule match
                        case Present(schedule) if config.retryOn(finalResp) =>
                            Clock.now.map { now =>
                                schedule.next(now) match
                                    case Present((delay, nextSchedule)) =>
                                        Async.delay(delay) {
                                            sendRaw(
                                                bootstrap,
                                                poolMap,
                                                sslContext,
                                                maxConnectionsPerHost,
                                                connectionAcquireTimeout,
                                                url,
                                                request,
                                                config,
                                                0, // Reset redirect count for retry
                                                Present(nextSchedule)
                                            )
                                        }
                                    case Absent =>
                                        // Schedule exhausted, return last response
                                        finalResp
                            }
                        case _ =>
                            // No retry needed
                            finalResp
                }
            case Result.Panic(e)   => Abort.fail(HttpError.fromThrowable(e, host, port))
            case Result.Failure(e) => Abort.fail(HttpError.ConnectionFailed(host, port, new RuntimeException(e.toString)))
        }
    end sendRaw

    private def sendOnChannel(
        channel: Channel,
        uri: URI,
        request: HttpRequest,
        host: String,
        port: Int,
        config: Config
    )(using Frame, AllowUnsafe): HttpResponse < (Async & Abort[HttpError]) =
        val path =
            val p = uri.getRawPath
            val q = uri.getRawQuery
            if p == null || p.isEmpty then
                if q == null then "/" else "/?" + q
            else if q == null then p
            else p + "?" + q
            end if
        end path

        // Create the Netty request
        val bodyBytes = request.bodyBytes
        val nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.valueOf(request.method.name),
            path,
            Unpooled.wrappedBuffer(bodyBytes.toArrayUnsafe)
        )

        // Set headers - use keep-alive for connection reuse
        discard(nettyRequest.headers().set(HttpHeaderNames.HOST, host))
        discard(nettyRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE))
        if bodyBytes.nonEmpty then
            discard(nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.size))
        val reqHeaders     = request.headers
        val reqHeaderCount = reqHeaders.size
        @tailrec def setHeaders(i: Int): Unit =
            if i < reqHeaderCount then
                val header    = reqHeaders(i)
                val name      = header._1
                val value     = header._2
                val lowerName = name.toLowerCase
                if lowerName != "host" && lowerName != "connection" && !lowerName.startsWith("x-kyo-") then
                    discard(nettyRequest.headers().set(name, value))
                setHeaders(i + 1)
        setHeaders(0)

        val promise = Promise.Unsafe.init[HttpResponse, Abort[HttpError]]()

        // Add response handler
        val pipeline    = channel.pipeline()
        val handlerName = "responseHandler"
        if pipeline.get(handlerName) != null then
            discard(pipeline.remove(handlerName))
        discard(pipeline.addLast(handlerName, new ResponseHandler(promise, channel, host, port)))

        // Set up timeout
        config.timeout.foreach { duration =>
            val expire: Runnable = () =>
                // Close channel so server can detect timeout and interrupt handler
                discard(channel.close())
                discard(promise.complete(Result.fail(HttpError.Timeout(s"Request timed out after $duration"))))
            val timeoutTask = channel.eventLoop().schedule(expire, duration.toMillis, TimeUnit.MILLISECONDS)
            promise.onComplete(_ => discard(timeoutTask.cancel(true)))
        }

        // Send the request
        val writeFuture = channel.writeAndFlush(nettyRequest)
        discard {
            writeFuture.addListener((wf: ChannelFuture) =>
                if !wf.isSuccess then
                    discard(promise.complete(Result.fail(HttpError.fromThrowable(wf.cause(), host, port))))
            )
        }

        promise.safe.get
    end sendOnChannel

    // Response handler - just completes the promise, doesn't manage pool
    private class ResponseHandler(
        promise: Promise.Unsafe[HttpResponse, Abort[HttpError]],
        channel: Channel,
        host: String,
        port: Int
    ) extends SimpleChannelInboundHandler[FullHttpResponse]:
        override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse): Unit =
            import AllowUnsafe.embrace.danger
            val status = HttpResponse.Status(msg.status().code())
            val body   = new Array[Byte](msg.content().readableBytes())
            msg.content().readBytes(body)

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

            @tailrec def addHeaders(resp: HttpResponse, i: Int): HttpResponse =
                if i >= headerCount then resp
                else
                    val (name, value) = headers(i)
                    addHeaders(resp.addHeader(name, value), i + 1)

            val response = addHeaders(HttpResponse(status, new String(body, StandardCharsets.UTF_8)), 0)
            discard(promise.complete(Result.succeed(response)))
        end channelRead0

        override def channelInactive(ctx: ChannelHandlerContext): Unit =
            import AllowUnsafe.embrace.danger
            discard(promise.complete(Result.fail(HttpError.InvalidResponse("Connection closed by server"))))
            super.channelInactive(ctx)
        end channelInactive

        override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =
            import AllowUnsafe.embrace.danger
            discard(promise.complete(Result.fail(HttpError.fromThrowable(cause, host, port))))
        end exceptionCaught
    end ResponseHandler

end HttpClient
