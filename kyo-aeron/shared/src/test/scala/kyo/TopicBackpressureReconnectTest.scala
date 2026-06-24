package kyo

/** Mirrors Aeron's PubAndSubTest, SubscriptionReconnectTest, StopStartSecondSubscriberTest, and PongTest:
  * each asserts the Aeron-documented backpressure and reconnect behavior through the Topic API.
  *
  * Every leaf runs on aeron:ipc with no java.net / DatagramSocket / free port (those break the JS/Native
  * link); the behaviors under test are transport-agnostic, so IPC loses no coverage.
  *
  * All bounded schedules and .take(n) bounds are load-bearing: a never-arriving message must fail fast,
  * never hang for 60s.
  */
class TopicBackpressureReconnectTest extends Test:

    /** Distinct case-class types per port so each maps to a distinct Aeron stream-id (Tag[A].hash.abs). */
    case class FlowMsg(seq: Int, pad: String) derives CanEqual, Schema
    case class DropMsg(seq: Int) derives CanEqual, Schema
    case class PubReconnectMsg(seq: Int) derives CanEqual, Schema
    case class SubRestartMsg(seq: Int) derives CanEqual, Schema
    case class SubRestartMsgB(seq: Int) derives CanEqual, Schema
    case class RestartPing(value: Int) derives CanEqual, Schema
    case class RestartPong(value: Int) derives CanEqual, Schema

    // Mirrors Aeron's PubAndSubTest.shouldReceiveOnlyAfterSendingUpToFlowControlLimit: exercises the real
    // BACK_PRESSURED (-2) full-term path, distinct from not-connected (-1); the default unbounded retry
    // schedule must ride it out and still deliver every message in order. Registering the consumer first
    // makes the offer reach BACK_PRESSURED rather than the not-connected pre-check.
    "a publisher blocked at the flow-control limit drains after the subscriber catches up" in {
        val flowUri  = "aeron:ipc?term-length=65536"
        val total    = 120
        val messages = Seq.tabulate(total)(i => FlowMsg(i, "x" * 2000))
        Topic.run {
            for
                started <- Latch.init(1)
                // Async.delay slows the consumer cooperatively, so the term fills behind it.
                consumer <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(
                        Topic.stream[FlowMsg](flowUri)
                            .map(m => Async.delay(1.millis)(m))
                            .take(total)
                            .run
                    )
                )
                _ <- started.await
                _ <- Fiber.initUnscoped(
                    Topic.publish[FlowMsg](flowUri)(Stream.init(messages).rechunk(1))
                )
                received <- consumer.get
            yield
                assert(received.size == total, s"expected $total messages after drain, got ${received.size}")
                assert(received == messages, "messages lost or reordered across the flow-control limit")
        }
    }

    // Mirrors Aeron's PubAndSubTest.shouldNoticeDroppedSubscriber: the publisher must notice the departed
    // subscriber promptly, not linger past the driver's image-liveness timeout. Joining the consumer fiber
    // (whose .take closes the subscription via Sync.ensure) before the second publish guarantees it is gone.
    "a dropped subscriber exhausts the publisher's retry schedule" in {
        val dropUri    = "aeron:ipc"
        val firstBatch = Seq(DropMsg(1), DropMsg(2), DropMsg(3))
        // 50 retries at 20ms is ~1s of patient retrying: long enough to ride out transient term
        // rotation, short enough that a never-noticed departure fails fast.
        val exhaustSchedule = Schedule.fixed(20.millis).take(50)
        Topic.run {
            for
                started <- Latch.init(1)
                consumer <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[DropMsg](dropUri).take(firstBatch.size).run)
                )
                _        <- started.await
                _        <- Topic.publish[DropMsg](dropUri)(Stream.init(firstBatch))
                received <- consumer.get
                _ = assert(received == firstBatch, s"first batch mismatch: $received")
                // The subscriber is now gone, so this publish sees a not-connected publication and the
                // retry must exhaust.
                result <- Abort.run[TopicException] {
                    Topic.publish[DropMsg](dropUri, exhaustSchedule)(Stream.init(Seq(DropMsg(4), DropMsg(5))))
                }
            yield result match
                case Result.Failure(_: TopicBackpressureExhaustedException) => succeed
                case Result.Failure(other) =>
                    fail(
                        s"expected TopicBackpressureExhaustedException after subscriber departed, got ${other.getClass.getSimpleName}: $other"
                    )
                case Result.Panic(t) =>
                    fail(s"expected TopicBackpressureExhaustedException but got panic: ${t.getClass.getSimpleName}: ${t.getMessage}")
                case Result.Success(_) =>
                    fail("expected TopicBackpressureExhaustedException after the only subscriber departed, but publish succeeded")
        }
    }

    // Mirrors Aeron's SubscriptionReconnectTest (plain linger=0 arm): the subscription must transparently
    // pick up the new image, not abort via CLOSED (-4) or retry-exhaustion. Joining publish A's fiber before
    // publish B starts sequences the close-then-readd, no sleeps needed.
    "a publisher reconnects to a live subscriber" in {
        val reconnectUri = "aeron:ipc"
        Topic.run {
            for
                started <- Latch.init(1)
                // Long-lived consumer: one subscription spanning both publisher lifecycles.
                consumer <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[PubReconnectMsg](reconnectUri).take(2).run)
                )
                _        <- started.await
                _        <- Topic.publish[PubReconnectMsg](reconnectUri)(Stream.init(Seq(PubReconnectMsg(1))))
                _        <- Topic.publish[PubReconnectMsg](reconnectUri)(Stream.init(Seq(PubReconnectMsg(2))))
                received <- consumer.get
            yield assert(
                received == Seq(PubReconnectMsg(1), PubReconnectMsg(2)),
                s"stream did not survive publisher reconnect: expected Seq(1, 2), got $received"
            )
        }
    }

    // Mirrors Aeron's StopStartSecondSubscriberTest (the four restart variants, mapped to URI/type at the
    // Topic layer). Each phase has its own Latch start-barrier, so phase-2 messages publish only after the
    // phase-2 subscriber registers, with the stopped subscriber's joined fiber as the departure signal.
    "subscriberRestartOnLivePublisher" - {

        "7a same-URI same-type restart" in {
            val uri    = "aeron:ipc"
            val phase1 = Seq(SubRestartMsg(1), SubRestartMsg(2))
            val phase2 = Seq(SubRestartMsg(3), SubRestartMsg(4))
            Topic.run {
                for
                    started1 <- Latch.init(1)
                    sub1 <- Fiber.initUnscoped(using Topic.isolate)(
                        started1.release.andThen(Topic.stream[SubRestartMsg](uri).take(phase1.size).run)
                    )
                    _         <- started1.await
                    _         <- Topic.publish[SubRestartMsg](uri)(Stream.init(phase1))
                    received1 <- sub1.get
                    _ = assert(received1 == phase1, s"sub#1 phase-1 mismatch: $received1")
                    // sub#1 has stopped (fiber joined); gate the phase-2 publish behind sub#2's registration.
                    started2 <- Latch.init(1)
                    sub2 <- Fiber.initUnscoped(using Topic.isolate)(
                        started2.release.andThen(Topic.stream[SubRestartMsg](uri).take(phase2.size).run)
                    )
                    _         <- started2.await
                    _         <- Topic.publish[SubRestartMsg](uri)(Stream.init(phase2))
                    received2 <- sub2.get
                yield assert(
                    received2 == phase2,
                    s"restarted subscriber did not resume on a live publisher: expected $phase2, got $received2"
                )
            }
        }

        // One continuous publication carries all four messages: order across two publish-then-close calls
        // is undefined in Aeron. The long-lived subscriber is slowed (Async.delay) so the short-lived
        // sibling departs mid-flight.
        "7b fan-out continuity after a sibling subscriber stops" in {
            val uri = "aeron:ipc"
            val all = Seq(SubRestartMsgB(1), SubRestartMsgB(2), SubRestartMsgB(3), SubRestartMsgB(4))
            Topic.run {
                for
                    started <- Latch.init(2)
                    shortSub <- Fiber.initUnscoped(using Topic.isolate)(
                        started.release.andThen(Topic.stream[SubRestartMsgB](uri).take(2).run)
                    )
                    longSub <- Fiber.initUnscoped(using Topic.isolate)(
                        started.release.andThen(
                            Topic.stream[SubRestartMsgB](uri)
                                .map(m => Async.delay(2.millis)(m))
                                .take(all.size)
                                .run
                        )
                    )
                    _          <- started.await
                    _          <- Fiber.initUnscoped(Topic.publish[SubRestartMsgB](uri)(Stream.init(all)))
                    shortRecvd <- shortSub.get
                    _ = assert(shortRecvd == all.take(2), s"short-lived subscriber first-two mismatch: $shortRecvd")
                    longRecvd <- longSub.get
                yield assert(
                    longRecvd == all,
                    s"surviving subscriber lost fan-out continuity after a sibling stopped: expected $all, got $longRecvd"
                )
            }
        }
    }

    // Mirrors Aeron's PongTest.playPingPongWithRestart: the two-stream echo must survive a mid-life stream
    // and publish close-and-recreate. Each round's Latch(2) start-barrier registers both stream[Ping] and
    // stream[Pong] before that round's Ping, and round-1's fiber completion is the teardown signal.
    "ping-pong survives a stream restart" in {
        val echoUri = "aeron:ipc"

        def echoOnce(barrier: Latch)
            : Unit < (Topic & Abort[TopicBackpressureException | TopicPublishException | TopicTransportException] & Async) =
            barrier.release.andThen(
                for
                    pings <- Topic.stream[RestartPing](echoUri).take(1).run
                    _     <- Topic.publish[RestartPong](echoUri)(Stream.init(pings.map(p => RestartPong(p.value))))
                yield ()
            )

        Topic.run {
            for
                started1 <- Latch.init(2)
                echo1    <- Fiber.initUnscoped(using Topic.isolate)(echoOnce(started1))
                initiator1 <- Fiber.initUnscoped(using Topic.isolate)(
                    started1.release.andThen(Topic.stream[RestartPong](echoUri).take(1).run)
                )
                _      <- started1.await
                _      <- Fiber.initUnscoped(Topic.publish[RestartPing](echoUri)(Stream.init(Seq(RestartPing(1)))))
                pongs1 <- initiator1.get
                _      <- echo1.get
                _ = assert(pongs1 == Seq(RestartPong(1)), s"round-1 pong mismatch: $pongs1")
                // Round 1 is torn down (both streams closed); a fresh responder and initiator must resume.
                started2 <- Latch.init(2)
                echo2    <- Fiber.initUnscoped(using Topic.isolate)(echoOnce(started2))
                initiator2 <- Fiber.initUnscoped(using Topic.isolate)(
                    started2.release.andThen(Topic.stream[RestartPong](echoUri).take(1).run)
                )
                _      <- started2.await
                _      <- Fiber.initUnscoped(Topic.publish[RestartPing](echoUri)(Stream.init(Seq(RestartPing(2)))))
                pongs2 <- initiator2.get
                _      <- echo2.get
            yield assert(
                pongs2 == Seq(RestartPong(2)),
                s"ping-pong did not resume after the responder stream restart: expected Seq(RestartPong(2)), got $pongs2"
            )
        }
    }

end TopicBackpressureReconnectTest
