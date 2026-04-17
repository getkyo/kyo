package kyo

import kyo.*

class HttpClientUnixTest extends Test with internal.UnixSocketTestHelperImpl:

    given CanEqual[Any, Any] = CanEqual.derived

    case class UserInput(name: String) derives Json, CanEqual
    case class UserOutput(id: Int, name: String) derives Json, CanEqual

    private def withUnixServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: (HttpServer, String) => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        tempSocketPath().map { sockPath =>
            val config = HttpServerConfig.default.unixSocket(sockPath)
            Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                HttpServer.init(config)(handlers*).map { server =>
                    test(server, sockPath)
                }
            }
        }

    // ── HttpUrl parsing (unit tests, no server needed) ──────────────────────

    "HttpUrl parsing" - {

        "http+unix scheme normalizes to http" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.scheme == Present("http"))
        }

        "https+unix scheme normalizes to https" in {
            val url = HttpUrl.parse("https+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.scheme == Present("https"))
        }

        "http+unix url.ssl returns false" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(!url.ssl)
        }

        "https+unix url.ssl returns true" in {
            val url = HttpUrl.parse("https+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.ssl)
        }

        "socket path URL-decoded correctly" in {
            val url = HttpUrl.parse("http+unix://%2Fvar%2Frun%2Fdocker.sock/v1/info").getOrThrow
            assert(url.unixSocket == Present("/var/run/docker.sock"))
        }

        "host defaults to localhost" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.host == "localhost")
        }

        "port defaults to 80 for http, 443 for https" in {
            val httpUrl = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(httpUrl.port == 80)

            val httpsUrl = HttpUrl.parse("https+unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(httpsUrl.port == 443)
        }

        "path extracted correctly after authority" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/v1/containers/json").getOrThrow
            assert(url.path == "/v1/containers/json")
        }

        "query parameters preserved" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/v1/info?key=val&a=b").getOrThrow
            assert(url.rawQuery == Present("key=val&a=b"))
            assert(url.query("key") == Present("val"))
            assert(url.query("a") == Present("b"))
        }

        "full reconstructs the original http+unix URL" in {
            val url  = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/v1/info?key=val").getOrThrow
            val full = url.full
            assert(full.startsWith("http+unix://"))
            assert(full.contains("key=val"))
            // Parse the reconstructed URL and verify round-trip
            val reparsed = HttpUrl.parse(full).getOrThrow
            assert(reparsed.scheme == url.scheme)
            assert(reparsed.host == url.host)
            assert(reparsed.port == url.port)
            assert(reparsed.path == url.path)
            assert(reparsed.rawQuery == url.rawQuery)
            assert(reparsed.unixSocket == url.unixSocket)
        }

        "baseUrl reconstructs without query" in {
            val url  = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/v1/info?key=val").getOrThrow
            val base = url.baseUrl
            assert(base.startsWith("http+unix://"))
            assert(!base.contains("key=val"))
            assert(base.contains("/v1/info"))
        }

        "regular http URLs still work with unixSocket Absent" in {
            val url = HttpUrl.parse("http://example.com/path").getOrThrow
            assert(url.unixSocket == Absent)
            assert(url.host == "example.com")
            assert(url.port == 80)
            assert(url.scheme == Present("http"))
        }

        "invalid scheme rejected" in {
            val result = HttpUrl.parse("")
            assert(result.isFailure)
        }

        "no path after socket defaults to /" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock").getOrThrow
            assert(url.unixSocket == Present("/tmp/test.sock"))
            assert(url.path == "/")
        }

        "root path only" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/").getOrThrow
            assert(url.unixSocket == Present("/tmp/test.sock"))
            assert(url.path == "/")
        }

        "socket path with special chars" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Fmy-app.v2.sock/path").getOrThrow
            assert(url.unixSocket == Present("/tmp/my-app.v2.sock"))
        }

        "socket path with spaces" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Fmy%20app.sock/path").getOrThrow
            assert(url.unixSocket == Present("/tmp/my app.sock"))
        }

        "double-encoded slashes stay encoded" in {
            val url = HttpUrl.parse("http+unix://%252Ftmp%252Ftest.sock/path").getOrThrow
            assert(url.unixSocket == Present("%2Ftmp%2Ftest.sock"))
        }

        "empty socket path" in {
            val url = HttpUrl.parse("http+unix:///path").getOrThrow
            assert(url.unixSocket == Present(""))
            assert(url.path == "/path")
        }

        "fragment is stripped from path" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path#frag").getOrThrow
            assert(url.path == "/path")
            assert(url.unixSocket == Present("/tmp/test.sock"))
        }

        "fragment after query" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock/path?k=v#frag").getOrThrow
            assert(url.path == "/path")
            assert(url.rawQuery == Present("k=v"))
        }

        "mixed case scheme" in {
            val url = HttpUrl.parse("HTTP+UNIX://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.scheme == Present("http"))
            assert(url.unixSocket == Present("/tmp/test.sock"))
        }

        "Http+Unix mixed case" in {
            val url = HttpUrl.parse("Http+Unix://%2Ftmp%2Ftest.sock/path").getOrThrow
            assert(url.scheme == Present("http"))
            assert(url.unixSocket == Present("/tmp/test.sock"))
        }

        "query without path" in {
            val url = HttpUrl.parse("http+unix://%2Ftmp%2Ftest.sock?key=val").getOrThrow
            assert(url.path == "/")
            assert(url.rawQuery == Present("key=val"))
            assert(url.unixSocket == Present("/tmp/test.sock"))
        }

        "deeply nested socket path" in {
            val url = HttpUrl.parse("http+unix://%2Fvar%2Frun%2Fapp%2Fsubdir%2Fmy.sock/api/v2").getOrThrow
            assert(url.unixSocket == Present("/var/run/app/subdir/my.sock"))
            assert(url.path == "/api/v2")
        }

        "round-trip preserves all fields" in {
            val original = "http+unix://%2Ftmp%2Ftest.sock/api?a=1&b=2"
            val url      = HttpUrl.parse(original).getOrThrow
            val reparsed = HttpUrl.parse(url.full).getOrThrow
            assert(reparsed.scheme == url.scheme)
            assert(reparsed.unixSocket == url.unixSocket)
            assert(reparsed.path == url.path)
            assert(reparsed.rawQuery == url.rawQuery)
            assert(reparsed.host == url.host)
            assert(reparsed.port == url.port)
        }
    }

    // ── HTTP Methods ──────────────────────────────────────────────────────────

    "HTTP methods" - {

        "GET returns text body" in run {
            val route   = HttpRoute.getRaw("hello").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("hello world"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/hello")
                HttpClient.getText(url).map { text =>
                    assert(text == "hello world")
                }
            }
        }

        "POST echoes request body" in run {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler(req => HttpResponse.ok(req.fields.body))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/echo")
                HttpClient.postText(url, "ping").map { text =>
                    assert(text == "ping")
                }
            }
        }

        "PUT updates resource" in run {
            val route   = HttpRoute.putRaw("data").request(_.bodyText).response(_.bodyText)
            val handler = route.handler(req => HttpResponse.ok(s"updated: ${req.fields.body}"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/data")
                HttpClient.putText(url, "new-value").map { text =>
                    assert(text == "updated: new-value")
                }
            }
        }

        "DELETE returns confirmation" in run {
            val route   = HttpRoute.deleteRaw("item").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("deleted"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/item")
                HttpClient.deleteText(url).map { text =>
                    assert(text == "deleted")
                }
            }
        }

        "PATCH returns partial update" in run {
            val route   = HttpRoute.patchRaw("item").request(_.bodyText).response(_.bodyText)
            val handler = route.handler(req => HttpResponse.ok(s"patched: ${req.fields.body}"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/item")
                HttpClient.patchText(url, "patch-data").map { text =>
                    assert(text == "patched: patch-data")
                }
            }
        }

        "HEAD returns headers without body" in run {
            val route       = HttpRoute.getRaw("hello").response(_.bodyText)
            val handler     = route.handler(_ => HttpResponse.ok("hello world"))
            val headRoute   = HttpRoute.headRaw("hello").response(_.bodyText)
            val headHandler = headRoute.handler(_ => HttpResponse.ok(""))
            withUnixServer(handler) { (server, sockPath) =>
                val url    = mkUrl(sockPath, "/hello")
                val client = internal.HttpTestPlatformBackend.client
                HttpClient.init().map { httpClient =>
                    HttpClient.let(httpClient) {
                        val parsedUrl = HttpUrl.parse(url).getOrThrow
                        client.connectWith(parsedUrl, 30.seconds, HttpTlsConfig(trustAll = true)) { conn =>
                            Scope.run {
                                Scope.ensure(client.closeNow(conn)).andThen {
                                    val rawRoute = HttpRoute.getRaw("").response(_.bodyText)
                                    client.sendWith(conn, rawRoute, HttpRequest(HttpMethod.HEAD, parsedUrl))(identity)
                                }
                            }
                        }.map { resp =>
                            assert(resp.status == HttpStatus.OK)
                        }
                    }
                }
            }
        }

        "OPTIONS returns allowed methods" in run {
            val getRoute    = HttpRoute.getRaw("multi").response(_.bodyText)
            val postRoute   = HttpRoute.postRaw("multi").request(_.bodyText).response(_.bodyText)
            val getHandler  = getRoute.handler(_ => HttpResponse.ok("get"))
            val postHandler = postRoute.handler(req => HttpResponse.ok(req.fields.body))
            withUnixServer(getHandler, postHandler) { (server, sockPath) =>
                val url    = mkUrl(sockPath, "/multi")
                val client = internal.HttpTestPlatformBackend.client
                HttpClient.init().map { httpClient =>
                    HttpClient.let(httpClient) {
                        val parsedUrl = HttpUrl.parse(url).getOrThrow
                        val rawRoute  = HttpRoute.getRaw("").response(_.bodyText)
                        client.connectWith(parsedUrl, 30.seconds, HttpTlsConfig(trustAll = true)) { conn =>
                            Scope.run {
                                Scope.ensure(client.closeNow(conn)).andThen {
                                    client.sendWith(conn, rawRoute, HttpRequest(HttpMethod.OPTIONS, parsedUrl))(identity)
                                }
                            }
                        }.map { resp =>
                            val allow = resp.headers.get("Allow")
                            assert(allow.isDefined)
                            assert(allow.get.contains("GET"))
                            assert(allow.get.contains("POST"))
                        }
                    }
                }
            }
        }
    }

    // ── JSON Bodies ──────────────────────────────────────────────────────────

    "JSON bodies" - {

        "POST JSON and receive JSON" in run {
            val route = HttpRoute.postRaw("users")
                .request(_.bodyJson[UserInput])
                .response(_.bodyJson[UserOutput])
            val handler = route.handler { req =>
                HttpResponse.ok.addField("body", UserOutput(1, req.fields.body.name))
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/users")
                HttpClient.postJson[UserOutput](url, UserInput("test")).map { user =>
                    assert(user == UserOutput(1, "test"))
                }
            }
        }

        "GET JSON response" in run {
            val route = HttpRoute.getRaw("user").response(_.bodyJson[UserOutput])
            val handler = route.handler { _ =>
                HttpResponse.ok.addField("body", UserOutput(1, "alice"))
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/user")
                HttpClient.getJson[UserOutput](url).map { user =>
                    assert(user == UserOutput(1, "alice"))
                }
            }
        }

        "PUT JSON body" in run {
            val route = HttpRoute.putRaw("user")
                .request(_.bodyJson[UserInput])
                .response(_.bodyJson[UserOutput])
            val handler = route.handler { req =>
                HttpResponse.ok.addField("body", UserOutput(1, req.fields.body.name))
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/user")
                HttpClient.putJson[UserOutput](url, UserInput("updated")).map { user =>
                    assert(user == UserOutput(1, "updated"))
                }
            }
        }
    }

    // ── Headers and Query Params ─────────────────────────────────────────────

    "headers and query params" - {

        "custom request headers round-trip" in run {
            val route = HttpRoute.getRaw("echo-header")
                .request(_.header[String]("X-Custom"))
                .response(_.header[String]("X-Echo").bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok
                    .addField("X-Echo", req.fields.`X-Custom`)
                    .addField("body", "ok")
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url    = mkUrl(sockPath, "/echo-header")
                val client = internal.HttpTestPlatformBackend.client
                HttpClient.init().map { httpClient =>
                    HttpClient.let(httpClient) {
                        val parsedUrl = HttpUrl.parse(url).getOrThrow
                        client.connectWith(parsedUrl, 30.seconds, HttpTlsConfig(trustAll = true)) { conn =>
                            Scope.run {
                                Scope.ensure(client.closeNow(conn)).andThen {
                                    client.sendWith(
                                        conn,
                                        route,
                                        HttpRequest.getRaw(parsedUrl)
                                            .addField("X-Custom", "value")
                                    )(identity)
                                }
                            }
                        }.map { resp =>
                            assert(resp.fields.`X-Echo` == "value")
                        }
                    }
                }
            }
        }

        "query parameters in URL" in run {
            val route = HttpRoute.getRaw("search")
                .request(_.query[String]("q"))
                .response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(s"result: ${req.fields.q}")
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/search?q=hello")
                HttpClient.getText(url).map { text =>
                    assert(text == "result: hello")
                }
            }
        }

        "path captures" in run {
            val route = HttpRoute.getRaw("users" / Capture[Int]("id")).response(_.bodyText)
            val handler = route.handler { req =>
                HttpResponse.ok(s"user ${req.fields.id}")
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/users/42")
                HttpClient.getText(url).map { text =>
                    assert(text == "user 42")
                }
            }
        }
    }

    // ── Large Bodies ─────────────────────────────────────────────────────────

    "large bodies" - {

        "large text body (100KB)" in run {
            val route = HttpRoute.postRaw("big")
                .request(_.bodyText)
                .response(_.bodyText)
            val handler = route.handler(req => HttpResponse.ok(req.fields.body))
            val config  = HttpServerConfig.default.maxContentLength(200000)
            tempSocketPath().map { sockPath =>
                val fullConfig = config.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(fullConfig)(handler).map { server =>
                        val url      = mkUrl(sockPath, "/big")
                        val largeStr = "x" * 100000
                        HttpClient.postText(url, largeStr).map { text =>
                            assert(text.length == 100000)
                            assert(text == largeStr)
                        }
                    }
                }
            }
        }

        "binary body round-trip" in run {
            val route = HttpRoute.postRaw("binary")
                .request(_.bodyBinary)
                .response(_.bodyBinary)
            val handler = route.handler(req => HttpResponse.ok(req.fields.body))
            withUnixServer(handler) { (server, sockPath) =>
                val url   = mkUrl(sockPath, "/binary")
                val bytes = Span.fromUnsafe(Array.tabulate[Byte](256)(i => i.toByte))
                HttpClient.postBinary(url, bytes).map { result =>
                    assert(result.size == 256)
                    assert(result.toArrayUnsafe.toSeq == bytes.toArrayUnsafe.toSeq)
                }
            }
        }
    }

    // ── Error Responses ──────────────────────────────────────────────────────

    "error responses" - {

        "404 for unknown path" in run {
            val route   = HttpRoute.getRaw("exists").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("found"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/nonexistent")
                Abort.run[HttpException] {
                    HttpClient.getJson[UserOutput](url)
                }.map { result =>
                    assert(result.isFailure)
                    result.failure match
                        case Present(e: HttpStatusException) =>
                            assert(e.status == HttpStatus.NotFound)
                        case other =>
                            fail(s"Expected HttpStatusException but got $other")
                    end match
                }
            }
        }

        "500 for handler error" in run {
            val route = HttpRoute.getRaw("boom").response(_.bodyText)
            val handler = route.handler { _ =>
                throw new RuntimeException("boom")
                HttpResponse.ok("unreachable")
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/boom")
                Abort.run[HttpException] {
                    HttpClient.getJson[UserOutput](url)
                }.map { result =>
                    assert(result.isFailure)
                    result.failure match
                        case Present(e: HttpStatusException) =>
                            assert(e.status == HttpStatus.InternalServerError)
                        case other =>
                            fail(s"Expected HttpStatusException(500) but got $other")
                    end match
                }
            }
        }

        "handler returns custom error status" in run {
            val route = HttpRoute.getRaw("bad").response(_.bodyText)
            val handler = route.handler { _ =>
                HttpResponse.halt(HttpResponse(HttpStatus.BadRequest))
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/bad")
                Abort.run[HttpException] {
                    HttpClient.getJson[UserOutput](url)
                }.map { result =>
                    assert(result.isFailure)
                    result.failure match
                        case Present(e: HttpStatusException) =>
                            assert(e.status == HttpStatus.BadRequest)
                        case other =>
                            fail(s"Expected HttpStatusException(400) but got $other")
                    end match
                }
            }
        }
    }

    // ── Keep-alive / Multiple Requests ───────────────────────────────────────

    "keep-alive and multiple requests" - {

        "multiple sequential requests on same socket" in run {
            val route   = HttpRoute.getRaw("counter").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("data"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/counter")
                HttpClient.getText(url).map { r1 =>
                    assert(r1 == "data")
                }.andThen {
                    HttpClient.getText(url).map { r2 =>
                        assert(r2 == "data")
                    }
                }.andThen {
                    HttpClient.getText(url).map { r3 =>
                        assert(r3 == "data")
                    }
                }.andThen {
                    HttpClient.getText(url).map { r4 =>
                        assert(r4 == "data")
                    }
                }.andThen {
                    HttpClient.getText(url).map { r5 =>
                        assert(r5 == "data")
                    }
                }
            }
        }

        "interleaved GET and POST" in run {
            val getRoute    = HttpRoute.getRaw("resource").response(_.bodyText)
            val postRoute   = HttpRoute.postRaw("resource").request(_.bodyText).response(_.bodyText)
            val getHandler  = getRoute.handler(_ => HttpResponse.ok("got"))
            val postHandler = postRoute.handler(req => HttpResponse.ok(s"posted: ${req.fields.body}"))
            withUnixServer(getHandler, postHandler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/resource")
                HttpClient.getText(url).map { r1 =>
                    assert(r1 == "got")
                }.andThen {
                    HttpClient.postText(url, "a").map { r2 =>
                        assert(r2 == "posted: a")
                    }
                }.andThen {
                    HttpClient.getText(url).map { r3 =>
                        assert(r3 == "got")
                    }
                }.andThen {
                    HttpClient.postText(url, "b").map { r4 =>
                        assert(r4 == "posted: b")
                    }
                }
            }
        }
    }

    // ── Concurrent Requests ──────────────────────────────────────────────────

    "concurrent requests" - {

        "10 concurrent GET requests" in run {
            val route   = HttpRoute.getRaw("data").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("hello"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/data")
                Kyo.foreach(1 to 10) { _ =>
                    HttpClient.getText(url)
                }.map { results =>
                    assert(results.size == 10)
                    results.foreach { text =>
                        assert(text == "hello")
                    }
                    succeed
                }
            }
        }

        "10 concurrent POST requests" in run {
            val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
            val handler = route.handler(req => HttpResponse.ok(req.fields.body))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/echo")
                Kyo.foreach(1 to 10) { i =>
                    HttpClient.postText(url, s"body-$i")
                }.map { results =>
                    assert(results.size == 10)
                    results.zipWithIndex.foreach { case (text, idx) =>
                        assert(text == s"body-${idx + 1}")
                    }
                    succeed
                }
            }
        }

        "mixed concurrent methods" in run {
            val getRoute    = HttpRoute.getRaw("resource").response(_.bodyText)
            val postRoute   = HttpRoute.postRaw("resource").request(_.bodyText).response(_.bodyText)
            val getHandler  = getRoute.handler(_ => HttpResponse.ok("got"))
            val postHandler = postRoute.handler(req => HttpResponse.ok(s"posted: ${req.fields.body}"))
            withUnixServer(getHandler, postHandler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/resource")
                Kyo.foreach(1 to 10) { i =>
                    if i <= 5 then
                        HttpClient.getText(url).map(text => (i, text))
                    else
                        HttpClient.postText(url, s"msg-$i").map(text => (i, text))
                }.map { results =>
                    assert(results.size == 10)
                    results.foreach { case (i, text) =>
                        if i <= 5 then assert(text == "got")
                        else assert(text == s"posted: msg-$i")
                    }
                    succeed
                }
            }
        }
    }

    // ── Connection Reuse ─────────────────────────────────────────────────────

    "connection reuse" - {

        "sequential requests reuse connection" in run {
            val route   = HttpRoute.getRaw("ping").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("pong"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/ping")
                Kyo.foreach(1 to 10) { _ =>
                    HttpClient.getText(url)
                }.map { results =>
                    assert(results.size == 10)
                    results.foreach { text =>
                        assert(text == "pong")
                    }
                    succeed
                }
            }
        }

        "rapid sequential requests" in run {
            val route   = HttpRoute.getRaw("fast").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("ok"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/fast")
                Kyo.foreach(1 to 50) { i =>
                    HttpClient.getText(url)
                }.map { results =>
                    assert(results.size == 50)
                    results.foreach { text =>
                        assert(text == "ok")
                    }
                    succeed
                }
            }
        }
    }

    // ── Client Lifecycle ─────────────────────────────────────────────────────

    "client lifecycle" - {

        "HttpClient.init with Unix socket" in run {
            val route   = HttpRoute.getRaw("pooled").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("pooled-ok"))
            withUnixServer(handler) { (server, sockPath) =>
                HttpClient.init(maxConnectionsPerHost = 4).map { httpClient =>
                    HttpClient.let(httpClient) {
                        val url = mkUrl(sockPath, "/pooled")
                        Kyo.foreach(1 to 8) { _ =>
                            HttpClient.getText(url)
                        }.map { results =>
                            assert(results.size == 8)
                            results.foreach { text =>
                                assert(text == "pooled-ok")
                            }
                            succeed
                        }
                    }
                }
            }
        }

        "multiple clients same server" in run {
            val route   = HttpRoute.getRaw("shared").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("shared-ok"))
            withUnixServer(handler) { (server, sockPath) =>
                val backend = internal.HttpTestPlatformBackend.client
                HttpClient.init(maxConnectionsPerHost = 2).map { client1 =>
                    HttpClient.init(maxConnectionsPerHost = 2).map { client2 =>
                        val url = mkUrl(sockPath, "/shared")
                        val fiber1 = Fiber.initUnscoped(
                            HttpClient.let(client1) {
                                Kyo.foreach(1 to 5) { _ =>
                                    HttpClient.getText(url)
                                }
                            }
                        )
                        val fiber2 = Fiber.initUnscoped(
                            HttpClient.let(client2) {
                                Kyo.foreach(1 to 5) { _ =>
                                    HttpClient.getText(url)
                                }
                            }
                        )
                        fiber1.map { f1 =>
                            fiber2.map { f2 =>
                                f1.get.map { results1 =>
                                    f2.get.map { results2 =>
                                        assert(results1.size == 5)
                                        assert(results2.size == 5)
                                        results1.foreach(text => assert(text == "shared-ok"))
                                        results2.foreach(text => assert(text == "shared-ok"))
                                        succeed
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Error Recovery ───────────────────────────────────────────────────────

    "error recovery" - {

        "server handles client disconnect" in run {
            case class Payload(value: String) derives Json
            val route   = HttpRoute.getRaw("resilient").response(_.bodyText)
            val handler = route.handler(_ => HttpResponse.ok("alive"))
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/resilient")
                // First request: succeeds normally
                HttpClient.getText(url).map { text1 =>
                    assert(text1 == "alive")
                }.andThen {
                    // Second request: hit a 404, which triggers a status exception
                    // when the client tries to decode JSON from the error body
                    val badUrl = mkUrl(sockPath, "/nonexistent")
                    Abort.run[HttpException] {
                        HttpClient.getJson[Payload](badUrl)
                    }.map { result =>
                        assert(result.isFailure)
                        result.failure match
                            case Present(_: HttpStatusException) => ()
                            case other                           => fail(s"Expected HttpStatusException but got $other")
                        end match
                    }
                }.andThen {
                    // Third request: server should still serve correctly after the error
                    HttpClient.getText(url).map { text2 =>
                        assert(text2 == "alive")
                    }
                }
            }
        }
    }

end HttpClientUnixTest
