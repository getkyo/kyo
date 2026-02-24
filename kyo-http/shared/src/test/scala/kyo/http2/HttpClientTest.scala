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
import kyo.Schedule
import kyo.Scope
import kyo.Span
import kyo.Stream
import kyo.Sync
import kyo.Test
import kyo.millis
import kyo.seconds
import scala.language.implicitConversions

class HttpClientTest extends Test:

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

    def withClient[A, S](f: HttpClient => A < (S & Async & Abort[HttpError]))(using Frame): A < (S & Async & Abort[HttpError]) =
        HttpClient.initUnscoped(client).map(f)

    def withClient[A, S](maxConnectionsPerHost: Int)(f: HttpClient => A < (S & Async & Abort[HttpError]))(using
        Frame
    ): A < (S & Async & Abort[HttpError]) =
        HttpClient.initUnscoped(client, maxConnectionsPerHost).map(f)

    val noTimeout = HttpClient.Config(timeout = Maybe.empty)

    "config" - {

        "default values" in {
            val config = HttpClient.Config()
            assert(config.baseUrl == Absent)
            assert(config.timeout == Present(5.seconds))
            assert(config.connectTimeout == Absent)
            assert(config.followRedirects == true)
            assert(config.maxRedirects == 10)
            assert(config.retrySchedule == Absent)
        }

        "default retryOn checks server errors" in {
            val config = HttpClient.Config()
            assert(config.retryOn(HttpStatus.InternalServerError) == true)
            assert(config.retryOn(HttpStatus.BadGateway) == true)
            assert(config.retryOn(HttpStatus.OK) == false)
            assert(config.retryOn(HttpStatus.BadRequest) == false)
        }

        "negative maxRedirects throws" in {
            assertThrows[IllegalArgumentException] {
                HttpClient.Config(maxRedirects = -1)
            }
        }

        "zero timeout throws" in {
            assertThrows[IllegalArgumentException] {
                HttpClient.Config(timeout = Present(Duration.Zero))
            }
        }

        "zero connectTimeout throws" in {
            assertThrows[IllegalArgumentException] {
                HttpClient.Config(connectTimeout = Present(Duration.Zero))
            }
        }

        "maxConnectionsPerHost must be positive" in {
            assertThrows[IllegalArgumentException] {
                HttpClient.initUnscoped(client, maxConnectionsPerHost = 0)
            }
        }
    }

    "HTTP methods" - {

        "GET with text response" in run {
            val route = HttpRoute.get("hello").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("world"))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/hello"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "world")
                }
            }
        }

        "POST with JSON request and response" in run {
            val route = HttpRoute.post("users")
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                val user = req.fields.body
                HttpResponse.ok.addField("body", User(user.id + 1, user.name.toUpperCase))
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/users"))
                    .addField("body", User(1, "bob"))
                send(port, route, request).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == User(2, "BOB"))
                }
            }
        }

        "PUT with JSON" in run {
            val route = HttpRoute.put("users" / Capture[Int]("id"))
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.id, req.fields.body.name))
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.PUT, HttpUrl.fromUri("/users/42"))
                    .addField("id", 42)
                    .addField("body", User(0, "updated"))
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == User(42, "updated"))
                }
            }
        }

        "PATCH with JSON" in run {
            val route = HttpRoute.patch("users" / Capture[Int]("id"))
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.id, req.fields.body.name))
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.PATCH, HttpUrl.fromUri("/users/99"))
                    .addField("id", 99)
                    .addField("body", User(0, "patched"))
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == User(99, "patched"))
                }
            }
        }

        "DELETE" in run {
            val route = HttpRoute.delete("users" / Capture[Int]("id"))
            val ep    = route.handler(_ => HttpResponse(HttpStatus.NoContent))
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.DELETE, HttpUrl.fromUri("/users/1"))
                    .addField("id", 1)
                send(port, route, request).map { resp =>
                    assert(resp.status == HttpStatus.NoContent)
                }
            }
        }
    }

    "path captures" - {

        "Int" in run {
            val route = HttpRoute.get("items" / Capture[Int]("id")).response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"item-${req.fields.id}")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/items/42"))
                    .addField("id", 42)
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "item-42")
                }
            }
        }

        "String" in run {
            val route = HttpRoute.get("users" / Capture[String]("name")).response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"hello ${req.fields.name}")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/users/alice"))
                    .addField("name", "alice")
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "hello alice")
                }
            }
        }

        "multiple" in run {
            val route = HttpRoute.get("orgs" / Capture[String]("org") / "repos" / Capture[Int]("id"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"${req.fields.org}/${req.fields.id}")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/orgs/kyo/repos/123"))
                    .addField("org", "kyo")
                    .addField("id", 123)
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "kyo/123")
                }
            }
        }
    }

    "query parameters" - {

        "single" in run {
            val route = HttpRoute.get("search")
                .request(_.query[String]("q"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"results for: ${req.fields.q}")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/search?q=kyo"))
                    .addField("q", "kyo")
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "results for: kyo")
                }
            }
        }

        "multiple" in run {
            val route = HttpRoute.get("search")
                .request(_.query[String]("q").query[Int]("page"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"${req.fields.q} page ${req.fields.page}")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/search?q=test&page=3"))
                    .addField("q", "test")
                    .addField("page", 3)
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "test page 3")
                }
            }
        }

        "optional" in run {
            val route = HttpRoute.get("search")
                .request(_.queryOpt[Int]("limit"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                val limit = req.fields.limit match
                    case Present(l) => l.toString
                    case Absent     => "default"
                HttpResponse.okText(limit)
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/search"))
                    .addField("limit", Absent: Maybe[Int])
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "default")
                }
            }
        }
    }

    "headers" - {

        "request header" in run {
            val route = HttpRoute.get("auth")
                .request(_.header[String]("Authorization"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(req.fields.Authorization)
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/auth"))
                    .addField("Authorization", "Bearer token123")
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "Bearer token123")
                }
            }
        }

        "response header" in run {
            val route = HttpRoute.get("headers")
                .response(_.header[String]("X-Custom").bodyText)
            val ep = route.handler { _ =>
                HttpResponse.ok
                    .addField("X-Custom", "custom-value")
                    .addField("body", "with-headers")
            }
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/headers"))).map { resp =>
                    assert(resp.fields.`X-Custom` == "custom-value")
                    assert(resp.fields.body == "with-headers")
                }
            }
        }

        "optional response header" in run {
            val route = HttpRoute.get("headers")
                .response(_.headerOpt[String]("X-Missing").bodyText)
            val ep = route.handler { _ =>
                HttpResponse.ok
                    .addField("X-Missing", Absent: Maybe[String])
                    .addField("body", "no-header")
            }
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/headers"))).map { resp =>
                    assert(resp.fields.`X-Missing` == Absent)
                    assert(resp.fields.body == "no-header")
                }
            }
        }
    }

    "cookies" - {

        "request cookie" in run {
            val route = HttpRoute.get("dashboard")
                .request(_.cookie[String]("session"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"session=${req.fields.session}")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/dashboard"))
                    .addField("session", "abc123")
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "session=abc123")
                }
            }
        }

        "response cookie" in run {
            val route = HttpRoute.get("login")
                .response(_.cookie[String]("token").bodyText)
            val ep = route.handler { _ =>
                HttpResponse.ok
                    .addField("token", HttpCookie("jwt-value"))
                    .addField("body", "logged in")
            }
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/login"))).map { resp =>
                    assert(resp.fields.token.value == "jwt-value")
                    assert(resp.fields.body == "logged in")
                }
            }
        }
    }

    "body variations" - {

        "text request and response" in run {
            val route = HttpRoute.post("echo")
                .request(_.bodyText)
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(req.fields.body.toUpperCase)
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/echo"))
                    .addField("body", "hello world")
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "HELLO WORLD")
                }
            }
        }

        "JSON round trip" in run {
            val route = HttpRoute.post("user")
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", req.fields.body)
            }
            withServer(ep) { port =>
                val user    = User(42, "alice")
                val request = HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/user")).addField("body", user)
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == user)
                }
            }
        }

        "form body" in run {
            val route = HttpRoute.post("login")
                .request(_.bodyForm[LoginForm])
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.okText(s"welcome ${req.fields.body.username}")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/login"))
                    .addField("body", LoginForm("alice", "secret"))
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "welcome alice")
                }
            }
        }

        "empty body" in run {
            val route = HttpRoute.get("empty")
            val ep    = route.handler(_ => HttpResponse(HttpStatus.NoContent))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/empty"))).map { resp =>
                    assert(resp.status == HttpStatus.NoContent)
                }
            }
        }

        "large text body (100KB)" in run {
            val largeBody = "x" * (100 * 1024)
            val route     = HttpRoute.get("large").response(_.bodyText)
            val ep        = route.handler(_ => HttpResponse.okText(largeBody))
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/large"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body.length == 100 * 1024)
                }
            }
        }
    }

    "streaming" - {

        "response" in run {
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

        "request" in run {
            val route = HttpRoute.post("upload")
                .request(_.bodyStream)
                .response(_.bodyText)
            val ep = route.handler { req =>
                Scope.run {
                    req.fields.body.run.map { chunks =>
                        val totalBytes = chunks.foldLeft(0)(_ + _.size)
                        HttpResponse.okText(s"received $totalBytes bytes")
                    }
                }
            }
            var called = false
            withServer(ep) { port =>
                val bodyStream: Stream[Span[Byte], Async & Scope] = Stream.init(Seq(
                    Span.fromUnsafe("chunk1".getBytes("UTF-8")),
                    Span.fromUnsafe("chunk2".getBytes("UTF-8"))
                ))
                val request = HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/upload"))
                    .addField("body", bodyStream)
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "received 12 bytes")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "request with buffered response" in run {
            val route = HttpRoute.post("upload")
                .request(_.bodyStream)
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                Scope.run {
                    req.fields.body.run.map { chunks =>
                        val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                        HttpResponse.ok.addField("body", User(1, text))
                    }
                }
            }
            var called = false
            withServer(ep) { port =>
                val bodyStream: Stream[Span[Byte], Async & Scope] = Stream.init(Seq(
                    Span.fromUnsafe("alice".getBytes("UTF-8"))
                ))
                val request = HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/upload"))
                    .addField("body", bodyStream)
                client.connectWith("localhost", port, ssl = false, Absent) { conn =>
                    Sync.Unsafe.ensure(client.closeNowUnsafe(conn)) {
                        client.sendWith(conn, route, request) { resp =>
                            called = true
                            assert(resp.fields.body == User(1, "alice"))
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "combined route features" - {

        "path capture + query + header + body" in run {
            val route = HttpRoute.post("orgs" / Capture[String]("org") / "users")
                .request(_.query[String]("role").header[String]("X-Request-Id").bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler { req =>
                val org       = req.fields.org
                val role      = req.fields.role
                val requestId = req.fields.`X-Request-Id`
                val user      = req.fields.body
                HttpResponse.okText(s"$org/$role/${user.name}/$requestId")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/orgs/kyo/users?role=admin"))
                    .addField("org", "kyo")
                    .addField("role", "admin")
                    .addField("X-Request-Id", "req-1")
                    .addField("body", User(1, "alice"))
                send(port, route, request).map { resp =>
                    assert(resp.fields.body == "kyo/admin/alice/req-1")
                }
            }
        }

        "query + cookie + response header" in run {
            val route = HttpRoute.get("dashboard")
                .request(_.query[String]("format").cookie[String]("session"))
                .response(_.header[String]("X-Format").bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok
                    .addField("X-Format", req.fields.format)
                    .addField("body", s"session=${req.fields.session}")
            }
            withServer(ep) { port =>
                val request = HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/dashboard?format=json"))
                    .addField("format", "json")
                    .addField("session", "tok-abc")
                send(port, route, request).map { resp =>
                    assert(resp.fields.`X-Format` == "json")
                    assert(resp.fields.body == "session=tok-abc")
                }
            }
        }
    }

    "multiple endpoints" - {

        "on same server" in run {
            val route1 = HttpRoute.get("a").response(_.bodyText)
            val ep1    = route1.handler(_ => HttpResponse.okText("endpoint-a"))

            val route2 = HttpRoute.get("b").response(_.bodyText)
            val ep2    = route2.handler(_ => HttpResponse.okText("endpoint-b"))

            withServer(ep1, ep2) { port =>
                send(port, route1, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/a"))).map { resp1 =>
                    assert(resp1.fields.body == "endpoint-a")
                    send(port, route2, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/b"))).map { resp2 =>
                        assert(resp2.fields.body == "endpoint-b")
                    }
                }
            }
        }
    }

    "error responses" - {

        "404 for unknown path" in run {
            val route = HttpRoute.get("exists").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("here"))

            val unknownRoute = HttpRoute.get("missing").response(_.bodyText)
            withServer(ep) { port =>
                send(port, unknownRoute, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/missing"))).map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                }
            }
        }

        "405 for wrong method" in run {
            val getRoute  = HttpRoute.get("test").response(_.bodyText)
            val ep        = getRoute.handler(_ => HttpResponse.okText("get"))
            val postRoute = HttpRoute.post("test").response(_.bodyText)
            withServer(ep) { port =>
                send(port, postRoute, HttpRequest(HttpMethod.POST, HttpUrl.fromUri("/test"))).map { resp =>
                    assert(resp.status == HttpStatus.MethodNotAllowed)
                }
            }
        }

        "status code propagation" in run {
            val route = HttpRoute.get("forbidden")
            val ep    = route.handler(_ => HttpResponse.forbidden)
            withServer(ep) { port =>
                send(port, route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/forbidden"))).map { resp =>
                    assert(resp.status == HttpStatus.Forbidden)
                }
            }
        }
    }

    "connection errors" - {

        "connection failure to invalid port" in run {
            Abort.run[HttpError] {
                client.connectWith("localhost", 1, ssl = false, Absent) { _ =>
                    ()
                }
            }.map { result =>
                assert(result.isFailure || result.isPanic)
            }
        }
    }

    "sendWith" - {

        "via HttpClient" in run {
            val route  = HttpRoute.get("ping").response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.okText("pong"))
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/ping", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.fields.body == "pong")
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "baseUrl" - {

        "resolution" in run {
            val route  = HttpRoute.get("api").response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.okText("api-response"))
            var called = false
            withServer(ep) { port =>
                val config = HttpClient.Config(
                    baseUrl = Present(HttpUrl(Present("http"), "localhost", port, "/", Absent)),
                    timeout = Absent
                )
                HttpClient.withConfig(config) {
                    withClient { client =>
                        client.sendWith(route, HttpRequest(HttpMethod.GET, HttpUrl.fromUri("/api"))) { resp =>
                            called = true
                            assert(resp.fields.body == "api-response")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "not applied when request has scheme" in run {
            val route  = HttpRoute.get("data").response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.okText("direct"))
            var called = false
            withServer(ep) { port =>
                val config = HttpClient.Config(
                    baseUrl = Present(HttpUrl(Present("http"), "other-host", 9999, "/", Absent)),
                    timeout = Absent
                )
                HttpClient.withConfig(config) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/data", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.fields.body == "direct")
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "redirect following" - {

        "basic redirect" in run {
            val route       = HttpRoute.get("start").response(_.bodyText)
            val targetRoute = HttpRoute.get("target").response(_.bodyText)
            val targetEp    = targetRoute.handler(_ => HttpResponse.okText("final"))
            val redirectEp  = route.handler(_ => HttpResponse.redirect("/target").addField("body", "redirect"))
            var called      = false
            withServer(targetEp, redirectEp) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/start", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "final")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "disabled" in run {
            val redirectRoute = HttpRoute.get("redir").response(_.bodyText)
            val ep            = redirectRoute.handler(_ => HttpResponse.redirect("/target").addField("body", "redirect"))
            var called        = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout.copy(followRedirects = false)) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/redir", Absent))
                        client.sendWith(redirectRoute, request) { resp =>
                            called = true
                            assert(resp.status.isRedirect)
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "too many redirects" in run {
            val route  = HttpRoute.get("loop").response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.redirect("/loop").addField("body", "loop"))
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout.copy(maxRedirects = 3)) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/loop", Absent))
                        Abort.run[HttpError](
                            client.sendWith(route, request)(identity)
                        ).map {
                            case Result.Failure(_: HttpError.TooManyRedirects) =>
                                called = true
                                succeed
                            case other => fail(s"Expected TooManyRedirects, got $other")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "chain A -> B -> C" in run {
            val routeA = HttpRoute.get("a").response(_.bodyText)
            val routeB = HttpRoute.get("b").response(_.bodyText)
            val routeC = HttpRoute.get("c").response(_.bodyText)
            val epC    = routeC.handler(_ => HttpResponse.okText("final"))
            val epB    = routeB.handler(_ => HttpResponse.redirect("/c").addField("body", "b"))
            val epA    = routeA.handler(_ => HttpResponse.redirect("/b").addField("body", "a"))
            var called = false
            withServer(epA, epB, epC) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/a", Absent))
                        client.sendWith(routeA, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "final")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "301 permanent redirect" in run {
            val route       = HttpRoute.get("old").response(_.bodyText)
            val targetRoute = HttpRoute.get("new").response(_.bodyText)
            val targetEp    = targetRoute.handler(_ => HttpResponse.okText("moved"))
            val redirectEp  = route.handler(_ => HttpResponse.movedPermanently("/new").addField("body", "redirect"))
            var called      = false
            withServer(targetEp, redirectEp) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/old", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "moved")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "307 temporary redirect" in run {
            val route       = HttpRoute.get("old").response(_.bodyText)
            val targetRoute = HttpRoute.get("new").response(_.bodyText)
            val targetEp    = targetRoute.handler(_ => HttpResponse.okText("temp"))
            val redirectEp = route.handler { _ =>
                HttpResponse(HttpStatus.TemporaryRedirect)
                    .setHeader("Location", "/new")
                    .addField("body", "redirect")
            }
            var called = false
            withServer(targetEp, redirectEp) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/old", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "temp")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "308 permanent redirect" in run {
            val route       = HttpRoute.get("old").response(_.bodyText)
            val targetRoute = HttpRoute.get("new").response(_.bodyText)
            val targetEp    = targetRoute.handler(_ => HttpResponse.okText("perm"))
            val redirectEp = route.handler { _ =>
                HttpResponse(HttpStatus.PermanentRedirect)
                    .setHeader("Location", "/new")
                    .addField("body", "redirect")
            }
            var called = false
            withServer(targetEp, redirectEp) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/old", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "perm")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "preserves query parameters in Location" in run {
            val route       = HttpRoute.get("start").response(_.bodyText)
            val targetRoute = HttpRoute.get("target").request(_.query[String]("q")).response(_.bodyText)
            val targetEp    = targetRoute.handler(req => HttpResponse.okText(s"q=${req.fields.q}"))
            val redirectEp  = route.handler(_ => HttpResponse.redirect("/target?q=hello").addField("body", "redirect"))
            var called      = false
            withServer(targetEp, redirectEp) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/start", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "q=hello")
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "retry" - {

        "on server error" in run {
            var attempts = 0
            val route    = HttpRoute.get("flaky").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                if attempts < 3 then HttpResponse.serverError.addField("body", "error")
                else HttpResponse.okText("recovered")
            }
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/flaky", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "recovered")
                            assert(attempts == 3)
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "no retry on client error" in run {
            var attempts = 0
            val route    = HttpRoute.get("bad").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                HttpResponse.notFound.addField("body", "nope")
            }
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/bad", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.NotFound)
                            assert(attempts == 1)
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "no retry after success" in run {
            var attempts = 0
            val route    = HttpRoute.get("ok").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                HttpResponse.okText("immediate")
            }
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/ok", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(attempts == 1)
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "custom predicate" in run {
            var attempts = 0
            val route    = HttpRoute.get("custom").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                if attempts == 1 then HttpResponse.serviceUnavailable.addField("body", "503")
                else if attempts == 2 then HttpResponse.serverError.addField("body", "500")
                else HttpResponse.okText("done")
            }
            var called = false
            withServer(ep) { port =>
                val config = noTimeout.copy(
                    retrySchedule = Present(Schedule.fixed(1.millis).take(5)),
                    retryOn = _ == HttpStatus.ServiceUnavailable
                )
                HttpClient.withConfig(config) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/custom", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            // Should retry 503, then stop at 500 (not matching predicate)
                            assert(resp.status == HttpStatus.InternalServerError)
                            assert(attempts == 2)
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "exponential backoff" in run {
            var attempts   = 0
            var timestamps = List.empty[Long]
            val route      = HttpRoute.get("slow").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                timestamps = timestamps :+ java.lang.System.currentTimeMillis()
                if attempts < 3 then HttpResponse.serverError.addField("body", "wait")
                else HttpResponse.okText("done")
            }
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.exponential(50.millis, 2.0).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/slow", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(attempts == 3)
                            assert(timestamps.size >= 3)
                            val delay1 = timestamps(1) - timestamps(0)
                            val delay2 = timestamps(2) - timestamps(1)
                            assert(delay2 >= delay1, s"Expected increasing delays: $delay1, $delay2")
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "exhausted returns last response" in run {
            var attempts = 0
            val route    = HttpRoute.get("fail").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                HttpResponse.serverError.addField("body", "always fails")
            }
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(3)))) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/fail", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            // After exhausting retries, should return the last response
                            assert(resp.status == HttpStatus.InternalServerError)
                            assert(attempts == 4) // 1 initial + 3 retries
                        }
                    }
                }
            }.andThen(assert(called))
        }
    }

    "timeout" - {

        "timeout error" in run {
            val route = HttpRoute.get("slow").response(_.bodyText)
            val ep = route.handler { _ =>
                Async.delay(10.seconds)(HttpResponse.okText("too late"))
            }
            withServer(ep) { port =>
                HttpClient.withConfig(HttpClient.Config(timeout = Present(100.millis))) {
                    withClient { client =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/slow", Absent))
                        Abort.run[HttpError](
                            client.sendWith(route, request)(identity)
                        ).map {
                            case Result.Failure(_: HttpError.TimeoutError) => succeed
                            case other                                     => fail(s"Expected TimeoutError, got $other")
                        }
                    }
                }
            }
        }
    }

    "connection pool" - {

        "exhausted" in run {
            val route  = HttpRoute.get("slow").response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.okText("ok"))
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout) {
                    Scope.run {
                        HttpClient.init(client, maxConnectionsPerHost = 1).map { c =>
                            val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/slow", Absent))
                            c.sendWith(route, request) { _ =>
                                Abort.run[HttpError](
                                    c.sendWith(route, request)(identity)
                                ).map {
                                    case Result.Failure(_: HttpError.ConnectionPoolExhausted) =>
                                        called = true
                                        succeed
                                    case other => fail(s"Expected ConnectionPoolExhausted, got $other")
                                }
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "works after error responses" in run {
            var count = 0
            val route = HttpRoute.get("maybe").response(_.bodyText)
            val ep = route.handler { _ =>
                count += 1
                if count <= 3 then HttpResponse.serverError.addField("body", "fail")
                else HttpResponse.okText("recovered")
            }
            var called = false
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient(2) { c =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/maybe", Absent))
                        Kyo.foreach(1 to 3) { _ =>
                            c.sendWith(route, request) { r =>
                                assert(r.status == HttpStatus.InternalServerError)
                            }
                        }.andThen {
                            c.sendWith(route, request) { r =>
                                called = true
                                assert(r.status == HttpStatus.OK)
                                assert(r.fields.body == "recovered")
                            }
                        }
                    }
                }
            }.andThen(assert(called))
        }

        "sequential requests reuse connections" in run {
            val route = HttpRoute.get("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("pong"))
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/ping", Absent))
                        Kyo.foreach(1 to 5) { _ =>
                            c.sendWith(route, request)(identity)
                        }.map { responses =>
                            assert(responses.forall(_.status == HttpStatus.OK))
                        }
                    }
                }
            }
        }

        "connections survive across multiple batches" in run {
            val route = HttpRoute.get("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("pong"))
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient(3) { c =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/ping", Absent))
                        Kyo.foreach(1 to 5) { _ =>
                            c.sendWith(route, request)(identity)
                        }.andThen {
                            Kyo.foreach(1 to 5) { _ =>
                                c.sendWith(route, request)(identity)
                            }.map { responses =>
                                assert(responses.forall(_.status == HttpStatus.OK))
                            }
                        }
                    }
                }
            }
        }
    }

    "close" - {

        "idempotent" in run {
            HttpClient.initUnscoped(client).map { c =>
                c.closeNow.andThen(c.closeNow).andThen(succeed)
            }
        }
    }

    "concurrency" - {

        "parallel requests to same endpoint" in run {
            val route = HttpRoute.get("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("pong"))
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient(10) { c =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/ping", Absent))
                        Async.fill(5, 5) {
                            c.sendWith(route, request)(identity)
                        }.map { responses =>
                            assert(responses.size == 5)
                            assert(responses.forall(_.status == HttpStatus.OK))
                        }
                    }
                }
            }
        }

        "concurrent requests with shared state" in run {
            val counter = new java.util.concurrent.atomic.AtomicInteger(0)
            val route   = HttpRoute.get("count").response(_.bodyText)
            val ep = route.handler { _ =>
                val n = counter.incrementAndGet()
                HttpResponse.okText(n.toString)
            }
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient(10) { c =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/count", Absent))
                        Async.fill(5, 5) {
                            c.sendWith(route, request)(identity)
                        }.map { responses =>
                            assert(responses.forall(_.status == HttpStatus.OK))
                            val counts = responses.map(_.fields.body.toInt)
                            assert(counts.toSet.size == 5)
                        }
                    }
                }
            }
        }

        "sequential then parallel requests" in run {
            val route = HttpRoute.get("data").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.okText("data"))
            withServer(ep) { port =>
                HttpClient.withConfig(noTimeout) {
                    withClient(10) { c =>
                        val request = HttpRequest(HttpMethod.GET, HttpUrl(Present("http"), "localhost", port, "/data", Absent))
                        c.sendWith(route, request)(identity).map { r1 =>
                            assert(r1.status == HttpStatus.OK)
                            c.sendWith(route, request)(identity).map { r2 =>
                                assert(r2.status == HttpStatus.OK)
                                Async.fill(3, 3)(c.sendWith(route, request)(identity)).map { parallel =>
                                    assert(parallel.forall(_.status == HttpStatus.OK))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end HttpClientTest
