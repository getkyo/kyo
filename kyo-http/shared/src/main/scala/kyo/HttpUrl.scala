package kyo

import kyo.*
import kyo.internal.Ascii
import scala.annotation.tailrec

/** Parsed URL with structured access to scheme, host, port, path, and query parameters.
  *
  * Construct via `HttpUrl.parse` for full URLs (`https://example.com/path?q=1`) or `HttpUrl.fromUri` for server-side request URIs
  * (`/path?q=1` with no scheme or host). `parse` fails with an [[kyo.HttpUrlParseException]] naming the RFC 3986 rule the input breaks;
  * `fromUri` never fails. `HttpUrl.resolve` resolves a relative reference, such as a redirect's `Location`, against a URL.
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
  * Note: `scheme` is `Absent` for path-only URLs produced by `fromUri`. The `ssl` property returns true for the TLS-bearing schemes
  * (`"https"` and the secure WebSocket scheme `"wss"`), or for a path-only URL whose port is 443.
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
                pathWithQuery
            case Present(s) =>
                unixSocket match
                    case Present(socketPath) =>
                        val unixScheme  = s + "+unix"
                        val encodedPath = internal.PercentEncoding.encode(socketPath, internal.PercentEncoding.Mode.Component)
                        val sb          = new StringBuilder(
                            unixScheme.length + 3 + encodedPath.length + path.length + rawQuery.fold(0)(_.length + 1)
                        )
                        discard(sb.append(unixScheme).append("://").append(encodedPath))
                        discard(sb.append(path))
                        rawQuery match
                            case Present(q) => discard(sb.append('?').append(q))
                            case Absent     =>
                        sb.toString
                    case Absent =>
                        val defaultPort = HttpUrl.schemeDefaultPort(s)
                        val sb          = new StringBuilder(s.length + 3 + host.length + 8 + path.length + rawQuery.fold(0)(_.length + 1))
                        discard(sb.append(s).append("://").append(host))
                        if port != defaultPort then discard(sb.append(':').append(port))
                        discard(sb.append(path))
                        rawQuery match
                            case Present(q) => discard(sb.append('?').append(q))
                            case Absent     =>
                        sb.toString

    /** Path plus query string, suitable as an HTTP/1.1 request target. */
    def pathWithQuery: String =
        rawQuery match
            case Present(q) => s"$path?$q"
            case Absent     => path

    def ssl: Boolean = scheme match
        case Present(s) => HttpUrl.isTlsScheme(s)
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

    /** URL without query params, safe for logging/error messages (no sensitive data). */
    def baseUrl: String =
        scheme match
            case Absent     => path
            case Present(s) =>
                unixSocket match
                    case Present(socketPath) =>
                        val unixScheme  = s + "+unix"
                        val encodedPath = internal.PercentEncoding.encode(socketPath, internal.PercentEncoding.Mode.Component)
                        val sb          = new StringBuilder(unixScheme.length + 3 + encodedPath.length + path.length)
                        discard(sb.append(unixScheme).append("://").append(encodedPath))
                        discard(sb.append(path))
                        sb.toString
                    case Absent =>
                        val defaultPort = HttpUrl.schemeDefaultPort(s)
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

    import HttpUrlParseException.Reason

    private val DefaultHttpPort  = 80
    private val DefaultHttpsPort = 443
    private val MaxPort          = 65535

    /** True for the schemes that ride TLS: `https` and the secure WebSocket scheme `wss`. */
    private[kyo] def isTlsScheme(scheme: String): Boolean =
        Ascii.equalsIgnoreCase(scheme, "https") || Ascii.equalsIgnoreCase(scheme, "wss")

    /** The default port for a scheme: 443 for the TLS schemes (`https`, `wss`), 80 otherwise (`http`, `ws`). */
    private def schemeDefaultPort(scheme: String): Int =
        if isTlsScheme(scheme) then DefaultHttpsPort else DefaultHttpPort

    /** Parses an absolute `http`, `https`, `ws`, `wss`, `http+unix` or `https+unix` URL, or a path from the root (`/path?query`), which a
      * client resolves against its `baseUrl`.
      *
      * The syntax is RFC 3986's, with RFC 9110 section 4.2.1's non-empty host. Anything else fails with an [[kyo.HttpUrlParseException]]
      * whose `reason` names the rule: a relative reference other than a path from the root, a scheme kyo-http does not send to, a missing
      * host, a host or port outside its grammar, a character no component allows, or a `%` that starts no escape. Two departures, both
      * deliberate: characters beyond ASCII pass (RFC 3987), since the send boundary refuses them with [[kyo.HttpNonAsciiException]] naming
      * the field; and userinfo is dropped. The fragment is dropped.
      */
    def parse(url: String)(using Frame): Result[HttpException, HttpUrl] =
        parseChecked(url).mapFailure(HttpUrlParseException(url, _))

    /** `reference` resolved against `base` (RFC 3986 section 5.2): an absolute URL is itself, `//authority/path` takes `base`'s scheme,
      * `/path` and `?query` keep `base`'s authority, and any other text is merged with the directory of `base`'s path. Dot segments are
      * removed, and the result passes every check of `parse`.
      */
    def resolve(base: HttpUrl, reference: String)(using Frame): Result[HttpException, HttpUrl] =
        resolveChecked(base, reference).mapFailure(HttpUrlParseException(reference, _))

    /** `url` parsed as a client's `baseUrl`: what `parse` accepts, with a scheme and either a host or a Unix socket. */
    private[kyo] def parseBase(url: String)(using Frame): Result[HttpUrlParseException, HttpUrl] =
        parseChecked(url).flatMap(parsed => baseProblem(parsed).fold(Result.succeed(parsed))(Result.fail))
            .mapFailure(HttpUrlParseException(url, _))

    /** `url` as a client's `baseUrl`, refused without a scheme or without both a host and a Unix socket. */
    private[kyo] def checkBase(url: HttpUrl)(using Frame): Result[HttpUrlParseException, HttpUrl] =
        baseProblem(url).fold(Result.succeed(url))(reason => Result.fail(HttpUrlParseException(url.full, reason)))

    private def baseProblem(base: HttpUrl): Maybe[Reason] =
        if base.scheme.isEmpty then Present(Reason.NotAbsolute)
        else if base.host.isEmpty && base.unixSocket.isEmpty then Present(Reason.EmptyHost)
        else Absent

    /** `url` sent by a client whose `baseUrl` is `base`: an absolute URL is itself, and a path from the root follows `base`'s path. The base
      * is a prefix, not an RFC 3986 base: `https://h/v1/` and `/users` give `https://h/v1/users`, where `resolve` gives `https://h/users`.
      */
    private[kyo] def underBase(base: HttpUrl, url: HttpUrl): HttpUrl =
        if url.scheme.nonEmpty then url
        else base.copy(path = base.path.stripSuffix("/") + url.path, rawQuery = url.rawQuery)

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

    private def parseChecked(url: String): Result[Reason, HttpUrl] =
        if url.isEmpty then Result.fail(Reason.Empty)
        else if url.startsWith("/") && !url.startsWith("//") then
            checkComponents(url, 0).map(_ => splitPathQuery(url)((path, query) => HttpUrl(Absent, "", DefaultHttpPort, path, query)))
        else
            val schemeEnd = url.indexOf("://")
            if schemeEnd < 0 then Result.fail(Reason.Relative)
            else
                val scheme = url.substring(0, schemeEnd)
                // Any other `scheme://` (ftp, gopher, file, ...) from untrusted input would reach the HTTP transport and its authority, an
                // SSRF surface, so only the HTTP family, the WebSocket schemes HttpClient.webSocket upgrades from, and the urllib3
                // Unix-socket variants are accepted.
                if !isScheme(scheme) then Result.fail(Reason.InvalidScheme)
                else if !SupportedSchemes.contains(Ascii.toLower(scheme)) then Result.fail(Reason.UnsupportedScheme(scheme))
                else
                    val start = schemeEnd + 3
                    val end   = authorityEnd(url, start)
                    checkComponents(url, end).flatMap { _ =>
                        val remaining = if end >= url.length then "/" else url.substring(end)
                        if Ascii.toLower(scheme).endsWith("+unix") then unixUrl(url, scheme, start, end, remaining)
                        else tcpUrl(url, scheme, start, end, remaining)
                    }
                end if
            end if
        end if
    end parseChecked

    private val SupportedSchemes = Set("http", "https", "ws", "wss", "http+unix", "https+unix")

    /** The index of the first `/`, `?` or `#` after `start`, where the authority ends. */
    private def authorityEnd(url: String, start: Int): Int =
        val found = url.indexWhere(c => c == '/' || c == '?' || c == '#', start)
        if found < 0 then url.length else found

    private def tcpUrl(url: String, scheme: String, start: Int, end: Int, remaining: String): Result[Reason, HttpUrl] =
        val at        = url.indexOf('@', start)
        val hostStart = if at >= 0 && at < end then at + 1 else start
        checkChars(url, start, hostStart - 1 max start, isUserInfo, Reason.InvalidAuthority(_)).flatMap { _ =>
            hostAndPortEnd(url, hostStart, end).flatMap { (host, portFrom) =>
                port(url, scheme, portFrom, end).map { port =>
                    splitPathQuery(remaining)((path, query) => HttpUrl(Present(scheme), host, port, path, query))
                }
            }
        }
    end tcpUrl

    /** The host, unbracketed when it is an IP literal, and the index where its port, if any, starts. */
    private def hostAndPortEnd(url: String, hostStart: Int, end: Int): Result[Reason, (String, Int)] =
        if hostStart < end && url.charAt(hostStart) == '[' then
            val close = url.indexOf(']', hostStart)
            if close < 0 || close >= end then Result.fail(Reason.InvalidAuthority(hostStart))
            else
                val literal = url.substring(hostStart + 1, close)
                if !isIpLiteral(literal) then Result.fail(Reason.InvalidAuthority(hostStart + 1))
                else if close + 1 < end && url.charAt(close + 1) != ':' then Result.fail(Reason.InvalidAuthority(close + 1))
                else Result.succeed((literal, close + 1))
            end if
        else
            val colon   = url.indexOf(':', hostStart)
            val hostEnd = if colon >= 0 && colon < end then colon else end
            if hostEnd == hostStart then Result.fail(Reason.EmptyHost)
            else
                checkChars(url, hostStart, hostEnd, isRegName, Reason.InvalidAuthority(_)).map(_ =>
                    (url.substring(hostStart, hostEnd), hostEnd)
                )
            end if
        end if
    end hostAndPortEnd

    /** The port after the `:` at `from`, or the scheme's default when there is no `:` or nothing follows it (RFC 3986 section 3.2.3). */
    private def port(url: String, scheme: String, from: Int, end: Int): Result[Reason, Int] =
        if from >= end || from + 1 == end then Result.succeed(schemeDefaultPort(scheme))
        else
            val digits = url.substring(from + 1, end)
            if digits.length <= 5 && digits.forall(c => c >= '0' && c <= '9') && digits.toInt <= MaxPort then Result.succeed(digits.toInt)
            else Result.fail(Reason.InvalidPort(from + 1))
    end port

    /** A Unix socket URL: `http+unix://%2Fvar%2Frun%2Fdocker.sock/v1.43/containers/json`.
      *
      * The authority is the URL-encoded socket path. The `+unix` suffix is consumed: the scheme is normalized to plain `http` or `https`,
      * host defaults to `"localhost"`, and the decoded socket path is stored in `unixSocket`.
      */
    private def unixUrl(url: String, scheme: String, start: Int, end: Int, remaining: String): Result[Reason, HttpUrl] =
        if end == start then Result.fail(Reason.EmptyHost)
        else
            checkChars(url, start, end, isRegName, Reason.InvalidAuthority(_)).map { _ =>
                val normalized = Ascii.toLower(scheme).stripSuffix("+unix")
                val socketPath = internal.PercentEncoding.decode(url.substring(start, end), internal.PercentEncoding.Mode.Component)
                splitPathQuery(remaining) { (path, query) =>
                    HttpUrl(Present(normalized), "localhost", schemeDefaultPort(normalized), path, query, Present(socketPath))
                }
            }
    end unixUrl

    /** Checks the path, query and fragment from `from` to the end: `pchar`, `/`, `?` and the `#` that starts the fragment. */
    private def checkComponents(url: String, from: Int): Result[Reason, Unit] =
        checkChars(url, from, url.length, c => isPchar(c) || c == '/' || c == '?' || c == '#', Reason.InvalidCharacter(_))

    /** Checks `url` from `from` until `until` against `allowed`, with `%` starting a two-hex-digit escape. */
    @tailrec private def checkChars(url: String, from: Int, until: Int, allowed: Char => Boolean, refused: Int => Reason)
        : Result[Reason, Unit] =
        if from >= until then Result.unit
        else
            val c = url.charAt(from)
            if c == '%' then
                if from + 2 < until && isHex(url.charAt(from + 1)) && isHex(url.charAt(from + 2)) then
                    checkChars(url, from + 3, until, allowed, refused)
                else Result.fail(Reason.InvalidPercentEncoding(from))
            else if allowed(c) then checkChars(url, from + 1, until, allowed, refused)
            else Result.fail(refused(from))
            end if
    end checkChars

    private def isAlpha(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
    private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
    private def isHex(c: Char): Boolean   = isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

    private def isScheme(s: String): Boolean =
        s.nonEmpty && isAlpha(s.charAt(0)) && s.forall(c => isAlpha(c) || isDigit(c) || c == '+' || c == '-' || c == '.')

    private def isUnreserved(c: Char): Boolean = isAlpha(c) || isDigit(c) || c == '-' || c == '.' || c == '_' || c == '~'
    private def isSubDelim(c: Char): Boolean   = "!$&'()*+,;=".indexOf(c) >= 0

    /** RFC 3986's reg-name characters (`%` is checked as an escape), widened by RFC 3987 to every character beyond ASCII. */
    private def isRegName(c: Char): Boolean  = isUnreserved(c) || isSubDelim(c) || c >= '\u0080'
    private def isUserInfo(c: Char): Boolean = isRegName(c) || c == ':'
    private def isPchar(c: Char): Boolean    = isRegName(c) || c == ':' || c == '@'

    /** An IPv6 address as RFC 3986's IP-literal holds it: hex digits, `:` and the `.` of an embedded IPv4 address, with at least one `:`. */
    private def isIpLiteral(s: String): Boolean =
        s.contains(':') && s.forall(c => isHex(c) || c == ':' || c == '.')

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

    // --- Resolution ---

    // A refusal's position is always an index into `reference`: a relative reference is checked before it is merged with the base path,
    // and a network-path reference's positions are shifted back past the scheme prefixed to it.
    private def resolveChecked(base: HttpUrl, reference: String): Result[Reason, HttpUrl] =
        def withoutDots(url: HttpUrl): HttpUrl              = url.copy(path = removeDotSegments(url.path))
        def onBase(target: String): Result[Reason, HttpUrl] =
            checkComponents(reference, 0).flatMap(_ =>
                parseChecked(target).map(url => base.copy(path = removeDotSegments(url.path), rawQuery = url.rawQuery))
            )
        if hasScheme(reference) then parseChecked(reference).map(withoutDots)
        else if reference.startsWith("//") then
            base.scheme match
                case Present(scheme) =>
                    parseChecked(s"$scheme:$reference").map(withoutDots).mapFailure(shifted(_, -(scheme.length + 1)))
                case Absent => Result.fail(Reason.Relative)
        else if reference.startsWith("/") then onBase(reference)
        else if reference.isEmpty || reference.startsWith("#") then Result.succeed(base)
        else if reference.startsWith("?") then onBase(base.path + reference)
        else
            // RFC 3986 section 5.2.3: a base with an authority and an empty path merges as if its path were "/".
            val directory = if base.path.isEmpty then "/" else base.path.substring(0, base.path.lastIndexOf('/') + 1)
            onBase(directory + reference)
        end if
    end resolveChecked

    private def shifted(reason: Reason, by: Int): Reason =
        reason match
            case Reason.InvalidAuthority(position)       => Reason.InvalidAuthority(position + by)
            case Reason.InvalidPort(position)            => Reason.InvalidPort(position + by)
            case Reason.InvalidCharacter(position)       => Reason.InvalidCharacter(position + by)
            case Reason.InvalidPercentEncoding(position) => Reason.InvalidPercentEncoding(position + by)
            case Reason.Empty | Reason.Relative | Reason.NotAbsolute | Reason.InvalidScheme | Reason.UnsupportedScheme(
                    _
                ) | Reason.EmptyHost =>
                reason

    /** True when `reference` starts with a scheme followed by `:`, before any `/`, `?` or `#`. */
    private def hasScheme(reference: String): Boolean =
        val colon = reference.indexOf(':')
        colon > 0 && isScheme(reference.substring(0, colon))

    /** RFC 3986 section 5.2.4, on a path that starts with `/`: a `.` segment is dropped and a `..` segment drops the one before it; a
      * trailing `.` or `..` leaves the path ending in `/`.
      */
    private def removeDotSegments(path: String): String =
        val segments = path.split("/", -1).drop(1)
        val kept     = segments.zipWithIndex.foldLeft(Chunk.empty[String]) { case (acc, (segment, index)) =>
            val last = index == segments.length - 1
            segment match
                case "."  => if last then acc :+ "" else acc
                case ".." => (if acc.isEmpty then acc else acc.dropRight(1)) ++ (if last then Chunk("") else Chunk.empty)
                case _    => acc :+ segment
            end match
        }
        "/" + kept.mkString("/")
    end removeDotSegments

    // --- Query parameter parsing ---

    /** Form mode, so a `+` reads as a space: HTML forms submitted with GET put their urlencoded fields in the query. */
    private def decodeUrl(s: String): String =
        internal.PercentEncoding.decode(s, internal.PercentEncoding.Mode.Form)

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
