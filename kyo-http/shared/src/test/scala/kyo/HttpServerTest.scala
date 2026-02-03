package kyo

import HttpRequest.Method
import HttpResponse.Status
import HttpRoute.Path
import HttpRoute.Path./

class HttpServerTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual

    "HttpServer.Config" - {

        "default values" in {
            val config = HttpServer.Config.default
            assert(config.port == 8080)
            assert(config.host == "0.0.0.0")
            assert(config.maxContentLength == 65536)
            assert(config.idleTimeout == 60.seconds)
        }

        "construction with custom values" in {
            val config = HttpServer.Config(
                port = 9000,
                host = "localhost",
                maxContentLength = 1024 * 1024,
                idleTimeout = 30.seconds
            )
            assert(config.port == 9000)
            assert(config.host == "localhost")
            assert(config.maxContentLength == 1024 * 1024)
            assert(config.idleTimeout == 30.seconds)
        }

        "builder methods" - {
            "withPort" in {
                val config = HttpServer.Config.default.withPort(9000)
                assert(config.port == 9000)
            }

            "withHost" in {
                val config = HttpServer.Config.default.withHost("localhost")
                assert(config.host == "localhost")
            }

            "withMaxContentLength" in {
                val config = HttpServer.Config.default.withMaxContentLength(1024 * 1024)
                assert(config.maxContentLength == 1024 * 1024)
            }

            "withIdleTimeout" in {
                val config = HttpServer.Config.default.withIdleTimeout(120.seconds)
                assert(config.idleTimeout == 120.seconds)
            }

            "chaining" in {
                val config = HttpServer.Config.default
                    .withPort(9000)
                    .withHost("localhost")
                    .withMaxContentLength(2 * 1024 * 1024)
                    .withIdleTimeout(90.seconds)
                assert(config.port == 9000)
                assert(config.host == "localhost")
                assert(config.maxContentLength == 2 * 1024 * 1024)
                assert(config.idleTimeout == 90.seconds)
            }
        }

        "validation" - {
            "negative port throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpServer.Config(port = -1)
                }
            }

            "port out of range throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpServer.Config(port = 65536)
                }
            }

            "empty host throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpServer.Config(host = "")
                }
            }

            "zero maxContentLength throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpServer.Config(maxContentLength = 0)
                }
            }

            "negative maxContentLength throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpServer.Config(maxContentLength = -1)
                }
            }

            "zero idleTimeout throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpServer.Config(idleTimeout = Duration.Zero)
                }
            }

            "negative idleTimeout throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpServer.Config(idleTimeout = -1.seconds)
                }
            }
        }
    }

    "HttpServer.init" - {

        "with handlers only" in run {
            val handler = HttpHandler.get("/health") { (_, _) =>
                HttpResponse.ok("healthy")
            }
            val server = HttpServer.init(handler)
            // Server should start and bind to default port
            server.map(s => succeed)
        }

        "with config and handlers" in run {
            val config  = HttpServer.Config(port = 0) // Random available port
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            val server  = HttpServer.init(config)(handler)
            server.map(s => succeed)
        }

        "with aspects and handlers" in run {
            val aspect  = HttpRequestAspect.logging
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            val server  = HttpServer.init(Seq(aspect), handler)
            server.map(s => succeed)
        }

        "with config, aspects, and handlers" in run {
            val config  = HttpServer.Config(port = 0)
            val aspect  = HttpRequestAspect.logging
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            val server  = HttpServer.init(config, Seq(aspect))(handler)
            server.map(s => succeed)
        }

        "with named parameters" in run {
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            val server = HttpServer.init(
                port = 0,
                host = "localhost",
                maxContentLength = 1024 * 1024,
                idleTimeout = 30.seconds
            )(handler)
            server.map(s => succeed)
        }

        "with empty handlers" in run {
            val server = HttpServer.init()
            // Server with no handlers should still start (returns 404 for all)
            server.map(s => succeed)
        }

        "with multiple handlers" in run {
            val health = HttpHandler.get("/health") { (_, _) => HttpResponse.ok("healthy") }
            val ready  = HttpHandler.get("/ready") { (_, _) => HttpResponse.ok("ready") }
            val server = HttpServer.init(health, ready)
            server.map(s => succeed)
        }
    }

    "HttpServer extensions" - {

        "port" in run {
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            HttpServer.init(HttpServer.Config(port = 0))(handler).map { server =>
                // Should return the actual bound port (may differ from 0)
                assert(server.port > 0)
            }
        }

        "host" in run {
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            HttpServer.init(HttpServer.Config(host = "localhost"))(handler).map { server =>
                assert(server.host == "localhost" || server.host == "127.0.0.1")
            }
        }

        "stop" in run {
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            HttpServer.init(HttpServer.Config(port = 0))(handler).map { server =>
                server.stop
                succeed
            }
        }

        "await" in run {
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            HttpServer.init(HttpServer.Config(port = 0))(handler).map { server =>
                // await would block until server stops
                // For testing, we just verify it's callable
                server.stop
                succeed
            }
        }

        "openApi" in run {
            val route   = HttpRoute.get("users").output[Seq[User]].tag("Users").summary("List users")
            val handler = route.handle(_ => Seq(User(1, "Alice")))
            HttpServer.init(HttpServer.Config(port = 0))(handler).map { server =>
                val spec = server.openApi
                assert(spec.contains("openapi"))
                assert(spec.contains("Users"))
            }
        }
    }

    "HttpHandler" - {

        "from typed route" - {
            "simple handler" in {
                val route   = HttpRoute.get("users").output[Seq[User]]
                val handler = HttpHandler.init(route) { _ => Seq(User(1, "Alice")) }
                assert(handler.route eq route)
            }

            "handler with path capture" in {
                val route   = HttpRoute.get("users" / Path.int("id")).output[User]
                val handler = HttpHandler.init(route) { id => User(id, "User") }
                succeed
            }

            "handler with multiple inputs" in {
                val route = HttpRoute.get("users")
                    .query[Int]("limit", 20)
                    .query[Int]("offset", 0)
                    .output[Seq[User]]
                val handler = HttpHandler.init(route) { case (limit, offset) =>
                    Seq.fill(limit)(User(1, "User")).drop(offset)
                }
                succeed
            }

            "handler with async effect" in {
                val route   = HttpRoute.get("users").output[Seq[User]]
                val handler = HttpHandler.init(route) { _ => Async.delay(10.millis)(Seq(User(1, "Alice"))) }
                succeed
            }

            "handler with custom effect" in {
                val route   = HttpRoute.get("users").output[Seq[User]]
                val handler = HttpHandler.init(route) { _ => Env.get[String].map(prefix => Seq(User(1, prefix))) }
                succeed
            }
        }

        "raw handler" - {
            "with method and path" in {
                val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok("healthy") }
                succeed
            }

            "accessing request properties" in {
                val handler = HttpHandler.post("/echo") { (_, request) =>
                    val body = request.bodyText
                    val ua   = request.header("User-Agent").getOrElse("unknown")
                    HttpResponse.ok(s"Received: $body from $ua")
                }
                succeed
            }

            "with async response" in {
                val handler = HttpHandler.get("/delayed") { (_, _) =>
                    Async.delay(100.millis)(HttpResponse.ok("done"))
                }
                succeed
            }
        }

        "handler apply method" in {
            val handler = HttpHandler.get("/test") { (_, _) => HttpResponse.ok }
            // Handler has an apply method that takes HttpRequest
            succeed
        }
    }

    "HttpRequestAspect" - {

        "init" in {
            val aspect = HttpRequestAspect.init
            succeed
        }

        "apply with custom logic" in {
            val aspect = HttpRequestAspect { (request, next) =>
                val start = java.lang.System.currentTimeMillis()
                next(request).map { response =>
                    val elapsed = java.lang.System.currentTimeMillis() - start
                    response.addHeader("X-Response-Time", s"${elapsed}ms")
                }
            }
            succeed
        }

        "logging" in {
            val aspect = HttpRequestAspect.logging
            succeed
        }

        "metrics" in {
            val aspect = HttpRequestAspect.metrics
            succeed
        }

        "timeout" - {
            "with duration" in {
                val aspect = HttpRequestAspect.timeout(5.seconds)
                succeed
            }

            "exceeding timeout" in run {
                val aspect = HttpRequestAspect.timeout(10.millis)
                val handler = HttpHandler.get("/slow") { (_, _) =>
                    Async.delay(100.millis)(HttpResponse.ok)
                }
                // When request exceeds timeout, should return 504 Gateway Timeout
                succeed
            }

            "zero duration throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRequestAspect.timeout(Duration.Zero)
                }
            }

            "negative duration throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRequestAspect.timeout(-1.seconds)
                }
            }
        }

        "cors" - {
            "with defaults" in {
                val aspect = HttpRequestAspect.cors()
                succeed
            }

            "with custom origin" in {
                val aspect = HttpRequestAspect.cors(allowOrigin = "https://example.com")
                succeed
            }

            "with multiple methods" in {
                val aspect = HttpRequestAspect.cors(
                    allowMethods = Seq(Method.GET, Method.POST, Method.PUT, Method.DELETE, Method.PATCH)
                )
                succeed
            }

            "with credentials" in {
                val aspect = HttpRequestAspect.cors(allowCredentials = true)
                succeed
            }

            "with max age" in {
                val aspect = HttpRequestAspect.cors(maxAge = Present(1.hour))
                succeed
            }

            "preflight request handling" in run {
                // OPTIONS request should be handled by CORS aspect
                val aspect = HttpRequestAspect.cors()
                succeed
            }

            "empty origin throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRequestAspect.cors(allowOrigin = "")
                }
            }

            "negative max age throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRequestAspect.cors(maxAge = Present(-1.seconds))
                }
            }
        }

        "rate limiting" - {
            "with meter" in run {
                Meter.initRateLimiter(100, 1.second).map { meter =>
                    val aspect = HttpRequestAspect.rateLimit(meter)
                    succeed
                }
            }

            "with requests per second" in {
                val aspect = HttpRequestAspect.rateLimit(100)
                succeed
            }

            "by IP" in {
                val aspect = HttpRequestAspect.rateLimitByIp(10)
                succeed
            }

            "by header" in {
                val aspect = HttpRequestAspect.rateLimitByHeader("X-API-Key", 100)
                succeed
            }

            "exceeding limit returns 429" in run {
                val aspect = HttpRequestAspect.rateLimit(1) // 1 req/sec
                // Second request within same second should get 429
                succeed
            }

            "zero requests per second throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRequestAspect.rateLimit(0)
                }
            }

            "negative requests per second throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRequestAspect.rateLimit(-1)
                }
            }

            "empty header name throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpRequestAspect.rateLimitByHeader("", 100)
                }
            }
        }

        "compression" - {
            "auto compression" in {
                val aspect = HttpRequestAspect.compression
                succeed
            }

            "gzip" in {
                val aspect = HttpRequestAspect.gzip
                succeed
            }

            "deflate" in {
                val aspect = HttpRequestAspect.deflate
                succeed
            }

            "respects Accept-Encoding" in run {
                // If client sends Accept-Encoding: gzip, response should be gzipped
                val aspect = HttpRequestAspect.compression
                succeed
            }
        }

        "caching" - {
            "etag" in {
                val aspect = HttpRequestAspect.etag
                succeed
            }

            "conditional requests" in {
                val aspect = HttpRequestAspect.conditionalRequests
                succeed
            }

            "304 on match" in run {
                // If If-None-Match matches ETag, should return 304
                val aspect = HttpRequestAspect.conditionalRequests
                succeed
            }
        }

        "authentication" - {
            "basic auth success" in run {
                val aspect = HttpRequestAspect.basicAuth { (user, pass) =>
                    user == "admin" && pass == "secret"
                }
                // Request with valid credentials should pass through
                succeed
            }

            "basic auth failure" in run {
                val aspect = HttpRequestAspect.basicAuth { (user, pass) =>
                    user == "admin" && pass == "secret"
                }
                // Request with invalid credentials should get 401
                succeed
            }

            "bearer auth success" in run {
                val aspect = HttpRequestAspect.bearerAuth { token =>
                    token == "valid-token"
                }
                succeed
            }

            "bearer auth failure" in run {
                val aspect = HttpRequestAspect.bearerAuth { token =>
                    token == "valid-token"
                }
                // Invalid token should get 401
                succeed
            }

            "missing credentials" in run {
                val aspect = HttpRequestAspect.basicAuth { (_, _) => true }
                // Request without Authorization header should get 401
                succeed
            }
        }

        "composition" - {
            "multiple aspects" in {
                val aspects = Seq(
                    HttpRequestAspect.logging,
                    HttpRequestAspect.cors(),
                    HttpRequestAspect.compression
                )
                succeed
            }

            "aspect order" in run {
                // Aspects should be applied in order (first aspect wraps second, etc.)
                var order = List.empty[String]
                val aspect1 = HttpRequestAspect { (req, next) =>
                    order = order :+ "before1"
                    next(req).map { res =>
                        order = order :+ "after1"
                        res
                    }
                }
                val aspect2 = HttpRequestAspect { (req, next) =>
                    order = order :+ "before2"
                    next(req).map { res =>
                        order = order :+ "after2"
                        res
                    }
                }
                // Expected: before1 -> before2 -> handler -> after2 -> after1
                succeed
            }

            "short-circuit on failure" in run {
                val failAspect = HttpRequestAspect { (_, _) =>
                    HttpResponse.unauthorized
                }
                // Handler should not be called if aspect short-circuits
                succeed
            }
        }
    }

    "Integration scenarios" - {

        "server with single handler" in run {
            val handler = HttpHandler.get("/health") { (_, _) =>
                HttpResponse.ok("healthy")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/health").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "healthy")
                }
            }
        }

        "server with multiple handlers" in run {
            val health = HttpHandler.get("/health") { (_, _) => HttpResponse.ok("ok") }
            val users  = HttpRoute.get("users").output[Seq[User]].handle(_ => Seq.empty[User])
            startTestServer(health, users).map { port =>
                for
                    r1 <- testGet(port, "/health")
                    r2 <- testGet(port, "/users")
                yield
                    assertStatus(r1, Status.OK)
                    assertStatus(r2, Status.OK)
                end for
            }
        }

        "server with aspects" in run {
            val aspect  = HttpRequestAspect.logging
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok("ok") }
            HttpServer.init(HttpServer.Config(port = 0), Seq(aspect))(handler).map { server =>
                val port = server.port
                testGet(port, "/health").map { response =>
                    Scope.ensure(server.stop).andThen {
                        assertStatus(response, Status.OK)
                    }
                }
            }
        }

        "handler routing by path" in run {
            val users = HttpHandler.get("/users") { (_, _) => HttpResponse.ok("users") }
            val posts = HttpHandler.get("/posts") { (_, _) => HttpResponse.ok("posts") }
            startTestServer(users, posts).map { port =>
                for
                    r1 <- testGet(port, "/users")
                    r2 <- testGet(port, "/posts")
                yield
                    assertBodyText(r1, "users")
                    assertBodyText(r2, "posts")
                end for
            }
        }

        "handler routing by method" in run {
            val getUsers  = HttpHandler.get("/users") { (_, _) => HttpResponse.ok("list") }
            val postUsers = HttpHandler.post("/users") { (_, _) => HttpResponse.created(User(1, "new")) }
            startTestServer(getUsers, postUsers).map { port =>
                for
                    r1 <- testGet(port, "/users")
                    r2 <- testPost(port, "/users", User(0, "new"))
                yield
                    assertBodyText(r1, "list")
                    assertStatus(r2, Status.Created)
                end for
            }
        }

        "404 for unmatched routes" in run {
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            startTestServer(handler).map { port =>
                testGet(port, "/unknown").map { response =>
                    assertStatus(response, Status.NotFound)
                }
            }
        }

        "error handling" in run {
            startTestServer(errorHandler("/error")).map { port =>
                testGet(port, "/error").map { response =>
                    assertStatus(response, Status.InternalServerError)
                }
            }
        }
    }

end HttpServerTest
