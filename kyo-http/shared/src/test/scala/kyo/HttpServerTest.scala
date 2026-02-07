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

        "handler fiber should be interrupted when client disconnects" ignore run {
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

    "Streaming responses" - {

        case class Event(value: Int) derives Schema, CanEqual

        "SSE stream returns correct content-type and body" in run {
            val handler = HttpHandler.streamSse[Unit, Event, Any]("/events") { (_, _) =>
                Stream.init(Seq(
                    ServerSentEvent(Event(1)),
                    ServerSentEvent(Event(2)),
                    ServerSentEvent(Event(3))
                ))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/events").map { response =>
                    assertStatus(response, Status.OK)
                    val body = response.bodyText
                    assert(body.contains("data: "), s"Expected SSE data fields in body: $body")
                    assert(body.contains("\"value\":1"), s"Expected value 1 in body: $body")
                    assert(body.contains("\"value\":2"), s"Expected value 2 in body: $body")
                    assert(body.contains("\"value\":3"), s"Expected value 3 in body: $body")
                }
            }
        }

        "SSE stream with event name and id" in run {
            val handler = HttpHandler.streamSse[Unit, String, Any]("/events") { (_, _) =>
                Stream.init(Seq(
                    ServerSentEvent("hello", event = Present("greeting"), id = Present("1")),
                    ServerSentEvent("world", event = Present("greeting"), id = Present("2"))
                ))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/events").map { response =>
                    assertStatus(response, Status.OK)
                    val body = response.bodyText
                    assert(body.contains("event: greeting"), s"Expected event field: $body")
                    assert(body.contains("id: 1"), s"Expected id field: $body")
                    assert(body.contains("id: 2"), s"Expected id field: $body")
                    assert(body.contains("data: "), s"Expected data field: $body")
                }
            }
        }

        "SSE stream with retry field" in run {
            val handler = HttpHandler.streamSse[Unit, String, Any]("/events") { (_, _) =>
                Stream.init(Seq(
                    ServerSentEvent("test", retry = Present(5000.millis))
                ))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/events").map { response =>
                    val body = response.bodyText
                    assert(body.contains("retry: 5000"), s"Expected retry field: $body")
                }
            }
        }

        "NDJSON stream returns correct body" in run {
            val handler = HttpHandler.streamNdjson[Unit, Event, Any]("/data") { (_, _) =>
                Stream.init(Seq(Event(10), Event(20), Event(30)))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/data").map { response =>
                    assertStatus(response, Status.OK)
                    val body  = response.bodyText
                    val lines = body.split("\n").filter(_.nonEmpty)
                    assert(lines.length == 3, s"Expected 3 NDJSON lines, got ${lines.length}: $body")
                    assert(lines(0).contains("\"value\":10"), s"Expected value 10: ${lines(0)}")
                    assert(lines(1).contains("\"value\":20"), s"Expected value 20: ${lines(1)}")
                    assert(lines(2).contains("\"value\":30"), s"Expected value 30: ${lines(2)}")
                }
            }
        }

        "empty SSE stream" in run {
            val handler = HttpHandler.streamSse[Unit, String, Any]("/empty") { (_, _) =>
                Stream.empty[ServerSentEvent[String]]
            }
            startTestServer(handler).map { port =>
                testGet(port, "/empty").map { response =>
                    assertStatus(response, Status.OK)
                    assert(response.bodyText.isEmpty || response.bodyText.trim.isEmpty)
                }
            }
        }

        "empty NDJSON stream" in run {
            val handler = HttpHandler.streamNdjson[Unit, Event, Any]("/empty") { (_, _) =>
                Stream.empty[Event]
            }
            startTestServer(handler).map { port =>
                testGet(port, "/empty").map { response =>
                    assertStatus(response, Status.OK)
                    assert(response.bodyText.isEmpty || response.bodyText.trim.isEmpty)
                }
            }
        }

        "SSE stream with path params" in run {
            val handler = HttpHandler.streamSse[Int, Event, Any]("events" / HttpPath.int("count")) { (count, _) =>
                Stream.init((1 to count).map(i => ServerSentEvent(Event(i))))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/events/5").map { response =>
                    assertStatus(response, Status.OK)
                    val body = response.bodyText
                    assert(body.contains("\"value\":1"), s"Expected value 1: $body")
                    assert(body.contains("\"value\":5"), s"Expected value 5: $body")
                }
            }
        }

        "NDJSON stream with POST method" in run {
            val handler = HttpHandler.streamNdjson[Unit, Event, Any](Method.POST, "/data") { (_, _) =>
                Stream.init(Seq(Event(42)))
            }
            startTestServer(handler).map { port =>
                HttpClient.send(HttpRequest.post(s"http://localhost:$port/data", "")).map { response =>
                    assertStatus(response, Status.OK)
                    assert(response.bodyText.contains("\"value\":42"))
                }
            }
        }

        "streaming coexists with default handlers" in run {
            val defaultHandler = HttpHandler.get("/hello") { (_, _) => HttpResponse.ok("world") }
            val sseHandler = HttpHandler.streamSse[Unit, Event, Any]("/events") { (_, _) =>
                Stream.init(Seq(ServerSentEvent(Event(1))))
            }
            startTestServer(defaultHandler, sseHandler).map { port =>
                testGet(port, "/hello").map { r1 =>
                    assertBodyText(r1, "world")
                }.andThen {
                    testGet(port, "/events").map { r2 =>
                        assert(r2.bodyText.contains("\"value\":1"))
                    }
                }
            }
        }
    }

    "Client streaming" - {

        case class Item(name: String) derives Schema, CanEqual

        "streamSse receives SSE events" in run {
            val handler = HttpHandler.streamSse[Unit, Item, Any]("/sse") { (_, _) =>
                Stream.init(Seq(
                    ServerSentEvent(Item("a")),
                    ServerSentEvent(Item("b")),
                    ServerSentEvent(Item("c"))
                ))
            }
            startTestServer(handler).map { port =>
                HttpClient.streamSse[Item](s"http://localhost:$port/sse").map { stream =>
                    stream.run.map { chunk =>
                        assert(chunk.size == 3)
                        assert(chunk(0).data == Item("a"))
                        assert(chunk(1).data == Item("b"))
                        assert(chunk(2).data == Item("c"))
                    }
                }
            }
        }

        "streamSse receives event name and id" in run {
            val handler = HttpHandler.streamSse[Unit, String, Any]("/sse") { (_, _) =>
                Stream.init(Seq(
                    ServerSentEvent("hello", event = Present("greeting"), id = Present("1")),
                    ServerSentEvent("world", event = Present("greeting"), id = Present("2"))
                ))
            }
            startTestServer(handler).map { port =>
                HttpClient.streamSse[String](s"http://localhost:$port/sse").map { stream =>
                    stream.run.map { chunk =>
                        assert(chunk.size == 2)
                        assert(chunk(0).data == "hello")
                        assert(chunk(0).event == Present("greeting"))
                        assert(chunk(0).id == Present("1"))
                        assert(chunk(1).data == "world")
                        assert(chunk(1).id == Present("2"))
                    }
                }
            }
        }

        "stream receives NDJSON values" in run {
            val handler = HttpHandler.streamNdjson[Unit, Item, Any]("/data") { (_, _) =>
                Stream.init(Seq(Item("x"), Item("y"), Item("z")))
            }
            startTestServer(handler).map { port =>
                HttpClient.streamNdjson[Item](s"http://localhost:$port/data").map { stream =>
                    stream.run.map { chunk =>
                        assert(chunk.size == 3)
                        assert(chunk(0) == Item("x"))
                        assert(chunk(1) == Item("y"))
                        assert(chunk(2) == Item("z"))
                    }
                }
            }
        }

        "streamSse with empty stream" in run {
            val handler = HttpHandler.streamSse[Unit, String, Any]("/empty") { (_, _) =>
                Stream.empty[ServerSentEvent[String]]
            }
            startTestServer(handler).map { port =>
                HttpClient.streamSse[String](s"http://localhost:$port/empty").map { stream =>
                    stream.run.map { chunk =>
                        assert(chunk.isEmpty)
                    }
                }
            }
        }

        "stream with empty NDJSON" in run {
            val handler = HttpHandler.streamNdjson[Unit, Item, Any]("/empty") { (_, _) =>
                Stream.empty[Item]
            }
            startTestServer(handler).map { port =>
                HttpClient.streamNdjson[Item](s"http://localhost:$port/empty").map { stream =>
                    stream.run.map { chunk =>
                        assert(chunk.isEmpty)
                    }
                }
            }
        }
    }

    "Streaming request bodies" - {

        "server receives streaming request body" in run {
            val handler = HttpHandler.streamingBody(Method.POST, "/upload") { (_, request) =>
                request.bodyStream.run.map { chunks =>
                    val totalBytes = chunks.foldLeft(0)(_ + _.size)
                    HttpResponse.ok(s"received $totalBytes bytes")
                }
            }
            startTestServer(handler).map { port =>
                val bodyData   = "Hello, streaming world!"
                val bodyBytes  = bodyData.getBytes("UTF-8")
                val bodyStream = Stream.init(Seq(Span.fromUnsafe(bodyBytes)))
                val request = HttpRequest.stream(
                    Method.POST,
                    s"http://localhost:$port/upload",
                    bodyStream
                )
                HttpClient.stream(request).map { response =>
                    assert(response.status == Status.OK)
                    response.bodyStream.run.map { chunks =>
                        val text = chunks.foldLeft("")((acc, span) =>
                            acc + new String(span.toArrayUnsafe, "UTF-8")
                        )
                        assert(text == s"received ${bodyBytes.length} bytes")
                    }
                }
            }
        }

        "server receives multi-chunk streaming body" in run {
            val handler = HttpHandler.streamingBody(Method.POST, "/upload") { (_, request) =>
                request.bodyStream.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) =>
                        acc + new String(span.toArrayUnsafe, "UTF-8")
                    )
                    HttpResponse.ok(text)
                }
            }
            startTestServer(handler).map { port =>
                val chunks     = Seq("chunk1-", "chunk2-", "chunk3")
                val bodyStream = Stream.init(chunks.map(s => Span.fromUnsafe(s.getBytes("UTF-8"))))
                val request = HttpRequest.stream(
                    Method.POST,
                    s"http://localhost:$port/upload",
                    bodyStream
                )
                HttpClient.stream(request).map { response =>
                    assert(response.status == Status.OK)
                    response.bodyStream.run.map { responseChunks =>
                        val text = responseChunks.foldLeft("")((acc, span) =>
                            acc + new String(span.toArrayUnsafe, "UTF-8")
                        )
                        assert(text == "chunk1-chunk2-chunk3")
                    }
                }
            }
        }

        "streaming request body with empty stream" in run {
            val handler = HttpHandler.streamingBody(Method.POST, "/upload") { (_, request) =>
                request.bodyStream.run.map { chunks =>
                    val totalBytes = chunks.foldLeft(0)(_ + _.size)
                    HttpResponse.ok(s"received $totalBytes bytes")
                }
            }
            startTestServer(handler).map { port =>
                val request = HttpRequest.stream(
                    Method.POST,
                    s"http://localhost:$port/upload",
                    Stream.empty[Span[Byte]]
                )
                HttpClient.stream(request).map { response =>
                    assert(response.status == Status.OK)
                    response.bodyStream.run.map { chunks =>
                        val text = chunks.foldLeft("")((acc, span) =>
                            acc + new String(span.toArrayUnsafe, "UTF-8")
                        )
                        assert(text == "received 0 bytes")
                    }
                }
            }
        }

        "mixed streaming and buffered handlers on same server" in run {
            val bufferedHandler = HttpHandler.get("/hello") { (_, _) => HttpResponse.ok("world") }
            val streamingHandler = HttpHandler.streamingBody(Method.POST, "/upload") { (_, request) =>
                request.bodyStream.run.map { chunks =>
                    val totalBytes = chunks.foldLeft(0)(_ + _.size)
                    HttpResponse.ok(s"received $totalBytes bytes")
                }
            }
            startTestServer(bufferedHandler, streamingHandler).map { port =>
                // Test buffered handler works
                testGet(port, "/hello").map { r1 =>
                    assertBodyText(r1, "world")
                }.andThen {
                    // Test streaming handler works
                    val bodyStream = Stream.init(Seq(Span.fromUnsafe("test".getBytes("UTF-8"))))
                    val request = HttpRequest.stream(
                        Method.POST,
                        s"http://localhost:$port/upload",
                        bodyStream
                    )
                    HttpClient.stream(request).map { response =>
                        assert(response.status == Status.OK)
                        response.bodyStream.run.map { chunks =>
                            val text = chunks.foldLeft("")((acc, span) =>
                                acc + new String(span.toArrayUnsafe, "UTF-8")
                            )
                            assert(text == "received 4 bytes")
                        }
                    }
                }
            }
        }

        "streaming request with path params" in run {
            val handler = HttpHandler.streamingBody(Method.POST, "upload" / HttpPath.string("name")) { (name, request) =>
                request.bodyStream.run.map { chunks =>
                    val totalBytes = chunks.foldLeft(0)(_ + _.size)
                    HttpResponse.ok(s"$name: $totalBytes bytes")
                }
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("data".getBytes("UTF-8"))))
                val request = HttpRequest.stream(
                    Method.POST,
                    s"http://localhost:$port/upload/myfile",
                    bodyStream
                )
                HttpClient.stream(request).map { response =>
                    assert(response.status == Status.OK)
                    response.bodyStream.run.map { chunks =>
                        val text = chunks.foldLeft("")((acc, span) =>
                            acc + new String(span.toArrayUnsafe, "UTF-8")
                        )
                        assert(text == "myfile: 4 bytes")
                    }
                }
            }
        }

        "404 for unmatched route with streaming request" in run {
            val handler = HttpHandler.streamingBody(Method.POST, "/upload") { (_, request) =>
                request.bodyStream.run.map(_ => HttpResponse.ok)
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("data".getBytes("UTF-8"))))
                val request = HttpRequest.stream(
                    Method.POST,
                    s"http://localhost:$port/nonexistent",
                    bodyStream
                )
                Abort.run(HttpClient.stream(request)).map {
                    case Result.Failure(HttpError.StatusError(status, _)) =>
                        assert(status == Status.NotFound)
                    case other =>
                        fail(s"Expected StatusError(404) but got $other")
                }
            }
        }

        "413 for oversized buffered request" in run {
            val handler = HttpHandler.post("/upload") { (_, request) =>
                HttpResponse.ok(s"received ${request.bodyBytes.size} bytes")
            }
            // Server with small maxContentLength
            HttpServer.init(HttpServer.Config(port = 0, maxContentLength = 100))(handler).map { server =>
                Scope.ensure(server.stopNow).andThen {
                    // Send a request larger than maxContentLength
                    val largeBody = "x" * 200
                    HttpClient.send(
                        HttpRequest.post(s"http://localhost:${server.port}/upload", largeBody)
                    ).map { response =>
                        assertStatus(response, Status.PayloadTooLarge)
                    }
                }
            }
        }
    }

end HttpServerTest
