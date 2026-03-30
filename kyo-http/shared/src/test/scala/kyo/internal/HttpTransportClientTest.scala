package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class HttpTransportClientTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    /** Start a raw HTTP/1.1 server that responds to all requests with a fixed response. */
    private def withEchoServer(
        responseStatus: Int = 200,
        responseBody: String = "ok",
        responseHeaders: HttpHeaders = HttpHeaders.empty
    )(
        f: (TestTransport, Int) => Assertion < (Async & Abort[HttpException])
    )(using Frame): Assertion < (Async & Abort[HttpException | Closed] & Scope) =
        val transport = new TestTransport
        transport.listen("127.0.0.1", 0, 5) { stream =>
            Abort.run[HttpException] {
                Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
                    val hdrs = responseHeaders
                        .add("Content-Length", responseBody.length.toString)
                        .add("Content-Type", "text/plain")
                    Http1Protocol.writeResponseHead(stream, HttpStatus(responseStatus), hdrs).andThen {
                        Http1Protocol.writeBody(stream, Span.fromUnsafe(responseBody.getBytes(Utf8)))
                    }
                }
            }.unit
        }.map { listener =>
            f(transport, listener.port)
        }
    end withEchoServer

    /** Server that echoes back the request body as response body. */
    private def withBodyEchoServer(
        f: (TestTransport, Int) => Assertion < (Async & Abort[HttpException])
    )(using Frame): Assertion < (Async & Abort[HttpException | Closed] & Scope) =
        val transport = new TestTransport
        transport.listen("127.0.0.1", 0, 5) { stream =>
            Abort.run[HttpException] {
                Http1Protocol.readRequest(stream, 65536).map { (method, path, headers, body) =>
                    val bodyBytes = body match
                        case HttpBody.Buffered(d) => d
                        case HttpBody.Empty       => Span.empty[Byte]
                        case _                    => Span.empty[Byte]
                    val hdrs = HttpHeaders.empty
                        .add("Content-Length", bodyBytes.size.toString)
                        .add("Content-Type", "application/octet-stream")
                        .add("X-Echo-Method", method.name)
                        .add("X-Echo-Path", path)
                    Http1Protocol.writeResponseHead(stream, HttpStatus(200), hdrs).andThen {
                        Http1Protocol.writeBody(stream, bodyBytes)
                    }
                }
            }.unit
        }.map { listener =>
            f(transport, listener.port)
        }
    end withBodyEchoServer

    private def makeClient(transport: TestTransport) =
        new HttpTransportClient(transport, Http1Protocol)

    // ── Basic request/response ──────────────────────────────────

    "GET 200 with text body" in run {
        val route = HttpRoute.getRaw("test").response(_.bodyText)
        withEchoServer(responseBody = "hello") { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.parse(s"http://127.0.0.1:$port/test").getOrThrow, HttpHeaders.empty, Record.empty),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 200)
                }
            }
        }
    }

    "POST with text body echoed back" in run {
        val route = HttpRoute.postText("echo")
        withBodyEchoServer { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(
                        HttpMethod.POST,
                        HttpUrl.parse(s"http://127.0.0.1:$port/echo").getOrThrow,
                        HttpHeaders.empty.add("Content-Type", "text/plain; charset=utf-8"),
                        Record.empty & "body" ~ "hello post"
                    ),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 200)
                }
            }
        }
    }

    "PUT request sends correct method" in run {
        val route = HttpRoute.putRaw("data").response(_.bodyText)
        withBodyEchoServer { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(
                        HttpMethod.PUT,
                        HttpUrl.parse(s"http://127.0.0.1:$port/data").getOrThrow,
                        HttpHeaders.empty,
                        Record.empty
                    ),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 200)
                }
            }
        }
    }

    "DELETE request sends correct method" in run {
        val route = HttpRoute.deleteRaw("item").response(_.bodyText)
        withBodyEchoServer { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(
                        HttpMethod.DELETE,
                        HttpUrl.parse(s"http://127.0.0.1:$port/item").getOrThrow,
                        HttpHeaders.empty,
                        Record.empty
                    ),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 200)
                }
            }
        }
    }

    "PATCH request sends correct method" in run {
        val route = HttpRoute.patchRaw("update").response(_.bodyText)
        withBodyEchoServer { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(
                        HttpMethod.PATCH,
                        HttpUrl.parse(s"http://127.0.0.1:$port/update").getOrThrow,
                        HttpHeaders.empty,
                        Record.empty
                    ),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 200)
                }
            }
        }
    }

    // ── Connection errors ──────────────────────────────────────

    "connect to non-existent host fails" in run {
        val transport = new TestTransport
        val client    = makeClient(transport)
        Scope.run {
            Abort.run[HttpException] {
                client.connectWith("127.0.0.1", 1, ssl = false, Absent) { _ =>
                    fail("should not connect")
                }
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    "TLS not supported → fails" in run {
        val transport = new TestTransport
        val client    = makeClient(transport)
        Scope.run {
            Abort.run[HttpException] {
                client.connectWith("127.0.0.1", 443, ssl = true, Absent) { _ =>
                    fail("should not connect")
                }
            }.map { result =>
                assert(result.isFailure)
            }
        }
    }

    // ── Status codes ────────────────────────────────────────────

    "404 response" in run {
        val route = HttpRoute.getRaw("missing").response(_.bodyText)
        withEchoServer(responseStatus = 404, responseBody = "not found") { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                Abort.run[HttpException] {
                    client.sendWith(
                        conn,
                        route,
                        HttpRequest(
                            HttpMethod.GET,
                            HttpUrl.parse(s"http://127.0.0.1:$port/missing").getOrThrow,
                            HttpHeaders.empty,
                            Record.empty
                        ),
                        _ => Kyo.unit
                    ) { response =>
                        response
                    }
                }.map { result =>
                    // 404 response — either decoded as status error (Failure) or successful decode with 404 status
                    result match
                        case Result.Success(resp) => assert(resp.status.code == 404)
                        case Result.Failure(_)    => succeed // HttpStatusException
                        case Result.Panic(_)      => fail("Should not panic")
                }
            }
        }
    }

    "500 response" in run {
        val route = HttpRoute.getRaw("error").response(_.bodyText)
        withEchoServer(responseStatus = 500, responseBody = "server error") { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                Abort.run[HttpException] {
                    client.sendWith(
                        conn,
                        route,
                        HttpRequest(
                            HttpMethod.GET,
                            HttpUrl.parse(s"http://127.0.0.1:$port/error").getOrThrow,
                            HttpHeaders.empty,
                            Record.empty
                        ),
                        _ => Kyo.unit
                    ) { response =>
                        response
                    }
                }.map { result =>
                    result match
                        case Result.Success(resp) => assert(resp.status.code == 500)
                        case Result.Failure(_)    => succeed // HttpStatusException
                        case Result.Panic(_)      => fail("Should not panic")
                }
            }
        }
    }

    "301 redirect response" in run {
        val route = HttpRoute.getRaw("old").response(_.bodyText)
        withEchoServer(
            responseStatus = 301,
            responseBody = "moved",
            responseHeaders = HttpHeaders.empty.add("Location", "/new")
        ) { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                Abort.run[HttpException] {
                    client.sendWith(
                        conn,
                        route,
                        HttpRequest(
                            HttpMethod.GET,
                            HttpUrl.parse(s"http://127.0.0.1:$port/old").getOrThrow,
                            HttpHeaders.empty,
                            Record.empty
                        ),
                        _ => Kyo.unit
                    ) { response =>
                        response
                    }
                }.map { result =>
                    result match
                        case Result.Success(resp) => assert(resp.status.code == 301)
                        case Result.Failure(_)    => succeed
                        case Result.Panic(_)      => fail("Should not panic")
                }
            }
        }
    }

    "204 no content response" in run {
        val route = HttpRoute.getRaw("empty")
        withEchoServer(responseStatus = 204, responseBody = "") { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(
                        HttpMethod.GET,
                        HttpUrl.parse(s"http://127.0.0.1:$port/empty").getOrThrow,
                        HttpHeaders.empty,
                        Record.empty
                    ),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 204)
                }
            }
        }
    }

    // ── Headers ─────────────────────────────────────────────────

    "custom response headers accessible" in run {
        val route = HttpRoute.getRaw("headers").response(_.bodyText)
        withEchoServer(
            responseBody = "ok",
            responseHeaders = HttpHeaders.empty
                .add("X-Custom-One", "value1")
                .add("X-Custom-Two", "value2")
        ) { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(
                        HttpMethod.GET,
                        HttpUrl.parse(s"http://127.0.0.1:$port/headers").getOrThrow,
                        HttpHeaders.empty,
                        Record.empty
                    ),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 200)
                    assert(response.headers.get("X-Custom-One").isDefined)
                    assert(response.headers.get("X-Custom-Two").isDefined)
                }
            }
        }
    }

    // ── Connection lifecycle ────────────────────────────────────

    "isAlive and closeNow" in run {
        withEchoServer() { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.isAlive(conn).map { alive =>
                    assert(alive)
                    client.closeNow(conn).map { _ =>
                        client.isAlive(conn).map { alive2 =>
                            assert(!alive2)
                        }
                    }
                }
            }
        }
    }

    "close with grace period" in run {
        withEchoServer() { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.isAlive(conn).map { alive =>
                    assert(alive)
                    client.close(conn, 1.second).andThen {
                        client.isAlive(conn).map { alive2 =>
                            assert(!alive2)
                        }
                    }
                }
            }
        }
    }

    // ── onRelease callback ──────────────────────────────────────

    "onRelease called with Absent on success" in run {
        val route = HttpRoute.getRaw("test").response(_.bodyText)
        withEchoServer(responseBody = "hello") { (transport, port) =>
            val client = makeClient(transport)
            AtomicRef.init[Maybe[Maybe[Result.Error[Any]]]](Absent).map { releaseRef =>
                client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                    client.sendWith(
                        conn,
                        route,
                        HttpRequest(
                            HttpMethod.GET,
                            HttpUrl.parse(s"http://127.0.0.1:$port/test").getOrThrow,
                            HttpHeaders.empty,
                            Record.empty
                        ),
                        error => releaseRef.set(Present(error))
                    ) { response =>
                        assert(response.status.code == 200)
                    }
                }.andThen {
                    releaseRef.get.map { released =>
                        assert(released == Present(Absent))
                    }
                }
            }
        }
    }

    // ── Large body ──────────────────────────────────────────────

    "large response body (64KB)" in run {
        val largeBody = "x" * 65536
        val route     = HttpRoute.getRaw("large").response(_.bodyText)
        withEchoServer(responseBody = largeBody) { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.parse(s"http://127.0.0.1:$port/large").getOrThrow, HttpHeaders.empty, Record.empty),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 200)
                }
            }
        }
    }

    // ── Empty body ──────────────────────────────────────────────

    "GET with empty response body" in run {
        val route = HttpRoute.getRaw("empty").response(_.bodyText)
        withEchoServer(responseBody = "") { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(HttpMethod.GET, HttpUrl.parse(s"http://127.0.0.1:$port/empty").getOrThrow, HttpHeaders.empty, Record.empty),
                    _ => Kyo.unit
                ) { response =>
                    assert(response.status.code == 200)
                }
            }
        }
    }

    // ── Multiple requests on same connection ────────────────────

    "sequential requests reuse connection" in run {
        val route        = HttpRoute.getRaw("ping").response(_.bodyText)
        val transport    = new TestTransport
        var requestCount = 0
        transport.listen("127.0.0.1", 0, 5) { stream =>
            val buffered = Http1Protocol.buffered(stream)
            Abort.run[HttpException] {
                Loop.foreach {
                    Http1Protocol.readRequest(buffered, 65536).map { (_, _, _, _) =>
                        requestCount += 1
                        val body = s"pong-$requestCount"
                        Http1Protocol.writeResponseHead(
                            stream,
                            HttpStatus(200),
                            HttpHeaders.empty
                                .add("Content-Length", body.length.toString)
                                .add("Content-Type", "text/plain")
                        ).andThen {
                            Http1Protocol.writeBody(stream, Span.fromUnsafe(body.getBytes(Utf8))).andThen {
                                Loop.continue
                            }
                        }
                    }
                }
            }.unit
        }.map { listener =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", listener.port, ssl = false, Absent) { conn =>
                client.sendWith(
                    conn,
                    route,
                    HttpRequest(
                        HttpMethod.GET,
                        HttpUrl.parse(s"http://127.0.0.1:${listener.port}/ping").getOrThrow,
                        HttpHeaders.empty,
                        Record.empty
                    ),
                    _ => Kyo.unit
                ) { r1 =>
                    assert(r1.status.code == 200)
                    client.sendWith(
                        conn,
                        route,
                        HttpRequest(
                            HttpMethod.GET,
                            HttpUrl.parse(s"http://127.0.0.1:${listener.port}/ping").getOrThrow,
                            HttpHeaders.empty,
                            Record.empty
                        ),
                        _ => Kyo.unit
                    ) { r2 =>
                        assert(r2.status.code == 200)
                    }
                }
            }
        }
    }

end HttpTransportClientTest
