package kyo.internal

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.{Channel as _, *}
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.*
import io.netty.handler.ssl.SslContextBuilder
import io.netty.util.concurrent.DefaultThreadFactory
import java.net.InetSocketAddress
import java.net.URI
import kyo.{WebSocketFrame as WsFrame, *}

final private[kyo] class NettyWebSocketClientBackend extends HttpBackend.WebSocketClient:

    private val threadFactory = DefaultThreadFactory("kyo-ws", true)
    private val workerGroup   = MultiThreadIoEventLoopGroup(threadFactory, NettyTransport.ioHandlerFactory)
    private val bootstrap     = Bootstrap().group(workerGroup).channel(NettyTransport.socketChannelClass)
    private val sslContext    = SslContextBuilder.forClient().build()

    def connect[A, S](
        host: String,
        port: Int,
        path: String,
        ssl: Boolean,
        headers: HttpHeaders,
        config: WebSocketConfig
    )(
        f: WebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        Sync.Unsafe.defer {
            val handshakePromise = Promise.Unsafe.init[WebSocket, Abort[HttpException]]()(using summon[AllowUnsafe])

            val scheme = if ssl then "wss" else "ws"
            val uri    = new URI(s"$scheme://$host:$port$path")
            val subprotocols =
                if config.subprotocols.isEmpty then null
                else config.subprotocols.mkString(",")

            val customHeaders = new DefaultHttpHeaders()
            headers.foreach((name, value) => discard(customHeaders.add(name, value)))

            val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri,
                WebSocketVersion.V13,
                subprotocols,
                true,
                customHeaders,
                config.maxFrameSize
            )

            val b = bootstrap.clone()
                .remoteAddress(new InetSocketAddress(host, port))
                .option(ChannelOption.TCP_NODELAY, java.lang.Boolean.TRUE)
                .handler(new ChannelInitializer[SocketChannel]:
                    override def initChannel(ch: SocketChannel): Unit =
                        val pipeline = ch.pipeline()
                        if ssl then
                            discard(pipeline.addLast("ssl", sslContext.newHandler(ch.alloc(), host, port)))
                        discard(pipeline.addLast("http-codec", new HttpClientCodec()))
                        discard(pipeline.addLast("http-aggregator", new HttpObjectAggregator(8192)))
                        discard(pipeline.addLast(
                            "ws-handler",
                            new NettyWebSocketClientHandler(handshaker, config, handshakePromise)
                        )))

            NettyUtil.continue(b.connect()) { channel =>
                handshakePromise.safe.use { ws =>
                    Sync.ensure {
                        Sync.defer {
                            import AllowUnsafe.embrace.danger
                            if channel.isActive then
                                discard(channel.writeAndFlush(new CloseWebSocketFrame(1000, "")))
                            discard(channel.close())
                        }
                    } {
                        f(ws)
                    }
                }
            }
        }
end NettyWebSocketClientBackend

/** Client-side WebSocket handler using manual handshake (like Netty's official example). */
private[kyo] class NettyWebSocketClientHandler(
    handshaker: WebSocketClientHandshaker,
    config: WebSocketConfig,
    handshakePromise: Promise.Unsafe[WebSocket, Abort[HttpException]]
)(using frame: Frame) extends SimpleChannelInboundHandler[AnyRef]:

    import AllowUnsafe.embrace.danger

    private var inbound: Channel.Unsafe[WsFrame]                       = scala.compiletime.uninitialized
    private var outbound: Channel.Unsafe[WsFrame]                      = scala.compiletime.uninitialized
    private var closeReasonRef: AtomicRef.Unsafe[Maybe[(Int, String)]] = scala.compiletime.uninitialized
    private var drainFiber: Fiber[Unit, Any]                           = scala.compiletime.uninitialized
    private var handshakeComplete                                      = false

    override def channelActive(ctx: ChannelHandlerContext): Unit =
        handshaker.handshake(ctx.channel())
        super.channelActive(ctx)

    override def channelRead0(ctx: ChannelHandlerContext, msg: AnyRef): Unit =

        if !handshakeComplete then
            msg match
                case response: FullHttpResponse =>
                    try
                        handshaker.finishHandshake(ctx.channel(), response)
                        handshakeComplete = true
                        startWebSocket(ctx)
                    catch
                        case e: WebSocketHandshakeException =>
                            val status = response.status().code()
                            discard(handshakePromise.complete(
                                Result.Failure(
                                    HttpWebSocketHandshakeException(handshaker.uri().toString, status)
                                )
                            ))
                case _ =>
                    discard(ctx.close())
        else
            msg match
                case frame: TextWebSocketFrame =>
                    deliverInbound(ctx, WsFrame.Text(frame.text()))

                case frame: BinaryWebSocketFrame =>
                    val buf   = frame.content()
                    val bytes = new Array[Byte](buf.readableBytes())
                    buf.readBytes(bytes)
                    deliverInbound(ctx, WsFrame.Binary(Span.fromUnsafe(bytes)))

                case frame: CloseWebSocketFrame =>
                    val code   = frame.statusCode()
                    val reason = if frame.reasonText() != null then frame.reasonText() else ""
                    discard(closeReasonRef.set(Present((code, reason))))
                    discard(inbound.close())

                case _: PongWebSocketFrame => ()

                case _ => ()
        end if
    end channelRead0

    private def startWebSocket(ctx: ChannelHandlerContext): Unit =
        inbound = Channel.Unsafe.init[WsFrame](config.bufferSize)
        outbound = Channel.Unsafe.init[WsFrame](config.bufferSize)
        closeReasonRef = AtomicRef.Unsafe.init[Maybe[(Int, String)]](Absent)

        val closeFn: (Int, String) => Unit < Async = (code, reason) =>
            Sync.defer {
                import AllowUnsafe.embrace.danger
                discard(closeReasonRef.set(Present((code, reason))))
                if ctx.channel().isActive then
                    discard(ctx.writeAndFlush(new CloseWebSocketFrame(code, reason)))
            }

        val ws = new WebSocket(inbound.safe, outbound.safe, closeReasonRef.safe, closeFn)

        drainFiber = NettyUtil.launchFiber {
            Loop.foreach {
                outbound.safe.takeWith { wsFrame =>
                    val nettyFrame = wsFrame match
                        case WsFrame.Text(data) =>
                            new TextWebSocketFrame(data)
                        case WsFrame.Binary(data) =>
                            new BinaryWebSocketFrame(Unpooled.wrappedBuffer(data.toArrayUnsafe))
                    NettyUtil.awaitWith(ctx.writeAndFlush(nettyFrame)) {
                        Loop.continue
                    }
                }
            }.handle(Abort.run[Closed]).unit
        }

        val df = drainFiber
        discard {
            ctx.channel().closeFuture().addListener((_: ChannelFuture) =>
                discard(inbound.close())
                discard(outbound.close())
                discard(df.unsafe.interrupt(Result.Panic(new Exception("WebSocket disconnected"))))
            )
        }

        discard(handshakePromise.complete(Result.succeed(ws)))
    end startWebSocket

    private def deliverInbound(ctx: ChannelHandlerContext, value: WsFrame)(using AllowUnsafe): Unit =
        if !handshakeComplete then return
        inbound.offer(value) match
            case Result.Success(true) =>
            case Result.Success(false) =>
                ctx.channel().config().setAutoRead(false)
                val fiber = inbound.putFiber(value)
                fiber.onComplete { _ =>
                    ctx.channel().config().setAutoRead(true)
                    discard(ctx.read())
                }
            case _ =>
                discard(ctx.channel().config().setAutoRead(false))
        end match
    end deliverInbound

    override def channelInactive(ctx: ChannelHandlerContext): Unit =

        if !handshakeComplete then
            discard(handshakePromise.complete(
                Result.Panic(new Exception("Connection closed before WebSocket handshake"))
            ))
        else
            discard(inbound.close())
            discard(outbound.close())
        end if
        super.channelInactive(ctx)
    end channelInactive

    override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit =

        if !handshakeComplete then
            discard(handshakePromise.complete(Result.Panic(cause)))
        discard(ctx.close())
    end exceptionCaught

end NettyWebSocketClientHandler
