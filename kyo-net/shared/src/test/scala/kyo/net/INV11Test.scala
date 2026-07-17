package kyo.net

import kyo.*

/** INV-11: [[Connection.status]] is a total function of a single consistent [[kyo.net.internal.posix.HalfCloseState]] value, not a
  * torn read of two independent flags.
  *
  * These tests verify that each distinct close scenario maps to exactly the expected [[Connection.Status]] value, and that
  * clean-close (TLS close_notify) and local-close are distinguishable from each other and from [[Connection.Status.Active]].
  *
  * The third close reason, [[Connection.Status.Truncated]] (bare TCP FIN without close_notify), is NOT achievable through the
  * public [[Connection]] API: both `conn.close()` and `Transport.close()` emit a TLS close_notify before the TCP close. Testing
  * Truncated requires raw socket access; it is covered at the driver level in NioTransportTlsCloseReasonTest (JVM NIO) and
  * PollerIoDriverTlsHalfCloseEtTest (posix).
  *
  * All leaves run via [[eachBackendTls]]. JS never wires `statusFn`, so every JS leaf asserts [[Connection.Status.Active]]
  * (XPLAT-5).
  */
class INV11Test extends Test:

    import AllowUnsafe.embrace.danger

    private def drainInbound(conn: Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.run[Closed](Loop.foreach(conn.inbound.safe.take.map(_ => Loop.continue))).map(_ => ())

    "INV-11 clean-close: after server close_notify, status is CleanClose and not confused with Truncated or LocalClose" - eachBackendTls {
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
                val reason = client.status
                client.close()
                listener.close()
                if kyo.internal.Platform.isJS then
                    assert(
                        reason == Connection.Status.Active,
                        s"JS: status must be Active (statusFn not wired on JS); got $reason"
                    )
                else
                    assert(
                        reason == Connection.Status.CleanClose,
                        s"status after server TLS close_notify must be CleanClose (not Truncated, not LocalClose); got $reason"
                    )
                    assert(
                        reason != Connection.Status.Truncated,
                        s"CleanClose must be distinguishable from Truncated; got $reason"
                    )
                    assert(
                        reason != Connection.Status.LocalClose,
                        s"CleanClose must be distinguishable from LocalClose; got $reason"
                    )
                end if
                succeed
            end for
    }

    "INV-11 local-close: after client close, status is LocalClose and not confused with CleanClose or Truncated" - eachBackendTls {
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
                if kyo.internal.Platform.isJS then
                    assert(
                        reason == Connection.Status.Active,
                        s"JS: status must be Active (statusFn not wired on JS); got $reason"
                    )
                else
                    assert(
                        reason == Connection.Status.LocalClose,
                        s"status after client local close must be LocalClose (not CleanClose, not Truncated); got $reason"
                    )
                    assert(
                        reason != Connection.Status.CleanClose,
                        s"LocalClose must be distinguishable from CleanClose; got $reason"
                    )
                    assert(
                        reason != Connection.Status.Truncated,
                        s"LocalClose must be distinguishable from Truncated; got $reason"
                    )
                end if
                succeed
            end for
    }

end INV11Test
