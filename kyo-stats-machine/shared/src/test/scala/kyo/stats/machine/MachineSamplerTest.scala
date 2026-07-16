package kyo.stats.machine

import kyo.*

class MachineSamplerTest extends kyo.test.Test[Any]:

    // The retained-Decode-identity and large-file leaves share no state with the fiber-driven leaves
    // below, but every leaf still runs sequentially: the fiber-driven leaves construct their own
    // MachineHandles.init (the process-global "machine" scope), and a parallel run would race one
    // leaf's virtual-clock advance against another leaf's tick assertions.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    "readInto" - {

        "passes the SAME retained Decode instance every tick (one Decode per proc file, identity stable)" in {
            val identities = collection.mutable.ArrayBuffer.empty[Int]
            val decode = new MachineSampler.Decode:
                def apply(bytes: Span[Byte], len: Int)(using AllowUnsafe): Unit =
                    discard(identities += java.lang.System.identityHashCode(this))
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

        "binds fill length before taking the span so a file larger than the initial 8192 buffer decodes in full" in {
            var decodedLen  = 0
            var decodedText = ""
            val decode = new MachineSampler.Decode:
                def apply(bytes: Span[Byte], len: Int)(using AllowUnsafe): Unit =
                    decodedLen = len
                    decodedText = new String(bytes.toArrayUnsafe, 0, len, java.nio.charset.StandardCharsets.US_ASCII)
            val content = "0123456789" * 2000 // 20000 bytes, larger than the sampler's 8192-byte initial slot
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
            val machine = new Machine:
                def read()(using AllowUnsafe): Unit      = discard(markers.updateAndGet(_.append("read")))
                def readDisks()(using AllowUnsafe): Unit = discard(markers.updateAndGet(_.append("readDisks")))
                def close()(using AllowUnsafe): Unit     = discard(markers.updateAndGet(_.append("close")))
            Clock.withTimeControl { tc =>
                for
                    handles <- MachineHandles.init
                    clock   <- Clock.get
                    fiber   <- Fiber.initUnscoped(Clock.let(clock)(Scope.run(MachineSampler.runWith(handles, _ => machine))))
                    // A zero-duration advance with a real wall-clock pause lets the freshly-spawned fiber
                    // actually reach both Clock.repeatAtInterval registrations before virtual time moves, so
                    // neither schedule's anchor point races the fiber's own async startup.
                    _           <- tc.advance(Duration.Zero, 100.millis)
                    _           <- tc.advance(1.seconds)
                    interrupted <- fiber.interrupt
                    // fiber.get suspends until the fiber's promise genuinely resolves (including every
                    // registered Scope finalizer, which Scope.run awaits before its own computation
                    // completes), a stronger synchronization point than a bare done-flag poll.
                    _    <- Abort.run(fiber.get)
                    done <- fiber.done
                    _    <- tc.advance(Duration.Zero, 500.millis)
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
            val innerSampler = AtomicRef.Unsafe.init(Maybe.empty[MachineSampler])
            Clock.withTimeControl { tc =>
                for
                    handles <- MachineHandles.init
                    clock   <- Clock.get
                    gate    <- Channel.init[Unit](1)
                    machine = new Machine:
                        def read()(using AllowUnsafe): Unit =
                            discard(tickCount.getAndSet(tickCount.get() + 1L))
                        def readDisks()(using AllowUnsafe): Unit =
                            // Parks a REAL OS worker thread on the channel's take, exactly the shape a genuinely
                            // blocking statvfs/GetDiskFreeSpaceEx syscall has: no kyo suspension point the
                            // Async.timeout race can preempt mid-flight. This hazard, and therefore this leaf,
                            // is JVM/Native-only: JS has no OS thread to park, so `.block` cannot reproduce an
                            // uninterruptible wait there. The gate is fed only at teardown (below), so this call
                            // never returns during the test's own assertions.
                            given Frame = Frame.internal
                            discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(gate.take).flatMap(_.block(Duration.Infinity))))
                        end readDisks
                        def close()(using AllowUnsafe): Unit = ()
                    buildMachine = (s: MachineSampler) =>
                        innerSampler.set(Present(s))
                        machine
                    fiber <- Fiber.initUnscoped(Clock.let(clock)(Scope.run(MachineSampler.runWith(handles, buildMachine))))
                    _     <- tc.advance(Duration.Zero, 100.millis) // let the fiber reach both schedule registrations first
                    _     <- tc.advance(1.seconds)
                    _     <- tc.advance(1.seconds)
                    _     <- tc.advance(1.seconds)
                    _     <- tc.advance(1.seconds)
                    _     <- tc.advance(1.seconds)
                    inFlightAfterFive = innerSampler.get() match
                        case Present(s) => s.diskReadInFlight()
                        case Absent     => false
                    _ <- gate.offerDiscard(())
                    _ <- fiber.interrupt
                yield
                    assert(tickCount.get() == 5L)
                    assert(inFlightAfterFive)
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
                    _     <- tc.advance(Duration.Zero, 100.millis) // let the fiber reach the fast-fiber schedule registration first
                    _     <- tc.advance(1.seconds)
                    _     <- tc.advance(1.seconds)
                    _     <- tc.advance(1.seconds)
                    _     <- tc.advance(1.seconds)
                    _     <- tc.advance(1.seconds)
                    _     <- fiber.interrupt
                    snapshot = recorded.get()
                yield assert(snapshot == Chunk(1.seconds, 2.seconds, 3.seconds, 4.seconds, 5.seconds))
                end for
            }
        }
    }

end MachineSamplerTest
