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
                _  <- assertEventually(outer.waiters.map(_ == 1))
                _  <- inner0.set(99)
                _  <- inner1.set(30)
                v  <- f2.get
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
                f      <- Fiber.initUnscoped(z.next)
                _      <- assertEventually(refA.waiters.map(_ == 1))
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
                _ <- assertEventually(refA.waiters.map(_ == 1))
                _ <- refB.set(2)
                v <- f.get
            yield assert(v == (0, 2))
        }

        "successive other changes each emit" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                // First emit: refB fires; wait on refA as sync point (no ghosts yet)
                f1 <- Fiber.initUnscoped(cl.next)
                _  <- assertEventually(refA.waiters.map(_ == 1))
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

end SignalTest
