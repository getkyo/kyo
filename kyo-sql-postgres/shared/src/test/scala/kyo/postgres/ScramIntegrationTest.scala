package kyo.postgres

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.OwnContainer
import kyo.internal.TestContainers

/** Integration tests for SCRAM-SHA-256 authentication.
  *
  * Uses a postgres:16 container with the default auth method (scram-sha-256 is the postgres:16 default, so no POSTGRES_HOST_AUTH_METHOD
  * override is needed). Tests cover successful auth, wrong-password rejection, and server signature verification.
  */
class ScramIntegrationTest extends SqlContainerTest:

    // Scope the podman/docker HttpClient per leaf so its idle-connection pool does not leak
    // unix sockets across tests that call ContainerPredef.*.initWith directly.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        super.aroundLeaf(HttpClient.init().flatMap(c => HttpClient.let(c)(body)))

    // Container startup under CI's cross-module churn can outlast the default 2m per-leaf limit, so widen it
    // (the suite runs sequentially via SqlContainerTest).
    override def timeout: Duration = 5.minutes

    // Every leaf on the default config shares the process's default server: each one only logs in, or creates roles of its own
    // and drops them.
    private def shared(using Frame): ContainerPredef.Postgres < (Async & Abort[ContainerException]) =
        TestContainers.initSharedPostgres(ContainerPredef.Postgres.Config.default, "postgres-default")

    // Helper: init a SqlClient via SCRAM and return it.
    private def initScramClient(
        pg: ContainerPredef.Postgres
    )(using Frame): PostgresClient < (Async & Scope & Abort[SqlException] & Abort[ContainerException]) =
        pg.container.mappedPort(pg.config.port).flatMap { port =>
            PostgresClient.init(
                s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}",
                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
            )
        }

    // Helper: the string of `codePoints`, so a password's invisible or non-ASCII characters are written as numbers.
    private def text(codePoints: Int*): String =
        val out = new java.lang.StringBuilder
        codePoints.foreach(c => out.appendCodePoint(c))
        out.toString
    end text

    // Helper: create a login role on the shared server and drop it when the leaf's scope closes. Roles are cluster-wide, so a role
    // left behind would make the next leaf that creates the same name fail; the drop before the create clears one a failed drop left.
    private def createRole(admin: PostgresClient, role: String, password: String)(using
        Frame
    ): Unit < (Async & Scope & Abort[SqlException]) =
        val quoted = "\"" + role + "\""
        admin.executeRaw(s"DROP ROLE IF EXISTS $quoted")
            .andThen(admin.executeRaw(s"CREATE ROLE $quoted LOGIN PASSWORD '$password'"))
            .andThen(Scope.ensure(admin.executeRaw(s"DROP ROLE IF EXISTS $quoted")))
    end createRole

    // Helper: open one SCRAM connection as `role` with `password`, its own scope closing it, and return `current_user`.
    private def loginAs(pg: ContainerPredef.Postgres, role: String, password: String)(using
        Frame
    ): String < (Async & Abort[SqlException] & Abort[ContainerException]) =
        pg.container.mappedPort(pg.config.port).flatMap { port =>
            Scope.run {
                PostgresClient.init(
                    s"postgres://$role:$password@${pg.container.host}:$port/${pg.database}",
                    SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                ).flatMap { client =>
                    client.query("SELECT current_user").flatMap(rows => rows(0).decode[String](0))
                }
            }
        }

    "StartupExchange succeeds with SCRAM-SHA-256 server, connect completes without error" in {
        Scope.run {
            // Default postgres:16 uses scram-sha-256; no authMethod override needed.
            shared.map { pg =>
                initScramClient(pg).flatMap { client =>
                    client.isAlive.map(alive => assert(alive))
                }
            }
        }
    }

    "StartupExchange SCRAM wrong password raises SqlConnectionAuthenticationFailedException" in {
        Scope.run {
            shared.map { pg =>
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    Abort.run[SqlException] {
                        Scope.run {
                            PostgresClient.init(
                                s"postgres://${pg.username}:wrongpassword@${pg.container.host}:$port/${pg.database}",
                                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                            )
                        }
                    }.map {
                        case Result.Failure(e: SqlConnectionAuthenticationFailedException) =>
                            assert(
                                e.sqlState == "28P01" || e.sqlState == "28000",
                                s"Expected sqlState 28P01 or 28000 for wrong SCRAM password, got: ${e.sqlState}"
                            )
                        case other => fail(s"Expected SqlConnectionAuthenticationFailedException for wrong SCRAM password, got: $other")
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM server signature verified, no error after successful SCRAM" in {
        Scope.run {
            shared.map { pg =>
                // If server signature verification fails, connect raises SqlConnectionException.
                // Success here proves the server signature was accepted.
                initScramClient(pg).flatMap { client =>
                    client.isAlive.map(alive => assert(alive, "Connection should be open after SCRAM with valid server signature"))
                }
            }
        }
    }

    "StartupExchange SCRAM populates ParameterStatus, server_version present after SCRAM connect" in {
        Scope.run {
            shared.map { pg =>
                initScramClient(pg).flatMap { client =>
                    client.parameters.map { params =>
                        assert(
                            params.contains("server_version"),
                            s"server_version missing from params: ${params.keys.mkString(", ")}"
                        )
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM SELECT 1 returns correct result after authentication" in {
        Scope.run {
            shared.map { pg =>
                initScramClient(pg).flatMap { client =>
                    // Use text literal '1' so the server returns text OID bytes (UTF-8 compatible in binary format).
                    client.query("SELECT '1'").map { rows =>
                        assert(rows.size == 1)
                        val v = new String(rows(0).column(0).get.toArray, StandardCharsets.UTF_8)
                        assert(v == "1")
                    }
                }
            }
        }
    }

    "StartupExchange trust auth still works after SCRAM addition, regression".tagged(OwnContainer.name) in {
        Scope.run {
            // Verify cleartext (password) auth still works after adding SCRAM support.
            val regPredefConfig    = ContainerPredef.Postgres.Config.default.password("regpw")
            val regContainerConfig = ContainerPredef.Postgres.buildContainerConfig(regPredefConfig)
                .env("POSTGRES_HOST_AUTH_METHOD", "password")
            // Through `TestContainers` rather than `Container.init` directly, so the container carries the
            // `kyo-test-container` and `kyo-test-owner-pid` labels and a force-killed run's leftover is still reapable.
            TestContainers.initScoped(regContainerConfig, "postgres-password").flatMap { regContainer =>
                val pg = new ContainerPredef.Postgres(regContainer, regPredefConfig)
                pg.container.mappedPort(pg.config.port).flatMap { port =>
                    PostgresClient.init(
                        s"postgres://${pg.username}:${pg.password}@${pg.container.host}:$port/${pg.database}",
                        SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                    ).flatMap { client =>
                        client.isAlive.map(alive => assert(alive, "Cleartext auth must still work alongside SCRAM"))
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM authenticates a role whose name holds a comma and an equals sign" in {
        // RFC 5802 section 5.1 reserves both characters in the client-first message's name attribute. The exchange sends an empty
        // name, as libpq does, and the server takes the role from the startup packet, so the name never reaches the SCRAM grammar.
        Scope.run {
            shared.map { pg =>
                initScramClient(pg).flatMap { admin =>
                    createRole(admin, "odd,role=name", "commapw").flatMap { _ =>
                        pg.container.mappedPort(pg.config.port).flatMap { port =>
                            PostgresClient.init(
                                s"postgres://odd,role=name:commapw@${pg.container.host}:$port/${pg.database}",
                                SqlConfig.default.copy(maxConnections = 1, minConnections = 1)
                            ).flatMap { client =>
                                client.query("SELECT current_user").flatMap { rows =>
                                    rows(0).decode[String](0).map(name => assert(name == "odd,role=name"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM authenticates a role whose password the server stored in its SASLprep form" in {
        // The server prepares the password when it stores the secret, so the secret of "pass", a no-break space and three fullwidth
        // digits is the secret of "pass 123": the client must salt the prepared form, and both spellings log in.
        Scope.run {
            shared.map { pg =>
                initScramClient(pg).flatMap { admin =>
                    val password = "pass" + text(0x00a0, 0xff11, 0xff12, 0xff13)
                    createRole(admin, "prepared", password).flatMap { _ =>
                        loginAs(pg, "prepared", password).flatMap { first =>
                            assert(first == "prepared")
                            loginAs(pg, "prepared", "pass 123").map(second => assert(second == "prepared"))
                        }
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM authenticates roles whose passwords NFKC composes and B.1 maps to nothing, under either spelling" in {
        // "e" and a combining acute compose to U+00E9 under NFKC; a soft hyphen (RFC 3454 table B.1) maps to nothing. The server stores the
        // prepared form's secret, so the raw and the prepared spellings both log in.
        Scope.run {
            shared.map { pg =>
                initScramClient(pg).flatMap { admin =>
                    val combining  = "caf" + text(0x0065, 0x0301)
                    val softHyphen = "soft" + text(0x00ad) + "hyphen"
                    for
                        _        <- createRole(admin, "composed", combining)
                        _        <- createRole(admin, "hyphenated", softHyphen)
                        raw1     <- loginAs(pg, "composed", combining)
                        prepared <- loginAs(pg, "composed", "caf" + text(0x00e9))
                        raw2     <- loginAs(pg, "hyphenated", softHyphen)
                        mapped   <- loginAs(pg, "hyphenated", "softhyphen")
                    yield assert(Seq(raw1, prepared, raw2, mapped) == Seq("composed", "composed", "hyphenated", "hyphenated"))
                    end for
                }
            }
        }
    }

    "StartupExchange SCRAM authenticates roles whose passwords the profile prohibits: U+2028 (table C.2.2) and a bidi violation" in {
        // A line separator is a prohibited control character, and an Arabic letter after a Latin one breaks RFC 3454 section 6; SASLprep
        // fails, the server stores the raw password's secret, and the client salts the raw password, the fallback libpq applies.
        Scope.run {
            shared.map { pg =>
                initScramClient(pg).flatMap { admin =>
                    val separator = "line" + text(0x2028) + "separator"
                    val bidi      = "latin" + text(0x0627, 0x0644)
                    for
                        _     <- createRole(admin, "separated", separator)
                        _     <- createRole(admin, "mixed", bidi)
                        first <- loginAs(pg, "separated", separator)
                        other <- loginAs(pg, "mixed", bidi)
                    yield assert(first == "separated" && other == "mixed")
                    end for
                }
            }
        }
    }

    "StartupExchange SCRAM authenticates a role whose password the profile refuses, stored and sent raw" in {
        // An emoji is unassigned in Unicode 3.2, so SASLprep refuses the password; the server stores the raw password's secret and the
        // client salts the raw password, the fallback libpq applies.
        Scope.run {
            shared.map { pg =>
                initScramClient(pg).flatMap { admin =>
                    val password = "pencil" + text(0x1f600)
                    createRole(admin, "emoji", password).flatMap { _ =>
                        loginAs(pg, "emoji", password).map(name => assert(name == "emoji"))
                    }
                }
            }
        }
    }

    "StartupExchange SCRAM stores BackendKeyData, processId > 0 after SCRAM connect" in {
        Scope.run {
            shared.map { pg =>
                initScramClient(pg).flatMap { client =>
                    client.query("SELECT pg_backend_pid()").flatMap { rows =>
                        assert(rows.nonEmpty, "pg_backend_pid() returned no rows")
                        rows(0).decode[Int](0).map { pid =>
                            assert(pid > 0, s"Expected positive processId (pg_backend_pid()), got $pid")
                        }
                    }
                }
            }
        }
    }

end ScramIntegrationTest
