package kyo.internal.postgres.exchange

import kyo.*
import kyo.SqlConfig.TlsMode
import kyo.internal.FakeServer
import kyo.net.Connection
import kyo.net.NetTlsConfig

/** Pins which socket a Postgres cancel is allowed to write `(processId, secretKey)` on.
  *
  * The pair is a credential: the server accepts it from anyone who presents it on a bare connection, with no authentication, so whoever
  * reads it can cancel that backend's statements. A cancel that writes it on a plaintext socket under a mode demanding encryption therefore
  * hands it to whoever sits between the client and the server, and an attacker able to decline the upgrade can arrange exactly that.
  *
  * Driven by a fake server that speaks only the pre-startup framing: it reports every message it reads into a channel and decides how to
  * answer an `SSLRequest`. Reporting from the server's side is what makes "the cancel key stayed off the wire" a value comparison rather
  * than an inference, since nothing on the client can observe what was written. No database is involved, so these leaves run on all four
  * platforms.
  *
  * Every leaf carries two pieces of evidence, and the pair is what makes it decisive. The reported events are the direct wire observation.
  * The client's own outcome is the deterministic discriminator: an exchange that did not read the mode answers a declined upgrade with a
  * SUCCESSFUL cancel, so a typed refusal cannot be produced by anything except the branch under test.
  *
  * Each expectation ends with `closed`, the server observing the client's own close, which is the second property these leaves pin: a
  * cancel opens a connection for one write and owes its release on every edge, including the edges where a refusal means no write happens
  * at all. The event is what distinguishes a released descriptor from one left to the garbage collector, and the client cannot observe it.
  *
  * The three demanding modes all negotiate with `NetTlsConfig(trustAll = true)` here, where `verify-ca` and `verify-full` would carry a CA
  * bundle in production. That is not a misconfiguration: no leaf below reaches a TLS handshake, so the settings are never consulted, and it
  * is the MODE the branch under test reads.
  */
class CancelExchangeTlsTest extends kyo.Test:

    private val ProcessId = 4242
    private val SecretKey = 0x5ec4e7

    /** The server's answer to an `SSLRequest` when it declines the upgrade. */
    private val Decline = Present('N'.toByte)

    /** No answer at all: the server closes the socket, so the client's read of the reply fails. */
    private val NoAnswer = Absent

    private val DemandingModes     = Chunk(TlsMode.Require, TlsMode.VerifyCa, TlsMode.VerifyFull)
    private val OpportunisticModes = Chunk(TlsMode.Allow, TlsMode.Prefer)

    private def tlsSettings: Maybe[NetTlsConfig] = Present(NetTlsConfig(trustAll = true))

    private def cancelKey: String = s"cancelrequest:$ProcessId:$SecretKey"

    // --- the fake server's side of the pre-startup protocol ---

    private def int32(bytes: Chunk[Byte], at: Int): Int =
        ((bytes(at) & 0xff) << 24) | ((bytes(at + 1) & 0xff) << 16) | ((bytes(at + 2) & 0xff) << 8) | (bytes(at + 3) & 0xff)

    /** One pre-startup message, as `(message, remaining)`.
      *
      * Accumulates inbound bytes until a whole message is present rather than assuming one read per message: TCP does not promise that, and
      * a fake server that assumed it would fail for reasons with nothing to do with the cancel. Every pre-startup message carries its own
      * total length in its leading Int32, which is what makes the framing self-delimiting with no type byte to key on.
      */
    private def readMessage(conn: Connection, carry: Chunk[Byte])(using Frame): Maybe[(Chunk[Byte], Chunk[Byte])] < Async =
        def pullMore: Maybe[(Chunk[Byte], Chunk[Byte])] < Async =
            Abort.run[Closed](conn.inbound.safe.take).flatMap {
                case Result.Success(bytes) => readMessage(conn, carry ++ Chunk.from(bytes.toArray))
                case _                     => Absent // the client closed the socket
            }
        if carry.size < 4 then pullMore
        else
            val length = int32(carry, 0)
            if carry.size < length then pullMore
            else Present((carry.take(length), carry.drop(length)))
        end if
    end readMessage

    /** Names what the client sent, by the magic code that follows the length. */
    private def describe(message: Chunk[Byte]): String =
        if message.size < 8 then s"runt:${message.size}"
        else
            int32(message, 4) match
                case 80877103                       => "sslrequest"
                case 80877102 if message.size >= 16 => s"cancelrequest:${int32(message, 8)}:${int32(message, 12)}"
                case other                          => s"unexpected:$other"

    /** Serves one connection, reporting every pre-startup message it reads and answering an `SSLRequest` with `sslAnswer`. */
    private def serve(events: Channel[String], sslAnswer: Maybe[Byte])(conn: Connection)(using Frame): Unit < Async =
        def report(event: String): Unit < Async =
            Abort.run[Closed](events.put(event)).unit

        def loop(carry: Chunk[Byte]): Unit < Async =
            readMessage(conn, carry).flatMap {
                case Absent => report("closed")
                case Present((message, rest)) =>
                    val event = describe(message)
                    report(event).andThen {
                        if event != "sslrequest" then loop(rest)
                        else
                            sslAnswer match
                                case Present(byte) =>
                                    Abort.run[Closed](conn.outbound.safe.put(Span.from(Array[Byte](byte)))).andThen(loop(rest))
                                case Absent =>
                                    // Unsafe: Connection.close requires AllowUnsafe. Closing unanswered is the whole point
                                    // of this arm, since the client's read of the reply is the edge the leaf drives.
                                    Sync.Unsafe.defer(conn.close())
                    }
            }

        report("accepted").andThen(loop(Chunk.empty))
    end serve

    // --- harness ---

    /** Reads exactly `n` reported events, in order.
      *
      * Bounded, so a leaf whose expectation the server never satisfies says what it was waiting for instead of sitting in the framework's
      * stuck reporter until the suite timeout.
      */
    private def report(events: Channel[String], n: Int)(using Frame): Chunk[String] < (Async & Abort[SqlException]) =
        Abort.run[Closed | Timeout](Async.timeout(10.seconds)(Kyo.foreach(Chunk.from(0 until n))(_ => events.take))).flatMap {
            case Result.Success(seen) => seen
            case other                => Abort.panic(new AssertionError(s"the fake server reported fewer than $n event(s): $other"))
        }

    /** Binds a fake server answering `sslAnswer` and runs `f` against its address and event channel. */
    private def againstServer[A](sslAnswer: Maybe[Byte])(
        f: (SqlConfig.Address, Channel[String]) => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Scope & Abort[SqlException] & Abort[kyo.net.NetException]) =
        Channel.initUnscoped[String](16).flatMap { events =>
            FakeServer.listenPort(serve(events, sslAnswer)).flatMap { listener =>
                f(SqlConfig.Address("postgres", "127.0.0.1", listener.port, "probe", Present("probe")), events)
            }
        }

    private def cancel(address: SqlConfig.Address, tlsMode: TlsMode, tls: Maybe[NetTlsConfig])(using
        Frame
    ): Result[SqlException, Unit] < Async =
        Abort.run[SqlException](CancelExchange.cancel(address, tlsMode, tls, ProcessId, SecretKey))

    /** One line per mode, `<sslmode>: <event>,<event>,...`, so a mismatch names the mode and the whole sequence the server saw. */
    private def line(mode: TlsMode, seen: Chunk[String]): String =
        s"${mode.sslMode}: ${seen.mkString(",")}"

    // --- a server that declines the upgrade ---

    "a mode demanding encryption does not send the cancel key after the server declines the upgrade" in {
        Kyo.foreach(DemandingModes) { mode =>
            Scope.run {
                againstServer(Decline) { (address, events) =>
                    cancel(address, mode, tlsSettings).flatMap { outcome =>
                        report(events, 3).map { seen =>
                            outcome match
                                case Result.Failure(_: SqlConnectionTlsNotAdvertisedException) => line(mode, seen)
                                case other =>
                                    fail(
                                        s"under ${mode.sslMode} the cancel must report that the server did not advertise TLS, got $other"
                                    )
                        }
                    }
                }
            }
        }.map { observed =>
            assert(
                observed == Chunk(
                    "require: accepted,sslrequest,closed",
                    "verify-ca: accepted,sslrequest,closed",
                    "verify-full: accepted,sslrequest,closed"
                ),
                s"no demanding mode may write the cancel key on a socket the server refused to encrypt; observed $observed"
            )
        }
    }

    "an opportunistic mode reuses the declined socket and does send the cancel key" in {
        // The other half of the same branch, and the reason the mode decides rather than refuses outright: for `allow`
        // and `prefer` plaintext is what the caller asked to accept, so a cancel that refused here would lose cancels
        // for every user who never demanded encryption.
        Kyo.foreach(OpportunisticModes) { mode =>
            Scope.run {
                againstServer(Decline) { (address, events) =>
                    cancel(address, mode, tlsSettings).flatMap { outcome =>
                        report(events, 4).map { seen =>
                            outcome match
                                case Result.Success(_) => line(mode, seen)
                                case other             => fail(s"under ${mode.sslMode} the cancel must complete over plaintext, got $other")
                        }
                    }
                }
            }
        }.map { observed =>
            assert(
                observed == Chunk(
                    s"allow: accepted,sslrequest,$cancelKey,closed",
                    s"prefer: accepted,sslrequest,$cancelKey,closed"
                ),
                s"an opportunistic mode must still stop the statement; observed $observed"
            )
        }
    }

    "disable sends the cancel key with no SSLRequest at all" in {
        Scope.run {
            againstServer(Decline) { (address, events) =>
                cancel(address, TlsMode.Disable, Absent).flatMap { outcome =>
                    report(events, 3).map { seen =>
                        assert(
                            seen == Chunk("accepted", cancelKey, "closed"),
                            s"disable configures no TLS settings, so the cancel goes out as it always did; the server saw $seen"
                        )
                        assert(outcome == Result.unit, s"the cancel must complete under disable, got $outcome")
                    }
                }
            }
        }
    }

    // --- a mode demanding encryption with nothing to encrypt with ---

    "a mode demanding encryption with no TLS settings is refused before a socket is opened" in {
        // Mirrors the refusal `SqlConnectionPool.connect` makes on the pooled path. It matters here as well because this
        // is the second place that opens a socket to the server, and it is the one that carries the credential.
        Kyo.foreach(DemandingModes) { mode =>
            Scope.run {
                againstServer(Decline) { (address, events) =>
                    cancel(address, mode, Absent).flatMap { outcome =>
                        // `poll` reads whatever the server has already reported without waiting, so an "accepted" would
                        // be there if a connection had been opened at all.
                        Abort.run[Closed](events.poll).map { arrived =>
                            assert(
                                arrived == Result.Success(Absent),
                                s"under ${mode.sslMode} no socket may be opened for a cancel that cannot be encrypted; saw $arrived"
                            )
                            outcome match
                                case Result.Failure(e: SqlConnectionTlsNotConfiguredException) => e.tlsMode.sslMode
                                case other => fail(s"under ${mode.sslMode} the refusal must name the mode, got $other")
                        }
                    }
                }
            }
        }.map { named =>
            assert(
                named == Chunk("require", "verify-ca", "verify-full"),
                s"each refusal must name the mode the caller configured; got $named"
            )
        }
    }

    // --- a server that answers nothing ---

    "an unanswered SSLRequest does not send the cancel key, whatever the mode" in {
        // On a failed read of the reply this refuses for both mode families, which is what the pooled path does with
        // the same silence.
        //
        // The exception type is determinate rather than merely "some failure", and `report(events, 2)` above is what
        // makes it so: requiring the server to have READ the SSLRequest means the client's write completed, so the
        // write arm of `InitSSLExchange.sendSslRequest` is out of reach and only the read arm can answer. Both of that
        // arm's edges, a closed channel and a panic, raise `SqlConnectionClosedException`.
        Kyo.foreach(DemandingModes ++ OpportunisticModes) { mode =>
            Scope.run {
                againstServer(NoAnswer) { (address, events) =>
                    cancel(address, mode, tlsSettings).flatMap { outcome =>
                        report(events, 2).flatMap { seen =>
                            Abort.run[Closed](events.poll).map { extra =>
                                assert(
                                    extra == Result.Success(Absent),
                                    s"under ${mode.sslMode} nothing may follow an unanswered SSLRequest; saw $extra"
                                )
                                outcome match
                                    case Result.Failure(_: SqlConnectionClosedException) => line(mode, seen)
                                    case other =>
                                        fail(
                                            s"under ${mode.sslMode} a silent server must fail the cancel on the SSLRequest read, got $other"
                                        )
                                end match
                            }
                        }
                    }
                }
            }
        }.map { observed =>
            assert(
                observed == Chunk(
                    "require: accepted,sslrequest",
                    "verify-ca: accepted,sslrequest",
                    "verify-full: accepted,sslrequest",
                    "allow: accepted,sslrequest",
                    "prefer: accepted,sslrequest"
                ),
                s"a server that never answers the SSLRequest must never receive the cancel key; observed $observed"
            )
        }
    }

end CancelExchangeTlsTest
