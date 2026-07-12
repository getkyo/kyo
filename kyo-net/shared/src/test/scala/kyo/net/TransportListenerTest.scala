package kyo.net

import kyo.*

/** Cross-backend listener-lifecycle guarantees for the public [[Transport]]/[[Listener]] surface, asserted once over `NetPlatform.transport`
  * (posix on JVM/Native, Node on JS). These were previously asserted only per-backend (NIO `NioTransportTest`, posix `PosixTransportSurfaceTest`),
  * never on JS, and the behavior must be identical everywhere.
  */
class TransportListenerTest extends Test:

    import AllowUnsafe.embrace.danger

    "Transport listener lifecycle (every backend via NetPlatform.transport)" - {
        "a listener reports its bound TCP address (ephemeral port resolved, host echoed)" in {
            val transport = NetPlatform.transport
            transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { listener =>
                val port    = listener.port
                val address = listener.address
                listener.close()
                assert(port > 0, s"an ephemeral listen must resolve a real port, got $port")
                assert(address == NetAddress.Tcp("127.0.0.1", port), s"listener.address must report the bound address, got $address")
            }
        }

        "listening on an already-bound port fails Closed" in {
            val transport = NetPlatform.transport
            transport.listen("127.0.0.1", 0, 16)(_ => ()).safe.get.map { first =>
                Abort.run[Closed](transport.listen("127.0.0.1", first.port, 16)(_ => ()).safe.get).map { second =>
                    second.foreach(_.close())
                    first.close()
                    assert(second.isFailure, s"a second listen on an in-use port must fail Closed, got $second")
                }
            }
        }

        "a handler that throws on one connection does not wedge the accept loop" in {
            val transport = NetPlatform.transport
            val firstSeen = new java.util.concurrent.atomic.AtomicBoolean(false)
            for
                listener <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                    if firstSeen.compareAndSet(false, true) then throw new RuntimeException("handler boom on first connection")
                    else
                        discard(Sync.Unsafe.evalOrThrow {
                            Fiber.initUnscoped {
                                Abort.run[Closed] {
                                    Loop.foreach {
                                        serverConn.inbound.safe.take.map(chunk =>
                                            serverConn.outbound.safe.put(chunk).andThen(Loop.continue)
                                        )
                                    }
                                }.unit
                            }
                        })
                }.safe.get
                // First connection: triggers the throwing handler. Best-effort; it may be torn down.
                first <- transport.connect("127.0.0.1", listener.port).safe.get
                _ = first.close()
                // Second connection must still be accepted and echo, proving the accept loop survived the handler throw.
                second <- transport.connect("127.0.0.1", listener.port).safe.get
                message = "after-throw".getBytes("UTF-8")
                _      <- second.outbound.safe.put(Span.fromUnsafe(message))
                echoed <- second.inbound.safe.take
            yield
                second.close()
                listener.close()
                assert(echoed.toArray.sameElements(message), s"the accept loop must survive a handler throw; second connection echo failed")
            end for
        }
    }

end TransportListenerTest
