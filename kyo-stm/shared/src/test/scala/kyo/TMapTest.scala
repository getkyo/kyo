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

        "add and contains" in run {
            STM.run {
                for
                    map     <- TMap.init[String, Int]()
                    _       <- map.put("key", 42)
                    exists  <- map.contains("key")
                    missing <- map.contains("nonexistent")
                    value   <- map.get("key")
                yield (exists, missing, value)
            }.map { case (exists, missing, value) =>
                assert(exists && missing && value == Maybe(42))
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
                result.isFail &&
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
                result.isFail &&
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
                result.isFail &&
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
                result.isFail &&
                    snapshot.isEmpty
            )
        }

        "nested effects with rollback" in run {
            Var.run(0) {
                for
                    map <- STM.run(TMap.init[String, Int]("start" -> 0))
                    result <- Abort.run {
                        STM.run(Var.isolate.update) {
                            for
                                _ <- map.put("key1", 100)
                                _ <- Var.set(1)
                                _ <- Abort.fail(new Exception("Nested effect failure"))
                                _ <- map.put("key2", 200)
                            yield ()
                        }
                    }
                    snapshot <- STM.run(map.snapshot)
                    varValue <- Var.get[Int]
                yield assert(
                    result.isFail &&
                        snapshot == Map("start" -> 0) &&
                        varValue == 0
                )
            }
        }
    }

    "Concurrency" - {
        val repeats = 100

        "concurrent modifications" in run {
            (for
                size     <- Choice.get(Seq(1, 10, 100))
                map      <- STM.run(TMap.init[Int, Int]())
                _        <- Async.parallelUnbounded((1 to size).map(i => STM.run(map.put(i, i))))
                snapshot <- STM.run(map.snapshot)
            yield assert(
                snapshot.size == size &&
                    snapshot.forall((k, v) => k == v && k >= 1 && k <= size)
            ))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent reads and writes" in run {
            (for
                size  <- Choice.get(Seq(1, 10, 100))
                map   <- STM.run(TMap.init[Int, Int]())
                latch <- Latch.init(1)

                writeFiber <- Async.run(
                    latch.await.andThen(
                        Async.parallelUnbounded(
                            (1 to size).map(i =>
                                STM.run(map.put(i, i * 2))
                            )
                        )
                    )
                )

                readFiber <- Async.run(
                    latch.await.andThen(
                        Async.parallelUnbounded(
                            (1 to size).map(i =>
                                STM.run(map.get(i))
                            )
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
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent updates" in run {
            (for
                size <- Choice.get(Seq(1, 10, 100))
                map  <- STM.run(TMap.init[Int, Int]())
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => map.put(i, 1))
                }
                _ <- Async.parallelUnbounded(
                    (1 to 10).map(_ =>
                        STM.run {
                            Kyo.foreachDiscard((1 to size)) { i =>
                                map.updateWith(i)(v => Maybe(v.getOrElse(0) + 1))
                            }
                        }
                    )
                )
                snapshot <- STM.run(map.snapshot)
            yield assert(
                snapshot.size == size &&
                    snapshot.forall((_, v) => v == 11) // Initial 1 + 10 increments
            ))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent removals" in run {
            (for
                size <- Choice.get(Seq(1, 10, 100))
                map  <- STM.run(TMap.init[Int, Int]())
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => map.put(i, i))
                }
                _ <- Async.parallelUnbounded(
                    (1 to size).map(i =>
                        STM.run(map.removeDiscard(i))
                    )
                )
                snapshot <- STM.run(map.snapshot)
            yield assert(snapshot.isEmpty))
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent bulk operations" in run {
            (for
                size <- Choice.get(Seq(1, 10, 100))
                map  <- STM.run(TMap.init[Int, Int]())
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => map.put(i, i))
                }

                filterOps = Async.parallelUnbounded(
                    (1 to 5).map(_ =>
                        STM.run(map.filter((k, v) => v % 2 == 0))
                    )
                )

                foldOps = Async.parallelUnbounded(
                    (1 to 5).map(_ =>
                        STM.run(map.fold(0)((acc, _, v) => acc + v))
                    )
                )

                _        <- filterOps
                sums     <- foldOps
                snapshot <- STM.run(map.snapshot)
            yield
                assert(snapshot.forall((_, v) => v % 2 == 0))
                assert(sums.forall(_ == snapshot.values.sum))
            )
                .pipe(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }
    }

end TMapTest
