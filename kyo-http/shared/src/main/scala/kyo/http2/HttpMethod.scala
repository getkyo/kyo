package kyo.http2

opaque type HttpMethod = String

object HttpMethod:
    given CanEqual[HttpMethod, HttpMethod] = CanEqual.derived

    val GET: HttpMethod     = "GET"
    val POST: HttpMethod    = "POST"
    val PUT: HttpMethod     = "PUT"
    val PATCH: HttpMethod   = "PATCH"
    val DELETE: HttpMethod  = "DELETE"
    val HEAD: HttpMethod    = "HEAD"
    val OPTIONS: HttpMethod = "OPTIONS"
    val TRACE: HttpMethod   = "TRACE"
    val CONNECT: HttpMethod = "CONNECT"

    /** Create a HttpMethod from a string name. */
    def unsafe(name: String): HttpMethod = name

    extension (m: HttpMethod)
        def name: String = m
end HttpMethod
