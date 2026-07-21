package kyo.internal

import kyo.*
import kyo.internal.mysql.MysqlConnection
import kyo.internal.postgres.PostgresConnection
import kyo.internal.postgres.types.EncodingRegistry

/** Empirical probe for the privileges granted by [[ContainerPredef.Postgres]] and [[ContainerPredef.MySQL]] default configurations.
  *
  * Probes whether the configured default user can `CREATE DATABASE`, whether the root user (MySQL) can, and whether a fresh connection can
  * authenticate to a freshly-created database with and without an explicit `GRANT`. Each probe always succeeds at the test level — its sole
  * purpose is to log the outcome (success, or the captured [[SqlException]] message/SQLSTATE/error code) to stderr so the results can be
  * copied into `kyo-sql/PHASE-0-PRIVILEGE-CHECK.md`.
  *
  * Probe results are surfaced via `scala.Console.err.println` with the prefix `[probe]` so they can be greped from sbt output.
  */
class PrivilegeProbeIntegrationTest extends kyo.Test:

    // ── helpers ──────────────────────────────────────────────────────────────

    private def logProbe(label: String, msg: String): Unit < Sync =
        Sync.defer {
            scala.Console.err.println(s"[probe] $label :: $msg")
        }

    private def describeFailure(label: String, t: Throwable): String =
        t match
            case s: SqlException.Server =>
                val code = s.extra.getOrElse("code", "")
                s"FAILURE — SqlException.Server[sqlState=${s.sqlState}, code=$code, message=${s.message}]"
            case s: SqlException.Connection =>
                s"FAILURE — SqlException.Connection[message=${s.message}]"
            case s: SqlException.Request =>
                s"FAILURE — SqlException.Request[message=${s.message}]"
            case s: SqlException.Decode =>
                s"FAILURE — SqlException.Decode[message=${s.message}]"
            case other =>
                s"FAILURE — ${other.getClass.getSimpleName}[message=${other.getMessage}]"

    private def describeResult(label: String, result: Result[Any, Any]): String =
        result match
            case Result.Success(v)            => s"SUCCESS — value=$v"
            case Result.Failure(e: Throwable) => describeFailure(label, e)
            case Result.Failure(other)        => s"FAILURE — non-throwable error=$other"
            case Result.Panic(t)              => describeFailure(label, t) + " (panic)"

    private def runAndLog[A](label: String)(eff: A < (Async & Abort[SqlException])): Unit < (Async & Sync) =
        Abort.run[SqlException](eff).map { result =>
            logProbe(label, describeResult(label, result))
        }

    // ── PG probes ────────────────────────────────────────────────────────────

    "Postgres default-user privilege probes" in {
        val pgConfig = ContainerPredef.Postgres.Config.default
        for
            _ <- logProbe(
                "PG.config",
                s"image=${pgConfig.image}, user=${pgConfig.username}, password=${pgConfig.password}, " +
                    s"database=${pgConfig.database}, port=${pgConfig.port}"
            )
            _ <- Async.timeout(120.seconds) {
                Scope.run {
                    ContainerPredef.Postgres.initWith(pgConfig) { pg =>
                        pg.container.mappedPort(pg.config.port).flatMap { port =>
                            val probeDb = "kyo_probe_db"
                            // Probe 1: PG default user can CREATE DATABASE foo?
                            PostgresConnection.connect(
                                pg.container.host,
                                port,
                                pg.username,
                                pg.database,
                                Present(pg.password),
                                Absent,
                                64,
                                Duration.Infinity,
                                EncodingRegistry.builtin
                            ).flatMap { conn =>
                                Scope.ensure(Abort.run(conn.terminate).unit).andThen {
                                    runAndLog("PG.default-user CREATE DATABASE")(
                                        conn.simpleExecute(s"CREATE DATABASE $probeDb")
                                    )
                                }
                            }.andThen {
                                // Probe 4 (PG): fresh connection as test user authenticates to fresh DB
                                Abort.run[SqlException](
                                    PostgresConnection.connect(
                                        pg.container.host,
                                        port,
                                        pg.username,
                                        probeDb,
                                        Present(pg.password),
                                        Absent,
                                        64,
                                        Duration.Infinity,
                                        EncodingRegistry.builtin
                                    ).flatMap { conn2 =>
                                        Scope.ensure(Abort.run(conn2.terminate).unit).andThen {
                                            // Sanity check round-trip after auth.
                                            conn2.simpleQuery("SELECT 1").map(_ => "auth-and-select-ok")
                                        }
                                    }
                                ).map { result =>
                                    logProbe(
                                        "PG.default-user authenticates to fresh DB",
                                        describeResult("PG.default-user authenticates to fresh DB", result)
                                    )
                                }
                            }.andThen {
                                // Cleanup: drop the probe DB so the container is in a clean state.
                                PostgresConnection.connect(
                                    pg.container.host,
                                    port,
                                    pg.username,
                                    pg.database,
                                    Present(pg.password),
                                    Absent,
                                    64,
                                    Duration.Infinity,
                                    EncodingRegistry.builtin
                                ).flatMap { dropConn =>
                                    Scope.ensure(Abort.run(dropConn.terminate).unit).andThen {
                                        Abort.run[SqlException](
                                            dropConn.simpleExecute(s"DROP DATABASE IF EXISTS $probeDb")
                                        ).map { res =>
                                            logProbe(
                                                "PG.cleanup DROP DATABASE",
                                                describeResult("PG.cleanup DROP DATABASE", res)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        yield succeed
        end for
    }

    // ── MySQL probes ─────────────────────────────────────────────────────────

    "MySQL default-user / root / GRANT probes" in {
        val myConfig = ContainerPredef.MySQL.Config.default
        for
            _ <- logProbe(
                "MySQL.config",
                s"image=${myConfig.image}, user=${myConfig.username}, password=${myConfig.password}, " +
                    s"database=${myConfig.database}, rootPassword=${myConfig.rootPassword}, port=${myConfig.port}"
            )
            _ <- Async.timeout(180.seconds) {
                Scope.run {
                    // We need the mysql_native_password plugin so the test user (using non-default auth) can connect at all
                    // — the rest of kyo-sql tests use the same flag (see SqlDbTest.scala:280).
                    val myContainerConfig = ContainerPredef.MySQL.buildContainerConfig(myConfig)
                        .command("--default-authentication-plugin=mysql_native_password")
                    Container.initWith(myContainerConfig) { myContainer =>
                        val my = new ContainerPredef.MySQL(myContainer, myConfig)
                        my.container.mappedPort(my.config.port).flatMap { port =>
                            val probeDbDefault = "kyo_probe_default"
                            val probeDbRoot    = "kyo_probe_root"
                            val probeDbGrant   = "kyo_probe_grant"

                            // Probe 2: MySQL default user can CREATE DATABASE foo?
                            MysqlConnection.connect(
                                my.container.host,
                                port,
                                my.username,
                                Present(my.password),
                                Present(my.database),
                                Absent,
                                64,
                                Duration.Infinity
                            ).flatMap { conn =>
                                Scope.ensure(conn.close).andThen {
                                    runAndLog("MySQL.default-user CREATE DATABASE")(
                                        conn.simpleExecute(s"CREATE DATABASE $probeDbDefault")
                                    )
                                }
                            }.andThen {
                                // Probe 3: MySQL root can CREATE DATABASE?
                                MysqlConnection.connect(
                                    my.container.host,
                                    port,
                                    "root",
                                    Present(my.config.rootPassword),
                                    Absent, // root has no default DB selected
                                    Absent,
                                    64,
                                    Duration.Infinity
                                ).flatMap { rootConn =>
                                    Scope.ensure(Abort.run(rootConn.quit()).andThen(rootConn.close)).andThen {
                                        runAndLog("MySQL.root CREATE DATABASE")(
                                            rootConn.simpleExecute(s"CREATE DATABASE $probeDbRoot")
                                        )
                                    }
                                }
                            }.andThen {
                                // Probe 4 (MySQL): fresh connection as test user authenticates to fresh DB created by root
                                Abort.run[SqlException](
                                    MysqlConnection.connect(
                                        my.container.host,
                                        port,
                                        my.username,
                                        Present(my.password),
                                        Present(probeDbRoot),
                                        Absent,
                                        64,
                                        Duration.Infinity
                                    ).flatMap { c =>
                                        Scope.ensure(Abort.run(c.quit()).andThen(c.close)).andThen {
                                            c.simpleQuery("SELECT 1").map(_ => "auth-and-select-ok")
                                        }
                                    }
                                ).map { res =>
                                    logProbe(
                                        "MySQL.default-user authenticates to root-created DB (no GRANT)",
                                        describeResult(
                                            "MySQL.default-user authenticates to root-created DB (no GRANT)",
                                            res
                                        )
                                    )
                                }
                            }.andThen {
                                // Probe 5: MySQL root can GRANT and then default user can authenticate to GRANTed DB
                                MysqlConnection.connect(
                                    my.container.host,
                                    port,
                                    "root",
                                    Present(my.config.rootPassword),
                                    Absent,
                                    Absent,
                                    64,
                                    Duration.Infinity
                                ).flatMap { rootConn =>
                                    Scope.ensure(Abort.run(rootConn.quit()).andThen(rootConn.close)).andThen {
                                        // Create DB then GRANT
                                        runAndLog("MySQL.root CREATE DATABASE (for GRANT probe)")(
                                            rootConn.simpleExecute(s"CREATE DATABASE $probeDbGrant")
                                        ).andThen {
                                            runAndLog(s"MySQL.root GRANT ALL ON $probeDbGrant.* TO '${my.username}'@'%'")(
                                                rootConn.simpleExecute(
                                                    s"GRANT ALL ON $probeDbGrant.* TO '${my.username}'@'%'"
                                                )
                                            )
                                        }.andThen {
                                            runAndLog("MySQL.root FLUSH PRIVILEGES")(
                                                rootConn.simpleExecute("FLUSH PRIVILEGES")
                                            )
                                        }
                                    }
                                }.andThen {
                                    // Now reconnect as the test user, targeting the GRANTed DB
                                    Abort.run[SqlException](
                                        MysqlConnection.connect(
                                            my.container.host,
                                            port,
                                            my.username,
                                            Present(my.password),
                                            Present(probeDbGrant),
                                            Absent,
                                            64,
                                            Duration.Infinity
                                        ).flatMap { c2 =>
                                            Scope.ensure(Abort.run(c2.quit()).andThen(c2.close)).andThen {
                                                c2.simpleQuery("SELECT 1").map(_ => "auth-and-select-ok")
                                            }
                                        }
                                    ).map { res =>
                                        logProbe(
                                            "MySQL.default-user authenticates after GRANT",
                                            describeResult(
                                                "MySQL.default-user authenticates after GRANT",
                                                res
                                            )
                                        )
                                    }
                                }
                            }.andThen {
                                // Cleanup: drop probe DBs so the container is in a clean state.
                                MysqlConnection.connect(
                                    my.container.host,
                                    port,
                                    "root",
                                    Present(my.config.rootPassword),
                                    Absent,
                                    Absent,
                                    64,
                                    Duration.Infinity
                                ).flatMap { rootConn =>
                                    Scope.ensure(Abort.run(rootConn.quit()).andThen(rootConn.close)).andThen {
                                        Abort.run[SqlException](
                                            rootConn.simpleExecute(s"DROP DATABASE IF EXISTS $probeDbDefault")
                                        ).map { res =>
                                            logProbe(
                                                s"MySQL.cleanup DROP DATABASE $probeDbDefault",
                                                describeResult(s"MySQL.cleanup DROP DATABASE $probeDbDefault", res)
                                            )
                                        }.andThen {
                                            Abort.run[SqlException](
                                                rootConn.simpleExecute(s"DROP DATABASE IF EXISTS $probeDbRoot")
                                            ).map { res =>
                                                logProbe(
                                                    s"MySQL.cleanup DROP DATABASE $probeDbRoot",
                                                    describeResult(s"MySQL.cleanup DROP DATABASE $probeDbRoot", res)
                                                )
                                            }
                                        }.andThen {
                                            Abort.run[SqlException](
                                                rootConn.simpleExecute(s"DROP DATABASE IF EXISTS $probeDbGrant")
                                            ).map { res =>
                                                logProbe(
                                                    s"MySQL.cleanup DROP DATABASE $probeDbGrant",
                                                    describeResult(s"MySQL.cleanup DROP DATABASE $probeDbGrant", res)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        yield succeed
        end for
    }

end PrivilegeProbeIntegrationTest
