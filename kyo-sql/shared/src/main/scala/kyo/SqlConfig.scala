package kyo

import kyo.*
import kyo.EncodingRegistry
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.TlsMode
import kyo.internal.tls.TlsContext
import kyo.net.NetTlsConfig

/** Configuration for a [[SqlClient]] connection pool.
  *
  * @param maxConnections
  *   maximum number of concurrent database connections in the pool
  * @param acquireTimeout
  *   maximum time to wait for a connection to become available from the pool before raising [[SqlConnectionException]]
  * @param queryTimeout
  *   maximum time allowed for a single query/execute call before it is aborted
  * @param idleTimeout
  *   maximum time a pooled connection may remain idle before being closed and replaced
  * @param retrySchedule
  *   retry policy for [[SqlConnectionException]] failures. [[Absent]] (the default) means no automatic retry, the error is propagated
  *   immediately. Supply a [[Schedule]] to enable retries; for example:
  *   {{{
  *   Schedule.exponentialBackoff(initial = 100.millis, factor = 2.0, maxBackoff = 5.seconds).take(5)
  *   }}}
  *   Only [[SqlConnectionException]] errors are retried. [[SqlServerException]], [[SqlRequestException]], and [[SqlDecodeException]]
  *   pass through unchanged. Note that retrying non-idempotent DML (INSERT/UPDATE/DELETE) carries the risk of duplicate execution if the
  *   server executed the statement before the connection was lost; consider this carefully before enabling automatic retries.
  *   [[SqlConnectionException]] due to [[acquireTimeout]] is also retried, so if the pool remains saturated, all retries may timeout too.
  *   For 40xxx (serialization failure / deadlock) [[SqlServerException]] errors, wrap the call in your own retry loop if needed.
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
  *   to `maxConnections`. When > 0, [[SqlClient.init]] and [[SqlClient.initMysql]] open `min(minConnections, maxConnections)` connections
  *   concurrently before returning the client, so the first user query is served from a pre-warmed pool without incurring connection
  *   establishment latency. Any connection failure during warm-up aborts `init` with [[SqlConnectionException]]. Default `0` (no warm-up).
  * @param cancelTimeout
  *   *(MySQL only)* maximum time to wait for a cancel connection to become available from the pool when calling [[SqlClient.cancel]] on a
  *   MySQL handle. MySQL cancellation requires acquiring a second connection from the pool and sending `KILL QUERY <connectionId>`; if the
  *   pool is saturated for longer than `cancelTimeout`, [[SqlConnectionException]] is raised. Default `2.seconds`.
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
  *   SqlConfig.default.copy(encodingRegistry = registry)
  *   }}}
  *   Only applies to PostgreSQL connections; MySQL connections use their own type encoding layer.
  * @param connectionTestQuery
  *   SQL to run as a liveness ping before lending a connection (e.g. `"SELECT 1"`); [[Absent]] uses a driver-level ping instead.
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
final case class SqlConfig(
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
    cancelTimeout: Duration = 2.seconds,
    tlsMode: TlsMode = TlsMode.Disable,
    metricsEnabled: Boolean = true,
    metricsScope: Maybe[String] = Absent,
    typeNames: Set[String] = Set.empty,
    encodingRegistry: EncodingRegistry = EncodingRegistry.builtin,
    connectionTestQuery: Maybe[String] = Absent,
    closeGrace: Duration = 30.seconds,
    streamBatchSize: Int = 64,
    copyOutCleanupTimeout: Duration = 5.seconds
) derives CanEqual

object SqlConfig:

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
      *   - connectionTestQuery: Absent (driver-level ping)
      *   - closeGrace: 30 seconds
      *   - streamBatchSize: 64 (rows per Execute batch)
      *   - copyOutCleanupTimeout: 5 seconds
      */
    val default: SqlConfig = SqlConfig(
        maxConnections = 10,
        acquireTimeout = 5.seconds,
        queryTimeout = 30.seconds,
        idleTimeout = 10.minutes
    )

    /** Connection pool key that uniquely identifies a database endpoint.
      *
      * Two addresses are equal when all five fields match, so the pool can reuse connections within a single backend and keep separate
      * pools for different databases, users, or hosts.
      *
      * @param driver
      *   lowercase driver identifier: "postgres" or "mysql"
      * @param host
      *   hostname or IP address
      * @param port
      *   TCP port number
      * @param db
      *   database name
      * @param user
      *   authentication user name
      */
    final case class Address(
        driver: String,
        host: String,
        port: Int,
        db: String,
        user: String
    ) derives CanEqual

    object Address:
        given Render[Address] = Render.from { a =>
            s"${a.driver}://${a.user}@${a.host}:${a.port}/${a.db}"
        }
    end Address

    /** Parsed representation of a JDBC-style database URL.
      *
      * Supported schemes: `postgres://` and `mysql://`.
      *
      * URL format: `<scheme>://<user>:<password>@<host>:<port>/<db>[?<options>]`
      *
      * Port is required. Missing port is a parse error.
      *
      * @param address
      *   the [[SqlConfig.Address]] extracted from the URL (driver, host, port, db, user)
      * @param password
      *   authentication password (may be empty)
      * @param options
      *   parsed query-string options including TLS mode, timeouts, and unrecognised extras
      */
    final case class Url(
        address: Address,
        password: String,
        options: Url.Options
    ) derives CanEqual:

        /** Converts this [[Url]] into a [[SqlConfig]] by mapping the `sslmode` and `sslrootcert` query parameters to TLS configuration via
          * [[kyo.internal.tls.TlsContext.build]].
          *
          * Mapping rules (delegated to [[kyo.internal.tls.TlsContext.build]]):
          *   - `sslmode=disable` or absent → `tls = Absent`
          *   - `sslmode=allow` or `sslmode=prefer` → `tls = Absent` (opportunistic upgrade handled at the backend layer)
          *   - `sslmode=require` → `tls = Present(NetTlsConfig(trustAll = true))` (TLS required, no cert-chain or hostname check)
          *   - `sslmode=verify-ca` → requires `sslrootcert`; fails with [[SqlConnectionException]] if absent
          *   - `sslmode=verify-full` → requires `sslrootcert`; fails with [[SqlConnectionException]] if absent
          *
          * `sslrootcert` is also stored in [[SqlConfig.caCertPath]] for programmatic use.
          *
          * All other config fields use [[SqlConfig.default]].
          */
        def toConfig(using Frame): SqlConfig < Abort[SqlConnectionException] =
            val caCertPath = options.sslrootcert
            val tlsMode: TlsMode = options.sslmode match
                case Present(Url.SslMode.Disable)    => TlsMode.Disable
                case Present(Url.SslMode.Allow)      => TlsMode.Allow
                case Present(Url.SslMode.Prefer)     => TlsMode.Prefer
                case Present(Url.SslMode.Require)    => TlsMode.Require
                case Present(Url.SslMode.VerifyCa)   => TlsMode.VerifyCa
                case Present(Url.SslMode.VerifyFull) => TlsMode.VerifyFull
                case Absent                          => TlsMode.Disable
            TlsContext.build(tlsMode, caCertPath).map { tls =>
                SqlConfig.default.copy(tls = tls, caCertPath = caCertPath, tlsMode = tlsMode)
            }
        end toConfig

    end Url

    object Url:

        /** Recognised SSL modes matching the PostgreSQL `sslmode` connection parameter. */
        enum SslMode derives CanEqual:
            case Disable, Allow, Prefer, Require, VerifyCa, VerifyFull
        end SslMode

        /** Parsed query-string options from the URL.
          *
          * @param sslmode
          *   TLS requirement level
          * @param sslrootcert
          *   path to a PEM-encoded CA certificate used to validate the server certificate chain; maps to [[SqlConfig.caCertPath]].
          *   Required when `sslmode=verify-ca` or `sslmode=verify-full`.
          * @param applicationName
          *   value for the `application_name` session parameter (PG) / connection attribute (MySQL)
          * @param params
          *   unrecognised query-string key/value pairs preserved for driver-specific use
          */
        final case class Options(
            sslmode: Maybe[SslMode],
            sslrootcert: Maybe[String],
            applicationName: Maybe[String],
            params: Map[String, String]
        ) derives CanEqual

        object Options:
            val default: Options = Options(Absent, Absent, Absent, Map.empty)
        end Options

        /** Parses a URL string into a [[Url]].
          *
          * Returns [[SqlConnectionException]] as a pure [[Result]] on any parse error (unknown scheme, missing port, malformed syntax).
          */
        def parse(raw: String)(using Frame): Result[SqlConnectionException, Url] =
            parseResult(raw)

        // --- internal parse implementation ---

        private def parseResult(raw: String)(using Frame): Result[SqlConnectionException, Url] =
            // Minimal URL parsing without java.net.URI to stay cross-platform and avoid
            // java.net.MalformedURLException for non-http schemes on some platforms.
            val schemeEnd = raw.indexOf("://")
            if schemeEnd < 0 then
                Result.fail(SqlConnectionUrlParseException(raw, ""))
            else
                val scheme = raw.substring(0, schemeEnd).toLowerCase
                if scheme != "postgres" && scheme != "mysql" then
                    Result.fail(SqlConnectionUrlParseException(raw, scheme))
                else
                    val rest = raw.substring(schemeEnd + 3) // after "://"

                    // Split off query string
                    val (authority, queryString) = rest.indexOf('?') match
                        case -1  => (rest, "")
                        case idx => (rest.substring(0, idx), rest.substring(idx + 1))

                    // Parse user:password@host:port/db
                    val (userInfo, hostPart) = authority.indexOf('@') match
                        case -1  => ("", authority)
                        case idx => (authority.substring(0, idx), authority.substring(idx + 1))

                    val (user, password) =
                        userInfo.indexOf(':') match
                            case -1  => (userInfo, "")
                            case idx => (userInfo.substring(0, idx), userInfo.substring(idx + 1))

                    hostPart.indexOf('/') match
                        case -1 =>
                            Result.fail(SqlConnectionUrlParseException(raw, scheme))
                        case idx =>
                            val hostPort = hostPart.substring(0, idx)
                            val db       = hostPart.substring(idx + 1)
                            // Handle IPv6 [::1]:5432
                            if hostPort.startsWith("[") then
                                val closeBracket = hostPort.indexOf(']')
                                if closeBracket < 0 then
                                    Result.fail(SqlConnectionUrlParseException(raw, scheme))
                                else
                                    val h        = hostPort.substring(1, closeBracket)
                                    val portPart = hostPort.substring(closeBracket + 1)
                                    if !portPart.startsWith(":") then
                                        Result.fail(SqlConnectionUrlParseException(raw, scheme))
                                    else
                                        portPart.substring(1).toIntOption match
                                            case None =>
                                                Result.fail(SqlConnectionUrlParseException(raw, scheme))
                                            case Some(port) =>
                                                val address = Address(scheme, h, port, db, user)
                                                val options = parseOptions(queryString)
                                                Result.Success(Url(address, password, options))
                                    end if
                                end if
                            else
                                hostPort.lastIndexOf(':') match
                                    case -1 =>
                                        Result.fail(SqlConnectionUrlParseException(raw, scheme))
                                    case portIdx =>
                                        val portStr = hostPort.substring(portIdx + 1)
                                        if portStr.isEmpty then
                                            Result.fail(SqlConnectionUrlParseException(raw, scheme))
                                        else
                                            portStr.toIntOption match
                                                case None =>
                                                    Result.fail(SqlConnectionUrlParseException(raw, scheme))
                                                case Some(port) =>
                                                    val host    = hostPort.substring(0, portIdx)
                                                    val address = Address(scheme, host, port, db, user)
                                                    val options = parseOptions(queryString)
                                                    Result.Success(Url(address, password, options))
                                        end if
                            end if
                    end match
                end if
            end if
        end parseResult

        private def parseOptions(queryString: String): Options =
            if queryString.nonEmpty then
                val pairs: Map[String, String] =
                    queryString.split('&').collect {
                        case kv if kv.contains('=') =>
                            val idx = kv.indexOf('=')
                            kv.substring(0, idx) -> kv.substring(idx + 1)
                        case k if k.nonEmpty =>
                            k -> ""
                    }.toMap

                val sslmode     = Maybe.fromOption(pairs.get("sslmode")).flatMap(parseSslMode)
                val sslrootcert = Maybe.fromOption(pairs.get("sslrootcert"))
                val appName     = Maybe.fromOption(pairs.get("application_name"))

                val knownKeys = Set("sslmode", "sslrootcert", "application_name")
                val extra     = pairs.filterNot { case (k, _) => knownKeys.contains(k) }

                Options(sslmode, sslrootcert, appName, extra)
            else
                Options.default
        end parseOptions

        private def parseSslMode(s: String): Maybe[SslMode] =
            s.toLowerCase match
                case "disable"     => Present(SslMode.Disable)
                case "allow"       => Present(SslMode.Allow)
                case "prefer"      => Present(SslMode.Prefer)
                case "require"     => Present(SslMode.Require)
                case "verify-ca"   => Present(SslMode.VerifyCa)
                case "verify-full" => Present(SslMode.VerifyFull)
                case _             => Absent

    end Url

end SqlConfig
