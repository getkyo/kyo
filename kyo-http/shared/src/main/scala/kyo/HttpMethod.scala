package kyo

/** HTTP request method as an opaque `String` wrapper.
  *
  * Standard methods are available as constants: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `HEAD`, `OPTIONS`, `TRACE`, `CONNECT`. Use
  * `HttpMethod.unsafe(name)` to create a custom method from an arbitrary string.
  */
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

        /** Whether the method is safe (RFC 9110 section 9.2.1): GET, HEAD, OPTIONS and TRACE, which ask for no change on the server. */
        def isSafe: Boolean = m == GET || m == HEAD || m == OPTIONS || m == TRACE

        /** Whether the method is idempotent (RFC 9110 section 9.2.2): the safe methods, PUT and DELETE, so a request may be sent again
          * after a failure that left its outcome unknown. PUT and DELETE are idempotent by contract, which an application may not honour;
          * the client takes the contract.
          */
        def isIdempotent: Boolean = isSafe || m == PUT || m == DELETE
    end extension
end HttpMethod
