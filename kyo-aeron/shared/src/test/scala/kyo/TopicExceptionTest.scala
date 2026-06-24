package kyo

/** Tests for the TopicException hierarchy (shared/cross-platform).
  *
  * The behavioral contract (every leaf carries a non-empty message that renders its
  * uri/streamId/detail fields, and the typed exceptions replace any inline message string)
  * is asserted here cross-platform.
  */
class TopicExceptionTest extends Test:

    // --- hierarchy shape is sealed and complete ---

    "hierarchy shape: sealed and complete" in {
        // Construct one representative of each subcategory to prove the hierarchy compiles.
        val exhausted: TopicBackpressureException = TopicBackpressureExhaustedException("aeron:ipc", 7)
        val closed: TopicPublishException         = TopicPublicationClosedException("aeron:ipc", 7)
        val maxPos: TopicPublishException         = TopicMaxPositionExceededException("aeron:ipc", 7)
        val tooLarge: TopicPublishException       = TopicMessageTooLargeException(9000, 8192)
        val regFail: TopicTransportException      = TopicRegistrationFailedException("aeron:ipc", 7, -234, "real detail")
        val addTmo: TopicTransportException       = TopicAddTimeoutException("aeron:ipc", 7, 200.millis)
        val transFail: TopicTransportException    = TopicTransportFailedException("conductor timeout")

        // Each leaf must be a TopicException (the base).
        assert(exhausted.isInstanceOf[TopicException])
        assert(closed.isInstanceOf[TopicException])
        assert(maxPos.isInstanceOf[TopicException])
        assert(tooLarge.isInstanceOf[TopicException])
        assert(regFail.isInstanceOf[TopicException])
        assert(addTmo.isInstanceOf[TopicException])
        assert(transFail.isInstanceOf[TopicException])

        // Each leaf belongs to exactly one of the three sealed subcategories. The membership
        // probes run through the TopicException base type so each isInstanceOf is a genuine
        // runtime check rather than a statically-decidable one.
        def assertSubcategory(e: TopicException, backpressured: Boolean, publish: Boolean, transport: Boolean): Unit =
            assert(e.isInstanceOf[TopicBackpressureException] == backpressured, s"backpressured membership: $e")
            assert(e.isInstanceOf[TopicPublishException] == publish, s"publish membership: $e")
            assert(e.isInstanceOf[TopicTransportException] == transport, s"transport membership: $e")
        end assertSubcategory

        assertSubcategory(exhausted, backpressured = true, publish = false, transport = false)
        assertSubcategory(closed, backpressured = false, publish = true, transport = false)
        assertSubcategory(maxPos, backpressured = false, publish = true, transport = false)
        assertSubcategory(tooLarge, backpressured = false, publish = true, transport = false)
        assertSubcategory(regFail, backpressured = false, publish = false, transport = true)
        assertSubcategory(addTmo, backpressured = false, publish = false, transport = true)
        assertSubcategory(transFail, backpressured = false, publish = false, transport = true)

        // Exhaustive match on the three subcategories; the Scala compiler must not
        // warn "match may not be exhaustive" for this to be correct.
        def subcategoryOf(e: TopicException): String =
            e match
                case _: TopicBackpressureException => "backpressure"
                case _: TopicPublishException      => "publish"
                case _: TopicTransportException    => "transport"

        assert(subcategoryOf(exhausted) == "backpressure")
        assert(subcategoryOf(closed) == "publish")
        assert(subcategoryOf(tooLarge) == "publish")
        assert(subcategoryOf(regFail) == "transport")
        assert(subcategoryOf(addTmo) == "transport")
        assert(subcategoryOf(transFail) == "transport")
        succeed
    }

    // --- every leaf message is non-empty and renders its fields ---

    "every leaf message is non-empty and renders its fields" in {
        // TopicBackpressureExhaustedException: must render uri and stream id.
        val exhaustedMsg = TopicBackpressureExhaustedException("aeron:ipc", 7).getMessage()
        assert(exhaustedMsg != null && exhaustedMsg.nonEmpty, "TopicBackpressureExhaustedException message is empty")
        assert(exhaustedMsg.contains("aeron:ipc"), s"TopicBackpressureExhaustedException message missing uri: $exhaustedMsg")
        assert(exhaustedMsg.contains("7"), s"TopicBackpressureExhaustedException message missing streamId: $exhaustedMsg")

        // TopicPublicationClosedException: must render uri and stream id.
        val closedMsg = TopicPublicationClosedException("aeron:ipc", 7).getMessage()
        assert(closedMsg != null && closedMsg.nonEmpty)
        assert(closedMsg.contains("aeron:ipc"))
        assert(closedMsg.contains("7"))

        // TopicMaxPositionExceededException: must render uri and stream id.
        val maxPosMsg = TopicMaxPositionExceededException("aeron:ipc", 7).getMessage()
        assert(maxPosMsg != null && maxPosMsg.nonEmpty)
        assert(maxPosMsg.contains("aeron:ipc"))
        assert(maxPosMsg.contains("7"))

        // TopicMessageTooLargeException: must render both numbers.
        val tooLargeMsg = TopicMessageTooLargeException(9000, 8192).getMessage()
        assert(tooLargeMsg != null && tooLargeMsg.nonEmpty)
        assert(tooLargeMsg.contains("9000"), s"TopicMessageTooLargeException missing messageSize: $tooLargeMsg")
        assert(tooLargeMsg.contains("8192"), s"TopicMessageTooLargeException missing maxMessageLength: $tooLargeMsg")

        // TopicRegistrationFailedException: must render uri, stream id, error code, and detail.
        val regFailMsg = TopicRegistrationFailedException("aeron:ipc", 7, -234, "real detail").getMessage()
        assert(regFailMsg != null && regFailMsg.nonEmpty)
        assert(regFailMsg.contains("aeron:ipc"))
        assert(regFailMsg.contains("7"))
        assert(regFailMsg.contains("-234"), s"TopicRegistrationFailedException missing errorCode: $regFailMsg")
        assert(regFailMsg.contains("real detail"), s"TopicRegistrationFailedException missing detail: $regFailMsg")

        // TopicAddTimeoutException: must render uri, stream id, and timeout.
        val addTmoMsg = TopicAddTimeoutException("aeron:ipc", 7, 200.millis).getMessage()
        assert(addTmoMsg != null && addTmoMsg.nonEmpty)
        assert(addTmoMsg.contains("aeron:ipc"))
        assert(addTmoMsg.contains("7"))

        // TopicTransportFailedException: must render detail.
        val transFail = TopicTransportFailedException("conductor timeout").getMessage()
        assert(transFail != null && transFail.nonEmpty)
        assert(transFail.contains("conductor timeout"), s"TopicTransportFailedException missing detail: $transFail")

        succeed
    }

    // --- TopicRegistrationFailedException.apply normalizes an empty detail ---

    "TopicRegistrationFailedException.apply normalizes empty detail" in {
        // Empty detail -> normalized to "(no driver detail)".
        val emptyDetail = TopicRegistrationFailedException("aeron:ipc", 7, -234, "")
        assert(emptyDetail.detail == "(no driver detail)", s"Expected '(no driver detail)' but got: ${emptyDetail.detail}")

        // Non-empty detail -> preserved verbatim.
        val realDetail = TopicRegistrationFailedException("aeron:ipc", 7, -234, "real detail")
        assert(realDetail.detail == "real detail", s"Expected 'real detail' but got: ${realDetail.detail}")

        // The "(no driver detail)" placeholder must appear in the message.
        val emptyMsg = emptyDetail.getMessage()
        assert(emptyMsg.contains("(no driver detail)"), s"Empty-detail message does not render placeholder: $emptyMsg")

        succeed
    }

end TopicExceptionTest
