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
        assert(config.channelCapacity.value == 4)
        assert(config.readChunkSize.value == 8192)
        assert(config.maxHeaderSize.value == 65536)
        assert(config.handshakeTimeout == HttpTransportConfig.HandshakeTimeout.unlimited)
    }

    "a size below one is refused with HttpConfigException naming the setting" in {
        val config = HttpTransportConfig.default
        assert(Chunk(
            config.channelCapacity(0).failure.map(_.setting),
            config.readChunkSize(-1).failure.map(_.setting),
            config.maxHeaderSize(0).failure.map(_.setting)
        ) == Chunk(Present("channelCapacity"), Present("readChunkSize"), Present("maxHeaderSize")))
        assert(HttpTransportConfig.Size.init(0).isFailure)
        assert(HttpTransportConfig.Size.init(1).map(_.value) == Result.succeed(1))
    }

    "a size refusal is raised at the caller's frame" in {
        val (refused, here) = (HttpTransportConfig.default.readChunkSize(0), summon[Frame])
        assert(refused.failure.map(_.frame.position.lineNumber) == Present(here.position.lineNumber))
    }

    "the constructor and copy take checked sizes, not raw ints" in {
        typeCheckFailure("HttpTransportConfig(0, 8192, 65536)")
        typeCheckFailure("HttpTransportConfig.Size(0)")
        typeCheckFailure("HttpTransportConfig.default.copy(channelCapacity = 0)")
        typeCheckFailure("HttpTransportConfig.default.copy(readChunkSize = 0)")
        typeCheckFailure("HttpTransportConfig.default.copy(maxHeaderSize = 0)")
    }

    "a zero handshake timeout is refused at the caller's frame" in {
        val (refused, here) = (HttpTransportConfig.default.handshakeTimeout(Duration.Zero), summon[Frame])
        assert(refused.failure.map(_.setting) == Present("handshakeTimeout"))
        assert(refused.failure.map(_.frame.position.lineNumber) == Present(here.position.lineNumber))
        assert(HttpTransportConfig.HandshakeTimeout.init(Duration.Zero).isFailure)
    }

    "a positive or unlimited handshake timeout is set" in {
        val config = HttpTransportConfig.default
        assert(config.handshakeTimeout(250.millis).map(_.handshakeTimeout.duration) == Result.succeed(250.millis))
        assert(config.handshakeTimeout(Duration.Infinity).map(_.handshakeTimeout.duration) == Result.succeed(Duration.Infinity))
    }

    "the constructor and copy take a checked handshake timeout, not a raw duration" in {
        typeCheckFailure("HttpTransportConfig(4, 8192, 65536, Duration.Zero)")
        typeCheckFailure("HttpTransportConfig.default.copy(handshakeTimeout = Duration.Zero)")
    }

    "client transport ownership" - {

        "every client owns and releases its transport when its Scope exits" in {
            // No client shares a process-global transport: each builds its own via init and closes it on shutdown. The Scope
            // release closing the pool proves that owned-transport release ran for both a default-config and a customized-config
            // client (the customized one applies a byte-transport field, so its transport is unambiguously per-config).
            var captured: Maybe[(HttpClient, HttpClient)] = Absent
            Scope.run {
                HttpClient.init().map { defaultClient =>
                    HttpClient.init(transportConfig = HttpTransportConfig.default.channelCapacity(HttpTransportConfig.Size(8))).map {
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
        val config = HttpTransportConfig.default.channelCapacity(8)
            .flatMap(_.readChunkSize(4096))
            .flatMap(_.maxHeaderSize(32768))
            .flatMap(_.handshakeTimeout(250.millis))
        assert(config.map(_.channelCapacity.value) == Result.succeed(8))
        assert(config.map(_.readChunkSize.value) == Result.succeed(4096))
        assert(config.map(_.maxHeaderSize.value) == Result.succeed(32768))
        assert(config.map(_.handshakeTimeout.duration) == Result.succeed(250.millis))
    }

    "custom channelCapacity respected" in {
        val tc     = HttpTransportConfig.default.channelCapacity(HttpTransportConfig.Size(1))
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
        val tc     = HttpTransportConfig.default.readChunkSize(HttpTransportConfig.Size(512))
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
        val tc     = HttpTransportConfig.default.maxHeaderSize(HttpTransportConfig.Size(128))
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
            .channelCapacity(HttpTransportConfig.Size(2))
            .readChunkSize(HttpTransportConfig.Size(1024))
        val config = HttpServerConfig.default.port(0).host("127.0.0.1").transportConfig(tc)
        assert(config.transportConfig.channelCapacity.value == 2)
        assert(config.transportConfig.readChunkSize.value == 1024)
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
        val tc    = HttpTransportConfig.default.channelCapacity(HttpTransportConfig.Size(2)).readChunkSize(HttpTransportConfig.Size(1024))
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
        HttpClient.init(transportConfig = HttpTransportConfig.default.maxHeaderSize(HttpTransportConfig.Size(512))).map { httpClient =>
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
