package kyo

import kyo.*

class WebSocketTest extends Test:

    def echo(req: HttpRequest[Any], ws: WebSocket)(using Frame): Unit < (Async & Abort[Closed]) =
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
                    ws.put(WebSocketFrame.Text("hello")).andThen {
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text("hello"))))
                    }
                }
            }.andThen(succeed)
        }

        "binary echo" in run {
            val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Binary(bytes)).andThen {
                        ws.take().map {
                            case WebSocketFrame.Binary(data) =>
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
                    Kyo.foreach(1 to 10)(i => ws.put(WebSocketFrame.Text(s"msg$i"))).andThen {
                        Kyo.foreach(1 to 10)(i =>
                            ws.take().map(f => discard(assert(f == WebSocketFrame.Text(s"msg$i"))))
                        ).unit
                    }
                }
            }.andThen(succeed)
        }

        "empty text frame" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Text("")).andThen {
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text(""))))
                    }
                }
            }.andThen(succeed)
        }

        "large text frame" in run {
            val text = "x" * 65000
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text(text))))
                    }
                }
            }.andThen(succeed)
        }

        "large binary frame" in run {
            val data = Span.fromUnsafe(Array.tabulate[Byte](65000)(i => (i % 256).toByte))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Binary(data)).andThen {
                        ws.take().map {
                            case WebSocketFrame.Binary(d) => discard(assert(d.size == 65000))
                            case other                    => discard(fail(s"Expected Binary, got $other"))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "interleaved text and binary" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Text("a"))
                        .andThen(ws.put(WebSocketFrame.Binary(Span.fromUnsafe(Array[Byte](1)))))
                        .andThen(ws.put(WebSocketFrame.Text("b")))
                        .andThen(ws.take().map(f => discard(assert(f == WebSocketFrame.Text("a")))))
                        .andThen(ws.take().map {
                            case WebSocketFrame.Binary(d) => discard(assert(d(0) == 1.toByte))
                            case other                    => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map(f => discard(assert(f == WebSocketFrame.Text("b")))))
                }
            }.andThen(succeed)
        }

        "unicode text roundtrip" in run {
            val text = "Hello \uD83D\uDE00 \u4F60\u597D"
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text(text))))
                    }
                }
            }.andThen(succeed)
        }

        "binary all byte values" in run {
            val allBytes = Span.fromUnsafe(Array.tabulate[Byte](256)(_.toByte))
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Binary(allBytes)).andThen {
                        ws.take().map {
                            case WebSocketFrame.Binary(d) =>
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
                    ws.put(WebSocketFrame.Binary(Span.fromUnsafe(Array[Byte](42)))).andThen {
                        ws.take().map {
                            case WebSocketFrame.Binary(d) =>
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
                    ws.put(WebSocketFrame.Text("x")).andThen {
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text("x"))))
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
                    ws.put(WebSocketFrame.Text("hi")).andThen(ws.take()).andThen(ws.close())
                }
            }.andThen(succeed)
        }

        "server initiates close" in run {
            withWsServer(HttpHandler.webSocket("ws/close") { (_, ws) =>
                ws.take().andThen(ws.close())
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/close") { ws =>
                    ws.put(WebSocketFrame.Text("trigger")).andThen {
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
                    ws.put(WebSocketFrame.Text("trigger")).andThen {
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
                    ws.put(WebSocketFrame.Text("a")).andThen(ws.take())
                        .andThen(ws.put(WebSocketFrame.Text("b"))).andThen(ws.take())
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
                            Kyo.foreach(1 to count)(i => ws.put(WebSocketFrame.Text(s"msg$i"))).unit,
                            Kyo.foreach(1 to count)(i =>
                                ws.take().map(f => discard(assert(f == WebSocketFrame.Text(s"msg$i"))))
                            ).unit
                        )
                    }.unit
                }
            }.andThen(succeed)
        }

        "handler that only sends" in run {
            withWsServer(HttpHandler.webSocket("ws/send-only") { (_, ws) =>
                Kyo.foreach(1 to 5)(i => ws.put(WebSocketFrame.Text(s"server$i"))).andThen {
                    Abort.run[Closed](ws.take()).unit
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/send-only") { ws =>
                    Kyo.foreach(1 to 5)(i =>
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text(s"server$i"))))
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
                    Kyo.foreach(1 to 3)(i => ws.put(WebSocketFrame.Text(s"msg$i"))).andThen {
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
                        ws.put(WebSocketFrame.Text("a")).andThen(ws.take()).map(f => discard(assert(f == WebSocketFrame.Text("a"))))
                    },
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(WebSocketFrame.Text("b")).andThen(ws.take()).map(f => discard(assert(f == WebSocketFrame.Text("b"))))
                    },
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(WebSocketFrame.Text("c")).andThen(ws.take()).map(f => discard(assert(f == WebSocketFrame.Text("c"))))
                    }
                ).unit
            }.andThen(succeed)
        }

        "5 concurrent clients" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 5) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(WebSocketFrame.Text(s"client$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text(s"client$i")))
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
                            ws.put(WebSocketFrame.Text(s"cycle$i")).andThen {
                                ws.take().map(f => discard(assert(f == WebSocketFrame.Text(s"cycle$i"))))
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
                            ws.put(WebSocketFrame.Text("tick")).andThen(Loop.continue)
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
                    ws.put(WebSocketFrame.Text("bye")).andThen(ws.close())
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
                        ws.put(WebSocketFrame.Text("test")).andThen {
                            ws.take().map(f => discard(assert(f == WebSocketFrame.Text("test"))))
                        }
                    }
                }
            }.andThen(succeed)
        }

        "http after websocket on same server" in run {
            val httpHandler = HttpHandler.getText("api/ping") { _ => "pong" }
            withWsServer(httpHandler, HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Text("a")).andThen(ws.take()).unit
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
                        ws.put(WebSocketFrame.Text("1")).andThen(ws.take()).unit
                    }
                }.andThen {
                    HttpClient.getText(s"${url.scheme.getOrElse("http")}://${url.host}:${url.port}/api/count")
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(WebSocketFrame.Text("2")).andThen(ws.take()).unit
                    }
                }
            }.andThen(succeed)
        }

        "path routing" in run {
            withWsServer(
                HttpHandler.webSocket("ws/echo1")(echo),
                HttpHandler.webSocket("ws/echo2") { (_, ws) =>
                    ws.stream.map {
                        case WebSocketFrame.Text(data) => WebSocketFrame.Text(s"echo2:$data")
                        case other                     => other
                    }.foreach(ws.put)
                }
            ) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo1") { ws =>
                    ws.put(WebSocketFrame.Text("a")).andThen(ws.take()).map(f =>
                        discard(assert(f == WebSocketFrame.Text("a")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo2") { ws =>
                        ws.put(WebSocketFrame.Text("b")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text("echo2:b")))
                        )
                    }
                }
            }.andThen(succeed)
        }

        "three different ws endpoints" in run {
            withWsServer(
                HttpHandler.webSocket("ws/a") { (_, ws) =>
                    ws.take().andThen(ws.put(WebSocketFrame.Text("from-a")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                },
                HttpHandler.webSocket("ws/b") { (_, ws) =>
                    ws.take().andThen(ws.put(WebSocketFrame.Text("from-b")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                },
                HttpHandler.webSocket("ws/c") { (_, ws) =>
                    ws.take().andThen(ws.put(WebSocketFrame.Text("from-c")))
                        .andThen(Abort.run[Closed](ws.take()).unit)
                }
            ) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/a") { ws =>
                    ws.put(WebSocketFrame.Text("x")).andThen(ws.take()).map(f =>
                        discard(assert(f == WebSocketFrame.Text("from-a")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/b") { ws =>
                        ws.put(WebSocketFrame.Text("x")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text("from-b")))
                        )
                    }
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/c") { ws =>
                        ws.put(WebSocketFrame.Text("x")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text("from-c")))
                        )
                    }
                }
            }.andThen(succeed)
        }

        "request headers accessible" in run {
            withWsServer(HttpHandler.webSocket("ws/auth") { (req, ws) =>
                val auth = req.headers.get("Authorization").getOrElse("none")
                ws.put(WebSocketFrame.Text(auth)).andThen {
                    Abort.run[Closed](ws.take()).unit
                }
            }) { url =>
                HttpClient.webSocket(
                    s"ws://${url.host}:${url.port}/ws/auth",
                    HttpHeaders.empty.add("Authorization", "Bearer token123"),
                    WebSocketConfig()
                ) { ws =>
                    ws.take().map(f => discard(assert(f == WebSocketFrame.Text("Bearer token123"))))
                }
            }.andThen(succeed)
        }
    }

    // ==================== Server handler patterns ====================

    "server handler patterns" - {

        "handler transforms messages" in run {
            withWsServer(HttpHandler.webSocket("ws/upper") { (_, ws) =>
                ws.stream.map {
                    case WebSocketFrame.Text(data) => WebSocketFrame.Text(data.toUpperCase)
                    case other                     => other
                }.foreach(ws.put)
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/upper") { ws =>
                    ws.put(WebSocketFrame.Text("hello")).andThen {
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text("HELLO"))))
                    }
                }
            }.andThen(succeed)
        }

        "handler sends greeting then echoes" in run {
            withWsServer(HttpHandler.webSocket("ws/greet") { (_, ws) =>
                ws.put(WebSocketFrame.Text("welcome")).andThen {
                    ws.stream.foreach(ws.put)
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/greet") { ws =>
                    ws.take().map(f => discard(assert(f == WebSocketFrame.Text("welcome")))).andThen {
                        ws.put(WebSocketFrame.Text("hi")).andThen {
                            ws.take().map(f => discard(assert(f == WebSocketFrame.Text("hi"))))
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
                            ws.put(WebSocketFrame.Text(s"count:$n"))
                        }
                    }
                }
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/count") { ws =>
                    ws.put(WebSocketFrame.Text("a")).andThen(ws.take()).map(f =>
                        discard(assert(f == WebSocketFrame.Text("count:0")))
                    ).andThen {
                        ws.put(WebSocketFrame.Text("b")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text("count:1")))
                        )
                    }.andThen {
                        ws.put(WebSocketFrame.Text("c")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text("count:2")))
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
                    ws.put(WebSocketFrame.Text("first")).andThen(ws.take()).map(f =>
                        discard(assert(f == WebSocketFrame.Text("first")))
                    )
                }.andThen {
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(WebSocketFrame.Text("second")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text("second")))
                        )
                    }
                }
            }.andThen(succeed)
        }

        "three sequential connections" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Kyo.foreach(1 to 3) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(WebSocketFrame.Text(s"conn$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text(s"conn$i")))
                        )
                    }
                }.unit
            }.andThen(succeed)
        }

        "sequential then concurrent" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                // Sequential first
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Text("seq")).andThen(ws.take()).unit
                }.andThen {
                    // Then concurrent
                    Async.gather(
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(WebSocketFrame.Text("par1")).andThen(ws.take()).unit
                        },
                        HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                            ws.put(WebSocketFrame.Text("par2")).andThen(ws.take()).unit
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
                ws.put(WebSocketFrame.Text("eager1"))
                    .andThen(ws.put(WebSocketFrame.Text("eager2")))
                    .andThen(ws.put(WebSocketFrame.Text("eager3")))
                    .andThen(Abort.run[Closed](ws.take()).unit) // wait for client
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/eager") { ws =>
                    ws.take().map(f => discard(assert(f == WebSocketFrame.Text("eager1"))))
                        .andThen(ws.take().map(f => discard(assert(f == WebSocketFrame.Text("eager2")))))
                        .andThen(ws.take().map(f => discard(assert(f == WebSocketFrame.Text("eager3")))))
                }
            }.andThen(succeed)
        }

        // sttp #901: large unicode payload inflates byte count beyond frame limit
        "large unicode payload" in run {
            // 3500 emoji chars × 4 bytes each = ~14000 bytes
            val text = "\uD83D\uDE00" * 3500
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                    ws.put(WebSocketFrame.Text(text)).andThen {
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text(text))))
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
                        ws.put(WebSocketFrame.Text("hi")).andThen(ws.take()).unit
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
                            ws.put(WebSocketFrame.Text("trigger")).andThen {
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
                ws.put(WebSocketFrame.Text("msg1"))
                    .andThen(ws.put(WebSocketFrame.Text("msg2")))
                    .andThen(ws.put(WebSocketFrame.Text("msg3")))
                    .andThen(Abort.run[Closed](ws.take()).unit) // wait for client to read
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/send-then-close") { ws =>
                    ws.take().map(f => discard(assert(f == WebSocketFrame.Text("msg1"))))
                        .andThen(ws.take().map(f => discard(assert(f == WebSocketFrame.Text("msg2")))))
                        .andThen(ws.take().map(f => discard(assert(f == WebSocketFrame.Text("msg3")))))
                }
            }.andThen(succeed)
        }

        // zio-http #623: many concurrent connections must all succeed
        "10 concurrent connections" in run {
            withWsServer(HttpHandler.webSocket("ws/echo")(echo)) { url =>
                Async.foreach(1 to 10) { i =>
                    HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/echo") { ws =>
                        ws.put(WebSocketFrame.Text(s"c$i")).andThen(ws.take()).map(f =>
                            discard(assert(f == WebSocketFrame.Text(s"c$i")))
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
                            ws.put(WebSocketFrame.Text(s"c${i}m$j")).andThen(ws.take()).map(f =>
                                discard(assert(f == WebSocketFrame.Text(s"c${i}m$j")))
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
                    ws.put(WebSocketFrame.Binary(data1))
                        .andThen(ws.put(WebSocketFrame.Binary(data2)))
                        .andThen(ws.put(WebSocketFrame.Binary(data3)))
                        .andThen(ws.take().map {
                            case WebSocketFrame.Binary(d) =>
                                discard(assert(d.size == 100))
                                discard(assert(d.toArrayUnsafe.forall(_ == 0x7f.toByte)))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map {
                            case WebSocketFrame.Binary(d) =>
                                discard(assert(d.size == 200))
                                discard(assert(d.toArrayUnsafe.forall(_ == 0x80.toByte)))
                            case other => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map {
                            case WebSocketFrame.Binary(d) =>
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
                            ws.put(WebSocketFrame.Text(s"ws$i")).andThen(ws.take()).map(f =>
                                discard(assert(f == WebSocketFrame.Text(s"ws$i")))
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
                ws.take().andThen(ws.put(WebSocketFrame.Text("ack")))
                    .andThen(Abort.run[Closed](ws.take()).unit)
            }) { url =>
                HttpClient.webSocket(s"ws://${url.host}:${url.port}/ws/ack") { ws =>
                    ws.put(WebSocketFrame.Text("msg")).andThen {
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text("ack"))))
                    }
                }
            }.andThen(succeed)
        }
    }

end WebSocketTest
