package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.Image

class ClaudeCodeWireTest extends kyo.test.Test[Any]:

    "the output-ceiling stop is recognized from the event field, not from the text around it" in {
        // The harness reports a ceiling stop in a field of its own, which is what lets a normal outcome
        // be told apart from an actual malfunction. Reading it structurally also means prose mentioning
        // the same words cannot be mistaken for the signal.
        val ceilingStop =
            """{"type":"system","subtype":"init","session_id":"s"}
              |{"type":"assistant","error":"max_output_tokens"}
              |{"type":"result","subtype":"success","is_error":true}""".stripMargin
        val plainFailure =
            """{"type":"system","subtype":"init","session_id":"s"}
              |{"type":"assistant","message":{"content":[{"type":"text","text":"about max_output_tokens"}]}}
              |{"type":"result","subtype":"success","is_error":true}""".stripMargin
        val unknownError =
            """{"type":"assistant","error":"something_else"}"""

        Abort.run[AIGenException](ClaudeCodeWire.stoppedAtOutputLimit(ceilingStop)).map { hit =>
            Abort.run[AIGenException](ClaudeCodeWire.stoppedAtOutputLimit(plainFailure)).map { prose =>
                Abort.run[AIGenException](ClaudeCodeWire.stoppedAtOutputLimit(unknownError)).map { other =>
                    assert(hit == Result.succeed(true), s"the event field must be recognized: $hit")
                    assert(prose == Result.succeed(false), s"prose must not be mistaken for the signal: $prose")
                    assert(other == Result.succeed(false), s"an unknown error value is not a ceiling stop: $other")
                }
            }
        }
    }

    "turnInput encodes the current request's image as a base64 image content block" in {
        val image = Image.fromBytes(Span.from(Array[Byte](1, 2, 3)))
        val ctx   = Context.empty.userMessage("look", Present(image))

        Abort.run[AIGenException](ClaudeCodeWire.turnInput(ctx)).map {
            case Result.Success(line) =>
                assert(line.contains("\"type\":\"image\""), s"image block missing: $line")
                assert(line.contains("\"media_type\":\"image/jpeg\""), s"image media type missing: $line")
                assert(line.contains("\"data\":\"AQID\""), s"image data missing: $line")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "turnInput emits exactly one user event and replays prior turns as a transcript block" in {
        // The CLI's stdin accepts only user-role message events, and several user events queue as
        // sequential live requests the model re-answers, so a replayed conversation must arrive as
        // one event carrying the history as transcript text.
        val ctx = Context.empty
            .userMessage("what's the answer?")
            .assistantMessage(
                "the answer is 42",
                Chunk(Call(CallId("harness-result"), Completion.resultToolName, """{"resultValue":{"answer":42}}"""))
            )
            .toolMessage(CallId("harness-result"), "Result received.")
            .userMessage("thanks, one more question")

        Abort.run[AIGenException](ClaudeCodeWire.turnInput(ctx)).map {
            case Result.Success(line) =>
                assert(!line.contains("\n"), s"the turn must be a single stream-json event: $line")
                assert(!line.contains("\"role\":\"assistant\""), s"no assistant event may ride stdin: $line")
                assert(!line.contains("\"type\":\"tool_use\""), s"no native tool_use block may ride stdin: $line")
                assert(
                    line.contains(s"[assistant, tool call ${Completion.resultToolName}]"),
                    s"the prior result call must render as a transcript line: $line"
                )
                assert(line.contains("the answer is 42"), s"assistant prose must survive the replay: $line")
                assert(line.contains("[tool result]: Result received."), s"the tool result must render as a transcript line: $line")
                assert(line.contains("thanks, one more question"), s"the current request must ride the event: $line")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "turnInput renders a failed tool result's failure text in the transcript" in {
        val ctx = Context.empty
            .userMessage("look it up")
            .assistantMessage("", Chunk(Call(CallId("toolu_1"), "lookup", """{"q":"kyo"}""")))
            .toolMessage(CallId("toolu_1"), "Tool 'lookup' failed: boom")
            .userMessage("what happened?")

        Abort.run[AIGenException](ClaudeCodeWire.turnInput(ctx)).map {
            case Result.Success(line) =>
                assert(line.contains("Tool 'lookup' failed: boom"), s"failure text must ride the transcript: $line")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "turnInput replays an assistant-terminated conversation as history under the Continue. request" in {
        // The CLI acts only on a user turn: with the whole conversation in the transcript block, the
        // Continue. request is what makes the model act on it.
        val ctx = Context.empty.userMessage("do the thing").assistantMessage("done")

        Abort.run[AIGenException](ClaudeCodeWire.turnInput(ctx)).map {
            case Result.Success(line) =>
                assert(line.contains("[assistant]: done"), s"the assistant turn must survive as transcript: $line")
                assert(line.contains("Continue."), s"an assistant-terminated conversation needs the Continue. request: $line")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "turnInput distributes the LEADING system message to the system prompt, post-turn system messages in place, and trailing ones as system-reminder blocks" in {
        // No assistant or tool turns here, so there is no transcript at all: prior user text and the
        // mid-conversation system message render in place as plain text and system-reminder blocks,
        // byte-matching what the Anthropic wire sends for the same context.
        val ctx = Context.empty
            .systemMessage("AMBIENT PROMPT")
            .userMessage("first question")
            .systemMessage("MID CONVERSATION INSTRUCTION")
            .userMessage("second question")
            .systemMessage("TRAILING REMINDER")
        val config = Config.ClaudeCode.sonnet

        val argv          = ClaudeCodeWire.commandArgs(config, ctx, """{"mcpServers":{}}""", Chunk.empty)
        val systemFlagIdx = argv.indexOf("--system-prompt")
        assert(systemFlagIdx >= 0, s"leading system message must ride --system-prompt: $argv")
        assert(argv(systemFlagIdx + 1) == "AMBIENT PROMPT", s"the --system-prompt value must be the leading system content: $argv")

        Abort.run[AIGenException](ClaudeCodeWire.turnInput(ctx)).map {
            case Result.Success(line) =>
                // JSON-encoded, so newlines appear as the \n escape.
                assert(
                    line.contains("<system-reminder>\\nMID CONVERSATION INSTRUCTION\\n</system-reminder>"),
                    s"a post-turn system message must render in place as its own system-reminder block: $line"
                )
                assert(
                    !line.contains("Conversation history for this session"),
                    s"a context with no completed turns must produce no transcript block: $line"
                )
                assert(line.contains("first question"), s"prior user text must render in place as a plain block: $line")
                assert(
                    line.contains("<system-reminder>\\nTRAILING REMINDER\\n</system-reminder>"),
                    s"a trailing system message must render as a system-reminder block after the request: $line"
                )
                assert(!line.contains("AMBIENT PROMPT"), s"the leading system message must not also appear in the event body: $line")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "turnInput keeps a system message inside completed turns as a transcript line" in {
        // The system message is followed by an assistant turn, so it is part of the replayed turn
        // history and renders as a transcript line, in the position it occupied.
        val ctx = Context.empty
            .userMessage("first question")
            .systemMessage("MID CONVERSATION INSTRUCTION")
            .assistantMessage("first answer")
            .userMessage("second question")

        Abort.run[AIGenException](ClaudeCodeWire.turnInput(ctx)).map {
            case Result.Success(line) =>
                assert(
                    line.contains("[system instruction]: MID CONVERSATION INSTRUCTION"),
                    s"a system message inside completed turns must render as a transcript line: $line"
                )
                assert(line.contains("[assistant]: first answer"), s"the completed turn must ride the transcript: $line")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "readMessages returns native tool_use blocks as Kyo tool calls" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"checking"},{"type":"tool_use","id":"toolu_1","name":"lookup","input":{"q":"kyo"}}]}}"""

        Abort.run[AIGenException](ClaudeCodeWire.readMessages(output, Absent, "seed1")).map {
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

    "readMessages appends the captured MCP result arguments verbatim, keeping the result-turn prose" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"answering"},{"type":"tool_use","id":"toolu_result","name":"mcp__kyo__result_tool","input":{"resultValue":{"answer":"fromtool"}}}]}}"""
        val captured = """{"resultValue":{"answer":"ok"}}"""

        Abort.run[AIGenException](ClaudeCodeWire.readMessages(output, Present(captured), "seed1")).map {
            case Result.Success(messages) =>
                messages.lastOption match
                    case Some(msg: AssistantMessage) =>
                        assert(msg.content == "answering", s"the result turn's prose must survive: $msg")
                        assert(msg.calls.size == 1)
                        assert(msg.calls.head.id == CallId("harness-result-seed1"))
                        assert(msg.calls.head.function == Completion.resultToolName)
                        assert(
                            msg.calls.head.arguments == captured,
                            s"the captured arguments must ride verbatim: ${msg.calls.head.arguments}"
                        )
                    case other =>
                        fail(s"expected a trailing assistant result message, got: $other")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "readMessages leaves the result-turn prose empty when the result event did not flush before the kill" in {
        val captured = """{"resultValue":{"answer":"ok"}}"""

        Abort.run[AIGenException](ClaudeCodeWire.readMessages("", Present(captured), "seed1")).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.content == "", s"no prose was captured from stdout, so the text must be empty, not truncated: $msg")
                assert(msg.calls.head.function == Completion.resultToolName)
                assert(msg.calls.head.arguments == captured)
            case other =>
                fail(s"expected one assistant result message, got: $other")
        }
    }

    "readMessages passes non-envelope captured arguments through raw, without decoding or wrapping" in {
        val captured = """{"answer":"ok"}"""

        Abort.run[AIGenException](ClaudeCodeWire.readMessages("", Present(captured), "seed1")).map {
            case Result.Success(Chunk(msg: AssistantMessage)) =>
                assert(msg.calls.head.function == Completion.resultToolName)
                assert(msg.calls.head.arguments == captured, s"a bare captured object must ride verbatim: ${msg.calls.head.arguments}")
            case other =>
                fail(s"expected one assistant result message, got: $other")
        }
    }

    "readMessages ignores the CLI terminal result string (the result rides only the MCP capture)" in {
        val output = """{"type":"result","subtype":"success","result":"{\"resultValue\":{\"answer\":\"stale\"}}"}"""

        Abort.run[AIGenException](ClaudeCodeWire.readMessages(output, Absent, "seed1")).map {
            case Result.Success(messages) =>
                assert(
                    !messages.exists {
                        case AssistantMessage(_, calls) => calls.exists(_.function == Completion.resultToolName)
                        case _                          => false
                    },
                    s"the stale terminal result string must never be selected as the result: $messages"
                )
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "readMessages filters the result tool's own tool_use out of the native transcript (no duplicate/dangling call)" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_result","name":"mcp__kyo__result_tool","input":{"resultValue":{"answer":"ok"}}}]}}"""
        val captured = """{"resultValue":{"answer":"ok"}}"""

        Abort.run[AIGenException](ClaudeCodeWire.readMessages(output, Present(captured), "seed1")).map {
            case Result.Success(messages) =>
                val resultCalls = messages.flatMap {
                    case AssistantMessage(_, calls) => calls.filter(_.function == Completion.resultToolName)
                    case _                          => Chunk.empty
                }
                assert(resultCalls.size == 1, s"expected exactly one result_tool call, got: $resultCalls")
                assert(
                    resultCalls.head.id == CallId("harness-result-seed1"),
                    s"the native event's own call id must not leak through: $resultCalls"
                )
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "readMessages drops a kill-truncated trailing line when a result was captured (lenient parse)" in {
        val output =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"lookup","input":{"q":"kyo"}}]}}
              |{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","tex""".stripMargin
        val captured = """{"resultValue":{"answer":"ok"}}"""

        Abort.run[AIGenException](ClaudeCodeWire.readMessages(output, Present(captured), "seed1")).map {
            case Result.Success(messages) =>
                val lookupCalls = messages.flatMap {
                    case AssistantMessage(_, calls) => calls.filter(_.function == "lookup")
                    case _                          => Chunk.empty
                }
                assert(lookupCalls.size == 1, s"the intact native call must survive: $messages")
                messages.lastOption match
                    case Some(msg: AssistantMessage) =>
                        assert(
                            msg.calls.head.function == Completion.resultToolName,
                            s"the captured result must still be appended: $messages"
                        )
                    case other =>
                        fail(s"expected a trailing result message, got: $other")
                end match
            case other =>
                fail(s"expected success (the undecodable tail is dropped, not fatal), got: $other")
        }
    }

    "readMessages mints per-generation unique ids for the result call and a lost-event executed tool" in {
        // Two generations of one conversation must never hold two calls with the same id: a CC-built
        // context replayed natively through an HTTP wire would otherwise carry duplicate tool_use ids.
        val captured = """{"resultValue":{"answer":"ok"}}"""
        val executed = Chunk(ClaudeCodeWire.ExecutedTool("lookup", Structure.Value.Record(Chunk.empty), "42"))
        def callIds(messages: Chunk[Message]): Chunk[String] =
            messages.flatMap {
                case AssistantMessage(_, calls) => calls.map(_.id.id)
                case _                          => Chunk.empty
            }
        Abort.run[AIGenException](ClaudeCodeWire.readMessages("", executed, Present(captured), "gen1")).map { first =>
            Abort.run[AIGenException](ClaudeCodeWire.readMessages("", executed, Present(captured), "gen2")).map { second =>
                (first, second) match
                    case (Result.Success(a), Result.Success(b)) =>
                        assert(
                            callIds(a) == Chunk("mcp-gen1-lookup-0", "harness-result-gen1"),
                            s"every wire-minted id must carry the generation seed: ${callIds(a)}"
                        )
                        assert(
                            callIds(a).toSet.intersect(callIds(b).toSet).isEmpty,
                            s"two generations must share no call id: ${callIds(a)} vs ${callIds(b)}"
                        )
                    case other => fail(s"expected two successful reads, got: $other")
            }
        }
    }

    "readMessages still fails on malformed stream-json when no result was captured" in {
        val output = "{not stream json"

        Abort.run[AIGenException](ClaudeCodeWire.readMessages(output, Absent, "seed1")).map {
            case Result.Failure(_: AIDecodeException) => succeed
            case other                                => fail(s"expected AIDecodeException (strict parse with no capture), got: $other")
        }
    }

    "commandArgs builds the CLI invocation with no --json-schema and the result tool advertised under the mcp prefix" in {
        val config       = Config.ClaudeCode.sonnet
        val ctx          = Context.empty.userMessage("hello")
        val allowedTools = Chunk("mcp__kyo__lookup", "mcp__kyo__result_tool")

        val argv = ClaudeCodeWire.commandArgs(config, ctx, """{"mcpServers":{"kyo":{}}}""", allowedTools)

        assert(argv.contains("--mcp-config"), s"argv must pass the mcp bridge config: $argv")
        val allowedIdx = argv.indexOf("--allowedTools")
        assert(allowedIdx >= 0, s"argv must advertise --allowedTools: $argv")
        assert(argv(allowedIdx + 1).contains("mcp__kyo__result_tool"), s"the result tool must be advertised under the mcp prefix: $argv")
        assert(!argv.contains("--json-schema"), s"both paths ride the MCP bridge now, no --json-schema flag: $argv")
        // One CLI invocation is one tool round plus its follow-up turn; without the cap the CLI's own
        // agentic loop runs unbounded and the eval loop's forced turn is never reachable.
        val maxTurnsIdx = argv.indexOf("--max-turns")
        assert(maxTurnsIdx >= 0, s"argv must cap the CLI's inner loop with --max-turns: $argv")
        assert(argv(maxTurnsIdx + 1) == "2", s"the inner-loop cap must be 2 (one tool round plus follow-up): $argv")
    }

    "endedAtTurnCap is true only on an error_max_turns terminal result event" in {
        val cappedOutput =
            """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","id":"toolu_1","name":"mcp__kyo__lookup","input":{"q":"kyo"}}]}}
              |{"type":"result","subtype":"error_max_turns","is_error":true,"api_error_status":null}""".stripMargin
        val successOutput =
            """{"type":"result","subtype":"success","is_error":false,"api_error_status":null}"""
        val providerFailureOutput =
            """{"type":"result","subtype":"error_during_execution","is_error":true,"api_error_status":529}"""

        for
            capped   <- Abort.run[AIGenException](ClaudeCodeWire.endedAtTurnCap(cappedOutput))
            success  <- Abort.run[AIGenException](ClaudeCodeWire.endedAtTurnCap(successOutput))
            provider <- Abort.run[AIGenException](ClaudeCodeWire.endedAtTurnCap(providerFailureOutput))
            empty    <- Abort.run[AIGenException](ClaudeCodeWire.endedAtTurnCap(""))
        yield
            assert(capped == Result.Success(true), s"an error_max_turns result event must read as the turn cap: $capped")
            assert(success == Result.Success(false), s"a success result event is not the turn cap: $success")
            assert(provider == Result.Success(false), s"a provider failure event is not the turn cap: $provider")
            assert(empty == Result.Success(false), s"no result event is not the turn cap: $empty")
        end for
    }

end ClaudeCodeWireTest
