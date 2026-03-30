package kyo.internal

import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kyo.*

/** Non-blocking NIO transport for JVM — java.nio.channels.Selector.
  *
  * Same per-fiber polling architecture as KqueueNativeTransport:
  *   - Each stream gets its own Selector to avoid event stealing
  *   - read()/write() register interest, poll with selectNow(), yield via Async.sleep
  *   - No poll thread, no AllowUnsafe, no mutable Java collections
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
                val connected = channel.connect(new InetSocketAddress(host, port))
                if connected then
                    Sync.defer(new NioConnection(channel))
                else
                    val selector = Selector.open()
                    channel.register(selector, SelectionKey.OP_CONNECT)
                    awaitSelector(selector).andThen {
                        Sync.defer {
                            channel.finishConnect()
                            selector.close()
                            new NioConnection(channel)
                        }
                    }
                end if
            }

    def isAlive(connection: NioConnection)(using Frame): Boolean < Sync =
        Sync.defer(connection.channel.isOpen && connection.channel.isConnected)

    def closeNow(connection: NioConnection)(using Frame): Unit < Sync =
        Sync.defer(connection.channel.close())

    def close(connection: NioConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer(connection.channel.close())

    def stream(connection: NioConnection)(using Frame): TransportStream < Async =
        Sync.defer(new NioStream(connection))

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
                        awaitSelector(acceptSelector).andThen {
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

    /** Poll selector with selectNow() until at least one key is ready. Yields between polls. */
    private def awaitSelector(selector: Selector)(using Frame): Unit < Async =
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

private[kyo] class NioConnection(val channel: SocketChannel)

private[kyo] class NioStream(conn: NioConnection) extends TransportStream:

    private val readSelector  = Selector.open()
    private val writeSelector = Selector.open()
    private val readKey       = conn.channel.register(readSelector, 0)
    private val writeKey      = conn.channel.register(writeSelector, 0)

    def read(buf: Array[Byte])(using Frame): Int < Async =
        readKey.interestOps(SelectionKey.OP_READ)
        Loop.foreach {
            Async.sleep(1.millis).andThen {
                Sync.defer {
                    val ready = readSelector.selectNow()
                    if ready > 0 then
                        readSelector.selectedKeys().clear()
                        readKey.interestOps(0)
                        val bb = ByteBuffer.wrap(buf)
                        val n  = conn.channel.read(bb)
                        Loop.done(n)
                    else
                        Loop.continue
                    end if
                }
            }
        }
    end read

    def write(data: Span[Byte])(using Frame): Unit < Async =
        if data.isEmpty then Kyo.unit
        else
            val arr = data.toArrayUnsafe
            val bb  = ByteBuffer.wrap(arr)
            writeKey.interestOps(SelectionKey.OP_WRITE)
            Loop.foreach {
                Async.sleep(1.millis).andThen {
                    Sync.defer {
                        val ready = writeSelector.selectNow()
                        if ready > 0 then
                            writeSelector.selectedKeys().clear()
                            conn.channel.write(bb)
                            if bb.hasRemaining then
                                Loop.continue // partial write, keep going
                            else
                                writeKey.interestOps(0)
                                Loop.done(())
                            end if
                        else
                            Loop.continue
                        end if
                    }
                }
            }

end NioStream
