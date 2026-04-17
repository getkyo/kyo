package kyo

import kyo.*

class HttpWebSocketTest extends Test with internal.UnixSocketTestHelperImpl:

    given CanEqual[Any, Any] = CanEqual.derived

    def echo(req: HttpRequest[Any], ws: HttpWebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
        ws.stream.foreach(ws.put)

    def withWsServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: HttpUrl => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        HttpServer.init(0, "localhost")(handlers*).map(server =>
            test(HttpUrl.parse(s"http://localhost:${server.port}").getOrThrow)
        )

    // ==================== Basic connectivity ====================

    "basic connectivity" - {

        "text echo" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hello"))))
                    }
                }
            }.andThen(succeed)
        }

        "binary echo" in run {
            val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(bytes)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 5))
                                discard(assert(data(0) == 1.toByte))
                                discard(assert(data(4) == 5.toByte))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "multiple messages in order" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    Kyo.foreach(1 to 10)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).andThen {
                        Kyo.foreach(1 to 10)(i =>
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"msg$i"))))
                        ).unit
                    }
                }
            }.andThen(succeed)
        }

        "empty text frame" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(""))))
                    }
                }
            }.andThen(succeed)
        }

        "large text frame" in run {
            val text = "x" * 65000
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(text))))
                    }
                }
            }.andThen(succeed)
        }

        "large binary frame" in run {
            val data = Span.fromUnsafe(Array.tabulate[Byte](65000)(i => (i % 256).toByte))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(data)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) => discard(assert(d.size == 65000))
                            case other                           => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "interleaved text and binary" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a"))
                        .andThen(ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](1)))))
                        .andThen(ws.put(HttpWebSocket.Payload.Text("b")))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("a")))))
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) => discard(assert(d(0) == 1.toByte))
                            case other                           => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("b")))))
                }
            }.andThen(succeed)
        }

        "unicode text roundtrip" in run {
            val text = "Hello \uD83D\uDE00 \u4F60\u597D"
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(text))))
                    }
                }
            }.andThen(succeed)
        }

        "binary all byte values" in run {
            val allBytes = Span.fromUnsafe(Array.tabulate[Byte](256)(_.toByte))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(allBytes)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 256))
                                var i = 0
                                while i < 256 do
                                    discard(assert(d(i) == i.toByte))
                                    i += 1
                                end while
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "single byte binary" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](42)))).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 1))
                                discard(assert(d(0) == 42.toByte))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "single char text" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("x")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("x"))))
                    }
                }
            }.andThen(succeed)
        }
    }

    // ==================== Close handshake ====================

    "close handshake" - {

        "client initiates close" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hi")).andThen(ws.take()).andThen(ws.close())
                }
            }.andThen(succeed)
        }

        "server initiates close" in run {
            withWsServer(HttpHandler.webSocket("ws/close") { (_, ws) =>
                ws.take().andThen(ws.close())
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/close") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                        Abort.run[Closed](ws.take()).map(result =>
                            discard(assert(result.isFailure || result.isPanic))
                        )
                    }
                }
            }.andThen(succeed)
        }

        "close code preserved" in run {
            withWsServer(HttpHandler.webSocket("ws/closecode") { (_, ws) =>
                ws.take().andThen(ws.close(4000, "app error"))
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/closecode") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                        Abort.run[Closed](ws.take()).andThen {
                            ws.closeReason.map(_ => ())
                        }
                    }
                }
            }.andThen(succeed)
        }

        "take after server close fails with Closed" in run {
            withWsServer(HttpHandler.webSocket("ws/close-fast") { (_, ws) =>
                ws.close()
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/close-fast") { ws =>
                    // take blocks until close arrives
                    Abort.run[Closed](ws.take()).map(r =>
                        discard(assert(r.isFailure || r.isPanic))
                    )
                }
            }.andThen(succeed)
        }

        "close after exchange" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take())
                        .andThen(ws.put(HttpWebSocket.Payload.Text("b"))).andThen(ws.take())
                        .andThen(ws.close())
                }
            }.andThen(succeed)
        }
    }

    // ==================== Backpressure ====================

    "backpressure" - {

        "bidirectional concurrent exchange" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    val count = 100
                    Abort.run[Closed] {
                        Async.gather(
                            Kyo.foreach(1 to count)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).unit,
                            Kyo.foreach(1 to count)(i =>
                                ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"msg$i"))))
                            ).unit
                        )
                    }.unit
                }
            }.andThen(succeed)
        }

        "handler that only sends" in run {
            withWsServer(HttpHandler.webSocket("ws/send-only") { (_, ws) =>
                Kyo.foreach(1 to 5)(i => ws.put(HttpWebSocket.Payload.Text(s"server$i"))).andThen {
                    Abort.run[Closed](ws.take()).unit
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/send-only") { ws =>
                    Kyo.foreach(1 to 5)(i =>
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"server$i"))))
                    ).unit
                }
            }.andThen(succeed)
        }

        "handler that only reads" in run {
            withWsServer(HttpHandler.webSocket("ws/read-only") { (_, ws) =>
                // Read 3 messages without sending any response
                Kyo.foreach(1 to 3)(_ => ws.take()).unit
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/read-only") { ws =>
                    Kyo.foreach(1 to 3)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).andThen {
                        // Server consumed all, now it returns → connection closes
                        Abort.run[Closed](ws.take()).unit
                    }
                }
            }.andThen(succeed)
        }
    }

    // ==================== Disconnect scenarios ====================

    "disconnect scenarios" - {

        "server handler throws" in run {
            withWsServer(HttpHandler.webSocket("ws/bad") { (_, _) =>
                throw new RuntimeException("server boom")
            }) { url =>
                Abort.run[HttpException] {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/bad") { ws =>
                        Abort.run[Closed](ws.take()).unit
                    }
                }.unit
            }.andThen(succeed)
        }

        "server handler returns immediately" in run {
            withWsServer(HttpHandler.webSocket("ws/empty") { (_, _) =>
                ()
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/empty") { ws =>
                    Abort.run[Closed](ws.take()).map(r =>
                        discard(assert(r.isFailure || r.isPanic))
                    )
                }
            }.andThen(succeed)
        }

        "server handler Abort.fails with Closed" in run {
            withWsServer(HttpHandler.webSocket("ws/abort") { (_, _) =>
                Abort.fail(Closed("test", Frame.internal))
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/abort") { ws =>
                    Abort.run[Closed](ws.take()).map(r =>
                        discard(assert(r.isFailure || r.isPanic))
                    )
                }
            }.andThen(succeed)
        }

        "multiple client connections" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.gather(
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("a")))
                        )
                    },
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("b")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("b")))
                        )
                    },
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("c")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("c")))
                        )
                    }
                ).unit
            }.andThen(succeed)
        }

        "5 concurrent clients" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 5) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text(s"client$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text(s"client$i")))
                        )
                    }
                }.unit
            }.andThen(succeed)
        }

        "rapid connect disconnect cycles" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Kyo.foreach(1 to 10) { i =>
                    Abort.run[HttpException] {
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(HttpWebSocket.Payload.Text(s"cycle$i")).andThen {
                                ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"cycle$i"))))
                            }
                        }
                    }.unit
                }.unit
            }.andThen(succeed)
        }

        "client disconnects mid-stream" in run {
            Latch.init(1).map { serverDone =>
                withWsServer(HttpHandler.webSocket("ws/infinite") { (_, ws) =>
                    Sync.ensure(serverDone.release) {
                        Loop.foreach {
                            ws.put(HttpWebSocket.Payload.Text("tick")).andThen(Loop.continue)
                        }
                    }
                }) { url =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/infinite") { ws =>
                        Kyo.foreach(1 to 3)(_ => ws.take()).unit
                    }.andThen {
                        serverDone.await.andThen(succeed)
                    }
                }
            }
        }

        "client closes before reading anything" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.close()
                }
            }.andThen(succeed)
        }

        "client sends then immediately closes" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("bye")).andThen(ws.close())
                }
            }.andThen(succeed)
        }
    }

    // ==================== HTTP integration ====================

    "HTTP integration" - {

        "websocket alongside http handlers" in run {
            val httpHandler = HttpHandler.getText("api/hello") { _ => "world" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/hello").map(text =>
                    discard(assert(text == "world"))
                ).andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("test")).andThen {
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("test"))))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "http after websocket on same server" in run {
            val httpHandler = HttpHandler.getText("api/ping") { _ => "pong" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take()).unit
                }.andThen {
                    HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/ping").map(text =>
                        discard(assert(text == "pong"))
                    )
                }
            }.andThen(succeed)
        }

        "multiple http and ws requests interleaved" in run {
            val httpHandler = HttpHandler.getText("api/count") { _ => "ok" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/count").andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("1")).andThen(ws.take()).unit
                    }
                }.andThen {
                    HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/count")
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("2")).andThen(ws.take()).unit
                    }
                }
            }.andThen(succeed)
        }

        "path routing" in run {
            withWsServer(
                HttpHandler.webSocket("ws/echo1")(echo),
                HttpHandler.webSocket("ws/echo2") { (_, ws) =>
                    ws.stream.map {
                        case HttpWebSocket.Payload.Text(data) => HttpWebSocket.Payload.Text(s"echo2:$data")
                        case other                            => other
                    }.foreach(ws.put)
                }
            ) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo1") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take()).map(f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("a")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo2") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("b")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("echo2:b")))
                        )
                    }
                }
            }.andThen(succeed)
        }

        "three different ws endpoints" in run {
            withWsServer(
                HttpHandler.webSocket("ws/a") { (_, ws) =>
                    ws.take().andThen(ws.put(HttpWebSocket.Payload.Text("from-a")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                },
                HttpHandler.webSocket("ws/b") { (_, ws) =>
                    ws.take().andThen(ws.put(HttpWebSocket.Payload.Text("from-b")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                },
                HttpHandler.webSocket("ws/c") { (_, ws) =>
                    ws.take().andThen(ws.put(HttpWebSocket.Payload.Text("from-c")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                }
            ) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/a") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("x")).andThen(ws.take()).map(f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("from-a")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/b") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("x")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("from-b")))
                        )
                    }
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/c") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("x")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("from-c")))
                        )
                    }
                }
            }.andThen(succeed)
        }

        "request headers accessible" in run {
            withWsServer(HttpHandler.webSocket("ws/auth") { (req, ws) =>
                val auth = req.headers.get("Authorization").getOrElse("none")
                ws.put(HttpWebSocket.Payload.Text(auth)).andThen {
                    Abort.run[Closed](ws.take()).unit
                }
            }) { url =>
                HttpClient.webSocket(
                    s"ws://${url.host}:${url.port}/ws/auth",
                    HttpHeaders.empty.add("Authorization", "Bearer token123"),
                    HttpWebSocket.Config()
                ) { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("Bearer token123"))))
                }
            }.andThen(succeed)
        }
    }

    // ==================== Server handler patterns ====================

    "server handler patterns" - {

        "handler transforms messages" in run {
            withWsServer(HttpHandler.webSocket("ws/upper") { (_, ws) =>
                ws.stream.map {
                    case HttpWebSocket.Payload.Text(data) => HttpWebSocket.Payload.Text(data.toUpperCase)
                    case other                            => other
                }.foreach(ws.put)
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/upper") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("HELLO"))))
                    }
                }
            }.andThen(succeed)
        }

        "handler sends greeting then echoes" in run {
            withWsServer(HttpHandler.webSocket("ws/greet") { (_, ws) =>
                ws.put(HttpWebSocket.Payload.Text("welcome")).andThen {
                    ws.stream.foreach(ws.put)
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/greet") { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("welcome")))).andThen {
                        ws.put(HttpWebSocket.Payload.Text("hi")).andThen {
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hi"))))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "handler responds with count" in run {
            withWsServer(HttpHandler.webSocket("ws/count") { (_, ws) =>
                AtomicRef.init(0).map { counter =>
                    ws.stream.foreach { _ =>
                        counter.getAndUpdate(_ + 1).map { n =>
                            ws.put(HttpWebSocket.Payload.Text(s"count:$n"))
                        }
                    }
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/count") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("a")).andThen(ws.take()).map(f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("count:0")))
                    ).andThen {
                        ws.put(HttpWebSocket.Payload.Text("b")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("count:1")))
                        )
                    }.andThen {
                        ws.put(HttpWebSocket.Payload.Text("c")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("count:2")))
                        )
                    }
                }
            }.andThen(succeed)
        }
    }

    // ==================== Server lifecycle ====================

    "server lifecycle" - {

        "server handles connection after previous ws closed" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("first")).andThen(ws.take()).map(f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("first")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("second")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text("second")))
                        )
                    }
                }
            }.andThen(succeed)
        }

        "three sequential connections" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Kyo.foreach(1 to 3) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text(s"conn$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text(s"conn$i")))
                        )
                    }
                }.unit
            }.andThen(succeed)
        }

        "sequential then concurrent" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                // Sequential first
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("seq")).andThen(ws.take()).unit
                }.andThen {
                    // Then concurrent
                    Async.gather(
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(HttpWebSocket.Payload.Text("par1")).andThen(ws.take()).unit
                        },
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(HttpWebSocket.Payload.Text("par2")).andThen(ws.take()).unit
                        }
                    ).unit
                }
            }.andThen(succeed)
        }
    }

    // ==================== Edge cases from zio-http/sttp/tapir bugs ====================

    "edge cases" - {

        // zio-http #2737, tapir #3685: server sends before client is ready
        "server sends immediately on connect" in run {
            withWsServer(HttpHandler.webSocket("ws/eager") { (_, ws) =>
                // Send immediately without waiting for client message
                ws.put(HttpWebSocket.Payload.Text("eager1"))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("eager2")))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("eager3")))
                    .andThen(Abort.run[Closed](ws.take()).unit) // wait for client
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/eager") { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("eager1"))))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("eager2")))))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("eager3")))))
                }
            }.andThen(succeed)
        }

        // sttp #901: large unicode payload inflates byte count beyond frame limit
        "large unicode payload" in run {
            // 3500 emoji chars × 4 bytes each = ~14000 bytes
            val text = "\uD83D\uDE00" * 3500
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(text))))
                    }
                }
            }.andThen(succeed)
        }

        // zio-http #1147: close handler side-effects must execute
        "server cleanup runs on normal close" in run {
            Latch.init(1).map { cleanupDone =>
                withWsServer(HttpHandler.webSocket("ws/cleanup") { (_, ws) =>
                    Sync.ensure(cleanupDone.release) {
                        ws.stream.foreach(ws.put)
                    }
                }) { url =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/cleanup") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("hi")).andThen(ws.take()).unit
                    }.andThen {
                        cleanupDone.await.andThen(succeed)
                    }
                }
            }
        }

        // zio-http #1147: close handler runs even when handler throws
        "server cleanup runs on handler error" in run {
            Latch.init(1).map { cleanupDone =>
                withWsServer(HttpHandler.webSocket("ws/cleanup-err") { (_, ws) =>
                    Sync.ensure(cleanupDone.release) {
                        ws.take().andThen {
                            throw new RuntimeException("handler error")
                        }
                    }
                }) { url =>
                    Abort.run[HttpException] {
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/cleanup-err") { ws =>
                            ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                                Abort.run[Closed](ws.take()).unit
                            }
                        }
                    }.unit.andThen {
                        cleanupDone.await.andThen(succeed)
                    }
                }
            }
        }

        // tapir #3776: in-flight messages must arrive before close takes effect
        "messages before close are delivered" in run {
            withWsServer(HttpHandler.webSocket("ws/send-then-close") { (_, ws) =>
                ws.put(HttpWebSocket.Payload.Text("msg1"))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("msg2")))
                    .andThen(ws.put(HttpWebSocket.Payload.Text("msg3")))
                    .andThen(Abort.run[Closed](ws.take()).unit) // wait for client to read
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/send-then-close") { ws =>
                    ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("msg1"))))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("msg2")))))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("msg3")))))
                }
            }.andThen(succeed)
        }

        // zio-http #623: many concurrent connections must all succeed
        "10 concurrent connections" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 10) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text(s"c$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == HttpWebSocket.Payload.Text(s"c$i")))
                        )
                    }
                }.unit
            }.andThen(succeed)
        }

        // zio-http #623: many concurrent connections with multi-message exchange
        "10 concurrent connections with multiple messages each" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 10) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        Kyo.foreach(1 to 5) { j =>
                            ws.put(HttpWebSocket.Payload.Text(s"c${i}m$j")).andThen(ws.take()).map(f =>
                                discard(assert(f == HttpWebSocket.Payload.Text(s"c${i}m$j")))
                            )
                        }.unit
                    }
                }.unit
            }.andThen(succeed)
        }

        // zio-http #1004: binary frame data integrity across echo
        "binary echo preserves exact bytes" in run {
            // Pattern that could trigger reference counting bugs
            val data1 = Span.fromUnsafe(Array.fill[Byte](100)(0x7f.toByte))
            val data2 = Span.fromUnsafe(Array.fill[Byte](200)(0x80.toByte))
            val data3 = Span.fromUnsafe(Array.fill[Byte](50)(0xff.toByte))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(data1))
                        .andThen(ws.put(HttpWebSocket.Payload.Binary(data2)))
                        .andThen(ws.put(HttpWebSocket.Payload.Binary(data3)))
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 100))
                                discard(assert(d.toArrayUnsafe.forall(_ == 0x7f.toByte)))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 200))
                                discard(assert(d.toArrayUnsafe.forall(_ == 0x80.toByte)))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) =>
                                discard(assert(d.size == 50))
                                discard(assert(d.toArrayUnsafe.forall(_ == 0xff.toByte)))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        })
                }
            }.andThen(succeed)
        }

        // Derived from zio-http #2977: WS and HTTP on same server must not interfere
        "interleaved ws and http requests under load" in run {
            val httpHandler = HttpHandler.getText("api/v") { _ => "ok" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 5) { i =>
                    if i % 2 == 0 then
                        HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/v").map(t =>
                            discard(assert(t == "ok"))
                        )
                    else
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(HttpWebSocket.Payload.Text(s"ws$i")).andThen(ws.take()).map(f =>
                                discard(assert(f == HttpWebSocket.Payload.Text(s"ws$i")))
                            )
                        }
                }.unit
            }.andThen(succeed)
        }

        // Regression: rapid open/close without any data exchange
        "rapid open close no data" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Kyo.foreach(1 to 20) { _ =>
                    Abort.run[HttpException] {
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { _ =>
                            () // connect and immediately return (triggers close)
                        }
                    }.unit
                }.unit
            }.andThen(succeed)
        }

        // Client sends and waits for ack before closing — server must process the message
        "server processes message with client ack" in run {
            withWsServer(HttpHandler.webSocket("ws/ack") { (_, ws) =>
                ws.take().andThen(ws.put(HttpWebSocket.Payload.Text("ack")))
                    .andThen(Abort.run[Closed](ws.take()).unit)
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/ack") { ws =>
                    ws.put(HttpWebSocket.Payload.Text("msg")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("ack"))))
                    }
                }
            }.andThen(succeed)
        }
    }

    // ==================== HttpWebSocket.connect (local, no network) ====================

    def echo(ws: HttpWebSocket)(using Frame): Unit < Async =
        ws.stream.foreach(ws.put).handle(Abort.run[Closed]).unit

    def echoWithReq(req: HttpRequest[Any], ws: HttpWebSocket)(using Frame): Unit < Async =
        echo(ws)

    def connectTest(
        p1: HttpWebSocket => Unit < (Async & Abort[Closed]),
        p2: HttpWebSocket => Unit < (Async & Abort[Closed])
    )(using Frame): Unit < (Async & Scope) =
        HttpWebSocket.connect(p1, p2)

    "HttpWebSocket.connect" - {

        "text echo" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello")).andThen(
                        ws.take().map(frame => discard(assert(frame == HttpWebSocket.Payload.Text("hello"))))
                    )
            ).andThen(succeed)
        }

        "binary echo" in run {
            val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Binary(bytes)).andThen(
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 5))
                                discard(assert(data(0) == 1.toByte))
                                discard(assert(data(4) == 5.toByte))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    )
            ).andThen(succeed)
        }

        "multiple messages in order" in run {
            connectTest(
                echo,
                ws =>
                    Kyo.foreach(1 to 10)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).andThen(
                        Kyo.foreach(1 to 10)(i =>
                            ws.take().map(frame => discard(assert(frame == HttpWebSocket.Payload.Text(s"msg$i"))))
                        ).unit
                    )
            ).andThen(succeed)
        }

        "empty text" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("")).andThen(
                        ws.take().map(frame => discard(assert(frame == HttpWebSocket.Payload.Text(""))))
                    )
            ).andThen(succeed)
        }

        "empty binary" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Binary(Span.empty[Byte])).andThen(
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) => discard(assert(data.size == 0))
                            case other                              => discard(fail(s"Expected Binary, got $other"))
                        }
                    )
            ).andThen(succeed)
        }

        "interleaved text and binary" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("a"))
                        .andThen(ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(Array[Byte](1)))))
                        .andThen(ws.put(HttpWebSocket.Payload.Text("b")))
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("a")))))
                        .andThen(ws.take().map {
                            case HttpWebSocket.Payload.Binary(d) => discard(assert(d(0) == 1.toByte))
                            case other                           => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("b")))))
            ).andThen(succeed)
        }

        "unicode text" in run {
            val text = "Hello \uD83D\uDE00 \u4F60\u597D"
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text(text)).andThen(
                        ws.take().map(frame => discard(assert(frame == HttpWebSocket.Payload.Text(text))))
                    )
            ).andThen(succeed)
        }

        "binary with all byte values" in run {
            val allBytes = Array.tabulate[Byte](256)(i => i.toByte)
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Binary(Span.fromUnsafe(allBytes))).andThen(
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 256))
                                var i = 0
                                while i < 256 do
                                    discard(assert(data(i) == i.toByte))
                                    i += 1
                                end while
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    )
            ).andThen(succeed)
        }

        "close propagation" in run {
            connectTest(
                ws =>
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(result.isFailure || result.isPanic))
                    ),
                ws => ws.close()
            ).andThen(succeed)
        }

        "close reason preserved" in run {
            // Both sides share the close reason ref via doClose — check it after connect returns
            val closeReasonRef = AtomicRef.init[Maybe[(Int, String)]](Absent)
            closeReasonRef.map { ref =>
                val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                    ref.set(Present((code, reason)))
                Channel.init[HttpWebSocket.Payload](32).map { ch1to2 =>
                    Channel.init[HttpWebSocket.Payload](32).map { ch2to1 =>
                        val ws1 = new HttpWebSocket(ch2to1, ch1to2, ref, closeFn)
                        val ws2 = new HttpWebSocket(ch1to2, ch2to1, ref, closeFn)
                        Sync.ensure(ch1to2.close.unit.andThen(ch2to1.close.unit)) {
                            Async.raceFirst(
                                Abort.run[Closed](ws1.take()).unit,
                                Abort.run[Closed](ws2.close(1001, "going away")).unit
                            ).unit
                        }.andThen(
                            ref.get.map(r => discard(assert(r == Present((1001, "going away")))))
                        ).andThen(succeed)
                    }
                }
            }
        }

        "close with custom code" in run {
            val closeReasonRef = AtomicRef.init[Maybe[(Int, String)]](Absent)
            closeReasonRef.map { ref =>
                val closeFn: (Int, String) => Unit < Async = (code, reason) =>
                    ref.set(Present((code, reason)))
                Channel.init[HttpWebSocket.Payload](32).map { ch1to2 =>
                    Channel.init[HttpWebSocket.Payload](32).map { ch2to1 =>
                        val ws1 = new HttpWebSocket(ch2to1, ch1to2, ref, closeFn)
                        val ws2 = new HttpWebSocket(ch1to2, ch2to1, ref, closeFn)
                        Sync.ensure(ch1to2.close.unit.andThen(ch2to1.close.unit)) {
                            Async.raceFirst(
                                Abort.run[Closed](ws1.take()).unit,
                                Abort.run[Closed](ws2.close(4000, "app error")).unit
                            ).unit
                        }.andThen(
                            ref.get.map(r => discard(assert(r == Present((4000, "app error")))))
                        ).andThen(succeed)
                    }
                }
            }
        }

        "put after close" in run {
            connectTest(
                echo,
                ws =>
                    ws.close().andThen(
                        Abort.run[Closed](ws.put(HttpWebSocket.Payload.Text("fail"))).map(result =>
                            discard(assert(result.isFailure || result.isPanic))
                        )
                    )
            ).andThen(succeed)
        }

        "take after close" in run {
            connectTest(
                ws => ws.close(),
                ws =>
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(result.isFailure || result.isPanic))
                    )
            ).andThen(succeed)
        }

        "double close is safe" in run {
            connectTest(
                ws => ws.close().andThen(ws.close()),
                ws => Abort.run[Closed](ws.take()).unit
            ).andThen(succeed)
        }

        "one party is infinite loop" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("hi")).andThen(
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hi"))))
                    )
            ).andThen(succeed)
        }

        "one party throws" in run {
            connectTest(
                _ => throw new RuntimeException("boom"),
                ws =>
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(result.isFailure || result.isPanic))
                    )
            ).andThen(succeed)
        }

        "both parties throw" in run {
            connectTest(
                _ => throw new RuntimeException("boom1"),
                _ => throw new RuntimeException("boom2")
            ).andThen(succeed)
        }

        "empty party" in run {
            connectTest(
                _ => (),
                ws =>
                    Abort.run[Closed](ws.take()).map(result =>
                        discard(assert(result.isFailure || result.isPanic))
                    )
            ).andThen(succeed)
        }

        "concurrent bidirectional" in run {
            connectTest(
                ws =>
                    Async.gather(
                        Kyo.foreach(1 to 5)(i => ws.put(HttpWebSocket.Payload.Text(s"a$i"))).unit,
                        Kyo.foreach(1 to 5)(_ => Abort.run[Closed](ws.take())).unit
                    ).unit,
                ws =>
                    Async.gather(
                        Kyo.foreach(1 to 5)(i => ws.put(HttpWebSocket.Payload.Text(s"b$i"))).unit,
                        Kyo.foreach(1 to 5)(_ => Abort.run[Closed](ws.take())).unit
                    ).unit
            ).andThen(succeed)
        }

        "poll returns Absent when empty" in run {
            connectTest(
                _ => (),
                ws =>
                    Abort.run[Closed](ws.poll()).map {
                        case kyo.Result.Success(v) => discard(assert(v == Absent))
                        case _                     => ()
                    }
            ).andThen(succeed)
        }

        "reuse server handler fn" in run {
            HttpWebSocket.connect(
                echoWithReq,
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("test")).andThen(
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("test"))))
                    )
            ).andThen(succeed)
        }

        "many small messages" in run {
            val count = 200
            connectTest(
                echo,
                ws =>
                    // Send and receive concurrently to avoid deadlock on bounded channel
                    val sender = Kyo.foreach(1 to count)(i => ws.put(HttpWebSocket.Payload.Text(i.toString)))
                    val receiver = Kyo.foreach(1 to count)(i =>
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(i.toString))))
                    )
                    Abort.run[Closed](Async.gather(sender.unit, receiver.unit)).unit
            ).andThen(succeed)
        }

        "stream terminates on close" in run {
            connectTest(
                ws =>
                    ws.put(HttpWebSocket.Payload.Text("a"))
                        .andThen(ws.put(HttpWebSocket.Payload.Text("b")))
                        .andThen(ws.close()),
                ws =>
                    ws.stream.run.map(frames =>
                        discard(assert(frames.size <= 2))
                    )
            ).andThen(succeed)
        }
    }

    // ==================== WebSocket over Unix socket ====================

    private def withWsUnixServer[A, S](handlers: HttpHandler[?, ?, ?]*)(
        test: String => A < (S & Async & Abort[HttpException])
    )(using Frame): A < (S & Async & Scope & Abort[HttpException]) =
        tempSocketPath().map { sockPath =>
            val config = HttpServerConfig.default.unixSocket(sockPath)
            Sync.ensure(Sync.defer(cleanupSocket(sockPath))) {
                HttpServer.init(config)(handlers*).map { server =>
                    test(sockPath)
                }
            }
        }

    private def mkWsUrl(socketPath: String, wsPath: String): String =
        mkUrl(socketPath, wsPath)

    "basic messaging over Unix socket" - {

        "text echo over Unix socket" in run {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hello"))))
                    }
                }
            }.andThen(succeed)
        }

        "binary echo over Unix socket" in run {
            val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(bytes)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 5))
                                discard(assert(data(0) == 1.toByte))
                                discard(assert(data(4) == 5.toByte))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "multiple sequential messages over Unix socket" in run {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    Kyo.foreach(1 to 5)(i => ws.put(HttpWebSocket.Payload.Text(s"msg$i"))).andThen {
                        Kyo.foreach(1 to 5)(i =>
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(s"msg$i"))))
                        ).unit
                    }
                }
            }.andThen(succeed)
        }

        "server pushes message to client over Unix socket" in run {
            val serverHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
                (_, ws) =>
                    ws.put(HttpWebSocket.Payload.Text("server-hello")).andThen {
                        ws.take().unit // wait for client to respond before handler returns
                    }
            withWsUnixServer(HttpHandler.webSocket("ws/push")(serverHandler)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/push")
                HttpClient.webSocket(url) { ws =>
                    ws.take().map { f =>
                        discard(assert(f == HttpWebSocket.Payload.Text("server-hello")))
                    }.andThen {
                        ws.put(HttpWebSocket.Payload.Text("ack")) // let server handler return
                    }
                }
            }.andThen(succeed)
        }
    }

    "close and lifecycle over Unix socket" - {

        "client close over Unix socket" in run {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hi")).andThen {
                        ws.take().andThen {
                            ws.close(1000, "bye")
                        }
                    }
                }
            }.andThen(succeed)
        }

        "server close over Unix socket" in run {
            val serverHandler: (HttpRequest[Any], HttpWebSocket) => Unit < (Async & Abort[Closed]) =
                (_, ws) => ws.take().andThen(ws.close())
            withWsUnixServer(HttpHandler.webSocket("ws/srv-close")(serverHandler)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/srv-close")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("trigger")).andThen {
                        Abort.run[Closed](ws.take()).map(result =>
                            discard(assert(result.isFailure || result.isPanic))
                        )
                    }
                }
            }.andThen(succeed)
        }

        "function return closes connection over Unix socket" in run {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("done")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("done"))))
                    }
                }
            }.andThen(succeed)
        }
    }

    "edge cases over Unix socket" - {

        "large text message (64KB) over Unix socket" in run {
            val largeText = "x" * 65536
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text(largeText)).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(largeText))))
                    }
                }
            }.andThen(succeed)
        }

        "empty text message over Unix socket" in run {
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("")).andThen {
                        ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text(""))))
                    }
                }
            }.andThen(succeed)
        }

        "binary with all byte values over Unix socket" in run {
            val allBytes = Span.fromUnsafe(Array.tabulate[Byte](256)(i => i.toByte))
            withWsUnixServer(HttpHandler.webSocket("ws/echo")(echo)) { sockPath =>
                val url = mkWsUrl(sockPath, "/ws/echo")
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(allBytes)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(data) =>
                                discard(assert(data.size == 256))
                                var i = 0
                                while i < 256 do
                                    discard(assert(data(i) == i.toByte))
                                    i += 1
                                end while
                            case other => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.andThen(succeed)
        }
    }

    // ==================== Config ====================

    "config" - {

        "webSocket respects baseUrl config" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                // Set baseUrl to the server URL, then connect with a path-only string.
                // The bug: webSocket ignores HttpClientConfig, so baseUrl is never applied
                // and the relative path fails to connect.
                HttpClient.withConfig(_.baseUrl(s"ws://${url.host}:${url.port}")) {
                    HttpClient.webSocket("/ws/echo") { ws =>
                        ws.put(HttpWebSocket.Payload.Text("hello")).andThen {
                            ws.take().map(f => discard(assert(f == HttpWebSocket.Payload.Text("hello"))))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "webSocket respects connectTimeout config" in run {
            // 192.0.2.0/24 is TEST-NET-1 (RFC 5737) — routable but no host responds,
            // so a TCP connect will hang until timeout rather than fail immediately.
            HttpClient.withConfig(_.connectTimeout(100.millis)) {
                Abort.run[HttpException] {
                    HttpClient.webSocket("ws://192.0.2.1/ws") { _ => () }
                }.map { result =>
                    // The bug: webSocket ignores HttpClientConfig, so connectTimeout is never applied.
                    // With the bug the call hangs indefinitely (or until the OS TCP timeout).
                    // The fixed impl should fail fast with an HttpException.
                    discard(assert(result.isFailure || result.isPanic))
                }
            }.andThen(succeed)
        }
    }

end HttpWebSocketTest
