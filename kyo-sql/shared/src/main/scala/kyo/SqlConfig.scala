package kyo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.internal.tls.TlsContext
import kyo.net.NetTlsConfig

/** Configuration for a [[SqlClient]] connection pool.
  *
  * Every field has a default, so `SqlConfig()` and [[SqlConfig.default]] are the same value. Backend-specific settings are not fields here:
  * they are attached as [[SqlConfig.Extension]] values through [[SqlConfig.extension]], which keeps this type free of any one backend's
  * vocabulary.
  *
  * @param maxConnections
  *   maximum number of concurrent database connections in the pool, honoured exactly. `1` serialises access to a single session, which is what
  *   session-scoped state or a server-side connection quota needs. The pool's internal idle buffer keeps a floor of two slots for its own
  *   structural reasons, but that bounds how many connections may be RETAINED, never how many run at once, so it is not observable here.
  * @param minConnections
  *   minimum number of connections to open eagerly when the pool is initialised (warm-up). If greater than `maxConnections`, it is clamped
  *   to `maxConnections`. When > 0, [[SqlClient.init]] opens `min(minConnections, maxConnections)` connections
  *   concurrently before returning the client, so the first user query is served from a pre-warmed pool without incurring connection
  *   establishment latency. Any connection failure during warm-up aborts `init` with [[SqlConnectionException]]. Default `0` (no warm-up).
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
  *   before startup. Use `kyo.net.NetTlsConfig.default` for JDK default trust validation, or `kyo.net.NetTlsConfig` with `trustAll = true` for
  *   development / self-signed certs.
  * @param caCertPath
  *   path to a PEM-encoded CA certificate used to validate the server's certificate chain under [[SqlConfig.TlsMode.VerifyCa]] or
  *   [[SqlConfig.TlsMode.VerifyFull]]. Equivalent to the `sslrootcert` URL query parameter. [[Absent]] = use the JDK default trust store.
  * @param preparedStatementCacheSize
  *   maximum number of server-side prepared statements cached per connection. Each connection independently maintains a cache; the total
  *   server-side statement memory is bounded by `preparedStatementCacheSize x maxConnections`.
  * @param preparedStatementTtl
  *   how long a cached prepared statement may remain unused before expiry. [[Duration.Infinity]] (the default) disables time-based expiry,
  *   entries are only evicted when the cache reaches capacity (CLOCK eviction).
  * @param cancelTimeout
  *   total budget for reclaiming a connection whose fiber was interrupted. One budget covers the whole chain, not one step each: telling the
  *   server to stop the statement, reading whatever it still owes the session, and rolling back a transaction left open. A chain that
  *   overruns it leaves the connection destroyed and its pool slot freed rather than returning a session whose state is unknown, so a larger
  *   value trades a longer wait for a better chance of reusing the connection. Default `2.seconds`.
  * @param tlsMode
  *   TLS policy enforced at connect time when [[tls]] is absent. [[SqlConfig.Url.parse]] fills this in from the URL's `sslmode` parameter.
  * @param metricsEnabled
  *   if `true` (the default), kyo.Stat metrics are collected: counters for connection lifecycle and query throughput, histograms for query
  *   duration and pool acquire wait time. Set to `false` to disable all metric instrumentation with zero overhead.
  * @param metricsScope
  *   custom metric scope prefix. [[Absent]] (the default) uses `"kyo.sql"` as the scope. Supply a [[Present]] value to override, e.g.
  *   `Present("myapp.db")`, all eight metric names will be registered under that prefix.
  * @param connectionTestQuery
  *   SQL to run against the server before lending a pooled connection (e.g. `"SELECT 1"`), bounded by [[queryTimeout]] and supported on every
  *   platform. [[Absent]], the default, sends nothing: the pool checks that the socket is still open and lends the connection, so a reused
  *   connection costs no extra round trip. A caller who wants a server probe on demand rather than per lease has [[SqlClient.ping]] and
  *   [[SqlClient.isAlive]].
  * @param closeGrace
  *   default grace period passed to [[SqlClient.close]] when no explicit `gracePeriod` argument is supplied. After the grace period, any
  *   remaining in-flight connections are force-closed. Default `30.seconds`.
  * @param streamBatchSize
  *   number of rows fetched per `Execute` round-trip inside [[SqlClient.streamQuery]]. Larger values amortise round-trip latency at the
  *   cost of higher memory usage per batch. Default `64` (matches the PostgreSQL extended-query protocol default). PostgreSQL honours
  *   it per round trip; the MySQL driver reads rows one at a time off the wire, so there it is informational only.
  * @param extensions
  *   backend-specific configuration attached to this config, at most one value per extension type. Read an extension back with
  *   [[extensionFor]]; a backend that finds none uses its own defaults.
  */
final case class SqlConfig(
    maxConnections: Int = 10,
    minConnections: Int = 0,
    acquireTimeout: Duration = 5.seconds,
    queryTimeout: Duration = 30.seconds,
    idleTimeout: Duration = 10.minutes,
    retrySchedule: Maybe[Schedule] = Absent,
    tls: Maybe[NetTlsConfig] = Absent,
    caCertPath: Maybe[String] = Absent,
    preparedStatementCacheSize: Int = 64,
    preparedStatementTtl: Duration = Duration.Infinity,
    cancelTimeout: Duration = 2.seconds,
    tlsMode: SqlConfig.TlsMode = SqlConfig.TlsMode.Disable,
    metricsEnabled: Boolean = true,
    metricsScope: Maybe[String] = Absent,
    connectionTestQuery: Maybe[String] = Absent,
    closeGrace: Duration = 30.seconds,
    streamBatchSize: Int = 64,
    extensions: Chunk[SqlConfig.Extension] = Chunk.empty
) derives CanEqual:

    /** Attaches `e`, replacing any extension already attached under the same runtime class.
      *
      * At most one value per extension type is held, so a second `extension(PostgresConfig(...))` call overwrites the first rather than
      * leaving the backend to choose between two.
      */
    def extension[E <: SqlConfig.Extension](e: E): SqlConfig =
        copy(extensions = extensions.filterNot(_.getClass eq e.getClass).append(e))

    /** Reads back the extension attached under type `E`, or [[Absent]] when none is. */
    def extensionFor[E <: SqlConfig.Extension](using ct: ConcreteTag[E]): Maybe[E] =
        extensions.find(ct.accepts) match
            // Erasure boundary: ct.accepts just tested the runtime class against E, so the cast cannot fail.
            case Some(e) => Present(e.asInstanceOf[E])
            case None    => Absent

    def maxConnections(n: Int): SqlConfig             = copy(maxConnections = n)
    def minConnections(n: Int): SqlConfig             = copy(minConnections = n)
    def acquireTimeout(d: Duration): SqlConfig        = copy(acquireTimeout = d)
    def queryTimeout(d: Duration): SqlConfig          = copy(queryTimeout = d)
    def idleTimeout(d: Duration): SqlConfig           = copy(idleTimeout = d)
    def retrySchedule(s: Schedule): SqlConfig         = copy(retrySchedule = Present(s))
    def tls(config: NetTlsConfig): SqlConfig          = copy(tls = Present(config))
    def caCertPath(path: String): SqlConfig           = copy(caCertPath = Present(path))
    def preparedStatementCacheSize(n: Int): SqlConfig = copy(preparedStatementCacheSize = n)
    def preparedStatementTtl(d: Duration): SqlConfig  = copy(preparedStatementTtl = d)
    def cancelTimeout(d: Duration): SqlConfig         = copy(cancelTimeout = d)
    def tlsMode(mode: SqlConfig.TlsMode): SqlConfig   = copy(tlsMode = mode)
    def metricsEnabled(enabled: Boolean): SqlConfig   = copy(metricsEnabled = enabled)
    def metricsScope(scope: String): SqlConfig        = copy(metricsScope = Present(scope))
    def connectionTestQuery(sql: String): SqlConfig   = copy(connectionTestQuery = Present(sql))
    def closeGrace(d: Duration): SqlConfig            = copy(closeGrace = d)
    def streamBatchSize(n: Int): SqlConfig            = copy(streamBatchSize = n)

end SqlConfig

object SqlConfig:

    /** Sane defaults suitable for development and light-traffic production use.
      *
      * Values:
      *   - maxConnections: 10
      *   - acquireTimeout: 5 seconds
      *   - queryTimeout: 30 seconds
      *   - idleTimeout: 10 minutes
      *   - cancelTimeout: 2 seconds (budget for the whole reclaim chain after an interrupt)
      *   - retrySchedule: Absent (no automatic retry, callers opt in via `retrySchedule = Present(schedule)`)
      *   - tls: Absent (plaintext)
      *   - metricsEnabled: true (kyo.Stat metrics collected under scope `"kyo.sql"`)
      *   - metricsScope: Absent (uses default `"kyo.sql"`)
      *   - connectionTestQuery: Absent (no probe query; a reused connection is checked for an open socket only)
      *   - closeGrace: 30 seconds
      *   - streamBatchSize: 64 (rows per Execute batch)
      *   - extensions: empty (every backend uses its own defaults)
      */
    val default: SqlConfig = SqlConfig()

    /** The TLS mode a connection negotiates: a five-rung ladder from plaintext to full certificate verification, engine-neutral.
      *
      * Core enforces the ladder at the single connect chokepoint and keys pool identity on the resolved TLS configuration; each backend maps
      * the rungs onto its own transport mechanics, and an engine with no transport at all refuses any mode other than [[TlsMode.Disable]]. The
      * URL spelling of these follows the libpq `sslmode` convention, but the semantics are the module's own rather than any one engine's.
      *
      * Variants:
      *   - [[SqlConfig.TlsMode.Disable]], no TLS; all traffic is plaintext.
      *   - [[SqlConfig.TlsMode.Allow]], prefer plaintext; upgrade to TLS only if the server demands it (opportunistic).
      *   - [[SqlConfig.TlsMode.Prefer]], prefer TLS; fall back to plaintext if the server does not support it (opportunistic).
      *   - [[SqlConfig.TlsMode.Require]], TLS is mandatory; accept any certificate (no CA verification, no hostname check).
      *   - [[SqlConfig.TlsMode.VerifyCa]], TLS is mandatory; validate the server certificate chain against the configured CA bundle (`sslrootcert`), but skip
      *     hostname verification.
      *   - [[SqlConfig.TlsMode.VerifyFull]], TLS is mandatory; validate the certificate chain AND verify the server hostname against the certificate's CN/SAN.
      */
    enum TlsMode derives CanEqual:
        case Disable
        case Allow
        case Prefer
        case Require
        case VerifyCa
        case VerifyFull

        /** Whether an unencrypted connection satisfies this mode.
          *
          * The three mandatory modes say no, and the split is what a connect path has to branch on: [[SqlConfig.TlsMode.Allow]] and [[SqlConfig.TlsMode.Prefer]] are opportunistic, so
          * plaintext is a legitimate outcome for them and their connect paths treat absent TLS settings as "nothing to negotiate with". For
          * [[SqlConfig.TlsMode.Require]], [[SqlConfig.TlsMode.VerifyCa]], and [[SqlConfig.TlsMode.VerifyFull]] the same absence is a contradiction, and answering it with a plaintext socket is the silent
          * downgrade [[kyo.SqlConnectionTlsNotConfiguredException]] exists to prevent.
          *
          * Named here rather than spelled as a match at each connect path: a new mode added to the enum must make an explicit choice here, where an
          * inline match would let it default to the wrong side silently.
          */
        def demandsEncryption: Boolean =
            this match
                case Disable | Allow | Prefer        => false
                case Require | VerifyCa | VerifyFull => true

        /** The `sslmode` spelling, for a message a caller can match against what they configured. */
        def sslMode: String =
            this match
                case Disable    => "disable"
                case Allow      => "allow"
                case Prefer     => "prefer"
                case Require    => "require"
                case VerifyCa   => "verify-ca"
                case VerifyFull => "verify-full"
    end TlsMode

    /** Marker for a backend's own configuration type.
      *
      * An implementation MUST be a final, non-generic case class declared at the top level or inside a static, non-generic object. All three
      * properties are what make runtime-class equality agree with compile-time type equality, which is how [[SqlConfig.extension]] decides
      * that a value replaces an earlier one and how [[SqlConfig.extensionFor]] finds it again. Behavior is undefined for a parameterized or
      * path-dependent implementation.
      */
    abstract class Extension

    /** Connection pool key that uniquely identifies a database endpoint.
      *
      * Two addresses are equal when all five fields match, so the pool can reuse connections within a single backend and keep separate
      * pools for different databases, users, or hosts.
      *
      * @param scheme
      *   lowercase URL scheme, as written in the URL. Which schemes an application can actually open is decided by the
      *   [[kyo.db.Backend]] instances on its classpath, not here: parsing accepts any scheme and [[kyo.db.Backend.Registry.forScheme]] is
      *   what rejects one no backend claims. Keeping the judgment there is what lets a backend declare an alias, and what keeps this file
      *   free of any engine's name.
      * @param host
      *   hostname or IP address
      * @param port
      *   TCP port number
      * @param database
      *   database name
      * @param user
      *   the authentication user name the endpoint declared. [[Absent]] when it named none, which for a URL means the authority carried no `@`;
      *   [[Present]] with an empty string when it named an empty one, spelled `scheme://@host:port/db`. The two are different facts and the
      *   backends treat them differently: a handshake that requires a user name refuses an [[Absent]] one before opening a socket
      *   (see [[SqlConnectionUserRequiredException]]), while an empty one is sent and the server rules on whether it names an account.
      */
    final case class Address(
        scheme: String,
        host: String,
        port: Int,
        database: String,
        user: Maybe[String]
    ) derives CanEqual

    object Address:
        /** Renders the address back into the URL form it came from, so an absent user produces no `user@` component at all.
          *
          * The three spellings stay distinguishable, which is the point of the field being a [[Maybe]]: `postgres://alice@h:5432/db` for a named
          * user, `postgres://@h:5432/db` for a declared empty one, and `postgres://h:5432/db` for none. Reporting an absent user as the literal
          * `Absent`, as a bare `@`, or as an empty string before one would each put a spelling on the page that no URL parses back to.
          */
        given Render[Address] = Render.from { a =>
            val userInfo = a.user match
                case Present(user) => s"$user@"
                case Absent        => ""
            s"${a.scheme}://$userInfo${a.host}:${a.port}/${a.database}"
        }
    end Address

    /** Parsed representation of a JDBC-style database URL.
      *
      * Supported schemes: `postgres://` and `mysql://`.
      *
      * URL format: `<scheme>://[<user>[:<password>]@]<host>:<port>/<db>[?<options>]`
      *
      * Port is required. Missing port is a parse error. The userinfo is optional, and its two delimiters are what declare the fields beside them:
      * a URL with no `@` named neither user nor password, so both read [[Absent]].
      *
      * @param address
      *   the [[SqlConfig.Address]] extracted from the URL (scheme, host, port, database, user)
      * @param password
      *   the credential the URL declared. [[Absent]] when the URL carried no userinfo, or when the userinfo carried no `:`,
      *   so the URL named no password at all; [[Present]] with an empty string when it carried a `:` followed by nothing,
      *   which is a declared empty credential and a
      *   different fact. The two spellings reach the wire identically: PostgreSQL refuses both rather than sending a credential,
      *   since none of its methods has a usable empty one, and MySQL encodes both as the zero-length auth response its plugins
      *   define, which is how a genuinely passwordless account authenticates. So this field records what the URL said.
      * @param options
      *   the query-string values the URL declared
      */
    final case class Url(
        address: Address,
        password: Maybe[String],
        options: Url.Options
    ) derives CanEqual:

        /** Redacts a declared password so it never appears in logs or error messages, and reports an absent one as absent.
          *
          * "No password was supplied" is the actionable fact and is not itself a secret: it is what
          * [[SqlConnectionPasswordRequiredException]] exists to report, and hiding it behind the same `***` a real credential
          * gets is what makes a missing credential read as a wrong one. A [[Present]] empty string still prints `***`, because
          * the shape of a declared credential is not something to report.
          */
        override def toString: String =
            val shown = password match
                case Present(_) => "***"
                case Absent     => "Absent"
            s"Url($address,$shown,$options)"
        end toString

        /** Converts this [[Url]] into a [[SqlConfig]] by mapping the TLS options the URL declared through
          * [[kyo.internal.tls.TlsContext.build]].
          *
          * Mapping rules (delegated to [[kyo.internal.tls.TlsContext.build]]):
          *   - no `sslmode` in the URL, or [[SqlConfig.TlsMode.Disable]] => `tls = Absent`
          *   - [[SqlConfig.TlsMode.Allow]] or [[SqlConfig.TlsMode.Prefer]] => `tls = Absent` (opportunistic upgrade handled at the backend layer)
          *   - [[SqlConfig.TlsMode.Require]] => `tls = Present(NetTlsConfig(trustAll = true))` (TLS required, no cert-chain or hostname check)
          *   - [[SqlConfig.TlsMode.VerifyCa]] => requires `sslrootcert`; fails with [[SqlConnectionException]] if absent
          *   - [[SqlConfig.TlsMode.VerifyFull]] => requires `sslrootcert`; fails with [[SqlConnectionException]] if absent
          *
          * `sslrootcert` is also stored in [[SqlConfig.caCertPath]] for programmatic use.
          *
          * All other config fields use [[SqlConfig.default]], which is what makes this the degenerate case of the general resolution: the
          * default declares no TLS of its own, so nothing of the URL's is overridden by it.
          */
        def toConfig(using Frame): SqlConfig < Abort[SqlConnectionException] =
            toConfig(SqlConfig.default)

        /** Resolves the TLS settings this URL declared onto `base`, which is the general form [[toConfig]] is the `SqlConfig.default` case
          * of.
          *
          *   - `tlsMode`: the URL's `sslmode` wins when the URL declares one, since it is the more specific statement about this endpoint.
          *     When the URL is silent `base`'s own `tlsMode` stands, so a programmatic `Require` is not reset to `Disable` by a URL that
          *     never mentioned TLS.
          *   - `caCertPath`: `base` wins if set, the URL's `sslrootcert` fills in otherwise.
          *   - `tls`: `base` wins if set, otherwise it is built from the resolved `tlsMode` and `caCertPath`, so the mode enforces TLS
          *     whichever of the two declared it.
          *
          * Every other field of `base` is preserved unchanged, so knobs like `metricsEnabled`, `closeGrace`, `streamBatchSize`, and every
          * attached [[SqlConfig.Extension]] reach the backend as-is.
          *
          * This is the only resolution of a URL's TLS declarations in the codebase, and it must stay the only one: a second copy of a rule that
          * decides whether a connection is encrypted can drift from this one and silently disagree about whether traffic is protected.
          */
        private[kyo] def toConfig(base: SqlConfig)(using Frame): SqlConfig < Abort[SqlConnectionException] =
            val tlsMode    = options.tlsMode.getOrElse(base.tlsMode)
            val caCertPath = base.caCertPath.orElse(options.sslRootCert)
            TlsContext.build(tlsMode, caCertPath).map { tls =>
                base.copy(tls = base.tls.orElse(tls), caCertPath = caCertPath, tlsMode = tlsMode)
            }
        end toConfig

    end Url

    object Url:

        /** The query-string values a URL declared.
          *
          * This is what the URL said, parsed: a value here is the user's declaration, and [[Url.toConfig]] is where the TLS ones become
          * [[SqlConfig]] settings. Every field is [[Absent]] when its key is not in the query string.
          *
          * @param applicationName
          *   value of the `application_name` key
          * @param connectTimeout
          *   value of the `connectTimeout` key, read as a whole number of seconds or a [[kyo.Duration]] spelling like `30s`. `0` means no timeout and reads back as
          *   [[Duration.Infinity]].
          * @param socketTimeout
          *   value of the `socketTimeout` key, read as a whole number of seconds or a [[kyo.Duration]] spelling like `30s`. `0` means no timeout and reads back as
          *   [[Duration.Infinity]].
          * @param tlsMode
          *   value of the `sslmode` key, in the [[SqlConfig.TlsMode]] vocabulary: `disable`, `allow`, `prefer`, `require`, `verify-ca`,
          *   `verify-full`. Any other value is a parse failure rather than a silent downgrade to plaintext.
          * @param sslRootCert
          *   value of the `sslrootcert` key, a path to a PEM-encoded CA certificate that validates the server certificate chain. Maps to
          *   [[SqlConfig.caCertPath]] and is required under [[SqlConfig.TlsMode.VerifyCa]] and [[SqlConfig.TlsMode.VerifyFull]].
          * @param extra
          *   every key/value pair none of the fields above claims, carried through unchanged
          */
        final case class Options(
            applicationName: Maybe[String],
            connectTimeout: Maybe[Duration],
            socketTimeout: Maybe[Duration],
            tlsMode: Maybe[TlsMode],
            sslRootCert: Maybe[String],
            extra: Map[String, String]
        ) derives CanEqual:

            /** The unclaimed `extra` pairs can carry credentials (`sslpassword`, a JDBC-style `password=...`), so only their keys are
              * rendered; every claimed field is a timeout, a mode, or a path.
              */
            override def toString: String =
                val maskedExtra = extra.keys.toList.sorted.mkString("extra(", ",", ")")
                s"Options($applicationName,$connectTimeout,$socketTimeout,$tlsMode,$sslRootCert,$maskedExtra)"
        end Options

        object Options:

            /** An empty query string: no key declared, no extras. */
            val default: Options = Options(Absent, Absent, Absent, Absent, Absent, Map.empty)

            /** Parses a URL query string (the part after `?`, without the `?`) into [[Options]].
              *
              * Keys the fields claim are read into them; every other pair lands in [[Options.extra]]. A claimed key whose value the field
              * cannot represent fails with [[SqlConnectionUrlOptionException]], so a mistyped TLS requirement is reported rather than
              * quietly ignored.
              */
            def parse(queryString: String)(using Frame): Result[SqlConnectionException, Options] =
                if queryString.isEmpty then Result.Success(default)
                else
                    val pairs: Map[String, String] =
                        queryString.split('&').collect {
                            case kv if kv.contains('=') =>
                                val idx = kv.indexOf('=')
                                kv.substring(0, idx) -> kv.substring(idx + 1)
                            case k if k.nonEmpty =>
                                k -> ""
                        }.toMap

                    for
                        connectTimeout <- parseSeconds("connectTimeout", pairs)
                        socketTimeout  <- parseSeconds("socketTimeout", pairs)
                        tlsMode        <- parseTlsMode(pairs)
                    yield Options(
                        applicationName = Maybe.fromOption(pairs.get("application_name")),
                        connectTimeout = connectTimeout,
                        socketTimeout = socketTimeout,
                        tlsMode = tlsMode,
                        sslRootCert = Maybe.fromOption(pairs.get("sslrootcert")),
                        extra = pairs.filterNot { case (k, _) => claimedKeys.contains(k) }
                    )
                    end for
                end if
            end parse

            private val claimedKeys: Set[String] =
                Set("application_name", "connectTimeout", "socketTimeout", "sslmode", "sslrootcert")

            /** Reads a timeout option: a whole number of seconds, decoding the `0` that both libpq and the JDBC drivers use for
              * "no timeout", or any [[kyo.Duration]] spelling (`500ms`, `30s`, `2min`) for a caller who wants a unit.
              */
            private def parseSeconds(key: String, pairs: Map[String, String])(using
                Frame
            ): Result[SqlConnectionException, Maybe[Duration]] =
                Maybe.fromOption(pairs.get(key)) match
                    case Absent => Result.Success(Absent)
                    case Present(raw) =>
                        raw.toIntOption match
                            case Some(0)          => Result.Success(Present(Duration.Infinity))
                            case Some(n) if n > 0 => Result.Success(Present(n.seconds))
                            case Some(_) =>
                                Result.fail(SqlConnectionUrlOptionException(
                                    key,
                                    raw,
                                    "a whole number of seconds (0 for no timeout) or a duration like '30s'"
                                ))
                            case None =>
                                Duration.parse(raw) match
                                    case Result.Success(d) => Result.Success(Present(d))
                                    case _ =>
                                        Result.fail(SqlConnectionUrlOptionException(
                                            key,
                                            raw,
                                            "a whole number of seconds (0 for no timeout) or a duration like '30s'"
                                        ))

            private def parseTlsMode(pairs: Map[String, String])(using Frame): Result[SqlConnectionException, Maybe[TlsMode]] =
                Maybe.fromOption(pairs.get("sslmode")) match
                    case Absent       => Result.Success(Absent)
                    case Present(raw) =>
                        // Per-char lowercasing: locale-independent, so "REQUIRE" still matches under a tr/az default locale.
                        raw.map(_.toLower) match
                            case "disable"     => Result.Success(Present(TlsMode.Disable))
                            case "allow"       => Result.Success(Present(TlsMode.Allow))
                            case "prefer"      => Result.Success(Present(TlsMode.Prefer))
                            case "require"     => Result.Success(Present(TlsMode.Require))
                            case "verify-ca"   => Result.Success(Present(TlsMode.VerifyCa))
                            case "verify-full" => Result.Success(Present(TlsMode.VerifyFull))
                            case _ =>
                                Result.fail(SqlConnectionUrlOptionException(
                                    "sslmode",
                                    raw,
                                    "one of disable, allow, prefer, require, verify-ca, verify-full"
                                ))
        end Options

        /** Parses a URL string into a [[Url]].
          *
          * Returns [[SqlConnectionException]] as a pure [[Result]] on any parse error (unknown scheme, missing port, malformed syntax,
          * unsupported option value).
          */
        def parse(raw: String)(using Frame): Result[SqlConnectionException, Url] =
            // Minimal URL parsing without java.net.URI to stay cross-platform and avoid
            // java.net.MalformedURLException for non-http schemes on some platforms.
            val schemeEnd = raw.indexOf("://")
            if schemeEnd < 0 then
                Result.fail(SqlConnectionUrlParseException(raw, ""))
            else
                // Per-char lowercasing: locale-independent scheme normalisation, so an upper-cased scheme still
                // resolves under a tr/az default locale.
                val scheme = raw.substring(0, schemeEnd).map(_.toLower)
                if scheme.isEmpty then
                    Result.fail(SqlConnectionUrlParseException(raw, scheme))
                else
                    val rest = raw.substring(schemeEnd + 3) // after "://"

                    // Split off query string
                    val (authority, queryString) = rest.indexOf('?') match
                        case -1  => (rest, "")
                        case idx => (rest.substring(0, idx), rest.substring(idx + 1))

                    // Parse user:password@host:port/db. Each delimiter is what DECLARES the field beside it, so its
                    // absence is Absent and never an empty value: no `@` means the URL named neither user nor password,
                    // while an `@` with nothing before it named an empty user and is Present("").
                    // The LAST `@` ends the userinfo (RFC 3986), so a password containing `@` parses whole instead
                    // of silently truncating and misreading the host.
                    val (userInfo, hostPart): (Maybe[String], String) = authority.lastIndexOf('@') match
                        case -1  => (Absent, authority)
                        case idx => (Present(authority.substring(0, idx)), authority.substring(idx + 1))

                    // Within a declared userinfo the colon does the same job: no colon means no password field at all,
                    // and a colon makes whatever follows it a declared credential, including the empty string, so
                    // `user:@host` said "empty password" and is Present("").
                    val (user, password): (Maybe[String], Maybe[String]) =
                        userInfo match
                            case Absent => (Absent, Absent)
                            case Present(info) =>
                                info.indexOf(':') match
                                    case -1  => (Present(info), Absent)
                                    case idx => (Present(info.substring(0, idx)), Present(info.substring(idx + 1)))

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
                                                Options.parse(queryString).map { options =>
                                                    Url(Address(scheme, h, port, db, user), password, options)
                                                }
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
                                                    val host = hostPort.substring(0, portIdx)
                                                    Options.parse(queryString).map { options =>
                                                        Url(Address(scheme, host, port, db, user), password, options)
                                                    }
                                        end if
                            end if
                    end match
                end if
            end if
        end parse

    end Url

end SqlConfig
