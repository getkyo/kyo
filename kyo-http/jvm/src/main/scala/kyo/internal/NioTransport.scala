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

/** Non-blocking NIO transport for JVM.
  *
  * Each connection gets its own Selector — avoids all thread-safety issues. read()/write() register interest, poll with selectNow(), yield
  * via Async.sleep. No shared mutable state between connections. No AllowUnsafe, no throw.
  */
final class NioTransport(
    clientSslContext: Maybe[SSLContext] = Absent,
    serverSslContext: Maybe[SSLContext] = Absent
) extends Transport:

    type Connection = NioConnection

    def connect(host: String, port: Int, tls: Boolean)(using
        Frame
    )
        : NioConnection < (Async & Abort[HttpException]) =
        if tls then connectTls(host, port)
        else connectPlain(host, port)

    private def connectTls(host: String, port: Int)(using Frame): NioConnection < (Async & Abort[HttpException]) =
        connectPlain(host, port).map { conn =>
            Sync.defer {
                val ctx = clientSslContext match
                    case Present(c) => c
                    case Absent     => SSLContext.getDefault
                val engine = ctx.createSSLEngine(host, port)
                engine.setUseClientMode(true)
                val params = engine.getSSLParameters
                params.setServerNames(java.util.List.of(new SNIHostName(host)))
                params.setApplicationProtocols(Array("http/1.1"))
                params.setEndpointIdentificationAlgorithm("HTTPS")
                engine.setSSLParameters(params)
                val nioStream = new NioStream(conn)
                val tlsStream = new NioTlsStream(nioStream, engine)
                conn.tlsStream = Present(tlsStream)
                tlsStream.handshake().map(_ => conn)
            }
        }

    private def connectPlain(host: String, port: Int)(using Frame): NioConnection < (Async & Abort[HttpException]) =
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

    def isAlive(connection: NioConnection)(using Frame): Boolean < Sync =
        Sync.defer(connection.channel.isOpen && connection.channel.isConnected)

    def closeNow(connection: NioConnection)(using Frame): Unit < Sync =
        Sync.defer {
            connection.selector.close()
            connection.channel.close()
        }

    def close(connection: NioConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        closeNow(connection)

    def stream(connection: NioConnection)(using Frame): TransportStream < Async =
        connection.tlsStream match
            case Present(tls) => Sync.defer(tls)
            case Absent       => Sync.defer(new NioStream(connection))

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
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

            Scope.acquireRelease {
                Fiber.init {
                    Loop.foreach {
                        pollSelector(acceptSelector).andThen {
                            Sync.defer {
                                val clientChannel = serverChannel.accept()
                                if clientChannel != null then
                                    clientChannel.configureBlocking(false)
                                    clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
                                    val conn = new NioConnection(clientChannel, Selector.open())
                                    Fiber.init {
                                        Sync.ensure(closeNow(conn)) {
                                            stream(conn).map(handler)
                                        }
                                    }.unit
                                else
                                    Kyo.unit
                                end if
                            }
                        }.andThen(Loop.continue)
                    }
                }.andThen {
                    new TransportListener:
                        val port = actualPort
                        val host = actualHost
                }
            } { _ =>
                Sync.defer {
                    acceptSelector.close()
                    serverChannel.close()
                }
            }
        }

    override def listenTls(host: String, port: Int, backlog: Int, tlsConfig: TlsConfig)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        Sync.defer {
            val sslCtx = serverSslContext match
                case Present(c) => c
                case Absent     => createServerSslContext(tlsConfig)
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

            Scope.acquireRelease {
                Fiber.init {
                    Loop.foreach {
                        pollSelector(acceptSelector).andThen {
                            Sync.defer {
                                val clientChannel = serverChannel.accept()
                                if clientChannel != null then
                                    clientChannel.configureBlocking(false)
                                    clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
                                    val conn = new NioConnection(clientChannel, Selector.open())
                                    Fiber.init {
                                        Sync.ensure(closeNow(conn)) {
                                            // Wrap in TLS
                                            val engine = sslCtx.createSSLEngine()
                                            engine.setUseClientMode(false)
                                            val nioStream = new NioStream(conn)
                                            val tlsStream = new NioTlsStream(nioStream, engine)
                                            tlsStream.handshake().andThen {
                                                handler(tlsStream)
                                            }
                                        }
                                    }.unit
                                else
                                    Kyo.unit
                                end if
                            }
                        }.andThen(Loop.continue)
                    }
                }.andThen {
                    new TransportListener:
                        val port = actualPort
                        val host = actualHost
                }
            } { _ =>
                Sync.defer {
                    acceptSelector.close()
                    serverChannel.close()
                }
            }
        }

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
                // Self-signed for development — generate ephemeral cert
                ctx.init(null, null, null)
        end match
        ctx
    end createServerSslContext

    /** Poll selector with selectNow() until at least one key is ready. */
    private def pollSelector(selector: Selector)(using Frame): Unit < Async =
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                Sync.defer {
                    val ready = selector.selectNow()
                    if ready > 0 then
                        selector.selectedKeys().clear()
                        Loop.done(())
                    else
                        Loop.continue
                    end if
                }
            }
        }

end NioTransport

private[kyo] class NioConnection(val channel: SocketChannel, val selector: Selector, var tlsStream: Maybe[NioTlsStream] = Absent)

/** NIO stream using the connection's selector with synchronized interestOps.
  *
  * Read and write may be called concurrently (e.g., WebSocket read/write loops). The selector and key are shared but interestOps
  * modifications are synchronized to prevent race conditions.
  */
private[kyo] class NioStream(conn: NioConnection) extends TransportStream:

    private val key  = conn.channel.register(conn.selector, 0)
    private val lock = new AnyRef // guards interestOps modifications

    private def pollFor(op: Int)(using Frame): Unit < Async =
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                Sync.defer {
                    lock.synchronized {
                        conn.selector.selectNow()
                        if key.isValid && (key.readyOps() & op) != 0 then
                            conn.selector.selectedKeys().remove(key)
                            true
                        else false
                        end if
                    }
                }.map { ready =>
                    if ready then Loop.done(())
                    else Loop.continue
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
        if data.isEmpty then Kyo.unit
        else writeLoop(ByteBuffer.wrap(data.toArrayUnsafe))

    private def writeLoop(bb: ByteBuffer)(using Frame): Unit < Async =
        Sync.defer(lock.synchronized(discard(key.interestOps(key.interestOps() | SelectionKey.OP_WRITE)))).andThen {
            pollFor(SelectionKey.OP_WRITE).andThen {
                Sync.defer {
                    conn.channel.write(bb)
                    if bb.hasRemaining then
                        writeLoop(bb)
                    else
                        lock.synchronized(discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE)))
                        Kyo.unit
                    end if
                }
            }
        }

end NioStream
