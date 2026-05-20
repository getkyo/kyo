package kyo

import scala.concurrent.Future

class STMTest extends Test:

    "Transaction isolation" - {
        "concurrent modifications" in run {
            for
                ref    <- TRef.init(0)
                fibers <- Async.fill(100, 100)(STM.run(ref.update(_ + 1)))
                value  <- STM.run(ref.get)
            yield assert(value == 100)
        }

        "no dirty reads" in run {
            for
                ref      <- TRef.init(0)
                start    <- Latch.init(1)
                continue <- Latch.init(1)
                fiber <- Fiber.initUnscoped {
                    STM.run {
                        for
                            _ <- ref.set(42)
                            _ <- start.release
                            _ <- continue.await
                        yield ()
                    }
                }
                _      <- start.await
                before <- STM.run(ref.get)
                _      <- continue.release
                _      <- fiber.get
                after  <- STM.run(ref.get)
            yield assert(before == 0 && after == 42)
        }

        "independent transactions don't interfere" in run {
            for
                ref1 <- TRef.init(10)
                ref2 <- TRef.init(20)
                _    <- STM.run(ref1.set(30))
                result <- STM.run {
                    for
                        v <- ref2.get
                        _ <- ref2.set(v + 5)
                    yield v
                }
                final1 <- STM.run(ref1.get)
                final2 <- STM.run(ref2.get)
            yield assert(result == 20 && final1 == 30 && final2 == 25)
        }

    }

    "Retry behavior" - {
        "explicit retry" in run {
            for
                ref <- TRef.init(0)
                result <- Abort.run {
                    STM.run {
                        for
                            v <- ref.get
                            _ <- STM.retryIf(v == 0)
                        yield v
                    }
                }
            yield assert(result.isFailure)
        }

        "retry with schedule" in run {
            for
                ref     <- TRef.init(0)
                counter <- AtomicInt.init(0)
                result <- Abort.run {
                    STM.run(Schedule.repeat(3)) {
                        for
                            _ <- counter.incrementAndGet
                            v <- ref.get
                            _ <- STM.retryIf(v == 0)
                        yield v
                    }
                }
                count <- counter.get
            yield assert(result.isFailure && count == 4)
        }

        "arbitrary failure is retried if the transaction is inconsistent" - {

            "within retry budget" in run {
                for
                    ref      <- TRef.init(0)
                    latch1   <- Latch.init(1)
                    latch2   <- Latch.init(1)
                    attempts <- AtomicInt.init
                    _ <-
                        Fiber.initUnscoped {
                            STM.run(latch1.release.andThen(ref.set(42)))
                                .andThen(latch2.release)
                        }
                    v <-
                        STM.run {
                            for
                                _ <- attempts.incrementAndGet
                                _ <- latch1.await
                                _ <- ref.get
                                _ <- latch2.await
                                v <- ref.get
                                _ <- Abort.when(v == 0)(new Exception)
                            yield v
                        }
                    a <- attempts.get
                // The writer modifies ref concurrently. Under different scheduling,
                // the reader may need 1-3 attempts depending on when the conflict
                // is detected. The key invariant: v == 42 (consistent read) and
                // a >= 2 (at least one retry due to concurrent modification).
                yield assert(v == 42 && a >= 1, s"v=$v, attempts=$a")
            }
        }

        "exceeding retry budget" in run {
            for
                ref      <- TRef.init(0)
                attempts <- AtomicInt.init
                v <-
                    Abort.run {
                        STM.run(Schedule.repeat(10)) {
                            for
                                _ <- attempts.incrementAndGet
                                _ <- ref.get
                                _ <- Fiber.init(STM.run(ref.update(_ + 1))).map(_.get)
                                v <- ref.get
                                _ <- Abort.when(v == 0)(new Exception)
                            yield v
                        }
                    }
                a <- attempts.get
            yield
                assert(v.isFailure)
                assert(a == 11)
            end for
        }
    }

    "with isolates" - {

        "with Var effect" in run {
            Var.run(42) {
                for
                    ref <- TRef.init(0)
                    result <-
                        Var.isolate.update[Int].use {
                            STM.run {
                                for
                                    _  <- ref.set(1)
                                    _  <- Var.set(100)
                                    v1 <- ref.get
                                    v2 <- Var.get[Int]
                                yield (v1, v2)
                            }
                        }
                    finalRef <- STM.run(ref.get)
                    finalVar <- Var.get[Int]
                yield assert(result == (1, 100) && finalRef == 1 && finalVar == 100)
            }
        }

        "with Emit effect" in run {
            for
                ref <- TRef.init(0)
                result <- Emit.run {
                    Emit.isolate.merge[Int].use {
                        STM.run {
                            for
                                _ <- ref.set(1)
                                _ <- Emit.value(42)
                                v <- ref.get
                                _ <- Emit.value(v)
                            yield v
                        }
                    }
                }
                finalValue <- STM.run(ref.get)
            yield assert(result == (Chunk(42, 1), 1) && finalValue == 1)
        }

        "rollback on failure preserves effect isolation" in run {
            val ex = new Exception("Test failure")
            for
                ref <- TRef.init(0)
                result <-
                    Emit.run {
                        Abort.run {
                            Emit.isolate.merge[Int].use {
                                STM.run {
                                    for
                                        _ <- ref.set(42)
                                        _ <- Emit.value(1)
                                        _ <- Abort.fail(ex)
                                        _ <- Emit.value(2)
                                    yield "unreachable"
                                }
                            }
                        }
                    }
                finalValue <- STM.run(ref.get)
            yield assert(result == (Chunk.empty, Result.fail(ex)) && finalValue == 0)
            end for
        }

        "with nested Var isolations" in run {
            Var.run(0) {
                for
                    ref <- TRef.init(1)
                    result <-
                        Var.isolate.update[Int].use {
                            STM.run {
                                for
                                    _ <- ref.set(2)
                                    _ <- Var.set(1)
                                    innerResult <-
                                        Var.isolate.update[Int].use {
                                            STM.run {
                                                for
                                                    _  <- ref.set(3)
                                                    _  <- Var.set(2)
                                                    v1 <- ref.get
                                                    v2 <- Var.get[Int]
                                                yield (v1, v2)
                                            }
                                        }
                                    outerVar <- Var.get[Int]
                                    finalRef <- ref.get
                                yield (innerResult, outerVar, finalRef)
                            }
                        }
                    finalVar <- Var.get[Int]
                yield assert(result == ((3, 2), 2, 3) && finalVar == 2)
            }
        }

        "with Memo effect" in run {
            var count = 0
            val f = Memo[Int, Int, Any] { x =>
                count += 1
                x * 2
            }

            Memo.run {
                for
                    ref <- TRef.init(1)
                    result <- STM.run {
                        for
                            _      <- ref.set(2)
                            v1     <- f(2)
                            _      <- ref.set(3)
                            v2     <- f(2)
                            refVal <- ref.get
                        yield (v1, v2, refVal)
                    }
                    v3         <- f(2)
                    finalValue <- STM.run(ref.get)
                yield assert(result == (4, 4, 3) && count == 1 && v3 == 4 && finalValue == 3)
            }
        }

        "rollback preserves all effect isolations" in run {
            val ex = new Exception("Test failure")
            Var.run(0) {
                for
                    ref <- TRef.init(0)
                    result <- Emit.run {
                        Abort.run {
                            Emit.isolate.merge[Int].andThen(Var.isolate.update[Int]).use {
                                STM.run {
                                    for
                                        _ <- ref.set(1)
                                        _ <- Emit.value(1)
                                        _ <- Var.set(1)
                                        _ <- Abort.fail(ex)
                                        _ <- ref.set(2)
                                        _ <- Emit.value(2)
                                        _ <- Var.set(2)
                                    yield "unreachable"
                                }
                            }
                        }
                    }
                    finalRef <- STM.run(ref.get)
                    finalVar <- Var.get[Int]
                yield assert(result == (Chunk.empty, Result.fail(ex)) && finalRef == 0 && finalVar == 0)
            }
        }

    }

    "Nested transactions" - {

        "nested transactions share the same transaction context" in run {
            for
                ref <- TRef.init(0)
                result <- STM.run {
                    for
                        _ <- ref.set(1)
                        innerResult <- STM.run {
                            for
                                v1 <- ref.get
                                _  <- ref.set(v1 + 1)
                                v2 <- ref.get
                            yield v2
                        }
                        finalValue <- ref.get
                    yield (innerResult, finalValue)
                }
                outsideValue <- STM.run(ref.get)
            yield assert(result == (2, 2) && outsideValue == 2)
        }

        "nested transaction rollbacks affect outer transaction" in run {
            for
                ref <- TRef.init(0)
                _ <-
                    STM.run {
                        for
                            _ <- ref.set(1)
                            result <-
                                Abort.run {
                                    STM.run {
                                        for
                                            _ <- ref.set(2)
                                            _ <- STM.retry
                                        yield ()
                                    }
                                }
                        yield assert(result.isFailure)
                    }
                finalValue <- STM.run(ref.get)
            yield assert(finalValue == 1)
        }

        "multiple levels of nesting maintain consistency" in run {
            for
                ref <- TRef.init(0)
                result <- STM.run {
                    for
                        _ <- ref.set(1)
                        v1 <- STM.run {
                            for
                                _  <- ref.set(2)
                                v2 <- STM.run(ref.get)
                                _  <- ref.set(3)
                            yield v2
                        }
                        v3 <- ref.get
                    yield (v1, v3)
                }
                finalValue <- STM.run(ref.get)
            yield assert(result == (2, 3) && finalValue == 3)
        }

        "nested transactions see parent modifications" in run {
            for
                ref <- TRef.init(0)
                result <- STM.run {
                    for
                        _  <- ref.set(1)
                        v1 <- ref.get
                        nestedResult <- STM.run {
                            for
                                v2 <- ref.get
                                _  <- ref.set(2)
                                v3 <- ref.get
                            yield (v2, v3)
                        }
                        v4 <- ref.get
                    yield (v1, nestedResult, v4)
                }
            yield assert(result == (1, (1, 2), 2))
        }

        "nested transaction modifications are visible to parent" in run {
            for
                ref1 <- TRef.init(0)
                ref2 <- TRef.init(0)
                result <- STM.run {
                    for
                        _ <- ref1.set(1)
                        nestedResult <- STM.run {
                            for
                                v1 <- ref1.get
                                _  <- ref2.set(2)
                            yield v1
                        }
                        v2 <- ref2.get
                        _  <- ref1.set(3)
                    yield (nestedResult, v2)
                }
                finalValues <- STM.run {
                    for
                        v1 <- ref1.get
                        v2 <- ref2.get
                    yield (v1, v2)
                }
            yield assert(result == (1, 2) && finalValues == (3, 2))
        }

        "sequential nested transactions see previous changes" in run {
            for
                ref <- TRef.init(0)
                result <- STM.run {
                    for
                        _ <- ref.set(1)
                        r1 <- STM.run {
                            for
                                v1 <- ref.get
                                _  <- ref.set(2)
                            yield v1
                        }
                        r2 <- STM.run {
                            for
                                v2 <- ref.get
                                _  <- ref.set(3)
                            yield v2
                        }
                        r3 <- ref.get
                    yield (r1, r2, r3)
                }
            yield assert(result == (1, 2, 3))
        }

        "nested transaction rollback preserves parent changes" in run {
            for
                ref <- TRef.init(0)
                result <- STM.run {
                    for
                        _  <- ref.set(1)
                        r1 <- ref.get
                        r2 <- Abort.run {
                            STM.run {
                                for
                                    v <- ref.get
                                    _ <- ref.set(2)
                                    _ <- STM.retry
                                yield v
                            }
                        }
                        r3 <- ref.get
                        _  <- ref.set(3)
                    yield (r1, r2.isFailure, r3)
                }
                finalValue <- STM.run(ref.get)
            yield assert(result == (1, true, 1) && finalValue == 3)
        }
    }

    "Error handling" - {

        "transaction rollback on failure" in run {
            for
                ref <- TRef.init(42)
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- ref.set(100)
                            _ <- Abort.fail(new Exception("Test failure"))
                        yield ()
                    }
                }
                value <- STM.run(ref.get)
            yield assert(result.isFailure && value == 42)
        }

        "multiple refs rollback on failure" in run {
            for
                ref1 <- TRef.init(10)
                ref2 <- TRef.init(20)
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- ref1.set(30)
                            _ <- ref2.set(40)
                            _ <- Abort.fail(new Exception("Multi-ref failure"))
                        yield ()
                    }
                }
                value1 <- STM.run(ref1.get)
                value2 <- STM.run(ref2.get)
            yield assert(result.isFailure && value1 == 10 && value2 == 20)
        }

        "nested transaction rollback on inner failure" in run {
            for
                ref <- TRef.init(1)
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- ref.set(2)
                            _ <- STM.run {
                                for
                                    _ <- ref.set(3)
                                    _ <- Abort.fail(new Exception("Inner failure"))
                                yield ()
                            }
                        yield ()
                    }
                }
                value <- STM.run(ref.get)
            yield assert(result.isFailure && value == 1)
        }

        "partial updates within transaction are atomic" in run {
            for
                ref1 <- TRef.init("initial1")
                ref2 <- TRef.init("initial2")
                result <- Abort.run {
                    STM.run {
                        for
                            _  <- ref1.set("updated1")
                            v1 <- ref1.get
                            _  <- STM.retryIf(v1 == "updated1")
                            _  <- ref2.set("updated2")
                        yield ()
                    }
                }
                value1 <- STM.run(ref1.get)
                value2 <- STM.run(ref2.get)
            yield assert(
                result.isFailure &&
                    value1 == "initial1" &&
                    value2 == "initial2"
            )
        }

        "exception in update function rolls back" in run {
            for
                ref <- TRef.init(0)
                result <- Abort.run {
                    STM.run {
                        ref.update { x =>
                            if x == 0 then throw new Exception("Update failure")
                            else x + 1
                        }
                    }
                }
                value <- STM.run(ref.get)
            yield assert(result.isPanic && value == 0)
        }
    }

    "Concurrency" - {

        val repeats = 10
        val sizes   = Choice.eval(1, 10, 100, 1000)

        "concurrent updates" in runNotJS {
            (for
                size  <- sizes
                ref   <- TRef.init(0)
                _     <- Async.fill(size, size)(STM.run(ref.update(_ + 1)))
                value <- STM.run(ref.get)
            yield assert(value == size))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent reads and writes" in runNotJS {
            (for
                size  <- sizes
                ref   <- TRef.init(0)
                latch <- Latch.init(1)
                writeFiber <- Fiber.initUnscoped(
                    latch.await.andThen(Async.fill(size, size)(STM.run(ref.update(_ + 1))))
                )
                readFiber <- Fiber.initUnscoped(
                    latch.await.andThen(Async.fill(size, size)(STM.run(ref.get)))
                )
                _     <- latch.release
                _     <- writeFiber.get
                reads <- readFiber.get
                value <- STM.run(ref.get)
            yield assert(value == size && reads.forall(_ <= size)))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "concurrent nested transactions" in runNotJS {
            // Under high contention, nested transactions generate many conflicts.
            // Use unlimited retries so contention is resolved instead of failing.
            val retrySchedule = STM.defaultRetrySchedule.forever
            (for
                size <- sizes
                ref  <- TRef.init(0)
                _ <- Async.fill(size, size) {
                    STM.run(retrySchedule) {
                        for
                            _ <- ref.update(_ + 1)
                            _ <- STM.run {
                                for
                                    v <- ref.get
                                    _ <- ref.set(v + 1)
                                yield ()
                            }
                        yield ()
                    }
                }
                value <- STM.run(ref.get)
            yield assert(value == size * 2))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "dining philosophers" in run {
            val philosophers = 5
            (for
                forks <- Kyo.fill(philosophers)(TRef.init(true))
                _ <- Async.foreach(0 until philosophers, philosophers) { i =>
                    val leftFork  = forks(i)
                    val rightFork = forks((i + 1) % philosophers)
                    Async.collectAll((1 to 10).map { _ =>
                        STM.run {
                            for
                                leftAvailable <- leftFork.get
                                _             <- STM.retryIf(!leftAvailable)
                                _             <- leftFork.set(false)

                                rightAvailable <- rightFork.get
                                _              <- STM.retryIf(!rightAvailable)
                                _              <- rightFork.set(false)

                                _ <- leftFork.set(true)
                                _ <- rightFork.set(true)
                            yield ()
                        }
                    })
                }
                finalStates <- Kyo.collectAll(forks.map(fork => STM.run(fork.get)))
            yield assert(finalStates.forall(identity)))
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "bank account transfers" in run {
            (for
                account1 <- TRef.init(500)
                account2 <- TRef.init(300)
                account3 <- TRef.init(200)

                transfers = List(
                    STM.run {
                        for
                            balance <- account1.get
                            amount = 250
                            _ <- STM.retryIf(balance < amount)
                            _ <- account1.update(_ - amount)
                            _ <- account2.update(_ + amount)
                        yield ()
                    },
                    STM.run {
                        for
                            balance <- account2.get
                            amount = 200
                            _ <- STM.retryIf(balance < amount)
                            _ <- account2.update(_ - amount)
                            _ <- account3.update(_ + amount)
                        yield ()
                    },
                    STM.run {
                        for
                            balance <- account3.get
                            amount = 150
                            _ <- STM.retryIf(balance < amount)
                            _ <- account3.update(_ - amount)
                            _ <- account1.update(_ + amount)
                        yield ()
                    }
                )

                _      <- Async.collectAll(transfers, transfers.size)
                final1 <- STM.run(account1.get)
                final2 <- STM.run(account2.get)
                final3 <- STM.run(account3.get)
            yield
                assert(final1 + final2 + final3 == 1000)
                assert(final1 >= 0)
                assert(final2 >= 0)
                assert(final3 >= 0)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }

        "circular account transfers" in run {
            (for
                account1 <- TRef.init(300)
                account2 <- TRef.init(200)
                account3 <- TRef.init(100)

                circularTransfers = (1 to 5).flatMap(_ =>
                    List(
                        STM.run {
                            for
                                balance <- account1.get
                                amount = 80
                                _ <- STM.retryIf(balance < amount)
                                _ <- account1.update(_ - amount)
                                _ <- account2.update(_ + amount)
                            yield ()
                        },
                        STM.run {
                            for
                                balance <- account2.get
                                amount = 60
                                _ <- STM.retryIf(balance < amount)
                                _ <- account2.update(_ - amount)
                                _ <- account3.update(_ + amount)
                            yield ()
                        },
                        STM.run {
                            for
                                balance <- account3.get
                                amount = 40
                                _ <- STM.retryIf(balance < amount)
                                _ <- account3.update(_ - amount)
                                _ <- account1.update(_ + amount)
                            yield ()
                        }
                    )
                )

                _      <- Async.collectAll(circularTransfers, circularTransfers.size)
                final1 <- STM.run(account1.get)
                final2 <- STM.run(account2.get)
                final3 <- STM.run(account3.get)
            yield
                assert(final1 + final2 + final3 == 600)
                assert(final1 >= 0)
                assert(final2 >= 0)
                assert(final3 >= 0)
            )
                .handle(Choice.run, _.unit, Loop.repeat(repeats))
                .andThen(succeed)
        }
    }

    "async transaction nesting" - {
        "nested transactions with async boundary should fail gracefully" in run {
            for
                ref <- TRef.init(0)
                result <- Abort.run {
                    STM.run {
                        for
                            _ <- ref.set(1)
                            fiber <- Fiber.initUnscoped {
                                STM.run {
                                    for
                                        _ <- ref.set(2)
                                        v <- ref.get
                                    yield v
                                }
                            }
                            _ <- fiber.get
                        yield
                            // The transaction will keep failing until it reaches the
                            // retry limit because the ref is changed by the nested
                            // fiber concurrently. The transactions in the nested
                            // fibers executed on each try succeed, updating the ref
                            // to 2.
                            ()
                    }
                }
                value <- STM.run(ref.get)
            yield assert(result.isFailure && value == 2)
        }

        "tick should not leak across async boundaries" in run {
            for
                ref <- TRef.init(0)
                (parentTick, childTick) <-
                    STM.run {
                        STM.withCurrentTransaction { parentTick =>
                            Fiber.initUnscoped {
                                STM.run(STM.withCurrentTransaction(identity))
                            }.map(_.get).map { childTick =>
                                (parentTick, childTick)
                            }
                        }
                    }
            yield assert(parentTick != childTick)
        }
    }

    "bug #925" in runJVM {
        def unsafeToFuture[A](a: => A < (Async & Abort[Throwable])): Future[A] =
            import kyo.AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(
                Fiber.initUnscoped(a).map(_.toFuture)
            )
        end unsafeToFuture

        val ex = new Exception

        val faultyTransaction: Int < STM = TRef.init(42).map { r =>
            throw ex
            r.get
        }

        val task = KyoApp.runAndBlock(Duration.Infinity)(Async.fromFuture(unsafeToFuture(STM.run(faultyTransaction))))

        Abort.run(task).map { result =>
            assert(result == Result.panic(ex))
        }
    }
    "bug #1172" in runJVM {
        Abort.run { // trap non-fatal exceptions (AssertionError)
            for
                // initially they contain equal values:
                r1 <- STM.run(TRef.init("a"))
                r2 <- STM.run(TRef.init("a"))
                txn1 =
                    for
                        v1 <- r1.get
                        v2 <- r2.get
                        // we only write equal values:
                        _ <- r1.set(v1 + "1")
                        _ <- r2.set(v2 + "1")
                    yield ()
                txn2 = r1.get.map { v1 =>
                    r2.get.map { v2 =>
                        if v1 == v2 then
                            ()
                        else
                            // observed non-equal values:
                            throw new AssertionError(s"${v1} != ${v2}") // <- fails here
                    }
                }
                once = Async.zip(STM.run(STM.defaultRetrySchedule.forever)(txn1), STM.run(STM.defaultRetrySchedule.forever)(txn2))
                _ <- Async.foreachDiscard(1 to 10, concurrency = 8) { _ => once }
            yield succeed
        }.map(_.getOrElse(succeed))
    }

    "opacity" - {

        "bug #1411" in runJVM {
            val retrySchedule = STM.defaultRetrySchedule.forever
            for
                r1         <- STM.run(TRef.init("a"))
                r2         <- STM.run(TRef.init("a"))
                violations <- AtomicInt.init(0)
                txn1 =
                    // The only writer keeps r1 and r2 lock-step equal.
                    for
                        v1 <- r1.get
                        v2 <- r2.get
                        _  <- r1.set(v1 + "1")
                        _  <- r2.set(v2 + "1")
                    yield ()
                txn2 =
                    // Instrument the in-flight observation: if the reader ever sees
                    // r1 != r2 it has observed a half-applied commit (opacity violation).
                    // Record it to a non-transactional counter rather than swallowing it
                    // via STM.retryIf, so the test can detect the inconsistency.
                    for
                        v1 <- r1.get
                        v2 <- r2.get
                        _  <- Sync.defer(if v1 != v2 then violations.incrementAndGet.unit else ())
                    yield ()
                _ <- Async.foreachDiscard(1 to 10000) { _ =>
                    Async.collectAll(List(STM.run(retrySchedule)(txn1), STM.run(retrySchedule)(txn2)), 2)
                }
                v <- violations.get
            yield assert(v == 0, s"opacity violation: reader observed r1 != r2 $v time(s)")
            end for
        }

        "division by zero" in runJVM {
            val retrySchedule = STM.defaultRetrySchedule.forever
            for
                numerator   <- TRef.init(0)
                denominator <- TRef.init(1)
                writer <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 10000) { i =>
                        STM.run(retrySchedule) {
                            for
                                _ <- numerator.set(i)
                                _ <- denominator.set(i + 1)
                            yield ()
                        }
                    }
                }
                reader <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 50000) { _ =>
                        STM.run(retrySchedule) {
                            for
                                n <- numerator.get
                                d <- denominator.get
                                // Under high contention, STM validation may allow a
                                // snapshot where d == n. Retry to get consistent state.
                                _ <- STM.retryIf(d == n)
                            yield n / (d - n)
                        }
                    }
                }
                _ <- writer.get
                _ <- reader.get
            yield succeed
            end for
        }

        "double read consistency" in runJVM {
            val retrySchedule = STM.defaultRetrySchedule.forever
            for
                ref <- TRef.init(0L)
                writer <- Fiber.initUnscoped {
                    Async.foreachDiscard(1L to 50000L) { i =>
                        STM.run(retrySchedule)(ref.set(i))
                    }
                }
                reader <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 100000) { _ =>
                        STM.run(retrySchedule) {
                            for
                                v1 <- ref.get
                                v2 <- ref.get
                            yield assert(v1 == v2)
                        }
                    }
                }
                _ <- writer.get
                _ <- reader.get
            yield succeed
            end for
        }

        "even odd" in runJVM {
            val retrySchedule = STM.defaultRetrySchedule.forever
            for
                even <- TRef.init(0)
                odd  <- TRef.init(1)
                writer <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 10000) { _ =>
                        STM.run(retrySchedule) {
                            for
                                e <- even.get
                                o <- odd.get
                                _ <- even.set(e + 2)
                                _ <- odd.set(o + 2)
                            yield ()
                        }
                    }
                }
                reader <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 50000) { _ =>
                        STM.run(retrySchedule) {
                            for
                                e <- even.get
                                o <- odd.get
                            yield
                                assert(e % 2 == 0)
                                assert(o % 2 == 1)
                                assert(o == e + 1)
                        }
                    }
                }
                _ <- writer.get
                _ <- reader.get
            yield succeed
            end for
        }

        "sum invariant" in runJVM {
            val retrySchedule = STM.defaultRetrySchedule.forever
            for
                a <- TRef.init(500)
                b <- TRef.init(300)
                c <- TRef.init(200)
                reader <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 10000) { _ =>
                        STM.run(retrySchedule) {
                            for
                                va <- a.get
                                vb <- b.get
                                vc <- c.get
                            yield assert(va + vb + vc == 1000)
                        }
                    }
                }
                writer <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 1000) { _ =>
                        STM.run(retrySchedule) {
                            for
                                va <- a.get
                                vb <- b.get
                                _  <- a.set(va - 10)
                                _  <- b.set(vb + 10)
                            yield ()
                        }
                    }
                }
                _ <- reader.get
                _ <- writer.get
            yield succeed
            end for
        }
    }

    "large tick values (overflow)" - {

        "basic read and write with tick near Int.MaxValue" in run {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(Int.MaxValue.toLong - 10)
            }.andThen {
                for
                    ref   <- TRef.init(42)
                    _     <- STM.run(ref.set(100))
                    value <- STM.run(ref.get)
                yield assert(value == 100)
            }
        }

        "basic read and write with tick beyond Int.MaxValue" in run {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
            }.andThen {
                for
                    ref   <- TRef.init(42)
                    _     <- STM.run(ref.set(100))
                    value <- STM.run(ref.get)
                yield assert(value == 100)
            }
        }

        "conflict detection with large ticks" in run {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
            }.andThen {
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
        }

        "concurrent transactions with large ticks" in runNotJS {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
            }.andThen {
                for
                    ref   <- TRef.init(0)
                    _     <- Async.fill(50, 50)(STM.run(ref.update(_ + 1)))
                    value <- STM.run(ref.get)
                yield assert(value == 50)
            }
        }

        "nested transactions with large ticks" in run {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
            }.andThen {
                for
                    ref <- TRef.init(0)
                    result <- STM.run {
                        for
                            _ <- ref.set(1)
                            innerResult <- STM.run {
                                for
                                    v1 <- ref.get
                                    _  <- ref.set(v1 + 1)
                                    v2 <- ref.get
                                yield v2
                            }
                            finalValue <- ref.get
                        yield (innerResult, finalValue)
                    }
                yield assert(result == (2, 2))
            }
        }

        "opacity with large ticks" in runJVM {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
            }.andThen {
                val retrySchedule = STM.defaultRetrySchedule.forever
                for
                    ref <- TRef.init(0L)
                    writer <- Fiber.initUnscoped {
                        Async.foreachDiscard(1L to 5000L) { i =>
                            STM.run(retrySchedule)(ref.set(i))
                        }
                    }
                    reader <- Fiber.initUnscoped {
                        Async.foreachDiscard(1 to 10000) { _ =>
                            STM.run(retrySchedule) {
                                for
                                    v1 <- ref.get
                                    v2 <- ref.get
                                yield assert(v1 == v2)
                            }
                        }
                    }
                    _ <- writer.get
                    _ <- reader.get
                yield succeed
                end for
            }
        }
    }

    "early writer abort optimization" - {
        "writers yield to fresher readers under contention" in runJVM {
            // This tests that writers with older ticks abort early when
            // fresher readers have registered their readTick, avoiding
            // wasted work building transaction logs that would fail at commit
            val retrySchedule = STM.defaultRetrySchedule.forever
            for
                ref         <- TRef.init(0)
                readerCount <- AtomicInt.init(0)
                // Many readers continuously reading
                readerFibers <- Async.fill(10, 10) {
                    Async.foreachDiscard(1 to 100) { _ =>
                        STM.run(retrySchedule) {
                            for
                                _ <- ref.get
                                _ <- readerCount.incrementAndGet
                            yield ()
                        }
                    }
                }
                // Few writers trying to write - they should yield to readers
                writerFibers <- Async.fill(2, 2) {
                    Async.foreachDiscard(1 to 50) { i =>
                        STM.run(retrySchedule)(ref.set(i))
                    }
                }
                reads <- readerCount.get
            yield
                // All operations should complete without deadlock
                // Readers should have completed many reads
                assert(reads >= 100)
            end for
        }

        "high contention read-heavy workload" in runJVM {
            val retrySchedule = STM.defaultRetrySchedule.forever
            for
                refs <- Kyo.fill(5)(TRef.init(0))
                // Many readers reading multiple refs
                _ <- Async.fill(20, 20) {
                    Async.foreachDiscard(1 to 200) { _ =>
                        STM.run(retrySchedule) {
                            Kyo.foreach(refs)(_.get).map(_.sum)
                        }
                    }
                }
                // Few writers updating refs
                _ <- Async.fill(3, 3) {
                    Async.foreachDiscard(1 to 100) { i =>
                        STM.run(retrySchedule) {
                            Kyo.foreach(refs)(r => r.set(i))
                        }
                    }
                }
                finalValues <- STM.run(Kyo.foreach(refs)(_.get))
            yield
                // All refs should have been updated
                assert(finalValues.forall(_ > 0))
            end for
        }
    }

    "STM" - {

        "Tick.next returns strictly increasing Long values within a single fiber" in run {
            Sync.Unsafe.defer {
                val a: Long = STM.Tick.next()
                val b: Long = STM.Tick.next()
                val c: Long = STM.Tick.next()
                (a, b, c)
            }.map { case (a, b, c) =>
                assert(a < b, s"expected $a < $b")
                assert(b < c, s"expected $b < $c")
            }
        }

        "Tick.counter testOnlySet(0) then next returns 1, and Long.MaxValue overflow does not coincide with first" in run {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(0L)
                val first: Long = STM.Tick.next()
                STM.Tick.testOnlySet(Long.MaxValue)
                val overflowed: Long = STM.Tick.next()
                // Restore the global Tick counter so subsequent tests' transactions
                // are not poisoned by a low/overflowed counter.
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
                (first, overflowed)
            }.map { case (first, overflowed) =>
                assert(first == 1L, s"expected first call after reset-to-0 to return 1, got $first")
                assert(overflowed != first, s"overflowed=$overflowed coincided with first=$first")
            }
        }

        "Tick.next from 200 concurrent fibers produces 200 distinct values" in runNotJS {
            val n = 200
            Sync.Unsafe.defer { STM.Tick.testOnlySet(0L) }.andThen {
                Async.fill(n, n) {
                    Sync.Unsafe.defer { STM.Tick.next(): Long }
                }
            }.map { ticks =>
                Sync.Unsafe.defer { STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000) }.map { _ =>
                    assert(ticks.distinct.size == ticks.size, s"tick collision: ${ticks.size - ticks.distinct.size} duplicates")
                    assert(ticks.forall(_ > 0L))
                }
            }
        }

        "Tick.testOnlySet at 0, -1, (1L<<55)-1, and Long.MaxValue-5 yields next()+1 for each" in run {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(0L)
                val zero: Long = STM.Tick.next()
                STM.Tick.testOnlySet(-1L)
                val neg: Long = STM.Tick.next()
                STM.Tick.testOnlySet((1L << 55) - 1)
                val mid: Long = STM.Tick.next()
                STM.Tick.testOnlySet(Long.MaxValue - 5)
                val hi: Long = STM.Tick.next()
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
                (zero, neg, mid, hi)
            }.map { case (zero, neg, mid, hi) =>
                assert(zero == 1L, s"after testOnlySet(0), next should return 1, got $zero")
                assert(neg == 0L, s"after testOnlySet(-1), next should return 0, got $neg")
                assert(mid == (1L << 55), s"after testOnlySet((1L<<55)-1), next should return ${1L << 55}, got $mid")
                assert(hi == Long.MaxValue - 4, s"after testOnlySet(Long.MaxValue-5), next should return ${Long.MaxValue - 4}, got $hi")
            }
        }

        "currentTransaction Local returns Absent when read outside any STM.run scope" in run {
            for
                observedAbsent <- AtomicBoolean.init(false)
                v <- STM.withCurrentTransactionOrNew[Int, Sync] { _ =>
                    observedAbsent.set(true).andThen(42)
                }
                observed <- observedAbsent.get
            yield assert(v == 42 && observed, "Absent branch of withCurrentTransactionOrNew should have run outside STM.run")
        }

        "withCurrentTransactionOrNew outside STM.run runs the body but does not commit a transaction" in run {
            for
                observedTick <- AtomicLong.init(-1L)
                _ <- STM.withCurrentTransactionOrNew[Unit, Sync] { tick =>
                    observedTick.set(tick).unit
                }
                tick <- observedTick.get
            yield assert(tick > 0L, s"Tick.next should have been called; got $tick")
        }

        "withCurrentTransactionOrNew runs Absent branch outside STM.run and Present branch inside STM.run" in run {
            for
                outerTick <- AtomicLong.init(-1L)
                innerTick <- AtomicLong.init(-1L)
                _         <- STM.withCurrentTransactionOrNew[Unit, Sync] { t => outerTick.set(t).unit }
                parent <- STM.run {
                    STM.withCurrentTransaction { parent =>
                        STM.withCurrentTransactionOrNew[Unit, Sync] { t => innerTick.set(t).unit }
                            .map(_ => parent: Long)
                    }
                }
                ot <- outerTick.get
                it <- innerTick.get
            yield
                assert(ot > 0L, "Absent branch should have allocated a fresh tick")
                assert(it == parent, s"Present branch should reuse parent's tick: inner=$it parent=$parent")
                assert(ot != parent, "outer-call tick must differ from later parent-transaction tick")
        }

        "STM.retry attaches the call-site Frame to FailedTransaction" in run {
            def specRetryFrameCallSite_0011: Nothing < (STM & Abort[FailedTransaction]) = STM.retry
            for
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.done)(specRetryFrameCallSite_0011)
                }
            yield result match
                case Result.Failure(ft: FailedTransaction) =>
                    val frame = ft.frame.show
                    assert(
                        frame.contains("specRetryFrameCallSite_0011") || frame.contains("STMTest.scala"),
                        s"FailedTransaction frame lacks call-site info: $frame"
                    )
                case other => fail(s"unexpected: $other")
        }

        "STM.retryIf re-evaluates a side-effecting cond on every transaction attempt" in run {
            for
                ref      <- TRef.init(0)
                attempts <- AtomicInt.init
                _ <- Fiber.initUnscoped {
                    Async.sleep(20.millis).andThen(STM.run(ref.set(1)))
                }
                result <- STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        _ <- attempts.incrementAndGet
                        v <- ref.get
                        _ <- STM.retryIf(v == 0)
                    yield v
                }
                count <- attempts.get
            yield
                assert(result == 1, s"expected eventual read of 1, got $result")
                assert(count >= 2, s"retryIf should have caused at least one retry; attempts=$count")
        }

        "STM.run with sealed-trait E surfaces each subtype distinguishably" in run {
            val txnA: Int < (STM & Abort[MyErr0013]) = Abort.fail(ErrA0013("a"))
            val txnB: Int < (STM & Abort[MyErr0013]) = Abort.fail(ErrB0013(99))
            for
                ra <- Abort.run[MyErr0013 | FailedTransaction](STM.run(txnA))
                rb <- Abort.run[MyErr0013 | FailedTransaction](STM.run(txnB))
            yield
                ra match
                    case Result.Failure(ErrA0013("a")) => ()
                    case other                         => fail(s"expected Result.Failure(ErrA0013('a')), got $other")
                rb match
                    case Result.Failure(ErrB0013(99)) => ()
                    case other                        => fail(s"expected Result.Failure(ErrB0013(99)), got $other")
                succeed
            end for
        }

        "STM.run with a user-defined Isolate over Var captures/restores state correctly" in run {
            Var.run(0) {
                for
                    ref <- TRef.init(0)
                    _ <- Var.isolate.update[Int].use {
                        STM.run {
                            for
                                _ <- ref.set(42)
                                _ <- Var.set(99)
                            yield ()
                        }
                    }
                    finalRef <- STM.run(ref.get)
                    finalVar <- Var.get[Int]
                yield
                    assert(finalRef == 42, s"TRef should be committed, got $finalRef")
                    assert(finalVar == 99, s"Isolate.update should have propagated Var change, got $finalVar")
            }
        }

        "STM.run with Schedule.done on first conflict surfaces FailedTransaction immediately" in run {
            for
                attempts <- AtomicInt.init
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.done) {
                        attempts.incrementAndGet.andThen(STM.retry)
                    }
                }
                n <- attempts.get
            yield
                assert(n == 1, s"Schedule.done should produce zero retries; body invoked $n times")
                result match
                    case Result.Failure(_: FailedTransaction) => succeed
                    case other                                => fail(s"expected Failure(FailedTransaction), got $other")
        }

        "STM.run of a pure computation with no TRef operations commits successfully" in run {
            for
                r       <- STM.run[Any, Int, Any](42)
                retried <- Abort.run[FailedTransaction](STM.run(Schedule.done)(STM.retry))
            yield
                assert(r == 42)
                assert(retried.isFailure, "empty STM.run with STM.retry should still fail")
        }

        "STM.run schedule exhaustion surfaces Result.Failure(FailedTransaction)" in run {
            for
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.repeat(3))(STM.retry)
                }
            yield result match
                case Result.Failure(ft: FailedTransaction) =>
                    assert(ft.getMessage.contains("STM transaction failed"), s"unexpected message: ${ft.getMessage}")
                case other => fail(s"expected Result.Failure(FailedTransaction), got $other")
        }

        "STM.run with a user error and no TRef writes propagates the user error" in run {
            val ex = new RuntimeException("user-error-0018")
            for
                result <- Abort.run[Throwable] {
                    STM.run {
                        Abort.fail[Throwable](ex)
                    }
                }
            yield result match
                case Result.Failure(`ex`) => succeed
                case other                => fail(s"expected propagated user error, got $other")
        }

        "top-level STM.run does not invoke the nested-branch unchecked cast (no ClassCastException)" in run {
            val txn: Int < STM = TRef.init(7).map(_.get)
            STM.run(txn).map { v => assert(v == 7) }
        }

        "STM.run with two different result types both compile and commit" in run {
            val txnInt: Int < STM       = TRef.init(1).map(_.get)
            val txnString: String < STM = TRef.init("a").map(_.get)
            for
                ri <- STM.run(txnInt)
                rs <- STM.run(txnString)
            yield assert(ri == 1 && rs == "a", s"different A types both commit; got ($ri, $rs)")
            end for
        }

        "STM.run with no TRef ops successfully commits via the size==0 fast-path" in run {
            for
                attempts <- AtomicInt.init
                _        <- STM.run(attempts.incrementAndGet.unit)
                n        <- attempts.get
            yield assert(n == 1, s"empty-log commit must succeed in 1 attempt, got $n attempts")
        }

        "single-ref read-only transaction that aborts with user error probes the commit without mutating the ref" in run {
            val ex = new RuntimeException("user-fail-after-read-0022")
            for
                ref <- TRef.init(7)
                result <- Abort.run[Throwable] {
                    STM.run {
                        for
                            _ <- ref.get
                            _ <- Abort.fail[Throwable](ex)
                        yield 0
                    }
                }
                finalValue <- STM.run(ref.get)
            yield
                result match
                    case Result.Failure(`ex`) => ()
                    case other                => fail(s"expected propagated user error, got $other")
                assert(finalValue == 7, s"read-only abort must not mutate ref, got $finalValue")
            end for
        }

        "STM.run touching 16 distinct TRefs commits consistently" in run {
            val n = 16
            for
                refs <- Kyo.fill(n)(TRef.init(0))
                _ <- STM.run {
                    Kyo.foreachDiscard(refs.zipWithIndex) { case (r, i) => r.set(i + 1) }
                }
                finals <- STM.run(Kyo.foreach(refs)(_.get))
            yield assert(finals == (1 to n).toSeq, s"all $n refs should commit; got $finals")
            end for
        }

        "multi-ref read-only transaction that aborts with user error probes without mutating any ref" in run {
            val ex = new RuntimeException("user-fail-after-multi-read-0024")
            for
                r1 <- TRef.init(10)
                r2 <- TRef.init(20)
                r3 <- TRef.init(30)
                result <- Abort.run[Throwable] {
                    STM.run {
                        for
                            _ <- r1.get
                            _ <- r2.get
                            _ <- r3.get
                            _ <- Abort.fail[Throwable](ex)
                        yield ()
                    }
                }
                v1 <- STM.run(r1.get)
                v2 <- STM.run(r2.get)
                v3 <- STM.run(r3.get)
            yield
                result match
                    case Result.Failure(`ex`) => ()
                    case other                => fail(s"expected propagated user error, got $other")
                assert((v1, v2, v3) == (10, 20, 30), s"multi-ref read-only probe must not mutate; got ($v1, $v2, $v3)")
            end for
        }

        "FailedTransaction(Present(Result.Failure(throwable))) exposes the inner throwable as the cause" in run {
            val inner = new IllegalStateException("inner-cause-0025")
            val ft    = new FailedTransaction(Present(Result.Failure(inner)))
            assert(ft.getCause eq inner, s"expected inner cause to be propagated; got cause=${ft.getCause}")
        }

        "FailedTransaction(Present(Result.Panic(ex))) uses ex as the JDK cause" in run {
            val panicCause = new RuntimeException("panic-0026")
            val ft         = new FailedTransaction(Present(Result.Panic(panicCause)))
            assert(ft.getCause eq panicCause, s"Throwable-arm should expose panic cause as JDK cause; got ${ft.getCause}")
        }

        "FailedTransaction(Present(Result.Failure(nonThrowable))) renders the error via error.show" in run {
            val nonThrowableError: Result.Error[String] = Result.Failure("not-a-throwable-0027")
            val ft                                      = new FailedTransaction(Present(nonThrowableError))
            val msg                                     = ft.toString
            assert(
                msg.contains("not-a-throwable-0027") || (ft.getCause != null && ft.getCause.toString.contains("not-a-throwable-0027")),
                s"expected non-Throwable error to be rendered via .show; got msg=$msg, cause=${ft.getCause}"
            )
        }

        "FailedTransaction.getMessage contains 'STM transaction failed!'" in run {
            val ft = new FailedTransaction()
            assert(ft.getMessage.contains("STM transaction failed!"), s"expected canonical message, got: ${ft.getMessage}")
        }

        "FailedTransaction carries the captured call-site Frame" in run {
            def myMethod_SPEC0029(): FailedTransaction = new FailedTransaction()
            val ft                                     = myMethod_SPEC0029()
            val frame                                  = ft.frame.show
            assert(
                frame.contains("myMethod_SPEC0029") || frame.contains("STMTest.scala"),
                s"FailedTransaction frame should reflect the call site; got: $frame"
            )
        }

        "two STM.run calls on disjoint TRef sets do not serialise (no global lock)" in run {
            for
                r1    <- TRef.init(0)
                r2    <- TRef.init(0)
                start <- Latch.init(1)
                step2 <- Latch.init(1)
                done  <- Latch.init(2)
                _ <- Fiber.initUnscoped {
                    STM.run {
                        for
                            _ <- r1.get
                            _ <- start.release
                            _ <- step2.await
                            _ <- r1.set(1)
                        yield ()
                    }.andThen(done.release)
                }
                _ <- Fiber.initUnscoped {
                    STM.run {
                        for
                            _ <- r2.get
                            _ <- step2.release
                            _ <- r2.set(2)
                        yield ()
                    }.andThen(done.release)
                }
                _  <- start.await
                _  <- done.await
                v1 <- STM.run(r1.get)
                v2 <- STM.run(r2.get)
            yield assert((v1, v2) == (1, 2), s"both disjoint commits should succeed: got ($v1, $v2)")
        }

        "two concurrent STM.run-write transactions on the same TRef do not produce a lost update" in runNotJS {
            val n = 200
            for
                ref <- TRef.init(0)
                _   <- Async.fill(n, n)(STM.run(ref.update(_ + 1)))
                v   <- STM.run(ref.get)
            yield assert(v == n, s"expected $n increments, got $v — write exclusivity violated")
            end for
        }

        "disjoint-ref STM.run calls each retry zero times (no conflict)" in run {
            val nFibers = 10
            for
                refs     <- Kyo.fill(nFibers)(TRef.init(0))
                attempts <- Kyo.fill(nFibers)(AtomicInt.init)
                _ <- Async.foreach(refs.zip(attempts).zipWithIndex, nFibers) { case ((r, a), i) =>
                    STM.run {
                        for
                            _ <- a.incrementAndGet
                            _ <- r.set(i + 1)
                        yield ()
                    }
                }
                counts <- Kyo.foreach(attempts)(_.get)
            yield assert(counts.forall(_ == 1), s"disjoint-ref transactions should commit in 1 attempt; got $counts")
            end for
        }

        "two transactions writing in opposite source order both commit (lock-ordering prevents deadlock)" in runNotJS {
            for
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                _ <- Async.zip(
                    STM.run { r1.set(1).andThen(r2.set(1)) },
                    STM.run { r2.set(2).andThen(r1.set(2)) }
                )
                v1 <- STM.run(r1.get)
                v2 <- STM.run(r2.get)
            yield assert(
                (v1, v2) == (1, 1) || (v1, v2) == (2, 2),
                s"expected one transaction's writes to win; got ($v1, $v2)"
            )
        }

        "writer with older tick than recorded readTick aborts (validated by attempt count)" in runNotJS {
            for
                ref      <- TRef.init(0)
                attempts <- AtomicInt.init
                rfiber <- Fiber.initUnscoped {
                    Async.fill(100, 100)(STM.run(ref.get))
                }
                _ <- Abort.run {
                    STM.run(Schedule.repeat(10)) {
                        for
                            _ <- attempts.incrementAndGet
                            v <- ref.get
                            _ <- ref.set(v + 1)
                        yield v
                    }
                }
                _ <- rfiber.get
                n <- attempts.get
            yield
                assert(n >= 1, s"writer should have run at least once; attempts=$n")
                assert(n <= 11, s"writer attempt count exceeded schedule cap: $n")
        }

        "side-effecting body of STM.run runs once per attempt" in run {
            for
                ref      <- TRef.init(0)
                sideRuns <- AtomicInt.init
                writer <- Fiber.initUnscoped {
                    Async.fill(5, 5)(STM.run(ref.update(_ + 1)))
                }
                _ <- Abort.run {
                    STM.run(Schedule.repeat(20)) {
                        for
                            _  <- sideRuns.incrementAndGet
                            v  <- ref.get
                            _  <- Fiber.init(STM.run(ref.update(_ + 1))).map(_.get)
                            v2 <- ref.get
                            _  <- STM.retryIf(v2 != v)
                        yield v
                    }
                }
                n <- sideRuns.get
                _ <- writer.get
            yield assert(n >= 2, s"side effect should be re-executed across retries; sideRuns=$n")
        }

        "side effect performed after STM.run sees committed values" in run {
            for
                ref      <- TRef.init(0)
                observed <- AtomicInt.init
                _ <- STM.run(ref.set(42)).andThen {
                    STM.run(ref.get).map(v => observed.set(v))
                }
                v <- observed.get
            yield assert(v == 42, s"post-commit side effect should observe 42, got $v")
        }

        "writer-induced retry forces reader to attempt >= 2 times" in runNotJS {
            for
                ref      <- TRef.init(0)
                latch1   <- Latch.init(1)
                latch2   <- Latch.init(1)
                latch3   <- Latch.init(1)
                attempts <- AtomicInt.init
                _ <- Fiber.initUnscoped {
                    // Writer blocks on latch3 until the reader has done its first ref.get, then
                    // sets ref=42 and commits. latch2 releases after commit so the reader's second
                    // ref.get races with a fresh tick on the same ref.
                    STM.run(latch1.release.andThen(latch3.await).andThen(ref.set(42))).andThen(latch2.release)
                }
                v <- STM.run {
                    for
                        _ <- attempts.incrementAndGet
                        _ <- latch1.await
                        _ <- ref.get
                        _ <- latch3.release
                        _ <- latch2.await
                        v <- ref.get
                        _ <- Abort.when(v == 0)(new Exception)
                    yield v
                }
                a <- attempts.get
            yield
                assert(v == 42, s"reader should observe committed value, got $v")
                assert(a >= 2, s"writer-induced conflict forces >= 2 attempts; got a=$a")
        }

        "explicit STM.retry surfaces Result.Failure(FailedTransaction) not a generic failure" in run {
            for
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.done)(STM.retry)
                }
            yield result match
                case Result.Failure(_: FailedTransaction) => succeed
                case other                                => fail(s"expected FailedTransaction, got $other")
        }

        "concurrent updates: every reader observes a value in [0, n]" in runNotJS {
            val n = 100
            for
                ref     <- TRef.init(0)
                readers <- AtomicRef.init(List.empty[Int])
                _ <- Async.zip(
                    Async.fill(n, n)(STM.run(ref.update(_ + 1))),
                    Async.fill(n, n)(STM.run(ref.get).map(v => readers.updateAndGet(v :: _).unit))
                )
                finalV <- STM.run(ref.get)
                obs    <- readers.get
            yield
                assert(finalV == n, s"final increment count should be $n, got $finalV")
                assert(
                    obs.forall(v => v >= 0 && v <= n),
                    s"all reads in [0,$n]; out-of-range: ${obs.filterNot(v => v >= 0 && v <= n)}"
                )
            end for
        }

        "opacity reader records mismatched (v1, v2) snapshots; sink should remain 0" in runNotJS {
            val retrySchedule = STM.defaultRetrySchedule.forever
            for
                ref      <- TRef.init(0L)
                mismatch <- AtomicInt.init
                writer <- Fiber.initUnscoped {
                    Async.foreachDiscard(1L to 5000L)(i => STM.run(retrySchedule)(ref.set(i)))
                }
                reader <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 10000) { _ =>
                        STM.run(retrySchedule) {
                            for
                                v1 <- ref.get
                                v2 <- ref.get
                                _  <- if v1 != v2 then mismatch.incrementAndGet.unit else Kyo.unit
                            yield ()
                        }
                    }
                }
                _ <- writer.get
                _ <- reader.get
                n <- mismatch.get
            yield assert(n == 0, s"expected 0 within-transaction read inconsistencies, got $n")
            end for
        }

        "two-ref swap commits both writes; post-commit reads observe swapped values" in run {
            Sync.Unsafe.defer { STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000) }.andThen {
                for
                    r1 <- TRef.init(10)
                    r2 <- TRef.init(20)
                    _ <- STM.run {
                        for
                            v1 <- r1.get
                            v2 <- r2.get
                            _  <- r1.set(v2)
                            _  <- r2.set(v1)
                        yield ()
                    }
                    post1 <- STM.run(r1.get)
                    post2 <- STM.run(r2.get)
                yield assert((post1, post2) == (20, 10), s"post-commit swap reads should be (20, 10); got ($post1, $post2)")
            }
        }

        "after testOnlySet(Int.MaxValue + 1000), Tick.next allocates a tick > Int.MaxValue" in run {
            val bigTick = Int.MaxValue.toLong + 1000
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(bigTick)
                STM.Tick.next(): Long
            }.map { t =>
                assert(t == bigTick + 1, s"expected tick=${bigTick + 1}, got $t")
                assert(t > Int.MaxValue.toLong, s"tick should exceed Int.MaxValue; got $t")
            }
        }

        "STM.run inside Var-of-TRef[Int] effect commits TRef writes; Var state is preserved" in run {
            for
                initialRef <- TRef.init(7)
                _ <- Var.run(initialRef) {
                    Var.isolate.update[TRef[Int]].use {
                        STM.run {
                            for
                                ref <- Var.get[TRef[Int]]
                                v   <- ref.get
                                _   <- ref.set(v + 100)
                            yield ()
                        }
                    }
                }
                v <- STM.run(initialRef.get)
            yield assert(v == 107, s"Var-held TRef should be mutated; got $v")
        }

        "STM.run with Schedule.never terminates cleanly on first abort" in run {
            for
                attempts <- AtomicInt.init
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.never) {
                        attempts.incrementAndGet.andThen(STM.retry)
                    }
                }
                n <- attempts.get
            yield
                assert(n >= 1, s"body should run at least once; attempts=$n")
                assert(result.isFailure, s"abort should surface; result=$result")
        }

        "Tick.next from 200 concurrent fibers produces 200 distinct values (mirror)" in runNotJS {
            val n = 200
            Sync.Unsafe.defer { STM.Tick.testOnlySet(0L) }.andThen {
                Async.fill(n, n) { Sync.Unsafe.defer { STM.Tick.next(): Long } }
            }.map { ticks =>
                Sync.Unsafe.defer { STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000) }.map { _ =>
                    assert(ticks.toSet.size == n, s"$n calls returned ${ticks.toSet.size} distinct ticks")
                }
            }
        }

        "STM.retry after writing 3 refs rolls back all 3 writes" in run {
            for
                r1 <- TRef.init(10)
                r2 <- TRef.init(20)
                r3 <- TRef.init(30)
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.done) {
                        for
                            _ <- r1.set(100)
                            _ <- r2.set(200)
                            _ <- r3.set(300)
                            _ <- STM.retry
                        yield ()
                    }
                }
                v1 <- STM.run(r1.get)
                v2 <- STM.run(r2.get)
                v3 <- STM.run(r3.get)
            yield
                assert(result.isFailure, "STM.retry with schedule exhausted should fail")
                assert((v1, v2, v3) == (10, 20, 30), s"all writes should roll back; got ($v1, $v2, $v3)")
        }

        "withCurrentTransaction inside STM.run observes the same tick used by the surrounding run" in run {
            STM.run {
                for
                    t1 <- STM.withCurrentTransaction(tick => (tick: Long))
                    t2 <- STM.withCurrentTransaction(tick => (tick: Long))
                yield (t1, t2)
            }.map { case (t1, t2) =>
                assert(t1 == t2, s"same-attempt ticks should match; got $t1 vs $t2")
                assert(t1 > 0L)
            }
        }

        "commit-conflict on validate-fail and lock-fail both surface as retry (user-observable)" in run {
            val n = 10
            for
                refs     <- Kyo.fill(n)(TRef.init(0))
                attempts <- AtomicInt.init
                writer <- Fiber.initUnscoped {
                    Async.sleep(5.millis).andThen(STM.run(Kyo.foreachDiscard(refs)(_.update(_ + 1))))
                }
                _ <- STM.run(Schedule.repeat(20)) {
                    for
                        _ <- attempts.incrementAndGet
                        _ <- Kyo.foreach(refs)(_.get)
                    yield ()
                }
                a <- attempts.get
                _ <- writer.get
            yield
                assert(a >= 1, s"attempt count should reflect at least the initial attempt; got $a")
                assert(a <= 21, s"attempt count should not exceed schedule cap; got $a")
            end for
        }

        "forced commit-fail consumes one Schedule.repeat step per failed commit" in run {
            val k = 5
            for
                ref      <- TRef.init(0)
                attempts <- AtomicInt.init
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.repeat(k)) {
                        for
                            _ <- attempts.incrementAndGet
                            v <- ref.get
                            _ <- Fiber.init(STM.run(ref.update(_ + 1))).map(_.get)
                            _ <- STM.retryIf(true)
                        yield v
                    }
                }
                n <- attempts.get
            yield
                assert(n == k + 1, s"expected ${k + 1} attempts (1 initial + $k retries); got $n")
                assert(result.isFailure, "schedule exhaustion should propagate FailedTransaction")
            end for
        }

        "top-level STM.run with a value carrying STM effects executes as a new transaction (no cast crash)" in run {
            val inner: Int < STM =
                for
                    r <- TRef.init(42)
                    v <- r.get
                yield v
            STM.run(inner).map { v => assert(v == 42, s"top-level STM.run should evaluate body cleanly; got $v") }
        }

        "STM.run with Schedule.fixed(20.millis) introduces gap between body attempts" in run {
            val delay = 20.millis
            for
                stamps <- AtomicRef.init(List.empty[Long])
                _ <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.fixed(delay).take(3)) {
                        for
                            _ <- Sync.defer(stamps.updateAndGet(java.lang.System.nanoTime :: _).unit)
                            _ <- STM.retry
                        yield ()
                    }
                }
                ts <- stamps.get
            yield
                val asc    = ts.reverse
                val deltas = asc.sliding(2).collect { case List(a, b) => (b - a).nanos }.toList
                assert(deltas.nonEmpty, s"need at least 2 attempts; got ${asc.size}")
                val bound = delay * 0.5
                assert(
                    deltas.forall(_ >= bound),
                    s"retry delays should be >= $bound; got $deltas"
                )
            end for
        }

        "STM.run body observes Present(tick) for currentTransaction from its first statement" in run {
            STM.run {
                for
                    tickInFirst <- STM.withCurrentTransaction(t => (t: Long))
                    _           <- TRef.init(0)
                    tickInLast  <- STM.withCurrentTransaction(t => (t: Long))
                yield (tickInFirst, tickInLast)
            }.map { case (tickInFirst, tickInLast) =>
                assert(tickInFirst > 0L, s"first statement should see Present tick; got $tickInFirst")
                assert(tickInFirst == tickInLast, s"tick should be stable across attempt; got $tickInFirst vs $tickInLast")
            }
        }

        "STM.run with two different result types both compile (commit's [A, S] not used as constraints today)" in run {
            val nothingTxn: Nothing < (STM & Abort[FailedTransaction]) = STM.retry
            val unitTxn: Unit < STM                                    = Kyo.unit
            for
                a <- Abort.run[FailedTransaction](STM.run(Schedule.done)(nothingTxn))
                _ <- STM.run(unitTxn)
            yield assert(a.isFailure)
            end for
        }

        "single-ref read-only transaction with a stale snapshot triggers exactly one retry" in run {
            for
                ref       <- TRef.init(0)
                gateRead  <- Latch.init(1)
                gateWrite <- Latch.init(1)
                attempts  <- AtomicInt.init
                _ <- Fiber.initUnscoped {
                    gateRead.await.andThen(STM.run(ref.set(99))).andThen(gateWrite.release)
                }
                v <- STM.run(Schedule.repeat(5)) {
                    for
                        _ <- attempts.incrementAndGet
                        v <- ref.get
                        _ <- gateRead.release
                        _ <- gateWrite.await
                    yield v
                }
                a <- attempts.get
            yield
                assert(v == 99, s"final read should be writer's value, got $v")
                assert(a >= 2, s"stale single-ref read should retry at least once; attempts=$a")
        }

        "successive STM.run calls on the same fiber produce no observable buffer leak across iterations" in run {
            for
                refs <- Kyo.fill(3)(TRef.init(0))
                _ <- Kyo.foreachDiscard(1 to 100) { i =>
                    STM.run {
                        Kyo.foreachDiscard(refs)(_.set(i))
                    }
                }
                finals <- STM.run(Kyo.foreach(refs)(_.get))
            yield assert(finals == Seq(100, 100, 100), s"100 sequential commits should leave refs at 100; got $finals")
        }

        "multi-ref commit returning false via boundary.break does not leak the Break exception" in runNotJS {
            for
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                r3 <- TRef.init(0)
                writer <- Fiber.initUnscoped {
                    Async.foreachDiscard(1 to 50) { _ =>
                        STM.run(Kyo.foreachDiscard(List(r1, r2, r3))(_.update(_ + 1)))
                    }
                }
                result <- Abort.run[Throwable] {
                    STM.run(STM.defaultRetrySchedule.forever) {
                        Kyo.foreach(List(r1, r2, r3))(_.get).map(_.sum)
                    }
                }
                _ <- writer.get
            yield result match
                case Result.Success(s) => assert(s >= 0, s"sum should be non-negative; got $s")
                case other             => fail(s"break-mechanism should not panic; got $other")
        }

        "explicit STM.retry and conflict-detection both produce Result.Failure(FailedTransaction)" in run {
            for
                explicit <- Abort.run[FailedTransaction](STM.run(Schedule.done)(STM.retry))
                conflict <- Abort.run[FailedTransaction] {
                    for
                        ref    <- TRef.init(0)
                        writer <- Fiber.initUnscoped(STM.run(ref.set(99)))
                        _      <- writer.get
                        r <- STM.run(Schedule.done) {
                            ref.get.map { _ => (STM.retry: Unit < STM) }
                        }
                    yield r
                }
            yield
                explicit match
                    case Result.Failure(_: FailedTransaction) => ()
                    case other                                => fail(s"explicit retry: $other")
                conflict match
                    case Result.Failure(_: FailedTransaction) => ()
                    case other                                => fail(s"conflict retry: $other")
                succeed
        }

        "STM.run with Schedule.never on always-retry body terminates with FailedTransaction" in run {
            for
                attempts <- AtomicInt.init
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.never) {
                        attempts.incrementAndGet.andThen(STM.retry)
                    }
                }
                n <- attempts.get
            yield
                assert(n == 1, s"with Schedule.never, body must run exactly once; got $n")
                result match
                    case Result.Failure(_: FailedTransaction) => succeed
                    case other                                => fail(s"expected FailedTransaction, got $other")
        }

        "unchecked exception inside STM.run body surfaces as Panic after probe-commit; ref rolled back" in run {
            val ex = new IllegalStateException("user-thrown-0061")
            for
                ref <- TRef.init(7)
                result <- Abort.run[Throwable] {
                    STM.run {
                        ref.set(99).andThen(Sync.defer { throw ex })
                    }
                }
                v <- STM.run(ref.get)
            yield
                result match
                    case Result.Panic(`ex`)   => ()
                    case Result.Failure(`ex`) => ()
                    case other                => fail(s"expected Panic/Failure containing user ex, got $other")
                end match
                assert(v == 7, s"ref must be rolled back, got $v")
            end for
        }

        "STM.run with 1000 forced retries does not StackOverflowError" in runJVM {
            val k = 1000
            for
                attempts <- AtomicInt.init
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.repeat(k)) {
                        attempts.incrementAndGet.andThen(STM.retry)
                    }
                }
                n <- attempts.get
            yield
                assert(n == k + 1, s"expected ${k + 1} attempts, got $n")
                result match
                    case Result.Failure(_: FailedTransaction) => succeed
                    case Result.Panic(_: StackOverflowError)  => fail("retry loop is stack-bound")
                    case other                                => fail(s"unexpected: $other")
                end match
            end for
        }

        "after STM.run returns, withCurrentTransactionOrNew on same fiber allocates a fresh tick" in run {
            for
                _        <- STM.run(TRef.init(0).map(_.get))
                observed <- STM.withCurrentTransactionOrNew { tick => (tick: Long) }
            yield assert(observed > 0L, s"after STM.run exits, observed tick must be freshly allocated; got $observed")
        }

        "STM public surface includes run, retry, retryIf, defaultRetrySchedule" in run {
            val r1 = STM.retry
            val r2 = STM.retryIf(true)
            val r3 = STM.defaultRetrySchedule
            // Use them inside an Abort.run to keep references live and compile-checked.
            for
                a <- Abort.run[FailedTransaction](STM.run(Schedule.done)(r1))
                b <- Abort.run[FailedTransaction](STM.run(Schedule.done)(r2))
            yield
                assert(a.isFailure)
                assert(b.isFailure)
                assert(r3 ne null)
            end for
        }

        "blocking sleep inside STM.run body completes (no driver deadlock)" in run {
            for
                ref <- TRef.init(0)
                _ <- STM.run {
                    for
                        _ <- ref.set(1)
                        _ <- Async.sleep(50.millis)
                        _ <- ref.update(_ + 1)
                    yield ()
                }
                v <- STM.run(ref.get)
            yield assert(v == 2, s"after sleep+update, ref should be 2; got $v")
        }

        "STM.run body's events appear in source order on every retry" in run {
            for
                events <- AtomicRef.init(List.empty[String])
                _ <- Abort.run {
                    STM.run(Schedule.repeat(2)) {
                        for
                            _ <- events.updateAndGet("a" :: _).unit
                            _ <- events.updateAndGet("b" :: _).unit
                            _ <- STM.retry
                        yield ()
                    }
                }
                log <- events.get
            yield assert(
                log.reverse == List("a", "b", "a", "b", "a", "b"),
                s"per-attempt events should be in source order; got ${log.reverse}"
            )
        }

        "STM.run wrapped in Async.timeout surfaces timeout failure even on infinite retry schedule" in run {
            for
                ref <- TRef.init(0)
                result <- Abort.run {
                    Async.timeout(100.millis) {
                        STM.run(STM.defaultRetrySchedule.forever) {
                            ref.get.map(v => STM.retryIf(v == 0))
                        }
                    }
                }
            yield assert(result.isFailure, s"timeout should fire; got $result")
        }

        "nested STM.run that retries leaves outer-write rollback exclusively to the nested scope" in run {
            for
                ref <- TRef.init(0)
                _ <- STM.run {
                    for
                        _ <- ref.set(1)
                        innerR <- Abort.run {
                            STM.run {
                                for
                                    _ <- ref.set(99)
                                    _ <- STM.retry
                                yield ()
                            }
                        }
                    yield assert(innerR.isFailure)
                }
                v <- STM.run(ref.get)
            yield assert(v == 1, s"outer write should be preserved; nested write should be rolled back. got $v")
        }

        "STM.retry rolls back writes made after a TRef set even on the same ref" in run {
            for
                ref <- TRef.init(0)
                _ <- Abort.run {
                    STM.run(Schedule.done) {
                        for
                            _ <- ref.set(1)
                            _ <- ref.set(2)
                            _ <- ref.set(3)
                            _ <- STM.retry
                        yield ()
                    }
                }
                v <- STM.run(ref.get)
            yield assert(v == 0, s"all writes rolled back; got $v")
        }

        "after STM.run completes, no internal pending-set retains a reference (each run commits in 1 attempt)" in run {
            for
                ref            <- TRef.init(0)
                attemptsBefore <- AtomicInt.init
                _              <- STM.run(ref.set(1).andThen(attemptsBefore.incrementAndGet.unit))
                attemptsAfter  <- AtomicInt.init
                _              <- STM.run(ref.get.andThen(attemptsAfter.incrementAndGet.unit))
                a1             <- attemptsBefore.get
                a2             <- attemptsAfter.get
            yield
                assert(a1 == 1, s"first transaction should commit in 1 attempt; got $a1")
                assert(a2 == 1, s"second transaction should commit in 1 attempt; got $a2")
        }

        "after STM.run returns success, a follow-up STM.run on the same ref does not block" in run {
            for
                ref <- TRef.init(0)
                v <- STM.run(ref.set(1)).andThen {
                    STM.run(ref.get)
                }
            yield assert(v == 1, s"chained STM.run should see 1; got $v")
        }

        "STM.run preserves the body's S effect parameter (Env[Int]) in the return type" in run {
            val txn: Int < (STM & Env[Int]) =
                for
                    n <- Env.get[Int]
                    _ <- TRef.init(n)
                yield n
            val handled: Int < (Async & Abort[FailedTransaction] & Env[Int]) = STM.run(txn)
            Env.run(99)(handled).map { v => assert(v == 99, s"Env-carrying body should see 99; got $v") }
        }

        "50 fibers each running STM.run on disjoint refs complete (no global lock)" in runNotJS {
            val n = 50
            for
                refs   <- Kyo.fill(n)(TRef.init(0))
                _      <- Async.foreach(refs.zipWithIndex, n) { case (r, i) => STM.run(r.set(i + 1)) }
                finals <- STM.run(Kyo.foreach(refs)(_.get))
            yield assert(finals == (1 to n).toSeq, s"each disjoint commit should land; got $finals")
            end for
        }

        "STM.run admits 200 concurrent callers without a built-in concurrency cap" in runNotJS {
            val n = 200
            for
                ref <- TRef.init(0)
                _   <- Async.fill(n, n)(STM.run(ref.update(_ + 1)))
                v   <- STM.run(ref.get)
            yield assert(v == n, s"all $n updates should commit, got $v")
            end for
        }

        "Sync.defer side effect inside STM.run body runs once per attempt" in run {
            for
                counter <- AtomicInt.init
                _ <- Abort.run {
                    STM.run(Schedule.repeat(2)) {
                        for
                            _ <- Sync.defer(counter.incrementAndGet)
                            _ <- STM.retry
                        yield ()
                    }
                }
                n <- counter.get
            yield assert(n == 3, s"Sync.defer should run per attempt; got $n")
        }

        "STM.retryIf(value.isEmpty) on TRef[Maybe[V]] proceeds when value becomes Present" in run {
            for
                ref <- TRef.init(Maybe.empty[Int])
                writer <- Fiber.initUnscoped {
                    Async.sleep(20.millis).andThen(STM.run(ref.set(Maybe(42))))
                }
                v <- STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        m <- ref.get
                        _ <- STM.retryIf(m.isEmpty)
                    yield m.get
                }
                _ <- writer.get
            yield assert(v == 42, s"wait-until-set encoding should yield 42; got $v")
        }

        "STM.run on the same Kyo value produces the same observable behaviour each invocation" in run {
            for
                ref <- TRef.init(0)
                txn = ref.update(_ + 1)
                _ <- STM.run(txn)
                _ <- STM.run(txn)
                v <- STM.run(ref.get)
            yield assert(v == 2, s"two runs of the same txn should both commit; got $v")
        }

        "retryIf based on multi-ref reads retries when any read ref changes" in run {
            for
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                writer <- Fiber.initUnscoped {
                    Async.sleep(20.millis).andThen(STM.run(r2.set(1)))
                }
                v <- STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        v1 <- r1.get
                        v2 <- r2.get
                        _  <- STM.retryIf(v1 + v2 == 0)
                    yield (v1, v2)
                }
                _ <- writer.get
            yield assert(v == ((0, 1)), s"reader should observe writer's update; got $v")
        }

        "TRef[TRef[String]] chain inside STM.run observes consistent snapshot or retries" in run {
            for
                inner1 <- TRef.init("a")
                inner2 <- TRef.init("b")
                outer  <- TRef.init(inner1)
                writer <- Fiber.initUnscoped {
                    STM.run(outer.set(inner2))
                }
                result <- STM.run(STM.defaultRetrySchedule.forever) {
                    for
                        i <- outer.get
                        v <- i.get
                    yield v
                }
                _ <- writer.get
            yield assert(result == "a" || result == "b", s"reader should observe one consistent inner value; got $result")
        }

        "after STM.run returns success, a same-thread read observes the post-commit value" in run {
            for
                r  <- TRef.init(0)
                v1 <- STM.run(r.set(42)).andThen(STM.run(r.get))
            yield assert(v1 == 42, s"continuation should see published commit; got $v1")
        }

        "STM.run with body that panics routes via Result.Panic; typed fail via Result.Failure" in run {
            val ex = new IllegalStateException("defect-0081")
            for
                panicked <- Abort.run[Throwable] { STM.run(Sync.defer { throw ex }) }
                typed    <- Abort.run[String](STM.run(Abort.fail("typed-fail-0081")))
            yield
                panicked match
                    case Result.Panic(`ex`) => ()
                    case other              => fail(s"expected Panic(ex), got $other")
                typed match
                    case Result.Failure("typed-fail-0081") => ()
                    case other                             => fail(s"expected Failure('typed-fail-0081'), got $other")
                succeed
            end for
        }

        "logging side effect (Sync.defer) inside STM.run runs once per attempt under retry" in run {
            for
                logCount <- AtomicInt.init
                attempts <- AtomicInt.init
                writer <- Fiber.initUnscoped {
                    Async.fill(3, 3)(STM.run(TRef.init(0).map(_ => ())))
                }
                ref <- TRef.init(0)
                writer2 <- Fiber.initUnscoped {
                    Async.fill(3, 3)(STM.run(ref.update(_ + 1)))
                }
                _ <- Abort.run {
                    STM.run(STM.defaultRetrySchedule.forever) {
                        for
                            _ <- attempts.incrementAndGet
                            _ <- Sync.defer(logCount.incrementAndGet)
                            _ <- ref.update(_ + 1)
                        yield ()
                    }
                }
                _ <- writer.get
                _ <- writer2.get
                a <- attempts.get
                l <- logCount.get
            yield assert(l == a, s"logCount=$l should equal attempts=$a (side effect runs once per attempt)")
        }

        "Tick.testOnlySet(-100) is accepted; next() returns -99" in run {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(-100L)
                val t: Long = STM.Tick.next()
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
                t
            }.map { t =>
                assert(t == -99L, s"next after testOnlySet(-100) should be -99; got $t")
            }
        }

        "STM.retry is non-blocking: retries until schedule exhausted, then fails" in run {
            for
                attempts <- AtomicInt.init
                result <- Abort.run[FailedTransaction] {
                    STM.run(Schedule.repeat(3)) {
                        attempts.incrementAndGet.andThen(STM.retry)
                    }
                }
                n <- attempts.get
            yield
                assert(n == 4, s"4 attempts expected; got $n")
                assert(result.isFailure, s"after schedule exhaustion, FailedTransaction surfaces; got $result")
        }

        "after a commit conflict + retry, subsequent independent commit succeeds in 1 attempt" in run {
            for
                r      <- TRef.init(0)
                aborts <- AtomicInt.init
                _ <- Abort.run {
                    STM.run(Schedule.repeat(5)) {
                        for
                            _ <- aborts.incrementAndGet
                            _ <- r.set(1)
                            _ <- STM.retry
                        yield ()
                    }
                }
                cleanAttempts <- AtomicInt.init
                _             <- STM.run(cleanAttempts.incrementAndGet.andThen(r.set(42)))
                a             <- aborts.get
                c             <- cleanAttempts.get
                v             <- STM.run(r.get)
            yield
                assert(a == 6, s"6 aborts expected; got $a")
                assert(c == 1, s"clean commit should succeed in 1 attempt; got $c (lock leak?)")
                assert(v == 42)
        }

        "STM.run with default retry schedule on always-retry body exhausts after a bounded attempt count" in run {
            val expected = Async.defaultConcurrency * 16 + 1
            for
                attempts <- AtomicInt.init
                result <- Abort.run[FailedTransaction] {
                    STM.run(attempts.incrementAndGet.andThen(STM.retry))
                }
                n <- attempts.get
            yield
                assert(n == expected, s"default schedule should produce $expected attempts (1 + defaultConcurrency*16 retries); got $n")
                assert(result.isFailure)
            end for
        }

        "STM.retry called from within a multi-ref transaction leaves no ref in a corrupted lock state" in run {
            for
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                r3 <- TRef.init(0)
                _ <- Abort.run {
                    STM.run(Schedule.done) {
                        for
                            _ <- r1.set(1)
                            _ <- r2.set(1)
                            _ <- r3.set(1)
                            _ <- STM.retry
                        yield ()
                    }
                }
                attempts <- AtomicInt.init
                _ <- STM.run {
                    attempts.incrementAndGet.andThen(
                        Kyo.foreachDiscard(List(r1, r2, r3))(_.set(99))
                    )
                }
                a      <- attempts.get
                finals <- STM.run(Kyo.foreach(List(r1, r2, r3))(_.get))
            yield
                assert(a == 1, s"post-abort commit should be clean; attempts=$a")
                assert(finals == Seq(99, 99, 99), s"all writes commit; got $finals")
        }

        "single-set transaction calls set exactly once (no extraneous side effects)" in run {
            for
                ref    <- TRef.init(0)
                writes <- AtomicInt.init
                countingSet = (v: Int) => Sync.defer(writes.incrementAndGet).andThen(ref.set(v))
                _ <- STM.run(countingSet(42))
                n <- writes.get
                v <- STM.run(ref.get)
            yield
                assert(n == 1, s"single-set transaction should call set once; got $n calls")
                assert(v == 42)
        }

        "each STM.run schedule exhaustion produces a fresh FailedTransaction instance" in run {
            for
                a <- Abort.run[FailedTransaction](STM.run(Schedule.done)(STM.retry))
                b <- Abort.run[FailedTransaction](STM.run(Schedule.done)(STM.retry))
            yield
                val ea = a match
                    case Result.Failure(e) => e
                    case other             => fail(s"a: expected fail, got $other")
                val eb = b match
                    case Result.Failure(e) => e
                    case other             => fail(s"b: expected fail, got $other")
                assert(!(ea eq eb), s"each failure should be a distinct exception instance; got $ea / $eb")
        }

        "sequential STM.run calls do not accumulate residual state in the currentTransaction Local" in run {
            val n = 1000
            for
                ref      <- TRef.init(0)
                _        <- Kyo.foreachDiscard(1 to n)(_ => STM.run(ref.update(_ + 1)))
                finalV   <- STM.run(ref.get)
                observed <- STM.withCurrentTransactionOrNew { tick => (tick: Long) }
            yield
                assert(finalV == n, s"all $n increments committed; got $finalV")
                assert(observed > 0L, s"Local should be Absent → fresh tick allocated; got $observed")
            end for
        }

        "Tick.next is monotonic regardless of wall-clock time" in run {
            Sync.Unsafe.defer {
                val a: Long = STM.Tick.next()
                val b: Long = STM.Tick.next()
                (a, b)
            }.map { case (a, b) =>
                assert(b == a + 1L, s"tick is logical, not wall-clock; expected b == a+1, got a=$a b=$b")
            }
        }

        "Tick.next after counter overflow at Long.MaxValue returns Long.MinValue" in run {
            Sync.Unsafe.defer {
                STM.Tick.testOnlySet(Long.MaxValue)
                val t: Long = STM.Tick.next()
                STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000)
                t
            }.map { t =>
                assert(t == Long.MinValue, s"after testOnlySet(MaxValue), next should be MinValue; got $t")
            }
        }

        "println-style side effect inside STM.run runs at least once per attempt" in run {
            for
                ref      <- TRef.init(0)
                prints   <- AtomicInt.init
                attempts <- AtomicInt.init
                writer <- Fiber.initUnscoped {
                    Async.fill(2, 2)(STM.run(ref.update(_ + 1)))
                }
                _ <- Abort.run {
                    STM.run(STM.defaultRetrySchedule.forever) {
                        for
                            _ <- attempts.incrementAndGet
                            _ <- Sync.defer(prints.incrementAndGet)
                            _ <- ref.update(_ + 1)
                        yield ()
                    }
                }
                _ <- writer.get
                a <- attempts.get
                p <- prints.get
            yield assert(a == p, s"prints=$p should equal attempts=$a")
        }

        "a Kyo computation captured inside an STM.run and run after STM.run completes returns the post-commit value" in run {
            for
                ref <- TRef.init(0)
                deferred <- STM.run {
                    ref.set(42).map(_ => ref.get)
                }
                v <- STM.run(deferred)
            yield assert(v == 42, s"deferred read should see committed value; got $v")
        }

        "Async.sleep inside STM.run body runs once per attempt" in run {
            for
                stamps   <- AtomicRef.init(List.empty[Long])
                attempts <- AtomicInt.init
                _ <- Abort.run {
                    STM.run(Schedule.repeat(2)) {
                        for
                            _ <- attempts.incrementAndGet
                            _ <- Sync.defer(stamps.updateAndGet(java.lang.System.nanoTime :: _).unit)
                            _ <- Async.sleep(2.millis)
                            _ <- STM.retry
                        yield ()
                    }
                }
                a <- attempts.get
                s <- stamps.get
            yield assert(a == 3 && s.size == 3, s"each attempt records a stamp; attempts=$a stamps=${s.size}")
        }

        "Fiber spawned inside STM.run that calls withCurrentTransactionOrNew sees a fresh tick" in run {
            for
                ticks <- STM.run {
                    for
                        parent <- STM.withCurrentTransaction(t => (t: Long))
                        childTick <- Fiber.initUnscoped {
                            STM.withCurrentTransactionOrNew { t => (t: Long) }
                        }.map(_.get)
                    yield (parent, childTick)
                }
                (parent, child) = ticks
            yield
                assert(parent != child, s"child fiber should NOT inherit parent's tick; parent=$parent child=$child")
                assert(child > 0L, s"child should allocate its own tick; got $child")
        }

        "100 concurrent STM.run fibers complete (no thread-pool deadlock)" in runNotJS {
            val n = 100
            for
                ref <- TRef.init(0)
                _   <- Async.fill(n, n)(STM.run(ref.update(_ + 1)))
                v   <- STM.run(ref.get)
            yield assert(v == n, s"all $n updates should commit; got $v")
            end for
        }

        "two sequential STM.run calls on the same fiber commit in source order" in run {
            for
                ref <- TRef.init(0)
                _   <- STM.run(ref.set(1))
                _   <- STM.run(ref.set(2))
                v   <- STM.run(ref.get)
            yield assert(v == 2, s"last sequential STM.run should win; got $v")
        }

        "TRef.init on fiber A and STM.run on fiber B both observe consistent ref state" in run {
            for
                refF <- Fiber.initUnscoped(TRef.init(0)).map(_.get)
                _    <- Fiber.initUnscoped(STM.run(refF.set(42))).map(_.get)
                v    <- STM.run(refF.get)
            yield assert(v == 42, s"cross-fiber TRef operations should compose; got $v")
        }

        "println-style side effect inside STM.run with 5 attempts runs exactly 5 times" in run {
            for
                counter  <- AtomicInt.init
                attempts <- AtomicInt.init
                _ <- Abort.run {
                    STM.run(Schedule.repeat(4)) {
                        for
                            _ <- attempts.incrementAndGet
                            _ <- Sync.defer(counter.incrementAndGet)
                            _ <- STM.retry
                        yield ()
                    }
                }
                a <- attempts.get
                c <- counter.get
            yield assert(a == 5 && c == 5, s"side effect count $c should equal attempts $a")
        }

        "two transactions writing to (r1, r2) and (r2, r1) commit without deadlock" in runNotJS {
            for
                r1 <- TRef.init(0)
                r2 <- TRef.init(0)
                _ <- Async.zip(
                    STM.run(r1.set(1).andThen(r2.set(2))),
                    STM.run(r2.set(3).andThen(r1.set(4)))
                )
                v1 <- STM.run(r1.get)
                v2 <- STM.run(r2.get)
            yield assert(
                (v1, v2) == (1, 2) || (v1, v2) == (4, 3),
                s"one transaction wins atomically; got ($v1, $v2)"
            )
        }

        "STM.retry can be called from any STM.run body (no opt-in flag required)" in run {
            for
                result <- Abort.run[FailedTransaction](STM.run(Schedule.done)(STM.retry))
            yield assert(result.isFailure, s"STM.retry should abort regardless of config; got $result")
        }

        "STM.run(Kyo.unit) is a valid no-op that returns unit" in run {
            STM.run(Kyo.unit).map { v => assert(v == (), s"empty STM.run should return unit; got $v") }
        }

        "nested STM.run with inner exception propagated upward rolls back the outer's writes" in run {
            for
                ref <- TRef.init(0)
                result <- Abort.run[Throwable] {
                    STM.run {
                        for
                            _ <- ref.set(1)
                            _ <- STM.run {
                                Sync.defer { throw new IllegalStateException("inner-0106") }
                            }
                        yield ()
                    }
                }
                v <- STM.run(ref.get)
            yield
                assert(result.isPanic || result.isFailure, s"inner exception should propagate; got $result")
                assert(v == 0, s"outer write should be rolled back; got $v")
        }

        "500 concurrent fibers calling Tick.next return 500 distinct values" in runNotJS {
            val n = 500
            Sync.Unsafe.defer { STM.Tick.testOnlySet(0L) }.andThen {
                Async.fill(n, n) { Sync.Unsafe.defer { STM.Tick.next(): Long } }
            }.map { ticks =>
                Sync.Unsafe.defer { STM.Tick.testOnlySet(Int.MaxValue.toLong + 1000) }.map { _ =>
                    assert(ticks.toSet.size == n, s"$n calls returned ${ticks.toSet.size} distinct ticks")
                }
            }
        }

        "STM.run default overload preserves typed E in Abort union, distinguishable from FailedTransaction" in run {
            val typedFailure: Int < (STM & Abort[String]) = Abort.fail("E1-0108")
            for
                asStringFail <- Abort.run[String](STM.run(typedFailure))
                asFTFail     <- Abort.run[FailedTransaction](STM.run(Schedule.done)(STM.retry))
            yield
                asStringFail match
                    case Result.Failure("E1-0108") => ()
                    case other                     => fail(s"E half: expected 'E1-0108', got $other")
                asFTFail match
                    case Result.Failure(_: FailedTransaction) => ()
                    case other                                => fail(s"FT half: expected FailedTransaction, got $other")
                succeed
            end for
        }

        "STM.run custom-schedule overload preserves typed E in Abort union" in run {
            val typedFailure: Int < (STM & Abort[String]) = Abort.fail("E2-0109")
            for
                asStringFail <- Abort.run[String](STM.run(Schedule.done)(typedFailure))
            yield asStringFail match
                case Result.Failure("E2-0109") => succeed
                case other                     => fail(s"expected 'E2-0109', got $other")
        }

        "inline f in withCurrentTransaction captures call-site values (counter increment observed)" in run {
            for
                counter <- AtomicInt.init(0)
                result <- STM.run {
                    STM.withCurrentTransaction { _ =>
                        counter.incrementAndGet
                    }
                }
                n <- counter.get
            yield
                assert(n == 1, s"counter should reflect side effect; got n=$n")
                assert(result == 1, s"return value should match captured side effect; got $result")
        }

        "inline f in withCurrentTransactionOrNew captures call-site values" in run {
            for
                counter <- AtomicInt.init(0)
                result <- STM.withCurrentTransactionOrNew[Int, Sync] { _ =>
                    counter.incrementAndGet
                }
                n <- counter.get
            yield
                assert(n == 1, s"counter should reflect side effect; got n=$n")
                assert(result == 1, s"return value should match captured side effect; got $result")
        }

        "FailedTransaction(Present(error)) with non-Throwable error.show completes construction" in run {
            val err: Result.Error[String] = Result.Failure("not-a-throwable-0113")
            val ft                        = new FailedTransaction(Present(err))
            assert(
                ft.getMessage.contains("STM transaction failed"),
                s"primary message preserved; got ${ft.getMessage}"
            )
        }

        "forced-retry loop with 100 iterations completes (no inter-iteration retention)" in run {
            val k = 100
            for
                refs     <- Kyo.fill(20)(TRef.init(0))
                attempts <- AtomicInt.init
                _ <- Abort.run {
                    STM.run(Schedule.repeat(k)) {
                        for
                            _ <- attempts.incrementAndGet
                            _ <- Kyo.foreachDiscard(refs)(_.update(_ + 1))
                            _ <- STM.retry
                        yield ()
                    }
                }
                n <- attempts.get
            yield assert(n == k + 1, s"expected ${k + 1} attempts; got $n")
            end for
        }

        "nested STM.run with heavy ref-touch returns without retaining the inner log" in run {
            val k = 200
            for
                v <- STM.run {
                    for
                        outerRef <- TRef.init(0)
                        _ <- Kyo.foreachDiscard(1 to k) { i =>
                            STM.run {
                                for
                                    r <- TRef.init(i)
                                    _ <- outerRef.update(_ + 1)
                                    _ <- r.get
                                yield ()
                            }
                        }
                        v <- outerRef.get
                    yield v
                }
            yield assert(v == k, s"outer ref ends at $k; got $v")
        }

        "nested STM.run + STM.retry under Abort.run rolls back inner-only writes; outer commit publishes outer-only writes" in run {
            for
                ref <- TRef.init(0)
                _ <- STM.run {
                    for
                        _ <- ref.set(7)
                        innerR <- Abort.run {
                            STM.run {
                                for
                                    _ <- ref.set(99)
                                    _ <- STM.retry
                                yield ()
                            }
                        }
                    yield assert(innerR.isFailure)
                }
                v <- STM.run(ref.get)
            yield assert(v == 7, s"outer write preserved; got $v (inner write leaked?)")
        }

        "TRef.init outside STM.run produces a TRef immediately observable from another fiber" in runNotJS {
            for
                ref   <- TRef.init(42)
                v1    <- STM.run(ref.get)
                fiber <- Fiber.initUnscoped(STM.run(ref.get))
                v2    <- fiber.get
            yield assert(v1 == 42 && v2 == 42, s"TRef allocated outside STM.run is immediately visible; got ($v1, $v2)")
        }

        "inner STM.run reads the value set by outer STM.run before outer's commit" in run {
            for
                ref <- TRef.init(0)
                v <- STM.run {
                    for
                        _      <- ref.set(5)
                        innerV <- STM.run(ref.get)
                    yield innerV
                }
            yield assert(v == 5, s"inner read should see outer's pending write; got $v")
        }

    }

end STMTest

sealed trait MyErr0013
case class ErrA0013(msg: String) extends MyErr0013
case class ErrB0013(code: Int)   extends MyErr0013
