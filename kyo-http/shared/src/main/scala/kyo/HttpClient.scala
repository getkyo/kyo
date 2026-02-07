package kyo

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.{Channel as NettyChannel, *}
import io.netty.channel.pool.*
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.util.concurrent.Future as NettyFuture
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kyo.internal.NdjsonDecoder
import kyo.internal.NettyTransport
import kyo.internal.NettyUtil
import kyo.internal.ResponseHandler
import kyo.internal.ResponseStreamingHandler
import kyo.internal.SseDecoder
import kyo.internal.StreamingHeaders

final class HttpClient private (
    private val workerGroup: MultiThreadIoEventLoopGroup,
    private val bootstrap: Bootstrap,
    private val poolMap: ConcurrentHashMap[HttpClient.PoolKey, ChannelPool],
    private val sslContext: SslContext,
    private val maxConnectionsPerHost: Maybe[Int],
    private val connectionAcquireTimeout: Duration,
    private val maxResponseSizeBytes: Int
):
    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        HttpFilter.use { filter =>
            HttpClient.configLocal.use { config =>
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
                            filteredRequest.asInstanceOf[HttpRequest[HttpBody.Bytes]],
                            config
                        )
                ).map(_.ensureBytes) // Filter can short-circuit with any response type
            }
        }

    /** Streams the response as an HttpResponse with a streaming body.
      *
      * Uses a non-pooled connection. The connection is closed when the enclosing Scope exits. Applies filters to the request/response.
      */
    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        HttpFilter.use { filter =>
            HttpClient.configLocal.use { config =>
                filter(
                    request,
                    filteredRequest =>
                        val effectiveUrl = HttpClient.buildEffectiveUrl(config, filteredRequest)
                        HttpClient.connectStreaming(bootstrap, sslContext, effectiveUrl, filteredRequest, config)
                ).map { response =>
                    response.body match
                        case _: HttpBody.Streamed =>
                            response.asInstanceOf[HttpResponse[HttpBody.Streamed]]
                        case b: HttpBody.Bytes =>
                            // A filter can short-circuit with a cached/mocked buffered response.
                            // Wrap the bytes as a single-chunk stream so the streaming contract holds.
                            response.withBody(HttpBody.stream(Stream.init(Chunk(b.span))))
                }
            }
        }

    def send(url: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        send(HttpRequest.get(url))

    def stream(url: String)(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        stream(HttpRequest.get(url))

    /** Streams Server-Sent Events from the given URL. */
    def streamSse[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
    ): Stream[ServerSentEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        streamSse[V](HttpRequest.get(url))

    /** Streams Server-Sent Events from the given request. */
    def streamSse[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
    ): Stream[ServerSentEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        stream(request).map { response =>
            val decoder = new SseDecoder[V](Schema[V])
            response.bodyStream.mapChunkPure[Span[Byte], ServerSentEvent[V]] { chunk =>
                val result = Seq.newBuilder[ServerSentEvent[V]]
                chunk.foreach(bytes => result ++= decoder.decode(bytes))
                result.result()
            }
        }

    /** Streams NDJSON values from the given URL. */
    def streamNdjson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Scope & Abort[HttpError]) =
        streamNdjson[V](HttpRequest.get(url))

    /** Streams NDJSON values from the given request. */
    def streamNdjson[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Scope & Abort[HttpError]) =
        stream(request).map { response =>
            val decoder = new NdjsonDecoder[V](Schema[V])
            response.bodyStream.mapChunkPure[Span[Byte], V] { chunk =>
                val result = Seq.newBuilder[V]
                chunk.foreach(bytes => result ++= decoder.decode(bytes))
                result.result()
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
        require(connections > 0, s"connections must be positive: $connections")
        Async.fill(connections)(Abort.run[HttpError](send(HttpRequest.head(url)))).unit
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
            NettyUtil.await(workerGroup.shutdownGracefully(graceMs, graceMs, TimeUnit.MILLISECONDS))
        }
end HttpClient

object HttpClient:

    // --- Convenience methods (use shared client) ---

    def get[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.get(url)).map(checkStatusAndParse[A])

    def post[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.post(url, body)).map(checkStatusAndParse[A])

    def put[A: Schema, B: Schema](url: String, body: B)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.put(url, body)).map(checkStatusAndParse[A])

    def delete[A: Schema](url: String)(using Frame): A < (Async & Abort[HttpError]) =
        send(HttpRequest.delete(url)).map(checkStatusAndParse[A])

    def send(url: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        clientLocal.use(_.send(url))

    def send(request: HttpRequest[HttpBody.Bytes])(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        clientLocal.use(_.send(request))

    def stream(request: HttpRequest[?])(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.stream(request))

    def stream(url: String)(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.stream(HttpRequest.get(url)))

    def streamSse[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
    ): Stream[ServerSentEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamSse[V](url))

    def streamSse[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[ServerSentEvent[V]]]]
    ): Stream[ServerSentEvent[V], Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamSse[V](request))

    def streamNdjson[V: Schema: Tag](url: String)(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamNdjson[V](url))

    def streamNdjson[V: Schema: Tag](request: HttpRequest[?])(using
        Frame,
        Tag[Emit[Chunk[V]]]
    ): Stream[V, Async] < (Async & Scope & Abort[HttpError]) =
        clientLocal.use(_.streamNdjson[V](request))

    // --- Context management ---

    def let[A, S](client: HttpClient)(v: A < S)(using Frame): A < S =
        clientLocal.let(client)(v)

    def withConfig[A, S](f: Config => Config)(v: A < S)(using Frame): A < S =
        configLocal.use(c => configLocal.let(f(c))(v))

    // --- Factory methods ---

    def init(
        maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
        connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
        maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
        daemon: Boolean = false
    )(using Frame): HttpClient < Sync =
        Sync.Unsafe {
            Unsafe.init(maxConnectionsPerHost, connectionAcquireTimeout, maxResponseSizeBytes, daemon)
        }
    end init

    object Unsafe:
        /** Low-level client initialization requiring AllowUnsafe.
          * @param daemon
          *   If true, uses daemon threads (JVM can exit while client is active)
          */
        def init(
            maxConnectionsPerHost: Maybe[Int] = DefaultMaxConnectionsPerHost,
            connectionAcquireTimeout: Duration = DefaultConnectionAcquireTimeout,
            maxResponseSizeBytes: Int = DefaultMaxResponseSizeBytes,
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

    // --- Config ---

    case class Config(
        baseUrl: Maybe[String] = Absent,
        timeout: Maybe[Duration] = Absent,
        connectTimeout: Maybe[Duration] = Absent,
        followRedirects: Boolean = true,
        maxRedirects: Int = 10,
        retrySchedule: Maybe[Schedule] = Absent,
        retryOn: HttpResponse[?] => Boolean = _.status.isServerError
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
        def retryWhen(f: HttpResponse[?] => Boolean): Config =
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

    private[kyo] inline def DefaultHttpPort                  = 80
    private[kyo] inline def DefaultHttpsPort                 = 443
    private[kyo] inline def DefaultMaxConnectionsPerHost     = Maybe(100)
    private[kyo] inline def DefaultConnectionAcquireTimeout  = 30.seconds
    private[kyo] inline def DefaultMaxResponseSizeBytes: Int = 1048576

    private case class PoolKey(host: String, port: Int, ssl: Boolean)

    // Shared client uses daemon threads so JVM can exit
    private lazy val sharedClient: HttpClient =
        import AllowUnsafe.embrace.danger
        Unsafe.init(daemon = true)

    private val clientLocal      = Local.init(sharedClient)
    private[kyo] val configLocal = Local.init(Config.default)

    private def checkStatusAndParse[A: Schema](response: HttpResponse[HttpBody.Bytes])(using Frame): A < Abort[HttpError] =
        if response.status.isError then
            Abort.fail(HttpError.StatusError(response.status, response.bodyText))
        else
            response.bodyAs[A]

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
                    override def channelCreated(ch: NettyChannel): Unit =
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

    private def buildEffectiveUrl(config: Config, request: HttpRequest[?]): String =
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
        request: HttpRequest[HttpBody.Bytes],
        config: Config,
        redirectCount: Int = 0,
        retrySchedule: Maybe[Schedule] = Absent,
        attemptCount: Int = 1
    )(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        Sync.Unsafe {
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

            // Acquire channel from pool — manual Promise used instead of NettyUtil.continue
            // because we need getResult to pattern match on Success/Panic/Failure for
            // specific error types (HttpError.fromThrowable vs ConnectionFailed)
            val acquirePromise = Promise.Unsafe.init[NettyChannel, Any]()
            val acquireFuture  = pool.acquire()
            discard {
                acquireFuture.addListener { (future: NettyFuture[NettyChannel]) =>
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
                                            Abort.fail(HttpError.RetriesExhausted(attemptCount, finalResp.status, finalResp.bodyText))
                                }
                            case _ =>
                                // No retry needed
                                finalResp
                    }
                case Result.Panic(e)   => Abort.fail(HttpError.fromThrowable(e, host, port))
                case Result.Failure(e) => Abort.fail(HttpError.ConnectionFailed(host, port, new RuntimeException(e.toString)))
            }
        } // end Sync.Unsafe
    end sendRaw

    private def sendOnChannel(
        channel: NettyChannel,
        uri: URI,
        request: HttpRequest[HttpBody.Bytes],
        host: String,
        port: Int,
        config: Config
    )(using Frame, AllowUnsafe): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        val path = buildRequestPath(uri)

        // Create Netty request with correct path (for redirects) and request body/headers
        // Note: this builds a Netty request with the redirect URI path, which differs from
        // request.toNetty (which uses the original URL). Not extractable into HttpRequest.
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

        val promise = Promise.Unsafe.init[HttpResponse[HttpBody.Bytes], Abort[HttpError]]()

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

        // Send the request — manual listener used instead of NettyUtil.continue because
        // write failure must be reported through the shared response promise
        val writeFuture = channel.writeAndFlush(nettyRequest)
        discard {
            writeFuture.addListener((wf: ChannelFuture) =>
                if !wf.isSuccess then
                    discard(promise.complete(Result.fail(HttpError.fromThrowable(wf.cause(), host, port))))
            )
        }

        promise.safe.get
    end sendOnChannel

    private def buildRequestPath(uri: URI): String =
        val p = uri.getRawPath
        val q = uri.getRawQuery
        if p == null || p.isEmpty then
            if q == null then "/" else "/?" + q
        else if q == null then p
        else p + "?" + q
        end if
    end buildRequestPath

    private def connectStreaming(
        bootstrap: Bootstrap,
        sslContext: SslContext,
        url: String,
        request: HttpRequest[?],
        config: Config
    )(using Frame): HttpResponse[HttpBody.Streamed] < (Async & Scope & Abort[HttpError]) =
        val uri    = new URI(url)
        val scheme = if uri.getScheme == null then "http" else uri.getScheme
        val host   = uri.getHost
        val port =
            if uri.getPort > 0 then uri.getPort
            else if scheme == "https" then DefaultHttpsPort
            else DefaultHttpPort
        val useSsl = scheme == "https"

        Sync.Unsafe {
            val byteChannel   = Channel.Unsafe.init[Span[Byte]](32)
            val headerPromise = Promise.Unsafe.init[StreamingHeaders, Abort[HttpError]]()

            val b = bootstrap.clone().remoteAddress(new InetSocketAddress(host, port))
            config.connectTimeout.foreach { timeout =>
                discard(b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Integer.valueOf(timeout.toMillis.toInt)))
            }
            discard(b.handler(new ChannelInitializer[SocketChannel]:
                override def initChannel(ch: SocketChannel): Unit =
                    val pipeline = ch.pipeline()
                    if useSsl then
                        discard(pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port)))
                    discard(pipeline.addLast("codec", new HttpClientCodec()))
                    discard(pipeline.addLast(
                        "handler",
                        new ResponseStreamingHandler(headerPromise, byteChannel, host, port)
                    ))))

            NettyUtil.continue(b.connect()) { nettyChannel =>
                // Close connection when scope exits; channelInactive will close byte channel
                Scope.ensure(NettyUtil.await(nettyChannel.close())).andThen {
                    val path = buildRequestPath(uri)

                    // Send request — branch on body type
                    val sendRequest: Unit < (Async & Abort[HttpError]) = request.body match
                        case bytes: HttpBody.Bytes =>
                            // Buffered body: send as FullHttpRequest
                            val bodyData = bytes.data
                            val nettyRequest = new DefaultFullHttpRequest(
                                HttpVersion.HTTP_1_1,
                                HttpMethod.valueOf(request.method.name),
                                path,
                                Unpooled.wrappedBuffer(bodyData)
                            )
                            discard(nettyRequest.headers().set(HttpHeaderNames.HOST, host))
                            discard(nettyRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE))
                            if bodyData.nonEmpty then
                                discard(nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyData.length))
                            request.headers.foreach { case (name, value) =>
                                val lowerName = name.toLowerCase
                                if lowerName != "host" && lowerName != "connection" then
                                    discard(nettyRequest.headers().set(name, value))
                            }
                            val writeFuture = nettyChannel.writeAndFlush(nettyRequest)
                            NettyUtil.await(writeFuture)

                        case streamed: HttpBody.Streamed =>
                            // Streaming body: send headers first, then stream chunks
                            val nettyRequest = new io.netty.handler.codec.http.DefaultHttpRequest(
                                HttpVersion.HTTP_1_1,
                                HttpMethod.valueOf(request.method.name),
                                path
                            )
                            discard(nettyRequest.headers().set(HttpHeaderNames.HOST, host))
                            discard(nettyRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE))
                            discard(nettyRequest.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED))
                            request.headers.foreach { case (name, value) =>
                                val lowerName = name.toLowerCase
                                if lowerName != "host" && lowerName != "connection" && lowerName != "transfer-encoding" then
                                    discard(nettyRequest.headers().set(name, value))
                            }
                            // Send headers
                            NettyUtil.continue(nettyChannel.writeAndFlush(nettyRequest)) { _ =>
                                // Stream body chunks
                                streamed.stream.foreach { bytes =>
                                    NettyUtil.await(
                                        nettyChannel.writeAndFlush(
                                            new io.netty.handler.codec.http.DefaultHttpContent(
                                                Unpooled.wrappedBuffer(bytes.toArrayUnsafe)
                                            )
                                        )
                                    )
                                }.andThen {
                                    // Send terminal chunk
                                    NettyUtil.await(
                                        nettyChannel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                                    )
                                }
                            }

                    sendRequest.map { _ =>
                        // Wait for response headers, then build streaming response
                        headerPromise.safe.get.map { streamingHeaders =>
                            val bodyStream = byteChannel.safe.streamUntilClosed()
                            HttpResponse.initStreaming(streamingHeaders.status, streamingHeaders.headers, bodyStream)
                        }
                    }
                }
            }
        }
    end connectStreaming

end HttpClient
