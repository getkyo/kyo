package kyo.internal

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.*
import kyo.*

/** Shared NIO Selector-based I/O event loop for JVM.
  *
  * A single Selector is shared across all connections. Fibers enqueue their (channel, op, promise) into a registration queue, wakeup the
  * selector, then suspend on their Promise. The poller drains the queue, registers interest, calls the @blocking selector.select(), then
  * completes Promises for all ready keys.
  *
  * Thread safety: ConcurrentLinkedQueue for the registration queue; Selector wakeup() is called after each enqueue so the poller sees new
  * registrations promptly.
  *
  * AllowUnsafe is confined to the single point where the poller completes Promises.
  */
private[kyo] class NioIoLoop extends IoLoop:

    private val selector = Selector.open()

    // Registration request: channel, interest op, promise to complete when ready
    private case class RegRequest(
        channel: SocketChannel,
        op: Int,
        promise: Promise.Unsafe[Unit, Any]
    )

    private val regQueue = new ConcurrentLinkedQueue[RegRequest]()

    // Slot indices in the per-key promise array (size 3)
    private val SlotRead    = 0 // OP_READ  = 1
    private val SlotWrite   = 1 // OP_WRITE = 4
    private val SlotConnect = 2 // OP_CONNECT = 8

    private type PromiseSlots = Array[Maybe[Promise.Unsafe[Unit, Any]]]

    private def slotFor(op: Int): Int =
        if op == SelectionKey.OP_READ then SlotRead
        else if op == SelectionKey.OP_WRITE then SlotWrite
        else SlotConnect

    /** Wake up the selector so the poller can notice newly closed channels. */
    def wakeup(): Unit = discard(selector.wakeup())

    /** Drain pending promises for a closed channel so waiting fibers are not stuck forever. */
    private[kyo] def drainClosedChannel(channel: SocketChannel)(using AllowUnsafe): Unit =
        Maybe(channel.keyFor(selector)) match
            case Present(key) =>
                val promises = key.attachment().asInstanceOf[PromiseSlots]
                if promises ne null then
                    for slot <- 0 until promises.length do
                        promises(slot) match
                            case Present(p) =>
                                promises(slot) = Absent
                                p.completeUnitDiscard()
                            case Absent =>
                        end match
                    end for
                end if
                key.cancel()
            case Absent =>
        end match
    end drainClosedChannel

    /** Start the shared poller fiber. Must be called once before any awaitReadable/awaitWritable. */
    def start()(using Frame): Unit < Async =
        Fiber.initUnscoped {
            Loop.foreach {
                Sync.defer {
                    // Drain the registration queue before blocking, then block on select
                    Loop.foreach {
                        Maybe(regQueue.poll()) match
                            case Present(req) =>
                                if req.channel.isOpen then
                                    Maybe(req.channel.keyFor(selector)) match
                                        case Present(existingKey) if existingKey.isValid =>
                                            // Accumulate interest on the existing key; update promise slot
                                            val promises = existingKey.attachment().asInstanceOf[PromiseSlots]
                                            promises(slotFor(req.op)) = Present(req.promise)
                                            discard(existingKey.interestOps(existingKey.interestOps() | req.op))
                                        case _ =>
                                            val promises: PromiseSlots = Array(Absent, Absent, Absent)
                                            promises(slotFor(req.op)) = Present(req.promise)
                                            discard(req.channel.register(selector, req.op, promises))
                                    end match
                                else
                                    // Channel was closed before we could register; complete the promise
                                    // so the waiting fiber unblocks immediately.
                                    import AllowUnsafe.embrace.danger
                                    req.promise.completeUnitDiscard()
                                end if
                                Loop.continue
                            case Absent =>
                                Loop.done(())
                    }
                }.andThen {
                    // @blocking: OS thread blocks here; Kyo scheduler spawns workers as needed
                    try selector.select()
                    catch case _: java.nio.channels.ClosedSelectorException => ()
                }.andThen {
                    val keys = selector.selectedKeys()
                    val iter = keys.iterator()
                    Loop.foreach {
                        if iter.hasNext then
                            val key = iter.next()
                            iter.remove()
                            if key.isValid then
                                val promises = key.attachment().asInstanceOf[PromiseSlots]
                                val ready    = key.readyOps()
                                // Clear the ready bits from interest to avoid re-firing
                                discard(key.interestOps(key.interestOps() & ~ready))
                                import AllowUnsafe.embrace.danger
                                if (ready & SelectionKey.OP_READ) != 0 then
                                    promises(SlotRead) match
                                        case Present(p) =>
                                            promises(SlotRead) = Absent
                                            p.completeUnitDiscard()
                                        case Absent =>
                                    end match
                                end if
                                if (ready & SelectionKey.OP_WRITE) != 0 then
                                    promises(SlotWrite) match
                                        case Present(p) =>
                                            promises(SlotWrite) = Absent
                                            p.completeUnitDiscard()
                                        case Absent =>
                                    end match
                                end if
                                if (ready & SelectionKey.OP_CONNECT) != 0 then
                                    promises(SlotConnect) match
                                        case Present(p) =>
                                            promises(SlotConnect) = Absent
                                            p.completeUnitDiscard()
                                        case Absent =>
                                    end match
                                end if
                            end if
                            Loop.continue
                        else
                            Loop.done(())
                        end if
                    }
                }.andThen(Loop.continue)
            }
        }.unit

    def awaitReadable(fd: Int)(using Frame): Unit < Async =
        Abort.panic(new UnsupportedOperationException("NioIoLoop.awaitReadable(Int) is not used; call awaitChannelReadable"))

    def awaitWritable(fd: Int)(using Frame): Unit < Async =
        Abort.panic(new UnsupportedOperationException("NioIoLoop.awaitWritable(Int) is not used; call awaitChannelWritable"))

    def awaitChannelReadable(channel: SocketChannel)(using Frame): Unit < Async =
        awaitOp(channel, SelectionKey.OP_READ)

    def awaitChannelWritable(channel: SocketChannel)(using Frame): Unit < Async =
        awaitOp(channel, SelectionKey.OP_WRITE)

    def awaitChannelConnectable(channel: SocketChannel)(using Frame): Unit < Async =
        awaitOp(channel, SelectionKey.OP_CONNECT)

    private def awaitOp(channel: SocketChannel, op: Int)(using Frame): Unit < Async =
        Promise.init[Unit, Any].map { promise =>
            Sync.defer {
                import AllowUnsafe.embrace.danger
                regQueue.add(RegRequest(channel, op, promise.unsafe))
                discard(selector.wakeup())
            }.andThen(promise.get)
        }

    def close(): Unit =
        selector.close()

end NioIoLoop

/** Non-blocking NIO Transport for JVM.
  *
  * Connections are distributed across a group of NioIoLoop instances via round-robin for multi-core scaling. Each NioIoLoop has its own
  * Selector and poller fiber.
  *
  * The default IoLoopGroup is a lazy companion-object singleton so that multiple NioTransport instances share the same poller threads.
  * Callers may supply a custom group for isolation (e.g. testing). TLS configuration stays per-instance since it is used at connection
  * creation, not at the IoLoop level.
  */
object NioTransport:
    private val groupSize = Math.max(1, Runtime.getRuntime.availableProcessors / 2)
    lazy val defaultGroup: IoLoopGroup[NioIoLoop] =
        val g = new IoLoopGroup((0 until groupSize).map(_ => new NioIoLoop))
        Runtime.getRuntime.addShutdownHook(new Thread(() => g.closeAll()))
        g
    end defaultGroup
end NioTransport

final class NioTransport(
    group: IoLoopGroup[NioIoLoop] = NioTransport.defaultGroup,
    clientSslContext: Maybe[SSLContext] = Absent,
    serverSslContext: Maybe[SSLContext] = Absent
) extends Transport:

    type Connection = NioConnection

    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): NioConnection < (Async & Abort[HttpException]) =
        group.ensureStarted().andThen {
            tls match
                case Present(tlsCfg) => connectTls(host, port, tlsCfg)
                case Absent          => connectPlain(host, port)
        }

    private def connectTls(host: String, port: Int, tlsCfg: TlsConfig)(using
        Frame
    ): NioConnection < (Async & Abort[HttpException]) =
        connectPlain(host, port).map { conn =>
            Sync.defer {
                val ctx = clientSslContext match
                    case Present(c) => c
                    case Absent =>
                        if tlsCfg.trustAll then
                            val ctx = SSLContext.getInstance("TLS")
                            ctx.init(null, Array(TrustAllManager), new java.security.SecureRandom())
                            ctx
                        else
                            SSLContext.getDefault
                val engine = ctx.createSSLEngine(host, port)
                engine.setUseClientMode(true)
                val params = engine.getSSLParameters
                val sni    = tlsCfg.sniHostname.getOrElse(host)
                params.setServerNames(java.util.List.of(new SNIHostName(sni)))
                val alpn = tlsCfg.alpnProtocols.toArray
                if alpn.nonEmpty then params.setApplicationProtocols(alpn)
                if !tlsCfg.trustAll then params.setEndpointIdentificationAlgorithm("HTTPS")
                engine.setSSLParameters(params)
                val bridge    = new NioStreamTlsBridge(conn)
                val tlsStream = new NioTlsStream(bridge, engine)
                conn.tlsStream = Present(tlsStream)
                tlsStream.handshake().map(_ => conn)
            }
        }

    private def connectPlain(host: String, port: Int)(using
        Frame
    ): NioConnection < (Async & Abort[HttpException]) =
        Sync.defer {
            val loop    = group.next()
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
            val connected = channel.connect(new InetSocketAddress(host, port))
            if connected then
                Sync.defer(new NioConnection(channel, loop))
            else
                loop.awaitChannelConnectable(channel).andThen {
                    Sync.defer {
                        Abort.recover[Exception](_ =>
                            Abort.fail(HttpConnectException(host, port, new Exception("connect failed")))
                        ) {
                            Sync.defer {
                                channel.finishConnect()
                                new NioConnection(channel, loop)
                            }
                        }
                    }
                }
            end if
        }

    def isAlive(c: NioConnection)(using Frame): Boolean < Sync =
        Sync.defer(c.channel.isOpen && c.channel.isConnected)

    def closeNow(c: NioConnection)(using Frame): Unit < Sync =
        Sync.defer {
            // Drain pending promises first so waiting fibers unblock immediately when the
            // channel closes, then close the channel and wake up the selector so the poller
            // can process the cancelled key on its next iteration.
            import AllowUnsafe.embrace.danger
            c.loop.drainClosedChannel(c.channel)
            c.channel.close()
            c.loop.wakeup()
        }

    def close(c: NioConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        closeNow(c)

    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): TransportListener[NioConnection] < (Async & Scope) =
        group.ensureStarted().andThen {
            Sync.defer {
                val serverChannel = ServerSocketChannel.open()
                serverChannel.configureBlocking(false)
                try
                    serverChannel.bind(new InetSocketAddress(host, port), backlog)
                catch
                    case e: java.net.BindException =>
                        serverChannel.close()
                        throw HttpBindException(host, port, e)
                end try
                val actualPort = serverChannel.socket().getLocalPort
                val actualHost = host

                // Dedicated selector just for accept — ServerSocketChannel can't use the
                // shared SocketChannel selector (different channel types). This selector is
                // only used for OP_ACCEPT, so no contention with the NioIoLoop.
                val acceptSelector = Selector.open()
                serverChannel.register(acceptSelector, SelectionKey.OP_ACCEPT)

                tls match
                    case Absent =>
                        Scope.acquireRelease {
                            val connStream: Stream[NioConnection, Async] =
                                Stream.unfold((), chunkSize = 1) { _ =>
                                    pollAcceptSelector(acceptSelector).map {
                                        case false => Maybe.empty
                                        case true =>
                                            Maybe(serverChannel.accept()).map { clientChannel =>
                                                clientChannel.configureBlocking(false)
                                                clientChannel.setOption(
                                                    StandardSocketOptions.TCP_NODELAY,
                                                    java.lang.Boolean.TRUE
                                                )
                                                (new NioConnection(clientChannel, group.next()), ())
                                            }
                                    }
                                }
                            new TransportListener(
                                actualPort,
                                actualHost,
                                connStream,
                                close = Sync.defer { acceptSelector.close(); serverChannel.close() }
                            )
                        } { _ =>
                            Sync.defer {
                                acceptSelector.close()
                                serverChannel.close()
                            }
                        }

                    case Present(tlsCfg) =>
                        val sslCtx = serverSslContext match
                            case Present(c) => c
                            case Absent     => createServerSslContext(tlsCfg)
                        Scope.acquireRelease {
                            val connStream: Stream[NioConnection, Async] =
                                Stream.unfold((), chunkSize = 1) { _ =>
                                    pollAcceptSelector(acceptSelector).map {
                                        case false => Maybe.empty
                                        case true =>
                                            Maybe(serverChannel.accept()).map { clientChannel =>
                                                clientChannel.configureBlocking(false)
                                                clientChannel.setOption(
                                                    StandardSocketOptions.TCP_NODELAY,
                                                    java.lang.Boolean.TRUE
                                                )
                                                val connLoop = group.next()
                                                val conn     = new NioConnection(clientChannel, connLoop)
                                                val engine   = sslCtx.createSSLEngine()
                                                engine.setUseClientMode(false)
                                                val bridge    = new NioStreamTlsBridge(conn)
                                                val tlsStream = new NioTlsStream(bridge, engine)
                                                conn.tlsStream = Present(tlsStream)
                                                (conn, ())
                                            }
                                    }
                                }
                            new TransportListener(
                                actualPort,
                                actualHost,
                                connStream,
                                close = Sync.defer { acceptSelector.close(); serverChannel.close() }
                            )
                        } { _ =>
                            Sync.defer {
                                acceptSelector.close()
                                serverChannel.close()
                            }
                        }
                end match
            }
        }

    /** Block on acceptSelector until a connection is ready. Returns false when selector is closed. */
    private def pollAcceptSelector(acceptSelector: Selector)(using Frame): Boolean < Async =
        Sync.defer {
            try
                acceptSelector.select()
                acceptSelector.selectedKeys().clear()
                true
            catch
                case _: java.nio.channels.ClosedSelectorException => false
        }

    private object TrustAllManager extends javax.net.ssl.X509TrustManager:
        def checkClientTrusted(chain: Array[java.security.cert.X509Certificate], authType: String): Unit = ()
        def checkServerTrusted(chain: Array[java.security.cert.X509Certificate], authType: String): Unit = ()
        def getAcceptedIssuers: Array[java.security.cert.X509Certificate]                                = Array.empty
    end TrustAllManager

    private def createServerSslContext(config: TlsConfig): SSLContext =
        import java.security.*
        import java.io.*
        val ctx = SSLContext.getInstance("TLS")
        (config.certChainPath, config.privateKeyPath) match
            case (Present(certPath), Present(keyPath)) =>
                val ks  = KeyStore.getInstance("PKCS12")
                val fis = new FileInputStream(certPath)
                try ks.load(fis, Array.empty)
                finally fis.close()
                val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
                kmf.init(ks, Array.empty)
                ctx.init(kmf.getKeyManagers, null, null)
            case _ =>
                ctx.init(null, null, null)
        end match
        ctx
    end createServerSslContext

end NioTransport

/** NIO-backed connection that implements TransportStream (stream-based reads).
  *
  * Uses the shared NioIoLoop for all read/write readiness. No per-connection Selector.
  */
private[kyo] class NioConnection(
    val channel: SocketChannel,
    private[internal] val loop: NioIoLoop,
    var tlsStream: Maybe[NioTlsStream] = Absent
) extends TransportStream:

    private val ChunkSize = 8192

    def read(using Frame): Stream[Span[Byte], Async] =
        Stream.unfold((), chunkSize = 1) { _ =>
            tlsStream match
                case Present(tls) => readTls(tls)
                case Absent       => readPlain()
        }

    private def readPlain()(using Frame): Maybe[(Span[Byte], Unit)] < Async =
        loop.awaitChannelReadable(channel).andThen {
            Sync.defer {
                val buf = ByteBuffer.allocate(ChunkSize)
                val n   = channel.read(buf)
                if n > 0 then
                    val arr = new Array[Byte](n)
                    buf.flip()
                    buf.get(arr)
                    Maybe((Span.fromUnsafe(arr), ()))
                else if n < 0 then
                    Maybe.empty // real EOF
                else
                    Maybe.empty // n == 0: spurious — treat as EOF (caller may retry via Stream.unfold)
                end if
            }
        }

    private def readTls(tls: NioTlsStream)(using Frame): Maybe[(Span[Byte], Unit)] < Async =
        val buf = new Array[Byte](ChunkSize)
        tls.read(buf).map { n =>
            if n > 0 then
                val arr = new Array[Byte](n)
                java.lang.System.arraycopy(buf, 0, arr, 0, n)
                Maybe((Span.fromUnsafe(arr), ()))
            else
                Maybe.empty
            end if
        }
    end readTls

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else
            tlsStream match
                case Present(tls) => tls.write(data)
                case Absent       => writePlain(data)

    private[kyo] def writePlain(data: Span[Byte])(using Frame): Unit < Async =
        val bb = ByteBuffer.wrap(data.toArrayUnsafe)
        writeLoop(bb)

    private def writeLoop(bb: ByteBuffer)(using Frame): Unit < Async =
        loop.awaitChannelWritable(channel).andThen {
            Sync.defer {
                channel.write(bb)
                if bb.hasRemaining then writeLoop(bb)
                else Kyo.unit
                end if
            }
        }

end NioConnection

/** Bridges a NioConnection to the RawStream API, so NioTlsStream can use it for TLS I/O.
  *
  * Delegates to the connection's raw I/O using the shared NioIoLoop.
  */
private[kyo] class NioStreamTlsBridge(conn: NioConnection) extends RawStream:

    def read(buf: Array[Byte])(using Frame): Int < Async =
        conn.loop.awaitChannelReadable(conn.channel).andThen {
            Sync.defer {
                val bb = ByteBuffer.wrap(buf)
                conn.channel.read(bb)
            }
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        conn.writePlain(data)

end NioStreamTlsBridge
