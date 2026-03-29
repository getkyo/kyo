package kyo.internal

import io.netty.buffer.Unpooled
import io.netty.channel.{Channel as _, *}
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import kyo.{WebSocketFrame as WsFrame, *}
import scala.compiletime.uninitialized

/** Server-side WebSocket frame handler for Netty. */
final private[kyo] class NettyWebSocketFrameHandler(
    wsHandler: WebSocketHttpHandler,
    request: HttpRequest[Any],
    config: WebSocketConfig
)(using frame: Frame) extends ChannelInboundHandlerAdapter:

    import AllowUnsafe.embrace.danger

    // Mutable state — safe because Netty guarantees single-thread-per-channel.
    private var inbound: Channel.Unsafe[WsFrame]                       = uninitialized
    private var outbound: Channel.Unsafe[WsFrame]                      = uninitialized
    private var closeReasonRef: AtomicRef.Unsafe[Maybe[(Int, String)]] = uninitialized
    private var handlerFiber: Fiber[Unit, Any]                         = uninitialized
    private var drainFiber: Fiber[Unit, Any]                           = uninitialized
    private var started                                                = false
    private var pendingFrames: java.util.ArrayDeque[WsFrame]           = new java.util.ArrayDeque()

    override def userEventTriggered(ctx: ChannelHandlerContext, evt: Any): Unit =
        evt match
            case _: WebSocketServerProtocolHandler.HandshakeComplete =>
                startWebSocket(ctx)
            case _ =>
                super.userEventTriggered(ctx, evt)

    /** Called when the WebSocket handshake completes. */
    def startWebSocket(ctx: ChannelHandlerContext): Unit =

        if started then return
        started = true

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

        handlerFiber = NettyUtil.launchFiber {
            Sync.ensure {
                Sync.defer {
                    import AllowUnsafe.embrace.danger
                    // Close outbound to signal drain fiber to stop, then close inbound
                    discard(outbound.close())
                    if ctx.channel().isActive then
                        discard(ctx.writeAndFlush(new CloseWebSocketFrame(1000, "")))
                    discard(inbound.close())
                }
            } {
                wsHandler.wsHandler(request, ws).handle(Abort.run[Any]).unit
            }
        }

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

        // Drain any frames received before startWebSocket was called
        while !pendingFrames.isEmpty do
            val frame = pendingFrames.poll()
            discard(inbound.offer(frame))
        end while
        pendingFrames = null

        val hf = handlerFiber
        val df = drainFiber
        discard {
            ctx.channel().closeFuture().addListener((_: ChannelFuture) =>
                discard(inbound.close())
                discard(outbound.close())
                val panic = Result.Panic(new Exception("WebSocket disconnected"))
                discard(hf.unsafe.interrupt(panic))
                discard(df.unsafe.interrupt(panic))
            )
        }
    end startWebSocket

    override def channelRead(ctx: ChannelHandlerContext, msg: Any): Unit =
        msg match
            case frame: TextWebSocketFrame =>
                deliverInbound(ctx, WsFrame.Text(frame.text()))
                discard(frame.release())

            case frame: BinaryWebSocketFrame =>
                val buf   = frame.content()
                val bytes = new Array[Byte](buf.readableBytes())
                buf.readBytes(bytes)
                deliverInbound(ctx, WsFrame.Binary(Span.fromUnsafe(bytes)))
                discard(frame.release())

            case frame: CloseWebSocketFrame =>
                val code   = frame.statusCode()
                val reason = if frame.reasonText() != null then frame.reasonText() else ""
                if started then
                    discard(closeReasonRef.set(Present((code, reason))))
                    discard(inbound.close())
                discard(frame.release())

            case _ =>
                super.channelRead(ctx, msg)
    end channelRead

    private def deliverInbound(ctx: ChannelHandlerContext, value: WsFrame)(using AllowUnsafe): Unit =
        if !started then
            pendingFrames.add(value)
            return
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

        if started then
            discard(inbound.close())
            discard(outbound.close())
        super.channelInactive(ctx)
    end channelInactive

end NettyWebSocketFrameHandler
