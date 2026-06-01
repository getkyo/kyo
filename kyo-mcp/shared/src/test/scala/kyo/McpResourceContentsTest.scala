package kyo

/** Tests for `Schema[McpRoute.ResourceContents]` hand-rolled tagged-union Schema (Phase 3).
  *
  * Pins INV-006 (discriminator key `"type"` with tags `"text"` and `"blob"`) and
  * INV-022 (uri field is typed `McpResourceUri`).
  */
class McpResourceContentsTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def roundtrip[A: Schema](value: A): A =
        val encoded = Structure.encode[A](value)
        Structure.decode[A](encoded).getOrElse(fail(s"decode failed for $value"))

    private def encodedJson[A: Schema](value: A): String =
        Json.encode[A](value)

    val sampleUri: McpResourceUri = McpResourceUri.apply("file:///x")

    "Text: wire JSON contains type:text" in {
        val rc   = McpRoute.ResourceContents.Text(sampleUri, Absent, "hello")
        val json = encodedJson[McpRoute.ResourceContents](rc)
        assert(json.contains("\"type\":\"text\""))
        assert(json.contains("\"hello\""))
    }

    "Blob: wire JSON contains type:blob" in {
        val rc   = McpRoute.ResourceContents.Blob(sampleUri, Absent, "<b64>")
        val json = encodedJson[McpRoute.ResourceContents](rc)
        assert(json.contains("\"type\":\"blob\""))
        assert(json.contains("\"<b64>\""))
    }

    "Text round-trip preserves fields" in {
        val rc = McpRoute.ResourceContents.Text(sampleUri, Absent, "hello world")
        assert(roundtrip[McpRoute.ResourceContents](rc) == rc)
    }

    "Blob round-trip preserves fields" in {
        val rc = McpRoute.ResourceContents.Blob(sampleUri, Absent, "aGVsbG8=")
        assert(roundtrip[McpRoute.ResourceContents](rc) == rc)
    }

    "Text with mimeType round-trips correctly" in {
        val rc = McpRoute.ResourceContents.Text(sampleUri, Present(McpMimeType("text/plain")), "content")
        assert(roundtrip[McpRoute.ResourceContents](rc) == rc)
    }

    "Blob with mimeType round-trips correctly" in {
        val rc = McpRoute.ResourceContents.Blob(sampleUri, Present(McpMimeType("application/octet-stream")), "aGVsbG8=")
        assert(roundtrip[McpRoute.ResourceContents](rc) == rc)
    }

    "uri field carries McpResourceUri type (compile-time evidence)" in {
        val rc: McpRoute.ResourceContents = McpRoute.ResourceContents.Text(sampleUri, Absent, "body")
        // Compile-time check: rc.uri is McpResourceUri, not String.
        val _: McpResourceUri = rc.uri
        assert(rc.uri == sampleUri)
    }

    "custom URI scheme round-trips correctly" in {
        val uri = McpResourceUri.apply("custom://my-server/resource-id")
        val rc  = McpRoute.ResourceContents.Text(uri, Absent, "data")
        assert(roundtrip[McpRoute.ResourceContents](rc) == rc)
    }

end McpResourceContentsTest
