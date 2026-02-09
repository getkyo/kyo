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
            val handler = HttpHandler.get("/health") { _ =>
                HttpResponse.ok("healthy")
            }
            HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.backend)(handler).map { server =>
                assert(server.port > 0)
                testGet(server.port, "/health").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "healthy")
                }
            }
        }

        "with config and handlers" in run {
            val config  = HttpServer.Config(port = 0)
            val handler = HttpHandler.get("/health") { _ => HttpResponse.ok("ok") }
            HttpServer.init(config, PlatformTestBackend.backend)(handler).map { server =>
                assert(server.port > 0)
                testGet(server.port, "/health").map { response =>
                    assertStatus(response, Status.OK)
                }
            }
        }

        "with named parameters" in run {
            val handler = HttpHandler.get("/health") { _ => HttpResponse.ok("ok") }
            HttpServer.init(
                HttpServer.Config(port = 0, host = "localhost", maxContentLength = 1024 * 1024, idleTimeout = 30.seconds),
                PlatformTestBackend.backend
            )(handler).map { server =>
                assert(server.port > 0)
                testGet(server.port, "/health").map { response =>
                    assertStatus(response, Status.OK)
                }
            }
        }

        "with empty handlers" in run {
            // Server with no handlers should return 404 for all requests
            HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.backend)().map { server =>
                testGet(server.port, "/anything").map { response =>
                    assertStatus(response, Status.NotFound)
                }
            }
        }

        "with multiple handlers" in run {
            val health = HttpHandler.get("/health") { _ => HttpResponse.ok("healthy") }
            val status = HttpHandler.get("/status") { _ => HttpResponse.ok("running") }
            HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.backend)(health, status).map { server =>
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

    "HttpServer extensions" - {

        "port" in run {
            val handler = HttpHandler.get("/health") { _ => HttpResponse.ok }
            HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.backend)(handler).map { server =>
                // Should return the actual bound port (may differ from 0)
                assert(server.port > 0)
            }
        }

        "host" in run {
            val handler = HttpHandler.get("/health") { _ => HttpResponse.ok }
            HttpServer.init(HttpServer.Config(port = 0, host = "localhost"), PlatformTestBackend.backend)(handler).map { server =>
                assert(server.host == "localhost" || server.host == "127.0.0.1")
            }
        }

        "stop" in run {
            val handler = HttpHandler.get("/health") { _ => HttpResponse.ok("ok") }
            HttpServer.initUnscoped(HttpServer.Config(port = 0), PlatformTestBackend.backend)(handler).map { server =>
                val port = server.port
                assert(port > 0)
                testGet(port, "/health").map { response =>
                    assertStatus(response, Status.OK)
                }.andThen {
                    server.closeNow.andThen {
                        Abort.run(testGet(port, "/health")).map { result =>
                            assert(result.isFailure)
                        }
                    }
                }
            }
        }

        "await" in run {
            val handler = HttpHandler.get("/health") { _ => HttpResponse.ok }
            HttpServer.initUnscoped(HttpServer.Config(port = 0), PlatformTestBackend.backend)(handler).map { server =>
                assert(server.port > 0)
                server.closeNow.andThen(succeed)
            }
        }

        "openApi" in run {
            val route   = HttpRoute.get("users").output[Seq[User]].tag("Users")
            val handler = route.handle(_ => Seq(User(1, "Alice")))
            HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.backend)(handler).map { server =>
                val spec = server.openApi("Test API", "1.0.0")
                assert(spec.info.title == "Test API")
                assert(spec.info.version == "1.0.0")
                val json = server.openApi("Test API").toJson
                assert(json.contains("Test API"))
                assert(json.contains("Users"))
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

            "handler with path capture" in run {
                val route   = HttpRoute.get("users" / HttpPath.int("id")).output[User]
                val handler = HttpHandler.init(route) { id => User(id, "User") }
                startTestServer(handler).map { port =>
                    testGetAs[User](port, "/users/42").map { user =>
                        assert(user == User(42, "User"))
                    }
                }
            }

            "handler with multiple inputs" in run {
                val route = HttpRoute.get("users")
                    .query[Int]("limit", 20)
                    .query[Int]("offset", 0)
                    .output[Seq[User]]
                val handler = HttpHandler.init(route) { case (limit, offset) =>
                    Seq.fill(limit)(User(1, "User")).drop(offset)
                }
                startTestServer(handler).map { port =>
                    testGetAs[Seq[User]](port, "/users?limit=3&offset=1").map { users =>
                        assert(users.size == 2)
                    }
                }
            }

            "handler with path + query params" in run {
                val route = HttpRoute.get("users" / HttpPath.int("id"))
                    .query[String]("fields")
                    .output[User]
                val handler = HttpHandler.init(route) { case (id, fields) =>
                    User(id, s"fields=$fields")
                }
                startTestServer(handler).map { port =>
                    testGetAs[User](port, "/users/42?fields=name").map { user =>
                        assert(user == User(42, "fields=name"))
                    }
                }
            }

            "handler with path + header" in run {
                val route = HttpRoute.get("users" / HttpPath.int("id"))
                    .header("X-Request-Id")
                    .output[User]
                val handler = HttpHandler.init(route) { case (id, requestId) =>
                    User(id, s"requestId=$requestId")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/users/42", HttpHeaders.empty.add("X-Request-Id", "req-123"))
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyContains(response, "requestId=req-123")
                    }
                }
            }

            "handler with path + query + header" in run {
                val route = HttpRoute.get("users" / HttpPath.int("id"))
                    .query[Int]("limit")
                    .header("Authorization")
                    .output[User]
                val handler = HttpHandler.init(route) { case (id, limit, auth) =>
                    User(id, s"limit=$limit,auth=$auth")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/users/42?limit=10", HttpHeaders.empty.add("Authorization", "Bearer token"))
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyContains(response, "limit=10")
                        assertBodyContains(response, "auth=Bearer token")
                    }
                }
            }

            "handler with multiple path params + query + header" in run {
                val route = HttpRoute.get("orgs" / HttpPath.string("org") / "users" / HttpPath.int("id"))
                    .query[Boolean]("verbose", false)
                    .header("Accept")
                    .output[User]
                val handler = HttpHandler.init(route) { case (org, id, verbose, accept) =>
                    User(id, s"org=$org,verbose=$verbose,accept=$accept")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.get(
                            s"http://localhost:$port/orgs/acme/users/42?verbose=true",
                            HttpHeaders.empty.add("Accept", "application/json")
                        )
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyContains(response, "org=acme")
                        assertBodyContains(response, "verbose=true")
                        assertBodyContains(response, "accept=application/json")
                    }
                }
            }

            "handler with async effect" in run {
                val route   = HttpRoute.get("users").output[Seq[User]]
                val handler = HttpHandler.init(route) { _ => Async.delay(10.millis)(Seq(User(1, "Alice"))) }
                startTestServer(handler).map { port =>
                    testGetAs[Seq[User]](port, "/users").map { users =>
                        assert(users == Seq(User(1, "Alice")))
                    }
                }
            }

            "handler with custom effect" in {
                val route   = HttpRoute.get("users").output[Seq[User]]
                val handler = HttpHandler.init(route) { _ => Env.get[String].map(prefix => Seq(User(1, prefix))) }
                assert(handler.route eq route)
            }
        }

        "raw handler" - {
            "with method and path" in run {
                val handler = HttpHandler.get("/health") { _ => HttpResponse.ok("healthy") }
                startTestServer(handler).map { port =>
                    testGet(port, "/health").map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "healthy")
                    }
                }
            }

            "accessing request properties" in run {
                val handler = HttpHandler.post("/echo") { request =>
                    val body = request.bodyText
                    val ua   = request.header("User-Agent").getOrElse("unknown")
                    HttpResponse.ok(s"Received: $body from $ua")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.postText(s"http://localhost:$port/echo", "test-body")
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyContains(response, "Received: test-body")
                    }
                }
            }

            "with async response" in run {
                val handler = HttpHandler.get("/delayed") { _ =>
                    Async.delay(100.millis)(HttpResponse.ok("done"))
                }
                startTestServer(handler).map { port =>
                    testGet(port, "/delayed").map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "done")
                    }
                }
            }

            "with int path capture" in run {
                val handler = HttpHandler.get("users" / HttpPath.int("id")) { (id, _) =>
                    HttpResponse.ok(s"user=$id")
                }
                startTestServer(handler).map { port =>
                    testGet(port, "/users/42").map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "user=42")
                    }
                }
            }

            "with string path capture" in run {
                val handler = HttpHandler.get("items" / HttpPath.string("slug")) { (slug, _) =>
                    HttpResponse.ok(s"slug=$slug")
                }
                startTestServer(handler).map { port =>
                    testGet(port, "/items/hello-world").map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "slug=hello-world")
                    }
                }
            }

            "with multiple path captures" in run {
                val handler = HttpHandler.get("users" / HttpPath.int("userId") / "posts" / HttpPath.int("postId")) { (userId, postId, _) =>
                    HttpResponse.ok(s"user=$userId,post=$postId")
                }
                startTestServer(handler).map { port =>
                    testGet(port, "/users/7/posts/99").map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "user=7,post=99")
                    }
                }
            }

            "with mixed string and int captures" in run {
                val handler = HttpHandler.get("orgs" / HttpPath.string("org") / "members" / HttpPath.int("id")) { (org, id, _) =>
                    HttpResponse.ok(s"org=$org,id=$id")
                }
                startTestServer(handler).map { port =>
                    testGet(port, "/orgs/acme/members/5").map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "org=acme,id=5")
                    }
                }
            }

            "path capture with post method" in run {
                val handler = HttpHandler.post("items" / HttpPath.int("id")) { (id, _) =>
                    HttpResponse.ok(s"posted=$id")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.postText(s"http://localhost:$port/items/123", "body")
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "posted=123")
                    }
                }
            }

            "path capture with put method" in run {
                val handler = HttpHandler.put("items" / HttpPath.int("id")) { (id, _) =>
                    HttpResponse.ok(s"updated=$id")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.put(s"http://localhost:$port/items/456", "body")
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "updated=456")
                    }
                }
            }

            "path capture with post body" in run {
                val handler = HttpHandler.post("users" / HttpPath.int("id") / "rename") { (id, request) =>
                    val newName = request.bodyText
                    HttpResponse.ok(s"user=$id,name=$newName")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.postText(s"http://localhost:$port/users/42/rename", "Alice")
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "user=42,name=Alice")
                    }
                }
            }

            "path capture with put body" in run {
                val handler = HttpHandler.put("items" / HttpPath.string("slug")) { (slug, request) =>
                    val body = request.bodyText
                    HttpResponse.ok(s"slug=$slug,body=$body")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.putText(s"http://localhost:$port/items/my-item", "updated content")
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "slug=my-item,body=updated content")
                    }
                }
            }

            "multiple path captures with post body" in run {
                val handler =
                    HttpHandler.post("orgs" / HttpPath.string("org") / "teams" / HttpPath.int("teamId")) { (org, teamId, request) =>
                        val body = request.bodyText
                        HttpResponse.ok(s"org=$org,team=$teamId,body=$body")
                    }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.postText(s"http://localhost:$port/orgs/acme/teams/7", "payload")
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "org=acme,team=7,body=payload")
                    }
                }
            }

            "path capture with delete method" in run {
                val handler = HttpHandler.delete("items" / HttpPath.int("id")) { (id, _) =>
                    HttpResponse.ok(s"deleted=$id")
                }
                startTestServer(handler).map { port =>
                    HttpClient.send(
                        HttpRequest.delete(s"http://localhost:$port/items/789")
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "deleted=789")
                    }
                }
            }
        }

        "handler apply method" in run {
            val handler = HttpHandler.get("/test") { _ => HttpResponse.ok("applied") }
            startTestServer(handler).map { port =>
                testGet(port, "/test").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "applied")
                }
            }
        }
    }

    "Integration scenarios" - {

        "server with single handler" in run {
            val handler = HttpHandler.get("/health") { _ =>
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
            val health = HttpHandler.get("/health") { _ => HttpResponse.ok("ok") }
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
            val users = HttpHandler.get("/users") { _ => HttpResponse.ok("users") }
            val posts = HttpHandler.get("/posts") { _ => HttpResponse.ok("posts") }
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
            val getUsers  = HttpHandler.get("/users") { _ => HttpResponse.ok("list") }
            val postUsers = HttpHandler.post("/users") { _ => HttpResponse.created(User(1, "new")) }
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
            val handler = HttpHandler.get("/health") { _ => HttpResponse.ok }
            startTestServer(handler).map { port =>
                testGet(port, "/unknown").map { response =>
                    assertStatus(response, Status.NotFound)
                }
            }
        }

        "405 for wrong method on existing path" in run {
            val handler = HttpHandler.get("/users") { _ => HttpResponse.ok("list") }
            startTestServer(handler).map { port =>
                HttpClient.send(HttpRequest.post(s"http://localhost:$port/users", "body")).map { response =>
                    assertStatus(response, Status.MethodNotAllowed)
                }
            }
        }

        "405 vs 404 distinction" in run {
            val getUsers  = HttpHandler.get("/users") { _ => HttpResponse.ok("list") }
            val postUsers = HttpHandler.post("/users") { _ => HttpResponse.created("new") }
            startTestServer(getUsers, postUsers).map { port =>
                // DELETE on /users should be 405 (path exists but method doesn't match)
                HttpClient.send(HttpRequest.delete(s"http://localhost:$port/users")).map { r1 =>
                    assertStatus(r1, Status.MethodNotAllowed)
                }.andThen {
                    // GET on /nonexistent should be 404 (path doesn't exist)
                    testGet(port, "/nonexistent").map { r2 =>
                        assertStatus(r2, Status.NotFound)
                    }
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
                Latch.init(1).map { startedLatch =>
                    Latch.init(1).map { doneLatch =>
                        val slowHandler = HttpHandler.get("/slow") { _ =>
                            startedLatch.release.andThen {
                                // Handler blocks on a long sleep - longer than client timeout (100ms)
                                // Sync.ensure releases the latch whether handler completes or is interrupted
                                Sync.ensure(doneLatch.release) {
                                    Async.sleep(10.seconds).andThen {
                                        handlerCompleted.set(true).andThen {
                                            HttpResponse.ok("done")
                                        }
                                    }
                                }
                            }
                        }
                        startTestServer(slowHandler).map { port =>
                            // Client request with short timeout (100ms < handler sleep)
                            HttpClient.withConfig(_.timeout(100.millis)) {
                                Abort.run(HttpClient.get[String](s"http://localhost:$port/slow"))
                            }.map { result =>
                                // Client should have timed out
                                assert(result.isFailure)
                            }.andThen {
                                // Wait for handler to be interrupted (doneLatch signals on interrupt or completion)
                                doneLatch.await.andThen {
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
                Latch.init(1).map { startedLatch =>
                    Latch.init(1).map { doneLatch =>
                        val slowHandler = HttpHandler.get("/slow") { _ =>
                            startedLatch.release.andThen {
                                // Handler blocks on a long sleep
                                // Sync.ensure releases the latch whether handler completes or is interrupted
                                Sync.ensure(doneLatch.release) {
                                    Async.sleep(10.seconds).andThen {
                                        handlerCompleted.set(true).andThen {
                                            HttpResponse.ok("done")
                                        }
                                    }
                                }
                            }
                        }
                        startTestServer(slowHandler).map { port =>
                            // Start request then interrupt the fiber once handler has started
                            Fiber.init(HttpClient.get[String](s"http://localhost:$port/slow")).map { clientFiber =>
                                // Wait for handler to start via latch
                                startedLatch.await.andThen {
                                    // Interrupt client fiber (simulates disconnect)
                                    clientFiber.interrupt.andThen {
                                        // Wait for handler to finish (interrupted or completed)
                                        doneLatch.await.andThen {
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
            val handler = HttpHandler.streamSse[Unit, Event, Any]("/events") { _ =>
                Stream.init(Seq(
                    HttpEvent(Event(1)),
                    HttpEvent(Event(2)),
                    HttpEvent(Event(3))
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
            val handler = HttpHandler.streamSse[Unit, String, Any]("/events") { _ =>
                Stream.init(Seq(
                    HttpEvent("hello", event = Present("greeting"), id = Present("1")),
                    HttpEvent("world", event = Present("greeting"), id = Present("2"))
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
            val handler = HttpHandler.streamSse[Unit, String, Any]("/events") { _ =>
                Stream.init(Seq(
                    HttpEvent("test", retry = Present(5000.millis))
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
            val handler = HttpHandler.streamNdjson[Unit, Event, Any]("/data") { _ =>
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
            val handler = HttpHandler.streamSse[Unit, String, Any]("/empty") { _ =>
                Stream.empty[HttpEvent[String]]
            }
            startTestServer(handler).map { port =>
                testGet(port, "/empty").map { response =>
                    assertStatus(response, Status.OK)
                    assert(response.bodyText.isEmpty || response.bodyText.trim.isEmpty)
                }
            }
        }

        "empty NDJSON stream" in run {
            val handler = HttpHandler.streamNdjson[Unit, Event, Any]("/empty") { _ =>
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
                Stream.init((1 to count).map(i => HttpEvent(Event(i))))
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
            val handler = HttpHandler.streamNdjson[Unit, Event, Any](Method.POST, "/data") { _ =>
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
            val defaultHandler = HttpHandler.get("/hello") { _ => HttpResponse.ok("world") }
            val sseHandler = HttpHandler.streamSse[Unit, Event, Any]("/events") { _ =>
                Stream.init(Seq(HttpEvent(Event(1))))
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

        "Content-Length via stream factory" in run {
            val data  = "Hello, Content-Length!"
            val bytes = data.getBytes("UTF-8")
            val handler = HttpHandler.get("/cl") { _ =>
                val s = Stream.init(Seq(Span.fromUnsafe(bytes)))
                HttpResponse.stream(s, bytes.length.toLong, Status.OK)
            }
            startTestServer(handler).map { port =>
                testGet(port, "/cl").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, data)
                    assertHeader(response, "Content-Length", bytes.length.toString)
                }
            }
        }

        "Content-Length multi-chunk stream" in run {
            val chunks   = Seq("chunk1-", "chunk2-", "chunk3")
            val totalLen = chunks.map(_.getBytes("UTF-8").length).sum
            val handler = HttpHandler.get("/cl-multi") { _ =>
                val s = Stream.init(chunks.map(c => Span.fromUnsafe(c.getBytes("UTF-8"))))
                HttpResponse.stream(s, totalLen.toLong, Status.OK)
            }
            startTestServer(handler).map { port =>
                testGet(port, "/cl-multi").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "chunk1-chunk2-chunk3")
                    assertHeader(response, "Content-Length", totalLen.toString)
                }
            }
        }

        "no Content-Length uses chunked (backward compat)" in run {
            val handler = HttpHandler.get("/chunked") { _ =>
                val s = Stream.init(Seq(Span.fromUnsafe("chunked".getBytes("UTF-8"))))
                HttpResponse.stream(s)
            }
            startTestServer(handler).map { port =>
                testGet(port, "/chunked").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "chunked")
                }
            }
        }

        "Content-Length via addHeader" in run {
            val data  = "manual-header"
            val bytes = data.getBytes("UTF-8")
            val handler = HttpHandler.get("/cl-manual") { _ =>
                val s = Stream.init(Seq(Span.fromUnsafe(bytes)))
                HttpResponse.stream(s).setHeader("Content-Length", bytes.length.toString)
            }
            startTestServer(handler).map { port =>
                testGet(port, "/cl-manual").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, data)
                    assertHeader(response, "Content-Length", bytes.length.toString)
                }
            }
        }

        "zero Content-Length empty stream" in run {
            val handler = HttpHandler.get("/cl-zero") { _ =>
                val s = Stream.empty[Span[Byte]]
                HttpResponse.stream(s, 0L, Status.OK)
            }
            startTestServer(handler).map { port =>
                testGet(port, "/cl-zero").map { response =>
                    assertStatus(response, Status.OK)
                    assert(response.bodyText.isEmpty)
                    assertHeader(response, "Content-Length", "0")
                }
            }
        }

        "streaming response preserves duplicate Set-Cookie headers" in run {
            val handler = HttpHandler.get("/multi-cookie") { _ =>
                val s = Stream.init(Seq(Span.fromUnsafe("ok".getBytes("UTF-8"))))
                HttpResponse.stream(s)
                    .addCookie(HttpResponse.Cookie("a", "1"))
                    .addCookie(HttpResponse.Cookie("b", "2"))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/multi-cookie").map { response =>
                    assertStatus(response, Status.OK)
                    // Collect all Set-Cookie header values from raw headers
                    val setCookies = Seq.newBuilder[String]
                    response.headers.foreach { (k, v) =>
                        if k.equalsIgnoreCase("Set-Cookie") then setCookies += v
                    }
                    val cookies = setCookies.result()
                    assert(cookies.exists(_.startsWith("a=1")), s"Expected cookie a=1 in $cookies")
                    assert(cookies.exists(_.startsWith("b=2")), s"Expected cookie b=2 in $cookies")
                }
            }
        }

        "negative contentLength rejected" in {
            assertThrows[IllegalArgumentException] {
                HttpResponse.stream(Stream.empty[Span[Byte]], -1L, Status.OK)
            }
        }
    }

    "Client streaming" - {

        case class Item(name: String) derives Schema, CanEqual

        "streamSse receives SSE events" in run {
            val handler = HttpHandler.streamSse[Unit, Item, Any]("/sse") { _ =>
                Stream.init(Seq(
                    HttpEvent(Item("a")),
                    HttpEvent(Item("b")),
                    HttpEvent(Item("c"))
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
            val handler = HttpHandler.streamSse[Unit, String, Any]("/sse") { _ =>
                Stream.init(Seq(
                    HttpEvent("hello", event = Present("greeting"), id = Present("1")),
                    HttpEvent("world", event = Present("greeting"), id = Present("2"))
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
            val handler = HttpHandler.streamNdjson[Unit, Item, Any]("/data") { _ =>
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
            val handler = HttpHandler.streamSse[Unit, String, Any]("/empty") { _ =>
                Stream.empty[HttpEvent[String]]
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
            val handler = HttpHandler.streamNdjson[Unit, Item, Any]("/empty") { _ =>
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
            val handler = HttpHandler.streamingBody(Method.POST, "/upload") { request =>
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
            val handler = HttpHandler.streamingBody(Method.POST, "/upload") { request =>
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
            val handler = HttpHandler.streamingBody(Method.POST, "/upload") { request =>
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
            val bufferedHandler = HttpHandler.get("/hello") { _ => HttpResponse.ok("world") }
            val streamingHandler = HttpHandler.streamingBody(Method.POST, "/upload") { request =>
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
            val handler = HttpHandler.streamingBody(Method.POST, "/upload") { request =>
                request.bodyStream.run.map(_ => HttpResponse.ok)
            }
            startTestServer(handler).map { port =>
                val bodyStream = Stream.init(Seq(Span.fromUnsafe("data".getBytes("UTF-8"))))
                val request = HttpRequest.stream(
                    Method.POST,
                    s"http://localhost:$port/nonexistent",
                    bodyStream
                )
                HttpClient.stream(request).map { response =>
                    assert(response.status == Status.NotFound)
                }
            }
        }

        "413 for oversized buffered request" in run {
            val handler = HttpHandler.post("/upload") { request =>
                HttpResponse.ok(s"received ${request.bodyBytes.size} bytes")
            }
            // Server with small maxContentLength
            HttpServer.init(HttpServer.Config(port = 0, maxContentLength = 100), PlatformTestBackend.backend)(handler).map { server =>
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

    "Route auth" - {

        "authBasic handler receives decoded username and password" in run {
            val route = HttpRoute.get("me").authBasic.outputText
            val handler = route.handle { case (username, password) =>
                s"user=$username,pass=$password"
            }
            startTestServer(handler).map { port =>
                val encoded = java.util.Base64.getEncoder.encodeToString("alice:secret123".getBytes("UTF-8"))
                HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/me", HttpHeaders.empty.add("Authorization", s"Basic $encoded"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "user=alice,pass=secret123")
                }
            }
        }

        "authBearer handler receives token without Bearer prefix" in run {
            val route = HttpRoute.get("me").authBearer.outputText
            val handler = route.handle { token =>
                s"token=$token"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/me", HttpHeaders.empty.add("Authorization", "Bearer mytoken123"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "token=mytoken123")
                    // Should NOT contain the "Bearer " prefix
                    val body = response.bodyText
                    assert(!body.contains("token=Bearer"), s"Token should not include Bearer prefix: $body")
                }
            }
        }

        "authBasic with path param" in run {
            val route = HttpRoute.get("users" / HttpPath.int("id")).authBasic.outputText
            val handler = route.handle { case (id, username, password) =>
                s"id=$id,user=$username,pass=$password"
            }
            startTestServer(handler).map { port =>
                val encoded = java.util.Base64.getEncoder.encodeToString("bob:pass".getBytes("UTF-8"))
                HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/users/42", HttpHeaders.empty.add("Authorization", s"Basic $encoded"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "id=42,user=bob,pass=pass")
                }
            }
        }

        "authBasic returns 401 when Authorization header missing" in run {
            val route = HttpRoute.get("me").authBasic.outputText
            val handler = route.handle { case (username, password) =>
                s"user=$username"
            }
            startTestServer(handler).map { port =>
                testGet(port, "/me").map { response =>
                    assertStatus(response, Status.Unauthorized)
                }
            }
        }

        "authBearer returns 401 when Authorization header missing" in run {
            val route = HttpRoute.get("me").authBearer.outputText
            val handler = route.handle { token =>
                s"token=$token"
            }
            startTestServer(handler).map { port =>
                testGet(port, "/me").map { response =>
                    assertStatus(response, Status.Unauthorized)
                }
            }
        }

        "authApiKey handler receives key value" in run {
            val route = HttpRoute.get("data").authApiKey("X-API-Key").outputText
            val handler = route.handle { key =>
                s"key=$key"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/data", HttpHeaders.empty.add("X-API-Key", "abc123"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "key=abc123")
                }
            }
        }

        "authApiKey with path param" in run {
            val route = HttpRoute.get("data" / HttpPath.string("name")).authApiKey("X-API-Key").outputText
            val handler = route.handle { case (name, key) =>
                s"name=$name,key=$key"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/data/test", HttpHeaders.empty.add("X-API-Key", "mykey"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "name=test,key=mykey")
                }
            }
        }

        "authApiKey returns 401 when header missing" in run {
            val route = HttpRoute.get("data").authApiKey("X-API-Key").outputText
            val handler = route.handle { key =>
                s"key=$key"
            }
            startTestServer(handler).map { port =>
                testGet(port, "/data").map { response =>
                    assertStatus(response, Status.Unauthorized)
                }
            }
        }
    }

    "Route cookies" - {

        "cookie handler receives cookie value" in run {
            val route = HttpRoute.get("me").cookie("session").outputText
            val handler = route.handle { session =>
                s"session=$session"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/me", HttpHeaders.empty.add("Cookie", "session=abc123"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "session=abc123")
                }
            }
        }

        "cookie with default value when cookie missing" in run {
            val route = HttpRoute.get("me").cookie("session", "default-session").outputText
            val handler = route.handle { session =>
                s"session=$session"
            }
            startTestServer(handler).map { port =>
                testGet(port, "/me").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "session=default-session")
                }
            }
        }

        "cookie with path param" in run {
            val route = HttpRoute.get("users" / HttpPath.int("id")).cookie("token").outputText
            val handler = route.handle { case (id, token) =>
                s"id=$id,token=$token"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/users/42", HttpHeaders.empty.add("Cookie", "token=xyz"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "id=42,token=xyz")
                }
            }
        }

        "multiple cookies" in run {
            val route = HttpRoute.get("me").cookie("session").cookie("lang").outputText
            val handler = route.handle { case (session, lang) =>
                s"session=$session,lang=$lang"
            }
            startTestServer(handler).map { port =>
                HttpClient.send(
                    HttpRequest.get(s"http://localhost:$port/me", HttpHeaders.empty.add("Cookie", "session=abc; lang=en"))
                ).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "session=abc,lang=en")
                }
            }
        }

        "missing required cookie returns 400" in run {
            val route = HttpRoute.get("me").cookie("session").outputText
            val handler = route.handle { session =>
                s"session=$session"
            }
            startTestServer(handler).map { port =>
                testGet(port, "/me").map { response =>
                    assertStatus(response, Status.BadRequest)
                }
            }
        }
    }

    "NoContent and empty body handling" - {

        "204 NoContent has empty body" in run {
            val handler = HttpHandler.delete("/items/1") { _ =>
                HttpResponse.noContent
            }
            startTestServer(handler).map { port =>
                testDelete(port, "/items/1").map { response =>
                    assertStatus(response, Status.NoContent)
                    assert(response.bodyText.isEmpty)
                }
            }
        }

        "empty body POST handler" in run {
            val handler = HttpHandler.post("/trigger") { request =>
                val bodyLen = request.bodyBytes.size
                HttpResponse.ok(s"received $bodyLen bytes")
            }
            startTestServer(handler).map { port =>
                HttpClient.send(HttpRequest.postText(s"http://localhost:$port/trigger", "")).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyContains(response, "received 0 bytes")
                }
            }
        }
    }

    "URL encoding through server" - {

        "percent-encoded path segments" in run {
            val handler = HttpHandler.get("items" / HttpPath.string("name")) { (name, _) =>
                HttpResponse.ok(s"name=$name")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/items/hello%20world").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "name=hello world")
                }
            }
        }

        "query param with plus sign through route" in run {
            val handler = HttpHandler.get("search") { request =>
                val q = request.query("q").getOrElse("missing")
                HttpResponse.ok(s"q=$q")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/search?q=hello+world").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "q=hello world")
                }
            }
        }

        "non-ASCII percent-encoded query param through route" in run {
            val handler = HttpHandler.get("search") { request =>
                val q = request.query("q").getOrElse("missing")
                HttpResponse.ok(s"q=$q")
            }
            startTestServer(handler).map { port =>
                // café = caf%C3%A9
                testGet(port, "/search?q=caf%C3%A9").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "q=café")
                }
            }
        }

        "query param with special characters" in run {
            val handler = HttpHandler.get("search") { request =>
                val q = request.query("q").getOrElse("missing")
                HttpResponse.ok(s"q=$q")
            }
            startTestServer(handler).map { port =>
                // a&b=c encoded: a%26b%3Dc
                testGet(port, "/search?q=a%26b%3Dc").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "q=a&b=c")
                }
            }
        }
    }

    "Multiple Set-Cookie through server" - {

        "buffered response preserves multiple Set-Cookie headers" in run {
            val handler = HttpHandler.get("/cookies") { _ =>
                HttpResponse.ok("ok")
                    .addCookie(HttpResponse.Cookie("a", "1"))
                    .addCookie(HttpResponse.Cookie("b", "2"))
                    .addCookie(HttpResponse.Cookie("c", "3"))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/cookies").map { response =>
                    assertStatus(response, Status.OK)
                    val setCookies = Seq.newBuilder[String]
                    response.headers.foreach { (k, v) =>
                        if k.equalsIgnoreCase("Set-Cookie") then setCookies += v
                    }
                    val cookies = setCookies.result()
                    assert(cookies.exists(_.startsWith("a=1")), s"Expected cookie a=1 in $cookies")
                    assert(cookies.exists(_.startsWith("b=2")), s"Expected cookie b=2 in $cookies")
                    assert(cookies.exists(_.startsWith("c=3")), s"Expected cookie c=3 in $cookies")
                }
            }
        }

        "cookie attributes preserved through round-trip" in run {
            val handler = HttpHandler.get("/cookies") { _ =>
                HttpResponse.ok("ok")
                    .addCookie(HttpResponse.Cookie("session", "abc", httpOnly = true, secure = true, path = Present("/api")))
            }
            startTestServer(handler).map { port =>
                testGet(port, "/cookies").map { response =>
                    assertStatus(response, Status.OK)
                    val setCookies = Seq.newBuilder[String]
                    response.headers.foreach { (k, v) =>
                        if k.equalsIgnoreCase("Set-Cookie") then setCookies += v
                    }
                    val cookies = setCookies.result()
                    assert(cookies.nonEmpty, "Expected Set-Cookie header")
                    val sessionCookie = cookies.find(_.startsWith("session=abc"))
                    assert(sessionCookie.isDefined, s"Expected session cookie in $cookies")
                    val cookieStr = sessionCookie.get
                    assert(cookieStr.contains("HttpOnly"), s"Expected HttpOnly in $cookieStr")
                    assert(cookieStr.contains("Secure"), s"Expected Secure in $cookieStr")
                    assert(cookieStr.contains("Path=/api"), s"Expected Path=/api in $cookieStr")
                }
            }
        }
    }

    "Concurrent response integrity" - {

        "concurrent requests get correct response bodies (no mixing)" in run {
            val handler = HttpHandler.get("items" / HttpPath.int("id")) { (id, _) =>
                // Simulate some work to increase chance of interleaving
                Async.delay(1.millis)(HttpResponse.ok(s"item-$id"))
            }
            startTestServer(handler).map { port =>
                Async.fill(20, 20) {
                    val id = scala.util.Random.nextInt(1000)
                    testGet(port, s"/items/$id").map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, s"item-$id")
                    }
                }.andThen(succeed)
            }
        }

        "concurrent requests preserve per-request headers" in run {
            val handler = HttpHandler.get("/echo") { req =>
                val reqId = req.header("X-Req-Id").getOrElse("none")
                HttpResponse.ok(reqId).setHeader("X-Echo-Id", reqId)
            }
            startTestServer(handler).map { port =>
                Async.fill(20, 20) {
                    val id = java.util.concurrent.ThreadLocalRandom.current().nextInt(100000).toString
                    HttpClient.send(
                        HttpRequest.get(s"http://localhost:$port/echo", HttpHeaders.empty.add("X-Req-Id", id))
                    ).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, id)
                        assertHeader(response, "X-Echo-Id", id)
                    }
                }.andThen(succeed)
            }
        }
    }

    "HEAD request" - {

        "returns headers without body" in run {
            val handler = HttpHandler.init(Method.HEAD, "/data") { _ =>
                HttpResponse.ok("this body should not be sent")
                    .setHeader("X-Custom", "present")
                    .setHeader("Content-Length", "35")
            }
            startTestServer(handler).map { port =>
                HttpClient.send(HttpRequest.head(s"http://localhost:$port/data")).map { response =>
                    assertStatus(response, Status.OK)
                    assertHeader(response, "X-Custom", "present")
                }
            }
        }
    }

    "Path encoding edge cases" - {

        "encoded slash %2F in path capture" in run {
            val handler = HttpHandler.get("files" / HttpPath.string("name")) { (name, _) =>
                HttpResponse.ok(s"name=$name")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/files/a%2Fb").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "name=a/b")
                }
            }
        }

        "plus sign in path is literal (not space)" in run {
            val handler = HttpHandler.get("items" / HttpPath.string("name")) { (name, _) =>
                HttpResponse.ok(s"name=$name")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/items/a+b").map { response =>
                    assertStatus(response, Status.OK)
                    // In URL path, + is literal (only means space in query string form-encoded)
                    assertBodyText(response, "name=a+b")
                }
            }
        }

        "percent-encoded non-ASCII in path capture" in run {
            val handler = HttpHandler.get("items" / HttpPath.string("name")) { (name, _) =>
                HttpResponse.ok(s"name=$name")
            }
            startTestServer(handler).map { port =>
                // café in UTF-8 percent-encoded
                testGet(port, "/items/caf%C3%A9").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "name=café")
                }
            }
        }

        "double-encoded percent in path" in run {
            val handler = HttpHandler.get("items" / HttpPath.string("name")) { (name, _) =>
                HttpResponse.ok(s"name=$name")
            }
            startTestServer(handler).map { port =>
                // %2520 means literal %20 (the % is encoded as %25)
                testGet(port, "/items/%2520").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "name=%20")
                }
            }
        }

        "multiple path captures with encoding" in run {
            val handler = HttpHandler.get("orgs" / HttpPath.string("org") / "items" / HttpPath.string("item")) { (org, item, _) =>
                HttpResponse.ok(s"org=$org,item=$item")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/orgs/my%20org/items/hello%20world").map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "org=my org,item=hello world")
                }
            }
        }
    }

    "Large response body" - {

        "100KB response body" in run {
            val largeBody = "x" * (100 * 1024)
            val handler = HttpHandler.get("/large") { _ =>
                HttpResponse.ok(largeBody)
            }
            HttpServer.init(HttpServer.Config(port = 0, maxContentLength = 200 * 1024), PlatformTestBackend.backend)(handler).map {
                server =>
                    HttpClient.send(HttpRequest.get(s"http://localhost:${server.port}/large")).map { response =>
                        assertStatus(response, Status.OK)
                        assert(response.bodyText.length == 100 * 1024)
                    }
            }
        }
    }

    "Handler error resilience" - {

        "handler throwing returns 500 and doesn't crash server" in run {
            val handler = HttpHandler.get("/crash") { _ =>
                throw new RuntimeException("handler crash")
            }
            val health = HttpHandler.get("/health") { _ => HttpResponse.ok("ok") }
            startTestServer(handler, health).map { port =>
                testGet(port, "/crash").map { response =>
                    assertStatus(response, Status.InternalServerError)
                }.andThen {
                    // Server should still be alive
                    testGet(port, "/health").map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "ok")
                    }
                }
            }
        }

        "handler returning null body doesn't crash" in run {
            val handler = HttpHandler.get("/null") { _ =>
                HttpResponse.ok("")
            }
            startTestServer(handler).map { port =>
                testGet(port, "/null").map { response =>
                    assertStatus(response, Status.OK)
                }
            }
        }
    }

    "Multiple handlers same path different methods" - {

        "GET and POST on same path" in run {
            val getHandler  = HttpHandler.get("/resource") { _ => HttpResponse.ok("get") }
            val postHandler = HttpHandler.post("/resource") { _ => HttpResponse(Status.Created, "post") }
            startTestServer(getHandler, postHandler).map { port =>
                testGet(port, "/resource").map { r1 =>
                    assertStatus(r1, Status.OK)
                    assertBodyText(r1, "get")
                }.andThen {
                    HttpClient.send(HttpRequest.postText(s"http://localhost:$port/resource", "body")).map { r2 =>
                        assertStatus(r2, Status.Created)
                        assertBodyText(r2, "post")
                    }
                }
            }
        }

        "GET, PUT, DELETE on same path" in run {
            val getH    = HttpHandler.get("/item") { _ => HttpResponse.ok("get-item") }
            val putH    = HttpHandler.put("/item") { _ => HttpResponse.ok("put-item") }
            val deleteH = HttpHandler.delete("/item") { _ => HttpResponse.noContent }
            startTestServer(getH, putH, deleteH).map { port =>
                testGet(port, "/item").map { r1 =>
                    assertBodyText(r1, "get-item")
                }.andThen {
                    HttpClient.send(HttpRequest.putText(s"http://localhost:$port/item", "body")).map { r2 =>
                        assertBodyText(r2, "put-item")
                    }
                }.andThen {
                    testDelete(port, "/item").map { r3 =>
                        assertStatus(r3, Status.NoContent)
                    }
                }
            }
        }
    }

    "Async.timeout and Async.race with handlers" - {

        "timeout catches slow handler" in run {
            val handler = HttpHandler.get("/slow") { _ =>
                Async.sleep(1.hour).andThen(HttpResponse.ok("never"))
            }
            startTestServer(handler).map { port =>
                Abort.run[Timeout](
                    Async.timeout(1.second)(testGetAs[String](port, "/slow"))
                ).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "race two requests to blocking handler" in run {
            AtomicInt.init.map { counter =>
                Promise.init[Unit, Nothing].map { promise =>
                    val handler = HttpHandler.get("/race") { _ =>
                        counter.incrementAndGet.map { num =>
                            if num == 1 then
                                promise.get.andThen(HttpResponse.ok("right"))
                            else
                                promise.completeUnitDiscard.andThen(
                                    Async.sleep(1.hour).andThen(HttpResponse.ok("wrong"))
                                )
                        }
                    }
                    startTestServer(handler).map { port =>
                        Async.race(
                            testGetAs[String](port, "/race"),
                            testGetAs[String](port, "/race")
                        ).map { result =>
                            assert(result == "right")
                        }
                    }
                }
            }
        }

        "timeout inside race with timer" in run {
            Promise.init[Unit, Nothing].map { promise =>
                val handler = HttpHandler.get("/tr") { _ =>
                    promise.get.andThen(HttpResponse.ok("right"))
                }
                startTestServer(handler).map { port =>
                    val normalReq = testGetAs[String](port, "/tr")
                    val timer =
                        Async.sleep(1.second)
                            .andThen(promise.completeUnitDiscard)
                            .andThen(Async.sleep(1.hour))
                            .andThen("never")
                    Async.race(normalReq, timer).map { result =>
                        assert(result == "right")
                    }
                }
            }
        }
    }

end HttpServerTest
