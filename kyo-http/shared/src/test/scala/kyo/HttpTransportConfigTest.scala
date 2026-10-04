package kyo

class HttpTransportConfigTest extends BaseHttpTest:

    val client = internal.HttpTestPlatformBackend.client

    def send[In, Out](
        url: HttpUrl,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        client.connectWith(url, Duration.Infinity, HttpTlsConfig(trustAll = true)) { conn =>
            Scope.run {
                Scope.ensure(client.closeNow(conn)).andThen {
                    client.sendWith(conn, route, request)(identity)
                }
            }
        }

    "default config values match design doc" in {
        val config = HttpTransportConfig.default
        assert(config.channelCapacity == 4)
        assert(config.readChunkSize == 8.kib)
        assert(config.maxHeaderSize == 64.kib)
        assert(config.handshakeTimeout == Duration.Infinity)
    }

    "a channel capacity and a handshake timeout are plain values, zero included, for kyo-net to apply" in {
        val config = HttpTransportConfig.default.channelCapacity(0).handshakeTimeout(Duration.Zero)
        assert((config.channelCapacity, config.handshakeTimeout) == (0, Duration.Zero))
    }

    "the constructor and copy take byte sizes, not raw ints" in {
        typeCheckFailure("HttpTransportConfig.default.copy(readChunkSize = 0)")
        typeCheckFailure("HttpTransportConfig.default.copy(maxHeaderSize = 0)")
    }

    "a zero channel capacity makes rendezvous pump channels, and the server still serves" in {
        val tc     = HttpTransportConfig.default.channelCapacity(0)
        val config = HttpServerConfig.default.port(0).host("127.0.0.1").transportConfig(tc)
        val route  = HttpRoute.getText("hello").response(_.bodyText)
        val ep     = route.handler(_ => HttpResponse.ok("world"))
        HttpServer.init(config)(ep).map { server =>
            val url = HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow
            send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map(resp => assert(resp.fields.body == "world"))
        }
    }

    "a zero read chunk size is narrowed to one byte where the transport reads, so the server still serves" in {
        val tc     = HttpTransportConfig.default.readChunkSize(ByteSize.Zero)
        val config = HttpServerConfig.default.port(0).host("127.0.0.1").transportConfig(tc)
        val route  = HttpRoute.getText("hello").response(_.bodyText)
        val ep     = route.handler(_ => HttpResponse.ok("world"))
        HttpServer.init(config)(ep).map { server =>
            val url = HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow
            send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map(resp => assert(resp.fields.body == "world"))
        }
    }

    "client transport ownership" - {

        "every client owns and releases its transport when its Scope exits" in {
            // No client shares a process-global transport: each builds its own via init and closes it on shutdown. The Scope
            // release closing the pool proves that owned-transport release ran for both a default-config and a customized-config
            // client (the customized one applies a byte-transport field, so its transport is unambiguously per-config).
            var captured: Maybe[(HttpClient, HttpClient)] = Absent
            Scope.run {
                HttpClient.init().map { defaultClient =>
                    HttpClient.init(transportConfig = HttpTransportConfig.default.channelCapacity(8)).map {
                        customClient =>
                            captured = Present((defaultClient, customClient))
                    }
                }
            }.andThen {
                captured match
                    case Present((defaultClient, customClient)) =>
                        Sync.Unsafe.defer(
                            assert(
                                defaultClient.isPoolClosed && customClient.isPoolClosed,
                                "the Scope release must close each client's owned transport"
                            )
                        )
                    case Absent =>
                        fail("clients were not captured")
            }
        }
    }

    "builder methods produce correct values" in {
        val config = HttpTransportConfig.default.channelCapacity(8).readChunkSize(4.kib).maxHeaderSize(32.kib).handshakeTimeout(250.millis)
        assert(config == HttpTransportConfig(8, 4.kib, 32.kib, 250.millis))
    }

    "custom channelCapacity respected" in {
        val tc     = HttpTransportConfig.default.channelCapacity(1)
        val config = HttpServerConfig.default.port(0).host("127.0.0.1").transportConfig(tc)
        val route  = HttpRoute.getText("hello").response(_.bodyText)
        val ep     = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(config)(ep).map { server =>
                HttpClient.let(httpClient) {
                    val url = HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow
                    send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                        assert(resp.status == HttpStatus.OK)
                    }
                }
            }
        }
    }

    "custom readChunkSize respected" in {
        val tc     = HttpTransportConfig.default.readChunkSize(512.bytes)
        val config = HttpServerConfig.default.port(0).host("127.0.0.1").transportConfig(tc)
        val route  = HttpRoute.getText("hello").response(_.bodyText)
        val ep     = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(config)(ep).map { server =>
                HttpClient.let(httpClient) {
                    val url = HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow
                    send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                        assert(resp.status == HttpStatus.OK)
                    }
                }
            }
        }
    }

    // The leaf timeout bounds only the failing state, in which the server answers nothing; the pass condition is the status alone.
    "custom maxHeaderSize answers an oversized request head with 431".timeout(30.seconds) in {
        val tc     = HttpTransportConfig.default.maxHeaderSize(128.bytes)
        val config = HttpServerConfig.default.port(0).host("127.0.0.1").transportConfig(tc)
        val route  = HttpRoute.getText("hello").response(_.bodyText)
        val ep     = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(config)(ep).map { server =>
                HttpClient.let(httpClient) {
                    val url     = HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow
                    val request = HttpRequest.getRaw(HttpUrl.fromUri("/hello")).addHeader("X-Large", "x" * 200)
                    Abort.run[HttpException](send(url, route, request)).map { result =>
                        assert(result.map(_.status) == Result.succeed(HttpStatus.RequestHeaderFieldsTooLarge), s"observed: $result")
                    }
                }
            }
        }
    }

    "config propagated through HttpServerConfig" in {
        val tc = HttpTransportConfig.default
            .channelCapacity(2)
            .readChunkSize(1.kib)
        val config = HttpServerConfig.default.port(0).host("127.0.0.1").transportConfig(tc)
        assert(config.transportConfig.channelCapacity == 2)
        assert(config.transportConfig.readChunkSize == 1.kib)
        val route = HttpRoute.getText("hello").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(config)(ep).map { server =>
                HttpClient.let(httpClient) {
                    val url = HttpUrl.parse(s"http://127.0.0.1:${server.port}").getOrThrow
                    send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                        assert(resp.status == HttpStatus.OK)
                    }
                }
            }
        }
    }

    "transportConfig propagated through HttpClient.init: owned transport serves requests" in {
        // A custom byte-transport field makes HttpClient.init build a per-config owned transport (closed when the client closes). A normal
        // request routed through this client (not the shared test backend) must succeed, proving the owned transport works end to end. The
        // high-level HttpClient.getText API is used so the request flows through the fiber-local client set by HttpClient.let.
        val tc    = HttpTransportConfig.default.channelCapacity(2).readChunkSize(1.kib)
        val route = HttpRoute.getText("hello").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init(transportConfig = tc).map { httpClient =>
            HttpServer.init(0, "127.0.0.1")(ep).map { server =>
                HttpClient.let(httpClient) {
                    HttpClient.getText(s"http://127.0.0.1:${server.port}/hello").map { body =>
                        assert(body == "world")
                    }
                }
            }
        }
    }

    "client maxHeaderSize is reachable via HttpClient.init and rejects an oversized response (CWE-400)" in {
        // The client parser's header limit is settable via HttpClient.init. A 512-byte limit against a ~2 KiB response header must fail
        // (a malicious/buggy server cannot force unbounded client header buffering); if the limit were ignored the request would succeed.
        val bigHeaderValue = "x" * 2048
        val route          = HttpRoute.getText("big").response(_.bodyText)
        val ep             = route.handler(_ => HttpResponse.ok("ok").addHeader("X-Big", bigHeaderValue))
        HttpClient.init(transportConfig = HttpTransportConfig.default.maxHeaderSize(512.bytes)).map { httpClient =>
            HttpServer.init(0, "127.0.0.1")(ep).map { server =>
                HttpClient.let(httpClient) {
                    Abort.run[HttpException](HttpClient.getText(s"http://127.0.0.1:${server.port}/big")).map { result =>
                        assert(
                            result == Result.fail(HttpProtocolException("the response head exceeds 512 bytes")),
                            s"expected the 512-byte client maxHeaderSize to reject the oversized response, got $result"
                        )
                    }
                }
            }
        }
    }

end HttpTransportConfigTest
