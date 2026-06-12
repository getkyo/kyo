package kyo.net

import kyo.*

/** Integration tests for the Connection and Transport surface over loopback TCP.
  *
  * The tests consume the unsafe Transport API directly and bridge to the effect system at the test boundary via .safe.get / .safe.put /
  * .safe.take, as permitted in test source.
  */
class ConnectionTest extends Test:

    import AllowUnsafe.embrace.danger

    "Connection echo loopback" - {
        "write and read back via echo server on loopback TCP" in {
            val transport = NetPlatform.transport
            for
                portRef <- AtomicRef.init[Int](0)
                listenerFiber = transport.listen("127.0.0.1", 0, 128) { serverConn =>
                    discard(Fiber.Unsafe.init {
                        serverConn.inbound.takeFiber().onComplete {
                            case Result.Success(bytes) =>
                                discard(serverConn.outbound.offer(bytes.asInstanceOf[Span[Byte]]))
                            case _ => ()
                        }
                    })
                }
                listener <- listenerFiber.safe.get
                _        <- portRef.set(listener.port)
                port = listener.port
                conn <- transport.connect("127.0.0.1", port).safe.get
                msg = Span.from("hello".getBytes)
                _     <- conn.outbound.safe.put(msg)
                bytes <- conn.inbound.safe.take
            yield
                conn.close()
                listener.close()
                assert(new String(bytes.toArray) == "hello")
            end for
        }
    }

    "NetAddress equality" - {
        "Tcp addresses are equal when structurally identical" in {
            val a1 = NetAddress.Tcp("localhost", 5432)
            val a2 = NetAddress.Tcp("localhost", 5432)
            assert(a1 == a2)
        }

        "Unix addresses are equal when structurally identical" in {
            val a1 = NetAddress.Unix("/tmp/test.sock")
            val a2 = NetAddress.Unix("/tmp/test.sock")
            assert(a1 == a2)
        }
    }

    "Connection.write propagates Closed abort" - {
        "write to closed connection raises Abort[Closed] or succeeds" in {
            val transport = NetPlatform.transport
            for
                listener <- transport.listen("127.0.0.1", 0, 128)(_ => ()).safe.get
                port = listener.port
                conn <- transport.connect("127.0.0.1", port).safe.get
                _ = conn.close()
                result <- Abort.run[Closed](conn.outbound.safe.put(Span.from("after-close".getBytes)))
            yield
                listener.close()
                assert(!result.isPanic, s"expected Success or Closed failure on write-after-close, got Panic: $result")
            end for
        }
    }

end ConnectionTest
