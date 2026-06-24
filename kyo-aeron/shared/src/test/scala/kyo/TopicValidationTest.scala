package kyo

// Mirrors Aeron's ChannelValidationTest, AsyncResourceTest, ChannelInterfaceTest,
// UriValidationTest, and StopStartSecondSubscriberTest. Every leaf is cross-platform
// (JVM + JS + Native). The UDP CONTRACT (Topic over aeron:udp) is transport-agnostic, so
// these leaves run on all three platforms.
//
// No java.net / DatagramSocket / InetSocketAddress is used anywhere here (those break the JS/Native
// LINK). The malformed-URI rejection leaves need NO free port: the bogus /
// wildcard / over-length endpoint is rejected at REGISTRATION before any bind, so a fixed literal
// endpoint suffices. The positive UDP round-trip leaf uses FIXED distinct high ports; the suite
// runs SERIALLY (kyo-aeron sets Test / parallelExecution := false, embedded-driver dir contention),
// so fixed high ports are collision-safe within a run.
//
// Each rejection leaf asserts TopicRegistrationFailedException (type + non-empty detail) on a BOUNDED
// schedule so a non-rejecting URI fails fast rather than hanging. The UDP round-trip leaf asserts a positive
// round-trip with no cross-talk.
class TopicValidationTest extends Test:

    // Distinct case-class types per port so each maps to a distinct Aeron stream-id (Tag[A].hash.abs).
    case class PairMsg(seq: Int) derives CanEqual, Schema
    case class RejMsg11(v: Int) derives CanEqual, Schema
    case class RejMsg12(v: Int) derives CanEqual, Schema
    case class RejMsg13(v: Int) derives CanEqual, Schema
    case class RejMsg14(v: Int) derives CanEqual, Schema
    case class RejMsg15(v: Int) derives CanEqual, Schema
    case class RejMsg16(v: Int) derives CanEqual, Schema
    case class RejMsg17udp(v: Int) derives CanEqual, Schema
    case class RejMsg17ipc(v: Int) derives CanEqual, Schema

    // Bounded schedule: 10 ms interval, 5 attempts. Load-bearing: a non-rejecting URI fails fast
    // rather than hanging. A non-rejection within 5 attempts reveals the gap (wrongly accepted).
    val failSchedule = Schedule.fixed(10.millis).take(5)

    // Fixed distinct high ports for the positive UDP round-trip. The suite runs serially
    // (Test / parallelExecution := false), so two fixed high ports never collide within a run, and
    // Topic cannot read back an Aeron-assigned ephemeral port anyway.
    val port8a = 24517
    val port8b = 24518

    // Helper: assert that result is Result.Failure(TopicRegistrationFailedException) with a non-empty detail.
    private def assertRegistrationFailed[A](result: Result[TopicException, A], ctx: String)(using kyo.test.AssertScope, Frame): Unit =
        result match
            case Result.Failure(reg: TopicRegistrationFailedException) =>
                assert(
                    reg.detail.nonEmpty,
                    s"$ctx: TopicRegistrationFailedException.detail must be non-empty; got: '${reg.detail}'"
                )
            case Result.Failure(other) =>
                fail(s"$ctx: expected TopicRegistrationFailedException but got ${other.getClass.getSimpleName}: $other")
            case Result.Panic(t) =>
                fail(s"$ctx: expected TopicRegistrationFailedException but got panic: ${t.getClass.getSimpleName}: ${t.getMessage}")
            case Result.Success(_) =>
                fail(s"$ctx: expected TopicRegistrationFailedException but the operation succeeded")
    end assertRegistrationFailed

    // ---------------------------------------------------------------------------
    // concurrentIndependentUdpPairs
    //
    // Mirrors Aeron's StopStartSecondSubscriberTest.shouldReceivePublishedMessage.
    //
    // Two independent publisher/subscriber pairs on DISTINCT UDP endpoints each round-trip
    // their own messages; asserts each subscriber receives exactly its own pair's values
    // (no cross-talk). Latch(2) ensures both consumers are registered before any publish.
    // Fixed distinct high ports (serial suite => collision-safe). First UDP round-trip on
    // Native/JS: a C-driver/UDP divergence would surface here (first UDP round-trip on those backends).
    // ---------------------------------------------------------------------------

    "concurrent independent UDP pairs stay isolated" in {
        val uri1  = s"aeron:udp?endpoint=127.0.0.1:$port8a"
        val uri2  = s"aeron:udp?endpoint=127.0.0.1:$port8b"
        val msgs1 = Seq(PairMsg(1), PairMsg(2))
        val msgs2 = Seq(PairMsg(3), PairMsg(4))
        Topic.run {
            for
                started <- Latch.init(2)
                // Consumer 1: registers then waits for messages on uri1.
                fiber1 <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[PairMsg](uri1).take(msgs1.size).run)
                )
                // Consumer 2: registers then waits for messages on uri2.
                fiber2 <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[PairMsg](uri2).take(msgs2.size).run)
                )
                _ <- started.await
                // Publish to each URI independently after both consumers are registered.
                _         <- Fiber.initUnscoped(Topic.publish[PairMsg](uri1)(Stream.init(msgs1)))
                _         <- Fiber.initUnscoped(Topic.publish[PairMsg](uri2)(Stream.init(msgs2)))
                received1 <- fiber1.get
                received2 <- fiber2.get
            yield
                assert(received1 == msgs1, s"pair1 cross-talk or loss: expected $msgs1, got $received1")
                assert(received2 == msgs2, s"pair2 cross-talk or loss: expected $msgs2, got $received2")
        }
    }

    // ---------------------------------------------------------------------------
    // udpUnresolvableHostRejected
    //
    // Mirrors Aeron's AsyncResourceTest.shouldDetectUnknownHost (SlowTest, 60s DNS timeout).
    //
    // publish to aeron:udp?endpoint=wibble:1234 (an unresolvable hostname). The driver
    // must reject the registration. The bounded failSchedule (5 attempts x 10 ms) converts a
    // non-rejection into an observable timeout/wrong-type assertion, not a 60s hang.
    //
    // Note: an unresolvable host may surface as a slow registration failure on some OSes
    // (DNS timeout) rather than an immediate rejection. The bounded schedule ensures the leaf
    // fails fast with the wrong type if the driver does not reject it promptly. That would be
    // a gap (the URI is genuinely invalid per the Aeron source).
    // ---------------------------------------------------------------------------

    "an unresolvable UDP host is rejected" in {
        val uri = "aeron:udp?endpoint=wibble:1234"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg11](uri, failSchedule)(Stream.init(Seq(RejMsg11(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // ---------------------------------------------------------------------------
    // udpInterfaceNotFoundRejected
    //
    // Mirrors Aeron's ChannelInterfaceTest.shouldThrowIfSpecifiedInterfaceDoesNotExist.
    //
    // publish to a UDP URI specifying a non-existent interface name {does_not_exist}. The driver
    // rejects the registration with "unknown interface does_not_exist".
    // ---------------------------------------------------------------------------

    "a UDP interface that does not exist is rejected" in {
        // Unicast form from ChannelInterfaceTest @ValueSource line 1.
        val uri = "aeron:udp?endpoint=localhost:24325|interface={does_not_exist}:24326"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg12](uri, failSchedule)(Stream.init(Seq(RejMsg12(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // ---------------------------------------------------------------------------
    // udpWildcardEndpointRejected
    //
    // Mirrors Aeron's ChannelValidationTest.shouldErrorOnPublicationWithWildcardEndpoint.
    //
    // publish to aeron:udp?endpoint=localhost:0 (wildcard port). A wildcard port is invalid
    // for a PUBLICATION (the driver needs a resolved address to send to).
    // ---------------------------------------------------------------------------

    "a UDP wildcard endpoint is rejected" in {
        val uri = "aeron:udp?endpoint=localhost:0"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg13](uri, failSchedule)(Stream.init(Seq(RejMsg13(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // ---------------------------------------------------------------------------
    // udpWildcardControlRejected
    //
    // Mirrors Aeron's ChannelValidationTest.shouldErrorOnSubscriptionWithWildcardControl.
    //
    // stream from aeron:udp?control=localhost:0|endpoint=localhost:20000 (wildcard control port).
    // A wildcard port is invalid for a SUBSCRIPTION control endpoint.
    // ---------------------------------------------------------------------------

    "a UDP wildcard control address is rejected" in {
        val uri = "aeron:udp?control=localhost:0|endpoint=localhost:20000"
        Topic.run {
            Abort.run[TopicException] {
                Topic.stream[RejMsg14](uri, failSchedule).take(1).run
            }.map(assertRegistrationFailed(_, s"stream from '$uri'"))
        }
    }

    // ---------------------------------------------------------------------------
    // udpControlWithoutModeRejected
    //
    // Mirrors Aeron's ChannelValidationTest.shouldDisallowControlEndpointWithoutControlModeOrEndpoint.
    //
    // publish to aeron:udp?control=localhost:9999 (control key without a control-mode or
    // endpoint). The driver rejects this as an invalid channel configuration.
    // ---------------------------------------------------------------------------

    "a UDP control address without a control mode is rejected" in {
        val uri = "aeron:udp?control=localhost:9999"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg15](uri, failSchedule)(Stream.init(Seq(RejMsg15(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // ---------------------------------------------------------------------------
    // udpControlModeDynamicWithoutControlRejected
    //
    // Mirrors Aeron's ChannelValidationTest.shouldDisallowControlModeDynamicWithoutControl.
    //
    // publish to aeron:udp?control-mode=dynamic (dynamic control mode without a control endpoint).
    // The driver rejects this as an invalid channel configuration.
    // ---------------------------------------------------------------------------

    "UDP control-mode dynamic without a control address is rejected" in {
        val uri = "aeron:udp?control-mode=dynamic"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg16](uri, failSchedule)(Stream.init(Seq(RejMsg16(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // ---------------------------------------------------------------------------
    // overLengthUriRejected, IPC variant
    //
    // Mirrors Aeron's UriValidationTest.shouldRejectChannelUrisWhenTooLong (IPC base URI:
    // "aeron:ipc?mtu=2K"). ChannelUri.MAX_URI_LENGTH = 4095.
    //
    // A URI padded to exceed 4095 characters with a valid scheme (aeron:ipc) must be rejected
    // by both Topic.publish and Topic.stream with TopicRegistrationFailedException. This is a distinct
    // trigger from the existing aeron:bogus tests (unknown scheme): the IPC scheme is valid but
    // the URI is rejected by LENGTH (ErrorCode.INVALID_CHANNEL on the JVM path).
    //
    // The bounded failSchedule (5 x 10ms) prevents a non-rejecting driver from hanging.
    // ---------------------------------------------------------------------------

    "an over-length URI is rejected (IPC arm)" in {
        val maxUriLength = 4095
        // Match the @ValueSource base URI from UriValidationTest: "aeron:ipc?mtu=2K"
        // Pad with alias= padding to push the total length past MAX_URI_LENGTH.
        val baseUri    = "aeron:ipc?mtu=2K|alias=too-long-"
        val padding    = "x" * (maxUriLength + 1 - baseUri.length)
        val tooLongUri = baseUri + padding
        assert(
            tooLongUri.length > maxUriLength,
            s"URI length ${tooLongUri.length} must exceed MAX_URI_LENGTH $maxUriLength"
        )
        Topic.run {
            for
                pubResult <- Abort.run[TopicException] {
                    Topic.publish[RejMsg17ipc](tooLongUri, failSchedule)(Stream.init(Seq(RejMsg17ipc(1))))
                }
                subResult <- Abort.run[TopicException] {
                    Topic.stream[RejMsg17ipc](tooLongUri, failSchedule).take(1).run
                }
            yield
                assertRegistrationFailed(pubResult, s"publish: over-length IPC URI (${tooLongUri.length} chars)")
                assertRegistrationFailed(subResult, s"stream: over-length IPC URI (${tooLongUri.length} chars)")
        }
    }

    // ---------------------------------------------------------------------------
    // overLengthUriRejected, UDP variant
    //
    // Mirrors Aeron's UriValidationTest.shouldRejectChannelUrisWhenTooLong (UDP base URI arm:
    // "aeron:udp?endpoint=localhost:8080|ssc=false"). ChannelUri.MAX_URI_LENGTH = 4095.
    //
    // A UDP URI padded past MAX_URI_LENGTH (4095) must be rejected by both publish and stream. No
    // free port is needed: the over-length URI is rejected by LENGTH before any bind, so a fixed
    // literal endpoint suffices (port8a is reused only as a literal in the rejected string).
    // ---------------------------------------------------------------------------

    "an over-length URI is rejected (UDP arm)" in {
        val maxUriLength = 4095
        // Base URI for the UDP arm (matches the @ValueSource in UriValidationTest). The endpoint
        // is a fixed literal: the URI is rejected by length before any port bind happens.
        val baseUri    = s"aeron:udp?endpoint=127.0.0.1:$port8a|ssc=false|alias=too-long-"
        val padding    = "x" * (maxUriLength + 1 - baseUri.length)
        val tooLongUri = baseUri + padding
        assert(
            tooLongUri.length > maxUriLength,
            s"URI length ${tooLongUri.length} must exceed MAX_URI_LENGTH $maxUriLength"
        )
        Topic.run {
            for
                pubResult <- Abort.run[TopicException] {
                    Topic.publish[RejMsg17udp](tooLongUri, failSchedule)(Stream.init(Seq(RejMsg17udp(1))))
                }
                subResult <- Abort.run[TopicException] {
                    Topic.stream[RejMsg17udp](tooLongUri, failSchedule).take(1).run
                }
            yield
                assertRegistrationFailed(pubResult, s"publish: over-length UDP URI (${tooLongUri.length} chars)")
                assertRegistrationFailed(subResult, s"stream: over-length UDP URI (${tooLongUri.length} chars)")
        }
    }

end TopicValidationTest
