package kyo

/** Tests for ToolOutcome named constructors and blank-dropping smart constructors.
  *
  * Covers ok/error isError semantics, blank-drop for ToolOutcome.content and
  * PromptMessage.messages, and structuredContentAs/metaAs decode projections.
  */
class McpToolOutcomeTest extends Test:

    case class Sum(v: Int) derives Schema, CanEqual

    // ToolOutcome.error sets isError true
    "ToolOutcome.error sets isError true" in {
        val t = McpHandler.ToolOutcome.error("boom")
        assert(t.isError)
        assert(t.content.nonEmpty)
        t.content.head match
            case McpContent.Text(text, _) => assert(text == "boom")
            case _                        => fail("expected Text leaf")
    }

    // ToolOutcome.ok sets isError false
    "ToolOutcome.ok sets isError false" in {
        val t = McpHandler.ToolOutcome.ok(McpContent.text("hi"))
        assert(!t.isError)
        assert(t.content.nonEmpty)
    }

    // ToolOutcome.ok drops blank text leaves
    "ToolOutcome.ok drops a blank text leaf" in {
        val t = McpHandler.ToolOutcome.ok(McpContent.text("hi"), McpContent.text("   "), McpContent.text(""))
        assert(t.content == Chunk(McpContent.text("hi")))
    }

    // ToolOutcome.content smart ctor drops blank leaves
    "ToolOutcome.content drops empty text, retains non-text leaf" in {
        val img   = McpContent.Image("<b64>", McpMimeType("image/png"))
        val chunk = McpHandler.ToolOutcome.content(McpContent.text(""), img, McpContent.text("  "))
        assert(chunk == Chunk(img))
    }

    // PromptMessage.messages drops blank-text message
    "PromptMessage.messages drops a blank-text message" in {
        val good = McpHandler.PromptMessage(McpContent.Role.User, McpContent.text("q"))
        val bad  = McpHandler.PromptMessage(McpContent.Role.User, McpContent.text("  "))
        val ms   = McpHandler.PromptMessage.messages(good, bad)
        assert(ms == Chunk(good))
    }

    // PromptMessage.messages retains non-text content
    "PromptMessage.messages retains a message whose content is not text" in {
        val img = McpContent.Image("<b64>", McpMimeType("image/png"))
        val m   = McpHandler.PromptMessage(McpContent.Role.User, img)
        val ms  = McpHandler.PromptMessage.messages(m)
        assert(ms == Chunk(m))
    }

    // structuredContentAs decodes Present
    "structuredContentAs decodes Present structuredContent" in {
        val sv = Structure.encode[Sum](Sum(5))
        val t  = McpHandler.ToolOutcome(Chunk.empty, isError = false, structuredContent = Present(sv))
        Abort.run[McpDecodeException](t.structuredContentAs[Sum]).map { r =>
            assert(r == Result.Success(Present(Sum(5))))
        }
    }

    // structuredContentAs returns Absent when no structuredContent
    "structuredContentAs returns Absent when structuredContent is Absent" in {
        val t = McpHandler.ToolOutcome(Chunk.empty, isError = false, structuredContent = Absent)
        Abort.run[McpDecodeException](t.structuredContentAs[Sum]).map { r =>
            assert(r == Result.Success(Absent))
        }
    }

    // structuredContentAs aborts McpDecodeException on wrong shape
    "structuredContentAs aborts McpDecodeException on non-conforming payload" in {
        val sv = Structure.encode[String]("notASum")
        val t  = McpHandler.ToolOutcome(Chunk.empty, isError = false, structuredContent = Present(sv))
        Abort.run[McpDecodeException](t.structuredContentAs[Sum]).map { r =>
            assert(r.isFailure)
        }
    }

end McpToolOutcomeTest
