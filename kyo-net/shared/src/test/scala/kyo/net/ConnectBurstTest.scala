package kyo.net

import kyo.*

/** Reproduction and regression guard for a load-sensitive concurrent-connect failure against an in-process listener.
  *
  * This drives the real production Transport (epoll on Linux, kqueue on macOS/BSD): a listener that reads one chunk and echoes it, and a
  * latch-released burst of simultaneous connects. Every connect must complete and round-trip its byte; a single connect that fails Closed
  * fails the test rather than hanging.
  */
class ConnectBurstTest extends Test:

    import AllowUnsafe.embrace.danger

    private def echoListener(transport: Transport)(using Frame): Listener < (Async & Abort[NetException]) =
        transport.listen("127.0.0.1", 0, 128) { serverConn =>
            discard(Sync.Unsafe.evalOrThrow {
                Fiber.initUnscoped {
                    Abort.run[Closed] {
                        Loop.foreach {
                            serverConn.inbound.safe.take.map { bytes =>
                                if bytes.isEmpty then Loop.done(())
                                else serverConn.outbound.safe.put(bytes).andThen(Loop.continue)
                            }
                        }
                    }.unit
                }
            })
        }.safe.get

    /** Fire `size` simultaneous connects (latch-gated) to `port`, each writing one byte and reading the echo, and assert every one succeeds. */
    private def burst(transport: Transport, port: Int, size: Int)(using Frame, kyo.test.AssertScope): Unit < (Async & Scope) =
        Latch.init(1).map { latch =>
            Kyo.foreach(0 until size) { i =>
                Fiber.initUnscoped {
                    latch.await.andThen {
                        Abort.run[NetException | Closed] {
                            transport.connect("127.0.0.1", port).safe.get.map { conn =>
                                Scope.ensure(Sync.defer(conn.close())).andThen {
                                    val msg = Span.from(Array[Byte]((i & 0x7f).toByte))
                                    conn.outbound.safe.put(msg).andThen(conn.inbound.safe.take).map { echo =>
                                        conn.close()
                                        echo.toArray.toList
                                    }
                                }
                            }
                        }
                    }
                }
            }.map { fibers =>
                latch.release.andThen(Kyo.foreach(fibers)(_.get)).map { results =>
                    val failures = results.collect { case Result.Failure(c) => c.getMessage }
                    val echoes   = results.collect { case Result.Success(bytes) => bytes }
                    assert(failures.isEmpty, s"connect(s) failed under burst: $failures")
                    assert(echoes == (0 until size).map(i => List((i & 0x7f).toByte)).toSeq)
                }
            }
        }

    "concurrent connect burst against an in-process listener" - {
        "16 simultaneous connects all complete and round-trip (no Closed under burst)" in {
            val transport = NetPlatform.transport
            for
                listener <- echoListener(transport)
                _        <- Scope.ensure(Sync.defer(listener.close()))
                port = listener.port
                _ <- Loop.repeat(8)(burst(transport, port, 16).unit)
            yield
                listener.close()
                succeed
            end for
        }
    }

end ConnectBurstTest
