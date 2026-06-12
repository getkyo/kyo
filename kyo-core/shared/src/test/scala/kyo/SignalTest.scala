package kyo

import kyo.internal.Platform

class SignalTest extends kyo.test.Test[Any]:

    "init" - {
        "initRef" - {
            "ok" in {
                for
                    ref <- Signal.initRef(42)
                    v   <- ref.current
                yield assert(v == 42)
            }
            "missing CanEqual" in {
                typeCheckFailure(
                    "Signal.initRef(Thread.currentThread())"
                )(
                    "Cannot create Signal"
                )
            }
        }

        "initConst" - {
            "ok" in {
                val sig = Signal.initConst(42)
                for
                    v1 <- sig.current
                    v2 <- sig.next
                yield assert(v1 == 42 && v2 == 42)
                end for
            }
            "missing CanEqual" in {
                typeCheckFailure(
                    "Signal.initConst(Thread.currentThread())"
                )(
                    "Cannot create Signal"
                )
            }
        }

        "initRaw" - {
            "ok" in {
                val sig = Signal.initRaw[Int](
                    currentWith = [B, S] => f => f(1),
                    nextWith = [B, S] => f => f(2)
                )
                for
                    v1 <- sig.current
                    v2 <- sig.next
                yield assert(v1 == 1 && v2 == 2)
                end for
            }
            "missing CanEqual" in {
                typeCheckFailure(
                    """
                Signal.initRaw[Thread](
                    currentWith = [B, S] => f => f(Thread.currentThread),
                    nextWith = [B, S] => f => f(Thread.currentThread)
                )
                """
                )(
                    "Cannot create Signal"
                )
            }
        }

        "initRefWith" - {
            "ok" in {
                for
                    v <- Signal.initRefWith(42) { ref =>
                        for
                            _ <- ref.set(43)
                            v <- ref.current
                        yield v
                    }
                yield assert(v == 43)
            }
            "missing CanEqual" in {
                typeCheckFailure(
                    "Signal.initRefWith(Thread.currentThread())(identity)"
                )(
                    "Cannot create Signal"
                )
            }
        }

        "initConstWith" - {
            "ok" in {
                for
                    v <- Signal.initConstWith(42) { sig =>
                        for
                            v1 <- sig.current
                            v2 <- sig.next
                        yield (v1, v2)
                    }
                yield assert(v == (42, 42))
            }
            "missing CanEqual" in {
                typeCheckFailure(
                    "Signal.initConstWith(Thread.currentThread())(identity)"
                )(
                    "Cannot create Signal"
                )
            }
        }

        "initRawWith" - {
            "ok" in {
                for
                    v <- Signal.initRawWith[Int](
                        currentWith = [B, S] => f => f(1),
                        nextWith = [B, S] => f => f(2)
                    ) { sig =>
                        for
                            v1 <- sig.current
                            v2 <- sig.next
                        yield (v1, v2)
                    }
                yield assert(v == (1, 2))
            }
            "missing CanEqual" in {
                typeCheckFailure(
                    """
                Signal.initRawWith[Thread](
                    currentWith = [B, S] => f => f(Thread.currentThread),
                    nextWith = [B, S] => f => f(Thread.currentThread)
                )(identity)
                """
                )(
                    "Cannot create Signal"
                )
            }
        }
    }

    "Signal.Ref" - {
        "get and set" in {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.get
                _   <- ref.set(2)
                v2  <- ref.get
            yield assert(v1 == 1 && v2 == 2)
        }

        "getAndSet" in {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.getAndSet(2)
                v2  <- ref.get
            yield assert(v1 == 1 && v2 == 2)
        }

        "compareAndSet" in {
            for
                ref     <- Signal.initRef(1)
                success <- ref.compareAndSet(1, 2)
                fail    <- ref.compareAndSet(1, 3)
                v       <- ref.get
            yield assert(success && !fail && v == 2)
        }

        "getAndUpdate" in {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.getAndUpdate(_ + 1)
                v2  <- ref.get
            yield assert(v1 == 1 && v2 == 2)
        }

        "updateAndGet" in {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.updateAndGet(_ + 1)
                v2  <- ref.get
            yield assert(v1 == 2 && v2 == 2)
        }

        "use" in {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.use(_ * 2)
                _   <- ref.set(2)
                v2  <- ref.use(_ * 2)
            yield assert(v1 == 2 && v2 == 4)
        }
    }

    "Signal operations" - {
        "current and next" in {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.current
                f   <- Fiber.initUnscoped(ref.next)
                _   <- assertEventually(ref.waiters.map(_ == 1))
                _   <- ref.set(2)
                v2  <- f.get
            yield assert(v1 == 1 && v2 == 2)
        }

        "map" in {
            for
                ref <- Signal.initRef(1)
                mapped = ref.map(_ * 2)
                v1 <- mapped.current
                _  <- ref.set(2)
                v2 <- mapped.current
            yield assert(v1 == 2 && v2 == 4)
        }

        "streamCurrent" in {
            for
                ref <- Signal.initRef(1)
                stream = ref.streamCurrent.take(3)
                values <- stream.run
            yield assert(values == Chunk(1, 1, 1))
        }

        "streamChanges" in {
            for
                ref    <- Signal.initRef(1)
                f      <- Fiber.initUnscoped(ref.streamChanges.take(3).run)
                _      <- Async.sleep(100.millis)
                _      <- ref.set(2)
                _      <- Async.sleep(100.millis)
                _      <- ref.set(2) // Should be ignored
                _      <- Async.sleep(100.millis)
                _      <- ref.set(3)
                _      <- Async.sleep(100.millis)
                values <- f.get
            yield assert(values == Chunk(1, 2, 3))
        }
    }

    "concurrency" - {
        val repeats = 50

        "parallel updates" in {
            (for
                ref <- Signal.initRef(0)
                _   <- Async.fill(10, 10)(ref.updateAndGet(_ + 1))
                v   <- ref.get
            yield assert(v == 10))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .unit
        }

        "concurrent reads and writes" in {
            assume(Runtime.getRuntime.availableProcessors() > 4, "Needs >4 cores for 20 concurrent fibers")
            // Native scheduler has limited preemption — 20 busy-wait fibers
            // contending on CAS need fewer repetitions to avoid starvation timeout
            val effectiveRepeats = if Platform.isNative then 5 else repeats
            {
                (for
                    ref <- Signal.initRef(0)
                    readers <-
                        Fiber.initUnscoped(Async.fill(10, 10)(
                            Loop(0)(_ => ref.currentWith(v => if v < 10 then Loop.continue(v) else Loop.done(v)))
                        ))
                    writers <-
                        Fiber.initUnscoped(Async.fill(10, 10)(
                            Loop.foreach {
                                ref.get.map { v =>
                                    if v < 10 then
                                        ref.compareAndSet(v, v + 1).andThen(Loop.continue)
                                    else
                                        Loop.done(v)
                                    end if
                                }
                            }
                        ))
                    readResults  <- readers.get
                    writeResults <- writers.get
                    finalValue   <- ref.get
                yield assert(readResults.forall(_ == 10) && writeResults.forall(_ == 10) && finalValue == 10))
                    .handle(Choice.run, _.unit, Loop.repeat(effectiveRepeats))
                    .unit
            }
        }

    }

    "switchMap" - {

        "initial currentWith reflects inner.current" in {
            for
                outer <- Signal.initRef(0)
                inner <- Signal.initRef(42)
                sm = outer.switchMap(_ => inner)
                v <- sm.current
            yield assert(v == 42)
        }

        "inner change is propagated" in {
            for
                outer <- Signal.initRef(0)
                inner <- Signal.initRef(10)
                sm = outer.switchMap(_ => inner)
                f <- Fiber.initUnscoped(sm.next)
                _ <- assertEventually(outer.waiters.map(_ == 1))
                _ <- inner.set(99)
                v <- f.get
            yield assert(v == 99)
        }

        "outer change switches to new inner" in {
            for
                outer  <- Signal.initRef(0)
                inner0 <- Signal.initRef(10)
                inner1 <- Signal.initRef(20)
                sm = outer.switchMap(v => if v == 0 then inner0 else inner1)
                f <- Fiber.initUnscoped(sm.next)
                _ <- assertEventually(outer.waiters.map(_ == 1))
                _ <- outer.set(1)
                v <- f.get
            yield assert(v == 20)
        }

        "previous inner emissions after switch are ignored" in {
            for
                outer  <- Signal.initRef(0)
                inner0 <- Signal.initRef(10)
                inner1 <- Signal.initRef(20)
                sm = outer.switchMap(v => if v == 0 then inner0 else inner1)
                f1 <- Fiber.initUnscoped(sm.next)
                _  <- assertEventually(outer.waiters.map(_ == 1))
                _  <- outer.set(1)
                _  <- f1.get
                f2 <- Fiber.initUnscoped(sm.next)
                // sm.next now races outer.next and inner1.next; wait until inner1 (the signal we change) is armed too.
                _ <- assertEventually(Kyo.zip(outer.waiters, inner1.waiters).map { case (o, i) => o == 1 && i == 1 })
                _ <- inner0.set(99)
                _ <- inner1.set(30)
                v <- f2.get
            yield assert(v == 30)
        }

        "race outer-vs-inner: both change simultaneously" in {
            for
                outer <- Signal.initRef(0)
                inner <- Signal.initRef(10)
                sm = outer.switchMap(_ => inner)
                f <- Fiber.initUnscoped(sm.next)
                _ <- assertEventually(outer.waiters.map(_ == 1))
                _ <- Fiber.initUnscoped(outer.set(1))
                _ <- Fiber.initUnscoped(inner.set(99))
                r <- Abort.run[Timeout](Async.timeout(2.seconds)(f.get))
            yield assert(r.isSuccess)
        }

        "inside streamChanges produces expected sequence" in {
            for
                outer <- Signal.initRef(0)
                inner <- Signal.initRef(10)
                sm = outer.switchMap(_ => inner)
                f  <- Fiber.initUnscoped(sm.streamChanges.take(3).run)
                _  <- Async.sleep(100.millis)
                _  <- inner.set(11)
                _  <- Async.sleep(100.millis)
                _  <- inner.set(12)
                vs <- f.get
            yield assert(vs == Chunk(10, 11, 12))
        }

        "switchMap f called once when only inner changes" in {
            var callCount = 0
            for
                outerRef <- Signal.initRef(0)
                innerRef <- Signal.initRef(0)
                sm = outerRef.switchMap { _ =>
                    callCount += 1; innerRef
                }
                f <- Fiber.initUnscoped(sm.next)
                _ <- assertEventually(outerRef.waiters.map(_ == 1))
                _ <- innerRef.set(1)
                _ <- f.get
            yield assert(callCount == 1, s"f called $callCount times, expected 1")
            end for
        }
    }

    "zip" - {

        "initial currentWith returns paired currents" in {
            for
                refA <- Signal.initRef(1)
                refB <- Signal.initRef(2)
                z = refA.zip(refB)
                v <- z.current
            yield assert(v == (1, 2))
        }

        "self change alone does not emit" in {
            val noEmitTimeout = if Platform.isNative then 1.second else 300.millis
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                z = refA.zip(refB)
                f <- Fiber.initUnscoped(z.next)
                _ <- assertEventually(refA.waiters.map(_ == 1))
                _ <- refA.set(1)
                r <- Abort.run[Timeout](Async.timeout(noEmitTimeout)(f.get))
            yield assert(r.isFailure)
            end for
        }

        "self-then-other emits the latest pair" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                z = refA.zip(refB)
                f <- Fiber.initUnscoped(z.next)
                _ <- assertEventually(refA.waiters.map(_ == 1))
                _ <- refA.set(1)
                _ <- assertEventually(refB.waiters.map(_ == 1))
                _ <- refB.set(2)
                v <- f.get
            yield assert(v == (1, 2))
        }

        "zip other-then-self emits the latest pair" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                z = refA.zip(refB)
                f <- Fiber.initUnscoped(z.next)
                // z.next races refA.next and refB.next, arming their waiters independently. Wait for both
                // before firing: this leaf changes refB first, so syncing only on refA can let refB's
                // subscriber be unregistered when set(1) lands, dropping the change and the zip never emits.
                _      <- assertEventually(Kyo.zip(refA.waiters, refB.waiters).map { case (a, b) => a == 1 && b == 1 })
                _      <- refB.set(1)
                _      <- refA.set(1)
                result <- Abort.run[Timeout](Async.timeout(2.seconds)(f.get))
            yield result match
                case Result.Failure(_: Timeout) => fail("zip did not emit within 2s")
                case Result.Success(pair)       => assert(pair == (1, 1))
                case other                      => fail(s"unexpected: $other")
        }
    }

    "combineLatest" - {

        "initial currentWith returns paired currents" in {
            for
                refA <- Signal.initRef(1)
                refB <- Signal.initRef(2)
                cl = refA.combineLatest(refB)
                v <- cl.current
            yield assert(v == (1, 2))
        }

        "self change alone emits" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                f <- Fiber.initUnscoped(cl.next)
                _ <- assertEventually(refA.waiters.map(_ == 1))
                _ <- refA.set(1)
                v <- f.get
            yield assert(v == (1, 0))
        }

        "other change alone emits" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                f <- Fiber.initUnscoped(cl.next)
                // combineLatest.next races refA.next and refB.next, arming their waiters independently.
                // Wait until both are armed before changing one; otherwise the set can race the arming.
                _ <- assertEventually(Kyo.zip(refA.waiters, refB.waiters).map { case (a, b) => a == 1 && b == 1 })
                _ <- refB.set(2)
                v <- f.get
            yield assert(v == (0, 2))
        }

        "successive other changes each emit" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                // First emit: refB fires. cl.next races refA.next and refB.next as two
                // independent fibers; assert refA has its single waiter (no ghosts yet),
                // then wait for refB's subscriber too before firing refB. Syncing only on
                // refA can leave refB's subscriber unregistered when set(1) swaps in a fresh
                // next-promise, which loses the emit and hangs f1 under contention.
                f1 <- Fiber.initUnscoped(cl.next)
                _  <- assertEventually(refA.waiters.map(_ == 1))
                _  <- assertEventually(refB.waiters.map(_ >= 1))
                _  <- refB.set(1)
                v1 <- f1.get
                // Second emit: refB fires again.
                // After f1 resolved via refB, refA has a ghost waiter (masked next
                // from the race that was cancelled without removing the onComplete).
                // refB has a fresh promise (0 waiters) because refB.set fired it.
                // Wait on refB to confirm the second awaitAny is subscribed to refB.
                f2 <- Fiber.initUnscoped(cl.next)
                _  <- assertEventually(refB.waiters.map(_ >= 1))
                _  <- refB.set(2)
                v2 <- f2.get
            yield assert(v1 == (0, 1) && v2 == (0, 2))
        }

        "interleaved self,other,self,other produces four emits" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                f  <- Fiber.initUnscoped(cl.streamChanges.take(5).run)
                _  <- Async.sleep(50.millis)
                _  <- refA.set(1)
                _  <- Async.sleep(50.millis)
                _  <- refB.set(1)
                _  <- Async.sleep(50.millis)
                _  <- refA.set(2)
                _  <- Async.sleep(50.millis)
                _  <- refB.set(2)
                vs <- f.get
            yield assert(vs.size >= 4 && vs.last == (2, 2))
        }

        "source remains usable after concurrent waiters complete" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                // Two independent waiters both see the broadcast when refA fires
                f1 <- Fiber.initUnscoped(cl.next)
                f2 <- Fiber.initUnscoped(cl.next)
                _  <- assertEventually(refA.waiters.map(_ >= 2))
                _  <- refA.set(1)
                v1 <- f1.get
                v2 <- f2.get
                // After refA fired: refA has a fresh promise (0 waiters), refB has
                // 2 ghost waiters (one from each race loser that cancelled without
                // removing the onComplete from refB's promise).
                // A third waiter adds 1 more to each; wait for refB >= 3 to confirm
                // the new awaitAny is subscribed to refB before firing it.
                f3 <- Fiber.initUnscoped(cl.next)
                _  <- assertEventually(refB.waiters.map(_ >= 3))
                _  <- refB.set(1)
                v3 <- f3.get
            yield assert(v1 == (1, 0) && v2 == (1, 0) && v3 == (1, 1))
        }

    }

    "awaitAny" - {

        "completes when any signal changes" in {
            for
                r0 <- Signal.initRef(0)
                r1 <- Signal.initRef(0)
                r2 <- Signal.initRef(0)
                f  <- Fiber.initUnscoped(Signal.awaitAny(Seq(r0, r1, r2)))
                _  <- assertEventually(r0.waiters.map(_ == 1))
                _  <- r1.set(1)
                _  <- f.get
            yield ()
        }

        "single-element seq equivalent to signal.next" in {
            for
                ref <- Signal.initRef(0)
                f   <- Fiber.initUnscoped(Signal.awaitAny(Seq(ref)))
                _   <- assertEventually(ref.waiters.map(_ == 1))
                _   <- ref.set(1)
                _   <- f.get
            yield ()
        }

        "source remains usable after concurrent waiters complete" in {
            for
                r0 <- Signal.initRef(0)
                r1 <- Signal.initRef(0)
                // Two independent awaitAny waiters both see the broadcast when r0 fires
                f1 <- Fiber.initUnscoped(Signal.awaitAny(Seq(r0, r1)))
                f2 <- Fiber.initUnscoped(Signal.awaitAny(Seq(r0, r1)))
                _  <- assertEventually(r0.waiters.map(_ >= 2))
                _  <- r0.set(1)
                _  <- f1.get
                _  <- f2.get
                // After r0 fired: r0 has a fresh promise (0 waiters), r1 has
                // 2 ghost waiters (one from each race loser). A third waiter
                // adds 1 more to r1; wait for r1 >= 3 to confirm subscription.
                f3 <- Fiber.initUnscoped(Signal.awaitAny(Seq(r0, r1)))
                _  <- assertEventually(r1.waiters.map(_ >= 3))
                _  <- r1.set(1)
                _  <- f3.get
            yield ()
        }

        "empty seq completes immediately" in {
            Signal.awaitAny(Seq.empty).andThen(succeed("empty seq returns immediately without blocking"))
        }
    }

    "zipAll" - {

        "empty seq returns Chunk.empty const" in {
            val z = Signal.zipAll(Seq.empty[Signal[Int]])
            for
                v1 <- z.current
                v2 <- z.next
            yield assert(v1 == Chunk.empty && v2 == Chunk.empty)
            end for
        }

        "single-element seq behaves like signal.map(Chunk(_))" in {
            for
                ref <- Signal.initRef(5)
                z = Signal.zipAll(Seq(ref))
                v  <- z.current
                f  <- Fiber.initUnscoped(z.next)
                _  <- assertEventually(ref.waiters.map(_ == 1))
                _  <- ref.set(6)
                nv <- f.get
            yield assert(v == Chunk(5) && nv == Chunk(6))
        }

        "N-element initial current returns Chunk of currents" in {
            for
                r0 <- Signal.initRef(1)
                r1 <- Signal.initRef(2)
                r2 <- Signal.initRef(3)
                z = Signal.zipAll(Seq(r0, r1, r2))
                v <- z.current
            yield assert(v == Chunk(1, 2, 3))
        }

        "all must change for next to fire" in {
            for
                r0 <- Signal.initRef(0)
                r1 <- Signal.initRef(0)
                r2 <- Signal.initRef(0)
                z = Signal.zipAll(Seq(r0, r1, r2))
                // Arm the waiter
                f <- Fiber.initUnscoped(z.next)
                _ <- assertEventually(r0.waiters.map(_ == 1))
                // Change r1 and r2 but NOT r0: emit must not fire yet
                _ <- r1.set(1)
                _ <- r2.set(1)
                // Check non-blocking: the fiber is still pending
                done <- f.done
                // Now change r0: all 3 have changed, emit must fire
                _ <- r0.set(1)
                v <- f.get
            yield assert(!done && v == Chunk(1, 1, 1))
        }

        "zipAll concurrent out-of-order changes emit" in {
            for
                r0 <- Signal.initRef(0)
                r1 <- Signal.initRef(0)
                r2 <- Signal.initRef(0)
                z = Signal.zipAll(Seq(r0, r1, r2))
                f      <- Fiber.initUnscoped(z.next)
                _      <- assertEventually(r0.waiters.map(_ == 1))
                _      <- r2.set(1)
                _      <- r1.set(1)
                _      <- r0.set(1)
                result <- Abort.run[Timeout](Async.timeout(2.seconds)(f.get))
            yield result match
                case Result.Failure(_: Timeout) => fail("zipAll did not emit within 2s")
                case Result.Success(chunk)      => assert(chunk == Chunk(1, 1, 1))
                case other                      => fail(s"unexpected: $other")
        }
    }

    "combineLatestAll" - {

        "empty seq returns Chunk.empty const" in {
            val z = Signal.combineLatestAll(Seq.empty[Signal[Int]])
            for
                v1 <- z.current
                v2 <- z.next
            yield assert(v1 == Chunk.empty && v2 == Chunk.empty)
            end for
        }

        "single-element delegates to map" in {
            for
                ref <- Signal.initRef(5)
                z = Signal.combineLatestAll(Seq(ref))
                v  <- z.current
                f  <- Fiber.initUnscoped(z.next)
                _  <- assertEventually(ref.waiters.map(_ == 1))
                _  <- ref.set(6)
                nv <- f.get
            yield assert(v == Chunk(5) && nv == Chunk(6))
        }

        "any signal change emits" in {
            for
                r0 <- Signal.initRef(0)
                r1 <- Signal.initRef(0)
                r2 <- Signal.initRef(0)
                z = Signal.combineLatestAll(Seq(r0, r1, r2))
                f <- Fiber.initUnscoped(z.next)
                _ <- assertEventually(r0.waiters.map(_ == 1))
                _ <- r1.set(99)
                v <- f.get
            yield assert(v == Chunk(0, 99, 0))
        }

        "every individual signal can wake the combinator" in {
            for
                r0 <- Signal.initRef(0)
                r1 <- Signal.initRef(0)
                r2 <- Signal.initRef(0)
                z = Signal.combineLatestAll(Seq(r0, r1, r2))
                // First emit: r0 fires; r0 starts with 0 waiters so reliable sync point
                f0 <- Fiber.initUnscoped(z.next)
                _  <- assertEventually(r0.waiters.map(_ == 1))
                _  <- r0.set(1)
                v0 <- f0.get
                // Second emit: r1 fires; after first emit, r1 has 1 ghost waiter.
                // After second awaitAny subscribes, r1 has ghost+new=2.
                // Use r1.waiters >= 2 as sync point to confirm subscription.
                f1 <- Fiber.initUnscoped(z.next)
                _  <- assertEventually(r1.waiters.map(_ >= 2))
                _  <- r1.set(1)
                v1 <- f1.get
                // Third emit: r2 fires; after second emit, r1 is fresh (0 waiters),
                // r2 has 2 ghost waiters. After third awaitAny subscribes, r1 has 1 new.
                // Use r1.waiters >= 1 as sync point (r1 is fresh, so 0+1=1 is reliable).
                f2 <- Fiber.initUnscoped(z.next)
                _  <- assertEventually(r1.waiters.map(_ >= 1))
                _  <- r2.set(1)
                v2 <- f2.get
            yield assert(v0 == Chunk(1, 0, 0) && v1 == Chunk(1, 1, 0) && v2 == Chunk(1, 1, 1))
        }

        "rapid bursts coalesce" in {
            for
                ref <- Signal.initRef(0)
                z = Signal.combineLatestAll(Seq(ref))
                f  <- Fiber.initUnscoped(z.streamChanges.take(2).run)
                _  <- Async.sleep(50.millis)
                _  <- Kyo.foreachDiscard(Seq.range(1, 11))(ref.set)
                vs <- f.get
            yield assert(vs.size == 2 && vs.head == Chunk(0) && vs.last.head >= 1)
        }

    }

    "composition" - {

        "map -> switchMap -> zip composes at type level" in {
            for
                ref <- Signal.initRef(0)
                mapped = ref.map(_ + 1)
                inner  = Signal.initConst(100)
                sm     = mapped.switchMap(_ => inner)
                inner2 = Signal.initConst(200)
                zipped = sm.zip(inner2)
                v <- zipped.current
            yield assert(v == (100, 200))
        }

        "switchMap inside streamChanges with mutation" in {
            for
                outer <- Signal.initRef(0)
                inner <- Signal.initRef(10)
                mapped = outer.map(_ * 2)
                sm     = mapped.switchMap(_ => inner)
                f  <- Fiber.initUnscoped(sm.streamChanges.take(3).run)
                _  <- Async.sleep(100.millis)
                _  <- inner.set(11)
                _  <- Async.sleep(100.millis)
                _  <- inner.set(12)
                vs <- f.get
            yield assert(vs == Chunk(10, 11, 12))
        }

        "combineLatest feeding streamChanges produces interleaved emit sequence" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                f  <- Fiber.initUnscoped(cl.streamChanges.take(5).run)
                _  <- Async.sleep(50.millis)
                _  <- refA.set(1)
                _  <- Async.sleep(50.millis)
                _  <- refB.set(1)
                _  <- Async.sleep(50.millis)
                _  <- refA.set(2)
                _  <- Async.sleep(50.millis)
                _  <- refB.set(2)
                vs <- f.get
            yield assert(vs.size >= 4 && vs.last == (2, 2))
        }

    }

    private def pollUntil(cond: Boolean < Async, maxTries: Int = 3000)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if i >= maxTries then Loop.done(false)
            else cond.map(c => if c then Loop.done(true) else Async.sleep(1.millis).andThen(Loop.continue))
        }

    private def recordValue[A](seen: AtomicRef[Chunk[A]], v: A)(using Frame): Unit < Async =
        seen.updateAndGet(_.append(v)).unit

    private def awaitValue(ref: AtomicRef[String], target: String, maxTries: Int)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if i >= maxTries then Loop.done(false)
            else ref.get.map(v => if v == target then Loop.done(true) else Async.sleep(1.millis).andThen(Loop.continue))
        }

    // Leaf and `map`-over-leaf `observe` use the repairing path (the exact register-before-read override was removed
    // because it miscompiled on Scala Native; see SignalRef in Signal.scala). The guarantee is that the final value is
    // never lost: a write that lands in the read/register window is reconciled within `repairInterval`. Drive
    // back-to-back set(a);set(b) under an explicit short repairInterval (50ms) and require the final value to arrive
    // within a generous multiple of it (1s); a returned count > 0 means a value was actually lost, not merely late.
    private def observeNeverLosesFinalValue(useMap: Boolean, iterations: Int)(using Frame): Int < Async =
        for
            ref <- Signal.initRef("")
            sig = if useMap then ref.map(v => v) else ref
            lastSeen <- AtomicRef.init("")
            fiber    <- Fiber.initUnscoped(sig.observe(50.millis)(lastSeen.set(_)))
            misses <- Kyo.foreach(Chunk.from(1 to iterations)) { i =>
                val a = s"a$i"
                val b = s"b$i"
                for
                    _   <- ref.set(a)
                    _   <- ref.set(b)
                    got <- awaitValue(lastSeen, b, 1000)
                yield if got then 0 else 1
                end for
            }
            _ <- fiber.interrupt
        yield misses.foldLeft(0)(_ + _)

    "observe" - {
        "emits the current value on subscription" in {
            for
                ref    <- Signal.initRef("init")
                seen   <- AtomicRef.init(Chunk.empty[String])
                fiber  <- Fiber.initUnscoped(ref.observe(recordValue(seen, _)))
                ok     <- pollUntil(seen.get.map(_.nonEmpty))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(ok && result == Chunk("init"))
        }

        "emits each distinct change in order" in {
            for
                ref    <- Signal.initRef(0)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(ref.observe(recordValue(seen, _)))
                _      <- pollUntil(seen.get.map(_ == Chunk(0)))
                _      <- ref.set(1)
                _      <- pollUntil(seen.get.map(_.contains(1)))
                _      <- ref.set(2)
                _      <- pollUntil(seen.get.map(_.contains(2)))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(result == Chunk(0, 1, 2))
        }

        "does not re-emit on a same-value set" in {
            for
                ref    <- Signal.initRef(0)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(ref.observe(recordValue(seen, _)))
                _      <- pollUntil(seen.get.map(_ == Chunk(0)))
                _      <- ref.set(0) // same value: SignalRef does not notify
                _      <- Async.sleep(30.millis)
                _      <- ref.set(1)
                _      <- pollUntil(seen.get.map(_.contains(1)))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(result == Chunk(0, 1))
        }

        "stops after interruption" in {
            for
                ref    <- Signal.initRef(0)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(ref.observe(recordValue(seen, _)))
                _      <- pollUntil(seen.get.map(_ == Chunk(0)))
                _      <- fiber.interrupt
                _      <- ref.set(1)
                _      <- Async.sleep(50.millis)
                result <- seen.get
            yield assert(result == Chunk(0)) // the post-interrupt change is not observed
        }

        "never loses the final value under back-to-back writes (SignalRef leaf)" in {
            observeNeverLosesFinalValue(useMap = false, iterations = 5000).map(lost => assert(lost == 0, s"SignalRef lost $lost / 5000"))
        }

        "never loses the final value under back-to-back writes (map delegates to leaf)" in {
            observeNeverLosesFinalValue(useMap = true, iterations = 5000).map(lost => assert(lost == 0, s"map lost $lost / 5000"))
        }

        "reconciles a missed wakeup within repairInterval on a non-exact signal" in {
            for
                state <- AtomicRef.init(0)
                sig = Signal.initRaw[Int](
                    currentWith = [B, S] => f => state.get.map(f),
                    nextWith = [B, S] => (_: Int => B < S) => Async.never[B] // never fires: every change is a "missed wakeup"
                )
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(sig.observe(40.millis)(recordValue(seen, _)))
                _      <- pollUntil(seen.get.map(_.contains(0)))
                _      <- state.set(1)
                ok     <- pollUntil(seen.get.map(_.contains(1)))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(ok && result.contains(0) && result.contains(1))
        }
    }

    "observe (per-value scope)" - {
        "runs f for the current value and each subsequent change" in {
            for
                ref    <- Signal.initRef(0)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(ref.observe(recordValue(seen, _)))
                _      <- assertEventually(seen.get.map(_ == Chunk(0)))
                _      <- ref.set(1)
                _      <- assertEventually(seen.get.map(_.contains(1)))
                _      <- ref.set(2)
                _      <- assertEventually(seen.get.map(_.contains(2)))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(result == Chunk(0, 1, 2))
        }

        "runs f for the mapped current value and each change (map over leaf)" in {
            for
                ref <- Signal.initRef(0)
                mapped = ref.map(_ * 10)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(mapped.observe(recordValue(seen, _)))
                _      <- assertEventually(seen.get.map(_ == Chunk(0)))
                _      <- ref.set(1)
                _      <- assertEventually(seen.get.map(_.contains(10)))
                _      <- ref.set(3)
                _      <- assertEventually(seen.get.map(_.contains(30)))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(result == Chunk(0, 10, 30))
        }

        "closes the per-value scope before the next value's f runs (resource released on change)" in {
            // Each value's `f` acquires a per-value-scope resource (`live` inc on acquire, dec on the scope's finalizer)
            // and forks a child fiber into the same scope. When the value changes, the prior value's scope MUST close
            // (running the dec and interrupting the forked child) before `f` runs for the new value, so `live` is back to
            // exactly 1 after every change and never climbs to N. The `live` counter is the deterministic witness here
            // (waiter-count is unreliable because cancelling a masked-promise waiter leaves a ghost until the next set).
            for
                parent <- Signal.initRef(0)
                child  <- Signal.initRef("c")
                live   <- AtomicInt.init(0)
                peak   <- AtomicInt.init(0)
                fiber <- Fiber.initUnscoped(parent.observe { _ =>
                    for
                        n <- Scope.acquireRelease(live.incrementAndGet)(_ => live.decrementAndGet.unit)
                        _ <- peak.updateAndGet(p => math.max(p, n))
                        _ <- Fiber.init(child.next)
                    yield ()
                })
                _ <- assertEventually(live.get.map(_ == 1))
                _ <- parent.set(1)
                _ <- assertEventually(parent.current.map(_ == 1))
                _ <- assertEventually(live.get.map(_ == 1))
                _ <- parent.set(2)
                _ <- assertEventually(parent.current.map(_ == 2))
                _ <- assertEventually(live.get.map(_ == 1))
                _ <- parent.set(3)
                _ <- assertEventually(parent.current.map(_ == 3))
                _ <- assertEventually(live.get.map(_ == 1))
                p <- peak.get
                _ <- fiber.interrupt
            yield assert(p == 1)
        }

        "interrupts a child forked in f when the value changes" in {
            // A child fiber forked into the per-value scope parks forever; the scope ALSO registers a finalizer that
            // records the value on close. When the value changes, the prior value's scope closes: the child fiber is
            // interrupted (it stops parking) and the finalizer records that value. Witnessing the finalizer for value 0
            // proves the per-value scope (and the child fiber it owns) was torn down on the change to 1.
            for
                parent   <- Signal.initRef(0)
                child    <- Signal.initRef("c")
                running  <- AtomicInt.init(0)
                released <- AtomicRef.init(Chunk.empty[Int])
                fiber <- Fiber.initUnscoped(parent.observe { v =>
                    Scope.ensure(released.updateAndGet(_.append(v)).unit).andThen {
                        // The child fiber increments `running` while alive; the per-value scope interrupts it on close.
                        Fiber.init(running.incrementAndGet.andThen(child.next)).unit
                    }
                })
                _ <- assertEventually(running.get.map(_ == 1))
                _ <- parent.set(1)
                _ <- assertEventually(parent.current.map(_ == 1))
                // value 0's scope must close on the change to 1, running its finalizer with v == 0.
                _      <- assertEventually(released.get.map(_.contains(0)))
                result <- released.get
                _      <- fiber.interrupt
            yield assert(result.contains(0))
        }

        "interrupts the current value's child on outer observe interrupt (cascade)" in {
            // Interrupting the outer observe fiber must close the current value's per-value scope, interrupting the
            // child fiber it forked. The per-value scope's finalizer running (released == true) is the deterministic
            // witness that the cascade reached the child fiber owned by that scope.
            for
                parent   <- Signal.initRef(0)
                child    <- Signal.initRef("c")
                running  <- AtomicInt.init(0)
                released <- AtomicRef.init(false)
                fiber <- Fiber.initUnscoped(parent.observe { _ =>
                    Scope.ensure(released.set(true)).andThen {
                        Fiber.init(running.incrementAndGet.andThen(child.next)).unit
                    }
                })
                _      <- assertEventually(running.get.map(_ == 1))
                _      <- fiber.interrupt
                _      <- assertEventually(released.get.map(_ == true))
                result <- released.get
            yield assert(result)
        }

        "does not tear the current value's scope down while the signal is idle (leaf)" in {
            // A leaf has NO repair timer, so an idle parent (no set) must keep the current value's per-value scope open
            // indefinitely. We drive many UNRELATED changes on a separate `ticker` signal while `parent` stays idle and
            // assert the per-value finalizer never fired and the per-value resource stays live the whole time.
            for
                parent   <- Signal.initRef(0)
                ticker   <- Signal.initRef(0)
                live     <- AtomicInt.init(0)
                released <- AtomicRef.init(false)
                fiber <- Fiber.initUnscoped(parent.observe { _ =>
                    Scope.acquireRelease(live.incrementAndGet)(_ => live.decrementAndGet.unit).andThen {
                        Scope.ensure(released.set(true)).unit
                    }
                })
                _ <- assertEventually(live.get.map(_ == 1))
                // Drive several UNRELATED ticks (on `ticker`, not `parent`) while parent stays idle.
                _ <- Kyo.foreachDiscard(Chunk(1, 2, 3, 4, 5))(i => ticker.set(i).andThen(assertEventually(ticker.current.map(_ == i))))
                stillLive <- live.get
                fired     <- released.get
                _         <- fiber.interrupt
            yield assert(stillLive == 1 && !fired)
        }

        "does not tear the current value's scope down on a repair timer for a still-current value (non-exact)" in {
            // A non-exact `initRaw` signal whose `nextWith` never fires forces the repairing default loop: the repair
            // timer fires repeatedly. While the value is unchanged the per-value scope MUST stay open (the hold loops
            // until `current` actually differs). We assert the finalizer did NOT fire across several repair intervals,
            // then change the value and assert convergence + that the OLD value's scope finally closes.
            for
                state <- AtomicRef.init(0)
                sig = Signal.initRaw[Int](
                    currentWith = [B, S] => f => state.get.map(f),
                    nextWith = [B, S] => (_: Int => B < S) => Async.never[B] // never fires: forces the repair path
                )
                live     <- AtomicInt.init(0)
                released <- AtomicRef.init(false)
                seen     <- AtomicRef.init(Chunk.empty[Int])
                // 30ms repair interval: the timer fires many times while the value stays 0, but must NOT close the scope.
                fiber <- Fiber.initUnscoped(sig.observe(30.millis) { v =>
                    recordValue(seen, v).andThen {
                        Scope.acquireRelease(live.incrementAndGet)(_ => live.decrementAndGet.unit).andThen {
                            Scope.ensure(released.set(true)).unit
                        }
                    }
                })
                _ <- assertEventually(seen.get.map(_.contains(0)))
                _ <- assertEventually(live.get.map(_ == 1))
                // Let several repair intervals (30ms each) elapse; the scope for value 0 must stay open the whole time.
                _         <- Kyo.foreachDiscard(Chunk(1, 2, 3, 4, 5))(_ => assertEventually(live.get.map(_ == 1)))
                idleFired <- released.get
                idleLive  <- live.get
                // Now actually change the value: the scope must converge to value 1 within repairInterval, closing value 0's scope.
                _      <- state.set(1)
                _      <- assertEventually(seen.get.map(_.contains(1)))
                _      <- assertEventually(released.get.map(_ == true))
                _      <- assertEventually(live.get.map(_ == 1)) // value 1's scope is now the only live one
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(!idleFired && idleLive == 1 && result.contains(0) && result.contains(1) && result.last == 1)
        }

        "stops after interruption" in {
            for
                ref    <- Signal.initRef(0)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(ref.observe(recordValue(seen, _)))
                _      <- assertEventually(seen.get.map(_ == Chunk(0)))
                _      <- fiber.interrupt
                _      <- fiber.getResult
                _      <- ref.set(1)
                result <- seen.get
            yield assert(result == Chunk(0)) // the post-interrupt change is not observed
        }
    }

end SignalTest
