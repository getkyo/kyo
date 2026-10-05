package kyo.internal.teams

import kyo.*
import kyo.TeamsException.UrlProblem
import kyo.internal.charset.Utf8

/** The URL rules of the module: which text is a service URL, which origins the token may be sent to, and how a path is built under a
  * base with each segment percent-encoded.
  */
private[kyo] object ServiceUrls:

    /** `text` as a service URL, or why it is not one. */
    def parse(text: String)(using Frame): Result[UrlProblem, HttpUrl] =
        val bad = text.indexWhere(c => c <= ' ' || c > '~')
        if bad >= 0 then Result.fail(UrlProblem.Character(bad))
        else if text.indexOf('#') >= 0 then Result.fail(UrlProblem.Fragment)
        else if userInfo(text) then Result.fail(UrlProblem.UserInfo)
        else
            HttpUrl.parse(text) match
                case Result.Success(url) =>
                    baseProblemOf(url) match
                        case Present(problem) => Result.fail(problem)
                        case Absent           => Result.succeed(url)
                case Result.Failure(e: HttpUrlParseException) if e.reason == HttpUrlParseException.Reason.Relative =>
                    Result.fail(UrlProblem.Scheme)
                case Result.Failure(e: HttpUrlParseException) if e.reason == HttpUrlParseException.Reason.EmptyHost =>
                    Result.fail(UrlProblem.Host)
                case _ => Result.fail(UrlProblem.Unparsable)
        end if
    end parse

    /** Why `url` is not an absolute http or https URL on a TCP host, with no user info, query or character kyo-http refuses. */
    def baseProblemOf(url: HttpUrl): Maybe[UrlProblem] =
        if !url.scheme.map(asciiLower).exists(s => s == "http" || s == "https") then Present(UrlProblem.Scheme)
        else if url.host.isEmpty then Present(UrlProblem.Host)
        else if url.unixSocket.nonEmpty then Present(UrlProblem.UnixSocket)
        else if url.host.contains('@') then Present(UrlProblem.UserInfo)
        else if url.rawQuery.nonEmpty then Present(UrlProblem.Query)
        else
            val at = url.full.indexWhere(c => c <= ' ' || c > '~')
            if at >= 0 then Present(UrlProblem.Character(at)) else Absent
        end if
    end baseProblemOf

    /** Why `url` is not an origin: a base URL whose path is `/`. */
    def originProblemOf(url: HttpUrl): Maybe[UrlProblem] =
        baseProblemOf(url).orElse(if url.path == "/" then Absent else Present(UrlProblem.Path))

    /** Whether `url`'s origin is one of `origins`, comparing scheme and host in any ASCII case and the port exactly. */
    def allowed(origins: Chunk[HttpUrl], url: HttpUrl): Boolean =
        origins.exists(origin =>
            origin.port == url.port &&
                origin.scheme.map(asciiLower) == url.scheme.map(asciiLower) &&
                asciiLower(origin.host) == asciiLower(url.host)
        )

    /** `base` with `segments` appended to its path, each percent-encoded as one segment. */
    def join(base: HttpUrl, segments: Chunk[String]): HttpUrl =
        val prefix = if base.path.endsWith("/") then base.path else base.path + "/"
        base.copy(path = prefix + segments.map(encode).mkString("/"), rawQuery = Absent)

    /** `text`'s UTF-8 bytes with every byte outside RFC 3986's unreserved characters percent-encoded, so the result is one segment. */
    def encode(text: String): String =
        Utf8.encode(text).map { b =>
            val c = (b & 0xff).toChar
            if (c >= 'A' && c <= 'Z') ||
                (c >= 'a' && c <= 'z') ||
                (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~'
            then c.toString
            else s"%${Hex.charAt((b >> 4) & 0xf)}${Hex.charAt(b & 0xf)}"
            end if
        }.mkString
    end encode

    private val Hex = "0123456789ABCDEF"

    // kyo-http's parser drops user info from the authority without a trace, so it is looked for in the text.
    private def userInfo(text: String): Boolean =
        val start = text.indexOf("://")
        if start < 0 then false
        else
            val from = start + 3
            val end  = Chunk('/', '?').map(c => text.indexOf(c, from)).filter(_ >= 0).foldLeft(text.length)(math.min)
            text.indexOf('@', from) match
                case at if at >= 0 && at < end => true
                case _                         => false
        end if
    end userInfo

    private def asciiLower(s: String): String = s.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)

end ServiceUrls
