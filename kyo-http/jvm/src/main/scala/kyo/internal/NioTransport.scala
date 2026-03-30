package kyo.internal

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*

/** Non-blocking NIO transport for JVM.
  *
  * Each connection gets its own Selector — avoids all thread-safety issues. read()/write() register interest, poll with selectNow(), yield
  * via Async.sleep. No shared mutable state between connections. No AllowUnsafe, no throw.
  */
final class NioTransport extends Transport:

    type Connection = NioConnection

    def connect(host: String, port: Int, tls: Boolean)(using
        Frame
    )
        : NioConnection < (Async & Abort[HttpException]) =
        if tls then Abort.fail(HttpConnectException(host, port, new Exception("TLS not yet supported")))
        else
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
        Sync.defer {
            val key = connection.channel.keyFor(connection.selector) match
                case null => connection.channel.register(connection.selector, 0)
                case k    => discard(k.interestOps(0)); k
            new NioStream(connection, key)
        }

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        Sync.defer {
            val serverChannel = ServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            serverChannel.bind(new InetSocketAddress(host, port), backlog)
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

private[kyo] class NioConnection(val channel: SocketChannel, val selector: Selector)

private[kyo] class NioStream(conn: NioConnection, key: SelectionKey) extends TransportStream:

    private def pollKey()(using Frame): Unit < Async =
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                Sync.defer {
                    conn.selector.selectNow()
                    if key.isValid && (key.readyOps() & key.interestOps()) != 0 then
                        conn.selector.selectedKeys().remove(key)
                        Loop.done(())
                    else
                        Loop.continue
                    end if
                }
            }
        }

    def read(buf: Array[Byte])(using Frame): Int < Async =
        Sync.defer(discard(key.interestOps(key.interestOps() | SelectionKey.OP_READ))).andThen {
            pollKey().andThen {
                Sync.defer {
                    discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_READ))
                    val bb = ByteBuffer.wrap(buf)
                    val n  = conn.channel.read(bb)
                    if n <= 0 then
                        java.lang.System.err.println(s"[DEBUG-NIO-READ] n=$n, ch=${conn.channel.isOpen}/${conn.channel.isConnected}")
                    n
                }
            }
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else writeLoop(ByteBuffer.wrap(data.toArrayUnsafe))

    private def writeLoop(bb: ByteBuffer)(using Frame): Unit < Async =
        Sync.defer(discard(key.interestOps(key.interestOps() | SelectionKey.OP_WRITE))).andThen {
            pollKey().andThen {
                Sync.defer {
                    conn.channel.write(bb)
                    if bb.hasRemaining then
                        writeLoop(bb)
                    else
                        discard(key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE))
                        Kyo.unit
                    end if
                }
            }
        }

end NioStream
