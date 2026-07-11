package kyo

import kyo.net.internal.transport.Connection as NetConn

/** Tests the [[kyo.internal.transport.ConnectionWireTransport]] adapter (kyo-net `Connection` -> `JsonRpcWireTransport`) and the framing that
  * sits on it via [[JsonRpcTransport.fromWire]]. Driven through a driverless in-memory `Connection` pair (no driver, no syscalls), so the cases
  * run identically on every platform. Absorbs the framing and EOF assertions of the former JVM-only Content-Length stdio test.
  */
class ConnectionWireTransportTest extends JsonRpcTest:

    import AllowUnsafe.embrace.danger

    /** (wire over side a, peer side b): a write on `wire` is readable on `peer.inbound`; a write on `peer.outbound` reaches `wire.incoming`. */
    private def pair(using Frame): (internal.transport.ConnectionWireTransport, kyo.net.Connection) =
        val (a, b) = NetConn.inMemoryPair()
        (internal.transport.ConnectionWireTransport(a), b)

    private def peerWrite(peer: kyo.net.Connection, s: String)(using Frame): Unit < (Async & Abort[Closed]) =
        peer.outbound.safe.put(Span.fromUnsafe(s.getBytes("UTF-8")))

    "send enqueues bytes readable on the peer" in {
        val (wire, peer) = pair
        wire.send(Chunk.from("hello".getBytes("UTF-8"))).andThen {
            peer.inbound.safe.take.map(span => assert(new String(span.toArray, "UTF-8") == "hello"))
        }
    }

    "incoming emits one chunk per peer write, in order" in {
        val (wire, peer) = pair
        peerWrite(peer, "one").andThen(peerWrite(peer, "two")).andThen {
            wire.incoming.take(2).run.map { chunks =>
                assert(chunks.map(c => new String(c.toArray, "UTF-8")) == Chunk("one", "two"))
            }
        }
    }

    "incoming ends without hanging when the peer closes before sending" in {
        val (wire, peer) = pair
        Sync.defer(peer.close()).andThen {
            wire.incoming.run.map(chunks => assert(chunks.isEmpty))
        }
    }

    "send after the connection closes aborts Closed" in {
        val (wire, _) = pair
        wire.close.andThen {
            Abort.run[Closed](wire.send(Chunk.from("x".getBytes("UTF-8")))).map(r => assert(r.isFailure))
        }
    }

    "fromWire round-trips an envelope with the line-delimited framer" in {
        val (a, b) = NetConn.inMemoryPair()
        val wire   = internal.transport.ConnectionWireTransport(a)
        Scope.run {
            JsonRpcTransport.fromWire(wire, JsonRpcFramer.lineDelimited).map { t =>
                peerWrite(b, """{"jsonrpc":"2.0","method":"ping"}""" + "\n").andThen {
                    t.incoming.take(1).run.map { frames =>
                        assert(frames.size == 1)
                        frames.head match
                            case JsonRpcNotification("ping", _, _) => succeed
                            case other                             => fail(s"unexpected $other")
                    }
                }
            }
        }
    }

    "fromWire parses back-to-back Content-Length frames and skips a Content-Type header" in {
        val (a, b) = NetConn.inMemoryPair()
        val wire   = internal.transport.ConnectionWireTransport(a)
        val b1     = """{"jsonrpc":"2.0","method":"one"}"""
        val b2     = """{"jsonrpc":"2.0","method":"two"}"""
        // second frame carries an extra Content-Type header that must be skipped
        val raw =
            s"Content-Length: ${b1.length}\r\n\r\n$b1" +
                s"Content-Length: ${b2.length}\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n$b2"
        Scope.run {
            JsonRpcTransport.fromWire(wire, JsonRpcFramer.contentLength).map { t =>
                peerWrite(b, raw).andThen {
                    t.incoming.take(2).run.map { frames =>
                        val methods = frames.map {
                            case JsonRpcNotification(m, _, _) => m
                            case other                        => fail(s"unexpected $other")
                        }
                        assert(methods == Chunk("one", "two"))
                    }
                }
            }
        }
    }

    "fromWire send emits well-formed Content-Length wire bytes" in {
        val (a, b) = NetConn.inMemoryPair()
        val wire   = internal.transport.ConnectionWireTransport(a)
        Scope.run {
            JsonRpcTransport.fromWire(wire, JsonRpcFramer.contentLength).map { t =>
                t.send(JsonRpcNotification("ping", Absent, Absent)).andThen {
                    // the framed bytes land on the peer's inbound
                    b.inbound.safe.take.map { span =>
                        val wireStr = new String(span.toArray, "UTF-8")
                        assert(wireStr.startsWith("Content-Length: "), s"missing header: $wireStr")
                        val headerEnd = wireStr.indexOf("\r\n\r\n")
                        assert(headerEnd > 0)
                        val body        = wireStr.substring(headerEnd + 4)
                        val declaredLen = wireStr.substring(0, wireStr.indexOf("\r\n")).stripPrefix("Content-Length: ").trim.toInt
                        assert(body.contains("\"method\":\"ping\""))
                        assert(declaredLen == body.getBytes("UTF-8").length)
                    }
                }
            }
        }
    }

    "fromWire ends incoming on peer EOF without hanging" in {
        val (a, b) = NetConn.inMemoryPair()
        val wire   = internal.transport.ConnectionWireTransport(a)
        Scope.run {
            JsonRpcTransport.fromWire(wire, JsonRpcFramer.contentLength).map { t =>
                Sync.defer(b.close()).andThen {
                    Abort.run[Closed](t.incoming.run).map {
                        case Result.Panic(e)        => fail(s"panic: ${e.getMessage}")
                        case Result.Failure(_)      => succeed
                        case Result.Success(frames) => assert(frames.isEmpty)
                    }
                }
            }
        }
    }

end ConnectionWireTransportTest
