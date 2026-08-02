package kyo

import kyo.*
import kyo.Sql.*
import kyo.SqlConfig.TlsMode
import kyo.Test
import kyo.net.Connection
import kyo.net.NetTlsConfig

/** Asserts that a [[SqlConfig.TlsMode]] demanding encryption is honoured at the moment a connection is opened and served, not only at the moment a config
  * is built.
  *
  * The two leaves here are the two halves of one defect, and either half alone leaves the other reachable.
  *
  * `TlsContext.build` turns a mode into a [[kyo.net.NetTlsConfig]], and `SqlConfig.Url.toConfig` is the only caller that runs it on the way
  * to a client. So the invariant "a mode demanding TLS carries a TLS context" is established once, at construction, and any later route to
  * a `SqlConfig` can break it: `withConfig(_.copy(tlsMode = VerifyFull))` produces `tlsMode = VerifyFull` with `tls = Absent`, and both
  * engines' connect paths read `config.tls` rather than the mode, so `Absent` opens in the clear. The first leaf drives that.
  *
  * The pool then keys idle connections by endpoint, so even a config that does carry its TLS context can be served a session that was
  * opened without one. The second leaf drives that, and it is why refusing the malformed config is not by itself a fix: a plaintext session
  * already in the ring stays reachable by a lease that asked for TLS.
  *
  * Both leaves run against a plaintext fake server on the loopback, so a statement that SUCCEEDS is the failure being demonstrated: it
  * completed over an unencrypted socket for a caller who demanded encryption. Neither leaf needs a real database, and the fake server is
  * what makes the transport observable at all: against a real one, a statement answering correctly says nothing about whether TLS happened.
  */
class SqlConfigTlsModeTest extends Test:

    // ── Fake Postgres trust-auth handshake, then an answer to every statement ──

    private val pgAuthOkBytes: Span[Byte] = Span.from(
        Array[Byte](
            // AuthenticationOk: type='R', length=8, authType=0
            'R'.toByte,
            0x00,
            0x00,
            0x00,
            0x08,
            0x00,
            0x00,
            0x00,
            0x00,
            // BackendKeyData: type='K', length=12, pid=1, secretKey=0
            'K'.toByte,
            0x00,
            0x00,
            0x00,
            0x0c,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            // ReadyForQuery: type='Z', length=5, status='I'
            'Z'.toByte,
            0x00,
            0x00,
            0x00,
            0x05,
            'I'.toByte
        )
    )

    /** CommandComplete("BEGIN") + ReadyForQuery: a success answer to any simple query. */
    private val pgSimpleOkBytes: Span[Byte] = Span.from(
        Array[Byte](
            // CommandComplete: 'C', length=10, "BEGIN\0"
            'C'.toByte,
            0x00,
            0x00,
            0x00,
            0x0a,
            'B'.toByte,
            'E'.toByte,
            'G'.toByte,
            'I'.toByte,
            'N'.toByte,
            0x00,
            // ReadyForQuery: 'Z', length=5, status='I'
            'Z'.toByte,
            0x00,
            0x00,
            0x00,
            0x05,
            'I'.toByte
        )
    )

    /** Completes trust-auth in the clear, then answers every later message with success.
      *
      * It never speaks TLS and never responds to an SSLRequest, which is the point: a client that reaches a statement through this server
      * reached it unencrypted.
      */
    private def pgPlaintextHandler(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(pgAuthOkBytes)).andThen {
                def loop: Unit < Async =
                    Abort.run[Closed](conn.inbound.safe.take).flatMap {
                        case Result.Success(_) => Abort.run[Closed](conn.outbound.safe.put(pgSimpleOkBytes)).andThen(loop)
                        case _                 => ()
                    }
                loop
            }
        }

    private def fakeUrl(port: Int): String =
        s"postgres://testuser:testpass@127.0.0.1:$port/testdb"

    private def plaintextConfig: SqlConfig =
        SqlConfig(
            maxConnections = 2,
            acquireTimeout = 5.seconds,
            queryTimeout = 5.seconds,
            idleTimeout = 10.minutes,
            tlsMode = TlsMode.Disable
        )

    // The row each leaf's adjusted statement is written against. It is a DSL statement rather than a direct
    // `client.simpleQuery` because the two are settled by different configs: a `.run` reads the config the [[DB]]
    // effect carries, which is the one `withConfig` narrows, and a direct call on a client in hand reads the client's
    // own. Neither leaf gets a row back, so nothing here has to match a table.
    case class Probe(id: Long) derives CanEqual

    /** Opens a client against the plaintext fake server and runs `f`. */
    private def withPlaintextServer[A](config: SqlConfig)(
        f: SqlClient => A < (Async & Abort[SqlException] & Scope)
    )(using Frame): A < (Async & Abort[Any] & Scope) =
        kyo.internal.FakeServer.listenPort(pgPlaintextHandler).flatMap { listener =>
            SqlClient.initUnscoped(fakeUrl(listener.port), config).flatMap { client =>
                Scope.ensure(Abort.run(client.close).unit).andThen(f(client))
            }
        }

    // ── Half one: the mode is read at the point of use ────────────────────────

    "a statement under a TLS-demanding withConfig does not run over plaintext" in {
        Scope.run {
            withPlaintextServer(plaintextConfig) { client =>
                // The client was built for plaintext, so its `tls` is Absent. Raising the mode afterwards is the
                // route that leaves `tlsMode` demanding encryption with no TLS context to provide it, and only the
                // mode says what the caller wanted. The factories cannot produce that config: they merge through
                // `SqlConfig.Url.toConfig`, which builds a context for every mode that demands one, so an adjustment
                // over an already-open client is the only way this state is reachable at all.
                Abort.run[SqlException](
                    DB.run(client) {
                        DB.withConfig(_.copy(tlsMode = TlsMode.VerifyFull)) {
                            Sql.from[Probe]("p").run
                        }
                    }
                ).map { outcome =>
                    // The exact exception, not `isFailure`. On this path a plaintext socket to a fake server can
                    // also fail by protocol decode, so `isFailure` passes on a statement that ran unencrypted and
                    // then tripped over the reply, reporting green over a live downgrade.
                    outcome match
                        case Result.Failure(e: SqlConnectionTlsNotConfiguredException) =>
                            assert(
                                e.tlsMode == SqlConfig.TlsMode.VerifyFull,
                                s"the refusal must name the mode the caller asked for, got '${e.tlsMode}'"
                            )
                        case other =>
                            fail(
                                "a verify-full statement must be refused before it opens a socket; instead it " +
                                    s"reached the server over plaintext: $other"
                            )
                }
            }
        }
    }

    // ── Half two: the pool does not serve a plaintext session to a TLS lease ──

    "a TLS-demanding lease is not served a connection that was opened in the clear" in {
        Scope.run {
            withPlaintextServer(plaintextConfig) { client =>
                // First statement: plaintext, by the client's own config, which is what a direct call on a client in
                // hand reads and the reason this half needs no install at all. It succeeds and its connection goes
                // back to the idle ring. This half is also the control: if it fails, the fake server is wrong and the
                // assertion below would pass for the wrong reason. It stays a simple query because that is the only
                // protocol the fake server answers.
                Abort.run[SqlException](client.simpleQuery("SELECT 1")).flatMap { plaintext =>
                    assert(
                        plaintext.isSuccess,
                        s"the plaintext control must succeed or this leaf proves nothing: $plaintext"
                    )
                    // Second statement: a fully-formed TLS-demanding config, mode AND context, against the same
                    // endpoint. Refusing the malformed config from the first leaf does not help here, because
                    // nothing is malformed. The only thing that can keep this off the pooled plaintext session is
                    // the pool distinguishing them, and the fake server speaks no TLS, so honouring the demand can
                    // only fail.
                    val demanding: SqlConfig => SqlConfig =
                        _.copy(tlsMode = TlsMode.Require, tls = Present(NetTlsConfig(trustAll = true)))
                    Abort.run[SqlException](
                        DB.run(client) {
                            DB.withConfig(demanding) {
                                Sql.from[Probe]("p").run
                            }
                        }
                    ).map { secured =>
                        // The exact exception again, and here it says more than a refusal would. The demanding config
                        // is well formed, so nothing refuses it; the pool has to MISS the pooled plaintext session and
                        // open a new one, which sends an SSLRequest. The fake server answers the first message with
                        // its handshake bytes, whose leading byte is 'R', so a genuine TLS attempt reports that byte
                        // back. Seeing it is positive evidence that a TLS handshake was attempted on a new socket,
                        // where `isFailure` would have been satisfied by any stumble at all.
                        secured match
                            case Result.Failure(_: SqlConnectionSslRequestFailedException) => succeed
                            case Result.Success(rows) =>
                                fail(
                                    "a require-TLS statement completed against a server that speaks no TLS, so the " +
                                        s"pool handed it the plaintext session it had idle: $rows"
                                )
                            case other =>
                                fail(
                                    "the require-TLS lease must open its own socket and report the server's refusal " +
                                        s"of the SSLRequest: $other"
                                )
                    }
                }
            }
        }
    }

end SqlConfigTlsModeTest
