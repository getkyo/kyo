package kyo

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.compatible.Assertion

class ActorTest extends Test:

    "basic actor operations" - {
        "completes with final value" in run {
            for
                actor  <- Actor.run("a")
                result <- actor.result
            yield assert(result == "a")
        }

        "processes messages" in run {
            for
                sum      <- AtomicInt.init(0)
                actor    <- Actor.run(Actor.receiveMax[Int](3)(sum.addAndGet(_)))
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                _        <- actor.result
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
                finalSum <- actor.result
            yield assert(finalSum == 6)
        }

        "stops after processing N messages" in run {
            for
                counter <- AtomicInt.init(0)
                actor   <- Actor.run(Actor.receiveMax[Int](1)(_ => counter.incrementAndGet))
                _       <- actor.send(1)
                _       <- actor.result
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
                result <- pingActor.result
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
                _        <- scheduler.result
                _        <- Async.foreach(workers)(_.result)
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
                result <- Abort.run(actor.result)
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
                                _ <- Resource.ensure(childActorStates.add("child1 cleaned up"))
                                _ <- childActorStates.add("child1 started")
                            yield Actor.receiveAll[Int] { _ =>
                                childActorStates.add("child1 received message").andThen(consumed.release)
                            }
                        }
                        childActor2 <- Actor.run {
                            for
                                _ <- Resource.ensure(childActorStates.add("child2 cleaned up"))
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
                result <- parentActor.result
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

        "child actors are cleaned up when parent fails" in runNotJS {
            case object ParentError

            for
                messageReceived <- Latch.init(1)
                childCleaned    <- AtomicBoolean.init(false)
                parentActorFiber <-
                    Actor.run {
                        for
                            childActor <- Actor.run {
                                for
                                    _ <- Resource.ensure(childCleaned.set(true))
                                yield Actor.receiveAll[Int] { _ =>
                                    messageReceived.release
                                }
                            }
                            _ <- childActor.send(1)
                            _ <- messageReceived.await
                            _ <- Abort.fail(ParentError)
                        yield "never reached"
                    }
                result <- Abort.run(parentActorFiber.result)
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
                                    _ <- Resource.ensure(cleanupCounter.incrementAndGet)
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
                result     <- parentActor.result
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
                promise   <- Promise.init[Nothing, Int]
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
                _       <- actor.result
                sum     <- counter.get
            yield assert(sum == (1 to 150).sum)
        }

        "under concurrency" in run {
            for
                results  <- Queue.Unbounded.init[Int]()
                actor    <- Actor.run(50)(Actor.receiveMax[Int](1000)(results.add(_)))
                _        <- Async.fill(10)(Async.foreach(1 to 100)(i => actor.send(i)))
                _        <- actor.result
                received <- results.drain
            yield
                assert(received.size == 1000)
                assert(received.toSet == (1 to 100).toSet)
        }
    }

    "graceful shutdown" in runNotJS {
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
                    Resource.ensure(resourceCleaned.set(true)).andThen {
                        Actor.receiveMax[Int](3) { _ => () }
                    }
                }
                _       <- actor.send(1)
                _       <- actor.send(2)
                _       <- actor.send(3)
                _       <- actor.result
                cleaned <- resourceCleaned.get
            yield assert(cleaned)
        }

        "cleans up resources on error" in runNotJS {
            case object TestError
            for
                resourceCleaned <- AtomicBoolean.init(false)
                actor <- Actor.run {
                    Resource.ensure(resourceCleaned.set(true)).andThen {
                        Actor.receiveMax[Int](1) { _ =>
                            Abort.fail(TestError)
                        }
                    }
                }
                _       <- actor.send(1)
                result  <- Abort.run(actor.result)
                cleaned <- resourceCleaned.get
            yield assert(cleaned && result == Result.fail(TestError))
            end for
        }
    }

    "concurrency" - {
        "handles multiple senders" in run {
            for
                sum    <- AtomicInt.init(0)
                actor  <- Actor.run(Actor.receiveMax[Int](100)(sum.addAndGet(_).unit))
                _      <- Async.foreach(1 to 100)(i => actor.send(i))
                _      <- actor.result
                result <- sum.get
            yield assert(result == 5050)
        }

        "maintains message order" in run {
            for
                queue  <- Queue.Unbounded.init[Int]()
                actor  <- Actor.run(Actor.receiveMax[Int](100)(queue.add(_)))
                _      <- Kyo.foreach(1 to 100)(i => actor.send(i))
                _      <- actor.result
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
                _        <- Async.fill(10)(account.subject.ask(AccountMessage.Deposit(10.0, _)))
                balance1 <- account.subject.ask(AccountMessage.GetBalance(_))
                result1  <- account.subject.ask(AccountMessage.Withdraw(200.0, _))
                result2  <- account.subject.ask(AccountMessage.Withdraw(50.0, _))
                balance2 <- account.subject.ask(AccountMessage.GetBalance(_))
                _        <- account.result
                _        <- logger.result
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
                        else sum.addAndGet(msg).map(_ => Loop.continue)
                    }
                }
                _        <- actor.send(1)
                _        <- actor.send(2)
                _        <- actor.send(3)
                _        <- actor.send(0)
                _        <- actor.result
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
                _        <- actor.result
                received <- results.drain
            yield assert(received == List("a-1", "b-2", "c-3"))
        }

    }
end ActorTest
