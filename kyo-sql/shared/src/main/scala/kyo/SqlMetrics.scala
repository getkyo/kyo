package kyo

import kyo.*
import kyo.SqlException
import kyo.stats.internal.Summary

/** Encapsulates all Stat counters and histograms for kyo-sql.
  *
  * When `metricsEnabled` is `false`, every method is a no-op (zero allocation beyond the call). When `metricsEnabled` is `true`, metrics
  * are registered under `metricsScope` (default `"kyo.sql"`).
  *
  * Metric names:
  *   - Counters: `connections_acquired`, `connections_released`, `connections_discarded`, `queries_executed`, `queries_failed`,
  *     `retries_attempted`.
  *   - Histograms: `query_duration_ms`, `pool_acquire_wait_ms`.
  */
final class SqlMetrics(metricsEnabled: Boolean, metricsScope: Maybe[String]):

    private val scopeName: String = metricsScope.getOrElse("kyo.sql")

    // Split scope name on '.' to build nested Stat scopes.
    private val stat: Stat =
        if metricsEnabled then
            val parts = scopeName.split('.')
            if parts.length == 1 then
                Stat.initScope(parts(0))
            else
                Stat.initScope(parts(0), parts.drop(1)*)
            end if
        else
            // Dummy stat — never used, but avoids null.
            Stat.initScope("__noop__")

    // --- Counters ---

    private val _connectionsAcquired: Counter =
        if metricsEnabled then stat.initCounter("connections_acquired", "Number of connections acquired from the pool")
        else SqlMetrics.noopCounter

    private val _connectionsReleased: Counter =
        if metricsEnabled then stat.initCounter("connections_released", "Number of connections released back to the pool")
        else SqlMetrics.noopCounter

    private val _connectionsDiscarded: Counter =
        if metricsEnabled then stat.initCounter("connections_discarded", "Number of connections discarded from the pool")
        else SqlMetrics.noopCounter

    private val _queriesExecuted: Counter =
        if metricsEnabled then stat.initCounter("queries_executed", "Number of queries successfully executed")
        else SqlMetrics.noopCounter

    private val _queriesFailed: Counter =
        if metricsEnabled then stat.initCounter("queries_failed", "Number of queries that resulted in an error")
        else SqlMetrics.noopCounter

    private val _retriesAttempted: Counter =
        if metricsEnabled then stat.initCounter("retries_attempted", "Number of retry attempts made")
        else SqlMetrics.noopCounter

    // --- Histograms ---

    private val _queryDurationMs: Histogram =
        if metricsEnabled then stat.initHistogram("query_duration_ms", "Query execution duration in milliseconds")
        else SqlMetrics.noopHistogram

    private val _poolAcquireWaitMs: Histogram =
        if metricsEnabled then
            stat.initHistogram("pool_acquire_wait_ms", "Time spent waiting to acquire a connection from the pool in milliseconds")
        else SqlMetrics.noopHistogram

    // --- Public accessors ---

    def connectionsAcquired: Counter  = _connectionsAcquired
    def connectionsReleased: Counter  = _connectionsReleased
    def connectionsDiscarded: Counter = _connectionsDiscarded
    def queriesExecuted: Counter      = _queriesExecuted
    def queriesFailed: Counter        = _queriesFailed
    def retriesAttempted: Counter     = _retriesAttempted
    def queryDurationMs: Histogram    = _queryDurationMs
    def poolAcquireWaitMs: Histogram  = _poolAcquireWaitMs

    // --- Histogram summary ---

    def queryDurationSummary(using Frame): Summary < Sync =
        Sync.Unsafe.defer(_queryDurationMs.unsafe.summary())

    def poolAcquireWaitSummary(using Frame): Summary < Sync =
        Sync.Unsafe.defer(_poolAcquireWaitMs.unsafe.summary())

    // --- Instrumented lifecycle methods ---

    /** Increments `connections_acquired`. */
    def recordAcquire(using Frame): Unit < Sync =
        _connectionsAcquired.inc

    /** Increments `connections_released`. */
    def recordRelease(using Frame): Unit < Sync =
        _connectionsReleased.inc

    /** Increments `connections_discarded`. */
    def recordDiscard(using Frame): Unit < Sync =
        _connectionsDiscarded.inc

    /** Increments `queries_executed`. */
    def recordQueryExecuted(using Frame): Unit < Sync =
        _queriesExecuted.inc

    /** Increments `queries_failed`. */
    def recordQueryFailed(using Frame): Unit < Sync =
        _queriesFailed.inc

    /** Increments `retries_attempted`. */
    def recordRetry(using Frame): Unit < Sync =
        _retriesAttempted.inc

    /** Records `durationMs` in `query_duration_ms`. */
    def recordQueryDuration(durationMs: Long)(using Frame): Unit < Sync =
        _queryDurationMs.observe(durationMs)

    /** Records `waitMs` in `pool_acquire_wait_ms`. */
    def recordPoolAcquireWait(waitMs: Long)(using Frame): Unit < Sync =
        _poolAcquireWaitMs.observe(waitMs)

    /** Runs a query body, timing its execution and incrementing `queries_executed` or `queries_failed` on exit.
      *
      * When disabled, the body is lifted into Sync by `Sync(body)` which is a legal zero-overhead Sync intro. The Sync effect is always in
      * the result type since callers already have Async in scope (Async subsumes Sync), so this adds nothing to the erasure.
      *
      * @param body
      *   The query computation to instrument.
      * @tparam A
      *   Result type.
      * @tparam S
      *   Effects.
      */
    def timedQuery[A, S](body: A < (S & Abort[SqlException]))(using Frame): A < (S & Abort[SqlException] & Sync) =
        if !metricsEnabled then
            body
        else
            Clock.stopwatch.flatMap { sw =>
                Abort.run[SqlException](body).flatMap { result =>
                    sw.elapsed.flatMap { dur =>
                        _queryDurationMs.observe(dur.toMillis).andThen {
                            result match
                                case Result.Success(a) =>
                                    _queriesExecuted.inc.andThen(a: A < (S & Abort[SqlException] & Sync))
                                case Result.Failure(e) =>
                                    _queriesFailed.inc.andThen(Abort.fail[SqlException](e): A < (S & Abort[SqlException] & Sync))
                                case Result.Panic(t) =>
                                    _queriesFailed.inc.andThen(Abort.error(Result.Panic(t)): A < (S & Abort[SqlException] & Sync))
                        }
                    }
                }
            }

end SqlMetrics

object SqlMetrics:

    /** No-op counter: every method does nothing. Used when `metricsEnabled = false`. */
    private[SqlMetrics] val noopCounter: Counter = new Counter:
        val unsafe                                 = new kyo.stats.internal.UnsafeCounter
        def get(using Frame): Long < Sync          = 0L
        def inc(using Frame): Unit < Sync          = ()
        def add(v: Long)(using Frame): Unit < Sync = ()

    /** No-op histogram: every method does nothing. Used when `metricsEnabled = false`. */
    private[SqlMetrics] val noopHistogram: Histogram = new Histogram:
        val unsafe                                       = new kyo.stats.internal.UnsafeHistogram(Array.empty[Double])
        def observe(v: Long)(using Frame): Unit < Sync   = ()
        def observe(v: Double)(using Frame): Unit < Sync = ()

    /** Construct with defaults: enabled, scope = "kyo.sql". */
    def apply(): SqlMetrics = new SqlMetrics(metricsEnabled = true, metricsScope = Absent)

    /** Construct with explicit enable flag and optional scope override. */
    def apply(metricsEnabled: Boolean, metricsScope: Maybe[String]): SqlMetrics =
        new SqlMetrics(metricsEnabled, metricsScope)

end SqlMetrics
