package kyo.net

import kyo.*

/** Handshake deadlines (`NetTlsConfig.handshakeTimeout`, CWE-400 slowloris) on every backend and TLS provider, each cell on a transport whose
  * clock is controlled: a deadline fires at the virtual instant a leaf advances to and never during an unrelated wait, so every leaf asserts
  * the exact instant and no leaf depends on the wall clock.
  *
  * A stalled server handshake is a plaintext client that completes the TCP accept and never sends a ClientHello; its reap closes the accepted
  * fd, which the client observes as its inbound terminating. The server arms the deadline at accept, so the leaves latch on the armed timer
  * (`awaitPendingSleepers`) before advancing. Clients connect with `connectTimeout = Infinity`, so no connect deadline shares the clock.
  */
class TransportHandshakeTimeoutTest extends Test:

    import AllowUnsafe.embrace.danger

    /** Read exactly `target` bytes from a connection's inbound channel, concatenated. */
    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** Whether the server reaped this stalled client: its inbound ends with a Closed failure or an empty EOF span, depending on backend. */
    private def reaped(client: Connection)(using Frame): Boolean < Async =
        Abort.run[Closed](client.inbound.safe.take).map {
            case Result.Success(span) => span.isEmpty
            case Result.Failure(_)    => true
            case _                    => false
        }

    private def echo(serverConn: Connection)(using Frame): Unit =
        discard(Sync.Unsafe.evalOrThrow {
            Fiber.initUnscoped {
                Abort.run[Closed] {
                    Loop.foreach(serverConn.inbound.safe.take.map(chunk => serverConn.outbound.safe.put(chunk).andThen(Loop.continue)))
                }.unit
            }
        })

    /** An echo handler that completes `handled` once the server has a connection. A client's handshake can finish before the server reads
      * its Finished, so only the handler proves the server settled its handshake guard and the deadline can no longer claim the connection.
      */
    private def handledEcho(handled: Promise[Unit, Any])(using Frame): Connection => Unit = serverConn =>
        echo(serverConn)
        handled.unsafe.completeDiscard(Result.succeed(()))

    /** A plaintext listener's handler that completes `latch` when the first bytes arrive: the client's ClientHello, sent only after its
      * handshake deadline is armed.
      */
    private def onFirstBytes(latch: Promise[Unit, Any])(using Frame): Connection => Unit = serverConn =>
        discard(Sync.Unsafe.evalOrThrow {
            Fiber.initUnscoped(Abort.run[Closed](serverConn.inbound.safe.take).map(_ => latch.unsafe.completeDiscard(Result.succeed(()))))
        })

    private def roundTrips(client: Connection, text: String)(using Frame): Boolean < (Async & Abort[Closed]) =
        val message = text.getBytes("UTF-8")
        client.outbound.safe.put(Span.fromUnsafe(message)).andThen(collect(client, message.length)).map(_.sameElements(message))

    // connectTimeout bounds the TCP phase and handshakeTimeout the handshake, so the worst case is their sum. A plaintext listener accepts the
    // TCP connection and never speaks TLS: if the connect timer still owned the connection after the TCP phase, it would fire at 1 s and
    // report a connect timeout for a stall that is entirely in the handshake.
    "connectTls hands the deadline from the TCP phase to the handshake phase" - eachBackendTlsOnClock { (transport, tc, _, clientTls) =>
        for
            hello    <- Promise.init[Unit, Any]
            listener <- transport.listen("127.0.0.1", 0, 16)(onFirstBytes(hello)).safe.get
            _        <- Scope.ensure(Sync.defer(listener.close()))
            outcome  <- Fiber.init(Abort.run[NetException](
                transport.connectTls(
                    "127.0.0.1",
                    listener.port,
                    clientTls.copy(handshakeTimeout = 10.seconds),
                    connectTimeout = 1.second
                ).safe.get
            ))
            _      <- hello.get
            _      <- tc.advance(1.second)
            _      <- tc.advance(9.seconds)
            result <- outcome.get
        yield result match
            case Result.Failure(e: NetTlsHandshakeTimeoutException) =>
                assert(e.timeout == 10.seconds, s"the handshake phase must fail on its own deadline, got ${e.timeout}")
            case Result.Failure(e: NetConnectTimeoutException) =>
                fail(
                    s"the TCP phase completed, so its ${e.timeout} deadline must have been disarmed: the connect timer owned the handshake"
                )
            case Result.Success(conn) =>
                conn.close()
                fail("expected the handshake deadline to fire, got a connection")
            case other =>
                fail(s"expected the handshake deadline to fire, got $other")
        end for
    }

    "a stalled server handshake is reaped at its deadline, not one tick before, and one that completes before it is not" -
        eachBackendTlsOnClock {
            (transport, tc, serverTls, clientTls) =>
                val timeout = 150.millis
                for
                    handled  <- Promise.init[Unit, Any]
                    listener <-
                        transport.listenTls("127.0.0.1", 0, 16, serverTls.copy(handshakeTimeout = timeout))(handledEcho(handled)).safe.get
                    _       <- Scope.ensure(Sync.defer(listener.close()))
                    early   <- transport.connect("127.0.0.1", listener.port, Duration.Infinity).safe.get
                    stalled <- transport.connect("127.0.0.1", listener.port, Duration.Infinity).safe.get
                    _       <- tc.awaitPendingSleepers(2)
                    _       <- tc.advance(timeout.minusOrZero(1.millis))
                    // One tick before the deadline both server handshakes are alive: this one completes, on both sides.
                    upgraded <- transport.upgradeToTls(early, clientTls.copy(handshakeTimeout = Duration.Infinity), 16).safe.get
                    _        <- handled.get
                    _        <- tc.advance(1.millis)
                    reap     <- reaped(stalled)
                    // Past the deadline, the completed handshake's timer is disarmed, not fired.
                    alive <- roundTrips(upgraded, "completed-before-deadline")
                yield
                    upgraded.close()
                    stalled.close()
                    assert(reap, "the stalled handshake must be reaped at its deadline")
                    assert(alive, "a handshake that completed before its deadline must survive past it")
                end for
        }

    "repeatedly reaping stalled server handshakes does not corrupt memory (io_uring UAF regression guard)" - eachBackendTlsOnClock {
        (transport, tc, serverTls, _) =>
            // A stalled server handshake parks in awaitReadCiphertext with an in-flight io_uring recv SQE on handle.readBuffer. The deadline
            // teardown must route the buffer and engine frees through the driver's closeHandle, deferred until the recv CQE reaps; freeing
            // them directly writes into kernel-owned memory. The corruption is silent on most runs, so the stall and reap cycle repeats: under
            // Valgrind or ASan on real io_uring an unsafe teardown reports the UAF here, and on every backend each stall must be reaped.
            val timeout = 60.millis
            for
                listener <- transport.listenTls("127.0.0.1", 0, 64, serverTls.copy(handshakeTimeout = timeout))(_ => ()).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                cycles   <- Loop(0) { i =>
                    if i >= 30 then Loop.done(i)
                    else
                        for
                            client <- transport.connect("127.0.0.1", listener.port, Duration.Infinity).safe.get
                            _      <- tc.awaitPendingSleepers(1)
                            _      <- tc.advance(timeout)
                            reap   <- reaped(client)
                        yield
                            client.close()
                            assert(reap, s"iteration $i: the stalled handshake must be reaped at its deadline")
                            Loop.continue(i + 1)
                }
            yield assert(cycles == 30)
            end for
    }

    "a handshake that completes within the deadline is not reaped past it" - eachBackendTlsOnClock {
        (transport, tc, serverTls, clientTls) =>
            for
                handled  <- Promise.init[Unit, Any]
                listener <-
                    transport.listenTls("127.0.0.1", 0, 16, serverTls.copy(handshakeTimeout = 10.seconds))(handledEcho(handled)).safe.get
                _      <- Scope.ensure(Sync.defer(listener.close()))
                client <- transport.connectTls("127.0.0.1", listener.port, clientTls.copy(handshakeTimeout = Duration.Infinity)).safe.get
                _      <- Scope.ensure(Sync.defer(client.close()))
                _      <- handled.get
                _      <- tc.advance(10.seconds + 1.millis)
                alive  <- roundTrips(client, "completes-within-deadline")
            yield assert(alive, "a completed handshake must not be reaped when its deadline passes")
            end for
    }

    "the default 30 s handshake deadline reaps at 30 s, not one tick before" - eachBackendTlsOnClock {
        (transport, tc, serverTls, clientTls) =>
            assert(NetTlsConfig.default.handshakeTimeout == 30.seconds)
            for
                handled  <- Promise.init[Unit, Any]
                listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls)(handledEcho(handled)).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                early    <- transport.connect("127.0.0.1", listener.port, Duration.Infinity).safe.get
                stalled  <- transport.connect("127.0.0.1", listener.port, Duration.Infinity).safe.get
                _        <- tc.awaitPendingSleepers(2)
                _        <- tc.advance(30.seconds.minusOrZero(1.millis))
                upgraded <- transport.upgradeToTls(early, clientTls.copy(handshakeTimeout = Duration.Infinity), 16).safe.get
                _        <- handled.get
                _        <- tc.advance(1.millis)
                reap     <- reaped(stalled)
            yield
                upgraded.close()
                stalled.close()
                assert(reap, "the stalled handshake must be reaped at the default deadline")
            end for
    }

    "handshakeTimeout = Infinity arms no timer: a stall a year long still completes" - eachBackendTlsOnClock {
        (transport, tc, serverTls, clientTls) =>
            val unbounded = clientTls.copy(handshakeTimeout = Duration.Infinity)
            for
                listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls.copy(handshakeTimeout = Duration.Infinity))(echo).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                stalled  <- transport.connect("127.0.0.1", listener.port, Duration.Infinity).safe.get
                // A second client completes its handshake. The listener accepts in connection order, so the stalled client's server handshake
                // was accepted first: had it armed a timer, the timer would be pending before the advance below.
                witness <- transport.connectTls("127.0.0.1", listener.port, unbounded).safe.get
                _       <- tc.advance(365.days)
                resumed <- transport.upgradeToTls(stalled, unbounded, 16).safe.get
                alive   <- roundTrips(resumed, "a-year-later")
            yield
                witness.close()
                resumed.close()
                assert(alive, "with handshakeTimeout = Infinity a stalled server handshake must survive any stall")
            end for
    }

    // The client side of the same guard. A peer that completes the TCP connect and never speaks TLS parks the client handshake on a read that
    // never arrives, holding the fd and the engine; without a deadline that is a permanent leak on the process-shared transport.
    "a client handshake that stalls is reaped at its own deadline, not one tick before" - eachBackendTlsOnClock {
        (transport, tc, _, clientTls) =>
            val timeout = 150.millis
            for
                hello    <- Promise.init[Unit, Any]
                listener <- transport.listen("127.0.0.1", 0, 16)(onFirstBytes(hello)).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                outcome  <- Fiber.init(Abort.run[NetException](
                    transport.connectTls("127.0.0.1", listener.port, clientTls.copy(handshakeTimeout = timeout), Duration.Infinity).safe.get
                ))
                // The ClientHello is sent after the deadline is armed.
                _      <- hello.get
                _      <- tc.awaitPendingSleepers(1)
                _      <- tc.advance(timeout.minusOrZero(1.millis))
                _      <- tc.awaitPendingSleepers(1)
                _      <- tc.advance(1.millis)
                result <- outcome.get
            yield result match
                case Result.Failure(e: NetTlsHandshakeTimeoutException) =>
                    assert(e.timeout == timeout, s"expected the client's own $timeout deadline, got ${e.timeout}")
                case Result.Success(conn) =>
                    conn.close()
                    fail("expected the client handshake deadline to fire, got a connection")
                case other =>
                    fail(s"expected the client handshake deadline to fire, got $other")
            end for
    }

end TransportHandshakeTimeoutTest
