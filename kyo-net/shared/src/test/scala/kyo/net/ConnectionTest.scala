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
                _        <- Scope.ensure(Sync.defer(listener.close()))
                _        <- portRef.set(listener.port)
                port = listener.port
                conn <- transport.connect("127.0.0.1", port).safe.get
                _    <- Scope.ensure(Sync.defer(conn.close()))
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

    "Connection.close on an abandoned STARTTLS upgrade" - {
        // A detached connection's fd belongs to the in-flight upgrade, so close() cannot take it directly: the state machine bars its own teardown
        // path while Upgrading. That makes an abandoned upgrade's fd unreachable unless close() routes to the upgrade's owner, and nothing else
        // will reach it either: the detach tore down the pumps that would otherwise observe the peer's FIN and close, and the transport sweep that
        // force-closes an Upgrading connection never runs on the process-shared transport (never closed by design).
        //
        // Asserted from the PEER, because that is what distinguishes the two outcomes: the server's read side ends only when our FIN actually goes
        // out. An orphaned fd sends no FIN (that IS the CLOSE_WAIT signature: peer FIN received, none sent), so the peer's stream never ends and
        // this leaf fails on the suite timeout rather than passing vacuously.
        "releases the detached fd, so the peer observes the FIN" - eachBackendTls { (transport, _, clientTls) =>
            val accepted = Promise.Unsafe.init[Connection, Any]()
            for
                listener <- transport.listen("127.0.0.1", 0, 16) { conn =>
                    discard(accepted.complete(Result.succeed(conn)))
                }.safe.get
                _    <- Scope.ensure(Sync.defer(listener.close()))
                conn <- transport.connect("127.0.0.1", listener.port).safe.get
                peer <- accepted.safe.get
                _    <- Scope.ensure(Sync.defer(peer.close()))
                // This listener is plaintext and never answers a ClientHello, so the upgrade parks on a handshake read nothing will ever complete.
                // The detach runs synchronously inside upgradeToTls, so `conn` is already Upgrading by the time close() runs below: the
                // abandonment is forced by construction rather than by timing, on every backend.
                upgrade <- Sync.defer(transport.upgradeToTls(conn, clientTls, 16).safe)
                _       <- Sync.defer(conn.close())
                // Completes only once the peer's own read side sees our FIN, which requires the abandoned fd to have actually been shut down.
                _      <- peer.onClosing.safe.get
                result <- upgrade.getResult
            yield
                // The close() route settles the upgrade through its own typed leaf, not a generic interrupt: the upgrade did not fail on its own
                // terms, its connection was closed underneath it. Pinned so a regression that settles the promise some other way (or lets the
                // handshake time out into a NetTlsHandshakeException) is a failure rather than a silently-still-passing loose assertion.
                assert(
                    result.failure.exists(_.isInstanceOf[NetConnectionClosedException]),
                    s"close() on an Upgrading connection must abandon the upgrade with NetConnectionClosedException, got $result"
                )
            end for
        }

        // The SECOND abandonment route into the same ownership gap, and the one that most plausibly produced the CI orphan: the caller's fiber is
        // interrupted while awaiting the upgrade (a timeout, a losing race arm, an enclosing abort all reduce to this). Async.useResult links the
        // awaiting task to the upgrade's outcome promise via task.interrupts(v), so interrupting the awaiting fiber settles that promise, which
        // must release the fd exactly as the close() route above does. It shares the owner hook with the close() route but reaches it through
        // kyo-core's interrupt cascade rather than Connection.close, so it is the route that proves the OWNER is what releases the fd, not the
        // close() arm.
        //
        // Deterministic without a sleep: the interrupt reaches the upgrade promise whether or not the awaiting fiber has parked yet. If it has,
        // the task.interrupts link is already registered; if the interrupt lands first, IOTask.ensureInterrupt dispatches the pending Async.Join
        // and registers the link against the already-settled task, and IOPromise.interrupts then interrupts the upgrade immediately.
        "releases the detached fd when the awaiting fiber is interrupted, so the peer observes the FIN" - eachBackendTls {
            (transport, _, clientTls) =>
                val accepted = Promise.Unsafe.init[Connection, Any]()
                for
                    listener <- transport.listen("127.0.0.1", 0, 16) { conn =>
                        discard(accepted.complete(Result.succeed(conn)))
                    }.safe.get
                    _    <- Scope.ensure(Sync.defer(listener.close()))
                    conn <- transport.connect("127.0.0.1", listener.port).safe.get
                    peer <- accepted.safe.get
                    _    <- Scope.ensure(Sync.defer(peer.close()))
                    // Parks forever exactly as above: a plaintext listener never answers the ClientHello.
                    upgrade <- Sync.defer(transport.upgradeToTls(conn, clientTls, 16).safe)
                    // The awaiting fiber's body is ONLY the await, so the upgrade's Async.Join is at the head of its computation: that is what
                    // ensureInterrupt needs to find to register the cascade when it is interrupted before parking.
                    awaiting <- Fiber.initUnscoped(upgrade.get)
                    _        <- awaiting.interrupt
                    // Note the connection is never closed here: nothing but the interrupt-driven owner can release this fd.
                    _      <- peer.onClosing.safe.get
                    result <- upgrade.getResult
                yield
                    // The interrupt route settles the promise with kyo-core's own Interrupted panic (the cascade's error), not a typed net leaf:
                    // the upgrade was cancelled by its caller rather than closed underneath it.
                    assert(
                        result.panic.exists(_.isInstanceOf[Interrupted]),
                        s"an interrupted upgrade must settle as an Interrupted panic, got $result"
                    )
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
                _        <- Scope.ensure(Sync.defer(listener.close()))
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
