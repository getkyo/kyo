package kyo

import kyo.Actor.Subject

class TopicTest extends kyo.test.Test[Any]:

    "Topic.init" - {
        "fans out a published value to all subscribers" in {
            for
                topic <- Topic.init[Int]
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
                topic <- Topic.init[Int]
                a     <- Channel.init[Int](4)
                _     <- topic.subscribe(Subject.init(a))
                n     <- topic.subscriberCount
            yield assert(n == 1)
        }
        "prunes a subscriber whose send fails with Closed" in {
            for
                topic <- Topic.init[Int]
                chan  <- Channel.init[Int](4)
                _     <- topic.subscribe(Subject.init(chan))
                _     <- chan.close
                _     <- topic.publish(1)
                n     <- topic.subscriberCount
            yield assert(n == 0)
        }
        "removes a subscriber when its scope closes" in {
            for
                topic <- Topic.init[Int]
                chan  <- Channel.init[Int](4)
                _     <- Scope.run(topic.subscribe(Subject.init(chan)))
                n     <- topic.subscriberCount
            yield assert(n == 0)
        }
        "delivers to live subscribers even when a dead one is pruned in the same publish" in {
            for
                topic <- Topic.init[Int]
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
                topic  <- Topic.init[Int]
                _      <- topic.close
                result <- Abort.run[Closed](topic.publish(1))
            yield assert(result.isFailure)
        }
    }
end TopicTest
