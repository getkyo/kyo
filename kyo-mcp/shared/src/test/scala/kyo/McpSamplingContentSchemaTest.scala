package kyo

/** Tests for the hand-rolled `Schema[McpServer.SamplingContent]` discriminator codec.
  *
  * Pins that a hostile wire discriminator (`"type":"video"`) must decode to
  * `Result.Failure`, never throw an uncaught exception escaping the receive loop.
  * The failure is routed through `McpEnumSchema.discriminatorMismatch`.
  */
class McpSamplingContentSchemaTest extends Test:

    private def decode(json: String): Result[DecodeException, McpServer.SamplingContent] =
        Json.decode[McpServer.SamplingContent](json)

    // hostile discriminator decodes to Failure
    "hostile SamplingContent discriminator decodes to Failure" in {
        val json = """{"type":"video","data":"abc","mimeType":"video/mp4"}"""
        val r    = decode(json)
        assert(r.isFailure)
    }

    // Valid text discriminator decodes to Success
    "valid type:text decodes to SamplingContent.Text" in {
        val json = """{"type":"text","text":"hello"}"""
        decode(json) match
            case Result.Success(McpServer.SamplingContent.Text(text, _)) =>
                assert(text == "hello")
            case other =>
                fail(s"unexpected result: $other")
        end match
    }

    // Valid image discriminator decodes to Success
    "valid type:image decodes to SamplingContent.Image" in {
        val json = """{"type":"image","data":"<b64>","mimeType":"image/png"}"""
        decode(json) match
            case Result.Success(McpServer.SamplingContent.Image(data, mime, _)) =>
                assert(data == "<b64>")
                assert(mime.asString == "image/png")
            case other =>
                fail(s"unexpected result: $other")
        end match
    }

    // Valid audio discriminator decodes to Success
    "valid type:audio decodes to SamplingContent.Audio" in {
        val json = """{"type":"audio","data":"<b64>","mimeType":"audio/mp3"}"""
        decode(json) match
            case Result.Success(McpServer.SamplingContent.Audio(data, mime, _)) =>
                assert(data == "<b64>")
                assert(mime.asString == "audio/mp3")
            case other =>
                fail(s"unexpected result: $other")
        end match
    }

end McpSamplingContentSchemaTest
