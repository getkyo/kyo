package kyo

/** Tests for the hand-rolled `Schema[McpHandler.CompletionRef]` discriminator codec.
  *
  * Pins that a hostile wire discriminator (`"type":"ref/tool"`) must decode to
  * `Result.Failure`, never throw an uncaught exception escaping the receive loop.
  * The failure is routed through `McpEnumSchema.discriminatorMismatch`.
  */
class McpCompletionRefSchemaTest extends Test:

    private def decode(json: String): Result[DecodeException, McpHandler.CompletionRef] =
        Json.decode[McpHandler.CompletionRef](json)

    // hostile discriminator decodes to Failure
    "hostile CompletionRef discriminator decodes to Failure" in {
        val json = """{"type":"ref/tool","name":"myTool"}"""
        val r    = decode(json)
        assert(r.isFailure)
    }

    // Valid ref/prompt discriminator decodes to Success
    "valid type:ref/prompt decodes to CompletionRef.Prompt" in {
        val json = """{"type":"ref/prompt","name":"myPrompt"}"""
        decode(json) match
            case Result.Success(McpHandler.CompletionRef.Prompt(name)) =>
                assert(name == "myPrompt")
            case other =>
                fail(s"unexpected result: $other")
        end match
    }

    // Valid ref/resource discriminator decodes to Success
    "valid type:ref/resource decodes to CompletionRef.Resource" in {
        val json = """{"type":"ref/resource","uri":"file:///r"}"""
        decode(json) match
            case Result.Success(McpHandler.CompletionRef.Resource(uri)) =>
                assert(uri.asString == "file:///r")
            case other =>
                fail(s"unexpected result: $other")
        end match
    }

end McpCompletionRefSchemaTest
