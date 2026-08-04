package kyo

import kyo.*

/** Conformance test for connection-pool warm-up (`minConnections`), run once per available backend.
  *
  * Warm-up is engine-neutral: it lives on the backend-agnostic connection pool, which opens `minConnections` connections during `init` and
  * releases them idle before the first statement runs. The behavior a caller can observe on any engine is that a client configured with
  * `minConnections` opens successfully and then serves that many queries concurrently, each returning its own correct value from a warmed
  * connection.
  *
  * The body fires `minConnections` queries at once (`Async.foreach` with matching concurrency), so the warmed set is exercised together rather
  * than one connection at a time. Each query decodes a distinct value and asserts it, which proves every warmed connection is usable and that
  * concurrent connections do not cross their results. A final probe confirms the pool still serves a correct row after the concurrent load.
  *
  * The exact count of warmed server-side sessions is NOT asserted here. Counting a client's own sessions means reading a server's own
  * session view, which each engine spells differently and exposes through no portable SQL and no descriptor capability, so it cannot be
  * expressed without naming an engine. This leaf asserts the portable warm-up contract; a per-engine session-count assertion belongs in the
  * module suites, or in this leaf once a descriptor exposes a portable session-count query.
  */
class SqlPoolWarmupConformanceTest extends SqlBackendTest:

    private val warmed = 5

    private val warmupConfig = SqlConfig(
        maxConnections = 10,
        minConnections = warmed,
        acquireTimeout = 10.seconds,
        queryTimeout = 10.seconds,
        idleTimeout = 10.minutes
    )

    forEachBackend(warmupConfig) { (_, client, _) =>
        // Fire minConnections queries concurrently so the warmed connections serve them together. Each returns
        // its own value; an ad-hoc scalar SELECT keeps the concurrent body free of the Scope a typed .run pulls
        // into its row (Scope is not isolated across fibers).
        Async.foreach(1 to warmed, warmed) { i =>
            client.query(s"SELECT $i").flatMap { rows =>
                assert(rows.size == 1, s"probe $i must return exactly one row, got ${rows.size}")
                rows(0).decode[Int](0).map { v =>
                    assert(v == i, s"probe $i must return $i, got $v")
                    i
                }
            }
        }.map { results =>
            assert(
                results == Chunk.from(1 to warmed),
                s"each of the $warmed concurrent probes must return its own value in order, got $results"
            )
        }.andThen {
            // The warmed pool must still serve a correct row after the concurrent load.
            client.query("SELECT 42").flatMap { rows =>
                assert(rows.size == 1, s"the reuse probe must return exactly one row, got ${rows.size}")
                rows(0).decode[Int](0).map { v =>
                    assert(v == 42, s"the reuse probe must return 42, got $v")
                }
            }
        }
    }

end SqlPoolWarmupConformanceTest
