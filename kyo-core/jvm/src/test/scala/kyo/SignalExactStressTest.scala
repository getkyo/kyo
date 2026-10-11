package kyo

import AllowUnsafe.embrace.danger
import kyo.Signal.SignalRef

/** JVM-only stress tests for the exact observation of combinators (`Signal.readExact` / `Signal.ExactRead`).
  *
  * They need parallel threads: the races are between a fiber registering one `ExactRead` on several next-change promises and a write that
  * completes one of those promises or an interrupt of the fiber. The two registration races run their racing side on a thread (see
  * [[Racer]]), the others on fibers. Every observation uses `observe(neverRepairs)` or awaits an `ExactRead`
  * directly, so a repair timer cannot hide a lost wakeup: it shows up as a missed deadline. The rounds draw their parameters from a `Random`
  * with a fixed seed, which every failure message names.
  */
class SignalExactStressTest extends kyo.test.Test[Any]:

    private val seed = 2179
    // Finite so that `zip` and `zipAll` observe exactly, and far longer than a round, so it never fires.
    private val neverRepairs = 1.hour
    private val deadline     = 2.seconds
    private val maxFailures  = 5
    private val minSources   = 2
    private val maxSources   = 8
    private val spinChecks   = 10_000
    private val pollInterval = 1.millis

    private val registrationRounds   = 40_000
    private val maxInterruptDelayNs  = 30_000
    private val deregisterRounds     = 60
    private val otherCallbacks       = 20_000
    private val maxDeregisterDelayNs = 100_000
    private val combinatorRounds     = 1500
    private val maxWrites            = 200
    private val maxWritePauseNs      = 4_000
    private val maxObserverWorkNs    = 10_000
    private val maxInterruptAfterNs  = 50_000

    /** What one combinator round needs: the observed signal over `sources`, and the waiter count each source holds while the observer
      * is idle on the final value.
      */
    private case class Setup(
        sources: Chunk[SignalRef.Unsafe[Int]],
        signal: Signal[Chunk[Int]],
        idleWaiters: () => Chunk[Int],
        expected: () => Chunk[Int],
        monotonic: Boolean
    )

    private def foldWith(
        refs: Chunk[SignalRef.Unsafe[Int]],
        step: (Signal[Chunk[Int]], Signal[Int]) => Signal[Chunk[Int]]
    ): Signal[Chunk[Int]] =
        refs.tail.foldLeft(refs.head.safe.map(Chunk(_)): Signal[Chunk[Int]])((acc, r) => step(acc, r.safe))

    private def plain(refs: Chunk[SignalRef.Unsafe[Int]], sig: Signal[Chunk[Int]]): Setup =
        Setup(refs, sig, () => refs.map(_ => 1), () => refs.map(_.get()), monotonic = true)

    private val kinds: Chunk[(String, Int => Setup)] = Chunk(
        "combineLatest" -> { n =>
            val refs = sources(n)
            plain(refs, foldWith(refs, (acc, s) => acc.combineLatest(s).map((c, v) => c.append(v))))
        },
        "combineLatestAll" -> { n =>
            val refs = sources(n)
            plain(refs, Signal.combineLatestAll(refs.map(_.safe)))
        },
        "zip" -> { n =>
            val refs = sources(n)
            plain(refs, foldWith(refs, (acc, s) => acc.zip(s).map((c, v) => c.append(v))))
        },
        "zipAll" -> { n =>
            val refs = sources(n)
            plain(refs, Signal.zipAll(refs.map(_.safe)))
        },
        "switchMap" -> { n =>
            // Source 0 selects one of the other sources; the value is (selected index, its value), so consecutive values are distinct
            // exactly when the selection or the selected source moved.
            val refs   = sources(n)
            val inners = refs.tail.zipWithIndex.map((r, i) => r.safe.map(v => Chunk(i, v)))
            val sig    = refs.head.safe.switchMap(sel => inners(sel % inners.length))
            def sel    = refs.head.get() % inners.length
            Setup(
                refs,
                sig,
                () => Chunk(1).concat(Chunk.from(0 until inners.length).map(i => if i == sel then 1 else 0)),
                () => Chunk(sel, refs(sel + 1).get()),
                monotonic = false
            )
        }
    )

    "ExactRead under a racing write or interrupt" - {

        // Each round reads fresh refs into one ExactRead and parks a fiber on `awaitChange`. The racer thread waits until the fiber has
        // registered on ref `j`, which puts it inside or just past the registration loop, then writes ref `k`. With `k > j` the write
        // can land before `k` is registered (needs the re-check after `watch`); with `k <= j` it completes a promise already registered
        // while the loop still runs (needs the `done()` re-check, or the registrations made after the completion leak).
        "a write racing registration always wakes the waiter and leaves no registration".timeout(120.seconds) in {
            raceRounds(registrationRounds) { round =>
                for
                    n    <- sourceCount
                    j    <- Random.nextInt(n)
                    k    <- Random.nextInt(n)
                    refs <- Sync.defer(sources(n))
                    read <- exactRead(refs)
                yield
                    var waiter = Maybe.empty[Fiber.Unsafe[Unit, Any]]
                    Race(
                        onTest = () => waiter = Present(Fiber.Unsafe.init(read.awaitChange)),
                        onRacer = () =>
                            spinOnThread(refs(j).waiters() > 0)
                            refs(k).set(1)
                        ,
                        check = () =>
                            for
                                woke     <- eventually(waiter.exists(_.done()))
                                _        <- Sync.defer(waiter.foreach(f => discard(f.interrupt())))
                                released <- eventually(refs.forall(_.waiters() == 0))
                            yield
                                val at = s"round $round (n=$n, j=$j, k=$k)"
                                if !woke then Chunk(s"$at: lost wakeup, waiters ${waitersOf(refs)}")
                                else if !released then Chunk(s"$at: leaked registration, waiters ${waitersOf(refs)}")
                                else Chunk.empty
                    )
                end for
            }.map(assertNone)
        }

        // Each round parks a fiber on a fresh ExactRead, and the racer thread interrupts it either once it has registered on ref `j` or
        // after a random delay (which also lands before it runs or while it runs). In a third of the rounds a write to `j` races too.
        // Once the fiber is done no ref may hold a registration.
        "an interrupt racing registration leaves no registration".timeout(120.seconds) in {
            raceRounds(registrationRounds) { round =>
                for
                    n      <- sourceCount
                    j      <- Random.nextInt(n)
                    mode   <- Random.nextInt(3)
                    delay  <- Random.nextInt(maxInterruptDelayNs)
                    refs   <- Sync.defer(sources(n))
                    read   <- exactRead(refs)
                    waiter <- Sync.defer(Fiber.Unsafe.init(read.awaitChange))
                yield Race(
                    onTest = () => (),
                    onRacer = () =>
                        if mode == 0 then spinOnThread(refs(j).waiters() > 0) else spinOnThread(false, delay.toLong)
                        if mode == 2 then refs(j).set(1)
                        discard(waiter.interrupt())
                    ,
                    check = () =>
                        for
                            stopped  <- eventually(waiter.done())
                            released <- eventually(refs.forall(_.waiters() == 0))
                        yield
                            val at = s"round $round (n=$n, j=$j, mode=$mode)"
                            if !stopped then Chunk(s"$at: the fiber never finished after the interrupt")
                            else if !released then Chunk(s"$at: leaked registration, waiters ${waitersOf(refs)}")
                            else Chunk.empty
                )
                end for
            }.map(assertNone)
        }

        // A write to `b` completes the observer's ExactRead and then removes it from `a` on the writer's thread. Many callbacks
        // registered on `a` after it make that removal slow, and the observer is interrupted at a random moment after the write. Once
        // its result is set, `a` must hold only those callbacks: the interrupt may not end the observer while the writer still removes.
        "an interrupt while a write still deregisters leaves no registration once the result is set".timeout(120.seconds) in {
            runRounds(deregisterRounds) { round =>
                for
                    delay <- Random.nextInt(maxDeregisterDelayNs)
                    a     <- Sync.defer(SignalRef.Unsafe.init(0))
                    b     <- Sync.defer(SignalRef.Unsafe.init(0))
                    first <- Latch.init(1)
                    fiber <- Fiber.initUnscoped(a.safe.combineLatest(b.safe).observe(neverRepairs)(_ => first.release))
                    _     <- first.await
                    _     <- eventually(a.waiters() == 1 && b.waiters() == 1)
                    _     <- Sync.defer {
                        val promise = a.next().lower
                        (1 to otherCallbacks).foreach(_ => promise.onComplete(_ => ()))
                    }
                    write <- Fiber.initUnscoped(b.safe.set(1))
                    _     <- spinUntil(b.version() != 0L)
                    _     <- spinFor(delay)
                    _     <- fiber.interrupt
                    _     <- fiber.getResult
                    after <- Sync.defer((a.waiters(), b.waiters()))
                    _     <- write.get
                yield
                    if after == (otherCallbacks, 0) then Chunk.empty
                    else Chunk(s"round $round (delay=${delay}ns): waiters $after, expected ($otherCallbacks, 0)")
                end for
            }.map(assertNone)
        }
    }

    "observers of each combinator under concurrent writers and interrupts" - {

        // Each round: a few sources, one observer, one writer fiber per source writing 1..`writes` in order (so every source only grows),
        // with a little work in `f` so writes land while it runs and while it re-registers. In half the rounds the observer is
        // interrupted at a random moment while the writers run. Otherwise, once the writers stop, it must deliver the final values within
        // the deadline, then hold exactly the idle waiter count per source. Once interrupted it must hold none. Throughout, `f` must never
        // see the same value twice in a row, and (for the non-switching combinators) never a source going backwards.
        kinds.foreach { (name, mk) =>
            s"$name converges without the repair timer and leaves no registration".timeout(120.seconds) in {
                runRounds(combinatorRounds)(round => combinatorRound(mk, round)).map(assertNone)
            }
        }
    }

    private def combinatorRound(mk: Int => Setup, round: Int)(using Frame): Chunk[String] < Async =
        for
            n               <- sourceCount
            writes          <- Random.nextInt(maxWrites).map(_ + 1)
            work            <- Random.nextInt(maxObserverWorkNs)
            interruptMidway <- Random.nextBoolean
            interruptAfter  <- Random.nextInt(maxInterruptAfterNs)
            setup           <- Sync.defer(mk(n))
            last            <- AtomicRef.init(Maybe.empty[Chunk[Int]])
            violations      <- AtomicRef.init(Chunk.empty[String])
            observer        <- forkUnseeded(setup.signal.observe(neverRepairs)(deliver(setup, last, violations, work)))
            first           <- eventually(last.unsafe.get().nonEmpty)
            failures        <-
                if !first then observer.interrupt.andThen(Chunk("no first delivery"))
                else
                    for
                        writers  <- forkUnseeded(Async.foreachDiscard(setup.sources, setup.sources.size)(writer(_, writes)))
                        _        <- if interruptMidway then spinFor(interruptAfter).andThen(observer.interrupt.unit) else Kyo.unit
                        wrote    <- Abort.run[Timeout](Async.timeout(deadline)(writers.get))
                        idle     <- if interruptMidway then Kyo.lift(Chunk.empty[String]) else checkIdle(setup, last)
                        _        <- if interruptMidway then Kyo.unit else observer.interrupt.unit
                        stopped  <- Abort.run[Timeout](Async.timeout(deadline)(observer.getResult))
                        released <- eventually(setup.sources.forall(_.waiters() == 0))
                    yield idle
                        .concat(if wrote.isFailure then Chunk("the writers did not finish") else Chunk.empty)
                        .concat(if stopped.isFailure then Chunk("the observer did not stop") else Chunk.empty)
                        .concat(
                            if released then Chunk.empty
                            else Chunk(s"waiters after interrupt ${waitersOf(setup.sources)}")
                        )
            seen <- violations.get
        yield failures.concat(seen).map(f => s"round $round (n=$n, writes=$writes, interruptMidway=$interruptMidway): $f")

    /** Records `v` as the last delivery, noting a repeated or (for a monotonic setup) backwards value, then works for up to `work`. */
    private def deliver(setup: Setup, last: AtomicRef[Maybe[Chunk[Int]]], violations: AtomicRef[Chunk[String]], work: Int)(
        v: Chunk[Int]
    )(using Frame): Unit < Async =
        for
            prev <- last.getAndSet(Present(v))
            _    <- prev match
                case Present(p) if p == v => violations.updateAndGet(_.append(s"delivered $v twice in a row")).unit
                case Present(p) if setup.monotonic && p.indices.exists(i => v(i) < p(i)) =>
                    violations.updateAndGet(_.append(s"went backwards: $p then $v")).unit
                case _ => Kyo.unit
            pause <- Random.nextInt(work + 1)
            _     <- spinFor(pause)
        yield ()

    /** Waits for the final values once the writers stopped, then for the idle waiter count of every source. */
    private def checkIdle(setup: Setup, last: AtomicRef[Maybe[Chunk[Int]]])(using Frame): Chunk[String] < Async =
        val expected = setup.expected()
        eventually(last.unsafe.get().contains(expected)).map { converged =>
            if !converged then
                Chunk(
                    s"lost wakeup, last delivered ${last.unsafe.get()}, expected $expected, waiters ${waitersOf(setup.sources)}"
                ): Chunk[String] < Async
            else
                val idle = setup.idleWaiters()
                eventually(waitersOf(setup.sources) == idle).map { settled =>
                    if settled then Chunk.empty else Chunk(s"idle waiters ${waitersOf(setup.sources)}, expected $idle")
                }
        }
    end checkIdle

    /** Writes 1..`writes` to `src` in order, after about half of them pausing briefly. */
    private def writer(src: SignalRef.Unsafe[Int], writes: Int)(using Frame): Unit < Sync =
        Loop.indexed { i =>
            if i == writes then Loop.done
            else
                Sync.defer(src.set(i + 1))
                    .andThen(Random.nextInt(2 * maxWritePauseNs))
                    .map(pause => if pause < maxWritePauseNs then spinFor(pause) else Kyo.unit)
                    .andThen(Loop.continue)
        }

    /** One round raced between the test thread and the racer thread, which a barrier releases together: `onTest` runs on the test
      * thread and `onRacer` on the racer. `check` runs once both returned.
      */
    final private case class Race(onTest: () => Unit, onRacer: () => Unit, check: () => Chunk[String] < Async)

    /** A daemon thread that runs the racer side of each round.
      *
      * The registration races have a few-instruction window: the fiber registers one `ExactRead` on a handful of promises, and the racer
      * must write or interrupt while that loop runs. The fiber scheduler's per-task overhead makes that window effectively unhittable
      * from fibers, so the racer is a `java.lang.Thread` released by a `CyclicBarrier`, as in `GateJvmTest`. Verified by mutation: a
      * fiber-based racer missed a removed re-check after `watch` in 2 of 3 runs, and a removed `done()` re-check in 1 of 3, at a cost
      * of about 10 ms per round.
      */
    final private class Racer:
        private val barrier     = new java.util.concurrent.CyclicBarrier(2)
        private var round: Race = null
        private val thread      = new Thread(() =>
            var next = take()
            while next != null do
                next.onRacer()
                discard(barrier.await())
                next = take()
            end while
        )
        thread.setDaemon(true)
        thread.start()

        // Crossing the barrier publishes `round` to the racer.
        private def take(): Race =
            discard(barrier.await())
            round

        def run(race: Race): Unit =
            round = race
            discard(barrier.await())
            race.onTest()
            discard(barrier.await())
        end run

        def stop(): Unit =
            round = null
            discard(barrier.await())
    end Racer

    /** Like [[runRounds]], with each round raced against a [[Racer]]. Blocks a worker while a round runs. */
    private def raceRounds(rounds: Int)(round: Int => Race < Sync)(using Frame): Chunk[String] < Async =
        Sync.defer(new Racer).map { racer =>
            Sync.ensure(Sync.defer(racer.stop())) {
                runRounds(rounds)(r => round(r).map(race => Sync.defer(racer.run(race)).andThen(race.check())))
            }
        }

    /** Spins on the racer thread until `cond` holds or `nanos` passed, so it acts within the window being raced. */
    private def spinOnThread(cond: => Boolean, nanos: Long = deadline.toNanos): Unit =
        val end = java.lang.System.nanoTime() + nanos
        while !cond && java.lang.System.nanoTime() < end do Thread.onSpinWait()

    /** Runs `rounds` rounds with the round parameters drawn under the fixed seed, stopping after `maxFailures` failures. */
    private def runRounds(rounds: Int)(round: Int => Chunk[String] < Async)(using Frame): Chunk[String] < Async =
        Random.withSeed(seed) {
            Loop.indexed(Chunk.empty[String]) { (r, failures) =>
                if r == rounds || failures.size >= maxFailures then Loop.done(failures)
                else round(r).map(f => Loop.continue(failures.concat(f)))
            }
        }

    /** Forks `v` with an unseeded `Random`: the timing of concurrent fibers is not reproducible, and their draws would otherwise shift
      * the seeded parameters of later rounds.
      */
    private def forkUnseeded[A](v: A < Async)(using Frame): Fiber[A, Any] < Sync =
        Fiber.initUnscoped(Random.let(Random.live)(v))

    private def assertNone(failures: Chunk[String])(using Frame, kyo.test.AssertScope): Unit =
        assert(failures.isEmpty, s"seed $seed, ${failures.size} failures:\n${failures.mkString("\n")}")

    private def sourceCount(using Frame): Int < Sync = Random.nextInt(maxSources - minSources + 1).map(_ + minSources)

    private def sources(n: Int): Chunk[SignalRef.Unsafe[Int]] = Chunk.from(Seq.fill(n)(SignalRef.Unsafe.init(0)))

    private def waitersOf(refs: Chunk[SignalRef.Unsafe[Int]]): Chunk[Int] = refs.map(_.waiters())

    private def exactRead(refs: Chunk[SignalRef.Unsafe[Int]])(using Frame): Signal.ExactRead < Sync =
        Sync.defer {
            val read = new Signal.ExactRead(Map.empty)
            discard(Signal.readAllExact(refs.map(_.safe), read, wakesOnAnyInput = true))
            read
        }

    /** Re-checks `cond` without pausing until it holds or the deadline passes, so the caller acts within the window being raced. */
    private def spinUntil(cond: => Boolean)(using Frame): Boolean < Sync =
        Clock.deadline(deadline).map { d =>
            Loop.foreach {
                if cond then Loop.done(true)
                else d.isOverdue.map(over => if over then Loop.done(false) else Loop.continue)
            }
        }

    /** Spins for `nanos`, a pause too short for a sleep. */
    private def spinFor(nanos: Int)(using Frame): Unit < Sync =
        Clock.deadline(Duration.fromNanos(nanos)).map(d => Loop.whileTrue(d.isOverdue.map(!_))(Kyo.unit))

    /** Polls `cond` until it holds or the deadline passes: at once for a while, then sleeping between checks so no worker is held. */
    private def eventually(cond: => Boolean)(using Frame): Boolean < Async =
        Clock.deadline(deadline).map { d =>
            Loop.indexed { i =>
                if cond then Loop.done(true)
                else if i < spinChecks then Loop.continue
                else d.isOverdue.map(over => if over then Loop.done(false) else Async.sleep(pollInterval).andThen(Loop.continue))
            }
        }

end SignalExactStressTest
