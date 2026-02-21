package kyo.http2

import kyo.Absent
import kyo.Present
import kyo.Test
import kyo.hours

class HttpCookieTest extends Test:

    override protected def useTestClient: Boolean = false

    "stores value and codec" in {
        val c = HttpCookie("session123")
        assert(c.value == "session123")
        assert(c.codec.encode("session123") == "session123")
    }

    "with Int codec" in {
        val c = HttpCookie(42)
        assert(c.value == 42)
        assert(c.codec.encode(42) == "42")
        assert(c.codec.decode("42") == 42)
    }

    "explicit codec constructor" in {
        val codec = summon[HttpCodec[String]]
        val c     = HttpCookie("val", codec)
        assert(c.value == "val")
    }

    "equality" in {
        assert(HttpCookie("a").equals(HttpCookie("a")))
        assert(!HttpCookie("a").equals(HttpCookie("b")))
    }

    "minimal construction" in {
        val c = HttpCookie("value123")
        assert(c.value == "value123")
        assert(c.maxAge == Absent)
        assert(c.domain == Absent)
        assert(c.path == Absent)
        assert(c.secure == false)
        assert(c.httpOnly == false)
        assert(c.sameSite == Absent)
    }

    "maxAge builder" in {
        val c = HttpCookie("v").maxAge(1.hours)
        assert(c.maxAge == Present(1.hours))
        assert(c.value == "v")
    }

    "domain builder" in {
        val c = HttpCookie("v").domain("example.com")
        assert(c.domain == Present("example.com"))
    }

    "path builder" in {
        val c = HttpCookie("v").path("/api")
        assert(c.path == Present("/api"))
    }

    "secure builder" in {
        val c = HttpCookie("v").secure(true)
        assert(c.secure == true)
    }

    "httpOnly builder" in {
        val c = HttpCookie("v").httpOnly(true)
        assert(c.httpOnly == true)
    }

    "sameSite builder" - {
        "Strict" in {
            val c = HttpCookie("v").sameSite(HttpCookie.SameSite.Strict)
            assert(c.sameSite == Present(HttpCookie.SameSite.Strict))
        }

        "Lax" in {
            val c = HttpCookie("v").sameSite(HttpCookie.SameSite.Lax)
            assert(c.sameSite == Present(HttpCookie.SameSite.Lax))
        }

        "None" in {
            val c = HttpCookie("v").sameSite(HttpCookie.SameSite.None)
            assert(c.sameSite == Present(HttpCookie.SameSite.None))
        }
    }

    "chaining all builders" in {
        val c = HttpCookie("session")
            .maxAge(24.hours)
            .domain("example.com")
            .path("/")
            .secure(true)
            .httpOnly(true)
            .sameSite(HttpCookie.SameSite.Strict)
        assert(c.value == "session")
        assert(c.maxAge == Present(24.hours))
        assert(c.domain == Present("example.com"))
        assert(c.path == Present("/"))
        assert(c.secure == true)
        assert(c.httpOnly == true)
        assert(c.sameSite == Present(HttpCookie.SameSite.Strict))
    }

    "builders are immutable (return new instance)" in {
        val c1 = HttpCookie("v")
        val c2 = c1.secure(true)
        assert(c1.secure == false)
        assert(c2.secure == true)
    }

    "with Int value" in {
        val c = HttpCookie(42)
        assert(c.value == 42)
        assert(c.codec.encode(42) == "42")
    }

    "SameSite enum" - {
        "all values" in {
            val values = HttpCookie.SameSite.values
            assert(values.length == 3)
            assert(values.contains(HttpCookie.SameSite.Strict))
            assert(values.contains(HttpCookie.SameSite.Lax))
            assert(values.contains(HttpCookie.SameSite.None))
        }

        "equality" in {
            assert(HttpCookie.SameSite.Strict == HttpCookie.SameSite.Strict)
            assert(HttpCookie.SameSite.Strict != HttpCookie.SameSite.Lax)
        }
    }

end HttpCookieTest
