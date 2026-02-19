package kyo

import java.net.URI

/** Parsed URL holding pre-decomposed components to avoid build-then-parse round-trips.
  *
  * On the hot path (no baseUrl, no redirects), an `HttpUrl` is constructed directly from the request's Host header and stored path/query —
  * zero string allocations. The full URL string is only materialized on demand (e.g. for redirect resolution).
  */
final class HttpUrl private (
    val host: String,
    val port: Int,
    val rawPath: String,
    val rawQuery: Maybe[String]
):

    def ssl: Boolean = HttpUrl.isSsl(port)

    /** Pre-computed Host header value. Empty string when host is empty. */
    private val hostHeaderValue: String =
        if host.isEmpty then ""
        else
            val isIpv6     = host.contains(':')
            val nonStdPort = port > 0 && !HttpUrl.isDefaultPort(port)
            if !isIpv6 && !nonStdPort then host
            else
                val sb = new StringBuilder(host.length + 8)
                if isIpv6 then discard(sb.append('[').append(host).append(']'))
                else discard(sb.append(host))
                if nonStdPort then discard(sb.append(':').append(port))
                sb.toString
            end if

    /** Full URL string (e.g. "https://example.com:8080/path?q=1"). Computed on demand. */
    def full: String =
        if host.isEmpty then
            rawQuery match
                case Present(q) => s"$rawPath?$q"
                case Absent     => rawPath
        else
            val scheme      = if ssl then "https" else "http"
            val defaultPort = if ssl then HttpUrl.DefaultHttpsPort else HttpUrl.DefaultHttpPort
            val sb          = new StringBuilder(scheme.length + 3 + host.length + 8 + rawPath.length + rawQuery.fold(0)(_.length + 1))
            discard(sb.append(scheme).append("://"))
            if host.contains(':') then discard(sb.append('[').append(host).append(']'))
            else discard(sb.append(host))
            if port != defaultPort then discard(sb.append(':').append(port))
            discard(sb.append(rawPath))
            rawQuery match
                case Present(q) => discard(sb.append('?').append(q))
                case Absent     =>
            sb.toString
    end full

    /** Resolve a redirect Location header against this URL. */
    def resolve(location: String): HttpUrl =
        if location.startsWith("http://") || location.startsWith("https://") then
            HttpUrl(location)
        else
            HttpUrl(new URI(full).resolve(location).toString)

    /** Inject a Host header into the given headers if not already present. Uses this URL's host/port. */
    def ensureHostHeader(headers: HttpHeaders): HttpHeaders =
        if host.isEmpty || headers.contains("Host") then headers
        else headers.add("Host", hostHeaderValue)
    end ensureHostHeader

    override def toString: String = full

end HttpUrl

object HttpUrl:

    private inline val DefaultHttpPort  = 80
    private inline val DefaultHttpsPort = 443

    /** Returns true if the port is the standard HTTPS port (443). */
    def isSsl(port: Int): Boolean =
        port == DefaultHttpsPort

    /** Returns true if the port is a standard HTTP(S) default (80 or 443). */
    def isDefaultPort(port: Int): Boolean =
        port == DefaultHttpPort || port == DefaultHttpsPort

    /** Parse a full URL string into an HttpUrl. Handles scheme, host, port, IPv6, path, query, and fragment. */
    def apply(url: String): HttpUrl =
        parseUrlParts(url) { (scheme, host, port, rawPath, rawQuery) =>
            val ssl           = scheme.contains("https")
            val effectivePort = if port < 0 then (if ssl then DefaultHttpsPort else DefaultHttpPort) else port
            val rawHost = host.getOrElse("") match
                case h if h.startsWith("[") && h.endsWith("]") => h.substring(1, h.length - 1)
                case h                                         => h
            new HttpUrl(rawHost, effectivePort, rawPath, rawQuery)
        }

    /** Construct from pre-parsed components. */
    def apply(host: String, port: Int, rawPath: String, rawQuery: Maybe[String]): HttpUrl =
        new HttpUrl(host, port, rawPath, rawQuery)

    /** Parse a server-side request URI (path + optional query) into an HttpUrl with empty host.
      *
      * Used by backend implementations that receive path-only URIs (e.g. "/api/users?page=1").
      */
    private[kyo] def fromUri(uri: String): HttpUrl =
        val qIdx    = uri.indexOf('?')
        val hashIdx = uri.indexOf('#')
        if qIdx < 0 || (hashIdx >= 0 && hashIdx < qIdx) then
            val endIdx = if hashIdx >= 0 then hashIdx else uri.length
            val path   = if endIdx == 0 then "/" else uri.substring(0, endIdx)
            new HttpUrl("", DefaultHttpPort, path, Absent)
        else
            val path   = if qIdx == 0 then "/" else uri.substring(0, qIdx)
            val afterQ = uri.substring(qIdx + 1)
            val qHash  = afterQ.indexOf('#')
            val q      = if qHash >= 0 then afterQ.substring(0, qHash) else afterQ
            new HttpUrl("", DefaultHttpPort, path, if q.isEmpty then Absent else Present(q))
        end if
    end fromUri

    /** Build an HttpUrl from a Host header value, scheme, and path/query components.
      *
      * Centralizes the Host header parsing logic (IPv6 brackets, port extraction, scheme-based default port).
      */
    private[kyo] def fromHostHeader(
        hostHeader: Maybe[String],
        scheme: Maybe[String],
        rawPath: String,
        rawQuery: Maybe[String]
    ): HttpUrl =
        hostHeader match
            case Absent => new HttpUrl("", DefaultHttpPort, rawPath, rawQuery)
            case Present(h) =>
                if h.startsWith("[") then
                    val endBracket = h.indexOf(']')
                    if endBracket < 0 then new HttpUrl(h, DefaultHttpPort, rawPath, rawQuery)
                    else
                        val host = h.substring(1, endBracket)
                        if endBracket + 1 < h.length && h.charAt(endBracket + 1) == ':' then
                            new HttpUrl(host, h.substring(endBracket + 2).toInt, rawPath, rawQuery)
                        else
                            val defaultPort =
                                if scheme.contains("https") then DefaultHttpsPort else DefaultHttpPort
                            new HttpUrl(host, defaultPort, rawPath, rawQuery)
                        end if
                    end if
                else
                    val idx = h.indexOf(':')
                    if idx >= 0 then
                        new HttpUrl(h.substring(0, idx), h.substring(idx + 1).toInt, rawPath, rawQuery)
                    else
                        val defaultPort =
                            if scheme.contains("https") then DefaultHttpsPort else DefaultHttpPort
                        new HttpUrl(h, defaultPort, rawPath, rawQuery)
                    end if
    end fromHostHeader

    /** Build the effective HttpUrl for a request, applying baseUrl resolution if configured. */
    private[kyo] def effective(baseUrl: Maybe[String], request: HttpRequest[?]): HttpUrl =
        baseUrl match
            case Present(base) =>
                val httpUrl = request.httpUrl
                if httpUrl.host.nonEmpty then httpUrl
                else
                    val url           = request.url
                    val normalizedUrl = if url.startsWith("/") then url else "/" + url
                    HttpUrl(new URI(base).resolve(normalizedUrl).toString)
                end if
            case Absent =>
                request.httpUrl

    // --- Private URL parsing (zero-allocation, inline continuation-passing) ---

    /** Parse a full URL into (scheme, host, port, rawPath, rawQuery) without allocating intermediate objects.
      *
      * For path-only URLs (no scheme), scheme and host are Absent, port is -1.
      */
    private inline def parseUrlParts[A](url: String)(
        inline f: (Maybe[String], Maybe[String], Int, String, Maybe[String]) => A
    ): A =
        val schemeEnd = url.indexOf("://")
        if schemeEnd < 0 then
            splitPathQuery(url) { (rawPath, rawQuery) =>
                f(Absent, Absent, -1, rawPath, rawQuery)
            }
        else
            val schemeName  = url.substring(0, schemeEnd)
            val afterScheme = schemeEnd + 3
            val slashIdx    = url.indexOf('/', afterScheme)
            val qIdx        = url.indexOf('?', afterScheme)
            val hashIdx     = url.indexOf('#', afterScheme)
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
                    f(Present(schemeName), Present(host), port, finalPath, rawQuery)
                }
            }
        end if
    end parseUrlParts

    /** Split path?query, stripping scheme+authority if present and fragment per RFC 3986. */
    private inline def splitPathQuery[A](url: String)(
        inline f: (String, Maybe[String]) => A
    ): A =
        val pathStart = url.indexOf("://")
        val pathPortion =
            if pathStart < 0 then url
            else
                val afterScheme = pathStart + 3
                val slashIdx    = url.indexOf('/', afterScheme)
                if slashIdx < 0 then
                    val qMarkIdx = url.indexOf('?', afterScheme)
                    if qMarkIdx < 0 then "/" else url.substring(qMarkIdx)
                else url.substring(slashIdx)
                end if
        val hashIdx       = pathPortion.indexOf('#')
        val qIdx          = pathPortion.indexOf('?')
        val effectiveQIdx = if hashIdx >= 0 && (qIdx < 0 || hashIdx < qIdx) then -1 else qIdx
        if effectiveQIdx < 0 then
            val p0 = if hashIdx >= 0 then pathPortion.substring(0, hashIdx) else pathPortion
            val p  = if p0.isEmpty then "/" else p0
            f(p, Absent)
        else
            val p        = if effectiveQIdx == 0 then "/" else pathPortion.substring(0, effectiveQIdx)
            val afterQ   = pathPortion.substring(effectiveQIdx + 1)
            val qHashIdx = afterQ.indexOf('#')
            val q        = if qHashIdx >= 0 then afterQ.substring(0, qHashIdx) else afterQ
            f(p, if q.isEmpty then Absent else Present(q))
        end if
    end splitPathQuery

    /** Parse authority "host:port" into (host, port). Handles IPv6 "[::1]:port" and userinfo "user:pass@host". */
    private inline def parseAuthority[A](authority: String, scheme: String)(
        inline f: (String, Int) => A
    ): A =
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
                val host = hostPort.substring(0, endBracket + 1)
                if endBracket + 1 < hostPort.length && hostPort.charAt(endBracket + 1) == ':' then
                    val port = hostPort.substring(endBracket + 2).toInt
                    f(host, port)
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
                    f(host, portStr.toInt)
                else
                    f(hostPort, defaultPort)
                end if
            end if
        end if
    end parseAuthority

end HttpUrl
