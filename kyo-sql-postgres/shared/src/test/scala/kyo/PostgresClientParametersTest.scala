package kyo

import java.nio.charset.StandardCharsets
import kyo.db.Idiom
import kyo.internal.FakeServer
import kyo.net.Connection

/** Pins what [[PostgresClient.parameters]] reports, and what reads it.
  *
  * PostgreSQL announces its session settings as `ParameterStatus` messages during startup, so the map is filled by the handshake and needs
  * no query. That is what makes it testable without a database: a fake server replays a captured startup sequence and each leaf asserts on
  * the exact map, or on what depends on it.
  */
class PostgresClientParametersTest extends Test:

    // ── captured startup frames ───────────────────────────────────────────────

    /** `'R'` AuthenticationOk: the trust-auth reply to a StartupMessage. */
    private val authenticationOk: Array[Byte] =
        Array[Byte]('R', 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00)

    /** `'K'` BackendKeyData carrying process id 1 and secret key 0. */
    private val backendKeyData: Array[Byte] =
        Array[Byte]('K', 0x00, 0x00, 0x00, 0x0c, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)

    /** `'Z'` ReadyForQuery, idle: the end of startup. */
    private val readyForQuery: Array[Byte] =
        Array[Byte]('Z', 0x00, 0x00, 0x00, 0x05, 'I')

    /** `'S'` ParameterStatus for one setting, both fields NUL-terminated. */
    private def parameterStatus(name: String, value: String): Array[Byte] =
        val nameBytes  = name.getBytes(StandardCharsets.US_ASCII)
        val valueBytes = value.getBytes(StandardCharsets.US_ASCII)
        val len        = 4 + nameBytes.length + 1 + valueBytes.length + 1
        Array[Byte]('S') ++
            Array[Byte]((len >>> 24).toByte, (len >>> 16).toByte, (len >>> 8).toByte, len.toByte) ++
            nameBytes ++ Array[Byte](0x00) ++ valueBytes ++ Array[Byte](0x00)
    end parameterStatus

    /** A server that answers the startup message with `settings` and then holds the connection open. */
    private def serverReporting(settings: (String, String)*)(conn: Connection)(using Frame): Unit < Async =
        val startup = settings.foldLeft(authenticationOk) { case (acc, (name, value)) => acc ++ parameterStatus(name, value) } ++
            backendKeyData ++ readyForQuery
        Abort.run[Closed](conn.inbound.safe.take).andThen {
            Abort.run[Closed](conn.outbound.safe.put(Span.from(startup))).andThen {
                Async.sleep(Duration.Infinity)
            }
        }
    end serverReporting

    /** Opens a client against a server reporting `settings`, and closes it when the leaf ends. */
    private def withClient[A, S](settings: (String, String)*)(
        f: PostgresClient => A < (S & Async & Abort[SqlException])
    )(using Frame): A < (S & Async & Scope & Abort[SqlException] & Abort[kyo.net.NetException]) =
        FakeServer.listenPort(serverReporting(settings*)).flatMap { listener =>
            val url = s"postgres://probe:probe@127.0.0.1:${listener.port}/probe"
            PostgresClient.initUnscoped(url, SqlConfig(maxConnections = 1, acquireTimeout = 30.seconds)).flatMap { client =>
                Scope.ensure(client.close).andThen(f(client))
            }
        }

    // ── leaves ────────────────────────────────────────────────────────────────

    "parameters reports every setting the server announced at startup" in {
        withClient(
            "server_version"    -> "16.2",
            "server_encoding"   -> "UTF8",
            "TimeZone"          -> "UTC",
            "integer_datetimes" -> "on"
        ) { client =>
            client.parameters.map { params =>
                assert(
                    params == Map(
                        "server_version"    -> "16.2",
                        "server_encoding"   -> "UTF8",
                        "TimeZone"          -> "UTC",
                        "integer_datetimes" -> "on"
                    ),
                    s"expected the announced settings, got $params"
                )
            }
        }
    }

    "serverVersion is read from the parameter map, not from a query" in {
        withClient("server_version" -> "16.2") { client =>
            // The handshake is the only round-trip this server answers: anything else would hang, so a
            // version coming back at all proves it came from the startup parameters.
            client.serverVersion.map { version =>
                assert(version == Idiom.ServerVersion(16, 2, 0), s"expected 16.2.0, got ${version.show}")
            }
        }
    }

    "a server_version carrying a build suffix parses to its numeric components" in {
        withClient("server_version" -> "16.2 (Debian 16.2-1.pgdg120+2)") { client =>
            client.serverVersion.map { version =>
                assert(version == Idiom.ServerVersion(16, 2, 0), s"expected the suffix to be stripped, got ${version.show}")
            }
        }
    }

    "a startup that announced no server_version fails the version read and still reports the rest" in {
        withClient("server_encoding" -> "UTF8") { client =>
            Abort.run[SqlException](client.serverVersion).flatMap { outcome =>
                client.parameters.map { params =>
                    outcome match
                        case Result.Failure(e: SqlConnectionProtocolDecodeException) =>
                            assert(
                                e.message.contains("server_version"),
                                s"the failure must name the missing parameter, got ${e.message}"
                            )
                        case other =>
                            fail(s"expected a protocol decode failure naming server_version, got $other")
                    end match
                    assert(params == Map("server_encoding" -> "UTF8"), s"the other settings are still reported, got $params")
                }
            }
        }
    }

end PostgresClientParametersTest
