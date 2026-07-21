package kyo.mysql

import kyo.*
import kyo.OwnContainer
import kyo.net.NetTlsConfig

/** Integration tests for MySQL TLS upgrade (CLIENT_SSL mid-handshake).
  *
  * Uses a vanilla `mysql:8.0` container — the image runs `mysqld` with `--auto-generate-certs=ON`, so a self-signed server certificate is
  * generated on first start and TLS is ready out of the box. No bind mounts, no `mysql_ssl_rsa_setup`, no container restart.
  *
  * All TLS assertions run inside a single test body so the container setup occurs only once per test run.
  */
class MysqlTlsIntegrationTest extends kyo.Test:

    override def timeout: Duration = 5.minutes

    private case class TlsConnDetails(
        host: String,
        port: Int,
        user: String,
        password: String,
        db: String,
        trustAllConfig: NetTlsConfig
    )

    /** Starts a fresh MySQL container (with TLS auto-enabled by mysql:8) and runs `f` against connection details that request TLS. */
    private def withTlsContainer[A](
        f: TlsConnDetails => A < (Async & Abort[SqlException])
    )(using Frame): A < (Async & Abort[Throwable] & Scope) =
        ContainerPredef.MySQL.initWith(ContainerPredef.MySQL.Config.default) { mysql =>
            mysql.container.mappedPort(mysql.config.port).flatMap { port =>
                val details = TlsConnDetails(
                    mysql.container.host,
                    port,
                    mysql.username,
                    mysql.password,
                    mysql.database,
                    NetTlsConfig(trustAll = true)
                )
                Abort.run[SqlException](f(details)).flatMap {
                    case Result.Success(a) => a
                    case Result.Failure(e) => Abort.fail(e: Throwable)
                    case Result.Panic(t)   => Abort.fail(t)
                }
            }
        }

    /** Opens a TLS client. Client is closed when the outer Scope exits. */
    private def openTlsClient(
        details: TlsConnDetails
    )(using Frame): SqlClient < (Async & Scope & Abort[SqlException]) =
        SqlClient.initMy(
            s"mysql://${details.user}:${details.password}@${details.host}:${details.port}/${details.db}",
            SqlClientConfig.default.copy(
                tls = Present(details.trustAllConfig),
                maxConnections = 1,
                minConnections = 1
            )
        )

    "MySQL TLS — InitTlsExchange, caching_sha2, and sequential queries".tagged("kyo.OwnContainer") in {
        Scope.run {
            withTlsContainer { details =>
                // ── Assertion 1: InitTlsExchange + handshake — isAlive=true after TLS upgrade ──
                Scope.run {
                    openTlsClient(details).flatMap { client =>
                        client.isAlive.map { open => assert(open, "Expected isAlive=true after TLS handshake") }
                    }
                }.flatMap { _ =>
                    // ── Assertion 2: server completes handshake (server version reachable via SELECT VERSION()) ──
                    Scope.run {
                        openTlsClient(details).flatMap { client =>
                            client.query("SELECT VERSION()").map { rows =>
                                val v = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                assert(v.nonEmpty, s"Expected non-empty server version, got: '$v'")
                            }
                        }
                    }.flatMap { _ =>
                        // ── Assertion 3: TLS MySQL connect + SELECT 1 succeeds ──
                        Scope.run {
                            openTlsClient(details).flatMap { client =>
                                client.query("SELECT 1").map { rows =>
                                    assert(rows.size == 1)
                                    val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    assert(str == "1", s"Expected '1', got '$str'")
                                }
                            }
                        }.flatMap { _ =>
                            // ── Assertion 4: caching_sha2 cleartext password over TLS — server sends fast-path or full-auth (cleartext) ──
                            Scope.run {
                                openTlsClient(details).flatMap { client =>
                                    client.query("SELECT 'tls_csha2_ok'").map { rows =>
                                        val str = new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                        assert(str == "tls_csha2_ok")
                                    }
                                }
                            }.flatMap { _ =>
                                // ── Assertion 5: multiple sequential queries on a single TLS connection ──
                                Scope.run {
                                    openTlsClient(details).flatMap { client =>
                                        client.query("SELECT 1").flatMap { r1 =>
                                            client.query("SELECT 2").flatMap { r2 =>
                                                client.query("SELECT 3").map { r3 =>
                                                    def str(rows: Chunk[SqlRow]) =
                                                        new String(rows(0).column(0).get.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                                    assert(str(r1) == "1")
                                                    assert(str(r2) == "2")
                                                    assert(str(r3) == "3")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end MysqlTlsIntegrationTest
