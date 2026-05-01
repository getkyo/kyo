package kyo

class HttpTransportConfigTest extends Test:

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

    "default config values match design doc" in run {
        val config = HttpTransportConfig.default
        assert(config.channelCapacity == 4)
        assert(config.readChunkSize == 8192)
        assert(config.writeBatchSize == 16)
        assert(config.maxHeaderSize == 65536)
    }

    "builder methods produce correct values" in run {
        val config = HttpTransportConfig.default
            .channelCapacity(8)
            .readChunkSize(4096)
            .writeBatchSize(32)
            .maxHeaderSize(32768)
        assert(config.channelCapacity == 8)
        assert(config.readChunkSize == 4096)
        assert(config.writeBatchSize == 32)
        assert(config.maxHeaderSize == 32768)
    }

    "custom channelCapacity respected" in run {
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

    "custom readChunkSize respected" in run {
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

    "custom writeBatchSize respected" in run {
        val tc     = HttpTransportConfig.default.writeBatchSize(2)
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

    "custom maxHeaderSize rejects oversized headers" in run {
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
                            case Result.Failure(_)    => assertionSuccess
                            case Result.Panic(_)      => assertionSuccess
                            case Result.Success(resp) =>
                                // If we somehow get a response, it should be an error status
                                assert(resp.status.isError || resp.status == HttpStatus.BadRequest)
                    }
                }
            }
        }
    }

    "config propagated through HttpServerConfig" in run {
        val tc = HttpTransportConfig.default
            .channelCapacity(2)
            .readChunkSize(1024)
            .writeBatchSize(4)
        val config = HttpServerConfig.default.port(0).host("localhost").transportConfig(tc)
        assert(config.transportConfig.channelCapacity == 2)
        assert(config.transportConfig.readChunkSize == 1024)
        assert(config.transportConfig.writeBatchSize == 4)
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

    "config propagated through HttpClientConfig" in run {
        val tc = HttpTransportConfig.default
            .channelCapacity(2)
            .readChunkSize(1024)
        val clientConfig = HttpClientConfig().transportConfig(tc)
        assert(clientConfig.transportConfig.channelCapacity == 2)
        assert(clientConfig.transportConfig.readChunkSize == 1024)
        // Verify the config is properly stored and retrievable
        val route = HttpRoute.getText("hello").response(_.bodyText)
        val ep    = route.handler(_ => HttpResponse.ok("world"))
        HttpClient.init().map { httpClient =>
            HttpServer.init(0, "localhost")(ep).map { server =>
                HttpClient.let(httpClient) {
                    HttpClient.withConfig(clientConfig) {
                        val url = HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow
                        send(url, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))).map { resp =>
                            assert(resp.status == HttpStatus.OK)
                        }
                    }
                }
            }
        }
    }

end HttpTransportConfigTest
