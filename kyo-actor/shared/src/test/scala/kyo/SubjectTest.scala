package kyo

import java.util.concurrent.atomic.AtomicInteger
import kyo.Actor.Subject

class SubjectTest extends kyo.test.Test[Any]:

    "Subject.noop" - {
        "discards messages sent via send" in {
            val subject = Subject.noop[Int]
            for
                _ <- subject.send(1)
                _ <- subject.send(2)
                _ <- subject.send(3)
            yield succeed("runs without error: noop subject discards messages without exception")
            end for
        }

        "trySend always returns false" in {
            val subject = Subject.noop[Int]
            for
                result <- subject.trySend(42)
            yield assert(!result)
        }
    }

    "Subject.init with Promise" - {
        "completes promise with message" in {
            for
                promise <- Promise.init[String, Any]
                subject = Subject.init(promise)
                _      <- subject.send("test message")
                result <- promise.get
            yield assert(result == "test message")
        }

        "can only be completed once" in {
            for
                promise <- Promise.init[String, Any]
                subject = Subject.init(promise)
                _      <- subject.send("first message")
                result <- Abort.run(subject.send("second message"))
            yield assert(result.isFailure)
        }
    }

    "Subject.init with Channel" - {
        "puts messages in the channel" in {
            for
                channel <- Channel.init[Int](3)
                subject = Subject.init(channel)
                _      <- subject.send(1)
                _      <- subject.send(2)
                _      <- subject.send(3)
                values <- channel.drain
            yield assert(values == List(1, 2, 3))
        }

        "trySend returns true for unbounded channels" in {
            for
                channel <- Channel.init[Int](1)
                subject = Subject.init(channel)
                result <- subject.trySend(42)
                value  <- channel.take
            yield assert(result && value == 42)
        }

        "trySend returns false for full bounded channels" in {
            for
                channel <- Channel.init[Int](1)
                subject = Subject.init(channel)
                _      <- subject.send(1)
                result <- subject.trySend(2)
            yield assert(!result)
        }
    }

    "Subject.init with custom functions" - {
        "uses provided function for send" in {
            for
                counter <- AtomicInt.init(0)
                subject = Subject.init[Int](
                    send = counter.addAndGet(_).unit,
                    trySend = v => ???
                )
                _   <- subject.send(1)
                _   <- subject.send(2)
                _   <- subject.send(3)
                sum <- counter.get
            yield assert(sum == 6)
        }

        "uses provided function for trySend" in {
            for
                counter <- AtomicInt.init(0)
                subject = Subject.init[Int](
                    send = _ => ???,
                    trySend = v => counter.addAndGet(v).map(_ => true)
                )
                result1 <- subject.trySend(5)
                result2 <- subject.trySend(10)
                sum     <- counter.get
            yield assert(result1 && result2 && sum == 15)
        }
    }

    "Subject.ask" - {
        "implements request-response pattern" in {
            case class Request(data: String, replyTo: Subject[String])

            for
                promise <- Promise.init[String, Any]
                subject = Subject.init[Request](
                    send = req => req.replyTo.send(s"Response to: ${req.data}"),
                    trySend = req => req.replyTo.trySend(s"Response to: ${req.data}")
                )
                response <- subject.ask[String](Request("hello", _))
            yield assert(response == "Response to: hello")
            end for
        }

        "handles sequential requests" in {
            case class Request(id: Int, replyTo: Subject[Int])
            val subject = Subject.init[Request](
                send = req => req.replyTo.send(req.id * 2),
                trySend = req => req.replyTo.trySend(req.id * 2)
            )
            for
                result1 <- subject.ask[Int](Request(5, _))
                result2 <- subject.ask[Int](Request(10, _))
                result3 <- subject.ask[Int](Request(15, _))
            yield assert(result1 == 10 && result2 == 20 && result3 == 30)
            end for
        }
    }

    "Subject.init with Queue.Unbounded" - {
        "adds messages to the queue" in {
            for
                queue <- Queue.Unbounded.init[Int]()
                subject = Subject.init(queue)
                _      <- subject.send(1)
                _      <- subject.send(2)
                _      <- subject.send(3)
                values <- queue.drain
            yield assert(values == Chunk(1, 2, 3))
        }

        "trySend always returns true" in {
            for
                queue <- Queue.Unbounded.init[Int]()
                subject = Subject.init(queue)
                result <- subject.trySend(42)
                values <- queue.drain
            yield assert(result && values == Chunk(42))
        }
    }

    "Subject.init with Hub" - {
        "publishes messages to hub listeners" in {
            for
                hub      <- Hub.init[Int]
                listener <- hub.listen
                subject = Subject.init(hub)
                _ <- subject.send(1)
                _ <- subject.send(2)
                a <- listener.take
                b <- listener.take
            yield assert(a == 1 && b == 2)
        }
        "trySend offers without blocking and returns true when accepted" in {
            for
                hub      <- Hub.init[Int]
                listener <- hub.listen
                subject = Subject.init(hub)
                accepted <- subject.trySend(1)
                v        <- listener.take
            yield assert(accepted && v == 1)
        }
        "trySend fails with Closed after the hub is closed" in {
            for
                hub <- Hub.init[Int]
                subject = Subject.init(hub)
                _      <- hub.close
                result <- Abort.run[Closed](subject.trySend(1))
            yield assert(result.isFailure)
        }
        "send fails with Closed after the hub is closed" in {
            for
                hub <- Hub.init[Int]
                subject = Subject.init(hub)
                _      <- hub.close
                result <- Abort.run[Closed](subject.send(1))
            yield assert(result.isFailure)
        }
    }

    "Subject.contramap" - {
        "adapts the message type via the mapping function" in {
            for
                chan <- Channel.init[Int](4)
                base    = Subject.init(chan)
                strings = base.contramap[String](_.length)
                _ <- strings.send("hello")
                v <- chan.take
            yield assert(v == 5)
        }
        "composes with an actor subject" in {
            for
                sum   <- AtomicInt.init(0)
                actor <- Actor.run(Actor.receiveMax[Int](1)(sum.addAndGet(_).unit))
                sink = actor.subject.contramap[String](_.length)
                _ <- sink.send("abcd")
                _ <- actor.await
                v <- sum.get
            yield assert(v == 4)
        }
        "adapts trySend as well as send" in {
            for
                chan <- Channel.init[Int](4)
                strings = Subject.init(chan).contramap[String](_.length)
                accepted <- strings.trySend("hey")
                v        <- chan.take
            yield assert(accepted && v == 3)
        }
    }

    "Multiple Subjects" - {
        "can coordinate between different subject implementations" in {
            for
                results    <- Queue.Unbounded.init[String]()
                promiseSub <- Promise.init[String, Any]
                promise = Subject.init(promiseSub)
                channel <- Channel.init[String](100)
                channelSub = Subject.init(channel)
                customSub = Subject.init[String](
                    send = msg => results.add(s"Custom: $msg"),
                    trySend = msg => results.add(s"TryCustom: $msg").map(_ => true)
                )
                _          <- promise.send("promise-message")
                _          <- channelSub.send("channel-message")
                _          <- customSub.send("custom-message")
                promiseMsg <- promiseSub.get
                channelMsg <- channel.take
                allResults <- results.drain
            yield
                assert(promiseMsg == "promise-message")
                assert(channelMsg == "channel-message")
                assert(allResults.contains("Custom: custom-message"))
        }

    }

end SubjectTest
