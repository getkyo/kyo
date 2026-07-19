package kyo

class HttpTransportConfigTest extends BaseHttpTest:

    val client = internal.HttpTestPlatformBackend.client

    def send[In, Out](
        url: HttpUrl,
        route: HttpRoute[In, Out, ?],
        request: HttpRequest[In]
    )(using Frame): HttpResponse[Out] < (Async & Abort[HttpException]) =
        client.connectWith(url, 30.seconds, HttpTlsConfig(trustAll = true)) { conn =>
            Scope.run {
                Scope.ensure(client.closeNow(conn)).andThen {
                    client.sendWith(conn, route, request)(identity)
                }
            }
        }

    "default config values match design doc" in {
        val config = HttpTransportConfig.default
        assert(config.channelCapacity == 4)
        assert(config.readChunkSize == 8192)
        assert(config.maxHeaderSize == 65536)
        assert(config.connectTimeout == 30.seconds)
        assert(config.handshakeTimeout == Duration.Infinity)
    }

    "client transport ownership" - {

        "every client owns and releases its transport when its Scope exits" in {
            // No client shares a process-global transport: each builds its own via init and closes it on shutdown. The Scope
            // release closing the pool proves that owned-transport release ran for both a default-config and a customized-config
            // client (the customized one applies a byte-transport field, so its transport is unambiguously per-config).
            var captured: Maybe[(HttpClient, HttpClient)] = Absent
            Scope.run {
                HttpClient.init().map { defaultClient =>
                    HttpClient.init(transportConfig = HttpTransportConfig.default.channelCapacity(8)).map { customClient =>
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
        val config = HttpTransportConfig.default
            .channelCapacity(8)
            .readChunkSize(4096)
            .maxHeaderSize(32768)
            .connectTimeout(15.seconds)
            .handshakeTimeout(250.millis)
        assert(config.channelCapacity == 8)
        assert(config.readChunkSize == 4096)
        assert(config.maxHeaderSize == 32768)
        assert(config.connectTimeout == 15.seconds)
        assert(config.handshakeTimeout == 250.millis)
    }

    "custom channelCapacity respected" in {
        val tc     = HttpTransportConfig.default.channelCapacity(1)
        val config = HttpServerConfig.default.port(0).host("localhost").transportConfig(tc)
        val route  = HttpRoute.getText("hello").response(_.bodyText)
        val ep     = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(config)(ep).map { server =>
                HttpClient.let(httpClient) {
                    val url = HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow
                    send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                        assert(resp.status == HttpStatus.OK)
                    }
                }
            }
        }
    }

    "custom readChunkSize respected" in {
        val tc     = HttpTransportConfig.default.readChunkSize(512)
        val config = HttpServerConfig.default.port(0).host("localhost").transportConfig(tc)
        val route  = HttpRoute.getText("hello").response(_.bodyText)
        val ep     = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(config)(ep).map { server =>
                HttpClient.let(httpClient) {
                    val url = HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow
                    send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                        assert(resp.status == HttpStatus.OK)
                    }
                }
            }
        }
    }

    "custom maxHeaderSize rejects oversized headers" in {
        val tc     = HttpTransportConfig.default.maxHeaderSize(128)
        val config = HttpServerConfig.default.port(0).host("localhost").transportConfig(tc)
        val route  = HttpRoute.getText("hello").response(_.bodyText)
        val ep     = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(config)(ep).map { server =>
                HttpClient.let(httpClient) {
                    val url = HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow
                    // Build a request with headers that exceed 128 bytes total
                    val largeHeaderValue = "x" * 200
                    val request = HttpRequest.getRaw(HttpUrl.fromUri("/hello"))
                        .addHeader("X-Large", largeHeaderValue)
                    // The server closes the connection when headers exceed maxHeaderSize.
                    // This manifests as a timeout or connection error on the client side.
                    Abort.run[Any](
                        Async.timeout(5.seconds)(send(url, route, request))
                    ).map { result =>
                        result match
                            case Result.Failure(_)    => succeed("expected: oversized headers cause error")
                            case Result.Panic(_)      => succeed("expected: oversized headers cause panic")
                            case Result.Success(resp) =>
                                // If we somehow get a response, it should be an error status
                                assert(resp.status.isError || resp.status == HttpStatus.BadRequest)
                    }
                }
            }
        }
    }

    "config propagated through HttpServerConfig" in {
        val tc = HttpTransportConfig.default
            .channelCapacity(2)
            .readChunkSize(1024)
        val config = HttpServerConfig.default.port(0).host("localhost").transportConfig(tc)
        assert(config.transportConfig.channelCapacity == 2)
        assert(config.transportConfig.readChunkSize == 1024)
        val route = HttpRoute.getText("hello").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(config)(ep).map { server =>
                HttpClient.let(httpClient) {
                    val url = HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow
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
        val tc    = HttpTransportConfig.default.channelCapacity(2).readChunkSize(1024)
        val route = HttpRoute.getText("hello").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init(transportConfig = tc).map { httpClient =>
            HttpServer.init(0, "localhost")(ep).map { server =>
                HttpClient.let(httpClient) {
                    HttpClient.getText(s"http://localhost:${server.port}/hello").map { body =>
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
        HttpClient.init(transportConfig = HttpTransportConfig.default.maxHeaderSize(512)).map { httpClient =>
            HttpServer.init(0, "localhost")(ep).map { server =>
                HttpClient.let(httpClient) {
                    Abort.run[HttpException](HttpClient.getText(s"http://localhost:${server.port}/big")).map { result =>
                        assert(
                            result.isFailure || result.isPanic,
                            s"expected the 512-byte client maxHeaderSize to reject the oversized response, got $result"
                        )
                    }
                }
            }
        }
    }

end HttpTransportConfigTest
