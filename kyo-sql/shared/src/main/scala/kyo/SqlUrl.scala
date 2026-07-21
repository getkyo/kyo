package kyo

import kyo.*
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.internal.tls.TlsContext
import kyo.internal.tls.TlsMode

/** Parsed representation of a JDBC-style database URL.
  *
  * Supported schemes: `postgres://` and `mysql://`.
  *
  * URL format: `<scheme>://<user>:<password>@<host>:<port>/<db>[?<options>]`
  *
  * Port is required. Missing port is a parse error.
  *
  * @param address
  *   the [[SqlAddress]] extracted from the URL (driver, host, port, db, user)
  * @param password
  *   authentication password (may be empty)
  * @param options
  *   parsed query-string options including TLS mode, timeouts, and unrecognised extras
  */
final case class SqlUrl(
    address: SqlAddress,
    password: String,
    options: SqlUrl.Options
) derives CanEqual

object SqlUrl:

    /** Recognised SSL modes matching the PostgreSQL `sslmode` connection parameter. */
    enum SslMode derives CanEqual:
        case Disable, Allow, Prefer, Require, VerifyCa, VerifyFull
    end SslMode

    /** Parsed query-string options from the URL.
      *
      * @param sslmode
      *   TLS requirement level
      * @param sslrootcert
      *   path to a PEM-encoded CA certificate used to validate the server certificate chain; maps to [[SqlClientConfig.caCertPath]].
      *   Required when `sslmode=verify-ca` or `sslmode=verify-full`.
      * @param applicationName
      *   value for the `application_name` session parameter (PG) / connection attribute (MySQL)
      * @param connectTimeout
      *   maximum time allowed to establish the TCP connection
      * @param socketTimeout
      *   maximum idle time on an established socket before it is forcibly closed
      * @param params
      *   unrecognised query-string key/value pairs preserved for driver-specific use
      */
    final case class Options(
        sslmode: Maybe[SslMode],
        sslrootcert: Maybe[String],
        applicationName: Maybe[String],
        connectTimeout: Maybe[Duration],
        socketTimeout: Maybe[Duration],
        params: Map[String, String]
    ) derives CanEqual

    object Options:
        val default: Options = Options(Absent, Absent, Absent, Absent, Absent, Map.empty)
    end Options

    /** Converts a [[SqlUrl]] into a [[SqlClientConfig]] by mapping the `sslmode` and `sslrootcert` query parameters to TLS configuration
      * via [[kyo.internal.tls.TlsContext.build]].
      *
      * Mapping rules (delegated to [[kyo.internal.tls.TlsContext.build]]):
      *   - `sslmode=disable` or absent → `tls = Absent`
      *   - `sslmode=allow` or `sslmode=prefer` → `tls = Absent` (opportunistic upgrade handled at the backend layer)
      *   - `sslmode=require` → `tls = Present(NetTlsConfig(trustAll = true))` (TLS required, no cert-chain or hostname check)
      *   - `sslmode=verify-ca` → requires `sslrootcert`; fails with [[SqlException.Connection]] if absent
      *   - `sslmode=verify-full` → requires `sslrootcert`; fails with [[SqlException.Connection]] if absent
      *
      * `sslrootcert` is also stored in [[SqlClientConfig.caCertPath]] for programmatic use.
      *
      * All other config fields use [[SqlClientConfig.default]].
      */
    extension (url: SqlUrl)
        def toClientConfig(using Frame): SqlClientConfig < Abort[SqlException.Connection] =
            val caCertPath = url.options.sslrootcert
            val tlsMode: TlsMode = url.options.sslmode match
                case Present(SslMode.Disable)    => TlsMode.Disable
                case Present(SslMode.Allow)      => TlsMode.Allow
                case Present(SslMode.Prefer)     => TlsMode.Prefer
                case Present(SslMode.Require)    => TlsMode.Require
                case Present(SslMode.VerifyCa)   => TlsMode.VerifyCa
                case Present(SslMode.VerifyFull) => TlsMode.VerifyFull
                case Absent                      => TlsMode.Disable
            TlsContext.build(tlsMode, caCertPath).map { tls =>
                SqlClientConfig.default.copy(tls = tls, caCertPath = caCertPath, tlsMode = tlsMode)
            }
        end toClientConfig
    end extension

    /** Parses a URL string into a [[SqlUrl]].
      *
      * Returns [[SqlException.Connection]] as a pure [[Result]] on any parse error (unknown scheme, missing port, malformed syntax).
      */
    def parse(raw: String)(using Frame): Result[SqlException.Connection, SqlUrl] =
        parseResult(raw)

    // --- internal parse implementation ---

    private def parseResult(raw: String)(using Frame): Result[SqlException.Connection, SqlUrl] =
        // Minimal URL parsing without java.net.URI to stay cross-platform and avoid
        // java.net.MalformedURLException for non-http schemes on some platforms.
        val schemeEnd = raw.indexOf("://")
        if schemeEnd < 0 then
            Result.fail(SqlException.Connection(s"Missing scheme in URL: $raw", summon[Frame]))
        else
            val scheme = raw.substring(0, schemeEnd).toLowerCase
            if scheme != "postgres" && scheme != "mysql" then
                Result.fail(SqlException.Connection(s"Unsupported scheme '$scheme'; expected 'postgres' or 'mysql'", summon[Frame]))
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
                        Result.fail(SqlException.Connection(s"Missing database name in URL: $raw", summon[Frame]))
                    case idx =>
                        val hostPort = hostPart.substring(0, idx)
                        val db       = hostPart.substring(idx + 1)
                        // Handle IPv6 [::1]:5432
                        if hostPort.startsWith("[") then
                            val closeBracket = hostPort.indexOf(']')
                            if closeBracket < 0 then
                                Result.fail(SqlException.Connection(s"Malformed IPv6 host in URL: $raw", summon[Frame]))
                            else
                                val h        = hostPort.substring(1, closeBracket)
                                val portPart = hostPort.substring(closeBracket + 1)
                                if !portPart.startsWith(":") then
                                    Result.fail(SqlException.Connection(s"Port is required in URL: $raw", summon[Frame]))
                                else
                                    portPart.substring(1).toIntOption match
                                        case None =>
                                            Result.fail(SqlException.Connection(s"Port is required in URL: $raw", summon[Frame]))
                                        case Some(port) =>
                                            val address = SqlAddress(scheme, h, port, db, user)
                                            val options = parseOptions(queryString)
                                            Result.Success(SqlUrl(address, password, options))
                                end if
                            end if
                        else
                            hostPort.lastIndexOf(':') match
                                case -1 =>
                                    Result.fail(SqlException.Connection(s"Port is required in URL: $raw", summon[Frame]))
                                case portIdx =>
                                    val portStr = hostPort.substring(portIdx + 1)
                                    if portStr.isEmpty then
                                        Result.fail(SqlException.Connection(s"Port is required in URL: $raw", summon[Frame]))
                                    else
                                        portStr.toIntOption match
                                            case None =>
                                                Result.fail(SqlException.Connection(s"Port is required in URL: $raw", summon[Frame]))
                                            case Some(port) =>
                                                val host    = hostPort.substring(0, portIdx)
                                                val address = SqlAddress(scheme, host, port, db, user)
                                                val options = parseOptions(queryString)
                                                Result.Success(SqlUrl(address, password, options))
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
            val connectTimeout = Maybe.fromOption(pairs.get("connect_timeout"))
                .flatMap(s => Maybe.fromOption(s.toLongOption).map(_.seconds))
            val socketTimeout = Maybe.fromOption(pairs.get("socket_timeout"))
                .flatMap(s => Maybe.fromOption(s.toLongOption).map(_.seconds))

            val knownKeys = Set("sslmode", "sslrootcert", "application_name", "connect_timeout", "socket_timeout")
            val extra     = pairs.filterNot { case (k, _) => knownKeys.contains(k) }

            Options(sslmode, sslrootcert, appName, connectTimeout, socketTimeout, extra)
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

end SqlUrl
