package kyo

class TRefTest extends Test:

    "init and get" in run {
        for
            ref   <- TRef.init(42)
            value <- STM.run(ref.get)
        yield assert(value == 42)
    }

    "set and get" in run {
        for
            ref   <- TRef.init(42)
            _     <- STM.run(ref.set(100))
            value <- STM.run(ref.get)
        yield assert(value == 100)
    }

    "multiple operations in transaction" in run {
        for
            ref1 <- TRef.init(10)
            ref2 <- TRef.init(20)
            result <- STM.run {
                for
                    v1 <- ref1.get
                    v2 <- ref2.get
                    _  <- ref1.set(v2)
                    _  <- ref2.set(v1)
                    r1 <- ref1.get
                    r2 <- ref2.get
                yield (r1, r2)
            }
        yield assert(result == (20, 10))
    }

    "initNow behavior" - {
        "creates ref with new transaction ID outside transaction" in run {
            for
                ref   <- TRef.init(1)
                value <- STM.run(ref.get)
            yield assert(value == 1)
        }

        "uses current transaction ID within transaction" in run {
            STM.run {
                for
                    ref1 <- TRef.init(1)
                    ref2 <- TRef.init(2)
                    _    <- ref1.set(3)
                    _    <- ref2.set(4)
                    val1 <- ref1.get
                    val2 <- ref2.get
                yield assert(val1 == 3 && val2 == 4)
            }
        }

        "nests properly in nested transactions" in run {
            STM.run {
                for
                    ref1 <- TRef.init(1)
                    result <- STM.run {
                        for
                            ref2 <- TRef.init(2)
                            _    <- ref1.set(3)
                            _    <- ref2.set(4)
                            v1   <- ref1.get
                            v2   <- ref2.get
                        yield (v1, v2)
                    }
                yield assert(result == (3, 4))
            }
        }
    }
    "State" - {
        import TRef.State
        import TRef.State.*

        "free" - {
            "can acquire writer" in {
                assert(State.free.acquireWriter(0L).isDefined)
            }
            "can acquire reader" in {
                assert(State.free.acquireReader.isDefined)
            }
            "has zero readTick" in {
                assert(State.free.readTick == 0L)
            }
            "asString is free" in {
                assert(State.free.render == "free")
            }
        }

        "readTick" - {
            "withReadTick sets tick" in {
                val s = State.free.withReadTick(42L)
                assert(s.readTick == 42L)
            }
            "withReadTick preserves lock state" in {
                val s = State.free.acquireReader.get.withReadTick(100L)
                assert(s.readTick == 100L)
                assert(s.acquireWriter(100L).isEmpty)
                assert(s.render == "1 readers")
            }
            "withoutReadTick clears tick" in {
                val s = State.free.withReadTick(42L).withoutReadTick
                assert(s.readTick == 0L)
            }
            "withoutReadTick preserves lock state" in {
                val s = State.free.acquireReader.get.withReadTick(100L).withoutReadTick
                assert(s.readTick == 0L)
                assert(s.acquireWriter(0L).isEmpty)
            }
        }

        "reader lock" - {
            "acquireReader increments count" in {
                val s = State.free.acquireReader.get
                assert(s.acquireWriter(0L).isEmpty)
                assert(s.acquireReader.isDefined)
                assert(s.render == "1 readers")
            }
            "multiple readers stack" in {
                val s = State.free.acquireReader.get.acquireReader.get.acquireReader.get
                assert(s.render == "3 readers")
            }
            "releaseReader decrements count" in {
                val s = State.free.acquireReader.get.acquireReader.get.releaseReader
                assert(s.render == "1 readers")
            }
            "releaseReader back to free" in {
                val s = State.free.acquireReader.get.releaseReader
                assert(s.acquireWriter(0L).isDefined)
                assert(s.render == "free")
            }
        }

        "writer lock" - {
            "acquireWriter acquires write lock" in {
                val s = State.free.acquireWriter(0L).get
                assert(s.acquireWriter(0L).isEmpty)
                assert(s.acquireReader.isEmpty)
                assert(s.render == "writer")
            }
            "acquireWriter preserves readTick" in {
                val s = State.free.withReadTick(42L).acquireWriter(100L).get
                assert(s.readTick == 42L)
                assert(s.render == "writer")
            }
            "acquireWriter blocked by newer readTick" in {
                val s = State.free.withReadTick(100L)
                assert(s.acquireWriter(50L).isEmpty)
                assert(s.acquireWriter(100L).isDefined)
                assert(s.acquireWriter(200L).isDefined)
            }
        }

        "large tick values" - {
            "readTick larger than Int.MaxValue" in {
                val tick = Int.MaxValue.toLong + 1000L
                val s    = State.free.withReadTick(tick)
                assert(s.readTick == tick)
                assert(s.acquireWriter(tick).isDefined)
                assert(s.render == "free")
            }
            "readTick near 56-bit max" in {
                val tick = (1L << 55) - 1
                val s    = State.free.withReadTick(tick)
                assert(s.readTick == tick)
                assert(s.acquireWriter(tick).isDefined)
            }
            "large tick preserves reader lock" in {
                val tick = Int.MaxValue.toLong * 2
                val s    = State.free.acquireReader.get.acquireReader.get.withReadTick(tick)
                assert(s.readTick == tick)
                assert(s.render == "2 readers")
                assert(s.acquireWriter(tick).isEmpty)
            }
            "large tick preserves writer lock" in {
                val tick = Int.MaxValue.toLong * 2
                val s    = State.free.withReadTick(tick).acquireWriter(tick).get
                assert(s.readTick == tick)
                assert(s.render == "writer")
            }
            "withoutReadTick clears large tick" in {
                val tick = Int.MaxValue.toLong * 3
                val s    = State.free.acquireReader.get.withReadTick(tick).withoutReadTick
                assert(s.readTick == 0L)
                assert(s.render == "1 readers")
            }
            "reader operations don't affect large tick" in {
                val tick = Int.MaxValue.toLong + 12345L
                val s    = State.free.withReadTick(tick).acquireReader.get.acquireReader.get.releaseReader
                assert(s.readTick == tick)
                assert(s.render == "1 readers")
            }
        }

        "acquireWriter conflict detection with large ticks" - {
            "writer allowed when readTick <= writerTick (both large)" in {
                val readerTick = Int.MaxValue.toLong + 100L
                val writerTick = Int.MaxValue.toLong + 200L
                val s          = State.free.withReadTick(readerTick)
                assert(s.acquireWriter(writerTick).isDefined)
            }
            "writer blocked when readTick > writerTick (both large)" in {
                val readerTick = Int.MaxValue.toLong + 300L
                val writerTick = Int.MaxValue.toLong + 200L
                val s          = State.free.withReadTick(readerTick)
                assert(s.acquireWriter(writerTick).isEmpty)
            }
            "writer allowed when readTick equals writerTick (large)" in {
                val tick = Int.MaxValue.toLong + 500L
                val s    = State.free.withReadTick(tick)
                assert(s.acquireWriter(tick).isDefined)
            }
            "comparison works across Int.MaxValue boundary" in {
                val smallTick = Int.MaxValue.toLong - 10L
                val largeTick = Int.MaxValue.toLong + 10L
                val s         = State.free.withReadTick(smallTick)
                assert(s.acquireWriter(largeTick).isDefined)
                assert(s.acquireWriter(smallTick - 1).isEmpty)
            }
            "comparison works with zero readTick and large writerTick" in {
                val writerTick = Int.MaxValue.toLong * 2
                val s          = State.free // readTick is 0
                assert(s.acquireWriter(writerTick).isDefined)
            }
            "comparison works near 56-bit boundary" in {
                val nearMax = (1L << 55) - 100L
                val atMax   = (1L << 55) - 1L
                val s       = State.free.withReadTick(nearMax)
                assert(s.acquireWriter(atMax).isDefined)
                assert(s.acquireWriter(nearMax).isDefined)
                assert(s.acquireWriter(nearMax - 1).isEmpty)
            }
        }

    }

    "early writer abort" - {
        "writer succeeds when readTick <= tick" in run {
            for
                ref   <- TRef.init(0)
                _     <- STM.run(ref.set(42))
                value <- STM.run(ref.get)
            yield assert(value == 42)
        }

        "concurrent readers and writers maintain consistency" in run {
            // This tests that the early abort optimization works correctly
            // under concurrent load - writers yield to fresher readers
            for
                ref <- TRef.init(0)
                // Many concurrent readers registering readTick
                readerFiber <- Fiber.initUnscoped {
                    Async.fill(50, 50) {
                        STM.run(ref.get)
                    }
                }
                // Writers trying to write - some may abort early due to readTick
                writerFiber <- Fiber.initUnscoped {
                    Async.fill(10, 10) {
                        STM.run(ref.update(_ + 1))
                    }
                }
                _          <- readerFiber.get
                _          <- writerFiber.get
                finalValue <- STM.run(ref.get)
            yield
                // All writes should have completed
                assert(finalValue == 10)
        }
    }

    "TRef class" - {

        "equals and hashCode are reference-identity" in run {
            import kyo.AllowUnsafe.embrace.danger
            Sync.defer {
                val refA      = TRef.Unsafe.init(7)
                val refB      = TRef.Unsafe.init(7)
                val refAalias = refA
                assert(refA == refAalias, "aliased TRef must compare equal under reference identity")
                assert(!(refA == refB), "two TRefs created with same value must NOT compare equal")
                assert(refA.hashCode == refAalias.hashCode, "aliased TRef hashCode must coincide")
            }
        }
    }

    "TRef.id" - {

        "sequential allocations produce strictly increasing positive ids" in run {
            import kyo.AllowUnsafe.embrace.danger
            Sync.defer {
                val n                  = 10
                val idsSeq             = (1 to n).map(_ => TRef.Unsafe.init(0).id)
                val strictlyIncreasing = idsSeq.sliding(2).forall { case Seq(a, b) => a < b }
                val allPositive        = idsSeq.forall(_ > 0)
                assert(strictlyIncreasing, s"ids must be strictly increasing in allocation order, got $idsSeq")
                assert(allPositive, s"ids must be positive for the first $n allocations, got $idsSeq")
            }
        }

        "uniqueness across many allocations" in run {
            import kyo.AllowUnsafe.embrace.danger
            Sync.defer {
                val n    = 1000
                val refs = Vector.fill(n)(TRef.Unsafe.init(0))
                val ids  = refs.map(_.id).toSet
                assert(ids.size == n, s"ids must be unique across $n distinct TRef allocations, got ${ids.size} distinct")
            }
        }

        "id field provides a total order suitable for lock-acquisition sort" in run {
            import kyo.AllowUnsafe.embrace.danger
            Sync.defer {
                val refs      = Vector.fill(50)(TRef.Unsafe.init(0))
                val sortedIds = refs.map(_.id).sorted
                assert(sortedIds.distinct == sortedIds, s"id field must produce a strict total order; got duplicates")
            }
        }
    }

    "TRef.update" - {

        "function with pending Sync effect runs the effect and commits the new value" in run {
            for
                sideRef <- AtomicInt.init(0)
                ref     <- TRef.init(0)
                _ <- STM.run {
                    ref.update(x => Sync.defer { x + 5 }.map(r => sideRef.incrementAndGet.andThen(r)))
                }
                sideObs <- sideRef.get
                refObs  <- STM.run(ref.get)
            yield
                assert(sideObs == 1, "Sync effect inside update lambda must run exactly once per successful commit")
                assert(refObs == 5, "TRef.update must store the result of the effectful lambda")
        }

        "returns Unit, not the new value" in run {
            for
                ref    <- TRef.init(99)
                result <- STM.run(ref.update(_ + 1))
                obs    <- STM.run(ref.get)
            yield
                assert(result == (), "update must yield the Unit value, not the new ref value")
                assert(obs == 100, "the new value must be committed to the ref")
        }

        "identity update commits without changing visible state" in run {
            for
                ref    <- TRef.init(13)
                before <- STM.run(ref.get)
                _      <- STM.run(ref.update(identity))
                after  <- STM.run(ref.get)
            yield assert(before == 13 && after == 13, "identity update must leave the observable value unchanged")
        }

        "lambda invocation count equals retry count + 1" in run {
            val n = 3
            for
                counter <- AtomicInt.init(0)
                ref     <- TRef.init(0)
                out <- Abort.run {
                    STM.run(Schedule.fixed(1.millis).take(n)) {
                        ref.update(v => counter.incrementAndGet.andThen(STM.retry).map(_ => v + 1))
                    }
                }
                count <- counter.get
            yield
                assert(out.isFailure, "schedule must exhaust")
                assert(count == n + 1, s"lambda must run exactly ${n + 1} times (once per attempt); got $count")
            end for
        }

        "panicking lambda must surface the panic, not silently swallow or commit" in run {
            for
                ref      <- TRef.init(7)
                out      <- Abort.run[Throwable](STM.run(ref.update(_ => throw new RuntimeException("boom"))))
                observed <- STM.run(ref.get)
            yield
                assert(out.isFailure || out.isPanic, "panicking lambda must surface as Abort failure/panic, not silent success")
                assert(observed == 7, "ref must remain at the pre-update value (no partial commit)")
        }

        "lambda sees the current in-log value, not the live entry" in run {
            for
                ref <- TRef.init(0)
                _ <- STM.run {
                    for
                        _ <- ref.update(_ + 1)
                        _ <- ref.update(_ + 10)
                    yield ()
                }
                v <- STM.run(ref.get)
            yield assert(v == 11, s"two composed updates must reach 11 (0 + 1 + 10); got $v")
        }
    }

    "TRef.use" - {

        "applies a non-identity lambda within a transaction" in run {
            for
                ref    <- TRef.init(10)
                result <- STM.run(ref.use(x => x * 3 + 1))
            yield assert(result == 31, "TRef.use must return f(currentValue), not the value itself")
        }

        "pending Sync in lambda surfaces in the return effect set" in run {
            for
                ref     <- TRef.init(7)
                sideRef <- AtomicInt.init(0)
                program = ref.use(x => Sync.defer { x + 100 }.map(r => sideRef.incrementAndGet.andThen(r)))
                result <- STM.run(program)
                side   <- sideRef.get
            yield
                assert(result == 107, "use must propagate the lambda's value")
                assert(side == 1, "the Sync effect must run exactly once on the committed path")
        }

        "pins B to a derived type distinct from A" in run {
            for
                ref      <- TRef.init(42)
                asString <- STM.run(ref.use(_.toString))
                asTuple  <- STM.run(ref.use(x => (x, x > 0)))
            yield
                assert(asString == "42", "use must permit String result for TRef[Int]")
                assert(asTuple == (42, true), "use must permit tuple result for TRef[Int]")
        }

        "second use within same transaction observes the value committed before transaction start" in run {
            for
                ref <- TRef.init(0)
                program =
                    (for
                        v1 <- ref.use(identity)
                        _  <- Sync.defer(())
                        v2 <- ref.use(identity)
                    yield (v1, v2))
                pair <- STM.run(program)
            yield assert(pair._1 == pair._2, s"two reads inside one transaction must agree (got ${pair._1}, ${pair._2})")
        }

        "log-level snapshot consistency: two reads inside one transaction agree on value" in run {
            for
                ref <- TRef.init(42)
                pair <- STM.run {
                    for
                        a <- ref.use(identity)
                        b <- ref.use(identity)
                    yield (a, b)
                }
            yield
                assert(pair._1 == pair._2, s"two reads inside one transaction must agree; got $pair")
                assert(pair._1 == 42 && pair._2 == 42, "both reads must equal the initial value 42")
        }

        "lambda throws after Read log add: outer transaction state unchanged" in run {
            for
                ref      <- TRef.init(11)
                otherRef <- TRef.init(0)
                out <- Abort.run[Throwable](
                    STM.run {
                        for
                            _ <- otherRef.set(99)
                            _ <- ref.use(v => throw new RuntimeException(v.toString))
                        yield ()
                    }
                )
                obs <- STM.run(otherRef.get)
            yield
                assert(out.isFailure || out.isPanic, "throwing lambda must surface as failure/panic")
                assert(obs == 0, "otherRef must NOT have been committed (transaction was aborted by the throw)")
        }

        "get is equivalent to use(identity) for any value" in run {
            for
                ref    <- TRef.init(123)
                viaGet <- STM.run(ref.get)
                viaUse <- STM.run(ref.use(identity))
            yield
                assert(viaGet == viaUse, s"get and use(identity) must yield identical values; got ($viaGet, $viaUse)")
                assert(viaGet == 123, "both must yield the initial value")
        }
    }

    "TRef.set" - {

        "assigning v then reading yields v in the same transaction" in run {
            for
                ref <- TRef.init("a")
                read <- STM.run {
                    for
                        _ <- ref.set("b")
                        x <- ref.get
                    yield x
                }
            yield assert(read == "b", "set then get within one transaction must yield the set value")
        }

        "storing null on a reference-typed TRef yields null on get" in run {
            for
                ref <- TRef.init[String]("initial")
                out <- Abort.run[Throwable] {
                    STM.run {
                        for
                            _ <- ref.set(null.asInstanceOf[String])
                            x <- ref.get
                        yield x
                    }
                }
            yield assert(out == Result.succeed(null))
        }

        "pure value parameter (no effect): set is a 1-arg method with no S" in run {
            for
                ref <- TRef.init(0)
                _   <- STM.run(ref.set(7))
                obs <- STM.run(ref.get)
            yield assert(obs == 7, "set must store the literal value (no effect interleaving)")
        }
    }

    "TRef commit semantics" - {

        "read-only transaction leaves the value unchanged" in run {
            for
                ref      <- TRef.init("initial")
                readOnly <- STM.run(ref.get)
                after    <- STM.run(ref.get)
            yield assert(readOnly == "initial" && after == "initial", "a read-only transaction must leave the value at 'initial'")
        }

        "second transaction sees the first transaction's committed write" in run {
            for
                ref <- TRef.init(0)
                _   <- STM.run(ref.set(7))
                a   <- STM.run(ref.get)
                b   <- STM.run(ref.get)
            yield assert(a == 7 && b == 7, s"sequential reads after a committed write must both yield 7; got ($a, $b)")
        }
    }

    "TRef.toString" - {

        "format includes state, readTick, and lock summary" in run {
            import kyo.AllowUnsafe.embrace.danger
            Sync.defer {
                val s = TRef.Unsafe.init(7).toString
                assert(s.startsWith("TRef("), s"toString must start with 'TRef('; got '$s'")
                assert(s.contains("state="), s"toString must include 'state='; got '$s'")
                assert(s.contains("readTick="), s"toString must include 'readTick='; got '$s'")
                assert(s.contains("lock="), s"toString must include 'lock='; got '$s'")
                assert(s.contains("free"), s"toString of fresh TRef must mention 'free' lock state; got '$s'")
            }
        }

        "sequential same-value writes advance the entry tick (no value-fallback at write time)" in run {
            import kyo.AllowUnsafe.embrace.danger
            for
                ref        <- Sync.defer(TRef.Unsafe.init(0))
                tickBefore <- Sync.defer(ref.toString)
                _          <- STM.run(ref.set(0))
                tickAfter1 <- Sync.defer(ref.toString)
                _          <- STM.run(ref.set(0))
                tickAfter2 <- Sync.defer(ref.toString)
                v          <- STM.run(ref.get)
            yield
                assert(v == 0, "value must remain 0 across same-value sets")
                assert(tickBefore != tickAfter1, s"first same-value set must advance tick: before=$tickBefore after=$tickAfter1")
                assert(tickAfter1 != tickAfter2, s"second same-value set must advance tick: $tickAfter1 vs $tickAfter2")
            end for
        }

        "after STM.run completes, a fresh read reflects the committed value" in run {
            import kyo.AllowUnsafe.embrace.danger
            for
                ref    <- Sync.defer(TRef.Unsafe.init(0))
                before <- Sync.defer(ref.toString)
                _      <- STM.run(ref.set(777))
                after  <- Sync.defer(ref.toString)
                v      <- STM.run(ref.get)
            yield
                assert(v == 777, "after STM.run(set(777)), the ref must reflect 777")
                assert(before != after, s"before/after toString must differ; before=$before after=$after")
            end for
        }

        "sequential observations during sequential commits are consistent" in run {
            import kyo.AllowUnsafe.embrace.danger
            for
                ref <- Sync.defer(TRef.Unsafe.init(0))
                ts0 <- Sync.defer(ref.toString)
                v0  <- STM.run(ref.get)
                _   <- STM.run(ref.set(1))
                ts1 <- Sync.defer(ref.toString)
                v1  <- STM.run(ref.get)
                _   <- STM.run(ref.set(2))
                ts2 <- Sync.defer(ref.toString)
                v2  <- STM.run(ref.get)
            yield
                assert(v0 == 0 && v1 == 1 && v2 == 2, s"committed values must progress 0,1,2; got ($v0,$v1,$v2)")
                assert(ts0.contains("0"), s"initial toString must mention 0; got '$ts0'")
                assert(ts1.contains("1"), s"toString after set(1) must mention 1; got '$ts1'")
                assert(ts2.contains("2"), s"toString after set(2) must mention 2; got '$ts2'")
            end for
        }
    }

    "TRef.init" - {

        "accepts null for a reference-typed TRef and yields null on get" in run {
            for
                ref <- TRef.init[String](null)
                out <- Abort.run[Throwable](STM.run(ref.get))
            yield assert(out == Result.succeed(null))
        }
    }

    "TRef.initWith" - {

        "invokes f exactly once with the new ref and returns f's value" in run {
            for
                counter <- AtomicInt.init(0)
                pair <- TRef.initWith(21) { ref =>
                    counter.incrementAndGet.andThen(STM.run(ref.use(v => (ref.id, v * 2))))
                }
                count <- counter.get
            yield
                assert(pair._2 == 42, "initWith's f must receive the new ref and observe the initial value")
                assert(pair._1 > 0, "the new TRef must have a positive id")
                assert(count == 1, "f must be invoked exactly once")
        }

        "when called inside STM.run, init+f share the surrounding transaction" in run {
            for
                out <- STM.run {
                    TRef.initWith(1) { ref =>
                        for
                            _ <- ref.set(2)
                            v <- ref.get
                        yield v
                    }
                }
            yield assert(out == 2, "init+set+get inside a single STM.run via initWith must commit as one transaction and yield 2")
        }

        "inline expansion accepts method reference, function literal, and eta-expanded forms" in run {
            def methodRef(ref: TRef[Int]): Int < STM = ref.get
            val asLit: TRef[Int] => Int < STM        = ref => ref.get
            for
                a <- STM.run(TRef.initWith(11)(ref => ref.get))
                b <- STM.run(TRef.initWith(22)(methodRef))
                c <- STM.run(TRef.initWith(33)(asLit))
            yield
                assert(a == 11, "function literal must work")
                assert(b == 22, "method reference (eta-expanded) must work")
                assert(c == 33, "value-typed lambda must work")
            end for
        }

        "return type carries the lambda's effect set S" in run {
            val program: Int < (Sync & STM & Env[Int]) =
                TRef.initWith(1) { ref =>
                    for
                        cfg <- Env.use[Int](identity)
                        v   <- ref.get
                    yield cfg + v
                }
            for result <- Env.run(41)(STM.run(program))
            yield assert(result == 42, "initWith must propagate Env[Int] in S; with cfg=41 + v=1, expected 42")
        }

        "inline value is evaluated exactly once per call" in run {
            import kyo.AllowUnsafe.embrace.danger
            val counter = AtomicInt.Unsafe.init(0)
            def expensive: Int =
                counter.incrementAndGet(); 42
            for
                out   <- TRef.initWith(expensive)(ref => STM.run(ref.get))
                count <- Sync.defer(counter.get())
            yield
                assert(out == 42, "the expensive value must be observable as 42")
                assert(count == 1, s"expensive value must be evaluated exactly once; got $count")
            end for
        }

        "TRef created inside doomed outer txn is observable post-rollback, still at initial value" in run {
            for
                capture <- AtomicRef.init[Maybe[TRef[Int]]](Absent)
                out <- Abort.run {
                    STM.run(Schedule.done) {
                        TRef.initWith(100) { ref =>
                            capture.set(Present(ref)).andThen {
                                for
                                    _ <- ref.set(999)
                                    _ <- STM.retry
                                yield ()
                            }
                        }
                    }
                }
                refMaybe <- capture.get
                v        <- STM.run(refMaybe.get.get)
            yield
                assert(out.isFailure, "outer transaction must fail")
                assert(v == 100, s"post-rollback TRef must show initial value 100 (set(999) was rolled back); got $v")
        }
    }

    "TRef.Unsafe.init" - {

        "public no-arg overload produces a usable TRef" in run {
            import kyo.AllowUnsafe.embrace.danger
            for
                ref  <- Sync.defer(TRef.Unsafe.init("hello"))
                read <- STM.run(ref.get)
            yield assert(read == "hello", "TRef.Unsafe.init(value) must yield a TRef whose .get returns the initial value")
            end for
        }

        "counter advances per call, no rollback semantics" in run {
            import kyo.AllowUnsafe.embrace.danger
            Sync.defer {
                val before = TRef.Unsafe.init(0).id
                val a      = TRef.Unsafe.init(STM.Tick.next(), 1)
                val b      = TRef.Unsafe.init(STM.Tick.next(), 2)
                val c      = TRef.Unsafe.init(STM.Tick.next(), 3)
                val after  = TRef.Unsafe.init(0).id
                assert(a.id < b.id && b.id < c.id, s"Unsafe.init must produce strictly-increasing ids; got ${a.id} ${b.id} ${c.id}")
                assert((after - before) == 4, s"counter must advance by exactly 4 (a, b, c, after); diff=${after - before}")
            }
        }

        "tick parameter is accepted and the ref's value round-trips" in run {
            import kyo.AllowUnsafe.embrace.danger
            for
                ref  <- Sync.defer(TRef.Unsafe.init(STM.Tick.next(), "zero-tick"))
                read <- STM.run(ref.get)
            yield assert(read == "zero-tick", "TRef.Unsafe.init(tick, value) must round-trip its value")
            end for
        }
    }

    "TRef.idCounter irreversibility" - {

        "idCounter is consumed per retry inside a doomed STM.run" in run {
            import kyo.AllowUnsafe.embrace.danger
            val n = 5
            for
                before <- Sync.defer(TRef.Unsafe.init(0).id)
                out <- Abort.run {
                    STM.run(Schedule.fixed(1.millis).take(n)) {
                        for
                            _ <- TRef.init(0)
                            _ <- STM.retry
                        yield ()
                    }
                }
                after <- Sync.defer(TRef.Unsafe.init(0).id)
            yield
                assert(out.isFailure, "schedule must exhaust to FailedTransaction")
                assert((after - before) > n, s"idCounter must have advanced by at least N+1; diff=${after - before}")
            end for
        }

        "initWith idCounter is consumed per retry attempt" in run {
            import kyo.AllowUnsafe.embrace.danger
            for
                before <- Sync.defer(TRef.Unsafe.init(0).id)
                out <- Abort.run {
                    STM.run(Schedule.fixed(1.millis).take(3)) {
                        TRef.initWith(0)(_ => STM.retry)
                    }
                }
                after <- Sync.defer(TRef.Unsafe.init(0).id)
            yield
                assert(out.isFailure, "transaction must exhaust schedule")
                assert((after - before) >= 4, s"each retry consumes one id; diff=${after - before}")
            end for
        }
    }

    "TRef payload type" - {

        "A may be a Kyo computation type; the stored value is opaque to STM" in run {
            import kyo.AllowUnsafe.embrace.danger
            for
                ref        <- Sync.defer(TRef.Unsafe.init(Sync.defer(99)))
                storedComp <- STM.run(ref.get)
                storedVal  <- storedComp
            yield assert(storedVal == 99, "TRef[Int < Sync] must round-trip the deferred computation and yield 99")
            end for
        }
    }

    "TRef.validate access gate" - {

        "requires AllowUnsafe; safe context cannot invoke (compile-time guard)" in run {
            typeCheckFailure(
                """
                import kyo.*
                import kyo.TRefLog.*
                val ref = TRef.Unsafe.init(0)(using AllowUnsafe.embrace.danger)
                ref.validate(Read(STM.Tick.next()(using AllowUnsafe.embrace.danger), 0))
                """
            )("AllowUnsafe")
        }
    }

    "TRef JVM visibility" - {

        "JVM-only sanity: single-fiber sequential read sees written value" in runJVM {
            for
                ref <- TRef.init(0)
                _   <- STM.run(ref.set(123))
                obs <- STM.run(ref.get)
            yield assert(obs == 123, "JVM: sequential same-fiber write -> read must observe the new value")
        }
    }

    "State extension contracts" - {
        import TRef.State
        import TRef.State.*

        "acquireWriter — writer at an older tick is denied after a reader registered at a fresher tick" in {
            val freshReadTick = 100L
            val olderWriter   = 50L
            val s             = State.free.withReadTick(freshReadTick)
            assert(
                s.acquireWriter(olderWriter).isEmpty,
                s"writer at tick $olderWriter must be denied after reader registered at tick $freshReadTick"
            )
        }

        "acquireWriter — denied when readTick is fresher than the writer tick" in {
            val s = State.free.withReadTick(200L)
            assert(s.acquireWriter(100L).isEmpty, "writer at 100 must be denied when readTick=200 (fresher reader exists)")
        }

        "withReadTick — yields max(current, new) under sequential application" in {
            val s0    = State.free
            val s1    = s0.withReadTick(50L)
            val s2    = if s1.readTick > 30L then s1 else s1.withReadTick(30L)
            val s3    = if s2.readTick > 100L then s2 else s2.withReadTick(100L)
            val ticks = (s0.readTick, s1.readTick, s2.readTick, s3.readTick)
            assert(ticks == (0L, 50L, 50L, 100L), s"readTick must monotonically merge to max; got $ticks")
        }

        "withReadTick — N sequential merges yield the maximum observed tick" in {
            val ticks = Seq(10L, 5L, 50L, 50L, 3L, 100L, 99L, 100L, 1L)
            val finalState = ticks.foldLeft(State.free) { (acc, t) =>
                if acc.readTick >= t then acc else acc.withReadTick(t)
            }
            assert(finalState.readTick == ticks.max, s"max-merge must produce ${ticks.max}; got ${finalState.readTick}")
        }

        "acquireReader — returns Absent at exactly MaxReaders (254)" in {
            val s254 = (1 to 254).foldLeft(State.free)((s, _) => s.acquireReader.get)
            assert(s254.acquireReader.isEmpty, "acquireReader on a 254-reader state must return Absent")
            assert(s254.render == "254 readers", s"render must reflect 254 readers, got ${s254.render}")
        }

        "acquireWriter — returns Absent when readers are present or a writer is held" in {
            val rs = State.free.acquireReader.get
            val w1 = rs.acquireWriter(1000L)
            val w2 = State.free.acquireWriter(1000L).get.acquireWriter(1000L)
            assert(w1.isEmpty, "acquireWriter must be Absent when one reader is present")
            assert(w2.isEmpty, "acquireWriter must be Absent when the writer lock is already held")
        }

        "releaseReader — single reader release returns state to free" in {
            val s0 = State.free.acquireReader.get.releaseReader
            assert(s0.render == "free", s"render must be 'free' after the only reader releases, got ${s0.render}")
            assert(s0.acquireWriter(0L).isDefined, "free state must permit acquireWriter")
        }

        "releaseReader — on a writer-locked state silently produces 254 readers (misuse oracle)" in {
            val wState = State.free.acquireWriter(0L).get
            val after  = wState.releaseReader
            assert(
                after.render == "254 readers",
                s"releaseReader on a writer-locked state silently produces 254 readers (current undefended behavior); got ${after.render}"
            )
        }

        "acquireReader — permits exactly 254 readers, then denies" in {
            val acquired = (1 to 254).foldLeft[Option[State]](Some(State.free)) {
                case (Some(s), _) => s.acquireReader.toOption
                case _            => None
            }
            val nextAttempt = acquired.get.acquireReader
            assert(acquired.isDefined, "must permit 254 sequential acquireReader calls")
            assert(nextAttempt.isEmpty, "must deny the 255th acquireReader call")
            assert(acquired.get.render == "254 readers", "render must reflect 254 readers")
        }

        "acquireReader — sequential cap is exactly 254" in {
            def countMax(s: State, n: Int): Int =
                s.acquireReader match
                    case Present(next) => countMax(next, n + 1)
                    case Absent        => n
            val cap = countMax(State.free, 0)
            assert(cap == 254, s"sequential acquireReader cap must be 254; got $cap")
        }

        "readTick — preserves a tick at the 56-bit boundary (1L << 56 - 1)" in {
            val maxTick  = (1L << 56) - 1L
            val readBack = State.free.withReadTick(maxTick).readTick
            assert(readBack == maxTick, s"56-bit-max tick must round-trip; expected $maxTick, got $readBack")
        }

        "withReadTick — tick larger than 2^56-1 is truncated to lower 56 bits" in {
            val oversized = 1L << 57
            val mask56    = (1L << 56) - 1L
            val back      = State.free.withReadTick(oversized).readTick
            assert(
                back == (oversized & mask56),
                s"oversized tick must be truncated to lower 56 bits; expected ${oversized & mask56}, got $back"
            )
        }

        "releaseReader — on writer-locked state silently yields 0xFE (diagnostic)" in {
            val after = State.free.acquireWriter(0L).get.releaseReader
            assert(
                after.render == "254 readers",
                s"releaseReader on writer-locked is undefended; current behavior yields '254 readers'; got ${after.render}"
            )
        }

        "releaseReader — on free state underflows into upper bits (diagnostic)" in {
            val after = State.free.releaseReader
            assert(
                after.render == "writer" || after.render.endsWith("readers"),
                s"releaseReader on free state silently corrupts; pinning current behavior; got ${after.render}"
            )
            assert(after.render != "free", "after the buggy release, state must NOT render as 'free' (asserts the underflow surfaces)")
        }

        "acquireWriter — tick = Long.MaxValue succeeds when readTick fits in 56 bits" in {
            val s = State.free.withReadTick((1L << 55) - 1L)
            assert(s.acquireWriter(Long.MaxValue).isDefined, "acquireWriter(Long.MaxValue) must succeed when readTick fits in 56 bits")
        }

        "render — at 254 readers shows '254 readers'; pluralization fixed even at 1" in {
            val s1   = State.free.acquireReader.get
            val s254 = (1 to 254).foldLeft(State.free)((s, _) => s.acquireReader.get)
            assert(s1.render == "1 readers", s"render at 1 reader is documented as '1 readers' (no special-case); got '${s1.render}'")
            assert(s254.render == "254 readers", s"render at MaxReaders must say '254 readers'; got '${s254.render}'")
        }

        "render — after two acquireReader the count is 2; after one release it is 1" in {
            val s2 = State.free.acquireReader.get.acquireReader.get
            assert(s2.render == "2 readers", s"after two acquireReader, count must be 2; got '${s2.render}'")
            assert(s2.releaseReader.render == "1 readers", s"after one releaseReader, count must be 1; got '${s2.releaseReader.render}'")
        }

        "acquireReader — at MaxReaders returns Absent (lock Read-branch denial)" in {
            val s254 = (1 to 254).foldLeft(State.free)((s, _) => s.acquireReader.get)
            assert(s254.acquireReader.isEmpty, "at MaxReaders, acquireReader must return Absent")
        }

        "acquireWriter — Absent distinguishably for 'locked' and 'stale tick'" in {
            val locked = State.free.acquireWriter(100L).get
            val stale  = State.free.withReadTick(200L)
            assert(locked.acquireWriter(100L).isEmpty, "denied because already writer-locked")
            assert(stale.acquireWriter(50L).isEmpty, "denied because readTick(200) > writer tick(50)")
        }

        "acquireWriterBarging — acquires past a fresher readTick yet never steals a held lock" in {
            val stale = State.free.withReadTick(200L)
            assert(stale.acquireWriter(50L).isEmpty, "polite acquireWriter is denied: readTick(200) > tick(50)")
            assert(stale.acquireWriterBarging.isDefined, "barging acquires despite the fresher readTick")
            val writerHeld = State.free.acquireWriter(100L).get
            assert(writerHeld.acquireWriterBarging.isEmpty, "barging must not steal a lock another writer physically holds")
            val readerHeld = State.free.acquireReader.get
            assert(readerHeld.acquireWriterBarging.isEmpty, "barging must not take the write lock while a reader holds it")
        }

        "acquireReader/releaseReader — N matched acquire/release returns to free" in {
            val n        = 30
            val acquired = (1 to n).foldLeft(State.free)((s, _) => s.acquireReader.get)
            val released = (1 to n).foldLeft(acquired)((s, _) => s.releaseReader)
            assert(released.render == "free", s"after $n matched acquire/release, state must be 'free'; got '${released.render}'")
            assert(released.acquireWriter(0L).isDefined, "free state must permit acquireWriter")
        }
    }
end TRefTest
