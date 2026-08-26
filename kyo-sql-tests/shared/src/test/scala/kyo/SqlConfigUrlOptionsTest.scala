package kyo

import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend
import kyo.net.Connection

/** Asserts that each option a URL declares reaches the code that applies it.
  *
  * [[SqlConfigUrlTest]] covers the parsing, and parsing is not the property at risk here: a value can round-trip through
  * [[SqlConfig.Url.parse]] perfectly and still be discarded on the way to the wire, which is what all three options below did. So every leaf
  * here observes the option's effect rather than its parse, either on a live server or in the payload of the failure it caused.
  *
  * The two timeout leaves assert the duration carried by the exception rather than how long the call took. A wall-clock assertion would be
  * flaky and would also pass for the wrong reason: what is under test is that the configured value reached the code that applies it, and the
  * value in the failure is direct evidence of that, where elapsed time is circumstantial.
  */
class SqlConfigUrlOptionsTest extends SqlContainerTest:

    override def timeout: Duration = 5.minutes

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx, query: String): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}$query"

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx, query: String): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}$query"

    private def text(row: SqlRow): String =
        row.column(0) match
            case Present(bytes) => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
            case Absent         => "null"

    // --- application_name ---

    "application_name from the URL is what the PG server reports for the session" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx, "?application_name=kyo-sql-options-test")).flatMap { client =>
                    DB.run(client) {
                        client.query("SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()").map { rows =>
                            assert(rows.size == 1, s"expected one row for the current backend, got ${rows.size}")
                            assert(
                                text(rows(0)) == "kyo-sql-options-test",
                                s"the server must report the application_name the URL declared, reported '${text(rows(0))}'"
                            )
                        }
                    }
                }
            }
        }
    }

    "a URL that declares no application_name leaves the driver's own name in place" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx, "")).flatMap { client =>
                    DB.run(client) {
                        client.query("SELECT application_name FROM pg_stat_activity WHERE pid = pg_backend_pid()").map { rows =>
                            assert(rows.size == 1)
                            assert(
                                text(rows(0)) == "kyo-sql",
                                s"the default must stay the driver's name rather than becoming empty, reported '${text(rows(0))}'"
                            )
                        }
                    }
                }
            }
        }
    }

    // --- connectTimeout ---

    "connectTimeout from the URL is the budget that bounds establishing a connection" in {
        // A server that accepts the TCP connection and never answers the startup message, which is the shape
        // `connectTimeout` exists for: the kernel completes the handshake, so nothing fails, and the client would
        // otherwise wait forever on a server that is up but not answering.
        val silent: Connection => Unit < Async =
            conn => Abort.run[Closed](conn.inbound.safe.take).unit
        Scope.run {
            kyo.internal.FakeServer.listenPort(silent).flatMap { listener =>
                val url = s"postgres://u:p@127.0.0.1:${listener.port}/db?connectTimeout=1"
                // acquireTimeout is deliberately the longer of the two, so a failure carrying 5 seconds would say
                // the URL's value never arrived and the fallback budget fired instead.
                val config = SqlConfig(maxConnections = 2, acquireTimeout = 5.seconds)
                SqlClient.initUnscoped(url, config).flatMap { client =>
                    Scope.ensure(Abort.run(client.close).unit).andThen {
                        Abort.run[SqlException](client.simpleQuery("SELECT 1")).map {
                            case Result.Failure(e: SqlConnectionEstablishTimeoutException) =>
                                assert(
                                    e.timeout == 1.second,
                                    s"establishment must be bounded by the URL's connectTimeout, was bounded by ${e.timeout}"
                                )
                                assert(e.port == listener.port)
                                // The failure names the knob that supplied the budget, which is the URL's here, and says
                                // that authentication shares it: this server answers the TCP connect and then nothing, so
                                // a message pointing only at reachability would send a reader to the wrong place.
                                assert(
                                    e.budgetSource == SqlConnectionEstablishTimeoutException.fromConnectTimeout,
                                    s"the URL's connectTimeout supplied the budget, message said: ${e.budgetSource}"
                                )
                                assert(
                                    e.message.contains("authentication handshake"),
                                    s"the failure must say the budget covers authentication too, got: ${e.message}"
                                )
                            case other =>
                                fail(s"expected establishment to time out, got $other")
                        }
                    }
                }
            }
        }
    }

    // --- socketTimeout ---

    "socketTimeout from the URL bounds a single read on an established PG connection" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                // The statement sleeps far longer than the read budget, so the read that waits for its response is
                // unambiguously what the budget cuts off.
                SqlClient.init(pgUrl(ctx, "?socketTimeout=1")).flatMap { client =>
                    DB.run(client) {
                        Abort.run[SqlException](client.simpleQuery("SELECT pg_sleep(5)")).map {
                            case Result.Failure(e: SqlConnectionSocketTimeoutException) =>
                                assert(
                                    e.socketTimeout == 1.second,
                                    s"the read must be bounded by the URL's socketTimeout, was bounded by ${e.socketTimeout}"
                                )
                            case other =>
                                fail(s"expected the read to time out, got $other")
                        }
                    }
                }
            }
        }
    }

    "socketTimeout from the URL bounds a single read on an established MySQL connection" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                MysqlClient.init(myUrl(ctx, "?socketTimeout=1")).flatMap { client =>
                    DB.run(client) {
                        Abort.run[SqlException](client.simpleQuery("SELECT SLEEP(5)")).map {
                            case Result.Failure(e: SqlConnectionSocketTimeoutException) =>
                                assert(
                                    e.socketTimeout == 1.second,
                                    s"the read must be bounded by the URL's socketTimeout, was bounded by ${e.socketTimeout}"
                                )
                            case other =>
                                fail(s"expected the read to time out, got $other")
                        }
                    }
                }
            }
        }
    }

    "a URL that declares no socketTimeout leaves reads unbounded, so a slow statement still completes" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                // The counterpart to the leaf above: the bound must be opt-in, or every long-running statement in
                // every existing program starts failing.
                SqlClient.init(pgUrl(ctx, "")).flatMap { client =>
                    DB.run(client) {
                        client.simpleQuery("SELECT pg_sleep(2), 'done' AS ok").map { rows =>
                            assert(rows.size == 1, s"the statement must run to completion, got ${rows.size} rows")
                        }
                    }
                }
            }
        }
    }

end SqlConfigUrlOptionsTest
