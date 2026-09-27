package kyo

import kyo.internal.SqlConformanceBackends
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** End-to-end leaves whose subject is kyo's own client factories rather than a backend's behavior: `SqlClient.initWith` and
  * `SqlClient.initUnscoped` resolve the backend at the call site from the compile classpath, which only an in-repo run has, and the typed
  * `MysqlClient` factory is one engine's surface.
  */
class SqlEndToEndTest extends SqlContainerTest with SqlBackendTest:

    def backends: Seq[SqlConformanceBackend] = SqlConformanceBackends.all

    case class Person(id: Long, name: String, age: Int) derives SqlSchema, CanEqual

    // Which factory installs the ambient client, and what each close variant promises. Not an engine property, so it
    // runs per backend and adds what a single-engine form cannot state: every URL scheme reaches the same behaviour.

    "initWith(url)(f) creates a client, runs f, and registers Scope cleanup" - {
        forEachBackend() { (backend, _, schema) =>
            // initWith registers Scope.ensure(close); the query succeeds inside `f`.
            SqlClient.initWith(schema.url) { client =>
                DB.run(client) {
                    client.executeRaw(
                        s"CREATE TABLE person (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL, age INT NOT NULL)"
                    ).andThen {
                        client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                            .map(n => assert(n == 1L, s"${backend.label}: expected 1 affected row, got $n"))
                    }
                }
            }
        }
    }

    "Scope.run(initWith(url)(f)) gives bracket semantics, with no Scope in the effect set" - {
        forEachBackend() { (backend, _, schema) =>
            // initWith binds close to the enclosing Scope; running that Scope inline discharges it, so the ascription
            // below (no Scope in the row) is the assertion: close is still guaranteed, and the caller inherits no Scope
            // requirement.
            val bracketed: Unit < (Async & Abort[SqlException]) =
                Scope.run {
                    SqlClient.initWith(schema.url) { client =>
                        client.executeRaw(
                            s"CREATE TABLE person (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL, age INT NOT NULL)"
                        ).andThen {
                            client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                                .map(n => assert(n == 1L, s"${backend.label}: expected 1 affected row, got $n"))
                        }
                    }
                }
            bracketed
        }
    }

    "initUnscoped(url) creates a client with no cleanup; manual close completes without error" - {
        forEachBackend() { (backend, _, schema) =>
            // initUnscoped leaves cleanup to the caller; the client is closed manually below.
            SqlClient.initUnscoped(schema.url).flatMap { client =>
                DB.run(client) {
                    for
                        _ <- client.executeRaw(
                            s"CREATE TABLE person (id BIGINT PRIMARY KEY, name ${backend.textColumnType} NOT NULL, age INT NOT NULL)"
                        )
                        _ <- client.executeRaw("INSERT INTO person VALUES (1, 'alice', 30)")
                        // The write is read back before the manual close: an unscoped client that accepts a statement
                        // and never serves the row back is not a working client.
                        rows <- Sql.from[Person]("p").run
                        _    <- client.close
                    yield
                        assert(rows.size == 1, s"${backend.label}: expected 1 row, got ${rows.size}")
                        assert(rows.head == Person(1L, "alice", 30), s"${backend.label}: expected Person(1,alice,30), got ${rows.head}")
                }
            }
        }
    }

    "close(gracePeriod), close, and closeNow all complete without error" - {
        forEachBackend() { (_, _, schema) =>
            for
                // Three independent clients, one per close variant. Completion without error is the contract; that an
                // idle close returns within the grace period rather than at it needs a Clock seam to assert, not a
                // wall-clock bound.
                c1 <- SqlClient.initUnscoped(schema.url)
                _  <- c1.close(30.seconds)
                c2 <- SqlClient.initUnscoped(schema.url)
                _  <- c2.close
                c3 <- SqlClient.initUnscoped(schema.url)
                _  <- c3.closeNow
            yield succeed
        }
    }

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def withMyClient[A, S](
        ctx: SqlSharedContainers.SchemaCtx
    )(f: SqlClient => A < (S & Async & Abort[SqlException] & DB))(using
        Frame
    ): A < (S & Async & Scope & Abort[SqlException]) =
        Abort.run[SqlConnectionException](MysqlClient.init(myUrl(ctx))).flatMap {
            case Result.Success(client) =>
                Scope.ensure(client.close).andThen(DB.run(client)(f(client)))
            case Result.Failure(e) =>
                Abort.fail(e: SqlException)
            case Result.Panic(t) =>
                Abort.error(Result.Panic(t))
        }

    "a MySQL simpleQuery row decodes its text values, which share no representation with the binary protocol" in {
        // simpleQuery is public and documented for one-off SQL, and its rows are Format.Text: every value is its ASCII
        // rendering. The digits of 1234 parsed as a little-endian LONG are 875770417, and the ASCII 0 of a false boolean
        // is the nonzero byte 0x30.
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                withMyClient(ctx) { client =>
                    for
                        numeric <- client.simpleQuery("SELECT 1234")
                        n       <- numeric.head.decode[Int]
                        falsy   <- client.simpleQuery("SELECT 0")
                        f       <- falsy.head.decode[Boolean]
                        truthy  <- client.simpleQuery("SELECT 1")
                        t       <- truthy.head.decode[Boolean]
                    yield
                        assert(n == 1234, s"a text-protocol 1234 must decode as 1234, got $n")
                        assert(!f, "a text-protocol 0 must decode as false")
                        assert(t, "a text-protocol 1 must decode as true")
                }
            }
        }
    }

end SqlEndToEndTest
