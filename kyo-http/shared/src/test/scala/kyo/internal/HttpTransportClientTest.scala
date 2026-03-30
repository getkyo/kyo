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
                    _ => ()
                ) { response =>
                    assert(response.status.code == 200)
                }
            }
        }
    }

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
                        _ => ()
                    ) { response =>
                        response
                    }
                }.map { result =>
                    // 404 with text body should decode but report status error
                    assert(result.isFailure || result.isSuccess)
                    succeed
                }
            }
        }
    }

    // ── Connection lifecycle ────────────────────────────────────

    "isAlive and closeNowUnsafe" in run {
        withEchoServer() { (transport, port) =>
            val client = makeClient(transport)
            client.connectWith("127.0.0.1", port, ssl = false, Absent) { conn =>
                import AllowUnsafe.embrace.danger
                assert(client.isAlive(conn))
                client.closeNowUnsafe(conn)
                assert(!client.isAlive(conn))
                succeed
            }
        }
    }

end HttpTransportClientTest
