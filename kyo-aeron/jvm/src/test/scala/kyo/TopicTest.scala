package kyo

import java.net.DatagramSocket
import java.net.InetSocketAddress

class TopicTest extends kyo.test.Test[Any]:

    case class Message(value: Int) derives CanEqual, Topic.AsMessage
    case class GenericMessage[A](value: A) derives CanEqual, Topic.AsMessage
    case class ComplexMessage(id: Int, items: List[String]) derives CanEqual, Topic.AsMessage

    val failSchedule = Schedule.fixed(1.millis).take(3)

    def freePort() =
        val socket = new DatagramSocket(null)
        try
            socket.bind(new InetSocketAddress("localhost", 0))
            socket.getLocalPort()
        finally
            socket.close()
        end try
    end freePort

    Seq(
        "aeron:ipc",
        s"aeron:udp?endpoint=127.0.0.1:${freePort()}"
    ).foreach { uri =>
        s"with uri $uri" - {

            "basic publish/subscribe" - {
                "single message type" in {
                    val messages = Seq(Message(1), Message(2), Message(3))
                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber <-
                                Fiber.initUnscoped(using Topic.isolate)(
                                    started.release.andThen(Topic.stream[Message](uri).take(messages.size).run)
                                )
                            _        <- started.await
                            _        <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages)))
                            received <- fiber.get
                        yield assert(received == messages)
                    }
                }

                "multiple message types" in {
                    val strings = Seq("hello", "world")
                    val ints    = Seq(42, 43)
                    Topic.run {
                        for
                            started       <- Latch.init(2)
                            stringFiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[String](uri).take(strings.size).run))
                            intFiber      <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Int](uri).take(ints.size).run))
                            _             <- started.await
                            _             <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(strings)))
                            _             <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(ints)))
                            stringResults <- stringFiber.get
                            intResults    <- intFiber.get
                        yield
                            assert(stringResults == strings)
                            assert(intResults == ints)
                    }
                }

                "generic message types" in {
                    val strMessages = Seq(GenericMessage("hello"), GenericMessage("world"))
                    val intMessages = Seq(GenericMessage(1), GenericMessage(2))

                    Topic.run {
                        for
                            started <- Latch.init(2)
                            strFiber <-
                                Fiber.initUnscoped(
                                    started.release.andThen(Topic.stream[GenericMessage[String]](uri).take(strMessages.size).run)
                                )
                            intFiber <-
                                Fiber.initUnscoped(
                                    started.release.andThen(Topic.stream[GenericMessage[Int]](uri).take(intMessages.size).run)
                                )
                            _          <- started.await
                            _          <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(strMessages)))
                            _          <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(intMessages)))
                            strResults <- strFiber.get
                            intResults <- intFiber.get
                        yield
                            assert(strResults == strMessages)
                            assert(intResults == intMessages)
                    }
                }

                "complex message types" in {
                    val messages = Seq(
                        ComplexMessage(1, List("a", "b")),
                        ComplexMessage(2, List("c", "d"))
                    )
                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber <- Fiber.initUnscoped(started.release.andThen(Topic.stream[ComplexMessage](uri).take(messages.size).run))
                            _     <- started.await
                            _     <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages)))
                            received <- fiber.get
                        yield assert(received == messages)
                    }
                }
            }

            "multiple subscribers" - {
                "fan-out to multiple subscribers" in {
                    val messages = Seq(Message(1), Message(2), Message(3))

                    // A subscriber fiber starting does not mean its Aeron image is connected to the publication,
                    // and Aeron is a best-effort transport with no redelivery: a publish issued before a
                    // subscriber's image has connected is silently lost to it. So the publisher republishes the
                    // whole stream in a loop until every subscriber has connected and received. `Stream.init`
                    // emits a single chunk and each `.take(n)` completes on the first chunk it receives, so a
                    // republish that arrives after a subscriber is done cannot duplicate or reorder its result;
                    // each consumer's `.take(n)` completing is the readiness witness. The `Async.delay` only
                    // paces the retries (a best-effort-transport retry backoff), it is not a readiness sleep.
                    Topic.run {
                        for
                            fiber1 <- Fiber.initUnscoped(Topic.stream[Message](uri).take(messages.size).run)
                            fiber2 <- Fiber.initUnscoped(Topic.stream[Message](uri).take(messages.size).run)
                            publisher <- Fiber.initUnscoped(Loop.forever(
                                Abort.run(Topic.publish(uri)(Stream.init(messages))).andThen(Async.delay(5.millis)(()))
                            ))
                            result1 <- fiber1.get
                            result2 <- fiber2.get
                            _       <- publisher.interrupt
                        yield
                            assert(result1 == messages)
                            assert(result2 == messages)
                    }
                }

                "subscribers with different consumption rates" in {
                    val messages = Seq(Message(1), Message(2))

                    // See "fan-out to multiple subscribers": the publisher republishes in a loop until every
                    // subscriber has connected and received, paced by a best-effort-transport retry backoff.
                    // The `Async.delay(1.millis)` inside `slowFiber` is the test's own slow-consumer model and is
                    // separate from that retry backoff.
                    Topic.run {
                        for
                            slowFiber <-
                                Fiber.initUnscoped(
                                    Topic.stream[Message](uri)
                                        .map(r => Async.delay(1.millis)(r))
                                        .take(messages.size)
                                        .run
                                )
                            fastFiber <-
                                Fiber.initUnscoped(
                                    Topic.stream[Message](uri)
                                        .take(messages.size)
                                        .run
                                )
                            publisher <- Fiber.initUnscoped(Loop.forever(
                                Abort.run(Topic.publish(uri)(Stream.init(messages))).andThen(Async.delay(5.millis)(()))
                            ))
                            slow <- slowFiber.get
                            fast <- fastFiber.get
                            _    <- publisher.interrupt
                        yield
                            assert(slow == messages)
                            assert(fast == messages)
                    }
                }
            }

            "subtyping" - {
                sealed trait Base derives Topic.AsMessage
                case class Derived1(value: Int)    extends Base derives CanEqual, Topic.AsMessage
                case class Derived2(value: String) extends Base derives CanEqual, Topic.AsMessage

                "base type cannot receive subtypes" in {
                    val messages = Seq(Derived1(1), Derived1(2))

                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Base](uri, failSchedule).run))
                            _       <- started.await
                            result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(messages)))
                            result2 <- fiber.getResult
                        yield assert(result1.isFailure && result2.isFailure)
                    }
                }

                "subtype cannot receive base type" in {
                    val messages = Seq[Base](Derived1(1), Derived1(2))

                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Derived1](uri, failSchedule).run))
                            _       <- started.await
                            result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(messages)))
                            result2 <- fiber.getResult
                        yield assert(result1.isFailure && result2.isFailure)
                    }
                }

                "generic base type cannot receive generic subtype" in {
                    sealed trait GenericBase[A] derives Topic.AsMessage
                    case class GenericDerived[A](value: A) extends GenericBase[A] derives CanEqual, Topic.AsMessage

                    val messages = Seq(GenericDerived("test"))

                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[GenericBase[String]](uri, failSchedule).run))
                            _       <- started.await
                            result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(messages)))
                            result2 <- fiber.getResult
                        yield assert(result1.isFailure && result2.isFailure)
                    }
                }

                "different subtypes maintain separation" in {
                    val messages1 = Seq(Derived1(1), Derived1(2))
                    val messages2 = Seq(Derived2("a"), Derived2("b"))

                    Topic.run {
                        for
                            started  <- Latch.init(2)
                            fiber1   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Derived1](uri).take(messages1.size).run))
                            fiber2   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Derived2](uri).take(messages2.size).run))
                            _        <- started.await
                            _        <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages1)))
                            _        <- Fiber.initUnscoped(Topic.publish(uri)(Stream.init(messages2)))
                            results1 <- fiber1.get
                            results2 <- fiber2.get
                        yield
                            assert(results1 == messages1)
                            assert(results2 == messages2)
                    }
                }
            }

            "maintains message order for large batches (pendingUntilFixed)".pendingUntilFixed(
                "large-batch publish currently panics; message ordering is not yet verifiable"
            ) in {
                val count    = 200
                val messages = Seq.tabulate(count)(Message(_))
                Topic.run {
                    for
                        started <- Latch.init(1)
                        fiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Message](uri).take(count).run))
                        _       <- started.await
                        result  <- Abort.run(Topic.publish[Message](uri)(Stream.init(messages)))
                    yield
                        // pendingUntilFixed: today the publish panics, so this assert fails -> the leaf reports Pending;
                        // once the panic is fixed the assert passes -> Failed, the tripwire to remove the marker.
                        assert(!result.isPanic, "large-batch publish should not panic once ordering is fixed")
                }
            }

            "handles empty streams" in {
                Topic.run {
                    for
                        started <- Latch.init(1)
                        fiber   <- Fiber.initUnscoped(started.release.andThen(Topic.stream[Message](uri, failSchedule).take(1).run))
                        _       <- started.await
                        _       <- Topic.publish[Message](uri, failSchedule)(Stream.empty)
                        result  <- fiber.getResult
                    yield assert(result.isFailure)
                }
            }

            "partial subscriber failure" in {
                val messageCount = 10
                val messages     = (0 until messageCount).map(Message(_))

                // See "fan-out to multiple subscribers": a subscriber fiber starting does not mean its Aeron
                // image is connected, and Aeron is best-effort with no redelivery, so the publisher republishes
                // the whole stream in a loop until both subscribers have connected and received. The failing
                // subscriber aborts on its first received message and the normal one completes on the first
                // chunk, so a later republish cannot change either result; resolution of both fibers is the
                // readiness witness. The `Async.delay` only paces the retries (a best-effort-transport retry
                // backoff), it is not a readiness sleep.
                Topic.run {
                    for
                        failingFiber <- Fiber.initUnscoped(
                            Topic.stream[Message](uri)
                                .map(_ => Abort.fail("Planned failure"): Message < Abort[String])
                                .take(messageCount)
                                .run
                        )
                        normalFiber <- Fiber.initUnscoped(
                            Topic.stream[Message](uri)
                                .take(messageCount)
                                .run
                        )
                        publisher <- Fiber.initUnscoped(Loop.forever(
                            Abort.run(Topic.publish(uri)(Stream.init(messages))).andThen(Async.delay(5.millis)(()))
                        ))
                        failingResult <- failingFiber.getResult
                        normalResult  <- normalFiber.get
                        _             <- publisher.interrupt
                    yield
                        assert(failingResult.isFailure)
                        assert(normalResult == messages)
                }
            }

            "isolation" - {
                "publisher without subscribers" in {
                    Topic.run {
                        for
                            result <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(Seq(Message(1)))))
                        yield assert(result.isFailure)
                    }
                }

                "subscriber without publisher" in {
                    Topic.run {
                        for
                            fiber  <- Fiber.initUnscoped(Topic.stream[Message](uri, failSchedule).take(1).run)
                            result <- fiber.getResult
                        yield assert(result.isFailure)
                    }
                }

                "subscriber starts after publisher" in {
                    Topic.run {
                        for
                            started <- Latch.init(1)
                            done    <- Latch.init(1)
                            result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(Seq(Message(1)))))
                            result2 <- Abort.run(Topic.stream[Message](uri, failSchedule).take(1).run)
                        yield
                            assert(result1.isFailure)
                            assert(result2.isFailure)
                    }
                }
            }
        }
    }

end TopicTest
