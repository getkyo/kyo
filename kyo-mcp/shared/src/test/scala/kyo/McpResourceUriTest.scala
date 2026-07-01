package kyo

/** Tests for the `McpResourceUri` smart constructor and Schema.
  *
  * Pins that `McpResourceUri` is the typed representation used throughout the public surface.
  */
class McpResourceUriTest extends Test:

    given CanEqual[Any, Any] = CanEqual.canEqualAny

    private def roundtrip(uri: McpResourceUri)(using kyo.test.AssertScope, Frame): McpResourceUri =
        val encoded = Structure.encode[McpResourceUri](uri)
        Structure.decode[McpResourceUri](encoded).getOrElse(fail(s"decode failed for $uri"))

    "parse: Present for valid URI" in {
        val r = McpResourceUri.parse("file:///x")
        assert(r.isDefined)
        assert(r.get.asString == "file:///x")
    }

    "parse: Absent for empty string" in {
        assert(McpResourceUri.parse("").isEmpty)
    }

    "parse: Absent for whitespace-only string" in {
        assert(McpResourceUri.parse("   ").isEmpty)
    }

    "Schema round-trip of valid URI" in {
        val uri = McpResourceUri.apply("file:///x")
        assert(roundtrip(uri) == uri)
    }

    "Schema round-trip preserves custom scheme" in {
        val uri = McpResourceUri.apply("custom://my-server/resource-id")
        assert(roundtrip(uri) == uri)
    }

    "apply produces equal values for same string (CanEqual)" in {
        val a = McpResourceUri.apply("file:///x")
        val b = McpResourceUri.apply("file:///x")
        assert(a == b)
    }

    "asString returns the underlying string" in {
        val uri = McpResourceUri.apply("file:///path/to/resource")
        assert(uri.asString == "file:///path/to/resource")
    }

    "Schema encodes to the underlying string JSON" in {
        val uri  = McpResourceUri.apply("file:///x")
        val json = Json.encode[McpResourceUri](uri)
        assert(json == "\"file:///x\"")
    }

    "Schema decodes from a string JSON" in {
        val json = "\"file:///x\""
        val uri  = Json.decode[McpResourceUri](json).getOrThrow
        assert(uri.asString == "file:///x")
    }

    // Template.extract: RFC 6570 Level 1 variable extraction

    "Template.extract: single placeholder binds to suffix" in {
        val tmpl = McpResourceUri.Template("file:///{path}")
        val uri  = McpResourceUri("file:///foo/bar.txt")
        assert(tmpl.extract(uri) == Present(Map("path" -> "foo/bar.txt")))
    }

    "Template.extract: multiple placeholders bind in order" in {
        val tmpl = McpResourceUri.Template("users/{id}/posts/{postId}")
        val uri  = McpResourceUri("users/42/posts/7")
        assert(tmpl.extract(uri) == Present(Map("id" -> "42", "postId" -> "7")))
    }

    "Template.extract: returns Absent when literal prefix mismatches" in {
        val tmpl = McpResourceUri.Template("file:///{path}")
        val uri  = McpResourceUri("http://example.com/foo")
        assert(tmpl.extract(uri) == Absent)
    }

    "Template.extract: returns Absent when placeholder cannot match empty" in {
        val tmpl = McpResourceUri.Template("file:///{path}")
        val uri  = McpResourceUri("file:///")
        assert(tmpl.extract(uri) == Absent)
    }

    "Template.extract: escapes literal regex metacharacters in the template" in {
        val tmpl = McpResourceUri.Template("api+v2://{op}")
        val uri  = McpResourceUri("api+v2://get/all")
        assert(tmpl.extract(uri) == Present(Map("op" -> "get/all")))
    }

end McpResourceUriTest
