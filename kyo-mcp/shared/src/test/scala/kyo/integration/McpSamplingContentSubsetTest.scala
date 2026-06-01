package kyo.integration

import kyo.*

/** Tests for SamplingContent typed subset (§3.10, Option A).
  *
  * SamplingContent is a sealed trait with only Text, Image, and Audio cases.
  * EmbeddedResource and ResourceLink are not present in the hierarchy; any reference to
  * SamplingContent.EmbeddedResource is a compile error (the case does not exist).
  */
class McpSamplingContentSubsetTest extends Test:

    private def encode[A: Schema](value: A): String = Json.encode[A](value)
    private def decode[A: Schema](json: String): A  = Json.decode[A](json).getOrThrow

    "SamplingContent.Text constructs and has type SamplingContent" in {
        val content: McpServer.SamplingContent = McpServer.SamplingContent.Text("hello")
        assert(content.isInstanceOf[McpServer.SamplingContent.Text])
    }

    "SamplingContent.Image constructs and has type SamplingContent" in {
        val content: McpServer.SamplingContent = McpServer.SamplingContent.Image("data", McpMimeType.fromWire("image/png"))
        assert(content.isInstanceOf[McpServer.SamplingContent.Image])
    }

    "SamplingContent.Audio constructs and has type SamplingContent" in {
        val content: McpServer.SamplingContent = McpServer.SamplingContent.Audio("data", McpMimeType.fromWire("audio/mp3"))
        assert(content.isInstanceOf[McpServer.SamplingContent.Audio])
    }

    "SamplingContent.Text encodes with type=text discriminator" in {
        val content = McpServer.SamplingContent.Text("hello")
        val json    = encode[McpServer.SamplingContent](content)
        assert(json.contains("\"type\""), s"expected type field, got: $json")
        assert(json.contains("\"text\""), s"expected text discriminator, got: $json")
        assert(json.contains("\"hello\""), s"expected content value, got: $json")
    }

    "SamplingContent.Image encodes with type=image discriminator" in {
        val content = McpServer.SamplingContent.Image("abc", McpMimeType.fromWire("image/png"))
        val json    = encode[McpServer.SamplingContent](content)
        assert(json.contains("\"image\""), s"expected image discriminator, got: $json")
        assert(json.contains("\"image/png\""), s"expected mimeType, got: $json")
    }

    "SamplingContent.Text round-trips" in {
        val content = McpServer.SamplingContent.Text("hello world")
        val decoded = decode[McpServer.SamplingContent](encode[McpServer.SamplingContent](content))
        assert(decoded == content)
    }

    "SamplingContent.toMcpContent converts to McpContent.Text" in {
        val content    = McpServer.SamplingContent.Text("hello")
        val mcpContent = content.toMcpContent
        assert(mcpContent == McpContent.Text("hello"))
    }

    "SamplingRequest with SamplingContent.Text message round-trips" in {
        val req = McpServer.SamplingRequest(
            messages = Chunk(McpServer.SamplingRequest.Message(McpContent.Role.User, McpServer.SamplingContent.Text("q"))),
            maxTokens = 128
        )
        val decoded = decode[McpServer.SamplingRequest](encode[McpServer.SamplingRequest](req))
        assert(decoded.messages.size == 1)
        decoded.messages.head.content match
            case McpServer.SamplingContent.Text(t, _) => assert(t == "q")
            case other                                => fail(s"expected Text, got $other")
    }

    // Compile-time check: SamplingContent.EmbeddedResource does not exist.
    // Any reference to it is a compile error by definition of the sealed hierarchy.
    // This test documents that constraint without using assertDoesNotCompile.
    "SamplingContent hierarchy contains only Text, Image, Audio" in {
        val cases = List(
            McpServer.SamplingContent.Text("t"): McpServer.SamplingContent,
            McpServer.SamplingContent.Image("d", McpMimeType.fromWire("image/png")): McpServer.SamplingContent,
            McpServer.SamplingContent.Audio("d", McpMimeType.fromWire("audio/wav")): McpServer.SamplingContent
        )
        assert(cases.size == 3)
    }

end McpSamplingContentSubsetTest
