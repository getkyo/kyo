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
    private val connectionAcquireTimeout: Duration,
    private val maxResponseSizeBytes: Int
):
    def send(request: HttpRequest)(using Frame): HttpResponse < (Async & Abort[HttpError]) =
        HttpFilter.use { filter =>
            HttpClient.configLocal.use { config =>
                Sync.Unsafe {
                    filter(
                        request,
                        filteredRequest =>
                            val effectiveUrl = HttpClient.buildEffectiveUrl(config, filteredRequest)
                            HttpClient.sendRaw(
                                bootstrap,
                                poolMap,
                                sslContext,
                                maxConnectionsPerHost,
                                connectionAcquireTimeout,
                                maxResponseSizeBytes,
                                effectiveUrl,
                                filteredRequest,
                                config
                            )
                    )
                }
            }
        }

    /** Pre-establishes connections to reduce first-request latency.
      *
      * @param url
      *   URL to warm up (e.g., "https://api.example.com")
      * @param connections
      *   Number of connections to pre-establish
      */
    def warmup(url: String, connections: Int = 1)(using Frame): Unit < Async =
        import HttpClient.{PoolKey, DefaultHttpPort, DefaultHttpsPort}
        require(connections > 0, s"connections must be positive: $connections")
        val uri    = new java.net.URI(url)
        val scheme = if uri.getScheme == null then "http" else uri.getScheme
        val host   = uri.getHost
        val port   = if uri.getPort > 0 then uri.getPort else if scheme == "https" then DefaultHttpsPort else DefaultHttpPort
        val ssl    = scheme == "https"
        val key    = PoolKey(host, port, ssl)
        Sync.Unsafe {
            val pool = HttpClient.getOrCreatePool(
                bootstrap,
                poolMap,
                sslContext,
                maxConnectionsPerHost,
                connectionAcquireTimeout,
                maxResponseSizeBytes,
                key,
                Absent
            )
            Async.fill(connections) {
                NettyUtil.future(pool.acquire()) { channel =>
                    pool.release(channel)
                }
            }
        }.unit
    end warmup

    /** Pre-establishes connections to multiple URLs.
      *
      * @param urls
      *   URLs to warm up
      */
    def warmup(urls: Seq[String])(using Frame): Unit < Async =
        Async.foreach(urls)(url => warmup(url, 1)).unit

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
            NettyUtil.future(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS))(_ => ())
        }
end HttpClient

object HttpClient:

    private[kyo] inline def DefaultHttpPort                   = 80
    private[kyo] inline def DefaultHttpsPort                  = 443
    private[kyo] val DefaultMaxConnectionsPerHost: Maybe[Int] = Present(100)

    // --- Factory methods ---

    def init(
        maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
        connectionAcquireTimeout: Duration = 30.seconds,
        maxResponseSizeBytes: Int = 1048576,
        daemon: Boolean = false
    )(using Frame): HttpClient < Sync =
        Sync.Unsafe {
            Unsafe.init(maxConnectionsPerHost, connectionAcquireTimeout, maxResponseSizeBytes, daemon)
        }
    end init

    // --- Unsafe ---

    object Unsafe:
        /** Low-level client initialization requiring AllowUnsafe.
          * @param daemon
          *   If true, uses daemon threads (JVM can exit while client is active)
          */
        def init(
            maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
            connectionAcquireTimeout: Duration = 30.seconds,
            maxResponseSizeBytes: Int = 1048576,
            daemon: Boolean = false
        )(using AllowUnsafe): HttpClient =
            maxConnectionsPerHost.foreach(n => require(n > 0, s"maxConnectionsPerHost must be positive: $n"))
            require(connectionAcquireTimeout > Duration.Zero, s"connectionAcquireTimeout must be positive: $connectionAcquireTimeout")
            require(maxResponseSizeBytes > 0, s"maxResponseSizeBytes must be positive: $maxResponseSizeBytes")
            val workerGroup =
                if daemon then
                    new MultiThreadIoEventLoopGroup(
                        0,
                        new io.netty.util.concurrent.DefaultThreadFactory("kyo-http-client", true),
                        NettyTransport.ioHandlerFactory
                    )
                else
                    new MultiThreadIoEventLoopGroup(NettyTransport.ioHandlerFactory)
            val bootstrap  = createBootstrap(workerGroup)
            val poolMap    = new ConcurrentHashMap[PoolKey, ChannelPool]()
            val sslContext = SslContextBuilder.forClient().build()
            new HttpClient(
                workerGroup,
                bootstrap,
                poolMap,
                sslContext,
                maxConnectionsPerHost,
                connectionAcquireTimeout,
                maxResponseSizeBytes
            )
        end init
    end Unsafe

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
            response.bodyText.map(body => Abort.fail(HttpError.StatusError(response.status, body)))
        else
            response.bodyAs[A]

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

        def baseUrl(url: String): Config =
            copy(baseUrl = Present(url))
        def timeout(d: Duration): Config =
            require(d > Duration.Zero, s"timeout must be positive: $d")
            copy(timeout = Present(d))
        def connectTimeout(d: Duration): Config =
            require(d > Duration.Zero, s"connectTimeout must be positive: $d")
            copy(connectTimeout = Present(d))
        def followRedirects(b: Boolean): Config =
            copy(followRedirects = b)
        def maxRedirects(n: Int): Config =
            require(n >= 0, s"maxRedirects must be non-negative: $n")
            copy(maxRedirects = n)
        def retry(schedule: Schedule): Config =
            copy(retrySchedule = Present(schedule))
        def retryWhen(f: HttpResponse => Boolean): Config =
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
        Unsafe.init(daemon = true)

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
        maxResponseSizeBytes: Int,
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
                        discard(pipeline.addLast("aggregator", new HttpObjectAggregator(maxResponseSizeBytes)))
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
                else if port == DefaultHttpPort then s"http://$host$path"
                else if port == DefaultHttpsPort then s"https://$host$path"
                else s"http://$host:$port$path"
                end if

    private def sendRaw(
        bootstrap: Bootstrap,
        poolMap: ConcurrentHashMap[PoolKey, ChannelPool],
        sslContext: SslContext,
        maxConnectionsPerHost: Maybe[Int],
        connectionAcquireTimeout: Duration,
        maxResponseSizeBytes: Int,
        url: String,
        request: HttpRequest,
        config: Config,
        redirectCount: Int = 0,
        retrySchedule: Maybe[Schedule] = Absent,
        attemptCount: Int = 1
    )(using Frame, AllowUnsafe): HttpResponse < (Async & Abort[HttpError]) =
        // Use provided retry schedule or get from config on first call
        val currentSchedule = retrySchedule.orElse(config.retrySchedule)
        val uri             = new URI(url)
        val scheme          = if uri.getScheme == null then "http" else uri.getScheme
        val host            = uri.getHost
        val port =
            if uri.getPort > 0 then uri.getPort
            else if scheme == "https" then DefaultHttpsPort
            else DefaultHttpPort
        val useSsl = scheme == "https"

        val key = PoolKey(host, port, useSsl)
        val pool =
            getOrCreatePool(
                bootstrap,
                poolMap,
                sslContext,
                maxConnectionsPerHost,
                connectionAcquireTimeout,
                maxResponseSizeBytes,
                key,
                config.connectTimeout
            )

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
                Sync.ensure {
                    // Release to pool - pool checks health via releaseHealthCheck
                    discard(pool.release(channel))
                } {
                    sendOnChannel(channel, uri, request, host, port, config)
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
                                        maxResponseSizeBytes,
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
                                                maxResponseSizeBytes,
                                                url,
                                                request,
                                                config,
                                                0, // Reset redirect count for retry
                                                Present(nextSchedule),
                                                attemptCount + 1
                                            )
                                        }
                                    case Absent =>
                                        // Schedule exhausted, fail with RetriesExhausted
                                        finalResp.bodyText.map { body =>
                                            Abort.fail(HttpError.RetriesExhausted(attemptCount, finalResp.status, body))
                                        }
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
        // Build path from the URI parameter (may differ from request.url for redirects)
        val path =
            val p = uri.getRawPath
            val q = uri.getRawQuery
            if p == null || p.isEmpty then
                if q == null then "/" else "/?" + q
            else if q == null then p
            else p + "?" + q
            end if
        end path

        // Create Netty request with correct path (for redirects) and request body/headers
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

        // Copy headers from request (excluding host/connection which we set above)
        val reqHeaders = request.headers
        reqHeaders.foreach { case (name, value) =>
            val lowerName = name.toLowerCase
            if lowerName != "host" && lowerName != "connection" then
                discard(nettyRequest.headers().set(name, value))
        }

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
    )(using Frame) extends SimpleChannelInboundHandler[FullHttpResponse]:
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
