package kyo

import kyo.*

class WebSocketLocalTest extends Test:

    def echo(ws: WebSocket)(using Frame): Unit < Async =
        ws.stream.foreach(ws.put).handle(Abort.run[Closed]).unit

    def echoWithReq(req: HttpRequest[Any], ws: WebSocket)(using Frame): Unit < Async =
        echo(ws)

    def connectTest(
        p1: WebSocket => Unit < (Async & Abort[Closed]),
        p2: WebSocket => Unit < (Async & Abort[Closed])
    )(using Frame): Unit < (Async & Scope) =
        WebSocket.connect(p1, p2)

    "WebSocket.connect" - {

        "text echo" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(WebSocketFrame.Text("hello")).andThen(
                        ws.take().map(frame => discard(assert(frame == WebSocketFrame.Text("hello"))))
                    )
            ).andThen(succeed)
        }

        "binary echo" in run {
            val bytes = Span.fromUnsafe(Array[Byte](1, 2, 3, 4, 5))
            connectTest(
                echo,
                ws =>
                    ws.put(WebSocketFrame.Binary(bytes)).andThen(
                        ws.take().map {
                            case WebSocketFrame.Binary(data) =>
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
                    Kyo.foreach(1 to 10)(i => ws.put(WebSocketFrame.Text(s"msg$i"))).andThen(
                        Kyo.foreach(1 to 10)(i =>
                            ws.take().map(frame => discard(assert(frame == WebSocketFrame.Text(s"msg$i"))))
                        ).unit
                    )
            ).andThen(succeed)
        }

        "empty text" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(WebSocketFrame.Text("")).andThen(
                        ws.take().map(frame => discard(assert(frame == WebSocketFrame.Text(""))))
                    )
            ).andThen(succeed)
        }

        "empty binary" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(WebSocketFrame.Binary(Span.empty[Byte])).andThen(
                        ws.take().map {
                            case WebSocketFrame.Binary(data) => discard(assert(data.size == 0))
                            case other                       => discard(fail(s"Expected Binary, got $other"))
                        }
                    )
            ).andThen(succeed)
        }

        "interleaved text and binary" in run {
            connectTest(
                echo,
                ws =>
                    ws.put(WebSocketFrame.Text("a"))
                        .andThen(ws.put(WebSocketFrame.Binary(Span.fromUnsafe(Array[Byte](1)))))
                        .andThen(ws.put(WebSocketFrame.Text("b")))
                        .andThen(ws.take().map(f => discard(assert(f == WebSocketFrame.Text("a")))))
                        .andThen(ws.take().map {
                            case WebSocketFrame.Binary(d) => discard(assert(d(0) == 1.toByte))
                            case other                    => discard(fail(s"Expected Binary, got $other"))
                        })
                        .andThen(ws.take().map(f => discard(assert(f == WebSocketFrame.Text("b")))))
            ).andThen(succeed)
        }

        "unicode text" in run {
            val text = "Hello \uD83D\uDE00 \u4F60\u597D"
            connectTest(
                echo,
                ws =>
                    ws.put(WebSocketFrame.Text(text)).andThen(
                        ws.take().map(frame => discard(assert(frame == WebSocketFrame.Text(text))))
                    )
            ).andThen(succeed)
        }

        "binary with all byte values" in run {
            val allBytes = Array.tabulate[Byte](256)(i => i.toByte)
            connectTest(
                echo,
                ws =>
                    ws.put(WebSocketFrame.Binary(Span.fromUnsafe(allBytes))).andThen(
                        ws.take().map {
                            case WebSocketFrame.Binary(data) =>
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
                Channel.init[WebSocketFrame](32).map { ch1to2 =>
                    Channel.init[WebSocketFrame](32).map { ch2to1 =>
                        val ws1 = new WebSocket(ch2to1, ch1to2, ref, closeFn)
                        val ws2 = new WebSocket(ch1to2, ch2to1, ref, closeFn)
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
                Channel.init[WebSocketFrame](32).map { ch1to2 =>
                    Channel.init[WebSocketFrame](32).map { ch2to1 =>
                        val ws1 = new WebSocket(ch2to1, ch1to2, ref, closeFn)
                        val ws2 = new WebSocket(ch1to2, ch2to1, ref, closeFn)
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
                        Abort.run[Closed](ws.put(WebSocketFrame.Text("fail"))).map(result =>
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
                    ws.put(WebSocketFrame.Text("hi")).andThen(
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text("hi"))))
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
                        Kyo.foreach(1 to 5)(i => ws.put(WebSocketFrame.Text(s"a$i"))).unit,
                        Kyo.foreach(1 to 5)(_ => Abort.run[Closed](ws.take())).unit
                    ).unit,
                ws =>
                    Async.gather(
                        Kyo.foreach(1 to 5)(i => ws.put(WebSocketFrame.Text(s"b$i"))).unit,
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
            WebSocket.connect(
                echoWithReq,
                ws =>
                    ws.put(WebSocketFrame.Text("test")).andThen(
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text("test"))))
                    )
            ).andThen(succeed)
        }

        "many small messages" in run {
            val count = 200
            connectTest(
                echo,
                ws =>
                    // Send and receive concurrently to avoid deadlock on bounded channel
                    val sender = Kyo.foreach(1 to count)(i => ws.put(WebSocketFrame.Text(i.toString)))
                    val receiver = Kyo.foreach(1 to count)(i =>
                        ws.take().map(f => discard(assert(f == WebSocketFrame.Text(i.toString))))
                    )
                    Abort.run[Closed](Async.gather(sender.unit, receiver.unit)).unit
            ).andThen(succeed)
        }

        "stream terminates on close" in run {
            connectTest(
                ws =>
                    ws.put(WebSocketFrame.Text("a"))
                        .andThen(ws.put(WebSocketFrame.Text("b")))
                        .andThen(ws.close()),
                ws =>
                    ws.stream.run.map(frames =>
                        discard(assert(frames.size <= 2))
                    )
            ).andThen(succeed)
        }
    }
end WebSocketLocalTest
