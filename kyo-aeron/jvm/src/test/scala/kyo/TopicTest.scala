package kyo

import java.net.DatagramSocket
import java.net.InetSocketAddress

class TopicTest extends Test:

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
                "single message type" in run {
                    val messages = Seq(Message(1), Message(2), Message(3))
                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber <-
                                Async.run(using Topic.isolate)(started.release.andThen(Topic.stream[Message](uri).take(messages.size).run))
                            _        <- started.await
                            _        <- Async.run(Topic.publish(uri)(Stream.init(messages)))
                            received <- fiber.get
                        yield assert(received == messages)
                    }
                }

                "multiple message types" in run {
                    val strings = Seq("hello", "world")
                    val ints    = Seq(42, 43)
                    Topic.run {
                        for
                            started       <- Latch.init(2)
                            stringFiber   <- Async.run(started.release.andThen(Topic.stream[String](uri).take(strings.size).run))
                            intFiber      <- Async.run(started.release.andThen(Topic.stream[Int](uri).take(ints.size).run))
                            _             <- started.await
                            _             <- Async.run(Topic.publish(uri)(Stream.init(strings)))
                            _             <- Async.run(Topic.publish(uri)(Stream.init(ints)))
                            stringResults <- stringFiber.get
                            intResults    <- intFiber.get
                        yield
                            assert(stringResults == strings)
                            assert(intResults == ints)
                    }
                }

                "generic message types" in run {
                    val strMessages = Seq(GenericMessage("hello"), GenericMessage("world"))
                    val intMessages = Seq(GenericMessage(1), GenericMessage(2))

                    Topic.run {
                        for
                            started <- Latch.init(2)
                            strFiber <-
                                Async.run(started.release.andThen(Topic.stream[GenericMessage[String]](uri).take(strMessages.size).run))
                            intFiber <-
                                Async.run(started.release.andThen(Topic.stream[GenericMessage[Int]](uri).take(intMessages.size).run))
                            _          <- started.await
                            _          <- Async.run(Topic.publish(uri)(Stream.init(strMessages)))
                            _          <- Async.run(Topic.publish(uri)(Stream.init(intMessages)))
                            strResults <- strFiber.get
                            intResults <- intFiber.get
                        yield
                            assert(strResults == strMessages)
                            assert(intResults == intMessages)
                    }
                }

                "complex message types" in run {
                    val messages = Seq(
                        ComplexMessage(1, List("a", "b")),
                        ComplexMessage(2, List("c", "d"))
                    )
                    Topic.run {
                        for
                            started  <- Latch.init(1)
                            fiber    <- Async.run(started.release.andThen(Topic.stream[ComplexMessage](uri).take(messages.size).run))
                            _        <- started.await
                            _        <- Async.run(Topic.publish(uri)(Stream.init(messages)))
                            received <- fiber.get
                        yield assert(received == messages)
                    }
                }
            }

            "multiple subscribers" - {
                "fan-out to multiple subscribers" in run {
                    val messages = Seq(Message(1), Message(2), Message(3))

                    Topic.run {
                        for
                            started <- Latch.init(2)
                            fiber1  <- Async.run(started.release.andThen(Topic.stream[Message](uri).take(messages.size).run))
                            fiber2  <- Async.run(started.release.andThen(Topic.stream[Message](uri).take(messages.size).run))
                            _       <- started.await
                            _       <- Async.run(Topic.publish(uri)(Stream.init(messages)))
                            result1 <- fiber1.get
                            result2 <- fiber2.get
                        yield
                            assert(result1 == messages)
                            assert(result2 == messages)
                    }
                }

                "subscribers with different consumption rates" in run {
                    val messages = Seq(Message(1), Message(2))

                    Topic.run {
                        for
                            started <- Latch.init(2)
                            slowFiber <-
                                Async.run(started.release.andThen(
                                    Topic.stream[Message](uri)
                                        .map(r => Async.delay(1.millis)(r))
                                        .take(messages.size)
                                        .run
                                ))
                            fastFiber <-
                                Async.run(started.release.andThen(
                                    Topic.stream[Message](uri)
                                        .take(messages.size)
                                        .run
                                ))
                            _    <- started.await
                            _    <- Async.run(Topic.publish(uri)(Stream.init(messages)))
                            slow <- slowFiber.get
                            fast <- fastFiber.get
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

                "base type cannot receive subtypes" in run {
                    val messages = Seq(Derived1(1), Derived1(2))

                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber   <- Async.run(started.release.andThen(Topic.stream[Base](uri, failSchedule).run))
                            _       <- started.await
                            result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(messages)))
                            result2 <- fiber.getResult
                        yield assert(result1.isFailure && result2.isFailure)
                    }
                }

                "subtype cannot receive base type" in run {
                    val messages = Seq[Base](Derived1(1), Derived1(2))

                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber   <- Async.run(started.release.andThen(Topic.stream[Derived1](uri, failSchedule).run))
                            _       <- started.await
                            result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(messages)))
                            result2 <- fiber.getResult
                        yield assert(result1.isFailure && result2.isFailure)
                    }
                }

                "generic base type cannot receive generic subtype" in run {
                    sealed trait GenericBase[A] derives Topic.AsMessage
                    case class GenericDerived[A](value: A) extends GenericBase[A] derives CanEqual, Topic.AsMessage

                    val messages = Seq(GenericDerived("test"))

                    Topic.run {
                        for
                            started <- Latch.init(1)
                            fiber   <- Async.run(started.release.andThen(Topic.stream[GenericBase[String]](uri, failSchedule).run))
                            _       <- started.await
                            result1 <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(messages)))
                            result2 <- fiber.getResult
                        yield assert(result1.isFailure && result2.isFailure)
                    }
                }

                "different subtypes maintain separation" in run {
                    val messages1 = Seq(Derived1(1), Derived1(2))
                    val messages2 = Seq(Derived2("a"), Derived2("b"))

                    Topic.run {
                        for
                            started  <- Latch.init(2)
                            fiber1   <- Async.run(started.release.andThen(Topic.stream[Derived1](uri).take(messages1.size).run))
                            fiber2   <- Async.run(started.release.andThen(Topic.stream[Derived2](uri).take(messages2.size).run))
                            _        <- started.await
                            _        <- Async.run(Topic.publish(uri)(Stream.init(messages1)))
                            _        <- Async.run(Topic.publish(uri)(Stream.init(messages2)))
                            results1 <- fiber1.get
                            results2 <- fiber2.get
                        yield
                            assert(results1 == messages1)
                            assert(results2 == messages2)
                    }
                }
            }

            "maintains message order for large batches (pendingUntilFixed)" in run {
                val count    = 200
                val messages = Seq.tabulate(count)(Message(_))
                Topic.run {
                    for
                        started <- Latch.init(1)
                        fiber   <- Async.run(started.release.andThen(Topic.stream[Message](uri).take(count).run))
                        _       <- started.await
                        result  <- Abort.run(Topic.publish[Message](uri)(Stream.init(messages)))
                    yield
                        if result.isPanic then
                            pending
                        else
                            fail("Pending test should have failed")
                }
            }

            "handles empty streams" in run {
                Topic.run {
                    for
                        started <- Latch.init(1)
                        fiber   <- Async.run(started.release.andThen(Topic.stream[Message](uri, failSchedule).take(1).run))
                        _       <- started.await
                        _       <- Topic.publish[Message](uri, failSchedule)(Stream.empty)
                        result  <- fiber.getResult
                    yield assert(result.isFailure)
                }
            }

            "partial subscriber failure" in run {
                val messageCount = 10
                val messages     = (0 until messageCount).map(Message(_))

                Topic.run {
                    for
                        started <- Latch.init(2)
                        failingFiber <- Async.run(
                            started.release.andThen(
                                Topic.stream[Message](uri)
                                    .map(_ => Abort.fail("Planned failure"): Message < Abort[String])
                                    .take(messageCount)
                                    .run
                            )
                        )
                        normalFiber <- Async.run(
                            started.release.andThen(
                                Topic.stream[Message](uri)
                                    .take(messageCount)
                                    .run
                            )
                        )
                        _             <- started.await
                        _             <- Topic.publish(uri)(Stream.init(messages))
                        failingResult <- failingFiber.getResult
                        normalResult  <- normalFiber.get
                    yield
                        assert(failingResult.isFailure)
                        assert(normalResult == messages)
                }
            }

            "isolation" - {
                "publisher without subscribers" in run {
                    Topic.run {
                        for
                            result <- Abort.run(Topic.publish(uri, failSchedule)(Stream.init(Seq(Message(1)))))
                        yield assert(result.isFailure)
                    }
                }

                "subscriber without publisher" in run {
                    Topic.run {
                        for
                            fiber  <- Async.run(Topic.stream[Message](uri, failSchedule).take(1).run)
                            result <- fiber.getResult
                        yield assert(result.isFailure)
                    }
                }

                "subscriber starts after publisher" in run {
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
