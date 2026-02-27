package kyo

class SignalTest extends Test:

    "init" - {
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

        "initRefWith" - {
            "ok" in run {
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
            "ok" in run {
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
            "ok" in run {
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

        "use" in run {
            for
                ref <- Signal.initRef(1)
                v1  <- ref.use(_ * 2)
                _   <- ref.set(2)
                v2  <- ref.use(_ * 2)
            yield assert(v1 == 2 && v2 == 4)
        }
    }

    "current and next" - {
        "current reads value" in run {
            for
                ref <- Signal.initRef(1)
                v   <- ref.current
            yield assert(v == 1)
        }

        "next waits for change" in run {
            for
                ref     <- Signal.initRef(1)
                started <- Latch.init(1)
                f       <- Fiber.initUnscoped(started.release.andThen(ref.next))
                _       <- started.await
                _       <- ref.set(2)
                v       <- f.get
            yield assert(v == 2)
        }
    }

    "map" - {
        "transforms current" in run {
            for
                ref <- Signal.initRef(1)
                mapped = ref.map(_ * 2)
                v1 <- mapped.current
                _  <- ref.set(2)
                v2 <- mapped.current
            yield assert(v1 == 2 && v2 == 4)
        }

        "transforms next" in run {
            for
                ref <- Signal.initRef(1)
                mapped = ref.map(_ * 2)
                started <- Latch.init(1)
                f       <- Fiber.initUnscoped(started.release.andThen(mapped.next))
                _       <- started.await
                _       <- ref.set(5)
                v       <- f.get
            yield assert(v == 10)
        }
    }

    "flatMap" - {
        "current reads through inner signal" in run {
            for
                outer  <- Signal.initRef(1)
                inner1 <- Signal.initRef(10)
                inner2 <- Signal.initRef(20)
                flat = outer.flatMap(v => if v == 1 then inner1 else inner2)
                v1 <- flat.current
                _  <- inner1.set(11)
                v2 <- flat.current
                _  <- outer.set(2)
                v3 <- flat.current
                _  <- inner2.set(21)
                v4 <- flat.current
            yield assert(v1 == 10 && v2 == 11 && v3 == 20 && v4 == 21)
        }

        "next fires on inner change" in run {
            for
                outer  <- Signal.initRef(1)
                inner1 <- Signal.initRef(10)
                inner2 <- Signal.initRef(20)
                flat = outer.flatMap(v => if v == 1 then inner1 else inner2)
                started <- Latch.init(1)
                f       <- Fiber.initUnscoped(started.release.andThen(flat.next))
                _       <- started.await
                _       <- Async.sleep(10.millis)
                _       <- inner1.set(11)
                v       <- f.get
            yield assert(v == 11)
        }

        "next fires on outer change and switches inner" in run {
            for
                outer  <- Signal.initRef(1)
                inner1 <- Signal.initRef(10)
                inner2 <- Signal.initRef(20)
                flat = outer.flatMap(v => if v == 1 then inner1 else inner2)
                started <- Latch.init(1)
                f       <- Fiber.initUnscoped(started.release.andThen(flat.next))
                _       <- started.await
                _       <- Async.sleep(10.millis)
                _       <- outer.set(2)
                v       <- f.get
            yield assert(v == 20)
        }

        "streamChanges tracks inner switches" in run {
            for
                outer  <- Signal.initRef(1)
                inner1 <- Signal.initRef(10)
                inner2 <- Signal.initRef(20)
                flat = outer.flatMap(v => if v == 1 then inner1 else inner2)
                f      <- Fiber.initUnscoped(flat.streamChanges.take(4).run)
                _      <- Async.sleep(10.millis)
                _      <- inner1.set(11)
                _      <- Async.sleep(10.millis)
                _      <- outer.set(2)
                _      <- Async.sleep(10.millis)
                _      <- inner2.set(21)
                _      <- Async.sleep(10.millis)
                values <- f.get
            yield assert(values == Chunk(10, 11, 20, 21))
        }
    }

    "zip" - {
        "current reads both" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef("x")
                zipped = a.zip(b)
                v1 <- zipped.current
                _  <- a.set(2)
                v2 <- zipped.current
                _  <- b.set("y")
                v3 <- zipped.current
            yield assert(v1 == (1, "x") && v2 == (2, "x") && v3 == (2, "y"))
        }

        "next waits for self then other" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef("x")
                zipped = a.zip(b)
                started <- Latch.init(1)
                f       <- Fiber.initUnscoped(started.release.andThen(zipped.next))
                _       <- started.await
                _       <- Async.sleep(10.millis)
                _       <- a.set(2)
                _       <- Async.sleep(10.millis)
                _       <- b.set("y")
                v       <- f.get
            yield assert(v == (2, "y"))
        }

        "next does not fire on only self change" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef("x")
                zipped = a.zip(b)
                started <- Latch.init(1)
                done    <- Latch.init(1)
                f <- Fiber.initUnscoped(
                    started.release.andThen(zipped.next).map { v =>
                        done.release.andThen(v)
                    }
                )
                _       <- started.await
                _       <- Async.sleep(10.millis)
                _       <- a.set(2)
                _       <- Async.sleep(10.millis)
                pending <- done.pending
                _       <- b.set("y")
                v       <- f.get
            yield assert(pending == 1 && v == (2, "y"))
        }
    }

    "zipLatest" - {
        "current reads both" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef("x")
                zl = a.zipLatest(b)
                v1 <- zl.current
                _  <- a.set(2)
                v2 <- zl.current
                _  <- b.set("y")
                v3 <- zl.current
            yield assert(v1 == (1, "x") && v2 == (2, "x") && v3 == (2, "y"))
        }

        "next fires on either change" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef("x")
                zl = a.zipLatest(b)
                started <- Latch.init(1)
                f       <- Fiber.initUnscoped(started.release.andThen(zl.next))
                _       <- started.await
                _       <- Async.sleep(10.millis)
                _       <- b.set("y")
                v       <- f.get
            yield assert(v == (1, "y"))
        }

        "next fires on self change" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef("x")
                zl = a.zipLatest(b)
                started <- Latch.init(1)
                f       <- Fiber.initUnscoped(started.release.andThen(zl.next))
                _       <- started.await
                _       <- Async.sleep(10.millis)
                _       <- a.set(2)
                v       <- f.get
            yield assert(v == (2, "x"))
        }

        "streamChanges fires repeatedly" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef("x")
                zl = a.zipLatest(b)
                f      <- Fiber.initUnscoped(zl.streamChanges.take(3).run)
                _      <- Async.sleep(10.millis)
                _      <- a.set(2)
                _      <- Async.sleep(10.millis)
                _      <- b.set("y")
                _      <- Async.sleep(10.millis)
                values <- f.get
            yield assert(values == Chunk((1, "x"), (2, "x"), (2, "y")))
        }
    }

    "collectAll" - {
        "current reads all" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef(2)
                c <- Signal.initRef(3)
                all = Signal.collectAll(Seq(a, b, c))
                v1 <- all.current
                _  <- b.set(20)
                v2 <- all.current
            yield assert(v1 == Chunk(1, 2, 3) && v2 == Chunk(1, 20, 3))
        }

        "next waits for all to change" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef(2)
                all = Signal.collectAll(Seq(a, b))
                started <- Latch.init(1)
                done    <- Latch.init(1)
                f <- Fiber.initUnscoped(
                    started.release.andThen(all.next).map { v =>
                        done.release.andThen(v)
                    }
                )
                _       <- started.await
                _       <- Async.sleep(10.millis)
                _       <- a.set(10)
                _       <- Async.sleep(10.millis)
                pending <- done.pending
                _       <- b.set(20)
                v       <- f.get
            yield assert(pending == 1 && v == Chunk(10, 20))
        }

        "single signal" in run {
            for
                a <- Signal.initRef(42)
                all = Signal.collectAll(Seq(a))
                v <- all.current
            yield assert(v == Chunk(42))
        }

        "empty" in run {
            val all = Signal.collectAll(Seq.empty[Signal[Int]])
            for v <- all.current
            yield assert(v == Chunk.empty)
        }
    }

    "collectAllLatest" - {
        "current reads all" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef(2)
                c <- Signal.initRef(3)
                all = Signal.collectAllLatest(Seq(a, b, c))
                v1 <- all.current
                _  <- b.set(20)
                v2 <- all.current
            yield assert(v1 == Chunk(1, 2, 3) && v2 == Chunk(1, 20, 3))
        }

        "next fires on any change" in run {
            for
                a <- Signal.initRef(1)
                b <- Signal.initRef(2)
                c <- Signal.initRef(3)
                all = Signal.collectAllLatest(Seq(a, b, c))
                started <- Latch.init(1)
                f       <- Fiber.initUnscoped(started.release.andThen(all.next))
                _       <- started.await
                _       <- Async.sleep(10.millis)
                _       <- c.set(30)
                v       <- f.get
            yield assert(v == Chunk(1, 2, 30))
        }

        "single signal" in run {
            for
                a <- Signal.initRef(42)
                all = Signal.collectAllLatest(Seq(a))
                v <- all.current
            yield assert(v == Chunk(42))
        }

        "empty" in run {
            val all = Signal.collectAllLatest(Seq.empty[Signal[Int]])
            for v <- all.current
            yield assert(v == Chunk.empty)
        }
    }

    "streamCurrent" - {
        "emits current value repeatedly" in run {
            for
                ref <- Signal.initRef(1)
                stream = ref.streamCurrent.take(3)
                values <- stream.run
            yield assert(values == Chunk(1, 1, 1))
        }
    }

    "streamChanges" - {
        "emits only on value change" in run {
            for
                ref    <- Signal.initRef(1)
                f      <- Fiber.initUnscoped(ref.streamChanges.take(3).run)
                _      <- Async.sleep(10.millis)
                _      <- ref.set(2)
                _      <- Async.sleep(10.millis)
                _      <- ref.set(2) // duplicate ignored
                _      <- Async.sleep(10.millis)
                _      <- ref.set(3)
                _      <- Async.sleep(10.millis)
                values <- f.get
            yield assert(values == Chunk(1, 2, 3))
        }
    }

    "awaitAny" - {
        "next works after losing a race" in run {
            for
                a        <- Signal.initRef(1)
                b        <- Signal.initRef("x")
                started  <- Latch.init(1)
                raceF    <- Fiber.initUnscoped(started.release.andThen(Signal.awaitAny(Seq(a, b))))
                _        <- started.await
                _        <- Async.sleep(10.millis)
                _        <- a.set(2)
                _        <- raceF.get
                started2 <- Latch.init(1)
                f        <- Fiber.initUnscoped(started2.release.andThen(b.next))
                _        <- started2.await
                _        <- Async.sleep(10.millis)
                _        <- b.set("y")
                v        <- f.get
            yield assert(v == "y")
        }
    }

    "concurrency" - {
        val repeats = 50

        "parallel updates" in run {
            (for
                ref <- Signal.initRef(0)
                _   <- Async.fill(10, 10)(ref.updateAndGet(_ + 1))
                v   <- ref.get
            yield assert(v == 10))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent reads and writes" in run {
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
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

    }

end SignalTest
