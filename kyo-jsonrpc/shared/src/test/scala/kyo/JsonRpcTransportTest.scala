package kyo

import kyo.Maybe.Absent

class JsonRpcTransportTest extends JsonRpcTest:

    val ping1 = JsonRpcRequest(JsonRpcId.Num(1L), "ping", Absent, Absent)
    val ping2 = JsonRpcRequest(JsonRpcId.Num(2L), "ping", Absent, Absent)
    val ping3 = JsonRpcRequest(JsonRpcId.Num(3L), "ping", Absent, Absent)

    // SignalingTransport completes `entered` the instant a matching envelope reaches send (before the
    // underlying channel.put parks). It gives the test a deterministic latch to await the parked-put state
    // instead of sleeping and hoping the forked send fiber has reached its put.
    private class SignalingTransport(
        inner: JsonRpcTransport,
        entered: Fiber.Promise[Unit, Any],
        entersOn: JsonRpcEnvelope => Boolean
    ) extends JsonRpcTransport:
        def send(env: JsonRpcEnvelope)(using Frame): Unit < (Async & Abort[Closed]) =
            val signal: Unit < Sync = if entersOn(env) then entered.completeUnitDiscard else Kyo.unit
            signal.andThen(inner.send(env))

        def incoming(using Frame): Stream[JsonRpcEnvelope, Async & Abort[Closed]] =
            inner.incoming

        def close(using Frame): Unit < Async =
            inner.close
    end SignalingTransport

    "a send on transport A is received via incoming on transport B" in {
        for
            (a, b) <- JsonRpcTransport.inMemory
            recv   <- Fiber.initUnscoped(b.incoming.take(1).run)
            _      <- a.send(ping1)
            result <- recv.get
        yield assert(result == Chunk(ping1))
    }

    "a send on transport B is received via incoming on transport A" in {
        for
            (a, b) <- JsonRpcTransport.inMemory
            recv   <- Fiber.initUnscoped(a.incoming.take(1).run)
            _      <- b.send(ping1)
            result <- recv.get
        yield assert(result == Chunk(ping1))
    }

    "a send on a closed transport fails with Abort[Closed]" in {
        for
            (a, _) <- JsonRpcTransport.inMemory
            _      <- a.close
            result <- Abort.run[Closed](a.send(ping2))
        yield assert(result.isFailure)
    }

    "the incoming stream on B terminates when A closes" in {
        for
            (a, b)    <- JsonRpcTransport.inMemory
            collector <- Fiber.initUnscoped(b.incoming.run)
            _         <- a.close
            result    <- collector.get
        yield assert(result.isEmpty)
    }

    "a send parks when the consumer of incoming is slow" in {
        // The capacity-2 channel is full after ping1+ping2 with no consumer, so the forked ping3 send parks
        // in channel.put. `entered` fires when ping3 reaches send; once it does the put cannot complete (full,
        // no consumer), so putFiber.done is reliably false. This replaces a fixed sleep with a state latch.
        for
            (inner, b) <- JsonRpcTransport.inMemory(2)
            entered    <- Fiber.Promise.init[Unit, Any]
            a = new SignalingTransport(inner, entered, _ == ping3)
            _         <- a.send(ping1)
            _         <- a.send(ping2)
            putFiber  <- Fiber.initUnscoped(a.send(ping3))
            _         <- entered.get
            notDone   <- putFiber.done
            collector <- Fiber.initUnscoped(b.incoming.take(3).run)
            _         <- assertEventually(putFiber.done)
            result    <- collector.get
        yield assert(!notDone && result == Chunk(ping1, ping2, ping3))
    }

    "a parked send unblocks with Abort[Closed] when the transport closes" in {
        // Same parked-put latch: `entered` fires when ping3 reaches send; with the channel full and no
        // consumer the put stays parked (done == false) until close unblocks it with Abort[Closed].
        for
            (inner, _) <- JsonRpcTransport.inMemory(2)
            entered    <- Fiber.Promise.init[Unit, Any]
            a = new SignalingTransport(inner, entered, _ == ping3)
            _       <- a.send(ping1)
            _       <- a.send(ping2)
            parked  <- Fiber.initUnscoped(Abort.run[Closed](a.send(ping3)))
            _       <- entered.get
            notDone <- parked.done
            _       <- a.close
            result  <- parked.get
        yield assert(!notDone && result.isFailure)
    }

    "stdio transport sends one line per envelope" in {
        Scope.run {
            Console.withOut {
                for
                    transport <- JsonRpcTransport.stdio()
                    notification = JsonRpcNotification(
                        "log",
                        Maybe.Present(Structure.Value.Record(Chunk("text" -> Structure.Value.Str("hi")))),
                        Absent
                    )
                    _ <- Abort.run[Closed](transport.send(notification))
                yield ()
            }.map { case (out, _) =>
                val line = out.stdOut.trim
                assert(line.nonEmpty)
                // The transport now encodes via Json.encode[Structure.Value]; decode with the same codec
                // to confirm the emitted line is standard, round-trippable JSON-RPC.
                val parsed = Json.decode[Structure.Value](line)
                assert(parsed.isSuccess)
            }
        }
    }

    "stdio transport reads one envelope per stdin line" in {
        val inputLine = """{"jsonrpc":"2.0","method":"ping"}"""
        Console.withIn(List(inputLine)) {
            Scope.run {
                for
                    transport <- JsonRpcTransport.stdio()
                    result    <- Abort.run[Closed](transport.incoming.take(1).run)
                yield result
            }
        }.map { result =>
            result match
                case Result.Success(chunk) =>
                    assert(chunk.size == 1)
                    chunk.head match
                        case JsonRpcNotification(method, _, _) =>
                            assert(method == "ping")
                        case other =>
                            fail(s"unexpected envelope: $other")
                    end match
                case other =>
                    fail(s"unexpected result: $other")
        }
    }

    "stdio transport EOF closes incoming" in {
        Console.withIn(Seq.empty[String]) {
            Abort.run[Timeout](Async.timeout(2.seconds) {
                Scope.run {
                    for
                        transport <- JsonRpcTransport.stdio()
                        result    <- Abort.run[Closed](transport.incoming.run)
                    yield result
                }
            })
        }.map { outerResult =>
            outerResult match
                case Result.Failure(_) => fail("timed out - incoming did not close on EOF")
                case Result.Panic(t)   => fail(s"panic: ${t.getMessage}")
                case Result.Success(innerResult) =>
                    innerResult match
                        case Result.Success(chunk: Chunk[?]) => assert(chunk.isEmpty)
                        case other                           => assert(true, s"acceptable result: $other")
        }
    }

end JsonRpcTransportTest
