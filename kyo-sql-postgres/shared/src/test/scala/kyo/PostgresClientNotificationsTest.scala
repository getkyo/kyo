package kyo

import java.nio.charset.StandardCharsets
import kyo.SqlConfig.TlsMode
import kyo.internal.FakeServer
import kyo.net.Connection

/** Pins that [[PostgresClient.notifications]] opens its dedicated connection through the pool rather than beside it.
  *
  * The listener is the one operation that takes no lease, so it is the one operation that can miss the guards the pool applies on the way
  * to a socket. A listener calling the raw connect directly reads `config.tls` and never `config.tlsMode`, so a caller demanding encryption
  * with no TLS context to provide it has every statement refused and is handed a plaintext listener.
  *
  * Two of those guards refuse before anything is written, which is what makes them testable with no database. The fake server here answers
  * nothing and closes: a leaf that reaches it fails with a connection error rather than the typed refusal it asserts, so the guard firing
  * is the only way to pass and a bypass cannot look like one.
  *
  * The third leaf covers the other direction. Refusing correctly is worth nothing if the connection the listener does open no longer
  * carries what the URL declared, and it is opened through the pool's factory. It drives one notification end to end and reads the
  * `application_name` back off the wire.
  */
class PostgresClientNotificationsTest extends Test:

    // ── wire bytes ────────────────────────────────────────────────────────────

    /** `'R'` AuthenticationOk: the trust-auth reply to a StartupMessage. */
    private val authenticationOk: Array[Byte] =
        Array[Byte]('R', 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00)

    /** `'K'` BackendKeyData carrying process id 1 and secret key 0. */
    private val backendKeyData: Array[Byte] =
        Array[Byte]('K', 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)

    /** `'Z'` ReadyForQuery, idle: the end of startup and of every simple query. */
    private val readyForQuery: Array[Byte] =
        Array[Byte]('Z', 0x00, 0x00, 0x00, 0x05, 'I')

    /** `'C'` CommandComplete("LISTEN"), length 4 plus the 7 bytes of the tag and its terminator. */
    private val commandComplete: Array[Byte] =
        Array[Byte]('C', 0x00, 0x00, 0x00, 0x0b, 'L', 'I', 'S', 'T', 'E', 'N', 0x00)

    /** `'A'` NotificationResponse: the message a `NOTIFY` on a subscribed channel produces. */
    private def notificationResponse(processId: Int, channel: String, payload: String): Array[Byte] =
        val channelBytes = channel.getBytes(StandardCharsets.US_ASCII)
        val payloadBytes = payload.getBytes(StandardCharsets.US_ASCII)
        val len          = 4 + 4 + channelBytes.length + 1 + payloadBytes.length + 1
        Array[Byte]('A') ++
            Array[Byte]((len >>> 24).toByte, (len >>> 16).toByte, (len >>> 8).toByte, len.toByte) ++
            Array[Byte]((processId >>> 24).toByte, (processId >>> 16).toByte, (processId >>> 8).toByte, processId.toByte) ++
            channelBytes ++ Array[Byte](0x00) ++ payloadBytes ++ Array[Byte](0x00)
    end notificationResponse

    // ── fake servers ──────────────────────────────────────────────────────────

    /** Reads one message and ends the session.
      *
      * Deliberately unhelpful. A listener that gets this far fails on its next read, promptly and with a connection error, which is what
      * makes the two refusal leaves unambiguous: the exception each asserts is not one this server can produce, and a server that answered
      * the handshake instead would let a bypassed guard hang on a read that never comes.
      */
    private def closingServer(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            // Unsafe: Connection.close is the unsafe-tier close, and this handler runs outside the test's fiber.
            Sync.Unsafe.defer(conn.close())
        }

    /** Answers the handshake, records the startup bytes, then answers each query and delivers one notification.
      *
      * `startupSeen` is written before the handshake is answered, so a notification arriving proves the startup message was already
      * captured. That causality is the latch: the leaf reads the ref only after taking a value, so it needs no sleep and no timeout.
      */
    private def listeningServer(startupSeen: AtomicRef[Maybe[Span[Byte]]], channel: String)(conn: Connection)(using
        Frame
    ): Unit < Async =
        val handshake  = authenticationOk ++ backendKeyData ++ readyForQuery
        val queryReply = commandComplete ++ readyForQuery ++ notificationResponse(4242, channel, "payload")
        Abort.run[Closed](conn.inbound.safe.take).flatMap {
            case Result.Success(startup) =>
                startupSeen.set(Present(startup)).andThen {
                    Abort.run[Closed](conn.outbound.safe.put(Span.from(handshake))).andThen {
                        def loop: Unit < Async =
                            Abort.run[Closed](conn.inbound.safe.take).flatMap {
                                case Result.Success(_) =>
                                    Abort.run[Closed](conn.outbound.safe.put(Span.from(queryReply))).andThen(loop)
                                case _ => ()
                            }
                        loop
                    }
                }
            case _ => ()
        }
    end listeningServer

    /** No leaf here waits on a statement, so the close has nothing to drain and needs no grace. */
    private def probeConfig: SqlConfig =
        SqlConfig(maxConnections = 1, acquireTimeout = 5.seconds, closeGrace = Duration.Zero)

    /** Bounds a read against a server that may never answer, so the failure names what the read wanted.
      *
      * This does a different job from the latch documented on [[listeningServer]], and the two are easy to conflate.
      * The latch establishes ORDERING, and take(1) completing is that proof, so no bound is needed for it and no
      * sleep belongs anywhere near it. This converts BREAKAGE into a NAMED failure.
      *
      * It is a diagnostics improvement and not a hang fix, which is worth stating because the opposite is easy to
      * assume. Every leaf is already bounded: `kyo.test.internal.TestBase.timeout` defaults to 120 seconds outside a
      * debugger, so a server that goes silent fails there rather than hanging. What this buys is failing in a quarter
      * of that time, and saying what the read was waiting for, where a bare per-test TimedOut says only which leaf
      * died.
      *
      * Do not copy this budget into a suite that raises `timeout`. The 30 seconds is safe here only because none of
      * these leaves overrides the default; the suites that do raise it are container work, up to 20 minutes, and a
      * tight bound would cut off tests that are legitimately slow.
      */
    private def withinBudget[A](waitingFor: String)(read: A < (Async & Abort[SqlException] & Scope))(using
        Frame,
        kyo.test.AssertScope
    ): A < (Async & Abort[SqlException] & Scope) =
        Abort.run[Timeout](Async.timeout(30.seconds)(read)).map {
            case Result.Success(a)          => a
            case Result.Failure(_: Timeout) => fail(s"timed out after 30s waiting for $waitingFor")
            case Result.Panic(t)            => fail(s"panicked while waiting for $waitingFor: ${t.getMessage}")
        }

    // ── the refusal that precedes any socket ──────────────────────────────────
    //
    // The require-TLS-before-socket refusal is not reachable from here. `withConfig` adjusts only the config the DSL
    // `.run` surface reads (DB.State.config), while a direct engine method like `notifications` reads the client's own
    // open-time config, which carries a real TLS context for `require`. SqlConfigTlsModeTest drives that refusal
    // against a DSL statement instead.

    "a closed client refuses to subscribe" in {
        Scope.run {
            FakeServer.listenPort(closingServer).flatMap { listener =>
                val url = s"postgres://probe:probe@127.0.0.1:${listener.port}/probe"
                PostgresClient.initUnscoped(url, probeConfig).flatMap { client =>
                    // Ascribed because `close` is overloaded: without an expected type the compiler eta-expands the
                    // `close(gracePeriod)` arity and `andThen` then resolves to `Function1.andThen`.
                    val closed: Unit < Async = client.close
                    closed.andThen {
                        withinBudget("the closed-pool refusal") {
                            Abort.run[SqlException](client.notifications("chan").take(1).run)
                        }.map {
                            case Result.Failure(_: SqlConnectionPoolClosedException) => succeed
                            case other =>
                                fail(
                                    "subscribing on a closed client must be refused by the pool; instead it reached " +
                                        s"the transport: $other"
                                )
                        }
                    }
                }
            }
        }
    }

    // ── the channel name the caller supplies ──────────────────────────────────

    "a channel name carrying a NUL is refused before anything is opened" in {
        Scope.run {
            FakeServer.listenPort(closingServer).flatMap { listener =>
                val url = s"postgres://probe:probe@127.0.0.1:${listener.port}/probe"
                PostgresClient.initUnscoped(url, probeConfig).flatMap { client =>
                    Scope.ensure(client.close).andThen {
                        // Built with `0.toChar` rather than an escape sequence, deliberately: an escape in this file
                        // is one tool decode away from becoming a literal NUL byte in the source, which turns the
                        // whole file binary and silently invisible to grep.
                        val channel = "a" + 0.toChar + "b"
                        // Quoting cannot cover this one. The statement is a cstring, so the NUL would end it at the
                        // server and `LISTEN "a` would come back as a syntax error naming nothing the caller wrote.
                        withinBudget("the NUL-channel refusal") {
                            Abort.run[SqlException](client.notifications(channel).take(1).run)
                        }.map {
                            case Result.Failure(e: SqlRequestNotificationChannelNulException) =>
                                assert(e.index == 1, s"the refusal must locate the NUL, got index ${e.index}")
                            case other =>
                                fail(
                                    "a channel name containing a NUL must be refused before a connection is opened; " +
                                        s"instead it reached the transport: $other"
                                )
                        }
                    }
                }
            }
        }
    }

    // ── the connection the listener does open ─────────────────────────────────

    "the listener's connection carries the application_name the URL declared" in {
        Scope.run {
            AtomicRef.init(Maybe.empty[Span[Byte]]).flatMap { startupSeen =>
                FakeServer.listenPort(listeningServer(startupSeen, "chan")).flatMap { listener =>
                    val url = s"postgres://probe:probe@127.0.0.1:${listener.port}/probe?application_name=kyo-listener-probe"
                    PostgresClient.initUnscoped(url, probeConfig).flatMap { client =>
                        Scope.ensure(client.close).andThen {
                            withinBudget("the notification the fake server delivers") {
                                client.notifications("chan").take(1).run
                            }.flatMap { received =>
                                assert(received.size == 1, s"expected the one notification the server sent, got ${received.size}")
                                val delivered = received(0)
                                assert(delivered.channel == "chan", s"expected channel 'chan', got '${delivered.channel}'")
                                assert(delivered.payload == "payload", s"expected payload 'payload', got '${delivered.payload}'")
                                assert(delivered.processId == 4242, s"expected process id 4242, got ${delivered.processId}")
                                startupSeen.get.map {
                                    case Present(startup) =>
                                        // The StartupMessage is a run of NUL-terminated key/value pairs, so the two
                                        // are asserted as ADJACENT fields rather than as a substring: a value that
                                        // arrived truncated, or under a different key, cannot satisfy this.
                                        val fields =
                                            new String(startup.toArray, StandardCharsets.US_ASCII).split(0.toChar).toIndexedSeq
                                        assert(
                                            fields.containsSlice(Seq("application_name", "kyo-listener-probe")),
                                            "the listener's startup message must carry the URL's application_name, got " +
                                                fields.mkString("|")
                                        )
                                    case Absent =>
                                        fail("the server recorded no startup message, so this leaf proves nothing")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end PostgresClientNotificationsTest
