package kyo

import kyo.*
import kyo.SqlClient.Metrics

/** Conformance test for client metrics via `kyo.Stat`, run once per available backend.
  *
  * Metrics are engine-neutral: the counters and histograms live on the backend-agnostic connection pool, so a run of ordinary queries moves
  * them the same way on every engine. The body runs three successful queries and one that fails, then asserts the exact movement of every
  * counter and histogram. Exact rather than above-zero because the instrumented unit is the lease and the four statements take four of them:
  * a leaked lease, a double-count, or a release that silently became a discard all read as a wrong number here, and every one of them
  * passes a `> 0` check.
  *
  * Exact readings need the instruments to belong to this suite alone, which is what `metricsScope` below is for. A `Metrics` does not own
  * its instruments: it asks the `Stat` registry for them by scope path, and the registry memoizes, so every client sharing a scope shares
  * one set of counters. `SqlConfig.metricsEnabled` defaults to TRUE and `metricsScope` to the default `"kyo.sql"`, so on the default scope
  * every other suite's client in this JVM would be incrementing what this leaf reads. Even inside the private scope the two backend leaves
  * still share it, so the counters are drained to zero before the run (`Counter.get` is a destructive `sumThenReset`) and the histograms,
  * which are not destructive, are read as a delta against a baseline taken at the same point.
  *
  * The queries are ad-hoc scalar `SELECT`s issued through `client.query`, the instrumented statement entry point a caller uses, because this
  * suite exercises that instrumentation directly. The one failing statement (`executeRaw`) has no typed surface: forcing a query to fail is
  * what drives `queries_failed`. A real connection-level retry needs a mid-run server drop, which is flaky in CI, so `retries_attempted` is
  * driven through `recordRetry`.
  *
  * After the counter assertions, one probe query decodes a concrete value to prove the metric-bearing pool still serves a correct row.
  */
class SqlMetricsConformanceTest extends SqlBackendTest:

    private val metricsConfig = SqlConfig(
        maxConnections = 5,
        acquireTimeout = 15.seconds,
        queryTimeout = 15.seconds,
        idleTimeout = 10.minutes,
        retrySchedule = Present(Schedule.fixed(Duration.Zero).take(1)),
        metricsEnabled = true,
        metricsScope = Present("kyo.sql.metrics-conformance")
    )

    forEachBackend(metricsConfig) { (_, client, _) =>
        val m: Metrics = client.runtime.pool.metrics
        for
            // Resolve the server version before zeroing the baseline. It is fetched lazily on the first render, through a
            // pooled lease, so leaving it until the measured window would charge that one-time internal probe to
            // queries_executed and connections_acquired. Triggering it here folds it into the setup the baseline drain
            // discards, so the window below contains only the leaf's own user statements.
            _ <- client.serverVersion
            // Zero the baseline. Draining each counter is the read itself, since `Counter.get` resets; the two
            // histograms keep their observations, so what they recorded before this leaf is subtracted below.
            _     <- m.queriesExecuted.get
            _     <- m.queriesFailed.get
            _     <- m.connectionsAcquired.get
            _     <- m.connectionsReleased.get
            _     <- m.retriesAttempted.get
            qds0  <- m.queryDurationSummary
            paws0 <- m.poolAcquireWaitSummary
            // Several successful queries exercise queries_executed, query_duration_ms, connections_acquired,
            // connections_released, and pool_acquire_wait_ms.
            _ <- client.query("SELECT 1")
            _ <- client.query("SELECT 2")
            _ <- client.query("SELECT 3")
            // A syntactically invalid statement drives queries_failed. Forcing a failure has no typed surface.
            _ <- Abort.run[SqlException](client.executeRaw("INVALID SQL THAT WILL FAIL"))
            // A real retry needs a connection-level failure mid-run (a server restart), which is flaky in CI,
            // so retries_attempted is driven directly.
            _    <- m.recordRetry
            qe   <- m.queriesExecuted.get
            qf   <- m.queriesFailed.get
            ca   <- m.connectionsAcquired.get
            cr   <- m.connectionsReleased.get
            ra   <- m.retriesAttempted.get
            qds  <- m.queryDurationSummary
            paws <- m.poolAcquireWaitSummary
            // Three statements succeeded and one failed, and a statement the server itself rejected leaves the session
            // idle, so all four connections went back to the ring rather than being discarded. The retry schedule is
            // typed to SqlConnectionException, so the rejected statement is not retried and recordRetry above is the
            // only thing that moved retries_attempted.
            _ = assert(qe == 3L, s"queries_executed should be 3, got $qe")
            _ = assert(qf == 1L, s"queries_failed should be 1, got $qf")
            _ = assert(ca == 4L, s"connections_acquired should be 4 (one lease per statement), got $ca")
            _ = assert(cr == 4L, s"connections_released should be 4 (one lease per statement), got $cr")
            _ = assert(ra == 1L, s"retries_attempted should be 1, got $ra")
            _ = assert(
                qds.count - qds0.count == 4L,
                s"query_duration_ms should gain 4 observations (one per lease), gained ${qds.count - qds0.count}"
            )
            _ = assert(
                paws.count - paws0.count == 4L,
                s"pool_acquire_wait_ms should gain 4 observations (one per lease), gained ${paws.count - paws0.count}"
            )
            // Probe reusability by value: a connection that answers with the wrong row is not reusable, and
            // non-emptiness cannot see that.
            rows <- client.query("SELECT 42")
            _ = assert(rows.size == 1, s"the probe query must return exactly one row, got ${rows.size}")
            v <- rows(0).decode[Int](0)
            _ = assert(v == 42, s"the probe query must return 42, got $v")
        yield ()
        end for
    }

end SqlMetricsConformanceTest
