package kyo.net.internal.transport

import kyo.*
import kyo.net.Connection
import kyo.net.Test

/** Invariant tests for the single-field [[HalfCloseState]] state machine via [[Connection.status]].
  *
  * All three tests drive a real TLS session through [[eachBackendTls]], so every registered backend x TLS-implementation cell is covered.
  * JS never wires `statusFn` on its connections, so every JS leaf asserts [[Connection.Status.Active]].
  *
  * The three scenarios are:
  *   - `clean-close`: server calls `close()` (sends TLS close_notify); client drains inbound; expects [[Connection.Status.CleanClose]].
  *   - `local-close`: client calls `close()` before any server-initiated close; expects [[Connection.Status.LocalClose]].
  *   - `single-writer-no-torn-read`: a latch ensures the reader is already draining when the server fires its close_notify; the result
  *     must be [[Connection.Status.CleanClose]], never [[Connection.Status.Truncated]]. This tests that the single-field
  *     transition (Open -> PeerCleanClose, a single volatile write) cannot produce a torn intermediate state.
  *
  * Truncated (bare TCP FIN without close_notify) is NOT testable through the public [[Connection]] API: both `conn.close()` and
  * `Transport.close()` send a TLS close_notify first, so every close path through this API produces CleanClose. The Truncated case is
  * covered at the driver level in NioTransportTlsCloseReasonTest (JVM NIO) and PollerIoDriverTlsHalfCloseEtTest (posix).
  */
class HalfCloseStateTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Drain `conn.inbound` to completion. The [[Closed]] raised by the final `take` is caught and discarded. The caller reads
      * [[Connection.status]] after this returns.
      */
    private def drainInbound(conn: Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.run[Closed](Loop.foreach(conn.inbound.safe.take.map(_ => Loop.continue))).map(_ => ())

    /** Expected [[Connection.Status]] on this platform for a given scenario reason. JS never wires `statusFn`, so every JS leaf
      * sees [[Connection.Status.Active]]. On JVM and Native the engine path wires the fn and reports the true reason.
      */
    private def expected(reason: Connection.Status): Connection.Status =
        if kyo.internal.Platform.isJS then Connection.Status.Active else reason

    "clean-close: server close_notify gives CleanClose on inbound drain" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            for
                serverConnCh <- Channel.init[Connection](1)
                listener <- transport.listen("127.0.0.1", 0, 16, serverTls) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed](serverConnCh.put(serverConn)).map(_ => ())
                        }
                    })
                }.safe.get
                client     <- transport.connect("127.0.0.1", listener.port, clientTls).safe.get
                serverConn <- serverConnCh.take
                _ = serverConn.close() // sends TLS close_notify then TCP FIN
                _ <- drainInbound(client) // suspends until inbound closes after close_notify
            yield
                val reason = client.status
                client.close()
                listener.close()
                assert(
                    reason == expected(Connection.Status.CleanClose),
                    s"expected ${expected(Connection.Status.CleanClose)} after server TLS close_notify; got $reason"
                )
                succeed
            end for
    }

    "local-close: client close before server gives LocalClose" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            for
                serverConnCh <- Channel.init[Connection](1)
                listener <- transport.listen("127.0.0.1", 0, 16, serverTls) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed](serverConnCh.put(serverConn)).map(_ => ())
                        }
                    })
                }.safe.get
                client     <- transport.connect("127.0.0.1", listener.port, clientTls).safe.get
                serverConn <- serverConnCh.take
                _ = client.close() // local close before any server-initiated close
            yield
                val reason = client.status
                serverConn.close()
                listener.close()
                assert(
                    reason == expected(Connection.Status.LocalClose),
                    s"expected ${expected(Connection.Status.LocalClose)} after client local close; got $reason"
                )
                succeed
            end for
    }

    "single-writer-no-torn-read: status after server close_notify is CleanClose, never Truncated" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // Given: a reader fiber is draining client.inbound (suspended on take) before the server closes.
            // When: server calls close() (writes halfClose = PeerCleanClose via a single volatile write).
            // Then: client.status is CleanClose (never Truncated), proving the single-field state
            //       machine cannot produce a torn intermediate value. A Channel latch ensures the reader is
            //       established (suspended on inbound take) before the server fires its close_notify.
            for
                ready        <- Channel.init[Unit](1)
                serverConnCh <- Channel.init[Connection](1)
                listener <- transport.listen("127.0.0.1", 0, 16, serverTls) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed](serverConnCh.put(serverConn)).map(_ => ())
                        }
                    })
                }.safe.get
                client     <- transport.connect("127.0.0.1", listener.port, clientTls).safe.get
                serverConn <- serverConnCh.take
                // Reader: signals readiness, then drains inbound with cooperative suspension at each take.
                // The Closed raised when inbound closes is caught by Abort.run, terminating the loop.
                readerFiber <- Fiber.init {
                    ready.put(()).andThen {
                        Abort.run[Closed](Loop.foreach(client.inbound.safe.take.map(_ => Loop.continue))).map(_ => ())
                    }
                }
                // Wait for the reader to be established (suspended inside the loop), then close the server.
                _ <- ready.take
                _ = serverConn.close() // single volatile write: halfClose = PeerCleanClose
                _ <- readerFiber.get
            yield
                val reason = client.status
                client.close()
                listener.close()
                assert(
                    reason == expected(Connection.Status.CleanClose),
                    s"status after server TLS close_notify must be ${expected(Connection.Status.CleanClose)}; got $reason. " +
                        s"Truncated would indicate a torn read of the half-close state."
                )
                succeed
            end for
    }

end HalfCloseStateTest
