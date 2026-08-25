package kyo.internal.postgres.exchange

import kyo.*
import kyo.internal.FakeServer
import kyo.internal.postgres.PostgresConnection
import kyo.internal.postgres.auth.ChannelBinding
import kyo.net.Connection

/** Unit tests for [[StartupExchange]]: the point where the SASL mechanism and the RFC 5802 GS2 channel-binding flag are chosen, and the point
  * where a server's demand for a credential meets a connection that has none.
  *
  * These leaves exist because the `y` flag is unreachable against an honest server, and so cannot be covered by any container fixture. A
  * PostgreSQL server built with SSL always advertises `SCRAM-SHA-256-PLUS` on an SSL connection, so "the connection can bind and the server
  * offered no -PLUS mechanism" is produced only by an intermediary editing the advertised list, which is precisely the case the flag exists
  * to report. The input domain is small and fully enumerated here instead; `ScramPlusIntegrationTest` covers the two arms a real server can
  * reach.
  *
  * `ScramSha256SharedTest` pins the bytes each [[ChannelBinding]] state emits. This file pins which state each server answer maps to, and
  * the last leaf drives a real connect against a fake server to pin what happens when no password was configured at all.
  */
class StartupExchangeTest extends kyo.Test:

    private val plain    = "SCRAM-SHA-256"
    private val plus     = "SCRAM-SHA-256-PLUS"
    private val certHash = Span.fill(32)(0xab.toByte)

    "-PLUS offered and a certificate hash in hand binds, and names the -PLUS mechanism" in {
        StartupExchange.selectMechanism(Chunk(plain, plus), Present(certHash)) match
            case Present((mechanism, binding)) =>
                assert(mechanism == plus)
                assert(binding == ChannelBinding.Bound(certHash))
            case Absent => fail("both mechanisms offered with a hash must select PLUS")
        end match
    }

    "a certificate hash with no -PLUS offered sends y, not n: this is the stripped-PLUS case" in {
        // The defect this leaf guards: collapsing the two absence cases into one emitted n here, which a server that
        // does support channel binding accepts, so an active attacker could strip -PLUS from the advertised list and
        // neither end would learn anything.
        StartupExchange.selectMechanism(Chunk(plain), Present(certHash)) match
            case Present((mechanism, binding)) =>
                assert(mechanism == plain)
                assert(binding == ChannelBinding.SupportedButNotOffered)
            case Absent => fail("plain SCRAM with a hash must still authenticate, reporting the missing -PLUS")
        end match
    }

    "no certificate hash reports n even when the server offered -PLUS, because there is nothing to bind to" in {
        StartupExchange.selectMechanism(Chunk(plain, plus), Absent) match
            case Present((mechanism, binding)) =>
                assert(mechanism == plain)
                assert(binding == ChannelBinding.NotSupported)
            case Absent => fail("a plaintext connection must still authenticate with plain SCRAM")
        end match
    }

    "no certificate hash and no -PLUS offered reports n" in {
        StartupExchange.selectMechanism(Chunk(plain), Absent) match
            case Present((mechanism, binding)) =>
                assert(mechanism == plain)
                assert(binding == ChannelBinding.NotSupported)
            case Absent => fail("plain SCRAM over plaintext is the ordinary case")
        end match
    }

    "-PLUS alone with no certificate hash selects nothing rather than downgrading to plain SCRAM" in {
        // A server advertising only the binding mechanism is demanding binding. Answering with a mechanism it never
        // named would be the client stripping that requirement, so the exchange fails instead.
        assert(StartupExchange.selectMechanism(Chunk(plus), Absent) == Absent)
    }

    "a mechanism list this client does not implement selects nothing" in {
        assert(StartupExchange.selectMechanism(Chunk("GSSAPI", "EXTERNAL"), Present(certHash)) == Absent)
        assert(StartupExchange.selectMechanism(Chunk.empty, Absent) == Absent)
    }

    // ── A server demanding a credential the caller never supplied ─────────────

    /** Answers the startup message with `AuthenticationCleartextPassword`, then rejects whatever comes next.
      *
      * The rejection is what makes the leaf below decisive rather than a timeout: a client that authenticates with the empty string gets a
      * `28P01` from this server, which is a different typed failure from the refusal being asserted, so the two behaviours cannot be
      * confused and neither of them hangs.
      */
    private val cleartextRequest: Span[Byte] = Span.from(
        Array[Byte](
            // AuthenticationCleartextPassword: type='R', length=8, authType=3
            'R'.toByte,
            0x00,
            0x00,
            0x00,
            0x08,
            0x00,
            0x00,
            0x00,
            0x03
        )
    )

    /** ErrorResponse carrying SQLSTATE 28P01 (invalid_password): 'E', Int32 length, ('C' sqlstate NUL) ('M' message NUL) NUL. */
    private val authRejected: Span[Byte] =
        val fields =
            Array[Byte]('C'.toByte) ++ "28P01".getBytes("UTF-8") ++ Array[Byte](0x00) ++
                Array[Byte]('M'.toByte) ++ "password authentication failed".getBytes("UTF-8") ++ Array[Byte](0x00) ++
                Array[Byte](0x00)
        // The length field counts itself and the fields, not the leading type byte.
        val len = 4 + fields.length
        Span.from(
            Array[Byte](
                'E'.toByte,
                ((len >>> 24) & 0xff).toByte,
                ((len >>> 16) & 0xff).toByte,
                ((len >>> 8) & 0xff).toByte,
                (len & 0xff).toByte
            ) ++ fields
        )
    end authRejected

    /** AuthenticationGSS: type='R', length=8, authType=7. The first and only byte sequence a server offering GSSAPI alone sends.
      *
      * The same five header bytes as [[cleartextRequest]] with a different sub-type code, which is the whole difference between a method
      * this client implements and one it refuses.
      */
    private val gssRequest: Span[Byte] = Span.from(
        Array[Byte]('R'.toByte, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x07)
    )

    /** Answers the startup message with `AuthenticationGSS` and says nothing further, because the client has nothing to reply with. */
    private def gssOfferingHandler(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(gssRequest)).unit
        }

    "a server offering only GSSAPI is refused by the mechanism's own name, with no trailing dollar" in {
        // The name is the whole diagnosis here: the caller cannot connect, and what the message says is all they have to work from.
        // `getSimpleName` on a Scala case object returns the module class's name, which reads `GSS$` and sends a reader looking for a
        // mechanism spelled with a dollar. A case object's `toString` is a compiled-in string literal, so it is `GSS` on every platform.
        Scope.run {
            FakeServer.listenPort(gssOfferingHandler).flatMap { listener =>
                Abort.run[SqlException](
                    PostgresConnection.connect(
                        "127.0.0.1",
                        listener.port,
                        "testuser",
                        "testdb",
                        Absent,
                        Absent,
                        64,
                        Duration.Infinity
                    )
                ).map {
                    case Result.Failure(e: SqlConnectionUnsupportedAuthMethodException) =>
                        assert(e.mechanism == "GSS", s"the refusal must name the mechanism, got '${e.mechanism}'")
                    case other =>
                        fail(s"a GSSAPI-only server must be refused as an unsupported mechanism, got: $other")
                }
            }
        }
    }

    private def cleartextDemandingHandler(conn: Connection)(using Frame): Unit < Async =
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(cleartextRequest)).andThen {
                Abort.run[Closed](conn.inbound.safe.take).flatMap { _ =>
                    Abort.run[Closed](conn.outbound.safe.put(authRejected)).unit
                }
            }
        }

    "a server demanding a password when none is configured is refused before any credential is derived" in {
        // Reachable from any caller whose URL carries no password, against any server whose pg_hba demands one. The
        // refusal names the method rather than letting the server report a rejected credential, because "none was
        // supplied" and "the one supplied is wrong" send the reader to different places.
        Scope.run {
            FakeServer.listenPort(cleartextDemandingHandler).flatMap { listener =>
                Abort.run[SqlException](
                    PostgresConnection.connect(
                        "127.0.0.1",
                        listener.port,
                        "testuser",
                        "testdb",
                        Absent,
                        Absent,
                        64,
                        Duration.Infinity
                    )
                ).map {
                    case Result.Failure(e: SqlConnectionPasswordRequiredException) =>
                        assert(
                            e.method == "cleartext password",
                            s"the refusal must name the method the server asked for, got '${e.method}'"
                        )
                    case other =>
                        fail(
                            "a connection with no password must be refused before it derives a credential; instead it " +
                                s"authenticated with the empty string and the server answered: $other"
                        )
                }
            }
        }
    }

end StartupExchangeTest
