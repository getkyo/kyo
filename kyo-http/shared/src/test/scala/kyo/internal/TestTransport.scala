package kyo.internal

import kyo.*

/** In-memory Transport for testing. Pure — zero AllowUnsafe, zero throw.
  *
  * Architecture: connect() puts a request into a pending channel. The accept loop (in listen scope) creates the channel pairs (in listen's
  * scope), delivers the server stream to the handler, and delivers the client connection back via a response channel.
  */
class TestTransport extends Transport:

    type Connection = TestConnection

    // Request channel: connect() puts a Promise here, accept loop completes it
    private var requestCh: Maybe[Channel[Promise[TestConnection, Any]]] = Absent

    def connect(host: String, port: Int, tls: Boolean)(using Frame): TestConnection < (Async & Abort[HttpException]) =
        if tls then Abort.fail(HttpConnectException(host, port, new Exception("TLS not supported in test")))
        else
            requestCh match
                case Absent =>
                    Abort.fail(HttpConnectException(host, port, new Exception("No server listening")))
                case Present(reqCh) =>
                    // Create a Promise to receive the client connection (no Scope needed)
                    Promise.init[TestConnection, Any].map { promise =>
                        Abort.recover[Closed](_ =>
                            Abort.fail(HttpConnectException(host, port, new Exception("Server closed")))
                        )(reqCh.put(promise)).andThen {
                            promise.get
                        }
                    }

    def isAlive(connection: TestConnection)(using Frame): Boolean < Sync =
        Sync.defer(!connection.closed)

    def closeNow(connection: TestConnection)(using Frame): Unit < Sync =
        Sync.defer { connection.closed = true }

    def close(connection: TestConnection, gracePeriod: Duration)(using Frame): Unit < Async =
        Sync.defer { connection.closed = true }

    def stream(connection: TestConnection)(using Frame): TransportStream < Async =
        Sync.defer(connection.stream)

    def listen(host: String, port: Int, backlog: Int)(
        handler: TransportStream => Unit < Async
    )(using Frame): TransportListener < (Async & Scope) =
        Channel.init[Promise[TestConnection, Any]](backlog).map { reqCh =>
            requestCh = Present(reqCh)
            Scope.acquireRelease {
                Fiber.init {
                    Loop.foreach {
                        reqCh.take.map { promise =>
                            Channel.init[Span[Byte]](64).map { clientToServer =>
                                Channel.init[Span[Byte]](64).map { serverToClient =>
                                    val clientStream = new ChannelStream(serverToClient, clientToServer)
                                    val serverStream = new ChannelStream(clientToServer, serverToClient)
                                    val clientConn   = new TestConnection(clientStream)
                                    promise.completeDiscard(Result.succeed(clientConn)).andThen {
                                        Fiber.init {
                                            handler(serverStream)
                                        }.unit
                                    }
                                }
                            }
                        }.andThen(Loop.continue)
                    }.handle(Abort.run[Closed]).unit
                }.andThen {
                    new TransportListener:
                        val port = if port == 0 then 12345 else port
                        val host = host
                }
            } { _ =>
                reqCh.close.unit
            }
        }

end TestTransport

class TestConnection(val stream: ChannelStream, var closed: Boolean = false)

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
            Abort.recover[Closed](_ => -1) {
                readCh.take.map { data =>
                    if data.isEmpty then -1
                    else
                        val available = math.min(buf.length, data.size)
                        discard(data.slice(0, available).copyToArray(buf))
                        if available < data.size then
                            readBuf = data
                            readPos = available
                        available
                }
            }

    def write(data: Span[Byte])(using Frame): Unit < Async =
        // Match real transport behavior: writing to a closed connection
        // fails with an unrecoverable error (like IOException: Broken pipe on NIO).
        // Abort.run converts Closed to a Result, then we re-raise as Panic to match
        // the contract that TransportStream.write returns Unit < Async.
        Abort.run[Closed](writeCh.put(data)).map {
            case Result.Success(_) => Kyo.unit
            case Result.Error(e)   => Abort.panic(new java.io.IOException("Broken pipe (test transport)"))
        }

end ChannelStream
