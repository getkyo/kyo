package kyo

import kyo.Maybe.Absent

class JsonRpcTransportTest extends JsonRpcTestBase:

    val ping1 = JsonRpcEnvelope.Request(JsonRpcId.Num(1L), "ping", Absent, Absent)
    val ping2 = JsonRpcEnvelope.Request(JsonRpcId.Num(2L), "ping", Absent, Absent)
    val ping3 = JsonRpcEnvelope.Request(JsonRpcId.Num(3L), "ping", Absent, Absent)

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

end JsonRpcTransportTest
