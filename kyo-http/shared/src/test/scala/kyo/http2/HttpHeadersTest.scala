package kyo.http2

import kyo.Absent
import kyo.Present
import kyo.Test
import kyo.hours

class HttpHeadersTest extends Test:

    override protected def useTestClient: Boolean = false

    "cookie" - {
        "parses single cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "session=abc123")
            assert(h.cookie("session") == Present("abc123"))
        }

        "parses first of multiple cookies" in {
            val h = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            assert(h.cookie("a") == Present("1"))
        }

        "parses middle cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            assert(h.cookie("b") == Present("2"))
        }

        "parses last cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            assert(h.cookie("c") == Present("3"))
        }

        "returns Absent for missing cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "a=1; b=2")
            assert(h.cookie("missing") == Absent)
        }

        "returns Absent when no Cookie header" in {
            assert(HttpHeaders.empty.cookie("anything") == Absent)
        }

        "handles whitespace around values" in {
            val h = HttpHeaders.empty.add("Cookie", "a = 1 ; b = 2 ")
            assert(h.cookie("a") == Present("1"))
            assert(h.cookie("b") == Present("2"))
        }

        "handles cookie value with equals sign" in {
            val h = HttpHeaders.empty.add("Cookie", "token=abc=def=ghi")
            assert(h.cookie("token") == Present("abc=def=ghi"))
        }

        "handles empty value" in {
            val h = HttpHeaders.empty.add("Cookie", "empty=; other=val")
            assert(h.cookie("empty") == Present(""))
            assert(h.cookie("other") == Present("val"))
        }

        "handles single cookie with no semicolons" in {
            val h = HttpHeaders.empty.add("Cookie", "only=one")
            assert(h.cookie("only") == Present("one"))
        }
    }

    "cookies" - {
        "parses all cookies" in {
            val h      = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            val result = h.cookies
            assert(result.length == 3)
            assert(result(0) == ("a", "1"))
            assert(result(1) == ("b", "2"))
            assert(result(2) == ("c", "3"))
        }

        "returns empty for no Cookie header" in {
            assert(HttpHeaders.empty.cookies == Seq.empty)
        }

        "parses single cookie" in {
            val h      = HttpHeaders.empty.add("Cookie", "k=v")
            val result = h.cookies
            assert(result.length == 1)
            assert(result(0) == ("k", "v"))
        }

        "handles whitespace" in {
            val h      = HttpHeaders.empty.add("Cookie", " a = 1 ; b = 2 ")
            val result = h.cookies
            assert(result.length == 2)
            assert(result(0) == ("a", "1"))
            assert(result(1) == ("b", "2"))
        }
    }

    "addCookie" - {
        "serializes simple cookie" in {
            val h = HttpHeaders.empty.addCookie("session", HttpCookie("abc123"))
            assert(h.get("Set-Cookie") == Present("session=abc123"))
        }

        "serializes cookie with maxAge" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("val").maxAge(1.hours))
            val v = h.get("Set-Cookie")
            assert(v.exists(_.contains("id=val")))
            assert(v.exists(_.contains("Max-Age=3600")))
        }

        "serializes cookie with domain" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").domain("example.com"))
            assert(h.get("Set-Cookie").exists(_.contains("Domain=example.com")))
        }

        "serializes cookie with path" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").path("/api"))
            assert(h.get("Set-Cookie").exists(_.contains("Path=/api")))
        }

        "serializes secure flag" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").secure(true))
            assert(h.get("Set-Cookie").exists(_.contains("Secure")))
        }

        "serializes httpOnly flag" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").httpOnly(true))
            assert(h.get("Set-Cookie").exists(_.contains("HttpOnly")))
        }

        "serializes sameSite" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v").sameSite(HttpCookie.SameSite.Strict))
            assert(h.get("Set-Cookie").exists(_.contains("SameSite=Strict")))
        }

        "serializes all attributes" in {
            val cookie = HttpCookie("token")
                .maxAge(24.hours)
                .domain("example.com")
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite(HttpCookie.SameSite.Lax)
            val h = HttpHeaders.empty.addCookie("session", cookie)
            val v = h.get("Set-Cookie").get
            assert(v.contains("session=token"))
            assert(v.contains("Max-Age=86400"))
            assert(v.contains("Domain=example.com"))
            assert(v.contains("Path=/"))
            assert(v.contains("Secure"))
            assert(v.contains("HttpOnly"))
            assert(v.contains("SameSite=Lax"))
        }

        "omits unset attributes" in {
            val h = HttpHeaders.empty.addCookie("id", HttpCookie("v"))
            val v = h.get("Set-Cookie").get
            assert(v == "id=v")
            assert(!v.contains("Max-Age"))
            assert(!v.contains("Domain"))
            assert(!v.contains("Path"))
            assert(!v.contains("Secure"))
            assert(!v.contains("HttpOnly"))
            assert(!v.contains("SameSite"))
        }

        "string value shorthand" in {
            val h = HttpHeaders.empty.addCookie("name", "value")
            assert(h.get("Set-Cookie") == Present("name=value"))
        }

        "multiple Set-Cookie headers" in {
            val h = HttpHeaders.empty
                .addCookie("a", "1")
                .addCookie("b", "2")
            val all = h.getAll("Set-Cookie")
            assert(all.length == 2)
            assert(all(0) == "a=1")
            assert(all(1) == "b=2")
        }

        "serializes Int cookie value" in {
            val h = HttpHeaders.empty.addCookie("count", HttpCookie(42))
            assert(h.get("Set-Cookie") == Present("count=42"))
        }
    }

end HttpHeadersTest
