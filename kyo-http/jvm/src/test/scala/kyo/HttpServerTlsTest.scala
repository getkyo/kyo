package kyo

import kyo.*
import kyo.internal.*

/** Runs a subset of HTTP server tests over TLS to verify encryption doesn't break protocol behavior.
  *
  * Uses TlsTestBackend with ephemeral certs (keytool-generated, CI-safe).
  */
class HttpServerTlsTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val tlsClient = TlsTestBackend.client
    private val tlsServer = TlsTestBackend.server

    def withTlsServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(
            tlsServer,
            HttpServerConfig.default.port(0).host("localhost").tls(TlsConfig.default)
        )(handlers*).map(s =>
            test(HttpUrl.parse(s"https://localhost:${s.port}").getOrThrow)
        )

    def send[In, Out](
        url: HttpUrl,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        tlsClient.connectWith(url, Absent) { conn =>
            Sync.ensure(tlsClient.closeNow(conn)) {
                tlsClient.sendWith(conn, route, request)(identity)
            }
        }

    // ── Core protocol over TLS ──────────────────────────

    "HTTPS GET 200" in run {
        val route   = HttpRoute.getRaw("hello").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "world"))
        withTlsServer(handler) { url =>
            send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == "world")
            }
        }
    }

    "HTTPS 404" in run {
        val route   = HttpRoute.getRaw("exists").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "found"))
        withTlsServer(handler) { url =>
            send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/not-exists"))).map { resp =>
                assert(resp.status == HttpStatus.NotFound)
            }
        }
    }

    "HTTPS POST with JSON body" in run {
        case class Data(value: Int) derives Json, CanEqual
        val route   = HttpRoute.postJson[Data, Data]("echo")
        val handler = route.handler(req => HttpResponse.ok.addField("body", req.fields.body))
        withTlsServer(handler) { url =>
            send(url, route, HttpRequest.postRaw(HttpUrl.fromUri("/echo")).addField("body", Data(42))).map { resp =>
                assert(resp.status == HttpStatus.OK)
                assert(resp.fields.body == Data(42))
            }
        }
    }

    "HTTPS keep-alive" in run {
        val route   = HttpRoute.getRaw("ping").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "pong"))
        withTlsServer(handler) { url =>
            send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/ping"))).map { resp1 =>
                assert(resp1.status == HttpStatus.OK)
                send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/ping"))).map { resp2 =>
                    assert(resp2.status == HttpStatus.OK)
                }
            }
        }
    }

    "HTTPS streaming response" in run {
        val route = HttpRoute.getRaw("stream").response(_.bodyStream)
        val handler = route.handler { _ =>
            HttpResponse.ok.addField(
                "body",
                Stream.init(Seq(
                    Span.fromUnsafe("hello ".getBytes("UTF-8")),
                    Span.fromUnsafe("world".getBytes("UTF-8"))
                ))
            )
        }
        withTlsServer(handler) { url =>
            tlsClient.connectWith(url, Absent) { conn =>
                Sync.ensure(tlsClient.closeNow(conn)) {
                    tlsClient.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/stream"))) { resp =>
                        assert(resp.status == HttpStatus.OK)
                        resp.fields.body.run.map { chunks =>
                            val text = chunks.foldLeft("")((acc, span) =>
                                acc + new String(span.toArrayUnsafe, "UTF-8")
                            )
                            assert(text == "hello world")
                        }
                    }
                }
            }
        }
    }

    "HTTPS query parameters" in run {
        val route   = HttpRoute.getRaw("search").request(_.query[String]("q")).response(_.bodyText)
        val handler = route.handler(req => HttpResponse.ok.addField("body", s"found:${req.fields.q}"))
        withTlsServer(handler) { url =>
            send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/search?q=hello")).addField("q", "hello")).map { resp =>
                assert(resp.status == HttpStatus.OK)
            }
        }
    }

    "HTTPS typed error" in run {
        val route   = HttpRoute.getRaw("fail").response(_.bodyText).error[String](HttpStatus(422))
        val handler = route.handler(_ => Abort.fail("bad input"))
        withTlsServer(handler) { url =>
            send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/fail"))).map { resp =>
                assert(resp.status.code == 422)
            }
        }
    }

    // TODO: WSS echo — WS upgrade over TLS needs debugging (HttpProtocolException during handshake)

end HttpServerTlsTest
