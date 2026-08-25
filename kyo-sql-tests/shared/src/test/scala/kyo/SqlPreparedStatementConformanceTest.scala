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

    private def oneLong(rows: Chunk[SqlRow])(using Frame): Long < Abort[SqlException] =
        rows.headMaybe match
            case Absent       => Abort.panic(new IllegalStateException("expected exactly one row but got none"))
            case Present(row) => Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(row.decode[Long](0))

end SqlPreparedStatementConformanceTest
