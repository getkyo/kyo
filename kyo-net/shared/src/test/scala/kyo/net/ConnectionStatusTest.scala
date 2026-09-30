package kyo.net

import kyo.*

/** [[Connection.status]] is a total function of a single consistent [[kyo.net.internal.posix.HalfCloseState]] value, not a
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
  * The TLS leaves run via [[eachBackendTls]]; the plaintext leaf runs via [[eachBackend]], since a plaintext connection reports no close reason
  * on any transport. A transport reports the TLS close reason only when it terminates TLS in-process (posix on every platform including JS, NIO
  * on JVM); the Node transport delegates TLS to Node, never observes the close_notify, and its connections read Active. Which cells report is
  * read from [[Transport.reportsTlsCloseReason]], the capability production consults.
  */
class ConnectionStatusTest extends Test:

    import AllowUnsafe.embrace.danger

    private def drainInbound(conn: Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        Abort.run[Closed](Loop.foreach(conn.inbound.safe.take.map(_ => Loop.continue))).map(_ => ())

    /** Whether this cell's transport reports the TLS close reason through [[Connection.status]], read from the capability the transport
      * declares so the matrix and production agree by construction.
      */
    private def reportsCloseReason(transport: Transport): Boolean =
        transport.reportsTlsCloseReason

    "clean-close: after server close_notify, status is CleanClose and not confused with Truncated or LocalClose" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            for
                serverConnCh <- Channel.init[Connection](1)
                listener     <- transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed](serverConnCh.put(serverConn)).map(_ => ())
                        }
                    })
                }.safe.get
                _          <- Scope.ensure(Sync.defer(listener.close()))
                client     <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                _          <- Scope.ensure(Sync.defer(client.close()))
                serverConn <- serverConnCh.take
                _ = serverConn.close() // sends TLS close_notify then TCP FIN
                _ <- drainInbound(client)
            yield
                val reason = client.status
                client.close()
                listener.close()
                if !reportsCloseReason(transport) then
                    assert(
                        reason == Connection.Status.Active,
                        s"the Node transport wires no statusFn, so status must be Active; got $reason"
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

    "local-close: after client close, status is LocalClose and not confused with CleanClose or Truncated" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            for
                serverConnCh <- Channel.init[Connection](1)
                listener     <- transport.listenTls("127.0.0.1", 0, 16, serverTls) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed](serverConnCh.put(serverConn)).map(_ => ())
                        }
                    })
                }.safe.get
                _          <- Scope.ensure(Sync.defer(listener.close()))
                client     <- transport.connectTls("127.0.0.1", listener.port, clientTls).safe.get
                _          <- Scope.ensure(Sync.defer(client.close()))
                serverConn <- serverConnCh.take
                _ = client.close() // local close before any server-initiated close
            yield
                val reason = client.status
                serverConn.close()
                listener.close()
                if !reportsCloseReason(transport) then
                    assert(
                        reason == Connection.Status.Active,
                        s"the Node transport wires no statusFn, so status must be Active; got $reason"
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

    "plaintext: the peer's FIN leaves the status Active, before and after the local close" - eachBackend { transport =>
        // A plaintext connection has no close_notify exchange, so its status is the close reason of nothing: Active for its whole life, on
        // every transport, as the Connection.status contract states. A transport that maps the bare FIN to Truncated here reports a
        // truncation attack on every ordinary plaintext close.
        for
            serverConnCh <- Channel.init[Connection](1)
            listener     <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                discard(Sync.Unsafe.evalOrThrow {
                    Fiber.initUnscoped {
                        Abort.run[Closed](serverConnCh.put(serverConn)).map(_ => ())
                    }
                })
            }.safe.get
            _          <- Scope.ensure(Sync.defer(listener.close()))
            client     <- transport.connect("127.0.0.1", listener.port).safe.get
            _          <- Scope.ensure(Sync.defer(client.close()))
            serverConn <- serverConnCh.take
            _ = serverConn.close() // a bare TCP FIN: plaintext has nothing else to send
            _ <- drainInbound(client)
        yield
            val afterPeerFin = client.status
            client.close()
            val afterLocalClose = client.status
            listener.close()
            assert(
                afterPeerFin == Connection.Status.Active,
                s"a plaintext connection reports no close reason, so the peer's FIN must leave it Active; got $afterPeerFin"
            )
            assert(
                afterLocalClose == Connection.Status.Active,
                s"a plaintext connection reports no close reason, so its own close must leave it Active; got $afterLocalClose"
            )
            succeed
        end for
    }

    "STARTTLS: a plaintext connection upgraded to TLS reports CleanClose after the server's close_notify" - eachBackendTls {
        (transport, serverTls, clientTls) =>
            // The status is installed by the wiring of a TLS handle. A connection that starts plaintext and upgrades in place gets its TLS
            // handle at the upgrade, so the upgrade's wiring must install what the plaintext wiring left out.
            val signal = Span.from(Array[Byte]('U'))
            val ready  = Span.from(Array[Byte]('R'))
            for
                serverTlsCh <- Channel.init[Connection](1)
                listener    <- transport.listen("127.0.0.1", 0, 16) { serverConn =>
                    discard(Sync.Unsafe.evalOrThrow {
                        Fiber.initUnscoped {
                            Abort.run[Closed | NetException] {
                                serverConn.inbound.safe.take.flatMap { _ =>
                                    serverConn.outbound.safe.put(ready).andThen {
                                        transport.upgradeToTls(serverConn, serverTls, 16).safe.get.flatMap(serverTlsCh.put)
                                    }
                                }
                            }.unit
                        }
                    })
                }.safe.get
                _             <- Scope.ensure(Sync.defer(listener.close()))
                conn          <- transport.connect("127.0.0.1", listener.port).safe.get
                _             <- Scope.ensure(Sync.defer(conn.close()))
                _             <- conn.outbound.safe.put(signal)
                _             <- conn.inbound.safe.take
                client        <- transport.upgradeToTls(conn, clientTls.copy(sniHostname = Present("localhost")), 16).safe.get
                _             <- Scope.ensure(Sync.defer(client.close()))
                serverTlsConn <- serverTlsCh.take
                _ = serverTlsConn.close() // sends TLS close_notify then TCP FIN
                _ <- drainInbound(client)
            yield
                val reason = client.status
                client.close()
                listener.close()
                if !reportsCloseReason(transport) then
                    assert(
                        reason == Connection.Status.Active,
                        s"the Node transport wires no statusFn, so status must be Active; got $reason"
                    )
                else
                    assert(
                        reason == Connection.Status.CleanClose,
                        s"an upgraded connection must report CleanClose after the server's close_notify; got $reason"
                    )
                end if
                succeed
            end for
    }

end ConnectionStatusTest
