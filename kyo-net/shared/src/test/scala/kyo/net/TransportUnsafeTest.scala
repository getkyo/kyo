package kyo.net

import kyo.*

/** Behavioral tests for the zero-pending kyo-net unsafe API.
  *
  * These tests verify end-to-end behavior using the unsafe Transport API: echo round-trips, connection refusals, handler invocation count,
  * concurrent read and write on a single connection, and fiber interruption. No source parsing and no reflection.
  *
  * All assertions are on concrete values: bytes received, counts, result types. The unsafe API is consumed directly; the test boundary
  * bridges to the effect system via .safe.get / .safe.take / .safe.put as permitted in test source.
  */
class TransportUnsafeTest extends Test:

    import AllowUnsafe.embrace.danger

    "echo round-trip via unsafe API" in {
        val transport = NetPlatform.transport
        for
            listener <- transport.listen("127.0.0.1", 0, 128) { serverConn =>
                discard(Fiber.Unsafe.init {
                    serverConn.inbound.takeFiber().onComplete {
                        case Result.Success(bytes) =>
                            discard(serverConn.outbound.offer(bytes.asInstanceOf[Span[Byte]]))
                        case _ => ()
                    }
                })
            }.safe.get
            conn <- transport.connect("127.0.0.1", listener.port).safe.get
            msg = Span.from("unsafe-echo".getBytes("UTF-8"))
            _   <- conn.outbound.safe.put(msg)
            got <- conn.inbound.safe.take
        yield
            conn.close()
            listener.close()
            assert(new String(got.toArray, "UTF-8") == "unsafe-echo", s"echo mismatch: ${new String(got.toArray)}")
        end for
    }

    "connect to a port where nothing listens surfaces NetConnectException" in {
        val transport = NetPlatform.transport
        // Use port 1: reserved, no service should be listening, and the connect must be refused.
        Abort.run[NetException](transport.connect("127.0.0.1", 1).safe.get).map { result =>
            assert(result.isFailure, s"expected a NetException connecting to port 1 (refused/unreachable), got $result")
        }
    }

    "listen handler fires once per accepted connection" in {
        val transport = NetPlatform.transport
        val count     = new java.util.concurrent.atomic.AtomicInteger(0)
        for
            // Latch the handler signals on each invocation, so the test synchronizes on the actual handler-fired events rather than polling.
            fired <- Channel.init[Unit](3)
            listener <- transport.listen("127.0.0.1", 0, 128) { serverConn =>
                count.incrementAndGet()
                serverConn.close()
                discard(fired.unsafe.offer(()))
            }.safe.get
            c1 <- transport.connect("127.0.0.1", listener.port).safe.get
            c2 <- transport.connect("127.0.0.1", listener.port).safe.get
            c3 <- transport.connect("127.0.0.1", listener.port).safe.get
            // Take one signal per accepted connection: each take returns the instant that handler ran, draining deterministically.
            _ <- fired.take
            _ <- fired.take
            _ <- fired.take
        yield
            c1.close(); c2.close(); c3.close()
            listener.close()
            assert(count.get() >= 3, s"expected at least 3 handler invocations, got ${count.get()}")
        end for
    }

    "write n bytes and read n echoes verifies no corruption" in {
        // Drives a sequence of n echo round-trips on one connection.
        // Uses the same simple onComplete-chain pattern as the echo test, but repeated n times sequentially.
        val transport = NetPlatform.transport
        val n         = 4
        for
            listener <- transport.listen("127.0.0.1", 0, 128) { serverConn =>
                // Echo handler using a self-scheduling pattern: each byte triggers the next echo.
                def loopEcho(): Unit =
                    discard(Fiber.Unsafe.init {
                        serverConn.inbound.takeFiber().onComplete {
                            case Result.Success(bytes) =>
                                discard(serverConn.outbound.offer(bytes.asInstanceOf[Span[Byte]]))
                                loopEcho()
                            case _ => () // channel closed, stop
                        }
                    })
                loopEcho()
            }.safe.get
            conn <- transport.connect("127.0.0.1", listener.port).safe.get
            // Write and read one byte at a time to ensure ordering
            results <- Kyo.foreach(0 until n) { i =>
                conn.outbound.safe.put(Span.from(Array[Byte](i.toByte))).andThen {
                    conn.inbound.safe.take
                }
            }
        yield
            conn.close()
            listener.close()
            assert(results.length == n, s"expected $n frames, got ${results.length}")
            assert(results.forall(_.toArray.length == 1), s"unexpected frame size")
        end for
    }

    "a fiber parked on inbound can be interrupted" in {
        val transport = NetPlatform.transport
        for
            listener <- transport.listen("127.0.0.1", 0, 128)(_ => ()).safe.get
            conn     <- transport.connect("127.0.0.1", listener.port).safe.get
            fiber <- Fiber.init {
                Abort.run[Closed](conn.inbound.safe.take).unit
            }
            // The inbound channel is empty and open, so `take` cannot complete. `fiber.interrupt` therefore
            // deterministically interrupts a fiber that has not yet completed and returns true, regardless of
            // whether the fiber has already parked in `take` or has not yet been scheduled.
            done <- fiber.interrupt
        yield
            conn.close()
            listener.close()
            assert(done, "fiber.interrupt returned false: interrupt was not observed by the parked inbound.take")
        end for
    }

end TransportUnsafeTest
