package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.Json.JsonSchema
import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.Image

class CodexWireTest extends kyo.test.Test[Any]:

    case class CodexWireResultProbe(answer: String, note: Maybe[String]) derives Schema
    case class CodexWireUserToolInput(q: String, hint: Maybe[String]) derives Schema

    "the stderr tail keeps the END of the output, since a death message arrives last" in {
        // captureStderr appends each chunk and re-truncates, so the bound must drop the OLDEST bytes:
        // keeping the head would discard exactly the dying words the tail exists to preserve.
        val short = "Error: exited"
        assert(CodexWire.truncateStderr(short) == short, "output within the bound must be kept verbatim")
        val over = ("noise" * 3000) + "FINAL: the app-server died here"
        val kept = CodexWire.truncateStderr(over)
        assert(kept.length == 8192, s"an over-long tail must be bounded, got: ${kept.length}")
        assert(kept.endsWith("FINAL: the app-server died here"), "the most recent output must survive truncation")
        assert(kept == over.takeRight(8192), "the kept region must be the suffix, not an arbitrary window")
    }

    "dynamicToolSpecs leaves a user tool's optional field OUT of required (require-all is result-tool-only)" in {
        val userTool     = Tool.init[CodexWireUserToolInput]("lookup", "an optional-field tool")(_ => 1)
        val tools        = userTool.infos.concat(Tool.internal.resultToolDefinition.infos)
        val resultSchema = Thought.internal.resultJson(Chunk.empty, Json.jsonSchema[CodexWireResultProbe])

        val specs   = CodexWire.dynamicToolSpecs(tools, resultSchema)
        val lookup  = specs.filter(_.name == "lookup").head
        val encoded = Json.encode(lookup.inputSchema)
        assert(
            encoded.contains("\"required\":[\"q\"]"),
            s"a user tool's schema keeps only its non-optional fields required: $encoded"
        )
        assert(
            !encoded.contains("\"required\":[\"q\",\"hint\"]"),
            s"a user tool must never be require-all'd: $encoded"
        )
    }

    "dynamicToolSpecs registers every tool as a real function spec, the result tool with the require-all wire schema" in {
        val userTool     = Tool.init[Int]("lookup", "a lookup tool")(_ => 1)
        val tools        = userTool.infos.concat(Tool.internal.resultToolDefinition.infos)
        val resultSchema = Thought.internal.resultJson(Chunk.empty, Json.jsonSchema[CodexWireResultProbe])

        val specs = CodexWire.dynamicToolSpecs(tools, resultSchema)

        assert(specs.size == 2, s"every tool must register, the result tool included: $specs")
        assert(specs.forall(_.`type` == "function"), s"each spec is a function dynamic tool: $specs")
        val result = specs.filter(_.name == Completion.resultToolName).head
        assert(
            Json.encode(result.inputSchema).contains("\"resultValue\""),
            s"the result tool must advertise the wire result schema: ${result.inputSchema}"
        )
        assert(
            result.inputSchema == Structure.encode(StrictSchema.requireAll(resultSchema)),
            s"the registered result schema is the require-all envelope, the shape every backend advertises: ${result.inputSchema}"
        )
        assert(
            Json.encode(result.inputSchema).contains("\"required\":[\"answer\",\"note\"]"),
            s"an optional field must be require-all'd on the registered result schema: ${result.inputSchema}"
        )
        assert(
            result.description == Tool.internal.resultToolDefinition.infos.head.description,
            s"the result tool's registered description is kyo's own: ${result.description}"
        )
        val lookup = specs.filter(_.name == "lookup").head
        assert(
            Json.encode(lookup.inputSchema).contains("\"integer\""),
            s"a user tool advertises its own input schema: ${lookup.inputSchema}"
        )
    }

    "threadStartParams carries only the LEADING system message in baseInstructions and registers the dynamic tools" in {
        val ctx = Context.empty
            .systemMessage("AMBIENT PROMPT")
            .userMessage("question")
            .systemMessage("MID CONVERSATION INSTRUCTION")
        val resultSchema = Thought.internal.resultJson(Chunk.empty, Json.jsonSchema[CodexWireResultProbe])
        val specs        = CodexWire.dynamicToolSpecs(Tool.internal.resultToolDefinition.infos, resultSchema)

        Path.tempDir("codex-wire-test").map { dir =>
            val params = CodexWire.threadStartParams(Config.Codex.gpt_5_5, ctx, dir, specs)
            assert(params.baseInstructions == "AMBIENT PROMPT", s"baseInstructions is the leading system alone: $params")
            assert(
                params.dynamicTools.map(_.name) == List(Completion.resultToolName),
                s"the registered tools ride thread/start.dynamicTools: ${params.dynamicTools}"
            )
        }
    }

    "historyItems renders a non-leading system message IN PLACE as a system-reminder user item" in {
        // The app-server delivers no injected system-role item to the model and treats a
        // developer-role item as confidential, so the in-place carrier is a user-role item with the
        // same <system-reminder> wrapper the Claude Code harness uses.
        val ctx = Context.empty
            .systemMessage("AMBIENT PROMPT")
            .userMessage("first question")
            .systemMessage("MID CONVERSATION INSTRUCTION")
            .userMessage("second question")

        Abort.run[AIGenException](CodexWire.historyItems(CodexWire.historyMessages(ctx))).map {
            case Result.Success(items) =>
                val encoded = Json.encode(items.toList)
                assert(
                    encoded.contains("<system-reminder>\\nMID CONVERSATION INSTRUCTION\\n</system-reminder>"),
                    s"a non-leading system message rides the history in place inside the system-reminder wrapper: $encoded"
                )
                assert(
                    !encoded.contains("\"role\":\"system\""),
                    s"no system-role item may ride the injection (the app-server never delivers them): $encoded"
                )
                assert(
                    !encoded.contains("AMBIENT PROMPT"),
                    s"the leading system message rides baseInstructions, never the history: $encoded"
                )
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "historyItems encodes a UserMessage image as an input_image content block" in {
        val image   = Image.fromBytes(Span.from(Array[Byte](1, 2, 3)))
        val history = Context.empty.userMessage("look", Present(image))

        Abort.run[AIGenException](CodexWire.historyItems(history.compacted)).map {
            case Result.Success(items) =>
                val encoded = Json.encode(items.toList)
                assert(encoded.contains("\"type\":\"input_image\""), s"input_image block missing: $encoded")
                assert(encoded.contains("data:image/jpeg;base64,AQID"), s"image data URL missing: $encoded")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "historyItems replays assistant tool calls as native function_call items and tool results as function_call_output" in {
        val history = Context.empty
            .assistantMessage("calling", Chunk(Call(CallId("c1"), "lookup", """{"q":"kyo"}""")))
            .toolMessage(CallId("c1"), """{"answer":"Kyo"}""")

        Abort.run[AIGenException](CodexWire.historyItems(history.compacted)).map {
            case Result.Success(items) =>
                val encoded = Json.encode(items.toList)
                assert(encoded.contains("\"type\":\"function_call\""), s"function_call item missing: $encoded")
                assert(encoded.contains("\"call_id\":\"c1\""), s"call id missing: $encoded")
                assert(encoded.contains("\"name\":\"lookup\""), s"call name missing: $encoded")
                assert(encoded.contains("\"arguments\":\"{\\\"q\\\":\\\"kyo\\\"}\""), s"call arguments missing: $encoded")
                assert(encoded.contains("\"type\":\"function_call_output\""), s"function_call_output item missing: $encoded")
                assert(encoded.contains("\"output\":\"{\\\"answer\\\":\\\"Kyo\\\"}\""), s"tool output missing: $encoded")
            case other =>
                fail(s"expected native function_call/function_call_output replay, got: $other")
        }
    }

    "resultMessages replays executed calls as native pairs and appends the captured result verbatim with the final text" in {
        val executed = Chunk(
            CodexWire.ExecutedCall(
                "call_1",
                "lookup",
                Structure.Value.Record(Chunk("q" -> Structure.Value.Str("kyo"))),
                """{"answer":"Kyo"}"""
            )
        )
        val messages = CodexWire.resultMessages(
            executed,
            Present(("call_r", """{"resultValue":{"answer":"ok"}}""")),
            Present("final prose")
        )

        assert(messages.size == 3, s"one call pair plus the result message: $messages")
        messages(0) match
            case AssistantMessage(content, calls, _, _) =>
                assert(content == "", s"an executed call message carries no prose: $content")
                assert(
                    calls.map(c => (c.id.id, c.function, c.arguments)) == Chunk(("call_1", "lookup", """{"q":"kyo"}""")),
                    s"the executed call must replay with its real wire id and verbatim arguments: $calls"
                )
            case other => fail(s"expected the executed call message, got: $other")
        end match
        assert(messages(1) == ToolMessage(CallId("call_1"), """{"answer":"Kyo"}"""))
        messages(2) match
            case AssistantMessage(content, calls, _, _) =>
                assert(content == "final prose", s"the turn's final text rides the result message: $content")
                assert(
                    calls.map(c => (c.id.id, c.function, c.arguments)) ==
                        Chunk(("call_r", Completion.resultToolName, """{"resultValue":{"answer":"ok"}}""")),
                    s"the captured result must ride verbatim as a result_tool call: $calls"
                )
            case other => fail(s"expected the captured result message, got: $other")
        end match
    }

    "turnInput on a tool-terminated body sends the shared tool-results continuation, then the trailing directive" in {
        val ctx = Context.empty
            .systemMessage("AMBIENT PROMPT")
            .userMessage("q")
            .assistantMessage("", Chunk(Call(CallId("c1"), "lookup", "{}")))
            .toolMessage(CallId("c1"), "42")
            .systemMessage("finalize directive")
        assert(
            CodexWire.turnInput(ctx).map(_.text) == Chunk(
                Present(
                    "Continue: complete the original request using the recorded tool results above; do not repeat completed tool calls."
                ),
                Present("<system-reminder>\nfinalize directive\n</system-reminder>")
            ),
            "a body ending on tool results gets the same continuation the Claude Code wire sends, the directive riding after it"
        )
        Abort.run[AIGenException](CodexWire.historyItems(CodexWire.historyMessages(ctx))).map {
            case Result.Success(items) =>
                assert(
                    !Json.encode(items.toList).contains("finalize directive"),
                    s"a trailing directive rides the live turn, never the injected history: $items"
                )
            case other => fail(s"expected success, got: $other")
        }
    }

    "turnInput keeps a user-final request as the live turn and appends the trailing directive after it" in {
        val ctx = Context.empty
            .systemMessage("AMBIENT PROMPT")
            .userMessage("first question")
            .assistantMessage("first answer")
            .userMessage("second question")
            .systemMessage("floating reminder")
        assert(
            CodexWire.turnInput(ctx).map(_.text) == Chunk(
                Present("second question"),
                Present("<system-reminder>\nfloating reminder\n</system-reminder>")
            ),
            "the request stays the live turn with the directive after it, the Claude Code request-then-directives shape"
        )
        val history = CodexWire.historyMessages(ctx)
        assert(
            history == Chunk(UserMessage("first question", Absent), AssistantMessage("first answer")),
            s"neither the live request nor the trailing directive may ride the injected history: $history"
        )
    }

    "turnInput on an assistant-terminated body sends the bare continuation" in {
        val ctx = Context.empty.userMessage("q").assistantMessage("partial answer")
        assert(CodexWire.turnInput(ctx).map(_.text) == Chunk(Present("Continue.")))
    }

    "startsFollowUp marks a reasoning or agentMessage item on this turn and positively clears everything else" in {
        def started(turnId: String, itemType: String) =
            CodexWire.RpcEvent(
                "item/started",
                Structure.encode(CodexWire.ItemNotification("t1", turnId, CodexWire.ThreadItem(itemType)))
            )
        assert(CodexWire.startsFollowUp(started("u1", "reasoning"), "t1", "u1"))
        assert(CodexWire.startsFollowUp(started("u1", "agentMessage"), "t1", "u1"))
        assert(
            !CodexWire.startsFollowUp(started("u1", "dynamicToolCall"), "t1", "u1"),
            "a parallel tool call in the same round keeps the round open"
        )
        assert(!CodexWire.startsFollowUp(started("other", "reasoning"), "t1", "u1"), "another turn's item is not this round's follow-up")
        val completed = CodexWire.RpcEvent(
            "item/completed",
            Structure.encode(CodexWire.ItemNotification("t1", "u1", CodexWire.ThreadItem("reasoning")))
        )
        assert(!CodexWire.startsFollowUp(completed, "t1", "u1"), "only item/started marks the follow-up")
    }

    "startsFollowUp fail-safes to true on an item/started shape that does not decode" in {
        // The one-round bound must never silently disarm on a decode miss: an unrecognizable
        // item/started counts as the follow-up, so a later user-tool call defers to the next turn
        // instead of opening an unbounded second round.
        val malformed = CodexWire.RpcEvent("item/started", Structure.Value.Str("junk"))
        assert(CodexWire.startsFollowUp(malformed, "t1", "u1"))
        val missingItem = CodexWire.RpcEvent(
            "item/started",
            Structure.Value.Record(Chunk("threadId" -> Structure.Value.Str("t1")))
        )
        assert(CodexWire.startsFollowUp(missingItem, "t1", "u1"))
    }

    "resultMessages on a resultless turn keeps the executed pairs plus the final text and adds no result call" in {
        val withText = CodexWire.resultMessages(Chunk.empty, Absent, Present("just prose"))
        assert(withText == Chunk(AssistantMessage("just prose")), s"a resultless text turn is a plain assistant message: $withText")
        val empty = CodexWire.resultMessages(Chunk.empty, Absent, Absent)
        assert(empty.isEmpty, s"a resultless empty turn produces no messages: $empty")
    }

end CodexWireTest
