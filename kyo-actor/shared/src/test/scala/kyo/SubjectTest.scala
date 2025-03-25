package kyo

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.compatible.Assertion

class SubjectTest extends Test:

    "Subject.noop" - {
        "discards messages sent via send" in run {
            val subject = Subject.noop[Int]
            for
                _ <- subject.send(1)
                _ <- subject.send(2)
                _ <- subject.send(3)
            yield succeed
            end for
        }

        "trySend always returns false" in run {
            val subject = Subject.noop[Int]
            for
                result <- subject.trySend(42)
            yield assert(!result)
        }
    }

    "Subject.init with Promise" - {
        "completes promise with message" in run {
            for
                promise <- Promise.init[Nothing, String]
                subject = Subject.init(promise)
                _      <- subject.send("test message")
                result <- promise.get
            yield assert(result == "test message")
        }

        "can only be completed once" in run {
            for
                promise <- Promise.init[Nothing, String]
                subject = Subject.init(promise)
                _      <- subject.send("first message")
                result <- Abort.run(subject.send("second message"))
            yield assert(result.isFailure)
        }
    }

    "Subject.init with Channel" - {
        "puts messages in the channel" in run {
            for
                channel <- Channel.init[Int](3)
                subject = Subject.init(channel)
                _      <- subject.send(1)
                _      <- subject.send(2)
                _      <- subject.send(3)
                values <- channel.drain
            yield assert(values == List(1, 2, 3))
        }

        "trySend returns true for unbounded channels" in run {
            for
                channel <- Channel.init[Int](1)
                subject = Subject.init(channel)
                result <- subject.trySend(42)
                value  <- channel.take
            yield assert(result && value == 42)
        }

        "trySend returns false for full bounded channels" in run {
            for
                channel <- Channel.init[Int](1)
                subject = Subject.init(channel)
                _      <- subject.send(1)
                result <- subject.trySend(2)
            yield assert(!result)
        }
    }

    "Subject.init with custom functions" - {
        "uses provided function for send" in run {
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

        "uses provided function for trySend" in run {
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
        "implements request-response pattern" in run {
            case class Request(data: String, replyTo: Subject[String])

            for
                promise <- Promise.init[Nothing, String]
                subject = Subject.init[Request](
                    send = req => req.replyTo.send(s"Response to: ${req.data}"),
                    trySend = req => req.replyTo.trySend(s"Response to: ${req.data}")
                )
                response <- subject.ask[String](Request("hello", _))
            yield assert(response == "Response to: hello")
            end for
        }

        "handles sequential requests" in run {
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

    "Multiple Subjects" - {
        "can coordinate between different subject implementations" in run {
            for
                results    <- Queue.Unbounded.init[String]()
                promiseSub <- Promise.init[Nothing, String]
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
