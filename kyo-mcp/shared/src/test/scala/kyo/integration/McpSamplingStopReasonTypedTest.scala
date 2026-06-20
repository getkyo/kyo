package kyo.integration

import kyo.*

/** Tests for typed SamplingResponse.StopReason Schema (§3.5).
  *
  * StopReason Schema is tested directly (not through SamplingResponse.serializeRead) because
  * SamplingResponse contains a McpContent field whose streaming reader uses the `fromStructureValue`
  * path rather than `serializeRead`; round-trip correctness for the full record is validated
  * through the integration test at McpSamplingReverseTest.
  */
class McpSamplingStopReasonTypedTest extends Test:

    import McpServer.SamplingResponse.StopReason

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow

    "StopReason.EndTurn encodes to \"endTurn\"" in {
        assert(encode[StopReason](StopReason.EndTurn) == "\"endTurn\"")
    }

    "StopReason.StopSequence encodes to \"stopSequence\"" in {
        assert(encode[StopReason](StopReason.StopSequence) == "\"stopSequence\"")
    }

    "StopReason.MaxTokens encodes to \"maxTokens\"" in {
        assert(encode[StopReason](StopReason.MaxTokens) == "\"maxTokens\"")
    }

    "StopReason round-trips EndTurn" in {
        val decoded = decode[StopReason](encode[StopReason](StopReason.EndTurn))
        assert(decoded == StopReason.EndTurn)
    }

    "StopReason round-trips StopSequence" in {
        val decoded = decode[StopReason](encode[StopReason](StopReason.StopSequence))
        assert(decoded == StopReason.StopSequence)
    }

    "StopReason round-trips MaxTokens" in {
        val decoded = decode[StopReason](encode[StopReason](StopReason.MaxTokens))
        assert(decoded == StopReason.MaxTokens)
    }

    "unknown stopReason wire string decodes to EndTurn (tolerant decode)" in {
        val decoded = decode[StopReason]("\"unknownFutureValue\"")
        assert(decoded == StopReason.EndTurn)
    }

    "SamplingResponse encodes stopReason field correctly" in {
        val resp = McpServer.SamplingResponse(
            role = McpContent.Role.Assistant,
            content = McpContent.text("hi"),
            model = "m",
            stopReason = Present(StopReason.EndTurn)
        )
        val json = encode[McpServer.SamplingResponse](resp)
        assert(json.contains("\"endTurn\""), s"expected endTurn in JSON, got: $json")
        assert(json.contains("\"stopReason\""), s"expected stopReason key in JSON, got: $json")
    }

    "SamplingResponse with Absent stopReason omits the field" in {
        val resp = McpServer.SamplingResponse(
            role = McpContent.Role.Assistant,
            content = McpContent.text("hi"),
            model = "m",
            stopReason = Absent
        )
        val json = encode[McpServer.SamplingResponse](resp)
        assert(!json.contains("stopReason"), s"expected no stopReason in JSON for Absent, got: $json")
    }

    "SamplingResponse.stopReason is typed Maybe[StopReason] not Maybe[String]" in {
        // Compile-time check: stopReason field has the correct type.
        val resp = McpServer.SamplingResponse(
            role = McpContent.Role.Assistant,
            content = McpContent.text("hi"),
            model = "m",
            stopReason = Present(StopReason.MaxTokens)
        )
        val sr: Maybe[StopReason] = resp.stopReason
        assert(sr == Present(StopReason.MaxTokens))
    }

end McpSamplingStopReasonTypedTest
