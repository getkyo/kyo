package kyo

/** Test harness:
  *
  * Wire = (Int, String) — tuple where Int is the message ID and String is the payload.
  *
  * sendCh captures outgoing wire messages written by Exchange. receiveCh is fed by tests to simulate incoming responses.
  */
class ExchangeTest extends Test:

    case class TestError(msg: String)

    // Wire type: (id, payload)
    type Wire = (Int, String)

    /** Sends a wire to a channel, converting Abort[Closed] into a no-op.
      *
      * Channel.put returns `Unit < (Async & Abort[Closed])`. Exchange's `send` callback expects `Unit < (Async & Abort[E])`. This helper
      * discards `Closed` failures (the channel won't close in normal tests) yielding `Unit < Async`.
      */
    def sendVia(ch: Channel[Wire]): Wire => Unit < Async =
        wire => Abort.run[Closed](ch.put(wire)).unit

    /** Create an Exchange backed by test channels.
      *
      * Returns (exchange, sendCh, receiveCh) where:
      *   - sendCh holds the encoded wire messages Exchange sent
      *   - receiveCh is what Exchange reads from (tests feed responses here)
      */
    def mkExchange: (Exchange[String, String, Nothing, TestError], Channel[Wire], Channel[Wire]) < Sync =
        for
            sendCh    <- Channel.initUnscoped[Wire](16)
            receiveCh <- Channel.initUnscoped[Wire](16)
            counter   <- AtomicInt.init(0)
            ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                nextId = counter.getAndIncrement,
                encode = (id, req) => Sync.defer((id, req)),
                send = sendVia(sendCh),
                receive = receiveCh.streamUntilClosed(),
                decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
            )
        yield (ex, sendCh, receiveCh)

    "init" - {

        "single request-response" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                fiber                   <- Fiber.initUnscoped(ex("hello"))
                wire                    <- sendCh.take
                _                       <- receiveCh.put((wire._1, "world"))
                result                  <- fiber.get
                _                       <- ex.close
            yield assert(result == "world")
        }

        "scope closes exchange" in run {
            for
                sendCh    <- Channel.initUnscoped[Wire](16)
                receiveCh <- Channel.initUnscoped[Wire](16)
                counter   <- AtomicInt.init(0)
                ex <- Scope.run {
                    Exchange.init[Int, String, String, Wire, Nothing, TestError](
                        nextId = counter.getAndIncrement,
                        encode = (id, req) => Sync.defer((id, req)),
                        send = sendVia(sendCh),
                        receive = receiveCh.streamUntilClosed(),
                        decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                    )
                }
                // After scope exits the exchange should be closed
                result <- Abort.run[Closed](ex("after-scope"))
            yield assert(result.isFailure)
        }

        "initUnscoped requires manual close" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Exchange should be open — feed a response and verify apply works
                fiber  <- Fiber.initUnscoped(ex("request"))
                wire   <- sendCh.take
                _      <- receiveCh.put((wire._1, "response"))
                result <- fiber.get
                // Now close manually
                _      <- ex.close
                closed <- Abort.run[Closed](ex("after-close"))
            yield
                assert(result == "response")
                assert(closed.isFailure)
        }

        "simplified Int IDs (no nextId)" - {

            "initUnscoped assigns sequential Int IDs" in run {
                for
                    sendCh    <- Channel.initUnscoped[Wire](16)
                    receiveCh <- Channel.initUnscoped[Wire](16)
                    ex <- Exchange.initUnscoped[String, String, Wire, Nothing, TestError](
                        encode = (id, req) => Sync.defer((id, req)),
                        send = sendVia(sendCh),
                        receive = receiveCh.streamUntilClosed(),
                        decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                    )
                    // Send three sequential requests and verify IDs are 0, 1, 2 in order
                    fiber0 <- Fiber.initUnscoped(ex("req-0"))
                    wire0  <- sendCh.take
                    _      <- receiveCh.put((wire0._1, "resp-0"))
                    res0   <- fiber0.get
                    fiber1 <- Fiber.initUnscoped(ex("req-1"))
                    wire1  <- sendCh.take
                    _      <- receiveCh.put((wire1._1, "resp-1"))
                    res1   <- fiber1.get
                    fiber2 <- Fiber.initUnscoped(ex("req-2"))
                    wire2  <- sendCh.take
                    _      <- receiveCh.put((wire2._1, "resp-2"))
                    res2   <- fiber2.get
                    _      <- ex.close
                yield
                    assert(res0 == "resp-0")
                    assert(res1 == "resp-1")
                    assert(res2 == "resp-2")
                    // IDs must be sequential starting from 0
                    assert(wire0._1 == 0)
                    assert(wire1._1 == 1)
                    assert(wire2._1 == 2)
            }

            "scoped init assigns sequential Int IDs" in run {
                for
                    sendCh    <- Channel.initUnscoped[Wire](16)
                    receiveCh <- Channel.initUnscoped[Wire](16)
                    ex <- Scope.run {
                        Exchange.init[String, String, Wire, Nothing, TestError](
                            encode = (id, req) => Sync.defer((id, req)),
                            send = sendVia(sendCh),
                            receive = receiveCh.streamUntilClosed(),
                            decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                        )
                    }
                    // After scope exits the exchange should be closed
                    result <- Abort.run[Closed](ex("after-scope"))
                yield assert(result.isFailure)
            }
        }

        "generic Id types" - {

            "Int id" in run {
                for
                    sendCh    <- Channel.initUnscoped[Wire](16)
                    receiveCh <- Channel.initUnscoped[Wire](16)
                    counter   <- AtomicInt.init(0)
                    ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                        nextId = counter.getAndIncrement,
                        encode = (id, req) => Sync.defer((id, req)),
                        send = sendVia(sendCh),
                        receive = receiveCh.streamUntilClosed(),
                        decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                    )
                    fiber  <- Fiber.initUnscoped(ex("hello"))
                    wire   <- sendCh.take
                    _      <- receiveCh.put((wire._1, "world"))
                    result <- fiber.get
                    _      <- ex.close
                yield assert(result == "world" && wire._1.isInstanceOf[Int])
            }

            "String id" in run {
                for
                    sendCh    <- Channel.initUnscoped[(String, String)](16)
                    receiveCh <- Channel.initUnscoped[(String, String)](16)
                    counter   <- AtomicInt.init(0)
                    ex <- Exchange.initUnscoped[String, String, String, (String, String), Nothing, TestError](
                        nextId = counter.getAndIncrement.map(n => s"req-$n"),
                        encode = (id, req) => Sync.defer((id, req)),
                        send = wire => Abort.run[Closed](sendCh.put(wire)).unit,
                        receive = receiveCh.streamUntilClosed(),
                        decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                    )
                    fiber  <- Fiber.initUnscoped(ex("hello"))
                    wire   <- sendCh.take
                    _      <- receiveCh.put((wire._1, "world"))
                    result <- fiber.get
                    _      <- ex.close
                yield
                    assert(result == "world")
                    assert(wire._1.startsWith("req-"))
            }
        }
    }

    "apply" - {

        "returns response matched by id" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                fiber                   <- Fiber.initUnscoped(ex("ping"))
                wire                    <- sendCh.take
                _                       <- receiveCh.put((wire._1, "pong"))
                result                  <- fiber.get
                _                       <- ex.close
            yield assert(result == "pong")
        }

        "routes responses to correct caller" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                fiber1                  <- Fiber.initUnscoped(ex("req-A"))
                fiber2                  <- Fiber.initUnscoped(ex("req-B"))
                // Collect both outgoing wires (order may differ from fiber launch order)
                wires <- Kyo.foreach(1 to 2)(_ => sendCh.take)
                // Feed a distinct response for each wire ID
                _ <- Kyo.foreachDiscard(wires)(w => receiveCh.put((w._1, s"resp-for-${w._1}")))
                // Collect results
                result1 <- fiber1.get
                result2 <- fiber2.get
                _       <- ex.close
            yield
                // Each fiber should have gotten exactly the response keyed to its own wire ID
                val expected = wires.map(w => s"resp-for-${w._1}").toSet
                assert(Set(result1, result2) == expected)
                assert(wires.map(_._1).toSet.size == 2) // IDs must be distinct
        }

        "multiple sequential requests" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                results <- Kyo.foreach(1 to 5) { i =>
                    for
                        fiber  <- Fiber.initUnscoped(ex(s"req-$i"))
                        wire   <- sendCh.take
                        _      <- receiveCh.put((wire._1, s"resp-$i"))
                        result <- fiber.get
                    yield result
                }
                _ <- ex.close
            yield assert(results == Chunk("resp-1", "resp-2", "resp-3", "resp-4", "resp-5"))
        }

        "concurrent requests" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                n = 10
                // Launch N concurrent requests
                fibers <- Kyo.foreach(1 to n)(i => Fiber.initUnscoped(ex(s"req-$i")))
                // Collect the N wire messages sent
                wires <- Kyo.foreach(1 to n)(_ => sendCh.take)
                // Feed responses for each wire message
                _ <- Kyo.foreachDiscard(wires)(wire => receiveCh.put((wire._1, s"resp-for-${wire._1}")))
                // Collect all results
                results <- Kyo.foreach(fibers)(_.get)
                _       <- ex.close
            yield
                // Each result should be "resp-for-<id>" matching the wire ID
                assert(results.size == n)
                assert(wires.map(w => s"resp-for-${w._1}").toSet == results.toSet)
        }

        "uses nextId for each request" in run {
            for
                callCount <- AtomicInt.init(0)
                sendCh    <- Channel.initUnscoped[Wire](16)
                receiveCh <- Channel.initUnscoped[Wire](16)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = callCount.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = sendVia(sendCh),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                fiber1 <- Fiber.initUnscoped(ex("a"))
                fiber2 <- Fiber.initUnscoped(ex("b"))
                fiber3 <- Fiber.initUnscoped(ex("c"))
                wire1  <- sendCh.take
                wire2  <- sendCh.take
                wire3  <- sendCh.take
                _      <- receiveCh.put((wire1._1, "r1"))
                _      <- receiveCh.put((wire2._1, "r2"))
                _      <- receiveCh.put((wire3._1, "r3"))
                _      <- fiber1.get
                _      <- fiber2.get
                _      <- fiber3.get
                count  <- callCount.get
                _      <- ex.close
            yield
                // nextId was called once per apply — so 3 times total, IDs should be distinct
                assert(count == 3)
                assert(Set(wire1._1, wire2._1, wire3._1).size == 3)
        }

        "encode receives correct id and request" in run {
            for
                capturedId  <- AtomicInt.init(-1)
                capturedReq <- AtomicRef.init("")
                sendCh      <- Channel.initUnscoped[Wire](16)
                receiveCh   <- Channel.initUnscoped[Wire](16)
                counter     <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) =>
                        capturedId.set(id).andThen(
                            capturedReq.set(req).andThen(
                                Sync.defer((id, req))
                            )
                        ),
                    send = sendVia(sendCh),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                fiber      <- Fiber.initUnscoped(ex("my-request"))
                wire       <- sendCh.take
                encodedId  <- capturedId.get
                encodedReq <- capturedReq.get
                _          <- receiveCh.put((wire._1, "response"))
                _          <- fiber.get
                _          <- ex.close
            yield
                assert(encodedId == wire._1)
                assert(encodedReq == "my-request")
        }

        "send receives encoded wire message" in run {
            for
                sentWires <- Channel.initUnscoped[Wire](16)
                receiveCh <- Channel.initUnscoped[Wire](16)
                counter   <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, s"encoded:$req")),
                    send = sendVia(sentWires),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                fiber    <- Fiber.initUnscoped(ex("hello"))
                sentWire <- sentWires.take
                _        <- receiveCh.put((sentWire._1, "response"))
                _        <- fiber.get
                _        <- ex.close
            yield
                // The sent wire should carry the encoded payload
                assert(sentWire._2 == "encoded:hello")
        }
    }

    "decode" - {

        "Absent skips message" in run {
            for
                sendCh    <- Channel.initUnscoped[Wire](16)
                receiveCh <- Channel.initUnscoped[Wire](16)
                counter   <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = sendVia(sendCh),
                    receive = receiveCh.streamUntilClosed(),
                    // decode: only match even IDs, skip odd IDs
                    decode = wire =>
                        Sync.defer(
                            if wire._1 % 2 == 0 then Exchange.Message.Response(wire._1, wire._2)
                            else Exchange.Message.Skip
                        )
                )
                fiber <- Fiber.initUnscoped(ex("req"))
                wire  <- sendCh.take
                // Make sure we got an even ID (the counter starts at 0)
                // The wire ID is 0, which is even, so we need to generate a different case.
                // Instead, we'll feed a skip message with a different (odd) ID, then feed the real response.
                skipId = if wire._1 % 2 == 0 then wire._1 + 1 else wire._1 + 1
                _      <- receiveCh.put((skipId, "ignored"))
                _      <- receiveCh.put((wire._1, "actual-response"))
                result <- fiber.get
                _      <- ex.close
            yield assert(result == "actual-response")
        }

        "unmatched id ignored" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                fiber                   <- Fiber.initUnscoped(ex("req"))
                wire                    <- sendCh.take
                // Feed response with non-existent ID 9999
                _ <- receiveCh.put((9999, "orphan"))
                // Feed the actual matching response
                _      <- receiveCh.put((wire._1, "correct"))
                result <- fiber.get
                _      <- ex.close
            yield assert(result == "correct")
        }

        "duplicate response for same id" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                fiber                   <- Fiber.initUnscoped(ex("req"))
                wire                    <- sendCh.take
                // Feed two responses for the same ID — first completes, second is ignored
                _      <- receiveCh.put((wire._1, "first"))
                _      <- receiveCh.put((wire._1, "second"))
                result <- fiber.get
                // Launch another request to verify exchange is still functional
                fiber2  <- Fiber.initUnscoped(ex("req2"))
                wire2   <- sendCh.take
                _       <- receiveCh.put((wire2._1, "resp2"))
                result2 <- fiber2.get
                _       <- ex.close
            yield
                // First response should win
                assert(result == "first")
                // Exchange still works after duplicate
                assert(result2 == "resp2")
        }
    }

    "close" - {

        "fails pending requests with Closed" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Start a request but don't feed a response
                fiber <- Fiber.initUnscoped(ex("pending"))
                _     <- sendCh.take
                // Close the exchange
                _      <- ex.close
                result <- Abort.run[TestError | Closed](fiber.get)
            yield assert(result match
                case Result.Failure(_: Closed) => true
                case _                         => false)
        }

        "multiple pending requests all fail with Closed" in run {
            val n = 5
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Launch N concurrent requests without feeding responses
                fibers <- Kyo.foreach(1 to n)(_ => Fiber.initUnscoped(ex("req")))
                // Drain the outgoing wires so sends complete
                _ <- Kyo.foreach(1 to n)(_ => sendCh.take)
                // Close the exchange
                _       <- ex.close
                results <- Kyo.foreach(fibers)(f => Abort.run[TestError | Closed](f.get))
            yield assert(results.forall(_.isFailure))
            end for
        }

        "close is idempotent" in run {
            for
                (ex, _, _) <- mkExchange
                _          <- ex.close
                _          <- ex.close
            yield succeed
        }

        "apply after close fails with Closed" in run {
            for
                (ex, _, _) <- mkExchange
                _          <- ex.close
                result     <- Abort.run[TestError | Closed](ex("after-close"))
            yield assert(result.isFailure)
        }

        "events stream ends on close" in run {
            for
                sendCh    <- Channel.initUnscoped[Wire](16)
                receiveCh <- Channel.initUnscoped[Wire](16)
                counter   <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, String, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = sendVia(sendCh),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Skip)
                )
                // Start consuming the events stream in a fiber
                eventsFiber <- Fiber.initUnscoped(ex.events.run)
                // Close the exchange
                _      <- ex.close
                result <- Abort.run[TestError | Closed](eventsFiber.get)
            yield
                // Events stream ends cleanly when exchange closes (channel close = normal stream end)
                assert(result.isSuccess)
        }

        "done completes with Closed on explicit close" in run {
            for
                (ex, _, _) <- mkExchange
                doneFiber  <- Fiber.initUnscoped(ex.awaitDone)
                _          <- ex.close
                result     <- Abort.run[TestError | Closed](doneFiber.get)
            yield assert(result match
                case Result.Failure(_: Closed) => true
                case _                         => false)
        }
    }

    "connection close" - {

        "receive stream ending fails all pending with Closed" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Start a request but don't feed a response
                fiber <- Fiber.initUnscoped(ex("pending"))
                _     <- sendCh.take
                // End the receive stream by closing the channel
                _      <- receiveCh.close
                result <- Abort.run[TestError | Closed](fiber.get)
            yield assert(result match
                case Result.Failure(_: Closed) => true
                case _                         => false)
        }

        "receive stream ending closes exchange" in run {
            for
                (ex, _, receiveCh) <- mkExchange
                // End the receive stream
                _ <- receiveCh.close
                // Wait a bit for the reader fiber to process the stream end
                _ <- Async.sleep(10.millis)
                // Subsequent apply calls should fail with Closed
                result <- Abort.run[TestError | Closed](ex("after-stream-end"))
            yield assert(result.isFailure)
        }

        "receive stream ending with no pending requests" in run {
            for
                (ex, _, receiveCh) <- mkExchange
                // Close the receive channel with no pending requests
                _ <- receiveCh.close
                // Wait for reader fiber to process stream end
                _ <- Async.sleep(10.millis)
                // Exchange should be closed cleanly
                result <- Abort.run[TestError | Closed](ex("after-end"))
            yield assert(result.isFailure)
        }

        "done completes with Closed when stream ends normally" in run {
            for
                (ex, _, receiveCh) <- mkExchange
                doneFiber          <- Fiber.initUnscoped(ex.awaitDone)
                // End the receive stream normally
                _      <- receiveCh.close
                result <- Abort.run[TestError | Closed](doneFiber.get)
            yield assert(result match
                case Result.Failure(_: Closed) => true
                case _                         => false)
        }
    }

    "send failure" - {

        // Helper to create an exchange where send always fails with TestError
        def mkFailingSendExchange(
            receiveCh: Channel[Wire]
        ): Exchange[String, String, Nothing, TestError] < Sync =
            AtomicInt.init(0).map { counter =>
                Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = _ => Abort.fail(TestError("send failed")),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
            }

        "send error fails caller with E" in run {
            for
                receiveCh <- Channel.initUnscoped[Wire](16)
                ex        <- mkFailingSendExchange(receiveCh)
                result    <- Abort.run[TestError | Closed](ex("req"))
                _         <- ex.close
            yield assert(result == Result.fail(TestError("send failed")))
        }

        "send error fails other pending requests with E" in run {
            for
                sendCh    <- Channel.initUnscoped[Wire](16)
                receiveCh <- Channel.initUnscoped[Wire](16)
                counter   <- AtomicInt.init(0)
                // First call: normal send; subsequent calls: fail
                callCount <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = wire =>
                        callCount.getAndIncrement.map { n =>
                            if n == 0 then sendVia(sendCh)(wire)
                            else Abort.fail(TestError("send failed"))
                        },
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                // First request: send succeeds, request is now pending
                fiber1 <- Fiber.initUnscoped(ex("req1"))
                _      <- sendCh.take // drain first wire so send completes
                // Second request: send fails — should fail caller AND fiber1
                result2 <- Abort.run[TestError | Closed](ex("req2"))
                result1 <- Abort.run[TestError | Closed](fiber1.get)
            yield
                assert(result2 == Result.fail(TestError("send failed")))
                assert(result1 == Result.fail(TestError("send failed")))
        }

        "send error closes exchange" in run {
            for
                receiveCh <- Channel.initUnscoped[Wire](16)
                ex        <- mkFailingSendExchange(receiveCh)
                // First call fails
                _ <- Abort.run[TestError | Closed](ex("req1"))
                // Subsequent call also fails (exchange is closed)
                result <- Abort.run[TestError | Closed](ex("req2"))
                _      <- ex.close
            yield assert(result.isFailure)
        }

        "done completes with E on send failure" in run {
            for
                receiveCh <- Channel.initUnscoped[Wire](16)
                ex        <- mkFailingSendExchange(receiveCh)
                doneFiber <- Fiber.initUnscoped(ex.awaitDone)
                // Trigger a send failure
                _      <- Abort.run[TestError | Closed](ex("req"))
                result <- Abort.run[TestError | Closed](doneFiber.get)
            yield assert(result == Result.fail(TestError("send failed")))
        }
    }

    "receive stream error" - {

        // A controllable receive stream: put Right(wire) to emit a message,
        // put Left(error) to make the stream fail with TestError.
        // Channel close ends the stream normally.
        def mkControllableStream(
            ctrlCh: Channel[Either[TestError, Wire]]
        ): Stream[Wire, Async & Abort[TestError]] =
            Stream[Wire, Async & Abort[TestError]] {
                Loop.foreach {
                    Abort.run[Closed](ctrlCh.take).map {
                        case Result.Success(Right(wire)) => Emit.valueWith(Chunk(wire))(Loop.continue)
                        case Result.Success(Left(e))     => Abort.fail(e).andThen(Loop.done)
                        case Result.Failure(_)           => Loop.done // channel closed normally
                    }
                }
            }

        "receive Abort[E] fails all pending with E" in run {
            for
                outgoingCh <- Channel.initUnscoped[Wire](16)
                ctrlCh     <- Channel.initUnscoped[Either[TestError, Wire]](16)
                counter    <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = wire => Abort.run[Closed](outgoingCh.put(wire)).unit,
                    receive = mkControllableStream(ctrlCh),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                // Start a request
                fiber <- Fiber.initUnscoped(ex("pending"))
                _     <- outgoingCh.take // drain outgoing wire
                // Trigger receive stream failure
                _      <- ctrlCh.put(Left(TestError("receive failed")))
                result <- Abort.run[TestError | Closed](fiber.get)
            yield assert(result == Result.fail(TestError("receive failed")))
        }

        "receive Abort[E] closes exchange" in run {
            for
                outgoingCh <- Channel.initUnscoped[Wire](16)
                ctrlCh     <- Channel.initUnscoped[Either[TestError, Wire]](16)
                counter    <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = wire => Abort.run[Closed](outgoingCh.put(wire)).unit,
                    receive = mkControllableStream(ctrlCh),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                // Trigger receive stream failure immediately
                _      <- ctrlCh.put(Left(TestError("receive failed")))
                _      <- Async.sleep(10.millis)
                result <- Abort.run[TestError | Closed](ex("after-error"))
            yield assert(result.isFailure)
        }

        "done completes with E on receive error" in run {
            for
                outgoingCh <- Channel.initUnscoped[Wire](16)
                ctrlCh     <- Channel.initUnscoped[Either[TestError, Wire]](16)
                counter    <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = wire => Abort.run[Closed](outgoingCh.put(wire)).unit,
                    receive = mkControllableStream(ctrlCh),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                doneFiber <- Fiber.initUnscoped(ex.awaitDone)
                // Trigger receive stream failure
                _      <- ctrlCh.put(Left(TestError("receive failed")))
                result <- Abort.run[TestError | Closed](doneFiber.get)
            yield assert(result == Result.fail(TestError("receive failed")))
        }
    }

    "interruption" - {

        "interrupted caller cleans up pending entry" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Start apply in a fiber, it will block waiting for a response
                fiber <- Fiber.initUnscoped(ex("req"))
                // Wait for the wire to be sent (so we know the fiber is registered)
                wire <- sendCh.take
                // Interrupt the fiber
                _ <- fiber.interrupt
                // Wait until the fiber is done
                _ <- untilTrue(fiber.done)
                // Now feed a response for that ID — it should be silently ignored
                _ <- receiveCh.put((wire._1, "ignored"))
                // Exchange should still work for other requests
                fiber2  <- Fiber.initUnscoped(ex("req2"))
                wire2   <- sendCh.take
                _       <- receiveCh.put((wire2._1, "ok"))
                result2 <- fiber2.get
                _       <- ex.close
            yield assert(result2 == "ok")
        }

        "interrupted caller does not affect other requests" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Start fiber1 and let it register in pending
                fiber1 <- Fiber.initUnscoped(ex("req1"))
                wire1  <- sendCh.take
                // Interrupt fiber1 while it's waiting for a response
                _ <- fiber1.interrupt
                _ <- untilTrue(fiber1.done)
                // Start fiber2 independently — exchange must still work
                fiber2  <- Fiber.initUnscoped(ex("req2"))
                wire2   <- sendCh.take
                _       <- receiveCh.put((wire2._1, "resp2"))
                result2 <- Abort.run[TestError | Closed](fiber2.get)
                _       <- ex.close
            yield assert(result2 == Result.succeed("resp2"))
        }
    }

    "events" - {

        // Helper to create an exchange with events support
        def mkEventsExchange(
            eventCapacity: Int = 16
        ): (Exchange[String, String, String, TestError], Channel[Wire], Channel[(Int, String)]) < Sync =
            for
                sendCh    <- Channel.initUnscoped[Wire](16)
                receiveCh <- Channel.initUnscoped[(Int, String)](64)
                counter   <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, (Int, String), String, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = wire => Abort.run[Closed](sendCh.put(wire)).unit,
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire =>
                        Sync.defer {
                            // Convention: payload starting with "push:" is a Push, "resp:" is a Response, "skip:" is a Skip
                            if wire._2.startsWith("push:") then Exchange.Message.Push(wire._2.stripPrefix("push:"))
                            else if wire._2.startsWith("resp:") then
                                Exchange.Message.Response(wire._1, wire._2.stripPrefix("resp:"))
                            else Exchange.Message.Skip
                        },
                    eventCapacity = eventCapacity
                )
            yield (ex, sendCh, receiveCh)

        "Push messages appear on events stream" in run {
            for
                (ex, sendCh, receiveCh) <- mkEventsExchange()
                // Start consuming events
                eventsFiber <- Fiber.initUnscoped(ex.events.take(1).run)
                // Feed a Push message
                _ <- receiveCh.put((0, "push:hello-event"))
                // Collect the event
                events <- Abort.run[TestError | Closed](eventsFiber.get)
                _      <- ex.close
            yield assert(events == Result.succeed(Chunk("hello-event")))
        }

        "events and responses are routed correctly" in run {
            for
                (ex, sendCh, receiveCh) <- mkEventsExchange()
                // Start a request
                reqFiber <- Fiber.initUnscoped(ex("my-req"))
                wire     <- sendCh.take
                // Start collecting 2 events
                eventsFiber <- Fiber.initUnscoped(ex.events.take(2).run)
                // Interleave: Push, Response, Push
                _ <- receiveCh.put((0, "push:event1"))
                _ <- receiveCh.put((wire._1, "resp:my-response"))
                _ <- receiveCh.put((0, "push:event2"))
                // Collect results
                respResult   <- Abort.run[TestError | Closed](reqFiber.get)
                eventsResult <- Abort.run[TestError | Closed](eventsFiber.get)
                _            <- ex.close
            yield
                assert(respResult == Result.succeed("my-response"))
                assert(eventsResult == Result.succeed(Chunk("event1", "event2")))
        }

        "events stream backpressures when full" in run {
            for
                (ex, sendCh, receiveCh) <- mkEventsExchange(eventCapacity = 1)
                // Start a request so we can verify the reader is parked
                reqFiber <- Fiber.initUnscoped(ex("req"))
                wire     <- sendCh.take
                // Feed event1: reader puts into event channel (capacity=1, now full)
                _ <- receiveCh.put((0, "push:event1"))
                // Feed event2: reader reads from receiveCh but parks trying to put into full event channel
                _ <- receiveCh.put((0, "push:event2"))
                // Feed the response: reader cannot reach it while parked on event2
                _ <- receiveCh.put((wire._1, "resp:actual-response"))
                // Give the reader fiber time to get parked on event2
                _ <- Async.sleep(20.millis)
                // reqFiber should still be waiting — reader is parked before the response
                reqDone <- reqFiber.done
                // Collect both events in a fiber — the fiber will drain event1, unblock reader for event2
                eventsFiber <- Fiber.initUnscoped(ex.events.take(2).run)
                // Wait for reqFiber to complete (reader processes response after event2)
                reqResult <- Abort.run[TestError | Closed](reqFiber.get)
                // Collect events result
                eventsResult <- Abort.run[TestError | Closed](eventsFiber.get)
                _            <- ex.close
            yield
                assert(!reqDone)
                assert(reqResult == Result.succeed("actual-response"))
                assert(eventsResult == Result.succeed(Chunk("event1", "event2")))
        }

        "events stream ends on close" in run {
            for
                (ex, sendCh, receiveCh) <- mkEventsExchange()
                eventsFiber             <- Fiber.initUnscoped(ex.events.run)
                _                       <- ex.close
                result                  <- Abort.run[TestError | Closed](eventsFiber.get)
            yield
                // Events stream ends cleanly when exchange closes (channel close = normal stream end)
                assert(result.isSuccess)
        }

        "Skip messages are dropped" in run {
            for
                (ex, sendCh, receiveCh) <- mkEventsExchange()
                reqFiber                <- Fiber.initUnscoped(ex("req"))
                wire                    <- sendCh.take
                // Feed Skip messages (payload doesn't start with "push:" or "resp:")
                _ <- receiveCh.put((0, "skip:ignored1"))
                _ <- receiveCh.put((0, "skip:ignored2"))
                // Feed the actual response
                _ <- receiveCh.put((wire._1, "resp:real-response"))
                // Request should complete normally (Skips didn't affect it)
                respResult <- Abort.run[TestError | Closed](reqFiber.get)
                _          <- ex.close
            yield assert(respResult == Result.succeed("real-response"))
        }

        "no-events overload returns Nothing events" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // events stream should compile and end on close
                eventsFiber <- Fiber.initUnscoped(ex.events.run)
                _           <- ex.close
                result      <- Abort.run[TestError | Closed](eventsFiber.get)
            yield
                // Events stream ends cleanly when exchange closes (channel close = normal stream end)
                assert(result.isSuccess)
        }

        "custom eventCapacity" in run {
            for
                (ex, sendCh, receiveCh) <- mkEventsExchange(eventCapacity = 64)
                // Feed 64 Push messages — all should buffer without blocking
                _ <- Kyo.foreachDiscard(1 to 64)(i => receiveCh.put((0, s"push:event$i")))
                // Give reader time to consume from receive and fill event channel
                _ <- Async.sleep(30.millis)
                // Drain all 64 events
                events <- ex.events.take(64).run
                _      <- ex.close
            yield assert(events.size == 64)
        }

        // The event channel is closed (not failed) when the exchange shuts down, so
        // streamUntilClosed() returns Success — the error is observable via done, not events.
        "events stream ends cleanly on receive error" in run {
            for
                outgoingCh <- Channel.initUnscoped[Wire](16)
                ctrlCh     <- Channel.initUnscoped[Either[TestError, Wire]](16)
                counter    <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, String, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = wire => Abort.run[Closed](outgoingCh.put(wire)).unit,
                    receive = Stream[Wire, Async & Abort[TestError]] {
                        Loop.foreach {
                            Abort.run[Closed](ctrlCh.take).map {
                                case Result.Success(Right(wire)) => Emit.valueWith(Chunk(wire))(Loop.continue)
                                case Result.Success(Left(e))     => Abort.fail(e).andThen(Loop.done)
                                case Result.Failure(_)           => Loop.done
                            }
                        }
                    },
                    decode = wire =>
                        Sync.defer {
                            if wire._2.startsWith("push:") then Exchange.Message.Push(wire._2.stripPrefix("push:"))
                            else Exchange.Message.Response(wire._1, wire._2)
                        },
                    eventCapacity = 16
                )
                eventsFiber <- Fiber.initUnscoped(ex.events.run)
                doneFiber   <- Fiber.initUnscoped(ex.awaitDone)
                // Trigger receive stream failure
                _            <- ctrlCh.put(Left(TestError("receive-error")))
                eventsResult <- Abort.run[TestError | Closed](eventsFiber.get)
                doneResult   <- Abort.run[TestError | Closed](doneFiber.get)
            yield
                // Events stream ends cleanly (channel closed = normal stream end)
                assert(eventsResult.isSuccess)
                // Transport error is observable via done
                assert(doneResult == Result.fail(TestError("receive-error")))
        }

        // The event channel is closed (not failed) when the exchange shuts down, so
        // streamUntilClosed() returns Success — the error is observable via done, not events.
        "events stream ends cleanly on send error" in run {
            for
                receiveCh <- Channel.initUnscoped[Wire](16)
                counter   <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, String, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = _ => Abort.fail(TestError("send-error")),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire =>
                        Sync.defer {
                            if wire._2.startsWith("push:") then Exchange.Message.Push(wire._2.stripPrefix("push:"))
                            else Exchange.Message.Response(wire._1, wire._2)
                        },
                    eventCapacity = 16
                )
                eventsFiber <- Fiber.initUnscoped(ex.events.run)
                doneFiber   <- Fiber.initUnscoped(ex.awaitDone)
                // Trigger a send failure — closes the exchange with E
                _            <- Abort.run[TestError | Closed](ex("req"))
                eventsResult <- Abort.run[TestError | Closed](eventsFiber.get)
                doneResult   <- Abort.run[TestError | Closed](doneFiber.get)
            yield
                // Events stream ends cleanly (channel closed = normal stream end)
                assert(eventsResult.isSuccess)
                // Transport error is observable via done
                assert(doneResult == Result.fail(TestError("send-error")))
        }
    }

    "done" - {

        "blocks until exchange closes" in run {
            for
                (ex, _, _) <- mkExchange
                doneFiber  <- Fiber.initUnscoped(ex.awaitDone)
                // done should not have completed yet
                isDone1 <- doneFiber.done
                // Close the exchange
                _ <- ex.close
                // Now done should complete
                _ <- untilTrue(doneFiber.done)
            yield assert(!isDone1)
        }

        "returns immediately if already closed" in run {
            for
                (ex, _, _) <- mkExchange
                _          <- ex.close
                // done should complete immediately (exchange already closed)
                result <- Abort.run[TestError | Closed](ex.awaitDone)
            yield assert(result.isFailure)
        }

        "surfaces E from send failure" in run {
            for
                receiveCh <- Channel.initUnscoped[Wire](16)
                counter   <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = _ => Abort.fail(TestError("send-error")),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                doneFiber <- Fiber.initUnscoped(ex.awaitDone)
                // Trigger a send failure
                _      <- Abort.run[TestError | Closed](ex("req"))
                result <- Abort.run[TestError | Closed](doneFiber.get)
            yield assert(result == Result.fail(TestError("send-error")))
        }

        "surfaces E from receive failure" in run {
            for
                outgoingCh <- Channel.initUnscoped[Wire](16)
                ctrlCh     <- Channel.initUnscoped[Either[TestError, Wire]](16)
                counter    <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = wire => Abort.run[Closed](outgoingCh.put(wire)).unit,
                    receive = Stream[Wire, Async & Abort[TestError]] {
                        Loop.foreach {
                            Abort.run[Closed](ctrlCh.take).map {
                                case Result.Success(Right(wire)) => Emit.valueWith(Chunk(wire))(Loop.continue)
                                case Result.Success(Left(e))     => Abort.fail(e).andThen(Loop.done)
                                case Result.Failure(_)           => Loop.done
                            }
                        }
                    },
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                doneFiber <- Fiber.initUnscoped(ex.awaitDone)
                _         <- ctrlCh.put(Left(TestError("receive-error")))
                result    <- Abort.run[TestError | Closed](doneFiber.get)
            yield assert(result == Result.fail(TestError("receive-error")))
        }

        "surfaces Closed from explicit close" in run {
            for
                (ex, _, _) <- mkExchange
                doneFiber  <- Fiber.initUnscoped(ex.awaitDone)
                _          <- ex.close
                result     <- Abort.run[TestError | Closed](doneFiber.get)
            yield assert(result match
                case Result.Failure(_: Closed) => true
                case _                         => false)
        }

        "surfaces Closed from normal stream end" in run {
            for
                (ex, _, receiveCh) <- mkExchange
                doneFiber          <- Fiber.initUnscoped(ex.awaitDone)
                _                  <- receiveCh.close
                result             <- Abort.run[TestError | Closed](doneFiber.get)
            yield assert(result match
                case Result.Failure(_: Closed) => true
                case _                         => false)
        }

        "multiple fibers can await done" in run {
            val n = 5
            for
                (ex, _, _) <- mkExchange
                doneFibers <- Kyo.foreach(1 to n)(_ => Fiber.initUnscoped(ex.awaitDone))
                _          <- ex.close
                _          <- Kyo.foreachDiscard(doneFibers)(f => untilTrue(f.done))
                results    <- Kyo.foreach(doneFibers)(f => Abort.run[TestError | Closed](f.get))
            yield assert(results.forall(_.isFailure))
            end for
        }
    }

    "Sync effects in callbacks" - {

        "nextId with AtomicInt" in run {
            for
                idCounter <- AtomicInt.init(0)
                sendCh    <- Channel.initUnscoped[Wire](16)
                receiveCh <- Channel.initUnscoped[Wire](16)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = idCounter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = sendVia(sendCh),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                // Make 3 sequential requests
                fiber1 <- Fiber.initUnscoped(ex("a"))
                wire1  <- sendCh.take
                _      <- receiveCh.put((wire1._1, "r1"))
                _      <- fiber1.get
                fiber2 <- Fiber.initUnscoped(ex("b"))
                wire2  <- sendCh.take
                _      <- receiveCh.put((wire2._1, "r2"))
                _      <- fiber2.get
                fiber3 <- Fiber.initUnscoped(ex("c"))
                wire3  <- sendCh.take
                _      <- receiveCh.put((wire3._1, "r3"))
                _      <- fiber3.get
                _      <- ex.close
            yield
                // IDs should be sequential
                assert(Set(wire1._1, wire2._1, wire3._1).size == 3)
                assert(wire2._1 == wire1._1 + 1)
                assert(wire3._1 == wire2._1 + 1)
        }

        "encode with mutable state" in run {
            for
                encodeCount <- AtomicInt.init(0)
                sendCh      <- Channel.initUnscoped[Wire](16)
                receiveCh   <- Channel.initUnscoped[Wire](16)
                counter     <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) =>
                        encodeCount.getAndIncrement.andThen(Sync.defer((id, req))),
                    send = sendVia(sendCh),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                fiber1 <- Fiber.initUnscoped(ex("a"))
                wire1  <- sendCh.take
                _      <- receiveCh.put((wire1._1, "r1"))
                _      <- fiber1.get
                fiber2 <- Fiber.initUnscoped(ex("b"))
                wire2  <- sendCh.take
                _      <- receiveCh.put((wire2._1, "r2"))
                _      <- fiber2.get
                fiber3 <- Fiber.initUnscoped(ex("c"))
                wire3  <- sendCh.take
                _      <- receiveCh.put((wire3._1, "r3"))
                _      <- fiber3.get
                count  <- encodeCount.get
                _      <- ex.close
            yield assert(count == 3)
        }

        "decode with stateful accumulation" in run {
            for
                decodeCount <- AtomicInt.init(0)
                sendCh      <- Channel.initUnscoped[Wire](16)
                receiveCh   <- Channel.initUnscoped[Wire](16)
                counter     <- AtomicInt.init(0)
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = sendVia(sendCh),
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire =>
                        decodeCount.getAndIncrement.andThen(
                            Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                        )
                )
                // Send 4 messages: 1 unmatched + 3 matched
                _      <- receiveCh.put((9999, "orphan"))
                fiber1 <- Fiber.initUnscoped(ex("a"))
                wire1  <- sendCh.take
                _      <- receiveCh.put((wire1._1, "r1"))
                _      <- fiber1.get
                fiber2 <- Fiber.initUnscoped(ex("b"))
                wire2  <- sendCh.take
                _      <- receiveCh.put((wire2._1, "r2"))
                _      <- fiber2.get
                fiber3 <- Fiber.initUnscoped(ex("c"))
                wire3  <- sendCh.take
                _      <- receiveCh.put((wire3._1, "r3"))
                _      <- fiber3.get
                _      <- untilTrue(decodeCount.get.map(_ >= 4))
                count  <- decodeCount.get
                _      <- ex.close
            yield assert(count >= 4) // decode called for each received message
        }
    }

    "concurrency" - {

        "many concurrent requests" in run {
            val n = 100
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Launch 100 concurrent requests
                fibers <- Kyo.foreach(1 to n)(i => Fiber.initUnscoped(ex(s"req-$i")))
                // Collect all 100 wire messages
                wires <- Kyo.foreach(1 to n)(_ => sendCh.take)
                // Feed responses in the same order we collected wires (arbitrary order)
                _ <- Kyo.foreachDiscard(wires)(wire => receiveCh.put((wire._1, s"resp-for-${wire._1}")))
                // Collect all results
                results <- Kyo.foreach(fibers)(_.get)
                _       <- ex.close
            yield
                assert(results.size == n)
                // Each result should be "resp-for-<id>" and all should be distinct
                val expected = wires.map(w => s"resp-for-${w._1}").toSet
                assert(results.toSet == expected)
            end for
        }

        "interleaved apply and close" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Race apply against close
                result <- Abort.run[TestError | Closed](
                    Async.race(
                        ex("req").map(r => Some(r)),
                        ex.close.map(_ => Option.empty[String])
                    )
                )
                _ <- ex.close
            yield
                // Either the response arrived (Success(Some(...))) or we got Closed
                // or close won and we got Success(None)
                // No hangs, no exceptions
                result match
                    case Result.Success(_) => succeed
                    case Result.Failure(_) => succeed
                    case Result.Panic(t)   => fail(s"unexpected panic: $t")
        }

        "concurrent close and receive end" in run {
            val n = 5
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Launch N pending requests
                fibers <- Kyo.foreach(1 to n)(_ => Fiber.initUnscoped(ex("req")))
                // Drain outgoing wires so sends complete (fibers are now parked on promise.get)
                _ <- Kyo.foreach(1 to n)(_ => sendCh.take)
                // Race explicit close against receive stream ending
                _ <- Async.race(
                    ex.close,
                    receiveCh.close
                )
                // All pending fibers should fail (Closed or TestError) — exactly once
                results <- Kyo.foreach(fibers)(f => Abort.run[TestError | Closed](f.get))
                _       <- ex.close // idempotent — ensure cleanup
            yield
                // All N requests should have failed
                assert(results.forall(_.isFailure))
            end for
        }
    }

    "Unsafe API" - {

        // Helper: build a pure-Unsafe exchange for Int IDs and String payloads.
        // Wire = (Int, String): first element is the ID, second is the payload.
        // Decode: payload starting with "push:" becomes a Push event; otherwise Response.
        def mkUnsafe(
            eventCapacity: Int = 16
        )(using AllowUnsafe): Exchange.Unsafe[Int, Wire, String, String, String, TestError] =
            var counter = 0
            Exchange.Unsafe.init[Int, Wire, String, String, String, TestError](
                nextId =
                    counter += 1; counter - 1
                ,
                encode = (id, req) => (id, req),
                decode = wire =>
                    if wire._2.startsWith("push:") then Exchange.Message.Push(wire._2.stripPrefix("push:"))
                    else Exchange.Message.Response(wire._1, wire._2),
                eventCapacity = eventCapacity
            )
        end mkUnsafe

        "init creates exchange and safe accessor compiles" in run {
            Sync.Unsafe.defer {
                val ex = mkUnsafe()
                // Safe accessor should return a valid Exchange opaque alias
                val _: Exchange[String, String, String, TestError] = ex.safe
                ex.close()
                succeed
            }
        }

        "apply assigns sequential IDs and encodes wire correctly" in run {
            Sync.Unsafe.defer {
                val ex               = mkUnsafe()
                val (id0, wire0, p0) = ex.apply("first")
                val (id1, wire1, _)  = ex.apply("second")
                assert(id0 == 0)
                assert(id1 == 1)
                assert(wire0 == (0, "first"))
                assert(wire1 == (1, "second"))
                // Promises are pending before feed
                assert(p0.poll().isEmpty)
                ex.close()
                succeed
            }
        }

        "feed routes response to pending promise" in run {
            Sync.Unsafe.defer {
                val ex               = mkUnsafe()
                val (id, _, promise) = ex.apply("ping")
                ex.feed((id, "pong"))
                val ok = promise.poll().exists(_.isSuccess)
                ex.close()
                assert(ok)
            }
        }

        "feed routes push to event channel via safe events" in run {
            for
                ex <- Sync.Unsafe.defer(mkUnsafe())
                // Consume one event from the safe events stream
                eventFiber <- Fiber.initUnscoped(ex.safe.events.take(1).run)
                // Feed a Push message via the unsafe feed path
                _      <- Sync.Unsafe.defer(ex.feed((0, "push:hello-event")))
                events <- Abort.run[TestError | Closed](eventFiber.get)
                _      <- Sync.Unsafe.defer(ex.close())
            yield assert(events == Result.succeed(Chunk("hello-event")))
        }

        "feed with Skip message leaves event channel empty" in run {
            for
                ex <- Sync.Unsafe.defer {
                    var counter = 0
                    Exchange.Unsafe.init[Int, Wire, String, String, String, TestError](
                        nextId =
                            counter += 1; counter - 1
                        ,
                        encode = (id, req) => (id, req),
                        decode = _ => Exchange.Message.Skip
                    )
                }
                _ <- Sync.Unsafe.defer(ex.feed((0, "anything")))
                // After Skip, events stream should end immediately when we close
                eventsFiber <- Fiber.initUnscoped(ex.safe.events.run)
                _           <- Sync.Unsafe.defer(ex.close())
                result      <- Abort.run[TestError | Closed](eventsFiber.get)
            yield assert(result.isSuccess && result.getOrElse(Chunk.empty).isEmpty)
        }

        "multiple apply then feed in reverse order" in run {
            Sync.Unsafe.defer {
                val ex           = mkUnsafe()
                val (id0, _, p0) = ex.apply("req-0")
                val (id1, _, p1) = ex.apply("req-1")
                val (id2, _, p2) = ex.apply("req-2")
                // Feed responses in reverse order
                ex.feed((id2, "resp-2"))
                ex.feed((id1, "resp-1"))
                ex.feed((id0, "resp-0"))
                val allDone     = p0.poll().exists(_.isSuccess) && p1.poll().exists(_.isSuccess) && p2.poll().exists(_.isSuccess)
                val allDistinct = Set(id0, id1, id2).size == 3
                ex.close()
                assert(allDone && allDistinct)
            }
        }

        "close fails all pending promises with Closed" in run {
            Sync.Unsafe.defer {
                val ex              = mkUnsafe()
                val (_, _, promise) = ex.apply("pending")
                ex.close()
                val isClosed = promise.poll().exists {
                    case Result.Failure(_: Closed) => true
                    case _                         => false
                }
                assert(isClosed)
            }
        }

        "feed after close does not throw" in run {
            Sync.Unsafe.defer {
                val ex = mkUnsafe()
                ex.close()
                ex.feed((0, "late-response"))
                succeed
            }
        }

        "safe awaitDone reflects Closed after Unsafe.close" in run {
            for
                ex     <- Sync.Unsafe.defer(mkUnsafe())
                _      <- Sync.Unsafe.defer(ex.close())
                result <- Abort.run[TestError | Closed](ex.safe.awaitDone)
            yield assert(result match
                case Result.Failure(_: Closed) => true
                case _                         => false)
        }

        "apply after close does not throw; exchange remains done" in run {
            for
                ex <- Sync.Unsafe.defer(mkUnsafe())
                _  <- Sync.Unsafe.defer(ex.close())
                // Calling apply after close must not throw
                _      <- Sync.Unsafe.defer { val (_, _, _) = ex.apply("after-close") }
                result <- Abort.run[TestError | Closed](ex.safe.awaitDone)
            yield assert(result.isFailure)
        }
    }

    "edge cases" - {

        "apply vs close race never hangs" in run {
            val iterations = 50
            Kyo.foreachDiscard(1 to iterations) { _ =>
                for
                    (ex, sendCh, receiveCh) <- mkExchange
                    applyFiber              <- Fiber.initUnscoped(Abort.run[TestError | Closed](ex("req")))
                    // Race: close the exchange immediately (before or after apply sends)
                    _ <- ex.close
                    // Must complete — no hang. Test framework has a timeout, so a hang = test failure.
                    _ <- Abort.run[TestError | Closed](applyFiber.get)
                yield ()
            }.andThen(succeed)
        }

        "apply vs receive stream end race never hangs" in run {
            val iterations = 50
            Kyo.foreachDiscard(1 to iterations) { _ =>
                for
                    (ex, sendCh, receiveCh) <- mkExchange
                    applyFiber              <- Fiber.initUnscoped(Abort.run[TestError | Closed](ex("req")))
                    // End the receive stream (reader loop will call shutdown)
                    _ <- receiveCh.close
                    // Must complete — no hang.
                    _ <- Abort.run[TestError | Closed](applyFiber.get)
                yield ()
            }.andThen(succeed)
        }

        "concurrent close from many fibers — no duplicates, no exceptions" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                n = 20
                // Launch N pending requests (all waiting for responses)
                pendingFibers <- Kyo.foreach(1 to n)(_ => Fiber.initUnscoped(ex("req")))
                // Drain outgoing wires so the sends complete and fibers park on promise.get
                _ <- Kyo.foreach(1 to n)(_ => sendCh.take)
                // Now close from many concurrent fibers
                closeFibers <- Kyo.foreach(1 to n)(_ => Fiber.initUnscoped(ex.close))
                _           <- Kyo.foreachDiscard(closeFibers)(_.get)
                // Each pending fiber should fail exactly once with Closed
                results <- Kyo.foreach(pendingFibers)(f => Abort.run[TestError | Closed](f.get))
            yield
                assert(results.forall(_.isFailure), "All pending requests must fail")
                assert(
                    results.forall {
                        case Result.Failure(_: Closed) => true
                        case _                         => false
                    },
                    "All failures must be Closed"
                )
            end for
        }

        "Unsafe.apply after close — promise is not left permanently pending" in run {
            Sync.Unsafe.defer {
                val ex = Exchange.Unsafe.init[Int, Wire, String, String, Nothing, TestError](
                    nextId = 0,
                    encode = (id, req) => (id, req),
                    decode = wire => Exchange.Message.Response(wire._1, wire._2)
                )
                ex.close()
                // Apply after close: the promise is added AFTER failAllPending cleared the map.
                val (_, _, promise) = ex.apply("late")
                // The promise should be completed (failed with Closed) since exchange is already closed.
                val isCompleted = promise.poll().isDefined
                assert(isCompleted, "Promise added after close() must be completed (not left dangling)")
            }
        }

        "Unsafe.feed with full event channel — drops silently, no crash" in run {
            for
                ex <- Sync.Unsafe.defer {
                    Exchange.Unsafe.init[Int, Wire, String, String, String, TestError](
                        nextId = 0,
                        encode = (id, req) => (id, req),
                        decode = wire =>
                            if wire._2.startsWith("push:") then Exchange.Message.Push(wire._2.stripPrefix("push:"))
                            else Exchange.Message.Response(wire._1, wire._2),
                        eventCapacity = 2
                    )
                }
                // Feed more push events than the event channel capacity (2)
                _ <- Sync.Unsafe.defer {
                    ex.feed((0, "push:e1"))
                    ex.feed((0, "push:e2"))
                    ex.feed((0, "push:e3")) // This should be dropped silently (channel full)
                    ex.feed((0, "push:e4")) // Also dropped
                }
                // Close the exchange
                _ <- Sync.Unsafe.defer(ex.close())
            yield succeed // No crash = good; we just verify it doesn't throw
        }

        "high contention: 80 concurrent apply + response routing" in run {
            val n = 80
            for
                (ex, sendCh, receiveCh) <- mkExchange
                // Launch N concurrent requests
                fibers <- Kyo.foreach(1 to n)(i => Fiber.initUnscoped(ex(s"req-$i")))
                // Collect all outgoing wires in a separate fiber (parallel to applies)
                wiresFiber <- Fiber.initUnscoped(Kyo.foreach(1 to n)(_ => sendCh.take))
                wires      <- wiresFiber.get
                // Feed all responses from a single fiber
                _ <- Kyo.foreachDiscard(wires)(wire => receiveCh.put((wire._1, s"resp-for-${wire._1}")))
                // Collect all results
                results <- Kyo.foreach(fibers)(_.get)
                _       <- ex.close
            yield
                assert(results.size == n, s"Expected $n results, got ${results.size}")
                val expected = wires.map(w => s"resp-for-${w._1}").toSet
                assert(results.toSet == expected, "Each fiber must receive exactly its own response")
            end for
        }

        "three-way race: apply, close, and receive stream end — no hangs, no panics" in run {
            val iterations = 30
            Kyo.foreachDiscard(1 to iterations) { _ =>
                for
                    (ex, sendCh, receiveCh) <- mkExchange
                    // Launch a pending request
                    applyFiber <- Fiber.initUnscoped(Abort.run[TestError | Closed](ex("req")))
                    // Race explicit close against receive stream ending, both simultaneously with apply
                    closeFiber     <- Fiber.initUnscoped(ex.close)
                    streamEndFiber <- Fiber.initUnscoped(receiveCh.close)
                    // Wait for all to finish
                    result <- applyFiber.get
                    _      <- closeFiber.get
                    _      <- streamEndFiber.get
                yield result match
                    case Result.Success(_) => () // got a response (unlikely but valid)
                    case Result.Failure(_) => () // Closed or TestError — expected
                    case Result.Panic(t)   => throw t
            }.andThen(succeed)
        }

        "feed for already-completed ID is silently ignored" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                fiber                   <- Fiber.initUnscoped(ex("req"))
                wire                    <- sendCh.take
                // Feed the first response — completes the promise
                _ <- receiveCh.put((wire._1, "first-response"))
                _ <- fiber.get
                // Feed a second response for the same ID — should be silently ignored
                _ <- receiveCh.put((wire._1, "second-response"))
                // Exchange should still work normally
                fiber2  <- Fiber.initUnscoped(ex("req2"))
                wire2   <- sendCh.take
                _       <- receiveCh.put((wire2._1, "resp2"))
                result2 <- fiber2.get
                _       <- ex.close
            yield assert(result2 == "resp2")
        }

        "feed for never-registered ID is silently ignored" in run {
            for
                (ex, sendCh, receiveCh) <- mkExchange
                fiber                   <- Fiber.initUnscoped(ex("req"))
                wire                    <- sendCh.take
                // Feed a response for an ID that was never registered
                _ <- receiveCh.put((wire._1 + 99999, "orphan"))
                // Feed the correct response
                _      <- receiveCh.put((wire._1, "correct"))
                result <- fiber.get
                _      <- ex.close
            yield assert(result == "correct")
        }

        "send failure propagates to all concurrent pending requests" in run {
            for
                sendCh    <- Channel.initUnscoped[Wire](32)
                receiveCh <- Channel.initUnscoped[Wire](16)
                counter   <- AtomicInt.init(0)
                sendCount <- AtomicInt.init(0)
                // First 3 sends succeed, 4th send fails
                ex <- Exchange.initUnscoped[Int, String, String, Wire, Nothing, TestError](
                    nextId = counter.getAndIncrement,
                    encode = (id, req) => Sync.defer((id, req)),
                    send = wire =>
                        sendCount.getAndIncrement.map { n =>
                            if n < 3 then sendVia(sendCh)(wire)
                            else Abort.fail(TestError("send failed"))
                        },
                    receive = receiveCh.streamUntilClosed(),
                    decode = wire => Sync.defer(Exchange.Message.Response(wire._1, wire._2))
                )
                // Launch 3 requests that will succeed at sending and be left pending
                fiber1 <- Fiber.initUnscoped(ex("req1"))
                fiber2 <- Fiber.initUnscoped(ex("req2"))
                fiber3 <- Fiber.initUnscoped(ex("req3"))
                // Drain the 3 successful sends
                _ <- Kyo.foreach(1 to 3)(_ => sendCh.take)
                // 4th request: send fails — should fail caller AND all pending (fiber1..3)
                result4 <- Abort.run[TestError | Closed](ex("req4"))
                result1 <- Abort.run[TestError | Closed](fiber1.get)
                result2 <- Abort.run[TestError | Closed](fiber2.get)
                result3 <- Abort.run[TestError | Closed](fiber3.get)
            yield
                assert(result4 == Result.fail(TestError("send failed")), "req4 must fail with send error")
                assert(result1.isFailure, "req1 must also fail due to exchange shutdown")
                assert(result2.isFailure, "req2 must also fail due to exchange shutdown")
                assert(result3.isFailure, "req3 must also fail due to exchange shutdown")
            end for
        }

        "100 sequential close calls are idempotent" in run {
            for
                (ex, _, _) <- mkExchange
                // Call close 100 times sequentially — must not throw or fail
                _ <- Kyo.foreachDiscard(1 to 100)(_ => ex.close)
            yield succeed
        }

        "apply after receive stream ends fails with Closed or E" in run {
            for
                (ex, _, receiveCh) <- mkExchange
                // Await done in a fiber first so we know when the exchange shuts down
                doneFiber <- Fiber.initUnscoped(Abort.run[TestError | Closed](ex.awaitDone))
                // End the receive stream
                _ <- receiveCh.close
                // Wait until exchange is fully shut down
                _ <- doneFiber.get
                // Any subsequent apply must fail (not hang, not succeed silently)
                result <- Abort.run[TestError | Closed](ex("late-req"))
            yield assert(result.isFailure, "apply after stream end must fail")
        }

        "Unsafe close then safe awaitDone returns Closed" in run {
            for
                ex <- Sync.Unsafe.defer {
                    Exchange.Unsafe.init[Int, Wire, String, String, Nothing, TestError](
                        nextId = 0,
                        encode = (id, req) => (id, req),
                        decode = wire => Exchange.Message.Response(wire._1, wire._2)
                    )
                }
                _      <- Sync.Unsafe.defer(ex.close())
                result <- Abort.run[TestError | Closed](ex.safe.awaitDone)
            yield assert(
                result match
                    case Result.Failure(_: Closed) => true
                    case _                         => false
            )
        }

        "many concurrent apply calls on already-closed exchange all fail fast" in run {
            val n = 50
            for
                (ex, _, _) <- mkExchange
                _          <- ex.close
                fibers     <- Kyo.foreach(1 to n)(_ => Fiber.initUnscoped(Abort.run[TestError | Closed](ex("req"))))
                results    <- Kyo.foreach(fibers)(_.get)
            yield assert(
                results.forall(_.isFailure),
                "All applies on closed exchange must fail"
            )
            end for
        }

        "Unsafe.apply after close — promise poll returns defined result" in run {
            Sync.Unsafe.defer {
                val ex = Exchange.Unsafe.init[Int, Wire, String, String, Nothing, TestError](
                    nextId = 0,
                    encode = (id, req) => (id, req),
                    decode = wire => Exchange.Message.Response(wire._1, wire._2)
                )
                ex.close()
                // Two applies after close
                val (_, _, p1) = ex.apply("a")
                val (_, _, p2) = ex.apply("b")
                // Both promises should be completed with Closed (failAllPending already ran!)
                val p1done = p1.poll().isDefined
                val p2done = p2.poll().isDefined
                assert(
                    p1done && p2done,
                    s"Promises after close must be completed: p1=$p1done, p2=$p2done"
                )
            }
        }

        "apply result is always Closed or TestError when stream ends concurrently" in run {
            val iterations = 40
            for
                outcomes <- Kyo.foreach(1 to iterations) { _ =>
                    for
                        (ex, sendCh, receiveCh) <- mkExchange
                        applyFiber              <- Fiber.initUnscoped(Abort.run[TestError | Closed](ex("req")))
                        _                       <- receiveCh.close
                        result                  <- applyFiber.get
                        _                       <- ex.close
                    yield result
                }
            yield
                outcomes.foreach { r =>
                    r match
                        case Result.Success(_) => // got a result before stream ended — OK
                        case Result.Failure(_) => // Closed or E — expected
                        case Result.Panic(t)   => fail(s"Panic: $t")
                }
                succeed
            end for
        }
    }

end ExchangeTest
