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
    }
end PubSubTest
