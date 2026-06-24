package kyo

// These mirror Aeron's own PubAndSubTest, SubscriptionReconnectTest, StopStartSecondSubscriberTest,
// and PongTest: each asserts the Aeron-documented backpressure and reconnect behavior through the
// Topic API.
//
// Every leaf is cross-platform (JVM + JS + Native). The subscriberRestartOnLivePublisher leaf
// expresses the subscriber-restart behavior on aeron:ipc, which is platform-agnostic: a fresh
// subscriber starting on a live publisher and receiving subsequent messages does not need UDP. No
// java.net / DatagramSocket / free port is used anywhere here (those break the JS/Native LINK).
//
// All bounded schedules / .take(n) bounds are LOAD-BEARING: a never-arriving message must fail
// fast (schedule exhaustion / take never satisfied joins to a failure), never a 60s hang.
class TopicBackpressureReconnectTest extends Test:

    // Distinct case-class types per port so each maps to a distinct Aeron stream-id
    // (Topic derives the stream-id from Tag[A].hash.abs).
    case class FlowMsg(seq: Int, pad: String) derives CanEqual, Schema
    case class DropMsg(seq: Int) derives CanEqual, Schema
    case class PubReconnectMsg(seq: Int) derives CanEqual, Schema
    case class SubRestartMsg(seq: Int) derives CanEqual, Schema
    case class SubRestartMsgB(seq: Int) derives CanEqual, Schema
    case class RestartPing(value: Int) derives CanEqual, Schema
    case class RestartPong(value: Int) derives CanEqual, Schema

    // ---------------------------------------------------------------------------
    // flowControlLimitThenDrain
    //
    // Mirrors Aeron's PubAndSubTest.shouldReceiveOnlyAfterSendingUpToFlowControlLimit.
    //
    // On a SMALL term (aeron:ipc?term-length=65536, maxMessageLength=8192) a publisher floods
    // faster than a slow consumer drains, so the term buffer fills and offer returns
    // BACK_PRESSURED (-2). With the default UNBOUNDED retry schedule the publish must RIDE OUT
    // the backpressure and complete once the consumer drains. Aeron-correct behavior: the
    // publisher blocks at the flow-control limit, then every accepted message is delivered in
    // order once the consumer catches up. This exercises the real BACK_PRESSURED (-2) / full-term
    // path at the Topic surface (distinct from the not-connected / -1 path).
    //
    // Determinism: the slow consumer is registered FIRST (Latch start-barrier) so the
    // publication connects and the offer path reaches the real BACK_PRESSURED signal rather
    // than the not-connected pre-check. The consumer is slowed cooperatively with Async.delay
    // (no thread blocking). .take(total) bounds the drain. Each ~2 KB message is published in its
    // own Envelope (rechunk(1)) so a single message never exceeds maxMessageLength=8192; the
    // count (120 messages ~= 240 KB) far exceeds the 3-term publication window (~192 KB) so the
    // term genuinely fills and BACK_PRESSURED fires before the consumer drains.
    // ---------------------------------------------------------------------------

    "a publisher blocked at the flow-control limit drains after the subscriber catches up" in {
        val flowUri = "aeron:ipc?term-length=65536"
        val total   = 120
        // ~2 KB payload: each single-message Envelope is well under maxMessageLength=8192,
        // but 120 of them (~240 KB) overruns the publication window before the slow consumer
        // drains, forcing real BACK_PRESSURED on offer.
        val messages = Seq.tabulate(total)(i => FlowMsg(i, "x" * 2000))
        Topic.run {
            for
                started <- Latch.init(1)
                // Slow consumer: cooperative Async.delay per message models a consumer that
                // drains slower than the publisher floods, so the term fills behind it.
                consumer <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(
                        Topic.stream[FlowMsg](flowUri)
                            .map(m => Async.delay(1.millis)(m))
                            .take(total)
                            .run
                    )
                )
                _ <- started.await
                // Publisher floods with the default UNBOUNDED retry schedule: it must ride out
                // the backpressure (retry through -2) and complete once the consumer drains.
                _ <- Fiber.initUnscoped(
                    Topic.publish[FlowMsg](flowUri)(Stream.init(messages).rechunk(1))
                )
                received <- consumer.get
            yield
                assert(received.size == total, s"expected $total messages after drain, got ${received.size}")
                assert(received == messages, "messages lost or reordered across the flow-control limit")
        }
    }

    // ---------------------------------------------------------------------------
    // droppedSubscriberExhaustsPublisher
    //
    // Mirrors Aeron's PubAndSubTest.shouldNoticeDroppedSubscriber.
    //
    // A connected subscriber receives a first batch then DEPARTS (its .take(k) stream fiber
    // completes, which closes the subscription via Topic.stream's Sync.ensure). A publisher with
    // a BOUNDED retry schedule keeps publishing; once the only subscriber is gone the publication
    // becomes not-connected, the offer path routes to the transient retry signal, and the bounded
    // schedule must EXHAUST to TopicBackpressureExhaustedException. Aeron-correct behavior: the publisher
    // promptly notices the departed subscriber (publicationIsConnected flips false) rather than
    // lingering connected past a long driver image-liveness timeout.
    //
    // Behavioral question: does Topic promptly surface the departure within the bounded schedule,
    // or only after a driver timeout that outlasts the schedule (yielding a wrong terminal type or
    // a hang)?
    //
    // Determinism: the consumer .take(firstBatch) completion is the drop signal; joining that
    // fiber BEFORE continuing the publish guarantees the subscription is closed when the second
    // publish runs. A generous-but-bounded schedule (50 retries) gives the driver ample iterations
    // to report the departure while still terminating fast if it never does.
    // ---------------------------------------------------------------------------

    "a dropped subscriber exhausts the publisher's retry schedule" in {
        val dropUri    = "aeron:ipc"
        val firstBatch = Seq(DropMsg(1), DropMsg(2), DropMsg(3))
        // Bounded so the publisher's continued offer exhausts deterministically once the
        // subscriber is gone. 50 retries at 20ms is ~1s of patient retrying: long enough to
        // ride out transient term rotation, short enough that a never-noticed departure fails fast.
        val exhaustSchedule = Schedule.fixed(20.millis).take(50)
        Topic.run {
            for
                started <- Latch.init(1)
                // Consumer receives exactly the first batch then its fiber completes, closing
                // the subscription (the deterministic departure signal).
                consumer <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[DropMsg](dropUri).take(firstBatch.size).run)
                )
                _ <- started.await
                // Publish the first batch so the consumer connects and drains it.
                _        <- Topic.publish[DropMsg](dropUri)(Stream.init(firstBatch))
                received <- consumer.get
                _ = assert(received == firstBatch, s"first batch mismatch: $received")
                // Subscriber is now gone (its fiber joined => subscription closed). Continue
                // publishing with the bounded schedule: with no subscriber the publication is
                // not-connected and the retry must exhaust to TopicBackpressureExhaustedException.
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

    // ---------------------------------------------------------------------------
    // publisherReconnectToLiveSubscriber
    //
    // Mirrors Aeron's SubscriptionReconnectTest (plain linger=0 arm).
    //
    // One long-lived Topic.stream[A].take(2) consumer. Publisher A publishes msg1 and its
    // publication closes (its publish fiber completes, firing Topic.publish's Sync.ensure
    // closePublication); THEN publisher B publishes msg2 on the SAME URI + type. Aeron-correct
    // behavior: the subscription transparently picks up the new image and the single stream
    // receives BOTH messages, the stream surviving the publisher restart.
    //
    // Behavioral question: does Topic.stream survive a publisher restart, or does a CLOSED (-4)
    // / image-unavailable / retry-exhaustion abort the stream between the two publications?
    //
    // Determinism: Latch start-barrier registers the consumer before the first publish; publish A
    // completes (joined) BEFORE publish B starts, so the close-then-readd ordering is sequenced
    // via the for-comprehension, no sleeps. take(2) bounds receipt and fails fast if msg2 never
    // arrives after the reconnect.
    // ---------------------------------------------------------------------------

    "a publisher reconnects to a live subscriber" in {
        val reconnectUri = "aeron:ipc"
        Topic.run {
            for
                started <- Latch.init(1)
                // Long-lived consumer: one subscription spanning both publisher lifecycles.
                consumer <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[PubReconnectMsg](reconnectUri).take(2).run)
                )
                _ <- started.await
                // Publisher A: publish msg1, then its publication closes when this completes.
                _ <- Topic.publish[PubReconnectMsg](reconnectUri)(Stream.init(Seq(PubReconnectMsg(1))))
                // Publisher B: a FRESH publication on the same URI + type publishes msg2.
                _        <- Topic.publish[PubReconnectMsg](reconnectUri)(Stream.init(Seq(PubReconnectMsg(2))))
                received <- consumer.get
            yield assert(
                received == Seq(PubReconnectMsg(1), PubReconnectMsg(2)),
                s"stream did not survive publisher reconnect: expected Seq(1, 2), got $received"
            )
        }
    }

    // ---------------------------------------------------------------------------
    // subscriberRestartOnLivePublisher
    //
    // Mirrors Aeron's StopStartSecondSubscriberTest (the four StopStart restart variants:
    // same/different channel x same/different stream).
    //
    // A publisher keeps publishing; subscriber #1 receives some messages then stops; subscriber #2
    // starts on the same URI + type and must receive SUBSEQUENT messages. Aeron-correct behavior: a
    // fresh subscription re-attaching to a live publisher resumes reception. The behavior is
    // transport-agnostic, so it is expressed on aeron:ipc (cross-platform) rather than the all-UDP
    // upstream channel/stream cross-product.
    //
    // Two distinct VARIANTS as sub-leaves (the channel/stream cross-product maps to URI/type at the
    // Topic layer):
    //   7a same-URI restart: sub#1 stops, then sub#2 on the same URI + type resumes.
    //   7b fan-out continuity: a second same-type subscriber alongside the first keeps receiving
    //      after the first stops (same channel, same stream-id, concurrent subscriptions).
    //
    // Determinism: each phase uses its own Latch start-barrier so a subscriber is registered before
    // the publisher offers into that phase; .take(n) bounds each receipt and joins. The publisher
    // emits in two latch-gated phases so phase-2 messages are published only after the phase-2
    // subscriber is registered (no reliance on the driver buffering past a stopped subscriber). No
    // thread blocking; the stopped subscriber's joined fiber is the deterministic departure signal.
    // ---------------------------------------------------------------------------

    "subscriberRestartOnLivePublisher" - {

        // 7a: sub#1 takes the phase-1 batch and stops; sub#2 starts on the same URI + type and
        // takes the phase-2 batch from the still-live publisher.
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
                    // sub#1 has stopped (fiber joined => subscription closed). Start sub#2 on the
                    // same URI + type and gate the phase-2 publish behind its registration.
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

        // 7b: a second same-type subscriber subscribed alongside the first keeps receiving the
        // later messages after the first subscriber stops (fan-out continuity across a sibling
        // subscription's departure on the same channel + stream-id).
        //
        // ONE continuous publication carries all four messages (1..4): Aeron guarantees in-order
        // delivery only WITHIN a single publication image, not across two separate
        // publish-then-close publications (that cross-publication order is undefined, which is why this
        // test uses ONE continuous publication for all four messages). The short-lived subscriber drains the first two
        // and departs (its take(2) fiber joins => subscription closed); the long-lived subscriber
        // keeps draining the SAME image and must receive all four in order, proving the sibling's
        // departure does not perturb the surviving subscription. The long-lived subscriber is
        // slowed cooperatively (Async.delay) so the short-lived sibling reaches its take(2) and
        // departs while the long-lived stream is still mid-flight.
        "7b fan-out continuity after a sibling subscriber stops" in {
            val uri = "aeron:ipc"
            val all = Seq(SubRestartMsgB(1), SubRestartMsgB(2), SubRestartMsgB(3), SubRestartMsgB(4))
            Topic.run {
                for
                    started <- Latch.init(2)
                    // Short-lived subscriber: takes the first two then departs.
                    shortSub <- Fiber.initUnscoped(using Topic.isolate)(
                        started.release.andThen(Topic.stream[SubRestartMsgB](uri).take(2).run)
                    )
                    // Long-lived subscriber: drains slowly across the sibling's departure and takes all four.
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

    // ---------------------------------------------------------------------------
    // pingPongSurvivesStreamRestart
    //
    // Mirrors Aeron's PongTest.playPingPongWithRestart.
    //
    // The two-stream ping-pong echo, but the responder's Ping stream is closed
    // (its round-1 .take(1) echo fiber completes) and a FRESH responder is started, then a
    // second Ping is published. Aeron-correct behavior: the round-trip resumes and the second
    // Pong is received after the responder restart.
    //
    // Behavioral question: does the two-stream echo survive a mid-life stream + publish close
    // and re-create?
    //
    // Determinism: round 1 uses a Latch(2) start-barrier (echo stream[Ping] + initiator
    // stream[Pong] both registered before the first Ping); round-1 fiber completion is the
    // deterministic teardown signal; a second Latch(2) gates the fresh round-2 fibers before the
    // second Ping. .take(1) on each hop bounds receipt and joins. No sleeps.
    // ---------------------------------------------------------------------------

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
                // Round 1: full ping-pong round-trip.
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
                // Round 1 torn down (echo1 + initiator1 joined => both streams closed). Start a
                // FRESH responder + initiator and publish a second Ping; the round-trip must resume.
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
