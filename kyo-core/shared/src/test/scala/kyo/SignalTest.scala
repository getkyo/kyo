package kyo

import kyo.internal.Platform

class SignalTest extends kyo.test.Test[Any]:

    // How long to wait before concluding that a `next` which should not fire indeed did not.
    private val noEmitTimeout = if Platform.isNative then 1.second else 300.millis

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
                    v <- sig.current
                yield assert(v == 42)
            }
            "next never completes" in {
                val sig = Signal.initConst(42)
                for
                    f <- Fiber.initUnscoped(sig.next)
                    r <- Abort.run[Timeout](Async.timeout(noEmitTimeout)(f.get))
                    _ <- f.interrupt
                yield assert(r.isFailure)
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
                    v <- Signal.initConstWith(42)(_.current)
                yield assert(v == 42)
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
                _      <- assertEventually(ref.waiters.map(_ == 1))
                _      <- ref.set(2)
                _      <- assertEventually(ref.waiters.map(_ == 1))
                _      <- ref.set(2) // Should be ignored
                _      <- assertEventually(ref.waiters.map(_ == 1))
                _      <- ref.set(3)
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
                    ref     <- Signal.initRef(0)
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
                // sm.next subscribes to BOTH outer and inner (awaitAny). Sync on both so the inner
                // subscription is registered before inner.set, otherwise the set is missed and f.get hangs.
                _ <- assertEventually(Kyo.zip(outer.waiters, inner.waiters).map { case (o, i) => o == 1 && i == 1 })
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
                _  <- assertEventually(inner.waiters.map(_ == 1))
                _  <- inner.set(11)
                _  <- assertEventually(inner.waiters.map(_ == 1))
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
                // Same as "inner change is propagated": sm.next subscribes to both signals, so sync on
                // both before setting inner, otherwise the set races the inner subscription and f.get hangs.
                _ <- assertEventually(Kyo.zip(outerRef.waiters, innerRef.waiters).map { case (o, i) => o == 1 && i == 1 })
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

        "a constant input never lets the pair emit" in {
            // zip waits for ALL inputs to change, and a constant never does, so pairing one with a
            // mutable signal yields a `next` that can never fire.
            for
                refA <- Signal.initRef(0)
                z = refA.zip(Signal.initConst(99))
                f <- Fiber.initUnscoped(z.next)
                _ <- assertEventually(refA.waiters.map(_ == 1))
                _ <- refA.set(1)
                r <- Abort.run[Timeout](Async.timeout(noEmitTimeout)(f.get))
                _ <- f.interrupt
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

        "a constant input never drives a change" in {
            // A constant's `next` must never complete: a constant has no changes to report. Completing it would win
            // every arm of the race, firing `combineLatest(ref, const).next` with nothing and busy-looping `observe`.
            for
                refA <- Signal.initRef(0)
                cl = refA.combineLatest(Signal.initConst(99))
                f <- Fiber.initUnscoped(cl.next)
                // With the bug the const wins the race and `cl.next` completes before arming refA (waiters stays 0).
                _ <- assertEventually(refA.waiters.map(_ == 1))
                _ <- refA.set(1)
                v <- f.get
            yield assert(v == (1, 99))
        }

        /** A set that lands before `next` has registered is missed, and the leaf then hangs on the waiter.
          *
          * A barrier on `waiters` cannot prevent it either. `awaitAny` cancels its losing branch
          * without unregistering, so the untouched signal keeps that waiter and the count cannot tell a stale one
          * from a live registration: `>=` is satisfied by stale waiters alone, and an exact count would never be
          * satisfied at all when a loser was cancelled before it registered.
          *
          * Driving the source upward until the waiter reports needs no barrier. A set that arrives early is simply
          * missed and the next one is not, so what is asserted is what the leaf is named for: a change on the OTHER
          * signal reaches a combined waiter, twice in a row, carrying the unchanged value of the first.
          */
        "successive other changes each emit" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                seen <- AtomicRef.init(Chunk.empty[(Int, Int)])
                f1   <- Fiber.initUnscoped(cl.next.map(recordValue(seen, _)))
                _    <- fireUntilSeen(refB, seen, want = 1, from = 1)
                f2   <- Fiber.initUnscoped(cl.next.map(recordValue(seen, _)))
                last <- fireUntilSeen(refB, seen, want = 2, from = 2)
                vs   <- seen.get
                _    <- f1.interrupt
                _    <- f2.interrupt
            yield
                assert(vs.size == 2, s"each of the two waiters should have reported one emit, got $vs")
                assert(vs.forall(_._1 == 0), s"refA never changed, so every emit must carry its initial value: $vs")
                assert(vs.forall(_._2 >= 1), s"every emit must carry a value refB was actually set to: $vs")
                assert(vs.last._2 <= last, s"the last emit cannot carry a value beyond the last one set: $vs, last=$last")
            end for
        }

        /** `streamChanges` is documented to skip intermediate values ("rapid changes may result in some intermediate
          * values being skipped", Signal.scala), so the exact emit sequence is not a property it has and must not be
          * asserted. Pacing sets behind a `waiters` count cannot force one either: `awaitAny` cancels its losing branch
          * without unregistering, so the untouched signal keeps that waiter and ghosts accumulate. A `>= 2` barrier is
          * satisfied by two ghosts and no live waiter, letting a set land in the read/register window where it is
          * missed, after which the collection waits for a value that never arrives.
          *
          * What IS a property, and what this asserts: every emitted pair is one the two signals actually held, they
          * arrive in order, and no value repeats. Each set is paced on the previous EMIT rather than on a waiter count,
          * which keeps the common path lossless without requiring it, and every wait is bounded so a skip ends the
          * collection instead of hanging it.
          */
        "interleaved self,other,self,other emits the value trajectory in order" in {
            val trajectory = Chunk((0, 0), (1, 0), (1, 1), (2, 1), (2, 2))
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                seen  <- AtomicRef.init(Chunk.empty[(Int, Int)])
                fiber <- Fiber.initUnscoped(cl.streamChanges.foreach(recordValue(seen, _)))
                _     <- pollUntil(seen.get.map(_.contains((0, 0))))
                _     <- refA.set(1)
                _     <- pollUntil(seen.get.map(_.contains((1, 0))))
                _     <- refB.set(1)
                _     <- pollUntil(seen.get.map(_.contains((1, 1))))
                _     <- refA.set(2)
                _     <- pollUntil(seen.get.map(_.contains((2, 1))))
                _     <- refB.set(2)
                _     <- pollUntil(seen.get.map(_.contains((2, 2))))
                vs    <- seen.get
                _     <- fiber.interrupt
            yield
                assert(vs.nonEmpty, "the stream emitted nothing at all")
                assert(vs.head == (0, 0), s"the first emit must be the initial pair, got ${vs.head}")
                assert(vs.distinct.size == vs.size, s"a value was emitted twice: $vs")
                assert(
                    isOrderedSubsetOf(vs, trajectory),
                    s"emitted $vs, which is not an in-order subset of the trajectory $trajectory"
                )
            end for
        }

        /** What this leaf is named for is that the source keeps working once concurrent waiters have completed, and
          * that is what it asserts: two waiters both complete on a change to one signal, a third registered
          * afterwards completes on a change to the other, and every reported pair carries values that were actually
          * set.
          *
          * DELIBERATELY NOT ASSERTED: that the two concurrent waiters observe the SAME change. That holds only if
          * both finished registering before the fire, which is unobservable here. A waiter count cannot stand in for
          * it, because it cannot distinguish a live registration from an `awaitAny` loser cancelled without
          * unregistering, so it can pass with no live waiter and hang the leaf.
          * Firing until each waiter reports keeps the source honest without asserting a coincidence the API does not
          * promise.
          */
        "source remains usable after concurrent waiters complete" in {
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                seen  <- AtomicRef.init(Chunk.empty[(Int, Int)])
                f1    <- Fiber.initUnscoped(cl.next.map(recordValue(seen, _)))
                f2    <- Fiber.initUnscoped(cl.next.map(recordValue(seen, _)))
                lastA <- fireUntilSeen(refA, seen, want = 2, from = 1)
                f3    <- Fiber.initUnscoped(cl.next.map(recordValue(seen, _)))
                lastB <- fireUntilSeen(refB, seen, want = 3, from = 1)
                vs    <- seen.get
                _     <- f1.interrupt
                _     <- f2.interrupt
                _     <- f3.interrupt
            yield
                assert(vs.size == 3, s"all three waiters should have completed, got $vs")
                val (broadcast, later) = vs.splitAt(2)
                assert(
                    broadcast.forall(p => p._1 >= 1 && p._1 <= lastA && p._2 == 0),
                    s"each concurrent waiter must report a refA value that was set, with refB untouched: $broadcast"
                )
                assert(
                    later.forall(p => p._1 == lastA && p._2 >= 1 && p._2 <= lastB),
                    s"the waiter registered afterwards must report the settled refA and a refB value that was set: $later"
                )
        }

    }

    "awaitAny" - {

        "completes when any signal changes" in {
            for
                r0 <- Signal.initRef(0)
                r1 <- Signal.initRef(0)
                r2 <- Signal.initRef(0)
                f  <- Fiber.initUnscoped(Signal.awaitAny(Seq(r0, r1, r2)))
                // awaitAny races r0.next, r1.next, r2.next, arming their waiters independently. Wait for all
                // three to be armed before firing r1, otherwise r1.set races r1's subscription and f.get hangs.
                _ <- assertEventually(Kyo.foreach(Seq(r0, r1, r2))(_.waiters).map(_.forall(_ == 1)))
                _ <- r1.set(1)
                _ <- f.get
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

        /** A liveness leaf: all three waiters must complete. It asserts nothing about WHICH change each observed,
          * because `awaitAny` yields no value, and nothing about the two concurrent ones seeing the same change,
          * which is not observable from here.
          *
          * A waiter-count barrier cannot establish that they are listening: a cancelled `awaitAny` loser stays
          * registered, so a count cannot tell a live registration from a stale one, and the leaf would fire into a
          * signal nobody is listening to and then hang on `get`. Firing until the completion count moves
          * makes an early set harmless, because the next one is a fresh change.
          */
        "source remains usable after concurrent waiters complete" in {
            for
                r0   <- Signal.initRef(0)
                r1   <- Signal.initRef(0)
                done <- AtomicInt.init(0)
                f1   <- Fiber.initUnscoped(Signal.awaitAny(Seq(r0, r1)).andThen(done.incrementAndGet.unit))
                f2   <- Fiber.initUnscoped(Signal.awaitAny(Seq(r0, r1)).andThen(done.incrementAndGet.unit))
                _    <- fireUntil(r0, done.get.map(_ >= 2), from = 1)
                f3   <- Fiber.initUnscoped(Signal.awaitAny(Seq(r0, r1)).andThen(done.incrementAndGet.unit))
                _    <- fireUntil(r1, done.get.map(_ >= 3), from = 1)
                n    <- done.get
                _    <- f1.interrupt
                _    <- f2.interrupt
                _    <- f3.interrupt
            yield assert(n == 3, s"all three waiters should have completed, got $n")
        }

        "empty seq never completes" in {
            for
                f <- Fiber.initUnscoped(Signal.awaitAny(Seq.empty))
                r <- Abort.run[Timeout](Async.timeout(noEmitTimeout)(f.get))
                _ <- f.interrupt
            yield assert(r.isFailure)
        }
    }

    "zipAll" - {

        "empty seq returns Chunk.empty const" in {
            val z = Signal.zipAll(Seq.empty[Signal[Int]])
            for
                v <- z.current
                f <- Fiber.initUnscoped(z.next)
                r <- Abort.run[Timeout](Async.timeout(noEmitTimeout)(f.get))
                _ <- f.interrupt
            yield assert(v == Chunk.empty && r.isFailure)
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
                f <- Fiber.initUnscoped(z.next)
                // zipAll subscribes to r0/r1/r2 concurrently; wait until all three are armed so the
                // r1/r2 changes below are registered. Syncing on r0 alone would let them fire before
                // their subscriptions land, dropping the changes and hanging the emit.
                _ <- assertEventually(Kyo.foreach(Seq(r0, r1, r2))(_.waiters).map(_.forall(_ == 1)))
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
                f <- Fiber.initUnscoped(z.next)
                // Wait until all three are armed: zipAll subscribes concurrently, so r0 being armed
                // does not imply r1/r2 are subscribed before we fire them.
                _      <- assertEventually(Kyo.foreach(Seq(r0, r1, r2))(_.waiters).map(_.forall(_ == 1)))
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
                v <- z.current
                f <- Fiber.initUnscoped(z.next)
                r <- Abort.run[Timeout](Async.timeout(noEmitTimeout)(f.get))
                _ <- f.interrupt
            yield assert(v == Chunk.empty && r.isFailure)
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
                // Sync on the signal we mutate. combineLatestAll subscribes to r0/r1/r2 concurrently
                // via Async.race, so r0 having a waiter does not imply r1 does; setting r1 before its
                // subscription lands would lose the wakeup and hang z.next.
                _ <- assertEventually(r1.waiters.map(_ == 1))
                _ <- r1.set(99)
                v <- f.get
            yield assert(v == Chunk(0, 99, 0))
        }

        "every individual signal can wake the combinator" in {
            // A fresh combinator per position keeps the sync point a clean `waiters == 1` on the source about to change;
            // reusing one syncs on the non-deterministic ghost callbacks an interrupted Async.race arm leaves, so a `waiters >= N` threshold can hang assertEventually.
            def wakes(index: Int, expected: Chunk[Int]) =
                for
                    r0 <- Signal.initRef(0)
                    r1 <- Signal.initRef(0)
                    r2 <- Signal.initRef(0)
                    sources = Chunk(r0, r1, r2)
                    z       = Signal.combineLatestAll(sources)
                    f <- Fiber.initUnscoped(z.next)
                    // combineLatestAll subscribes to its sources concurrently via Async.race, so sync on
                    // the source we mutate: setting it before its subscription lands loses the wakeup.
                    _ <- assertEventually(sources(index).waiters.map(_ == 1))
                    _ <- sources(index).set(1)
                    v <- f.get
                yield assert(v == expected)
            for
                _ <- wakes(0, Chunk(1, 0, 0))
                _ <- wakes(1, Chunk(0, 1, 0))
                _ <- wakes(2, Chunk(0, 0, 1))
            yield succeed
            end for
        }

        "rapid bursts coalesce" in {
            for
                ref <- Signal.initRef(0)
                z = Signal.combineLatestAll(Seq(ref))
                f  <- Fiber.initUnscoped(z.streamChanges.take(2).run)
                _  <- assertEventually(ref.waiters.map(_ == 1))
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
                _  <- assertEventually(inner.waiters.map(_ == 1))
                _  <- inner.set(11)
                _  <- assertEventually(inner.waiters.map(_ == 1))
                _  <- inner.set(12)
                vs <- f.get
            yield assert(vs == Chunk(10, 11, 12))
        }

        // Same contract as the interleaved leaf above: `streamChanges` may skip intermediate values, so this asserts
        // the emitted pairs are an in-order subset of the trajectory the two signals actually walked, paced on emits
        // rather than on a waiter count that cannot tell a stale ghost from a live re-arm.
        "combineLatest feeding streamChanges emits an in-order subset of the trajectory" in {
            val trajectory = Chunk((0, 0), (1, 0), (1, 1), (2, 1), (2, 2))
            for
                refA <- Signal.initRef(0)
                refB <- Signal.initRef(0)
                cl = refA.combineLatest(refB)
                seen  <- AtomicRef.init(Chunk.empty[(Int, Int)])
                fiber <- Fiber.initUnscoped(cl.streamChanges.foreach(recordValue(seen, _)))
                _     <- pollUntil(seen.get.map(_.contains((0, 0))))
                _     <- refA.set(1)
                _     <- pollUntil(seen.get.map(_.contains((1, 0))))
                _     <- refB.set(1)
                _     <- pollUntil(seen.get.map(_.contains((1, 1))))
                _     <- refA.set(2)
                _     <- pollUntil(seen.get.map(_.contains((2, 1))))
                _     <- refB.set(2)
                _     <- pollUntil(seen.get.map(_.contains((2, 2))))
                vs    <- seen.get
                _     <- fiber.interrupt
            yield
                assert(vs.nonEmpty, "the stream emitted nothing at all")
                assert(vs.head == (0, 0), s"the first emit must be the initial pair, got ${vs.head}")
                assert(vs.distinct.size == vs.size, s"a value was emitted twice: $vs")
                assert(
                    isOrderedSubsetOf(vs, trajectory),
                    s"emitted $vs, which is not an in-order subset of the trajectory $trajectory"
                )
            end for
        }

    }

    private def pollUntil(cond: Boolean < Async, maxTries: Int = 3000)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if i >= maxTries then Loop.done(false)
            else cond.map(c => if c then Loop.done(true) else Async.sleep(1.millis).andThen(Loop.continue))
        }

    /** True when `emitted` appears inside `trajectory` in order, allowing gaps.
      *
      * The gaps are the point: a stream that documents skipping intermediate values may emit any subsequence, so this
      * accepts every outcome the contract allows and rejects the ones it does not, a value never held or two arriving
      * out of order.
      */
    private def isOrderedSubsetOf[A](emitted: Chunk[A], trajectory: Chunk[A])(using CanEqual[A, A]): Boolean =
        var remaining = trajectory
        emitted.forall { v =>
            remaining = remaining.dropWhile(_ != v)
            if remaining.isEmpty then false
            else
                remaining = remaining.drop(1)
                true
            end if
        }
    end isOrderedSubsetOf

    /** Sets `ref` to successive values until `seen` holds at least `want` entries, returning the last value set.
      *
      * A `next` waiter that has not finished registering misses a set entirely, and no count of waiters can tell that
      * state apart from a registered one, because a cancelled `awaitAny` loser stays registered. Firing again is what
      * makes the miss harmless: each new value is a real change, so the first set that lands after registration is
      * observed. The returned value bounds what the waiter can have seen.
      */
    private def fireUntil(ref: SignalRef[Int], cond: Boolean < Async, from: Int)(using Frame): Int < Async =
        Loop.indexed(from) { (attempt, v) =>
            if attempt >= 20 then Loop.done(v)
            else
                ref.set(v).andThen(pollUntil(cond, maxTries = 200)).map { ok =>
                    if ok then Loop.done(v) else Loop.continue(v + 1)
                }
        }

    private def fireUntilSeen(ref: SignalRef[Int], seen: AtomicRef[Chunk[(Int, Int)]], want: Int, from: Int)(using
        Frame
    ): Int < Async =
        fireUntil(ref, seen.get.map(_.size >= want), from)

    private def recordValue[A](seen: AtomicRef[Chunk[A]], v: A)(using Frame): Unit < Async =
        seen.updateAndGet(_.append(v)).unit

    // The guarantee is that the final value is never lost. A leaf and a `map` over a leaf observe exactly, so they deliver
    // a write that lands in the read/register window without the repair timer; with `Duration.Infinity` as the
    // repairInterval only the exact protocol can pass. Drive back-to-back set(a);set(b) and take what the observer hands
    // over until the final value arrives. The handoff is a channel rather than a polled reference because a poll's sleep
    // costs a timer tick per iteration, and on a platform whose tick is 15ms that alone is over the leaf's budget at
    // 5000 iterations; a take returns the instant the value is put. Each wait is bounded so a genuinely lost value
    // ends the iteration as a miss instead of hanging the leaf, and the bound is wide enough that a starved repair
    // fiber that is merely late is not miscounted as a loss.
    private def observeNeverLosesFinalValue(useMap: Boolean, iterations: Int, repairInterval: Duration = 50.millis)(using
        Frame
    ): Int < Async =
        for
            ref <- Signal.initRef("")
            sig = if useMap then ref.map(v => v) else ref
            seen   <- Channel.initUnscoped[String](16)
            fiber  <- Fiber.initUnscoped(sig.observe(repairInterval)(v => Abort.run[Closed](seen.put(v)).unit))
            misses <- Kyo.foreach(Chunk.from(1 to iterations)) { i =>
                val a                                          = s"a$i"
                val b                                          = s"b$i"
                def untilFinal: Unit < (Async & Abort[Closed]) =
                    seen.take.map(v => if v == b then () else untilFinal)
                for
                    _   <- ref.set(a)
                    _   <- ref.set(b)
                    got <- Abort.run[Timeout | Closed](Async.timeout(2.seconds)(untilFinal))
                yield if got.isSuccess then 0 else 1
                end for
            }
            _ <- fiber.interrupt
            _ <- seen.close
        yield misses.foldLeft(0)(_ + _)

    private def streamChangesNeverLosesFinalValue(iterations: Int)(using Frame): Int < Async =
        for
            ref    <- Signal.initRef("")
            seen   <- Channel.initUnscoped[String](16)
            fiber  <- Fiber.initUnscoped(ref.streamChanges.foreach(v => Abort.run[Closed](seen.put(v)).unit))
            misses <- Kyo.foreach(Chunk.from(1 to iterations)) { i =>
                val a                                          = s"a$i"
                val b                                          = s"b$i"
                def untilFinal: Unit < (Async & Abort[Closed]) =
                    seen.take.map(v => if v == b then () else untilFinal)
                for
                    _   <- ref.set(a)
                    _   <- ref.set(b)
                    got <- Abort.run[Timeout | Closed](Async.timeout(2.seconds)(untilFinal))
                yield if got.isSuccess then 0 else 1
                end for
            }
            _ <- fiber.interrupt
            _ <- seen.close
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
            // Causal fence on `waiters`, not a settle window: wait for the observer parked, do the same-value set(0), then fence
            // it is still parked before set(1). That orders any wakeup the same-value set could cause before set(1), so a spurious re-emission would land in `seen` before 1.
            for
                ref   <- Signal.initRef(0)
                seen  <- AtomicRef.init(Chunk.empty[Int])
                fiber <- Fiber.initUnscoped(ref.observe(recordValue(seen, _)))
                _     <- pollUntil(seen.get.map(_ == Chunk(0)))
                _     <- assertEventually(ref.waiters.map(_ == 1)) // observer parked for the next change
                _ <- ref.set(0)                                // same value: SignalRef does not notify, so the parked observer is not woken
                _ <- assertEventually(ref.waiters.map(_ == 1)) // still exactly one waiter: the same-value set injected no wakeup
                _ <- ref.set(1)                                // a real change wakes the observer
                _ <- pollUntil(seen.get.map(_.contains(1)))
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
                _      <- fiber.getResult // the observer has fully stopped before the change is published
                _      <- ref.set(1)
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
                fiber  <- Fiber.initUnscoped(parent.observe { _ =>
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
                // No value changes after set(3), so the last value's `f` runs to completion and `peak`
                // settles at 1. Reading `peak` once here races that `f` and can see 0; wait for it to
                // settle instead. A `peak` above 1 would mean two per-value scopes overlapped, which this
                // still catches: it would never settle at 1. A `peak` stuck at 0 means the observer never
                // ran `f` to the update, caught by the same wait rather than passing silently.
                _ <- assertEventually(peak.get.map(_ == 1))
                // The loop runs until interrupted, so a settled result now is a failure carrying the frame
                // that ended it. A poll rather than a get, because a healthy loop never settles.
                ended <- fiber.poll
                _     <- fiber.interrupt
            yield assert(ended.isEmpty, s"the observer loop ended before the test interrupted it: $ended")
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
                fiber    <- Fiber.initUnscoped(parent.observe { v =>
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
                fiber    <- Fiber.initUnscoped(parent.observe { _ =>
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
                fiber    <- Fiber.initUnscoped(parent.observe { _ =>
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

    "waiter registration" - {

        "interrupting a parked waiter deregisters it" in {
            val waiterCount = 20
            for
                ref    <- Signal.initRef(0)
                fibers <- Kyo.foreach(1 to waiterCount)(_ => Fiber.initUnscoped(ref.next))
                _      <- assertEventually(ref.waiters.map(_ == waiterCount))
                _      <- Kyo.foreachDiscard(fibers)(f => f.interrupt.andThen(f.getResult))
                after  <- ref.waiters
            yield assert(after == 0, s"$after waiters remain registered after all were interrupted")
            end for
        }

        "a parked observe does not accumulate waiters across repair ticks" in {
            val repairInterval = 1.second
            val ticks          = 20
            Clock.withTimeControl { control =>
                for
                    ref <- Signal.initRef(0)
                    // A SignalRef observes exactly and arms no repair timer, so the repairing loop runs over a raw
                    // view of the reference: it parks on the same next-change promise and races the timer.
                    repairing = Signal.initRaw[Int](
                        currentWith = [B, S] => f => ref.currentWith(f),
                        nextWith = [B, S] => f => ref.nextWith(f)
                    )
                    fiber <- Fiber.initUnscoped(repairing.observe(repairInterval)(_ => Kyo.unit))
                    // Fenced on the pending sleeper, not assertEventually: a retry's backoff is a virtual sleep that
                    // nothing here advances.
                    _ <- control.awaitPendingSleepers(1)
                    _ <- Kyo.foreachDiscard(1 to ticks) { _ =>
                        control.advance(repairInterval).andThen(control.awaitPendingSleepers(1))
                    }
                    parked <- ref.waiters
                    _      <- fiber.interrupt
                yield assert(parked == 1, s"observer holds $parked registrations after $ticks repair ticks")
            }
        }
    }

    "exact observation" - {
        "never loses the final value with no repair timer (SignalRef leaf)" in {
            observeNeverLosesFinalValue(useMap = false, iterations = 5000, repairInterval = Duration.Infinity)
                .map(lost => assert(lost == 0, s"SignalRef lost $lost / 5000 without repair"))
        }

        "never loses the final value with no repair timer (map over a leaf)" in {
            observeNeverLosesFinalValue(useMap = true, iterations = 5000, repairInterval = Duration.Infinity)
                .map(lost => assert(lost == 0, s"map lost $lost / 5000 without repair"))
        }

        "streamChanges never loses the final value under back-to-back writes" in {
            streamChangesNeverLosesFinalValue(iterations = 5000).map(lost => assert(lost == 0, s"streamChanges lost $lost / 5000"))
        }

        "delivers a write that lands while f runs, without repair (SignalRef leaf)" in {
            for
                ref     <- Signal.initRef(0)
                gate    <- Latch.init(1)
                started <- Latch.init(1)
                seen    <- AtomicRef.init(Chunk.empty[Int])
                fiber   <- Fiber.initUnscoped(ref.observe(Duration.Infinity) { v =>
                    recordValue(seen, v).andThen {
                        if v == 0 then started.release.andThen(gate.await) else (): Unit < Async
                    }
                })
                _  <- started.await
                _  <- ref.set(1)
                _  <- gate.release
                ok <- pollUntil(seen.get.map(_.contains(1)))
                _  <- fiber.interrupt
            yield assert(ok)
        }

        "delivers a write that lands while f runs, without repair (map over a leaf)" in {
            for
                ref     <- Signal.initRef(0)
                gate    <- Latch.init(1)
                started <- Latch.init(1)
                seen    <- AtomicRef.init(Chunk.empty[Int])
                sig = ref.map(_ + 10)
                fiber <- Fiber.initUnscoped(sig.observe(Duration.Infinity) { v =>
                    recordValue(seen, v).andThen {
                        if v == 10 then started.release.andThen(gate.await) else (): Unit < Async
                    }
                })
                _  <- started.await
                _  <- ref.set(1)
                _  <- gate.release
                ok <- pollUntil(seen.get.map(_.contains(11)))
                _  <- fiber.interrupt
            yield assert(ok)
        }

        "an idle observer on a leaf holds exactly one waiter across repair intervals" in {
            Clock.withTimeControl { control =>
                for
                    ref   <- Signal.initRef(0)
                    seen  <- AtomicRef.init(Chunk.empty[Int])
                    fiber <- Fiber.initUnscoped(ref.observe(10.millis)(recordValue(seen, _)))
                    _     <- assertEventually(seen.get.map(_.nonEmpty))
                    _     <- assertEventually(ref.waiters.map(_ == 1))
                    _     <- Kyo.foreachDiscard(Chunk.from(1 to 10))(_ => control.advance(10.millis))
                    w     <- ref.waiters
                    _     <- fiber.interrupt
                yield assert(w == 1)
            }
        }

        "the version advances once per distinct-value write" in {
            import AllowUnsafe.embrace.danger
            for
                ref <- Signal.initRef(0)
                v0  <- Sync.defer(ref.unsafe.version())
                _   <- ref.set(0)
                v1  <- Sync.defer(ref.unsafe.version())
                _   <- ref.set(1)
                v2  <- Sync.defer(ref.unsafe.version())
                _   <- ref.getAndUpdate(_ + 1)
                v3  <- Sync.defer(ref.unsafe.version())
            yield assert(v1 == v0 && v2 == v0 + 1 && v3 == v0 + 2)
            end for
        }
    }

    "observe with a baseline" - {
        "runs nothing while the current value equals the baseline" in {
            for
                ref    <- Signal.initRef(0)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(ref.observe(Present(0), Signal.defaultRepairInterval)(recordValue(seen, _)))
                _      <- assertEventually(ref.waiters.map(_ == 1))
                silent <- seen.get
                _      <- ref.set(1)
                _      <- pollUntil(seen.get.map(_.contains(1)))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(silent.isEmpty && result == Chunk(1))
        }

        "delivers a write that landed between processing the baseline and subscribing" in {
            for
                ref       <- Signal.initRef("a")
                processed <- ref.current
                _         <- ref.set("b")
                seen      <- AtomicRef.init(Chunk.empty[String])
                fiber     <- Fiber.initUnscoped(ref.observe(Present(processed), Signal.defaultRepairInterval)(recordValue(seen, _)))
                ok        <- pollUntil(seen.get.map(_.contains("b")))
                _         <- fiber.interrupt
            yield assert(ok)
        }

        "a map chain runs nothing while the source's image equals the baseline" in {
            for
                ref <- Signal.initRef(1)
                sig = ref.map(_ * 2)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(sig.observe(Present(2), Signal.defaultRepairInterval)(recordValue(seen, _)))
                _      <- assertEventually(ref.waiters.map(_ == 1))
                silent <- seen.get
                _      <- ref.set(2)
                _      <- pollUntil(seen.get.map(_.contains(4)))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(silent.isEmpty && result == Chunk(4))
        }

        "streamChanges with a baseline starts at the first value that differs from it" in {
            for
                ref    <- Signal.initRef(0)
                fiber  <- Fiber.initUnscoped(ref.streamChanges(Present(0)).take(2).run)
                _      <- assertEventually(ref.waiters.map(_ == 1))
                _      <- ref.set(1)
                _      <- assertEventually(ref.waiters.map(_ == 1))
                _      <- ref.set(2)
                values <- fiber.get
            yield assert(values == Chunk(1, 2))
        }

        "streamChanges with an absent baseline starts at the current value" in {
            for
                ref    <- Signal.initRef(0)
                fiber  <- Fiber.initUnscoped(ref.streamChanges(Absent).take(2).run)
                _      <- assertEventually(ref.waiters.map(_ == 1))
                _      <- ref.set(1)
                values <- fiber.get
            yield assert(values == Chunk(0, 1))
        }
    }

    "projected observation" - {
        "an observer of a map runs only when the map's own value changes" in {
            for
                ref  <- Signal.initRef(1)
                seen <- AtomicRef.init(Chunk.empty[Boolean])
                sig = ref.map(_ > 0)
                fiber  <- Fiber.initUnscoped(sig.observe(recordValue(seen, _)))
                _      <- assertEventually(ref.waiters.map(_ == 1))
                _      <- ref.set(2)
                _      <- assertEventually(ref.waiters.map(_ == 1))
                still  <- seen.get
                _      <- ref.set(-1)
                _      <- pollUntil(seen.get.map(_.size == 2))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(still == Chunk(true) && result == Chunk(true, false))
        }

        "an idle observer of a map chain holds exactly one waiter on the leaf" in {
            for
                ref  <- Signal.initRef(0)
                seen <- AtomicRef.init(Chunk.empty[Int])
                sig = ref.map(v => v).map(v => v).map(v => v)
                fiber <- Fiber.initUnscoped(sig.observe(10.millis)(recordValue(seen, _)))
                _     <- pollUntil(seen.get.map(_.nonEmpty))
                _     <- Async.sleep(100.millis)
                w     <- ref.waiters
                _     <- fiber.interrupt
            yield assert(w == 1, s"map chain left $w waiters on the leaf")
        }

        "a constant delivers once and holds its scope across would-be repair intervals" in {
            for
                seen     <- AtomicRef.init(Chunk.empty[Int])
                released <- AtomicRef.init(false)
                fiber    <- Fiber.initUnscoped(Signal.initConst(7).observe(10.millis) { v =>
                    Scope.ensure(released.set(true)).andThen(recordValue(seen, v))
                })
                _         <- pollUntil(seen.get.map(_.nonEmpty))
                _         <- Async.sleep(100.millis)
                values    <- seen.get
                duringRun <- released.get
                _         <- fiber.interrupt
                afterStop <- pollUntil(released.get)
            yield assert(values == Chunk(7) && !duringRun && afterStop)
        }
    }

    /** Observes `sig` with the clock frozen and runs `write` while `f` runs for the first value. Answers whether `f` then sees `expected`
      * before the observation arms a repair timer: an exact observation needs none, a repairing one finds nothing until the timer fires.
      * The observation runs inside `observeIn` in its own fiber, for a binding that does not cross a fork.
      */
    private def seesWriteDuringF[A](sig: Signal[A], expected: A, observeIn: (Unit < Async) => Unit < Async = identity)(write: Unit < Sync)(
        using
        Frame,
        CanEqual[A, A]
    ): Boolean < Async =
        Clock.withTimeControl { control =>
            for
                started   <- Latch.init(1)
                gate      <- Latch.init(1)
                delivered <- Latch.init(1)
                fiber     <- Fiber.initUnscoped(observeIn(sig.observe { v =>
                    if v == expected then delivered.release else started.release.andThen(gate.await)
                }))
                _  <- started.await
                _  <- write
                _  <- gate.release
                ok <- Async.race(delivered.await.andThen(true), control.awaitPendingSleeper(Signal.defaultRepairInterval).andThen(false))
                _  <- fiber.interrupt
            yield ok
        }

    /** A view of `backing` defined with `initRaw`, which therefore does not observe exactly. Without `wakes` its `nextWith` never fires,
      * so only a repair timer sees a write to `backing`.
      */
    private def rawView(backing: SignalRef[Int], wakes: Boolean = true)(using Frame): Signal[Int] =
        Signal.initRaw[Int](
            currentWith = [B, S] => f => backing.currentWith(f),
            nextWith = [B, S] => f => if wakes then backing.nextWith(f) else Async.never[B]
        )

    /** Observes `sig` with the clock frozen and runs `write` once `f` ran for the first value. Waits until `expected` is delivered on the
      * next repair tick, and answers whether it was not delivered before.
      */
    private def deliversOnlyOnRepairTick[A](sig: Signal[A], expected: A)(write: Unit < Sync)(using Frame, CanEqual[A, A]): Boolean < Async =
        Clock.withTimeControl { control =>
            for
                first     <- Latch.init(1)
                delivered <- Latch.init(1)
                fiber     <- Fiber.initUnscoped(sig.observe(v => if v == expected then delivered.release else first.release))
                _         <- first.await
                _         <- write
                _         <- control.awaitPendingSleeper(Signal.defaultRepairInterval)
                early     <- delivered.pending
                _         <- control.advance(Signal.defaultRepairInterval)
                _         <- delivered.await
                _         <- fiber.interrupt
            yield early == 1
        }

    "exact observation of combinators" - {

        "combineLatest delivers a write that lands while f runs, without repair" in {
            for
                a  <- Signal.initRef(0)
                b  <- Signal.initRef(0)
                ok <- seesWriteDuringF(a.combineLatest(b), (0, 1))(b.set(1))
            yield assert(ok)
        }

        "zip delivers a write that lands while f runs, without repair" in {
            for
                a  <- Signal.initRef(0)
                b  <- Signal.initRef(0)
                ok <- seesWriteDuringF(a.zip(b), (1, 0))(a.set(1))
            yield assert(ok)
        }

        "combineLatestAll delivers a write that lands while f runs, without repair" in {
            for
                a  <- Signal.initRef(0)
                b  <- Signal.initRef(0)
                c  <- Signal.initRef(0)
                ok <- seesWriteDuringF(Signal.combineLatestAll(Seq(a, b.map(_ * 10), c)), Chunk(0, 0, 1))(c.set(1))
            yield assert(ok)
        }

        "zipAll delivers a write that lands while f runs, without repair" in {
            for
                a  <- Signal.initRef(0)
                b  <- Signal.initRef(0)
                c  <- Signal.initRef(0)
                ok <- seesWriteDuringF(Signal.zipAll(Seq(a, b, c)), Chunk(0, 1, 0))(b.set(1))
            yield assert(ok)
        }

        "switchMap delivers a write to the inner signal that lands while f runs, without repair" in {
            for
                outer <- Signal.initRef(0)
                inner <- Signal.initRef(10)
                ok    <- seesWriteDuringF(outer.switchMap(_ => inner), 11)(inner.set(11))
            yield assert(ok)
        }

        "switchMap delivers a switch of the outer signal that lands while f runs, without repair" in {
            for
                outer  <- Signal.initRef(0)
                inner0 <- Signal.initRef(10)
                inner1 <- Signal.initRef(20)
                ok     <- seesWriteDuringF(outer.switchMap(v => if v == 0 then inner0 else inner1), 20)(outer.set(1))
            yield assert(ok)
        }

        "after a switch, switchMap follows the new inner signal and no longer the old one" in {
            for
                outer  <- Signal.initRef(0)
                inner0 <- Signal.initRef(10)
                inner1 <- Signal.initRef(20)
                seen   <- AtomicRef.init(Chunk.empty[Int])
                fiber  <- Fiber.initUnscoped(outer.switchMap(v => if v == 0 then inner0 else inner1).observe(recordValue(seen, _)))
                _      <- assertEventually(inner0.waiters.map(_ == 1))
                _      <- outer.set(1)
                _      <- assertEventually(Kyo.zip(seen.get, inner0.waiters, inner1.waiters).map((s, w0, w1) =>
                    s.size == 2 && w0 == 0 && w1 == 1
                ))
                _      <- inner0.set(11)
                _      <- inner1.set(21)
                _      <- assertEventually(seen.get.map(_.size == 3))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(result == Chunk(10, 20, 21))
        }

        "an idle observer of a combinator re-reads nothing across repair intervals" in {
            val intervals      = 3
            val wallClockDelay = 20.millis
            Clock.withTimeControl { control =>
                for
                    reads <- AtomicInt.init
                    a     <- Signal.initRef(0)
                    b     <- Signal.initRef(0)
                    first <- Latch.init(1)
                    counted = a.map { v =>
                        import AllowUnsafe.embrace.danger
                        discard(reads.unsafe.incrementAndGet())
                        v
                    }
                    fiber <- Fiber.initUnscoped(counted.combineLatest(b).observe(_ => first.release))
                    _     <- first.await
                    r0    <- reads.get
                    _     <- Kyo.foreachDiscard(1 to intervals)(_ => control.advance(Signal.defaultRepairInterval, wallClockDelay))
                    idle  <- reads.get
                    _     <- fiber.interrupt
                yield assert(
                    idle == r0,
                    s"an idle observer read its sources ${idle - r0} more times over $intervals repair intervals, expected none"
                )
                end for
            }
        }

        "an idle observer of a combinator holds one waiter per source, and none once interrupted" in {
            val writes = 50
            for
                a     <- Signal.initRef(0)
                b     <- Signal.initRef(0)
                seen  <- AtomicRef.init(Chunk.empty[(Int, Int)])
                fiber <- Fiber.initUnscoped(a.combineLatest(b).observe(recordValue(seen, _)))
                _     <- assertEventually(Kyo.zip(a.waiters, b.waiters).map((wa, wb) => wa == 1 && wb == 1))
                _ <- Kyo.foreachDiscard(1 to writes)(i => a.set(i).andThen(assertEventually(seen.get.map(_.lastMaybe.exists(_ == (i, 0))))))
                _ <- assertEventually(Kyo.zip(a.waiters, b.waiters).map((wa, wb) => wa == 1 && wb == 1))
                _ <- fiber.interrupt
                _ <- fiber.getResult
                after <- Kyo.zip(a.waiters, b.waiters)
            yield assert(after == (0, 0), s"waiters after interrupt: $after")
            end for
        }

        "an interrupt while a change is in flight leaves no waiter" in {
            val rounds = 200
            Kyo.foreachDiscard(1 to rounds) { i =>
                for
                    a     <- Signal.initRef(0)
                    b     <- Signal.initRef(0)
                    first <- Latch.init(1)
                    fiber <- Fiber.initUnscoped(a.combineLatest(b).observe(_ => first.release))
                    _     <- first.await
                    _     <- assertEventually(Kyo.zip(a.waiters, b.waiters).map((wa, wb) => wa == 1 && wb == 1))
                    write <- Fiber.initUnscoped(if i % 2 == 0 then a.set(1) else b.set(1))
                    _     <- fiber.interrupt
                    _     <- fiber.getResult
                    after <- Kyo.zip(a.waiters, b.waiters)
                    _     <- write.get
                yield assert(after == (0, 0), s"round $i: waiters after interrupt: $after")
            }.andThen(succeed)
        }

        "combineLatest of a source with itself holds a registration per read, released by a change and by an interrupt" in {
            val writes = 20
            for
                a     <- Signal.initRef(0)
                seen  <- AtomicRef.init(Chunk.empty[(Int, Int)])
                fiber <- Fiber.initUnscoped(a.combineLatest(a).observe(recordValue(seen, _)))
                _     <- assertEventually(a.waiters.map(_ == 2))
                _     <- Kyo.foreachDiscard(1 to writes)(a.set(_))
                _     <- assertEventually(seen.get.map(_.lastMaybe.exists(_ == (writes, writes))))
                _     <- assertEventually(a.waiters.map(_ == 2))
                _     <- fiber.interrupt.andThen(fiber.getResult)
                after <- a.waiters
            yield assert(after == 0, s"waiters after interrupt: $after")
            end for
        }

        "combineLatestAll over a source and a map of it holds a registration per read, released by a change and by an interrupt" in {
            val writes = 20
            for
                b     <- Signal.initRef(0)
                seen  <- AtomicRef.init(Chunk.empty[Chunk[Int]])
                fiber <- Fiber.initUnscoped(Signal.combineLatestAll(Seq(b, b.map(_ * 10))).observe(recordValue(seen, _)))
                _     <- assertEventually(b.waiters.map(_ == 2))
                _     <- Kyo.foreachDiscard(1 to writes)(b.set(_))
                _     <- assertEventually(seen.get.map(_.lastMaybe.exists(_ == Chunk(writes, writes * 10))))
                _     <- assertEventually(b.waiters.map(_ == 2))
                _     <- fiber.interrupt.andThen(fiber.getResult)
                after <- b.waiters
            yield assert(after == 0, s"waiters after interrupt: $after")
            end for
        }

        "switchMap keeps delivering when its inner signal switches between a ref and one defined by initRaw" in {
            for
                outer   <- Signal.initRef(0)
                inner   <- Signal.initRef(10)
                backing <- Signal.initRef(20)
                raw = rawView(backing)
                seen  <- AtomicRef.init(Chunk.empty[Int])
                fiber <- Fiber.initUnscoped(outer.switchMap(v => if v == 0 then inner else raw).observe(recordValue(seen, _)))
                _     <- assertEventually(inner.waiters.map(_ == 1))
                _     <- inner.set(11)
                _     <- assertEventually(seen.get.map(_.lastMaybe.exists(_ == 11)))
                _     <- outer.set(1)
                _     <- assertEventually(seen.get.map(_.lastMaybe.exists(_ == 20)))
                _     <- assertEventually(backing.waiters.map(_ == 1))
                _     <- backing.set(21)
                _     <- assertEventually(seen.get.map(_.lastMaybe.exists(_ == 21)))
                _     <- outer.set(0)
                _     <- assertEventually(seen.get.map(_.lastMaybe.exists(_ == 11)))
                _     <- assertEventually(inner.waiters.map(_ == 1))
                _     <- inner.set(12)
                _     <- assertEventually(seen.get.map(_.lastMaybe.exists(_ == 12)))
                _     <- fiber.interrupt
                _     <- fiber.getResult
                // The repairing loop's `Async.race` may release its waiters only after the observer's result is set.
                _      <- assertEventually(Kyo.zip(outer.waiters, inner.waiters, backing.waiters).map(_ == (0, 0, 0)))
                result <- seen.get
            yield assert(result == Chunk(10, 11, 20, 21, 11, 12), s"seen $result")
        }

        "a combinator with a source defined by initRaw still reconciles on the repair timer" in {
            for
                a       <- Signal.initRef(0)
                backing <- Signal.initRef(0)
                ok      <- deliversOnlyOnRepairTick(a.combineLatest(rawView(backing, wakes = false)), (0, 1))(backing.set(1))
            yield assert(ok, "delivered before the repair tick")
        }

        "streamChanges on a combineLatest emits a write that lands while an element is processed" in {
            for
                a         <- Signal.initRef(0)
                b         <- Signal.initRef(0)
                started   <- Latch.init(1)
                gate      <- Latch.init(1)
                delivered <- Latch.init(1)
                fiber     <- Fiber.initUnscoped(a.combineLatest(b).streamChanges.foreach { v =>
                    if v == (0, 0) then started.release.andThen(gate.await) else delivered.release
                })
                _       <- started.await
                _       <- a.set(1)
                _       <- gate.release
                _       <- assertEventually(b.waiters.map(_ > 0))
                pending <- delivered.pending
                _       <- fiber.interrupt
            yield assert(pending == 0, "the write was not emitted: the stream waits on the next change")
        }

        "streamChanges on a zip still waits for both inputs to change" in {
            for
                a      <- Signal.initRef(0)
                b      <- Signal.initRef(0)
                seen   <- AtomicRef.init(Chunk.empty[(Int, Int)])
                fiber  <- Fiber.initUnscoped(a.zip(b).streamChanges.foreach(recordValue(seen, _)))
                _      <- assertEventually(Kyo.zip(seen.get, a.waiters, b.waiters).map((s, wa, wb) => s.nonEmpty && wa == 1 && wb == 1))
                _      <- a.set(1)
                _      <- assertEventually(Kyo.zip(a.waiters, b.waiters).map(_ == (0, 1)))
                early  <- seen.get
                _      <- b.set(1)
                _      <- assertEventually(seen.get.map(_.size > 1))
                result <- seen.get
                _      <- fiber.interrupt
            yield assert(early == Chunk((0, 0)) && result == Chunk((0, 0), (1, 1)), s"emitted $early, then $result")
        }
    }

    "withLocal" - {

        val chosen = Local.init[Signal[Int]](Signal.initConst(-1))

        "reads the signal chosen by the local where it is read" in {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef(2)
                sig = Signal.withLocal(chosen)(identity)
                outside <- sig.current
                underA  <- chosen.let(a)(sig.current)
                underB  <- chosen.let(b)(sig.current)
            yield assert((outside, underA, underB) == (-1, 1, 2))
        }

        "over a ref delivers a write that lands while f runs, without repair" in {
            for
                a  <- Signal.initRef(0)
                ok <- chosen.let(a)(seesWriteDuringF(Signal.withLocal(chosen)(_.map(_ + 1)), 2)(a.set(1)))
            yield assert(ok)
        }

        "inside a combinator delivers a write that lands while f runs, without repair" in {
            for
                a  <- Signal.initRef(1)
                b  <- Signal.initRef(0)
                ok <- chosen.let(a)(seesWriteDuringF(Signal.withLocal(chosen)(identity).combineLatest(b), (1, 1))(b.set(1)))
            yield assert(ok)
        }

        "an observation holds a waiter only on the signal its own local chose" in {
            for
                a     <- Signal.initRef(0)
                b     <- Signal.initRef(0)
                seenA <- AtomicRef.init(Chunk.empty[Int])
                seenB <- AtomicRef.init(Chunk.empty[Int])
                sig = Signal.withLocal(chosen)(identity)
                fa    <- Fiber.initUnscoped(chosen.let(a)(sig.observe(recordValue(seenA, _))))
                _     <- assertEventually(Kyo.zip(a.waiters, b.waiters).map(_ == (1, 0)))
                fb    <- Fiber.initUnscoped(chosen.let(b)(sig.observe(recordValue(seenB, _))))
                _     <- assertEventually(Kyo.zip(a.waiters, b.waiters).map(_ == (1, 1)))
                _     <- a.set(1)
                _     <- b.set(2)
                _     <- assertEventually(Kyo.zip(seenA.get, seenB.get).map(_ == (Chunk(0, 1), Chunk(0, 2))))
                _     <- fa.interrupt.andThen(fa.getResult)
                _     <- fb.interrupt.andThen(fb.getResult)
                after <- Kyo.zip(a.waiters, b.waiters)
            yield assert(after == (0, 0), s"waiters after interrupt: $after")
        }

        "over a non-inheritable local bound in the observing fiber delivers a write that lands while f runs, without repair" in {
            val pinned = Local.initNoninheritable[Signal[Int]](Signal.initConst(-1))
            for
                a  <- Signal.initRef(0)
                ok <- seesWriteDuringF(Signal.withLocal(pinned)(identity), 1, pinned.let(a)(_))(a.set(1))
            yield assert(ok)
            end for
        }

        "over a signal defined by initRaw still reconciles on the repair timer" in {
            for
                backing <- Signal.initRef(0)
                ok      <- chosen.let(rawView(backing, wakes = false))(deliversOnlyOnRepairTick(
                    Signal.withLocal(chosen)(identity),
                    1
                )(backing.set(1)))
            yield assert(ok, "delivered before the repair tick")
        }
    }

end SignalTest
