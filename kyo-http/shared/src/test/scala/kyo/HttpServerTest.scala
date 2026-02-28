package kyo

import kyo.*
import kyo.Record2.~
import kyo.millis
import kyo.seconds
import scala.language.implicitConversions

class HttpServerTest extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class LoginForm(username: String, password: String) derives HttpFormCodec, CanEqual

    val client = kyo.internal.HttpPlatformBackend.client

    def withServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: Int => A < (S & Async & Abort[HttpError])
    )(using Frame): A < (S & Async & Scope & Abort[HttpError]) =
        HttpServer.init(0, "localhost")(handlers*).map(server => test(server.port))

    def withCorsServer[A, S](cors: CorsConfig, handlers: HttpHandler[?, ?, ?]*)(
        test: Int => A < (S & Async & Abort[HttpError])
    )(using Frame): A < (S & Async & Scope & Abort[HttpError]) =
        HttpServer.init(HttpServer.Config(port = 0, host = "localhost", cors = Present(cors)))(handlers*).map(server => test(server.port))

    def send[In, Out](
        port: Int,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
        client.connectWith("localhost", port, ssl = false, Absent) { conn =>
            Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                client.sendWith(conn, route, request)(identity)
            }
        }

    // Raw HTTP request helper - sends a request without route-based encoding
    // Uses a simple text route for decoding the raw response
    val rawRoute = HttpRoute.getRaw("raw").response(_.bodyText)

    def sendRaw(
        port: Int,
        method: HttpMethod,
        path: String
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpError]) =
        send(port, rawRoute, HttpRequest(method, HttpUrl.fromUri(path)))

    def sendGet[Out](port: Int, route: HttpRoute[Any, Out, ?], path: String)(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
        Abort.get(HttpRequest.getRaw(path)).map(req => send(port, route, req))

    def sendPost[Out](port: Int, route: HttpRoute[Any, Out, ?], path: String)(using Frame): HttpResponse[Out] < (Async & Abort[HttpError]) =
        Abort.get(HttpRequest.postRaw(path)).map(req => send(port, route, req))

    "routing" - {

        "404 for unknown path" in run {
            val route = HttpRoute.getRaw("exists").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("found"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/not-exists"))).map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                }
            }
        }

        "405 with Allow header" in run {
            val getRoute    = HttpRoute.getRaw("resource").response(_.bodyText)
            val postRoute   = HttpRoute.postRaw("resource").response(_.bodyText)
            val deleteRoute = HttpRoute.deleteRaw("resource").response(_.bodyText)
            val getEp       = getRoute.handler(_ => HttpResponse.okText("get"))
            val postEp      = postRoute.handler(_ => HttpResponse.okText("post"))
            withServer(getEp, postEp) { port =>
                send(port, deleteRoute, HttpRequest.deleteRaw(HttpUrl.fromUri("/resource"))).map { resp =>
                    assert(resp.status == HttpStatus.MethodNotAllowed)
                    val allow = resp.headers.get("Allow")
                    assert(allow.isDefined)
                    val allowValue = allow.get
                    assert(allowValue.contains("GET"))
                    assert(allowValue.contains("POST"))
                }
            }
        }

        "HEAD implicit fallback to GET" in run {
            val getRoute = HttpRoute.getRaw("hello").response(_.bodyText)
            val ep       = getRoute.handler(_ => HttpResponse.okText("world"))
            withServer(ep) { port =>
                // HEAD should match the GET handler
                send(port, getRoute, HttpRequest.headRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                }
            }
        }

        "explicit HEAD handler takes priority over GET fallback" in run {
            val getRoute  = HttpRoute.getRaw("hello").response(_.header[String]("X-Handler"))
            val headRoute = HttpRoute.headRaw("hello").response(_.header[String]("X-Handler"))
            val getEp     = getRoute.handler(_ => HttpResponse.ok.addField("X-Handler", "get"))
            val headEp    = headRoute.handler(_ => HttpResponse.ok.addField("X-Handler", "head"))
            withServer(getEp, headEp) { port =>
                send(port, headRoute, HttpRequest.headRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.`X-Handler` == "head")
                }
            }
        }

        "multiple methods on same path" in run {
            val getRoute    = HttpRoute.getRaw("item").response(_.bodyText)
            val postRoute   = HttpRoute.postRaw("item").request(_.bodyText).response(_.bodyText)
            val deleteRoute = HttpRoute.deleteRaw("item").response(_.bodyText)
            val getEp       = getRoute.handler(_ => HttpResponse.okText("got"))
            val postEp      = postRoute.handler(req => HttpResponse.okText(s"posted: ${req.fields.body}"))
            val deleteEp    = deleteRoute.handler(_ => HttpResponse.okText("deleted"))
            withServer(getEp, postEp, deleteEp) { port =>
                for
                    r1 <- send(port, getRoute, HttpRequest.getRaw(HttpUrl.fromUri("/item")))
                    r2 <- send(port, postRoute, HttpRequest.postRaw(HttpUrl.fromUri("/item")).addField("body", "data"))
                    r3 <- send(port, deleteRoute, HttpRequest.deleteRaw(HttpUrl.fromUri("/item")))
                yield
                    assert(r1.fields.body == "got")
                    assert(r2.fields.body == "posted: data")
                    assert(r3.fields.body == "deleted")
            }
        }

        "root path" in run {
            val route = HttpRoute.getRaw("").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("root"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "root")
                }
            }
        }

        "trailing slash normalized" in run {
            val route = HttpRoute.getRaw("users").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("list"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/users/"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "list")
                }
            }
        }

        "URL-encoded path segments" in run {
            val route = HttpRoute.getRaw("items" / Capture[String]("name")).response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(req.fields.name)
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/items/hello%20world"))
                        .addField("name", "hello world")
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "hello world")
                }
            }
        }

        "rest capture" in run {
            val route = HttpRoute.getRaw("files" / Rest("path")).response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(req.fields.path)
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/files/a/b/c"))
                        .addField("path", "a/b/c")
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "a/b/c")
                }
            }
        }

        "empty router returns 404" in run {
            withServer() { port =>
                val route = HttpRoute.getRaw("anything").response(_.bodyText)
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/anything"))).map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                }
            }
        }

        "deeply nested path" in run {
            val route = HttpRoute.getRaw("a" / "b" / "c" / "d" / "e").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("deep"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/a/b/c/d/e"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "deep")
                }
            }
        }

        "OPTIONS returns 204 with Allow header" in run {
            val route = HttpRoute.getRaw("no-cors").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("hello"))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.OPTIONS, "/no-cors").map { resp =>
                    assert(
                        resp.status == HttpStatus.NoContent,
                        s"OPTIONS should return 204, got ${resp.status}"
                    )
                    val allow = resp.headers.get("Allow")
                    assert(allow.isDefined, "OPTIONS should include Allow header")
                    assert(allow.get.contains("GET"), "Allow should include GET")
                    assert(allow.get.contains("HEAD"), "Allow should include HEAD")
                    assert(allow.get.contains("OPTIONS"), "Allow should include OPTIONS")
                }
            }
        }

        "OPTIONS on SSE endpoint does not open stream" in run {
            val ep = HttpHandler.getSseText("sse-opts") { _ =>
                Stream[HttpEvent[String], Async] {
                    Loop.foreach {
                        Async.delay(1.seconds)(()).andThen {
                            Emit.valueWith(Chunk(HttpEvent("data")))(Loop.continue)
                        }
                    }
                }
            }
            withServer(ep) { port =>
                Async.timeout(3.seconds) {
                    sendRaw(port, HttpMethod.OPTIONS, "/sse-opts").map { resp =>
                        assert(
                            resp.status == HttpStatus.NoContent,
                            s"OPTIONS on SSE endpoint should return 204, got ${resp.status}"
                        )
                        val allow = resp.headers.get("Allow")
                        assert(allow.isDefined, "OPTIONS on SSE should include Allow header")
                    }
                }
            }
        }

        "OPTIONS on mixed GET+POST path returns Allow with all methods" in run {
            val getRoute  = HttpRoute.getRaw("mixed").response(_.bodyText)
            val postRoute = HttpRoute.postRaw("mixed").request(_.bodyJson[User]).response(_.bodyText)
            val getEp     = getRoute.handler(_ => HttpResponse.okText("get-body"))
            val postEp    = postRoute.handler(_ => HttpResponse.okText("post-body"))
            withServer(getEp, postEp) { port =>
                Async.timeout(3.seconds) {
                    sendRaw(port, HttpMethod.OPTIONS, "/mixed").map { resp =>
                        assert(
                            resp.status == HttpStatus.NoContent,
                            s"OPTIONS on mixed path should return 204, got ${resp.status}"
                        )
                        val allow = resp.headers.get("Allow")
                        assert(allow.isDefined, "OPTIONS should include Allow header")
                        assert(allow.get.contains("GET"), "Allow should include GET")
                        assert(allow.get.contains("POST"), "Allow should include POST")
                        assert(allow.get.contains("HEAD"), "Allow should include HEAD")
                        assert(allow.get.contains("OPTIONS"), "Allow should include OPTIONS")
                        assert(
                            resp.fields.body != "get-body",
                            "OPTIONS should not return the GET handler's body"
                        )
                    }
                }
            }
        }
    }

    "request decoding" - {

        "query params" in run {
            val route = HttpRoute.getRaw("search")
                .request(_.query[String]("q"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"query=${req.fields.q}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/search?q=hello"))
                        .addField("q", "hello")
                ).map { resp =>
                    assert(resp.fields.body == "query=hello")
                }
            }
        }

        "multiple query params" in run {
            val route = HttpRoute.getRaw("search")
                .request(_.query[String]("q").query[Int]("page"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"${req.fields.q}:${req.fields.page}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/search?q=hello&page=2"))
                        .addField("q", "hello").addField("page", 2)
                ).map { resp =>
                    assert(resp.fields.body == "hello:2")
                }
            }
        }

        "optional query param absent" in run {
            val route = HttpRoute.getRaw("search")
                .request(_.queryOpt[String]("q"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"q=${req.fields.q}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/search"))
                        .addField("q", Maybe.empty[String])
                ).map { resp =>
                    assert(resp.fields.body == "q=Absent")
                }
            }
        }

        "optional query param present" in run {
            val route = HttpRoute.getRaw("search")
                .request(_.queryOpt[String]("q"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"q=${req.fields.q}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/search?q=found"))
                        .addField("q", Present("found"))
                ).map { resp =>
                    assert(resp.fields.body == "q=found")
                }
            }
        }

        "header extraction" in run {
            val route = HttpRoute.getRaw("check")
                .request(_.header[String]("X-Custom"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.`X-Custom`))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/check"))
                        .addField("X-Custom", "myvalue")
                ).map { resp =>
                    assert(resp.fields.body == "myvalue")
                }
            }
        }

        "cookie extraction" in run {
            val route = HttpRoute.getRaw("check")
                .request(_.cookie[String]("session"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.session))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/check"))
                        .addField("session", "abc123")
                ).map { resp =>
                    assert(resp.fields.body == "abc123")
                }
            }
        }

        "JSON body" in run {
            val route = HttpRoute.postRaw("users")
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.body.id + 1, req.fields.body.name.toUpperCase))
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/users"))
                        .addField("body", User(1, "bob"))
                ).map { resp =>
                    assert(resp.fields.body == User(2, "BOB"))
                }
            }
        }

        "text body" in run {
            val route = HttpRoute.postRaw("echo")
                .request(_.bodyText)
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.body))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/echo"))
                        .addField("body", "hello world")
                ).map { resp =>
                    assert(resp.fields.body == "hello world")
                }
            }
        }

        "form body" in run {
            val route = HttpRoute.postRaw("login")
                .request(_.bodyForm[LoginForm])
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"${req.fields.body.username}:${req.fields.body.password}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/login"))
                        .addField("body", LoginForm("admin", "secret"))
                ).map { resp =>
                    assert(resp.fields.body == "admin:secret")
                }
            }
        }

        "binary body" in run {
            val route = HttpRoute.postRaw("data")
                .request(_.bodyBinary)
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(new String(req.fields.body.toArrayUnsafe, "UTF-8"))
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/data"))
                        .addField("body", Span.fromUnsafe("binary data".getBytes("UTF-8")))
                ).map { resp =>
                    assert(resp.fields.body == "binary data")
                }
            }
        }

        "wrong Content-Type returns 415" in run {
            val route = HttpRoute.postRaw("users")
                .request(_.bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText("should not reach"))
            // Use a text route to send text/plain to a JSON endpoint (content-type mismatch)
            val textRoute = HttpRoute.postRaw("users")
                .request(_.bodyText)
                .response(_.bodyText)
            withServer(ep) { port =>
                send(
                    port,
                    textRoute,
                    HttpRequest.postRaw(HttpUrl.fromUri("/users"))
                        .addField("body", "not json")
                ).map { resp =>
                    assert(resp.status == HttpStatus.UnsupportedMediaType)
                }
            }
        }

        "missing required query param returns 400" in run {
            val route = HttpRoute.getRaw("search")
                .request(_.query[String]("q"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.q))
            // Send without the query param
            val bareRoute = HttpRoute.getRaw("search").response(_.bodyText)
            withServer(ep) { port =>
                send(port, bareRoute, HttpRequest.getRaw(HttpUrl.fromUri("/search"))).map { resp =>
                    assert(resp.status == HttpStatus.BadRequest)
                }
            }
        }

        "empty body on POST" in run {
            val route = HttpRoute.postRaw("empty").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("accepted"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.postRaw(HttpUrl.fromUri("/empty"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "accepted")
                }
            }
        }

        "wrong Content-Type on JSON endpoint returns 415" in run {
            val route = HttpRoute.postRaw("json-ct")
                .request(_.bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"got: ${req.fields.body}"))
            // Send with text/plain Content-Type via a text route
            val textRoute = HttpRoute.postRaw("json-ct")
                .request(_.bodyText)
                .response(_.bodyText)
            withServer(ep) { port =>
                send(
                    port,
                    textRoute,
                    HttpRequest.postRaw(HttpUrl.fromUri("/json-ct"))
                        .addField("body", """{"id":1,"name":"alice"}""")
                ).map { resp =>
                    assert(
                        resp.status == HttpStatus.UnsupportedMediaType || resp.status == HttpStatus.BadRequest,
                        s"Wrong Content-Type should be rejected, got ${resp.status}"
                    )
                }
            }
        }

        "Content-Type edge cases on JSON endpoint" in run {
            val route = HttpRoute.postRaw("json-ct-edges")
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler(req => HttpResponse.okJson(req.fields.body))
            withServer(ep) { port =>
                // application/json with charset should be accepted
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/json-ct-edges"))
                        .addField("body", User(1, "alice"))
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK, s"Valid JSON request should succeed, got ${resp.status}")
                    assert(resp.fields.body == User(1, "alice"))
                }
            }
        }

        "wrong Content-Type on form endpoint" in run {
            val route = HttpRoute.postRaw("form-ct")
                .request(_.bodyForm[LoginForm])
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"${req.fields.body.username}"))
            // Send JSON to a form endpoint
            val textRoute = HttpRoute.postRaw("form-ct")
                .request(_.bodyText)
                .response(_.bodyText)
            withServer(ep) { port =>
                send(
                    port,
                    textRoute,
                    HttpRequest.postRaw(HttpUrl.fromUri("/form-ct"))
                        .addField("body", """{"username":"admin","password":"secret"}""")
                ).map { resp =>
                    // Should either reject with 415/400 or at least fail to parse
                    assert(
                        resp.status != HttpStatus.OK || resp.fields.body != "admin",
                        "JSON body should not be successfully parsed as form data"
                    )
                }
            }
        }
    }

    "response encoding" - {

        "empty body" in run {
            val route = HttpRoute.getRaw("empty")
            val ep    = route.handler(_ => HttpResponse.noContent)
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/empty"))).map { resp =>
                    assert(resp.status == HttpStatus.NoContent)
                }
            }
        }

        "text body" in run {
            val route = HttpRoute.getRaw("text").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("hello"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/text"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "hello")
                }
            }
        }

        "JSON body" in run {
            val route = HttpRoute.getRaw("user").response(_.bodyJson[User])
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", User(1, "alice")))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/user"))).map { resp =>
                    assert(resp.fields.body == User(1, "alice"))
                }
            }
        }

        "custom status codes" in run {
            val route = HttpRoute.postRaw("create").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.created.addField("body", "done"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.postRaw(HttpUrl.fromUri("/create"))).map { resp =>
                    assert(resp.status == HttpStatus.Created)
                    assert(resp.fields.body == "done")
                }
            }
        }

        "route-declared status overrides response default" in run {
            val route = HttpRoute.postRaw("items")
                .response(_.bodyJson[User].status(HttpStatus.Created))
            val ep = route.handler { _ =>
                HttpResponse.ok.addField("body", User(1, "alice"))
            }
            withServer(ep) { port =>
                sendPost(port, route, "/items").map { resp =>
                    assert(resp.status == HttpStatus.Created)
                    assert(resp.fields.body == User(1, "alice"))
                }
            }
        }

        "route-declared status with no body" in run {
            val route = HttpRoute.getRaw("empty")
                .response(_.status(HttpStatus.NoContent))
            val ep = route.handler { _ =>
                HttpResponse.ok
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/empty").map { resp =>
                    assert(resp.status == HttpStatus.NoContent)
                }
            }
        }

        "route-declared status with text body" in run {
            val route = HttpRoute.postRaw("accepted")
                .response(_.bodyText.status(HttpStatus.Accepted))
            val ep = route.handler { _ =>
                HttpResponse.ok.addField("body", "queued")
            }
            withServer(ep) { port =>
                sendPost(port, route, "/accepted").map { resp =>
                    assert(resp.status == HttpStatus.Accepted)
                    assert(resp.fields.body == "queued")
                }
            }
        }

        "handler-set status still works when route has no status override" in run {
            val route = HttpRoute.postRaw("create2").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.created.addField("body", "done"))
            withServer(ep) { port =>
                sendPost(port, route, "/create2").map { resp =>
                    assert(resp.status == HttpStatus.Created)
                    assert(resp.fields.body == "done")
                }
            }
        }

        "response headers" in run {
            val route = HttpRoute.getRaw("headers")
                .response(_.header[String]("X-Custom"))
            val ep = route.handler(_ => HttpResponse.ok.addField("X-Custom", "value123"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/headers"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.`X-Custom` == "value123")
                }
            }
        }

        "response cookies" in run {
            val route = HttpRoute.getRaw("login")
                .response(_.cookie[String]("session"))
            val ep = route.handler(_ => HttpResponse.ok.addField("session", HttpCookie("tok123")))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/login"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.session.value == "tok123")
                }
            }
        }

        "multiple response headers" in run {
            val route = HttpRoute.getRaw("multi")
                .response(_.header[String]("X-One").header[String]("X-Two"))
            val ep = route.handler(_ => HttpResponse.ok.addField("X-One", "1").addField("X-Two", "2"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/multi"))).map { resp =>
                    assert(resp.fields.`X-One` == "1")
                    assert(resp.fields.`X-Two` == "2")
                }
            }
        }

        "large text body" in run {
            val largeText = "x" * 100000
            val route     = HttpRoute.getRaw("large").response(_.bodyText)
            val ep        = route.handler(_ => HttpResponse.okText(largeText))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/large"))).map { resp =>
                    assert(resp.fields.body.length == 100000)
                }
            }
        }
    }

    "streaming" - {

        "response ByteStream" in run {
            val route = HttpRoute.getRaw("stream").response(_.bodyStream)
            val ep = route.handler { _ =>
                val chunks = Stream.init(Seq(
                    Span.fromUnsafe("hello ".getBytes("UTF-8")),
                    Span.fromUnsafe("world".getBytes("UTF-8"))
                ))
                HttpResponse.ok.addField("body", chunks)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/stream"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                val text = chunks.foldLeft("")((acc, span) =>
                                    acc + new String(span.toArrayUnsafe, "UTF-8")
                                )
                                called = true
                                assert(text == "hello world")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "response Ndjson" in run {
            val route = HttpRoute.getRaw("events").response(_.bodyNdjson[User])
            val ep = route.handler { _ =>
                val users = Stream.init(Seq(User(1, "alice"), User(2, "bob")))
                HttpResponse.ok.addField("body", users)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/events"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                called = true
                                val users = chunks.toSeq
                                assert(users == Seq(User(1, "alice"), User(2, "bob")))
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "response SSE" in run {
            val route = HttpRoute.getRaw("sse").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.init(Seq(
                    HttpEvent("hello", Present("msg"), Absent, Absent),
                    HttpEvent("world", Present("msg"), Absent, Absent)
                ))
                HttpResponse.ok.addField("body", events)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                called = true
                                val events = chunks.toSeq
                                assert(events.size == 2)
                                assert(events(0).data == "hello")
                                assert(events(1).data == "world")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "streaming request" in run {
            val route = HttpRoute.postRaw("upload")
                .request(_.bodyStream)
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) =>
                        acc + new String(span.toArrayUnsafe, "UTF-8")
                    )
                    HttpResponse.okText(text)
                }
            }
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        val bodyStream: Stream[Span[Byte], Async] = Stream.init(Seq(
                            Span.fromUnsafe("part1 ".getBytes("UTF-8")),
                            Span.fromUnsafe("part2".getBytes("UTF-8"))
                        ))
                        val request = HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                            .addField("body", bodyStream)
                        client.sendWith(conn, route, request) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "part1 part2")
                        }
                    }
                }
            }
        }

        "many streaming chunks" in run {
            val route = HttpRoute.getRaw("many").response(_.bodyStream)
            val ep = route.handler { _ =>
                val chunks = Stream.init((1 to 100).map(i =>
                    Span.fromUnsafe(s"chunk$i\n".getBytes("UTF-8"))
                ))
                HttpResponse.ok.addField("body", chunks)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/many"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                called = true
                                val text = chunks.foldLeft("")((acc, span) =>
                                    acc + new String(span.toArrayUnsafe, "UTF-8")
                                )
                                assert(text.contains("chunk1\n"))
                                assert(text.contains("chunk100\n"))
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "handler errors" - {

        "handler throws exception returns 500" in run {
            val route = HttpRoute.getRaw("fail").response(_.bodyText)
            val ep = route.handler { _ =>
                throw new RuntimeException("boom")
                HttpResponse.okText("unreachable")
            }
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/fail"))).map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                }
            }
        }

        "handler returns Abort.fail returns 500" in run {
            val route = HttpRoute.getRaw("abort").response(_.bodyText)
            val ep = route.handler { _ =>
                Abort.fail(HttpError.ParseError("bad")).asInstanceOf[Nothing < Any]
            }
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/abort"))).map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                }
            }
        }

        "Abort.fail with declared error mapping returns mapped status and body" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("items" / HttpPath.Capture[Int]("id"))
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { req =>
                Abort.fail(ApiError(s"Item ${req.fields.id} not found"))
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/items/999").map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                    assert(resp.fields.body.contains("Item 999 not found"))
                }
            }
        }

        "Abort.fail with unmatched error type still returns 500" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            case class OtherError(msg: String)
            val route = HttpRoute.getRaw("items")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { _ =>
                Abort.fail(OtherError("unexpected")).asInstanceOf[Nothing < Any]
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/items").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                }
            }
        }

        "Abort.fail with multiple error mappings selects correct one" in run {
            case class NotFoundError(error: String) derives Schema, CanEqual
            case class ValidationError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("items")
                .response(
                    _.bodyJson[User]
                        .error[NotFoundError](HttpStatus.NotFound)
                        .error[ValidationError](HttpStatus.UnprocessableEntity)
                )
            val ep = route.handler { _ =>
                Abort.fail(ValidationError("invalid name"))
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/items").map { resp =>
                    assert(resp.status == HttpStatus.UnprocessableEntity)
                    assert(resp.fields.body.contains("invalid name"))
                }
            }
        }

        "Abort.fail with first of multiple error mappings" in run {
            case class NotFoundError(error: String) derives Schema, CanEqual
            case class ValidationError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("items2")
                .response(
                    _.bodyJson[User]
                        .error[NotFoundError](HttpStatus.NotFound)
                        .error[ValidationError](HttpStatus.UnprocessableEntity)
                )
            val ep = route.handler { _ =>
                Abort.fail(NotFoundError("gone"))
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/items2").map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                    assert(resp.fields.body.contains("gone"))
                }
            }
        }

        "successful handler ignores error mappings" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("ok-with-errors")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { _ =>
                HttpResponse.ok.addField("body", User(1, "alice"))
            }
            withServer(ep) { port =>
                sendGet(port, route, "/ok-with-errors").map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == User(1, "alice"))
                }
            }
        }

        "route-declared status combined with error mapping - success path" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("create-with-error")
                .response(
                    _.bodyJson[User]
                        .status(HttpStatus.Created)
                        .error[ApiError](HttpStatus.Conflict)
                )
            val ep = route.handler { _ =>
                HttpResponse.ok.addField("body", User(1, "alice"))
            }
            withServer(ep) { port =>
                sendGet(port, route, "/create-with-error").map { resp =>
                    assert(resp.status == HttpStatus.Created)
                }
            }
        }

        "route-declared status combined with error mapping - error path" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("create-with-error2")
                .response(
                    _.bodyJson[User]
                        .status(HttpStatus.Created)
                        .error[ApiError](HttpStatus.Conflict)
                )
            val ep = route.handler { _ =>
                Abort.fail(ApiError("already exists"))
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/create-with-error2").map { resp =>
                    assert(resp.status == HttpStatus.Conflict)
                    assert(resp.fields.body.contains("already exists"))
                }
            }
        }

        "Halt still works alongside error mappings" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("halt-test")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { _ =>
                Abort.fail(HttpResponse.Halt(HttpResponse.forbidden))
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/halt-test").map { resp =>
                    assert(resp.status == HttpStatus.Forbidden)
                }
            }
        }

        "error response body is valid JSON" in run {
            case class ApiError(code: Int, message: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("json-error")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.BadRequest))
            val ep = route.handler { _ =>
                Abort.fail(ApiError(42, "bad input"))
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/json-error").map { resp =>
                    assert(resp.status == HttpStatus.BadRequest)
                    val body = resp.fields.body
                    assert(body.contains("\"code\":42"))
                    assert(body.contains("\"message\":\"bad input\""))
                }
            }
        }

        "error response has Content-Type application/json" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("ct-error")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { _ =>
                Abort.fail(ApiError("missing"))
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/ct-error").map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                    val ct = resp.headers.get("Content-Type")
                    assert(ct.isDefined)
                    assert(ct.get.contains("application/json"))
                }
            }
        }
    }

    "keep-alive" - {

        "sequential requests on same connection" in run {
            val route = HttpRoute.getRaw("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("pong"))
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        Kyo.foreach(1 to 5) { i =>
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/ping")))(identity)
                        }.map { responses =>
                            assert(responses.forall(_.status == HttpStatus.OK))
                            assert(responses.forall(_.fields.body == "pong"))
                        }
                    }
                }
            }
        }
    }

    "endpoint composition" - {

        "handler wraps response" in run {
            val route = HttpRoute.getRaw("wrapped").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("inner").addHeader("X-Wrapped", "true"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/wrapped"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "inner")
                    assert(resp.headers.get("X-Wrapped") == Present("true"))
                }
            }
        }
    }

    "concurrent requests" - {

        "parallel requests to different endpoints" in run {
            val route1 = HttpRoute.getRaw("a").response(_.bodyText)
            val route2 = HttpRoute.getRaw("b").response(_.bodyText)
            val ep1    = route1.handler(_ => HttpResponse.okText("aaa"))
            val ep2    = route2.handler(_ => HttpResponse.okText("bbb"))
            withServer(ep1, ep2) { port =>
                Kyo.foreach(1 to 20) { i =>
                    val (route, path) = if i % 2 == 0 then (route1, "/a") else (route2, "/b")
                    send(port, route, HttpRequest.getRaw(HttpUrl.fromUri(path)))
                }.map { responses =>
                    assert(responses.forall(_.status == HttpStatus.OK))
                }
            }
        }

        "parallel requests to same endpoint" in run {
            val route = HttpRoute.getRaw("shared").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                Kyo.foreach(1 to 50) { _ =>
                    send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/shared")))
                }.map { responses =>
                    assert(responses.size == 50)
                    assert(responses.forall(_.status == HttpStatus.OK))
                }
            }
        }

        "concurrent handler isolation" in run {
            val route = HttpRoute.postText("handler-id")
            val ep = route.handler { req =>
                val id    = req.fields.body
                val delay = id.toInt % 10
                Async.sleep(delay.millis).andThen(HttpResponse.okText(id))
            }
            withServer(ep) { port =>
                val repeats = 10
                val sizes   = Choice.eval(4, 8, 16)
                (for
                    size  <- sizes
                    latch <- Latch.init(1)
                    fibers <- Kyo.foreach(0 until size) { i =>
                        Fiber.initUnscoped(
                            latch.await.andThen(
                                send(
                                    port,
                                    route,
                                    HttpRequest.postRaw(
                                        HttpUrl(Present("http"), "localhost", port, "/handler-id", Absent)
                                    ).addField("body", i.toString)
                                )
                            )
                        )
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield assert(results.zipWithIndex.forall { case (r, i) => r.fields.body == i.toString }))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "slow handler does not block fast handlers" in run {
            val slowRoute = HttpRoute.getRaw("slow-h").response(_.bodyText)
            val fastRoute = HttpRoute.getRaw("fast-h").response(_.bodyText)
            val slowEp    = slowRoute.handler(_ => Async.sleep(2.seconds).andThen(HttpResponse.okText("slow")))
            val fastEp    = fastRoute.handler(_ => HttpResponse.okText("fast"))
            withServer(slowEp, fastEp) { port =>
                val repeats = 5
                val sizes   = Choice.eval(2, 4, 8)
                (for
                    size <- sizes
                    // Fire slow request in background
                    slowFiber <- Fiber.initUnscoped(
                        Abort.run[Throwable](send(port, slowRoute, HttpRequest.getRaw(HttpUrl.fromUri("/slow-h"))))
                    )
                    _ <- Async.sleep(10.millis)
                    // Fast requests must complete quickly even while slow handler runs
                    latch <- Latch.init(1)
                    fastFibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            send(port, fastRoute, HttpRequest.getRaw(HttpUrl.fromUri("/fast-h")))
                        )
                    ))
                    _           <- latch.release
                    fastResults <- Async.timeout(1.second)(Kyo.foreach(fastFibers)(_.get))
                    _           <- slowFiber.interrupt
                yield assert(fastResults.forall(_.status == HttpStatus.OK)))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "concurrent streaming responses data isolation" in run {
            val route = HttpRoute.getRaw("stream-iso").response(_.bodyStream)
            val ep = route.handler { req =>
                val marker = req.headers.get("X-Marker").getOrElse("x")
                val chunks = Stream.init(Seq(
                    Span.fromUnsafe(s"$marker-1\n".getBytes("UTF-8")),
                    Span.fromUnsafe(s"$marker-2\n".getBytes("UTF-8")),
                    Span.fromUnsafe(s"$marker-3\n".getBytes("UTF-8"))
                ))
                HttpResponse.ok.addField("body", chunks)
            }
            withServer(ep) { port =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4, 8)
                (for
                    size  <- sizes
                    latch <- Latch.init(1)
                    fibers <- Kyo.foreach(0 until size) { i =>
                        Fiber.initUnscoped(
                            latch.await.andThen {
                                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                                        client.sendWith(
                                            conn,
                                            route,
                                            HttpRequest.getRaw(HttpUrl.fromUri("/stream-iso"))
                                                .setHeader("X-Marker", s"m$i")
                                        ) { resp =>
                                            resp.fields.body.run.map { chunks =>
                                                val text: String = chunks.foldLeft("")((acc, span) =>
                                                    acc + new String(span.toArrayUnsafe, "UTF-8")
                                                )
                                                (i, text)
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield assert(results.forall { case (i, text) =>
                    text.contains(s"m$i-1") && text.contains(s"m$i-2") && text.contains(s"m$i-3")
                }))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }
    }

    "server lifecycle" - {

        "bind to port 0 assigns random port" in run {
            HttpServer.init(0, "localhost")().map { server =>
                assert(server.port > 0)
                assert(server.host == "localhost" || server.host == "127.0.0.1")
            }
        }

        "close stops accepting new connections" in run {
            val route = HttpRoute.getRaw("test").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            HttpServer.init(0, "localhost")(ep).map { server =>
                val port = server.port
                // First request works
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/test"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                }
            }
        }
    }

    "HttpServer convenience APIs" - {

        val route = HttpRoute.getRaw("health").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))

        "initWith" in run {
            HttpServer.initWith(0, "localhost")(ep) { server =>
                assert(server.port > 0)
                send(server.port, route, HttpRequest.getRaw(HttpUrl.fromUri("/health"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "ok")
                }
            }
        }

        "initUnscopedWith" in run {
            HttpServer.initUnscopedWith(0, "localhost")(ep) { server =>
                assert(server.port > 0)
                server.closeNow.andThen(succeed)
            }
        }
    }

    "edge cases" - {

        "large request body" in run {
            val largeText = "x" * 50000
            val route = HttpRoute.postRaw("big")
                .request(_.bodyText)
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"len=${req.fields.body.length}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/big"))
                        .addField("body", largeText)
                ).map { resp =>
                    assert(resp.fields.body == "len=50000")
                }
            }
        }

        "special characters in query values" in run {
            val route = HttpRoute.getRaw("q")
                .request(_.query[String]("v"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.v))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/q?v=hello%26world%3Dfoo"))
                        .addField("v", "hello&world=foo")
                ).map { resp =>
                    assert(resp.fields.body == "hello&world=foo")
                }
            }
        }

        "multiple captures in path" in run {
            val route = HttpRoute.getRaw("users" / Capture[Int]("userId") / "posts" / Capture[Int]("postId"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"user=${req.fields.userId},post=${req.fields.postId}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/users/42/posts/7"))
                        .addField("userId", 42).addField("postId", 7)
                ).map { resp =>
                    assert(resp.fields.body == "user=42,post=7")
                }
            }
        }

        "path capture with special characters" in run {
            val route = HttpRoute.getRaw("items" / Capture[String]("id")).response(_.bodyText)
            val ep    = route.handler(req => HttpResponse.okText(req.fields.id))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.getRaw(HttpUrl.fromUri("/items/hello%2Fworld"))
                        .addField("id", "hello/world")
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "hello/world")
                }
            }
        }

        "combined request and response features" in run {
            val route = HttpRoute.postRaw("api" / Capture[Int]("id"))
                .request(_.query[String]("format").header[String]("X-Token").bodyJson[User])
                .response(_.header[String]("X-Request-Id").bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok
                    .addField("X-Request-Id", s"req-${req.fields.id}")
                    .addField("body", User(req.fields.id, s"${req.fields.body.name}-${req.fields.format}"))
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/api/99?format=json"))
                        .addField("id", 99)
                        .addField("format", "json")
                        .addField("X-Token", "secret")
                        .addField("body", User(0, "alice"))
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.`X-Request-Id` == "req-99")
                    assert(resp.fields.body == User(99, "alice-json"))
                }
            }
        }
    }

    "multipart" - {

        "buffered request" in run {
            val route = HttpRoute.postRaw("upload")
                .request(_.bodyMultipart)
                .response(_.bodyText)
            val ep = route.handler { req =>
                val parts = req.fields.body
                HttpResponse.okText(s"parts=${parts.size},name=${parts.head.name}")
            }
            withServer(ep) { port =>
                val part = HttpPart(
                    "file",
                    Present("test.txt"),
                    Present("text/plain"),
                    Span.fromUnsafe("file content".getBytes("UTF-8"))
                )
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                        .addField("body", Seq(part))
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "parts=1,name=file")
                }
            }
        }

        "streaming request" in run {
            val route = HttpRoute.postRaw("upload")
                .request(_.bodyMultipartStream)
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.body.run.map { chunks =>
                    val parts = chunks.toSeq
                    HttpResponse.okText(s"parts=${parts.size}")
                }
            }
            withServer(ep) { port =>
                val part = HttpPart(
                    "file",
                    Present("test.txt"),
                    Present("text/plain"),
                    Span.fromUnsafe("hello".getBytes("UTF-8"))
                )
                val sendRoute = HttpRoute.postRaw("upload")
                    .request(_.bodyMultipart)
                    .response(_.bodyText)
                send(
                    port,
                    sendRoute,
                    HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                        .addField("body", Seq(part))
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "parts=1")
                }
            }
        }

        "binary multipart upload preserves byte data" in run {
            val route = HttpRoute.postRaw("upload-bin")
                .request(_.bodyMultipart)
                .response(_.bodyBinary)
            val ep = route.handler { req =>
                val parts = req.fields.body
                val data  = parts.head.data
                HttpResponse.ok.addField("body", data)
            }
            val inputBytes = Array.tabulate[Byte](256)(i => i.toByte)
            withServer(ep) { port =>
                val part = HttpPart(
                    "file",
                    Present("data.bin"),
                    Present("application/octet-stream"),
                    Span.fromUnsafe(inputBytes)
                )
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/upload-bin"))
                        .addField("body", Seq(part))
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    val outputBytes = resp.fields.body.toArrayUnsafe
                    assert(
                        outputBytes.length == 256,
                        s"Binary data should be 256 bytes, got ${outputBytes.length} (corruption if inflated)"
                    )
                    assert(
                        outputBytes.sameElements(inputBytes),
                        "Binary data round-trip should preserve all byte values"
                    )
                }
            }
        }

        "large binary multipart round-trip" in run {
            val route = HttpRoute.postRaw("upload-large")
                .request(_.bodyMultipart)
                .response(_.bodyText)
            val ep = route.handler { req =>
                val data = req.fields.body.head.data
                HttpResponse.okText(s"size=${data.size}")
            }
            val inputBytes = Array.tabulate[Byte](5120)(i => (i % 256).toByte)
            withServer(ep) { port =>
                val part = HttpPart(
                    "file",
                    Present("large.bin"),
                    Present("application/octet-stream"),
                    Span.fromUnsafe(inputBytes)
                )
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/upload-large"))
                        .addField("body", Seq(part))
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "size=5120", s"Large binary should be 5120 bytes, got: ${resp.fields.body}")
                }
            }
        }

        "mixed text and binary multipart" in run {
            val route = HttpRoute.postRaw("upload-mixed")
                .request(_.bodyMultipart)
                .response(_.bodyText)
            val ep = route.handler { req =>
                val parts = req.fields.body
                val names = parts.map(_.name).mkString(",")
                val sizes = parts.map(_.data.size).mkString(",")
                HttpResponse.okText(s"names=$names;sizes=$sizes")
            }
            val textPart = HttpPart(
                "description",
                Absent,
                Present("text/plain"),
                Span.fromUnsafe("hello world".getBytes("UTF-8"))
            )
            val binPart = HttpPart(
                "file",
                Present("photo.png"),
                Present("image/png"),
                Span.fromUnsafe(Array.tabulate[Byte](100)(i => i.toByte))
            )
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/upload-mixed"))
                        .addField("body", Seq(textPart, binPart))
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body.contains("names=description,file"))
                    assert(resp.fields.body.contains("sizes=11,100"))
                }
            }
        }
    }

    "SSE advanced" - {

        "event name, id, and retry fields" in run {
            val route = HttpRoute.getRaw("sse").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.init(Seq(
                    HttpEvent("hello", Present("greeting"), Present("evt-1"), Present(5.seconds)),
                    HttpEvent("world", Present("greeting"), Present("evt-2"), Absent)
                ))
                HttpResponse.ok.addField("body", events)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse"))) { resp =>
                            resp.fields.body.run.map { chunks =>
                                called = true
                                val events = chunks.toSeq
                                assert(events.size == 2)
                                assert(events(0).data == "hello")
                                assert(events(0).event.contains("greeting"))
                                assert(events(0).id.contains("evt-1"))
                                assert(events(0).retry.contains(5.seconds))
                                assert(events(1).data == "world")
                                assert(events(1).id.contains("evt-2"))
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "SSE wire format should not JSON-wrap string data" in run {
            val route = HttpRoute.getRaw("sse").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.init(Seq(HttpEvent("hello", Absent, Absent, Absent)))
                HttpResponse.ok.addField("body", events)
            }
            // Read raw bytes to check wire format, bypassing typed decode
            val rawRoute = HttpRoute.getRaw("sse").response(_.bodyStream)
            var called   = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, rawRoute, HttpRequest.getRaw(HttpUrl.fromUri("/sse"))) { resp =>
                            resp.fields.body.run.map { chunks =>
                                called = true
                                val text = chunks.foldLeft("")((acc, span) =>
                                    acc + new String(span.toArrayUnsafe, "UTF-8")
                                )
                                assert(!text.contains("\"hello\""), s"SSE data should not be JSON-quoted, got: $text")
                                assert(text.contains("data: hello"), s"Expected plain text SSE data, got: $text")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "JSON string validation" - {

        "wrong Content-Type returns 415" in run {
            val route = HttpRoute.postRaw("echo")
                .request(_.bodyJson[String])
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"got: ${req.fields.body}")
            }
            // Send text/plain to a JSON endpoint (content-type mismatch)
            val textRoute = HttpRoute.postRaw("echo")
                .request(_.bodyText)
                .response(_.bodyText)
            withServer(ep) { port =>
                send(
                    port,
                    textRoute,
                    HttpRequest.postRaw(HttpUrl.fromUri("/echo"))
                        .addField("body", "{broken json")
                ).map { resp =>
                    assert(resp.status == HttpStatus.UnsupportedMediaType)
                }
            }
        }
    }

    "HttpClient.getText against server" - {

        "simple GET returns body text" in run {
            val route = HttpRoute.getRaw("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("pong"))
            withServer(ep) { port =>
                // First verify the direct path works
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/ping"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "pong")
                }.andThen {
                    HttpClient.getText(s"http://localhost:$port/ping").map { text =>
                        assert(text == "pong")
                    }
                }
            }
        }

        "getJson, postJson, putJson, deleteText convenience methods" in run {
            val getRoute = HttpRoute.getRaw("users" / Capture[Int]("id"))
                .response(_.bodyJson[User])
            val getEp = getRoute.handler { req =>
                HttpResponse.okJson(User(req.fields.id, "alice"))
            }
            val postRoute = HttpRoute.postRaw("users")
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val postEp = postRoute.handler { req =>
                HttpResponse.okJson(User(99, req.fields.body.name))
            }
            val putRoute = HttpRoute.putRaw("users" / Capture[Int]("id"))
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val putEp = putRoute.handler { req =>
                HttpResponse.okJson(User(req.fields.id, req.fields.body.name + "-updated"))
            }
            val delRoute = HttpRoute.deleteRaw("users" / Capture[Int]("id"))
                .response(_.bodyText)
            val delEp = delRoute.handler { req =>
                HttpResponse.okText(s"deleted-${req.fields.id}")
            }
            withServer(getEp, postEp, putEp, delEp) { port =>
                val base = s"http://localhost:$port"
                HttpClient.getJson[User](s"$base/users/1").map { user =>
                    assert(user == User(1, "alice"))
                }.andThen {
                    HttpClient.postJson[User, User](s"$base/users", User(0, "bob")).map { user =>
                        assert(user == User(99, "bob"))
                    }
                }.andThen {
                    HttpClient.putJson[User, User](s"$base/users/5", User(5, "carol")).map { user =>
                        assert(user == User(5, "carol-updated"))
                    }
                }.andThen {
                    HttpClient.deleteText(s"$base/users/3").map { text =>
                        assert(text == "deleted-3")
                    }
                }
            }
        }

        "getSseJson streaming convenience method" in run {
            val ep = HttpHandler.getSseJson[User]("events-conv") { _ =>
                Stream.init(Seq(
                    HttpEvent(data = User(1, "alice")),
                    HttpEvent(data = User(2, "bob"))
                ))
            }
            withServer(ep) { port =>
                HttpClient.getSseJson[User](s"http://localhost:$port/events-conv").map { stream =>
                    stream.take(2).run.map { chunks =>
                        val events = chunks.toSeq
                        assert(events.size == 2)
                        assert(events(0).data == User(1, "alice"))
                        assert(events(1).data == User(2, "bob"))
                    }
                }
            }
        }
    }

    "convenience method error responses" - {

        case class Item(id: Int, name: String) derives Schema, CanEqual
        case class CreateItem(name: String) derives Schema, CanEqual
        case class ConflictError(error: String, existingId: Int) derives Schema, CanEqual
        case class ValidationError(error: String, field: String) derives Schema, CanEqual

        "postJson receives 409 typed error — error body should be accessible" in run {
            val route = HttpRoute
                .postRaw("items")
                .request(_.bodyJson[CreateItem])
                .response(
                    _.bodyJson[Item]
                        .status(HttpStatus.Created)
                        .error[ConflictError](HttpStatus.Conflict)
                )
            val ep = route.handler { _ =>
                Abort.fail(ConflictError("already exists", 42))
            }
            withServer(ep) { port =>
                val url = s"http://localhost:$port/items"
                Abort.run[HttpError](
                    HttpClient.postJson[Item, CreateItem](url, CreateItem("dup"))
                ).map {
                    case Result.Error(err) =>
                        assert(
                            err.getMessage.contains("409") || err.getMessage.contains("Conflict") ||
                                err.getMessage.contains("already exists"),
                            s"Error should reference 409/Conflict/error body, got: ${err.getMessage}"
                        )
                    case Result.Success(item) =>
                        fail(s"Expected error for conflict, got success: $item")
                    case Result.Panic(ex) =>
                        fail(s"Unexpected panic: ${ex.getMessage}")
                }
            }
        }

        "getJson receives 404 typed error — error body should be accessible" in run {
            val route = HttpRoute
                .getRaw("items" / HttpPath.Capture[Int]("id"))
                .response(
                    _.bodyJson[Item]
                        .error[ValidationError](HttpStatus.NotFound)
                )
            val ep = route.handler { _ =>
                Abort.fail(ValidationError("not found", "id"))
            }
            withServer(ep) { port =>
                val url = s"http://localhost:$port/items/999"
                Abort.run[HttpError](
                    HttpClient.getJson[Item](url)
                ).map {
                    case Result.Error(err) =>
                        assert(
                            err.getMessage.contains("404") || err.getMessage.contains("NotFound") ||
                                err.getMessage.contains("not found"),
                            s"Error should reference 404/NotFound/error body, got: ${err.getMessage}"
                        )
                    case Result.Success(item) =>
                        fail(s"Expected error for not found, got success: $item")
                    case Result.Panic(ex) =>
                        fail(s"Unexpected panic: ${ex.getMessage}")
                }
            }
        }

        "raw sendWith preserves error response status and body" in run {
            val route = HttpRoute
                .postRaw("items")
                .request(_.bodyJson[CreateItem])
                .response(
                    _.bodyText
                        .error[ConflictError](HttpStatus.Conflict)
                )
            val ep = route.handler { _ =>
                Abort.fail(ConflictError("already exists", 42))
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest.postRaw(HttpUrl.fromUri("/items"))
                        .addField("body", CreateItem("dup"))
                ).map { resp =>
                    assert(resp.status == HttpStatus.Conflict, s"Expected 409 Conflict, got ${resp.status}")
                    assert(
                        resp.fields.body.contains("already exists"),
                        s"Error body should contain error message, got: ${resp.fields.body}"
                    )
                    assert(
                        resp.fields.body.contains("42"),
                        s"Error body should contain existingId, got: ${resp.fields.body}"
                    )
                }
            }
        }

        "putJson receives 400 validation error" in run {
            val route = HttpRoute
                .putRaw("items" / HttpPath.Capture[Int]("id"))
                .request(_.bodyJson[Item])
                .response(
                    _.bodyJson[Item]
                        .error[ValidationError](HttpStatus.BadRequest)
                )
            val ep = route.handler { _ =>
                Abort.fail(ValidationError("Name cannot be blank", "name"))
            }
            withServer(ep) { port =>
                val url = s"http://localhost:$port/items/1"
                Abort.run[HttpError](
                    HttpClient.putJson[Item, Item](url, Item(1, ""))
                ).map {
                    case Result.Error(err) =>
                        assert(
                            err.getMessage.contains("400") || err.getMessage.contains("BadRequest") ||
                                err.getMessage.contains("cannot be blank"),
                            s"Error should reference 400/BadRequest/error body, got: ${err.getMessage}"
                        )
                    case Result.Success(item) =>
                        fail(s"Expected error for validation, got success: $item")
                    case Result.Panic(ex) =>
                        fail(s"Unexpected panic: ${ex.getMessage}")
                }
            }
        }
    }

    "client disconnect and cleanup" - {

        "streaming response: server stops writing after client disconnects" in run {
            // Server sends an infinite stream. Client reads a few chunks then disconnects.
            // The server should stop writing (not leak the fiber).
            kyo.Latch.init(1).map { handlerDone =>
                val route = HttpRoute.getRaw("infinite").response(_.bodyStream)
                val ep = route.handler { _ =>
                    val infiniteStream = Stream[Span[Byte], Async] {
                        kyo.Loop.foreach {
                            Async.sleep(10.millis).andThen {
                                kyo.Emit.valueWith(Chunk(Span.fromUnsafe("chunk\n".getBytes("UTF-8"))))(kyo.Loop.continue[Unit])
                            }
                        }
                    }
                    Sync.ensure(handlerDone.release) {
                        HttpResponse.ok.addField("body", infiniteStream)
                    }
                }
                withServer(ep) { port =>
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/infinite"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.take(1).run.map { chunks =>
                                assert(chunks.size == 1)
                            }
                        }
                    }
                }.andThen {
                    handlerDone.await.andThen(succeed)
                }
            }
        }

        "streaming response: blocked stream terminates on client disconnect" in run {
            // Server produces a stream that blocks (via latch). Client disconnects.
            // The write fiber should be interrupted, not leaked.
            kyo.Latch.init(1).map { writeDone =>
                val route = HttpRoute.getRaw("hang").response(_.bodyStream)
                val ep = route.handler { _ =>
                    val hangStream = Stream[Span[Byte], Async] {
                        // Emit one chunk, then block forever
                        kyo.Emit.valueWith(Chunk(Span.fromUnsafe("first\n".getBytes("UTF-8")))) {
                            kyo.Latch.init(1).map(_.await).andThen {
                                kyo.Emit.valueWith(Chunk(Span.fromUnsafe("never".getBytes("UTF-8"))))(())
                            }
                        }
                    }
                    // Note: Sync.ensure here runs when the handler returns the response (immediately),
                    // NOT when the stream is done writing. We track the write fiber separately.
                    HttpResponse.ok.addField("body", hangStream)
                }
                withServer(ep) { port =>
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/hang"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            // Read the first chunk, then disconnect
                            resp.fields.body.take(1).run.map { chunks =>
                                assert(chunks.size == 1)
                            }
                        }
                    }.andThen(succeed)
                }
            }
        }

        "streaming request: handler completes even if client sends slowly" in run {
            // Client sends a streaming body with delays. Handler should receive all chunks.
            val route = HttpRoute.postRaw("slow-upload")
                .request(_.bodyStream)
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) =>
                        acc + new String(span.toArrayUnsafe, "UTF-8")
                    )
                    HttpResponse.okText(text)
                }
            }
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                            kyo.Emit.valueWith(Chunk(Span.fromUnsafe("a".getBytes("UTF-8")))) {
                                Async.sleep(50.millis).andThen {
                                    kyo.Emit.valueWith(Chunk(Span.fromUnsafe("b".getBytes("UTF-8"))))(())
                                }
                            }
                        }
                        val request = HttpRequest.postRaw(HttpUrl.fromUri("/slow-upload"))
                            .addField("body", bodyStream)
                        client.sendWith(conn, route, request) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "ab")
                        }
                    }
                }
            }
        }

        "handler error returns 500 and does not hang" in run {
            val route = HttpRoute.getRaw("error").response(_.bodyText)
            val ep = route.handler { _ =>
                throw new RuntimeException("handler boom")
            }
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/error"))).map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                }
            }
        }

        "streaming request: request body channel closed on normal completion" in run {
            // Verifies that the streaming request body channel is properly closed
            // after the request completes, and handler doesn't hang.
            kyo.Latch.init(1).map { handlerDone =>
                val route = HttpRoute.postRaw("upload-complete")
                    .request(_.bodyStream)
                    .response(_.bodyText)
                val ep = route.handler { req =>
                    Sync.ensure(handlerDone.release) {
                        req.fields.body.run.map { chunks =>
                            val text = chunks.foldLeft("")((acc, span) =>
                                acc + new String(span.toArrayUnsafe, "UTF-8")
                            )
                            HttpResponse.okText(text)
                        }
                    }
                }
                withServer(ep) { port =>
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                            val bodyStream: Stream[Span[Byte], Async] = Stream[Span[Byte], Async] {
                                kyo.Emit.valueWith(Chunk(Span.fromUnsafe("hello".getBytes("UTF-8"))))(())
                            }
                            val request = HttpRequest.postRaw(HttpUrl.fromUri("/upload-complete"))
                                .addField("body", bodyStream)
                            client.sendWith(conn, route, request) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                assert(resp.fields.body == "hello")
                            }
                        }
                    }.andThen {
                        handlerDone.await.andThen(succeed)
                    }
                }
            }
        }

        "streaming response: backpressure does not leak listeners" in run {
            // Server sends many chunks requiring backpressure. If drain listeners accumulate,
            // Node.js emits MaxListenersExceededWarning. We verify the response completes
            // without hanging (a sign of listener issues).
            val route = HttpRoute.getRaw("many-chunks").response(_.bodyStream)
            val ep = route.handler { _ =>
                val manyChunks = Stream[Span[Byte], Async] {
                    // Emit 50 chunks — enough to trigger backpressure multiple times
                    var i = 0
                    kyo.Loop.foreach {
                        if i >= 50 then kyo.Loop.done[Unit, Unit](())
                        else
                            i += 1
                            val data = ("x" * 1024 + "\n").getBytes("UTF-8")
                            kyo.Emit.valueWith(Chunk(Span.fromUnsafe(data)))(kyo.Loop.continue[Unit])
                    }
                }
                HttpResponse.ok.addField("body", manyChunks)
            }
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/many-chunks"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                val totalBytes = chunks.foldLeft(0)(_ + _.size)
                                assert(totalBytes > 0)
                                succeed
                            }
                        }
                    }
                }
            }
        }

        "streaming response error: response ends cleanly" in run {
            // Server stream throws after a few chunks. Client should still get a response
            // (possibly truncated) and not hang.
            val route = HttpRoute.getRaw("err-stream").response(_.bodyStream)
            val ep = route.handler { _ =>
                val failingStream = Stream[Span[Byte], Async] {
                    kyo.Emit.valueWith(Chunk(Span.fromUnsafe("ok\n".getBytes("UTF-8")))) {
                        throw new RuntimeException("stream error")
                    }
                }
                HttpResponse.ok.addField("body", failingStream)
            }
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/err-stream"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            Abort.run[Throwable](Abort.catching[Throwable] {
                                resp.fields.body.run.map { chunks =>
                                    succeed
                                }
                            }).map(_ => succeed)
                        }
                    }
                }
            }
        }
    }

    // ============================================================
    // New tests from demo validation coverage analysis
    // ============================================================

    "error response bodies" - {

        "500 response has non-empty body when handler throws" in run {
            val route = HttpRoute.getRaw("throw-test").response(_.bodyText)
            val ep = route.handler { _ =>
                throw new RuntimeException("boom"); HttpResponse.okText("unreachable")
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/throw-test").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                    assert(resp.fields.body.nonEmpty, "500 response body should not be empty")
                }
            }
        }

        "400 response for missing required query param includes error detail" in run {
            val route = HttpRoute.getRaw("search-test")
                .request(_.query[String]("q"))
                .response(_.bodyText)
            val ep        = route.handler(req => HttpResponse.okText(req.fields.q))
            val bareRoute = HttpRoute.getRaw("search-test").response(_.bodyText)
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/search-test").map { resp =>
                    assert(resp.status == HttpStatus.BadRequest)
                    assert(resp.fields.body.nonEmpty, "400 response body should explain what's missing")
                }
            }
        }

        "404 response has non-empty JSON body" in run {
            val route = HttpRoute.getRaw("exists").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/no-such-path").map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                    assert(resp.fields.body.nonEmpty, "404 response body should not be empty")
                }
            }
        }

        "405 response has non-empty body and Allow header" in run {
            val route = HttpRoute.getRaw("only-get").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.POST, "/only-get").map { resp =>
                    assert(resp.status == HttpStatus.MethodNotAllowed)
                    assert(resp.fields.body.nonEmpty, "405 response body should not be empty")
                    val allow = resp.headers.get("Allow")
                    assert(allow.isDefined, "405 response should include Allow header")
                }
            }
        }

        "error bodies work for all HTTP methods" in run {
            val route = HttpRoute.getRaw("get-only").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                Kyo.foreach(Seq(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) { method =>
                    sendRaw(port, method, "/get-only").map { resp =>
                        assert(resp.status == HttpStatus.MethodNotAllowed, s"$method should return 405")
                        assert(resp.fields.body.nonEmpty, s"$method 405 body should not be empty")
                    }
                }.map(_ => succeed)
            }
        }

        "bearer auth filter rejection has non-empty body" in run {
            val route = HttpRoute.getRaw("protected")
                .request(_.headerOpt[String]("authorization"))
                .response(_.bodyText)
            val ep = route.filter(HttpFilter.server.bearerAuth(token => token == "secret")).handler { _ =>
                HttpResponse.okText("authorized")
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/protected").map { resp =>
                    assert(resp.status == HttpStatus.Unauthorized)
                    assert(resp.fields.body.nonEmpty, "401 filter rejection should have non-empty body")
                }
            }
        }

        // Extends existing "wrong Content-Type returns 415" — verifies body contains error info
        "415 response for wrong Content-Type includes error detail" in run {
            val route = HttpRoute.postRaw("json-test")
                .request(_.bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler(_ => HttpResponse.okText("should not reach"))
            val textRoute = HttpRoute.postRaw("json-test")
                .request(_.bodyText)
                .response(_.bodyText)
            withServer(ep) { port =>
                send(
                    port,
                    textRoute,
                    HttpRequest.postRaw(HttpUrl.fromUri("/json-test"))
                        .addField("body", "not json")
                ).map { resp =>
                    assert(resp.status == HttpStatus.UnsupportedMediaType)
                    assert(resp.fields.body.nonEmpty, "415 response body should include error details")
                }
            }
        }
    }

    // Per RFC 9110 §9.3.7, OPTIONS "requests information about the communication options
    // available for the target resource." The router is the authoritative source for which
    // methods are registered on a path, so OPTIONS is handled at the router level — not by
    // dispatching to a per-route handler/filter. CORS preflight (which rides on OPTIONS) is
    // therefore a server-level concern configured via CorsConfig, not a per-route filter.
    // The per-route HttpFilter.server.cors() filter still adds CORS headers to regular
    // (non-OPTIONS) responses.
    "CORS" - {

        "CORS preflight OPTIONS returns 204 with CORS headers (server-level)" in run {
            val route = HttpRoute.getRaw("cors-resource").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            withCorsServer(CorsConfig.allowAll, ep) { port =>
                sendRaw(port, HttpMethod.OPTIONS, "/cors-resource").map { resp =>
                    assert(
                        resp.status == HttpStatus.NoContent,
                        s"CORS preflight should return 204, got ${resp.status}"
                    )
                    val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined, "Response should include Access-Control-Allow-Origin")
                    assert(allowOrigin.get == "*")
                    val allowMethods = resp.headers.get("Access-Control-Allow-Methods")
                    assert(allowMethods.isDefined, "Response should include Access-Control-Allow-Methods")
                    // RFC 9110: Allow header reflects actually registered methods
                    val allow = resp.headers.get("Allow")
                    assert(allow.isDefined, "Response should include Allow header")
                    assert(allow.get.contains("GET"), "Allow should include GET")
                    assert(allow.get.contains("HEAD"), "Allow should include HEAD (implicit per RFC 9110 §9.3.2)")
                    assert(allow.get.contains("OPTIONS"), "Allow should include OPTIONS")
                }
            }
        }

        "CORS filter adds Access-Control-Allow-Origin on regular responses" in run {
            val route = HttpRoute.getRaw("cors-get").response(_.bodyText)
            val ep    = route.filter(HttpFilter.server.cors()).handler(_ => HttpResponse.okText("hello"))
            withServer(ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/cors-get"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined, "GET response should include Access-Control-Allow-Origin")
                    assert(allowOrigin.get == "*")
                }
            }
        }

        "CORS preflight on POST-only endpoint returns 204" in run {
            val route = HttpRoute.postRaw("cors-post")
                .request(_.bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler(_ => HttpResponse.okText("ok"))
            withCorsServer(CorsConfig.allowAll, ep) { port =>
                sendRaw(port, HttpMethod.OPTIONS, "/cors-post").map { resp =>
                    assert(
                        resp.status == HttpStatus.NoContent,
                        s"CORS preflight on POST should return 204, got ${resp.status}"
                    )
                    val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined, "CORS preflight should include Access-Control-Allow-Origin")
                    // RFC 9110: Allow reflects the POST method actually registered
                    val allow = resp.headers.get("Allow")
                    assert(allow.isDefined, "Should include Allow header")
                    assert(allow.get.contains("POST"), "Allow should include POST")
                    assert(allow.get.contains("OPTIONS"), "Allow should include OPTIONS")
                }
            }
        }

        "CORS preflight works for PUT, PATCH, DELETE endpoints" in run {
            val putRoute    = HttpRoute.putRaw("cors-put").request(_.bodyJson[User]).response(_.bodyText)
            val patchRoute  = HttpRoute.patchRaw("cors-patch").request(_.bodyJson[User]).response(_.bodyText)
            val deleteRoute = HttpRoute.deleteRaw("cors-del").response(_.bodyText)
            val putEp       = putRoute.handler(_ => HttpResponse.okText("ok"))
            val patchEp     = patchRoute.handler(_ => HttpResponse.okText("ok"))
            val deleteEp    = deleteRoute.handler(_ => HttpResponse.okText("ok"))
            withCorsServer(CorsConfig.allowAll, putEp, patchEp, deleteEp) { port =>
                Kyo.foreach(Seq("/cors-put", "/cors-patch", "/cors-del")) { path =>
                    sendRaw(port, HttpMethod.OPTIONS, path).map { resp =>
                        assert(
                            resp.status == HttpStatus.NoContent,
                            s"CORS preflight on $path should return 204, got ${resp.status}"
                        )
                        val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                        assert(allowOrigin.isDefined, s"CORS preflight on $path should include Access-Control-Allow-Origin")
                    }
                }.map(_ => succeed)
            }
        }

        "CORS preflight on SSE endpoint returns 204 not SSE stream" in run {
            val corsEp = HttpRoute.getRaw("cors-sse").response(_.bodySseText)
                .handler { _ =>
                    val events = Stream[HttpEvent[String], Async] {
                        Loop.foreach {
                            Async.delay(1.seconds)(()).andThen {
                                Emit.valueWith(Chunk(HttpEvent("data")))(Loop.continue)
                            }
                        }
                    }
                    HttpResponse.ok.addField("body", events)
                }
            withCorsServer(CorsConfig.allowAll, corsEp) { port =>
                Async.timeout(3.seconds) {
                    sendRaw(port, HttpMethod.OPTIONS, "/cors-sse").map { resp =>
                        assert(
                            resp.status == HttpStatus.NoContent,
                            s"CORS preflight on SSE should return 204, got ${resp.status}"
                        )
                        val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                        assert(allowOrigin.isDefined, "CORS preflight on SSE should include CORS headers")
                    }
                }
            }
        }

        "CORS preflight on mixed GET+POST endpoint returns 204 with all methods" in run {
            val getRoute  = HttpRoute.getRaw("cors-mixed").response(_.bodyText)
            val postRoute = HttpRoute.postRaw("cors-mixed").request(_.bodyJson[User]).response(_.bodyText)
            val getEp     = getRoute.handler(_ => HttpResponse.okText("get"))
            val postEp    = postRoute.handler(_ => HttpResponse.okText("post"))
            withCorsServer(CorsConfig.allowAll, getEp, postEp) { port =>
                Async.timeout(3.seconds) {
                    sendRaw(port, HttpMethod.OPTIONS, "/cors-mixed").map { resp =>
                        assert(
                            resp.status == HttpStatus.NoContent,
                            s"CORS preflight on mixed path should return 204, got ${resp.status}"
                        )
                        val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                        assert(allowOrigin.isDefined, "CORS preflight should include CORS headers")
                        // RFC 9110: Allow must reflect all actually registered methods
                        val allow = resp.headers.get("Allow")
                        assert(allow.isDefined, "Should include Allow header")
                        assert(allow.get.contains("GET"), "Allow should include GET")
                        assert(allow.get.contains("POST"), "Allow should include POST")
                        assert(allow.get.contains("HEAD"), "Allow should include HEAD")
                        assert(allow.get.contains("OPTIONS"), "Allow should include OPTIONS")
                    }
                }
            }
        }
    }

    "server-level CORS" - {

        "CORS preflight OPTIONS returns 204 with CORS headers" in run {
            val route = HttpRoute.getRaw("cors-resource").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            withCorsServer(CorsConfig.allowAll, ep) { port =>
                sendRaw(port, HttpMethod.OPTIONS, "/cors-resource").map { resp =>
                    assert(
                        resp.status == HttpStatus.NoContent,
                        s"CORS preflight should return 204, got ${resp.status}"
                    )
                    val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined, "Response should include Access-Control-Allow-Origin")
                    assert(allowOrigin.get == "*")
                    val allowMethods = resp.headers.get("Access-Control-Allow-Methods")
                    assert(allowMethods.isDefined, "Response should include Access-Control-Allow-Methods")
                    val allow = resp.headers.get("Allow")
                    assert(allow.isDefined, "Response should include Allow header")
                }
            }
        }

        "CORS adds Access-Control-Allow-Origin on regular responses" in run {
            val route = HttpRoute.getRaw("cors-get").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("hello"))
            withCorsServer(CorsConfig.allowAll, ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/cors-get"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined, "GET response should include Access-Control-Allow-Origin")
                    assert(allowOrigin.get == "*")
                }
            }
        }

        "CORS preflight on POST-only endpoint returns 204" in run {
            val route = HttpRoute.postRaw("cors-post2")
                .request(_.bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler(_ => HttpResponse.okText("ok"))
            withCorsServer(CorsConfig.allowAll, ep) { port =>
                sendRaw(port, HttpMethod.OPTIONS, "/cors-post2").map { resp =>
                    assert(
                        resp.status == HttpStatus.NoContent,
                        s"CORS preflight on POST should return 204, got ${resp.status}"
                    )
                    val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined, "CORS preflight should include Access-Control-Allow-Origin")
                }
            }
        }

        "CORS preflight works for PUT, PATCH, DELETE endpoints" in run {
            val putRoute    = HttpRoute.putRaw("cors-put2").request(_.bodyJson[User]).response(_.bodyText)
            val patchRoute  = HttpRoute.patchRaw("cors-patch2").request(_.bodyJson[User]).response(_.bodyText)
            val deleteRoute = HttpRoute.deleteRaw("cors-del2").response(_.bodyText)
            val putEp       = putRoute.handler(_ => HttpResponse.okText("ok"))
            val patchEp     = patchRoute.handler(_ => HttpResponse.okText("ok"))
            val deleteEp    = deleteRoute.handler(_ => HttpResponse.okText("ok"))
            withCorsServer(CorsConfig.allowAll, putEp, patchEp, deleteEp) { port =>
                Kyo.foreach(Seq("/cors-put2", "/cors-patch2", "/cors-del2")) { path =>
                    sendRaw(port, HttpMethod.OPTIONS, path).map { resp =>
                        assert(
                            resp.status == HttpStatus.NoContent,
                            s"CORS preflight on $path should return 204, got ${resp.status}"
                        )
                        val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                        assert(allowOrigin.isDefined, s"CORS preflight on $path should include Access-Control-Allow-Origin")
                    }
                }.map(_ => succeed)
            }
        }

        "CORS preflight on SSE endpoint returns 204 not SSE stream" in run {
            val corsEp = HttpRoute.getRaw("cors-sse2").response(_.bodySseText)
                .handler { _ =>
                    val events = Stream[HttpEvent[String], Async] {
                        Loop.foreach {
                            Async.delay(1.seconds)(()).andThen {
                                Emit.valueWith(Chunk(HttpEvent("data")))(Loop.continue)
                            }
                        }
                    }
                    HttpResponse.ok.addField("body", events)
                }
            withCorsServer(CorsConfig.allowAll, corsEp) { port =>
                Async.timeout(3.seconds) {
                    sendRaw(port, HttpMethod.OPTIONS, "/cors-sse2").map { resp =>
                        assert(
                            resp.status == HttpStatus.NoContent,
                            s"CORS preflight on SSE should return 204, got ${resp.status}"
                        )
                        val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                        assert(allowOrigin.isDefined, "CORS preflight on SSE should include CORS headers")
                    }
                }
            }
        }

        "CORS preflight on mixed GET+POST endpoint returns 204 with all methods" in run {
            val getRoute  = HttpRoute.getRaw("cors-mixed2").response(_.bodyText)
            val postRoute = HttpRoute.postRaw("cors-mixed2").request(_.bodyJson[User]).response(_.bodyText)
            val getEp     = getRoute.handler(_ => HttpResponse.okText("get"))
            val postEp    = postRoute.handler(_ => HttpResponse.okText("post"))
            withCorsServer(CorsConfig.allowAll, getEp, postEp) { port =>
                Async.timeout(3.seconds) {
                    sendRaw(port, HttpMethod.OPTIONS, "/cors-mixed2").map { resp =>
                        assert(
                            resp.status == HttpStatus.NoContent,
                            s"CORS preflight on mixed path should return 204, got ${resp.status}"
                        )
                        val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                        assert(allowOrigin.isDefined, "CORS preflight should include CORS headers")
                        val allow = resp.headers.get("Allow")
                        assert(allow.isDefined, "Should include Allow header")
                        assert(allow.get.contains("GET"), "Allow should include GET")
                        assert(allow.get.contains("POST"), "Allow should include POST")
                    }
                }
            }
        }

        "CORS with custom origin" in run {
            val route = HttpRoute.getRaw("cors-custom").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            val cors  = CorsConfig(allowOrigin = "https://example.com")
            withCorsServer(cors, ep) { port =>
                send(port, route, HttpRequest.getRaw(HttpUrl.fromUri("/cors-custom"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined)
                    assert(allowOrigin.get == "https://example.com")
                }
            }
        }

        "CORS with bearerAuth" in run {
            val route = HttpRoute.getRaw("cors-auth")
                .request(_.headerOpt[String]("authorization"))
                .response(_.bodyText)
            val ep = route
                .filter(HttpFilter.server.bearerAuth(t => t == "valid"))
                .handler { _ => HttpResponse.okText("authorized") }
            withCorsServer(CorsConfig.allowAll, ep) { port =>
                sendRaw(port, HttpMethod.GET, "/cors-auth").map { noAuth =>
                    assert(noAuth.status == HttpStatus.Unauthorized, s"Missing auth should be 401, got ${noAuth.status}")
                    val allowOrigin = noAuth.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined, "CORS headers should be present even on 401 responses")
                }
            }
        }

        "CORS on SSE endpoint adds headers to GET response" in run {
            val route = HttpRoute.getRaw("cors-sse-get2").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.init(Seq(HttpEvent("test")))
                HttpResponse.ok.addField("body", events)
            }
            var called = false
            withCorsServer(CorsConfig.allowAll, ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/cors-sse-get2"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                            assert(allowOrigin.isDefined, "SSE GET with CORS should include Access-Control-Allow-Origin")
                            resp.fields.body.run.map { chunks =>
                                called = true
                                assert(chunks.toSeq.size == 1)
                                assert(chunks.toSeq(0).data == "test")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "filter runtime behavior" - {

        "bearerAuth filter accept, reject, and missing header" in run {
            val route = HttpRoute.getRaw("auth-test")
                .request(_.headerOpt[String]("authorization"))
                .response(_.bodyText)
            val ep = route.filter(HttpFilter.server.bearerAuth(token => token == "valid-token")).handler { _ =>
                HttpResponse.okText("authorized")
            }
            withServer(ep) { port =>
                // Valid token → 200
                sendRaw(port, HttpMethod.GET, "/auth-test").map { _ =>
                    // Missing header → 401
                    sendRaw(port, HttpMethod.GET, "/auth-test").map { noAuth =>
                        assert(noAuth.status == HttpStatus.Unauthorized, s"Missing auth should be 401, got ${noAuth.status}")
                    }
                }
            }
        }

        "chained CORS and bearerAuth filters" in run {
            val route = HttpRoute.getRaw("chained")
                .request(_.headerOpt[String]("authorization"))
                .response(_.bodyText)
            val ep = route
                .filter(HttpFilter.server.cors().andThen(HttpFilter.server.bearerAuth(t => t == "valid")))
                .handler { _ => HttpResponse.okText("authorized") }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/chained").map { noAuth =>
                    assert(noAuth.status == HttpStatus.Unauthorized, s"Missing auth should be 401, got ${noAuth.status}")
                }
            }
        }

        "CORS filter on SSE endpoint adds headers to GET response" in run {
            val route = HttpRoute.getRaw("cors-sse-get").response(_.bodySseText)
            val ep = route.filter(HttpFilter.server.cors()).handler { _ =>
                val events = Stream.init(Seq(HttpEvent("test")))
                HttpResponse.ok.addField("body", events)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/cors-sse-get"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                            assert(allowOrigin.isDefined, "SSE GET with CORS filter should include Access-Control-Allow-Origin")
                            resp.fields.body.run.map { chunks =>
                                called = true
                                assert(chunks.toSeq.size == 1)
                                assert(chunks.toSeq(0).data == "test")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "OpenAPI endpoint" - {

        "OpenAPI endpoint returns Content-Type application/json" in run {
            val route  = HttpRoute.getRaw("items").response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.okText("ok"))
            val config = HttpServer.Config(port = 0, host = "localhost").openApi("/openapi.json", "Test API")
            HttpServer.init(config)(ep).map { server =>
                val oaRoute = HttpRoute.getRaw("openapi.json").response(_.bodyText)
                send(server.port, oaRoute, HttpRequest.getRaw(HttpUrl.fromUri("/openapi.json"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    val ct = resp.headers.get("Content-Type")
                    assert(ct.isDefined)
                    assert(
                        ct.get.contains("application/json"),
                        s"OpenAPI endpoint should return application/json, got ${ct.get}"
                    )
                    // Also verify the body is valid JSON (contains expected fields)
                    assert(resp.fields.body.contains("\"openapi\""))
                    assert(resp.fields.body.contains("Test API"))
                }
            }
        }
    }

    "streaming with delayed chunks" - {

        "SSE JSON with infinite stream and delay" in run {
            var counter = 0
            val ep = HttpHandler.getSseJson[User]("sse-repeat") { _ =>
                counter = 0
                Stream[HttpEvent[User], Async] {
                    Loop.foreach {
                        for
                            _ <- Async.delay(500.millis)(())
                        yield
                            counter += 1
                            Emit.valueWith(Chunk(HttpEvent(User(counter, s"user-$counter"), Absent, Absent, Absent)))(Loop.continue)
                    }
                }
            }
            val route  = HttpRoute.getRaw("sse-repeat").response(_.bodySseJson[User])
            var called = false
            withServer(ep) { port =>
                Async.timeout(10.seconds) {
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse-repeat"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                resp.fields.body.take(2).run.map { chunks =>
                                    called = true
                                    val events = chunks.toSeq
                                    assert(events.size == 2)
                                    assert(events(0).data == User(1, "user-1"))
                                    assert(events(1).data == User(2, "user-2"))
                                }
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "SSE with delayed first event" in run {
            val route = HttpRoute.getRaw("sse-delayed").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.init(Chunk(1, 2, 3)).mapChunk { chunk =>
                    Async.delay(100.millis) {
                        chunk.map(i => HttpEvent(s"event-$i"))
                    }
                }
                HttpResponse.ok.addField("body", events)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse-delayed"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                called = true
                                val events = chunks.toSeq
                                assert(events.size == 3, s"Expected 3 delayed SSE events, got ${events.size}")
                                assert(events(0).data == "event-1")
                                assert(events(1).data == "event-2")
                                assert(events(2).data == "event-3")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "NDJSON with infinite stream and delay" in run {
            var counter = 0
            val route   = HttpRoute.getRaw("ndjson-repeat").response(_.bodyNdjson[User])
            val ep = route.handler { _ =>
                counter = 0
                val users = Stream[User, Async] {
                    Loop.foreach {
                        for
                            _ <- Async.delay(500.millis)(())
                        yield
                            counter += 1
                            Emit.valueWith(Chunk(User(counter, s"user-$counter")))(Loop.continue)
                    }
                }
                HttpResponse.ok.addField("body", users)
            }
            var called = false
            withServer(ep) { port =>
                Async.timeout(10.seconds) {
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/ndjson-repeat"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                resp.fields.body.take(2).run.map { chunks =>
                                    called = true
                                    val users = chunks.toSeq
                                    assert(users.size == 2)
                                    assert(users(0) == User(1, "user-1"))
                                    assert(users(1) == User(2, "user-2"))
                                }
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "NDJSON with delayed chunks" in run {
            val route = HttpRoute.getRaw("ndjson-delayed").response(_.bodyNdjson[User])
            val ep = route.handler { _ =>
                val users = Stream.init(Chunk(User(1, "alice"), User(2, "bob"))).mapChunk { chunk =>
                    Async.delay(100.millis) {
                        chunk
                    }
                }
                HttpResponse.ok.addField("body", users)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/ndjson-delayed"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                called = true
                                val users = chunks.toSeq
                                assert(users.size == 2, s"Expected 2 delayed NDJSON items, got ${users.size}")
                                assert(users(0) == User(1, "alice"))
                                assert(users(1) == User(2, "bob"))
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "empty SSE stream" in run {
            val route = HttpRoute.getRaw("sse-empty").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.empty[HttpEvent[String]]
                HttpResponse.ok.addField("body", events)
            }
            withServer(ep) { port =>
                Async.timeout(5.seconds) {
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse-empty"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                resp.fields.body.run.map { chunks =>
                                    assert(chunks.isEmpty)
                                }
                            }
                        }
                    }
                }
            }
        }

        "SSE with gaps between events" in run {
            val route = HttpRoute.getRaw("sse-gaps").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream[HttpEvent[String], Async] {
                    Emit.valueWith(Chunk(HttpEvent("first", Present("msg"), Absent, Absent))) {
                        Async.sleep(200.millis).andThen {
                            Emit.valueWith(Chunk(HttpEvent("second", Present("msg"), Absent, Absent))) {
                                Async.sleep(200.millis).andThen {
                                    Emit.valueWith(Chunk(HttpEvent("third", Present("msg"), Absent, Absent)))(())
                                }
                            }
                        }
                    }
                }
                HttpResponse.ok.addField("body", events)
            }
            var called = false
            withServer(ep) { port =>
                Async.timeout(5.seconds) {
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse-gaps"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                resp.fields.body.run.map { chunks =>
                                    called = true
                                    val events = chunks.toSeq
                                    assert(events.size == 3)
                                    assert(events(0).data == "first")
                                    assert(events(1).data == "second")
                                    assert(events(2).data == "third")
                                }
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        // Tests the .map pattern (used in McpServer — BUG-9) vs .mapChunk pattern (used in EventBus — works)
        "delayed stream via map pattern" in run {
            val route = HttpRoute.getRaw("sse-map-delay").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.init(Chunk(1, 2, 3)).map { i =>
                    Async.delay(100.millis) {
                        HttpEvent(s"item-$i")
                    }
                }
                HttpResponse.ok.addField("body", events)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse-map-delay"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                called = true
                                val events = chunks.toSeq
                                assert(events.size == 3, s"Expected 3 events via .map pattern, got ${events.size}")
                                assert(events(0).data == "item-1")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "SSE JSON stream with Async.delay inside map" in run {
            val ep = HttpHandler.getSseJson[User]("sse-delay-map") { _ =>
                Stream.init(Chunk.from(1 to 3)).map { i =>
                    Async.delay(200.millis) {
                        HttpEvent(data = User(i, s"u$i"))
                    }
                }
            }
            val route  = HttpRoute.getRaw("sse-delay-map").response(_.bodySseJson[User])
            var called = false
            withServer(ep) { port =>
                Async.timeout(10.seconds) {
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse-delay-map"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                resp.fields.body.take(2).run.map { chunks =>
                                    called = true
                                    val events = chunks.toSeq
                                    assert(events.size == 2, s"Expected 2 SSE JSON events, got ${events.size}")
                                    assert(events(0).data == User(1, "u1"))
                                    assert(events(1).data == User(2, "u2"))
                                }
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "NDJSON stream with Loop.foreach and Async.delay before Emit" in run {
            val ep = HttpHandler.getNdJson[User]("ndjson-loop-delay") { _ =>
                Stream[User, Async] {
                    Loop.indexed { i =>
                        if i >= 3 then Loop.done
                        else
                            Async.delay(200.millis)(()).andThen {
                                Emit.valueWith(Chunk(User(i + 1, s"u${i + 1}")))(Loop.continue)
                            }
                    }
                }
            }
            val route  = HttpRoute.getRaw("ndjson-loop-delay").response(_.bodyNdjson[User])
            var called = false
            withServer(ep) { port =>
                Async.timeout(10.seconds) {
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/ndjson-loop-delay"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                resp.fields.body.take(2).run.map { chunks =>
                                    called = true
                                    val users = chunks.toSeq
                                    assert(users.size == 2, s"Expected 2 NDJSON items, got ${users.size}")
                                    assert(users(0) == User(1, "u1"))
                                    assert(users(1) == User(2, "u2"))
                                }
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "stream with Async.delay before first Emit sends headers immediately" in run {
            val route = HttpRoute.getRaw("sse-delay-first").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream[HttpEvent[String], Async] {
                    Async.delay(200.millis) {
                        Emit.value(Chunk(HttpEvent("delayed-event")))
                    }
                }
                HttpResponse.ok.addField("body", events)
            }
            var called = false
            withServer(ep) { port =>
                Async.timeout(5.seconds) {
                    client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                        Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse-delay-first"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                resp.fields.body.run.map { chunks =>
                                    called = true
                                    val events = chunks.toSeq
                                    assert(events.size == 1, s"Expected 1 delayed event, got ${events.size}")
                                    assert(events(0).data == "delayed-event")
                                }
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "unhandled error response format" - {

        "handler throws exception returns 500 with non-empty body" in run {
            val route = HttpRoute.getRaw("fail-body").response(_.bodyText)
            val ep = route.handler { _ =>
                throw new RuntimeException("boom")
                HttpResponse.okText("unreachable")
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/fail-body").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                    assert(resp.fields.body.nonEmpty, "500 response should have a non-empty body")
                }
            }
        }

        "Abort.fail with unmatched error type returns 500 with non-empty body" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            case class OtherError(msg: String)
            val route = HttpRoute.getRaw("unmatched-body")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { _ =>
                Abort.fail(OtherError("unexpected")).asInstanceOf[Nothing < Any]
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/unmatched-body").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                    assert(resp.fields.body.nonEmpty, "500 response should have a non-empty body")
                }
            }
        }

        "unmatched Abort.fail does not leak internal class names in body" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            case class InternalDetail(secret: String)
            val route = HttpRoute.getRaw("leak-test")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { _ =>
                Abort.fail(InternalDetail("sensitive info")).asInstanceOf[Nothing < Any]
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/leak-test").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                    val body = resp.fields.body
                    assert(body.nonEmpty, "500 body should not be empty")
                    assert(!body.contains("InternalDetail"), s"500 body should not contain error class name, got: $body")
                    assert(!body.contains("sensitive info"), s"500 body should not contain error details, got: $body")
                    assert(!body.contains("kyo."), s"500 body should not contain kyo package references, got: $body")
                }
            }
        }

        "handler exception does not leak exception message in body" in run {
            val route = HttpRoute.getRaw("throw-leak").response(_.bodyText)
            val ep = route.handler { _ =>
                throw new RuntimeException("secret internal detail")
                HttpResponse.okText("unreachable")
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/throw-leak").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                    val body = resp.fields.body
                    assert(body.nonEmpty, "500 body should not be empty")
                    assert(
                        !body.contains("secret internal detail"),
                        s"500 body should not contain exception message, got: $body"
                    )
                }
            }
        }

        "unmatched error response has Content-Type application/json" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            case class OtherError(msg: String)
            val route = HttpRoute.getRaw("ct-unmatched")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { _ =>
                Abort.fail(OtherError("oops")).asInstanceOf[Nothing < Any]
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/ct-unmatched").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                    val ct = resp.headers.get("Content-Type")
                    assert(ct.isDefined, "500 response should have Content-Type header")
                    assert(
                        ct.get.contains("application/json"),
                        s"500 Content-Type should be application/json, got: ${ct.get}"
                    )
                }
            }
        }

        "unmatched error response body is valid JSON" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            case class OtherError(msg: String)
            val route = HttpRoute.getRaw("json-unmatched")
                .response(_.bodyJson[User].error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { _ =>
                Abort.fail(OtherError("oops")).asInstanceOf[Nothing < Any]
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/json-unmatched").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                    val body = resp.fields.body
                    assert(body.nonEmpty, "500 body should not be empty")
                    assert(
                        body.startsWith("{") || body.startsWith("["),
                        s"500 body should be JSON, got: $body"
                    )
                }
            }
        }
    }

    "HEAD request semantics" - {

        "HEAD response has empty body" in run {
            val getRoute = HttpRoute.getRaw("head-body-test").response(_.bodyText)
            val ep       = getRoute.handler(_ => HttpResponse.okText("this should not appear in HEAD"))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.HEAD, "/head-body-test").map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    // HEAD response body must be empty per RFC 9110 Section 9.3.2
                    assert(
                        resp.fields.body.isEmpty,
                        s"HEAD response body should be empty, got: '${resp.fields.body}'"
                    )
                }
            }
        }

        "HEAD response preserves Content-Type header from GET" in run {
            val route = HttpRoute.getRaw("head-ct-test").response(_.bodyJson[User])
            val ep    = route.handler(_ => HttpResponse.okJson(User(1, "alice")))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.HEAD, "/head-ct-test").map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    val ct = resp.headers.get("Content-Type")
                    assert(ct.isDefined, "HEAD response should include Content-Type")
                    assert(ct.get.contains("application/json"))
                }
            }
        }

        "HEAD Content-Length matches GET body size" in run {
            val route = HttpRoute.getRaw("head-cl").response(_.bodyJson[User])
            val ep    = route.handler(_ => HttpResponse.okJson(User(1, "alice")))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/head-cl").map { getResp =>
                    val getBodyLen = getResp.fields.body.length
                    assert(getBodyLen > 0, "GET body should be non-empty")
                    sendRaw(port, HttpMethod.HEAD, "/head-cl").map { headResp =>
                        assert(headResp.status == HttpStatus.OK)
                        assert(headResp.fields.body.isEmpty, "HEAD body should be empty")
                        val cl = headResp.headers.get("Content-Length").orElse(headResp.headers.get("content-length"))
                        cl match
                            case Present(v) =>
                                assert(
                                    v.toInt != 0,
                                    s"HEAD Content-Length should not be 0 when GET body is $getBodyLen bytes"
                                )
                            case Absent =>
                                succeed
                        end match
                    }
                }
            }
        }

        "HEAD on streaming endpoint returns immediately without body" in run {
            val ep = HttpHandler.getSseText("head-sse") { _ =>
                Stream[HttpEvent[String], Async] {
                    Loop.foreach {
                        Async.delay(1.seconds)(()).andThen {
                            Emit.valueWith(Chunk(HttpEvent("data")))(Loop.continue)
                        }
                    }
                }
            }
            withServer(ep) { port =>
                Async.timeout(3.seconds) {
                    sendRaw(port, HttpMethod.HEAD, "/head-sse").map { resp =>
                        assert(resp.fields.body.isEmpty, "HEAD on SSE should have empty body")
                    }
                }
            }
        }

        "HEAD on unknown path returns 404 with empty body" in run {
            val route = HttpRoute.getRaw("something").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.HEAD, "/no-such-path").map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                    assert(resp.fields.body.isEmpty, "HEAD 404 should have empty body")
                }
            }
        }
    }

    "304 Not Modified semantics" - {

        "304 response has no Content-Type header" in run {
            val route = HttpRoute.getRaw("nm-ct")
                .request(_.headerOpt[String]("if-none-match"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.`if-none-match` match
                    case Present(_) =>
                        HttpResponse.halt(HttpResponse.notModified.etag("\"test-etag\""))
                    case _ =>
                        HttpResponse.okText("content").etag("\"test-etag\"")
            }
            withServer(ep) { port =>
                val req = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/nm-ct"))
                    .setHeader("if-none-match", "\"test-etag\"")
                send(port, rawRoute, req).map { resp =>
                    assert(resp.status == HttpStatus.NotModified)
                    val ct = resp.headers.get("Content-Type")
                    assert(
                        ct.isEmpty || !ct.get.contains("application/json"),
                        s"304 response should not have Content-Type: application/json, got: ${ct.getOrElse("none")}"
                    )
                }
            }
        }

        "304 response has empty body" in run {
            val route = HttpRoute.getRaw("nm-body")
                .request(_.headerOpt[String]("if-none-match"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.`if-none-match` match
                    case Present(_) =>
                        HttpResponse.halt(HttpResponse.notModified.etag("\"test-etag\""))
                    case _ =>
                        HttpResponse.okText("content").etag("\"test-etag\"")
            }
            withServer(ep) { port =>
                val req = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/nm-body"))
                    .setHeader("if-none-match", "\"test-etag\"")
                send(port, rawRoute, req).map { resp =>
                    assert(resp.status == HttpStatus.NotModified)
                    assert(
                        resp.fields.body.isEmpty,
                        s"304 response body should be empty, got: '${resp.fields.body}'"
                    )
                }
            }
        }

        "304 response preserves ETag header" in run {
            val route = HttpRoute.getRaw("nm-etag")
                .request(_.headerOpt[String]("if-none-match"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.`if-none-match` match
                    case Present(_) =>
                        HttpResponse.halt(HttpResponse.notModified.etag("\"my-etag-123\""))
                    case _ =>
                        HttpResponse.okText("content").etag("\"my-etag-123\"")
            }
            withServer(ep) { port =>
                val req = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/nm-etag"))
                    .setHeader("if-none-match", "\"my-etag-123\"")
                send(port, rawRoute, req).map { resp =>
                    assert(resp.status == HttpStatus.NotModified)
                    val etag = resp.headers.get("ETag")
                    assert(etag.isDefined, "304 response should include ETag header")
                    assert(
                        etag.get.contains("my-etag-123"),
                        s"304 ETag should contain the value set by handler, got: ${etag.get}"
                    )
                }
            }
        }

        "304 on route with error mapping does not leak error Content-Type" in run {
            case class ApiError(error: String) derives Schema, CanEqual
            val route = HttpRoute.getRaw("nm-err")
                .request(_.headerOpt[String]("if-none-match"))
                .response(_.bodyText.error[ApiError](HttpStatus.NotFound))
            val ep = route.handler { req =>
                req.fields.`if-none-match` match
                    case Present(_) =>
                        HttpResponse.halt(HttpResponse.notModified.etag("\"test\""))
                    case _ =>
                        HttpResponse.okText("content").etag("\"test\"")
            }
            withServer(ep) { port =>
                val req = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/nm-err"))
                    .setHeader("if-none-match", "\"test\"")
                send(port, rawRoute, req).map { resp =>
                    assert(resp.status == HttpStatus.NotModified)
                    val ct = resp.headers.get("Content-Type")
                    assert(
                        ct.isEmpty || !ct.get.contains("application/json"),
                        s"304 on text route with error mapping should not have Content-Type: application/json, got: ${ct.getOrElse("none")}"
                    )
                    assert(
                        resp.fields.body.isEmpty,
                        s"304 body should be empty, got: '${resp.fields.body}'"
                    )
                }
            }
        }

        // Coverage expansion: 304 preserves Cache-Control
        "304 response preserves Cache-Control header" in run {
            val route = HttpRoute.getRaw("nm-cc")
                .request(_.headerOpt[String]("if-none-match"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.`if-none-match` match
                    case Present(_) =>
                        HttpResponse.halt(
                            HttpResponse.notModified
                                .etag("\"test\"")
                                .cacheControl("public, max-age=3600")
                        )
                    case _ =>
                        HttpResponse.okText("content")
                            .etag("\"test\"")
                            .cacheControl("public, max-age=3600")
            }
            withServer(ep) { port =>
                val req = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/nm-cc"))
                    .setHeader("if-none-match", "\"test\"")
                send(port, rawRoute, req).map { resp =>
                    assert(resp.status == HttpStatus.NotModified)
                    val cc = resp.headers.get("Cache-Control")
                    assert(cc.isDefined, "304 response should include Cache-Control header")
                    assert(
                        cc.get.contains("max-age=3600"),
                        s"304 Cache-Control should preserve value, got: ${cc.get}"
                    )
                }
            }
        }
    }

    "streaming response headers" - {

        "SSE response has Content-Type text/event-stream" in run {
            val route = HttpRoute.getRaw("sse-ct").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.init(Seq(HttpEvent("test")))
                HttpResponse.ok.addField("body", events)
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/sse-ct").map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    val ct = resp.headers.get("Content-Type")
                    assert(ct.isDefined, "SSE response should have Content-Type header")
                    assert(
                        ct.get.contains("text/event-stream"),
                        s"SSE Content-Type should be text/event-stream, got: ${ct.get}"
                    )
                }
            }
        }

        "NDJSON response has Content-Type application/x-ndjson" in run {
            val route = HttpRoute.getRaw("ndjson-ct").response(_.bodyNdjson[User])
            val ep = route.handler { _ =>
                val users = Stream.init(Seq(User(1, "alice")))
                HttpResponse.ok.addField("body", users)
            }
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.GET, "/ndjson-ct").map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    val ct = resp.headers.get("Content-Type")
                    assert(ct.isDefined, "NDJSON response should have Content-Type header")
                    assert(
                        ct.get.contains("application/x-ndjson"),
                        s"NDJSON Content-Type should be application/x-ndjson, got: ${ct.get}"
                    )
                }
            }
        }
    }

    "operational error quality" - {

        "multiple servers on different ports" in run {
            val route1 = HttpRoute.getRaw("s1").response(_.bodyText)
            val ep1    = route1.handler(_ => HttpResponse.okText("server1"))
            val route2 = HttpRoute.getRaw("s2").response(_.bodyText)
            val ep2    = route2.handler(_ => HttpResponse.okText("server2"))
            HttpServer.init(0, "localhost")(ep1).map { server1 =>
                HttpServer.init(0, "localhost")(ep2).map { server2 =>
                    assert(server1.port != server2.port)
                    send(server1.port, route1, HttpRequest.getRaw(HttpUrl.fromUri("/s1"))).map { resp1 =>
                        assert(resp1.fields.body == "server1")
                        send(server2.port, route2, HttpRequest.getRaw(HttpUrl.fromUri("/s2"))).map { resp2 =>
                            assert(resp2.fields.body == "server2")
                        }
                    }
                }
            }
        }

        "multiple servers with streaming responses" in run {
            val ep1 = HttpHandler.getSseText("stream1") { _ =>
                Stream[HttpEvent[String], Async] {
                    Loop.foreach {
                        Async.delay(100.millis)(()).andThen {
                            Emit.valueWith(Chunk(HttpEvent("from-server1")))(Loop.continue)
                        }
                    }
                }
            }
            val ep2 = HttpHandler.getSseText("stream2") { _ =>
                Stream[HttpEvent[String], Async] {
                    Loop.foreach {
                        Async.delay(100.millis)(()).andThen {
                            Emit.valueWith(Chunk(HttpEvent("from-server2")))(Loop.continue)
                        }
                    }
                }
            }
            val route1 = HttpRoute.getRaw("stream1").response(_.bodySseText)
            val route2 = HttpRoute.getRaw("stream2").response(_.bodySseText)
            withServer(ep1) { port1 =>
                withServer(ep2) { port2 =>
                    Async.timeout(10.seconds) {
                        // Read from both servers concurrently to trigger stream ID collision
                        Async.zip(
                            client.connectWith("localhost", port1, ssl = false, Absent) { conn =>
                                Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                                    client.sendWith(conn, route1, HttpRequest.getRaw(HttpUrl.fromUri("/stream1"))) { resp =>
                                        resp.fields.body.take(2).run.map { chunks =>
                                            val events = chunks.toSeq
                                            assert(events.size == 2)
                                            assert(events.forall(_.data == "from-server1"))
                                        }
                                    }
                                }
                            },
                            client.connectWith("localhost", port2, ssl = false, Absent) { conn =>
                                Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                                    client.sendWith(conn, route2, HttpRequest.getRaw(HttpUrl.fromUri("/stream2"))) { resp =>
                                        resp.fields.body.take(2).run.map { chunks =>
                                            val events = chunks.toSeq
                                            assert(events.size == 2)
                                            assert(events.forall(_.data == "from-server2"))
                                        }
                                    }
                                }
                            }
                        ).map((_, _) => succeed)
                    }
                }
            }
        }

        "handler error after client disconnect does not crash" in run {
            val route = HttpRoute.getRaw("slow-fail").response(_.bodyText)
            val ep = route.handler { _ =>
                Async.sleep(2.seconds).andThen {
                    throw new RuntimeException("delayed boom")
                    HttpResponse.okText("unreachable")
                }
            }
            withServer(ep) { port =>
                // Send request, then disconnect before handler finishes via timeout
                Abort.run[Throwable] {
                    Abort.catching[Throwable] {
                        Async.timeout(500.millis) {
                            sendRaw(port, HttpMethod.GET, "/slow-fail")
                        }
                    }
                }.map { _ =>
                    // Wait for handler to complete and potentially try to write to closed connection
                    Async.sleep(3.seconds).andThen(succeed)
                }
            }
        }

        "binding to in-use port fails with HttpError.BindError" in run {
            val route = HttpRoute.getRaw("test").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            HttpServer.init(0, "localhost")(ep).map { server =>
                val port = server.port
                Abort.run[Throwable] {
                    HttpServer.init(port, "localhost")(ep)
                }.map {
                    case Result.Failure(e: HttpError.BindError) =>
                        assert(e.port == port, s"BindError.port should be $port but was: ${e.port}")
                        assert(e.host == "localhost", s"BindError.host should be localhost but was: ${e.host}")
                        assert(e.getMessage.contains(port.toString), s"Error message should contain port $port but was: ${e.getMessage}")
                    case Result.Panic(e: HttpError.BindError) =>
                        assert(e.port == port, s"BindError.port should be $port but was: ${e.port}")
                        assert(e.host == "localhost", s"BindError.host should be localhost but was: ${e.host}")
                        assert(e.getMessage.contains(port.toString), s"Error message should contain port $port but was: ${e.getMessage}")
                    case other =>
                        fail(s"Expected HttpError.BindError on port $port but got: $other")
                }
            }
        }
    }

    "expanded coverage" - {

        "server recovers after 500 error" in run {
            val route   = HttpRoute.getRaw("maybe-fail").response(_.bodyText)
            var counter = 0
            val ep = route.handler { _ =>
                counter += 1
                if counter == 1 then throw new RuntimeException("first request fails")
                else HttpResponse.okText("recovered")
            }
            withServer(ep) { port =>
                // First request -> 500
                sendRaw(port, HttpMethod.GET, "/maybe-fail").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                }.andThen {
                    // Second request -> 200, server recovered
                    sendRaw(port, HttpMethod.GET, "/maybe-fail").map { resp =>
                        assert(resp.status == HttpStatus.OK)
                        assert(resp.fields.body == "recovered")
                    }
                }
            }
        }

        "concurrent error responses do not hang" in run {
            val route = HttpRoute.getRaw("concurrent-err").response(_.bodyText)
            val ep = route.handler { _ =>
                throw new RuntimeException("fail"); HttpResponse.okText("x")
            }
            withServer(ep) { port =>
                Async.foreach(1 to 5, 5) { _ =>
                    sendRaw(port, HttpMethod.GET, "/concurrent-err")
                }.map { responses =>
                    assert(responses.forall(_.status == HttpStatus.InternalServerError))
                }
            }
        }

        "empty SSE stream returns 200 with no events" in run {
            val route = HttpRoute.getRaw("sse-empty").response(_.bodySseText)
            val ep = route.handler { _ =>
                HttpResponse.ok.addField("body", Stream.empty[HttpEvent[String]])
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/sse-empty"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            resp.fields.body.run.map { chunks =>
                                called = true
                                assert(chunks.isEmpty, s"Empty SSE stream should produce 0 events, got ${chunks.size}")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

end HttpServerTest
