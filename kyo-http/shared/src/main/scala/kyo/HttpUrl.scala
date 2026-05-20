package kyo

import kyo.*
import scala.annotation.tailrec

/** Parsed URL with structured access to scheme, host, port, path, and query parameters.
  *
  * Construct via `HttpUrl.parse` for full URLs (`https://example.com/path?q=1`) or `HttpUrl.fromUri` for server-side request URIs
  * (`/path?q=1` with no scheme or host). `parse` fails with `Result[HttpException, HttpUrl]` on malformed input; `fromUri` never fails.
  *
  * Query parameters are lazily parsed on each call to `query(name)` or `queryAll(name)`. Results are not cached, so avoid repeated lookups
  * on the same name in a tight loop. URL decoding is lenient: malformed percent-encoding falls back to the raw value rather than throwing.
  *
  * The `baseUrl` property returns the URL without query parameters. Because query strings may carry sensitive data (API keys, tokens,
  * session IDs), `baseUrl` is the safe form for logging and error messages. All `HttpException` messages use it internally.
  *
  * Unix socket URLs use the `http+unix` or `https+unix` scheme following the urllib3 convention:
  * `http+unix://%2Fvar%2Frun%2Fdocker.sock/v1.43/containers/json`. During parsing the `+unix` suffix is consumed: `scheme` normalizes to
  * plain `http` or `https`, `host` defaults to `"localhost"`, and the decoded socket path is stored in `unixSocket`. The transport layer
  * checks `unixSocket` to decide between TCP and Unix domain socket connections.
  *
  * Note: `scheme` is `Absent` for path-only URLs produced by `fromUri`. The `ssl` property returns true when the scheme is `"https"` or the
  * port is 443.
  *
  * @see
  *   [[kyo.HttpRequest]] Carries the parsed URL for each request
  * @see
  *   [[kyo.HttpClient]] Accepts string URLs and parses them via `HttpUrl.parse`
  * @see
  *   [[kyo.HttpException]] Uses `baseUrl` to avoid leaking sensitive query data
  */
final case class HttpUrl(
    scheme: Maybe[String],
    host: String,
    port: Int,
    path: String,
    rawQuery: Maybe[String],
    unixSocket: Maybe[String] = Absent
) derives CanEqual:
    /** Full URL string (e.g. "https://example.com:8080/path?q=1").
      *
      * For Unix socket URLs, reconstructs the `http+unix://` or `https+unix://` format with URL-encoded socket path.
      */
    def full: String =
        scheme match
            case Absent =>
                rawQuery match
                    case Present(q) => s"$path?$q"
                    case Absent     => path
            case Present(s) =>
                unixSocket match
                    case Present(socketPath) =>
                        val unixScheme  = s + "+unix"
                        val encodedPath = java.net.URLEncoder.encode(socketPath, "UTF-8")
                        val sb = new StringBuilder(
                            unixScheme.length + 3 + encodedPath.length + path.length + rawQuery.fold(0)(_.length + 1)
                        )
                        discard(sb.append(unixScheme).append("://").append(encodedPath))
                        discard(sb.append(path))
                        rawQuery match
                            case Present(q) => discard(sb.append('?').append(q))
                            case Absent     =>
                        sb.toString
                    case Absent =>
                        val defaultPort = if s == "https" then HttpUrl.DefaultHttpsPort else HttpUrl.DefaultHttpPort
                        val sb          = new StringBuilder(s.length + 3 + host.length + 8 + path.length + rawQuery.fold(0)(_.length + 1))
                        discard(sb.append(s).append("://").append(host))
                        if port != defaultPort then discard(sb.append(':').append(port))
                        discard(sb.append(path))
                        rawQuery match
                            case Present(q) => discard(sb.append('?').append(q))
                            case Absent     =>
                        sb.toString

    def ssl: Boolean = scheme match
        case Present(s) => s.equalsIgnoreCase("https")
        case Absent     => port == HttpUrl.DefaultHttpsPort

    /** Returns the first value for the given query parameter name. */
    def query(name: String): Maybe[String] =
        rawQuery match
            case Absent     => Absent
            case Present(q) => HttpUrl.parseQueryParam(q, name)

    /** Returns all values for the given query parameter name. */
    def queryAll(name: String): Seq[String] =
        rawQuery match
            case Absent     => Seq.empty
            case Present(q) => HttpUrl.parseQueryParamAll(q, name)

    /** Parses the raw query string into an HttpQueryParams. Returns HttpQueryParams.empty if no query string is present. */
    def queryParams: HttpQueryParams =
        rawQuery match
            case Absent     => HttpQueryParams.empty
            case Present(q) => HttpUrl.parseAllQueryParams(q)

    /** URL without query params — safe for logging/error messages (no sensitive data). */
    def baseUrl: String =
        scheme match
            case Absent => path
            case Present(s) =>
                unixSocket match
                    case Present(socketPath) =>
                        val unixScheme  = s + "+unix"
                        val encodedPath = java.net.URLEncoder.encode(socketPath, "UTF-8")
                        val sb          = new StringBuilder(unixScheme.length + 3 + encodedPath.length + path.length)
                        discard(sb.append(unixScheme).append("://").append(encodedPath))
                        discard(sb.append(path))
                        sb.toString
                    case Absent =>
                        val defaultPort = if s == "https" then HttpUrl.DefaultHttpsPort else HttpUrl.DefaultHttpPort
                        val sb          = new StringBuilder(s.length + 3 + host.length + 8 + path.length)
                        discard(sb.append(s).append("://").append(host))
                        if port != defaultPort then discard(sb.append(':').append(port))
                        discard(sb.append(path))
                        sb.toString

    lazy val address: HttpAddress = unixSocket match
        case Present(p) => HttpAddress.Unix(p)
        case Absent     => HttpAddress.Tcp(host, port)

    override def toString: String = full

end HttpUrl

object HttpUrl:

    private val DefaultHttpPort  = 80
    private val DefaultHttpsPort = 443

    /** Parse a full URL string into an HttpUrl. */
    def parse(url: String)(using Frame): Result[HttpException, HttpUrl] =
        if url.isEmpty then Result.fail(HttpUrlParseException("", "URL cannot be empty"))
        else
            Result.catching[Exception] {
                doParse(url)
            }.mapFailure(e => HttpUrlParseException(url, e))

    /** Parse a server-side request URI (path + optional query) with no host. */
    def fromUri(uri: String): HttpUrl =
        val qIdx    = uri.indexOf('?')
        val hashIdx = uri.indexOf('#')
        if qIdx < 0 || (hashIdx >= 0 && hashIdx < qIdx) then
            val endIdx = if hashIdx >= 0 then hashIdx else uri.length
            val path   = if endIdx == 0 then "/" else uri.substring(0, endIdx)
            HttpUrl(Absent, "", DefaultHttpPort, path, Absent)
        else
            val path   = if qIdx == 0 then "/" else uri.substring(0, qIdx)
            val afterQ = uri.substring(qIdx + 1)
            val qHash  = afterQ.indexOf('#')
            val q      = if qHash >= 0 then afterQ.substring(0, qHash) else afterQ
            HttpUrl(Absent, "", DefaultHttpPort, path, if q.isEmpty then Absent else Present(q))
        end if
    end fromUri

    // --- Private parsing ---

    private def doParse(url: String): HttpUrl =
        val schemeEnd = url.indexOf("://")
        if schemeEnd < 0 then
            // Path-only URL
            splitPathQuery(url) { (path, query) =>
                HttpUrl(Absent, "", DefaultHttpPort, path, query)
            }
        else
            val schemeName  = url.substring(0, schemeEnd)
            val afterScheme = schemeEnd + 3
            val isUnix      = schemeName.equalsIgnoreCase("http+unix") || schemeName.equalsIgnoreCase("https+unix")
            if isUnix then
                parseUnixSocketUrl(url, schemeName, afterScheme)
            else
                val slashIdx = url.indexOf('/', afterScheme)
                val qIdx     = url.indexOf('?', afterScheme)
                val hashIdx  = url.indexOf('#', afterScheme)
                val authorityEnd =
                    val m0 = url.length
                    val m1 = if slashIdx >= 0 && slashIdx < m0 then slashIdx else m0
                    val m2 = if qIdx >= 0 && qIdx < m1 then qIdx else m1
                    if hashIdx >= 0 && hashIdx < m2 then hashIdx else m2
                end authorityEnd
                val authority = url.substring(afterScheme, authorityEnd)
                val remaining = if authorityEnd >= url.length then "/" else url.substring(authorityEnd)
                parseAuthority(authority, schemeName) { (host, port) =>
                    splitPathQuery(remaining) { (rawPath, rawQuery) =>
                        val fragIdx   = rawPath.indexOf('#')
                        val cleanPath = if fragIdx >= 0 then rawPath.substring(0, fragIdx) else rawPath
                        val finalPath = if cleanPath.isEmpty then "/" else cleanPath
                        HttpUrl(Present(schemeName), host, port, finalPath, rawQuery)
                    }
                }
            end if
        end if
    end doParse

    /** Parse a Unix socket URL: `http+unix://%2Fvar%2Frun%2Fdocker.sock/v1.43/containers/json`.
      *
      * The authority is the URL-encoded socket path. The `+unix` suffix is consumed: the scheme is normalized to plain `http` or `https`,
      * host defaults to `"localhost"`, and the decoded socket path is stored in `unixSocket`.
      */
    private def parseUnixSocketUrl(url: String, schemeName: String, afterScheme: Int): HttpUrl =
        // Normalize scheme: "http+unix" → "http", "https+unix" → "https"
        val normalizedScheme = schemeName.toLowerCase match
            case "http+unix"  => "http"
            case "https+unix" => "https"
            case other        => other // should not happen
        val defaultPort =
            if normalizedScheme == "https" then DefaultHttpsPort
            else DefaultHttpPort
        // Find end of authority: first unencoded slash after ://
        // The authority contains the URL-encoded socket path (e.g., %2Fvar%2Frun%2Fdocker.sock)
        val slashIdx = url.indexOf('/', afterScheme)
        val qIdx     = url.indexOf('?', afterScheme)
        val hashIdx  = url.indexOf('#', afterScheme)
        val authorityEnd =
            val m0 = url.length
            val m1 = if slashIdx >= 0 && slashIdx < m0 then slashIdx else m0
            val m2 = if qIdx >= 0 && qIdx < m1 then qIdx else m1
            if hashIdx >= 0 && hashIdx < m2 then hashIdx else m2
        end authorityEnd
        val encodedSocketPath = url.substring(afterScheme, authorityEnd)
        val socketPath        = decodeUrl(encodedSocketPath)
        val remaining         = if authorityEnd >= url.length then "/" else url.substring(authorityEnd)
        splitPathQuery(remaining) { (rawPath, rawQuery) =>
            val fragIdx   = rawPath.indexOf('#')
            val cleanPath = if fragIdx >= 0 then rawPath.substring(0, fragIdx) else rawPath
            val finalPath = if cleanPath.isEmpty then "/" else cleanPath
            HttpUrl(Present(normalizedScheme), "localhost", defaultPort, finalPath, rawQuery, Present(socketPath))
        }
    end parseUnixSocketUrl

    private inline def splitPathQuery[A](url: String)(inline f: (String, Maybe[String]) => A): A =
        val hashIdx       = url.indexOf('#')
        val qIdx          = url.indexOf('?')
        val effectiveQIdx = if hashIdx >= 0 && (qIdx < 0 || hashIdx < qIdx) then -1 else qIdx
        if effectiveQIdx < 0 then
            val p0 = if hashIdx >= 0 then url.substring(0, hashIdx) else url
            val p  = if p0.isEmpty then "/" else p0
            f(p, Absent)
        else
            val p      = if effectiveQIdx == 0 then "/" else url.substring(0, effectiveQIdx)
            val afterQ = url.substring(effectiveQIdx + 1)
            val qHash  = afterQ.indexOf('#')
            val q      = if qHash >= 0 then afterQ.substring(0, qHash) else afterQ
            f(p, if q.isEmpty then Absent else Present(q))
        end if
    end splitPathQuery

    private inline def parseAuthority[A](authority: String, scheme: String)(inline f: (String, Int) => A): A =
        val defaultPort =
            if scheme == "https" then DefaultHttpsPort
            else DefaultHttpPort
        val hostPort =
            if authority.startsWith("[") then authority
            else
                val atIdx = authority.indexOf('@')
                if atIdx < 0 then authority else authority.substring(atIdx + 1)
        if hostPort.startsWith("[") then
            val endBracket = hostPort.indexOf(']')
            if endBracket < 0 then f(hostPort, defaultPort)
            else
                val host = hostPort.substring(1, endBracket)
                if endBracket + 1 < hostPort.length && hostPort.charAt(endBracket + 1) == ':' then
                    val portStr = hostPort.substring(endBracket + 2)
                    f(host, Integer.parseInt(portStr))
                else
                    f(host, defaultPort)
                end if
            end if
        else
            val colonIdx = hostPort.lastIndexOf(':')
            if colonIdx < 0 then f(hostPort, defaultPort)
            else
                val host    = hostPort.substring(0, colonIdx)
                val portStr = hostPort.substring(colonIdx + 1)
                if portStr.nonEmpty && portStr.forall(_.isDigit) then
                    f(host, Integer.parseInt(portStr))
                else
                    f(hostPort, defaultPort)
                end if
            end if
        end if
    end parseAuthority

    // --- Query parameter parsing ---

    private def decodeUrl(s: String): String =
        Result.catching[Exception] {
            java.net.URLDecoder.decode(s, "UTF-8")
        }.getOrElse(s) // fall back to raw value on malformed encoding

    private def parseAllQueryParams(queryString: String): HttpQueryParams =
        @tailrec def loop(pos: Int, acc: List[(String, String)]): HttpQueryParams =
            if pos >= queryString.length then HttpQueryParams.init(acc.reverse*)
            else
                val ampIdx = queryString.indexOf('&', pos)
                val end    = if ampIdx < 0 then queryString.length else ampIdx
                val eqIdx  = queryString.indexOf('=', pos)
                val next   = if ampIdx < 0 then queryString.length else ampIdx + 1
                if eqIdx >= 0 && eqIdx < end then
                    val key   = decodeUrl(queryString.substring(pos, eqIdx))
                    val value = decodeUrl(queryString.substring(eqIdx + 1, end))
                    loop(next, (key, value) :: acc)
                else
                    val key = decodeUrl(queryString.substring(pos, end))
                    loop(next, (key, "") :: acc)
                end if
        loop(0, Nil)
    end parseAllQueryParams

    private def parseQueryParam(queryString: String, name: String): Maybe[String] =
        @tailrec def loop(pos: Int): Maybe[String] =
            if pos >= queryString.length then Absent
            else
                val ampIdx = queryString.indexOf('&', pos)
                val end    = if ampIdx < 0 then queryString.length else ampIdx
                val eqIdx  = queryString.indexOf('=', pos)
                val next   = if ampIdx < 0 then queryString.length else ampIdx + 1
                if eqIdx >= 0 && eqIdx < end then
                    val key = decodeUrl(queryString.substring(pos, eqIdx))
                    if key == name then Present(decodeUrl(queryString.substring(eqIdx + 1, end)))
                    else loop(next)
                else
                    val key = decodeUrl(queryString.substring(pos, end))
                    if key == name then Present("")
                    else loop(next)
                end if
        loop(0)
    end parseQueryParam

    private def parseQueryParamAll(queryString: String, name: String): Seq[String] =
        @tailrec def loop(pos: Int, acc: List[String]): Seq[String] =
            if pos >= queryString.length then acc.reverse
            else
                val ampIdx = queryString.indexOf('&', pos)
                val end    = if ampIdx < 0 then queryString.length else ampIdx
                val eqIdx  = queryString.indexOf('=', pos)
                val next   = if ampIdx < 0 then queryString.length else ampIdx + 1
                if eqIdx >= 0 && eqIdx < end then
                    val key = decodeUrl(queryString.substring(pos, eqIdx))
                    if key == name then loop(next, decodeUrl(queryString.substring(eqIdx + 1, end)) :: acc)
                    else loop(next, acc)
                else
                    val key = decodeUrl(queryString.substring(pos, end))
                    if key == name then loop(next, "" :: acc)
                    else loop(next, acc)
                end if
        loop(0, Nil)
    end parseQueryParamAll

end HttpUrl
