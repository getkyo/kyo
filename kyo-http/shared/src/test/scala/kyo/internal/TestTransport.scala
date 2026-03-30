package kyo.internal

import kyo.*

/** In-memory Transport for testing. No real TCP — connects client and server via paired channels. All safe APIs, no AllowUnsafe. */
class TestTransport extends Transport:

    type Connection = TestConnection

    // Server accept channel — created by listen(), used by connect() to deliver new connections.
    // Wrapped in Maybe because listen() hasn't been called yet at construction time.
    private var acceptCh: Maybe[Channel[TestConnection]] = Absent

    def connect(host: String, port: Int, tls: Boolean)(using Frame): TestConnection < (Async & Abort[HttpException]) =
        if tls then Abort.fail(HttpConnectException(host, port, new Exception("TLS not supported in test")))
        else
            acceptCh match
                case Absent =>
                    Abort.fail(HttpConnectException(host, port, new Exception("No server listening")))
                case Present(ch) =>
                    // Channels represent TCP kernel buffers — they must outlive any scope.
                    // This is test infrastructure, not user code, so Unsafe is justified here.
                    Sync.Unsafe.defer {
                        val clientToServer = Channel.Unsafe.init[Span[Byte]](64)
                        val serverToClient = Channel.Unsafe.init[Span[Byte]](64)
                        val clientStream   = new ChannelStream(serverToClient.safe, clientToServer.safe)
                        val serverStream   = new ChannelStream(clientToServer.safe, serverToClient.safe)
                        val serverConn     = new TestConnection(serverStream)
                        val clientConn     = new TestConnection(clientStream)
                        Abort.run[Closed](ch.put(serverConn)).map {
                            case Result.Success(_) => clientConn
                            case _                 => Abort.fail(HttpConnectException(host, port, new Exception("Server closed")))
                        }
                    }

    def isAlive(connection: TestConnection)(using AllowUnsafe): Boolean =
        !connection.closed

    def closeNowUnsafe(connection: TestConnection)(using AllowUnsafe): Unit =
        connection.closed = true

    def close(connection: TestConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer { connection.closed = true }

    def stream(connection: TestConnection)(using Frame): TransportStream < Async =
        Sync.defer(connection.stream)

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        Channel.init[TestConnection](backlog).map { ch =>
            acceptCh = Present(ch)
            Scope.acquireRelease {
                discard(Fiber.init {
                    Loop.foreach {
                        ch.take.map { serverConn =>
                            discard(Fiber.init {
                                Sync.ensure(Sync.defer { serverConn.closed = true }) {
                                    handler(serverConn.stream)
                                }
                            })
                        }.andThen(Loop.continue)
                    }.handle(Abort.run[Closed]).unit
                })
                new TransportListener:
                    val port = if port == 0 then 12345 else port
                    val host = host
            } { _ =>
                ch.close.unit
            }
        }

end TestTransport

class TestConnection(val stream: ChannelStream, var closed: Boolean = false)

/** Bidirectional stream backed by two channels. */
class ChannelStream(
    readCh: Channel[Span[Byte]],
    writeCh: Channel[Span[Byte]]
) extends TransportStream:

    private var readBuf: Span[Byte] = Span.empty[Byte]
    private var readPos: Int        = 0

    def read(buf: Array[Byte])(using Frame): Int < Async =
        if readPos < readBuf.size then
            Sync.defer {
                val available = math.min(buf.length, readBuf.size - readPos)
                discard(readBuf.slice(readPos, readPos + available).copyToArray(buf))
                readPos += available
                if readPos >= readBuf.size then
                    readBuf = Span.empty[Byte]
                    readPos = 0
                available
            }
        else
            Abort.run[Closed](readCh.take).map {
                case Result.Success(data) =>
                    if data.isEmpty then -1
                    else
                        val available = math.min(buf.length, data.size)
                        discard(data.slice(0, available).copyToArray(buf))
                        if available < data.size then
                            readBuf = data
                            readPos = available
                        available
                case Result.Failure(_: Closed) =>
                    -1 // Channel closed = TCP EOF
                case Result.Panic(ex) =>
                    throw ex
            }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        Abort.run[Closed](writeCh.put(data)).map {
            case Result.Failure(_: Closed) =>
                throw new java.io.IOException("Write to closed connection")
            case Result.Panic(ex) =>
                throw ex
            case _ => ()
        }

end ChannelStream
