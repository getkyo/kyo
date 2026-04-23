package kyo

import kyo.*

class HttpServerUnixTest extends Test with internal.UnixSocketTestHelperImpl:

    override def timeout = 30.seconds

    given CanEqual[Any, Any] = CanEqual.derived

    case class Item(id: Int, name: String) derives Json, CanEqual

    private def withUnixServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: (HttpServer, String) => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        tempSocketPath().map { sockPath =>
            val config = HttpServerConfig.default.unixSocket(sockPath)
            Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                HttpServer.init(config)(handlers*).map { server =>
                    test(server, sockPath)
                }
            }
        }

    // ── Transport Integration ─────────────────────────────────────────────────

    "transport integration" - {

        "HTTP GET over Unix socket" in run {
            tempSocketPath().map { sockPath =>
                val route   = HttpRoute.getRaw("test").response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok("hello"))
                val config  = HttpServerConfig.default.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(config)(handler).map { server =>
                        val url = mkUrl(sockPath, "/test")
                        HttpClient.getText(url).map { text =>
                            assert(text == "hello")
                        }
                    }
                }
            }
        }

        "HTTP POST with body over Unix socket" in run {
            tempSocketPath().map { sockPath =>
                val route   = HttpRoute.postRaw("echo").request(_.bodyText).response(_.bodyText)
                val handler = route.handler(req => HttpResponse.ok(req.fields.body))
                val config  = HttpServerConfig.default.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(config)(handler).map { server =>
                        val url = mkUrl(sockPath, "/echo")
                        HttpClient.postText(url, "ping").map { text =>
                            assert(text == "ping")
                        }
                    }
                }
            }
        }

        "server address is Unix" in run {
            tempSocketPath().map { sockPath =>
                val route   = HttpRoute.getRaw("test").response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok("ok"))
                val config  = HttpServerConfig.default.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(config)(handler).map { server =>
                        assert(server.address == HttpAddress.Unix(sockPath))
                        assert(server.port == -1)
                        assert(server.host == "localhost")
                        succeed
                    }
                }
            }
        }

        "non-existent socket path fails with HttpConnectException" in run {
            Abort.run[HttpException] {
                HttpClient.getText("http+unix://%2Ftmp%2Fnonexistent_kyo_unix_test.sock/test")
            }.map { result =>
                assert(result.isFailure)
                assert(result.failure.exists(_.isInstanceOf[HttpConnectException]))
            }
        }

        "error response (404) over Unix socket" in run {
            tempSocketPath().map { sockPath =>
                val route   = HttpRoute.getRaw("exists").response(_.bodyText)
                val handler = route.handler(_ => HttpResponse.ok("ok"))
                val config  = HttpServerConfig.default.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(config)(handler).map { server =>
                        val url = mkUrl(sockPath, "/missing")
                        case class Dummy(x: Int) derives Json
                        Abort.run[HttpException] {
                            HttpClient.getJson[Dummy](url)
                        }.map { result =>
                            assert(result.isFailure)
                            result.failure match
                                case Present(e: HttpStatusException) =>
                                    assert(e.status == HttpStatus.NotFound)
                                case other =>
                                    fail(s"Expected HttpStatusException but got $other")
                            end match
                        }
                    }
                }
            }
        }
    }

    // ── Chunked Streaming ────────────────────────────────────────────────────

    "chunked streaming" - {

        "chunked response over Unix socket" in run {
            val route = HttpRoute.getRaw("stream").response(_.bodyStream)
            val handler = route.handler { _ =>
                val chunks = Stream.init(Seq(
                    Span.fromUnsafe("hello ".getBytes("UTF-8")),
                    Span.fromUnsafe("beautiful ".getBytes("UTF-8")),
                    Span.fromUnsafe("world".getBytes("UTF-8"))
                ))
                HttpResponse.ok.addField("body", chunks)
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url    = mkUrl(sockPath, "/stream")
                val client = internal.HttpTestPlatformBackend.client
                HttpClient.init().map { httpClient =>
                    HttpClient.let(httpClient) {
                        val parsedUrl = HttpUrl.parse(url).getOrThrow
                        client.connectWith(parsedUrl, 30.seconds, HttpTlsConfig(trustAll = true)) { conn =>
                            Scope.run {
                                Scope.ensure(client.closeNow(conn)).andThen {
                                    client.sendWith(conn, route, HttpRequest.getRaw(parsedUrl)) { resp =>
                                        assert(resp.status == HttpStatus.OK)
                                        resp.fields.body.run.map { chunks =>
                                            val text = chunks.foldLeft("")((acc, span) =>
                                                acc + new String(span.toArrayUnsafe, "UTF-8")
                                            )
                                            assert(text == "hello beautiful world")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "streaming request body over Unix socket" in run {
            val route = HttpRoute.postRaw("upload")
                .request(_.bodyStream)
                .response(_.bodyText)
            val handler = route.handler { req =>
                req.fields.body.run.map { chunks =>
                    val totalBytes = chunks.foldLeft(0)(_ + _.size)
                    HttpResponse.ok(s"received $totalBytes bytes")
                }
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url    = mkUrl(sockPath, "/upload")
                val client = internal.HttpTestPlatformBackend.client
                HttpClient.init().map { httpClient =>
                    HttpClient.let(httpClient) {
                        val parsedUrl = HttpUrl.parse(url).getOrThrow
                        val bodyStream: Stream[Span[Byte], Async] = Stream.init(Seq(
                            Span.fromUnsafe("chunk1".getBytes("UTF-8")),
                            Span.fromUnsafe("chunk2".getBytes("UTF-8"))
                        ))
                        val request = HttpRequest.postRaw(parsedUrl)
                            .addField("body", bodyStream)
                        client.connectWith(parsedUrl, 30.seconds, HttpTlsConfig(trustAll = true)) { conn =>
                            Scope.run {
                                Scope.ensure(client.closeNow(conn)).andThen {
                                    client.sendWith(conn, route, request) { resp =>
                                        assert(resp.status == HttpStatus.OK)
                                        assert(resp.fields.body == "received 12 bytes")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "large streaming body (100KB in 1KB chunks)" in run {
            val route = HttpRoute.postRaw("big-upload")
                .request(_.bodyStream)
                .response(_.bodyText)
            val handler = route.handler { req =>
                req.fields.body.run.map { chunks =>
                    val totalBytes = chunks.foldLeft(0)(_ + _.size)
                    HttpResponse.ok(s"received $totalBytes bytes")
                }
            }
            val serverConfig = HttpServerConfig.default.maxContentLength(200000)
            tempSocketPath().map { sockPath =>
                val fullConfig = serverConfig.unixSocket(sockPath)
                Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                    HttpServer.init(fullConfig)(handler).map { server =>
                        val url    = mkUrl(sockPath, "/big-upload")
                        val client = internal.HttpTestPlatformBackend.client
                        HttpClient.init().map { httpClient =>
                            HttpClient.let(httpClient) {
                                val parsedUrl                             = HttpUrl.parse(url).getOrThrow
                                val oneKb                                 = Array.fill[Byte](1024)(65)
                                val chunks                                = (1 to 100).map(_ => Span.fromUnsafe(oneKb.clone()))
                                val bodyStream: Stream[Span[Byte], Async] = Stream.init(chunks)
                                val request = HttpRequest.postRaw(parsedUrl)
                                    .addField("body", bodyStream)
                                client.connectWith(parsedUrl, 30.seconds, HttpTlsConfig(trustAll = true)) { conn =>
                                    Scope.run {
                                        Scope.ensure(client.closeNow(conn)).andThen {
                                            client.sendWith(conn, route, request) { resp =>
                                                assert(resp.status == HttpStatus.OK)
                                                assert(resp.fields.body == "received 102400 bytes")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── SSE ──────────────────────────────────────────────────────────────────

    "SSE" - {

        "SSE text events over Unix socket" in run {
            val route = HttpRoute.getRaw("events").response(_.bodySseText)
            val handler = route.handler { _ =>
                HttpResponse.ok.addField(
                    "body",
                    Stream.init(Seq(
                        HttpSseEvent("hello", Absent, Absent, Absent),
                        HttpSseEvent("beautiful", Absent, Absent, Absent),
                        HttpSseEvent("world", Absent, Absent, Absent)
                    ))
                )
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/events")
                HttpClient.getSseText(url).take(3).run.map { chunks =>
                    val events = chunks.toSeq
                    assert(events.size == 3)
                    assert(events(0).data == "hello")
                    assert(events(1).data == "beautiful")
                    assert(events(2).data == "world")
                }
            }
        }

        "SSE with event type and id" in run {
            val route = HttpRoute.getRaw("typed-events").response(_.bodySseText)
            val handler = route.handler { _ =>
                HttpResponse.ok.addField(
                    "body",
                    Stream.init(Seq(
                        HttpSseEvent("first", Present("message"), Present("1"), Absent),
                        HttpSseEvent("second", Present("update"), Present("2"), Absent)
                    ))
                )
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/typed-events")
                HttpClient.getSseText(url).take(2).run.map { chunks =>
                    val events = chunks.toSeq
                    assert(events.size == 2)
                    assert(events(0).data == "first")
                    assert(events(0).event == Present("message"))
                    assert(events(0).id == Present("1"))
                    assert(events(1).data == "second")
                    assert(events(1).event == Present("update"))
                    assert(events(1).id == Present("2"))
                }
            }
        }

        "SSE JSON events over Unix socket" in run {
            val route = HttpRoute.getRaw("json-events").response(_.bodySseJson[Item])
            val handler = route.handler { _ =>
                HttpResponse.ok.addField(
                    "body",
                    Stream.init(Seq(
                        HttpSseEvent(data = Item(1, "alice")),
                        HttpSseEvent(data = Item(2, "bob"))
                    ))
                )
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/json-events")
                HttpClient.getSseJson[Item](url).take(2).run.map { chunks =>
                    val events = chunks.toSeq
                    assert(events.size == 2)
                    assert(events(0).data == Item(1, "alice"))
                    assert(events(1).data == Item(2, "bob"))
                }
            }
        }
    }

    // ── NDJSON ───────────────────────────────────────────────────────────────

    "NDJSON" - {

        "NDJSON streaming over Unix socket" in run {
            val route = HttpRoute.getRaw("data").response(_.bodyNdjson[Item])
            val handler = route.handler { _ =>
                HttpResponse.ok.addField(
                    "body",
                    Stream.init(Seq(
                        Item(1, "alice"),
                        Item(2, "bob"),
                        Item(3, "charlie")
                    ))
                )
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url = mkUrl(sockPath, "/data")
                HttpClient.getNdJson[Item](url).take(3).run.map { chunks =>
                    val items = chunks.toSeq
                    assert(items.size == 3)
                    assert(items(0) == Item(1, "alice"))
                    assert(items(1) == Item(2, "bob"))
                    assert(items(2) == Item(3, "charlie"))
                }
            }
        }
    }

    // ── Edge Cases ───────────────────────────────────────────────────────────

    "edge cases" - {

        "empty stream over Unix socket" in run {
            val route = HttpRoute.getRaw("empty").response(_.bodyStream)
            val handler = route.handler { _ =>
                val chunks: Stream[Span[Byte], Async] = Stream.init(Seq.empty[Span[Byte]])
                HttpResponse.ok.addField("body", chunks)
            }
            withUnixServer(handler) { (server, sockPath) =>
                val url    = mkUrl(sockPath, "/empty")
                val client = internal.HttpTestPlatformBackend.client
                HttpClient.init().map { httpClient =>
                    HttpClient.let(httpClient) {
                        val parsedUrl = HttpUrl.parse(url).getOrThrow
                        client.connectWith(parsedUrl, 30.seconds, HttpTlsConfig(trustAll = true)) { conn =>
                            Scope.run {
                                Scope.ensure(client.closeNow(conn)).andThen {
                                    client.sendWith(conn, route, HttpRequest.getRaw(parsedUrl)) { resp =>
                                        assert(resp.status == HttpStatus.OK)
                                        resp.fields.body.run.map { chunks =>
                                            val text = chunks.foldLeft("")((acc, span) =>
                                                acc + new String(span.toArrayUnsafe, "UTF-8")
                                            )
                                            assert(text == "")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end HttpServerUnixTest
