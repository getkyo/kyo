package kyo.integration

import kyo.*

/** Tests §3.3 MCP 2025-06-18: `CompletionRef` encodes with the correct `"type"` discriminator
  * key (`"ref/prompt"` or `"ref/resource"`) and round-trips through the hand-rolled Schema.
  */
class McpCompletionRefWireTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def encodedJson[A: Schema](value: A): String = Json.encode[A](value)

    private def roundtrip[A: Schema](value: A)(using kyo.test.AssertScope, Frame): A =
        val encoded = Structure.encode[A](value)
        Structure.decode[A](encoded).getOrElse(fail(s"decode failed for $value"))

    "CompletionRef.Prompt encodes with type 'ref/prompt'" in {
        val ref  = McpHandler.CompletionRef.Prompt("my-prompt")
        val json = encodedJson[McpHandler.CompletionRef](ref)
        assert(json.contains("\"type\":\"ref/prompt\""))
        assert(json.contains("\"name\":\"my-prompt\""))
    }

    "CompletionRef.Resource encodes with type 'ref/resource'" in {
        val ref  = McpHandler.CompletionRef.Resource(McpResourceUri("file:///my-resource"))
        val json = encodedJson[McpHandler.CompletionRef](ref)
        assert(json.contains("\"type\":\"ref/resource\""))
        assert(json.contains("file:///my-resource"))
    }

    "CompletionRef.Prompt round-trips" in {
        val ref = McpHandler.CompletionRef.Prompt("x")
        assert(roundtrip[McpHandler.CompletionRef](ref) == ref)
    }

    "CompletionRef.Resource round-trips" in {
        val ref = McpHandler.CompletionRef.Resource(McpResourceUri("file:///r"))
        assert(roundtrip[McpHandler.CompletionRef](ref) == ref)
    }

    "CompletionRef.Prompt with empty name encodes correctly" in {
        val ref  = McpHandler.CompletionRef.Prompt("")
        val json = encodedJson[McpHandler.CompletionRef](ref)
        assert(json.contains("\"type\":\"ref/prompt\""))
    }

    "Schema singleton reference is stable" in {
        val s1 = summon[Schema[McpHandler.CompletionRef]]
        val s2 = summon[Schema[McpHandler.CompletionRef]]
        assert(s1 eq s2)
    }

end McpCompletionRefWireTest
