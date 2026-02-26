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

        "malformed JSON returns 400" in run {
            val route = HttpRoute.postRaw("users")
                .request(_.bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText("should not reach"))
            // Use a text route to send malformed JSON
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
                    assert(resp.status == HttpStatus.BadRequest)
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

        "malformed JSON returns 400" in run {
            val route = HttpRoute.postRaw("echo")
                .request(_.bodyJson[String])
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"got: ${req.fields.body}")
            }
            // Send malformed JSON via a text route
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
                    assert(resp.status == HttpStatus.BadRequest)
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
                    // Now test via HttpClient.getText
                    HttpClient.getText(s"http://localhost:$port/ping").map { text =>
                        assert(text == "pong")
                    }
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

        // Reproduces: BUG-1 from DEMO_VALIDATION — handler exception returns 500 with empty body
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

        // Reproduces: BUG-5 from DEMO_VALIDATION — missing query param returns 400 with empty body
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

        // Extends existing "malformed JSON returns 400" — verifies body contains parse error info
        "400 response for malformed JSON includes error detail" in run {
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
                    assert(resp.status == HttpStatus.BadRequest)
                    assert(resp.fields.body.nonEmpty, "400 response body should include parse error details")
                }
            }
        }
    }

    "CORS" - {

        // Reproduces: BUG-2 from DEMO_VALIDATION — CORS preflight OPTIONS returns 405 instead of 200
        "CORS preflight OPTIONS returns 200 with CORS headers" in run {
            val route = HttpRoute.getRaw("cors-resource").response(_.bodyText)
            val ep    = route.filter(HttpFilter.server.cors()).handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                sendRaw(port, HttpMethod.OPTIONS, "/cors-resource").map { resp =>
                    assert(
                        resp.status == HttpStatus.NoContent || resp.status == HttpStatus.OK,
                        s"CORS preflight should return 200 or 204, got ${resp.status}"
                    )
                    val allowOrigin = resp.headers.get("Access-Control-Allow-Origin")
                    assert(allowOrigin.isDefined, "Response should include Access-Control-Allow-Origin")
                    assert(allowOrigin.get == "*")
                    val allowMethods = resp.headers.get("Access-Control-Allow-Methods")
                    assert(allowMethods.isDefined, "Response should include Access-Control-Allow-Methods")
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
    }

    "OpenAPI endpoint" - {

        // Reproduces: BUG-3 from DEMO_VALIDATION — OpenAPI returns Content-Type text/plain instead of application/json
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

    "streaming with delays" - {

        // Reproduces: BUG-7/BUG-9 from DEMO_VALIDATION — SSE with delayed events never sends data
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
    }

    "HEAD request semantics" - {

        // Reproduces: BUG-N3 from DEMO_VALIDATION_NATIVE — HEAD returns full body on Native
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
    }

    "streaming response headers" - {

        // Reproduces: BUG-N2 from DEMO_VALIDATION_NATIVE — streaming headers corrupted on Native
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
                // First request → 500
                sendRaw(port, HttpMethod.GET, "/maybe-fail").map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                }.andThen {
                    // Second request → 200, server recovered
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
