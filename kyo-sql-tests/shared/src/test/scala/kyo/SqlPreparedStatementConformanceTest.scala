package kyo

import kyo.internal.SqlTestBackend

/** Backend-agnostic conformance for the prepared-statement cache, run once per available backend through [[SqlBackendTest]].
  *
  * The cache is a cross-engine feature home (`preparedStatementCacheSize` on [[kyo.SqlConfig]]): a parameterised statement is prepared once and
  * reused, and the cache is bounded so exceeding it evicts the least-recently-used entry. The observable contract of both behaviours is
  * TRANSPARENCY: caching and eviction must never change the rows a caller sees. Reuse must re-bind fresh parameters rather than replay the
  * first execution's, and an evicted statement must re-prepare on its next use rather than fail or return stale rows.
  *
  * This suite asserts that transparency through the typed API alone, on concrete values, so it names no engine and reads no server counter. The
  * mechanism, that the server actually holds one prepared statement per live cache slot and closes the rest, is engine-specific and stays in
  * the per-engine eviction suites, which count server-side statements through each engine's own catalog. The division is deliberate: those
  * suites prove eviction OCCURS, this one proves eviction stays CORRECT.
  *
  * Both leaves pin the pool to a single connection, because the cache is per connection: reuse and eviction are only observable when every
  * statement lands on the same session. Reuse keeps the SQL text constant (the cache key) and varies a bound parameter, so the second run is a
  * cache hit. Eviction varies the SQL text so each statement claims its own slot, overflowing a size-two cache.
  */
class SqlPreparedStatementConformanceTest extends SqlBackendTest:

    // A single connection so every statement shares one per-connection cache; without this a second connection would
    // start with an empty cache and neither reuse nor eviction would be observable.
    private val singleConn = SqlConfig(maxConnections = 1, minConnections = 1)

    // The reset leaf reads a counter, so it needs instruments no other suite shares: a `Metrics` does not own its
    // instruments, it asks the `Stat` registry for them by scope path and the registry memoizes.
    private val resetConn = singleConn.copy(
        preparedStatementCacheSize = 16,
        metricsEnabled = true,
        metricsScope = Present("kyo.sql.prepared-statement-conformance")
    )

    "a reused parameterised statement re-binds fresh parameters on each run" - {
        forEachBackend(singleConn.copy(preparedStatementCacheSize = 16)) { (backend, client, _) =>
            val bigint = backend.columnType(SqlTestBackend.ColumnType.BigInt)
            for
                _ <- client.executeRaw(s"CREATE TABLE ps_probe (id $bigint PRIMARY KEY, amount $bigint NOT NULL)")
                _ <- client.executeRaw("INSERT INTO ps_probe VALUES (1, 101), (2, 102), (3, 103)")
                // One parameterised statement, constant SQL text, run repeatedly with different bound ids. After the
                // first run every run is a cache hit; a cache that replayed the first binding would return 101 for
                // every id, so the per-id assertion pins re-binding.
                reuse <- Kyo.foreach(Chunk(1L, 2L, 3L, 1L, 3L, 2L, 2L, 1L)) { id =>
                    client.query(sql"SELECT amount FROM ps_probe WHERE id = $id").flatMap(oneLong).map(amount => (id, amount))
                }
                // Two distinct parameterised statements, both within the cache bound, interleaved: proof the cache holds
                // and dispatches more than one prepared statement at once, each re-binding its own parameter.
                interleaved <- Kyo.foreach(Chunk(1L, 2L, 3L)) { n =>
                    client.query(sql"SELECT amount FROM ps_probe WHERE id = $n").flatMap(oneLong).flatMap { amount =>
                        client.query(sql"SELECT id FROM ps_probe WHERE amount = $amount").flatMap(oneLong).map(id => (n, amount, id))
                    }
                }
            yield
                reuse.foreach { case (id, amount) =>
                    assert(amount == 100 + id, s"a reused statement must re-bind id=$id and return ${100 + id}, got $amount")
                }
                interleaved.foreach { case (n, amount, id) =>
                    assert(amount == 100 + n, s"statement A must return ${100 + n} for id=$n, got $amount")
                    assert(id == n, s"statement B must return id=$n for amount=$amount, got $id")
                }
            end for
        }
    }

    "an evicted statement re-prepares transparently while the cache churns" - {
        forEachBackend(singleConn.copy(preparedStatementCacheSize = 2)) { (backend, client, _) =>
            val bigint = backend.columnType(SqlTestBackend.ColumnType.BigInt)
            val n      = 6
            val values = (1 to n).map(i => s"($i, ${100 + i})").mkString(", ")
            for
                _ <- client.executeRaw(s"CREATE TABLE ps_probe (id $bigint PRIMARY KEY, amount $bigint NOT NULL)")
                _ <- client.executeRaw(s"INSERT INTO ps_probe VALUES $values")
                // n distinct SQL texts (the id is inline, so each is its own cache key) exceed the size-two cache, so
                // every statement past the second evicts the least-recently-used one. Each must still return its own
                // amount while the cache churns underneath.
                churn <- Kyo.foreach(Chunk.from(1 to n)) { k =>
                    client.query(s"SELECT amount FROM ps_probe WHERE id = $k").flatMap(oneLong).map(amount => (k, amount))
                }
                // The earliest statement was evicted long ago; re-running it must re-prepare transparently, not fail or
                // return a neighbour's rows.
                reEvicted <- client.query("SELECT amount FROM ps_probe WHERE id = 1").flatMap(oneLong)
                // The most-recent statement is still cached; it must stay correct too.
                stillCached <- client.query(s"SELECT amount FROM ps_probe WHERE id = $n").flatMap(oneLong)
            yield
                churn.foreach { case (k, amount) =>
                    assert(amount == 100L + k, s"a distinct statement id=$k must return ${100L + k} while the cache evicts, got $amount")
                }
                assert(reEvicted == 101L, s"an evicted statement must re-prepare transparently and return 101, got $reEvicted")
                assert(stillCached == 100L + n, s"a still-cached statement must return ${100L + n}, got $stillCached")
            end for
        }
    }

    "a cached statement still resolves after the session it was prepared on is reset" - {
        forEachBackend(resetConn) { (backend, client, _) =>
            val bigint = backend.columnType(SqlTestBackend.ColumnType.BigInt)
            val m      = client.runtime.pool.metrics
            for
                // Resolve the server version first, so its own internal lease lands in the setup rather than the
                // measured window.
                _ <- client.serverVersion
                _ <- client.executeRaw(s"CREATE TABLE ps_reset (id $bigint PRIMARY KEY, amount $bigint NOT NULL)")
                _ <- client.executeRaw("INSERT INTO ps_reset VALUES (1, 101), (2, 102)")
                // Caches the statement against the session the single-connection pool keeps handing out.
                before <- client.query(sql"SELECT amount FROM ps_reset WHERE id = ${1L}").flatMap(oneLong)
                // Zero the baseline; `Counter.get` is a destructive read.
                _ <- m.preparedStatementsReprepared.get
                // A scrub releases every server-side statement on the engines that have them, and runs on a lease
                // of its own, so the session returns to the pool and the next borrower meets the dead handle.
                // Here that borrower is this same test.
                _     <- client.reset
                after <- client.query(sql"SELECT amount FROM ps_reset WHERE id = ${1L}").flatMap(oneLong)
                // Read before the later queries, so it counts that one statement and no other.
                reprepares <- m.preparedStatementsReprepared.get
                // A second time, to catch a cache that healed by accident rather than by dropping the entry.
                other <- client.query(sql"SELECT amount FROM ps_reset WHERE id = ${2L}").flatMap(oneLong)
                again <- client.query(sql"SELECT amount FROM ps_reset WHERE id = ${1L}").flatMap(oneLong)
            yield
                assert(before == 101L, s"the statement must return 101 before the reset, got $before")
                assert(after == 101L, s"the same statement must still return 101 after the reset, got $after")
                assert(other == 102L, s"a second statement must return 102 on the reset session, got $other")
                assert(again == 101L, s"the re-prepared statement must stay usable, got $again")
                // The rows alone do not say WHY they are right. On an engine that recovers from the server's
                // complaint, a cache the reset failed to drop still answers correctly, one wasted round trip and
                // one counted re-prepare later. Dropping the entry makes that query an ordinary miss, so the
                // counter is what separates the fix from the fallback.
                assert(
                    reprepares == 0L,
                    s"the reset must drop the entry, leaving the next query a plain cache miss; it was recovered from instead ($reprepares)"
                )
            end for
        }
    }

    private def oneLong(rows: Chunk[SqlRow])(using Frame): Long < Abort[SqlException] =
        rows.headMaybe match
            case Absent       => Abort.panic(new IllegalStateException("expected exactly one row but got none"))
            case Present(row) => Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(row.decode[Long](0))

end SqlPreparedStatementConformanceTest
