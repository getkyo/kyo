package kyo

import kyo.Actor.Subject

class PubSubTest extends kyo.test.Test[Any]:

    "PubSub.init" - {
        "fans out a published value to all subscribers" in {
            for
                topic <- PubSub.init[Int]
                a     <- Channel.init[Int](4)
                b     <- Channel.init[Int](4)
                _     <- topic.subscribe(Subject.init(a))
                _     <- topic.subscribe(Subject.init(b))
                _     <- topic.publish(7)
                va    <- a.take
                vb    <- b.take
            yield assert(va == 7 && vb == 7)
        }
        "reports the subscriber count" in {
            for
                topic <- PubSub.init[Int]
                a     <- Channel.init[Int](4)
                _     <- topic.subscribe(Subject.init(a))
                n     <- topic.subscriberCount
            yield assert(n == 1)
        }
        "prunes a subscriber whose send fails with Closed" in {
            for
                topic <- PubSub.init[Int]
                chan  <- Channel.init[Int](4)
                _     <- topic.subscribe(Subject.init(chan))
                _     <- chan.close
                _     <- topic.publish(1)
                n     <- topic.subscriberCount
            yield assert(n == 0)
        }
        "removes a subscriber when its scope closes" in {
            for
                topic <- PubSub.init[Int]
                chan  <- Channel.init[Int](4)
                _     <- Scope.run(topic.subscribe(Subject.init(chan)))
                n     <- topic.subscriberCount
            yield assert(n == 0)
        }
        // The add commits to the shared set, so a removal registered in a later step is separable from it by an
        // interrupt, and the subscriber then stays published to for good. Unlike the linearized case there is no
        // join to aim at: the window is a single preemption point between two steps of one fiber. The interrupt is
        // requested with nothing ordering it against the subscribe, so across rounds it lands on both sides of that
        // point and on it.
        "a subscriber interrupted as it joins the set is not left there" in {
            val rounds = 200
            Loop.indexed { i =>
                if i >= rounds then Loop.done
                else
                    for
                        topic <- PubSub.init[Int]
                        chan  <- Channel.init[Int](4)
                        fiber <- Fiber.initUnscoped(Scope.run(topic.subscribe(Subject.init(chan)).andThen(Async.never)))
                        _     <- fiber.interrupt
                        _     <- fiber.getResult
                        // Retried rather than read once: an interrupt spawns the scope's drain and does not wait for
                        // it, so the fiber's result is available before the removal has run. A registered removal
                        // arrives; one that was never registered never does, and the leaf timeout is what says so.
                        _ <- assertEventually(topic.subscriberCount.map(_ == 0))
                    yield Loop.continue
            }
        }
        "delivers to live subscribers even when a dead one is pruned in the same publish" in {
            for
                topic <- PubSub.init[Int]
                dead  <- Channel.init[Int](4)
                live  <- Channel.init[Int](4)
                _     <- topic.subscribe(Subject.init(dead))
                _     <- topic.subscribe(Subject.init(live))
                _     <- dead.close
                _     <- topic.publish(9)
                v     <- live.take
                n     <- topic.subscriberCount
            yield assert(v == 9 && n == 1)
        }
        "publish after close fails with Closed" in {
            for
                topic  <- PubSub.init[Int]
                _      <- topic.close
                result <- Abort.run[Closed](topic.publish(1))
            yield assert(result.isFailure)
        }
        "with concurrency 1 delivers to all subscribers" in {
            for
                topic <- PubSub.init[Int](1)
                a     <- Channel.init[Int](4)
                b     <- Channel.init[Int](4)
                c     <- Channel.init[Int](4)
                _     <- topic.subscribe(Subject.init(a))
                _     <- topic.subscribe(Subject.init(b))
                _     <- topic.subscribe(Subject.init(c))
                _     <- topic.publish(7)
                va    <- a.take
                vb    <- b.take
                vc    <- c.take
            yield assert(va == 7 && vb == 7 && vc == 7)
        }
        "with concurrency 2 delivers to all subscribers" in {
            for
                topic <- PubSub.init[Int](2)
                a     <- Channel.init[Int](4)
                b     <- Channel.init[Int](4)
                c     <- Channel.init[Int](4)
                d     <- Channel.init[Int](4)
                _     <- topic.subscribe(Subject.init(a))
                _     <- topic.subscribe(Subject.init(b))
                _     <- topic.subscribe(Subject.init(c))
                _     <- topic.subscribe(Subject.init(d))
                _     <- topic.publish(7)
                va    <- a.take
                vb    <- b.take
                vc    <- c.take
                vd    <- d.take
            yield assert(va == 7 && vb == 7 && vc == 7 && vd == 7)
        }
        "rejects a concurrency below 1" in {
            Abort.run[IllegalArgumentException](Sync.defer(PubSub.init[Int](0)).map(_ => ())).map { result =>
                assert(result.failure.exists(_.getMessage.contains("concurrency must be >= 1")))
            }
        }
    }

    "PubSub.linearized" - {
        "fans out to all subscribers" in {
            for
                topic <- PubSub.linearized[Int]
                a     <- Channel.init[Int](16)
                b     <- Channel.init[Int](16)
                _     <- topic.subscribe(Subject.init(a))
                _     <- topic.subscribe(Subject.init(b))
                _     <- topic.publish(7)
                va    <- a.take
                vb    <- b.take
            yield assert(va == 7 && vb == 7)
        }
        "delivers the same order to all subscribers under concurrent publishers" in {
            for
                topic <- PubSub.linearized[Int]
                a     <- Channel.init[Int](1024)
                b     <- Channel.init[Int](1024)
                _     <- topic.subscribe(Subject.init(a))
                _     <- topic.subscribe(Subject.init(b))
                _     <- Async.foreach(1 to 100)(topic.publish)
                // drainUpTo(100) is safe: each actor.ask-backed publish only completes after the actor commits
                // the value to every subscriber, so once Async.foreach returns all 100 are present in both channels.
                as <- a.drainUpTo(100)
                bs <- b.drainUpTo(100)
            yield assert(as == bs && as.size == 100)
        }
        "an actor subscribes via contramap and also takes direct sends through one mailbox" in {
            for
                topic <- PubSub.linearized[Int]
                seen  <- Queue.Unbounded.init[String]()
                actor <- Actor.run(Actor.receiveMax[String](2)(seen.add(_)))
                _     <- topic.subscribe(actor.subject.contramap[Int](i => s"event:$i"))
                _     <- topic.publish(1)
                _     <- actor.subject.send("direct:2")
                _     <- actor.await
                got   <- seen.drain
            yield assert(got.toSet == Set("event:1", "direct:2"))
        }
        "removes a subscriber when its scope closes" in {
            for
                topic <- PubSub.linearized[Int]
                chan  <- Channel.init[Int](4)
                _     <- Scope.run(topic.subscribe(Subject.init(chan)))
                n     <- topic.subscriberCount
            yield assert(n == 0)
        }

        // The subscribe reply is a join: the actor has added the subscriber by the time it answers, and the
        // unsubscribe is registered only when the subscriber's fiber resumes. An interrupt landing between the two
        // would abandon that continuation and leave the subscriber in the set, where every later publish waits on a
        // mailbox nobody drains. The interrupt here is requested as soon as the actor reports the subscriber, so the
        // rounds sample that window.
        "a subscriber interrupted at the subscribe reply is not left in the set" in {
            val rounds = 200
            Loop.indexed { i =>
                if i >= rounds then Loop.done
                else
                    for
                        topic <- PubSub.linearized[Int]
                        chan  <- Channel.init[Int](4)
                        fiber <- Fiber.initUnscoped(Scope.run(topic.subscribe(Subject.init(chan)).andThen(Async.never)))
                        _     <- assertEventually(topic.subscriberCount.map(_ == 1))
                        _     <- fiber.interrupt
                        _     <- fiber.getResult
                        // Retried rather than read once, for the reason given on the init leaf: the interrupt spawns
                        // the scope's drain without waiting for it. Reading once happens to pass here only because
                        // the count is an actor round trip, which is usually long enough for the drain to have landed.
                        _ <- assertEventually(topic.subscriberCount.map(_ == 0))
                    yield Loop.continue
            }
        }
        "publish after close fails with Closed" in {
            for
                topic  <- PubSub.linearized[Int]
                _      <- topic.close
                result <- Abort.run[Closed](topic.publish(1))
            yield assert(result.isFailure)
        }
        "does not strand a caller when the topic closes with a queued publish" in {
            // Abort.run[Closed] (not [Closed | Timeout]): a genuine strand hangs past the 2s timeout and the
            // uncaught Timeout fails the leaf. A completed call (success or Closed) satisfies isSuccess || isFailure.
            for
                topic  <- PubSub.linearized[Int]
                fiber  <- Fiber.initUnscoped(topic.publish(1))
                _      <- topic.close
                result <- Abort.run[Closed](Async.timeout(2.seconds)(fiber.get))
            yield assert(result.isSuccess || result.isFailure)
        }
        "does not strand a subscriberCount caller when the topic closes" in {
            for
                topic  <- PubSub.linearized[Int]
                fiber  <- Fiber.initUnscoped(topic.subscriberCount)
                _      <- topic.close
                result <- Abort.run[Closed](Async.timeout(2.seconds)(fiber.get))
            yield assert(result.isSuccess || result.isFailure)
        }
        "with concurrency 1 delivers to all subscribers" in {
            for
                topic <- PubSub.linearized[Int](1)
                a     <- Channel.init[Int](16)
                b     <- Channel.init[Int](16)
                c     <- Channel.init[Int](16)
                _     <- topic.subscribe(Subject.init(a))
                _     <- topic.subscribe(Subject.init(b))
                _     <- topic.subscribe(Subject.init(c))
                _     <- topic.publish(7)
                va    <- a.take
                vb    <- b.take
                vc    <- c.take
            yield assert(va == 7 && vb == 7 && vc == 7)
        }
        "with concurrency 2 delivers to all subscribers" in {
            for
                topic <- PubSub.linearized[Int](2)
                a     <- Channel.init[Int](16)
                b     <- Channel.init[Int](16)
                c     <- Channel.init[Int](16)
                d     <- Channel.init[Int](16)
                _     <- topic.subscribe(Subject.init(a))
                _     <- topic.subscribe(Subject.init(b))
                _     <- topic.subscribe(Subject.init(c))
                _     <- topic.subscribe(Subject.init(d))
                _     <- topic.publish(7)
                va    <- a.take
                vb    <- b.take
                vc    <- c.take
                vd    <- d.take
            yield assert(va == 7 && vb == 7 && vc == 7 && vd == 7)
        }
        "rejects a concurrency below 1" in {
            Abort.run[IllegalArgumentException](Sync.defer(PubSub.linearized[Int](0)).map(_ => ())).map { result =>
                assert(result.failure.exists(_.getMessage.contains("concurrency must be >= 1")))
            }
        }
    }
end PubSubTest
