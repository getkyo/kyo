package kyo.http2

import kyo.Absent
import kyo.Present
import kyo.Test
import kyo.hours

class HttpCookieTest extends Test:

    override protected def useTestClient: Boolean = false

    "Request" - {
        "stores value and codec" in {
            val c = HttpCookie.Request("session123")
            assert(c.value == "session123")
            assert(c.codec.encode("session123") == "session123")
        }

        "with Int codec" in {
            val c = HttpCookie.Request(42)
            assert(c.value == 42)
            assert(c.codec.encode(42) == "42")
            assert(c.codec.decode("42") == 42)
        }

        "explicit codec constructor" in {
            val codec = summon[HttpCodec[String]]
            val c     = HttpCookie.Request("val", codec)
            assert(c.value == "val")
        }

        "equality" in {
            assert(HttpCookie.Request("a").equals(HttpCookie.Request("a")))
            assert(!HttpCookie.Request("a").equals(HttpCookie.Request("b")))
        }
    }

    "Response" - {
        "minimal construction" in {
            val c = HttpCookie.Response("value123")
            assert(c.value == "value123")
            assert(c.maxAge == Absent)
            assert(c.domain == Absent)
            assert(c.path == Absent)
            assert(c.secure == false)
            assert(c.httpOnly == false)
            assert(c.sameSite == Absent)
        }

        "maxAge builder" in {
            val c = HttpCookie.Response("v").maxAge(1.hours)
            assert(c.maxAge == Present(1.hours))
            assert(c.value == "v")
        }

        "domain builder" in {
            val c = HttpCookie.Response("v").domain("example.com")
            assert(c.domain == Present("example.com"))
        }

        "path builder" in {
            val c = HttpCookie.Response("v").path("/api")
            assert(c.path == Present("/api"))
        }

        "secure builder" in {
            val c = HttpCookie.Response("v").secure(true)
            assert(c.secure == true)
        }

        "httpOnly builder" in {
            val c = HttpCookie.Response("v").httpOnly(true)
            assert(c.httpOnly == true)
        }

        "sameSite builder" - {
            "Strict" in {
                val c = HttpCookie.Response("v").sameSite(HttpCookie.Response.SameSite.Strict)
                assert(c.sameSite == Present(HttpCookie.Response.SameSite.Strict))
            }

            "Lax" in {
                val c = HttpCookie.Response("v").sameSite(HttpCookie.Response.SameSite.Lax)
                assert(c.sameSite == Present(HttpCookie.Response.SameSite.Lax))
            }

            "None" in {
                val c = HttpCookie.Response("v").sameSite(HttpCookie.Response.SameSite.None)
                assert(c.sameSite == Present(HttpCookie.Response.SameSite.None))
            }
        }

        "chaining all builders" in {
            val c = HttpCookie.Response("session")
                .maxAge(24.hours)
                .domain("example.com")
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite(HttpCookie.Response.SameSite.Strict)
            assert(c.value == "session")
            assert(c.maxAge == Present(24.hours))
            assert(c.domain == Present("example.com"))
            assert(c.path == Present("/"))
            assert(c.secure == true)
            assert(c.httpOnly == true)
            assert(c.sameSite == Present(HttpCookie.Response.SameSite.Strict))
        }

        "builders are immutable (return new instance)" in {
            val c1 = HttpCookie.Response("v")
            val c2 = c1.secure(true)
            assert(c1.secure == false)
            assert(c2.secure == true)
        }

        "with Int value" in {
            val c = HttpCookie.Response(42)
            assert(c.value == 42)
            assert(c.codec.encode(42) == "42")
        }
    }

    "sealed hierarchy" - {
        "Request is HttpCookie" in {
            val c: HttpCookie[String] = HttpCookie.Request("v")
            assert(c.value == "v")
        }

        "Response is HttpCookie" in {
            val c: HttpCookie[String] = HttpCookie.Response("v")
            assert(c.value == "v")
        }
    }

    "SameSite enum" - {
        "all values" in {
            val values = HttpCookie.Response.SameSite.values
            assert(values.length == 3)
            assert(values.contains(HttpCookie.Response.SameSite.Strict))
            assert(values.contains(HttpCookie.Response.SameSite.Lax))
            assert(values.contains(HttpCookie.Response.SameSite.None))
        }

        "equality" in {
            assert(HttpCookie.Response.SameSite.Strict == HttpCookie.Response.SameSite.Strict)
            assert(HttpCookie.Response.SameSite.Strict != HttpCookie.Response.SameSite.Lax)
        }
    }

    "toResponse" - {
        "converts request cookie to response" in {
            val req = HttpCookie.Request("session123")
            val res = req.toResponse
            assert(res.value == "session123")
            assert(res.maxAge == Absent)
            assert(res.secure == false)
        }

        "preserves codec" in {
            val req = HttpCookie.Request(42)
            val res = req.toResponse
            assert(res.value == 42)
            assert(res.codec.encode(42) == "42")
        }
    }

end HttpCookieTest
