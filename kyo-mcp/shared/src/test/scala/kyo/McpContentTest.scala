package kyo

/** Tests for `Schema[McpContent]` hand-rolled discriminator Schema (Phase 3).
  *
  * Pins INV-006 (discriminator key `"type"` with exact tags) and
  * INV-013 (Schema singleton reference equality).
  */
class McpContentTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def roundtrip[A: Schema](value: A): A =
        val encoded = Structure.encode[A](value)
        Structure.decode[A](encoded).getOrElse(fail(s"decode failed for $value"))

    private def encodedJson[A: Schema](value: A): String =
        Json.encode[A](value)

    "Text: wire JSON contains type:text" in {
        val content = McpContent.Text("hello")
        val json    = encodedJson[McpContent](content)
        assert(json.contains("\"type\":\"text\""))
        assert(json.contains("\"text\":\"hello\""))
    }

    "Image: wire JSON contains type:image" in {
        val content = McpContent.Image("<b64>", McpMimeType("image/png"))
        val json    = encodedJson[McpContent](content)
        assert(json.contains("\"type\":\"image\""))
        assert(json.contains("\"image/png\""))
    }

    "Audio: wire JSON contains type:audio" in {
        val content = McpContent.Audio("<b64>", McpMimeType("audio/mp3"))
        val json    = encodedJson[McpContent](content)
        assert(json.contains("\"type\":\"audio\""))
        assert(json.contains("\"audio/mp3\""))
    }

    "EmbeddedResource: wire JSON contains type:resource" in {
        val uri      = McpResourceUri.apply("file:///x")
        val resource = McpResourceContents.Text(uri, Absent, "body")
        val content  = McpContent.EmbeddedResource(resource)
        val json     = encodedJson[McpContent](content)
        assert(json.contains("\"type\":\"resource\""))
        assert(json.contains("\"resource\""))
    }

    "Text round-trip preserves fields" in {
        val content = McpContent.Text("hello world")
        assert(roundtrip[McpContent](content) == content)
    }

    "Image round-trip preserves fields" in {
        val content = McpContent.Image("base64data", McpMimeType("image/jpeg"))
        assert(roundtrip[McpContent](content) == content)
    }

    "Audio round-trip preserves fields" in {
        val content = McpContent.Audio("base64audio", McpMimeType("audio/wav"))
        assert(roundtrip[McpContent](content) == content)
    }

    "EmbeddedResource round-trip preserves fields" in {
        val uri      = McpResourceUri.apply("file:///path/to/resource")
        val resource = McpResourceContents.Text(uri, Absent, "resource body")
        val content  = McpContent.EmbeddedResource(resource)
        assert(roundtrip[McpContent](content) == content)
    }

    "Text with annotations round-trips correctly" in {
        val ann     = McpContent.Annotations(Present(Chunk(McpRole.User)), Absent)
        val content = McpContent.Text("hello", ann)
        assert(roundtrip[McpContent](content) == content)
    }

    "Schema singleton: summon twice yields same reference (INV-013)" in {
        val s1 = summon[Schema[McpContent]]
        val s2 = summon[Schema[McpContent]]
        assert(s1 eq s2)
    }

end McpContentTest
