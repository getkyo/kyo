package kyo

import kyo.*

/** D-014 (R-003/R-026 partition, Q-004): kyo-http's connect ESTABLISHMENT sites migrated (through `transportConnectFailure`) to consume
  * `Abort[kyo.net.NetException]`, mapping it to the matching typed `HttpException`; every genuine channel-close site (WebSocket,
  * `ChannelBackedStream`, `ConnectionBackedStream`, `Http1Parser`, `Http1StreamContext`) STAYS `Abort[Closed]`, untouched by the re-root. A
  * compile of kyo-http on all four platforms is the exhaustiveness proof (AC-2, the whole build succeeding); this suite is the runtime
  * half, one leaf per side of the partition.
  */
class HttpClientBackendPartitionTest extends BaseHttpTest:

    private val client = internal.HttpTestPlatformBackend.client

    /** Connect, send one request, and close, mirroring HttpClientTest's helper: the migrated establishment site under test. */
    private def send[In, Out](
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

    private def withWsServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "localhost")(handlers*).map(server =>
            test(HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow)
        )

    "establishment: a refused connection is classified through the migrated Abort[NetException] site (a NetConnectException-derived HttpConnectException, not a compile break)" in {
        val route = HttpRoute.getRaw("test").response(_.bodyText)
        Abort.run[HttpException] {
            send(HttpUrl(Present("http"), "localhost", 1, "/", Absent), route, HttpRequest.getRaw(HttpUrl.fromUri("/test")))
        }.map {
            case Result.Failure(e: HttpConnectException) =>
                assert(e.host == "localhost" && e.port == 1, s"the migrated site must carry the transport's structured host/port, got $e")
            case other => fail(s"expected Result.Failure(HttpConnectException), got $other")
        }
    }

    "channel-close: a WebSocket take on a server-closed channel stays Abort[Closed] (the STAY partition, untouched by the re-root)" in {
        withWsServer(HttpHandler.webSocket("ws/close") { (_, ws) =>
            ws.take().andThen(ws.close())
        }) { url =>
            HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/close") { ws =>
                ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(
                            result.isFailure || result.isPanic,
                            s"a take on a server-closed WS channel must stay Abort[Closed], got $result"
                        ))
                    )
                }
            }
        }.unit
    }

end HttpClientBackendPartitionTest
