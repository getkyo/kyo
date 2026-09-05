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

    private def awaitValue(ref: AtomicRef[String], target: String, maxTries: Int)(using Frame): Boolean < Async =
        Loop.indexed { i =>
            if i >= maxTries then Loop.done(false)
            else ref.get.map(v => if v == target then Loop.done(true) else Async.sleep(1.millis).andThen(Loop.continue))
        }

    // Leaf and `map`-over-leaf `observe` use the repairing path (the exact register-before-read override was removed
    // because it miscompiled on Scala Native; see SignalRef in Signal.scala). The guarantee is that the final value is
    // never lost: a write that lands in the read/register window is reconciled within `repairInterval`. Drive
    // back-to-back set(a);set(b) under an explicit short repairInterval (50ms) and await the final value under a generous
    // hang-guard (10000 x ~1ms polls): the poll returns the instant the value arrives, so the budget only bounds a
    // genuinely lost value. A tighter budget could expire while a starved repair fiber was merely late, miscounting a
    // delivered value as lost; only a returned count > 0 after the hang-guard means a value was actually lost.
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
                    got <- awaitValue(lastSeen, b, 10000)
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
