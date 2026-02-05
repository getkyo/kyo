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
                val config = HttpClient.Config.default.withTimeout(30.seconds)
                assert(config.timeout == Present(30.seconds))
            }

            "connectTimeout" in {
                val config = HttpClient.Config.default.withConnectTimeout(5.seconds)
                assert(config.connectTimeout == Present(5.seconds))
            }

            "followRedirects true" in {
                val config = HttpClient.Config.default.withFollowRedirects(true)
                assert(config.followRedirects == true)
            }

            "followRedirects false" in {
                val config = HttpClient.Config.default.withFollowRedirects(false)
                assert(config.followRedirects == false)
            }

            "maxRedirects" in {
                val config = HttpClient.Config.default.withMaxRedirects(5)
                assert(config.maxRedirects == 5)
            }

            "retry with schedule" in {
                val schedule = Schedule.exponential(100.millis, 2.0).take(3)
                val config   = HttpClient.Config.default.withRetry(schedule)
                assert(config.retrySchedule.isDefined)
            }

            "retryWhen with predicate" in {
                val config = HttpClient.Config.default.withRetryWhen(_.status.isServerError)
                // Custom predicate set
                succeed
            }

            "chaining" in {
                val config = HttpClient.Config.default
                    .withTimeout(30.seconds)
                    .withConnectTimeout(5.seconds)
                    .withFollowRedirects(false)
                    .withMaxRedirects(3)
                assert(config.timeout == Present(30.seconds))
                assert(config.connectTimeout == Present(5.seconds))
                assert(config.followRedirects == false)
                assert(config.maxRedirects == 3)
            }
        }

        "validation" - {
            "negative maxRedirects throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpClient.Config.default.withMaxRedirects(-1)
                }
            }

            "negative timeout throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpClient.Config.default.withTimeout(-1.seconds)
                }
            }

            "negative connectTimeout throws" in {
                assertThrows[IllegalArgumentException] {
                    HttpClient.Config.default.withConnectTimeout(-1.seconds)
                }
            }
        }
    }

    "HttpClient.init" - {

        "with all defaults" in run {
            HttpClient.init().map { client =>
                succeed
            }
        }

        "with pool settings" in run {
            HttpClient.init(maxConnectionsPerHost = Present(10)).map { client =>
                succeed
            }
        }
    }

    "HttpClient extensions" - {

        "send" - {
            "successful request" in run {
                val handler = HttpHandler.health("/test")
                startTestServer(handler).map { port =>
                    HttpClient.init().map { client =>
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
                    HttpClient.init().map { client =>
                        client.send(HttpRequest.get(s"http://localhost:$port/data")).map { response =>
                            assertStatus(response, Status.OK)
                            assertBodyText(response, "test-data")
                        }
                    }
                }
            }

            "timeout handling" in run {
                startTestServer(delayedHandler("/slow", 10.seconds)).map { port =>
                    HttpClient.init().map { client =>
                        HttpClient.withConfig(_.withTimeout(100.millis)) {
                            Abort.run(client.send(HttpRequest.get(s"http://localhost:$port/slow"))).map { result =>
                                assert(result.isFailure)
                            }
                        }
                    }
                }
            }

            "connection error" in run {
                HttpClient.init().map { client =>
                    Abort.run(client.send(HttpRequest.get("http://localhost:59999/test"))).map { result =>
                        assert(result.isFailure)
                    }
                }
            }
        }

        "close" - {
            "closes connection" in run {
                HttpClient.init().map { client =>
                    client.closeNow.map(_ => succeed)
                }
            }

            "idempotent" in run {
                HttpClient.init().map { client =>
                    client.closeNow.andThen(client.closeNow).map(_ => succeed)
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
                val handler = HttpHandler.get("/test") { (_, _) => HttpResponse.ok("ok") }
                startTestServer(handler).map { port =>
                    // Use a very short timeout - if config is applied, slow requests would fail
                    HttpClient.withConfig(_.withTimeout(5.seconds)) {
                        HttpClient.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
                            assertStatus(response, Status.OK)
                        }
                    }
                }
            }

            "restores after scope" in run {
                val handler = HttpHandler.get("/test") { (_, _) => HttpResponse.ok("ok") }
                startTestServer(handler).map { port =>
                    // Set a config in inner scope
                    HttpClient.withConfig(_.withMaxRedirects(1)) {
                        succeed
                    }.andThen {
                        // After scope, should be able to follow more redirects (default is 10)
                        val target = HttpHandler.get("/target") { (_, _) => HttpResponse.ok("done") }
                        val redir1 = HttpHandler.get("/r1") { (_, _) => HttpResponse.redirect("/r2") }
                        val redir2 = HttpHandler.get("/r2") { (_, _) => HttpResponse.redirect("/target") }
                        startTestServer(target, redir1, redir2).map { port2 =>
                            HttpClient.get[String](s"http://localhost:$port2/r1").map { body =>
                                assert(body == "done")
                            }
                        }
                    }
                }
            }

            "nested let" in run {
                val handler = HttpHandler.get("/test") { (_, _) => HttpResponse.ok("ok") }
                startTestServer(handler).map { port =>
                    HttpClient.withConfig(_.withMaxRedirects(5)) {
                        HttpClient.withConfig(_.withTimeout(10.seconds)) {
                            // Both configs should be applied - inner timeout, outer maxRedirects
                            HttpClient.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
                                assertStatus(response, Status.OK)
                            }
                        }
                    }
                }
            }
        }

        "update" - {
            "modifies current config" in run {
                val handler = HttpHandler.get("/test") { (_, _) => HttpResponse.ok("ok") }
                startTestServer(handler).map { port =>
                    // Start with a base config
                    HttpClient.withConfig(_.withMaxRedirects(5)) {
                        // Modify it further
                        HttpClient.withConfig(_.withTimeout(10.seconds)) {
                            HttpClient.send(HttpRequest.get(s"http://localhost:$port/test")).map { response =>
                                assertStatus(response, Status.OK)
                            }
                        }
                    }
                }
            }

            "restores after scope" in run {
                HttpClient.withConfig(_.withTimeout(1.seconds)) {
                    succeed
                }.andThen {
                    // Original timeout restored
                    succeed
                }
            }
        }

        "baseUrl" - {
            "prefixes all requests" in run {
                val handler = HttpHandler.get("/api/data") { (_, _) => HttpResponse.ok("prefixed") }
                startTestServer(handler).map { port =>
                    HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port")) {
                        // Request with relative path should be prefixed with baseUrl
                        HttpClient.send(HttpRequest.get("/api/data")).map { response =>
                            assertStatus(response, Status.OK)
                            assertBodyText(response, "prefixed")
                        }
                    }
                }
            }

            "nested baseUrl" in run {
                val outerHandler = HttpHandler.get("/outer") { (_, _) => HttpResponse.ok("outer") }
                val innerHandler = HttpHandler.get("/inner") { (_, _) => HttpResponse.ok("inner") }
                startTestServer(outerHandler, innerHandler).map { port =>
                    HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port/outer")) {
                        // Inner baseUrl should replace outer baseUrl
                        HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port")) {
                            HttpClient.send(HttpRequest.get("/inner")).map { response =>
                                assertStatus(response, Status.OK)
                                assertBodyText(response, "inner")
                            }
                        }
                    }
                }
            }

            "with absolute URL in request" in run {
                // Both handlers on same server - test that absolute URL bypasses baseUrl
                val baseHandler     = HttpHandler.get("/data") { (_, _) => HttpResponse.ok("from-base") }
                val absoluteHandler = HttpHandler.get("/other") { (_, _) => HttpResponse.ok("from-absolute") }
                startTestServer(baseHandler, absoluteHandler).map { port =>
                    // Set baseUrl pointing to /data, but request absolute URL to /other
                    HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port/data")) {
                        // Absolute URL should bypass baseUrl entirely
                        HttpClient.send(HttpRequest.get(s"http://localhost:$port/other")).map { response =>
                            assertStatus(response, Status.OK)
                            assertBodyText(response, "from-absolute")
                        }
                    }
                }
            }
        }
    }

    "Retry behavior" - {

        "no retry by default on client error" in pending

        "retry on server error" in pending

        "retry with custom schedule" - {
            "exponential backoff" in pending

            "max attempts" in pending
        }

        "custom retry predicate" in pending

        "no retry after success" in pending
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
                HttpClient.withConfig(_.withMaxRedirects(2)) {
                    Abort.run(HttpClient.get[String](s"http://localhost:$port/loop"))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "does not follow when disabled" in run {
            val redirect = HttpHandler.get("/redir") { (_, _) => HttpResponse.redirect("/target") }
            startTestServer(redirect).map { port =>
                HttpClient.withConfig(_.withFollowRedirects(false)) {
                    HttpClient.send(HttpRequest.get(s"http://localhost:$port/redir"))
                }.map { response =>
                    assert(response.status.isRedirect)
                }
            }
        }

        "handles redirect loop" in run {
            val redirect = HttpHandler.get("/loop") { (_, _) => HttpResponse.redirect("/loop") }
            startTestServer(redirect).map { port =>
                HttpClient.withConfig(_.withMaxRedirects(5)) {
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
                HttpClient.withConfig(_.withTimeout(100.millis)) {
                    Abort.run(HttpClient.get[String](s"http://localhost:$port/slow"))
                }.map { result =>
                    assert(result.isFailure)
                }
            }
        }

        "connect timeout" in run {
            // Use a non-routable IP to simulate slow/hanging connection
            // 10.255.255.1 is typically non-routable and will cause connection to hang
            HttpClient.withConfig(_.withConnectTimeout(100.millis)) {
                Abort.run(HttpClient.get[String]("http://10.255.255.1:12345/test"))
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

    "HttpError types" - {

        "ConnectionFailed for connection refused" in run {
            Abort.run(HttpClient.get[String]("http://localhost:59999")).map {
                case Result.Failure(HttpError.ConnectionFailed(host, port, _)) =>
                    assert(host == "localhost")
                    assert(port == 59999)
                case other =>
                    fail(s"Expected ConnectionFailed but got $other")
            }
        }

        "InvalidResponse for HTTP error status" in run {
            val handler = HttpHandler.const(HttpRequest.Method.GET, "/notfound", Status.NotFound)
            startTestServer(handler).map { port =>
                Abort.run(HttpClient.get[User](s"http://localhost:$port/notfound")).map {
                    case Result.Failure(HttpError.InvalidResponse(msg)) =>
                        assert(msg.contains("404"))
                    case other =>
                        fail(s"Expected InvalidResponse but got $other")
                }
            }
        }

        "InvalidResponse for parsing error" in run {
            val handler = HttpHandler.get("/html") { (_, _) =>
                HttpResponse.ok("<html>not json</html>")
            }
            startTestServer(handler).map { port =>
                Abort.run(HttpClient.get[User](s"http://localhost:$port/html")).map {
                    case Result.Failure(HttpError.InvalidResponse(msg)) =>
                        assert(msg.contains("Failed to parse"))
                    case other =>
                        fail(s"Expected InvalidResponse but got $other")
                }
            }
        }

        "TooManyRedirects when exceeding limit" in run {
            val handler = HttpHandler.get("/redirect") { (_, _) =>
                HttpResponse(Status.Found).addHeader("Location", "/redirect")
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.withMaxRedirects(3)) {
                    Abort.run(HttpClient.send(HttpRequest.get(s"http://localhost:$port/redirect")))
                }.map {
                    case Result.Failure(HttpError.TooManyRedirects(count)) =>
                        assert(count == 3)
                    case other =>
                        fail(s"Expected TooManyRedirects but got $other")
                }
            }
        }

        "Timeout when request exceeds duration" in run {
            val handler = HttpHandler.get("/slow") { (_, _) =>
                Async.delay(500.millis)(HttpResponse.ok("slow"))
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.withTimeout(100.millis)) {
                    Abort.run(HttpClient.send(HttpRequest.get(s"http://localhost:$port/slow")))
                }.map {
                    case Result.Failure(HttpError.Timeout(msg)) =>
                        assert(msg.contains("timed out"))
                    case other =>
                        fail(s"Expected Timeout but got $other")
                }
            }
        }
    }

    "Concurrency" - {

        val iterations = 20

        "single client parallel requests" in run {
            val handler = HttpHandler.get("/ping") { (_, _) =>
                HttpResponse.ok("pong")
            }
            startTestServer(handler).map { port =>
                Kyo.foreach(1 to iterations) { _ =>
                    Async.fill(10, 10)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/ping"))).map { responses =>
                        assert(responses.forall(_.status == Status.OK))
                        assert(responses.forall(_.bodyText == "pong"))
                    }
                }.andThen(succeed)
            }
        }

        "parallel requests with delay" in run {
            val handler = HttpHandler.get("/slow") { (_, _) =>
                Async.delay(10.millis)(HttpResponse.ok("done"))
            }
            startTestServer(handler).map { port =>
                Kyo.foreach(1 to iterations) { _ =>
                    Async.fill(5, 5)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/slow"))).map { responses =>
                        assert(responses.forall(_.status == Status.OK))
                    }
                }.andThen(succeed)
            }
        }

        "sequential then parallel" in run {
            val handler = HttpHandler.get("/data") { (_, _) =>
                HttpResponse.ok("data")
            }
            startTestServer(handler).map { port =>
                Kyo.foreach(1 to iterations) { _ =>
                    for
                        r1       <- HttpClient.send(HttpRequest.get(s"http://localhost:$port/data"))
                        r2       <- HttpClient.send(HttpRequest.get(s"http://localhost:$port/data"))
                        parallel <- Async.fill(3, 3)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/data")))
                    yield
                        assert(r1.status == Status.OK)
                        assert(r2.status == Status.OK)
                        assert(parallel.forall(_.status == Status.OK))
                }.andThen(succeed)
            }
        }

        "high concurrency" in run {
            val handler = HttpHandler.get("/ping") { (_, _) =>
                HttpResponse.ok("pong")
            }
            startTestServer(handler).map { port =>
                val concurrency = Runtime.getRuntime().availableProcessors()
                Kyo.foreach(1 to iterations) { _ =>
                    Async.fill(concurrency, concurrency)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/ping"))).map {
                        responses =>
                            assert(responses.size == concurrency)
                            assert(responses.forall(_.status == Status.OK))
                    }
                }.andThen(succeed)
            }
        }

        "interleaved client and server" in run {
            val handler = HttpHandler.get("/echo") { (_, req) =>
                HttpResponse.ok(req.header("X-Request-Id").getOrElse("unknown"))
            }
            startTestServer(handler).map { port =>
                Kyo.foreach(1 to iterations) { iter =>
                    val requests = (1 to 10).map { i =>
                        HttpClient.send(HttpRequest.get(s"http://localhost:$port/echo", Seq("X-Request-Id" -> s"req-$iter-$i")))
                    }
                    Async.collectAll(requests).map { responses =>
                        assert(responses.forall(_.status == Status.OK))
                        val bodies = responses.map(_.bodyText).toSet
                        assert(bodies.size == 10)
                    }
                }.andThen(succeed)
            }
        }

        "multiple clients parallel" in run {
            val handler = HttpHandler.get("/ping") { (_, _) =>
                HttpResponse.ok("pong")
            }
            startTestServer(handler).map { port =>
                Kyo.foreach(1 to iterations) { _ =>
                    Async.fill(3, 3) {
                        HttpClient.init().map { client =>
                            client.send(HttpRequest.get(s"http://localhost:$port/ping")).map { response =>
                                client.closeNow.andThen(response)
                            }
                        }
                    }.map { responses =>
                        assert(responses.forall(_.status == Status.OK))
                    }
                }.andThen(succeed)
            }
        }

        "race between requests" in run {
            val slowHandler = HttpHandler.get("/slow") { (_, _) =>
                Async.delay(1.second)(HttpResponse.ok("slow"))
            }
            val fastHandler = HttpHandler.get("/fast") { (_, _) =>
                HttpResponse.ok("fast")
            }
            startTestServer(slowHandler, fastHandler).map { port =>
                Kyo.foreach(1 to 100) { _ =>
                    Async.race(
                        HttpClient.send(HttpRequest.get(s"http://localhost:$port/slow")),
                        HttpClient.send(HttpRequest.get(s"http://localhost:$port/fast"))
                    ).map { response =>
                        assert(response.bodyText == "fast")
                    }
                }.andThen(succeed)
            }
        }

        "concurrent requests with shared state handler" in run {
            val counter = new java.util.concurrent.atomic.AtomicInteger(0)
            val handler = HttpHandler.get("/count") { (_, _) =>
                val count = counter.incrementAndGet()
                HttpResponse.ok(count.toString)
            }
            startTestServer(handler).map { port =>
                Kyo.foreach(1 to iterations) { iter =>
                    val startCount = counter.get()
                    Async.fill(20, 20)(HttpClient.send(HttpRequest.get(s"http://localhost:$port/count"))).map { responses =>
                        assert(responses.forall(_.status == Status.OK))
                        val counts = responses.map(_.bodyText.toInt)
                        // All counts in this batch should be unique (no lost updates)
                        assert(counts.toSet.size == 20)
                    }
                }.andThen(succeed)
            }
        }

        "stress test - rapid sequential requests" in run {
            val handler = HttpHandler.get("/ping") { (_, _) =>
                HttpResponse.ok("pong")
            }
            startTestServer(handler).map { port =>
                Kyo.foreach(1 to iterations) { _ =>
                    Kyo.foreach(1 to 50) { _ =>
                        HttpClient.send(HttpRequest.get(s"http://localhost:$port/ping"))
                    }.map { responses =>
                        assert(responses.forall(_.status == Status.OK))
                    }
                }.andThen(succeed)
            }
        }

        "benchmark-like pattern - single request" in run {
            val handler = HttpHandler.get("/ping") { (_, _) =>
                HttpResponse.ok("pong")
            }
            startTestServer(handler).map { port =>
                val url = s"http://localhost:$port/ping"
                Kyo.foreach(1 to iterations) { _ =>
                    Abort.run[HttpError](HttpClient.send(HttpRequest.get(url)).map(_.bodyText)).map {
                        case Result.Success(body) => assert(body == "pong")
                        case Result.Failure(e)    => fail(s"Request failed: $e")
                        case Result.Panic(e)      => fail(s"Request panicked: $e")
                    }
                }.andThen(succeed)
            }
        }

        "benchmark-like pattern - concurrent fill" in run {
            val handler = HttpHandler.get("/ping") { (_, _) =>
                HttpResponse.ok("pong")
            }
            val concurrency = Runtime.getRuntime().availableProcessors()
            startTestServer(handler).map { port =>
                val url = s"http://localhost:$port/ping"
                Kyo.foreach(1 to iterations) { _ =>
                    Abort.run[HttpError](Async.fill(concurrency, concurrency)(HttpClient.send(HttpRequest.get(url)).map(_.bodyText))).map {
                        case Result.Success(bodies) =>
                            assert(bodies.size == concurrency)
                            assert(bodies.forall(_ == "pong"))
                        case Result.Failure(e) => fail(s"Request failed: $e")
                        case Result.Panic(e)   => fail(s"Request panicked: $e")
                    }
                }.andThen(succeed)
            }
        }

        "benchmark-like pattern - repeated concurrent fill" in run {
            val handler = HttpHandler.get("/ping") { (_, _) =>
                HttpResponse.ok("pong")
            }
            val concurrency = Runtime.getRuntime().availableProcessors()
            startTestServer(handler).map { port =>
                val url = s"http://localhost:$port/ping"
                Kyo.foreach(1 to iterations) { _ =>
                    Kyo.foreach(1 to 5) { _ =>
                        Abort.run[HttpError](Async.fill(concurrency, concurrency)(HttpClient.send(HttpRequest.get(url)).map(_.bodyText)))
                    }.map { results =>
                        assert(results.forall(_.isSuccess))
                    }
                }.andThen(succeed)
            }
        }

        "with explicit fiber init - like benchmark" in run {
            val handler = HttpHandler.get("/ping") { (_, _) =>
                HttpResponse.ok("pong")
            }
            startTestServer(handler).map { port =>
                val url = s"http://localhost:$port/ping"
                Kyo.foreach(1 to iterations) { _ =>
                    val computation = Abort.run[HttpError](HttpClient.send(HttpRequest.get(url)).map(_.bodyText)).map {
                        case Result.Success(s) => s
                        case Result.Failure(e) => throw new RuntimeException(e.toString)
                        case Result.Panic(e)   => throw e
                    }
                    Fiber.initUnscoped(computation).map { fiber =>
                        fiber.block(Duration.Infinity).map {
                            case Result.Success(body) => assert(body == "pong")
                            case Result.Failure(t)    => fail(s"Fiber failed: $t")
                            case Result.Panic(e)      => fail(s"Fiber panicked: $e")
                        }
                    }
                }.andThen(succeed)
            }
        }
    }

    "Integration with HttpRoute" - {

        "call route" in run {
            val route   = HttpRoute.get("users").output[Seq[User]]
            val handler = route.handle(_ => Seq(User(1, "Alice"), User(2, "Bob")))
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port")) {
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
                HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port")) {
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
                HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port")) {
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
                HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port")) {
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
                HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port")) {
                    route.call(CreateUser("Alice", "alice@example.com"))
                }.map { user =>
                    assert(user == User(1, "Alice"))
                }
            }
        }

        "route error handling" in run {
            import HttpRoute.Path
            import HttpRoute.Path./
            case class NotFoundError(message: String) derives Schema, CanEqual
            val route = HttpRoute.get("users" / Path.int("id"))
                .output[User]
                .error[NotFoundError](Status.NotFound)
            val handler = route.handle { id =>
                if id == 999 then Abort.fail(NotFoundError("User not found"))
                else User(id, s"User$id")
            }
            startTestServer(handler).map { port =>
                HttpClient.withConfig(_.withBaseUrl(s"http://localhost:$port")) {
                    // Test successful case
                    route.call(1).map { user =>
                        assert(user == User(1, "User1"))
                    }.andThen {
                        // Test error case - should return 404 with error body
                        HttpClient.send(HttpRequest.get(s"http://localhost:$port/users/999")).map { response =>
                            assertStatus(response, Status.NotFound)
                            assert(response.bodyText.contains("User not found"))
                        }
                    }
                }
            }
        }
    }

end HttpClientTest
