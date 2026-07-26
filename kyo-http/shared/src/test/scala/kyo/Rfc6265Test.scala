package kyo

import kyo.*

// RFC 6265: HTTP State Management Mechanism (Cookies)
// Tests validate cookie serialization/parsing per the RFC specification.
// Failing tests indicate RFC non-compliance — do NOT adjust assertions to match implementation.
class Rfc6265Test extends BaseHttpTest:

    // ==================== Set-Cookie Serialization ====================

    "Section 4.1 - Max-Age=0 deletes cookie" in {
        // RFC 6265 §4.1.2.2: "If delta-seconds is less than or equal to zero (0),
        // let expiry-time be the earliest representable date and time."
        val cookie     = HttpCookie("val").maxAge(0.seconds)
        val serialized = HttpHeaders.serializeCookie("test", cookie)
        assert(serialized.contains("Max-Age=0"), s"Max-Age=0 should be serialized, got: $serialized")
    }

    "Section 4.1 - Empty cookie value" in {
        // RFC 6265 §4.1.1: cookie-value can be empty
        val cookie     = HttpCookie("")
        val serialized = HttpHeaders.serializeCookie("name", cookie)
        assert(serialized.startsWith("name="), s"Empty value should serialize as 'name=', got: $serialized")
    }

    "Section 4.1 - Printable ASCII in cookie value" in {
        val cookie     = HttpCookie("abcXYZ0123456789")
        val serialized = HttpHeaders.serializeCookie("test", cookie)
        assert(serialized.contains("abcXYZ0123456789"), s"Printable ASCII should pass through, got: $serialized")
    }

    // ==================== Cookie Header Parsing ====================

    "Section 4.2 - Cookie with base64 value containing ==" in {
        // RFC 6265 §4.2.1: Cookie header values may contain = in value
        val headers = HttpHeaders.empty.set("Cookie", "token=abc123==")
        val value   = headers.cookie("token")
        assert(value == Present("abc123=="), s"Base64 value with == should be preserved, got: $value")
    }

    "Section 4.2 - Many cookies (>20) in single header" in {
        val cookieStr = (1 to 25).map(i => s"c$i=v$i").mkString("; ")
        val headers   = HttpHeaders.empty.set("Cookie", cookieStr)
        val all       = headers.cookies
        assert(all.size == 25, s"Should parse all 25 cookies, got: ${all.size}")
    }

    "Section 4.2 - Cookie name with hyphen and underscore" in {
        // These are valid token characters per RFC 2616 Section 2.2
        val headers = HttpHeaders.empty.set("Cookie", "my-cookie_name=value")
        val value   = headers.cookie("my-cookie_name")
        assert(value == Present("value"), s"Hyphen/underscore names should work, got: $value")
    }

    "Section 4.2 - Strict mode rejects empty cookie name" in {
        val headers = HttpHeaders.empty.set("Cookie", "=value")
        val all     = headers.cookies(strict = true)
        assert(!all.exists(_._1.isEmpty), s"Empty cookie name should be rejected in strict mode, got: $all")
    }

    "Section 4.2 - DQUOTE-wrapped value in lax mode" in {
        val headers = HttpHeaders.empty.set("Cookie", "name=\"quoted\"")
        val value   = headers.cookie("name")
        // Lax mode may or may not strip quotes
        assert(value.nonEmpty, s"DQUOTE-wrapped value should be parseable, got: $value")
    }

    // A ';' inside a DQUOTE-wrapped cookie value must still delimit cookie-pairs. Jetty CVE-2023-26049 and Undertow
    // CVE-2023-4639 read a value beginning with '"' to the closing quote and swallowed every ';' in between, so a
    // later "JSESSIONID=..." pair was captured INSIDE the first value; an intermediary splitting on ';' and the
    // server disagreed on the cookie boundaries, which exfiltrates an HttpOnly session cookie into a readable one.
    "Section 4.2 - a quoted value does not swallow following cookies (CVE-2023-26049, CVE-2023-4639)" in {
        val headers = HttpHeaders.empty.set("Cookie", "DISPLAY=\"a; JSESSIONID=secret; b=c\"")
        val session = headers.cookie("JSESSIONID")
        assert(
            session == Present("secret"),
            s"';' inside a quoted value must still delimit, so JSESSIONID must parse separately, got: $session"
        )
    }

    // ==================== Response Cookie Parsing (Set-Cookie) ====================

    "Section 4.1 - Response cookie with Max-Age attribute" in {
        val headers = HttpHeaders.empty.add("Set-Cookie", "session=abc; Max-Age=3600")
        val value   = headers.responseCookie("session")
        assert(value == Present("abc"), s"Should extract value before attributes, got: $value")
    }

    "Section 4.1 - Response cookie with Domain attribute" in {
        val headers = HttpHeaders.empty.add("Set-Cookie", "session=xyz; Domain=example.com")
        val value   = headers.responseCookie("session")
        assert(value == Present("xyz"), s"Should extract value ignoring Domain attr, got: $value")
    }

    "Section 4.1 - Response cookie with multiple attributes" in {
        val headers = HttpHeaders.empty.add("Set-Cookie", "id=42; Max-Age=3600; Domain=example.com; Path=/; Secure; HttpOnly")
        val value   = headers.responseCookie("id")
        assert(value == Present("42"), s"Should extract value from complex Set-Cookie, got: $value")
    }

    "Section 4.1 - Multiple Set-Cookie headers" in {
        val headers = HttpHeaders.empty
            .add("Set-Cookie", "a=1; Path=/")
            .add("Set-Cookie", "b=2; Path=/")
        assert(headers.responseCookie("a") == Present("1"), s"Should find cookie 'a'")
        assert(headers.responseCookie("b") == Present("2"), s"Should find cookie 'b'")
    }

    // ==================== Section 4.1: Set-Cookie serialization (additional) ====================

    "Section 4.1 - Cookie with all attributes" in {
        val cookie = HttpCookie("val")
            .maxAge(3600.seconds)
            .domain("example.com")
            .path("/app")
            .secure(true)
            .httpOnly(true)
            .sameSite(HttpCookie.SameSite.Strict)
        val s = HttpHeaders.serializeCookie("full", cookie)
        assert(s.contains("full=val"), s"Name=value missing: $s")
        assert(s.contains("Max-Age=3600"), s"Max-Age missing: $s")
        assert(s.contains("Domain=example.com"), s"Domain missing: $s")
        assert(s.contains("Path=/app"), s"Path missing: $s")
        assert(s.contains("Secure"), s"Secure missing: $s")
        assert(s.contains("HttpOnly"), s"HttpOnly missing: $s")
        assert(s.contains("SameSite=Strict"), s"SameSite missing: $s")
    }

    "Section 4.1 - Cookie with SameSite=Lax" in {
        val cookie = HttpCookie("v").sameSite(HttpCookie.SameSite.Lax)
        val s      = HttpHeaders.serializeCookie("c", cookie)
        assert(s.contains("SameSite=Lax"), s"SameSite=Lax missing: $s")
    }

    "Section 4.1 - Cookie with SameSite=None" in {
        val cookie = HttpCookie("v").sameSite(HttpCookie.SameSite.None)
        val s      = HttpHeaders.serializeCookie("c", cookie)
        assert(s.contains("SameSite=None"), s"SameSite=None missing: $s")
    }

    "Section 4.1 - Cookie with Path attribute" in {
        val cookie = HttpCookie("v").path("/api")
        val s      = HttpHeaders.serializeCookie("c", cookie)
        assert(s.contains("Path=/api"), s"Path missing: $s")
    }

    "Section 4.1 - Cookie with Domain attribute" in {
        val cookie = HttpCookie("v").domain(".example.com")
        val s      = HttpHeaders.serializeCookie("c", cookie)
        assert(s.contains("Domain=.example.com"), s"Domain missing: $s")
    }

    "Section 4.1 - Cookie with Secure flag" in {
        val cookie = HttpCookie("v").secure(true)
        val s      = HttpHeaders.serializeCookie("c", cookie)
        assert(s.contains("Secure"), s"Secure missing: $s")
    }

    "Section 4.1 - Cookie with HttpOnly flag" in {
        val cookie = HttpCookie("v").httpOnly(true)
        val s      = HttpHeaders.serializeCookie("c", cookie)
        assert(s.contains("HttpOnly"), s"HttpOnly missing: $s")
    }

    "Section 4.1 - Cookie with positive Max-Age" in {
        val cookie = HttpCookie("v").maxAge(7200.seconds)
        val s      = HttpHeaders.serializeCookie("c", cookie)
        assert(s.contains("Max-Age=7200"), s"Max-Age=7200 missing: $s")
    }

    "Section 4.1 - Cookie name=value basic format" in {
        val cookie = HttpCookie("hello")
        val s      = HttpHeaders.serializeCookie("greeting", cookie)
        assert(s.startsWith("greeting=hello"), s"Should start with name=value, got: $s")
    }

    // ==================== Section 4.2: Cookie header parsing (additional) ====================

    "Section 4.2 - Single cookie in header" in {
        val headers = HttpHeaders.empty.set("Cookie", "session=abc123")
        assert(headers.cookie("session") == Present("abc123"))
    }

    "Section 4.2 - Multiple cookies semicolon separated" in {
        val headers = HttpHeaders.empty.set("Cookie", "a=1; b=2; c=3")
        assert(headers.cookie("a") == Present("1"))
        assert(headers.cookie("b") == Present("2"))
        assert(headers.cookie("c") == Present("3"))
    }

    "Section 4.2 - Missing cookie returns Absent" in {
        val headers = HttpHeaders.empty.set("Cookie", "a=1")
        assert(headers.cookie("missing") == Absent)
    }

    "Section 4.2 - Cookie with empty value" in {
        val headers = HttpHeaders.empty.set("Cookie", "empty=")
        assert(headers.cookie("empty") == Present(""), s"Empty value should be Present(''), got: ${headers.cookie("empty")}")
    }

    // ==================== Section 4.1: Response cookie parsing (additional) ====================

    "Section 4.1 - Response cookie not found returns Absent" in {
        val headers = HttpHeaders.empty.add("Set-Cookie", "other=val")
        assert(headers.responseCookie("missing") == Absent)
    }

    "Section 4.1 - Response cookie with empty value" in {
        val headers = HttpHeaders.empty.add("Set-Cookie", "name=; Path=/")
        val v       = headers.responseCookie("name")
        assert(v == Present(""), s"Empty Set-Cookie value should be Present(''), got: $v")
    }

    // ==================== Server round-trip tests ====================

    val rawRoute = HttpRoute.getRaw("raw").response(_.bodyText)

    def withServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: Int => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "localhost")(handlers*).map(server => test(server.port))

    def send[In, Out](port: Int, route: HttpRoute[In, Out, Any], request: HttpRequest[In])(using
        Frame
    ): HttpResponse[Out] < (Async & Abort[HttpException]) =
        HttpClient.use { client =>
            client.sendWith(
                route,
                request.copy(url =
                    HttpUrl(Present("http"), "localhost", port, request.url.path, request.url.rawQuery)
                )
            )(identity)
        }

    "Section 4.1 - Server sets cookie via Set-Cookie header" in {
        val route = HttpRoute.getRaw("set-cookie").response(_.bodyText)
        val ep = route.handler { _ =>
            HttpResponse.ok("ok")
                .addHeader("Set-Cookie", "session=abc123; Path=/; HttpOnly")
        }
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/set-cookie"))).map { resp =>
                assert(resp.status == HttpStatus.OK)
                val setCookie = resp.headers.getAll("Set-Cookie")
                assert(setCookie.nonEmpty, "Response should have Set-Cookie header")
                assert(setCookie.exists(_.contains("session=abc123")), s"Set-Cookie should contain session, got: $setCookie")
            }
        }
    }

    "Section 4.2 - Client sends cookie via Cookie header" in {
        val route = HttpRoute.getRaw("read-cookie")
            .request(_.headerOpt[String]("cookie"))
            .response(_.bodyText)
        val ep = route.handler { req =>
            val cookieHeader = req.headers.get("Cookie").getOrElse("none")
            HttpResponse.ok(s"cookie=$cookieHeader")
        }
        withServer(ep) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/read-cookie"))
                .setHeader("Cookie", "user=alice; theme=dark")
            send(port, rawRoute, req).map { resp =>
                assert(resp.fields.body.contains("user=alice"), s"Server should receive cookie, got: ${resp.fields.body}")
                assert(resp.fields.body.contains("theme=dark"), s"Server should receive all cookies, got: ${resp.fields.body}")
            }
        }
    }

    "Section 4.1 - Server sets multiple cookies" in {
        val route = HttpRoute.getRaw("multi-cookie").response(_.bodyText)
        val ep = route.handler { _ =>
            HttpResponse.ok("ok")
                .addHeader("Set-Cookie", "a=1; Path=/")
                .addHeader("Set-Cookie", "b=2; Path=/")
        }
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/multi-cookie"))).map { resp =>
                val setCookies = resp.headers.getAll("Set-Cookie")
                assert(setCookies.size >= 2, s"Should have at least 2 Set-Cookie headers, got: ${setCookies.size}")
            }
        }
    }

    // ==================== Set-Cookie Attribute Injection ====================

    // RFC 6265 section 4.1.1 makes ';' the delimiter BETWEEN attributes of a set-cookie-string, and excludes it (with ',',
    // DQUOTE, backslash, whitespace and controls) from cookie-value. A serializer that concatenates a value in without
    // enforcing that grammar lets whoever supplies the value write the ATTRIBUTES too, and cookie attributes are security
    // decisions: Domain widens who receives the cookie, Path widens where it is sent, and a re-stated Secure or HttpOnly
    // cannot be undone by the caller who believed it set them. Cookie values are routinely user-derived, which is what makes
    // this an injection rather than a formatting quirk.
    //
    // The grammar defines no escape, so there is nothing to escape TO: rejection is the only representation-preserving
    // answer, and these leaves assert a raise rather than a transformed string. Asserting on a rendered string would instead
    // presuppose that some encoding exists, which is the assumption that produces silent corruption.

    "Section 4.1.1 - cookie value carrying an attribute delimiter is rejected (GHSA-7qh7-rghh-698h)" in {
        val ex = intercept[IllegalArgumentException] {
            discard(HttpHeaders.serializeCookie("session", HttpCookie("x; Domain=evil.example")))
        }
        assert(ex.getMessage.contains("cookie value"), s"expected a cookie-value grammar failure, got: ${ex.getMessage}")
    }

    "Section 4.1.1 - cookie value carrying a flag attribute is rejected (GHSA-7qh7-rghh-698h)" in {
        // The reverse direction of the same defect: injecting an attribute the caller deliberately left off.
        val ex = intercept[IllegalArgumentException] {
            discard(HttpHeaders.serializeCookie("session", HttpCookie("x; HttpOnly")))
        }
        assert(ex.getMessage.contains("cookie value"), s"expected a cookie-value grammar failure, got: ${ex.getMessage}")
    }

    "Section 4.1.1 - cookie value carrying CR or LF is rejected (GHSA-7qh7-rghh-698h)" in {
        // CR/LF is header injection rather than attribute injection: it ends the field and starts another. RFC 9110
        // section 5.5 forbids it in a field value outright. Caught here at the grammar rather than downstream, so one bad
        // cookie is a failure at the call that built it instead of a torn response.
        val ex = intercept[IllegalArgumentException] {
            discard(HttpHeaders.serializeCookie("session", HttpCookie("x\r\nX-Injected: 1")))
        }
        assert(ex.getMessage.contains("cookie value"), s"expected a cookie-value grammar failure, got: ${ex.getMessage}")
    }

    "Section 4.1.1 - cookie name carrying an attribute delimiter is rejected (GHSA-7qh7-rghh-698h)" in {
        // The name reaches the same buffer by the same path and is just as often caller-supplied.
        val ex = intercept[IllegalArgumentException] {
            discard(HttpHeaders.serializeCookie("session; Domain=evil.example", HttpCookie("v")))
        }
        assert(ex.getMessage.contains("cookie name"), s"expected a cookie-name grammar failure, got: ${ex.getMessage}")
    }

    "Section 4.1.1 - Domain attribute carrying a delimiter is rejected (GHSA-7qh7-rghh-698h)" in {
        val ex = intercept[IllegalArgumentException] {
            discard(HttpHeaders.serializeCookie("session", HttpCookie("v").domain("example.com; Path=/admin")))
        }
        assert(ex.getMessage.contains("Domain"), s"expected a Domain grammar failure, got: ${ex.getMessage}")
    }

    // Path is appended exactly as rawly as Domain, so a fix validating only Domain would leave this open.
    "Section 4.1.1 - Path attribute carrying a delimiter is rejected (GHSA-7qh7-rghh-698h)" in {
        val ex = intercept[IllegalArgumentException] {
            discard(HttpHeaders.serializeCookie("session", HttpCookie("v").path("/app; Domain=evil.example")))
        }
        assert(ex.getMessage.contains("Path"), s"expected a Path grammar failure, got: ${ex.getMessage}")
    }

    // The over-strictness guard. Every leaf above asserts a refusal, so without this the grammar could be satisfied by
    // refusing everything; an ordinary cookie with every attribute set must still render.
    "Section 4.1.1 - an ordinary cookie with all attributes still renders" in {
        val cookie = HttpCookie("abc123")
            .maxAge(3600.seconds)
            .domain("example.com")
            .path("/app")
            .secure(true)
            .httpOnly(true)
            .sameSite(HttpCookie.SameSite.Strict)
        val s = HttpHeaders.serializeCookie("session", cookie)
        assert(s == "session=abc123; Max-Age=3600; Domain=example.com; Path=/app; Secure; HttpOnly; SameSite=Strict", s"got: $s")
    }

    // The predicates the raise is paired with, so a caller holding content that may not qualify can test it and take
    // its own path rather than relying on the exception. This is the isAscii/writeAscii pairing from GrowableByteBuffer.
    //
    // The leaf checks AGREEMENT rather than the predicates in isolation: a predicate that answered correctly but
    // disagreed with what serialization actually accepts would be worse than none, since a caller would clear the check
    // and then be raised on anyway. So each value is run through both.
    "Section 4.1.1 - the cookie grammar predicates agree with what serialization accepts" in {
        def serializes(value: String): Boolean =
            try
                discard(HttpHeaders.serializeCookie("session", HttpCookie(value)))
                true
            catch case _: IllegalArgumentException => false

        val values = List("abc123", "x; HttpOnly", "x; Domain=evil.example", "", "a=b", "x\r\ny", "plain")
        values.foreach { v =>
            assert(
                HttpHeaders.isValidCookieValue(v) == serializes(v),
                s"predicate and serializer disagree on \"${v.replace("\r", "\\r").replace("\n", "\\n")}\""
            )
        }

        def serializesName(name: String): Boolean =
            try
                discard(HttpHeaders.serializeCookie(name, HttpCookie("v")))
                true
            catch case _: IllegalArgumentException => false

        List("session", "sess ion", "a;b", "", "x-y_z").foreach { n =>
            assert(
                HttpHeaders.isValidCookieName(n) == serializesName(n),
                s"name predicate and serializer disagree on \"$n\""
            )
        }

        def serializesPath(path: String): Boolean =
            try
                discard(HttpHeaders.serializeCookie("session", HttpCookie("v").path(path)))
                true
            catch case _: IllegalArgumentException => false

        List("/app", "/app; Secure", "/", "/a\u0001b").foreach { a =>
            assert(
                HttpHeaders.isValidCookieAttribute(a) == serializesPath(a),
                s"attribute predicate and serializer disagree on \"$a\""
            )
        }
    }

end Rfc6265Test
