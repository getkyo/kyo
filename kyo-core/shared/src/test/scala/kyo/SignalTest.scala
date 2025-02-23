package kyo

import kyo.debug.Debug

class SignalTest extends Test:

    "initRef" - {
        "ok" in run {
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
        "ok" in run {
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
        "ok" in run {
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

    "Ref operations" - {
        "get and set" in run {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.get
                _   <- ref.set(2)
                v2  <- ref.get
            yield assert(v1 == 1 && v2 == 2)
        }

        "getAndSet" in run {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.getAndSet(2)
                v2  <- ref.get
            yield assert(v1 == 1 && v2 == 2)
        }

        "compareAndSet" in run {
            for
                ref     <- Signal.initRef(1)
                success <- ref.compareAndSet(1, 2)
                fail    <- ref.compareAndSet(1, 3)
                v       <- ref.get
            yield assert(success && !fail && v == 2)
        }

        "getAndUpdate" in run {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.getAndUpdate(_ + 1)
                v2  <- ref.get
            yield assert(v1 == 1 && v2 == 2)
        }

        "updateAndGet" in run {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.updateAndGet(_ + 1)
                v2  <- ref.get
            yield assert(v1 == 2 && v2 == 2)
        }
    }

    "Signal operations" - {
        "current and next" in run {
            for
                ref     <- Signal.initRef(1)
                v1      <- ref.current
                started <- Latch.init(1)
                f       <- Async.run(started.release.andThen(ref.next))
                _       <- started.await
                _       <- ref.set(2)
                v2      <- f.get
            yield assert(v1 == 1 && v2 == 2)
        }

        "map" in run {
            for
                ref <- Signal.initRef(1)
                mapped = ref.map(_ * 2)
                v1 <- mapped.current
                _  <- ref.set(2)
                v2 <- mapped.current
            yield assert(v1 == 2 && v2 == 4)
        }

        "streamCurrent" in run {
            for
                ref <- Signal.initRef(1)
                stream = ref.streamCurrent.take(3)
                values <- stream.run
            yield assert(values == Chunk(1, 1, 1))
        }

        "streamChanges" in run {
            for
                ref    <- Signal.initRef(1)
                f      <- Async.run(ref.streamChanges.take(3).run)
                _      <- Async.sleep(2.millis)
                _      <- ref.set(2)
                _      <- Async.sleep(2.millis)
                _      <- ref.set(3)
                _      <- Async.sleep(2.millis)
                _      <- ref.set(3) // Should be ignored
                _      <- Async.sleep(2.millis)
                _      <- ref.set(4)
                _      <- Async.sleep(2.millis)
                values <- f.get
            yield assert(values == Chunk(2, 3, 4))
        }
    }

    "concurrency" - {
        val repeats = 50

        "parallel updates" in run {
            (for
                ref <- Signal.initRef(0)
                _   <- Async.parallelUnbounded((1 to 10).map(_ => ref.updateAndGet(_ + 1)))
                v   <- ref.get
            yield assert(v == 10))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent reads and writes" in run {
            (for
                ref <- Signal.initRef(0)
                readers <- Async.run(Async.parallelUnbounded(
                    (1 to 10).map(_ => Loop(0)(_ => ref.currentWith(v => if v < 10 then Loop.continue(v) else Loop.done(v))))
                ))
                writers <- Async.run(Async.parallelUnbounded(
                    (1 to 10).map(_ =>
                        Loop(()) { _ =>
                            ref.get.map { v =>
                                if v < 10 then
                                    ref.compareAndSet(v, v + 1).andThen(Loop.continue)
                                else
                                    Loop.done(v)
                                end if
                            }
                        }
                    )
                ))
                readResults  <- readers.get
                writeResults <- writers.get
                finalValue   <- ref.get
            yield assert(readResults.forall(_ == 10) && writeResults.forall(_ == 10) && finalValue == 10))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

    }

end SignalTest
