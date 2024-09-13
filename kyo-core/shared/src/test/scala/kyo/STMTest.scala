package kyo

class STMTest extends Test:

    "basic operations" - {
        "get and set" in run {
            for
                ref <- STM.runCommit(STM.initRef(0))
                v1  <- STM.runCommit(ref.get)
                _   <- STM.runCommit(ref.set(42))
                v2  <- STM.runCommit(ref.get)
            yield assert(v1 == 0 && v2 == 42)
        }

        "atomic update" in run {
            for
                ref <- STM.runCommit(STM.initRef(0))
                v1 <- STM.runCommit(for
                    v1 <- ref.get
                    _  <- ref.set(v1 + 1)
                yield v1)
                v2 <- STM.runCommit(ref.get)
            yield assert(v1 == 0 && v2 == 1)
        }
    }

    "concurrent operations" - {
        "increment" in runJVM {
            for
                ref <- STM.runCommit(STM.initRef(0))
                fibers <- Kyo.fill(100)(Async.run(STM.runCommit(for
                    v <- ref.get
                    _ <- ref.set(v + 1)
                yield ())))
                _ <- Kyo.foreach(fibers)(_.get)
                v <- STM.runCommit(ref.get)
            yield assert(v == 100)
        }

        "transfer" in runJVM {
            for
                account1 <- STM.runCommit(STM.initRef(100))
                account2 <- STM.runCommit(STM.initRef(100))
                transfer = STM.runCommit(for
                    v1 <- account1.get
                    v2 <- account2.get
                    _  <- account1.set(v1 - 10)
                    _  <- account2.set(v2 + 10)
                yield ())
                fibers <- Kyo.fill(10)(Async.run(transfer))
                _      <- Kyo.foreach(fibers)(_.get)
                v1     <- STM.runCommit(account1.get)
                v2     <- STM.runCommit(account2.get)
            yield assert(v1 == 0 && v2 == 200)
        }
    }

    "retry" - {
        "basic retry" in run {
            for
                ref <- STM.runCommit(STM.initRef(0))
                fiber <- Async.run(STM.runCommit(for
                    v <- ref.get
                    _ <- if v == 0 then STM.retry else STM.initRef(v)
                yield v))
                _ <- Async.sleep(100.millis)
                _ <- STM.runCommit(ref.set(42))
                v <- fiber.get
            yield assert(v == 42)
        }
    }

    //     "dining philosophers" in runJVM {
    //         val numPhilosophers = 5
    //         val mealCount       = 10

    //         case class Fork(id: Int)
    //         case class Philosopher(id: Int, leftFork: STM.Ref[Maybe[Fork]], rightFork: STM.Ref[Maybe[Fork]])

    //         def philosopher(p: Philosopher, meals: STM.Ref[Int]): Unit < Async =
    //             def pickUpForks: Unit < STM =
    //                 for
    //                     left  <- p.leftFork.get
    //                     right <- p.rightFork.get
    //                     _ <- (left, right) match
    //                         case (Maybe.Empty, Maybe.Empty) =>
    //                             for
    //                                 _ <- p.leftFork.set(Maybe(Fork(p.id)))
    //                                 _ <- p.rightFork.set(Maybe(Fork(p.id)))
    //                             yield ()
    //                         case _ => STM.retry
    //                 yield ()

    //             def putDownForks: Unit < STM =
    //                 for
    //                     _ <- p.leftFork.set(Maybe.empty)
    //                     _ <- p.rightFork.set(Maybe.empty)
    //                 yield ()

    //             def eat: Unit < STM =
    //                 for
    //                     count <- meals.get
    //                     _     <- meals.set(count + 1)
    //                 yield ()

    //             Loop.forever {
    //                 for
    //                     _     <- STM.runCommit(pickUpForks)
    //                     _     <- STM.runCommit(eat)
    //                     _     <- STM.runCommit(putDownForks)
    //                     count <- STM.runCommit(meals.get)
    //                     _     <- if count >= mealCount then Abort.fail(()) else Async.unit
    //                 yield ()
    //             }
    //         end philosopher

    //         for
    //             forks <- Kyo.fill(numPhilosophers)(STM.initRef[Maybe[Fork]](Maybe.empty))
    //             philosophers = List.tabulate(numPhilosophers)(i =>
    //                 Philosopher(i, forks(i), forks((i + 1) % numPhilosophers))
    //             )
    //             meals      <- STM.initRef(0)
    //             fibers     <- Kyo.foreach(philosophers)(p => Async.run(philosopher(p, meals)))
    //             _          <- Kyo.foreach(fibers)(f => Abort.run(f.get))
    //             totalMeals <- STM.runCommit(meals.get)
    //         yield assert(totalMeals == numPhilosophers * mealCount)
    //         end for
    //     }
end STMTest
