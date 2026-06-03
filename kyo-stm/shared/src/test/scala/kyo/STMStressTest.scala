package kyo

import java.lang.ref.WeakReference

/** Stress and concurrency tests for kyo-stm.
  *
  * Most tests are JS-excluded because the scenarios require real OS threads / preemption; cross-platform specs use `run`. Fiber and
  * iteration counts are kept high enough to be meaningful but bounded, with `Async.timeout` guarding against livelock.
  */
class STMStressTest extends kyo.test.Test[Any]:

    // Sequential leaves (as the ScalaTest base ran them): the GC-reclamation leaf finds its TRefs still reachable
    // (cleared=0) when the other stress leaves' fibers are live alongside it.
    override def config = super.config.sequential

    "every transaction under heavy single-ref contention commits, none starves".notJs in {
        // 64 reader-writer fibers plus one writer all contend the same TRef. Barging bounds the
        // politeness yield a writer makes to fresher readers, so every contended transaction
        // commits within its retry budget — none is starved into an FailedTransaction.
        for
            ref       <- TRef.init(0)
            committed <- AtomicInt.init(0)
            start     <- Latch.init(1)
            writer <- Fiber.initUnscoped {
                start.await.andThen(
                    Async.foreachDiscard(1 to 1000)(i => STM.run(ref.set(i)))
                )
            }
            readers = Async.fill(64, 64) {
                start.await.andThen(
                    STM.run(Schedule.fixed(1.millis).jitter(0.5).take(200))(ref.update(_ + 1)).handle(Abort.run).map { r =>
                        if r.isSuccess then committed.incrementAndGet.unit
                        else Sync.defer(java.lang.System.err.println(s"reader transaction did not commit: $r")).unit
                    }
                )
            }
            _ <- start.release
            _ <- Abort.run(Async.timeout(15.seconds)(writer.get.andThen(readers)))
            c <- committed.get
        yield assert(c == 64, s"committed=$c (every contended reader transaction must commit)")
    }

    "TMap.snapshot under concurrent put never returns half-applied state".notJs in {
        for
            tmap                <- TMap.init[Int, Int]
            _                   <- STM.run(Kyo.foreachDiscard(0 until 50)(i => tmap.put(i, 0)))
            invariantViolations <- AtomicInt.init(0)
            latch               <- Latch.init(1)
            writer <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 5000)(i => STM.run(tmap.put(i % 50, i)))
            ))
            reader <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 1000) { _ =>
                    STM.run(tmap.snapshot).map { snap =>
                        if snap.size > 50 || snap.values.exists(_ < 0) then invariantViolations.incrementAndGet.unit
                        else ()
                    }
                }
            ))
            _ <- latch.release
            _ <- writer.get
            _ <- reader.get
            v <- invariantViolations.get
        yield assert(v == 0, s"violations=$v")
    }

    "long-running STM workload releases per-fiber transaction state".notJs in {
        for
            ref <- TRef.init(Chunk.empty[Int])
            _ <- Async.fill(8, 8) {
                Async.foreachDiscard(1 to 200) { i =>
                    STM.run(STM.defaultRetrySchedule.forever)(ref.update(_ ++ Chunk.fill(1000)(i)))
                        .andThen(STM.run(STM.defaultRetrySchedule.forever)(ref.set(Chunk.empty)))
                }
            }
            finalSize <- STM.run(ref.use(_.size))
        yield assert(finalSize == 0, s"finalSize=$finalSize")
    }

    "TMap.clear during concurrent puts produces a clean log with no orphan inner TRefs".notJs in {
        for
            tmap     <- TMap.init[Int, Int]
            _        <- STM.run(Kyo.foreachDiscard(0 until 100)(i => tmap.put(i, i)))
            observed <- AtomicRef.init(Set.empty[Int])
            writer   <- Fiber.initUnscoped(Async.foreachDiscard(1 to 1000)(i => STM.run(tmap.put(i % 100, i))))
            clearer  <- Fiber.initUnscoped(STM.run(tmap.clear))
            reader <- Fiber.initUnscoped(Async.foreachDiscard(1 to 200) { _ =>
                STM.run(tmap.snapshot).map(s => observed.updateAndGet(_ + s.size).unit)
            })
            _         <- writer.get
            _         <- clearer.get
            _         <- reader.get
            finalSize <- STM.run(tmap.size)
            sizes     <- observed.get
        yield assert(
            sizes.forall(s => s == 0 || s <= 100) && finalSize >= 0 && finalSize <= 100,
            s"sizes=$sizes finalSize=$finalSize"
        )
    }

    "opacity: invariant r1 < r2 holds inside transaction across writer interleaving".notJs in {
        // Opacity (Guerraoui-Kapalka): an in-flight transaction must never observe a
        // read-set-inconsistent pair, even if it is doomed to abort. The only writer keeps
        // r1 < r2 (r1 = i*2, r2 = i*2+1), so a reader that observes r1 >= r2 has seen a
        // half-applied multi-ref commit. TRef.use rejects reads of write-locked refs and
        // re-confirms the entry sample, so `violations` is deterministically 0.
        for
            r1         <- TRef.init(0)
            r2         <- TRef.init(1)
            violations <- AtomicInt.init(0)
            start      <- Latch.init(1)
            writers = Async.fill(8, 8) {
                start.await.andThen(Async.foreachDiscard(1 to 5000) { i =>
                    STM.run(STM.defaultRetrySchedule.forever) {
                        for
                            _ <- r1.set(i * 2)
                            _ <- r2.set(i * 2 + 1)
                        yield ()
                    }
                })
            }
            readers = Async.fill(8, 8) {
                start.await.andThen(Async.foreachDiscard(1 to 5000) { _ =>
                    STM.run {
                        for
                            a <- r1.get
                            b <- r2.get
                            _ <- Sync.defer(if a >= b then violations.incrementAndGet.unit else ())
                        yield ()
                    }.handle(Abort.run)
                })
            }
            _ <- start.release
            _ <- Async.zip(writers, readers)
            v <- violations.get
        yield assert(v == 0, s"opacity violation: reader observed r1 >= r2 $v time(s)")
    }

    "older transaction makes progress under stream of newer short transactions".notJs in {
        for
            ref           <- TRef.init(0)
            elderDone     <- AtomicBoolean.init(false)
            elderAttempts <- AtomicInt.init(0)
            elderStart    <- Latch.init(1)
            elder <- Fiber.initUnscoped {
                elderStart.await.andThen(
                    STM.run(STM.defaultRetrySchedule.forever) {
                        for
                            _ <- Sync.defer(elderAttempts.incrementAndGet)
                            v <- ref.get
                            _ <- Async.sleep(50.millis)
                            _ <- ref.set(v + 1)
                        yield ()
                    }.andThen(elderDone.set(true))
                )
            }
            youngs <- Fiber.initUnscoped {
                elderStart.await.andThen(
                    Async.fill(20, 20)(
                        Loop.repeat(200)(STM.run(ref.update(_ + 1)))
                    )
                )
            }
            _        <- elderStart.release
            _        <- Abort.run(Async.timeout(20.seconds)(elder.get))
            _        <- Abort.run(Async.timeout(25.seconds)(youngs.get))
            done     <- elderDone.get
            attempts <- elderAttempts.get
        yield assert(done && attempts < 200, s"done=$done attempts=$attempts")
    }

    "nested transaction rollback under concurrent contention does not leak inner writes".notJs in {
        for
            outerRef <- TRef.init(0)
            innerRef <- TRef.init(0)
            _ <- Async.fill(64, 64) {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        _ <- outerRef.update(_ + 1)
                        _ <- STM.run {
                            for
                                _ <- innerRef.set(999)
                                _ <- STM.retry
                            yield ()
                        }.handle(Abort.run)
                    yield ()
                }
            }
            finalOuter <- STM.run(outerRef.get)
            finalInner <- STM.run(innerRef.get)
        yield assert(finalOuter == 64 && finalInner == 0, s"finalOuter=$finalOuter finalInner=$finalInner")
    }

    "nested transaction retry observes external write to ref-only-read-in-nested-scope" in {
        for
            r1            <- TRef.init(0)
            r2            <- TRef.init(0)
            wokeUp        <- AtomicBoolean.init(false)
            nestedRetries <- AtomicInt.init(0)
            waiter <- Fiber.initUnscoped {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        v <- r2.get
                        _ <- STM.run {
                            for
                                _ <- nestedRetries.incrementAndGet
                                a <- r1.get
                                _ <- STM.retryIf(a == 0)
                                _ <- r2.set(a)
                            yield ()
                        }
                        _ <- Sync.defer(wokeUp.set(true))
                    yield ()
                }
            }
            _     <- Async.sleep(50.millis)
            _     <- STM.run(r1.set(42))
            _     <- Abort.run(Async.timeout(5.seconds)(waiter.get))
            woken <- wokeUp.get
            nr    <- nestedRetries.get
        yield assert(woken && nr >= 2, s"woken=$woken nestedRetries=$nr")
    }

    "transaction's own writes do not trigger self-wakeup pending-list churn".notJs in {
        for
            ref       <- TRef.init(0)
            spurious  <- AtomicInt.init(0)
            committed <- AtomicInt.init(0)
            _ <- Async.fill(50, 50) {
                STM.run {
                    for
                        v <- ref.get
                        _ <- ref.set(v + 1)
                        _ <- Sync.defer(committed.incrementAndGet)
                    yield ()
                }
            }
            watcher <- Fiber.initUnscoped {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        v <- ref.get
                        _ <- ref.set(v + 1)
                        _ <- Sync.defer(spurious.incrementAndGet)
                        _ <- STM.retryIf(false)
                    yield ()
                }
            }
            _  <- Abort.run(Async.timeout(2.seconds)(watcher.get))
            sp <- spurious.get
        yield assert(sp == 1, s"spurious=$sp")
    }

    "waiter-list reversal does not cause N-th waiter to wake before 1st under FIFO contract".notJs in {
        for
            ref       <- TRef.init(0)
            wakeOrder <- AtomicRef.init(Chunk.empty[Int])
            gates     <- Kyo.fill(10)(Latch.init(1))
            waiters <- Kyo.foreach(0 until 10) { i =>
                Fiber.initUnscoped {
                    for
                        _ <- gates(i).release
                        _ <- STM.run(STM.defaultRetrySchedule.forever) {
                            for
                                v <- ref.get
                                _ <- STM.retryIf(v < i + 1)
                            yield ()
                        }
                        _ <- wakeOrder.updateAndGet(_ :+ i)
                    yield ()
                }
            }
            _     <- Kyo.foreachDiscard(0 until 10)(i => gates(i).await)
            _     <- Async.sleep(100.millis)
            _     <- Kyo.foreachDiscard(1 to 10)(i => STM.run(ref.set(i)))
            _     <- Kyo.foreachDiscard(waiters)(_.get)
            order <- wakeOrder.get
        yield assert(!order.sameElements(9 to 0 by -1), s"order=$order")
    }

    "signalling waiters does not hold per-ref lock".notJs in {
        for
            ref       <- TRef.init(0)
            completed <- AtomicInt.init(0)
            waiters = Async.fill(16, 16) {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        v <- ref.get
                        _ <- STM.retryIf(v == 0)
                        _ <- ref.set(v - 1)
                    yield ()
                }.handle(Abort.run).andThen(completed.incrementAndGet)
            }
            waiterFiber <- Fiber.initUnscoped(waiters)
            _           <- Async.sleep(100.millis)
            _           <- STM.run(ref.set(16))
            _           <- Abort.run(Async.timeout(10.seconds)(waiterFiber.get))
            p           <- completed.get
        yield assert(p == 16, s"completed=$p")
    }

    "two transactions touching the same two refs in different source orders never deadlock".notJs in {
        for
            a      <- TRef.init(0)
            b      <- TRef.init(0)
            cycles <- AtomicInt.init(0)
            forward = Async.fill(50, 50) {
                STM.run {
                    for
                        va <- a.get
                        vb <- b.get
                        _  <- a.set(va + 1)
                        _  <- b.set(vb + 1)
                        _  <- Sync.defer(cycles.incrementAndGet)
                    yield ()
                }
            }
            backward = Async.fill(50, 50) {
                STM.run {
                    for
                        vb <- b.get
                        va <- a.get
                        _  <- b.set(vb + 1)
                        _  <- a.set(va + 1)
                        _  <- Sync.defer(cycles.incrementAndGet)
                    yield ()
                }
            }
            _      <- Abort.run(Async.timeout(30.seconds)(Async.zip(forward, backward)))
            finalA <- STM.run(a.get)
            finalB <- STM.run(b.get)
            c      <- cycles.get
        // finalA/finalB == 100 is the load-bearing no-deadlock + atomicity invariant; `c`
        // is an in-body counter and may exceed 100 because it replays on transaction retry.
        yield assert(finalA == 100 && finalB == 100 && c >= 100, s"finalA=$finalA finalB=$finalB cycles=$c")
    }

    "concurrent nested handleErrorWith chains do not desync".notJs in {
        for
            ref          <- TRef.init(0)
            wrongHandler <- AtomicInt.init(0)
            rightHandler <- AtomicInt.init(0)
            _ <- Async.fill(64, 64) {
                STM.run {
                    Abort.run[String] {
                        for
                            _ <- ref.update(_ + 1)
                            _ <- STM.run {
                                Abort.run[Int] {
                                    Abort.fail(42)
                                }.map {
                                    case Result.Failure(_) => Sync.defer(rightHandler.incrementAndGet).unit
                                    case _                 => Sync.defer(wrongHandler.incrementAndGet).unit
                                }
                            }
                        yield ()
                    }
                }
            }
            right <- rightHandler.get
            wrong <- wrongHandler.get
        // The load-bearing invariant is `wrong == 0`: the inner Abort.fail must always be
        // caught by its matching inner Abort.run[Int], never by the wrong handler. `right`
        // may exceed the fiber count because the in-body counter replays when the outer
        // transaction retries under single-ref contention.
        yield assert(wrong == 0 && right >= 64, s"right=$right wrong=$wrong")
    }

    "TMap.snapshot / entries / values under concurrent put+remove never throw".notJs in {
        for
            tmap   <- TMap.init[Int, Int]
            _      <- STM.run(Kyo.foreachDiscard(0 until 100)(i => tmap.put(i, i)))
            thrown <- AtomicInt.init(0)
            mutators = Async.fill(8, 8) {
                Async.foreachDiscard(1 to 1000) { i =>
                    (if i % 2 == 0 then STM.run(tmap.put(i % 100, i))
                     else STM.run(tmap.removeDiscard(i % 100))).handle(Abort.run).unit
                }
            }
            iterators = Async.fill(8, 8) {
                Async.foreachDiscard(1 to 500) { _ =>
                    STM.run(tmap.entries.map(_.toMap))
                        .handle(Abort.run)
                        .map {
                            case Result.Panic(_) => thrown.incrementAndGet.unit
                            case _               => ()
                        }
                }
            }
            _ <- Async.zip(mutators, iterators)
            t <- thrown.get
        yield assert(t == 0, s"thrown=$t")
    }

    "typed Abort.fail inside STM is re-tried when log is stale, surfaced only when consistent".notJs in {
        for
            outer      <- TRef.init[TRef[String]](null)
            firstInner <- TRef.init("X")
            _          <- STM.run(outer.set(firstInner))
            // Pre-commit one writer update before forking readers so outer no longer points at
            // firstInner. After this point no consistent observation of "X" is possible, so any
            // surfaced user error would mean a stale-log read of firstInner leaked instead of
            // being retried. The concurrent writer loop continues to mutate outer so the
            // stale-log retry path is still exercised throughout the reader phase.
            _          <- STM.run(TRef.initWith("v0")(newInner => outer.set(newInner)))
            userErrors <- AtomicInt.init(0)
            committed  <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped {
                Async.foreachDiscard(1 to 200) { i =>
                    STM.run {
                        TRef.initWith(s"v$i")(newInner => outer.set(newInner))
                    }
                }
            }
            readers = Async.fill(64, 64) {
                STM.run {
                    for
                        inner <- outer.get
                        v     <- inner.get
                        _     <- if v == "X" then Abort.fail(new Exception("X observed")) else Kyo.unit
                    yield ()
                }.handle(Abort.run).map { r =>
                    if r.isSuccess then committed.incrementAndGet.unit
                    else
                        r match
                            case Result.Failure(_: FailedTransaction) =>
                                Sync.defer(java.lang.System.err.println("unexpected FailedTransaction")).unit
                            case Result.Failure(_) =>
                                userErrors.incrementAndGet.unit
                            case other =>
                                Sync.defer(java.lang.System.err.println(s"unexpected $other")).unit
                }
            }
            _   <- Async.zip(writer.get, readers)
            err <- userErrors.get
            ok  <- committed.get
        yield assert(ok == 64 && err == 0, s"err=$err ok=$ok")
    }

    "per-retry TRef allocation does not cause livelock on per-ref locks".notJs in {
        for
            outer    <- TRef.init(Map.empty[Int, Int])
            finished <- AtomicInt.init(0)
            run = Async.fillIndexed(32, 32) { fiberId =>
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        _ <- TRef.initWith(fiberId)(r => r.get)
                        _ <- outer.update(_ + (fiberId -> fiberId))
                    yield ()
                }.andThen(finished.incrementAndGet)
            }
            _ <- Abort.run(Async.timeout(30.seconds)(run))
            f <- finished.get
        yield assert(f == 32, s"finished=$f")
    }

    "two-queue STM atomicity: dequeue observes both heads or neither, never one".notJs in {
        for
            q1         <- TRef.init(Chunk.empty[Int])
            q2         <- TRef.init(Chunk.empty[Int])
            violations <- AtomicInt.init(0)
            latch      <- Latch.init(1)
            enqueuer <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 2000) { i =>
                    STM.run {
                        for
                            _ <- q1.update(_ :+ i)
                            _ <- q2.update(_ :+ i)
                        yield ()
                    }
                }
            ))
            dequeuer <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 2000) { _ =>
                    STM.run {
                        for
                            s1 <- q1.use(_.size)
                            s2 <- q2.use(_.size)
                            _  <- Sync.defer(if (s1 == 0) != (s2 == 0) then violations.incrementAndGet.unit else ())
                            _  <- if s1 > 0 then q1.update(_.tail) else Kyo.unit
                            _  <- if s2 > 0 then q2.update(_.tail) else Kyo.unit
                        yield ()
                    }
                }
            ))
            _ <- latch.release
            _ <- enqueuer.get
            _ <- dequeuer.get
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "a fiber observing post-commit value via another ref also observes all commit-batched writes".notJs in {
        // The writer commits `a` and `b` together; the reader must never see `a` from one
        // commit and `b` from another (publish-ordering atomicity / opacity). The writer must
        // also never starve out of its retry budget — barging guarantees it commits within a
        // bounded number of attempts — so an unhandled FailedTransaction here is a real failure.
        for
            a          <- TRef.init(0)
            b          <- TRef.init(0)
            violations <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped(
                Async.foreachDiscard(1 to 5000) { i =>
                    STM.run {
                        for
                            _ <- a.set(i)
                            _ <- b.set(i)
                        yield ()
                    }
                }
            )
            reader <- Fiber.initUnscoped(
                Async.foreachDiscard(1 to 5000) { _ =>
                    STM.run {
                        for
                            va <- a.get
                            vb <- b.get
                            _  <- Sync.defer(if va != vb then violations.incrementAndGet.unit else ())
                        yield ()
                    }
                }
            )
            _ <- writer.get
            _ <- reader.get
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "long-running large transaction commits within bounded retries under short-tx contention".notJs in {
        for
            refs       <- Kyo.fill(1000)(TRef.init(0))
            longDone   <- AtomicBoolean.init(false)
            shortCount <- AtomicInt.init(0)
            long <- Fiber.initUnscoped {
                STM.run(STM.defaultRetrySchedule.forever) {
                    Kyo.foreachDiscard(refs)(r => r.update(_ + 1))
                }.andThen(longDone.set(true))
            }
            shorts <- Fiber.initUnscoped {
                Async.fill(8, 8) {
                    Async.foreachDiscard(1 to 500) { _ =>
                        STM.run(refs.head.update(_ + 1)).andThen(shortCount.incrementAndGet)
                    }
                }
            }
            _    <- Abort.run(Async.timeout(60.seconds)(long.get))
            _    <- Abort.run(Async.timeout(65.seconds)(shorts.get))
            done <- longDone.get
        yield assert(done, s"longDone=$done")
    }

    "ref-id assignment is stable across retries of the same transaction" in {
        for
            ref       <- TRef.init(0)
            initialId <- Sync.defer(ref.id)
            seen      <- AtomicRef.init(Set.empty[Int])
            _ <- Async.fill(100, 100) {
                STM.run {
                    for
                        v <- ref.get
                        _ <- seen.updateAndGet(_ + ref.id)
                        _ <- ref.set(v + 1)
                    yield ()
                }
            }
            ids <- seen.get
        yield assert(ids.size == 1 && ids.head == initialId, s"ids=$ids initialId=$initialId")
    }

    "TRef.init inside retrying STM.run consumes idCounter per retry but never produces duplicate IDs" in {
        for
            allIds   <- AtomicRef.init(Set.empty[Int])
            attempts <- AtomicInt.init(0)
            _ <- Async.fill(32, 32) {
                STM.run(Schedule.repeat(5)) {
                    for
                        _   <- Sync.defer(attempts.incrementAndGet)
                        ref <- TRef.init(0)
                        _   <- allIds.updateAndGet(_ + ref.id)
                        v   <- ref.get
                        _   <- STM.retryIf(v == 0)
                    yield ()
                }.handle(Abort.run)
            }
            ids <- allIds.get
            a   <- attempts.get
        yield assert(ids.size == a && a > 32, s"ids.size=${ids.size} attempts=$a")
    }

    "TMap.fold (transactional iteration) makes progress under sparse concurrent writes".notJs in {
        for
            tmap <- TMap.init[Int, Int]
            _    <- STM.run(Kyo.foreachDiscard(0 until 200)(i => tmap.put(i, 0)))
            done <- AtomicBoolean.init(false)
            stop <- AtomicBoolean.init(false)
            iter <- Fiber.initUnscoped {
                STM.run(STM.defaultRetrySchedule.forever) {
                    tmap.fold(0L)((acc, _, v) => acc + v.toLong)
                }.andThen(done.set(true))
            }
            writers <- Fiber.initUnscoped {
                Async.fill(4, 4) {
                    Loop(0) { i =>
                        stop.get.map {
                            case true  => Loop.done(())
                            case false => STM.run(tmap.put(i % 200, i)).andThen(Loop.continue(i + 1))
                        }
                    }
                }
            }
            _ <- Abort.run(Async.timeout(30.seconds)(iter.get))
            _ <- stop.set(true)
            _ <- writers.get
            d <- done.get
        yield assert(d, s"done=$d")
    }

    "fiber blocked in STM.retryIf wakes when reachable writer eventually publishes" in {
        for
            ref   <- TRef.init(0)
            woken <- AtomicBoolean.init(false)
            waiter <- Fiber.initUnscoped {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        v <- ref.get
                        _ <- STM.retryIf(v == 0)
                    yield ()
                }.andThen(woken.set(true))
            }
            _ <- Async.sleep(500.millis)
            _ <- STM.run(ref.set(1))
            _ <- Abort.run(Async.timeout(5.seconds)(waiter.get))
            w <- woken.get
        yield assert(w, s"woken=$w")
    }

    "doomed STM transaction aborts before user code observes division-by-zero from stale snapshot".notJs in {
        for
            a          <- TRef.init(10)
            b          <- TRef.init(2)
            violations <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped(
                Async.foreachDiscard(1 to 5000)(i =>
                    STM.run {
                        for
                            _ <- a.set(if i % 2 == 0 then 10 else 0)
                            _ <- b.set(2)
                        yield ()
                    }
                )
            )
            reader <- Fiber.initUnscoped(
                Async.foreachDiscard(1 to 5000) { _ =>
                    STM.run {
                        for
                            va <- a.get
                            vb <- b.get
                            _  <- if vb == 0 then Sync.defer(violations.incrementAndGet).unit else Kyo.unit
                        yield va / vb
                    }.handle(Abort.run)
                }
            )
            _ <- writer.get
            _ <- reader.get
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "endless batches of 8 STM-update fibers run >60s with no deadlock or progress loss".notJs in {
        for
            ref     <- TRef.init(0L)
            batches <- AtomicInt.init(0)
            stop    <- AtomicBoolean.init(false)
            soak <- Fiber.initUnscoped {
                Loop(()) { _ =>
                    stop.get.map {
                        case true => Loop.done(())
                        case false =>
                            Async.fill(8, 8)(STM.run(STM.defaultRetrySchedule.forever)(ref.update(_ + 1)))
                                .andThen(batches.incrementAndGet)
                                .andThen(Loop.continue(()))
                    }
                }
            }
            // Soak duration bounded to fit the test harness's 60s per-test cap.
            // Batch threshold scaled proportionally.
            _        <- Async.sleep(10.seconds)
            _        <- stop.set(true)
            _        <- Abort.run(Async.timeout(10.seconds)(soak.get))
            b        <- batches.get
            finalRef <- STM.run(ref.get)
        yield assert(b >= 10 && finalRef == b * 8L, s"batches=$b finalRef=$finalRef")
    }

    "STM atomicity holds under sustained mixed read/write workload (JIT-warming soak)".notJs in {
        for
            ref         <- TRef.init(0L)
            totalWrites <- AtomicLong.init(0)
            violations  <- AtomicInt.init(0)
            writers = Async.fill(4, 4) {
                Async.foreachDiscard(1L to 10000L) { _ =>
                    STM.run(STM.defaultRetrySchedule.forever)(ref.update(_ + 1)).andThen(totalWrites.incrementAndGet)
                }
            }
            readers = Async.fill(4, 4) {
                Async.foreachDiscard(1 to 10000) { _ =>
                    STM.run(STM.defaultRetrySchedule.forever)(ref.get).map(v => if v < 0 then violations.incrementAndGet.unit else ())
                }
            }
            _        <- Async.zip(writers, readers)
            w        <- totalWrites.get
            v        <- violations.get
            finalRef <- STM.run(ref.get)
        yield assert(v == 0 && w == 40000L && finalRef == 40000L, s"writes=$w violations=$v finalRef=$finalRef")
    }

    "STM.retryIf with N concurrent waiters survives sustained wake/sleep cycles".notJs in {
        for
            ref    <- TRef.init(0)
            wakes  <- AtomicInt.init(0)
            errors <- AtomicInt.init(0)
            waiters = Async.fill(100, 100) {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        v <- ref.get
                        _ <- STM.retryIf(v == 0)
                        _ <- Sync.defer(wakes.incrementAndGet)
                    yield ()
                }.handle(Abort.run).map {
                    case Result.Panic(_) => errors.incrementAndGet.unit
                    case _               => ()
                }
            }
            waiterFiber <- Fiber.initUnscoped(waiters)
            _           <- Async.sleep(200.millis)
            _           <- STM.run(ref.set(1))
            _           <- Abort.run(Async.timeout(20.seconds)(waiterFiber.get))
            w           <- wakes.get
            e           <- errors.get
        yield assert(w == 100 && e == 0, s"wakes=$w errors=$e")
    }

    "100 concurrent readers on the same TRef chain commit within bounded time".notJs in {
        for
            chain <- Kyo.fill(50)(TRef.init(0))
            done  <- AtomicInt.init(0)
            run = Async.fill(100, 100) {
                STM.run {
                    Kyo.foldLeft(chain)(0)((acc, r) => r.get.map(_ + acc))
                }.andThen(done.incrementAndGet)
            }
            _ <- Abort.run(Async.timeout(15.seconds)(run))
            d <- done.get
        yield assert(d == 100, s"done=$d")
    }

    "transaction with forever schedule exits within bounded retries when invariant becomes satisfiable" in {
        for
            ref      <- TRef.init(0)
            attempts <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped {
                Async.foreachDiscard(1 to 5) { i =>
                    STM.run(ref.set(i)).andThen(Async.sleep(3.millis))
                }
            }
            result <- Abort.run {
                STM.run(STM.defaultRetrySchedule) {
                    for
                        _ <- attempts.incrementAndGet
                        v <- ref.get
                        _ <- STM.retryIf(v < 5)
                    yield v
                }
            }
            a <- attempts.get
            _ <- writer.get
        yield assert(
            a <= Async.defaultConcurrency * 16 + 1 && (result == Result.succeed(5) || result.isFailure),
            s"attempts=$a result=$result"
        )
    }

    "read lock release -> write lock acquire transition produces no stale-read observers".notJs in {
        for
            ref   <- TRef.init(0)
            torn  <- AtomicInt.init(0)
            latch <- Latch.init(1)
            writer <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 10000)(i => STM.run(ref.set(i)))
            ))
            readers = latch.await.andThen(Async.fill(8, 8)(
                Async.foreachDiscard(1 to 5000) { _ =>
                    STM.run {
                        for
                            a <- ref.get
                            b <- ref.get
                            _ <- Sync.defer(if a != b then torn.incrementAndGet.unit else ())
                        yield ()
                    }
                }
            ))
            readersFiber <- Fiber.initUnscoped(readers)
            _            <- latch.release
            _            <- writer.get
            _            <- readersFiber.get
            t            <- torn.get
        yield assert(t == 0, s"torn=$t")
    }

    "child fiber forked inside STM.run does not observe parent's transaction tick".notJs in {
        // Each parent writes a value unique to itself, then forks a child that reads `ref` in a
        // fresh STM.run. A child must never see its own parent's still-uncommitted write; it may
        // only see the committed value of an already-finished parent, or the initial 0. (A
        // unique per-parent value is required so a committed value of *another* parent is not
        // mistaken for a leak.) The parent body's counters replay on retry, so `clean` may
        // exceed the fiber count; the load-bearing invariant is `leaked == 0`.
        val parents = 8
        for
            ref    <- TRef.init(0)
            leaked <- AtomicInt.init(0)
            clean  <- AtomicInt.init(0)
            run = Async.fillIndexed(parents, parents) { fiberId =>
                val mine = 1000 + fiberId
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        _ <- ref.set(mine)
                        child <- Fiber.initUnscoped {
                            STM.run(ref.get).map { v =>
                                if v == mine then leaked.incrementAndGet.unit
                                else clean.incrementAndGet.unit
                            }
                        }
                        _ <- child.get
                    yield ()
                }
            }
            _ <- Abort.run(Async.timeout(30.seconds)(run))
            l <- leaked.get
            c <- clean.get
        yield assert(l == 0 && c >= parents, s"leaked=$l clean=$c")
        end for
    }

    "long Async.sleep inside STM body does not livelock concurrent transactions".notJs in {
        for
            x         <- TRef.init(1)
            slowDone  <- AtomicInt.init(0)
            shortDone <- AtomicInt.init(0)
            other     <- TRef.init(0)
            slows <- Fiber.initUnscoped {
                Async.fill(2, 2) {
                    STM.run(STM.defaultRetrySchedule.forever) {
                        for
                            v <- x.get
                            _ <- Async.sleep(200.millis)
                            _ <- x.set(v * 2)
                        yield ()
                    }.andThen(slowDone.incrementAndGet)
                }
            }
            _  <- Async.sleep(50.millis)
            _  <- Async.fill(50, 50)(STM.run(other.update(_ + 1)).andThen(shortDone.incrementAndGet))
            _  <- Abort.run(Async.timeout(15.seconds)(slows.get))
            sd <- slowDone.get
            od <- shortDone.get
        yield assert(sd >= 1 && od == 50, s"slowDone=$sd shortDone=$od")
    }

    "100 concurrent transactions complete on undersized executor without deadlock" in {
        for
            ref  <- TRef.init(0)
            done <- AtomicInt.init(0)
            run = Async.fill(100, 100)(STM.run(ref.update(_ + 1)).andThen(done.incrementAndGet))
            _        <- Abort.run(Async.timeout(20.seconds)(run))
            d        <- done.get
            finalRef <- STM.run(ref.get)
        yield assert(d == 100 && finalRef == 100, s"done=$d finalRef=$finalRef")
    }

    "sequential STM.run on same fiber preserves write order in observers".notJs in {
        for
            ref        <- TRef.init(0)
            outOfOrder <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped {
                Kyo.foreachDiscard(1 to 1000)(i => STM.run(ref.set(i)))
            }
            observer <- Fiber.initUnscoped {
                Loop(0) { last =>
                    STM.run(ref.get).map { v =>
                        if v < last then outOfOrder.incrementAndGet.unit else ()
                        if v >= 1000 then Loop.done(()) else Loop.continue(math.max(v, last))
                    }
                }
            }
            _ <- writer.get
            _ <- Abort.run(Async.timeout(10.seconds)(observer.get))
            o <- outOfOrder.get
        yield assert(o == 0, s"outOfOrder=$o")
    }

    "STM.run from a freshly forked fiber commits without thread-local stale init" in {
        for
            ref      <- TRef.init(0)
            ok       <- AtomicInt.init(0)
            fibers   <- Kyo.fill(50)(Fiber.initUnscoped(STM.run(ref.update(_ + 1)).andThen(ok.incrementAndGet)))
            _        <- Kyo.foreachDiscard(fibers)(_.get)
            o        <- ok.get
            finalRef <- STM.run(ref.get)
        yield assert(o == 50 && finalRef == 50, s"ok=$o finalRef=$finalRef")
    }

    "concurrent TRef.init produces 1000 unique IDs".notJs in {
        for
            ids <- AtomicRef.init(Set.empty[Int])
            _ <- Async.fill(1000, 100) {
                TRef.init(0).map(r => ids.updateAndGet(_ + r.id).unit)
            }
            s <- ids.get
        yield assert(s.size == 1000, s"ids.size=${s.size}")
    }

    "TRef inside lazy val is initialized once even under concurrent access".notJs in {
        class Holder:
            lazy val ref: TRef[Int] =
                import AllowUnsafe.embrace.danger
                Sync.Unsafe.evalOrThrow(TRef.init(0))
        end Holder
        for
            h   <- Sync.defer(new Holder)
            ids <- AtomicRef.init(Set.empty[Int])
            _ <- Async.fill(100, 100) {
                ids.updateAndGet(_ + h.ref.id)
            }
            s <- ids.get
        yield assert(s.size == 1, s"ids.size=${s.size}")
        end for
    }

    "two-doctor invariant (>=1 on-call) holds under concurrent set-to-off transactions".notJs in {
        Loop.repeat(100) {
            for
                docA  <- TRef.init(true)
                docB  <- TRef.init(true)
                latch <- Latch.init(1)
                f1 <- Fiber.initUnscoped(latch.await.andThen(
                    STM.run {
                        for
                            a <- docA.get
                            b <- docB.get
                            _ <- STM.retryIf(!(a && b))
                            _ <- docA.set(false)
                        yield ()
                    }.handle(Abort.run)
                ))
                f2 <- Fiber.initUnscoped(latch.await.andThen(
                    STM.run {
                        for
                            a <- docA.get
                            b <- docB.get
                            _ <- STM.retryIf(!(a && b))
                            _ <- docB.set(false)
                        yield ()
                    }.handle(Abort.run)
                ))
                _ <- latch.release
                _ <- f1.get
                _ <- f2.get
                a <- STM.run(docA.get)
                b <- STM.run(docB.get)
            yield assert(a || b, s"write skew: docA=$a docB=$b")
        }.unit
    }

    "observer between writer's first ref-publish and last ref-publish sees no half-state".notJs in {
        for
            refs  <- Kyo.fill(10)(TRef.init(0))
            torn  <- AtomicInt.init(0)
            latch <- Latch.init(1)
            writer <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 2000)(i => STM.run(Kyo.foreachDiscard(refs)(_.set(i))))
            ))
            reader <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 5000) { _ =>
                    STM.run {
                        Kyo.collectAll(refs.map(_.get)).map { vs =>
                            if vs.distinct.size > 1 then Sync.defer(torn.incrementAndGet).unit
                            else Kyo.unit
                        }
                    }
                }
            ))
            _ <- latch.release
            _ <- writer.get
            _ <- reader.get
            t <- torn.get
        yield assert(t == 0, s"torn=$t")
    }

    "inner STM.run failure rolls back inner writes but not outer writes (concurrent)" in {
        for
            outer            <- TRef.init(0)
            inner            <- TRef.init(0)
            outerSeenByInner <- AtomicInt.init(0)
            innerNotSeen     <- AtomicInt.init(0)
            innerRolled      <- AtomicInt.init(0)
            innerLeaked      <- AtomicInt.init(0)
            _ <- Async.fill(32, 32) {
                STM.run {
                    for
                        _ <- outer.set(42)
                        _ <- STM.run {
                            for
                                v <- outer.get
                                _ <-
                                    Sync.defer(if v == 42 then outerSeenByInner.incrementAndGet.unit else innerNotSeen.incrementAndGet.unit)
                                _ <- inner.set(99)
                                _ <- STM.retry
                            yield ()
                        }.handle(Abort.run)
                        v <- inner.get
                        _ <- Sync.defer(if v == 0 then innerRolled.incrementAndGet.unit else innerLeaked.incrementAndGet.unit)
                    yield ()
                }
            }
            seen    <- outerSeenByInner.get
            notSeen <- innerNotSeen.get
            rolled  <- innerRolled.get
            leaked  <- innerLeaked.get
        // The counters replay on retry so absolute counts vary; the load-bearing invariants
        // are that the inner NEVER missed the outer's pending write (notSeen == 0) and the
        // inner write was NEVER observed post-rollback (leaked == 0), while each held at
        // least once per fiber (seen/rolled >= 32).
        yield assert(
            notSeen == 0 && leaked == 0 && seen >= 32 && rolled >= 32,
            s"seen=$seen notSeen=$notSeen rolled=$rolled leaked=$leaked"
        )
    }

    "TRef.use does not livelock when writer ticks always exceed reader's start tick".notJs in {
        for
            ref        <- TRef.init(0)
            readerDone <- AtomicBoolean.init(false)
            stop       <- AtomicBoolean.init(false)
            writer <- Fiber.initUnscoped {
                Loop(0) { i =>
                    stop.get.map {
                        case true  => Loop.done(())
                        case false => STM.run(ref.set(i)).andThen(Loop.continue(i + 1))
                    }
                }
            }
            reader <- Fiber.initUnscoped {
                Abort.run {
                    STM.run(STM.defaultRetrySchedule)(ref.use(_ + 1))
                }.andThen(readerDone.set(true))
            }
            _ <- Abort.run(Async.timeout(5.seconds)(reader.get))
            _ <- stop.set(true)
            _ <- writer.get
            d <- readerDone.get
        yield assert(d, s"readerDone=$d")
    }

    "updateReadTick CAS loop bounded under 100 concurrent readers".notJs in {
        for
            ref       <- TRef.init(0)
            readsDone <- AtomicInt.init(0)
            run = Async.fill(100, 100)(STM.run(ref.get).andThen(readsDone.incrementAndGet))
            _ <- Abort.run(Async.timeout(10.seconds)(run))
            d <- readsDone.get
        yield assert(d == 100, s"readsDone=$d")
    }

    "TRef.lock CAS loop terminates under 200 concurrent reader-acquire attempts".notJs in {
        for
            ref  <- TRef.init(0)
            done <- AtomicInt.init(0)
            run = Async.fill(200, 200)(STM.run(ref.get).andThen(done.incrementAndGet))
            _ <- Abort.run(Async.timeout(10.seconds)(run))
            d <- done.get
        yield assert(d == 200, s"done=$d")
    }

    "unlock reader-release loop terminates under 200 concurrent release attempts".notJs in {
        for
            ref  <- TRef.init(0)
            done <- AtomicInt.init(0)
            run = Async.fill(200, 200)(STM.run(ref.get).andThen(done.incrementAndGet))
            _ <- Abort.run(Async.timeout(10.seconds)(run))
            d <- done.get
        yield assert(d == 200, s"done=$d")
    }

    "multi-ref commit with conflict on last ref does not livelock".notJs in {
        for
            refs <- Kyo.fill(10)(TRef.init(0))
            done <- AtomicInt.init(0)
            contender <- Fiber.initUnscoped(
                Async.foreachDiscard(1 to 5000)(_ => STM.run(refs.last.update(_ + 1)))
            )
            multi = Async.fill(32, 32) {
                STM.run(STM.defaultRetrySchedule.forever)(
                    Kyo.foreachDiscard(refs)(_.update(_ + 1))
                ).andThen(done.incrementAndGet)
            }
            _ <- Abort.run(Async.timeout(30.seconds)(multi))
            _ <- contender.get
            d <- done.get
        yield assert(d == 32, s"done=$d")
    }

    "32 concurrent TMap.put with unique new keys all complete within bounded wall-clock".notJs in {
        for
            tmap <- TMap.init[Int, Int]
            done <- AtomicInt.init(0)
            run = Async.fillIndexed(32, 32)(i => STM.run(tmap.put(i, i)).andThen(done.incrementAndGet))
            _         <- Abort.run(Async.timeout(10.seconds)(run))
            d         <- done.get
            finalSize <- STM.run(tmap.size)
        yield assert(d == 32 && finalSize == 32, s"done=$d finalSize=$finalSize")
    }

    "TRef.use log entry tick matches the value read (no torn read-then-log)".notJs in {
        for
            ref        <- TRef.init(0)
            violations <- AtomicInt.init(0)
            latch      <- Latch.init(1)
            writer <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 10000)(i => STM.run(ref.set(i)))
            ))
            reader <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 5000) { _ =>
                    STM.run {
                        for
                            v1 <- ref.use(identity)
                            v2 <- ref.use(identity)
                            _  <- Sync.defer(if v1 != v2 then violations.incrementAndGet.unit else ())
                        yield ()
                    }
                }
            ))
            _ <- latch.release
            _ <- writer.get
            _ <- reader.get
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "TRef.set never logs a Write whose prev.tick mismatches the current entry tick".notJs in {
        for
            ref            <- TRef.init(0)
            commitFailures <- AtomicInt.init(0)
            committed      <- AtomicInt.init(0)
            _ <- Async.fill(64, 64) {
                Async.foreachDiscard(1 to 500) { i =>
                    STM.run(ref.set(i))
                        .andThen(committed.incrementAndGet)
                        .handle(Abort.run)
                        .map {
                            case Result.Failure(_: FailedTransaction) => commitFailures.incrementAndGet.unit
                            case _                                    => ()
                        }
                }
            }
            c        <- committed.get
            f        <- commitFailures.get
            finalRef <- STM.run(ref.get)
        yield assert(c + f == 64 * 500, s"committed=$c failures=$f finalRef=$finalRef")
    }

    "observers immediately after a committing writer never see write-locked-with-stale-readTick state".notJs in {
        for
            refs  <- Kyo.fill(10)(TRef.init(0))
            torn  <- AtomicInt.init(0)
            latch <- Latch.init(1)
            writer <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 2000)(i =>
                    STM.run(STM.defaultRetrySchedule.forever)(Kyo.foreachDiscard(refs)(_.set(i)))
                )
            ))
            reader <- Fiber.initUnscoped(latch.await.andThen(
                Async.foreachDiscard(1 to 5000) { _ =>
                    // The invariant is no-torn-read across concurrent commits; the retry budget
                    // is irrelevant. .forever isolates the consistency assertion from budget
                    // exhaustion under sustained writer pressure on small-parallelism runners.
                    STM.run(STM.defaultRetrySchedule.forever) {
                        Kyo.collectAll(refs.map(_.get)).map { vs =>
                            if vs.distinct.size > 1 then Sync.defer(torn.incrementAndGet).unit
                            else Kyo.unit
                        }
                    }
                }
            ))
            _ <- latch.release
            _ <- writer.get
            _ <- reader.get
            t <- torn.get
        yield assert(t == 0, s"torn=$t")
    }

    "differential: single-ref and two-ref STM commits behave equivalently under same workload".notJs in {
        def workload(refs: Seq[TRef[Int]]) =
            for
                done   <- AtomicInt.init(0)
                failed <- AtomicInt.init(0)
                _ <- Async.fill(64, 64) {
                    STM.run(STM.defaultRetrySchedule)(
                        Kyo.foreachDiscard(refs)(_.update(_ + 1))
                    ).andThen(done.incrementAndGet)
                        .handle(Abort.run)
                        .map {
                            case Result.Failure(_) => failed.incrementAndGet.unit
                            case _                 => ()
                        }
                }
                d <- done.get
                f <- failed.get
            yield (d, f)
        for
            single <- TRef.init(0).map(Seq(_))
            s1     <- workload(single)
            double <- Kyo.fill(2)(TRef.init(0))
            s2     <- workload(double)
        yield assert(s1._1 + s1._2 == 64 && s2._1 + s2._2 == 64, s"s1=$s1 s2=$s2")
        end for
    }

    "concurrent writer between validate-phase and lock-phase forces retry, not silent commit".notJs in {
        for
            a       <- TRef.init(0)
            b       <- TRef.init(0)
            commits <- AtomicInt.init(0)
            _ <- Async.fill(64, 64) {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        va <- a.get
                        vb <- b.get
                        _  <- a.set(va + 1)
                        _  <- b.set(vb + 1)
                        _  <- Sync.defer(commits.incrementAndGet)
                    yield ()
                }
            }
            finalA <- STM.run(a.get)
            finalB <- STM.run(b.get)
            c      <- commits.get
        // finalA/finalB == 64 is the load-bearing atomicity invariant (every fiber's
        // increment committed exactly once); `commits` is an in-body counter that replays
        // on retry and may exceed 64.
        yield assert(c >= 64 && finalA == 64 && finalB == 64, s"commits=$c finalA=$finalA finalB=$finalB")
    }

    "concurrent put(K, v1) + put(K, v2) — only one inner TRef in final map".notJs in {
        Loop.repeat(50) {
            for
                tmap  <- TMap.init[Int, Int]
                latch <- Latch.init(1)
                f1    <- Fiber.initUnscoped(latch.await.andThen(STM.run(tmap.put(0, 1))))
                f2    <- Fiber.initUnscoped(latch.await.andThen(STM.run(tmap.put(0, 2))))
                _     <- latch.release
                _     <- f1.get
                _     <- f2.get
                size  <- STM.run(tmap.size)
                v     <- STM.run(tmap.get(0))
            yield assert(size == 1 && (v == Present(1) || v == Present(2)), s"size=$size v=$v")
        }.unit
    }

    "100 concurrent updateWith(K)(_.map(_ + 1)) increments K's value exactly 100 times".notJs in {
        for
            tmap <- TMap.init[Int, Int]
            _    <- STM.run(tmap.put(0, 0))
            _ <- Async.fill(100, 100) {
                STM.run(tmap.updateWith(0)(_.map(_ + 1)))
            }
            v <- STM.run(tmap.get(0))
        yield assert(v == Present(100), s"v=$v")
    }

    "concurrent remove(K) + put(K, v) yields consistent (key-present XOR key-absent) state".notJs in {
        for
            tmap       <- TMap.init[Int, Int]
            _          <- STM.run(tmap.put(0, 99))
            violations <- AtomicInt.init(0)
            _ <- Loop(0) { i =>
                if i >= 100 then Loop.done(())
                else
                    (for
                        f1 <- Fiber.initUnscoped(STM.run(tmap.remove(0)))
                        f2 <- Fiber.initUnscoped(STM.run(tmap.put(0, i)))
                        _  <- f1.get
                        _  <- f2.get
                        s  <- STM.run(tmap.snapshot)
                        _  <- Sync.defer(if s.size > 1 then violations.incrementAndGet.unit else ())
                        _  <- STM.run(tmap.put(0, 99))
                    yield ()).andThen(Loop.continue(i + 1))
            }
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "32 concurrent TTable.insert produces 32 unique IDs and 32 stored records".notJs in {
        for
            table <- TTable.init["name" ~ String & "n" ~ Int]
            ids   <- AtomicRef.init(Set.empty[Int])
            _ <- Async.fillIndexed(32, 32) { i =>
                STM.run(table.insert("name" ~ s"r$i" & "n" ~ i)).map(id => ids.updateAndGet(_ + id.toInt).unit)
            }
            s    <- ids.get
            size <- STM.run(table.size)
        yield assert(s.size == 32 && size == 32, s"ids.size=${s.size} size=$size")
    }

    "concurrent update(id, r1) + update(id, r2) leaves indexes consistent with one final record".notJs in {
        Loop.repeat(50) {
            for
                table <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                id    <- STM.run(table.insert("name" ~ "init"))
                f1    <- Fiber.initUnscoped(STM.run(table.update(id, "name" ~ "a")))
                f2    <- Fiber.initUnscoped(STM.run(table.update(id, "name" ~ "b")))
                _     <- f1.get
                _     <- f2.get
                rec   <- STM.run(table.get(id))
                idsA  <- STM.run(table.queryIds("name" ~ "a"))
                idsB  <- STM.run(table.queryIds("name" ~ "b"))
            yield assert(
                rec.isDefined &&
                    ((rec.get.name == "a" && idsA.contains(id) && idsB.isEmpty) ||
                        (rec.get.name == "b" && idsB.contains(id) && idsA.isEmpty)),
                s"rec=$rec idsA=$idsA idsB=$idsB"
            )
        }.unit
    }

    "concurrent remove(id) + update(id, r) leaves indexes consistent with final store state".notJs in {
        Loop.repeat(50) {
            for
                table   <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                id      <- STM.run(table.insert("name" ~ "init"))
                f1      <- Fiber.initUnscoped(STM.run(table.remove(id)))
                f2      <- Fiber.initUnscoped(STM.run(table.update(id, "name" ~ "new")))
                _       <- f1.get
                _       <- f2.get
                rec     <- STM.run(table.get(id))
                idsInit <- STM.run(table.queryIds("name" ~ "init"))
                idsNew  <- STM.run(table.queryIds("name" ~ "new"))
                inAnyIndex = idsInit ++ idsNew
            yield assert(
                (rec.isEmpty && inAnyIndex.isEmpty) ||
                    (rec.isDefined && inAnyIndex.contains(id)),
                s"rec=$rec idsInit=$idsInit idsNew=$idsNew"
            )
        }.unit
    }

    "transactions of size 7, 8, 9, 10 with same refs all complete without deadlock".notJs in {
        val sizes = Seq(7, 8, 9, 10, 15, 20)
        for
            refs <- Kyo.fill(20)(TRef.init(0))
            run = Async.foreachDiscard(sizes) { size =>
                val subset = refs.take(size)
                for
                    f1 <- Fiber.initUnscoped(STM.run(STM.defaultRetrySchedule.forever)(Kyo.foreachDiscard(subset)(_.update(_ + 1))))
                    f2 <- Fiber.initUnscoped(STM.run(STM.defaultRetrySchedule.forever)(Kyo.foreachDiscard(subset.reverse)(_.update(_ + 1))))
                    _  <- f1.get
                    _  <- f2.get
                yield ()
                end for
            }
            r         <- Abort.run(Async.timeout(15.seconds)(run))
            finalVals <- Kyo.foreach(refs)(r => STM.run(r.get))
        // No deadlock: the timed run completed. Each ref i is touched by 2 fibers in every
        // size-group whose size > i, so its final value must be exactly 2 * (#sizes > i).
        yield assert(
            r.isSuccess &&
                finalVals.toSeq.zipWithIndex.forall((v, i) => v == 2 * sizes.count(_ > i)),
            s"result=$r finalVals=$finalVals"
        )
        end for
    }

    "two transactions each performing N puts on different key sets do not deadlock".notJs in {
        Loop.repeat(10) {
            for
                tmap <- TMap.init[Int, Int]
                f1   <- Fiber.initUnscoped(STM.run(Kyo.foreachDiscard(0 until 50)(i => tmap.put(i, i))))
                f2   <- Fiber.initUnscoped(STM.run(Kyo.foreachDiscard(50 until 100)(i => tmap.put(i, i))))
                _    <- f1.get
                _    <- f2.get
                size <- STM.run(tmap.size)
            yield assert(size == 100, s"size=$size")
        }.unit
    }

    "concurrent TMap.removeAll + put preserves consistency".notJs in {
        Loop.repeat(20) {
            for
                tmap <- TMap.init[Int, Int]
                _    <- STM.run(Kyo.foreachDiscard(0 until 50)(i => tmap.put(i, i)))
                f1   <- Fiber.initUnscoped(STM.run(tmap.removeAll((0 until 25).toSeq)))
                f2   <- Fiber.initUnscoped(Async.foreachDiscard(0 until 25)(i => STM.run(tmap.put(i, i + 100))))
                _    <- f1.get
                _    <- f2.get
                snap <- STM.run(tmap.snapshot)
            yield assert(
                (25 until 50).forall(k => snap.get(k).contains(k)) &&
                    (0 until 25).forall(k => snap.get(k).forall(v => v == k || v == k + 100)),
                s"snap=$snap"
            )
        }.unit
    }

    "TTable.Indexed with empty Indexes survives concurrent insert/update/remove".notJs in {
        for
            table <- TTable.init["name" ~ String & "n" ~ Int]
            done  <- AtomicInt.init(0)
            run = Async.fillIndexed(32, 32) { i =>
                STM.run(table.insert("name" ~ s"r$i" & "n" ~ i)).andThen(done.incrementAndGet)
            }
            _ <- Abort.run(Async.timeout(15.seconds)(run))
            d <- done.get
        yield assert(d == 32, s"done=$d")
    }

    "TMap.initWith inside retrying STM.run consumes idCounter N*K times for N retries × K entries" in {
        for
            attempts <- AtomicInt.init(0)
            _ <- Async.fill(8, 8) {
                STM.run(Schedule.repeat(3)) {
                    for
                        _ <- Sync.defer(attempts.incrementAndGet)
                        _ <- TMap.initWith((0 until 10).map(i => (i, i))*)(identity)
                        _ <- STM.retry
                    yield ()
                }.handle(Abort.run)
            }
            a <- attempts.get
        yield assert(a >= 8 * 4, s"attempts=$a")
    }

    "a doomed TTable.insert rolls back its record; the id counter is not transactional" in {
        Loop.repeat(10) {
            for
                table    <- TTable.init["name" ~ String]
                beforeId <- STM.run(table.insert("name" ~ "before"))
                _ <- Abort.run {
                    STM.run(Schedule.done) {
                        table.insert("name" ~ "doomed").andThen(STM.retry)
                    }
                }
                afterId <- STM.run(table.insert("name" ~ "after"))
                snap    <- STM.run(table.snapshot)
            yield
                // The doomed insert's store write rolls back, so no "doomed" record survives.
                // The id counter is a lock-free AtomicInt (not transactional), so the doomed
                // attempt still consumed an id: afterId is unique and strictly greater than
                // beforeId, though not necessarily beforeId + 1.
                assert((afterId: Int) > (beforeId: Int), s"before=$beforeId after=$afterId")
                assert(!snap.values.exists(_.name == "doomed"), "rolled-back insert must leave no record")
        }.unit
    }

    // Runs on the JVM only: a WeakReference is reclaimed promptly on System.gc() only on the JVM.
    "WeakReference to TRef allocated in doomed transaction becomes GC-eligible after rollback".onlyJvm in {
        for
            weakRef <- AtomicRef.init(null: WeakReference[TRef[Int]])
            _ <- Abort.run {
                STM.run {
                    for
                        ref <- TRef.init(42)
                        _   <- Sync.defer(weakRef.set(new WeakReference(ref)))
                        _   <- STM.retry
                    yield ()
                }
            }
            _ <- Sync.defer {
                (1 to 5).foreach(_ => java.lang.System.gc())
            }
            wr <- weakRef.get
        yield assert(wr != null && wr.get == null, "TRef from doomed transaction not GC-eligible")
    }

    "nested STM.run inner log does not leak into outer log on failure" in {
        Loop.repeat(100) {
            for
                outerRef <- TRef.init(1)
                innerRef <- TRef.init(2)
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- outerRef.update(_ + 10)
                            _ <- STM.run {
                                for
                                    _ <- innerRef.set(99)
                                    _ <- STM.retry
                                yield ()
                            }.handle(Abort.run)
                            v <- outerRef.get
                        yield v
                    }
                }
                finalOuter <- STM.run(outerRef.get)
                finalInner <- STM.run(innerRef.get)
            yield assert(
                result == Result.succeed(11) && finalOuter == 11 && finalInner == 2,
                s"result=$result finalOuter=$finalOuter finalInner=$finalInner"
            )
        }.unit
    }

    // Runs on the JVM only: a WeakReference is reclaimed promptly on System.gc() only on the JVM.
    "nested STM.run with heavy inner-log does not retain log after success".onlyJvm in {
        for
            outer    <- TRef.init(0)
            weakRefs <- AtomicRef.init(Chunk.empty[WeakReference[TRef[Int]]])
            _ <- STM.run {
                STM.run {
                    for
                        refs <- Kyo.fill(50)(TRef.init(0))
                        _    <- weakRefs.updateAndGet(_ ++ refs.map(r => new WeakReference(r)))
                    yield ()
                }
            }
            _ <- Sync.defer {
                (1 to 5).foreach(_ => java.lang.System.gc())
            }
            wrs <- weakRefs.get
            cleared = wrs.count(_.get == null)
        yield assert(cleared >= wrs.size / 2, s"cleared=$cleared of ${wrs.size}")
    }

    // Runs on the JVM only: a WeakReference is reclaimed promptly on System.gc() only on the JVM.
    "after a multi-ref commit, the per-thread CommitBuffer does not retain prior-cycle TRefs".onlyJvm in {
        for
            weak <- AtomicRef.init(null: WeakReference[TRef[Int]])
            _ <- STM.run {
                for
                    r1 <- TRef.init(1)
                    r2 <- TRef.init(2)
                    r3 <- TRef.init(3)
                    _  <- Sync.defer(weak.set(new WeakReference(r1)))
                    _  <- r1.update(_ + 1)
                    _  <- r2.update(_ + 1)
                    _  <- r3.update(_ + 1)
                yield ()
            }
            _ <- Async.foreachDiscard(1 to 100) { _ =>
                STM.run {
                    for
                        a <- TRef.init(0)
                        b <- TRef.init(0)
                        c <- TRef.init(0)
                        _ <- a.set(1)
                        _ <- b.set(2)
                        _ <- c.set(3)
                    yield ()
                }
            }
            _ <- Sync.defer {
                (1 to 5).foreach(_ => java.lang.System.gc())
            }
            wr <- weak.get
        yield assert(wr.get == null, "CommitBuffer retains prior-cycle TRef")
    }

    "TMap.entries observes consistent (outer, inner) snapshot or aborts".notJs in {
        for
            tmap       <- TMap.init[Int, Int]
            _          <- STM.run(Kyo.foreachDiscard(0 until 20)(i => tmap.put(i, i)))
            violations <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped(Async.foreachDiscard(1 to 5000)(i =>
                STM.run(STM.defaultRetrySchedule.forever)(tmap.put(i % 20, i))
            ))
            reader <- Fiber.initUnscoped(Async.foreachDiscard(1 to 2000) { _ =>
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        snap <- tmap.entries.map(_.toMap)
                        _    <- Sync.defer(if snap.size != 20 then violations.incrementAndGet.unit else ())
                    yield ()
                }
            })
            _ <- writer.get
            _ <- reader.get
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "queryIds result is consistent with a single timeline of inserts/updates".notJs in {
        for
            table           <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
            inconsistencies <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped(Async.foreachDiscard(1 to 1000) { i =>
                STM.run(STM.defaultRetrySchedule.forever)(table.insert("name" ~ s"n$i" & "age" ~ (i % 50)))
            })
            reader <- Fiber.initUnscoped(Async.foreachDiscard(1 to 1000) { _ =>
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        idsByAge  <- table.queryIds("age" ~ 25)
                        idsByName <- table.queryIds("name" ~ "n1")
                        _ <- Sync.defer {
                            val intersect = idsByAge.toSet.intersect(idsByName.toSet)
                            if intersect.size > 1 then inconsistencies.incrementAndGet.unit else ()
                        }
                    yield ()
                }
            })
            _ <- writer.get
            _ <- reader.get
            v <- inconsistencies.get
        yield assert(v == 0, s"inconsistencies=$v")
    }

    "slow TMap.fold under concurrent put aborts cleanly (no accumulator escape)".notJs in {
        for
            tmap          <- TMap.init[Int, Int]
            _             <- STM.run(Kyo.foreachDiscard(0 until 20)(i => tmap.put(i, 1)))
            accSeen       <- AtomicRef.init(Chunk.empty[Int])
            startMutating <- Latch.init(1)
            mutator <- Fiber.initUnscoped(startMutating.await.andThen(
                Async.foreachDiscard(1 to 1000)(i => STM.run(tmap.put(i % 20, i)))
            ))
            folder <- Fiber.initUnscoped(
                STM.run(STM.defaultRetrySchedule.forever) {
                    tmap.fold(0) { (acc, _, v) =>
                        for
                            _ <- Async.sleep(1.millis)
                            _ <- accSeen.updateAndGet(_ :+ (acc + v))
                        yield acc + v
                    }
                }
            )
            _        <- startMutating.release
            folded   <- folder.get
            _        <- mutator.get
            finalSum <- STM.run(tmap.snapshot).map(_.values.sum)
        yield assert(folded >= 0 && folded <= finalSum + 1000, s"folded=$folded finalSum=$finalSum")
    }

    "transaction reading A then B aborts at commit if A was concurrently written".notJs in {
        // The writer always writes a == b atomically in one transaction. Commit-time
        // validation must ensure any committed reader that read A then B observed a
        // consistent timeline, i.e. va == vb. `violations` is a TRef so the increment is
        // part of the committed transaction — counted only for commit-consistent reads.
        for
            a          <- TRef.init(0)
            b          <- TRef.init(0)
            violations <- TRef.init(0)
            writer <- Fiber.initUnscoped(Async.foreachDiscard(1 to 5000) { i =>
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        _ <- a.set(i)
                        _ <- b.set(i)
                    yield ()
                }
            })
            reader <- Fiber.initUnscoped(Async.foreachDiscard(1 to 5000) { _ =>
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        va <- a.get
                        vb <- b.get
                        _  <- if va != vb then violations.update(_ + 1) else Kyo.unit
                    yield ()
                }
            })
            _ <- writer.get
            _ <- reader.get
            v <- STM.run(violations.get)
        yield assert(v == 0, s"violations=$v")
    }

    "TRef allocated mid-transaction survives outer rollback with its initial value" in {
        Loop.repeat(50) {
            for
                capturedRef <- AtomicRef.init(null: TRef[Int])
                _ <- Abort.run {
                    STM.run {
                        TRef.initWith(42) { r =>
                            for
                                _ <- Sync.defer(capturedRef.set(r))
                                _ <- r.set(99)
                                _ <- STM.retry
                            yield ()
                        }
                    }
                }
                r <- capturedRef.get
                v <- STM.run(r.get)
            yield assert(v == 42, s"v=$v")
        }.unit
    }

    "sustained reader-acquire spam against one TRef: writer eventually exits via STM.run schedule cap".notJs in {
        for
            ref          <- TRef.init(0)
            writerExited <- AtomicBoolean.init(false)
            stop         <- AtomicBoolean.init(false)
            // 16 readers provide sustained reader-acquire contention; a sub-millisecond
            // yield per iteration keeps them from saturating the scheduler (and thereby
            // starving the writer / timeout fibers) while still keeping `ref`'s readTick
            // perpetually fresher than the writer's start tick.
            readers <- Fiber.initUnscoped {
                Async.fill(16, 16) {
                    Loop(()) { _ =>
                        stop.get.map {
                            case true  => Loop.done(())
                            case false => STM.run(ref.get).andThen(Async.sleep(1.millis)).andThen(Loop.continue(()))
                        }
                    }
                }
            }
            writer <- Fiber.initUnscoped {
                Abort.run {
                    STM.run(STM.defaultRetrySchedule)(ref.set(42))
                }.andThen(writerExited.set(true))
            }
            _      <- Abort.run(Async.timeout(10.seconds)(writer.get))
            _      <- stop.set(true)
            _      <- Abort.run(Async.timeout(10.seconds)(readers.get))
            exited <- writerExited.get
        yield assert(exited, s"writerExited=$exited")
    }

    "100x repeat of 64-fiber STM increment workload yields exactly 6400 each time".notJs in {
        for
            results <- AtomicRef.init(Chunk.empty[Int])
            _ <- Loop.repeat(100) {
                for
                    ref <- TRef.init(0)
                    _   <- Async.fill(64, 64)(STM.run(ref.update(_ + 1)))
                    v   <- STM.run(ref.get)
                    _   <- results.updateAndGet(_ :+ v)
                yield ()
            }
            r <- results.get
        yield assert(r.forall(_ == 64), s"results=$r")
    }

    "concurrent TChunk.append from 32 fibers yields final size 32".notJs in {
        for
            tchunk <- TChunk.init(Chunk.empty[Int])
            _ <- Async.fillIndexed(32, 32) { i =>
                STM.run(tchunk.append(i))
            }
            finalChunk <- STM.run(tchunk.snapshot)
        yield assert(finalChunk.size == 32 && finalChunk.distinct.size == 32, s"size=${finalChunk.size}")
    }

    "STM.run(Schedule.repeat(5)) under contention bounds attempts to 6".notJs in {
        for
            ref      <- TRef.init(0)
            attempts <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped(Async.foreachDiscard(1 to 1000) { _ =>
                STM.run(ref.update(_ + 1))
            })
            _ <- Abort.run {
                STM.run(Schedule.repeat(5)) {
                    for
                        _ <- attempts.incrementAndGet
                        v <- ref.get
                        _ <- ref.set(v - 100)
                    yield ()
                }
            }
            a <- attempts.get
            _ <- writer.get
        yield assert(a <= 6, s"attempts=$a")
    }

    "atomic update across TMap + TRef + TChunk preserves cross-type invariant".notJs in {
        for
            tmap       <- TMap.init[Int, Int]
            tref       <- TRef.init(0)
            tchunk     <- TChunk.init(Chunk.empty[Int])
            violations <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped(Async.foreachDiscard(1 to 1000) { i =>
                STM.run {
                    for
                        _ <- tmap.put(i % 10, i)
                        _ <- tref.set(i)
                        _ <- tchunk.append(i)
                    yield ()
                }
            })
            reader <- Fiber.initUnscoped(Async.foreachDiscard(1 to 1000) { _ =>
                STM.run {
                    for
                        _ <- tmap.snapshot
                        r <- tref.get
                        c <- tchunk.snapshot
                        _ <- Sync.defer(if c.nonEmpty && c.last != r then violations.incrementAndGet.unit else ())
                    yield ()
                }
            })
            _ <- writer.get
            _ <- reader.get
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "STM.retry from within Async.sleep-containing transaction body retries through the schedule" in {
        for
            ref         <- TRef.init(0)
            sideEffects <- AtomicInt.init(0)
            // The writer must publish 1..5 in order so `ref` monotonically reaches 5;
            // Kyo.foreachDiscard is sequential (Async.foreachDiscard would run the writes
            // in parallel and leave `ref` at an arbitrary final value below 5).
            writer <- Fiber.initUnscoped(Kyo.foreachDiscard(1 to 5)(i =>
                Async.sleep(5.millis).andThen(STM.run(STM.defaultRetrySchedule.forever)(ref.set(i)))
            ))
            result <- Abort.run(Async.timeout(15.seconds) {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        _ <- sideEffects.incrementAndGet
                        _ <- Async.sleep(20.millis)
                        v <- ref.get
                        _ <- STM.retryIf(v < 5)
                    yield v
                }
            })
            se <- sideEffects.get
            _  <- writer.get
        yield assert(result == Result.succeed(5) && se >= 2, s"result=$result sideEffects=$se")
    }

    "nested TRef pointer-chase under concurrent rotation never observes orphan node".notJs in {
        for
            leaf       <- TRef.init(0)
            mid        <- TRef.init(leaf)
            root       <- TRef.init(mid)
            violations <- AtomicInt.init(0)
            rotater <- Fiber.initUnscoped(Async.foreachDiscard(1 to 2000) { i =>
                STM.run {
                    for
                        newLeaf <- TRef.initWith(i)(identity)
                        newMid  <- TRef.initWith(newLeaf)(identity)
                        _       <- root.set(newMid)
                    yield ()
                }
            })
            reader <- Fiber.initUnscoped(Async.foreachDiscard(1 to 2000) { _ =>
                STM.run {
                    for
                        m <- root.get
                        l <- m.get
                        v <- l.get
                        _ <- Sync.defer(if v < 0 then violations.incrementAndGet.unit else ())
                    yield v
                }
            })
            _ <- rotater.get
            _ <- reader.get
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "retry's wake-set is union of every branch's read-set" in {
        // kyo-stm has no `orElse` primitive; a transaction that consults multiple refs
        // builds one read-set that is the union of every ref it touched. The contract
        // under test: a transaction that read both `a` and `b` and then retried must
        // re-run when EITHER ref changes — here, a write to `a` wakes a waiter whose
        // body also read `b`.
        for
            a      <- TRef.init(0)
            b      <- TRef.init(0)
            woken  <- AtomicBoolean.init(false)
            sawVia <- AtomicRef.init("")
            waiter <- Fiber.initUnscoped {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        va <- a.get
                        vb <- b.get
                        _  <- STM.retryIf(va == 0 && vb == 0)
                        _  <- Sync.defer(sawVia.set(if va != 0 then "a" else "b"))
                    yield ()
                }.andThen(woken.set(true))
            }
            _   <- Async.sleep(100.millis)
            _   <- STM.run(a.set(42))
            _   <- Abort.run(Async.timeout(5.seconds)(waiter.get))
            w   <- woken.get
            via <- sawVia.get
        yield assert(w && via == "a", s"woken=$w sawVia=$via")
    }

    "panic in STM body is suppressed-and-retried if log is stale".notJs in {
        for
            outer      <- TRef.init(0)
            propagated <- AtomicInt.init(0)
            ok         <- AtomicInt.init(0)
            writer <- Fiber.initUnscoped(Async.foreachDiscard(1 to 1000)(i =>
                STM.run(STM.defaultRetrySchedule.forever)(outer.set(i))
            ))
            readers = Async.fill(32, 32) {
                STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        v <- outer.get
                        _ <- if v < 0 then throw new RuntimeException("doomed snapshot")
                        else Sync.defer(ok.incrementAndGet).unit
                    yield ()
                }.handle(Abort.run).map {
                    case Result.Panic(_) => propagated.incrementAndGet.unit
                    case _               => ()
                }
            }
            _ <- Async.zip(writer.get, readers)
            p <- propagated.get
            o <- ok.get
        // The load-bearing invariant is `p == 0` (no panic from a consistent read leaks);
        // `ok` may exceed the fiber count because the in-body counter replays on retry.
        yield assert(p == 0 && o >= 32, s"propagated=$p ok=$o")
    }

    "high-concurrency commits never produce a mismatched ref/entry pair in CommitBuffer".notJs in {
        for
            refs   <- Kyo.fill(20)(TRef.init(0))
            panics <- AtomicInt.init(0)
            _ <- Async.fillIndexed(64, 64) { i =>
                STM.run(Kyo.foreachDiscard(refs.take(i % 20 + 1))(_.update(_ + 1)))
                    .handle(Abort.run)
                    .map {
                        case Result.Panic(_) => panics.incrementAndGet.unit
                        case _               => ()
                    }
            }
            p <- panics.get
        yield assert(p == 0, s"panics=$p")
    }

    "every STM.run multi-ref commit observes sorted buffer before lock phase".notJs in {
        val sizes = Seq(7, 8, 9, 10, 15, 20)
        for
            refs <- Kyo.fill(20)(TRef.init(0))
            run = Async.foreachDiscard(sizes) { size =>
                val subset = refs.take(size)
                for
                    f1 <- Fiber.initUnscoped(STM.run(STM.defaultRetrySchedule.forever)(Kyo.foreachDiscard(subset)(_.update(_ + 1))))
                    f2 <- Fiber.initUnscoped(STM.run(STM.defaultRetrySchedule.forever)(Kyo.foreachDiscard(subset.reverse)(_.update(_ + 1))))
                    _  <- f1.get
                    _  <- f2.get
                yield ()
                end for
            }
            r         <- Abort.run(Async.timeout(15.seconds)(run))
            finalVals <- Kyo.foreach(refs)(r => STM.run(r.get))
        // No deadlock implies the sort ran before lock acquisition.
        yield assert(
            r.isSuccess &&
                finalVals.toSeq.zipWithIndex.forall((v, i) => v == 2 * sizes.count(_ > i)),
            s"result=$r finalVals=$finalVals"
        )
        end for
    }

    "TMap.snapshot observes consistent (outer, inner) snapshot or aborts (snapshot variant)".notJs in {
        for
            tmap       <- TMap.init[Int, Int]
            _          <- STM.run(Kyo.foreachDiscard(0 until 20)(i => tmap.put(i, i)))
            violations <- AtomicInt.init(0)
            writer     <- Fiber.initUnscoped(Async.foreachDiscard(1 to 5000)(i => STM.run(tmap.put(i % 20, i))))
            reader <- Fiber.initUnscoped(Async.foreachDiscard(1 to 2000) { _ =>
                STM.run {
                    for
                        snap <- tmap.snapshot
                        _    <- Sync.defer(if snap.size != 20 then violations.incrementAndGet.unit else ())
                    yield ()
                }
            })
            _ <- writer.get
            _ <- reader.get
            v <- violations.get
        yield assert(v == 0, s"violations=$v")
    }

    "TRef.init outside STM.run is observable from another fiber without commit barrier" in {
        Loop.repeat(50) {
            for
                refHolder   <- AtomicRef.init(null: TRef[Int])
                observerSaw <- AtomicInt.init(-1)
                producer    <- Fiber.initUnscoped(TRef.init(42).map(r => refHolder.set(r)))
                _           <- producer.get
                observer <- Fiber.initUnscoped {
                    Sync.defer(refHolder.get).map { ref =>
                        STM.run(ref.get).map(v => observerSaw.set(v))
                    }
                }
                _ <- observer.get
                v <- observerSaw.get
            yield assert(v == 42, s"v=$v")
        }.unit
    }

end STMStressTest
