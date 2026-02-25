package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.Async
import kyo.Chunk
import kyo.Duration
import kyo.Frame
import kyo.Kyo
import kyo.Maybe
import kyo.Present
import kyo.Record2.~
import kyo.Result
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Sync
import kyo.Test
import kyo.millis
import kyo.seconds
import scala.language.implicitConversions

class HttpServerTest extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Schema, CanEqual
    case class LoginForm(username: String, password: String) derives HttpFormCodec, CanEqual

    val client = kyo.http2.internal.NettyPlatformBackend.client

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
    val rawRoute = HttpRoute.get("raw").response(_.bodyText)

    def sendRaw(
        port: Int,
        method: HttpMethod,
        path: String
    )(using Frame): HttpResponse["body" ~ String] < (Async & Abort[HttpError]) =
        send(port, rawRoute, HttpRequest(method, HttpUrl.fromUri(path)))

    "routing" - {

        "404 for unknown path" in run {
            val route = HttpRoute.get("exists").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("found"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/not-exists"))).map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                }
            }
        }

        "405 with Allow header" in run {
            val getRoute    = HttpRoute.get("resource").response(_.bodyText)
            val postRoute   = HttpRoute.post("resource").response(_.bodyText)
            val deleteRoute = HttpRoute.delete("resource").response(_.bodyText)
            val getEp       = getRoute.handler(_ => HttpResponse.okText("get"))
            val postEp      = postRoute.handler(_ => HttpResponse.okText("post"))
            withServer(getEp, postEp) { port =>
                send(port, deleteRoute, HttpRequest(HttpMethod.DELETE, HttpUrl.fromUri("/resource"))).map { resp =>
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
            val getRoute = HttpRoute.get("hello").response(_.bodyText)
            val ep       = getRoute.handler(_ => HttpResponse.okText("world"))
            withServer(ep) { port =>
                // HEAD should match the GET handler
                send(port, getRoute, HttpRequest(HttpMethod.HEAD, HttpUrl.fromUri("/hello"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                }
            }
        }

        "explicit HEAD handler takes priority over GET fallback" in run {
            val getRoute  = HttpRoute.get("hello").response(_.header[String]("X-Handler"))
            val headRoute = HttpRoute.head("hello").response(_.header[String]("X-Handler"))
            val getEp     = getRoute.handler(_ => HttpResponse.ok.addField("X-Handler", "get"))
            val headEp    = headRoute.handler(_ => HttpResponse.ok.addField("X-Handler", "head"))
            withServer(getEp, headEp) { port =>
                send(port, headRoute, HttpRequest(HttpMethod.HEAD, HttpUrl.fromUri("/hello"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.`X-Handler` == "head")
                }
            }
        }

        "multiple methods on same path" in run {
            val getRoute    = HttpRoute.get("item").response(_.bodyText)
            val postRoute   = HttpRoute.post("item").request(_.bodyText).response(_.bodyText)
            val deleteRoute = HttpRoute.delete("item").response(_.bodyText)
            val getEp       = getRoute.handler(_ => HttpResponse.okText("got"))
            val postEp      = postRoute.handler(req => HttpResponse.okText(s"posted: ${req.fields.body}"))
            val deleteEp    = deleteRoute.handler(_ => HttpResponse.okText("deleted"))
            withServer(getEp, postEp, deleteEp) { port =>
                for
                    r1 <- send(port, getRoute, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/item")))
                    r2 <- send(port, postRoute, HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/item")).addField("body", "data"))
                    r3 <- send(port, deleteRoute, HttpRequest(HttpMethod.DELETE, HttpUrl.fromUri("/item")))
                yield
                    assert(r1.fields.body == "got")
                    assert(r2.fields.body == "posted: data")
                    assert(r3.fields.body == "deleted")
            }
        }

        "root path" in run {
            val route = HttpRoute.get("").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("root"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "root")
                }
            }
        }

        "trailing slash normalized" in run {
            val route = HttpRoute.get("users").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("list"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/users/"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "list")
                }
            }
        }

        "URL-encoded path segments" in run {
            val route = HttpRoute.get("items" / Capture[String]("name")).response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(req.fields.name)
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/items/hello%20world"))
                        .addField("name", "hello world")
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "hello world")
                }
            }
        }

        "rest capture" in run {
            val route = HttpRoute.get("files" / Rest("path")).response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(req.fields.path)
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/files/a/b/c"))
                        .addField("path", "a/b/c")
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "a/b/c")
                }
            }
        }

        "empty router returns 404" in run {
            withServer() { port =>
                val route = HttpRoute.get("anything").response(_.bodyText)
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/anything"))).map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                }
            }
        }

        "deeply nested path" in run {
            val route = HttpRoute.get("a" / "b" / "c" / "d" / "e").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("deep"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/a/b/c/d/e"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "deep")
                }
            }
        }
    }

    "request decoding" - {

        "query params" in run {
            val route = HttpRoute.get("search")
                .request(_.query[String]("q"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"query=${req.fields.q}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/search?q=hello"))
                        .addField("q", "hello")
                ).map { resp =>
                    assert(resp.fields.body == "query=hello")
                }
            }
        }

        "multiple query params" in run {
            val route = HttpRoute.get("search")
                .request(_.query[String]("q").query[Int]("page"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"${req.fields.q}:${req.fields.page}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/search?q=hello&page=2"))
                        .addField("q", "hello").addField("page", 2)
                ).map { resp =>
                    assert(resp.fields.body == "hello:2")
                }
            }
        }

        "optional query param absent" in run {
            val route = HttpRoute.get("search")
                .request(_.queryOpt[String]("q"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"q=${req.fields.q}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/search"))
                        .addField("q", Maybe.empty[String])
                ).map { resp =>
                    assert(resp.fields.body == "q=Absent")
                }
            }
        }

        "optional query param present" in run {
            val route = HttpRoute.get("search")
                .request(_.queryOpt[String]("q"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"q=${req.fields.q}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/search?q=found"))
                        .addField("q", Present("found"))
                ).map { resp =>
                    assert(resp.fields.body == "q=found")
                }
            }
        }

        "header extraction" in run {
            val route = HttpRoute.get("check")
                .request(_.header[String]("X-Custom"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.`X-Custom`))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/check"))
                        .addField("X-Custom", "myvalue")
                ).map { resp =>
                    assert(resp.fields.body == "myvalue")
                }
            }
        }

        "cookie extraction" in run {
            val route = HttpRoute.get("check")
                .request(_.cookie[String]("session"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.session))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/check"))
                        .addField("session", "abc123")
                ).map { resp =>
                    assert(resp.fields.body == "abc123")
                }
            }
        }

        "JSON body" in run {
            val route = HttpRoute.post("users")
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.body.id + 1, req.fields.body.name.toUpperCase))
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/users"))
                        .addField("body", User(1, "bob"))
                ).map { resp =>
                    assert(resp.fields.body == User(2, "BOB"))
                }
            }
        }

        "text body" in run {
            val route = HttpRoute.post("echo")
                .request(_.bodyText)
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.body))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/echo"))
                        .addField("body", "hello world")
                ).map { resp =>
                    assert(resp.fields.body == "hello world")
                }
            }
        }

        "form body" in run {
            val route = HttpRoute.post("login")
                .request(_.bodyForm[LoginForm])
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"${req.fields.body.username}:${req.fields.body.password}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/login"))
                        .addField("body", LoginForm("admin", "secret"))
                ).map { resp =>
                    assert(resp.fields.body == "admin:secret")
                }
            }
        }

        "binary body" in run {
            val route = HttpRoute.post("data")
                .request(_.bodyBinary)
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(new String(req.fields.body.toArrayUnsafe, "UTF-8"))
            }
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/data"))
                        .addField("body", Span.fromUnsafe("binary data".getBytes("UTF-8")))
                ).map { resp =>
                    assert(resp.fields.body == "binary data")
                }
            }
        }

        "malformed JSON returns 400" in run {
            val route = HttpRoute.post("users")
                .request(_.bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText("should not reach"))
            // Use a text route to send malformed JSON
            val textRoute = HttpRoute.post("users")
                .request(_.bodyText)
                .response(_.bodyText)
            withServer(ep) { port =>
                send(
                    port,
                    textRoute,
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/users"))
                        .addField("body", "not json")
                ).map { resp =>
                    assert(resp.status == HttpStatus.BadRequest)
                }
            }
        }

        "missing required query param returns 400" in run {
            val route = HttpRoute.get("search")
                .request(_.query[String]("q"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.q))
            // Send without the query param
            val bareRoute = HttpRoute.get("search").response(_.bodyText)
            withServer(ep) { port =>
                send(port, bareRoute, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/search"))).map { resp =>
                    assert(resp.status == HttpStatus.BadRequest)
                }
            }
        }

        "empty body on POST" in run {
            val route = HttpRoute.post("empty").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("accepted"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/empty"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "accepted")
                }
            }
        }
    }

    "response encoding" - {

        "empty body" in run {
            val route = HttpRoute.get("empty")
            val ep    = route.handler(_ => HttpResponse.noContent)
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/empty"))).map { resp =>
                    assert(resp.status == HttpStatus.NoContent)
                }
            }
        }

        "text body" in run {
            val route = HttpRoute.get("text").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("hello"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/text"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "hello")
                }
            }
        }

        "JSON body" in run {
            val route = HttpRoute.get("user").response(_.bodyJson[User])
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", User(1, "alice")))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/user"))).map { resp =>
                    assert(resp.fields.body == User(1, "alice"))
                }
            }
        }

        "custom status codes" in run {
            val route = HttpRoute.post("create").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.created.addField("body", "done"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/create"))).map { resp =>
                    assert(resp.status == HttpStatus.Created)
                    assert(resp.fields.body == "done")
                }
            }
        }

        "response headers" in run {
            val route = HttpRoute.get("headers")
                .response(_.header[String]("X-Custom"))
            val ep = route.handler(_ => HttpResponse.ok.addField("X-Custom", "value123"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/headers"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.`X-Custom` == "value123")
                }
            }
        }

        "response cookies" in run {
            val route = HttpRoute.get("login")
                .response(_.cookie[String]("session"))
            val ep = route.handler(_ => HttpResponse.ok.addField("session", HttpCookie("tok123")))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/login"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.session.value == "tok123")
                }
            }
        }

        "multiple response headers" in run {
            val route = HttpRoute.get("multi")
                .response(_.header[String]("X-One").header[String]("X-Two"))
            val ep = route.handler(_ => HttpResponse.ok.addField("X-One", "1").addField("X-Two", "2"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/multi"))).map { resp =>
                    assert(resp.fields.`X-One` == "1")
                    assert(resp.fields.`X-Two` == "2")
                }
            }
        }

        "large text body" in run {
            val largeText = "x" * 100000
            val route     = HttpRoute.get("large").response(_.bodyText)
            val ep        = route.handler(_ => HttpResponse.okText(largeText))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/large"))).map { resp =>
                    assert(resp.fields.body.length == 100000)
                }
            }
        }
    }

    "streaming" - {

        "response ByteStream" in run {
            val route = HttpRoute.get("stream").response(_.bodyStream)
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
                        client.sendWith(conn, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/stream"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            Scope.run {
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
                }
            }.andThen(assert(called))
        }

        "response Ndjson" in run {
            val route = HttpRoute.get("events").response(_.bodyNdjson[User])
            val ep = route.handler { _ =>
                val users = Stream.init(Seq(User(1, "alice"), User(2, "bob")))
                HttpResponse.ok.addField("body", users)
            }
            var called = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/events"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            Scope.run {
                                resp.fields.body.run.map { chunks =>
                                    called = true
                                    val users = chunks.toSeq
                                    assert(users == Seq(User(1, "alice"), User(2, "bob")))
                                }
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "response SSE" in run {
            val route = HttpRoute.get("sse").response(_.bodySseText)
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
                        client.sendWith(conn, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/sse"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            Scope.run {
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
                }
            }.andThen(assert(called))
        }

        "streaming request" in run {
            val route = HttpRoute.post("upload")
                .request(_.bodyStream)
                .response(_.bodyText)
            val ep = route.handler { req =>
                Scope.run {
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
                        val bodyStream: Stream[Span[Byte], Async & Scope] = Stream.init(Seq(
                            Span.fromUnsafe("part1 ".getBytes("UTF-8")),
                            Span.fromUnsafe("part2".getBytes("UTF-8"))
                        ))
                        val request = HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/upload"))
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
            val route = HttpRoute.get("many").response(_.bodyStream)
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
                        client.sendWith(conn, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/many"))) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            Scope.run {
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
                }
            }.andThen(assert(called))
        }
    }

    "handler errors" - {

        "handler throws exception returns 500" in run {
            val route = HttpRoute.get("fail").response(_.bodyText)
            val ep = route.handler { _ =>
                throw new RuntimeException("boom")
                HttpResponse.okText("unreachable")
            }
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/fail"))).map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                }
            }
        }

        "handler returns Abort.fail returns 500" in run {
            val route = HttpRoute.get("abort").response(_.bodyText)
            val ep = route.handler { _ =>
                Abort.fail(HttpError.ParseError("bad")).asInstanceOf[Nothing < Any]
            }
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/abort"))).map { resp =>
                    assert(resp.status == HttpStatus.InternalServerError)
                }
            }
        }
    }

    "keep-alive" - {

        "sequential requests on same connection" in run {
            val route = HttpRoute.get("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("pong"))
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        Kyo.foreach(1 to 5) { i =>
                            client.sendWith(conn, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/ping")))(identity)
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
            val route = HttpRoute.get("wrapped").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("inner").addHeader("X-Wrapped", "true"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/wrapped"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "inner")
                    assert(resp.headers.get("X-Wrapped") == Present("true"))
                }
            }
        }
    }

    "concurrent requests" - {

        "parallel requests to different endpoints" in run {
            val route1 = HttpRoute.get("a").response(_.bodyText)
            val route2 = HttpRoute.get("b").response(_.bodyText)
            val ep1    = route1.handler(_ => HttpResponse.okText("aaa"))
            val ep2    = route2.handler(_ => HttpResponse.okText("bbb"))
            withServer(ep1, ep2) { port =>
                Kyo.foreach(1 to 20) { i =>
                    val (route, path) = if i % 2 == 0 then (route1, "/a") else (route2, "/b")
                    send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri(path)))
                }.map { responses =>
                    assert(responses.forall(_.status == HttpStatus.OK))
                }
            }
        }

        "parallel requests to same endpoint" in run {
            val route = HttpRoute.get("shared").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            withServer(ep) { port =>
                Kyo.foreach(1 to 50) { _ =>
                    send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/shared")))
                }.map { responses =>
                    assert(responses.size == 50)
                    assert(responses.forall(_.status == HttpStatus.OK))
                }
            }
        }
    }

    "server lifecycle" - {

        "bind to port 0 assigns random port" in run {
            Scope.run {
                HttpServer.init(0, "localhost")().map { server =>
                    assert(server.port > 0)
                    assert(server.host == "localhost" || server.host == "127.0.0.1")
                }
            }
        }

        "close stops accepting new connections" in run {
            val route = HttpRoute.get("test").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("ok"))
            Scope.run {
                HttpServer.init(0, "localhost")(ep).map { server =>
                    val port = server.port
                    // First request works
                    send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/test"))).map { resp =>
                        assert(resp.status == HttpStatus.OK)
                    }
                }
            }
        }
    }

    "HttpServer convenience APIs" - {

        val route = HttpRoute.get("health").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.okText("ok"))

        "initWith" in run {
            HttpServer.initWith(0, "localhost")(ep) { server =>
                assert(server.port > 0)
                send(server.port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/health"))).map { resp =>
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
            val route = HttpRoute.post("big")
                .request(_.bodyText)
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"len=${req.fields.body.length}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/big"))
                        .addField("body", largeText)
                ).map { resp =>
                    assert(resp.fields.body == "len=50000")
                }
            }
        }

        "special characters in query values" in run {
            val route = HttpRoute.get("q")
                .request(_.query[String]("v"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(req.fields.v))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/q?v=hello%26world%3Dfoo"))
                        .addField("v", "hello&world=foo")
                ).map { resp =>
                    assert(resp.fields.body == "hello&world=foo")
                }
            }
        }

        "multiple captures in path" in run {
            val route = HttpRoute.get("users" / Capture[Int]("userId") / "posts" / Capture[Int]("postId"))
                .response(_.bodyText)
            val ep = route.handler(req => HttpResponse.okText(s"user=${req.fields.userId},post=${req.fields.postId}"))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/users/42/posts/7"))
                        .addField("userId", 42).addField("postId", 7)
                ).map { resp =>
                    assert(resp.fields.body == "user=42,post=7")
                }
            }
        }

        "path capture with special characters" in run {
            val route = HttpRoute.get("items" / Capture[String]("id")).response(_.bodyText)
            val ep    = route.handler(req => HttpResponse.okText(req.fields.id))
            withServer(ep) { port =>
                send(
                    port,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/items/hello%2Fworld"))
                        .addField("id", "hello/world")
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "hello/world")
                }
            }
        }

        "combined request and response features" in run {
            val route = HttpRoute.post("api" / Capture[Int]("id"))
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
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/api/99?format=json"))
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
            val route = HttpRoute.post("upload")
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
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/upload"))
                        .addField("body", Seq(part))
                ).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "parts=1,name=file")
                }
            }
        }

        "streaming request" in run {
            val route = HttpRoute.post("upload")
                .request(_.bodyMultipartStream)
                .response(_.bodyText)
            val ep = route.handler { req =>
                Scope.run {
                    req.fields.body.run.map { chunks =>
                        val parts = chunks.toSeq
                        HttpResponse.okText(s"parts=${parts.size}")
                    }
                }
            }
            withServer(ep) { port =>
                val part = HttpPart(
                    "file",
                    Present("test.txt"),
                    Present("text/plain"),
                    Span.fromUnsafe("hello".getBytes("UTF-8"))
                )
                val sendRoute = HttpRoute.post("upload")
                    .request(_.bodyMultipart)
                    .response(_.bodyText)
                send(
                    port,
                    sendRoute,
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/upload"))
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
            val route = HttpRoute.get("sse").response(_.bodySseText)
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
                        client.sendWith(conn, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/sse"))) { resp =>
                            Scope.run {
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
                }
            }.andThen(assert(called))
        }

        "SSE wire format should not JSON-wrap string data" in run {
            val route = HttpRoute.get("sse").response(_.bodySseText)
            val ep = route.handler { _ =>
                val events = Stream.init(Seq(HttpEvent("hello", Absent, Absent, Absent)))
                HttpResponse.ok.addField("body", events)
            }
            // Read raw bytes to check wire format, bypassing typed decode
            val rawRoute = HttpRoute.get("sse").response(_.bodyStream)
            var called   = false
            withServer(ep) { port =>
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, rawRoute, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/sse"))) { resp =>
                            Scope.run {
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
                }
            }.andThen(assert(called))
        }
    }

    "JSON string validation" - {

        "malformed JSON returns 400" in run {
            val route = HttpRoute.post("echo")
                .request(_.bodyJson[String])
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"got: ${req.fields.body}")
            }
            // Send malformed JSON via a text route
            val textRoute = HttpRoute.post("echo")
                .request(_.bodyText)
                .response(_.bodyText)
            withServer(ep) { port =>
                send(
                    port,
                    textRoute,
                    HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/echo"))
                        .addField("body", "{broken json")
                ).map { resp =>
                    assert(resp.status == HttpStatus.BadRequest)
                }
            }
        }
    }

    "HttpClient.getText against server" - {

        "simple GET returns body text" in run {
            val route = HttpRoute.get("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("pong"))
            withServer(ep) { port =>
                // First verify the direct path works
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/ping"))).map { resp =>
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

end HttpServerTest
