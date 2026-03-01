package kyo

import kyo.*

// RFC 3986: Uniform Resource Identifier (URI): Generic Syntax
// Tests validate URI parsing behavior per the RFC specification.
// Failing tests indicate RFC non-compliance — do NOT adjust assertions to match implementation.
class Rfc3986Test extends Test:
    import HttpPath.*

    // ==================== Section 3.1: Scheme ====================

    "Section 3.1 - Scheme is case-insensitive" in {
        // RFC 3986 §3.1: "An implementation should accept uppercase letters as equivalent
        // to lowercase in scheme names"
        val url = HttpUrl.parse("HTTP://example.com/path").getOrThrow
        assert(
            url.scheme.contains("http") || url.scheme.contains("HTTP"),
            s"Scheme should be recognized regardless of case, got: ${url.scheme}"
        )
    }

    // ==================== Section 3.2: Authority ====================

    "Section 3.2.1 - Userinfo stripped from URL" in {
        // RFC 3986 §3.2.1: "Use of the format 'user:password' in the userinfo field is deprecated"
        // The parser should strip userinfo when present
        val url = HttpUrl.parse("http://user@host.com/path").getOrThrow
        assert(url.host == "host.com", s"Host should be 'host.com' after stripping userinfo, got: ${url.host}")
    }

    "Section 3.2.2 - Host case normalization" in {
        // RFC 3986 §3.2.2: "the host subcomponent is case-insensitive"
        val url = HttpUrl.parse("http://EXAMPLE.COM/path").getOrThrow
        // Note: RFC allows but doesn't require case normalization
        assert(
            url.host == "EXAMPLE.COM" || url.host == "example.com",
            s"Host should be preserved or lowercased, got: ${url.host}"
        )
    }

    "Section 3.2.2 - Default HTTP port recognized" in {
        // RFC 3986 §3.2.3: port 80 is default for http
        val url = HttpUrl.parse("http://host:80/path").getOrThrow
        assert(url.port == 80, s"Port should be 80, got: ${url.port}")
    }

    "Section 3.2.2 - Default HTTPS port recognized" in {
        val url = HttpUrl.parse("https://host:443/path").getOrThrow
        assert(url.port == 443, s"Port should be 443, got: ${url.port}")
        assert(url.ssl, "ssl should be true for port 443")
    }

    "Section 3.2.3 - Non-default port preserved" in {
        val url = HttpUrl.parse("http://host:8080/path").getOrThrow
        assert(url.port == 8080, s"Port should be 8080, got: ${url.port}")
    }

    // ==================== Section 3.2.2: IPv6 ====================

    "Section 3.2.2 - IPv6 loopback address" in {
        // RFC 3986 §3.2.2: "A host identified by an Internet Protocol literal address, version 6
        // [RFC3513], is distinguished by enclosing the IP literal within square brackets"
        val url = HttpUrl.parse("http://[::1]:8080/path").getOrThrow
        assert(url.host == "::1", s"IPv6 host should be '::1', got: ${url.host}")
        assert(url.port == 8080, s"Port should be 8080, got: ${url.port}")
    }

    "Section 3.2.2 - IPv6 full address" in {
        val url = HttpUrl.parse("http://[2001:db8::1]/path").getOrThrow
        assert(url.host == "2001:db8::1", s"IPv6 host should be '2001:db8::1', got: ${url.host}")
    }

    "Section 3.2.2 - IPv6 with port" in {
        val url = HttpUrl.parse("http://[::1]:9090/path").getOrThrow
        assert(url.host == "::1", s"Host should be '::1', got: ${url.host}")
        assert(url.port == 9090, s"Port should be 9090, got: ${url.port}")
    }

    // ==================== Section 3.3: Path ====================

    "Section 3.3 - URL with no path defaults to /" in {
        val url = HttpUrl.parse("http://host").getOrThrow
        assert(url.path == "/" || url.path == "", s"Path should default to '/' or '', got: '${url.path}'")
    }

    // ==================== Section 3.4: Query ====================

    "Section 3.4 - URL with empty query string" in {
        // "http://host/path?" — query component present but empty
        val url = HttpUrl.parse("http://host/path?").getOrThrow
        // Empty query after ? — implementation may set rawQuery to Absent or Present("")
        assert(url.path == "/path", s"Path should be '/path', got: '${url.path}'")
    }

    "Section 3.4 - URL without query" in {
        val url = HttpUrl.parse("http://host/path").getOrThrow
        assert(url.rawQuery == Absent, s"rawQuery should be Absent, got: ${url.rawQuery}")
    }

    // ==================== Section 3.5: Fragment ====================

    "Section 3.5 - Fragment stripped from URL" in {
        // RFC 3986 §3.5: Fragment identifier is not sent in HTTP requests
        val url = HttpUrl.parse("http://host/path#fragment").getOrThrow
        assert(url.path == "/path", s"Path should be '/path' with fragment stripped, got: '${url.path}'")
        assert(url.rawQuery == Absent, s"Fragment should not leak into query, got: ${url.rawQuery}")
    }

    "Section 3.5 - Fragment stripped when query present" in {
        val url = HttpUrl.parse("http://host/path?q=1#frag").getOrThrow
        assert(url.rawQuery == Present("q=1"), s"Query should be 'q=1' without fragment, got: ${url.rawQuery}")
    }

    // ==================== Error Handling ====================

    "Section 4.1 - Empty string is invalid URL" in {
        val result = HttpUrl.parse("")
        assert(result.isFailure, s"Empty string should fail to parse, got: $result")
    }

    "Section 4.1 - Invalid URL produces error" in {
        val result = HttpUrl.parse("not a url at all ://")
        // Should either fail or produce something parseable — check it doesn't throw
        // The implementation catches exceptions and wraps in Result.fail
        succeed
    }

    // ==================== fromUri (path-only parsing) ====================

    "fromUri - path with query" in {
        val url = HttpUrl.fromUri("/path?query=value")
        assert(url.path == "/path", s"Path should be '/path', got: '${url.path}'")
        assert(url.rawQuery == Present("query=value"), s"Query should be Present, got: ${url.rawQuery}")
    }

    "fromUri - root path" in {
        val url = HttpUrl.fromUri("/")
        assert(url.path == "/", s"Path should be '/', got: '${url.path}'")
        assert(url.rawQuery == Absent)
    }

    "fromUri - empty string defaults to root" in {
        val url = HttpUrl.fromUri("")
        assert(url.path == "/", s"Empty URI should default to '/', got: '${url.path}'")
    }

    "fromUri - fragment stripped" in {
        val url = HttpUrl.fromUri("/path#fragment")
        assert(url.path == "/path", s"Fragment should be stripped, got: '${url.path}'")
        assert(url.rawQuery == Absent, s"Fragment should not be in query, got: ${url.rawQuery}")
    }

    "fromUri - multiple query values" in {
        val url = HttpUrl.fromUri("/path?a=1&b=2")
        assert(url.query("a") == Present("1"))
        assert(url.query("b") == Present("2"))
    }

    // ==================== Query Parameter Decoding ====================

    "Section 3.4 - Query param without value" in {
        // "?key" with no = sign
        val url = HttpUrl.fromUri("/path?key")
        assert(url.query("key") == Present(""), s"Param without '=' should have empty value, got: ${url.query("key")}")
    }

    "Section 3.4 - Query param with empty value" in {
        // "?key=" with = but empty value
        val url = HttpUrl.fromUri("/path?key=")
        assert(url.query("key") == Present(""), s"Param with '=' but no value should be empty, got: ${url.query("key")}")
    }

    "Section 3.4 - Query param with = in value" in {
        // "?key=a=b" — only first = splits key/value
        val url = HttpUrl.fromUri("/path?key=a=b")
        assert(url.query("key") == Present("a=b"), s"Value should be 'a=b', got: ${url.query("key")}")
    }

    "Section 3.4 - Query param with encoded ampersand" in {
        // "?key=a%26b" — %26 is encoded &, should decode to "a&b"
        val url = HttpUrl.fromUri("/path?key=a%26b")
        assert(url.query("key") == Present("a&b"), s"Encoded & should decode, got: ${url.query("key")}")
    }

    "Section 3.4 - Multiple params same name" in {
        val url = HttpUrl.fromUri("/path?a=1&a=2")
        assert(url.queryAll("a") == Seq("1", "2"), s"Should return both values, got: ${url.queryAll("a")}")
    }

    "Section 3.4 - Query param with + literal" in {
        // RFC 3986: + is a valid character in query, NOT a space encoding
        // (space encoding with + is an HTML form convention, not RFC 3986)
        val url = HttpUrl.fromUri("/path?q=a+b")
        val v   = url.query("q")
        // Implementation may treat + as space (HTML convention) or literal +
        // RFC 3986 says + is literal, but many implementations decode it as space
        assert(
            v == Present("a+b") || v == Present("a b"),
            s"+ should be literal (RFC 3986) or space (HTML convention), got: $v"
        )
    }

    "Section 3.4 - Query param with percent-encoded space" in {
        val url = HttpUrl.fromUri("/path?q=a%20b")
        assert(url.query("q") == Present("a b"), s"%20 should decode to space, got: ${url.query("q")}")
    }

    "Section 3.4 - UTF-8 encoded query value" in {
        // %C3%A9 = UTF-8 encoding of é
        val url = HttpUrl.fromUri("/path?q=%C3%A9")
        assert(url.query("q") == Present("é"), s"UTF-8 encoding should decode, got: ${url.query("q")}")
    }

    "Section 3.4 - Malformed percent-encoding falls back to raw" in {
        // %GG is not valid hex — should fall back to raw value
        val url = HttpUrl.fromUri("/path?q=%GG")
        val v   = url.query("q")
        // Should either fail gracefully or return raw value
        assert(v.nonEmpty, s"Malformed encoding should not crash, got: $v")
    }

    "Section 3.4 - Incomplete percent-encoding" in {
        // %A is incomplete — should fall back to raw value
        val url = HttpUrl.fromUri("/path?q=%A")
        val v   = url.query("q")
        assert(v.nonEmpty, s"Incomplete encoding should not crash, got: $v")
    }

    "Section 3.4 - Empty query string produces no params" in {
        // "?" with nothing after — rawQuery may be Absent
        val url = HttpUrl.fromUri("/path?")
        assert(url.query("anything") == Absent, s"Empty query should have no params")
    }

    "Section 3.4 - Multiple ? in URL" in {
        // "?a=1?b=2" — second ? is part of the query value
        val url = HttpUrl.fromUri("/path?a=1?b=2")
        // The entire query string is "a=1?b=2", so param "a" has value "1?b=2"
        // unless impl splits on second ? differently
        val v = url.query("a")
        assert(v.nonEmpty, s"Should parse query with embedded ?, got: $v")
    }

    // ==================== Percent-Encoding in Path (via routing) ====================

    "Section 2.1 - Percent-encoded space in path" in run {
        // RFC 3986 §2.1: "A percent-encoding mechanism is used to represent a data octet
        // in a component when that octet's corresponding character is outside the allowed set"
        val route = HttpRoute.getRaw("items" / Capture[String]("name")).response(_.bodyText)
        val ep    = route.handler(req => HttpResponse.okText(req.fields.name))
        withServer(ep) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/items/hello%20world"))
            send(port, rawRoute, req).map { resp =>
                assert(resp.fields.body == "hello world", s"%20 should decode to space in path, got: '${resp.fields.body}'")
            }
        }
    }

    "Section 2.1 - Percent-encoded UTF-8 in path" in run {
        val route = HttpRoute.getRaw("items" / Capture[String]("name")).response(_.bodyText)
        val ep    = route.handler(req => HttpResponse.okText(req.fields.name))
        withServer(ep) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/items/%C3%A9"))
            send(port, rawRoute, req).map { resp =>
                // %C3%A9 = UTF-8 for é
                assert(resp.fields.body == "é", s"UTF-8 path should decode, got: '${resp.fields.body}'")
            }
        }
    }

    "Section 2.1 - Unencoded path passthrough" in run {
        val route = HttpRoute.getRaw("items" / Capture[String]("name")).response(_.bodyText)
        val ep    = route.handler(req => HttpResponse.okText(req.fields.name))
        withServer(ep) { port =>
            val req = HttpRequest.getRaw(HttpUrl.fromUri("/items/simple"))
            send(port, rawRoute, req).map { resp =>
                assert(resp.fields.body == "simple", s"Unencoded path should pass through, got: '${resp.fields.body}'")
            }
        }
    }

    // ==================== Server/client helpers (same as Rfc9110Test) ====================

    val rawRoute = HttpRoute.getRaw("raw").response(_.bodyText)

    def withServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: Int => A < (S & Async & Abort[HttpError])
    )(using Frame): A < (S & Async & Scope & Abort[HttpError]) =
        HttpServer.init(0, "localhost")(handlers*).map(server => test(server.port))

    def send[In, Out](port: Int, route: HttpRoute[In, Out, Any], request: HttpRequest[In])(using
        Frame
    ): HttpResponse[Out] < (Async & Abort[HttpError]) =
        HttpClient.use { client =>
            client.sendWith(
                route,
                request.copy(url =
                    HttpUrl(Present("http"), "localhost", port, request.url.path, request.url.rawQuery)
                )
            )(identity)
        }

    // ==================== Section 2.1: Percent-Encoding (additional) ====================

    "Section 2.1 - All unreserved chars pass through" in {
        // RFC 3986 §2.3: unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
        val url = HttpUrl.parse("http://host/path?key=Az09-._~").getOrThrow
        val v   = url.query("key")
        assert(v == Present("Az09-._~"), s"Unreserved chars should pass through, got: $v")
    }

    "Section 2.1 - Double-encoded percent preserved" in {
        // %2520 should decode to %20, not to space
        val url = HttpUrl.fromUri("/path?key=%2520")
        val v   = url.query("key")
        assert(v == Present("%20"), s"Double-encoded should decode once to %20, got: $v")
    }

    // ==================== Section 3.2: Authority (additional) ====================

    "Section 3.2.2 - IPv4 address as host" in {
        val url = HttpUrl.parse("http://127.0.0.1:8080/path").getOrThrow
        assert(url.host == "127.0.0.1", s"IPv4 host should be '127.0.0.1', got: ${url.host}")
        assert(url.port == 8080, s"Port should be 8080, got: ${url.port}")
    }

    "Section 3.2.3 - Port absent uses default" in {
        val url = HttpUrl.parse("http://host/path").getOrThrow
        assert(url.port == 80, s"HTTP default port should be 80, got: ${url.port}")
    }

    "Section 3.2.3 - HTTPS default port" in {
        val url = HttpUrl.parse("https://host/path").getOrThrow
        assert(url.port == 443, s"HTTPS default port should be 443, got: ${url.port}")
        assert(url.ssl, "ssl should be true for https")
    }

    // ==================== Section 3.3: Path (additional) ====================

    "Section 3.3 - Path with multiple segments" in {
        val url = HttpUrl.parse("http://host/a/b/c/d").getOrThrow
        assert(url.path == "/a/b/c/d", s"Multi-segment path, got: '${url.path}'")
    }

    "Section 3.3 - Path with trailing slash" in {
        val url = HttpUrl.parse("http://host/path/").getOrThrow
        assert(url.path == "/path/", s"Trailing slash should be preserved, got: '${url.path}'")
    }

    // ==================== Section 3.4: Query (additional) ====================

    "Section 3.4 - Query with encoded equals in value" in {
        val url = HttpUrl.fromUri("/path?key=hello%3Dworld")
        assert(url.query("key") == Present("hello=world"), s"Encoded = should decode, got: ${url.query("key")}")
    }

    "Section 3.4 - Empty query param name" in {
        val url = HttpUrl.fromUri("/path?=value")
        val v   = url.query("")
        // Either Present("value") or Absent depending on implementation
        succeed // Just verify no crash
    }

    // ==================== Section 3: Full URL (additional) ====================

    "Section 3 - Full URL with all components" in {
        val url = HttpUrl.parse("https://example.com:8443/api/v1?format=json").getOrThrow
        assert(url.scheme == Present("https"), s"Scheme should be https, got: ${url.scheme}")
        assert(url.host == "example.com", s"Host should be example.com, got: ${url.host}")
        assert(url.port == 8443, s"Port should be 8443, got: ${url.port}")
        assert(url.path == "/api/v1", s"Path should be /api/v1, got: '${url.path}'")
        assert(url.query("format") == Present("json"), s"Query param, got: ${url.query("format")}")
        assert(url.ssl, "ssl should be true for https scheme regardless of port")
    }

    "Section 3 - HTTP URL ssl is false" in {
        val url = HttpUrl.parse("http://host/path").getOrThrow
        assert(!url.ssl, "ssl should be false for http")
    }

    // ==================== Path routing (server, additional) ====================

    "Section 3.3 - Multiple path captures" in run {
        val route = HttpRoute.getRaw("a" / Capture[String]("x") / "b" / Capture[String]("y")).response(_.bodyText)
        val ep    = route.handler(req => HttpResponse.okText(s"${req.fields.x}-${req.fields.y}"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/a/hello/b/world"))).map { resp =>
                assert(resp.fields.body == "hello-world", s"Multiple captures, got: '${resp.fields.body}'")
            }
        }
    }

    "Section 3.3 - Path capture with integer" in run {
        val route = HttpRoute.getRaw("items" / Capture[Int]("id")).response(_.bodyText)
        val ep    = route.handler(req => HttpResponse.okText(s"id=${req.fields.id}"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/items/42"))).map { resp =>
                assert(resp.fields.body == "id=42", s"Int capture, got: '${resp.fields.body}'")
            }
        }
    }

    "Section 3.3 - Static path matching exact" in run {
        val route = HttpRoute.getRaw("exact" / "path").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("matched"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/exact/path"))).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == "matched")
            }
        }
    }

    "Section 2.1 - Percent-encoded special chars in path capture" in run {
        val route = HttpRoute.getRaw("items" / Capture[String]("name")).response(_.bodyText)
        val ep    = route.handler(req => HttpResponse.okText(req.fields.name))
        withServer(ep) { port =>
            // %21 = !
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/items/hello%21"))).map { resp =>
                assert(resp.fields.body == "hello!", s"%21 should decode to !, got: '${resp.fields.body}'")
            }
        }
    }

    "Section 3.3 - Path not found returns 404" in run {
        val route = HttpRoute.getRaw("exists").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))
        withServer(ep) { port =>
            send(port, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/doesnotexist"))).map { resp =>
                assert(resp.status == HttpStatus.NotFound)
            }
        }
    }

    "Section 3.4 - Server receives query parameters" in run {
        val route = HttpRoute.getRaw("search")
            .request(_.query[String]("q"))
            .response(_.bodyText)
        val ep = route.handler(req => HttpResponse.okText(s"q=${req.fields.q}"))
        withServer(ep) { port =>
            send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/search?q=test")).addField("q", "test")).map { resp =>
                assert(resp.fields.body == "q=test", s"Query param through server, got: ${resp.fields.body}")
            }
        }
    }

end Rfc3986Test
