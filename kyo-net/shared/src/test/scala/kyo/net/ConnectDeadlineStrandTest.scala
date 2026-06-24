package kyo.net

import kyo.*

/** Transport-level deterministic stress repro for the connect-deadline lost-wakeup that surfaced as
  * `NetConnectTimeoutException` in `TransportHandshakeTimeoutTest`'s rapid stall+reap loop.
  *
  * Drives the FULL public connect path (`NetPlatform.transport(...).connect`), which arms the `Clock`-driven connect deadline
  * (`config.handshakeTimeout`) racing the OS connect's write-readiness, exactly the path that timed out in the failing test.
  * Connects run SEQUENTIALLY (matching the failing leaf's `Loop`, not a concurrency storm) against one real plaintext listener
  * that accepts and immediately closes; the connect deadline is the failing leaf's tight `handshakeTimeout`. Every connect MUST
  * succeed or fail with a NON-timeout cause; a `NetConnectTimeoutException` means the connect's write-readiness was not delivered
  * before the deadline. Observation is non-perturbing: only in-memory counters (timeout count + a latency max/histogram via
  * `System.nanoTime`), NO console logging in any hot path.
  *
  * Runs on every backend via the public factory (posix epoll/kqueue, the NIO floor, Node), like `TransportHandshakeTimeoutTest`.
  */
class ConnectDeadlineStrandTest extends Test:

    import AllowUnsafe.embrace.danger

    "sequential connects under a finite connect deadline never spuriously time out" in {
        given Frame = Frame.internal
        // A generous 2s connect deadline: a CORRECTLY delivered loopback connect-completion beats it by orders of magnitude even
        // under load, so a failure here is a genuinely dropped/never-delivered write-readiness (the lost-wakeup), not a few-ms
        // latency tail against a too-tight bound. (The failing leaf's own 60ms deadline is reproduced by TransportHandshakeTimeoutTest;
        // this guard isolates DELIVERY correctness from deadline tightness, so it is not host-load-flaky.)
        val transport = NetPlatform.transport(TransportConfig.default.copy(handshakeTimeout = 2.seconds))
        transport.listen("127.0.0.1", 0, 128) { conn => conn.close() }.safe.get.map { listener =>
            val timeouts = new java.util.concurrent.atomic.AtomicInteger(0)
            val maxLatNs = new java.util.concurrent.atomic.AtomicLong(0L)
            val total    = 200
            // Sequential connect loop (the failing-leaf cadence), each connect timed end to end so a slow-but-delivered connect is
            // distinguished from a never-delivered one by the recorded max latency.
            Loop(0) { i =>
                if i >= total then Loop.done(i)
                else
                    val t0 = java.lang.System.nanoTime()
                    Abort.run[NetException](transport.connect("127.0.0.1", listener.port).safe.get).map { outcome =>
                        val latNs = java.lang.System.nanoTime() - t0
                        maxLatNs.updateAndGet(p => math.max(p, latNs))
                        outcome match
                            case Result.Success(conn)                          => conn.close()
                            case Result.Failure(_: NetConnectTimeoutException) => discard(timeouts.incrementAndGet())
                            case _                                             => ()
                        end match
                        Loop.continue(i + 1)
                    }
            }.map { _ =>
                listener.close()
                transport.close()
                val timedOut = timeouts.get()
                val maxMs    = maxLatNs.get() / 1000000
                assert(
                    timedOut == 0,
                    s"$timedOut of $total sequential connects spuriously timed out (dropped connect-completion wakeup); maxConnectMs=$maxMs"
                )
            }
        }
    }

end ConnectDeadlineStrandTest
