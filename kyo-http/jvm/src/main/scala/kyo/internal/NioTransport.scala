package kyo.internal

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*

/** Non-blocking NIO transport for JVM.
  *
  * Uses a shared Selector polled by a kyo Fiber (not a raw Thread). Each read/write registers interest on the shared Selector, then waits
  * on a per-operation Promise. The poll fiber calls selectNow() in a loop, completing Promises for ready keys.
  *
  * No AllowUnsafe, no throw. The only mutable state is the Selector (JDK requirement) and the pending operation queue.
  */
final class NioTransport extends Transport:

    type Connection = NioConnection

    // Shared selector + pending registrations queue.
    // New interest registrations go through the queue because Selector.register
    // must be called from the same thread that calls select (or the channel must not be selecting).
    // We use selectNow() (non-blocking) so registration is safe from any thread.
    private val selector = Selector.open()

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
                val connected = channel.connect(new InetSocketAddress(host, port))
                if connected then
                    Sync.defer(new NioConnection(channel))
                else
                    val key = syncSelector(channel.register(selector, SelectionKey.OP_CONNECT))
                    pollUntilReady(key).andThen {
                        Sync.defer {
                            discard(syncSelector(key.interestOps(0)))
                            Abort.recover[Exception](_ =>
                                Abort.fail(HttpConnectException(host, port, new Exception("connect failed")))
                            ) {
                                Sync.defer {
                                    channel.finishConnect()
                                    new NioConnection(channel)
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
            syncSelector {
                connection.channel.keyFor(selector) match
                    case null =>
                    case key  => key.cancel()
            }
            connection.channel.close()
        }

    def close(connection: NioConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        closeNow(connection)

    def stream(connection: NioConnection)(using Frame): TransportStream < Async =
        Sync.defer {
            val key = syncSelector {
                connection.channel.keyFor(selector) match
                    case null => connection.channel.register(selector, 0)
                    case k    => k
            }
            new NioStream(connection, key, this)
        }

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        Sync.defer {
            val serverChannel = ServerSocketChannel.open()
            serverChannel.configureBlocking(false)
            serverChannel.bind(new InetSocketAddress(host, port), backlog)
            val actualPort = serverChannel.socket().getLocalPort
            val actualHost = host
            val acceptKey  = syncSelector(serverChannel.register(selector, SelectionKey.OP_ACCEPT))

            Scope.acquireRelease {
                Fiber.init {
                    Loop.foreach {
                        pollUntilReady(acceptKey).andThen {
                            Sync.defer {
                                val clientChannel = serverChannel.accept()
                                if clientChannel != null then
                                    clientChannel.configureBlocking(false)
                                    clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
                                    val conn = new NioConnection(clientChannel)
                                    Fiber.init {
                                        Sync.ensure(closeNow(conn)) {
                                            stream(conn).map(handler)
                                        }
                                    }.unit
                                else
                                    Kyo.unit
                                end if
                            }
                        }.andThen {
                            discard(syncSelector(acceptKey.interestOps(SelectionKey.OP_ACCEPT)))
                            Loop.continue
                        }
                    }
                }.andThen {
                    new TransportListener:
                        val port = actualPort
                        val host = actualHost
                }
            } { _ =>
                Sync.defer {
                    syncSelector(acceptKey.cancel())
                    serverChannel.close()
                }
            }
        }

    /** Synchronized Selector access — NIO Selectors are not thread-safe. */
    private[kyo] def syncSelector[A](f: => A): A = selector.synchronized(f)

    /** Poll the shared selector until the given key is ready. Non-blocking, yields between polls. */
    private[kyo] def pollUntilReady(key: SelectionKey)(using Frame): Unit < Async =
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                Sync.defer {
                    syncSelector {
                        selector.selectNow()
                        if key.isValid && (key.readyOps() & key.interestOps()) != 0 then
                            selector.selectedKeys().remove(key)
                            Loop.done(())
                        else
                            Loop.continue
                        end if
                    }
                }
            }
        }

end NioTransport

private[kyo] class NioConnection(val channel: SocketChannel)

private[kyo] class NioStream(
    conn: NioConnection,
    key: SelectionKey,
    transport: NioTransport
) extends TransportStream:

    private def setInterest(ops: Int)(using Frame): Unit < Sync =
        Sync.defer(discard(transport.syncSelector(key.interestOps(ops))))

    private def addInterest(op: Int)(using Frame): Unit < Sync =
        Sync.defer(discard(transport.syncSelector(key.interestOps(key.interestOps() | op))))

    private def removeInterest(op: Int)(using Frame): Unit < Sync =
        Sync.defer(discard(transport.syncSelector(key.interestOps(key.interestOps() & ~op))))

    def read(buf: Array[Byte])(using Frame): Int < Async =
        addInterest(SelectionKey.OP_READ).andThen {
            transport.pollUntilReady(key).andThen {
                removeInterest(SelectionKey.OP_READ).andThen {
                    Sync.defer {
                        val bb = ByteBuffer.wrap(buf)
                        conn.channel.read(bb)
                    }
                }
            }
        }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else
            val bb = ByteBuffer.wrap(data.toArrayUnsafe)
            writeLoop(bb)

    private def writeLoop(bb: ByteBuffer)(using Frame): Unit < Async =
        addInterest(SelectionKey.OP_WRITE).andThen {
            transport.pollUntilReady(key).andThen {
                Sync.defer {
                    conn.channel.write(bb)
                    if bb.hasRemaining then
                        writeLoop(bb)
                    else
                        removeInterest(SelectionKey.OP_WRITE)
                    end if
                }
            }
        }

end NioStream
