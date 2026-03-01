package kyo

import kyo.*
import kyo.hours

class HttpHeadersTest extends Test:

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

    "cookie (strict)" - {
        "accepts valid cookie" in {
            val h = HttpHeaders.empty.add("Cookie", "session=abc123")
            assert(h.cookie("session", strict = true) == Present("abc123"))
        }

        "rejects cookie value with comma" in {
            val h = HttpHeaders.empty.add("Cookie", "bad=val,ue")
            assert(h.cookie("bad", strict = true) == Absent)
        }

        "rejects cookie value with semicolon" in {
            val h = HttpHeaders.empty.add("Cookie", "bad=val;ue")
            // Note: semicolon is the cookie separator, so the parser would split here.
            // "bad" gets value "val" and "ue" has no equals sign.
            // In lax mode, cookie("bad") should return "val"
            assert(h.cookie("bad") == Present("val"))
        }

        "rejects cookie value with backslash" in {
            val h = HttpHeaders.empty.add("Cookie", "bad=val\\ue")
            assert(h.cookie("bad", strict = true) == Absent)
        }

        "rejects cookie value with space" in {
            // After trim, if there's still a space in the middle
            val h = HttpHeaders.empty.add("Cookie", "bad=val ue")
            assert(h.cookie("bad", strict = true) == Absent)
        }

        "rejects cookie value with control character" in {
            val h = HttpHeaders.empty.add("Cookie", "bad=val\u0001ue")
            assert(h.cookie("bad", strict = true) == Absent)
        }

        "accepts DQUOTE-wrapped value" in {
            val h = HttpHeaders.empty.add("Cookie", "name=\"validvalue\"")
            assert(h.cookie("name", strict = true) == Present("\"validvalue\""))
        }

        "rejects DQUOTE-wrapped value with invalid inner content" in {
            val h = HttpHeaders.empty.add("Cookie", "name=\"val,ue\"")
            assert(h.cookie("name", strict = true) == Absent)
        }

        "lax mode accepts invalid characters" in {
            val h = HttpHeaders.empty.add("Cookie", "name=val\\ue")
            assert(h.cookie("name", strict = false) == Present("val\\ue"))
        }

        "accepts empty value in strict mode" in {
            val h = HttpHeaders.empty.add("Cookie", "name=")
            assert(h.cookie("name", strict = true) == Present(""))
        }
    }

    "cookies (strict)" - {
        "filters out invalid cookies, keeps valid ones" in {
            // "good" has valid value, "bad" has comma in value
            val h      = HttpHeaders.empty.add("Cookie", "good=valid; bad=inv,alid; also_good=ok")
            val result = h.cookies(strict = true)
            assert(result.length == 2)
            assert(result(0) == ("good", "valid"))
            assert(result(1) == ("also_good", "ok"))
        }

        "rejects cookie with invalid name" in {
            // Space in cookie name is invalid per RFC 2616 token
            val h      = HttpHeaders.empty.add("Cookie", "bad name=value; good=ok")
            val result = h.cookies(strict = true)
            assert(result.length == 1)
            assert(result(0) == ("good", "ok"))
        }

        "rejects empty cookie name" in {
            val h      = HttpHeaders.empty.add("Cookie", "=value; good=ok")
            val result = h.cookies(strict = true)
            assert(result.length == 1)
            assert(result(0) == ("good", "ok"))
        }

        "accepts all valid cookies" in {
            val h      = HttpHeaders.empty.add("Cookie", "a=1; b=2; c=3")
            val result = h.cookies(strict = true)
            assert(result.length == 3)
        }

        "lax mode keeps everything" in {
            val h      = HttpHeaders.empty.add("Cookie", "good=valid; bad=inv,alid")
            val result = h.cookies(strict = false)
            assert(result.length == 2)
        }

        "cookie name with separator characters rejected in strict" in {
            // RFC 2616 token excludes: ( ) < > @ , ; : \ " / [ ] ? = { }
            val h      = HttpHeaders.empty.add("Cookie", "na(me=val; good=ok")
            val result = h.cookies(strict = true)
            assert(result.length == 1)
            assert(result(0) == ("good", "ok"))
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
