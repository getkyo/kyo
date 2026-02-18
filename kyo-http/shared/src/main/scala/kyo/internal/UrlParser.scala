package kyo.internal

import kyo.Maybe
import kyo.Maybe.*

/** Zero-allocation URL parser using inline continuation-passing style.
  *
  * All parsing results are passed via inline callbacks so values stay as stack locals — no tuples, no case classes.
  */
private[kyo] object UrlParser:

    inline def DefaultHttpPort  = 80
    inline def DefaultHttpsPort = 443

    /** Parse a full URL into (scheme, host, port, rawPath, rawQuery) without allocating intermediate objects.
      *
      * For path-only URLs (no scheme), scheme and host are Absent, port is -1.
      */
    inline def parseUrlParts[A](url: String)(
        inline f: (Maybe[String], Maybe[String], Int, String, Maybe[String]) => A
    ): A =
        val schemeEnd = url.indexOf("://")
        if schemeEnd < 0 then
            // Path-only URL (no scheme/host) — used for relative paths
            splitPathQuery(url) { (rawPath, rawQuery) =>
                f(Absent, Absent, -1, rawPath, rawQuery)
            }
        else
            // Full URL — extract scheme, authority (host:port), and remaining path+query
            val schemeName  = url.substring(0, schemeEnd)
            val afterScheme = schemeEnd + 3
            val slashIdx    = url.indexOf('/', afterScheme)
            val qIdx        = url.indexOf('?', afterScheme)
            val hashIdx     = url.indexOf('#', afterScheme)
            // Authority ends at the first of /, ?, or # (whichever comes first)
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
    inline def splitPathQuery[A](url: String)(
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
        val hashIdx = pathPortion.indexOf('#')
        val qIdx    = pathPortion.indexOf('?')
        // Per RFC 3986, # delimits fragment — ? after # is part of the fragment, not a query
        val effectiveQIdx = if hashIdx >= 0 && (qIdx < 0 || hashIdx < qIdx) then -1 else qIdx
        if effectiveQIdx < 0 then
            val p0 = if hashIdx >= 0 then pathPortion.substring(0, hashIdx) else pathPortion
            val p  = if p0.isEmpty then "/" else p0
            f(p, Absent)
        else
            val p      = if effectiveQIdx == 0 then "/" else pathPortion.substring(0, effectiveQIdx)
            val afterQ = pathPortion.substring(effectiveQIdx + 1)
            // Strip fragment from query
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
        // Strip userinfo (user:pass@) but preserve IPv6 brackets
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

end UrlParser
