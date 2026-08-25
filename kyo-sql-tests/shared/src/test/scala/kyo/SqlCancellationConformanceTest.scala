package kyo

import kyo.internal.SqlTestBackend

/** Backend-agnostic conformance for cancellation and session reclaim, run once per available backend through [[SqlBackendTest]].
  *
  * The property under test is the pool-visible half of the reclaim contract ([[kyo.db.Connection]] `inFlight` / `cancelInFlight` /
  * `drainToIdle`, `Connection.scala:160-185`): interrupting the fiber that holds an in-flight statement must leave the session POOLABLE, not
  * destroyed and leaked. A session is poolable here when three engine-free observables hold together: a fresh statement issued afterwards
  * succeeds against the reclaimed connection, the interrupted lease's slot permit returns to the pool, and no connection is discarded over the
  * interrupt.
  *
  * Everything observed is a pool-level signal shared by every backend (`slotPermits` / `slotCapacity`, `connectionsDiscarded`,
  * `cancelsTimedOut`), so this suite names no engine and asserts no engine-specific counter. Whether the reclaim drains the remaining rows or
  * fires a wire cancel is an engine choice the per-engine cancel suites pin against server counters; the outcome, that the session comes back
  * reusable, is the cross-engine contract pinned here.
  *
  * The in-flight statement is a stream parked on its first row rather than an engine-specific sleep call: streaming a table with a batch of one
  * leaves the cursor open with rows still server-side, which raises the in-flight flag on every backend without naming an engine sleep builtin.
  * The interrupt is driven by a latch, not a timed wait: the consumer releases the latch as it parks on the first row, the driver awaits that
  * release, then interrupts, so the interrupt always lands on an established in-flight session. The reclaim is awaited without a poll: the pool
  * is pinned to one slot, so the follow-up query cannot acquire until the reclaim has handed the permit back, which makes its success the
  * deterministic proof the reclaim finished.
  */
class SqlCancellationConformanceTest extends SqlBackendTest:

    // maxConnections = 1 makes a leaked permit fatal and the reclaim observable: with a single slot, the follow-up
    // query blocks until the reclaim returns the permit, so its success is a race-free barrier and a lost permit would
    // strand it. The metrics scope is engine-free and names only this suite, so its counters are written here alone.
    private val cancelConfig = SqlConfig(
        maxConnections = 1,
        minConnections = 1,
        acquireTimeout = 10.seconds,
        cancelTimeout = 2.seconds,
        metricsScope = Present("kyo.sql.conformance.cancellation")
    )

    "interrupting an in-flight statement returns the session poolable" - {
        forEachBackend(cancelConfig) { (backend, client, _) =>
            val bigint   = backend.columnType(SqlTestBackend.ColumnType.BigInt)
            val rowCount = 200
            val values   = (1 to rowCount).map(i => s"($i)").mkString(", ")
            for
                _               <- client.executeRaw(s"CREATE TABLE cancel_probe (id $bigint PRIMARY KEY)")
                _               <- client.executeRaw(s"INSERT INTO cancel_probe VALUES $values")
                discardedBefore <- client.runtime.pool.metrics.connectionsDiscarded.get
                timedOutBefore  <- client.runtime.pool.metrics.cancelsTimedOut.get
                _ <-
                    Latch.initWith(1) { started =>
                        // The consumer holds the session in-flight: it takes the first row, signals the driver, then parks
                        // forever. The open cursor with rows still server-side is what raises the in-flight flag the reclaim
                        // reacts to. Batch size one guarantees rows remain unfetched behind the first.
                        val consume =
                            Scope.run {
                                client.streamQuery(Sql.Fragment.lit[Any]("SELECT id FROM cancel_probe"), 1).foreach { _ =>
                                    started.release.andThen(Async.never)
                                }
                            }
                        Fiber.initUnscoped(Abort.run[SqlException](consume)).flatMap { fiber =>
                            // Deterministic interrupt: await the parked-on-first-row signal, so the interrupt lands on an
                            // established in-flight session rather than racing the statement's start.
                            started.await.andThen {
                                fiber.interrupt.flatMap { interrupted =>
                                    assert(interrupted, "the fiber holding an in-flight statement must be interruptible")
                                    // The follow-up query cannot acquire the pool's single permit until the reclaim hands
                                    // it back, so reaching a correct row is the race-free proof the session came back
                                    // reusable rather than destroyed and leaked.
                                    client.query("SELECT id FROM cancel_probe WHERE id = 1").flatMap(oneLong).flatMap { id =>
                                        assert(id == 1L, s"a query on the reclaimed pool must succeed and return 1, got $id")
                                        permits(client).flatMap { case (available, capacity) =>
                                            client.runtime.pool.metrics.connectionsDiscarded.get.flatMap { discardedAfter =>
                                                client.runtime.pool.metrics.cancelsTimedOut.get.map { timedOutAfter =>
                                                    assert(
                                                        available == capacity,
                                                        s"the interrupted lease's permit must return to the pool, got $available of $capacity"
                                                    )
                                                    assert(
                                                        discardedAfter - discardedBefore == 0L,
                                                        s"the interrupted session must be reclaimed, not discarded (discarded delta ${discardedAfter - discardedBefore})"
                                                    )
                                                    assert(
                                                        timedOutAfter - timedOutBefore == 0L,
                                                        s"the reclaim must finish inside the cancel budget (timed-out delta ${timedOutAfter - timedOutBefore})"
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            yield ()
            end for
        }
    }

    /** Permits available in the client's single slot channel paired with the capacity they return to when every lease resolves.
      *
      * A missing channel fails the leaf rather than reading as a number, because a default would make a pool no statement ever reached look
      * like a pool whose permits all returned.
      */
    private def permits(client: SqlClient)(using Frame, kyo.test.AssertScope): (Int, Int) < Sync =
        Sync.Unsafe.defer {
            // Unsafe: both accessors read a slot Channel's size, the same way the pool's own observability accessors do.
            (client.runtime.pool.slotPermits(client.url.address), client.runtime.pool.slotCapacity(client.url.address)) match
                case (Present(available), Present(capacity)) => (available, capacity)
                case _ => fail(s"no slot channel exists for ${client.url.address}, so no statement ever reached the pool")
        }

    private def oneLong(rows: Chunk[SqlRow])(using Frame): Long < Abort[SqlException] =
        rows.headMaybe match
            case Absent       => Abort.panic(new IllegalStateException("expected exactly one row but got none"))
            case Present(row) => Abort.recover((e: SqlDecodeException) => Abort.fail(e: SqlException))(row.decode[Long](0))

end SqlCancellationConformanceTest
