package kyo

import kyo.Maybe.Absent

class JsonRpcTransportWireTransportTest extends JsonRpcTest:

    "null results and peer error details survive JSON framing" in {
        Scope.run {
            Channel.init[Chunk[Byte]](2).map { channel =>
                val wire = new JsonRpcWireTransport:
                    def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) = channel.put(bytes)
                    def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]]     = channel.stream()
                    def close(using Frame): Unit < Async                                      = channel.close.unit
                val id      = JsonRpcId.Num(1L)
                val data    = Present(Structure.Value.Str("peer details"))
                val success = JsonRpcResponse.success(id, Structure.Value.Null)
                val failure = JsonRpcResponse.failure(id, JsonRpcPeerError(-32602, "unknown model", data))
                JsonRpcTransport.fromWire(wire, JsonRpcFramer.lineDelimited).map { transport =>
                    transport.send(success).andThen(transport.send(failure)).andThen(transport.incoming.take(2).run).map { responses =>
                        assert(responses.head == success)
                        assert(responses.size == 2)
                        responses.last match
                            case JsonRpcResponse(`id`, Absent, Present(e), Absent) =>
                                assert((e.code, e.message, e.data) == (-32602, "unknown model", data))
                            case other => fail(s"expected the peer error response, got $other")
                        end match
                    }
                }
            }
        }
    }

    "empty wire transport produces no bytes" in {
        val wire   = JsonRpcWireTransport.empty
        val result = wire.incoming.run
        result.map { chunks =>
            assert(chunks.isEmpty)
        }
    }

    "fromWire round-trips one envelope over in-memory channel pair" in {
        for
            aToBChan <- Channel.initUnscoped[Chunk[Byte]](64)
            bToAChan <- Channel.initUnscoped[Chunk[Byte]](64)
            wireA = new JsonRpcWireTransport:
                def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
                    aToBChan.put(bytes)
                def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
                    bToAChan.stream()
                def close(using Frame): Unit < Async =
                    aToBChan.close.andThen(bToAChan.close).unit
            wireB = new JsonRpcWireTransport:
                def send(bytes: Chunk[Byte])(using Frame): Unit < (Async & Abort[Closed]) =
                    bToAChan.put(bytes)
                def incoming(using Frame): Stream[Chunk[Byte], Async & Abort[Closed]] =
                    aToBChan.stream()
                def close(using Frame): Unit < Async =
                    bToAChan.close.andThen(aToBChan.close).unit
            transportA   = new internal.transport.WireTransportAdapter(wireA, JsonRpcFramer.lineDelimited, summon[Schema[JsonRpcEnvelope]])
            transportB   = new internal.transport.WireTransportAdapter(wireB, JsonRpcFramer.lineDelimited, summon[Schema[JsonRpcEnvelope]])
            sentEnvelope = JsonRpcRequest(JsonRpcId.Num(1L), "ping", Absent, Absent)
            receiverFiber <- Fiber.initUnscoped(Abort.run[Closed](transportB.incoming.take(1).run))
            _             <- transportA.send(sentEnvelope)
            received      <- receiverFiber.get
        yield received match
            case Result.Success(chunk) =>
                assert(chunk.size == 1)
                chunk.head match
                    case JsonRpcRequest(id, method, _, _) =>
                        assert(id == JsonRpcId.Num(1L) && method == "ping")
                    case other =>
                        fail(s"unexpected envelope: $other")
                end match
            case other =>
                fail(s"unexpected result: $other")

    }

end JsonRpcTransportWireTransportTest
