package kyo

import kyo.*
import kyo.internal.postgres.types.EncodingRegistry
import kyo.internal.tls.TlsMode
import kyo.net.NetTlsConfig

/** Configuration for a [[SqlClient]] connection pool.
  *
  * @param maxConnections
  *   maximum number of concurrent database connections in the pool
  * @param acquireTimeout
  *   maximum time to wait for a connection to become available from the pool before raising [[SqlException.Connection]]
  * @param queryTimeout
  *   maximum time allowed for a single query/execute call before it is aborted
  * @param idleTimeout
  *   maximum time a pooled connection may remain idle before being closed and replaced
  * @param retrySchedule
  *   retry policy for [[SqlException.Connection]] failures. [[Absent]] (the default) means no automatic retry, the error is propagated
  *   immediately. Supply a [[Schedule]] to enable retries; for example:
  *   {{{
  *   Schedule.exponentialBackoff(initial = 100.millis, factor = 2.0, maxBackoff = 5.seconds).take(5)
  *   }}}
  *   Only [[SqlException.Connection]] errors are retried. [[SqlException.Server]], [[SqlException.Request]], and [[SqlException.Decode]]
  *   pass through unchanged. Note that retrying non-idempotent DML (INSERT/UPDATE/DELETE) carries the risk of duplicate execution if the
  *   server executed the statement before the connection was lost; consider this carefully before enabling automatic retries.
  *   [[SqlException.Connection]] due to [[acquireTimeout]] is also retried, so if the pool remains saturated, all retries may timeout too.
  *   For 40xxx (serialization failure / deadlock) [[SqlException.Server]] errors, wrap the call in your own retry loop if needed.
  * @param tls
  *   TLS configuration. [[Absent]] = no TLS (plaintext). [[Present]] = TLS required; the connection will perform an SSLRequest handshake
  *   before startup. Use [[NetTlsConfig.default]] for JDK default trust validation, or [[NetTlsConfig]] with `trustAll = true` for
  *   development / self-signed certs.
  * @param caCertPath
  *   path to a PEM-encoded CA certificate used to validate the server's certificate chain when using `sslmode=verify-ca` or
  *   `sslmode=verify-full`. Equivalent to the `sslrootcert` URL query parameter. [[Absent]] = use the JDK default trust store.
  * @param preparedStmtCacheSize
  *   maximum number of server-side prepared statements cached per connection. Each connection independently maintains a cache; the total
  *   server-side statement memory is bounded by `preparedStmtCacheSize × maxConnections`.
  * @param preparedStmtTtl
  *   how long a cached prepared statement may remain unused before expiry. [[Duration.Infinity]] (the default) disables time-based expiry,
  *   entries are only evicted when the cache reaches capacity (CLOCK eviction).
  * @param minConnections
  *   minimum number of connections to open eagerly when the pool is initialised (warm-up). If greater than `maxConnections`, it is clamped
  *   to `maxConnections`. When > 0, [[SqlClient.init]] and [[SqlClient.initMy]] open `min(minConnections, maxConnections)` connections
  *   concurrently before returning the client, so the first user query is served from a pre-warmed pool without incurring connection
  *   establishment latency. Any connection failure during warm-up aborts `init` with [[SqlException.Connection]]. Default `0` (no warm-up).
  * @param resetOnRelease
  *   *(MySQL only)* if `true`, sends `COM_RESET_CONNECTION` before returning a MySQL connection to the pool. This clears session variables,
  *   server-side prepared statements, last-insert-id, and other per-session state accumulated during the connection's use, ensuring the
  *   next borrower sees a clean session. Default `false` to avoid the latency cost on every connection release.
  * @param cancelTimeout
  *   *(MySQL only)* maximum time to wait for a cancel connection to become available from the pool when calling [[SqlClient.cancel]] on a
  *   MySQL handle. MySQL cancellation requires acquiring a second connection from the pool and sending `KILL QUERY <connectionId>`; if the
  *   pool is saturated for longer than `cancelTimeout`, [[SqlException.Connection]] is raised. Default `2.seconds`.
  * @param pipelineMode
  *   if `true`, the `pipeline` API is enabled. When `false` (the default), calls to `pipeline` still work but are executed sequentially
  *   using individual Bind/Execute/Sync round trips. Enable to coalesce multiple DML statements into a single TCP write.
  * @param metricsEnabled
  *   if `true` (the default), kyo.Stat metrics are collected: counters for connection lifecycle and query throughput, histograms for query
  *   duration and pool acquire wait time. Set to `false` to disable all metric instrumentation with zero overhead.
  * @param metricsScope
  *   custom metric scope prefix. [[Absent]] (the default) uses `"kyo.sql"` as the scope. Supply a [[Present]] value to override, e.g.
  *   `Present("myapp.db")`, all eight metric names will be registered under that prefix.
  * @param encodingRegistry
  *   custom encoding registry for PostgreSQL type codecs. Defaults to [[EncodingRegistry.builtin]], which covers all standard scalar types.
  *   Call [[EncodingRegistry.builtin.register]] to add custom OID-to-codec mappings on top of the builtin defaults:
  *   {{{
  *   val registry = EncodingRegistry.builtin.register(myOid, myEncoder, myDecoder)
  *   SqlClientConfig.default.copy(encodingRegistry = registry)
  *   }}}
  *   Only applies to PostgreSQL connections; MySQL connections use their own type encoding layer.
  * @param maxLifetime
  *   maximum age a connection may reach before it is closed and replaced; [[Absent]] means connections live forever.
  * @param connectionTestQuery
  *   SQL to run as a liveness ping before lending a connection (e.g. `"SELECT 1"`); [[Absent]] uses a driver-level ping instead.
  * @param connectionInitSql
  *   SQL to execute once after a new connection is established and before it enters the pool; [[Absent]] skips init SQL.
  * @param keepaliveTime
  *   interval between TCP keepalive probes sent while a connection is idle; [[Absent]] relies on OS defaults.
  * @param connectTimeout
  *   maximum time allowed for the TCP+TLS+auth handshake when opening a new connection. Separate from [[acquireTimeout]].
  * @param socketTimeout
  *   maximum time allowed for a single socket read or write; [[Absent]] disables the per-IO bound. TODO: wire through transport layer.
  * @param leakDetectionThreshold
  *   if a borrowed connection is held longer than this duration a warning is emitted; [[Absent]] disables leak detection. TODO: wire
  *   through pool borrow tracking.
  * @param connectionInitTimeout
  *   maximum time allowed for the combined connect + [[connectionInitSql]] phase before the attempt is aborted.
  * @param closeGrace
  *   default grace period passed to [[SqlClient.close]] when no explicit `gracePeriod` argument is supplied. After the grace period, any
  *   remaining in-flight connections are force-closed. Default `30.seconds`.
  * @param streamBatchSize
  *   number of rows fetched per `Execute` round-trip inside [[SqlClient.streamQuery]]. Larger values amortise round-trip latency at the
  *   cost of higher memory usage per batch. Default `64` (matches the PostgreSQL extended-query protocol default).
  * @param copyOutCleanupTimeout
  *   maximum time allowed for the uninterruptible `CopyFail` + `ReadyForQuery`-drain cleanup path that runs when a `COPY TO STDOUT` stream
  *   is closed before the server has sent [[CopyDone]]. If the budget expires the connection is marked corrupted and removed from the pool.
  *   Default `5.seconds`.
  */
final case class SqlClientConfig(
    maxConnections: Int,
    acquireTimeout: Duration,
    queryTimeout: Duration,
    idleTimeout: Duration,
    minConnections: Int = 0,
    retrySchedule: Maybe[Schedule] = Absent,
    tls: Maybe[NetTlsConfig] = Absent,
    caCertPath: Maybe[String] = Absent,
    preparedStmtCacheSize: Int = 64,
    preparedStmtTtl: Duration = Duration.Infinity,
    resetOnRelease: Boolean = false,
    cancelTimeout: Duration = 2.seconds,
    tlsMode: TlsMode = TlsMode.Disable,
    pipelineMode: Boolean = false,
    metricsEnabled: Boolean = true,
    metricsScope: Maybe[String] = Absent,
    typeNames: Set[String] = Set.empty,
    encodingRegistry: EncodingRegistry = EncodingRegistry.builtin,
    maxLifetime: Maybe[Duration] = Absent,
    connectionTestQuery: Maybe[String] = Absent,
    connectionInitSql: Maybe[String] = Absent,
    keepaliveTime: Maybe[Duration] = Absent,
    connectTimeout: Duration = 30.seconds,
    socketTimeout: Maybe[Duration] = Absent,
    leakDetectionThreshold: Maybe[Duration] = Absent,
    connectionInitTimeout: Duration = 30.seconds,
    closeGrace: Duration = 30.seconds,
    streamBatchSize: Int = 64,
    copyOutCleanupTimeout: Duration = 5.seconds
) derives CanEqual

object SqlClientConfig:

    /** Sane defaults suitable for development and light-traffic production use.
      *
      * Values:
      *   - maxConnections: 10
      *   - acquireTimeout: 5 seconds
      *   - queryTimeout: 30 seconds
      *   - idleTimeout: 10 minutes
      *   - retrySchedule: Absent (no automatic retry, callers opt in via `retrySchedule = Present(schedule)`)
      *   - tls: Absent (plaintext)
      *   - metricsEnabled: true (kyo.Stat metrics collected under scope `"kyo.sql"`)
      *   - metricsScope: Absent (uses default `"kyo.sql"`)
      *   - maxLifetime: Absent (connections live forever)
      *   - connectionTestQuery: Absent (driver-level ping)
      *   - connectionInitSql: Absent (no init SQL)
      *   - keepaliveTime: Absent (OS default keepalive)
      *   - connectTimeout: 30 seconds
      *   - socketTimeout: Absent (no per-IO bound)
      *   - leakDetectionThreshold: Absent (no leak detection)
      *   - connectionInitTimeout: 30 seconds
      *   - closeGrace: 30 seconds
      *   - streamBatchSize: 64 (rows per Execute batch)
      *   - copyOutCleanupTimeout: 5 seconds
      */
    val default: SqlClientConfig = SqlClientConfig(
        maxConnections = 10,
        acquireTimeout = 5.seconds,
        queryTimeout = 30.seconds,
        idleTimeout = 10.minutes
    )

end SqlClientConfig
