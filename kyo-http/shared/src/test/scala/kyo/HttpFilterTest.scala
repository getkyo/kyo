package kyo

import HttpRequest.Method
import HttpResponse.Status

class HttpFilterTest extends Test:

    val simpleHandler = HttpHandler.get("/test") { (_, _) => HttpResponse.ok("hello") }

    "HttpFilter" - {
        "custom filter wraps computation" in run {
            var called = false
            val customFilter = new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    called = true
                    next(request)
                end apply
            HttpFilter.let(customFilter) {
                for
                    port <- startTestServer(simpleHandler)
                    _    <- testGet(port, "/test")
                yield assert(called)
            }
        }

        "custom filter can modify response" in run {
            val customFilter = new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    next(request).map(_.addHeader("X-Custom", "value"))
            HttpFilter.let(customFilter) {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertHeader(response, "X-Custom", "value")
            }
        }

        "init creates request-transforming filter" in run {
            var receivedHeader: Maybe[String] = Absent
            val handler = HttpHandler.get("/test") { (_, req) =>
                receivedHeader = req.header("X-Init-Test")
                HttpResponse.ok
            }
            HttpFilter.let(HttpFilter.request(_.addHeader("X-Init-Test", "works"))) {
                for
                    port <- startTestServer(handler)
                    _    <- testGet(port, "/test")
                yield assert(receivedHeader == Present("works"))
            }
        }
    }

    "HttpFilter.server.cors" - {
        "adds CORS headers to response" in run {
            HttpFilter.server.cors().enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertHeader(response, "Access-Control-Allow-Origin", "*")
            }
        }

        "handles preflight OPTIONS request" in run {
            val optionsHandler = HttpHandler.options("/test") { (_, _) => HttpResponse.ok }
            HttpFilter.server.cors().enable {
                for
                    port <- startTestServer(simpleHandler, optionsHandler)
                    response <- HttpClient.send(
                        HttpRequest.options(s"http://localhost:$port/test")
                    )
                yield
                    assertStatus(response, Status.NoContent)
                    assertHeader(response, "Access-Control-Allow-Origin", "*")
                    assertHasHeader(response, "Access-Control-Allow-Methods")
            }
        }

        "custom origin" in run {
            HttpFilter.server.cors(allowOrigin = "https://example.com").enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertHeader(response, "Access-Control-Allow-Origin", "https://example.com")
            }
        }

        "empty origin throws" in {
            assertThrows[IllegalArgumentException] {
                HttpFilter.server.cors(allowOrigin = "")
            }
        }
    }

    "HttpFilter.server.basicAuth" - {
        "success with valid credentials" in run {
            HttpFilter.let(HttpFilter.server.basicAuth((u, p) => u == "admin" && p == "secret")) {
                for
                    port <- startTestServer(simpleHandler)
                    response <- HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/test", Seq("Authorization" -> "Basic YWRtaW46c2VjcmV0"))
                    )
                yield assertStatus(response, Status.OK)
            }
        }

        "failure with invalid credentials" in run {
            HttpFilter.let(HttpFilter.server.basicAuth((u, p) => u == "admin" && p == "secret")) {
                for
                    port <- startTestServer(simpleHandler)
                    response <- HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/test", Seq("Authorization" -> "Basic d3Jvbmc6d3Jvbmc="))
                    )
                yield
                    assertStatus(response, Status.Unauthorized)
                    assertHasHeader(response, "WWW-Authenticate")
            }
        }

        "failure with missing credentials" in run {
            HttpFilter.let(HttpFilter.server.basicAuth((_, _) => true)) {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertStatus(response, Status.Unauthorized)
            }
        }
    }

    "HttpFilter.server.bearerAuth" - {
        "success with valid token" in run {
            HttpFilter.let(HttpFilter.server.bearerAuth(token => token == "valid-token")) {
                for
                    port <- startTestServer(simpleHandler)
                    response <- HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/test", Seq("Authorization" -> "Bearer valid-token"))
                    )
                yield assertStatus(response, Status.OK)
            }
        }

        "failure with invalid token" in run {
            HttpFilter.let(HttpFilter.server.bearerAuth(token => token == "valid-token")) {
                for
                    port <- startTestServer(simpleHandler)
                    response <- HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/test", Seq("Authorization" -> "Bearer invalid"))
                    )
                yield assertStatus(response, Status.Unauthorized)
            }
        }
    }

    "HttpFilter.server.etag" - {
        "adds ETag header" in run {
            HttpFilter.let(HttpFilter.server.etag) {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertHasHeader(response, "ETag")
            }
        }
    }

    "HttpFilter.server.conditionalRequests" - {
        "returns 304 on matching ETag" in run {
            HttpFilter.let(HttpFilter.server.conditionalRequests) {
                for
                    port <- startTestServer(simpleHandler)
                    r1   <- testGet(port, "/test")
                    etag = r1.header("ETag").getOrElse("")
                    r2 <- HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/test", Seq("If-None-Match" -> etag))
                    )
                yield assertStatus(r2, Status.NotModified)
            }
        }
    }

    "HttpFilter.server.securityHeaders()" - {
        "adds security headers" in run {
            HttpFilter.let(HttpFilter.server.securityHeaders()) {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield
                    assertHeader(response, "X-Content-Type-Options", "nosniff")
                    assertHeader(response, "X-Frame-Options", "DENY")
            }
        }
    }

    "filter composition" - {
        "multiple filters compose with andThen" in run {
            HttpFilter.server.cors().andThen(HttpFilter.server.securityHeaders()).enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield
                    assertHasHeader(response, "Access-Control-Allow-Origin")
                    assertHasHeader(response, "X-Frame-Options")
            }
        }

        "multiple filters compose with nested enable" in run {
            HttpFilter.server.cors().enable {
                HttpFilter.server.securityHeaders().enable {
                    for
                        port     <- startTestServer(simpleHandler)
                        response <- testGet(port, "/test")
                    yield
                        assertHasHeader(response, "Access-Control-Allow-Origin")
                        assertHasHeader(response, "X-Frame-Options")
                }
            }
        }

        "auth failure short-circuits" in run {
            var handlerCalled = false
            val handler = HttpHandler.get("/test") { (_, _) =>
                handlerCalled = true
                HttpResponse.ok
            }
            HttpFilter.server.basicAuth((_, _) => false).enable {
                for
                    port     <- startTestServer(handler)
                    response <- testGet(port, "/test")
                yield
                    assertStatus(response, Status.Unauthorized)
                    assert(!handlerCalled)
            }
        }
    }

    "HttpFilter.client" - {
        "addHeader adds header to request" in run {
            var receivedHeader: Maybe[String] = Absent
            val handler = HttpHandler.get("/test") { (_, req) =>
                receivedHeader = req.header("X-Custom")
                HttpResponse.ok
            }
            HttpFilter.client.addHeader("X-Custom", "test-value").enable {
                for
                    port <- startTestServer(handler)
                    _    <- testGet(port, "/test")
                yield assert(receivedHeader == Present("test-value"))
            }
        }

        "basicAuth adds Authorization header" in run {
            var receivedAuth: Maybe[String] = Absent
            val handler = HttpHandler.get("/test") { (_, req) =>
                receivedAuth = req.header("Authorization")
                HttpResponse.ok
            }
            HttpFilter.client.basicAuth("user", "pass").enable {
                for
                    port <- startTestServer(handler)
                    _    <- testGet(port, "/test")
                yield
                    assert(receivedAuth.isDefined)
                    assert(receivedAuth.get.startsWith("Basic "))
            }
        }

        "bearerAuth adds Bearer token" in run {
            var receivedAuth: Maybe[String] = Absent
            val handler = HttpHandler.get("/test") { (_, req) =>
                receivedAuth = req.header("Authorization")
                HttpResponse.ok
            }
            HttpFilter.client.bearerAuth("my-token").enable {
                for
                    port <- startTestServer(handler)
                    _    <- testGet(port, "/test")
                yield assert(receivedAuth == Present("Bearer my-token"))
            }
        }
    }

    "HttpFilter.server.logging" - {
        "logs requests with default format" in run {
            HttpFilter.server.logging.enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertStatus(response, Status.OK)
            }
        }

        "logs requests with custom handler" in run {
            var logged: Option[(String, Int, Long)] = None
            val customLog = HttpFilter.server.logging { (req, resp, dur) =>
                logged = Some((req.path, resp.status.code, dur.toMillis))
            }
            customLog.enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield
                    assert(logged.isDefined)
                    assert(logged.get._1 == "/test")
                    assert(logged.get._2 == 200)
            }
        }
    }

    "HttpFilter.server.requestId" - {
        "generates request ID when absent" in run {
            HttpFilter.server.requestId.enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertHasHeader(response, "X-Request-ID")
            }
        }

        "propagates existing request ID" in run {
            val existingId = "test-request-123"
            HttpFilter.server.requestId.enable {
                for
                    port <- startTestServer(simpleHandler)
                    response <- HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/test", Seq("X-Request-ID" -> existingId))
                    )
                yield assertHeader(response, "X-Request-ID", existingId)
            }
        }

        "uses custom header name" in run {
            HttpFilter.server.requestId("X-Correlation-ID", Sync.defer(java.util.UUID.randomUUID().toString)).enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertHasHeader(response, "X-Correlation-ID")
            }
        }
    }

    "HttpFilter.server.rateLimit" - {
        "allows requests under limit" in run {
            Meter.initRateLimiter(10, 1.second).map { meter =>
                HttpFilter.server.rateLimit(meter).enable {
                    for
                        port     <- startTestServer(simpleHandler)
                        response <- testGet(port, "/test")
                    yield assertStatus(response, Status.OK)
                }
            }
        }

        "returns 429 when rate limit exceeded" in run {
            Meter.initSemaphore(0).map { meter => // 0 permits = always reject
                HttpFilter.server.rateLimit(meter).enable {
                    for
                        port     <- startTestServer(simpleHandler)
                        response <- testGet(port, "/test")
                    yield
                        assertStatus(response, Status.TooManyRequests)
                        assertHasHeader(response, "Retry-After")
                }
            }
        }
    }

    "HttpFilter.server.securityHeaders with options" - {
        "adds HSTS header when configured" in run {
            HttpFilter.server.securityHeaders(hsts = Present(365.days)).enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertHasHeader(response, "Strict-Transport-Security")
            }
        }

        "adds CSP header when configured" in run {
            HttpFilter.server.securityHeaders(csp = Present("default-src 'self'")).enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertHeader(response, "Content-Security-Policy", "default-src 'self'")
            }
        }
    }

    "HttpFilter.client.logging" - {
        "logs requests with default format" in run {
            HttpFilter.client.logging.enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield assertStatus(response, Status.OK)
            }
        }

        "logs requests with custom handler" in run {
            var logged: Option[(String, Int)] = None
            val customLog = HttpFilter.client.logging { (req, resp, _) =>
                logged = Some((req.url, resp.status.code))
            }
            customLog.enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield
                    assert(logged.isDefined)
                    assert(logged.get._2 == 200)
            }
        }
    }

    "HttpFilter Local API" - {
        "get returns current filter" in run {
            HttpFilter.let(HttpFilter.server.cors()) {
                for
                    filter <- HttpFilter.get
                yield assert(filter ne HttpFilter.noop)
            }
        }

        "use provides access to current filter" in run {
            HttpFilter.let(HttpFilter.server.cors()) {
                HttpFilter.use { filter =>
                    assert(filter ne HttpFilter.noop)
                }
            }
        }

        "noop is default" in run {
            for
                filter <- HttpFilter.get
            yield assert(filter eq HttpFilter.noop)
        }
    }

    "filter error handling" - {

        "filter that throws propagates exception to caller" in run {
            val throwingFilter = new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    throw new RuntimeException("filter exploded")
            for
                port <- startTestServer(simpleHandler)
                result <- Abort.run {
                    throwingFilter.enable {
                        testGet(port, "/test")
                    }
                }
            yield result match
                case Result.Panic(e) =>
                    assert(e.getMessage == "filter exploded")
                case other => fail(s"Expected Panic but got $other")
            end for
        }

        "filter that fails after next propagates exception to caller" in run {
            val throwingFilter = new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    next(request).map { _ =>
                        throw new RuntimeException("post-processing failed")
                    }
            for
                port <- startTestServer(simpleHandler)
                result <- Abort.run {
                    throwingFilter.enable {
                        testGet(port, "/test")
                    }
                }
            yield result match
                case Result.Panic(e) =>
                    assert(e.getMessage == "post-processing failed")
                case other => fail(s"Expected Panic but got $other")
            end for
        }

        "composed filter short-circuits on first failure" in run {
            var secondCalled = false
            val first = new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    HttpResponse.unauthorized: HttpResponse[HttpBody.Bytes]
            val second = new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    secondCalled = true
                    next(request)
                end apply
            first.andThen(second).enable {
                for
                    port     <- startTestServer(simpleHandler)
                    response <- testGet(port, "/test")
                yield
                    assertStatus(response, Status.Unauthorized)
                    assert(!secondCalled)
            }
        }
    }

end HttpFilterTest
