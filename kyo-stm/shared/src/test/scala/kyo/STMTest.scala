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
                fiber <- Async.run {
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
                        Async.run {
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
                yield assert(v == 42 && a == 2)
            }
        }

        "exceeding retry budget" in run {
            for
                ref      <- TRef.init(0)
                latch1   <- Latch.init(1)
                latch2   <- Latch.init(1)
                attempts <- AtomicInt.init
                _ <-
                    Async.run {
                        STM.run(latch1.release.andThen(ref.set(42)))
                            .andThen(latch2.release)
                    }
                v <-
                    Abort.run {
                        STM.run(Schedule.never) {
                            for
                                _ <- attempts.incrementAndGet
                                _ <- latch1.await
                                _ <- ref.get
                                _ <- latch2.await
                                v <- ref.get
                                _ <- Abort.when(v == 0)(new Exception)
                            yield v
                        }
                    }
                a <- attempts.get
            yield assert(v.isFailure && a == 1)
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
                writeFiber <- Async.run(
                    latch.await.andThen(Async.fill(size, size)(STM.run(ref.update(_ + 1))))
                )
                readFiber <- Async.run(
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
            (for
                size <- sizes
                ref  <- TRef.init(0)
                _ <- Async.fill(size, size) {
                    STM.run {
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

        "dining philosophers" in runNotJS {
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

        "bank account transfers" in runNotJS {
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

        "circular account transfers" in runNotJS {
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
                            fiber <- Async.run {
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

        "transaction ID should not leak across async boundaries" in run {
            for
                ref <- TRef.init(0)
                (parentTid, childTid) <-
                    STM.run {
                        TID.useIO { parentTid =>
                            Async.run {
                                STM.run(TID.useIO(identity))
                            }.map(_.get).map { childTid =>
                                (parentTid, childTid)
                            }
                        }
                    }
            yield assert(parentTid != childTid)
        }
    }

    "bug #925" in runJVM {
        def unsafeToFuture[A](a: => A < (Async & Abort[Throwable])): Future[A] =
            import kyo.AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(
                Async.run(a).map(_.toFuture)
            )
        end unsafeToFuture

        val ex = new Exception

        val faultyTransaction: Int < STM = TRef.init(42).map { r =>
            throw ex
            r.get
        }

        val task = Async.runAndBlock(Duration.Infinity)(Async.fromFuture(unsafeToFuture(STM.run(faultyTransaction))))

        Abort.run(task).map { result =>
            assert(result == Result.fail(ex))
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

end STMTest
