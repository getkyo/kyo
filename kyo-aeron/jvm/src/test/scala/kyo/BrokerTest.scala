package kyo

import java.net.DatagramSocket
import java.net.InetSocketAddress

class BrokerTest extends Test:
    case class Message(value: Int) derives CanEqual, Topic.AsMessage

    val failSchedule = Schedule.fixed(1.millis).take(3)

    def newPath = Path(".aeron", "test1", java.lang.System.currentTimeMillis().toString)

    def freePort() =
        val socket = new DatagramSocket(null)
        try
            socket.bind(new InetSocketAddress("localhost", 0))
            socket.getLocalPort()
        finally
            socket.close()
        end try
    end freePort

    "Broker" - {
        "single node cluster" - {
            "basic publish/subscribe" in run {
                val messages = Seq(Message(1), Message(2), Message(3))
                val port     = freePort()
                val node     = Broker.NodeConfig(1, "localhost", port)

                Topic.run {
                    for
                        broker  <- Broker.initNode(newPath, Set(node), 1)
                        started <- Latch.init(1)
                        fiber <- Async.run(
                            started.release.andThen(
                                broker.stream[Message]().take(messages.size).run
                            )
                        )
                        _        <- started.await
                        _        <- Async.run(broker.publish(Stream.init(messages)))
                        received <- fiber.get
                    yield assert(received == messages)
                }
            }

            // "handles backpressure" in run {
            //     val messages = Seq(Message(1), Message(2))
            //     val port     = freePort()
            //     val node     = Broker.NodeConfig(1, "localhost", port)

            //     Topic.run {
            //         for
            //             broker  <- Broker.initNode(newPath, Set(node), 1)
            //             started <- Latch.init(2)
            //             slowFiber <- Async.run(
            //                 started.release.andThen(
            //                     broker.stream[Message]()
            //                         .map(r => Async.delay(1.millis)(r))
            //                         .take(messages.size)
            //                         .run
            //                 )
            //             )
            //             fastFiber <- Async.run(
            //                 started.release.andThen(
            //                     broker.stream[Message]()
            //                         .take(messages.size)
            //                         .run
            //                 )
            //             )
            //             _    <- started.await
            //             _    <- Async.run(broker.publish(Stream.init(messages)))
            //             slow <- slowFiber.get
            //             fast <- fastFiber.get
            //         yield
            //             assert(slow == messages)
            //             assert(fast == messages)
            //     }
            // }

            // "handles empty streams" in run {
            //     val port = freePort()
            //     val node = Broker.NodeConfig(1, "localhost", port)

            //     Topic.run {
            //         for
            //             broker  <- Broker.init(Set(node))
            //             started <- Latch.init(1)
            //             fiber <- Async.run(
            //                 started.release.andThen(
            //                     broker.stream[Message](failSchedule).take(1).run
            //                 )
            //             )
            //             _      <- started.await
            //             _      <- broker.publish(Stream.empty, failSchedule)
            //             result <- fiber.getResult
            //         yield assert(result.isFailure)
            //     }
            // }
        }

        "multi-node cluster" - {
            "basic publish/subscribe" in run {
                val messages = Seq(Message(1), Message(2), Message(3))
                val port1    = freePort()
                val port2    = freePort()
                val nodes = Set(
                    Broker.NodeConfig(1, "localhost", port1),
                    Broker.NodeConfig(2, "localhost", port2)
                )

                Topic.run {
                    for
                        broker1 <- Broker.initNode(newPath, nodes, 1)
                        broker2 <- Broker.initNode(newPath, nodes, 2)
                        started <- Latch.init(1)
                        fiber <- Async.run(
                            started.release.andThen(
                                broker2.stream[Message]().take(messages.size).run
                            )
                        )
                        _        <- started.await
                        _        <- Async.run(broker1.publish(Stream.init(messages)))
                        received <- fiber.get
                    yield assert(received == messages)
                }
            }
        }
    }
end BrokerTest
