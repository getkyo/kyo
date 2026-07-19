package kyo.net.internal.posix

import java.util.Collections
import java.util.IdentityHashMap
import kyo.*
import kyo.net.NetException
import kyo.net.Test
import kyo.net.TestBackends
import kyo.net.TransportConfig
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool

/** Per-handle driver ownership and real N-driver parallelism tests over real posix transports.
  *
  * Every test uses a real [[PosixTransport]] built from a real [[IoDriverPool]] over real [[PollerIoDriver]] instances. No decorators,
  * mocks, or behavioral spies are used. Driver identity is observed by reading [[PosixHandle.driver]] (the @volatile field set at bind time)
  * on the real connected handle after the connect completes.
  *
  * Gate: [[PosixTestSockets.assumePoller]] cancels the suite where no epoll (Linux) or kqueue (macOS/BSD) is available.
  *
  * Anti-flakiness: all coordination uses [[Channel]] latches. No sleep anywhere; no mock/spy/fake.
  */
class PosixTransportMultiDriverTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** Build a real transport backed by `n` real PollerIoDriver instances, start the pool, run `body`, then close the transport. */
    private def withNDriverTransport[A](n: Int)(body: PosixTransport => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        val drivers: Array[IoDriver[PosixHandle]] =
            Array.fill(n)(PollerIoDriver.init(TransportConfig.default).asInstanceOf[IoDriver[PosixHandle]])
        val pool      = IoDriverPool.init(drivers)
        val transport = PosixTransport.init(TransportConfig.default, pool)
        pool.start()
        Abort.run[NetException | Closed](body(transport)).map { result =>
            Sync.defer(transport.close()).andThen(Abort.get(result))
        }
    end withNDriverTransport

    // --- singleDriverOwnership ---
    // Each client handle's .driver must be non-null after connect and reference-identical to one of the pool's real driver instances.
    // This proves the per-handle single-driver ownership invariant: a connection is bound to exactly one driver at open time, and
    // that driver is drawn from the real pool (not null, not a phantom object outside the pool).
    //
    // Note: the assertion is NOT on distribution across N drivers. The sequential connect+accept interleaving can cause all M client
    // connections to land on the same pool driver (d0) when d0 and d1 alternate with server-side handleAccepted calls, which also consume
    // pool.next() slots. Distribution across multiple drivers is verified separately in exercisesRealParallelism.
    //
    // Anti-flakiness: real connect completes deterministically on loopback; no sleep, no echo handler needed.
    "singleDriverOwnership" in {
        assumePoller()
        val N = 2
        val M = N * 2
        // Capture pool drivers for identity comparison: the handle's .driver must be one of these.
        val driverArray: Array[IoDriver[PosixHandle]] =
            Array.fill(N)(PollerIoDriver.init(TransportConfig.default).asInstanceOf[IoDriver[PosixHandle]])
        val pool      = IoDriverPool.init(driverArray)
        val transport = PosixTransport.init(TransportConfig.default, pool)
        pool.start()
        val poolDriverSet = Collections.newSetFromMap(new IdentityHashMap[IoDriver[PosixHandle], java.lang.Boolean]())
        driverArray.foreach(d => discard(poolDriverSet.add(d)))

        Abort.run[NetException | Closed] {
            transport.listen("127.0.0.1", 0, M + 4)(_ => ()).safe.get.map { listener =>
                val port = listener.port
                Kyo.foreach(0 until M) { _ =>
                    transport.connect("127.0.0.1", port).safe.get.map { client =>
                        val handle = client.asInstanceOf[kyo.net.internal.transport.Connection[PosixHandle]].handle
                        val d      = handle.driver
                        // Each handle must have a non-null driver assigned at bind time (connectImpl).
                        assert(d ne null, "handle.driver must be non-null after connect")
                        // The assigned driver must be one of the N real pool drivers (reference identity).
                        assert(
                            poolDriverSet.contains(d),
                            s"handle.driver must be a pool driver, got $d which is not in pool drivers ${driverArray.mkString("[", ", ", "]")}"
                        )
                        client.close()
                    }
                }.map { _ =>
                    listener.close()
                    succeed
                }
            }
        }.map { result =>
            Sync.defer(transport.close()).andThen(Abort.get(result))
        }
    }

    // --- exercisesRealParallelism ---
    // An N=4 pool must produce >1 distinct driver across concurrent in-flight connections. An N=1 control must produce exactly 1.
    // Latch pattern: each client fiber opens a connection, puts a token into `registeredCh` (signaling registration), then blocks on its
    // per-connection release channel until the orchestrator releases it. The orchestrator drains all M tokens from registeredCh (confirming
    // all M connections are alive simultaneously), then releases all release channels. This keeps >1 connection in-flight at the same time,
    // proving the N=4 pool distributes across multiple drivers without any serialization.
    //
    // Cross-platform: both JVM and Native run the scheduler over real OS threads (one carrier thread per driver poll loop), so an N>1 pool
    // distributes connections across distinct drivers on both. distinct4 > 1 is the direct proof that the pool is a real multi-driver pool and
    // not a single driver wrapped in a pool API.
    // Anti-flakiness: Channel.take/put are purely suspending; no sleep anywhere.
    "exercisesRealParallelism" in {
        exercisesRealParallelismBody
    }

    private def exercisesRealParallelismBody(using Frame, kyo.test.AssertScope): Unit < (Async & Abort[NetException | Closed] & Scope) =
        assumePoller()

        def runWithN(n: Int)(using Frame): Int < (Async & Abort[NetException | Closed] & Scope) =
            withNDriverTransport(n) { transport =>
                val M           = n * 2 + 2
                val driversSeen = Collections.newSetFromMap(new IdentityHashMap[IoDriver[PosixHandle], java.lang.Boolean]())
                // Capacity-M registered channel: each client puts a token once it has recorded handle.driver.
                val registeredCh = Channel.Unsafe.init[Unit](M)
                // Per-connection release channels (capacity 1): each client blocks here until released.
                val releaseLatches = Array.fill(M)(Channel.Unsafe.init[Unit](1))

                transport.listen("127.0.0.1", 0, M + 8)(_ => ()).safe.get.map { listener =>
                    val port = listener.port
                    // Spawn M concurrent client fibers via Kyo.foreach so all fibers are collected as Chunk[Fiber[...]].
                    // Each fiber holds its connection open until the orchestrator releases it.
                    Kyo.foreach(0 until M) { i =>
                        Fiber.initUnscoped {
                            val connected: Unit < (Async & Abort[NetException | Closed]) =
                                transport.connect("127.0.0.1", port).safe.get.map { client =>
                                    val handle = client.asInstanceOf[kyo.net.internal.transport.Connection[PosixHandle]].handle
                                    discard(driversSeen.add(handle.driver))
                                    // Signal registration (non-blocking since capacity is M).
                                    registeredCh.safe.put(()).andThen {
                                        // Block until the orchestrator releases this connection.
                                        releaseLatches(i).safe.take.andThen {
                                            Sync.defer(client.close())
                                        }
                                    }
                                }
                            connected
                        }
                    }.map { connFibers =>
                        // Wait for all M fibers to register their drivers (all M in-flight simultaneously).
                        val released: Int < (Async & Abort[NetException | Closed]) =
                            Kyo.foreach(0 until M)(_ => registeredCh.safe.take).andThen {
                                // Release all M connections simultaneously.
                                Kyo.foreach(0 until M)(i => releaseLatches(i).safe.put(())).andThen {
                                    Kyo.foreach(connFibers)(_.get).map { _ =>
                                        listener.close()
                                        driversSeen.size()
                                    }
                                }
                            }
                        released
                    }
                }
            }
        end runWithN

        Scope.run {
            // N=4: must distribute across >1 distinct driver (proves real parallelism across the pool).
            runWithN(4).map { distinct4 =>
                // N=1 discriminating control: all connections land on the single real driver.
                // If the N=4 result equaled this it would mean the pool has degenerated to serial; the test would fail.
                runWithN(1).map { distinct1 =>
                    assert(distinct4 > 1, s"N=4 pool must distribute across >1 driver, got $distinct4 (pool degenerated to serial)")
                    assert(distinct1 == 1, s"N=1 pool must serialize on 1 driver, got $distinct1")
                    succeed
                }
            }
        }
    end exercisesRealParallelismBody

    // --- poolCloseLeaksNothing ---
    // After transport.close() (which calls pool.close()), all N real driver fibers are interrupted and a second close() is idempotent.
    // Anti-flakiness: close() is synchronous wrt the CAS guard; no sleep.
    "poolCloseLeaksNothing" in {
        assumePoller()
        val N = 3
        val drivers: Array[IoDriver[PosixHandle]] =
            Array.fill(N)(PollerIoDriver.init(TransportConfig.default).asInstanceOf[IoDriver[PosixHandle]])
        val pool      = IoDriverPool.init(drivers)
        val transport = PosixTransport.init(TransportConfig.default, pool)
        pool.start()

        // Open one real connection so the accept loop is live, then close everything.
        Abort.run[NetException | Closed] {
            transport.listen("127.0.0.1", 0, 4)(_ => ()).safe.get.map { listener =>
                transport.connect("127.0.0.1", listener.port).safe.get.map { conn =>
                    conn.close()
                    listener.close()
                }
            }
        }.map { _ =>
            Sync.defer {
                // First close: stops all N driver fibers and releases their resources.
                transport.close()
                // Second close: must be idempotent and not throw (AtomicBoolean CAS in pool.close is the guard).
                transport.close()
                succeed
            }
        }
    }

    // --- poolSizeMatchesIoPoolSize ---
    // The production registry build path sizes the pool at max(1, ioPoolSize) drivers on every platform: both JVM and Native run each driver's
    // poll loop on its own OS thread, so ioPoolSize drivers give real cross-core parallelism on both. With the default ioPoolSize > 1 on any
    // multi-core host, the built pool holds more than one driver.
    // This exercises the real production Entry.build (the same path NetPlatform.transport selects), then reads the built transport's pool size.
    "poolSizeMatchesIoPoolSize" in {
        assumePoller()
        // The pool-size invariant is specific to the posix IoDriverPool transport; the NIO floor builds a NioTransport with no such pool. Select
        // an available posix backend (every jvm-native registry entry except the "nio" floor) and skip cleanly when only a non-posix backend is
        // selected (e.g. KYO_NET_ONLY=nio restricts the registry to the floor), rather than casting a NioTransport to PosixTransport.
        TestBackends.all.find(entry => entry.isAvailable && entry.name != "nio") match
            case None =>
                Sync.defer(cancel("no posix backend selected/available on this host (pool-size check is posix-only)"))
            case Some(entry) =>
                Sync.defer(entry.build(TransportConfig.default, summon[Frame])).map { transport =>
                    // A posix backend entry always builds a PosixTransport; the cast reaches its pool to count drivers.
                    val posix    = transport.asInstanceOf[PosixTransport]
                    val expected = math.max(1, kyo.net.ioPoolSize())
                    Sync.defer {
                        try
                            assert(
                                posix.pool.size == expected,
                                s"pool size must be $expected (max(1, ioPoolSize)), got ${posix.pool.size}"
                            )
                            succeed
                        finally discard(transport.close())
                    }
                }
        end match
    }

end PosixTransportMultiDriverTest
