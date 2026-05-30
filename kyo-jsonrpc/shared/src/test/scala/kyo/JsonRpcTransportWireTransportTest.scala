package kyo

import kyo.Maybe.Absent

class JsonRpcTransportWireTransportTest extends JsonRpcTestBase:

    "empty wire transport produces no bytes" in run {
        val wire   = JsonRpcTransport.WireTransport.empty
        val result = wire.incoming.run
        result.map { chunks =>
            assert(chunks.isEmpty)
        }
    }

    "fromWire round-trips one envelope over in-memory channel pair" in run {
        for
            aToBChan <- Channel.initUnscoped[Chunk[Byte]](64)
            bToAChan <- Channel.initUnscoped[Chunk[Byte]](64)
            wireA = new JsonRpcTransport.WireTransport:
                def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
                    aToBChan.put(bytes)
                def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
                    bToAChan.stream()
                def close(using Frame): Unit < Async =
                    aToBChan.close.andThen(bToAChan.close).unit
            wireB = new JsonRpcTransport.WireTransport:
                def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
                    bToAChan.put(bytes)
                def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
                    aToBChan.stream()
                def close(using Frame): Unit < Async =
                    bToAChan.close.andThen(aToBChan.close).unit
            transportA   = new internal.transport.WireTransportAdapter(wireA, JsonRpcTransport.Framer.lineDelimited, JsonRpcCodec.Strict2_0)
            transportB   = new internal.transport.WireTransportAdapter(wireB, JsonRpcTransport.Framer.lineDelimited, JsonRpcCodec.Strict2_0)
            sentEnvelope = JsonRpcEnvelope.Request(JsonRpcEnvelope.Id.Num(1L), "ping", Absent, Absent)
            receiverFiber <- Fiber.initUnscoped(Abort.run[Closed](transportB.incoming.take(1).run))
            _             <- transportA.send(sentEnvelope)
            received      <- receiverFiber.get
        yield received match
            case Result.Success(chunk) =>
                assert(chunk.size == 1)
                chunk.head match
                    case JsonRpcEnvelope.Request(id, method, _, _) =>
                        assert(id == JsonRpcEnvelope.Id.Num(1L) && method == "ping")
                    case other =>
                        fail(s"unexpected envelope: $other")
                end match
            case other =>
                fail(s"unexpected result: $other")

    }

end JsonRpcTransportWireTransportTest
