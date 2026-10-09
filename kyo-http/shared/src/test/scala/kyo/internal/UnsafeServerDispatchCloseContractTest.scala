package kyo.internal

import kyo.*
import kyo.net.Connection
import kyo.net.NetConfig
import kyo.net.NetPlatform
import kyo.net.ObservedConnection
import kyo.net.ObservedServerTransport
import kyo.net.internal.transport.WriteState

class UnsafeServerDispatchCloseContractTest extends kyo.BaseHttpTest:

    import AllowUnsafe.embrace.danger

    private val loopback = HttpServerConfig.default.port(0).host("127.0.0.1").idleTimeout(Duration.Infinity)

    // Bounds only the failing state of a leaf whose violation is a hang: a passing leaf never waits on it.
    private val hangBound = 20.seconds

    private def observedServer(config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(using
        Frame
    ): (HttpServer, ObservedServerTransport) < (Async & Scope & Abort[Any]) =
        Sync.Unsafe.defer {
            val transport = new ObservedServerTransport(NetPlatform.transport)
            Abort.get(HttpServer.Unsafe.init(transport, config, handlers, Clock.live)).map { started =>
                started.safe.get.map(server => Scope.ensure(server.safe.closeNow).andThen((server.safe, transport)))
            }
        }

    private def connectPeer(port: Int, config: NetConfig = NetConfig.default)(using Frame): Connection < (Async & Scope & Abort[Any]) =
        Sync.Unsafe.defer(NetPlatform.transport.connect("127.0.0.1", port, config = config).safe.get)
            .map(conn => Scope.ensure(Sync.Unsafe.defer(conn.close())).andThen(conn))

    private def send(conn: Connection, bytes: Array[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
        conn.outbound.safe.put(Span.fromUnsafe(bytes))

    private def send(conn: Connection, text: String)(using Frame): Unit < (Async & Abort[Closed]) =
        send(conn, text.getBytes("ISO-8859-1"))

    private def text(spans: Chunk[Span[Byte]]): String =
        spans.map(span => new String(span.toArrayUnsafe, "ISO-8859-1")).mkString

    /** Every span the server sends until its EOF. */
    private def readToEof(conn: Connection)(using Frame): Chunk[Span[Byte]] < Async =
        Loop(Chunk.empty[Span[Byte]]) { acc =>
            Abort.run[Closed](conn.inbound.safe.take).map {
                case Result.Success(span) => Loop.continue(acc.append(span))
                case _                    => Loop.done(acc)
            }
        }

    /** Reads until what arrived satisfies `done` or the server's EOF, whichever comes first. */
    private def readUntil(conn: Connection)(done: String => Boolean)(using Frame): String < Async =
        Loop("") { acc =>
            if done(acc) then Loop.done(acc)
            else
                Abort.run[Closed](conn.inbound.safe.take).map {
                    case Result.Success(span) => Loop.continue(acc + new String(span.toArrayUnsafe, "ISO-8859-1"))
                    case _                    => Loop.done(acc)
                }
        }

    /** Reads to the server's EOF, `Absent` when it does not come within [[hangBound]]. */
    private def eofWithinBound(conn: Connection)(using Frame): Maybe[String] < Async =
        Abort.run[Timeout](Async.timeout(hangBound)(readToEof(conn))).map {
            case Result.Success(spans) => Present(text(spans))
            case _                     => Absent
        }

    "Connection: close" - {

        // The peer never reads until the end, so once the server's write pump is parked it stays parked. The last request is sent only
        // when the pump is parked and the one-slot outbound channel is full with no put waiting, so every write of the last response waits
        // for room. A driver that takes a whole span into its own tail (io_uring) parks the pump one span later than one that parks inside
        // the span (poller, NIO), so bodyless HEAD answers fill the slot until that state holds. The peer starts reading only once the
        // server closed, or once the server, given thousands of turns, has not closed while that write waits.
        "a response whose last write waits for room in the outbound channel reaches the peer in full".pendingUntilFixed(
            "H1: closing after a Connection: close response fails its write still parked on the outbound channel, cutting the response"
        ) in {
            val fillSize = 16 * 1024 * 1024
            val lastSize = 64 * 1024
            Latch.init(1).map { lastStarted =>
                val fill  = HttpHandler.getBinary("fill")(_ => Span.fromUnsafe(Array.fill(fillSize)('a'.toByte)))
                val probe = HttpHandler.getText("probe")(_ => "")
                val last  =
                    HttpHandler.getBinary("last")(_ => lastStarted.release.andThen(Span.fromUnsafe(Array.fill(lastSize)('b'.toByte))))
                val config = loopback.transportConfig(HttpTransportConfig.default.channelCapacity(1))
                observedServer(config)(fill, probe, last).map { (server, transport) =>
                    connectPeer(server.port, NetConfig(channelCapacity = 1, soRcvBuf = Present(16.kb))).map { peer =>
                        val headProbe                                        = "HEAD /probe HTTP/1.1\r\nHost: h\r\n\r\n"
                        def puts(served: ObservedConnection): Int            = served.outbound.pendingPuts().getOrElse(0)
                        def queued(served: ObservedConnection): Int          = served.outbound.size().getOrElse(0)
                        def parkedOn(served: ObservedConnection): Maybe[Int] = served.writeState match
                            case Present(WriteState.AwaitingWritable(span, _)) => Present(span.size)
                            case Present(WriteState.Backpressured(span, _))    => Present(span.size)
                            case _                                             => Absent
                        def slotFull(served: ObservedConnection): Boolean =
                            parkedOn(served).nonEmpty && queued(served) == 1 && puts(served) == 0
                        def pumpTookProbe(served: ObservedConnection): Boolean =
                            parkedOn(served).exists(_ < fillSize) && queued(served) == 0 && puts(served) == 0
                        send(peer, "GET /fill HTTP/1.1\r\nHost: h\r\n\r\n" + headProbe)
                            .andThen(pollUntil(transport.accepted.nonEmpty))
                            .andThen {
                                val served = transport.accepted.head
                                pollUntil(slotFull(served) || pumpTookProbe(served)).map { _ =>
                                    if slotFull(served) then Kyo.unit
                                    else send(peer, headProbe).andThen(pollUntil(slotFull(served))).unit
                                }.andThen {
                                    assert(
                                        slotFull(served),
                                        s"setup: the pump never parked behind a full outbound channel: ${served.writeState}"
                                    )
                                    send(peer, "GET /last HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n")
                                }.andThen(lastStarted.await).andThen {
                                    def lastWriteWaits: Boolean = puts(served) > 0
                                    pollUntil(served.wasClosed || lastWriteWaits).andThen(pollUntil(served.wasClosed, maxPolls = 10000))
                                }
                            }
                            .andThen(readToEof(peer))
                            .map { spans =>
                                val received = spans.foldLeft(0L)(_ + _.size)
                                val tail     = spans.reverse.scanLeft(0L)(_ + _.size).indexWhere(_ > lastSize + 1024)
                                val all      = text(if tail < 0 then spans else spans.takeRight(tail))
                                val lastHead = all.lastIndexOf("HTTP/1.1 200 OK")
                                val lastBody = if lastHead < 0 then "" else all.substring(all.indexOf("\r\n\r\n", lastHead) + 4)
                                assert(
                                    lastBody == "b" * lastSize,
                                    s"received $received bytes in all; the last response's body arrived with ${lastBody.length} of $lastSize bytes"
                                )
                            }
                    }
                }
            }
        }
    }

    "lingering close" - {

        val refusing = loopback.lingeringTimeout(Duration.Infinity).transportConfig(HttpTransportConfig.default.maxHeaderSize(256.bytes))
        val hello    = HttpHandler.getText("hello")(_ => "world")
        val upload   = HttpHandler.postText("upload")((_, body) => body.length.toString)

        // The server reads and discards what the peer still sends after its answer before it closes (RFC 9112 section 9.6). A peer that
        // reads until the server's EOF before it closes its own side learns the answer is complete only from a FIN sent ahead of that
        // drain; with no lingering bound, nothing else ends the exchange.
        "a peer that reads to EOF after a refused head gets the answer and the server's EOF while the server drains".pendingUntilFixed(
            "H2: the lingering drain runs before any FIN, so a peer that reads to EOF waits out lingeringTimeout, forever with Infinity"
        ) in {
            HttpServer.init(refusing)(hello).map { server =>
                connectPeer(server.port).map { peer =>
                    send(peer, "GET /hello HTTP/1.1\r\nHost: h\r\nX-Big: " + "x" * 300 + "\r\n\r\n")
                        .andThen(eofWithinBound(peer))
                        .map { answer =>
                            assert(answer.nonEmpty, s"no EOF from the server within $hangBound after its answer")
                            assert(answer.exists(_.startsWith("HTTP/1.1 431 ")), s"observed: $answer")
                        }
                }
            }
        }

        "a peer that reads to EOF after a refused declared body gets the 413 and the server's EOF while the server drains".pendingUntilFixed(
            "H2: the lingering drain runs before any FIN, so a peer that reads to EOF waits out lingeringTimeout, forever with Infinity"
        ) in {
            HttpServer.init(refusing.maxContentLength(1024.bytes))(upload).map { server =>
                connectPeer(server.port).map { peer =>
                    send(peer, "POST /upload HTTP/1.1\r\nHost: h\r\nContent-Length: 100000\r\n\r\n")
                        .andThen(eofWithinBound(peer))
                        .map { answer =>
                            assert(answer.nonEmpty, s"no EOF from the server within $hangBound after its answer")
                            assert(answer.exists(_.startsWith("HTTP/1.1 413 ")), s"observed: $answer")
                        }
                }
            }
        }
    }

    "WebSocket session end" - {

        def upgrade(path: String): String =
            s"GET /$path HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"

        // Close with status 1000 and no reason: unmasked from the server, masked (RFC 6455 section 5.3) from the peer.
        val serverClose = new String(Array[Byte](0x88.toByte, 0x02, 0x03, 0xe8.toByte), "ISO-8859-1")
        val peerClose   = Array[Byte](0x88.toByte, 0x82.toByte, 0x01, 0x02, 0x03, 0x04, (0x03 ^ 0x01).toByte, (0xe8 ^ 0x02).toByte)

        def afterHead(bytes: String): String =
            val end = bytes.indexOf("\r\n\r\n")
            if end < 0 then "" else bytes.substring(end + 4)

        // Once both Close frames are exchanged the server closes the TCP connection first (RFC 6455 section 7.1.1).
        "the server closes the TCP connection once a session its handler ended has exchanged Close frames".pendingUntilFixed(
            "H3: after a WebSocket session the server never closes the TCP connection"
        ) in {
            observedServer(loopback)(HttpHandler.webSocket("ws/done")((_, _) => Kyo.unit)).map { (server, _) =>
                connectPeer(server.port).map { peer =>
                    send(peer, upgrade("ws/done"))
                        .andThen(Abort.run[Timeout](Async.timeout(hangBound)(readUntil(peer)(afterHead(_).contains(serverClose)))))
                        .map { opening =>
                            assert(
                                opening.exists(o => o.startsWith("HTTP/1.1 101") && afterHead(o).contains(serverClose)),
                                s"the server's Close frame never arrived: $opening"
                            )
                            send(peer, peerClose).andThen(eofWithinBound(peer)).map { rest =>
                                assert(rest.nonEmpty, s"the server kept the TCP connection open for $hangBound after both Close frames")
                            }
                        }
                }
            }
        }

        "the server closes the TCP connection once a session the peer ended has exchanged Close frames".pendingUntilFixed(
            "H3: after a WebSocket session the server never closes the TCP connection"
        ) in {
            observedServer(loopback)(HttpHandler.webSocket("ws/wait")((_, ws) => ws.onPeerClose)).map { (server, _) =>
                connectPeer(server.port).map { peer =>
                    send(peer, upgrade("ws/wait"))
                        .andThen(readUntil(peer)(_.contains("\r\n\r\n")))
                        .map { head =>
                            assert(head.startsWith("HTTP/1.1 101"), s"upgrade response: $head")
                            send(peer, peerClose).andThen(eofWithinBound(peer)).map { rest =>
                                assert(
                                    rest.nonEmpty,
                                    s"the server kept the TCP connection open for $hangBound after the peer's Close frame"
                                )
                                assert(rest.exists(_.contains(serverClose)), s"the server never answered the Close frame: $rest")
                            }
                        }
                }
            }
        }
    }

    "pipelined requests behind the last" - {

        // The peer pipelines requests behind one that asks the server to close. The server reads none of them, so they are still
        // unread in its socket when it closes, and a close with unread bytes resets the connection.
        // Whether the reset lands before the peer has read the whole body depends on scheduling, so the exchange is repeated until a round
        // loses bytes or every round delivered the body in full.
        "the response to a Connection: close request reaches a peer that pipelined more requests behind it".pendingUntilFixed(
            "H4: closeConnectionNow closes with the pipelined requests unread in the kernel buffer, so the close resets the connection and cuts the response"
        ) in {
            val bodySize = 8 * 1024 * 1024
            val rounds   = 100
            val big      = HttpHandler.getBinary("big")(_ => Span.fromUnsafe(Array.fill(bodySize)('r'.toByte)))
            val config   = loopback.transportConfig(HttpTransportConfig.default.channelCapacity(1).readChunkSize(1.kb))
            def round(using Frame): Int < (Async & Abort[Any]) =
                Scope.run {
                    observedServer(config)(big).map { (server, _) =>
                        connectPeer(server.port).map { peer =>
                            val pipelined = "GET /big HTTP/1.1\r\nHost: h\r\n\r\n" * 40000
                            send(peer, "GET /big HTTP/1.1\r\nHost: h\r\nConnection: close\r\n\r\n" + pipelined)
                                .andThen(readToEof(peer))
                                .map { spans =>
                                    val all = text(spans)
                                    all.length - (all.indexOf("\r\n\r\n") + 4)
                                }
                        }
                    }
                }
            Loop(0) { i =>
                if i >= rounds then Loop.done(Absent: Maybe[(Int, Int)])
                else round.map(received => if received == bodySize then Loop.continue(i + 1) else Loop.done(Present((i + 1, received))))
            }.map { short =>
                assert(
                    short.isEmpty,
                    short.fold("")((r, received) => s"round $r of $rounds: the response's body arrived with $received of $bodySize bytes")
                )
            }
        }
    }

end UnsafeServerDispatchCloseContractTest
