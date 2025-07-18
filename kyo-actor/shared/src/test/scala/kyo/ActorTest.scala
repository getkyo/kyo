package kyo

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.compatible.Assertion

class ActorTest extends Test:

    "basic actor operations" - {
        "completes with final value" in run {
            for
                actor  <- Actor.run("a")
                result <- actor.await
            yield assert(result == "a")
        }

        "processes messages" in run {
            for
                sum      <- AtomicInt.init(0)
                actor    <- Actor.run(Actor.receiveMax[Int](3)(sum.addAndGet(_)))
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                _        <- actor.await
                finalSum <- sum.get
            yield assert(finalSum == 6)
        }

        "processes messages and returns value" in run {
            for
                sum      <- AtomicInt.init(0)
                actor    <- Actor.run(Actor.receiveMax[Int](3)(sum.addAndGet(_)).andThen(sum.get))
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                finalSum <- actor.await
            yield assert(finalSum == 6)
        }

        "stops after processing N messages" in run {
            for
                counter <- AtomicInt.init(0)
                actor   <- Actor.run(Actor.receiveMax[Int](1)(_ => counter.incrementAndGet))
                _       <- actor.send(1)
                _       <- actor.await
                result  <- Abort.run(actor.send(1))
                count   <- counter.get
            yield assert(result.isFailure && count == 1)
        }
    }

    "inter-actor communication" - {
        "ping pong between actors" in run {
            case class Ping(replyTo: Subject[Pong])
            case class Pong(replyTo: Subject[Ping])

            for
                pongActor <- Actor.run {
                    Actor.receiveMax[Ping](3) { ping =>
                        Actor.self[Ping].map(self => ping.replyTo.send(Pong(self)))
                    }
                }
                pingActor <- Actor.run {
                    Var.runTuple(0) {
                        Actor.receiveMax[Pong](3) { pong =>
                            Var.update[Int](_ + 1).map { r =>
                                Actor.self[Pong].map(self => pongActor.send(Ping(self))).andThen(r)
                            }
                        }
                    }
                }
                _      <- pingActor.send(Pong(pongActor.subject))
                result <- pingActor.await
            yield assert(result == (3, ()))
            end for
        }

        "broadcast to multiple actors" in run {
            for
                results <- Queue.Unbounded.init[Int]()
                workers <-
                    Kyo.foreach(1 to 3) { id =>
                        Actor.run(Actor.receiveMax[Int](3)(msg => results.add(msg * id)))
                    }
                scheduler <- Actor.run {
                    Actor.receiveMax[Int](3) { msg =>
                        Async.foreach(workers)(_.send(msg))
                    }
                }
                _        <- scheduler.send(1)
                _        <- scheduler.send(2)
                _        <- scheduler.send(3)
                _        <- scheduler.await
                _        <- Async.foreach(workers)(_.await)
                received <- results.drain.map(_.sorted)
            yield assert(received == List(1, 2, 2, 3, 3, 4, 6, 6, 9))
        }

    }

    "error handling" - {
        case object TestError

        "propagates errors" in run {
            for
                actor <- Actor.run {
                    Actor.receiveAll[Int] { v =>
                        if v == 42 then Abort.fail(TestError)
                        else ()
                    }
                }
                _      <- actor.send(1)
                _      <- actor.send(42)
                result <- Abort.run(actor.await)
            yield assert(result == Result.fail(TestError))
        }
    }

    "actor hierarchy" - {
        "child actors are properly cleaned up when parent finishes" in run {
            for
                consumed         <- Latch.init(2)
                childActorStates <- Queue.Unbounded.init[String]()
                parentActor <- Actor.run {
                    for
                        childActor1 <- Actor.run {
                            for
                                _ <- Scope.ensure(childActorStates.add("child1 cleaned up"))
                                _ <- childActorStates.add("child1 started")
                            yield Actor.receiveAll[Int] { _ =>
                                childActorStates.add("child1 received message").andThen(consumed.release)
                            }
                        }
                        childActor2 <- Actor.run {
                            for
                                _ <- Scope.ensure(childActorStates.add("child2 cleaned up"))
                                _ <- childActorStates.add("child2 started")
                            yield Actor.receiveAll[Int] { _ =>
                                childActorStates.add("child2 received message").andThen(consumed.release)
                            }
                        }
                        _ <- childActor1.send(1)
                        _ <- childActor2.send(2)
                        _ <- consumed.await
                    yield "parent complete"
                }
                result <- parentActor.await
                _      <- untilTrue(childActorStates.size.map(_ == 6))
                events <- childActorStates.drain
            yield
                assert(result == "parent complete")
                assert(events.contains("child1 started"))
                assert(events.contains("child2 started"))
                assert(events.contains("child1 received message"))
                assert(events.contains("child2 received message"))
                assert(events.contains("child1 cleaned up"))
                assert(events.contains("child2 cleaned up"))
        }

        "child actors are cleaned up when parent fails" in run {
            case object ParentError

            for
                messageReceived <- Latch.init(1)
                childCleaned    <- AtomicBoolean.init(false)
                parentActorFiber <-
                    Actor.run {
                        for
                            childActor <- Actor.run {
                                for
                                    _ <- Scope.ensure(childCleaned.set(true))
                                yield Actor.receiveAll[Int] { _ =>
                                    messageReceived.release
                                }
                            }
                            _ <- childActor.send(1)
                            _ <- messageReceived.await
                            _ <- Abort.fail(ParentError)
                        yield "never reached"
                    }
                result <- Abort.run(parentActorFiber.await)
                _      <- untilTrue(childCleaned.get)
            yield assert(result.isFailure)
            end for
        }

        "parallel child actor creation and cleanup works correctly" in run {
            val actorCount = 50

            for
                allReceived    <- Latch.init(actorCount)
                startCounter   <- AtomicInt.init(0)
                cleanupCounter <- AtomicInt.init(0)
                parentActor <- Actor.run {
                    for
                        childActors <- Async.fill(actorCount) {
                            Actor.run {
                                for
                                    _ <- Scope.ensure(cleanupCounter.incrementAndGet)
                                    _ <- startCounter.incrementAndGet
                                yield Actor.receiveAll[Int] { _ =>
                                    allReceived.release
                                }
                            }
                        }
                        _ <- Async.foreach(childActors)(_.send(1))
                        _ <- allReceived.await
                    yield "parent done"
                }
                result     <- parentActor.await
                startCount <- startCounter.get
                _          <- untilTrue(cleanupCounter.get.map(_ == actorCount))
            yield
                assert(result == "parent done")
                assert(startCount == actorCount)
            end for
        }

        "multi-level hierarchy" in run {
            case class Message(value: Int, replyTo: Subject[Int])

            for
                results <- Queue.Unbounded.init[Int]()
                grandparent <- Actor.run {
                    for
                        parents <- Async.foreach(1 to 2) { parentId =>
                            Actor.run {
                                for
                                    children <- Async.foreach(1 to 2) { childId =>
                                        Actor.run {
                                            Actor.receiveMax[Message](3) { msg =>
                                                val result = msg.value * parentId * childId
                                                results.add(result).andThen {
                                                    msg.replyTo.send(result)
                                                }
                                            }
                                        }
                                    }
                                yield Actor.receiveMax[Message](3) { msg =>
                                    Async.foreach(children)(_.send(msg))
                                }
                            }
                        }
                    yield Actor.receiveMax[Message](3) { msg =>
                        Async.foreach(parents)(_.send(msg))
                    }
                }
                promise   <- Promise.init[Int, Any]
                _         <- grandparent.send(Message(5, Subject.init(promise)))
                _         <- promise.get
                _         <- untilTrue(results.size.map(_ == 4))
                processed <- results.drain
            yield assert(processed.toSet == Set(5 * 1 * 1, 5 * 1 * 2, 5 * 2 * 1, 5 * 2 * 2))
            end for
        }
    }

    "backpressure and capacity" - {
        "handles mailbox at capacity" in run {
            for
                counter <- AtomicInt.init(0)
                actor   <- Actor.run(100)(Actor.receiveMax[Int](150)(counter.addAndGet(_)))
                _       <- Async.foreach(1 to 150)(i => actor.send(i))
                _       <- actor.await
                sum     <- counter.get
            yield assert(sum == (1 to 150).sum)
        }

        "under concurrency" in run {
            for
                results  <- Queue.Unbounded.init[Int]()
                actor    <- Actor.run(50)(Actor.receiveMax[Int](1000)(results.add(_)))
                _        <- Async.fill(10)(Async.foreach(1 to 100)(i => actor.send(i)))
                _        <- actor.await
                received <- results.drain
            yield
                assert(received.size == 1000)
                assert(received.toSet == (1 to 100).toSet)
        }
    }

    "graceful shutdown" in run {
        for
            started   <- Latch.init(1)
            exit      <- Latch.init(1)
            processed <- AtomicBoolean.init
            actor <- Actor.run {
                Actor.receiveAll[Int] { msg =>
                    for
                        _ <- started.release
                        _ <- exit.await
                        _ <- processed.set(true)
                    yield ()
                }
            }
            _ <- actor.send(1)
            _ <- started.await
            _ <- actor.close
            _ <- exit.release
            _ <- untilTrue(processed.get)
        yield succeed
    }

    "resource management" - {
        "properly cleans up resources on normal completion" in run {
            for
                resourceCleaned <- AtomicBoolean.init(false)
                actor <- Actor.run {
                    Scope.ensure(resourceCleaned.set(true)).andThen {
                        Actor.receiveMax[Int](3) { _ => () }
                    }
                }
                _       <- actor.send(1)
                _       <- actor.send(2)
                _       <- actor.send(3)
                _       <- actor.await
                cleaned <- resourceCleaned.get
            yield assert(cleaned)
        }

        "cleans up resources on error" in run {
            case object TestError
            for
                resourceCleaned <- AtomicBoolean.init(false)
                actor <- Actor.run {
                    Scope.ensure(resourceCleaned.set(true)).andThen {
                        Actor.receiveMax[Int](1) { _ =>
                            Abort.fail(TestError)
                        }
                    }
                }
                _      <- actor.send(1)
                result <- Abort.run(actor.await)
                _      <- untilTrue(resourceCleaned.get)
            yield assert(result == Result.fail(TestError))
            end for
        }
    }

    "concurrency" - {
        "handles multiple senders" in run {
            for
                sum    <- AtomicInt.init(0)
                actor  <- Actor.run(Actor.receiveMax[Int](100)(sum.addAndGet(_).unit))
                _      <- Async.foreach(1 to 100)(i => actor.send(i))
                _      <- actor.await
                result <- sum.get
            yield assert(result == 5050)
        }

        "maintains message order" in run {
            for
                queue  <- Queue.Unbounded.init[Int]()
                actor  <- Actor.run(Actor.receiveMax[Int](100)(queue.add(_)))
                _      <- Kyo.foreach(1 to 100)(i => actor.send(i))
                _      <- actor.await
                result <- queue.drain
            yield assert(result == (1 to 100))
        }
    }

    "banking simulation" - {
        case class Account(id: Int, balance: Double)

        enum AccountMessage:
            case Deposit(amount: Double, replyTo: Subject[Double])
            case Withdraw(amount: Double, replyTo: Subject[Either[String, Double]])
            case GetBalance(replyTo: Subject[Double])
        end AccountMessage

        case class Transaction(accountId: Int, kind: String, amount: Double, balance: Double)

        "handles concurrent transactions correctly" in run {
            for
                loggedTransactions <- Queue.Unbounded.init[Transaction]()
                logger <- Actor.run {
                    Actor.receiveMax[Transaction](11) { tx =>
                        loggedTransactions.add(tx)
                    }
                }
                account <- Actor.run {
                    Var.run(Account(1, 0.0)) {
                        Actor.receiveMax[AccountMessage](14) {
                            case AccountMessage.Deposit(amount, replyTo) =>
                                for
                                    newBalance <- Var.update[Account](acc => acc.copy(balance = acc.balance + amount))
                                    _          <- logger.send(Transaction(1, "deposit", amount, newBalance.balance))
                                    _          <- replyTo.send(newBalance.balance)
                                yield ()

                            case AccountMessage.Withdraw(amount, replyTo) =>
                                Var.use[Account] { acc =>
                                    if acc.balance < amount then
                                        replyTo.send(Left("Insufficient funds"))
                                    else
                                        for
                                            newBalance <- Var.update[Account](a => a.copy(balance = a.balance - amount))
                                            _          <- logger.send(Transaction(1, "withdraw", amount, newBalance.balance))
                                            _          <- replyTo.send(Right(newBalance.balance))
                                        yield ()
                                }

                            case AccountMessage.GetBalance(replyTo) =>
                                Var.use[Account](acc => replyTo.send(acc.balance))
                        }
                    }
                }
                _        <- Async.fill(10)(account.ask(AccountMessage.Deposit(10.0, _)))
                balance1 <- account.ask(AccountMessage.GetBalance(_))
                result1  <- account.ask(AccountMessage.Withdraw(200.0, _))
                result2  <- account.ask(AccountMessage.Withdraw(50.0, _))
                balance2 <- account.ask(AccountMessage.GetBalance(_))
                _        <- account.await
                _        <- logger.await
                logs     <- loggedTransactions.drain
            yield
                assert(balance1 == 100.0)
                assert(result1 == Left("Insufficient funds"))
                assert(result2 == Right(50.0))
                assert(balance2 == 50.0)
                assert(logs.count(_.kind == "deposit") == 10)
                assert(logs.count(_.kind == "withdraw") == 1)
                assert(logs.last.balance == 50.0)
        }
    }

    "receiveLoop" - {
        "processes messages until done" in run {
            for
                sum <- AtomicInt.init(0)
                actor <- Actor.run {
                    Actor.receiveLoop[Int] { msg =>
                        if msg == 0 then Loop.done
                        else sum.addAndGet(msg).andThen(Loop.continue)
                    }
                }
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                _        <- actor.send(0)
                _        <- actor.await
                finalSum <- sum.get
            yield assert(finalSum == 6)
        }

        "can maintain state between iterations" in run {
            for
                results <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    Var.run(0) {
                        Actor.receiveLoop[String] { msg =>
                            if msg == "stop" then Loop.done
                            else
                                for
                                    count <- Var.update[Int](_ + 1)
                                    _     <- results.add(s"$msg-$count")
                                yield Loop.continue
                        }
                    }
                }
                _        <- actor.send("a")
                _        <- actor.send("b")
                _        <- actor.send("c")
                _        <- actor.send("stop")
                _        <- actor.await
                received <- results.drain
            yield assert(received == List("a-1", "b-2", "c-3"))
        }

        "can maintain single state value" in run {
            for
                actor <- Actor.run {
                    Actor.receiveLoop[Int](0) { (msg, sum) =>
                        if msg == 0 then Loop.done(sum)
                        else Loop.continue(sum + msg)
                    }
                }
                _      <- actor.send(10)
                _      <- actor.send(20)
                _      <- actor.send(30)
                _      <- actor.send(0)
                result <- actor.await
            yield assert(result == 60)
        }

        "can maintain two state values" in run {
            for
                actor <- Actor.run {
                    Actor.receiveLoop[String]("", 0) { (msg, str, count) =>
                        if msg == "stop" then Loop.done((str, count))
                        else Loop.continue(str + msg, count + 1)
                    }
                }
                _      <- actor.send("a")
                _      <- actor.send("b")
                _      <- actor.send("c")
                _      <- actor.send("stop")
                result <- actor.await
            yield assert(result == ("abc", 3))
        }

        "can maintain three state values" in run {
            for
                actor <- Actor.run {
                    Actor.receiveLoop[Int](0, 0, 1) { (msg, sum, count, product) =>
                        if msg == 0 then Loop.done((sum, count, product))
                        else Loop.continue(sum + msg, count + 1, product * msg)
                    }
                }
                _      <- actor.send(2)
                _      <- actor.send(3)
                _      <- actor.send(4)
                _      <- actor.send(0)
                result <- actor.await
            yield assert(result == (9, 3, 24))
        }

        "can maintain four state values" in run {
            for
                actor <- Actor.run {
                    Actor.receiveLoop[String]("", 0, 0, true) { (msg, str, length, wordCount, valid) =>
                        if msg == "stop" then Loop.done((str, length, wordCount, valid))
                        else if msg == "invalid" then Loop.continue(str, length, wordCount, false)
                        else if msg == " " then Loop.continue(str + msg, length + 1, wordCount + 1, valid)
                        else Loop.continue(str + msg, length + msg.length, wordCount, valid)
                    }
                }
                _      <- actor.send("Hello")
                _      <- actor.send(" ")
                _      <- actor.send("World")
                _      <- actor.send("invalid")
                _      <- actor.send("stop")
                result <- actor.await
            yield
                assert(result._1 == "Hello World")
                assert(result._2 == 11)
                assert(result._3 == 1)
                assert(result._4 == false)
        }
    }

    "multiple receive calls" - {

        "combines receiveMax and receiveAll" in run {
            for
                results <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    for
                        _ <- Actor.receiveMax[Int](2) { msg =>
                            results.add(s"receiveMax: $msg")
                        }
                        _ <- Actor.receiveAll[Int] { msg =>
                            results.add(s"receiveAll: $msg")
                        }
                    yield ()
                }
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                _        <- untilTrue(results.size.map(_ == 3))
                _        <- actor.close
                received <- results.drain
            yield assert(received == List("receiveMax: 1", "receiveMax: 2", "receiveAll: 3"))
        }

        "combines receiveLoop and receiveMax" in run {
            for
                results <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    for
                        sum <- Actor.receiveLoop[Int](0) { (msg, acc) =>
                            if msg == 0 then Loop.done(acc)
                            else Loop.continue(acc + msg)
                        }
                        _ <- Actor.receiveMax[Int](2) { msg =>
                            results.add(s"After loop: $msg (sum was $sum)")
                        }
                    yield sum
                }
                _      <- actor.send(10)
                _      <- actor.send(20)
                _      <- actor.send(0)
                _      <- actor.send(30)
                _      <- actor.send(40)
                result <- actor.await
                logs   <- results.drain
            yield
                assert(result == 30)
                assert(logs == List("After loop: 30 (sum was 30)", "After loop: 40 (sum was 30)"))
        }
    }

    "supervision" - {

        case object TemporaryError
        case object PermanentError

        case class TestMessage(v: Int, replyTo: Subject[Int])

        "Retry" in run {
            for
                attempts <- AtomicInt.init(0)
                actor <- Actor.run {
                    Retry[TemporaryError.type] {
                        attempts.incrementAndGet.map { count =>
                            Actor.receiveAll[TestMessage] { msg =>
                                msg.replyTo.send(msg.v + 1)
                                    .andThen(Abort.when(msg.v == 42)(TemporaryError))
                            }
                        }
                    }
                }
                v1    <- actor.ask(TestMessage(1, _))
                v2    <- actor.ask(TestMessage(42, _))
                v3    <- actor.ask(TestMessage(2, _))
                v4    <- actor.ask(TestMessage(3, _))
                v5    <- actor.ask(TestMessage(42, _))
                v6    <- actor.ask(TestMessage(4, _))
                count <- attempts.get
            yield assert(
                count == 3 && v1 == 2 && v2 == 43 && v3 == 3 && v4 == 4 && v5 == 43 && v6 == 5
            )
        }

        "Retry limit" in run {
            for
                attempts <- AtomicInt.init(0)
                actor <- Actor.run {
                    Retry[TemporaryError.type](Schedule.repeat(2)) {
                        attempts.incrementAndGet.map { count =>
                            Actor.receiveAll[TestMessage] { msg =>
                                msg.replyTo.send(msg.v + 1)
                                    .andThen(Abort.when(msg.v == 42)(TemporaryError))
                            }
                        }
                    }
                }
                v1     <- actor.ask(TestMessage(1, _))
                v2     <- actor.ask(TestMessage(42, _))
                v3     <- actor.ask(TestMessage(2, _))
                _      <- actor.ask(TestMessage(42, _))
                _      <- actor.ask(TestMessage(42, _))
                result <- Abort.run(actor.ask(TestMessage(3, _)))
                count  <- attempts.get
            yield assert(
                count == 3 && v1 == 2 && v2 == 43 && v3 == 3 && result.isFailure
            )
        }

        "Abort" in run {
            for
                events <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    Abort.recover[TemporaryError.type] { _ =>
                        events.add("Recovered from error").andThen {
                            Actor.receiveMax[Int](2) { msg =>
                                events.add(s"Processing $msg")
                            }
                        }
                    } {
                        Actor.receiveAll[Int] { msg =>
                            if msg < 0 then Abort.fail(TemporaryError)
                            else events.add(s"Received $msg")
                        }
                    }
                }
                _   <- actor.send(1)
                _   <- actor.send(-1)
                _   <- actor.send(2)
                _   <- actor.send(3)
                _   <- actor.await
                log <- events.drain
            yield assert(log == List(
                "Received 1",
                "Recovered from error",
                "Processing 2",
                "Processing 3"
            ))
        }

        "mixed" in run {
            for
                attempts <- AtomicInt.init(0)
                events   <- Queue.Unbounded.init[String]()
                actor <- Actor.run {
                    Abort.recover[PermanentError.type] { _ =>
                        events.add("Switched to fallback behavior").andThen {
                            Actor.receiveMax[Int](1) { msg =>
                                events.add(s"Fallback processing: $msg")
                            }
                        }
                    } {
                        Retry[TemporaryError.type](Schedule.repeat(2)) {
                            attempts.incrementAndGet.map { count =>
                                events.add(s"Attempt #$count").andThen {
                                    Actor.receiveAll[Int] { msg =>
                                        if msg == 0 then Abort.fail(PermanentError)
                                        else if msg < 0 then Abort.fail(TemporaryError)
                                        else events.add(s"Processing: $msg (attempt $count)")
                                    }
                                }
                            }
                        }
                    }
                }
                _     <- actor.send(1)
                _     <- actor.send(-1)
                _     <- actor.send(2)
                _     <- actor.send(-1)
                _     <- actor.send(3)
                _     <- actor.send(0)
                _     <- actor.send(4)
                _     <- actor.await
                count <- attempts.get
                log   <- events.drain
            yield
                assert(count == 3)
                assert(log == List(
                    "Attempt #1",
                    "Processing: 1 (attempt 1)",
                    "Attempt #2",
                    "Processing: 2 (attempt 2)",
                    "Attempt #3",
                    "Processing: 3 (attempt 3)",
                    "Switched to fallback behavior",
                    "Fallback processing: 4"
                ))
        }

    }

end ActorTest
