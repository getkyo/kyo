package kyo

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.compatible.Assertion

class ActorTest extends Test:

    "basic actor operations" - {
        "completes with final value" in run {
            for
                actor  <- Actor.run(42)
                result <- actor.result
            yield assert(result == 42)
        }

        "processes messages" in run {
            for
                sum      <- AtomicInt.init(0)
                actor    <- Actor.run(Poll[Int](3)(sum.addAndGet(_).unit))
                _        <- actor.subject.send(1)
                _        <- actor.subject.send(2)
                _        <- actor.subject.send(3)
                _        <- actor.result
                finalSum <- sum.get
            yield assert(finalSum == 6)
        }

        "processes messages and returns value" in run {
            for
                sum      <- AtomicInt.init(0)
                actor    <- Actor.run(Poll[Int](3)(sum.addAndGet(_).unit).andThen(sum.get))
                _        <- actor.subject.send(1)
                _        <- actor.subject.send(2)
                _        <- actor.subject.send(3)
                finalSum <- actor.result
            yield assert(finalSum == 6)
        }

        "stops after processing N messages" in run {
            for
                counter <- AtomicInt.init(0)
                actor   <- Actor.run(Poll[Int](1)(_ => counter.incrementAndGet.unit))
                _       <- actor.subject.send(1)
                _       <- actor.result
                result  <- Abort.run(actor.subject.send(1))
                count   <- counter.get
            yield assert(result.isFail && count == 1)
        }
    }

    "inter-actor communication" - {
        "ping pong between actors" in run {
            case class Ping(replyTo: Subject[Pong])
            case class Pong(replyTo: Subject[Ping])

            for
                pongActor <- Actor.run {
                    Poll[Ping] { ping =>
                        Actor.self[Ping].map(self => ping.replyTo.send(Pong(self)))
                    }
                }
                pingActor <- Actor.run {
                    Var.runTuple(0) {
                        Poll[Pong](3) { pong =>
                            Var.update[Int](_ + 1).andThen(
                                Actor.self[Pong].map(self => pongActor.subject.send(Ping(self)))
                            )
                        }
                    }
                }
                _      <- pingActor.subject.send(Pong(pongActor.subject))
                result <- pingActor.result
            yield assert(result == (3, ()))
            end for
        }

        "broadcast to multiple actors" in run {
            for
                results <- Queue.Unbounded.init[Int]()
                workers <-
                    Kyo.collect((1 to 3).map { id =>
                        Actor.run(Poll[Int](3)(msg => results.add(msg * id)))
                    })
                scheduler <- Actor.run {
                    Poll[Int](3) { msg =>
                        Async.parallelUnbounded(workers.map(_.subject.send(msg))).unit
                    }
                }
                _        <- scheduler.subject.send(1)
                _        <- scheduler.subject.send(2)
                _        <- scheduler.subject.send(3)
                _        <- scheduler.result
                _        <- Async.parallelUnbounded(workers.map(_.result))
                received <- results.drain.map(_.sorted)
            yield assert(received == List(1, 2, 2, 3, 3, 4, 6, 6, 9))
        }

    }

    "error handling" - {
        case object TestError

        "propagates errors" in run {
            for
                actor <- Actor.run {
                    Poll[Int] { v =>
                        if v == 42 then Abort.fail(TestError)
                        else ()
                    }
                }
                _      <- actor.subject.send(1)
                _      <- actor.subject.send(42)
                result <- Abort.run(actor.result)
            yield assert(result == Result.fail(TestError))
        }

    }

    "concurrency" - {
        "handles multiple senders" in run {
            for
                sum    <- AtomicInt.init(0)
                actor  <- Actor.run(Poll[Int](100)(sum.addAndGet(_).unit))
                _      <- Async.parallelUnbounded((1 to 100).map(i => actor.subject.send(i)))
                _      <- actor.result
                result <- sum.get
            yield assert(result == 5050)
        }

        "maintains message order from same sender" in run {
            for
                queue  <- Queue.Unbounded.init[Int]()
                actor  <- Actor.run(Poll[Int](100)(queue.add(_)))
                _      <- Kyo.foreach(1 to 100)(i => actor.subject.send(i))
                _      <- actor.result
                result <- queue.drain
            yield assert(result == (1 to 100))
        }
    }
end ActorTest
