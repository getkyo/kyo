package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class HttpTransportServerTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    private def withServer(handlers: HttpHandler[?, ?, ?]*)(
        f: (TestTransport, HttpBackend.Binding) => Assertion < (Async & Abort[HttpException])
    )(using Frame): Assertion < (Async & Abort[HttpException] & Scope) =
        val transport = new TestTransport
        val server    = new HttpTransportServer(transport, Http1Protocol)
        server.bind(handlers, HttpServerConfig.default).map { binding =>
            f(transport, binding)
        }
    end withServer

    private def sendRaw(
        transport: TestTransport,
        port: Int,
        method: HttpMethod,
        path: String,
        extraHeaders: HttpHeaders = HttpHeaders.empty,
        body: String = ""
    )(using Frame): (HttpStatus, HttpHeaders, HttpBody) < (Async & Abort[HttpException]) =
        transport.connect("127.0.0.1", port, tls = false).map { conn =>
            transport.stream(conn).map { stream =>
                val hdrs = extraHeaders
                    .add("Host", "localhost")
                    .add("Content-Length", body.length.toString)
                Http1Protocol.writeRequestHead(stream, method, path, hdrs).andThen {
                    (if body.nonEmpty then Http1Protocol.writeBody(stream, Span.fromUnsafe(body.getBytes(Utf8)))
                     else Sync.defer(())).andThen {
                        Http1Protocol.readResponse(stream, 65536)
                    }
                }
            }
        }

    // ── Routing ─────────────────────────────────────────────────

    "GET /existing → 200" in run {
        val route   = HttpRoute.getRaw("hello").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "world"))
        withServer(handler) { (transport, binding) =>
            sendRaw(transport, binding.port, HttpMethod.GET, "/hello").map { (status, _, _) =>
                assert(status.code == 200)
            }
        }
    }

    "GET /nonexistent → 404" in run {
        val route   = HttpRoute.getRaw("hello").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "world"))
        withServer(handler) { (transport, binding) =>
            sendRaw(transport, binding.port, HttpMethod.GET, "/notfound").map { (status, _, _) =>
                assert(status.code == 404)
            }
        }
    }

    "POST to GET-only route → 405" in run {
        val route   = HttpRoute.getRaw("hello").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "world"))
        withServer(handler) { (transport, binding) =>
            sendRaw(transport, binding.port, HttpMethod.POST, "/hello").map { (status, _, _) =>
                assert(status.code == 405)
            }
        }
    }

    // ── Keep-alive ──────────────────────────────────────────────

    "multiple requests on same connection" in run {
        val route   = HttpRoute.getRaw("ping").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "pong"))
        withServer(handler) { (transport, binding) =>
            transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                transport.stream(conn).map { stream =>
                    val hdrs = HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                    Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/ping", hdrs).andThen {
                        Http1Protocol.readResponse(stream, 65536).map { (s1, _, _) =>
                            assert(s1.code == 200)
                            Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/ping", hdrs).andThen {
                                Http1Protocol.readResponse(stream, 65536).map { (s2, _, _) =>
                                    assert(s2.code == 200)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Error handling ──────────────────────────────────────────

    "typed error → mapped status code" in run {
        val route = HttpRoute.getRaw("fail").response(_.bodyText)
            .error[String](HttpStatus(422))
        val handler = route.handler(_ => Abort.fail("bad input"))
        withServer(handler) { (transport, binding) =>
            sendRaw(transport, binding.port, HttpMethod.GET, "/fail").map { (status, _, _) =>
                assert(status.code == 422)
            }
        }
    }

    // ── Response body ───────────────────────────────────────────

    "text body response" in run {
        val route   = HttpRoute.getRaw("text").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "hello world"))
        withServer(handler) { (transport, binding) =>
            sendRaw(transport, binding.port, HttpMethod.GET, "/text").map { (status, _, body) =>
                assert(status.code == 200)
                body match
                    case HttpBody.Buffered(data) =>
                        assert(new String(data.toArrayUnsafe, Utf8).contains("hello world"))
                    case other => fail(s"Expected Buffered, got $other")
                end match
            }
        }
    }

    "empty body response" in run {
        val route   = HttpRoute.getRaw("empty")
        val handler = route.handler(_ => HttpResponse.ok)
        withServer(handler) { (transport, binding) =>
            sendRaw(transport, binding.port, HttpMethod.GET, "/empty").map { (status, _, _) =>
                assert(status.code == 200)
            }
        }
    }

end HttpTransportServerTest
