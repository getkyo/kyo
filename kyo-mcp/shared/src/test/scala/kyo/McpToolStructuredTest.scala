package kyo

/** Tests for the server-side structured tool output (producer half).
  *
  * Asserts that `McpHandler.tool[In,Out]` advertises `outputSchema = Present(...)` in the
  * carrier's `ToolMeta`. The client-side decode (the consumer half) is decoded on the client side.
  */
class McpToolStructuredTest extends Test:

    case class AddIn(a: Int, b: Int) derives Schema, CanEqual
    case class Sum(result: Int) derives Schema, CanEqual

    // (server half): tool[In,Out] advertises outputSchema = Present(...)
    "tool[In,Out] advertises outputSchema Present" in {
        val h = McpHandler.tool[AddIn]("add") { in =>
            Sum(in.a + in.b)
        }
        h match
            case th: McpHandler.ToolHandler[?, ?, ?] =>
                assert(th.toolMeta.outputSchema.isDefined)
            case _ =>
                fail("expected ToolHandler")
        end match
    }

    // toolRaw does NOT advertise outputSchema (no Out type)
    "toolRaw does not advertise outputSchema" in {
        val h = McpHandler.toolRaw[AddIn]("addRaw") { in =>
            McpHandler.ToolOutcome.ok(McpContent.text(s"${in.a + in.b}"))
        }
        h match
            case th: McpHandler.ToolMultiHandler[?, ?] =>
                assert(!th.toolMeta.outputSchema.isDefined)
            case _ =>
                fail("expected ToolMultiHandler")
        end match
    }

    // tool[In,Out] outputSchema matches the Json.jsonSchema of Out
    "tool[In,Out] outputSchema matches Json.jsonSchema[Sum]" in {
        val h = McpHandler.tool[AddIn]("add2") { in =>
            Sum(in.a + in.b)
        }
        h match
            case th: McpHandler.ToolHandler[?, ?, ?] =>
                val expected = Json.jsonSchema[Sum]
                assert(th.toolMeta.outputSchema == Present(expected))
            case _ =>
                fail("expected ToolHandler")
        end match
    }

end McpToolStructuredTest
