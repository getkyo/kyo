package kyo

/** Mirrors Aeron's ChannelValidationTest, AsyncResourceTest, ChannelInterfaceTest, UriValidationTest, and
  * StopStartSecondSubscriberTest.
  *
  * No java.net / DatagramSocket / InetSocketAddress: those break the JS/Native link. Rejection leaves fail
  * at REGISTRATION before any bind, so a fixed literal endpoint suffices with no free port needed. The
  * positive UDP round-trip uses fixed distinct high ports, safe because kyo-aeron sets
  * `Test / parallelExecution := false` for embedded-driver dir contention.
  *
  * Every rejection leaf runs on a bounded schedule so a non-rejecting URI fails fast instead of hanging.
  */
class TopicValidationTest extends Test:

    /** Distinct case-class types per port so each maps to a distinct Aeron stream-id (Tag[A].hash.abs). */
    case class PairMsg(seq: Int) derives CanEqual, Schema
    case class RejMsg11(v: Int) derives CanEqual, Schema
    case class RejMsg12(v: Int) derives CanEqual, Schema
    case class RejMsg13(v: Int) derives CanEqual, Schema
    case class RejMsg14(v: Int) derives CanEqual, Schema
    case class RejMsg15(v: Int) derives CanEqual, Schema
    case class RejMsg16(v: Int) derives CanEqual, Schema
    case class RejMsg17udp(v: Int) derives CanEqual, Schema
    case class RejMsg17ipc(v: Int) derives CanEqual, Schema

    /** A non-rejection within these 5 attempts reveals the gap (the URI was wrongly accepted). */
    val failSchedule = Schedule.fixed(10.millis).take(5)

    /** Fixed rather than ephemeral because Topic cannot read back an Aeron-assigned port. */
    val port8a = 24517
    val port8b = 24518

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

    // Mirrors Aeron's StopStartSecondSubscriberTest.shouldReceivePublishedMessage: two pairs on distinct
    // UDP endpoints must not cross-talk. Latch(2) ensures both consumers are registered before any publish.
    "concurrent independent UDP pairs stay isolated" in {
        val uri1  = s"aeron:udp?endpoint=127.0.0.1:$port8a"
        val uri2  = s"aeron:udp?endpoint=127.0.0.1:$port8b"
        val msgs1 = Seq(PairMsg(1), PairMsg(2))
        val msgs2 = Seq(PairMsg(3), PairMsg(4))
        Topic.run {
            for
                started <- Latch.init(2)
                fiber1 <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[PairMsg](uri1).take(msgs1.size).run)
                )
                fiber2 <- Fiber.initUnscoped(using Topic.isolate)(
                    started.release.andThen(Topic.stream[PairMsg](uri2).take(msgs2.size).run)
                )
                _         <- started.await
                _         <- Fiber.initUnscoped(Topic.publish[PairMsg](uri1)(Stream.init(msgs1)))
                _         <- Fiber.initUnscoped(Topic.publish[PairMsg](uri2)(Stream.init(msgs2)))
                received1 <- fiber1.get
                received2 <- fiber2.get
            yield
                assert(received1 == msgs1, s"pair1 cross-talk or loss: expected $msgs1, got $received1")
                assert(received2 == msgs2, s"pair2 cross-talk or loss: expected $msgs2, got $received2")
        }
    }

    // Mirrors Aeron's AsyncResourceTest.shouldDetectUnknownHost. Some OSes resolve an unknown host via a
    // slow DNS timeout rather than an immediate rejection; the bounded schedule turns that into a fast
    // wrong-type assertion instead of a 60s hang.
    "an unresolvable UDP host is rejected" in {
        val uri = "aeron:udp?endpoint=wibble:1234"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg11](uri, failSchedule)(Stream.init(Seq(RejMsg11(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // Mirrors Aeron's ChannelInterfaceTest.shouldThrowIfSpecifiedInterfaceDoesNotExist.
    "a UDP interface that does not exist is rejected" in {
        // Unicast form from ChannelInterfaceTest @ValueSource line 1.
        val uri = "aeron:udp?endpoint=localhost:24325|interface={does_not_exist}:24326"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg12](uri, failSchedule)(Stream.init(Seq(RejMsg12(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // Mirrors Aeron's ChannelValidationTest.shouldErrorOnPublicationWithWildcardEndpoint: a wildcard port
    // is invalid for a publication, since the driver needs a resolved address to send to.
    "a UDP wildcard endpoint is rejected" in {
        val uri = "aeron:udp?endpoint=localhost:0"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg13](uri, failSchedule)(Stream.init(Seq(RejMsg13(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // Mirrors Aeron's ChannelValidationTest.shouldErrorOnSubscriptionWithWildcardControl.
    "a UDP wildcard control address is rejected" in {
        val uri = "aeron:udp?control=localhost:0|endpoint=localhost:20000"
        Topic.run {
            Abort.run[TopicException] {
                Topic.stream[RejMsg14](uri, failSchedule).take(1).run
            }.map(assertRegistrationFailed(_, s"stream from '$uri'"))
        }
    }

    // Mirrors Aeron's ChannelValidationTest.shouldDisallowControlEndpointWithoutControlModeOrEndpoint.
    "a UDP control address without a control mode is rejected" in {
        val uri = "aeron:udp?control=localhost:9999"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg15](uri, failSchedule)(Stream.init(Seq(RejMsg15(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // Mirrors Aeron's ChannelValidationTest.shouldDisallowControlModeDynamicWithoutControl.
    "UDP control-mode dynamic without a control address is rejected" in {
        val uri = "aeron:udp?control-mode=dynamic"
        Topic.run {
            Abort.run[TopicException] {
                Topic.publish[RejMsg16](uri, failSchedule)(Stream.init(Seq(RejMsg16(1))))
            }.map(assertRegistrationFailed(_, s"publish to '$uri'"))
        }
    }

    // Mirrors Aeron's UriValidationTest.shouldRejectChannelUrisWhenTooLong; ChannelUri.MAX_URI_LENGTH is
    // 4095. Distinct from the aeron:bogus leaves in TopicTest: here the scheme is valid and rejection is
    // by LENGTH (ErrorCode.INVALID_CHANNEL on the JVM path).
    "an over-length URI is rejected (IPC arm)" in {
        val maxUriLength = 4095
        // Matches the @ValueSource base URI from UriValidationTest, padded past MAX_URI_LENGTH.
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

    // UDP arm of the same UriValidationTest.shouldRejectChannelUrisWhenTooLong. port8a here is just a
    // literal in the rejected string; the URI fails by length before any bind, so nothing listens on it.
    "an over-length URI is rejected (UDP arm)" in {
        val maxUriLength = 4095
        val baseUri      = s"aeron:udp?endpoint=127.0.0.1:$port8a|ssc=false|alias=too-long-"
        val padding      = "x" * (maxUriLength + 1 - baseUri.length)
        val tooLongUri   = baseUri + padding
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
