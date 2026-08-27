package kyo.stats.machine

import kyo.*

class MachineSamplerTest extends kyo.test.Test[Any]:

    // The retained-Decode-identity and large-file leaves share no state with the fiber-driven leaves
    // below, but every leaf still runs sequentially: the fiber-driven leaves construct their own
    // MachineHandles.init (the process-global "machine" scope), and a parallel run would race one
    // leaf's virtual-clock advance against another leaf's tick assertions.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    /** A `System.Unsafe` whose env and property maps are staged, so the sample-interval lever is read the way
      * the sampler reads it at start: a real environment variable cannot be set inside a running process.
      */
    private def staged(envs: Map[String, String], props: Map[String, String] = Map.empty): System.Unsafe =
        new System.Unsafe:
            def env(name: String)(using AllowUnsafe): Maybe[String]      = Maybe.fromOption(envs.get(name))
            def property(name: String)(using AllowUnsafe): Maybe[String] = Maybe.fromOption(props.get(name))
            def lineSeparator()(using AllowUnsafe): String               = "\n"
            def userName()(using AllowUnsafe): String                    = "test"
            def operatingSystem()(using AllowUnsafe): System.OS          = System.OS.Unknown
            def architecture()(using AllowUnsafe): System.Arch           = System.Arch.Unknown
            def availableProcessors()(using AllowUnsafe): Int            = 1

    "sample interval" - {

        "defaults to one second when nothing overrides it" in {
            assert(MachineSampler.intervalFrom(staged(Map.empty)) == 1.second)
            assert(MachineSampler.defaultInterval == 1.second)
        }

        "the environment variable sets the cadence in milliseconds" in {
            assert(MachineSampler.intervalFrom(staged(Map("KYO_MACHINE_INTERVAL_MS" -> "100"))) == 100.millis)
            assert(MachineSampler.intervalFrom(staged(Map("KYO_MACHINE_INTERVAL_MS" -> " 5000 "))) == 5.seconds)
        }

        "the system property sets the cadence when the environment variable is unset" in {
            assert(MachineSampler.intervalFrom(staged(Map.empty, Map("kyo.machine.intervalMs" -> "250"))) == 250.millis)
        }

        "the environment variable wins over the system property" in {
            val both = staged(Map("KYO_MACHINE_INTERVAL_MS" -> "100"), Map("kyo.machine.intervalMs" -> "250"))
            assert(MachineSampler.intervalFrom(both) == 100.millis)
        }

        "an unparseable or non-positive value falls back to the default rather than stopping the sampler" in {
            assert(MachineSampler.intervalFrom(staged(Map("KYO_MACHINE_INTERVAL_MS" -> "fast"))) == 1.second)
            assert(MachineSampler.intervalFrom(staged(Map("KYO_MACHINE_INTERVAL_MS" -> "0"))) == 1.second)
            assert(MachineSampler.intervalFrom(staged(Map("KYO_MACHINE_INTERVAL_MS" -> "-100"))) == 1.second)
            assert(MachineSampler.intervalFrom(staged(Map("KYO_MACHINE_INTERVAL_MS" -> ""))) == 1.second)
        }
    }

    "readInto" - {

        "passes the SAME retained Decode instance every tick (one Decode per proc file, identity stable)" in {
            val identities = collection.mutable.ArrayBuffer.empty[Int]
            val decode = new MachineSampler.Decode:
                def apply(bytes: Span[Byte], len: Int)(using AllowUnsafe): Unit =
                    discard(identities += java.lang.System.identityHashCode(this))
            Scope.run {
                for
                    handles <- MachineHandles.init
                    dir     <- Path.tempDir("kyo-stats-machine-sampler-identity")
                    file = dir / "small.txt"
                    _ <- file.write("hello")
                    sampler = new MachineSampler(handles)
                    slot    = sampler.openSlot(file)
                    first   = sampler.readInto(slot, decode)
                    second  = sampler.readInto(slot, decode)
                    _ <- dir.removeAll
                yield
                    assert(first)
                    assert(second)
                    assert(identities.size == 2)
                    assert(identities(0) == identities(1))
                end for
            }
        }

        "binds fill length before taking the span so a file larger than the initial 8192 buffer decodes in full" in {
            var decodedLen  = 0
            var decodedText = ""
            val decode = new MachineSampler.Decode:
                def apply(bytes: Span[Byte], len: Int)(using AllowUnsafe): Unit =
                    decodedLen = len
                    decodedText = new String(bytes.toArrayUnsafe, 0, len, java.nio.charset.StandardCharsets.US_ASCII)
            val content = "0123456789" * 2000 // 20000 bytes, larger than the sampler's 8192-byte initial slot
            Scope.run {
                for
                    handles <- MachineHandles.init
                    dir     <- Path.tempDir("kyo-stats-machine-sampler-large")
                    file = dir / "large.txt"
                    _ <- file.write(content)
                    sampler = new MachineSampler(handles)
                    slot    = sampler.openSlot(file)
                    ok      = sampler.readInto(slot, decode)
                    _ <- dir.removeAll
                yield
                    assert(ok)
                    assert(decodedLen == 20000)
                    assert(decodedText == content)
                end for
            }
        }
    }

    "disk in-flight guard" - {

        "admits exactly one disk read and refuses a second while it is outstanding" in {
            for handles <- MachineHandles.init
            yield
                val sampler                    = new MachineSampler(handles)
                val first                      = sampler.diskReadBegin()
                val second                     = sampler.diskReadBegin()
                val inFlightWhileSecondRefused = sampler.diskReadInFlight()
                sampler.diskReadDone()
                val third = sampler.diskReadBegin()
                assert(first)
                assert(!second)
                assert(inFlightWhileSecondRefused)
                assert(third)
            end for
        }
    }

    "detached-fiber tick loop" - {

        "teardown interrupts BOTH the fast and disk fibers before the close-buffers finalizer runs, and no read observes a closed handle" in {
            val markers = AtomicRef.Unsafe.init(Chunk.empty[String])
            val closed  = Latch.Unsafe.init(1) // close() releases it; the test fences on it, no settle
            val machine = new Machine:
                def read()(using AllowUnsafe): Unit      = discard(markers.updateAndGet(_.append("read")))
                def readDisks()(using AllowUnsafe): Unit = discard(markers.updateAndGet(_.append("readDisks")))
                def close()(using AllowUnsafe): Unit =
                    discard(markers.updateAndGet(_.append("close")))
                    closed.release()
            Clock.withTimeControl { tc =>
                for
                    handles <- MachineHandles.init
                    clock   <- Clock.get
                    fiber   <- Fiber.initUnscoped(Clock.let(clock)(Scope.run(MachineSampler.runWith(handles, _ => machine))))
                    // Wait for both schedules (fast + disk) to arm before advancing.
                    _           <- tc.awaitPendingSleepers(2)
                    _           <- tc.advance(1.seconds)
                    interrupted <- fiber.interrupt
                    _           <- Abort.run(fiber.get)
                    // fiber.get resolves on the interrupted result BEFORE the close-buffers finalizer runs,
                    // so fence on the latch close() releases rather than on a wall-clock settle.
                    _    <- closed.safe.await
                    done <- fiber.done
                    snapshot = markers.get()
                yield
                    assert(interrupted)
                    assert(done)
                    assert(snapshot.count(_ == "close") == 1)
                    assert(snapshot.lastOption.contains("close"))
                end for
            }
        }

        "a never-completing readDisks leaves the fast cells advancing at the anchored 1 Hz".notJs in {
            val tickCount    = AtomicLong.Unsafe.init(0L)
            val readCount    = AtomicLong.Unsafe.init(0L)
            val innerSampler = AtomicRef.Unsafe.init(Maybe.empty[MachineSampler])
            val released     = AtomicBoolean.Unsafe.init(false)
            val parkedThread = AtomicRef.Unsafe.init(Maybe.empty[Thread])
            val parkedLatch  = Latch.Unsafe.init(1) // readDisks releases it as it parks; the test awaits it, no settle
            Clock.withTimeControl { tc =>
                for
                    handles <- MachineHandles.init
                    clock   <- Clock.get
                    machine = new Machine:
                        def read()(using AllowUnsafe): Unit =
                            discard(tickCount.getAndSet(tickCount.get() + 1L))
                        def readDisks()(using AllowUnsafe): Unit =
                            // Models a genuinely-blocking statvfs/statfs/GetDiskFreeSpaceEx against a dead mount:
                            // a native downcall with no suspension point that ignores JVM thread interrupts. The
                            // sampler runs this read on its DEDICATED disk thread, off the Async scheduler pool,
                            // so parking here parks THAT thread, never a scheduler worker; the fast fiber and the
                            // teardown keep running on the scheduler regardless, needing no blocking compensation.
                            // Park uninterruptibly (clearing, never propagating, any interrupt, e.g. the disk
                            // executor's shutdownNow at teardown) until the test releases it below, so the read
                            // stays the ONE outstanding read. It parks a real OS thread, so this hazard, and
                            // therefore this leaf, is JVM/Native-only (JS has no thread to off-load to): `.notJs`.
                            discard(readCount.getAndSet(readCount.get() + 1L))
                            parkedThread.set(Present(Thread.currentThread()))
                            parkedLatch.release()
                            @scala.annotation.tailrec
                            def parkUntilReleased(): Unit =
                                if released.get() then ()
                                else
                                    java.util.concurrent.locks.LockSupport.park()
                                    discard(Thread.interrupted())
                                    parkUntilReleased()
                            parkUntilReleased()
                        end readDisks
                        def close()(using AllowUnsafe): Unit = ()
                    buildMachine = (s: MachineSampler) =>
                        innerSampler.set(Present(s))
                        machine
                    fiber <- Fiber.initUnscoped(Clock.let(clock)(Scope.run(MachineSampler.runWith(handles, buildMachine))))
                    // Wait for both schedules to arm, then for the disk read to reach its thread and park.
                    _ <- tc.awaitPendingSleepers(2)
                    _ <- parkedLatch.safe.await
                    // Five fast ticks; the disk stays in-flight and every disk cycle skips.
                    _ <- Loop.repeat(5)(tc.advance(1.seconds).andThen(tc.awaitPendingSleepers(2)))
                    inFlightAfterFive = innerSampler.get() match
                        case Present(s) => s.diskReadInFlight()
                        case Absent     => false
                    readsAfterFive = readCount.get()
                    readThreadName = parkedThread.get().map(_.getName)
                    _ <- Sync.Unsafe.defer {
                        released.set(true)
                        parkedThread.get() match
                            case Present(t) => java.util.concurrent.locks.LockSupport.unpark(t)
                            case Absent     => ()
                    }
                    _ <- fiber.interrupt
                yield
                    assert(tickCount.get() == 5L)
                    assert(inFlightAfterFive)
                    assert(readsAfterFive == 1L)
                    // The read ran OFF the scheduler pool, on the sampler's dedicated disk thread. A regression
                    // that put it back on a scheduler worker (the interrupt/compensation-wedge hazard) fails here
                    // loudly instead of wedging the run, since kyo-test's own per-leaf timeout runs on the same
                    // scheduler and cannot fire once that scheduler is wedged.
                    assert(readThreadName.exists(_.startsWith("kyo-stats-machine-disk")))
                end for
            }
        }

        "N ticks fire at the anchored instants with no accumulated drift" in {
            val recorded = AtomicRef.Unsafe.init(Chunk.empty[Duration])
            Clock.withTimeControl { tc =>
                for
                    handles <- MachineHandles.init
                    clock   <- Clock.get
                    machine = new Machine:
                        def read()(using AllowUnsafe): Unit      = discard(recorded.updateAndGet(_.append(clock.unsafe.nowMonotonic())))
                        def readDisks()(using AllowUnsafe): Unit = ()
                        def close()(using AllowUnsafe): Unit     = ()
                    fiber <- Fiber.initUnscoped(Clock.let(clock)(Scope.run(MachineSampler.runWith(handles, _ => machine))))
                    // One interval per tick, fencing on the fibers re-arming so the anchored fire instants stay exact.
                    _ <- tc.awaitPendingSleepers(2)
                    _ <- Loop.repeat(5)(tc.advance(1.seconds).andThen(tc.awaitPendingSleepers(2)))
                    _ <- fiber.interrupt
                    snapshot = recorded.get()
                yield assert(snapshot == Chunk(1.seconds, 2.seconds, 3.seconds, 4.seconds, 5.seconds))
                end for
            }
        }

        "a configured interval is the cadence the loop actually ticks at" in {
            // The lever has to move the producer, not just parse: everything downstream inherits this
            // cadence, so a consumer polling ten times a second reads the same value ten times unless the
            // sampler itself ticks faster.
            val recorded = AtomicRef.Unsafe.init(Chunk.empty[Duration])
            Clock.withTimeControl { tc =>
                for
                    handles <- MachineHandles.init
                    clock   <- Clock.get
                    machine = new Machine:
                        def read()(using AllowUnsafe): Unit      = discard(recorded.updateAndGet(_.append(clock.unsafe.nowMonotonic())))
                        def readDisks()(using AllowUnsafe): Unit = ()
                        def close()(using AllowUnsafe): Unit     = ()
                    interval = MachineSampler.intervalFrom(staged(Map("KYO_MACHINE_INTERVAL_MS" -> "100")))
                    fiber <- Fiber.initUnscoped(Clock.let(clock)(Scope.run(MachineSampler.runWith(handles, _ => machine, interval))))
                    _     <- tc.advance(Duration.Zero, 100.millis) // let the fiber reach the fast-fiber schedule registration first
                    _     <- tc.advance(100.millis)
                    _     <- tc.advance(100.millis)
                    _     <- tc.advance(100.millis)
                    _     <- tc.advance(100.millis)
                    _     <- tc.advance(100.millis)
                    _     <- fiber.interrupt
                    snapshot = recorded.get()
                yield
                    assert(interval == 100.millis)
                    // Five ticks in half a second, where the default cadence would have produced none.
                    assert(snapshot == Chunk(100.millis, 200.millis, 300.millis, 400.millis, 500.millis))
                end for
            }
        }
    }

end MachineSamplerTest
