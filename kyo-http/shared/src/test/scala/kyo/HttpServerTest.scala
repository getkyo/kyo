package kyo

import HttpPath./
import HttpRequest.Method
import HttpResponse.Status

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
                val config = HttpServer.Config.default.port(9000)
                assert(config.port == 9000)
            }

            "withHost" in {
                val config = HttpServer.Config.default.host("localhost")
                assert(config.host == "localhost")
            }

            "withMaxContentLength" in {
                val config = HttpServer.Config.default.maxContentLength(1024 * 1024)
                assert(config.maxContentLength == 1024 * 1024)
            }

            "withIdleTimeout" in {
                val config = HttpServer.Config.default.idleTimeout(120.seconds)
                assert(config.idleTimeout == 120.seconds)
            }

            "chaining" in {
                val config = HttpServer.Config.default
                    .port(9000)
                    .host("localhost")
                    .maxContentLength(2 * 1024 * 1024)
                    .idleTimeout(90.seconds)
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
            // Use port 0 to avoid conflicts in parallel test runs
            val server = HttpServer.init(HttpServer.Config(port = 0))(handler)
            server.map(s => succeed)
        }

        "with config and handlers" in run {
            val config  = HttpServer.Config(port = 0) // Random available port
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            val server  = HttpServer.init(config)(handler)
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
            // Server with no handlers should return 404 for all requests
            HttpServer.init(HttpServer.Config(port = 0))().map { server =>
                Scope.ensure(server.stopNow).andThen {
                    testGet(server.port, "/anything").map { response =>
                        assertStatus(response, Status.NotFound)
                    }
                }
            }
        }

        "with multiple handlers" in run {
            val health = HttpHandler.get("/health") { (_, _) => HttpResponse.ok("healthy") }
            val status = HttpHandler.get("/status") { (_, _) => HttpResponse.ok("running") }
            HttpServer.init(HttpServer.Config(port = 0))(health, status).map { server =>
                Scope.ensure(server.stopNow).andThen {
                    for
                        r1 <- testGet(server.port, "/health")
                        r2 <- testGet(server.port, "/status")
                    yield
                        assertStatus(r1, Status.OK)
                        assertBodyText(r1, "healthy")
                        assertStatus(r2, Status.OK)
                        assertBodyText(r2, "running")
                    end for
                }
            }
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
            HttpServer.init(HttpServer.Config(port = 0, host = "localhost"))(handler).map { server =>
                assert(server.host == "localhost" || server.host == "127.0.0.1")
            }
        }

        "stop" in run {
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            HttpServer.init(HttpServer.Config(port = 0))(handler).map { server =>
                server.stopNow
                succeed
            }
        }

        "await" in run {
            val handler = HttpHandler.get("/health") { (_, _) => HttpResponse.ok }
            HttpServer.init(HttpServer.Config(port = 0))(handler).map { server =>
                // await would block until server stops
                // For testing, we just verify it's callable
                server.stopNow
                succeed
            }
        }

        "openApi" in run {
            val route   = HttpRoute.get("users").output[Seq[User]].tag("Users")
            val handler = route.handle(_ => Seq(User(1, "Alice")))
            HttpServer.init(HttpServer.Config(port = 0))(handler).map { server =>
                Scope.ensure(server.stopNow).andThen {
                    val spec = server.openApi("Test API", "1.0.0")
                    assert(spec.info.title == "Test API")
                    assert(spec.info.version == "1.0.0")
                    // Also test the JSON generation
                    val json = server.openApi("Test API").toJson
                    assert(json.contains("Test API"))
                    succeed
                }
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
                val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
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

            "handler with path + query params" in {
                val route = HttpRoute.get("users" / HttpPath.int("id"))
                    .query[String]("fields")
                    .output[User]
                val handler = HttpHandler.init(route) { case (id, fields) =>
                    User(id, s"fields=$fields")
                }
                succeed
            }

            "handler with path + header" in {
                val route = HttpRoute.get("users" / HttpPath.int("id"))
                    .header("X-Request-Id")
                    .output[User]
                val handler = HttpHandler.init(route) { case (id, requestId) =>
                    User(id, s"requestId=$requestId")
                }
                succeed
            }

            "handler with path + query + header" in {
                val route = HttpRoute.get("users" / HttpPath.int("id"))
                    .query[Int]("limit")
                    .header("Authorization")
                    .output[User]
                val handler = HttpHandler.init(route) { case (id, limit, auth) =>
                    User(id, s"limit=$limit,auth=$auth")
                }
                succeed
            }

            "handler with multiple path params + query + header" in {
                val route = HttpRoute.get("orgs" / HttpPath.string("org") / "users" / HttpPath.int("id"))
                    .query[Boolean]("verbose", false)
                    .header("Accept")
                    .output[User]
                val handler = HttpHandler.init(route) { case (org, id, verbose, accept) =>
                    User(id, s"org=$org,verbose=$verbose,accept=$accept")
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

        "error schema returns correct status and JSON body" in run {
            case class NotFoundError(message: String) derives Schema
            val route = HttpRoute.get("users" / HttpPath.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
            val handler = route.handle { id =>
                Abort.fail(NotFoundError("User not found"))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/users/1").map { response =>
                    assertStatus(response, Status.NotFound)
                    assertBodyText(response, """{"message":"User not found"}""")
                    assert(response.header("Content-Type") == Present("application/json"))
                }
            }
        }

        "multiple error schemas select correct status by error type" in run {
            case class NotFoundError(message: String) derives Schema
            case class ValidationError(field: String, reason: String) derives Schema
            val route = HttpRoute.get("users" / HttpPath.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
                .error[ValidationError](Status.BadRequest)
            val handler = route.handle { id =>
                if id == 999 then Abort.fail(NotFoundError("No such user"))
                else Abort.fail(ValidationError("id", "must be positive"))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/users/999").map { response =>
                    assertStatus(response, Status.NotFound)
                    assertBodyText(response, """{"message":"No such user"}""")
                }.andThen {
                    testGet(port, "/users/0").map { response =>
                        assertStatus(response, Status.BadRequest)
                        assertBodyText(response, """{"field":"id","reason":"must be positive"}""")
                    }
                }
            }
        }

        "first error schema matches when error type is first" in run {
            case class NotFoundError(message: String) derives Schema
            case class ValidationError(field: String, reason: String) derives Schema
            val route = HttpRoute.get("items" / HttpPath.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
                .error[ValidationError](Status.BadRequest)
            val handler = route.handle { id =>
                Abort.fail(NotFoundError("Item missing"))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/items/1").map { response =>
                    assertStatus(response, Status.NotFound)
                    assertBodyText(response, """{"message":"Item missing"}""")
                }
            }
        }

        "successful response on route with error schemas" in run {
            case class NotFoundError(message: String) derives Schema
            val route = HttpRoute.get("users" / HttpPath.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
            val handler = route.handle { id =>
                User(id, s"User$id")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/users/42").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "User42")
                }
            }
        }

        "handler panic returns 500 even with error schemas registered" in run {
            case class NotFoundError(message: String) derives Schema
            val route = HttpRoute.get("users" / HttpPath.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
            val handler = route.handle { id =>
                throw new RuntimeException("unexpected panic")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/users/1").map { response =>
                    assertStatus(response, Status.InternalServerError)
                }
            }
        }

    }

    "Streaming responses" - {

        "stream handler delivers body to client" in run {
            val handler = HttpHandler.get("/stream") { (_, _) =>
                val items = Stream.init(Seq(User(1, "Alice"), User(2, "Bob")))
                HttpResponse.stream(items)
            }
            startTestServer(handler).map { port =>
                testGet(port, "/stream").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "Alice")
                    assertBodyContains(response, "Bob")
                }
            }
        }

        "stream handler sets application/json content type" in run {
            val handler = HttpHandler.get("/stream") { (_, _) =>
                HttpResponse.stream(Stream.init(Seq(User(1, "A"))))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/stream").map { response =>
                    assertStatus(response, Status.OK)
                    assertHeader(response, "Content-Type", "application/json")
                }
            }
        }

        "stream handler with custom status" in run {
            val handler = HttpHandler.get("/stream") { (_, _) =>
                HttpResponse.stream(Status.Created, Stream.init(Seq(User(1, "New"))))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/stream").map { response =>
                    assertStatus(response, Status.Created)
                    assertBodyContains(response, "New")
                }
            }
        }

        "sse handler delivers events to client" in run {
            val handler = HttpHandler.get("/events") { (_, _) =>
                HttpResponse.sse(Stream.init(Seq("event1", "event2", "event3")))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/events").map { response =>
                    assertStatus(response, Status.OK)
                    assertHeader(response, "Content-Type", "text/event-stream")
                    assertBodyContains(response, "data: event1")
                    assertBodyContains(response, "data: event2")
                }
            }
        }

        "stream handler with empty stream returns empty body" in run {
            val handler = HttpHandler.get("/stream") { (_, _) =>
                HttpResponse.stream(Stream.init(Seq.empty[User]))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/stream").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "")
                }
            }
        }

        "stream handler with single item" in run {
            val handler = HttpHandler.get("/stream") { (_, _) =>
                HttpResponse.stream(Stream.init(Seq(User(42, "Solo"))))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/stream").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "Solo")
                }
            }
        }
    }

    "Client disconnect handling" - {

        "handler fiber should be interrupted when client times out" in run {
            // Track whether handler was interrupted vs completed normally
            AtomicBoolean.init(false).map { handlerCompleted =>
                AtomicBoolean.init(false).map { handlerStarted =>
                    val slowHandler = HttpHandler.get("/slow") { (_, _) =>
                        handlerStarted.set(true).andThen {
                            // Handler runs for 300ms - longer than client timeout (100ms)
                            // but short enough for test to verify completion
                            Async.delay(300.millis) {
                                handlerCompleted.set(true).andThen {
                                    HttpResponse.ok("done")
                                }
                            }
                        }
                    }
                    startTestServer(slowHandler).map { port =>
                        // Client request with short timeout (100ms < 300ms handler delay)
                        HttpClient.withConfig(_.timeout(100.millis)) {
                            Abort.run(HttpClient.get[String](s"http://localhost:$port/slow"))
                        }.map { result =>
                            // Client should have timed out
                            assert(result.isFailure)
                        }.andThen {
                            // Wait for handler to potentially complete (500ms > 300ms handler delay)
                            Async.delay(500.millis) {
                                handlerStarted.get.map { started =>
                                    assert(started, "Handler should have started")
                                }.andThen {
                                    handlerCompleted.get.map { completed =>
                                        // Handler should be interrupted when client times out
                                        assert(!completed, "Handler should have been interrupted")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "handler fiber should be interrupted when client disconnects" in run {
            AtomicBoolean.init(false).map { handlerCompleted =>
                AtomicBoolean.init(false).map { handlerStarted =>
                    val slowHandler = HttpHandler.get("/slow") { (_, _) =>
                        handlerStarted.set(true).andThen {
                            // Handler runs for 300ms
                            Async.delay(300.millis) {
                                handlerCompleted.set(true).andThen {
                                    HttpResponse.ok("done")
                                }
                            }
                        }
                    }
                    startTestServer(slowHandler).map { port =>
                        // Start request then interrupt the fiber after 100ms
                        Fiber.init(HttpClient.get[String](s"http://localhost:$port/slow")).map { clientFiber =>
                            // Wait for handler to start
                            Async.delay(100.millis) {
                                // Interrupt client fiber (simulates disconnect)
                                clientFiber.interrupt.andThen {
                                    // Wait for handler to potentially complete (500ms > 300ms)
                                    Async.delay(500.millis) {
                                        handlerStarted.get.map { started =>
                                            assert(started, "Handler should have started")
                                        }.andThen {
                                            handlerCompleted.get.map { completed =>
                                                // Handler should be interrupted when client disconnects
                                                assert(!completed, "Handler should have been interrupted")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end HttpServerTest
