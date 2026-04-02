package kyo.internal

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.*
import kyo.*

/** Non-blocking NIO Transport for JVM.
  *
  * Stream-first variant of NioTransport. Each connection exposes a pull-based `Stream[Span[Byte], Async]` for reads instead of a
  * callback-based `read(buf): Int`. Server listen returns a `TransportListener` whose `connections` stream yields accepted connections.
  *
  * Design follows NioTransport closely:
  *   - Per-connection Selector to avoid all thread-safety issues.
  *   - Blocking selector.select() (pollFor/pollSelector) — Kyo's preemptive scheduler handles other fibers while blocked.
  *   - TLS via NioTlsStream wrapping a NioStreamTlsBridge (RawStream), so TLS layer is unchanged.
  */
final class NioTransport(
    clientSslContext: Maybe[SSLContext] = Absent,
    serverSslContext: Maybe[SSLContext] = Absent
) extends Transport:

    type Connection = NioConnection

    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): NioConnection < (Async & Abort[HttpException]) =
        tls match
            case Present(tlsCfg) => connectTls(host, port, tlsCfg)
            case Absent          => connectPlain(host, port)

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
            val channel = SocketChannel.open()
            channel.configureBlocking(false)
            channel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
            val selector  = Selector.open()
            val connected = channel.connect(new InetSocketAddress(host, port))
            if connected then
                selector.close()
                Sync.defer(new NioConnection(channel, Selector.open()))
            else
                channel.register(selector, SelectionKey.OP_CONNECT)
                pollSelector(selector).andThen {
                    Sync.defer {
                        Abort.recover[Exception](_ =>
                            Abort.fail(HttpConnectException(host, port, new Exception("connect failed")))
                        ) {
                            Sync.defer {
                                channel.finishConnect()
                                selector.close()
                                new NioConnection(channel, Selector.open())
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
            c.selector.close()
            c.channel.close()
        }

    def close(c: NioConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        closeNow(c)

    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using
        Frame
    ): TransportListener[NioConnection] < (Async & Scope) =
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
            val actualPort     = serverChannel.socket().getLocalPort
            val actualHost     = host
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
                                        val clientChannel = serverChannel.accept()
                                        if clientChannel != null then
                                            clientChannel.configureBlocking(false)
                                            clientChannel.setOption(
                                                StandardSocketOptions.TCP_NODELAY,
                                                java.lang.Boolean.TRUE
                                            )
                                            Maybe((new NioConnection(clientChannel, Selector.open()), ()))
                                        else
                                            Maybe.empty
                                        end if
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
                                        val clientChannel = serverChannel.accept()
                                        if clientChannel != null then
                                            clientChannel.configureBlocking(false)
                                            clientChannel.setOption(
                                                StandardSocketOptions.TCP_NODELAY,
                                                java.lang.Boolean.TRUE
                                            )
                                            val conn   = new NioConnection(clientChannel, Selector.open())
                                            val engine = sslCtx.createSSLEngine()
                                            engine.setUseClientMode(false)
                                            val bridge    = new NioStreamTlsBridge(conn)
                                            val tlsStream = new NioTlsStream(bridge, engine)
                                            conn.tlsStream = Present(tlsStream)
                                            Maybe((conn, ()))
                                        else
                                            Maybe.empty
                                        end if
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

    /** Block on selector until at least one key is ready. Kyo's preemptive scheduler handles other fibers while this thread blocks. */
    private def pollSelector(selector: Selector)(using Frame): Unit < Async =
        Sync.defer {
            selector.select() // blocks until ready — Kyo preempts, doesn't block other fibers
            selector.selectedKeys().clear()
        }

    /** Like pollSelector but returns false when the selector is closed (triggered by listener.close). */
    private def pollAcceptSelector(selector: Selector)(using Frame): Boolean < Async =
        Sync.defer {
            try
                selector.select()
                selector.selectedKeys().clear()
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
  * Uses a per-connection Selector to register OP_READ / OP_WRITE interest ops and block with select(). TLS is stored as a NioTlsStream when
  * the connection is TLS-upgraded.
  */
private[kyo] class NioConnection(
    val channel: SocketChannel,
    val selector: Selector,
    var tlsStream: Maybe[NioTlsStream] = Absent
) extends TransportStream:

    private val key       = channel.register(selector, 0)
    private val lock      = new AnyRef
    private val ChunkSize = 8192

    def read(using Frame): Stream[Span[Byte], Async] =
        Stream.unfold((), chunkSize = 1) { _ =>
            tlsStream match
                case Present(tls) => readTls(tls)
                case Absent       => readPlain()
        }

    private def readPlain()(using Frame): Maybe[(Span[Byte], Unit)] < Async =
        Loop.foreach {
            Sync.defer(lock.synchronized(discard(key.interestOps(key.interestOps() | SelectionKey.OP_READ)))).andThen {
                pollFor(SelectionKey.OP_READ).andThen {
                    Sync.defer {
                        lock.synchronized(discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ)))
                        val buf = ByteBuffer.allocate(ChunkSize)
                        val n   = channel.read(buf)
                        if n > 0 then
                            val arr = new Array[Byte](n)
                            buf.flip()
                            buf.get(arr)
                            Loop.done(Maybe((Span.fromUnsafe(arr), ())))
                        else if n < 0 then
                            Loop.done(Maybe.empty) // real EOF
                        else
                            Loop.continue // n == 0: spurious wakeup, retry
                        end if
                    }
                }
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
        Sync.defer(lock.synchronized(discard(key.interestOps(key.interestOps() | SelectionKey.OP_WRITE)))).andThen {
            pollFor(SelectionKey.OP_WRITE).andThen {
                Sync.defer {
                    channel.write(bb)
                    if bb.hasRemaining then
                        writeLoop(bb)
                    else
                        lock.synchronized(discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE)))
                        Kyo.unit
                    end if
                }
            }
        }

    private def pollFor(op: Int)(using Frame): Unit < Async =
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                Sync.defer {
                    lock.synchronized {
                        selector.selectNow()
                        if key.isValid && (key.readyOps() & op) != 0 then
                            selector.selectedKeys().remove(key)
                            true
                        else
                            false
                        end if
                    }
                }.map { ready =>
                    if ready then Loop.done(()) else Loop.continue
                }
            }
        }

end NioConnection

/** Bridges a NioConnection to the RawStream API, so NioTlsStream can use it for TLS I/O.
  *
  * NioTlsStream requires a RawStream with `read(buf): Int < Async`. This bridge delegates to the NIO-backed raw I/O of a NioConnection
  * without going through the TLS layer (avoids recursion).
  */
private[kyo] class NioStreamTlsBridge(conn: NioConnection) extends RawStream:

    private val key  = conn.channel.register(conn.selector, 0)
    private val lock = new AnyRef

    private def pollFor(op: Int)(using Frame): Unit < Async =
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                Sync.defer {
                    lock.synchronized {
                        conn.selector.selectNow()
                        if key.isValid && (key.readyOps() & op) != 0 then
                            conn.selector.selectedKeys().remove(key)
                            true
                        else
                            false
                        end if
                    }
                }.map { ready =>
                    if ready then Loop.done(()) else Loop.continue
                }
            }
        }

    def read(buf: Array[Byte])(using Frame): Int < Async =
        Sync.defer(lock.synchronized(discard(key.interestOps(key.interestOps() | SelectionKey.OP_READ)))).andThen {
            pollFor(SelectionKey.OP_READ).andThen {
                Sync.defer {
                    lock.synchronized(discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ)))
                    val bb = ByteBuffer.wrap(buf)
                    conn.channel.read(bb)
                }
            }
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        conn.writePlain(data)

end NioStreamTlsBridge
