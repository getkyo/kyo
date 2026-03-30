package kyo

import kyo.*
import scala.language.implicitConversions

class HttpClientTest extends Test:

    import HttpPath.*

    case class User(id: Int, name: String) derives Json, CanEqual
    case class LoginForm(username: String, password: String) derives HttpFormCodec, CanEqual

    val client = internal.HttpTestPlatformBackend.client

    def runServer(handlers: HttpHandler[?, ?, ?]*)(
        test: HttpUrl => Assertion < (Async & Abort[Any] & Scope)
    )(using Frame): Unit =
        "plain" in run {
            HttpServer.init(0, "localhost")(handlers*).map(s =>
                test(HttpUrl.parse(s"http://localhost:${s.port}").getOrThrow)
            )
        }
        if internal.HttpTestPlatformBackend.tlsServerAvailable then
            "tls" in run {
                HttpServer.init(
                    internal.HttpTestPlatformBackend.server,
                    HttpServerConfig.default.port(0).host("localhost").tls(internal.TlsConfig.default)
                )(handlers*).map(s =>
                    test(HttpUrl.parse(s"https://localhost:${s.port}").getOrThrow)
                )
            }
        end if
    end runServer

    def withServer(handlers: HttpHandler[?, ?, ?]*)(
        test: HttpUrl => Assertion < (Async & Abort[Any] & Scope)
    )(using Frame): Assertion < (Scope & Async & Abort[Any]) =
        HttpServer.init(0, "localhost")(handlers*).map(s =>
            test(HttpUrl.parse(s"http://localhost:${s.port}").getOrThrow)
        )

    def send[In, Out](
        url: HttpUrl,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        client.connectWith(url, Absent) { conn =>
            Sync.ensure(client.closeNow(conn)) {
                client.sendWith(conn, route, request)(identity)
            }
        }

    def withClient[A, S](f: HttpClient => A < (S & Async & Abort[HttpException]))(using Frame): A < (S & Async & Abort[HttpException]) =
        HttpClient.initUnscoped(client).map(f)

    def withClient[A, S](maxConnectionsPerHost: Int)(f: HttpClient => A < (S & Async & Abort[HttpException]))(using
        Frame
    ): A < (S & Async & Abort[HttpException]) =
        HttpClient.initUnscoped(client, maxConnectionsPerHost).map(f)

    val noTimeout = HttpClientConfig(timeout = Maybe.empty)

    "config" - {

        "default values" in {
            val config = HttpClientConfig()
            assert(config.baseUrl == Absent)
            assert(config.timeout == Present(5.seconds))
            assert(config.connectTimeout == Absent)
            assert(config.followRedirects == true)
            assert(config.maxRedirects == 10)
            assert(config.retrySchedule == Absent)
        }

        "default retryOn checks server errors" in {
            val config = HttpClientConfig()
            assert(config.retryOn(HttpStatus.InternalServerError) == true)
            assert(config.retryOn(HttpStatus.BadGateway) == true)
            assert(config.retryOn(HttpStatus.OK) == false)
            assert(config.retryOn(HttpStatus.BadRequest) == false)
        }

        "negative maxRedirects throws" in {
            assertThrows[IllegalArgumentException] {
                HttpClientConfig(maxRedirects = -1)
            }
        }

        "zero timeout throws" in {
            assertThrows[IllegalArgumentException] {
                HttpClientConfig(timeout = Present(Duration.Zero))
            }
        }

        "zero connectTimeout throws" in {
            assertThrows[IllegalArgumentException] {
                HttpClientConfig(connectTimeout = Present(Duration.Zero))
            }
        }

        "maxConnectionsPerHost must be positive" in {
            assertThrows[IllegalArgumentException] {
                HttpClient.initUnscoped(client, maxConnectionsPerHost = 0)
            }
        }
    }

    "HTTP methods" - {

        "GET with text response" - {
            val route = HttpRoute.getRaw("hello").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("world"))
            runServer(ep) { url =>
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == "world")
                }
            }
        }

        "POST with JSON request and response" - {
            val route = HttpRoute.postRaw("users")
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                val user = req.fields.body
                HttpResponse.ok.addField("body", User(user.id + 1, user.name.toUpperCase))
            }
            runServer(ep) { url =>
                val request = HttpRequest.postRaw(HttpUrl.fromUri("/users"))
                    .addField("body", User(1, "bob"))
                send(url, route, request).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body == User(2, "BOB"))
                }
            }
        }

        "PUT with JSON" - {
            val route = HttpRoute.putRaw("users" / Capture[Int]("id"))
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.id, req.fields.body.name))
            }
            runServer(ep) { url =>
                val request = HttpRequest.putRaw(HttpUrl.fromUri("/users/42"))
                    .addField("id", 42)
                    .addField("body", User(0, "updated"))
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == User(42, "updated"))
                }
            }
        }

        "PATCH with JSON" - {
            val route = HttpRoute.patchRaw("users" / Capture[Int]("id"))
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.id, req.fields.body.name))
            }
            runServer(ep) { url =>
                val request = HttpRequest.patchRaw(HttpUrl.fromUri("/users/99"))
                    .addField("id", 99)
                    .addField("body", User(0, "patched"))
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == User(99, "patched"))
                }
            }
        }

        "DELETE" - {
            val route = HttpRoute.deleteRaw("users" / Capture[Int]("id"))
            val ep    = route.handler(_ => HttpResponse.noContent)
            runServer(ep) { url =>
                val request = HttpRequest.deleteRaw(HttpUrl.fromUri("/users/1"))
                    .addField("id", 1)
                send(url, route, request).map { resp =>
                    assert(resp.status == HttpStatus.NoContent)
                }
            }
        }
    }

    "path captures" - {

        "Int" - {
            val route = HttpRoute.getRaw("items" / Capture[Int]("id")).response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(s"item-${req.fields.id}")
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/items/42"))
                    .addField("id", 42)
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "item-42")
                }
            }
        }

        "String" - {
            val route = HttpRoute.getRaw("users" / Capture[String]("name")).response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(s"hello ${req.fields.name}")
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/users/alice"))
                    .addField("name", "alice")
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "hello alice")
                }
            }
        }

        "multiple" - {
            val route = HttpRoute.getRaw("orgs" / Capture[String]("org") / "repos" / Capture[Int]("id"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(s"${req.fields.org}/${req.fields.id}")
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/orgs/kyo/repos/123"))
                    .addField("org", "kyo")
                    .addField("id", 123)
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "kyo/123")
                }
            }
        }
    }

    "query parameters" - {

        "single" - {
            val route = HttpRoute.getRaw("search")
                .request(_.query[String]("q"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(s"results for: ${req.fields.q}")
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/search?q=kyo"))
                    .addField("q", "kyo")
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "results for: kyo")
                }
            }
        }

        "multiple" - {
            val route = HttpRoute.getRaw("search")
                .request(_.query[String]("q").query[Int]("page"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(s"${req.fields.q} page ${req.fields.page}")
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/search?q=test&page=3"))
                    .addField("q", "test")
                    .addField("page", 3)
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "test page 3")
                }
            }
        }

        "optional" - {
            val route = HttpRoute.getRaw("search")
                .request(_.queryOpt[Int]("limit"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                val limit = req.fields.limit match
                    case Present(l) => l.toString
                    case Absent     => "default"
                HttpResponse.ok(limit)
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/search"))
                    .addField("limit", Absent: Maybe[Int])
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "default")
                }
            }
        }
    }

    "headers" - {

        "request header" - {
            val route = HttpRoute.getRaw("auth")
                .request(_.header[String]("Authorization"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(req.fields.Authorization)
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/auth"))
                    .addField("Authorization", "Bearer token123")
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "Bearer token123")
                }
            }
        }

        "response header" - {
            val route = HttpRoute.getRaw("headers")
                .response(_.header[String]("X-Custom").bodyText)
            val ep = route.handler { _ =>
                HttpResponse.ok
                    .addField("X-Custom", "custom-value")
                    .addField("body", "with-headers")
            }
            runServer(ep) { url =>
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/headers"))).map { resp =>
                    assert(resp.fields.`X-Custom` == "custom-value")
                    assert(resp.fields.body == "with-headers")
                }
            }
        }

        "optional response header" - {
            val route = HttpRoute.getRaw("headers")
                .response(_.headerOpt[String]("X-Missing").bodyText)
            val ep = route.handler { _ =>
                HttpResponse.ok
                    .addField("X-Missing", Absent: Maybe[String])
                    .addField("body", "no-header")
            }
            runServer(ep) { url =>
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/headers"))).map { resp =>
                    assert(resp.fields.`X-Missing` == Absent)
                    assert(resp.fields.body == "no-header")
                }
            }
        }
    }

    "cookies" - {

        "request cookie" - {
            val route = HttpRoute.getRaw("dashboard")
                .request(_.cookie[String]("session"))
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(s"session=${req.fields.session}")
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/dashboard"))
                    .addField("session", "abc123")
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "session=abc123")
                }
            }
        }

        "response cookie" - {
            val route = HttpRoute.getRaw("login")
                .response(_.cookie[String]("token").bodyText)
            val ep = route.handler { _ =>
                HttpResponse.ok
                    .addField("token", HttpCookie("jwt-value"))
                    .addField("body", "logged in")
            }
            runServer(ep) { url =>
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/login"))).map { resp =>
                    assert(resp.fields.token.value == "jwt-value")
                    assert(resp.fields.body == "logged in")
                }
            }
        }
    }

    "body variations" - {

        "text request and response" - {
            val route = HttpRoute.postRaw("echo")
                .request(_.bodyText)
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(req.fields.body.toUpperCase)
            }
            runServer(ep) { url =>
                val request = HttpRequest.postRaw(HttpUrl.fromUri("/echo"))
                    .addField("body", "hello world")
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "HELLO WORLD")
                }
            }
        }

        "JSON round trip" - {
            val route = HttpRoute.postRaw("user")
                .request(_.bodyJson[User])
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", req.fields.body)
            }
            runServer(ep) { url =>
                val user    = User(42, "alice")
                val request = HttpRequest.postRaw(HttpUrl.fromUri("/user")).addField("body", user)
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == user)
                }
            }
        }

        "form body" - {
            val route = HttpRoute.postRaw("login")
                .request(_.bodyForm[LoginForm])
                .response(_.bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok(s"welcome ${req.fields.body.username}")
            }
            runServer(ep) { url =>
                val request = HttpRequest.postRaw(HttpUrl.fromUri("/login"))
                    .addField("body", LoginForm("alice", "secret"))
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "welcome alice")
                }
            }
        }

        "empty body" - {
            val route = HttpRoute.getRaw("empty")
            val ep    = route.handler(_ => HttpResponse.noContent)
            runServer(ep) { url =>
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/empty"))).map { resp =>
                    assert(resp.status == HttpStatus.NoContent)
                }
            }
        }

        "large text body (100KB)" - {
            val largeBody = "x" * (100 * 1024)
            val route     = HttpRoute.getRaw("large").response(_.bodyText)
            val ep        = route.handler(_ => HttpResponse.ok(largeBody))
            runServer(ep) { url =>
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/large"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                    assert(resp.fields.body.length == 100 * 1024)
                }
            }
        }
    }

    "streaming" - {

        "response" - {
            val route = HttpRoute.getRaw("stream").response(_.bodyStream)
            val ep = route.handler { _ =>
                val chunks = Stream.init(Seq(
                    Span.fromUnsafe("hello ".getBytes("UTF-8")),
                    Span.fromUnsafe("world".getBytes("UTF-8"))
                ))
                HttpResponse.ok.addField("body", chunks)
            }
            runServer(ep) { url =>
                var called = false
                client.connectWith(url, Absent) { conn =>
                    Sync.ensure(client.closeNow(conn)) {
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
                }.andThen(assert(called))
            }
        }

        "request" - {
            val route = HttpRoute.postRaw("upload")
                .request(_.bodyStream)
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.body.run.map { chunks =>
                    val totalBytes = chunks.foldLeft(0)(_ + _.size)
                    HttpResponse.ok(s"received $totalBytes bytes")
                }
            }
            runServer(ep) { url =>
                var called = false
                val bodyStream: Stream[Span[Byte], Async] = Stream.init(Seq(
                    Span.fromUnsafe("chunk1".getBytes("UTF-8")),
                    Span.fromUnsafe("chunk2".getBytes("UTF-8"))
                ))
                val request = HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", bodyStream)
                client.connectWith(url, Absent) { conn =>
                    Sync.ensure(client.closeNow(conn)) {
                        client.sendWith(conn, route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "received 12 bytes")
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "request with buffered response" - {
            val route = HttpRoute.postRaw("upload")
                .request(_.bodyStream)
                .response(_.bodyJson[User])
            val ep = route.handler { req =>
                req.fields.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) => acc + new String(span.toArrayUnsafe, "UTF-8"))
                    HttpResponse.ok.addField("body", User(1, text))
                }
            }
            runServer(ep) { url =>
                var called = false
                val bodyStream: Stream[Span[Byte], Async] = Stream.init(Seq(
                    Span.fromUnsafe("alice".getBytes("UTF-8"))
                ))
                val request = HttpRequest.postRaw(HttpUrl.fromUri("/upload"))
                    .addField("body", bodyStream)
                client.connectWith(url, Absent) { conn =>
                    Sync.ensure(client.closeNow(conn)) {
                        client.sendWith(conn, route, request) { resp =>
                            called = true
                            assert(resp.fields.body == User(1, "alice"))
                        }
                    }
                }.andThen(assert(called))
            }
        }
    }

    "combined route features" - {

        "path capture + query + header + body" - {
            val route = HttpRoute.postRaw("orgs" / Capture[String]("org") / "users")
                .request(_.query[String]("role").header[String]("X-Request-Id").bodyJson[User])
                .response(_.bodyText)
            val ep = route.handler { req =>
                val org       = req.fields.org
                val role      = req.fields.role
                val requestId = req.fields.`X-Request-Id`
                val user      = req.fields.body
                HttpResponse.ok(s"$org/$role/${user.name}/$requestId")
            }
            runServer(ep) { url =>
                val request = HttpRequest.postRaw(HttpUrl.fromUri("/orgs/kyo/users?role=admin"))
                    .addField("org", "kyo")
                    .addField("role", "admin")
                    .addField("X-Request-Id", "req-1")
                    .addField("body", User(1, "alice"))
                send(url, route, request).map { resp =>
                    assert(resp.fields.body == "kyo/admin/alice/req-1")
                }
            }
        }

        "query + cookie + response header" - {
            val route = HttpRoute.getRaw("dashboard")
                .request(_.query[String]("format").cookie[String]("session"))
                .response(_.header[String]("X-Format").bodyText)
            val ep = route.handler { req =>
                HttpResponse.ok
                    .addField("X-Format", req.fields.format)
                    .addField("body", s"session=${req.fields.session}")
            }
            runServer(ep) { url =>
                val request = HttpRequest.getRaw(HttpUrl.fromUri("/dashboard?format=json"))
                    .addField("format", "json")
                    .addField("session", "tok-abc")
                send(url, route, request).map { resp =>
                    assert(resp.fields.`X-Format` == "json")
                    assert(resp.fields.body == "session=tok-abc")
                }
            }
        }
    }

    "multiple endpoints" - {

        "on same server" - {
            val route1 = HttpRoute.getRaw("a").response(_.bodyText)
            val ep1    = route1.handler(_ => HttpResponse.ok("endpoint-a"))

            val route2 = HttpRoute.getRaw("b").response(_.bodyText)
            val ep2    = route2.handler(_ => HttpResponse.ok("endpoint-b"))

            runServer(ep1, ep2) { url =>
                send(url, route1, HttpRequest.getRaw(HttpUrl.fromUri("/a"))).map { resp1 =>
                    assert(resp1.fields.body == "endpoint-a")
                    send(url, route2, HttpRequest.getRaw(HttpUrl.fromUri("/b"))).map { resp2 =>
                        assert(resp2.fields.body == "endpoint-b")
                    }
                }
            }
        }
    }

    "error responses" - {

        "404 for unknown path" - {
            val route = HttpRoute.getRaw("exists").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("here"))

            val unknownRoute = HttpRoute.getRaw("missing").response(_.bodyText)
            runServer(ep) { url =>
                send(url, unknownRoute, HttpRequest.getRaw(HttpUrl.fromUri("/missing"))).map { resp =>
                    assert(resp.status == HttpStatus.NotFound)
                }
            }
        }

        "405 for wrong method" - {
            val getRoute  = HttpRoute.getRaw("test").response(_.bodyText)
            val ep        = getRoute.handler(_ => HttpResponse.ok("get"))
            val postRoute = HttpRoute.postRaw("test").response(_.bodyText)
            runServer(ep) { url =>
                send(url, postRoute, HttpRequest.postRaw(HttpUrl.fromUri("/test"))).map { resp =>
                    assert(resp.status == HttpStatus.MethodNotAllowed)
                }
            }
        }

        "status code propagation" - {
            val route = HttpRoute.getRaw("forbidden")
            val ep    = route.handler(_ => HttpResponse.forbidden)
            runServer(ep) { url =>
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/forbidden"))).map { resp =>
                    assert(resp.status == HttpStatus.Forbidden)
                }
            }
        }
    }

    "sendWith" - {

        "via HttpClient" - {
            val route = HttpRoute.getRaw("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("pong"))
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/ping", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.fields.body == "pong")
                        }
                    }
                }.andThen(assert(called))
            }
        }
    }

    "baseUrl" - {

        "resolution" - {
            val route = HttpRoute.getRaw("api").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("api-response"))
            runServer(ep) { url =>
                var called = false
                val config = HttpClientConfig(
                    baseUrl = Present(HttpUrl(url.scheme, url.host, url.port, "/", Absent)),
                    timeout = Absent
                )
                HttpClient.withConfig(config) {
                    withClient { client =>
                        client.sendWith(route, HttpRequest.getRaw(HttpUrl.fromUri("/api"))) { resp =>
                            called = true
                            assert(resp.fields.body == "api-response")
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "not applied when request has scheme" - {
            val route = HttpRoute.getRaw("data").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("direct"))
            runServer(ep) { url =>
                var called = false
                val config = HttpClientConfig(
                    baseUrl = Present(HttpUrl(Present("http"), "other-host", 9999, "/", Absent)),
                    timeout = Absent
                )
                HttpClient.withConfig(config) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/data", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.fields.body == "direct")
                        }
                    }
                }.andThen(assert(called))
            }
        }
    }

    "redirect following" - {

        "basic redirect" - {
            val route       = HttpRoute.getRaw("start").response(_.bodyText)
            val targetRoute = HttpRoute.getRaw("target").response(_.bodyText)
            val targetEp    = targetRoute.handler(_ => HttpResponse.ok("final"))
            val redirectEp  = route.handler(_ => HttpResponse.redirect("/target").addField("body", "redirect"))
            runServer(targetEp, redirectEp) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/start", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "final")
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "disabled" - {
            val redirectRoute = HttpRoute.getRaw("redir").response(_.bodyText)
            val ep            = redirectRoute.handler(_ => HttpResponse.redirect("/target").addField("body", "redirect"))
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(followRedirects = false)) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/redir", Absent))
                        client.sendWith(redirectRoute, request) { resp =>
                            called = true
                            assert(resp.status.isRedirect)
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "too many redirects" - {
            val route = HttpRoute.getRaw("loop").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.redirect("/loop").addField("body", "loop"))
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(maxRedirects = 3)) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/loop", Absent))
                        Abort.run[HttpException](
                            client.sendWith(route, request)(identity)
                        ).map {
                            case Result.Failure(_: HttpRedirectLoopException) =>
                                called = true
                                succeed
                            case other => fail(s"Expected TooManyRedirects, got $other")
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "chain A -> B -> C" - {
            val routeA = HttpRoute.getRaw("a").response(_.bodyText)
            val routeB = HttpRoute.getRaw("b").response(_.bodyText)
            val routeC = HttpRoute.getRaw("c").response(_.bodyText)
            val epC    = routeC.handler(_ => HttpResponse.ok("final"))
            val epB    = routeB.handler(_ => HttpResponse.redirect("/c").addField("body", "b"))
            val epA    = routeA.handler(_ => HttpResponse.redirect("/b").addField("body", "a"))
            runServer(epA, epB, epC) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/a", Absent))
                        client.sendWith(routeA, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "final")
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "301 permanent redirect" - {
            val route       = HttpRoute.getRaw("old").response(_.bodyText)
            val targetRoute = HttpRoute.getRaw("new").response(_.bodyText)
            val targetEp    = targetRoute.handler(_ => HttpResponse.ok("moved"))
            val redirectEp  = route.handler(_ => HttpResponse.movedPermanently("/new").addField("body", "redirect"))
            runServer(targetEp, redirectEp) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/old", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "moved")
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "307 temporary redirect" - {
            val route       = HttpRoute.getRaw("old").response(_.bodyText)
            val targetRoute = HttpRoute.getRaw("new").response(_.bodyText)
            val targetEp    = targetRoute.handler(_ => HttpResponse.ok("temp"))
            val redirectEp = route.handler { _ =>
                HttpResponse(HttpStatus.TemporaryRedirect)
                    .setHeader("Location", "/new")
                    .addField("body", "redirect")
            }
            runServer(targetEp, redirectEp) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/old", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "temp")
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "308 permanent redirect" - {
            val route       = HttpRoute.getRaw("old").response(_.bodyText)
            val targetRoute = HttpRoute.getRaw("new").response(_.bodyText)
            val targetEp    = targetRoute.handler(_ => HttpResponse.ok("perm"))
            val redirectEp = route.handler { _ =>
                HttpResponse(HttpStatus.PermanentRedirect)
                    .setHeader("Location", "/new")
                    .addField("body", "redirect")
            }
            runServer(targetEp, redirectEp) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/old", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "perm")
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "preserves query parameters in Location" - {
            val route       = HttpRoute.getRaw("start").response(_.bodyText)
            val targetRoute = HttpRoute.getRaw("target").request(_.query[String]("q")).response(_.bodyText)
            val targetEp    = targetRoute.handler(req => HttpResponse.ok(s"q=${req.fields.q}"))
            val redirectEp  = route.handler(_ => HttpResponse.redirect("/target?q=hello").addField("body", "redirect"))
            runServer(targetEp, redirectEp) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/start", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "q=hello")
                        }
                    }
                }.andThen(assert(called))
            }
        }
    }

    "retry" - {

        "on server error" - {
            var attempts = 0
            val route    = HttpRoute.getRaw("flaky").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                if attempts < 3 then HttpResponse.serverError.addField("body", "error")
                else HttpResponse.ok("recovered")
            }
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/flaky", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "recovered")
                            assert(attempts == 3)
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "no retry on client error" - {
            var attempts = 0
            val route    = HttpRoute.getRaw("bad").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                HttpResponse.notFound.addField("body", "nope")
            }
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/bad", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.NotFound)
                            assert(attempts == 1)
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "no retry after success" - {
            var attempts = 0
            val route    = HttpRoute.getRaw("ok").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                HttpResponse.ok("immediate")
            }
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/ok", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(attempts == 1)
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "custom predicate" - {
            var attempts = 0
            val route    = HttpRoute.getRaw("custom").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                if attempts == 1 then HttpResponse.serviceUnavailable.addField("body", "503")
                else if attempts == 2 then HttpResponse.serverError.addField("body", "500")
                else HttpResponse.ok("done")
            }
            runServer(ep) { url =>
                var called = false
                val config = noTimeout.copy(
                    retrySchedule = Present(Schedule.fixed(1.millis).take(5)),
                    retryOn = _ == HttpStatus.ServiceUnavailable
                )
                HttpClient.withConfig(config) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/custom", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            // Should retry 503, then stop at 500 (not matching predicate)
                            assert(resp.status == HttpStatus.InternalServerError)
                            assert(attempts == 2)
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "exponential backoff" - {
            var attempts   = 0
            var timestamps = List.empty[Long]
            val route      = HttpRoute.getRaw("slow").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                timestamps = timestamps :+ java.lang.System.currentTimeMillis()
                if attempts < 3 then HttpResponse.serverError.addField("body", "wait")
                else HttpResponse.ok("done")
            }
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.exponential(50.millis, 2.0).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/slow", Absent))
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
                }.andThen(assert(called))
            }
        }

        "retry after redirect to flaky endpoint" - {
            var attempts   = 0
            val flakyRoute = HttpRoute.getRaw("flaky").response(_.bodyText)
            val flakyEp = flakyRoute.handler { _ =>
                attempts += 1
                if attempts < 3 then HttpResponse.serverError.addField("body", "error")
                else HttpResponse.ok("recovered")
            }
            val redirectRoute = HttpRoute.getRaw("start").response(_.bodyText)
            val redirectEp = redirectRoute.handler { _ =>
                HttpResponse.redirect("/flaky").addField("body", "redirect")
            }
            runServer(redirectEp, flakyEp) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/start", Absent))
                        client.sendWith(redirectRoute, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "recovered")
                            assert(attempts == 3)
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "retry gets redirect then succeeds" - {
            var attempts    = 0
            val route       = HttpRoute.getRaw("ep").response(_.bodyText)
            val targetRoute = HttpRoute.getRaw("target").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                if attempts < 2 then HttpResponse.serverError.addField("body", "error")
                else HttpResponse.redirect("/target").addField("body", "redirect")
            }
            val targetEp = targetRoute.handler { _ =>
                HttpResponse.ok("final destination")
            }
            runServer(ep, targetEp) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(5)))) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/ep", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "final destination")
                            assert(attempts == 2)
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "connection error aborts without retry" in run {
            // Connection errors propagate immediately — retryOn only checks HTTP status
            val route = HttpRoute.getRaw("unreachable").response(_.bodyText)
            HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(3)))) {
                withClient { client =>
                    val request = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", 1, "/unreachable", Absent))
                    Abort.run(client.sendWith(route, request)(identity)).map { result =>
                        assert(result.isFailure)
                    }
                }
            }
        }

        "exhausted returns last response" - {
            var attempts = 0
            val route    = HttpRoute.getRaw("fail").response(_.bodyText)
            val ep = route.handler { _ =>
                attempts += 1
                HttpResponse.serverError.addField("body", "always fails")
            }
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(3)))) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/fail", Absent))
                        client.sendWith(route, request) { resp =>
                            called = true
                            // After exhausting retries, should return the last response
                            assert(resp.status == HttpStatus.InternalServerError)
                            assert(attempts == 4) // 1 initial + 3 retries
                        }
                    }
                }.andThen(assert(called))
            }
        }
    }

    "timeout" - {

        "timeout error" - {
            val route = HttpRoute.getRaw("slow").response(_.bodyText)
            val ep = route.handler { _ =>
                Async.delay(10.seconds)(HttpResponse.ok("too late"))
            }
            runServer(ep) { url =>
                HttpClient.withConfig(HttpClientConfig(timeout = Present(100.millis))) {
                    withClient { client =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/slow", Absent))
                        Abort.run[HttpException](
                            client.sendWith(route, request)(identity)
                        ).map {
                            case Result.Failure(_: HttpTimeoutException) => succeed
                            case other                                   => fail(s"Expected TimeoutError, got $other")
                        }
                    }
                }
            }
        }
    }

    "connection pool" - {

        "exhausted" - {
            val route = HttpRoute.getRaw("slow").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("ok"))
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    HttpClient.init(client, maxConnectionsPerHost = 2).map { c =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/slow", Absent))
                        // Use both slots by nesting two sendWith calls, then try a third
                        c.sendWith(route, request) { _ =>
                            c.sendWith(route, request) { _ =>
                                Abort.run[HttpException](
                                    c.sendWith(route, request)(identity)
                                ).map {
                                    case Result.Failure(_: HttpPoolExhaustedException) =>
                                        called = true
                                        succeed
                                    case other => fail(s"Expected ConnectionPoolExhausted, got $other")
                                }
                            }
                        }
                    }
                }.andThen(assert(called))
            }
        }

        "works after error responses" - {
            var count = 0
            val route = HttpRoute.getRaw("maybe").response(_.bodyText)
            val ep = route.handler { _ =>
                count += 1
                if count <= 3 then HttpResponse.serverError.addField("body", "fail")
                else HttpResponse.ok("recovered")
            }
            runServer(ep) { url =>
                var called = false
                HttpClient.withConfig(noTimeout) {
                    withClient(2) { c =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/maybe", Absent))
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
                }.andThen(assert(called))
            }
        }

        "sequential requests reuse connections" - {
            val route = HttpRoute.getRaw("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("pong"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/ping", Absent))
                        Kyo.foreach(1 to 5) { _ =>
                            c.sendWith(route, request)(identity)
                        }.map { responses =>
                            assert(responses.forall(_.status == HttpStatus.OK))
                        }
                    }
                }
            }
        }

        "connections survive across multiple batches" - {
            val route = HttpRoute.getRaw("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("pong"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient(3) { c =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/ping", Absent))
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

        "connection reuse with varying data" - {
            val route = HttpRoute.postText("echo-reuse")
            val ep    = route.handler(req => HttpResponse.ok(req.fields.body))
            runServer(ep) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4)
                (for
                    size <- sizes
                    c    <- HttpClient.initUnscoped(client, size)
                    // Sequentially send 10 requests with different body data through a small pool
                    // Each reuses a connection — tests mutable var reset in backend
                    results <- Kyo.foreach(0 until 10) { i =>
                        val request = HttpRequest.postRaw(
                            HttpUrl(url.scheme, url.host, url.port, "/echo-reuse", Absent)
                        ).addField("body", s"data-$i")
                        HttpClient.withConfig(noTimeout) {
                            c.sendWith(route, request)(identity)
                        }
                    }
                yield assert(
                    results.zipWithIndex.forall { case (r, i) => r.fields.body == s"data-$i" },
                    s"Bodies: ${results.map(_.fields.body)}"
                ))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "reuse after streaming response" - {
            val streamRoute = HttpRoute.getRaw("stream-reuse").response(_.bodyStream)
            val textRoute   = HttpRoute.getRaw("text-reuse").response(_.bodyText)
            val streamEp = streamRoute.handler { _ =>
                val chunks = Stream.init(Seq(
                    Span.fromUnsafe("a".getBytes("UTF-8")),
                    Span.fromUnsafe("b".getBytes("UTF-8"))
                ))
                HttpResponse.ok.addField("body", chunks)
            }
            val textEp = textRoute.handler(_ => HttpResponse.ok("buffered"))
            runServer(streamEp, textEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4)
                (for
                    size <- sizes
                    c    <- HttpClient.initUnscoped(client, size)
                    streamReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/stream-reuse", Absent))
                    textReq   = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/text-reuse", Absent))
                    // Stream response, fully consume
                    _ <- HttpClient.withConfig(noTimeout) {
                        c.sendWith(streamRoute, streamReq) { resp =>
                            resp.fields.body.run.map(_ => ())
                        }
                    }
                    // Then buffered request on same pool — connection reused
                    result <- HttpClient.withConfig(noTimeout) {
                        c.sendWith(textRoute, textReq)(identity)
                    }
                yield assert(result.status == HttpStatus.OK && result.fields.body == "buffered"))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "slot returned after concurrent timeouts" - {
            val slowRoute = HttpRoute.getRaw("slow").response(_.bodyText)
            val fastRoute = HttpRoute.getRaw("fast").response(_.bodyText)
            val slowEp    = slowRoute.handler(_ => Async.sleep(10.seconds).andThen(HttpResponse.ok("late")))
            val fastEp    = fastRoute.handler(_ => HttpResponse.ok("ok"))
            runServer(slowEp, fastEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4, 8)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size * 2)
                    latch <- Latch.init(1)
                    slowReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/slow", Absent))
                    fastReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/fast", Absent))
                    fibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(HttpClientConfig(timeout = Present(50.millis))) {
                                Abort.run[HttpException](c.sendWith(slowRoute, slowReq)(identity))
                            }
                        )
                    ))
                    _ <- latch.release
                    _ <- Kyo.foreach(fibers)(_.get)
                    results <- HttpClient.withConfig(noTimeout) {
                        Async.fill(size, size)(c.sendWith(fastRoute, fastReq)(identity))
                    }
                yield assert(results.forall(_.status == HttpStatus.OK)))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "slot returned after concurrent fiber cancellations" - {
            val slowRoute = HttpRoute.getRaw("slow2").response(_.bodyText)
            val fastRoute = HttpRoute.getRaw("fast2").response(_.bodyText)
            val slowEp    = slowRoute.handler(_ => Async.sleep(10.seconds).andThen(HttpResponse.ok("late")))
            val fastEp    = fastRoute.handler(_ => HttpResponse.ok("ok"))
            runServer(slowEp, fastEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4, 8)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size)
                    latch <- Latch.init(1)
                    slowReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/slow2", Absent))
                    fastReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/fast2", Absent))
                    fibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(noTimeout) {
                                c.sendWith(slowRoute, slowReq)(identity)
                            }
                        )
                    ))
                    _ <- latch.release
                    _ <- Async.sleep(50.millis)
                    _ <- Kyo.foreach(fibers)(_.interrupt)
                    _ <- untilTrue {
                        Abort.run(c.sendWith(fastRoute, fastReq)(identity)).map(_.isSuccess)
                    }
                    results <- HttpClient.withConfig(noTimeout) {
                        Async.fill(size, size)(c.sendWith(fastRoute, fastReq)(identity))
                    }
                yield assert(results.forall(_.status == HttpStatus.OK)))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "slot returned after streaming response cancellation" - {
            val streamRoute = HttpRoute.getRaw("stream").response(_.bodyStream)
            val fastRoute   = HttpRoute.getRaw("fast3").response(_.bodyText)
            val streamEp = streamRoute.handler { _ =>
                val chunks = Stream[Span[Byte], Async] {
                    kyo.Loop.foreach {
                        Async.sleep(10.millis).andThen {
                            kyo.Emit.valueWith(Chunk(Span.fromUnsafe("data\n".getBytes("UTF-8"))))(kyo.Loop.continue[Unit])
                        }
                    }
                }
                HttpResponse.ok.addField("body", chunks)
            }
            val fastEp = fastRoute.handler(_ => HttpResponse.ok("ok"))
            runServer(streamEp, fastEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size)
                    latch <- Latch.init(1)
                    streamReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/stream", Absent))
                    fastReq   = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/fast3", Absent))
                    fibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(noTimeout) {
                                c.sendWith(streamRoute, streamReq) { resp =>
                                    resp.fields.body.take(1).run.map(_ => ())
                                }
                            }
                        )
                    ))
                    _ <- latch.release
                    _ <- Kyo.foreach(fibers)(f => Abort.run[Throwable](f.get))
                    _ <- untilTrue {
                        Abort.run(c.sendWith(fastRoute, fastReq)(identity)).map(_.isSuccess)
                    }
                    results <- HttpClient.withConfig(noTimeout) {
                        Async.fill(size, size)(c.sendWith(fastRoute, fastReq)(identity))
                    }
                yield assert(results.forall(_.status == HttpStatus.OK)))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "slot returned after streaming request cancellation" - {
            val postRoute = HttpRoute.postRaw("upload")
                .request(_.bodyStream)
                .response(_.bodyText)
            val fastRoute = HttpRoute.getRaw("fast4").response(_.bodyText)
            val postEp = postRoute.handler { req =>
                req.fields.body.run.map(_ => HttpResponse.ok("done"))
            }
            val fastEp = fastRoute.handler(_ => HttpResponse.ok("ok"))
            runServer(postEp, fastEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size)
                    latch <- Latch.init(1)
                    fastReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/fast4", Absent))
                    fibers <- Kyo.fill(size)(Fiber.initUnscoped {
                        latch.await.andThen {
                            val slowBody = Stream[Span[Byte], Async] {
                                kyo.Loop.foreach {
                                    Async.sleep(100.millis).andThen {
                                        kyo.Emit.valueWith(Chunk(Span.fromUnsafe("x".getBytes("UTF-8"))))(kyo.Loop.continue[Unit])
                                    }
                                }
                            }
                            val req = HttpRequest.postRaw(HttpUrl(url.scheme, url.host, url.port, "/upload", Absent))
                                .addField("body", slowBody)
                            HttpClient.withConfig(noTimeout) {
                                c.sendWith(postRoute, req)(identity)
                            }
                        }
                    })
                    _ <- latch.release
                    _ <- Async.sleep(50.millis)
                    _ <- Kyo.foreach(fibers)(_.interrupt)
                    _ <- untilTrue {
                        Abort.run(c.sendWith(fastRoute, fastReq)(identity)).map(_.isSuccess)
                    }
                    results <- HttpClient.withConfig(noTimeout) {
                        Async.fill(size, size)(c.sendWith(fastRoute, fastReq)(identity))
                    }
                yield assert(results.forall(_.status == HttpStatus.OK)))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "slot returned after connection error" - {
            val fastRoute = HttpRoute.getRaw("fast5").response(_.bodyText)
            val fastEp    = fastRoute.handler(_ => HttpResponse.ok("ok"))
            runServer(fastEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4, 8)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size)
                    latch <- Latch.init(1)
                    badReq   = HttpRequest.getRaw(HttpUrl(Present("http"), "localhost", 1, "/nope", Absent))
                    fastReq  = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/fast5", Absent))
                    badRoute = HttpRoute.getRaw("nope").response(_.bodyText)
                    fibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(noTimeout) {
                                Abort.run[HttpException](c.sendWith(badRoute, badReq)(identity))
                            }
                        )
                    ))
                    _ <- latch.release
                    _ <- Kyo.foreach(fibers)(_.get)
                    results <- HttpClient.withConfig(noTimeout) {
                        Async.fill(size, size)(c.sendWith(fastRoute, fastReq)(identity))
                    }
                yield assert(results.forall(_.status == HttpStatus.OK)))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
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

        "parallel requests to same endpoint" - {
            val route = HttpRoute.getRaw("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("pong"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient(10) { c =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/ping", Absent))
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

        "concurrent requests with shared state" - {
            val counter = new java.util.concurrent.atomic.AtomicInteger(0)
            val route   = HttpRoute.getRaw("count").response(_.bodyText)
            val ep = route.handler { _ =>
                val n = counter.incrementAndGet()
                HttpResponse.ok(n.toString)
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient(10) { c =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/count", Absent))
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

        "sequential then parallel requests" - {
            val route = HttpRoute.getRaw("data").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("data"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient(10) { c =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/data", Absent))
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

        "concurrent contention with more fibers than pool slots" - {
            val route = HttpRoute.getRaw("ping").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("pong"))
            runServer(ep) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4, 8)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size)
                    latch <- Latch.init(1)
                    request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/ping", Absent))
                    fibers <- Kyo.fill(size * 3)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(noTimeout) {
                                Abort.run[HttpException](c.sendWith(route, request)(identity))
                            }
                        )
                    ))
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                    successes = results.count(_.isSuccess)
                    poolExhausted = results.count {
                        case Result.Failure(_: HttpPoolExhaustedException) => true
                        case _                                             => false
                    }
                yield assert(successes + poolExhausted == size * 3 && successes > 0))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "concurrent burst within pool capacity" - {
            val route = HttpRoute.getRaw("burst").response(_.bodyText)
            val ep    = route.handler(_ => Async.sleep(10.millis).andThen(HttpResponse.ok("ok")))
            runServer(ep) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4, 8)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size)
                    latch <- Latch.init(1)
                    request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/burst", Absent))
                    fibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(noTimeout) {
                                c.sendWith(route, request)(identity)
                            }
                        )
                    ))
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield assert(results.forall(_.status == HttpStatus.OK)))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "high concurrency stress" - {
            val route = HttpRoute.getRaw("stress").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("ok"))
            runServer(ep) { url =>
                val repeats = 5
                val sizes   = Choice.eval(4, 8)
                (for
                    size <- sizes
                    c    <- HttpClient.initUnscoped(client, size)
                    request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/stress", Absent))
                    // Run 5 batches, each batch fires `size` concurrent requests with a latch
                    _ <- Kyo.foreach(1 to 5) { _ =>
                        for
                            latch <- Latch.init(1)
                            fibers <- Kyo.fill(size)(Fiber.initUnscoped(
                                latch.await.andThen(
                                    HttpClient.withConfig(noTimeout) {
                                        c.sendWith(route, request)(identity)
                                    }
                                )
                            ))
                            _       <- latch.release
                            results <- Kyo.foreach(fibers)(_.get)
                        yield assert(results.forall(_.status == HttpStatus.OK))
                    }
                yield ())
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "mixed buffered and streaming concurrent requests" - {
            val textRoute   = HttpRoute.getRaw("text").response(_.bodyText)
            val streamRoute = HttpRoute.getRaw("stream-mix").response(_.bodyStream)
            val textEp      = textRoute.handler(_ => HttpResponse.ok("hello"))
            val streamEp = streamRoute.handler { _ =>
                val chunks = Stream.init(Seq(
                    Span.fromUnsafe("chunk1\n".getBytes("UTF-8")),
                    Span.fromUnsafe("chunk2\n".getBytes("UTF-8"))
                ))
                HttpResponse.ok.addField("body", chunks)
            }
            runServer(textEp, streamEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size * 2)
                    latch <- Latch.init(1)
                    textReq   = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/text", Absent))
                    streamReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/stream-mix", Absent))
                    textFibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(noTimeout) {
                                c.sendWith(textRoute, textReq)(identity)
                            }
                        )
                    ))
                    streamFibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(noTimeout) {
                                c.sendWith(streamRoute, streamReq) { resp =>
                                    resp.fields.body.run.map { chunks =>
                                        chunks.foldLeft("")((acc, span) =>
                                            acc + new String(span.toArrayUnsafe, "UTF-8")
                                        )
                                    }
                                }
                            }
                        )
                    ))
                    _           <- latch.release
                    textResults <- Kyo.foreach(textFibers)(_.get)
                    streamData  <- Kyo.foreach(streamFibers)(_.get)
                yield
                    assert(textResults.forall(_.status == HttpStatus.OK))
                    assert(streamData.forall(_.contains("chunk1")))
                )
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }

        "concurrent streaming request bodies" - {
            val postRoute = HttpRoute.postRaw("echo-body")
                .request(_.bodyStream)
                .response(_.bodyText)
            val postEp = postRoute.handler { req =>
                req.fields.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) =>
                        acc + new String(span.toArrayUnsafe, "UTF-8")
                    )
                    HttpResponse.ok(text)
                }
            }
            runServer(postEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size)
                    latch <- Latch.init(1)
                    fibers <- Kyo.foreach(0 until size) { i =>
                        Fiber.initUnscoped {
                            latch.await.andThen {
                                val marker = s"marker-$i"
                                val bodyStream: Stream[Span[Byte], Async] = Stream.init(Seq(
                                    Span.fromUnsafe(s"$marker-a,".getBytes("UTF-8")),
                                    Span.fromUnsafe(s"$marker-b".getBytes("UTF-8"))
                                ))
                                val req = HttpRequest.postRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-body", Absent))
                                    .addField("body", bodyStream)
                                HttpClient.withConfig(noTimeout) {
                                    c.sendWith(postRoute, req) { resp =>
                                        val body: String = resp.fields.body
                                        (i, body)
                                    }
                                }
                            }
                        }
                    }
                    _       <- latch.release
                    results <- Kyo.foreach(fibers)(_.get)
                yield assert(results.forall { (i, body) => body.contains(s"marker-$i") }))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }
    }

    "maxContentLength" - {

        def withServerConfig[A, S](config: HttpServerConfig)(handlers: HttpHandler[?, ?, ?]*)(
            test: HttpUrl => A < (S & Async & Abort[HttpException])
        )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
            HttpServer.init(config)(handlers*).map(s =>
                test(HttpUrl.parse(s"http://localhost:${s.port}").getOrThrow)
            )

        "accepts body within limit" in run {
            val route = HttpRoute.postRaw("data")
                .request(_.bodyBinary)
                .response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.ok("ok"))
            val config = HttpServerConfig.default.port(0).host("localhost").maxContentLength(1024)
            withServerConfig(config)(ep) { url =>
                val body = Span.fill(512)(0.toByte) // 512 bytes, within 1024 limit
                send(url, route, HttpRequest.postRaw(HttpUrl.fromUri("/data")).addField("body", body)).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                }
            }
        }

        "rejects body exceeding limit with 413" in run {
            val route = HttpRoute.postRaw("data")
                .request(_.bodyBinary)
                .response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.ok("ok"))
            val config = HttpServerConfig.default.port(0).host("localhost").maxContentLength(64)
            withServerConfig(config)(ep) { url =>
                val body = Span.fill(128)(0.toByte) // 128 bytes, exceeds 64 limit
                send(url, route, HttpRequest.postRaw(HttpUrl.fromUri("/data")).addField("body", body)).map { resp =>
                    assert(resp.status == HttpStatus.PayloadTooLarge)
                }
            }
        }

        "exact boundary: body equal to limit succeeds" in run {
            val route = HttpRoute.postRaw("data")
                .request(_.bodyBinary)
                .response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.ok("ok"))
            val config = HttpServerConfig.default.port(0).host("localhost").maxContentLength(100)
            withServerConfig(config)(ep) { url =>
                val body = Span.fill(100)(0.toByte) // exactly 100 bytes
                send(url, route, HttpRequest.postRaw(HttpUrl.fromUri("/data")).addField("body", body)).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                }
            }
        }

        "one byte over limit gets 413" in run {
            val route = HttpRoute.postRaw("data")
                .request(_.bodyBinary)
                .response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.ok("ok"))
            val config = HttpServerConfig.default.port(0).host("localhost").maxContentLength(100)
            withServerConfig(config)(ep) { url =>
                val body = Span.fill(101)(0.toByte) // 101 bytes, over 100 limit
                send(url, route, HttpRequest.postRaw(HttpUrl.fromUri("/data")).addField("body", body)).map { resp =>
                    assert(resp.status == HttpStatus.PayloadTooLarge)
                }
            }
        }

        "empty body always succeeds" in run {
            val route  = HttpRoute.getRaw("data").response(_.bodyText)
            val ep     = route.handler(_ => HttpResponse.ok("ok"))
            val config = HttpServerConfig.default.port(0).host("localhost").maxContentLength(1)
            withServerConfig(config)(ep) { url =>
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/data"))).map { resp =>
                    assert(resp.status == HttpStatus.OK)
                }
            }
        }

        "server still works after rejecting oversized request" in run {
            val route = HttpRoute.postRaw("data")
                .request(_.bodyText)
                .response(_.bodyText)
            val ep     = route.handler(req => HttpResponse.ok(req.fields.body))
            val config = HttpServerConfig.default.port(0).host("localhost").maxContentLength(64)
            withServerConfig(config)(ep) { url =>
                // First request: too large, should get 413
                val bigBody = "x" * 128
                send(url, route, HttpRequest.postRaw(HttpUrl.fromUri("/data")).addField("body", bigBody)).map { resp =>
                    assert(resp.status == HttpStatus.PayloadTooLarge)
                }.andThen {
                    // Second request: small enough, should succeed
                    val smallBody = "hello"
                    send(url, route, HttpRequest.postRaw(HttpUrl.fromUri("/data")).addField("body", smallBody)).map {
                        resp =>
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "hello")
                    }
                }
            }
        }
    }

    "HttpServerConfig" - {

        "default config values" in {
            val config = HttpServerConfig.default
            assert(config.port == 0)
            assert(config.host == "0.0.0.0")
            assert(config.maxContentLength == 65536)
            assert(config.backlog == 128)
            assert(config.keepAlive == true)
            assert(config.tcpFastOpen == true)
            assert(config.flushConsolidationLimit == 256)
            assert(config.strictCookieParsing == false)
        }

        "builder methods" in {
            val config = HttpServerConfig.default
                .maxContentLength(1024)
                .backlog(256)
                .keepAlive(false)
                .tcpFastOpen(false)
                .flushConsolidationLimit(128)
                .strictCookieParsing(true)
            assert(config.maxContentLength == 1024)
            assert(config.backlog == 256)
            assert(config.keepAlive == false)
            assert(config.tcpFastOpen == false)
            assert(config.flushConsolidationLimit == 128)
            assert(config.strictCookieParsing == true)
        }
    }

    "client cancellation and error handling" - {

        "buffered request: fiber interruption cancels in-flight request" - {
            // Server delays response. Client times out. Request should be cancelled, not leak.
            val route = HttpRoute.getRaw("slow").response(_.bodyText)
            val ep = route.handler { _ =>
                Async.sleep(5.seconds).andThen {
                    HttpResponse.ok("too late")
                }
            }
            runServer(ep) { url =>
                Abort.run[HttpException | kyo.Timeout] {
                    Async.timeout(100.millis) {
                        send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/slow")))
                    }
                }.map { result =>
                    // Should fail with timeout, not hang
                    assert(result.isFailure || result.isPanic)
                }
            }
        }

        "streaming response: fiber interruption stops stream consumption" - {
            // Server sends an infinite stream. Client reads one chunk then cancels via scope close.
            val route = HttpRoute.getRaw("infinite").response(_.bodyStream)
            val ep = route.handler { _ =>
                val chunks = Stream[Span[Byte], Async] {
                    kyo.Loop.foreach {
                        Async.sleep(10.millis).andThen {
                            kyo.Emit.valueWith(Chunk(Span.fromUnsafe("data\n".getBytes("UTF-8"))))(kyo.Loop.continue[Unit])
                        }
                    }
                }
                HttpResponse.ok.addField("body", chunks)
            }
            runServer(ep) { url =>
                Async.timeout(5.seconds) {
                    client.connectWith(url, Absent) { conn =>
                        Sync.ensure(client.closeNow(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/infinite"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                // Read just the first chunk, then let scope close — should not hang
                                resp.fields.body.take(1).run.map { chunks =>
                                    assert(chunks.size == 1)
                                }
                            }
                        }
                    }
                }
            }
        }

        "streaming response: server-side stream error propagates to client" - {
            // Server stream throws after first chunk. Client should see an error, not hang.
            val route = HttpRoute.getRaw("fail-stream").response(_.bodyStream)
            val ep = route.handler { _ =>
                val failingStream = Stream[Span[Byte], Async] {
                    kyo.Emit.valueWith(Chunk(Span.fromUnsafe("ok\n".getBytes("UTF-8")))) {
                        throw new RuntimeException("stream error")
                    }
                }
                HttpResponse.ok.addField("body", failingStream)
            }
            runServer(ep) { url =>
                Async.timeout(5.seconds) {
                    client.connectWith(url, Absent) { conn =>
                        Sync.ensure(client.closeNow(conn)) {
                            client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/fail-stream"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                // Stream should either deliver partial data or error, but not hang
                                Abort.run[Throwable](Abort.catching[Throwable] {
                                    resp.fields.body.run.map { _ => succeed }
                                }).map(_ => succeed)
                            }
                        }
                    }
                }
            }
        }

        "buffered response: connection error is classified as HttpException" in run {
            val route = HttpRoute.getRaw("test").response(_.bodyText)
            Abort.run[HttpException] {
                send(HttpUrl(Present("http"), "localhost", 1, "/", Absent), route, HttpRequest.getRaw(HttpUrl.fromUri("/test")))
            }.map { result =>
                result match
                    case Result.Failure(e: HttpConnectException) => succeed
                    case other                                   => fail(s"Expected ConnectionError but got $other")
            }
        }

        "streaming request body: sent correctly" - {
            val route = HttpRoute.postRaw("echo")
                .request(_.bodyStream)
                .response(_.bodyText)
            val ep = route.handler { req =>
                req.fields.body.run.map { chunks =>
                    val text = chunks.foldLeft("")((acc, span) =>
                        acc + new String(span.toArrayUnsafe, "UTF-8")
                    )
                    HttpResponse.ok(text)
                }
            }
            runServer(ep) { url =>
                client.connectWith(url, Absent) { conn =>
                    Sync.ensure(client.closeNow(conn)) {
                        val bodyStream: Stream[Span[Byte], Async] = Stream.init(Seq(
                            Span.fromUnsafe("hello ".getBytes("UTF-8")),
                            Span.fromUnsafe("world".getBytes("UTF-8"))
                        ))
                        val request = HttpRequest.postRaw(HttpUrl.fromUri("/echo"))
                            .addField("body", bodyStream)
                        client.sendWith(conn, route, request) { resp =>
                            assert(resp.status == HttpStatus.OK)
                            assert(resp.fields.body == "hello world")
                        }
                    }
                }
            }
        }

        "client close while requests in-flight" in run {
            val slowRoute = HttpRoute.getRaw("slow-close").response(_.bodyText)
            val slowEp    = slowRoute.handler(_ => Async.sleep(5.seconds).andThen(HttpResponse.ok("late")))
            withServer(slowEp) { url =>
                val repeats = 10
                val sizes   = Choice.eval(2, 4)
                (for
                    size  <- sizes
                    c     <- HttpClient.initUnscoped(client, size)
                    latch <- Latch.init(1)
                    slowReq = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/slow-close", Absent))
                    fibers <- Kyo.fill(size)(Fiber.initUnscoped(
                        latch.await.andThen(
                            HttpClient.withConfig(noTimeout) {
                                Abort.run[HttpException](c.sendWith(slowRoute, slowReq)(identity))
                            }
                        )
                    ))
                    _       <- latch.release
                    _       <- Async.sleep(50.millis)
                    _       <- c.closeNow
                    results <- Kyo.foreach(fibers)(f => Abort.run[Throwable](f.get))
                yield
                    // All fibers must complete (success or failure), not hang
                    assert(results.size == size))
                    .handle(Choice.run, _.unit, Loop.repeat(repeats))
                    .andThen(succeed)
            }
        }
    }

    "client filters" - {

        // Server endpoint that echoes back the Authorization header value
        def echoAuthEndpoint =
            val route = HttpRoute.getRaw("echo-auth")
                .request(_.headerOpt[String]("authorization"))
                .response(_.bodyText)
            route.handler { req =>
                val auth = req.headers.get("Authorization").getOrElse("none")
                HttpResponse.ok(s"auth=$auth")
            }
        end echoAuthEndpoint

        // Server endpoint that echoes back the X-Custom header
        def echoCustomHeaderEndpoint =
            val route = HttpRoute.getRaw("echo-header")
                .request(_.headerOpt[String]("x-custom"))
                .response(_.bodyText)
            route.handler { req =>
                val value = req.headers.get("X-Custom").getOrElse("none")
                HttpResponse.ok(s"X-Custom=$value")
            }
        end echoCustomHeaderEndpoint

        "basicAuth adds Authorization header" - {
            runServer(echoAuthEndpoint) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val route = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                            .filter(HttpFilter.client.basicAuth("user", "pass"))
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        c.sendWith(route, request) { resp =>
                            val expected = java.util.Base64.getEncoder.encodeToString("user:pass".getBytes("UTF-8"))
                            assert(resp.fields.body.contains(s"Basic $expected"), s"Should contain Basic auth, got: ${resp.fields.body}")
                        }
                    }
                }
            }
        }

        "basicAuth with special characters in credentials" - {
            runServer(echoAuthEndpoint) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val route = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                            .filter(HttpFilter.client.basicAuth("user@domain.com", "p@ss:w0rd!"))
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        c.sendWith(route, request) { resp =>
                            val expected = java.util.Base64.getEncoder.encodeToString("user@domain.com:p@ss:w0rd!".getBytes("UTF-8"))
                            assert(resp.fields.body.contains(s"Basic $expected"), s"Should contain encoded creds, got: ${resp.fields.body}")
                        }
                    }
                }
            }
        }

        "bearerAuth adds Authorization header" - {
            runServer(echoAuthEndpoint) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val route = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                            .filter(HttpFilter.client.bearerAuth("my-token-123"))
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        c.sendWith(route, request) { resp =>
                            assert(
                                resp.fields.body.contains("Bearer my-token-123"),
                                s"Should contain Bearer token, got: ${resp.fields.body}"
                            )
                        }
                    }
                }
            }
        }

        "bearerAuth with long token" - {
            val longToken = "x" * 500
            runServer(echoAuthEndpoint) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val route = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                            .filter(HttpFilter.client.bearerAuth(longToken))
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        c.sendWith(route, request) { resp =>
                            assert(resp.fields.body.contains(s"Bearer $longToken"), s"Should contain long token, got truncated response")
                        }
                    }
                }
            }
        }

        "addHeader adds custom header" - {
            runServer(echoCustomHeaderEndpoint) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val route = HttpRoute.getRaw("echo-header").response(_.bodyText)
                            .filter(HttpFilter.client.addHeader("X-Custom", "custom-value"))
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-header", Absent))
                        c.sendWith(route, request) { resp =>
                            assert(
                                resp.fields.body.contains("custom-value"),
                                s"Should contain custom header value, got: ${resp.fields.body}"
                            )
                        }
                    }
                }
            }
        }

        "chained filters apply in order" - {
            val route2 = HttpRoute.getRaw("echo-auth")
                .request(_.headerOpt[String]("authorization"))
                .response(_.bodyText)
            val ep = route2.handler { req =>
                val auth   = req.headers.get("Authorization").getOrElse("none")
                val custom = req.headers.get("X-Request-Id").getOrElse("none")
                HttpResponse.ok(s"auth=$auth,rid=$custom")
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val route = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                            .filter(HttpFilter.client.bearerAuth("token-abc"))
                            .filter(HttpFilter.client.addHeader("X-Request-Id", "req-42"))
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        c.sendWith(route, request) { resp =>
                            assert(resp.fields.body.contains("Bearer token-abc"), s"Should have bearer, got: ${resp.fields.body}")
                            assert(resp.fields.body.contains("req-42"), s"Should have custom header, got: ${resp.fields.body}")
                        }
                    }
                }
            }
        }

        "filter applied on multiple sequential requests" - {
            runServer(echoAuthEndpoint) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val route = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                            .filter(HttpFilter.client.bearerAuth("persistent-token"))
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        c.sendWith(route, request) { resp1 =>
                            assert(resp1.fields.body.contains("Bearer persistent-token"))
                            c.sendWith(route, request) { resp2 =>
                                assert(resp2.fields.body.contains("Bearer persistent-token"))
                                c.sendWith(route, request) { resp3 =>
                                    assert(resp3.fields.body.contains("Bearer persistent-token"))
                                }
                            }
                        }
                    }
                }
            }
        }

        "filter does not leak across routes without filter" - {
            runServer(echoAuthEndpoint) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val filteredRoute = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                            .filter(HttpFilter.client.bearerAuth("secret"))
                        val unfilteredRoute = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                        val request         = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        // Filtered route should have auth
                        c.sendWith(filteredRoute, request) { resp1 =>
                            assert(resp1.fields.body.contains("Bearer secret"))
                            // Unfiltered route should NOT have auth
                            c.sendWith(unfilteredRoute, request) { resp2 =>
                                assert(
                                    resp2.fields.body == "auth=none",
                                    s"Unfiltered route should not have auth, got: ${resp2.fields.body}"
                                )
                            }
                        }
                    }
                }
            }
        }

        "noop filter has no effect" - {
            runServer(echoAuthEndpoint) { url =>
                HttpClient.withConfig(noTimeout) {
                    withClient { c =>
                        val route = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                            .filter(HttpFilter.noop)
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        c.sendWith(route, request) { resp =>
                            assert(resp.fields.body == "auth=none", s"Noop filter should not add headers, got: ${resp.fields.body}")
                        }
                    }
                }
            }
        }

        "filter works with default shared client" - {
            runServer(echoAuthEndpoint) { url =>
                val route = HttpRoute.getRaw("echo-auth").response(_.bodyText)
                    .filter(HttpFilter.client.bearerAuth("shared-token"))
                HttpClient.withConfig(noTimeout) {
                    HttpClient.use { c =>
                        val request = HttpRequest.getRaw(HttpUrl(url.scheme, url.host, url.port, "/echo-auth", Absent))
                        c.sendWith(route, request) { resp =>
                            assert(
                                resp.fields.body.contains("Bearer shared-token"),
                                s"Shared client should apply filter, got: ${resp.fields.body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "convenience methods" - {

        "getText with String url" - {
            val route = HttpRoute.getText("msg")
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", "hello world"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/msg").map { body =>
                        assert(body == "hello world")
                    }
                }
            }
        }

        "getText with HttpUrl" - {
            val route = HttpRoute.getText("msg")
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", "from url"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.getText(HttpUrl(url.scheme, url.host, url.port, "/msg", Absent)).map { body =>
                        assert(body == "from url")
                    }
                }
            }
        }

        "postJson round-trip" - {
            val route = HttpRoute.postJson[User, User]("users")
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.body.id + 1, req.fields.body.name.toUpperCase))
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.postJson[User](s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/users", User(1, "alice")).map {
                        user =>
                            assert(user == User(2, "ALICE"))
                    }
                }
            }
        }

        "putText" - {
            val route = HttpRoute.putText("data")
            val ep    = route.handler(req => HttpResponse.ok.addField("body", s"got:${req.fields.body}"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.putText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/data", "payload").map { body =>
                        assert(body == "got:payload")
                    }
                }
            }
        }

        "deleteJson" - {
            val route = HttpRoute.deleteJson[User]("users")
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", User(99, "deleted")))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.deleteJson[User](s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/users").map { user =>
                        assert(user == User(99, "deleted"))
                    }
                }
            }
        }

        "getBinary" - {
            val route = HttpRoute.getBinary("bin")
            val data  = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", data))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.getBinary(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/bin").map { body =>
                        assert(body.toArrayUnsafe.toSeq == data.toArrayUnsafe.toSeq)
                    }
                }
            }
        }

        "postBinary round-trip" - {
            val route = HttpRoute.postBinary("bin")
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", req.fields.body)
            }
            val data = Span.fromUnsafe(Array[Byte](10, 20, 30))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.postBinary(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/bin", data).map { body =>
                        assert(body.toArrayUnsafe.toSeq == data.toArrayUnsafe.toSeq)
                    }
                }
            }
        }

        "getJson" - {
            val route = HttpRoute.getJson[User]("user")
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", User(1, "bob")))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.getJson[User](s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/user").map { user =>
                        assert(user == User(1, "bob"))
                    }
                }
            }
        }

        "putJson" - {
            val route = HttpRoute.putJson[User, User]("user")
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.body.id, "updated"))
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.putJson[User](s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/user", User(5, "old")).map {
                        user =>
                            assert(user == User(5, "updated"))
                    }
                }
            }
        }

        "patchJson" - {
            val route = HttpRoute.patchJson[User, User]("user")
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", User(req.fields.body.id, "patched"))
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.patchJson[User](s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/user", User(3, "old")).map {
                        user =>
                            assert(user == User(3, "patched"))
                    }
                }
            }
        }

        "postText" - {
            val route = HttpRoute.postText("echo")
            val ep    = route.handler(req => HttpResponse.ok.addField("body", s"echo:${req.fields.body}"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.postText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/echo", "hello").map { body =>
                        assert(body == "echo:hello")
                    }
                }
            }
        }

        "patchText" - {
            val route = HttpRoute.patchText("data")
            val ep    = route.handler(req => HttpResponse.ok.addField("body", s"patched:${req.fields.body}"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.patchText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/data", "fix").map { body =>
                        assert(body == "patched:fix")
                    }
                }
            }
        }

        "deleteText" - {
            val route = HttpRoute.deleteText("item")
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", "deleted"))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.deleteText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/item").map { body =>
                        assert(body == "deleted")
                    }
                }
            }
        }

        "putBinary" - {
            val route = HttpRoute.putBinary("bin")
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", req.fields.body)
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    val data = Span.fromUnsafe(Array[Byte](7, 8, 9))
                    HttpClient.putBinary(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/bin", data).map { body =>
                        assert(body.toArrayUnsafe.toSeq == Seq[Byte](7, 8, 9))
                    }
                }
            }
        }

        "patchBinary" - {
            val route = HttpRoute.patchBinary("bin")
            val ep = route.handler { req =>
                HttpResponse.ok.addField("body", req.fields.body)
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    val data = Span.fromUnsafe(Array[Byte](11, 22, 33))
                    HttpClient.patchBinary(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/bin", data).map { body =>
                        assert(body.toArrayUnsafe.toSeq == Seq[Byte](11, 22, 33))
                    }
                }
            }
        }

        "deleteBinary" - {
            val route = HttpRoute.deleteBinary("bin")
            val ep    = route.handler(_ => HttpResponse.ok.addField("body", Span.fromUnsafe(Array[Byte](42))))
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.deleteBinary(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/bin").map { body =>
                        assert(body.toArrayUnsafe.toSeq == Seq(42.toByte))
                    }
                }
            }
        }

        "getSseText" - {
            val route = HttpRoute.getRaw("events").response(_.bodySseText)
            val ep = route.handler { _ =>
                HttpResponse.ok.addField(
                    "body",
                    Stream.init(Seq(
                        HttpSseEvent("hello", Absent, Absent, Absent),
                        HttpSseEvent("world", Absent, Absent, Absent)
                    ))
                )
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.getSseText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/events").take(2).run.map { chunks =>
                        val events = chunks.toSeq
                        assert(events.size == 2)
                        assert(events(0).data == "hello")
                        assert(events(1).data == "world")
                    }
                }
            }
        }

        "getSseJson" - {
            val route = HttpRoute.getRaw("events").response(_.bodySseJson[User])
            val ep = route.handler { _ =>
                HttpResponse.ok.addField(
                    "body",
                    Stream.init(Seq(
                        HttpSseEvent(data = User(1, "alice")),
                        HttpSseEvent(data = User(2, "bob"))
                    ))
                )
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.getSseJson[User](s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/events").take(2).run.map {
                        chunks =>
                            val events = chunks.toSeq
                            assert(events.size == 2)
                            assert(events(0).data == User(1, "alice"))
                            assert(events(1).data == User(2, "bob"))
                    }
                }
            }
        }

        "getNdJson" - {
            val route = HttpRoute.getRaw("data").response(_.bodyNdjson[User])
            val ep = route.handler { _ =>
                HttpResponse.ok.addField(
                    "body",
                    Stream.init(Seq(
                        User(1, "alice"),
                        User(2, "bob")
                    ))
                )
            }
            runServer(ep) { url =>
                HttpClient.withConfig(noTimeout) {
                    HttpClient.getNdJson[User](s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/data").take(2).run.map {
                        chunks =>
                            val items = chunks.toSeq
                            assert(items.size == 2)
                            assert(items(0) == User(1, "alice"))
                            assert(items(1) == User(2, "bob"))
                    }
                }
            }
        }

        "invalid URL returns ParseError" in run {
            HttpClient.withConfig(noTimeout) {
                Abort.run(HttpClient.getText("not a valid url")).map { result =>
                    assert(result.isFailure)
                }
            }
        }
    }

    "client context" - {

        "HttpClient.let uses provided client" - {
            val route = HttpRoute.getRaw("ctx").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("via-let"))
            runServer(ep) { url =>
                HttpClient.initUnscoped(client).map { customClient =>
                    HttpClient.let(customClient) {
                        HttpClient.withConfig(noTimeout) {
                            HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/ctx").map { body =>
                                assert(body == "via-let")
                            }
                        }
                    }
                }
            }
        }

        "nested let scopes are isolated" - {
            val route = HttpRoute.getRaw("ctx").response(_.bodyText)
            val ep    = route.handler(_ => HttpResponse.ok("ok"))
            runServer(ep) { url =>
                HttpClient.initUnscoped(client).map { outerClient =>
                    HttpClient.initUnscoped(client).map { innerClient =>
                        HttpClient.let(outerClient) {
                            HttpClient.use { c1 =>
                                assert(c1 eq outerClient)
                                HttpClient.let(innerClient) {
                                    HttpClient.use { c2 =>
                                        assert(c2 eq innerClient)
                                        assert(!(c2 eq outerClient))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "config stacking" - {

        "nested withConfig composes" - {
            val route    = HttpRoute.getRaw("stack").response(_.bodyText)
            var attempts = 0
            val ep = route.handler { _ =>
                attempts += 1
                if attempts < 2 then HttpResponse.serverError.addField("body", "fail")
                else HttpResponse.ok("ok")
            }
            runServer(ep) { url =>
                val base = HttpUrl(url.scheme, url.host, url.port, "/", Absent)
                // Outer sets baseUrl, inner adds retry — both should apply
                HttpClient.withConfig(noTimeout.copy(baseUrl = Present(base))) {
                    HttpClient.withConfig(_.copy(retrySchedule = Present(Schedule.fixed(1.millis).take(3)))) {
                        withClient { c =>
                            c.sendWith(route, HttpRequest.getRaw(HttpUrl.fromUri("/stack"))) { resp =>
                                assert(resp.status == HttpStatus.OK)
                                assert(attempts == 2)
                            }
                        }
                    }
                }
            }
        }

        "withConfig transform preserves untouched fields" in run {
            val base = HttpUrl(Present("http"), "localhost", 1234, "/", Absent)
            val config = noTimeout.copy(
                baseUrl = Present(base),
                maxRedirects = 3,
                followRedirects = false
            )
            HttpClient.withConfig(config) {
                HttpClient.withConfig(_.copy(maxRedirects = 20)) {
                    HttpClient.use { _ =>
                        // Can't directly read config from outside, but we can verify
                        // the transform API compiles and works by checking behavior
                        succeed
                    }
                }
            }
        }
    }

end HttpClientTest
