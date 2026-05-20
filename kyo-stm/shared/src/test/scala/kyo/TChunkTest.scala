package kyo

class TChunkTest extends Test:

    "init" - {
        "creates an empty chunk" in run {
            for
                chunk  <- TChunk.init[Int]
                empty  <- STM.run(chunk.isEmpty)
                size   <- STM.run(chunk.size)
                result <- STM.run(chunk.snapshot)
            yield
                assert(empty)
                assert(size == 0)
                assert(result.isEmpty)
        }

        "creates a chunk with initial values" in run {
            for
                chunk  <- TChunk.init(1, 2, 3)
                empty  <- STM.run(chunk.isEmpty)
                size   <- STM.run(chunk.size)
                result <- STM.run(chunk.snapshot)
            yield
                assert(!empty)
                assert(size == 3)
                assert(result == Chunk(1, 2, 3))
        }
    }

    "basic operations" - {
        "append" in run {
            for
                chunk  <- TChunk.init[Int]
                _      <- STM.run(chunk.append(1))
                _      <- STM.run(chunk.append(2))
                _      <- STM.run(chunk.append(3))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(1, 2, 3))
        }

        "get" in run {
            for
                chunk  <- TChunk.init(1, 2, 3)
                first  <- STM.run(chunk.get(0))
                second <- STM.run(chunk.get(1))
                third  <- STM.run(chunk.get(2))
            yield
                assert(first == 1)
                assert(second == 2)
                assert(third == 3)
        }

        "head and last" in run {
            for
                chunk <- TChunk.init(1, 2, 3)
                head  <- STM.run(chunk.head)
                last  <- STM.run(chunk.last)
            yield
                assert(head == 1)
                assert(last == 3)
        }

        "compact" in run {
            for
                chunk <- TChunk.init[Int]
                _     <- STM.run(chunk.append(1))
                _     <- STM.run(chunk.append(2))
                _     <- STM.run(chunk.append(3))
                _     <- STM.run(chunk.compact)
                snap  <- STM.run(chunk.snapshot)
            yield
                assert(snap.isInstanceOf[Chunk.Indexed[Int]])
                assert(snap == Chunk(1, 2, 3))
        }

        "use" in run {
            for
                chunk  <- TChunk.init(1, 2, 3)
                sum    <- STM.run(chunk.use(_.sum))
                first  <- STM.run(chunk.use(_.head))
                mapped <- STM.run(chunk.use(_.map(_ * 2)))
            yield
                assert(sum == 6)
                assert(first == 1)
                assert(mapped == Chunk(2, 4, 6))
        }
    }

    "modification operations" - {
        "take" in run {
            for
                chunk  <- TChunk.init(1, 2, 3, 4, 5)
                _      <- STM.run(chunk.take(3))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(1, 2, 3))
        }

        "drop" in run {
            for
                chunk  <- TChunk.init(1, 2, 3, 4, 5)
                _      <- STM.run(chunk.drop(2))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(3, 4, 5))
        }

        "dropRight" in run {
            for
                chunk  <- TChunk.init(1, 2, 3, 4, 5)
                _      <- STM.run(chunk.dropRight(2))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(1, 2, 3))
        }

        "slice" in run {
            for
                chunk  <- TChunk.init(1, 2, 3, 4, 5)
                _      <- STM.run(chunk.slice(1, 4))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(2, 3, 4))
        }

        "concat" in run {
            for
                chunk  <- TChunk.init(1, 2, 3)
                _      <- STM.run(chunk.concat(Chunk(4, 5, 6)))
                result <- STM.run(chunk.snapshot)
            yield assert(result == Chunk(1, 2, 3, 4, 5, 6))
        }
    }

    "filter" in run {
        for
            chunk  <- TChunk.init(1, 2, 3, 4, 5)
            _      <- STM.run(chunk.filter(_ % 2 == 0))
            result <- STM.run(chunk.snapshot)
        yield assert(result == Chunk(2, 4))
    }

    "transaction isolation" - {
        "concurrent modifications" in run {
            for
                chunk  <- TChunk.init[Int]
                _      <- Async.foreach(1 to 100, 100)(i => STM.run(chunk.append(i)))
                result <- STM.run(chunk.snapshot)
            yield
                assert(result.toSet == (1 to 100).toSet)
                assert(result.length == 100)
        }

        "rollback on failure" in run {
            for
                chunk <- TChunk.init(1, 2, 3)
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- chunk.append(4)
                            _ <- chunk.append(5)
                            _ <- Abort.fail(new Exception("Test failure"))
                        yield ()
                    }
                }
                snapshot <- STM.run(chunk.snapshot)
            yield
                assert(result.isFailure)
                assert(snapshot == Chunk(1, 2, 3))
        }

        "maintains compactness after transaction" in run {
            for
                chunk <- TChunk.init(1, 2, 3)
                _     <- STM.run(chunk.compact)
                _ <- STM.run {
                    for
                        _ <- chunk.append(4)
                        _ <- chunk.append(5)
                        _ <- chunk.compact
                    yield ()
                }
                snap <- STM.run(chunk.snapshot)
            yield
                assert(snap.isInstanceOf[Chunk.Indexed[Int]])
                assert(snap == Chunk(1, 2, 3, 4, 5))
        }
    }

    "concurrency" - {
        val repeats = 100

        "concurrent modifications" in run {
            (for
                size     <- Choice.eval(1, 10, 100)
                chunk    <- TChunk.init[Int]()
                _        <- Async.foreach(1 to size, size)(i => STM.run(chunk.append(i)))
                snapshot <- STM.run(chunk.snapshot)
            yield assert(
                snapshot.length == size &&
                    snapshot.toSet == (1 to size).toSet
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent filtering" in run {
            val retries = STM.defaultRetrySchedule.forever
            (for
                size  <- Choice.eval(1, 10, 100)
                chunk <- TChunk.init[Int]()
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => chunk.append(i))
                }
                _ <- Async.fill(5, 5)(
                    STM.run(retries)(chunk.filter(_ % 2 == 0))
                )
                snapshot <- STM.run(chunk.snapshot)
            yield assert(
                snapshot.forall(_ % 2 == 0) &&
                    snapshot.length == size / 2
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent slice operations" in run {
            val retrySchedule = STM.defaultRetrySchedule.forever
            (for
                size  <- Choice.eval(1, 10, 100)
                chunk <- TChunk.init[Int]()
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => chunk.append(i))
                }
                _ <- Async.fill(5, 5)(
                    STM.run(retrySchedule) {
                        for
                            midpoint <- chunk.size.map(_ / 2)
                            _        <- chunk.slice(0, midpoint)
                        yield ()
                    }
                )
                snapshot <- STM.run(chunk.snapshot)
            yield assert(
                snapshot.length <= size / 2 &&
                    snapshot.toSet.subsetOf((1 to size).toSet)
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent compaction" in run {
            val retrySchedule = STM.defaultRetrySchedule.forever
            (for
                size  <- Choice.eval(1, 10, 100)
                chunk <- TChunk.init[Int]()
                _ <- STM.run {
                    Kyo.foreachDiscard((1 to size))(i => chunk.append(i))
                }
                _ <- Async.fill(5, 5)(
                    STM.run(retrySchedule)(chunk.compact)
                )
                snapshot <- STM.run(chunk.snapshot)
            yield assert(
                snapshot.isInstanceOf[Chunk.Indexed[Int]] &&
                    snapshot.length == size &&
                    snapshot.toSet == (1 to size).toSet
            ))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }
    }

    "TChunk" - {

        "init" - {
            "supports non-Int element types (String)" in run {
                for
                    chunk <- TChunk.init("a", "b", "c")
                    size  <- STM.run(chunk.size)
                    snap  <- STM.run(chunk.snapshot)
                yield
                    assert(size == 3)
                    assert(snap == Chunk("a", "b", "c"))
            }

            "no-arg with String type parameter" in run {
                for
                    chunk <- TChunk.init[String]
                    empty <- STM.run(chunk.isEmpty)
                    size  <- STM.run(chunk.size)
                    snap  <- STM.run(chunk.snapshot)
                yield
                    assert(empty)
                    assert(size == 0)
                    assert(snap == Chunk.empty[String])
            }

            "empty vararg yields empty chunk" in run {
                for
                    chunk <- TChunk.init[Int]()
                    size  <- STM.run(chunk.size)
                    empty <- STM.run(chunk.isEmpty)
                    snap  <- STM.run(chunk.snapshot)
                yield
                    assert(empty)
                    assert(size == 0)
                    assert(snap.isEmpty)
            }

            "single-value vararg" in run {
                for
                    chunk <- TChunk.init(42)
                    size  <- STM.run(chunk.size)
                    snap  <- STM.run(chunk.snapshot)
                yield
                    assert(size == 1)
                    assert(snap == Chunk(42))
            }

            "large vararg (>100 elements) preserves all in order" in run {
                val xs = (1 to 1024)
                for
                    chunk <- TChunk.init(xs*)
                    size  <- STM.run(chunk.size)
                    snap  <- STM.run(chunk.snapshot)
                yield
                    assert(size == 1024)
                    assert(snap == Chunk.from(xs))
                end for
            }

            "direct Chunk argument is wrapped verbatim" in run {
                val input: Chunk[Int] = Chunk(7, 8, 9)
                for
                    chunk <- TChunk.init(input)
                    snap  <- STM.run(chunk.snapshot)
                    size  <- STM.run(chunk.size)
                yield
                    assert(snap == input)
                    assert(size == 3)
                end for
            }

            "Chunk.Indexed and very large direct Chunk" in run {
                val indexed: Chunk[Int] = Chunk.from(1 to 500).toIndexed
                val sliced: Chunk[Int]  = Chunk.from(1 to 100).drop(10).take(80)
                for
                    a     <- TChunk.init(indexed)
                    b     <- TChunk.init(sliced)
                    aSnap <- STM.run(a.snapshot)
                    bSnap <- STM.run(b.snapshot)
                yield
                    assert(aSnap == indexed)
                    assert(bSnap == sliced)
                    assert(aSnap.length == 500)
                    assert(bSnap.length == 80)
                end for
            }

            "direct Chunk overload is distinct from no-arg and vararg overloads" in run {
                val xs = Chunk.from(100 to 105)
                for
                    direct <- TChunk.init[Int](xs: Chunk[Int])
                    sized  <- STM.run(direct.size)
                    snap   <- STM.run(direct.snapshot)
                yield
                    assert(sized == 6)
                    assert(snap == Chunk(100, 101, 102, 103, 104, 105))
                end for
            }
        }

        "initWith" - {
            "directly invoked returns f(tchunk) result" in run {
                val input: Chunk[Int] = Chunk(10, 20, 30)
                TChunk.initWith(input) { tc =>
                    STM.run(tc.size)
                }.map(out => assert(out == 3))
            }

            "non-identity lambda with effectful S returns B in Sync & S" in run {
                val initial = Chunk(1, 2, 3)
                val program: String < (Sync & Async & Abort[String | FailedTransaction]) =
                    TChunk.initWith(initial) { tc =>
                        STM.run(tc.snapshot).map { snap =>
                            if snap.isEmpty then Abort.fail("empty") else snap.mkString(",")
                        }
                    }
                Abort.run(program).map(res => assert(res == Result.succeed("1,2,3")))
            }

            "combines init and first op in single transaction" in run {
                val initial = Chunk(1, 2, 3)
                val r: (Int, Chunk[Int]) < (Sync & Async & Abort[FailedTransaction]) =
                    TChunk.initWith(initial) { tc =>
                        STM.run {
                            for
                                _    <- tc.append(4)
                                _    <- tc.append(5)
                                sz   <- tc.size
                                snap <- tc.snapshot
                            yield (sz, snap)
                        }
                    }
                r.map { res =>
                    val (sz, snap) = res
                    assert(sz == 5)
                    assert(snap == Chunk(1, 2, 3, 4, 5))
                }
            }

            "observable atomicity (writes inside f visible without external commit boundary)" in run {
                val initial = Chunk(1, 2, 3)
                val program: Chunk[Int] < (Sync & Async & Abort[FailedTransaction]) =
                    TChunk.initWith(initial) { tc =>
                        STM.run {
                            for
                                _    <- tc.append(4)
                                snap <- tc.snapshot
                            yield snap
                        }
                    }
                program.map(seen => assert(seen == Chunk(1, 2, 3, 4)))
            }

            "f that throws surfaces the thrown exception" in run {
                final case class Boom() extends RuntimeException
                val program: Int < Sync = TChunk.initWith(Chunk(1)) { _ => throw Boom() }
                Abort.run[Throwable](program).map { r =>
                    assert(r.isError && r.failureOrPanic.get.isInstanceOf[Boom])
                }
            }

            "f performs STM operations on the freshly created chunk" in run {
                val program: Chunk[Int] < (Sync & Async & Abort[FailedTransaction]) =
                    TChunk.initWith(Chunk(1, 2, 3)) { tc =>
                        STM.run {
                            for
                                _    <- tc.append(4)
                                _    <- tc.append(5)
                                _    <- tc.filter(_ % 2 == 1)
                                snap <- tc.snapshot
                            yield snap
                        }
                    }
                program.map(out => assert(out == Chunk(1, 3, 5)))
            }

            "preserves S effect across return-type boundary" in run {
                val expected: Chunk[Int] = Chunk(1, 2, 3)
                val a: Chunk[Int] < (Sync & Abort[String]) =
                    TChunk.initWith(expected)(_ => Abort.fail("S=Abort"))
                val b: Chunk[Int] < (Sync & Async & Var[Int] & Abort[FailedTransaction]) =
                    TChunk.initWith(expected)(tc => Var.update[Int](_ + 1).andThen(STM.run(tc.snapshot)))
                for
                    ra   <- Abort.run(a)
                    bRun <- Var.runTuple(0)(b)
                yield
                    val (varOut, bSnap) = bRun
                    assert(ra == Result.fail("S=Abort"))
                    assert(bSnap == expected)
                    assert(varOut == 1)
                end for
            }
        }

        "size" - {
            "single-element and large (>100) chunks" in run {
                for
                    one     <- TChunk.init(42)
                    big     <- TChunk.init(Chunk.from(1 to 5000))
                    oneSize <- STM.run(one.size)
                    bigSize <- STM.run(big.size)
                yield
                    assert(oneSize == 1)
                    assert(bigSize == 5000)
            }

            "reflects mutations within the same transaction" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    sizes <- STM.run {
                        for
                            _       <- chunk.take(4)
                            sTake   <- chunk.size
                            _       <- chunk.drop(1)
                            sDrop   <- chunk.size
                            _       <- chunk.dropRight(0)
                            sDropR  <- chunk.size
                            _       <- chunk.slice(0, 2)
                            sSlice  <- chunk.size
                            _       <- chunk.concat(Chunk(9))
                            sConcat <- chunk.size
                            _       <- chunk.filter(_ != 9)
                            sFilter <- chunk.size
                        yield (sTake, sDrop, sDropR, sSlice, sConcat, sFilter)
                    }
                yield
                    val (sTake, sDrop, sDropR, sSlice, sConcat, sFilter) = sizes
                    assert(sTake == 4)
                    assert(sDrop == 3)
                    assert(sDropR == 3)
                    assert(sSlice == 2)
                    assert(sConcat == 3)
                    assert(sFilter == 2)
            }
        }

        "isEmpty" - {
            "after drop(size), slice(0,0), and take(0) mutations" in run {
                val base = Chunk(1, 2, 3, 4)
                for
                    a  <- TChunk.init(base)
                    _  <- STM.run(a.drop(4))
                    ea <- STM.run(a.isEmpty)
                    b  <- TChunk.init(base)
                    _  <- STM.run(b.slice(0, 0))
                    eb <- STM.run(b.isEmpty)
                    c  <- TChunk.init(base)
                    _  <- STM.run(c.take(0))
                    ec <- STM.run(c.isEmpty)
                yield
                    assert(ea)
                    assert(eb)
                    assert(ec)
                end for
            }

            "true after drop(size) and slice(0,0) within same transaction" in run {
                for
                    a  <- TChunk.init(1, 2, 3)
                    r1 <- STM.run(for _ <- a.drop(3); e <- a.isEmpty yield e)
                    b  <- TChunk.init(1, 2, 3)
                    r2 <- STM.run(for _ <- b.slice(0, 0); e <- b.isEmpty yield e)
                yield
                    assert(r1)
                    assert(r2)
            }
        }

        "get" - {
            "boundary indices (0, size-1) on larger chunk" in run {
                val xs = (1 to 50).toList
                for
                    chunk <- TChunk.init(xs*)
                    first <- STM.run(chunk.get(0))
                    mid   <- STM.run(chunk.get(25))
                    last  <- STM.run(chunk.get(49))
                yield
                    assert(first == 1)
                    assert(mid == 26)
                    assert(last == 50)
                end for
            }

            "throws IndexOutOfBoundsException for -1, size, Int.MaxValue, and on empty chunk" in run {
                for
                    chunk   <- TChunk.init(1, 2, 3)
                    empty   <- TChunk.init[Int]
                    neg     <- Abort.run[Throwable](STM.run(chunk.get(-1)))
                    end     <- Abort.run[Throwable](STM.run(chunk.get(3)))
                    huge    <- Abort.run[Throwable](STM.run(chunk.get(Int.MaxValue)))
                    onEmpty <- Abort.run[Throwable](STM.run(empty.get(0)))
                yield
                    assert(neg.isPanic && neg.failureOrPanic.get.isInstanceOf[IndexOutOfBoundsException])
                    assert(end.isPanic && end.failureOrPanic.get.isInstanceOf[IndexOutOfBoundsException])
                    assert(huge.isPanic && huge.failureOrPanic.get.isInstanceOf[IndexOutOfBoundsException])
                    assert(onEmpty.isPanic && onEmpty.failureOrPanic.get.isInstanceOf[IndexOutOfBoundsException])
            }
        }

        "use" - {
            "arbitrary B (Unit, String, tuple)" in run {
                for
                    ref   <- AtomicInt.init(0)
                    chunk <- TChunk.init(1, 2, 3)
                    u     <- STM.run(chunk.use(_ => ref.incrementAndGet.map(_ => ())))
                    c     <- ref.get
                    s     <- STM.run(chunk.use(_.mkString("[", ",", "]")))
                    t     <- STM.run(chunk.use(c => (c.head, c.last)))
                yield
                    assert(u == ())
                    assert(c == 1)
                    assert(s == "[1,2,3]")
                    assert(t == (1, 3))
            }

            "effectful read function aborting leaves chunk unchanged" in run {
                val baseline = Chunk(1, 2, 3)
                for
                    chunk <- TChunk.init(baseline)
                    res <- Abort.run {
                        STM.run {
                            chunk.use(c => Abort.fail(s"saw size=${c.length}"))
                        }
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(res == Result.fail("saw size=3"))
                    assert(snap == baseline)
                end for
            }

            "effectful f (Abort.fail) rolls back surrounding STM updates" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    r <- Abort.run {
                        STM.run {
                            for
                                _   <- chunk.append(99)
                                out <- chunk.use(c => Abort.fail[String](s"size=${c.length}"))
                            yield out
                        }
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.failure.exists {
                        case s: String => s == "size=4"
                        case _         => false
                    })
                    assert(snap == Chunk(1, 2, 3))
            }

            "f that throws surfaces panic and rolls back" in run {
                final case class Boom() extends RuntimeException("boom")
                for
                    chunk <- TChunk.init(1, 2, 3)
                    r <- Abort.run[Throwable] {
                        STM.run {
                            for
                                _   <- chunk.append(99)
                                out <- chunk.use(_ => throw Boom())
                            yield out
                        }
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isPanic && r.failureOrPanic.get.isInstanceOf[Boom])
                    assert(snap == Chunk(1, 2, 3))
                end for
            }
        }

        "head" - {
            "returns first element for singleton and large chunks" in run {
                for
                    single <- TChunk.init(42)
                    big    <- TChunk.init(Chunk.from(100 to 200))
                    h1     <- STM.run(single.head)
                    h2     <- STM.run(big.head)
                yield
                    assert(h1 == 42)
                    assert(h2 == 100)
            }

            "throws NoSuchElementException on empty" in run {
                for
                    empty <- TChunk.init[Int]
                    r     <- Abort.run[Throwable](STM.run(empty.head))
                yield assert(r.isPanic && r.failureOrPanic.get.isInstanceOf[NoSuchElementException])
            }
        }

        "last" - {
            "returns final element for singleton and large chunks" in run {
                for
                    single <- TChunk.init(42)
                    big    <- TChunk.init(Chunk.from(100 to 200))
                    l1     <- STM.run(single.last)
                    l2     <- STM.run(big.last)
                yield
                    assert(l1 == 42)
                    assert(l2 == 200)
            }

            "throws NoSuchElementException on empty" in run {
                for
                    empty <- TChunk.init[Int]
                    r     <- Abort.run[Throwable](STM.run(empty.last))
                yield assert(r.isPanic && r.failureOrPanic.get.isInstanceOf[NoSuchElementException])
            }
        }

        "append" - {
            "preserves order under interleaved sub-transaction rollbacks inside a successful outer transaction" in run {
                for
                    chunk <- TChunk.init[Int]
                    _ <- STM.run {
                        for
                            _ <- chunk.append(1)
                            _ <- Abort.run(STM.run(chunk.append(2).andThen(Abort.fail("nope"))))
                            _ <- chunk.append(3)
                        yield ()
                    }
                    snap <- STM.run(chunk.snapshot)
                yield assert(snap == Chunk(1, 3))
            }

            "null for reference-type A is stored without NPE" in run {
                val s: String = null
                for
                    chunk <- TChunk.init[String]
                    _     <- STM.run(chunk.append(s))
                    _     <- STM.run(chunk.append("after"))
                    snap  <- STM.run(chunk.snapshot)
                yield
                    assert(snap.length == 2)
                    assert(snap(0) == null)
                    assert(snap(1) == "after")
                end for
            }

            "two TChunks created in the same STM.run are independent" in run {
                for
                    a <- TChunk.init(1, 2)
                    b <- TChunk.init(10, 20)
                    snaps <- STM.run {
                        for
                            _ <- a.append(3)
                            _ <- b.append(30)
                            x <- a.snapshot
                            y <- b.snapshot
                        yield (x, y)
                    }
                yield
                    val (snapA, snapB) = snaps
                    assert(snapA == Chunk(1, 2, 3))
                    assert(snapB == Chunk(10, 20, 30))
            }
        }

        "take" - {
            "n = 0 yields empty chunk" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    _     <- STM.run(chunk.take(0))
                    snap  <- STM.run(chunk.snapshot)
                    empty <- STM.run(chunk.isEmpty)
                yield
                    assert(empty)
                    assert(snap.isEmpty)
            }

            "on empty chunk is a no-op" in run {
                for
                    chunk <- TChunk.init[Int]
                    _     <- STM.run(chunk.take(5))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap.isEmpty)
            }

            "rolls back on STM failure" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    r <- Abort.run {
                        STM.run(chunk.take(2).andThen(Abort.fail("nope")))
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isFailure)
                    assert(snap == Chunk(1, 2, 3, 4, 5))
            }
        }

        "drop" - {
            "n = 0 is a no-op" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    _     <- STM.run(chunk.drop(0))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap == Chunk(1, 2, 3))
            }

            "on empty chunk is a no-op" in run {
                for
                    chunk <- TChunk.init[Int]
                    _     <- STM.run(chunk.drop(5))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap.isEmpty)
            }

            "rolls back on STM failure" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    r <- Abort.run {
                        STM.run(chunk.drop(2).andThen(Abort.fail("nope")))
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isFailure)
                    assert(snap == Chunk(1, 2, 3, 4, 5))
            }
        }

        "dropRight" - {
            "n = 0 is a no-op" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    _     <- STM.run(chunk.dropRight(0))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap == Chunk(1, 2, 3))
            }

            "on empty chunk is a no-op" in run {
                for
                    chunk <- TChunk.init[Int]
                    _     <- STM.run(chunk.dropRight(3))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap.isEmpty)
            }

            "rolls back on STM failure" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    r <- Abort.run {
                        STM.run(chunk.dropRight(2).andThen(Abort.fail("nope")))
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isFailure)
                    assert(snap == Chunk(1, 2, 3, 4, 5))
            }
        }

        "slice" - {
            "(0, 0) yields empty chunk" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    _     <- STM.run(chunk.slice(0, 0))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap.isEmpty)
            }

            "from > until yields empty chunk" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    _     <- STM.run(chunk.slice(10, 5))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap.isEmpty)
            }

            "on empty chunk is a no-op" in run {
                for
                    chunk <- TChunk.init[Int]
                    _     <- STM.run(chunk.slice(0, 3))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap.isEmpty)
            }

            "rolls back on STM failure" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    r <- Abort.run {
                        STM.run(chunk.slice(1, 4).andThen(Abort.fail("nope")))
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isFailure)
                    assert(snap == Chunk(1, 2, 3, 4, 5))
            }

            "concurrent slice assertion is non-vacuous" in runNotJS {
                val retrySchedule = STM.defaultRetrySchedule.forever
                val size          = 100
                for
                    chunk <- TChunk.init[Int]()
                    _     <- STM.run(Kyo.foreachDiscard(1 to size)(i => chunk.append(i)))
                    _ <- Async.fill(5, 5)(STM.run(retrySchedule) {
                        for
                            m <- chunk.size.map(_ / 2)
                            _ <- chunk.slice(0, m)
                        yield ()
                    })
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(snap.length >= size / 32)
                    assert(snap.length <= size / 2)
                    assert(snap == Chunk.from(1 to snap.length))
                end for
            }

            "degenerate ranges per Akka MatchError family" in run {
                val cases = List(
                    (0, 0),
                    (5, 5),
                    (10, 5),
                    (Int.MaxValue, 0)
                )
                Kyo.foreach(cases) { c =>
                    val (f, u) = c
                    for
                        chunk <- TChunk.init(1, 2, 3, 4, 5)
                        _     <- STM.run(chunk.slice(f, u))
                        snap  <- STM.run(chunk.snapshot)
                    yield (c, snap)
                    end for
                }.map { results =>
                    results.foreach { r =>
                        val ((f, u), snap) = r
                        assert(snap.isEmpty, s"slice($f, $u) should yield empty, got $snap")
                    }
                    succeed
                }
            }
        }

        "concat" - {
            "with Chunk.empty is identity" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    _     <- STM.run(chunk.concat(Chunk.empty[Int]))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap == Chunk(1, 2, 3))
            }

            "onto empty self produces other" in run {
                for
                    chunk <- TChunk.init[Int]
                    _     <- STM.run(chunk.concat(Chunk(7, 8, 9)))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap == Chunk(7, 8, 9))
            }

            "self-concat using snapshot doubles the chunk" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    _ <- STM.run {
                        for
                            snap <- chunk.snapshot
                            _    <- chunk.concat(snap)
                        yield ()
                    }
                    out <- STM.run(chunk.snapshot)
                yield assert(out == Chunk(1, 2, 3, 1, 2, 3))
            }

            "rolls back on STM failure" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    r <- Abort.run {
                        STM.run(chunk.concat(Chunk(4, 5, 6)).andThen(Abort.fail("nope")))
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isFailure)
                    assert(snap == Chunk(1, 2, 3))
            }

            "large other (>100) preserves order" in run {
                val big = Chunk.from(1 to 1024)
                for
                    chunk <- TChunk.init(0)
                    _     <- STM.run(chunk.concat(big))
                    snap  <- STM.run(chunk.snapshot)
                yield
                    assert(snap.length == 1025)
                    assert(snap.head == 0)
                    assert(snap.tail == big)
                end for
            }
        }

        "filter" - {
            "effectful predicate aborting leaves chunk unchanged" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    r <- Abort.run {
                        STM.run {
                            chunk.filter[Abort[String]] { i =>
                                if i == 3 then Abort.fail("hit 3") else (i % 2 == 0)
                            }
                        }
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r == Result.fail("hit 3"))
                    assert(snap == Chunk(1, 2, 3, 4, 5))
            }

            "always-true predicate is identity" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    _     <- STM.run(chunk.filter(_ => true))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap == Chunk(1, 2, 3, 4, 5))
            }

            "always-false predicate yields empty" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    _     <- STM.run(chunk.filter(_ => false))
                    snap  <- STM.run(chunk.snapshot)
                    empty <- STM.run(chunk.isEmpty)
                yield
                    assert(empty)
                    assert(snap.isEmpty)
            }

            "on empty chunk is a no-op" in run {
                for
                    callCount <- AtomicInt.init(0)
                    chunk     <- TChunk.init[Int]
                    _         <- STM.run(chunk.filter(_ => callCount.incrementAndGet.map(_ => true)))
                    snap      <- STM.run(chunk.snapshot)
                    count     <- callCount.get
                yield
                    assert(snap.isEmpty)
                    assert(count == 0)
            }

            "predicate that throws rolls back chunk" in run {
                final case class Boom(i: Int) extends RuntimeException
                for
                    chunk <- TChunk.init((1 to 10)*)
                    r <- Abort.run[Throwable] {
                        STM.run {
                            chunk.filter { i => if i == 5 then throw Boom(i) else i % 2 == 0 }
                        }
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isPanic && r.failureOrPanic.get.isInstanceOf[Boom])
                    assert(snap == Chunk.from(1 to 10))
                end for
            }

            "preserves order on a 50-element chunk" in run {
                val xs       = (1 to 50).toList
                val expected = xs.filter(_ % 3 == 0)
                for
                    chunk <- TChunk.init(xs*)
                    _     <- STM.run(chunk.filter(_ % 3 == 0))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap == Chunk.from(expected))
                end for
            }

            "resulting chunk has a stable Chunk representation across calls" in run {
                for
                    a     <- TChunk.init((1 to 20)*)
                    _     <- STM.run(a.filter(_ % 2 == 0))
                    snapA <- STM.run(a.snapshot)
                    b     <- TChunk.init((1 to 20)*)
                    _     <- STM.run(b.filter(_ % 2 == 0))
                    snapB <- STM.run(b.snapshot)
                yield
                    assert(snapA == snapB)
                    assert(snapA == Chunk(2, 4, 6, 8, 10, 12, 14, 16, 18, 20))
                    assert(snapA.getClass.equals(snapB.getClass))
            }

            "predicate invocation count equals chunk size on single successful attempt" in run {
                val n = 30
                for
                    counter <- AtomicInt.init(0)
                    chunk   <- TChunk.init((1 to n)*)
                    _ <- STM.run {
                        chunk.filter(_ => counter.incrementAndGet.map(_ => true))
                    }
                    c    <- counter.get
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(c == n, s"expected $n predicate calls, got $c")
                    assert(snap == Chunk.from(1 to n))
                end for
            }

            "predicate panic mid-iteration rolls back without partial removals" in run {
                final case class MidFilterBoom() extends RuntimeException
                for
                    chunk <- TChunk.init((1 to 10)*)
                    r <- Abort.run[Throwable] {
                        STM.run {
                            chunk.filter { i =>
                                if i == 5 then throw MidFilterBoom() else i % 2 == 0
                            }
                        }
                    }
                    snap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isPanic && r.failureOrPanic.get.isInstanceOf[MidFilterBoom])
                    assert(snap == Chunk.from(1 to 10))
                end for
            }
        }

        "compact" - {
            "on empty chunk yields empty indexed chunk" in run {
                for
                    chunk <- TChunk.init[Int]
                    _     <- STM.run(chunk.compact)
                    snap  <- STM.run(chunk.snapshot)
                yield
                    assert(snap.isEmpty)
                    assert(snap.isInstanceOf[Chunk.Indexed[Int]])
            }

            "is idempotent on an already-indexed chunk" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    _     <- STM.run(chunk.compact)
                    snap1 <- STM.run(chunk.snapshot)
                    _     <- STM.run(chunk.compact)
                    snap2 <- STM.run(chunk.snapshot)
                yield
                    assert(snap1.isInstanceOf[Chunk.Indexed[Int]])
                    assert(snap2.isInstanceOf[Chunk.Indexed[Int]])
                    assert(snap1 == snap2)
                    assert(snap2 == Chunk(1, 2, 3))
            }

            "after slice yields an indexed chunk whose representation is independent" in run {
                val initial = Chunk.from(1 to 1000)
                for
                    chunk           <- TChunk.init(initial)
                    _               <- STM.run(chunk.slice(0, 3))
                    preCompactSnap  <- STM.run(chunk.snapshot)
                    _               <- STM.run(chunk.compact)
                    postCompactSnap <- STM.run(chunk.snapshot)
                    checkSnap <-
                        val arr: Array[Int] = postCompactSnap.toArray
                        arr(0) = -99
                        STM.run(chunk.snapshot)
                yield
                    assert(preCompactSnap == Chunk(1, 2, 3))
                    assert(postCompactSnap == Chunk(1, 2, 3))
                    assert(postCompactSnap.isInstanceOf[Chunk.Indexed[Int]])
                    assert(checkSnap == Chunk(1, 2, 3))
                end for
            }

            "rolls back on STM failure" in run {
                for
                    chunk   <- TChunk.init(1, 2, 3)
                    _       <- STM.run(chunk.slice(0, 2))
                    preSnap <- STM.run(chunk.snapshot)
                    r <- Abort.run {
                        STM.run(chunk.compact.andThen(Abort.fail("nope")))
                    }
                    postSnap <- STM.run(chunk.snapshot)
                yield
                    assert(r.isFailure)
                    assert(postSnap == preSnap)
            }

            "converts a non-indexed (sliced) chunk into Chunk.Indexed" in run {
                val big = Chunk.from(1 to 100)
                for
                    chunk    <- TChunk.init(big)
                    _        <- STM.run(chunk.slice(10, 20))
                    preSnap  <- STM.run(chunk.snapshot)
                    _        <- STM.run(chunk.compact)
                    postSnap <- STM.run(chunk.snapshot)
                yield
                    assert(
                        !preSnap.isInstanceOf[Chunk.Indexed[Int]],
                        s"setup is invalid: pre-compact already indexed (got ${preSnap.getClass.getName})"
                    )
                    assert(postSnap.isInstanceOf[Chunk.Indexed[Int]])
                    assert(postSnap == Chunk.from(11 to 20))
                end for
            }

            "maintainsCompactness — second compact is a no-op without regressing representation" in run {
                val big = Chunk.from(1 to 200)
                for
                    chunk <- TChunk.init(big)
                    _     <- STM.run(chunk.slice(50, 60))
                    pre   <- STM.run(chunk.snapshot)
                    _     <- STM.run(chunk.compact)
                    mid   <- STM.run(chunk.snapshot)
                    _     <- STM.run(chunk.compact)
                    post  <- STM.run(chunk.snapshot)
                yield
                    assert(
                        !pre.isInstanceOf[Chunk.Indexed[Int]],
                        s"setup invalid: pre-compact already indexed (${pre.getClass.getName})"
                    )
                    assert(mid.isInstanceOf[Chunk.Indexed[Int]])
                    assert(post.isInstanceOf[Chunk.Indexed[Int]])
                    assert(pre == mid && mid == post)
                    assert(post == Chunk.from(51 to 60))
                end for
            }
        }

        "snapshot" - {
            "repeated calls return the same value and do not mutate" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    s1    <- STM.run(chunk.snapshot)
                    s2    <- STM.run(chunk.snapshot)
                    s3    <- STM.run(chunk.snapshot)
                    sz    <- STM.run(chunk.size)
                yield
                    assert(s1 == s2)
                    assert(s2 == s3)
                    assert(s3 == Chunk(1, 2, 3, 4, 5))
                    assert(sz == 5)
            }

            "preserves order under concurrent appends" in runNotJS {
                val n = 200
                for
                    chunk <- TChunk.init[Int]
                    _     <- Async.foreach(1 to n, 1)(i => STM.run(chunk.append(i)))
                    snap  <- STM.run(chunk.snapshot)
                yield assert(snap == Chunk.from(1 to n))
                end for
            }

            "sees writes made earlier in the same transaction" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    out <- STM.run {
                        for
                            _     <- chunk.append(4)
                            mid   <- chunk.snapshot
                            midSz <- chunk.size
                            _     <- chunk.append(5)
                            fin   <- chunk.snapshot
                        yield (mid, midSz, fin)
                    }
                yield
                    val (mid, midSize, finalSnap) = out
                    assert(mid == Chunk(1, 2, 3, 4))
                    assert(midSize == 4)
                    assert(finalSnap == Chunk(1, 2, 3, 4, 5))
            }
        }

        "transaction isolation" - {
            "take / drop / dropRight / slice / concat / filter / compact each roll back individually" in run {
                val initialA = Chunk(1, 2, 3, 4, 5)
                val ops: List[(String, TChunk[Int] => Unit < STM)] = List(
                    "take"      -> (_.take(2)),
                    "drop"      -> (_.drop(2)),
                    "dropRight" -> (_.dropRight(2)),
                    "slice"     -> (_.slice(1, 4)),
                    "concat"    -> (_.concat(Chunk(99, 100))),
                    "filter"    -> (_.filter(_ % 2 == 0)),
                    "compact"   -> (_.compact)
                )
                Kyo.foreach(ops) { entry =>
                    val (name, op) = entry
                    for
                        chunk <- TChunk.init(initialA)
                        r     <- Abort.run(STM.run(op(chunk).andThen(Abort.fail(s"$name aborted"))))
                        snap  <- STM.run(chunk.snapshot)
                    yield (name, r, snap)
                    end for
                }.map { results =>
                    results.foreach { r =>
                        val (name, res, snap) = r
                        assert(res.isFailure, s"$name: expected abort to surface")
                        assert(snap == initialA, s"$name: expected chunk unchanged after rollback, got $snap")
                    }
                    succeed
                }
            }

            "multiple TChunks in one transaction roll back together" in run {
                for
                    a <- TChunk.init(1)
                    b <- TChunk.init(10)
                    r <- Abort.run {
                        STM.run {
                            for
                                _ <- a.append(2)
                                _ <- b.append(20)
                                _ <- Abort.fail("nope")
                            yield ()
                        }
                    }
                    snapA <- STM.run(a.snapshot)
                    snapB <- STM.run(b.snapshot)
                yield
                    assert(r.isFailure)
                    assert(snapA == Chunk(1))
                    assert(snapB == Chunk(10))
            }
        }

        "concurrent appends" - {
            "each value appears exactly once with no duplicates or substitutions" in runNotJS {
                val n = 100
                for
                    chunk <- TChunk.init[Int]
                    _     <- Async.foreach(1 to n, n)(i => STM.run(chunk.append(i)))
                    snap  <- STM.run(chunk.snapshot)
                yield
                    val counts = snap.groupBy(identity).view.mapValues(_.length).toMap
                    assert(counts.size == n)
                    assert(counts.values.forall(_ == 1))
                    assert(counts.keySet == (1 to n).toSet)
                    assert(snap.length == n)
                end for
            }
        }

        "compositional" - {
            "append then take then snapshot in one transaction" in run {
                for
                    chunk <- TChunk.init(1, 2, 3)
                    snap <- STM.run {
                        for
                            _    <- chunk.append(4)
                            _    <- chunk.append(5)
                            _    <- chunk.take(3)
                            snap <- chunk.snapshot
                        yield snap
                    }
                yield assert(snap == Chunk(1, 2, 3))
            }
        }

        "extension methods" - {
            "compose inside one outer STM.run without nested STM.run" in run {
                for
                    chunk <- TChunk.init(1, 2, 3, 4, 5)
                    out <- STM.run {
                        for
                            sz    <- chunk.size
                            _     <- chunk.append(sz + 1)
                            h     <- chunk.head
                            l     <- chunk.last
                            empty <- chunk.isEmpty
                            snap  <- chunk.snapshot
                        yield (sz, h, l, empty, snap)
                    }
                yield
                    val (sz, h, l, empty, snap) = out
                    assert(sz == 5)
                    assert(h == 1)
                    assert(l == 6)
                    assert(!empty)
                    assert(snap == Chunk(1, 2, 3, 4, 5, 6))
            }
        }
    }
end TChunkTest
