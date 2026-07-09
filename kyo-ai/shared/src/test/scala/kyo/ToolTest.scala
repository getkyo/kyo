package kyo

import kyo.Json.JsonSchema
import kyo.Schema
import kyo.ai.Context
import kyo.ai.Context.*

class ToolTest extends kyo.test.Test[Any]:

    "init builds one info; aggregate composes; empty has none" in {
        val a   = Tool.init[Int]("a")(_ => 1)
        val b   = Tool.init[Int]("b")(_ => 2)
        val agg = Tool.aggregate(a, b)
        assert(a.infos.size == 1)
        assert(agg.infos.size == 2)
        assert(Tool.empty.infos.isEmpty)
    }

    "enabling an empty tool aggregate surfaces no infos" in {
        LLM.run(
            AI.enable(Tool.empty)(
                Tool.internal.infos.map(_.size)
            )
        ).map(size => assert(size == 0))
    }

    "result_tool uses the exact name and description string" in {
        val info = Tool.internal.resultToolInfo.infos.head
        assert(info.name == "result_tool")
        assert(
            info.description ==
                "Call this tool with the result. Do not make parallel calls to this tool in the same completion. " +
                "Only the first invocation will be considered."
        )
    }

    "decode-failure repair removes the call AND injects a corrective system message" in {
        val callId   = CallId("decode-fail-id")
        val call     = Call(callId, "t", "not-valid-json-for-int{{{")
        val aTool    = Tool.init[Int]("t")(_ => 42)
        val toolInfo = aTool.infos.head.asInstanceOf[Tool.internal.Info[Int, Int, LLM]]

        LLM.run(
            AI.init.map { ai =>
                ai.updateContext(
                    _.assistantMessage("", Chunk(call))
                ).andThen(
                    ai.updateContext(_.add(ToolMessage(callId, "Processing tool call: t")))
                ).andThen(
                    Tool.internal.handle(ai, Chunk(toolInfo.asInstanceOf[Tool.internal.Info[?, ?, LLM]]), Chunk(call))
                ).andThen(
                    ai.context.map(identity)
                )
            }
        ).map { ctx =>
            val msgs = ctx.messages.toList
            val hasProcessingMsg = msgs.exists {
                case ToolMessage(cid, content) => cid == callId && content.contains("Processing tool call")
                case _                         => false
            }
            val assistantHasCall = msgs.exists {
                case AssistantMessage(_, calls) => calls.exists(_.id == callId)
                case _                          => false
            }
            val hasCorrectiveMsg = msgs.exists {
                case SystemMessage(content) => content.contains("carefully review its schema")
                case _                      => false
            }
            assert(!hasProcessingMsg, "processingMessage should be removed on decode failure")
            assert(!assistantHasCall, "call should be removed from assistant message on decode failure")
            assert(hasCorrectiveMsg, "corrective system message should be injected")
        }
    }

    "tool-NOT-FOUND answers with a not-found tool message" in {
        val callId = CallId("nf-id")
        val call   = Call(callId, "missing", "{}")
        LLM.run(
            AI.init.map { ai =>
                Tool.internal.handle(ai, Chunk.empty, Chunk(call))
                    .andThen(ai.context.map(identity))
            }
        ).map { ctx =>
            val found = ctx.messages.toList.exists {
                case ToolMessage(cid, content) => cid == callId && content.contains("not found")
                case _                         => false
            }
            assert(found)
        }
    }

    "run-failure feeds back keeping the call; run-success replaces in place" in {
        val failCallId    = CallId("fail-id")
        val successCallId = CallId("ok-id")
        val failCall      = Call(failCallId, "failing", "1")
        val successCall   = Call(successCallId, "succeeding", "2")

        val failingTool = Tool.init[Int]("failing") { (_: Int) =>
            (throw new RuntimeException("expected run failure")): Unit
        }
        val succeedingTool = Tool.init[Int]("succeeding") { n =>
            n + 10
        }

        val failInfo    = failingTool.infos.head.asInstanceOf[Tool.internal.Info[?, ?, LLM]]
        val successInfo = succeedingTool.infos.head.asInstanceOf[Tool.internal.Info[?, ?, LLM]]
        val allInfos    = Chunk(failInfo, successInfo)

        LLM.run(
            AI.init.map { ai =>
                Tool.internal.handle(ai, allInfos, Chunk(failCall, successCall))
                    .andThen(ai.context.map(identity))
            }
        ).map { ctx =>
            val msgs = ctx.messages.toList
            val failMsg = msgs.collect {
                case ToolMessage(cid, content) if cid == failCallId => content
            }
            val successMsg = msgs.collect {
                case ToolMessage(cid, content) if cid == successCallId => content
            }
            assert(failMsg.nonEmpty, "failing call should have a tool message")
            assert(failMsg.head.contains("expected run failure") || failMsg.head.contains("failed"), "failure text should appear")
            assert(successMsg.nonEmpty, "succeeding call should have a tool message")
            assert(!successMsg.head.contains("Processing tool call"), "success should replace the processing placeholder")
        }
    }

    "a tool whose input has a Map field produces the additionalProperties schema" in {
        case class Lookup(table: Map[String, Int]) derives Schema
        val tool   = Tool.init[Lookup]("lookup", "")(_ => ())
        val info   = tool.infos.head
        val schema = Json.jsonSchema(using info.inputSchema.asInstanceOf[Schema[Lookup]])
        schema match
            case JsonSchema.Obj(props, _, _, _, _, _) =>
                val tableNode = props.find(_._1 == "table").map(_._2)
                tableNode match
                    case Some(JsonSchema.Obj(innerProps, _, additionalProps, _, _, _)) =>
                        assert(innerProps.isEmpty)
                        additionalProps match
                            case Present(JsonSchema.Integer(_, _, _, _, _)) => succeed
                            case other                                      => fail(s"expected Integer additionalProperties, got $other")
                    case _ =>
                        assert(false, s"expected Obj with additionalProperties for table, got $tableNode")
                end match
            case _ =>
                assert(false, s"expected Obj for Lookup schema, got $schema")
        end match
    }

    "the tool input schema encodes to standard JSON text, no tagged wrapper" in {
        case class Lookup(table: Map[String, Int]) derives Schema
        val tool     = Tool.init[Lookup]("lookup", "")(_ => ())
        val info     = tool.infos.head
        val jsSchema = Json.jsonSchema(using info.inputSchema.asInstanceOf[Schema[Lookup]])
        val encoded  = Json.encode(jsSchema)
        assert(encoded.contains("\"type\":\"object\"") || encoded.contains("\"additionalProperties\""))
        assert(!encoded.contains("\"Obj\""))
        assert(!encoded.contains("\"Nullable\""))
    }

    "a tool's prompt and reminder render as TOOL / TOOL REMINDER blocks in the enriched context" in {
        val tool = Tool.init[Int]("locator", "finds things", prompt = Prompt.init("tool-guidance", "tool-reminder"))(_ => 0)
        LLM.run(Prompt.internal.enrichedContext(Context.empty, tool.infos)).map { ctx =>
            val contents = ctx.messages.toList.map(_.content)
            assert(
                contents.exists(c => c.contains("TOOL: locator") && c.contains("tool-guidance")),
                s"the tool's prompt block (with header + description) must reach the context, got: $contents"
            )
            assert(
                contents.exists(c => c.contains("TOOL REMINDER: locator") && c.contains("tool-reminder")),
                s"the tool's reminder block must reach the context, got: $contents"
            )
        }
    }

end ToolTest
