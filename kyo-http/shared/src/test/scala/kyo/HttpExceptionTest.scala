package kyo

import kyo.*

class HttpExceptionTest extends Test:

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

end HttpExceptionTest
