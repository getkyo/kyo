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
        val resource = McpHandler.ResourceContents.Text(uri, Absent, "body")
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
        val resource = McpHandler.ResourceContents.Text(uri, Absent, "resource body")
        val content  = McpContent.EmbeddedResource(resource)
        assert(roundtrip[McpContent](content) == content)
    }

    "Text with annotations round-trips correctly" in {
        val ann     = McpContent.Annotations(Present(Chunk(McpContent.Role.User)), Absent)
        val content = McpContent.Text("hello", ann)
        assert(roundtrip[McpContent](content) == content)
    }

    "Schema singleton: summon twice yields same reference (INV-013)" in {
        val s1 = summon[Schema[McpContent]]
        val s2 = summon[Schema[McpContent]]
        assert(s1 eq s2)
    }

    "ResourceLink: wire JSON contains type:resource_link" in {
        val uri     = McpResourceUri("file:///a")
        val content = McpContent.ResourceLink(uri, "a-file")
        val json    = encodedJson[McpContent](content)
        assert(json.contains("\"type\":\"resource_link\""))
        assert(json.contains("file:///a"))
        assert(json.contains("\"name\":\"a-file\""))
    }

    "ResourceLink round-trip preserves fields" in {
        val uri     = McpResourceUri("file:///a")
        val content = McpContent.ResourceLink(uri, "a-file", Absent, Absent, McpContent.Annotations.noop)
        assert(roundtrip[McpContent](content) == content)
    }

    "ResourceLink with description round-trips" in {
        val uri     = McpResourceUri("file:///b")
        val content = McpContent.ResourceLink(uri, "b-file", Present("A description"), Absent)
        assert(roundtrip[McpContent](content) == content)
    }

    "ResourceLink with mimeType round-trips" in {
        val uri     = McpResourceUri("file:///c")
        val content = McpContent.ResourceLink(uri, "c-file", Absent, Present(McpMimeType("text/plain")))
        assert(roundtrip[McpContent](content) == content)
    }

    "ResourceLink omits absent Maybe fields from wire JSON" in {
        val uri     = McpResourceUri("file:///d")
        val content = McpContent.ResourceLink(uri, "d-file", Absent, Absent)
        val json    = encodedJson[McpContent](content)
        assert(!json.contains("\"description\""))
        assert(!json.contains("\"mimeType\""))
    }

    "ResourceLink with annotations round-trips" in {
        val uri     = McpResourceUri("file:///e")
        val ann     = McpContent.Annotations(Present(Chunk(McpContent.Role.User)), Absent)
        val content = McpContent.ResourceLink(uri, "e-file", Absent, Absent, ann)
        assert(roundtrip[McpContent](content) == content)
    }

end McpContentTest
