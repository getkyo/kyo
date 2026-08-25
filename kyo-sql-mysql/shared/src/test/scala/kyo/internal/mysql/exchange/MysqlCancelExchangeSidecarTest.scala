package kyo.internal.mysql.exchange

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.SqlConfig.TlsMode
import kyo.internal.FakeServer
import kyo.internal.mysql.MysqlConnection
import kyo.internal.mysql.MysqlSqlConnection
import kyo.net.Connection

/** Pins the lifecycle of the connection a MySQL cancel runs on.
  *
  * MySQL has no out-of-band cancel packet, so stopping a statement means opening a second authenticated connection and running
  * `KILL QUERY <threadId>` on it. That connection is a sidecar: it exists for one kill and is closed afterwards, never pooled, because a
  * pooled one would hold a server connection open for every cancel that ever fired.
  *
  * Driven by a fake server that speaks just enough of the protocol to complete a handshake and answer one statement, and that reports what
  * each connection did into a channel. The sidecar's fate is only observable from the server's side, which is what this fake is for: a leaf
  * asserts on the exact sequence a server saw, so "the sidecar was closed" is a value comparison rather than an inference.
  */
class MysqlCancelExchangeSidecarTest extends kyo.Test:

    // ── the fake server's side of the protocol ────────────────────────────────

    /** Frames `payload` as one MySQL packet: 3-byte little-endian length, 1-byte sequence id, payload. */
    private def packet(seq: Int, payload: Array[Byte]): Array[Byte] =
        val len = payload.length
        Array[Byte](len.toByte, (len >>> 8).toByte, (len >>> 16).toByte, seq.toByte) ++ payload

    /** A `HandshakeV10` announcing `threadId`, `mysql_native_password`, and no `CLIENT_SSL`. */
    private def handshakeV10(threadId: Long): Array[Byte] =
        val version   = "8.0.34".getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](0x00)
        val scramble1 = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
        val scramble2 = Array[Byte](9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20)
        // CLIENT_SSL (0x0800) is deliberately absent: the client is configured with SqlConfig.TlsMode.Disable and a
        // server that advertised it would be answering a question this fake cannot answer.
        val capLow  = 0xf7ff
        val capHigh = 0x0008 // CLIENT_PLUGIN_AUTH
        val payload =
            Array[Byte](10) ++
                version ++
                Array[Byte](
                    threadId.toByte,
                    (threadId >>> 8).toByte,
                    (threadId >>> 16).toByte,
                    (threadId >>> 24).toByte
                ) ++
                scramble1 ++
                Array[Byte](0x00) ++
                Array[Byte](capLow.toByte, (capLow >>> 8).toByte) ++
                Array[Byte](255.toByte) ++ // utf8mb4
                Array[Byte](0x02, 0x00) ++ // status flags: autocommit
                Array[Byte](capHigh.toByte, (capHigh >>> 8).toByte) ++
                Array[Byte](21) ++     // auth-plugin-data length
                new Array[Byte](10) ++ // reserved
                scramble2 ++ Array[Byte](0x00) ++
                "mysql_native_password".getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](0x00)
        packet(0, payload)
    end handshakeV10

    /** An `OK` packet reporting no affected rows, in the `CLIENT_PROTOCOL_41` layout. */
    private def okPacket(seq: Int): Array[Byte] =
        packet(seq, Array[Byte](0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00))

    /** An `ERR` packet, in the `CLIENT_PROTOCOL_41` layout: code, the `#` marker, a five-character SQLSTATE, then the message. */
    private def errPacket(seq: Int, code: Int, sqlState: String, message: String): Array[Byte] =
        packet(
            seq,
            Array[Byte](0xff.toByte, code.toByte, (code >>> 8).toByte, '#'.toByte) ++
                sqlState.getBytes(StandardCharsets.US_ASCII) ++
                message.getBytes(StandardCharsets.US_ASCII)
        )

    /** One client packet, as `(sequence id, payload)`.
      *
      * Accumulates inbound bytes until a whole packet is present rather than assuming one read per packet: TCP does not promise that, and a
      * fake server that assumed it would fail for reasons that have nothing to do with the cancel.
      */
    private def readPacket(conn: Connection, carry: Chunk[Byte])(using Frame): Maybe[(Int, Chunk[Byte], Chunk[Byte])] < Async =
        def pullMore: Maybe[(Int, Chunk[Byte], Chunk[Byte])] < Async =
            Abort.run[Closed](conn.inbound.safe.take).flatMap {
                case Result.Success(bytes) => readPacket(conn, carry ++ Chunk.from(bytes.toArray))
                case _                     => Absent // the client closed the socket
            }
        if carry.size < 4 then pullMore
        else
            val len = (carry(0) & 0xff) | ((carry(1) & 0xff) << 8) | ((carry(2) & 0xff) << 16)
            if carry.size < 4 + len then pullMore
            else Present((carry(3) & 0xff, carry.slice(4, 4 + len), carry.drop(4 + len)))
        end if
    end readPacket

    /** Serves one connection: handshake, then one answer per statement, reporting everything it saw into `events`.
      *
      * The thread id the handshake announces is the connection's arrival order, which is what lets a leaf assert that a `KILL QUERY` named
      * the target rather than the sidecar itself. `killError` decides how a statement is answered: [[Absent]] answers `OK`, and
      * [[Present]] answers the named `ERR`, which is how a leaf drives the kill's failure paths.
      */
    private def serve(events: Channel[String], arrivals: AtomicInt, killError: Maybe[(Int, String)])(conn: Connection)(using
        Frame
    ): Unit < Async =
        def report(event: String): Unit < Async =
            Abort.run[Closed](events.put(event)).unit

        def loop(id: Long, carry: Chunk[Byte]): Unit < Async =
            readPacket(conn, carry).flatMap {
                case Absent => report(s"closed:$id")
                case Present((seq, payload, rest)) =>
                    val command = payload.headMaybe.map(_ & 0xff).getOrElse(-1)
                    if command == 0x03 then
                        val sql = new String(payload.drop(1).toArray, StandardCharsets.UTF_8)
                        val answer = killError match
                            case Present((code, sqlState)) => errPacket(seq + 1, code, sqlState, "refused by the fake server")
                            case Absent                    => okPacket(seq + 1)
                        report(s"query:$id:$sql").andThen {
                            Abort.run[Closed](conn.outbound.safe.put(Span.from(answer))).andThen(loop(id, rest))
                        }
                    else if command == 0x01 then report(s"quit:$id").andThen(loop(id, rest))
                    else report(s"unexpected:$id:$command").andThen(loop(id, rest))
                    end if
            }

        arrivals.incrementAndGet.flatMap { arrival =>
            val id = arrival.toLong
            report(s"accepted:$id").andThen {
                Abort.run[Closed](conn.outbound.safe.put(Span.from(handshakeV10(id)))).andThen {
                    readPacket(conn, Chunk.empty).flatMap {
                        case Absent => report(s"closed:$id")
                        case Present((_, _, rest)) =>
                            report(s"authenticated:$id").andThen {
                                Abort.run[Closed](conn.outbound.safe.put(Span.from(okPacket(2)))).andThen(loop(id, rest))
                            }
                    }
                }
            }
        }
    end serve

    // ── harness ───────────────────────────────────────────────────────────────

    private val config: SqlConfig = SqlConfig(maxConnections = 2, acquireTimeout = 30.seconds, tlsMode = TlsMode.Disable)

    /** Reads exactly `n` reported events, in order. */
    private def report(events: Channel[String], n: Int)(using Frame): Chunk[String] < (Async & Abort[SqlException]) =
        Abort.run[Closed](Kyo.foreach(Chunk.from(0 until n))(_ => events.take)).flatMap {
            case Result.Success(seen) => seen
            case other                => Abort.panic(new AssertionError(s"the fake server's event channel closed early: $other"))
        }

    /** Opens a connection whose in-flight flag is already raised, so a cancel has a statement to stop.
      *
      * The flag is what `cancelInFlight` reads to decide whether a sidecar is owed, and raising it directly is what lets this suite drive
      * the decision without a server that can run a slow statement.
      */
    private def withTarget[A](inFlight: Boolean, killError: Maybe[(Int, String)] = Absent)(
        f: (MysqlSqlConnection, Channel[String]) => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Scope & Abort[SqlException] & Abort[kyo.net.NetException]) =
        Channel.initUnscoped[String](64).flatMap { events =>
            AtomicInt.init(0).flatMap { arrivals =>
                FakeServer.listenPort(serve(events, arrivals, killError)).flatMap { listener =>
                    val address = SqlConfig.Address("mysql", "127.0.0.1", listener.port, "probe", Present("probe"))
                    val options = SqlConfig.Url.Options.default
                    MysqlSqlConnection.connect(address, Present("probe"), config, options).flatMap { conn =>
                        conn.connectionId.get.flatMap { cid =>
                            // Unsafe: three plain flags backing the in-flight window and the stream drain's
                            // clean-boundary record, exactly as the pool's factory builds them at
                            // kyo/internal/mysql/MysqlSqlConnection.scala.
                            Sync.Unsafe.defer(
                                new MysqlSqlConnection(
                                    conn,
                                    cid,
                                    address,
                                    Present("probe"),
                                    config,
                                    options,
                                    AtomicBoolean.Unsafe.init(inFlight),
                                    AtomicBoolean.Unsafe.init(false),
                                    AtomicBoolean.Unsafe.init(false)
                                )
                            ).flatMap { target =>
                                Scope.ensure(target.close).andThen(f(target, events))
                            }
                        }
                    }
                }
            }
        }

    // ── leaves ────────────────────────────────────────────────────────────────

    "a cancel opens a sidecar, kills the target's thread on it, and closes it" in {
        Scope.run {
            withTarget(inFlight = true) { (target, events) =>
                report(events, 2).flatMap { opened =>
                    target.cancelInFlight.andThen {
                        report(events, 5).map { seen =>
                            assert(opened == Chunk("accepted:1", "authenticated:1"), s"the target is the first connection; saw $opened")
                            assert(
                                seen == Chunk("accepted:2", "authenticated:2", "query:2:KILL QUERY 1", "quit:2", "closed:2"),
                                s"expected a second connection to kill thread 1 and then close; saw $seen"
                            )
                        }
                    }
                }
            }
        }
    }

    "a second cancel opens a fresh sidecar rather than reusing the first" in {
        Scope.run {
            withTarget(inFlight = true) { (target, events) =>
                report(events, 2).andThen {
                    target.cancelInFlight.andThen {
                        report(events, 5).flatMap { first =>
                            target.cancelInFlight.andThen {
                                report(events, 5).map { second =>
                                    assert(
                                        first == Chunk("accepted:2", "authenticated:2", "query:2:KILL QUERY 1", "quit:2", "closed:2"),
                                        s"the first sidecar is connection 2; saw $first"
                                    )
                                    // A pooled sidecar would have been reused here and no third connection
                                    // would appear; the arrival number is what tells the two apart.
                                    assert(
                                        second == Chunk("accepted:3", "authenticated:3", "query:3:KILL QUERY 1", "quit:3", "closed:3"),
                                        s"the second cancel must open connection 3; saw $second"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a cancel with nothing in flight opens no sidecar at all" in {
        Scope.run {
            withTarget(inFlight = false) { (target, events) =>
                report(events, 2).flatMap { opened =>
                    target.cancelInFlight.andThen {
                        // Nothing to stop, so no connection is owed. `poll` reads whatever the server has
                        // already reported without waiting, which would be a third arrival if one had happened.
                        Abort.run[Closed](events.poll).map { extra =>
                            assert(opened == Chunk("accepted:1", "authenticated:1"), s"the target is the first connection; saw $opened")
                            assert(extra == Result.Success(Absent), s"no further connection may be opened; saw $extra")
                        }
                    }
                }
            }
        }
    }

    // ── the kill's failure paths ──────────────────────────────────────────────

    "a kill the server rejects because the target thread is gone is not a failed cancel" in {
        Scope.run {
            // ER_NO_SUCH_THREAD is the ordinary answer to cancelling a statement that finished on its own,
            // which is the race a best-effort cancel is defined to tolerate.
            withTarget(inFlight = true, killError = Present((1094, "HY000"))) { (target, events) =>
                report(events, 2).andThen {
                    Abort.run[SqlException](target.cancelInFlight).flatMap { outcome =>
                        report(events, 5).map { seen =>
                            assert(outcome.isSuccess, s"a kill for an absent thread must not fail the cancel, got $outcome")
                            assert(
                                seen == Chunk("accepted:2", "authenticated:2", "query:2:KILL QUERY 1", "quit:2", "closed:2"),
                                s"expected the sidecar to be used and closed as on the OK path; saw $seen"
                            )
                        }
                    }
                }
            }
        }
    }

    "a kill the server refuses for any other reason still closes the sidecar" in {
        Scope.run {
            // ER_SPECIFIC_ACCESS_DENIED_ERROR: a real failure, which the cancel reports. The socket is still
            // the client's to close, and the close has to happen on this edge too or every refused cancel
            // leaks one connection.
            withTarget(inFlight = true, killError = Present((1227, "42000"))) { (target, events) =>
                report(events, 2).andThen {
                    Abort.run[SqlException](target.cancelInFlight).flatMap { outcome =>
                        report(events, 4).map { seen =>
                            outcome match
                                case Result.Failure(e: SqlServerException) =>
                                    assert(e.extra.get("code").contains("1227"), s"expected the server's own code, got ${e.extra}")
                                case other =>
                                    fail(s"expected the refusal to surface as a server error, got $other")
                            end match
                            assert(
                                seen == Chunk("accepted:2", "authenticated:2", "query:2:KILL QUERY 1", "closed:2"),
                                s"expected the sidecar closed after the refusal; saw $seen"
                            )
                        }
                    }
                }
            }
        }
    }

end MysqlCancelExchangeSidecarTest
