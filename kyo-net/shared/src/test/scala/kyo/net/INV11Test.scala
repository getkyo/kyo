package kyo.net

import kyo.*

/** INV-11: [[Connection.closeReason]] is a total function of a single consistent [[kyo.net.internal.posix.HalfCloseState]] value, not a
  * torn read of two independent flags.
  *
  * These tests verify that each distinct close scenario maps to exactly the expected [[Connection.CloseReason]] value, and that
  * clean-close (TLS close_notify) and local-close are distinguishable from each other and from [[Connection.CloseReason.Active]].
  *
  * The third close reason, [[Connection.CloseReason.Truncated]] (bare TCP FIN without close_notify), is NOT achievable through the
  * public [[Connection]] API: both `conn.close()` and `Transport.close()` emit a TLS close_notify before the TCP close. Testing
  * Truncated requires raw socket access; it is covered at the driver level in NioTransportTlsCloseReasonTest (JVM NIO) and
  * PollerIoDriverTlsHalfCloseEtTest (posix).
  *
  * All leaves run via [[eachBackendTls]]. JS never wires `closeReasonFn`, so every JS leaf asserts [[Connection.CloseReason.Active]]
  * (XPLAT-5).
  */
class INV11Test extends Test:

    import AllowUnsafe.embrace.danger

    private def drainInbound(conn: Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.run[Closed](Loop.foreach(conn.inbound.safe.take.map(_ => Loop.continue))).map(_ => ())

    "INV-11 clean-close: after server close_notify, closeReason is CleanClose and not confused with Truncated or LocalClose" - eachBackendTls {
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
                _ <- drainInbound(client)
            yield
                val reason = client.closeReason
                client.close()
                listener.close()
                if kyo.internal.Platform.isJS then
                    assert(
                        reason == Connection.CloseReason.Active,
                        s"JS: closeReason must be Active (closeReasonFn not wired on JS); got $reason"
                    )
                else
                    assert(
                        reason == Connection.CloseReason.CleanClose,
                        s"closeReason after server TLS close_notify must be CleanClose (not Truncated, not LocalClose); got $reason"
                    )
                    assert(
                        reason != Connection.CloseReason.Truncated,
                        s"CleanClose must be distinguishable from Truncated; got $reason"
                    )
                    assert(
                        reason != Connection.CloseReason.LocalClose,
                        s"CleanClose must be distinguishable from LocalClose; got $reason"
                    )
                end if
                succeed
            end for
    }

    "INV-11 local-close: after client close, closeReason is LocalClose and not confused with CleanClose or Truncated" - eachBackendTls {
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
                val reason = client.closeReason
                serverConn.close()
                listener.close()
                if kyo.internal.Platform.isJS then
                    assert(
                        reason == Connection.CloseReason.Active,
                        s"JS: closeReason must be Active (closeReasonFn not wired on JS); got $reason"
                    )
                else
                    assert(
                        reason == Connection.CloseReason.LocalClose,
                        s"closeReason after client local close must be LocalClose (not CleanClose, not Truncated); got $reason"
                    )
                    assert(
                        reason != Connection.CloseReason.CleanClose,
                        s"LocalClose must be distinguishable from CleanClose; got $reason"
                    )
                    assert(
                        reason != Connection.CloseReason.Truncated,
                        s"LocalClose must be distinguishable from Truncated; got $reason"
                    )
                end if
                succeed
            end for
    }

end INV11Test
