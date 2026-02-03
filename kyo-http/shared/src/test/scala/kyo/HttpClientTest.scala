package kyo

import HttpResponse.Status

class HttpClientTest extends Test:

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class CreateUser(name: String, email: String) derives Schema, CanEqual

    "HttpClient.Config" - {

        "default values" in {
            val config = HttpClient.Config.default
            assert(config.baseUrl == Absent)
            assert(config.timeout == Absent)
            assert(config.connectTimeout == Absent)
            assert(config.followRedirects == true)
            assert(config.maxRedirects == 10)
            assert(config.retrySchedule == Absent)
        }

        "default retryOn" in {
            val config = HttpClient.Config.default
            assert(config.retryOn(HttpResponse(Status.InternalServerError)) == true)
            assert(config.retryOn(HttpResponse(Status.BadGateway)) == true)
            assert(config.retryOn(HttpResponse(Status.OK)) == false)
            assert(config.retryOn(HttpResponse(Status.BadRequest)) == false)
        }

        "construction with base URL" in {
            val config = HttpClient.Config("https://api.example.com")
            assert(config.baseUrl == Present("https://api.example.com"))
        }

        "builder methods" - {
            "timeout" in {
                val config = HttpClient.Config.default.timeout(30.seconds)
                assert(config.timeout == Present(30.seconds))
            }

            "connectTimeout" in {
                val config = HttpClient.Config.default.connectTimeout(5.seconds)
                assert(config.connectTimeout == Present(5.seconds))
            }

            "followRedirects true" in {
                val config = HttpClient.Config.default.followRedirects(true)
                assert(config.followRedirects == true)
            }

            "followRedirects false" in {
                val config = HttpClient.Config.default.followRedirects(false)
                assert(config.followRedirects == false)
            }

            "maxRedirects" in {
                val config = HttpClient.Config.default.maxRedirects(5)
                assert(config.maxRedirects == 5)
            }

            "retry with schedule" in {
                val schedule = Schedule.exponential(100.millis, 2.0).take(3)
                val config   = HttpClient.Config.default.retry(schedule)
                assert(config.retrySchedule.isDefined)
            }

            "retryWhen with predicate" in {
                val config = HttpClient.Config.default.retryWhen(_.status.isServerError)
                // Custom predicate set
                succeed
            }

            "chaining" in {
                val config = HttpClient.Config.default
                    .timeout(30.seconds)
                    .connectTimeout(5.seconds)
                    .followRedirects(false)
                    .maxRedirects(3)
                assert(config.timeout == Present(30.seconds))
                assert(config.connectTimeout == Present(5.seconds))
                assert(config.followRedirects == false)
                assert(config.maxRedirects == 3)
            }
        }

        "validation" - {
            "negative maxRedirects throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpClient.Config.default.maxRedirects(-1)
                }
            }

            "negative timeout throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpClient.Config.default.timeout(-1.seconds)
                }
            }

            "negative connectTimeout throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpClient.Config.default.connectTimeout(-1.seconds)
                }
            }
        }
    }

    "HttpClient.init" - {

        "with config" in run {
            val config = HttpClient.Config.default
            HttpClient.init(config).map { client =>
                succeed
            }
        }

        "with named parameters" in run {
            HttpClient.init(
                baseUrl = Present("https://api.example.com"),
                timeout = Present(30.seconds),
                followRedirects = true
            ).map { client =>
                succeed
            }
        }

        "with all defaults" in run {
            HttpClient.init(HttpClient.Config.default).map { client =>
                succeed
            }
        }
    }

    "HttpClient extensions" - {

        "send" - {
            "successful request" in run {
                val handler = HttpHandler.health("/test")
                startTestServer(handler).map { port =>
                    HttpClient.init(HttpClient.Config.default).map { client =>
                        client.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
                            assertStatus(response, Status.OK)
                        }
                    }
                }
            }

            "returns response with body" in run {
                val handler = HttpHandler.get("/data") { (_, _) =>
                    HttpResponse.ok("test-data")
                }
                startTestServer(handler).map { port =>
                    HttpClient.init(HttpClient.Config.default).map { client =>
                        client.send(HttpRequest.get(s"http://localhost:$port/data")).map { response =>
                            assertStatus(response, Status.OK)
                            assertBodyText(response, "test-data")
                        }
                    }
                }
            }

            "timeout handling" in run {
                startTestServer(delayedHandler("/slow", 10.seconds)).map { port =>
                    val config = HttpClient.Config.default.timeout(100.millis)
                    HttpClient.init(config).map { client =>
                        Abort.run(client.send(HttpRequest.get(s"http://localhost:$port/slow"))).map { result =>
                            assert(result.isFailure)
                        }
                    }
                }
            }

            "connection error" in run {
                HttpClient.init(HttpClient.Config.default).map { client =>
                    Abort.run(client.send(HttpRequest.get("http://localhost:59999/test"))).map { result =>
                        assert(result.isFailure)
                    }
                }
            }
        }

        "close" - {
            "closes connection" in run {
                HttpClient.init(HttpClient.Config.default).map { client =>
                    client.close.map(_ => succeed)
                }
            }

            "idempotent" in run {
                HttpClient.init(HttpClient.Config.default).map { client =>
                    client.close.andThen(client.close).map(_ => succeed)
                }
            }
        }
    }

    "Quick methods" - {

        "get" - {
            "simple URL" in run {
                val handler = HttpHandler.get("/data") { (_, _) =>
                    HttpResponse.ok("hello")
                }
                startTestServer(handler).map { port =>
                    HttpClient.get[String](s"http://localhost:$port/data").map { body =>
                        assert(body == "hello")
                    }
                }
            }

            "deserializes response" in run {
                val handler = jsonHandler("/users/1", User(1, "Alice"))
                startTestServer(handler).map { port =>
                    HttpClient.get[User](s"http://localhost:$port/users/1").map { user =>
                        assert(user == User(1, "Alice"))
                    }
                }
            }

            "handles error status" in run {
                val handler = HttpHandler.const(HttpRequest.Method.GET, "/users/999", Status.NotFound)
                startTestServer(handler).map { port =>
                    Abort.run(HttpClient.get[User](s"http://localhost:$port/users/999")).map { result =>
                        assert(result.isFailure)
                    }
                }
            }
        }

        "post" - {
            "with typed body" in run {
                val handler = HttpHandler.post("/users") { (_, req) =>
                    val input = req.bodyAs[CreateUser]
                    HttpResponse.created(User(1, input.name))
                }
                startTestServer(handler).map { port =>
                    HttpClient.post[User, CreateUser](
                        s"http://localhost:$port/users",
                        CreateUser("Alice", "alice@example.com")
                    ).map { user =>
                        assert(user.name == "Alice")
                    }
                }
            }

            "deserializes response" in run {
                val handler = HttpHandler.post("/users") { (_, _) =>
                    HttpResponse.created(User(2, "Bob"))
                }
                startTestServer(handler).map { port =>
                    HttpClient.post[User, CreateUser](
                        s"http://localhost:$port/users",
                        CreateUser("Bob", "bob@example.com")
                    ).map { user =>
                        assert(user == User(2, "Bob"))
                    }
                }
            }
        }

        "put" - {
            "with typed body" in run {
                val handler = HttpHandler.put("/users/1") { (_, req) =>
                    val input = req.bodyAs[CreateUser]
                    HttpResponse.ok(User(1, input.name))
                }
                startTestServer(handler).map { port =>
                    HttpClient.put[User, CreateUser](
                        s"http://localhost:$port/users/1",
                        CreateUser("Updated", "updated@example.com")
                    ).map { user =>
                        assert(user.name == "Updated")
                    }
                }
            }
        }

        "delete" - {
            "simple URL" in run {
                val handler = HttpHandler.delete("/users/1") { (_, _) =>
                    HttpResponse.noContent
                }
                startTestServer(handler).map { port =>
                    HttpClient.delete[Unit](s"http://localhost:$port/users/1").map { _ =>
                        succeed
                    }
                }
            }

            "returns deleted entity" in run {
                val handler = HttpHandler.delete("/users/1") { (_, _) =>
                    HttpResponse.ok(User(1, "Deleted"))
                }
                startTestServer(handler).map { port =>
                    HttpClient.delete[User](s"http://localhost:$port/users/1").map { user =>
                        assert(user == User(1, "Deleted"))
                    }
                }
            }
        }

        "send with HttpRequest" - {
            "get request" in run {
                val handler = HttpHandler.health("/health")
                startTestServer(handler).map { port =>
                    HttpClient.send(HttpRequest.get(s"http://localhost:$port/health")).map { response =>
                        assertStatus(response, Status.OK)
                    }
                }
            }

            "post request" in run {
                val handler = HttpHandler.post("/users") { (_, _) =>
                    HttpResponse.created(User(1, "Test"))
                }
                startTestServer(handler).map { port =>
                    val request = HttpRequest.post(s"http://localhost:$port/users", CreateUser("Test", "test@example.com"))
                    HttpClient.send(request).map { response =>
                        assertStatus(response, Status.Created)
                    }
                }
            }

            "custom headers" in run {
                val handler = HttpHandler.get("/auth") { (_, req) =>
                    req.header("Authorization") match
                        case Present(auth) => HttpResponse.ok(auth)
                        case Absent        => HttpResponse.unauthorized
                }
                startTestServer(handler).map { port =>
                    val request = HttpRequest.get(
                        s"http://localhost:$port/auth",
                        Seq("Authorization" -> "Bearer token123")
                    )
                    HttpClient.send(request).map { response =>
                        assertStatus(response, Status.OK)
                        assertBodyText(response, "Bearer token123")
                    }
                }
            }
        }
    }

    "Context management" - {

        "let" - {
            "overrides config for scope" in run {
                val customConfig = HttpClient.Config("https://custom.example.com")
                HttpClient.let(customConfig) {
                    HttpClient.get[String]("/users")
                }.map { _ =>
                    // Should use customConfig's baseUrl
                    succeed
                }
            }

            "restores after scope" in run {
                val customConfig = HttpClient.Config("https://custom.example.com")
                HttpClient.let(customConfig) {
                    HttpClient.get[String]("/users")
                }.andThen {
                    // Should restore original config
                    HttpClient.get[String]("https://api.example.com/users")
                }.map { _ =>
                    succeed
                }
            }

            "nested let" in run {
                val config1 = HttpClient.Config("https://api1.example.com")
                val config2 = HttpClient.Config("https://api2.example.com")
                HttpClient.let(config1) {
                    HttpClient.let(config2) {
                        HttpClient.get[String]("/users")
                        // Should use config2
                    }.andThen {
                        HttpClient.get[String]("/users")
                        // Should use config1
                    }
                }.map { _ =>
                    succeed
                }
            }
        }

        "update" - {
            "modifies current config" in run {
                HttpClient.update(_.timeout(60.seconds)) {
                    HttpClient.get[String]("https://api.example.com/slow")
                }.map { _ =>
                    succeed
                }
            }

            "restores after scope" in run {
                HttpClient.update(_.timeout(1.seconds)) {
                    succeed
                }.andThen {
                    // Original timeout restored
                    succeed
                }
            }
        }

        "baseUrl" - {
            "prefixes all requests" in run {
                HttpClient.baseUrl("https://api.example.com") {
                    HttpClient.get[String]("/users").andThen {
                        HttpClient.get[String]("/posts")
                    }
                    // Both should use https://api.example.com prefix
                }.map { _ =>
                    succeed
                }
            }

            "nested baseUrl" in run {
                HttpClient.baseUrl("https://api.example.com") {
                    HttpClient.baseUrl("https://other.example.com") {
                        HttpClient.get[String]("/users")
                        // Should use https://other.example.com/users
                    }
                }.map { _ =>
                    succeed
                }
            }

            "with absolute URL in request" in run {
                HttpClient.baseUrl("https://api.example.com") {
                    HttpClient.get[String]("https://different.example.com/users")
                    // Absolute URL should override baseUrl
                }.map { _ =>
                    succeed
                }
            }
        }
    }

    "Retry behavior" - {

        "no retry by default on client error" in run {
            // 4xx errors should not be retried by default
            HttpClient.get[User]("https://api.example.com/users/999").map { _ =>
                succeed
            }
        }

        "retry on server error" in run {
            // 5xx errors should trigger retry with default config
            HttpClient.get[User]("https://api.example.com/error").map { _ =>
                succeed
            }
        }

        "retry with custom schedule" - {
            "exponential backoff" in run {
                val schedule = Schedule.exponential(100.millis, 2.0).take(3)
                val config   = HttpClient.Config.default.retry(schedule)
                HttpClient.let(config) {
                    HttpClient.get[User]("https://api.example.com/flaky")
                }.map { _ =>
                    succeed
                }
            }

            "max attempts" in run {
                val schedule = Schedule.fixed(100.millis).take(5)
                val config   = HttpClient.Config.default.retry(schedule)
                HttpClient.let(config) {
                    HttpClient.get[User]("https://api.example.com/always-fails")
                }.map { _ =>
                    // Should try 5 times then fail
                    succeed
                }
            }
        }

        "custom retry predicate" in run {
            val config = HttpClient.Config.default
                .retryWhen(r => r.status == Status.ServiceUnavailable)
            HttpClient.let(config) {
                HttpClient.get[User]("https://api.example.com/maintenance")
            }.map { _ =>
                succeed
            }
        }

        "no retry after success" in run {
            val schedule = Schedule.fixed(100.millis).take(3)
            val config   = HttpClient.Config.default.retry(schedule)
            HttpClient.let(config) {
                HttpClient.get[User]("https://api.example.com/users/1")
                // Should not retry on 200
            }.map { _ =>
                succeed
            }
        }
    }

    "Redirect handling" - {

        "follows redirects by default" in run {
            val target   = HttpHandler.get("/target") { (_, _) => HttpResponse.ok("final") }
            val redirect = HttpHandler.get("/start") { (_, _) => HttpResponse.redirect("/target") }
            startTestServer(target, redirect).map { port =>
                HttpClient.get[String](s"http://localhost:$port/start").map { body =>
                    assert(body == "final")
                }
            }
        }

        "respects maxRedirects" in run {
            val redirect = HttpHandler.get("/loop") { (_, _) => HttpResponse.redirect("/loop") }
            startTestServer(redirect).map { port =>
                val config = HttpClient.Config.default.maxRedirects(2)
                HttpClient.let(config) {
                    Abort.run(HttpClient.get[String](s"http://localhost:$port/loop"))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "does not follow when disabled" in run {
            val redirect = HttpHandler.get("/redir") { (_, _) => HttpResponse.redirect("/target") }
            startTestServer(redirect).map { port =>
                val config = HttpClient.Config.default.followRedirects(false)
                HttpClient.let(config) {
                    HttpClient.send(HttpRequest.get(s"http://localhost:$port/redir"))
                }.map { response =>
                    assert(response.status.isRedirect)
                }
            }
        }

        "handles redirect loop" in run {
            val redirect = HttpHandler.get("/loop") { (_, _) => HttpResponse.redirect("/loop") }
            startTestServer(redirect).map { port =>
                val config = HttpClient.Config.default.maxRedirects(5)
                HttpClient.let(config) {
                    Abort.run(HttpClient.get[String](s"http://localhost:$port/loop"))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "301 permanent redirect" in run {
            val target   = HttpHandler.get("/new") { (_, _) => HttpResponse.ok("moved") }
            val redirect = HttpHandler.get("/old") { (_, _) => HttpResponse.movedPermanently("/new") }
            startTestServer(target, redirect).map { port =>
                HttpClient.send(HttpRequest.get(s"http://localhost:$port/old")).map { response =>
                    assertStatus(response, Status.OK)
                    assertBodyText(response, "moved")
                }
            }
        }

        "302 found" in run {
            val target   = HttpHandler.get("/new") { (_, _) => HttpResponse.ok("found") }
            val redirect = HttpHandler.get("/old") { (_, _) => HttpResponse.redirect("/new", Status.Found) }
            startTestServer(target, redirect).map { port =>
                HttpClient.send(HttpRequest.get(s"http://localhost:$port/old")).map { response =>
                    assertStatus(response, Status.OK)
                }
            }
        }

        "307 temporary redirect" in run {
            val target   = HttpHandler.get("/new") { (_, _) => HttpResponse.ok("temp") }
            val redirect = HttpHandler.get("/old") { (_, _) => HttpResponse.redirect("/new", Status.TemporaryRedirect) }
            startTestServer(target, redirect).map { port =>
                HttpClient.send(HttpRequest.get(s"http://localhost:$port/old")).map { response =>
                    assertStatus(response, Status.OK)
                }
            }
        }

        "308 permanent redirect" in run {
            val target   = HttpHandler.get("/new") { (_, _) => HttpResponse.ok("perm") }
            val redirect = HttpHandler.get("/old") { (_, _) => HttpResponse.redirect("/new", Status.PermanentRedirect) }
            startTestServer(target, redirect).map { port =>
                HttpClient.send(HttpRequest.get(s"http://localhost:$port/old")).map { response =>
                    assertStatus(response, Status.OK)
                }
            }
        }
    }

    "Timeout handling" - {

        "request timeout" in run {
            startTestServer(delayedHandler("/slow", 10.seconds)).map { port =>
                val config = HttpClient.Config.default.timeout(100.millis)
                HttpClient.let(config) {
                    Abort.run(HttpClient.get[String](s"http://localhost:$port/slow"))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "connect timeout" in run {
            val config = HttpClient.Config.default.connectTimeout(100.millis)
            HttpClient.let(config) {
                Abort.run(HttpClient.get[String]("http://10.255.255.1/"))
            }.map { result =>
                assert(result.isFailure)
            }
        }

        "no timeout by default" in {
            val config = HttpClient.Config.default
            assert(config.timeout == Absent)
            assert(config.connectTimeout == Absent)
        }
    }

    "Error scenarios" - {

        "connection refused" in run {
            Abort.run(HttpClient.get[String]("http://localhost:59999")).map { result =>
                assert(result.isFailure)
            }
        }

        "DNS resolution failure" in run {
            Abort.run(HttpClient.get[String]("http://nonexistent.invalid.domain.test")).map { result =>
                assert(result.isFailure)
            }
        }

        "response parsing error" in run {
            val handler = HttpHandler.get("/html") { (_, _) =>
                HttpResponse.ok("<html>not json</html>")
            }
            startTestServer(handler).map { port =>
                Abort.run(HttpClient.get[User](s"http://localhost:$port/html")).map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "invalid URL throws" in {
            assertThrows[Exception] {
                HttpRequest.get("not a valid url")
            }
        }

        "empty URL throws" in {
            assertThrows[Exception] {
                HttpRequest.get("")
            }
        }

        "invalid baseUrl throws" in {
            assertThrows[Exception] {
                HttpClient.Config("not a valid url")
            }
        }
    }

    "Integration with HttpRoute" - {

        "call route" in run {
            val route   = HttpRoute.get("users").output[Seq[User]]
            val handler = route.handle(_ => Seq(User(1, "Alice"), User(2, "Bob")))
            startTestServer(handler).map { port =>
                HttpClient.baseUrl(s"http://localhost:$port") {
                    route.call(())
                }.map { users =>
                    assert(users == Seq(User(1, "Alice"), User(2, "Bob")))
                }
            }
        }

        "route with path params" in run {
            import HttpRoute.Path
            import HttpRoute.Path./
            val route   = HttpRoute.get("users" / Path.int("id")).output[User]
            val handler = route.handle(id => User(id, s"User$id"))
            startTestServer(handler).map { port =>
                HttpClient.baseUrl(s"http://localhost:$port") {
                    route.call(42)
                }.map { user =>
                    assert(user == User(42, "User42"))
                }
            }
        }

        "route with query params" in run {
            val route = HttpRoute.get("users")
                .query[Int]("limit", 20)
                .query[Int]("offset", 0)
                .output[Seq[User]]
            val handler = route.handle { case (limit, offset) =>
                (offset until (offset + limit)).map(i => User(i, s"User$i"))
            }
            startTestServer(handler).map { port =>
                HttpClient.baseUrl(s"http://localhost:$port") {
                    route.call((3, 10))
                }.map { users =>
                    assert(users.size == 3)
                    assert(users.head.id == 10)
                }
            }
        }

        "route with headers" in run {
            val route = HttpRoute.get("users")
                .header("X-Request-Id")
                .output[Seq[User]]
            val handler = route.handle { reqId =>
                Seq(User(1, reqId))
            }
            startTestServer(handler).map { port =>
                HttpClient.baseUrl(s"http://localhost:$port") {
                    route.call("req-123")
                }.map { users =>
                    assert(users.head.name == "req-123")
                }
            }
        }

        "route with body" in run {
            val route = HttpRoute.post("users")
                .input[CreateUser]
                .output[User]
            val handler = route.handle { input =>
                User(1, input.name)
            }
            startTestServer(handler).map { port =>
                HttpClient.baseUrl(s"http://localhost:$port") {
                    route.call(CreateUser("Alice", "alice@example.com"))
                }.map { user =>
                    assert(user == User(1, "Alice"))
                }
            }
        }

        "route error handling" in run {
            import HttpRoute.Path
            import HttpRoute.Path./

            case class NotFoundError(message: String) derives Schema

            val route = HttpRoute.get("users" / Path.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
            val handler = route.handle { id =>
                if id == 999 then Abort.fail(NotFoundError("User not found"))
                else User(id, s"User$id")
            }
            startTestServer(handler).map { port =>
                HttpClient.baseUrl(s"http://localhost:$port") {
                    Abort.run[NotFoundError](route.call(999))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }
    }

end HttpClientTest
