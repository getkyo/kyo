package kyo

import kyo.*

class HttpExceptionTest extends BaseHttpTest:

    "HttpStatusException" - {
        "includes body when provided" in {
            val ex = HttpStatusException(HttpStatus.BadRequest, "POST", "http://x/y", "bad input")
            assert(ex.body == Maybe("bad input"))
            assert(ex.getMessage.contains("bad input"))
        }
        "omits body when absent" in {
            val ex = HttpStatusException(HttpStatus.InternalServerError, "GET", "http://x/y")
            assert(ex.body == Maybe.empty)
            assert(!ex.getMessage.contains("Body:"))
        }
        "truncates body over 500 chars" in {
            val longBody = "x" * 600
            val ex       = HttpStatusException(HttpStatus.BadRequest, "GET", "http://x/y", longBody)
            assert(ex.body == Maybe(longBody))
            assert(ex.getMessage.contains("..."))
            assert(!ex.getMessage.contains("x" * 600))
        }
        "strips query from url" in {
            val ex = HttpStatusException(HttpStatus.BadRequest, "GET", "http://x/y?token=abc", "err")
            assert(ex.url == "http://x/y")
        }
    }

    // Built here, away from the leaves: KyoException renders the source around its frame into getMessage, so a leaf that constructed the
    // exception next to the sentences it checks would find every sentence in every message.
    private def closedIn(phase: HttpConnectionClosedException.Phase): HttpConnectionClosedException =
        HttpConnectionClosedException(phase)

    "HttpConnectionClosedException" - {
        import HttpConnectionClosedException.Phase

        "is a connectivity failure, whatever its phase" in {
            val before: HttpConnectionException = HttpConnectionClosedException(Phase.BeforeHead)
            val body: HttpConnectionException   = HttpConnectionClosedException(Phase.BodyTruncated)
            val tls: HttpConnectionException    = HttpConnectionClosedException(Phase.TlsTruncated)
            val asAny: HttpException            = before
            assert(Seq(before, body, tls).forall(_.isInstanceOf[HttpConnectionException]))
            assert(!asAny.isInstanceOf[HttpDecodeException])
        }

        "builds its message from the phase" in {
            val beforeHead = "The connection closed before the message head arrived."
            val body       = "The connection closed before the body its framing declared was complete."
            val tls        =
                "The connection ended without the peer's TLS close_notify, so the close-framed body is incomplete (RFC 9112 section 9.8)."
            assert(closedIn(Phase.BeforeHead).getMessage.contains(beforeHead))
            assert(!closedIn(Phase.BeforeHead).getMessage.contains(body) && !closedIn(Phase.BeforeHead).getMessage.contains(tls))
            assert(closedIn(Phase.BodyTruncated).getMessage.contains(body))
            assert(!closedIn(Phase.BodyTruncated).getMessage.contains(beforeHead) &&
                !closedIn(Phase.BodyTruncated).getMessage.contains(tls))
            assert(closedIn(Phase.TlsTruncated).getMessage.contains(tls))
            assert(!closedIn(Phase.TlsTruncated).getMessage.contains(beforeHead) && !closedIn(Phase.TlsTruncated).getMessage.contains(body))
        }

        "two closes in the same phase are equal, in different phases not" in {
            assert(HttpConnectionClosedException(Phase.BeforeHead) == HttpConnectionClosedException(Phase.BeforeHead))
            assert(HttpConnectionClosedException(Phase.BeforeHead) != HttpConnectionClosedException(Phase.BodyTruncated))
        }
    }

end HttpExceptionTest
