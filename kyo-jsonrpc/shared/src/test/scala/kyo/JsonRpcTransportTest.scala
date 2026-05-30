package kyo

import kyo.Maybe.Absent

class JsonRpcTransportTest extends JsonRpcTest:

    val ping1 = JsonRpcEnvelope.Request(JsonRpcEnvelope.Id.Num(1L), "ping", Absent, Absent)
    val ping2 = JsonRpcEnvelope.Request(JsonRpcEnvelope.Id.Num(2L), "ping", Absent, Absent)
    val ping3 = JsonRpcEnvelope.Request(JsonRpcEnvelope.Id.Num(3L), "ping", Absent, Absent)

    "a send on transport A is received via incoming on transport B" in run {
        for
            (a, b) <- JsonRpcTransport.inMemory
            recv   <- Fiber.initUnscoped(b.incoming.take(1).run)
            _      <- a.send(ping1)
            result <- recv.get
        yield assert(result == Chunk(ping1))
    }

    "a send on transport B is received via incoming on transport A" in run {
        for
            (a, b) <- JsonRpcTransport.inMemory
            recv   <- Fiber.initUnscoped(a.incoming.take(1).run)
            _      <- b.send(ping1)
            result <- recv.get
        yield assert(result == Chunk(ping1))
    }

    "a send on a closed transport fails with Abort[Closed]" in run {
        for
            (a, _) <- JsonRpcTransport.inMemory
            _      <- a.close
            result <- Abort.run[Closed](a.send(ping2))
        yield assert(result.isFailure)
    }

    "the incoming stream on B terminates when A closes" in run {
        for
            (a, b)    <- JsonRpcTransport.inMemory
            collector <- Fiber.initUnscoped(b.incoming.run)
            _         <- a.close
            result    <- collector.get
        yield assert(result.isEmpty)
    }

    "a send parks when the consumer of incoming is slow" in run {
        for
            (a, b)    <- JsonRpcTransport.inMemory(2)
            _         <- a.send(ping1)
            _         <- a.send(ping2)
            putFiber  <- Fiber.initUnscoped(a.send(ping3))
            _         <- Async.sleep(10.millis)
            notDone   <- putFiber.done
            collector <- Fiber.initUnscoped(b.incoming.take(3).run)
            _         <- untilTrue(putFiber.done)
            result    <- collector.get
        yield assert(!notDone && result == Chunk(ping1, ping2, ping3))
    }

    "a parked send unblocks with Abort[Closed] when the transport closes" in run {
        for
            (a, _)  <- JsonRpcTransport.inMemory(2)
            _       <- a.send(ping1)
            _       <- a.send(ping2)
            parked  <- Fiber.initUnscoped(Abort.run[Closed](a.send(ping3)))
            _       <- Async.sleep(10.millis)
            notDone <- parked.done
            _       <- a.close
            result  <- parked.get
        yield assert(!notDone && result.isFailure)
    }

    "stdio transport sends one line per envelope" in run {
        Scope.run {
            Console.withOut {
                for
                    transport <- JsonRpcTransport.stdio()
                    notification = JsonRpcEnvelope.Notification(
                        "log",
                        Maybe.Present(Structure.Value.Record(Chunk("text" -> Structure.Value.Str("hi")))),
                        Absent
                    )
                    _ <- Abort.run[Closed](transport.send(notification))
                yield ()
            }.map { case (out, _) =>
                val line = out.stdOut.trim
                assert(line.nonEmpty)
                val parsed = internal.codec.RawJsonParser.parse(line)
                assert(parsed.isSuccess)
            }
        }
    }

    "stdio transport reads one envelope per stdin line" in run {
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
                        case JsonRpcEnvelope.Notification(method, _, _) =>
                            assert(method == "ping")
                        case other =>
                            fail(s"unexpected envelope: $other")
                    end match
                case other =>
                    fail(s"unexpected result: $other")
        }
    }

    "stdio transport EOF closes incoming" in run {
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
