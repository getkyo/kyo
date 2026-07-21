package kyo

import kyo.*
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** Integration tests for `SqlClient.withAdvisoryLock`.
  *
  * Tests run against real PG and MySQL containers via [[SqlSharedContainers.withFreshSchema]]. Each leaf calls `withAdvisoryLock(key) { body
  * }` which pins one connection from the pool for the duration of `body`, so the acquire and release SQL always target the same session
  * (advisory locks are session-scoped on both PG and MySQL). Nested `SqlClient` queries inside `body` route to the pinned connection via
  * [[SqlClient.txLocal]].
  *
  * Tests:
  *   1. PG withAdvisoryLock releases on body completion; a subsequent try-lock succeeds because the lock is free.
  *   2. MySQL withAdvisoryLock releases on body completion; a subsequent GET_LOCK(0) succeeds because the lock is free.
  *   3. PG: a concurrent client on a different session sees pg_try_advisory_lock return false while the body is running.
  *   4. MySQL: a concurrent client sees GET_LOCK(0) return 0 while the body is running.
  */
class SqlClientAdvisoryLockTest extends kyo.Test:

    override def timeout: Duration = 5.minutes

    // ── Helpers ──────────────────────────────────────────────────────────────

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    // ── Leaf 1: PG, lock released on withAdvisoryLock body completion ────────

    "withAdvisoryLock on PG releases the lock when the body completes" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx)).flatMap { client =>
                    SqlClient.let(client) {
                        val key = 42L
                        client.withAdvisoryLock(key) {
                            (): Unit < (Async & Abort[SqlException])
                        }.andThen {
                            // Lock is released; a second lock attempt (via pg_try_advisory_lock)
                            // must observe the key as free.
                            client.query(s"SELECT pg_try_advisory_lock($key)::text").flatMap { rows =>
                                assert(rows.size == 1, s"Expected 1 row from pg_try_advisory_lock, got ${rows.size}")
                                val raw = rows(0).column(0)
                                val txt = raw match
                                    case Present(bytes) => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    case Absent         => "null"
                                client.executeRaw(s"SELECT pg_advisory_unlock($key)").map { _ =>
                                    assert(
                                        txt == "t" || txt == "true" || txt == "1",
                                        s"Expected pg_try_advisory_lock to return true (lock should be free after body), got: '$txt'"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 2: MySQL, GET_LOCK acquired; RELEASE_LOCK fires on body exit ──

    "withAdvisoryLock on MySQL releases the lock when the body completes" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                SqlClient.initMysql(myUrl(ctx)).flatMap { client =>
                    SqlClient.let(client) {
                        val key = 99L
                        client.withAdvisoryLock(key) {
                            (): Unit < (Async & Abort[SqlException])
                        }.andThen {
                            // Simple query returns text; "1" if lock is free.
                            // Cast the BIGINT to CHAR so the text bytes come back regardless of whether
                            // the client is on the binary (extended) or text (simple) protocol; the
                            // decoded row is a "1"/"0" ASCII string in both cases.
                            client.query(s"SELECT CAST(GET_LOCK('$key', 0) AS CHAR)").flatMap { rows =>
                                assert(rows.size == 1, s"Expected 1 row from GET_LOCK probe, got ${rows.size}")
                                rows(0).decode[String](0).flatMap { got =>
                                    client.executeRaw(s"SELECT RELEASE_LOCK('$key')").map { _ =>
                                        assert(got == "1", s"Expected GET_LOCK to return 1 (lock free after body), got: $got")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 3: PG, pg_try_advisory_lock returns false while body runs ──

    "withAdvisoryLock on PG blocks a concurrent pg_try_advisory_lock on the same key" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx)).flatMap { outerClient =>
                    SqlClient.let(outerClient) {
                        val key = 7654321L
                        outerClient.withAdvisoryLock(key) {
                            // Inside the body: the outer connection holds the lock.
                            // A separate SqlClient (fresh pool, new session) must observe the lock held.
                            SqlClient.init(pgUrl(ctx)).flatMap { innerClient =>
                                SqlClient.let(innerClient) {
                                    innerClient.query(s"SELECT pg_try_advisory_lock($key)").flatMap { rows =>
                                        assert(rows.size == 1)
                                        rows(0).decode[Boolean](0).map { acquired =>
                                            assert(
                                                !acquired,
                                                s"Expected pg_try_advisory_lock to return false (lock held by outer session), got: '$acquired'"
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
    }

    // ── Leaf 4: MySQL, GET_LOCK(0) returns 0 while body runs ──

    "withAdvisoryLock on MySQL blocks a concurrent GET_LOCK on the same key" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                SqlClient.initMysql(myUrl(ctx)).flatMap { outerClient =>
                    SqlClient.let(outerClient) {
                        val key = 1122334455L
                        outerClient.withAdvisoryLock(key) {
                            SqlClient.initMysql(myUrl(ctx)).flatMap { innerClient =>
                                SqlClient.let(innerClient) {
                                    innerClient.query(s"SELECT CAST(GET_LOCK('$key', 0) AS CHAR)").flatMap { rows =>
                                        assert(rows.size == 1)
                                        rows(0).decode[String](0).map { got =>
                                            assert(
                                                got == "0",
                                                s"Expected GET_LOCK to return 0 (lock held by outer connection), got: $got"
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
    }

end SqlClientAdvisoryLockTest
