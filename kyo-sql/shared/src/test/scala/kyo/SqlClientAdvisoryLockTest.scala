package kyo

import kyo.*
import kyo.internal.SqlSharedContainers
import kyo.internal.SqlSharedContainers.Backend

/** Integration tests for SqlClient.advisoryLock.
  *
  * Tests run against real PG and MySQL containers via [[SqlSharedContainers.withFreshSchema]]. Each test leaf uses [[Scope.run]] so the
  * advisory lock's [[Scope.ensure]] release finaliser always fires before the assertion executes.
  *
  * Tests:
  *   1. advisoryLock on PG — lock is held while scope is active, released on scope exit.
  *   2. advisoryLock on MySQL — GET_LOCK succeeds; RELEASE_LOCK fires on scope exit.
  *   3. PG: concurrent second client cannot acquire the same session lock while held (pg_try_advisory_lock returns false).
  *   4. MySQL: concurrent second client times out (timeout = 0s) while first client holds the lock.
  */
class SqlClientAdvisoryLockTest extends kyo.Test:

    override def timeout: Duration = 5.minutes

    // ── Helpers ──────────────────────────────────────────────────────────────

    private def pgUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"postgres://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    private def myUrl(ctx: SqlSharedContainers.SchemaCtx): String =
        s"mysql://${ctx.username}:${ctx.password}@${ctx.host}:${ctx.port}/${ctx.database}"

    // ── Leaf 1: PG — lock released on Scope exit ──────────────────────────

    "advisoryLock on PG is released when the enclosing Scope exits" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx)).flatMap { client =>
                    SqlClient.let(client) {
                        val key = 42L
                        // Acquire the lock in a nested scope; the scope exits before we probe.
                        Scope.run {
                            client.advisoryLock(key)
                            // while scope is active the lock is held — no assertion needed here
                        }.andThen {
                            // After the scope exits the lock must have been released.
                            // pg_try_advisory_lock returns true if we can acquire it (i.e. it is free).
                            client.query(s"SELECT pg_try_advisory_lock($key)::text").map { rows =>
                                assert(rows.size == 1, s"Expected 1 row from pg_try_advisory_lock, got ${rows.size}")
                                val raw = rows(0).column(0)
                                val txt = raw match
                                    case Present(bytes) => new String(bytes.toArray, java.nio.charset.StandardCharsets.UTF_8)
                                    case Absent         => "null"
                                // Release the lock we just acquired via pg_try_advisory_lock.
                                client.executeRaw(s"SELECT pg_advisory_unlock($key)").map { _ =>
                                    assert(
                                        txt == "t" || txt == "true" || txt == "1",
                                        s"Expected pg_try_advisory_lock to return true (lock should be free), got: '$txt'"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 2: MySQL — GET_LOCK acquired; RELEASE_LOCK fires on Scope exit ──

    "advisoryLock on MySQL is released when the enclosing Scope exits" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                SqlClient.initMy(myUrl(ctx)).flatMap { client =>
                    SqlClient.let(client) {
                        val key = 99L
                        // Acquire the lock in a nested scope.
                        Scope.run {
                            client.advisoryLock(key)
                        }.andThen {
                            // After scope exit the lock must be free.
                            // GET_LOCK with timeout=0 returns 1 immediately if the lock is free, 0 if held.
                            client.query(s"SELECT GET_LOCK('$key', 0)").flatMap { rows =>
                                assert(rows.size == 1, s"Expected 1 row from GET_LOCK probe, got ${rows.size}")
                                // `client.query` returns binary-encoded MySQL BIGINT for `GET_LOCK`; decode the
                                // typed Long rather than compare raw wire bytes to the ASCII "1".
                                rows(0).decode[Long](0).flatMap { got =>
                                    // Release the probe lock we just acquired.
                                    client.executeRaw(s"SELECT RELEASE_LOCK('$key')").map { _ =>
                                        assert(got == 1L, s"Expected GET_LOCK to return 1 (lock free after Scope exit), got: $got")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Leaf 3: PG — pg_try_advisory_lock returns false while lock is held ──

    "PG: concurrent advisoryLock blocks a second pg_try_advisory_lock on the same key" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.Postgres) { ctx =>
                SqlClient.init(pgUrl(ctx)).flatMap { outerClient =>
                    SqlClient.let(outerClient) {
                        val key = 7654321L
                        // Acquire in an outer scope and probe while it is still held.
                        Scope.run {
                            outerClient.advisoryLock(key).andThen {
                                // pg_try_advisory_lock on a *separate* connection should return false.
                                // We open a second SqlClient to get a different session.
                                SqlClient.init(pgUrl(ctx)).flatMap { innerClient =>
                                    SqlClient.let(innerClient) {
                                        innerClient.query(s"SELECT pg_try_advisory_lock($key)").flatMap { rows =>
                                            assert(rows.size == 1)
                                            // Typed decode via Phase 19b row.decode[T] — handles both binary
                                            // (extended protocol via client.query) and text format for Boolean.
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
    }

    // ── Leaf 4: MySQL — GET_LOCK with timeout=0 fails while lock is held ──

    "MySQL: GET_LOCK with timeout=0 returns 0 while another connection holds the lock" in {
        Scope.run {
            SqlSharedContainers.withFreshSchema(Backend.MySQL) { ctx =>
                SqlClient.initMy(myUrl(ctx)).flatMap { outerClient =>
                    SqlClient.let(outerClient) {
                        val key = 1122334455L
                        Scope.run {
                            outerClient.advisoryLock(key).andThen {
                                // Probe from a second connection with timeout=0.
                                SqlClient.initMy(myUrl(ctx)).flatMap { innerClient =>
                                    SqlClient.let(innerClient) {
                                        innerClient.query(s"SELECT GET_LOCK('$key', 0)").flatMap { rows =>
                                            assert(rows.size == 1)
                                            // Binary BIGINT, decode typed rather than compare wire bytes to ASCII.
                                            rows(0).decode[Long](0).map { got =>
                                                assert(
                                                    got == 0L,
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
    }

end SqlClientAdvisoryLockTest
