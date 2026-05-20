package kyo

class TMapTest extends Test:

    "Basic operations" - {
        "init and get" in run {
            STM.run {
                for
                    map   <- TMap.init("key" -> 42)
                    value <- map.get("key")
                yield value
            }.map { value =>
                assert(value == Maybe(42))
            }
        }

        "init from Map" in run {
            val initial = Map("a" -> 1, "b" -> 2, "c" -> 3)
            STM.run {
                for
                    map      <- TMap.init(initial)
                    snapshot <- map.snapshot
                yield snapshot
            }.map { snapshot =>
                assert(snapshot == initial)
            }
        }

        "initWith applies function" in run {
            STM.run {
                TMap.initWith("a" -> 1, "b" -> 2) { map =>
                    for
                        _        <- map.put("c", 3)
                        snapshot <- map.snapshot
                    yield snapshot
                }
            }.map { snapshot =>
                assert(snapshot == Map("a" -> 1, "b" -> 2, "c" -> 3))
            }
        }

        "add and contains rejects unknown keys" in run {
            STM.run {
                for
                    map     <- TMap.init[String, Int]()
                    _       <- map.put("key", 42)
                    exists  <- map.contains("key")
                    missing <- map.contains("nonexistent")
                    value   <- map.get("key")
                yield (exists, missing, value)
            }.map { case (exists, missing, value) =>
                assert(exists == true && missing == false && value == Maybe(42))
            }
        }

        "size and empty checks" in run {
            STM.run {
                for
                    map      <- TMap.init[String, Int]()
                    empty1   <- map.isEmpty
                    _        <- map.put("key", 42)
                    size     <- map.size
                    empty2   <- map.isEmpty
                    nonEmpty <- map.nonEmpty
                yield (empty1, size, empty2, nonEmpty)
            }.map { case (empty1, size, empty2, nonEmpty) =>
                assert(empty1 && size == 1 && !empty2 && nonEmpty)
            }
        }

        "remove operations" in run {
            STM.run {
                for
                    map     <- TMap.init("a" -> 1, "b" -> 2)
                    value   <- map.remove("a")
                    missing <- map.remove("nonexistent")
                    _       <- map.removeDiscard("b")
                    size    <- map.size
                yield (value, missing, size)
            }.map { case (value, missing, size) =>
                assert(value == Maybe(1) && missing.isEmpty && size == 0)
            }
        }

        "update operations" in run {
            STM.run {
                for
                    map    <- TMap.init("key" -> 10)
                    _      <- map.updateWith("key")(v => Maybe(v.getOrElse(0) + 1))
                    value1 <- map.get("key")
                    _      <- map.updateWith("key")(_ => Maybe.empty)
                    value2 <- map.get("key")
                yield (value1, value2)
            }.map { case (value1, value2) =>
                assert(value1 == Maybe(11) && value2.isEmpty)
            }
        }

        "clear" in run {
            STM.run {
                for
                    map   <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _     <- map.clear
                    size  <- map.size
                    empty <- map.isEmpty
                yield (size, empty)
            }.map { case (size, empty) =>
                assert(size == 0 && empty)
            }
        }
    }

    "Collection operations" - {
        "keys" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    keys <- map.keys
                yield keys
            }.map { keys =>
                assert(keys.toSet == Set("a", "b", "c"))
            }
        }

        "values" in run {
            STM.run {
                for
                    map    <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    values <- map.values
                yield values
            }.map { values =>
                assert(values.toSet == Set(1, 2, 3))
            }
        }

        "entries" in run {
            val initial = Map("a" -> 1, "b" -> 2, "c" -> 3)
            STM.run {
                for
                    map      <- TMap.init(initial)
                    snapshot <- map.snapshot
                yield snapshot
            }.map { snapshot =>
                assert(snapshot == initial)
            }
        }

        "filter" in run {
            STM.run {
                for
                    map      <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _        <- map.filter((_, v) => v % 2 == 1)
                    snapshot <- map.snapshot
                yield snapshot
            }.map { snapshot =>
                assert(snapshot == Map("a" -> 1, "c" -> 3))
            }
        }

        "fold" in run {
            STM.run {
                for
                    map    <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    sum    <- map.fold(0)((acc, _, v) => acc + v)
                    concat <- map.fold("")((acc, k, v) => acc + k + v)
                yield (sum, concat)
            }.map { case (sum, concat) =>
                assert(sum == 6 && concat == "a1b2c3")
            }
        }

        "findFirst" in run {
            STM.run {
                for
                    map     <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    found   <- map.findFirst((k, v) => if v % 2 == 0 then Maybe(k) else Maybe.empty)
                    missing <- map.findFirst((_, v) => if v > 10 then Maybe(v) else Maybe.empty)
                yield (found, missing)
            }.map { case (found, missing) =>
                assert(found == Maybe("b") && missing.isEmpty)
            }
        }

        "snapshot" in run {
            val initial = Map("a" -> 1, "b" -> 2, "c" -> 3)
            STM.run {
                for
                    map      <- TMap.init(initial)
                    snapshot <- map.snapshot
                yield snapshot
            }.map { result =>
                assert(result == initial)
            }
        }
    }

    "Error handling" - {
        "rollback on direct failure" in run {
            for
                map <- STM.run(TMap.init[String, Int]("initial" -> 42))
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- map.put("key1", 100)
                            _ <- map.put("key2", 200)
                            _ <- Abort.fail(new Exception("Test failure"))
                        yield ()
                    }
                }
                snapshot <- STM.run(map.snapshot)
            yield assert(
                result.isFailure &&
                    snapshot == Map("initial" -> 42)
            )
        }

        "rollback on nested transaction failure" in run {
            for
                map <- STM.run(TMap.init[String, Int]())
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- map.put("outer", 1)
                            _ <- STM.run {
                                for
                                    _ <- map.put("inner", 2)
                                    _ <- Abort.fail(new Exception("Nested failure"))
                                yield ()
                            }
                        yield ()
                    }
                }
                snapshot <- STM.run(map.snapshot)
            yield assert(
                result.isFailure &&
                    snapshot.isEmpty
            )
        }

        "partial updates remain atomic" in run {
            for
                map <- STM.run(TMap.init[String, Int]())
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- map.put("key1", 100)
                            _ <- map.updateWith("key1") { _ => Maybe(200) }
                            _ <- STM.retry
                            _ <- map.put("key2", 300)
                        yield ()
                    }
                }
                snapshot <- STM.run(map.snapshot)
            yield assert(
                result.isFailure &&
                    snapshot.isEmpty
            )
        }

        "exception in update function rolls back" in run {
            for
                map <- STM.run(TMap.init[String, Int]("test" -> 42))
                result <- Abort.run {
                    STM.run {
                        map.updateWith("test") { _ =>
                            throw new Exception("Update failure")
                            Maybe(100)
                        }
                    }
                }
                value <- STM.run(map.get("test"))
            yield assert(
                result.isPanic &&
                    value == Maybe(42)
            )
        }

        "filter operation rollback" in run {
            for
                map <- STM.run(TMap.init[String, Int]("a" -> 1, "b" -> 2, "c" -> 3))
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- map.filter { (k, v) =>
                                if k == "b" then throw new Exception("Filter failure")
                                true
                            }
                        yield ()
                    }
                }
                snapshot <- STM.run(map.snapshot)
            yield assert(
                result.isPanic &&
                    snapshot == Map("a" -> 1, "b" -> 2, "c" -> 3)
            )
        }

        "fold operation rollback" in run {
            for
                map <- STM.run(TMap.init[String, Int]("a" -> 1, "b" -> 2, "c" -> 3))
                result <- Abort.run {
                    STM.run {
                        map.fold(0) { (acc, k, v) =>
                            if acc > 2 then throw new Exception("Fold failure")
                            acc + v
                        }
                    }
                }
                snapshot <- STM.run(map.snapshot)
            yield assert(
                result.isPanic &&
                    snapshot == Map("a" -> 1, "b" -> 2, "c" -> 3)
            )
        }

        "findFirst operation rollback" in run {
            for
                map <- STM.run(TMap.init[String, Int]("a" -> 1, "b" -> 2, "c" -> 3))
                result <- Abort.run {
                    STM.run {
                        map.findFirst { (k, v) =>
                            if k == "b" then throw new Exception("Find failure")
                            Maybe.empty
                        }
                    }
                }
                snapshot <- STM.run(map.snapshot)
            yield assert(
                result.isPanic &&
                    snapshot == Map("a" -> 1, "b" -> 2, "c" -> 3)
            )
        }

        "multiple operations rollback on failure" in run {
            for
                map <- STM.run(TMap.init[String, Int]())
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- map.put("key1", 100)
                            _ <- map.removeDiscard("key1")
                            _ <- map.put("key2", 200)
                            _ <- Abort.fail(new Exception("Multi-op failure"))
                            _ <- map.put("key3", 300)
                        yield ()
                    }
                }
                snapshot <- STM.run(map.snapshot)
            yield assert(
                result.isFailure &&
                    snapshot.isEmpty
            )
        }

        "nested effects with rollback" in run {
            Var.run(0) {
                for
                    map <- STM.run(TMap.init[String, Int]("start" -> 0))
                    result <- Abort.run {
                        Var.isolate.update[Int].use {
                            STM.run {
                                for
                                    _ <- map.put("key1", 100)
                                    _ <- Var.set(1)
                                    _ <- Abort.fail(new Exception("Nested effect failure"))
                                    _ <- map.put("key2", 200)
                                yield ()
                            }
                        }
                    }
                    snapshot <- STM.run(map.snapshot)
                    varValue <- Var.get[Int]
                yield assert(
                    result.isFailure &&
                        snapshot == Map("start" -> 0) &&
                        varValue == 0
                )
            }
        }
    }

    "Concurrency" - {
        val repeats = 50

        "concurrent modifications" in runNotJS {
            val retrySchedule = STM.defaultRetrySchedule.forever
            (for
                size     <- Choice.eval(1, 10, 100)
                map      <- STM.run(TMap.init[Int, Int]())
                _        <- Async.foreach(1 to size, size)(i => STM.run(retrySchedule)(map.put(i, i)))
                snapshot <- STM.run(map.snapshot)
            yield assert(
                snapshot.size == size &&
                    snapshot.forall((k, v) => k == v && k >= 1 && k <= size)
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent reads and writes" in runNotJS {
            (for
                size  <- Choice.eval(1, 10, 100)
                map   <- STM.run(TMap.init[Int, Int]())
                latch <- Latch.init(1)

                writeFiber <- Fiber.initUnscoped(
                    latch.await.andThen(
                        Async.foreach(1 to size, size)(i =>
                            STM.run(map.put(i, i * 2))
                        )
                    )
                )

                readFiber <- Fiber.initUnscoped(
                    latch.await.andThen(
                        Async.foreach(1 to size, size)(i =>
                            STM.run(map.get(i))
                        )
                    )
                )

                _        <- latch.release
                _        <- writeFiber.get
                reads    <- readFiber.get
                snapshot <- STM.run(map.snapshot)
            yield
                assert(snapshot.size == size)
                assert(snapshot.forall((k, v) => v == k * 2))
                assert(reads.forall(maybeVal => maybeVal.isEmpty || maybeVal.exists(_ % 2 == 0)))
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent updates" in runNotJS {
            (for
                size <- Choice.eval(1, 10, 50)
                map  <- STM.run(TMap.init[Int, Int]())
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => map.put(i, 1))
                }
                _ <- Async.fill(10, 10)(
                    STM.run(Schedule.fixed(1.millis).jitter(0.5).forever) {
                        Kyo.foreachDiscard((1 to size)) { i =>
                            map.updateWith(i)(v => Maybe(v.getOrElse(0) + 1))
                        }
                    }
                )
                snapshot <- STM.run(map.snapshot)
            yield assert(
                snapshot.size == size &&
                    snapshot.forall((_, v) => v == 11) // Initial 1 + 10 increments
            ))
                .handle(Choice.run, _.unit, Loop.repeat(50))
                .andThen(succeed)
        }

        "concurrent removals" in runNotJS {
            (for
                size <- Choice.eval(1, 10, 100)
                map  <- STM.run(TMap.init[Int, Int]())
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => map.put(i, i))
                }
                _ <- Async.foreach(1 to size, size)(i =>
                    STM.run(map.removeDiscard(i))
                )
                snapshot <- STM.run(map.snapshot)
            yield assert(snapshot.isEmpty))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent bulk operations" in runNotJS {
            val retries = STM.defaultRetrySchedule.forever
            (for
                size <- Choice.eval(1, 10, 100)
                map  <- STM.run(TMap.init[Int, Int]())
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => map.put(i, i))
                }

                filterOps = Async.fill(5, 5)(
                    STM.run(retries)(map.filter((k, v) => v % 2 == 0))
                )

                foldOps = Async.fill(5, 5)(
                    STM.run(retries)(map.fold(0)((acc, _, v) => acc + v))
                )

                _        <- filterOps
                sums     <- foldOps
                snapshot <- STM.run(map.snapshot)
            yield
                assert(snapshot.forall((_, v) => v % 2 == 0))
                assert(sums.forall(_ == snapshot.values.sum))
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }
    }

    // TMap.initWith invokes `f` and returns its result with declared type `A < (Sync & S)`.
    // The runtime specs below verify `f` runs exactly once (AtomicInteger counter) and that
    // its result is returned. Specs 0004 and 0069 are type-level claims verified via
    // `typeCheck` of the correct (non-TMap) usage.
    "initWith" - {
        "initWith invokes f and returns its result, not the TMap" in run {
            val invocations = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                TMap.initWith[String, Int]("a" -> 1, "b" -> 2) { (m: TMap[String, Int]) =>
                    invocations.incrementAndGet()
                    m.size
                }
            }.map { size =>
                assert(invocations.get() == 1)
                assert(size == 2)
            }
        }

        "initWith allows A to be a value other than TMap[K, V]" in {
            typeCheck(
                """
                val result: String < (Sync & STM) =
                    TMap.initWith[String, Int]("a" -> 1, "b" -> 2, "c" -> 3) { m =>
                        m.fold("")((acc, k, v) => acc + k + v)
                    }
                result
                """
            )
        }

        "initWith supports an effectful lambda (Abort)" in run {
            val invocations = new java.util.concurrent.atomic.AtomicInteger(0)
            Abort.run {
                STM.run {
                    TMap.initWith[String, Int]("a" -> 1) { m =>
                        invocations.incrementAndGet()
                        m.get("a").map {
                            case Present(v) if v > 0 => v
                            case _                   => Abort.fail(new Exception("invalid"))
                        }
                    }
                }
            }.map { result =>
                assert(invocations.get() == 1)
                assert(result == Result.succeed(1))
            }
        }

        "initWith and the first map operation share one transaction" in run {
            val invocations = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                TMap.initWith[String, Int]("k" -> 99) { m =>
                    invocations.incrementAndGet()
                    for
                        present <- m.contains("k")
                        value   <- m.get("k")
                    yield (present, value)
                    end for
                }
            }.map { case (present, value) =>
                assert(invocations.get() == 1)
                assert(present == true && value == Maybe(99))
            }
        }

        "initWith works inside an existing STM transaction" in run {
            val invocations = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                for
                    outer <- TRef.init(0)
                    size <- TMap.initWith[String, Int]("a" -> 1, "b" -> 2) { m =>
                        invocations.incrementAndGet()
                        for
                            _ <- outer.set(1)
                            s <- m.size
                        yield s
                        end for
                    }
                    outerValue <- outer.get
                yield (size, outerValue)
            }.map { case (size, outerValue) =>
                assert(invocations.get() == 1)
                assert(size == 2 && outerValue == 1)
            }
        }

        "initWith with effectful lambda surfaces S in the result type" in {
            typeCheck(
                """
                val computation: Int < (Sync & STM & Abort[Throwable]) =
                    TMap.initWith[String, Int]("k" -> 1) { m =>
                        m.get("k").map {
                            case Present(v) => v
                            case Absent     => Abort.fail(new Exception("nope"))
                        }
                    }
                computation
                """
            )
        }

        "initWith with many entries makes every binding observable via get" in run {
            val invocations = new java.util.concurrent.atomic.AtomicInteger(0)
            val entries     = (1 to 100).map(i => i.toString -> i)
            STM.run {
                TMap.initWith[String, Int](entries*) { m =>
                    invocations.incrementAndGet()
                    for
                        all <- Kyo.foreach(entries.map(_._1))(k => m.get(k))
                        sz  <- m.size
                    yield (all, sz)
                    end for
                }
            }.map { case (all, sz) =>
                assert(invocations.get() == 1)
                assert(sz == 100)
                assert(all == Chunk.from(entries.map((_, v) => Maybe(v))))
            }
        }

        "initWith with effectful S in the lambda surfaces S at the call site" in run {
            Emit.run[Int] {
                Emit.isolate.merge[Int].use {
                    STM.run {
                        TMap.initWith[String, Int]("a" -> 7) { m =>
                            for
                                v <- m.get("a")
                                _ <- Emit.value(v.getOrElse(-1))
                            yield ()
                        }
                    }
                }
            }.map { case (emitted, _) =>
                assert(emitted == Chunk(7))
            }
        }

        "initWith evaluates each entry expression exactly once per call" in run {
            val keyCalls = new java.util.concurrent.atomic.AtomicInteger(0)
            val valCalls = new java.util.concurrent.atomic.AtomicInteger(0)
            val fCalls   = new java.util.concurrent.atomic.AtomicInteger(0)
            def k(): String =
                keyCalls.incrementAndGet()
                "k"
            def v(): Int =
                valCalls.incrementAndGet()
                7
            STM.run {
                TMap.initWith[String, Int](k() -> v()) { m =>
                    fCalls.incrementAndGet()
                    m.get("k")
                }
            }.map { value =>
                assert(keyCalls.get() == 1 && valCalls.get() == 1 && fCalls.get() == 1)
                assert(value == Maybe(7))
            }
        }
    }

    "init overloads" - {
        "no-arg init overload constructs an empty TMap" in run {
            val construct: TMap[String, Int] < Sync = TMap.init[String, Int]
            STM.run {
                for
                    map   <- construct
                    size  <- map.size
                    empty <- map.isEmpty
                yield (size, empty)
            }.map { case (size, empty) =>
                assert(size == 0)
                assert(empty == true)
            }
        }

        "init varargs with duplicate keys keeps the last value" in run {
            STM.run {
                for
                    map   <- TMap.init("a" -> 1, "a" -> 2, "a" -> 3)
                    size  <- map.size
                    value <- map.get("a")
                yield (size, value)
            }.map { case (size, value) =>
                assert(size == 1)
                assert(value == Maybe(3))
            }
        }

        "init from Map.empty produces an empty TMap" in run {
            STM.run {
                for
                    map   <- TMap.init(Map.empty[String, Int])
                    size  <- map.size
                    empty <- map.isEmpty
                    snap  <- map.snapshot
                yield (size, empty, snap)
            }.map { case (size, empty, snap) =>
                assert(size == 0)
                assert(empty == true)
                assert(snap == Map.empty[String, Int])
            }
        }
    }

    "use" - {
        "use applies the function to Present(value) and Absent" in run {
            STM.run {
                for
                    map     <- TMap.init("a" -> 10)
                    present <- map.use("a")(m => m.map(_ * 2).getOrElse(-1))
                    absent  <- map.use("z")(m => m.map(_ * 2).getOrElse(-1))
                yield (present, absent)
            }.map { case (present, absent) =>
                assert(present == 20)
                assert(absent == -1)
            }
        }

        "use composes with an effectful lambda and rolls back on failure" in run {
            for
                map <- STM.run(TMap.init("ok" -> 1))
                r <- Abort.run {
                    STM.run {
                        map.use("ok") {
                            case Present(v) if v > 0 => v
                            case _                   => Abort.fail(new Exception("bad"))
                        }
                    }
                }
                after <- STM.run(map.snapshot)
            yield
                assert(r == Result.succeed(1))
                assert(after == Map("ok" -> 1))
        }

        "use's Maybe wrap distinguishes Present(value) from Absent for non-null V" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 0)
                    r <- map.use("a") {
                        case Absent     => "absent"
                        case Present(_) => "present"
                    }
                yield r
            }.map { r =>
                assert(r == "present")
            }
        }

        "use invokes f with Maybe.empty when the key is absent" in run {
            val invocations = new java.util.concurrent.atomic.AtomicInteger(0)
            val seen        = new java.util.concurrent.atomic.AtomicReference[Maybe[Int]](null)
            STM.run {
                for
                    map <- TMap.init("present" -> 1)
                    r <- map.use("absent") { m =>
                        invocations.incrementAndGet()
                        seen.set(m)
                        m.getOrElse(-1)
                    }
                yield r
            }.map { r =>
                assert(r == -1)
                assert(invocations.get() == 1)
                assert(seen.get() == Maybe.empty)
            }
        }

        "use surfaces a panicking lambda and leaves the TMap unchanged" in run {
            for
                map <- STM.run(TMap.init("a" -> 1))
                r <- Abort.run {
                    STM.run {
                        map.use("a") { _ => throw new RuntimeException("boom") }
                    }
                }
                snap <- STM.run(map.snapshot)
            yield
                assert(r.isPanic)
                assert(snap == Map("a" -> 1))
        }
    }

    "nonEmpty and clear" - {
        "nonEmpty returns false for an empty map" in run {
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    ne  <- map.nonEmpty
                yield ne
            }.map { ne =>
                assert(ne == false)
            }
        }

        "clear on an already-empty map is a no-op" in run {
            STM.run {
                for
                    map   <- TMap.init[String, Int]
                    _     <- map.clear
                    size  <- map.size
                    empty <- map.isEmpty
                    snap  <- map.snapshot
                yield (size, empty, snap)
            }.map { case (size, empty, snap) =>
                assert(size == 0)
                assert(empty == true)
                assert(snap == Map.empty[String, Int])
            }
        }

        "clear then put on the same key stores the new value" in run {
            STM.run {
                for
                    map <- TMap.init("k" -> 1)
                    _   <- map.clear
                    _   <- map.put("k", 99)
                    v   <- map.get("k")
                    s   <- map.size
                yield (v, s)
            }.map { case (v, s) =>
                assert(v == Maybe(99))
                assert(s == 1)
            }
        }

        "clear leaves the map empty after the call (Frame-aware)" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _    <- map.clear
                    snap <- map.snapshot
                    sz   <- map.size
                yield (snap, sz)
            }.map { case (snap, sz) =>
                assert(snap == Map.empty[String, Int])
                assert(sz == 0)
            }
        }
    }

    "get and getOrElse" - {
        "get on a never-inserted key returns Maybe.empty" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1, "b" -> 2)
                    v   <- map.get("never-inserted")
                yield v
            }.map { v =>
                assert(v.isEmpty)
            }
        }

        "getOrElse returns the value for a present key" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 42)
                    v   <- map.getOrElse("a", -1)
                yield v
            }.map { v =>
                assert(v == 42)
            }
        }

        "getOrElse returns the default for an absent key" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 42)
                    v   <- map.getOrElse("z", -1)
                yield v
            }.map { v =>
                assert(v == -1)
            }
        }

        "getOrElse's type parameter A does not affect runtime behavior" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1)
                    v1  <- map.getOrElse[Int, Any]("a", -1)
                    v2  <- map.getOrElse[String, Any]("a", -1)
                yield (v1, v2)
            }.map { case (v1, v2) =>
                assert(v1 == 1 && v2 == 1)
            }
        }

        "getOrElse does not evaluate the default when the key is present" in run {
            val evals = new java.util.concurrent.atomic.AtomicInteger(0)
            def expensive: Int =
                evals.incrementAndGet(); 99
            STM.run {
                for
                    map <- TMap.init("a" -> 1)
                    v   <- map.getOrElse("a", expensive)
                yield v
            }.map { v =>
                assert(v == 1)
                assert(evals.get() == 0)
            }
        }

        "getOrElse propagates Abort.fail from orElse when the key is absent" in run {
            for
                map <- STM.run(TMap.init("a" -> 1))
                r <- Abort.run {
                    STM.run {
                        map.getOrElse("missing", Abort.fail(new Exception("absent")))
                    }
                }
                snap <- STM.run(map.snapshot)
            yield
                assert(r.isFailure)
                assert(snap == Map("a" -> 1))
        }

        "getOrElse skips Abort.fail default when the key is present" in run {
            Abort.run {
                STM.run {
                    for
                        map <- TMap.init("a" -> 42)
                        v   <- map.getOrElse("a", Abort.fail(new Exception("should not run")))
                    yield v
                }
            }.map { result =>
                assert(result == Result.succeed(42))
            }
        }
    }

    "put and contains" - {
        "put a null value stores and retrieves null" in run {
            val nullStr: String = null
            STM.run {
                for
                    map <- TMap.init[String, String]
                    _   <- map.put("k", nullStr)
                    v   <- map.get("k")
                    c   <- map.contains("k")
                yield (v, c)
            }.map { case (v, c) =>
                assert(c == true)
            }
        }

        "contains returns false on an empty map" in run {
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    c   <- map.contains("anything")
                yield c
            }.map { c =>
                assert(c == false)
            }
        }

        "contains returns false for an unknown key when other entries exist" in run {
            STM.run {
                for
                    map     <- TMap.init("a" -> 1)
                    present <- map.contains("a")
                    absent  <- map.contains("z")
                yield (present, absent)
            }.map { case (present, absent) =>
                assert(present == true && absent == false)
            }
        }

        "contains returns false for a key that was removed while others remain" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _    <- map.removeDiscard("b")
                    hasB <- map.contains("b")
                    hasA <- map.contains("a")
                yield (hasB, hasA)
            }.map { case (hasB, hasA) =>
                assert(hasB == false && hasA == true)
            }
        }

        "sequential put of N new keys grows the map to size N" in run {
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    _   <- Kyo.foreachDiscard(1 to 50)(i => map.put(i.toString, i))
                    sz  <- map.size
                    all <- Kyo.foreach(1 to 50)(i => map.get(i.toString))
                yield (sz, all)
            }.map { case (sz, all) =>
                assert(sz == 50)
                assert(all.zip(1 to 50).forall { case (mb, i) => mb == Maybe(i) })
            }
        }
    }

    "updateWith" - {
        "updateWith inserts when the key is missing and f returns Present(v)" in run {
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    _ <- map.updateWith("k") {
                        case Absent => Maybe(7)
                        case other  => other
                    }
                    v <- map.get("k")
                    s <- map.size
                yield (v, s)
            }.map { case (v, s) =>
                assert(v == Maybe(7))
                assert(s == 1)
            }
        }

        "updateWith is a no-op when the key is missing and f returns Absent" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1)
                    _   <- map.updateWith("missing")(_ => Maybe.empty)
                    s   <- map.size
                    v   <- map.get("missing")
                yield (s, v)
            }.map { case (s, v) =>
                assert(s == 1)
                assert(v.isEmpty)
            }
        }

        "updateWith propagates Abort.fail and rolls back the transaction" in run {
            for
                map <- STM.run(TMap.init("k" -> 1))
                r <- Abort.run {
                    STM.run {
                        map.updateWith("k") { _ => Abort.fail(new Exception("nope")) }
                    }
                }
                snap <- STM.run(map.snapshot)
            yield
                assert(r.isFailure)
                assert(snap == Map("k" -> 1))
        }

        "updateWith with a panicking lambda surfaces panic and rolls back" in run {
            for
                map <- STM.run(TMap.init("a" -> 1))
                r <- Abort.run {
                    STM.run {
                        for
                            _ <- map.put("b", 2)
                            _ <- map.updateWith("a") { _ => throw new RuntimeException("boom") }
                        yield ()
                    }
                }
                snap <- STM.run(map.snapshot)
            yield
                assert(r.isPanic)
                assert(snap == Map("a" -> 1))
        }
    }

    "removeDiscard and removeAll" - {
        "removeDiscard is a no-op on a missing key" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2)
                    _    <- map.removeDiscard("nope")
                    snap <- map.snapshot
                yield snap
            }.map { snap =>
                assert(snap == Map("a" -> 1, "b" -> 2))
            }
        }

        "removeDiscard then get returns Maybe.empty" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1)
                    _   <- map.removeDiscard("a")
                    v   <- map.get("a")
                yield v
            }.map { v =>
                assert(v.isEmpty)
            }
        }

        "removeAll removes all the specified existing keys" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4)
                    _    <- map.removeAll(Seq("a", "c"))
                    snap <- map.snapshot
                    size <- map.size
                yield (snap, size)
            }.map { case (snap, size) =>
                assert(snap == Map("b" -> 2, "d" -> 4))
                assert(size == 2)
            }
        }

        "removeAll with empty Seq leaves the map unchanged" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2)
                    _    <- map.removeAll(Seq.empty)
                    snap <- map.snapshot
                yield snap
            }.map { snap =>
                assert(snap == Map("a" -> 1, "b" -> 2))
            }
        }

        "removeAll on missing keys is a no-op" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2)
                    _    <- map.removeAll(Seq("x", "y", "z"))
                    snap <- map.snapshot
                yield snap
            }.map { snap =>
                assert(snap == Map("a" -> 1, "b" -> 2))
            }
        }

        "removeAll with duplicate keys removes once" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _    <- map.removeAll(Seq("a", "a", "a", "b"))
                    snap <- map.snapshot
                yield snap
            }.map { snap =>
                assert(snap == Map("c" -> 3))
            }
        }

        "removeAll on all keys empties the map" in run {
            STM.run {
                for
                    map   <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _     <- map.removeAll(Seq("a", "b", "c"))
                    empty <- map.isEmpty
                    snap  <- map.snapshot
                yield (empty, snap)
            }.map { case (empty, snap) =>
                assert(empty == true)
                assert(snap == Map.empty[String, Int])
            }
        }

        "removeAll with empty Seq does not invalidate existing bindings" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _   <- map.removeAll(Seq.empty)
                    a   <- map.get("a")
                    b   <- map.get("b")
                    c   <- map.get("c")
                    sz  <- map.size
                yield (a, b, c, sz)
            }.map { case (a, b, c, sz) =>
                assert(a == Maybe(1))
                assert(b == Maybe(2))
                assert(c == Maybe(3))
                assert(sz == 3)
            }
        }
    }

    "keys, values, entries" - {
        "keys on an empty map returns an empty iterable" in run {
            STM.run {
                for
                    map  <- TMap.init[String, Int]
                    keys <- map.keys
                yield keys
            }.map { keys =>
                assert(keys.toSeq.isEmpty)
            }
        }

        "keys contains exactly the inserted keys, no duplicates, count matches size" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    keys <- map.keys
                    sz   <- map.size
                yield (keys, sz)
            }.map { case (keys, sz) =>
                val seq = keys.toSeq
                assert(seq.length == sz)
                assert(seq.toSet == Set("a", "b", "c"))
                assert(seq.length == seq.toSet.size)
            }
        }

        "keys returned in one transaction is consistent with that transaction's view" in run {
            for
                map  <- STM.run(TMap.init("a" -> 1, "b" -> 2))
                keys <- STM.run(map.keys)
                _    <- STM.run(map.put("c", 3))
                _    <- STM.run(map.removeDiscard("a"))
            yield assert(keys.toSeq.toSet == Set("a", "b"))
        }

        "values on an empty map returns an empty iterable" in run {
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    vs  <- map.values
                yield vs
            }.map { vs =>
                assert(vs.toSeq.isEmpty)
            }
        }

        "values preserves duplicate values (no deduplication)" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1, "b" -> 1, "c" -> 1)
                    vs  <- map.values
                    sz  <- map.size
                yield (vs, sz)
            }.map { case (vs, sz) =>
                val seq = vs.toSeq
                assert(seq.length == sz)
                assert(seq.forall(_ == 1))
                assert(seq.length == 3)
            }
        }

        "entries returns exactly the inserted (K, V) pairs" in run {
            STM.run {
                for
                    map     <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    entries <- map.entries
                yield entries
            }.map { entries =>
                assert(entries.toSet == Set("a" -> 1, "b" -> 2, "c" -> 3))
                assert(entries.toSeq.length == 3)
            }
        }

        "entries on an empty map returns an empty iterable" in run {
            STM.run {
                for
                    map     <- TMap.init[String, Int]
                    entries <- map.entries
                yield entries
            }.map { entries =>
                assert(entries.toSeq.isEmpty)
            }
        }

        "entries covers every binding regardless of iteration order" in run {
            STM.run {
                for
                    map     <- TMap.init("z" -> 26, "a" -> 1, "m" -> 13)
                    entries <- map.entries
                yield entries
            }.map { entries =>
                val seq = entries.toSeq
                assert(seq.length == 3)
                assert(seq.toSet == Set("z" -> 26, "a" -> 1, "m" -> 13))
            }
        }

        "keys and values length each equal size, with no missing/extra" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1, "b" -> 1, "c" -> 2)
                    ks  <- map.keys
                    vs  <- map.values
                    sz  <- map.size
                yield (ks.toSeq, vs.toSeq, sz)
            }.map { case (ks, vs, sz) =>
                assert(ks.length == sz)
                assert(vs.length == sz)
                assert(ks.toSet == Set("a", "b", "c"))
                assert(vs.count(_ == 1) == 2)
                assert(vs.count(_ == 2) == 1)
            }
        }

        "entries (direct call) returns the inserted (K, V) pairs" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1, "b" -> 2)
                    es  <- map.entries
                yield es.toList
            }.map { es =>
                assert(es.toSet == Set("a" -> 1, "b" -> 2))
                assert(es.length == 2)
            }
        }
    }

    "filter" - {
        "filter on an empty map leaves the map empty" in run {
            val pCalls = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    _ <- map.filter { (_, _) =>
                        pCalls.incrementAndGet(); true
                    }
                    snap <- map.snapshot
                yield snap
            }.map { snap =>
                assert(snap.isEmpty)
                assert(pCalls.get() == 0)
            }
        }

        "filter with always-true predicate retains every entry" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _    <- map.filter((_, _) => true)
                    snap <- map.snapshot
                yield snap
            }.map { snap =>
                assert(snap == Map("a" -> 1, "b" -> 2, "c" -> 3))
            }
        }

        "filter with always-false predicate empties the map" in run {
            STM.run {
                for
                    map   <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    _     <- map.filter((_, _) => false)
                    snap  <- map.snapshot
                    empty <- map.isEmpty
                yield (snap, empty)
            }.map { case (snap, empty) =>
                assert(snap.isEmpty)
                assert(empty == true)
            }
        }

        "filter propagates Abort.fail from the predicate and rolls back" in run {
            for
                map <- STM.run(TMap.init("a" -> 1, "b" -> 2, "c" -> 3))
                r <- Abort.run {
                    STM.run {
                        map.filter { (k, _) =>
                            if k == "b" then Abort.fail(new Exception("boom"))
                            else true
                        }
                    }
                }
                snap <- STM.run(map.snapshot)
            yield
                assert(r.isFailure)
                assert(snap == Map("a" -> 1, "b" -> 2, "c" -> 3))
        }

        "filter invokes the predicate exactly once per entry (single attempt, no retry)" in run {
            val calls   = new java.util.concurrent.atomic.AtomicInteger(0)
            val n       = 25
            val entries = (1 to n).map(i => i.toString -> i)
            STM.run {
                for
                    map <- TMap.init(entries*)
                    _ <- map.filter { (_, _) =>
                        calls.incrementAndGet(); true
                    }
                yield ()
            }.map { _ =>
                assert(calls.get() == n)
            }
        }

        "filter with a mid-iteration panicking predicate rolls back any removals" in run {
            val entries = (1 to 10).map(i => i.toString -> i)
            for
                map <- STM.run(TMap.init(entries*))
                r <- Abort.run {
                    STM.run {
                        map.filter { (k, _) =>
                            if k == "5" then throw new RuntimeException("boom")
                            false
                        }
                    }
                }
                snap <- STM.run(map.snapshot)
            yield
                assert(r.isPanic)
                assert(snap == entries.toMap)
            end for
        }
    }

    "fold" - {
        "fold on an empty map returns the initial accumulator" in run {
            val calls = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    r <- map.fold(7) { (_, _, _) =>
                        calls.incrementAndGet(); 99
                    }
                yield r
            }.map { r =>
                assert(r == 7)
                assert(calls.get() == 0)
            }
        }

        "fold's type parameter B has no runtime effect" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1, "b" -> 2)
                    r1  <- map.fold[Int, Int, Any](0)((acc, _, v) => acc + v)
                    r2  <- map.fold[Int, String, Any](0)((acc, _, v) => acc + v)
                yield (r1, r2)
            }.map { case (r1, r2) =>
                assert(r1 == r2)
                assert(r1 == 3)
            }
        }

        "fold computes the correct sum and visits every entry regardless of order" in run {
            STM.run {
                for
                    map     <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4)
                    sum     <- map.fold(0)((acc, _, v) => acc + v)
                    visited <- map.fold(Set.empty[String])((acc, k, _) => acc + k)
                yield (sum, visited)
            }.map { case (sum, visited) =>
                assert(sum == 10)
                assert(visited == Set("a", "b", "c", "d"))
            }
        }

        "fold propagates Abort.fail from the combiner" in run {
            for
                map <- STM.run(TMap.init("a" -> 1, "b" -> 2, "c" -> 3))
                r <- Abort.run {
                    STM.run {
                        map.fold(0) { (acc, k, _) =>
                            if k == "b" then Abort.fail(new Exception("boom")) else acc + 1
                        }
                    }
                }
            yield assert(r.isFailure)
        }

        "fold result is correct without depending on Map iteration order" in run {
            STM.run {
                for
                    map   <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    sum   <- map.fold(0)((acc, _, v) => acc + v)
                    keys  <- map.fold(Set.empty[String])((acc, k, _) => acc + k)
                    pairs <- map.fold(Set.empty[(String, Int)])((acc, k, v) => acc + (k -> v))
                yield (sum, keys, pairs)
            }.map { case (sum, keys, pairs) =>
                assert(sum == 6)
                assert(keys == Set("a", "b", "c"))
                assert(pairs == Set("a" -> 1, "b" -> 2, "c" -> 3))
            }
        }

        "fold result equals snapshot.values.sum at point of fold's commit" in run {
            STM.run {
                for
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3, "d" -> 4)
                    sum  <- map.fold(0)((acc, _, v) => acc + v)
                    snap <- map.snapshot
                yield (sum, snap)
            }.map { case (sum, snap) =>
                assert(sum == snap.values.sum)
                assert(sum == 10)
            }
        }

        "fold lambda can read another TRef and the result composes correctly" in run {
            STM.run {
                for
                    bias <- TRef.init(10)
                    map  <- TMap.init("a" -> 1, "b" -> 2, "c" -> 3)
                    sum <- map.fold(0) { (acc, _, v) =>
                        bias.get.map(b => acc + v + b)
                    }
                yield sum
            }.map { sum =>
                assert(sum == 1 + 10 + 2 + 10 + 3 + 10)
            }
        }

        "fold invokes the combiner exactly once per entry (counter == size)" in run {
            val calls   = new java.util.concurrent.atomic.AtomicInteger(0)
            val n       = 13
            val entries = (1 to n).map(i => i.toString -> i)
            STM.run {
                for
                    map <- TMap.init(entries*)
                    s <- map.fold(0) { (acc, _, _) =>
                        calls.incrementAndGet(); acc + 1
                    }
                yield s
            }.map { s =>
                assert(s == n)
                assert(calls.get() == n)
            }
        }

        "fold with a panicking combiner mid-iteration surfaces panic, no state leaks" in run {
            for
                map <- STM.run(TMap.init("a" -> 1, "b" -> 2, "c" -> 3))
                r <- Abort.run {
                    STM.run {
                        for
                            _ <- map.put("d", 99)
                            _ <- map.fold(0) { (acc, k, _) =>
                                if k == "a" || k == "b" || k == "c" || k == "d" then throw new RuntimeException("boom")
                                else acc + 1
                            }
                        yield ()
                    }
                }
                snap <- STM.run(map.snapshot)
            yield
                assert(r.isPanic)
                assert(snap == Map("a" -> 1, "b" -> 2, "c" -> 3))
        }
    }

    "findFirst" - {
        "findFirst on an empty map returns Maybe.empty and does not invoke f" in run {
            val calls = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    r <- map.findFirst { (_, _) =>
                        calls.incrementAndGet(); Maybe("hit")
                    }
                yield r
            }.map { r =>
                assert(r.isEmpty)
                assert(calls.get() == 0)
            }
        }

        "findFirst returns one of the matching entries (order-tolerant)" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 2, "b" -> 4, "c" -> 6)
                    r   <- map.findFirst((k, v) => if v % 2 == 0 then Maybe(k) else Maybe.empty)
                yield r
            }.map { r =>
                assert(r.exists(k => Set("a", "b", "c").contains(k)))
            }
        }

        "findFirst supports return type A != String" in run {
            STM.run {
                for
                    map <- TMap.init("a" -> 1, "b" -> 2)
                    r   <- map.findFirst((k, v) => if v == 2 then Maybe(Found(k, v)) else Maybe.empty)
                yield r
            }.map { r =>
                assert(r == Maybe(Found("b", 2)))
            }
        }

        "findFirst propagates Abort.fail from the predicate" in run {
            for
                map <- STM.run(TMap.init("a" -> 1, "b" -> 2))
                r <- Abort.run {
                    STM.run {
                        map.findFirst { (k, _) =>
                            if k == "a" || k == "b" then Abort.fail(new Exception("nope"))
                            else Maybe.empty
                        }
                    }
                }
            yield assert(r.isFailure)
        }

        "findFirst returns the first match when multiple candidates exist (multi-match oracle)" in run {
            val visited = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                for
                    map <- TMap.init("a" -> 2, "b" -> 4, "c" -> 6)
                    r <- map.findFirst { (k, v) =>
                        visited.incrementAndGet()
                        if v % 2 == 0 then Maybe(k) else Maybe.empty
                    }
                yield r
            }.map { r =>
                assert(r.exists(k => Set("a", "b", "c").contains(k)))
                assert(visited.get() == 1)
            }
        }

        "findFirst empty-map contract: f not invoked, returns Absent (counter-confirmed)" in run {
            val calls = new java.util.concurrent.atomic.AtomicInteger(0)
            STM.run {
                for
                    map <- TMap.init[String, Int]
                    r <- map.findFirst { (_, _) =>
                        calls.incrementAndGet(); Maybe("hit")
                    }
                yield r
            }.map { r =>
                assert(r.isEmpty)
                assert(calls.get() == 0)
            }
        }

        "findFirst with a panicking predicate mid-iteration surfaces panic, no state leaks" in run {
            for
                map <- STM.run(TMap.init("a" -> 1, "b" -> 2, "c" -> 3))
                r <- Abort.run {
                    STM.run {
                        for
                            _ <- map.put("z", 99)
                            _ <- map.findFirst { (k, _) =>
                                if k == "a" || k == "b" || k == "c" || k == "z" then throw new RuntimeException("boom")
                                else Maybe.empty
                            }
                        yield ()
                    }
                }
                snap <- STM.run(map.snapshot)
            yield
                assert(r.isPanic)
                assert(snap == Map("a" -> 1, "b" -> 2, "c" -> 3))
        }
    }

    "snapshot" - {
        "snapshot is immutable with respect to subsequent TMap mutations" in run {
            for
                map  <- STM.run(TMap.init("a" -> 1, "b" -> 2))
                snap <- STM.run(map.snapshot)
                _    <- STM.run(map.put("c", 3))
                _    <- STM.run(map.removeDiscard("a"))
            yield assert(snap == Map("a" -> 1, "b" -> 2))
        }

        "snapshot of an empty TMap returns Map.empty" in run {
            STM.run {
                for
                    map  <- TMap.init[String, Int]
                    snap <- map.snapshot
                yield snap
            }.map { snap =>
                assert(snap == Map.empty[String, Int])
                assert(snap.size == 0)
            }
        }

        "snapshot reflects only entries present at the snapshot's transaction commit" in run {
            for
                map   <- STM.run(TMap.init("a" -> 1, "b" -> 2))
                snap1 <- STM.run(map.snapshot)
                _     <- STM.run(map.put("c", 3))
                _     <- STM.run(map.put("a", 100))
                snap2 <- STM.run(map.snapshot)
            yield
                assert(snap1 == Map("a" -> 1, "b" -> 2))
                assert(snap2 == Map("a" -> 100, "b" -> 2, "c" -> 3))
                assert(snap1 != snap2)
        }
    }

    "consistency and generic types" - {
        "a read returns either Absent or the exact written value (no torn reads)" in run {
            for
                map <- STM.run(TMap.init[Int, Int])
                _   <- STM.run(map.put(1, 100))
                r1  <- STM.run(map.get(1))
                _   <- STM.run(map.put(1, 200))
                r2  <- STM.run(map.get(1))
                _   <- STM.run(map.removeDiscard(1))
                r3  <- STM.run(map.get(1))
            yield
                assert(r1 == Maybe(100))
                assert(r2 == Maybe(200))
                assert(r3.isEmpty)
        }

        "TMap supports V = case class (round-trip through put/get/values/snapshot)" in run {
            val alice = Person("Alice", 30)
            val bob   = Person("Bob", 25)
            STM.run {
                for
                    map  <- TMap.init("a" -> alice)
                    _    <- map.put("b", bob)
                    ga   <- map.get("a")
                    gb   <- map.get("b")
                    vs   <- map.values
                    snap <- map.snapshot
                yield (ga, gb, vs.toSet, snap)
            }.map { case (ga, gb, vs, snap) =>
                assert(ga == Maybe(alice))
                assert(gb == Maybe(bob))
                assert(vs == Set(alice, bob))
                assert(snap == Map("a" -> alice, "b" -> bob))
            }
        }

        "TMap supports K = case class (round-trip through put/get/contains/keys)" in run {
            val k1 = Id(1L)
            val k2 = Id(2L)
            STM.run {
                for
                    map <- TMap.init(k1 -> "a")
                    _   <- map.put(k2, "b")
                    v1  <- map.get(k1)
                    c1  <- map.contains(k1)
                    c3  <- map.contains(Id(99))
                    ks  <- map.keys
                yield (v1, c1, c3, ks.toSet)
            }.map { case (v1, c1, c3, ks) =>
                assert(v1 == Maybe("a") && c1 == true && c3 == false && ks == Set(k1, k2))
            }
        }

        "per-value TRefs created in a rolled-back transaction do not leak into a fresh transaction" in run {
            for
                map <- STM.run(TMap.init[String, Int])
                _ <- Abort.run {
                    STM.run {
                        for
                            _ <- map.put("ghost", 999)
                            _ <- Abort.fail(new Exception("roll back"))
                        yield ()
                    }
                }
                hasGhost <- STM.run(map.contains("ghost"))
                ghostVal <- STM.run(map.get("ghost"))
                size     <- STM.run(map.size)
            yield
                assert(hasGhost == false)
                assert(ghostVal.isEmpty)
                assert(size == 0)
        }
    }

end TMapTest

// Generic-type fixtures.
case class Found(key: String, value: Int) derives CanEqual
case class Person(name: String, age: Int) derives CanEqual
case class Id(value: Long) derives CanEqual
