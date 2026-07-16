package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.Image

class HarnessCompletionTest extends kyo.test.Test[Any]:

    case class CodexSchemaProbe(scores: Map[String, Int], note: Maybe[String]) derives Schema
    case class CodexPromptAnswer(marker: String, primaryLabel: String, reminderLabel: String) derives Schema
    case class CodexToolAnswer(marker: String, status: String, etaDays: Int, toolUsed: Boolean) derives Schema
    case class CodexOrderQuery(orderId: Int) derives Schema

    "ClaudeCodeCompletion.inputJsonLines preserves roles, images, and native Kyo tool transcript records" in {
        val image = Image.fromBytes(Span.from(Array[Byte](1, 2, 3)))
        val ctx = Context.empty
            .userMessage("look", Present(image))
            .assistantMessage("calling", Chunk(Call(CallId("c1"), "lookup", """{"q":"kyo"}""")))
            .toolMessage(CallId("c1"), """{"answer":"Kyo"}""")

        Abort.run[AIGenException](ClaudeCodeCompletion.inputJsonLines(ctx.messages)).map {
            case Result.Success(lines) =>
                assert(lines.contains("\"role\":\"user\""), s"user role missing: $lines")
                assert(lines.contains("\"role\":\"assistant\""), s"assistant role missing: $lines")
                assert(lines.contains("\"type\":\"image\""), s"image block missing: $lines")
                assert(lines.contains("\"media_type\":\"image/jpeg\""), s"image media type missing: $lines")
                assert(lines.contains("\"data\":\"AQID\""), s"image data missing: $lines")
                assert(lines.contains("\"type\":\"tool_use\""), s"tool_use block missing: $lines")
                assert(lines.contains("\"id\":\"c1\""), s"tool call id missing: $lines")
                assert(lines.contains("\"name\":\"mcp__kyo__lookup\""), s"MCP tool function missing: $lines")
                assert(lines.contains("\"input\":{\"q\":\"kyo\"}"), s"tool arguments missing: $lines")
                assert(lines.contains("\"type\":\"tool_result\""), s"tool_result block missing: $lines")
                assert(lines.contains("\"tool_use_id\":\"c1\""), s"tool result id missing: $lines")
                assert(lines.contains("answer") && lines.contains("Kyo"), s"tool output missing: $lines")
                assert(lines.contains("\"is_error\":false"), s"successful tool result should not be marked as an error: $lines")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "ClaudeCodeCompletion.inputJsonLines marks failed Kyo tool results in structured transcript records" in {
        val ctx = Context.empty
            .assistantMessage("calling", Chunk(Call(CallId("c1"), "lookup", """{"q":"kyo"}""")))
            .toolMessage(
                CallId("c1"),
                """Tool 'lookup' failed:
                  |temporary lookup failure
                  |Call ID: c1""".stripMargin
            )

        Abort.run[AIGenException](ClaudeCodeCompletion.inputJsonLines(ctx.messages)).map {
            case Result.Success(lines) =>
                assert(lines.contains("\"type\":\"tool_result\""), s"tool_result block missing: $lines")
                assert(lines.contains("\"tool_use_id\":\"c1\""), s"tool result id missing: $lines")
                assert(lines.contains("\"is_error\":true"), s"failed tool result should be marked as an error: $lines")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "ClaudeCodeCompletion.inputJsonLines replays result_tool as assistant history record" in {
        val ctx = Context.empty
            .assistantMessage(
                "",
                Chunk(Call(CallId("harness-result"), Completion.resultToolName, """{"resultValue":{"status":"ready","etaDays":3}}"""))
            )
            .toolMessage(CallId("harness-result"), "{}")

        Abort.run[AIGenException](ClaudeCodeCompletion.inputJsonLines(ctx.messages)).map {
            case Result.Success(lines) =>
                assert(lines.contains("Kyo history record for one previous assistant result"), s"history record missing: $lines")
                assert(lines.contains("status = ready"), s"status field missing: $lines")
                assert(lines.contains("etaDays = 3"), s"etaDays field missing: $lines")
                assert(!lines.contains("resultValue"), s"internal result envelope should not be model-visible: $lines")
                assert(!lines.contains("Application data returned for request harness-result"), s"internal result output leaked: $lines")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "CodexCompletion.historyItems preserves raw Responses items, images, calls, and tool outputs" in {
        val image = Image.fromBytes(Span.from(Array[Byte](1, 2, 3)))
        val ctx = Context.empty
            .systemMessage("system stays in base instructions")
            .userMessage("look", Present(image))
            .assistantMessage("calling", Chunk(Call(CallId("c1"), "lookup", """{"q":"kyo"}""")))
            .toolMessage(CallId("c1"), """{"answer":"Kyo"}""")

        Abort.run[AIGenException](CodexCompletion.historyItems(ctx.messages)).map {
            case Result.Success(items) =>
                val encoded = Json.encode(items.toList)
                assert(encoded.contains("\"type\":\"message\""), s"message item missing: $encoded")
                assert(encoded.contains("\"role\":\"user\""), s"user role missing: $encoded")
                assert(encoded.contains("\"type\":\"input_text\""), s"input_text block missing: $encoded")
                assert(encoded.contains("\"type\":\"input_image\""), s"input_image block missing: $encoded")
                assert(encoded.contains("data:image/jpeg;base64,AQID"), s"image data URL missing: $encoded")
                assert(encoded.contains("\"role\":\"assistant\""), s"assistant role missing: $encoded")
                assert(encoded.contains("\"type\":\"output_text\""), s"output_text block missing: $encoded")
                assert(encoded.contains("\"type\":\"function_call\""), s"function_call item missing: $encoded")
                assert(encoded.contains("\"call_id\":\"c1\""), s"call id missing: $encoded")
                assert(encoded.contains("\"name\":\"lookup\""), s"call name missing: $encoded")
                assert(encoded.contains("\"arguments\":\"{\\\"q\\\":\\\"kyo\\\"}\""), s"call arguments missing: $encoded")
                assert(encoded.contains("\"type\":\"function_call_output\""), s"function_call_output item missing: $encoded")
                assert(
                    !encoded.contains("system stays in base instructions"),
                    s"system message should not be injected as raw item: $encoded"
                )
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "CodexCompletion.appServerSchema relaxes schema constructs rejected by the app-server" in {
        val encoded = Json.encode(CodexCompletion.appServerSchema(Json.jsonSchema[CodexSchemaProbe]))
        assert(!encoded.contains("\"oneOf\""), s"nullable fields should not be sent as oneOf to Codex app-server: $encoded")
        assert(encoded.contains("\"additionalProperties\":true"), s"map fields should remain open objects: $encoded")
        assert(encoded.contains("\"note\":{\"type\":\"string\""), s"present nullable values should keep their value schema: $encoded")
    }

    "CodexCompletion.outputSchemaFor exposes native result and tool-call objects to the app-server" in {
        val direct = Json.encode(CodexCompletion.outputSchemaFor(Chunk.empty, Json.jsonSchema[CodexPromptAnswer]))
        assert(direct.contains("\"marker\""), s"direct result schema should expose result fields: $direct")
        assert(!direct.contains("\"resultValue\""), s"direct result schema should not force an internal envelope: $direct")
        assert(
            !direct.contains("JSON string whose parsed value matches this schema"),
            s"direct result schema should not string-encode JSON: $direct"
        )

        val lookupOrder = Tool.init[CodexOrderQuery](
            "lookup_order",
            "Look up an order by id. Use this tool whenever an order status or ETA is requested."
        )(_ => ())
        val tools    = lookupOrder.infos ++ Tool.internal.resultToolInfo.infos
        val toolTurn = Json.encode(CodexCompletion.outputSchemaFor(tools, Json.jsonSchema[CodexToolAnswer]))
        assert(toolTurn.contains("\"lookup_order\""), s"tool schema should include the user tool: $toolTurn")
        assert(toolTurn.contains("\"result_tool\""), s"tool schema should include the result tool: $toolTurn")
        assert(toolTurn.contains("\"orderId\""), s"tool schema should expose native tool arguments: $toolTurn")
        assert(toolTurn.contains("\"marker\""), s"result tool schema should expose native result fields: $toolTurn")
        assert(!toolTurn.contains("\"resultValue\""), s"tool schema should not force the internal result envelope: $toolTurn")
        assert(
            !toolTurn.contains("JSON string whose parsed value matches this schema"),
            s"tool schema should not string-encode JSON: $toolTurn"
        )
    }

    "CodexCompletion.readStructuredOutput accepts top-level assistant message arrays" in {
        val raw = """[{"content":"","calls":[{"id":"call_1","function":"lookup_order","arguments":{"orderId":733}}]}]"""
        Abort.run[AIGenException](CodexCompletion.readStructuredOutput(raw)).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.content == "")
                assert(msg.calls.size == 1)
                assert(msg.calls.head.id == CallId("call_1"))
                assert(msg.calls.head.function == "lookup_order")
                assert(msg.calls.head.arguments == """{"orderId":733}""")
            case other =>
                fail(s"expected assistant tool call message, got: $other")
        }
    }

    "CodexCompletion.readStructuredOutput repairs Codex fused id and empty function fields" in {
        val raw =
            """{"messages":[{"content":"","calls":[{"id":"call_1function","":"prompted_lookup","arguments":{"code":"tool_prompt_secret_503"}}]}]}"""
        Abort.run[AIGenException](CodexCompletion.readStructuredOutput(raw)).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.content == "")
                assert(msg.calls.size == 1)
                assert(msg.calls.head.id == CallId("call_1"))
                assert(msg.calls.head.function == "prompted_lookup")
                assert(msg.calls.head.arguments == """{"code":"tool_prompt_secret_503"}""")
            case other =>
                fail(s"expected normalized assistant tool call message, got: $other")
        }
    }

    "ClaudeCodeCompletion.readMessages returns native tool_use blocks as Kyo tool calls" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"checking"},{"type":"tool_use","id":"toolu_1","name":"lookup","input":{"q":"kyo"}}]}}"""

        Abort.run[AIGenException](ClaudeCodeCompletion.readMessages(output)).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.content == "checking")
                assert(msg.calls.size == 1)
                assert(msg.calls.head.id == CallId("toolu_1"))
                assert(msg.calls.head.function == "lookup")
                assert(msg.calls.head.arguments == """{"q":"kyo"}""")
            case other =>
                fail(s"expected assistant tool call message, got: $other")
        }
    }

    "ClaudeCodeCompletion.readMessages wraps native result_tool calls in the result envelope" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"r1","name":"result_tool","input":{"answer":"ok"}}]}}"""

        Abort.run[AIGenException](ClaudeCodeCompletion.readMessages(output)).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.calls.head.function == Completion.resultToolName)
                assert(msg.calls.head.arguments == """{"resultValue":{"answer":"ok"}}""")
            case other =>
                fail(s"expected native result_tool call, got: $other")
        }
    }

    "ClaudeCodeCompletion.readMessages decodes stringified resultValue objects" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"r1","name":"result_tool","input":{"resultValue":"{\"answer\":\"ok\"}"}}]}}"""

        Abort.run[AIGenException](ClaudeCodeCompletion.readMessages(output)).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.calls.head.function == Completion.resultToolName)
                assert(msg.calls.head.arguments == """{"resultValue":{"answer":"ok"}}""")
            case other =>
                fail(s"expected decoded stringified resultValue, got: $other")
        }
    }

    "ClaudeCodeCompletion.readMessages decodes StructuredOutput direct results" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_result","name":"StructuredOutput","input":{"answer":"ok"}}]}}"""

        Abort.run[AIGenException](ClaudeCodeCompletion.readMessages(output)).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.content == "")
                assert(msg.calls.size == 1)
                assert(msg.calls.head.id == CallId("harness-result"))
                assert(msg.calls.head.function == Completion.resultToolName)
                assert(msg.calls.head.arguments == """{"resultValue":{"answer":"ok"}}""")
            case other =>
                fail(s"expected structured output message, got: $other")
        }
    }

    "ClaudeCodeCompletion.readMessages preserves native tool transcript before final result" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"checking"},{"type":"tool_use","id":"toolu_1","name":"lookup","input":{"q":"kyo"}}]}}
              |{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"{\"answer\":\"ok\"}"}]}}
              |{"type":"result","subtype":"success","result":"{\"answer\":\"ok\"}"}""".stripMargin

        Abort.run[AIGenException](ClaudeCodeCompletion.readMessages(output)).map {
            case Result.Success(Chunk(callMsg: AssistantMessage, toolMsg: ToolMessage, resultMsg: AssistantMessage)) =>
                assert(callMsg.content == "checking")
                assert(callMsg.calls.head.id == CallId("toolu_1"))
                assert(callMsg.calls.head.function == "lookup")
                assert(callMsg.calls.head.arguments == """{"q":"kyo"}""")
                assert(toolMsg.callId == CallId("toolu_1"))
                assert(toolMsg.content == """{"answer":"ok"}""")
                assert(resultMsg.calls.head.function == Completion.resultToolName)
                assert(resultMsg.calls.head.arguments == """{"resultValue":{"answer":"ok"}}""")
            case other =>
                fail(s"expected MCP transcript and final result, got: $other")
        }
    }

    "resultOutput converts a direct structured result to the result_tool call shape" in {
        Abort.run[AIGenException](HarnessCompletion.resultOutput("test", """{"answer":"ok"}""")).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.calls.head.function == Completion.resultToolName)
                assert(msg.calls.head.arguments == """{"resultValue":{"answer":"ok"}}""")
            case other =>
                fail(s"expected one assistant result_tool message, got: $other")
        }
    }

    "resultOutput decodes stringified resultValue objects" in {
        Abort.run[AIGenException](HarnessCompletion.resultOutput("test", """{"resultValue":"{\"answer\":\"ok\"}"}""")).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.calls.head.function == Completion.resultToolName)
                assert(msg.calls.head.arguments == """{"resultValue":{"answer":"ok"}}""")
            case other =>
                fail(s"expected decoded direct result output, got: $other")
        }
    }

    "resultOutput unwraps stringified resultValue envelopes" in {
        Abort.run[AIGenException](HarnessCompletion.resultOutput("test", """{"resultValue":"{\"resultValue\":{\"answer\":\"ok\"}}"}"""))
            .map {
                case Result.Success(Chunk(msg: AssistantMessage)) =>
                    assert(msg.calls.head.function == Completion.resultToolName)
                    assert(msg.calls.head.arguments == """{"resultValue":{"answer":"ok"}}""")
                case other =>
                    fail(s"expected decoded direct result output, got: $other")
            }
    }

    "resultOutput normalizes Codex prefixed empty-key direct results" in {
        Abort.run[AIGenException](HarnessCompletion.resultOutput("test", """resultValue{"":{"answer":"ok"}}""")).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.calls.head.function == Completion.resultToolName)
                assert(msg.calls.head.arguments == """{"resultValue":{"answer":"ok"}}""")
            case other =>
                fail(s"expected normalized direct result output, got: $other")
        }
    }

end HarnessCompletionTest
