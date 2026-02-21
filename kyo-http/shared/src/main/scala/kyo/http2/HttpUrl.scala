package kyo.http2

import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.Result
import kyo.discard
import scala.annotation.tailrec

/** Parsed URL with structured access to components.
  *
  * Constructed via `HttpUrl.parse` which returns `Result[String, HttpUrl]` for safe handling of malformed URLs. Query parameters are lazily
  * parsed from the raw query string with safe URL decoding (falls back to raw value on malformed percent-encoding).
  */
final case class HttpUrl(
    scheme: Maybe[String],
    host: String,
    port: Int,
    path: String,
    rawQuery: Maybe[String]
):
    /** Full URL string (e.g. "https://example.com:8080/path?q=1"). */
    def full: String =
        scheme match
            case Absent =>
                rawQuery match
                    case Present(q) => s"$path?$q"
                    case Absent     => path
            case Present(s) =>
                val defaultPort = if s == "https" then HttpUrl.DefaultHttpsPort else HttpUrl.DefaultHttpPort
                val sb          = new StringBuilder(s.length + 3 + host.length + 8 + path.length + rawQuery.fold(0)(_.length + 1))
                discard(sb.append(s).append("://").append(host))
                if port != defaultPort then discard(sb.append(':').append(port))
                discard(sb.append(path))
                rawQuery match
                    case Present(q) => discard(sb.append('?').append(q))
                    case Absent     =>
                sb.toString

    def ssl: Boolean = port == HttpUrl.DefaultHttpsPort

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

    override def toString: String = full

end HttpUrl

object HttpUrl:

    private val DefaultHttpPort  = 80
    private val DefaultHttpsPort = 443

    /** Parse a full URL string into an HttpUrl. Returns failure message on malformed input. */
    def parse(url: String): Result[String, HttpUrl] =
        if url.isEmpty then Result.fail("URL cannot be empty")
        else
            Result.catching[Exception] {
                doParse(url)
            }.mapFailure(e => s"Invalid URL '$url': ${e.getMessage}")

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
            val (path, query) = splitPathQuery(url)
            HttpUrl(Absent, "", DefaultHttpPort, path, query)
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
            val authority           = url.substring(afterScheme, authorityEnd)
            val remaining           = if authorityEnd >= url.length then "/" else url.substring(authorityEnd)
            val (host, port)        = parseAuthority(authority, schemeName)
            val (rawPath, rawQuery) = splitPathQuery(remaining)
            val fragIdx             = rawPath.indexOf('#')
            val cleanPath           = if fragIdx >= 0 then rawPath.substring(0, fragIdx) else rawPath
            val finalPath           = if cleanPath.isEmpty then "/" else cleanPath
            HttpUrl(Present(schemeName), host, port, finalPath, rawQuery)
        end if
    end doParse

    private def splitPathQuery(url: String): (String, Maybe[String]) =
        val hashIdx       = url.indexOf('#')
        val qIdx          = url.indexOf('?')
        val effectiveQIdx = if hashIdx >= 0 && (qIdx < 0 || hashIdx < qIdx) then -1 else qIdx
        if effectiveQIdx < 0 then
            val p0 = if hashIdx >= 0 then url.substring(0, hashIdx) else url
            val p  = if p0.isEmpty then "/" else p0
            (p, Absent)
        else
            val p      = if effectiveQIdx == 0 then "/" else url.substring(0, effectiveQIdx)
            val afterQ = url.substring(effectiveQIdx + 1)
            val qHash  = afterQ.indexOf('#')
            val q      = if qHash >= 0 then afterQ.substring(0, qHash) else afterQ
            (p, if q.isEmpty then Absent else Present(q))
        end if
    end splitPathQuery

    private def parseAuthority(authority: String, scheme: String): (String, Int) =
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
            if endBracket < 0 then (hostPort, defaultPort)
            else
                val host = hostPort.substring(1, endBracket)
                if endBracket + 1 < hostPort.length && hostPort.charAt(endBracket + 1) == ':' then
                    val portStr = hostPort.substring(endBracket + 2)
                    (host, Integer.parseInt(portStr))
                else
                    (host, defaultPort)
                end if
            end if
        else
            val colonIdx = hostPort.lastIndexOf(':')
            if colonIdx < 0 then (hostPort, defaultPort)
            else
                val host    = hostPort.substring(0, colonIdx)
                val portStr = hostPort.substring(colonIdx + 1)
                if portStr.nonEmpty && portStr.forall(_.isDigit) then
                    (host, Integer.parseInt(portStr))
                else
                    (hostPort, defaultPort)
                end if
            end if
        end if
    end parseAuthority

    // --- Query parameter parsing ---

    private def decodeUrl(s: String): String =
        Result.catching[Exception] {
            java.net.URLDecoder.decode(s, "UTF-8")
        }.getOrElse(s) // fall back to raw value on malformed encoding

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
