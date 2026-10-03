package kyo.net

import java.util.concurrent.atomic.AtomicBoolean as JAtomicBoolean
import kyo.*

/** Cross-driver resilience: the process-shared transport driver must survive per-connection failures without ever
  * closing itself. A single connection failing in a poll/reap cycle stays isolated to that connection; because the
  * transport is a process-lifetime shared singleton, closing the driver would wedge every unrelated caller forever with
  * "... is closed".
  *
  * Every scenario runs on every registered backend via [[eachBackendWatched]] (io_uring/epoll/kqueue/nio), drives a failure
  * load, and then asserts a co-tenant clean-echo listener on the SAME transport still round-trips: if the driver closed
  * itself under the load, that liveness probe fails with "... is closed". A driver that wedges instead is caught by the
  * watch: each scenario ticks once per unit of work the driver completes for it, and only work that needs the driver may
  * tick, since a tick from a timer or a local loop would keep a wedged leaf looking alive.
  */
class TransportResilienceTest extends Test:

    import AllowUnsafe.embrace.danger

    // Every scenario shares the process-lifetime per-backend transport (TestBackends builds one transport per backend and
    // reuses it across leaves, exactly as production shares NetPlatform.transport). Run leaves sequentially AND globally
    // sequentially so no other scenario, and no other suite sharing the transport, runs concurrently and starves or
    // interferes with the churn/cancellation load on that shared driver.
    override def config = super.config.sequential.globallySequential(true)

    /** Fire-and-forget echo: each accepted connection copies every inbound chunk back out until the peer closes. */
    private def echo(conn: Connection): Unit =
        discard(serve(conn)(echoLoop(conn)))

    private def echoLoop(conn: Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        Loop.foreach {
            conn.inbound.safe.take.map(chunk => conn.outbound.safe.put(chunk).andThen(Loop.continue))
        }

    /** Fire-and-forget drain: read and discard every inbound chunk until the peer closes. */
    private def drain(conn: Connection): Unit =
        discard(serve(conn)(drainLoop(conn)))

    private def drainLoop(conn: Connection)(using Frame): Unit < (Async & Abort[Closed]) =
        Loop.foreach {
            conn.inbound.safe.take.andThen(Loop.continue)
        }

    /** Connects to `port`, runs `use` on the connection, and closes it however `use` ends, interruption included. */
    private def withConnection[A](transport: Transport, port: Int)(use: Connection => A < (Async & Abort[NetException | Closed]))(using
        Frame
    ): A < (Async & Abort[NetException | Closed]) =
        transport.connect("127.0.0.1", port).safe.get.map(conn => Sync.ensure(Sync.defer(conn.close()))(use(conn)))

    /** Runs `body` on an accepted connection in its own fiber and closes the connection however `body` ends. The fiber is unscoped, so it
      * outlives an interrupted leaf; it ends once its peer closes.
      */
    private def serve(conn: Connection)(body: Unit < (Async & Abort[Closed]))(using Frame): Fiber[Unit, Any] =
        Sync.Unsafe.evalOrThrow {
            Fiber.initUnscoped(Sync.ensure(Sync.defer(conn.close()))(Abort.run[Closed](body).unit))
        }

    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else conn.inbound.safe.take.map(chunk => Loop.continue(acc ++ chunk.toArray))
        }

    /** Liveness probe on a co-tenant clean-echo listener: a fresh round-trip on the SAME shared transport must still
      * succeed after the failure load. If the driver wedged, this fails with a `Closed`/`... is closed`.
      */
    private def assertAlive(transport: Transport, port: Int, label: String)(using
        Frame,
        kyo.test.AssertScope
    ): Unit < (Async & Abort[NetException | Closed]) =
        withConnection(transport, port) { conn =>
            val msg = s"liveness-$label".getBytes("UTF-8")
            conn.outbound.safe.put(Span.fromUnsafe(msg)).andThen(collect(conn, msg.length)).map { echoed =>
                assert(
                    echoed.sameElements(msg),
                    s"[$label] driver wedged: post-load liveness echo did not round-trip (driver-closed?)"
                )
            }
        }

    // Every leaf runs on the process-lifetime transport they all share, so a connection an interrupted leaf leaves open (a stalled leaf,
    // a cancelled suite) stays registered on that transport for every leaf after it.
    "a connection whose user is interrupted mid-read is closed, so its server side sees the close" - eachBackendWatched {
        (transport, progress) =>
            val served                             = Promise.Unsafe.init[(Connection, Fiber[Unit, Any]), Any]()
            val reading                            = Promise.Unsafe.init[Unit, Any]()
            def serveDrain(conn: Connection): Unit =
                served.completeDiscard(Result.succeed((conn, serve(conn)(drainLoop(conn)))))
            for
                listener <- transport.listen("127.0.0.1", 0, 16)(serveDrain).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                client   <- Fiber.initUnscoped(Abort.run[NetException | Closed](withConnection(transport, listener.port) { conn =>
                    Sync.defer(reading.completeDiscard(Result.succeed(()))).andThen(conn.inbound.safe.take)
                }))
                _      <- reading.safe.get
                _      <- progress.tick
                server <- served.safe.get
                _      <- progress.tick
                _      <- client.interrupt
                // The drain ends only on the client's close, so this waits for exactly that, with no clock.
                _ <- server._2.getResult
            yield assert(!server._1.isOpen, "the server side must close once the interrupted client's connection is closed")
            end for
    }

    // ---- mass simultaneous in-flight invalidation (server mass-close) ----------------------------------------------

    "mass in-flight invalidation under sustained concurrent reads (server restart) does not wedge the driver" - eachBackendWatched {
        (transport, progress) =>
            // Many long-lived connections with reads in flight while the server goes away all at once, RST-ing every
            // in-flight read simultaneously: modeled by closing EVERY currently-connected server side in one sweep,
            // repeatedly, while sustained concurrent clients round-trip and reconnect.
            val serverConns                             = java.util.concurrent.ConcurrentHashMap.newKeySet[Connection]()
            val stop                                    = new JAtomicBoolean(false)
            val clients                                 = 48
            val iters                                   = 40
            val perConn                                 = 6
            val msg                                     = "ping".getBytes("UTF-8")
            def registeringEcho(conn: Connection): Unit =
                discard(serverConns.add(conn))
                discard(serve(conn)(Sync.ensure(Sync.defer(discard(serverConns.remove(conn))))(echoLoop(conn))))
            end registeringEcho
            for
                cleanListener <- transport.listen("127.0.0.1", 0, 64)(echo).safe.get
                _             <- Scope.ensure(Sync.defer(cleanListener.close()))
                churnListener <- transport.listen("127.0.0.1", 0, 256)(registeringEcho).safe.get
                _             <- Scope.ensure(Sync.defer(churnListener.close()))
                churn = Loop(0) { _ =>
                    if stop.get() then Loop.done(())
                    else
                        Sync.defer {
                            val it = serverConns.iterator()
                            while it.hasNext do
                                val c = it.next()
                                discard(serverConns.remove(c))
                                try c.close()
                                catch case _: Throwable => ()
                            end while
                        }.andThen(Async.sleep(2.millis)).andThen(Loop.continue(0))
                }
                load = Async.foreach(0 until clients, clients) { _ =>
                    Loop(0) { i =>
                        if i >= iters then Loop.done(())
                        else
                            Abort.run[NetException | Closed] {
                                withConnection(transport, churnListener.port) { conn =>
                                    Loop(0) { j =>
                                        if j >= perConn then Loop.done(())
                                        else
                                            conn.outbound.safe.put(Span.fromUnsafe(msg))
                                                .andThen(conn.inbound.safe.take)
                                                .andThen(progress.tick)
                                                .andThen(Loop.continue(j + 1))
                                    }
                                }
                            }.andThen(progress.tick).andThen(Loop.continue(i + 1))
                    }
                }.andThen(Sync.defer(discard(stop.set(true))))
                _ <- Async.zip(load, churn)
                _ <- assertAlive(transport, cleanListener.port, "mass-invalidation")
            yield
                cleanListener.close()
                churnListener.close()
                succeed
            end for
    }

    // ---- connect-refused storm -------------------------------------------------------------------------------------

    "connect-refused storm: concurrent connects to a dead port do not wedge the driver" - eachBackendWatched { (transport, progress) =>
        for
            cleanListener <- transport.listen("127.0.0.1", 0, 64)(echo).safe.get
            _             <- Scope.ensure(Sync.defer(cleanListener.close()))
            // A listener opened then immediately closed: connects to its port race a refusal.
            deadPort <- transport.listen("127.0.0.1", 0, 8)(echo).safe.get.map { l =>
                val p = l.port; l.close(); p
            }
            _ <- Async.foreach(0 until 400, 64) { _ =>
                Abort.run[NetException | Closed] {
                    withConnection(transport, deadPort)(_ => ())
                }.andThen(progress.tick)
            }
            _ <- assertAlive(transport, cleanListener.port, "connect-refused")
        yield
            cleanListener.close()
            succeed
    }

    // ---- abrupt local close during an in-flight read ---------------------------------------------------------------

    "abrupt local close during an in-flight read does not wedge the driver" - eachBackendWatched { (transport, progress) =>
        // The client arms a read (no data will come: the handler never echoes) and closes the connection out from under
        // that armed read, racing the driver's read dispatch. Repeated under concurrency.
        for
            cleanListener  <- transport.listen("127.0.0.1", 0, 64)(echo).safe.get
            _              <- Scope.ensure(Sync.defer(cleanListener.close()))
            silentListener <- transport.listen("127.0.0.1", 0, 128)(drain).safe.get
            _              <- Scope.ensure(Sync.defer(silentListener.close()))
            _              <- Async.foreach(0 until 300, 48) { _ =>
                Abort.run[NetException | Closed] {
                    withConnection(transport, silentListener.port) { conn =>
                        // Race a close against an armed read: start the read, close concurrently.
                        Async.zip(Abort.run(conn.inbound.safe.take).unit, Sync.defer(conn.close())).unit
                    }
                }.andThen(progress.tick)
            }
            _ <- assertAlive(transport, cleanListener.port, "abrupt-close")
        yield
            cleanListener.close()
            succeed
        end for
    }

    // ---- listener open/close churn racing connects -----------------------------------------------------------------

    "listener open/close churn racing connects does not wedge the driver" - eachBackendWatched { (transport, progress) =>
        for
            cleanListener <- transport.listen("127.0.0.1", 0, 64)(echo).safe.get
            _             <- Scope.ensure(Sync.defer(cleanListener.close()))
            _             <- Loop(0) { round =>
                if round >= 40 then Loop.done(())
                else
                    transport.listen("127.0.0.1", 0, 128)(echo).safe.get.map { churnListener =>
                        val port     = churnListener.port
                        val connects = Async.foreach(0 until 16, 16) { _ =>
                            Abort.run[NetException | Closed] {
                                withConnection(transport, port) { conn =>
                                    conn.outbound.safe.put(Span.fromUnsafe("x".getBytes("UTF-8")))
                                }
                            }.andThen(progress.tick)
                        }.unit
                        Async.zip(connects, Sync.defer(churnListener.close())).andThen(progress.tick).andThen(Loop.continue(round + 1))
                    }
            }
            _ <- assertAlive(transport, cleanListener.port, "listener-churn")
        yield
            cleanListener.close()
            succeed
    }

    // ---- mixed healthy + failing under concurrency: failures stay isolated ------------------------------------------

    "a mix of healthy and abruptly-closed connections keeps healthy ones round-tripping (isolation)" - eachBackendWatched {
        (transport, progress) =>
            val seq = new java.util.concurrent.atomic.AtomicInteger(0)
            // Half the accepted connections are abruptly closed by the server before echoing; the other half echo.
            def mixed(conn: Connection): Unit =
                discard(serve(conn) {
                    conn.inbound.safe.take.map { chunk =>
                        if seq.getAndIncrement() % 2 == 0 then Sync.defer(conn.close())
                        else conn.outbound.safe.put(chunk)
                    }.unit
                })
            val msg = "iso".getBytes("UTF-8")
            for
                cleanListener <- transport.listen("127.0.0.1", 0, 64)(echo).safe.get
                _             <- Scope.ensure(Sync.defer(cleanListener.close()))
                mixedListener <- transport.listen("127.0.0.1", 0, 128)(mixed).safe.get
                _             <- Scope.ensure(Sync.defer(mixedListener.close()))
                _             <- Async.foreach(0 until 200, 40) { _ =>
                    Abort.run[NetException | Closed] {
                        withConnection(transport, mixedListener.port) { conn =>
                            conn.outbound.safe.put(Span.fromUnsafe(msg))
                                .andThen(Abort.run(conn.inbound.safe.take))
                                .unit
                        }
                    }.andThen(progress.tick)
                }
                _ <- assertAlive(transport, cleanListener.port, "isolation")
            yield
                cleanListener.close()
                succeed
            end for
    }

    // ---- cancellation: interrupting an in-flight operation must stay contained to that operation --------------------

    "interrupting in-flight reads at concurrency does not wedge the driver" - eachBackendWatched { (transport, progress) =>
        // A silent server never echoes, so each client read parks. Interrupting the parked read must fail only that one
        // read (Interrupted/Closed) and never escape the poll loop to close the driver. Repeated under concurrency.
        for
            cleanListener  <- transport.listen("127.0.0.1", 0, 64)(echo).safe.get
            _              <- Scope.ensure(Sync.defer(cleanListener.close()))
            silentListener <- transport.listen("127.0.0.1", 0, 128)(drain).safe.get
            _              <- Scope.ensure(Sync.defer(silentListener.close()))
            _              <- Async.foreach(0 until 300, 48) { _ =>
                Abort.run[NetException | Closed] {
                    withConnection(transport, silentListener.port) { conn =>
                        Fiber.initUnscoped(Abort.run[Closed](conn.inbound.safe.take).unit).map(_.interrupt.unit)
                    }
                }.andThen(progress.tick)
            }
            _ <- assertAlive(transport, cleanListener.port, "interrupt-read")
        yield
            cleanListener.close()
            silentListener.close()
            succeed
        end for
    }

    "a firing read timeout (Async.timeout) against a silent server does not wedge the driver" - eachBackendWatched {
        (transport, progress) =>
            // An Async.timeout that EXPIRES on an in-flight read (the server never responds) and interrupts it. The timeout's
            // interrupt runs the connection teardown; it must stay contained to that connection.
            for
                cleanListener  <- transport.listen("127.0.0.1", 0, 64)(echo).safe.get
                _              <- Scope.ensure(Sync.defer(cleanListener.close()))
                silentListener <- transport.listen("127.0.0.1", 0, 128)(drain).safe.get
                _              <- Scope.ensure(Sync.defer(silentListener.close()))
                _              <- Async.foreach(0 until 200, 40) { _ =>
                    Abort.run[NetException | Closed] {
                        withConnection(transport, silentListener.port) { conn =>
                            Abort.run[Closed | Timeout](Async.timeout(20.millis)(conn.inbound.safe.take)).unit
                        }
                    }.andThen(progress.tick)
                }
                _ <- assertAlive(transport, cleanListener.port, "read-timeout")
            yield
                cleanListener.close()
                silentListener.close()
                succeed
            end for
    }

    "cancellation of in-flight reads during server-restart churn does not wedge the driver" - eachBackendWatched {
        (transport, progress) =>
            // In-flight reads are CANCELLED (interrupted) at the same time the server mass-closes their connections
            // (RST), so the caller-carrier connection teardown races the poll carrier dispatching the peer RST/FIN on
            // that same fd. A driver whose per-connection dispatch is not total, or whose cancel is not confined to the
            // poll carrier, lets a non-cancellation exception escape and closes the whole driver. Must stay contained on
            // every backend.
            val serverConns = java.util.concurrent.ConcurrentHashMap.newKeySet[Connection]()
            val stop        = new JAtomicBoolean(false)
            // Register the accepted connection for the churn to RST, and drain (read+discard) without echoing: the client
            // read below stays genuinely ARMED (the server never sends) until the churn closes this side, so the timeout's
            // interrupt races the peer-FIN/RST dispatch on an fd with an in-flight read. An echo would complete the read
            // first and erase the race. The drain loop closes this side on peer-close so no server-side fd leaks.
            def registeringDrain(conn: Connection): Unit =
                discard(serverConns.add(conn))
                discard(serve(conn)(Sync.ensure(Sync.defer(discard(serverConns.remove(conn))))(drainLoop(conn))))
            end registeringDrain
            for
                cleanListener <- transport.listen("127.0.0.1", 0, 64)(echo).safe.get
                _             <- Scope.ensure(Sync.defer(cleanListener.close()))
                churnListener <- transport.listen("127.0.0.1", 0, 256)(registeringDrain).safe.get
                _             <- Scope.ensure(Sync.defer(churnListener.close()))
                churn = Loop(0) { _ =>
                    if stop.get() then Loop.done(())
                    else
                        Sync.defer {
                            val it = serverConns.iterator()
                            while it.hasNext do
                                val c = it.next()
                                discard(serverConns.remove(c))
                                try c.close()
                                catch case _: Throwable => ()
                            end while
                        }.andThen(Async.sleep(1.milli)).andThen(Loop.continue(0))
                }
                load = Async.foreach(0 until 64, 64) { _ =>
                    Loop(0) { i =>
                        if i >= 120 then Loop.done(())
                        else
                            Abort.run[NetException | Closed] {
                                withConnection(transport, churnListener.port) { conn =>
                                    // Arm a read the drain server never answers, bounded by a short timeout that fires
                                    // while it is still parked.
                                    Abort.run[Closed | Timeout](Async.timeout(8.millis)(conn.inbound.safe.take)).unit
                                }
                            }.andThen(progress.tick).andThen(Loop.continue(i + 1))
                    }
                }.andThen(Sync.defer(discard(stop.set(true))))
                _ <- Async.zip(load, churn)
                _ <- assertAlive(transport, cleanListener.port, "cancel-during-churn")
            yield
                cleanListener.close()
                churnListener.close()
                succeed
            end for
    }

end TransportResilienceTest
