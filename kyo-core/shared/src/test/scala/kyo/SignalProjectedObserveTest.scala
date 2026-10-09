package kyo

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The repairing loop of `Signal.observeProjected`, over a combinator source and on virtual time.
  *
  * A combinator cannot observe exactly, so its loop wakes on either `nextWith` or a repair timer and re-reads `current`. What a wakeup
  * may do with that read is pinned here:
  *
  *   - **A wakeup with an unchanged source.** A wakeup that finds the source unchanged neither projects nor delivers. Images need not be `==` for equal
  *     sources: a rendered UI tree never is, so re-projecting on a tick repaints the region once per repair interval.
  *   - **A real source change.** A real source change still projects and compares images: a new image delivers once, an equal one delivers
  *     nothing, and a change the wakeup missed is still reconciled by a tick.
  *   - **Closing an image's Scope.** The source value remembered for an image does not outlive that image's `Scope`.
  *
  * Every test runs under `Clock.withTimeControl`, with a fiber that fires each repair timer shortly after it is armed, and waits on the
  * number of `currentWith` reads the loop has made instead of on time. The loop is the only reader and reads again only after it has
  * acted on its previous read, so once read `n + 1` has started, read `n` has been fully handled.
  */
class SignalProjectedObserveTest extends kyo.test.Test[Any]:

    private val ri = Signal.defaultRepairInterval

    /** An image compared by reference: two projections of one source value differ, as two renders of a UI tree do. */
    final private class Img(val n: Int) derives CanEqual

    /** Counts the reads the observation loop makes of its source and opens latches at chosen counts. */
    final private class Reads(count: AtomicInt, waiters: AtomicRef[Chunk[(Int, Latch)]]):

        def record(using Frame): Unit < Sync =
            count.incrementAndGet.map { n =>
                waiters.get.map(ws => Kyo.foreachDiscard(ws)(w => if n >= w._1 then w._2.release else Kyo.unit))
            }

        def get(using Frame): Int < Sync = count.get

        /** Waits until read `n` has started. */
        def await(n: Int)(using Frame): Unit < Async =
            Latch.init(1).map { latch =>
                waiters.updateAndGet(_.append((n, latch)))
                    .andThen(count.get.map(c => if c >= n then latch.release else Kyo.unit))
                    .andThen(latch.await)
            }
    end Reads

    private def initReads(using Frame): Reads < Sync =
        AtomicInt.init.map(count => AtomicRef.init(Chunk.empty[(Int, Latch)]).map(new Reads(count, _)))

    /** `a.combineLatest(b)` behind a raw signal that counts reads. Without `wakes`, `nextWith` never fires and every change is a missed
      * wakeup.
      */
    private def source(a: Signal[Int], b: Signal[Int], reads: Reads, wakes: Boolean = true)(using Frame): Signal[(Int, Int)] =
        val pair = a.combineLatest(b)
        Signal.initRaw[(Int, Int)](
            currentWith = [C, S] => g => reads.record.andThen(pair.currentWith(g)),
            nextWith = [C, S] => g => if wakes then pair.nextWith(g) else Async.never[C]
        )
    end source

    /** Projects like a render, a fresh image per source change, but reuses the image while the source repeats: no tick can move it. */
    private def rendering: ((Int, Int)) => Img =
        val last = new AtomicReference[Maybe[((Int, Int), Img)]](Absent)
        src =>
            last.get() match
                case Present((prev, img)) if prev == src => img
                case _                                   =>
                    val img = Img(src._1 * 10 + src._2)
                    last.set(Present((src, img)))
                    img
            end match
    end rendering

    /** Runs `body` on virtual time, with a fiber that fires every repair timer shortly after it is armed. */
    private def ticking[A](body: => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any]) =
        Clock.withTimeControl(tc => Scope.run(Fiber.init(Loop.forever(tc.advance(ri, 1.millis))).andThen(body)))

    "a wakeup with an unchanged source" - {

        "a repair tick does not re-run the projection while a combinator's sources hold" in ticking {
            val projections = new AtomicInteger(0)
            for
                a         <- Signal.initRef(0)
                b         <- Signal.initRef(0)
                reads     <- initReads
                delivered <- AtomicInt.init
                sig = source(a, b, reads).map { (x, y) =>
                    discard(projections.incrementAndGet())
                    Img(x + y)
                }
                _ <- Fiber.init(sig.observe(ri)(_ => delivered.incrementAndGet.unit))
                // Read 1 delivers; reads 2 to 6 are repair ticks over unchanged sources.
                _ <- reads.await(7)
                p = projections.get()
                d <- delivered.get
            yield assert(p == 1 && d == 1, s"unchanged sources, 5 repair ticks: $p projections and $d deliveries, expected 1 and 1")
            end for
        }

        "the per-image Scope stays open across repair ticks and closes on a real change" in ticking {
            for
                a      <- Signal.initRef(0)
                b      <- Signal.initRef(0)
                reads  <- initReads
                closes <- AtomicInt.init
                closed <- Latch.init(1)
                sig = source(a, b, reads).map((x, y) => Img(x + y))
                _    <- Fiber.init(sig.observe(ri)(_ => Scope.ensure(closes.incrementAndGet.andThen(closed.release))))
                _    <- reads.await(7)
                idle <- closes.get
                _ = assert(idle == 0, s"unchanged sources, 5 repair ticks: the image's Scope closed $idle times, expected 0")
                _     <- a.set(1)
                _     <- closed.await
                after <- closes.get
            yield assert(after == 1, s"one source change closed $after Scopes, expected 1")
        }
    }

    "a real source change" - {

        "a source change with a new image delivers exactly once" in ticking {
            for
                a         <- Signal.initRef(0)
                b         <- Signal.initRef(0)
                reads     <- initReads
                delivered <- AtomicRef.init(Chunk.empty[Int])
                sig = source(a, b, reads).map((x, y) => x + y)
                _ <- Fiber.init(sig.observe(ri)(n => delivered.updateAndGet(_.append(n)).unit))
                _ <- reads.await(2)
                _ <- a.set(1)
                // Every read from r + 1 on sees the write; waiting for r + 7 lets the change and five ticks after it be handled.
                r    <- reads.get
                _    <- reads.await(r + 7)
                seen <- delivered.get
            yield assert(seen == Chunk(0, 1), s"delivered $seen, expected Chunk(0, 1)")
        }

        "a source change with an equal image delivers nothing and later ticks do not re-project" in ticking {
            val projections = new AtomicInteger(0)
            for
                a         <- Signal.initRef(0)
                b         <- Signal.initRef(0)
                reads     <- initReads
                delivered <- AtomicRef.init(Chunk.empty[Int])
                sig = source(a, b, reads).map { (_, y) =>
                    discard(projections.incrementAndGet())
                    y
                }
                _ <- Fiber.init(sig.observe(ri)(n => delivered.updateAndGet(_.append(n)).unit))
                _ <- reads.await(2)
                _ <- a.set(1)
                r <- reads.get
                _ <- reads.await(r + 7)
                p = projections.get()
                seen <- delivered.get
            yield assert(
                p == 2 && seen == Chunk(0),
                s"$p projections and deliveries $seen, expected 2 (the initial value and the change to a) and Chunk(0)"
            )
            end for
        }

        "a change the wakeup missed is delivered by the repair tick" in ticking {
            for
                a         <- Signal.initRef(0)
                b         <- Signal.initRef(0)
                reads     <- initReads
                delivered <- AtomicRef.init(Chunk.empty[Int])
                sig = source(a, b, reads, wakes = false).map((x, y) => x + y)
                _    <- Fiber.init(sig.observe(ri)(n => delivered.updateAndGet(_.append(n)).unit))
                _    <- reads.await(2)
                _    <- a.set(1)
                r    <- reads.get
                _    <- reads.await(r + 7)
                seen <- delivered.get
            yield assert(seen == Chunk(0, 1), s"delivered $seen, expected Chunk(0, 1)")
        }

        "a seeded observation over a combinator map skips the baseline and does not re-project on ticks" in ticking {
            val projections = new AtomicInteger(0)
            for
                a         <- Signal.initRef(0)
                b         <- Signal.initRef(0)
                reads     <- initReads
                delivered <- AtomicRef.init(Chunk.empty[Int])
                sig = source(a, b, reads).map { (x, y) =>
                    discard(projections.incrementAndGet())
                    x + y
                }
                _ <- Fiber.init(sig.currentWith(c => sig.observe(Present(c), ri)(n => delivered.updateAndGet(_.append(n)).unit)))
                // Read 1 takes the baseline, read 2 starts the loop, reads 3 to 7 are repair ticks.
                _ <- reads.await(8)
                p = projections.get()
                seen <- delivered.get
            yield assert(
                p == 2 && seen.isEmpty,
                s"$p projections and deliveries $seen, expected 2 (the baseline and the loop's first read) and none"
            )
            end for
        }
    }

    "closing an image's Scope" - {

        "a source that returns to its delivered value while that Scope closes is delivered again" in ticking {
            for
                a         <- Signal.initRef(0)
                b         <- Signal.initRef(0)
                reads     <- initReads
                delivered <- AtomicRef.init(Chunk.empty[Int])
                closing   <- Latch.init(1)
                proceed   <- Latch.init(1)
                first     <- AtomicBoolean.init(true)
                sig = source(a, b, reads).map(rendering)
                _ <- Fiber.init(sig.observe(ri) { img =>
                    delivered.updateAndGet(_.append(img.n)).andThen(first.getAndSet(false).map { isFirst =>
                        if isFirst then Scope.ensure(closing.release.andThen(proceed.await)) else Kyo.unit
                    })
                })
                _ <- reads.await(2)
                _ <- a.set(1)
                // The loop has seen the change and is closing the first image's Scope; the source goes back while it does.
                _ <- closing.await
                _ <- a.set(0)
                _ <- proceed.release
                // The first read after the close is at most r + 1, so read r + 2 starting means it has been handled.
                r    <- reads.get
                _    <- reads.await(r + 2)
                seen <- delivered.get
            yield assert(
                seen == Chunk(0, 0),
                s"delivered $seen, expected Chunk(0, 0): the value current after the close is new to the loop"
            )
        }
    }

    "a map chain" - {

        "an unchanged intermediate value delivers nothing, though the final image would be built afresh" in {
            for
                ref       <- Signal.initRef(0)
                delivered <- AtomicInt.init
                // A row of a list: selected or not, rendered to an image that is never `==` to a fresh one.
                sig = ref.map(_ == 1).map(selected => Img(if selected then 1 else 0))
                _ <- Fiber.init(sig.observe(_ => delivered.incrementAndGet.unit))
                _ <- assertEventually(delivered.get.map(_ == 1))
                _ <- assertEventually(ref.waiters.map(_ == 1))
                // Neither write changes `_ == 1`; the observer has handled each once it parks on the next promise again.
                _ <- ref.set(2)
                _ <- assertEventually(ref.waiters.map(_ == 1))
                _ <- ref.set(3)
                _ <- assertEventually(ref.waiters.map(_ == 1))
                _ <- ref.set(1)
                _ <- assertEventually(delivered.get.map(_ >= 2))
                _ <- assertEventually(ref.waiters.map(_ == 1))
                d <- delivered.get
            yield assert(d == 2, s"writes 2, 3, 1 delivered ${d - 1} times, expected once: only the write to 1 changes `_ == 1`")
        }
    }

    "streamChanges on a combinator" - {

        "an idle stream arms no repair timer and does not re-read its source" in {
            Clock.withTimeControl { control =>
                for
                    a     <- Signal.initRef(0)
                    b     <- Signal.initRef(0)
                    reads <- initReads
                    first <- Latch.init(1)
                    fiber <- Fiber.initUnscoped(source(a, b, reads).streamChanges.foreach(_ => first.release))
                    _     <- first.await
                    r0    <- reads.get
                    // With a timer, each interval would fire it and the stream would read both sources again.
                    _    <- Kyo.foreachDiscard(1 to 3)(_ => control.advance(ri, 20.millis))
                    idle <- reads.get
                    _    <- fiber.interrupt
                yield assert(idle == r0, s"an idle stream read its source ${idle - r0} more times over 3 repair intervals, expected none")
            }
        }
    }

end SignalProjectedObserveTest
