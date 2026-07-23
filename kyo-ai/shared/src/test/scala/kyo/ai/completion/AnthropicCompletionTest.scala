package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.ai.*
import kyo.ai.Context.*

class AnthropicCompletionTest extends kyo.test.Test[Any]:

    def keyedConfig(baseUrl: String): Config =
        Config.Anthropic.default.apiKey("test-key").apiUrl(baseUrl)

    def minimalAnthropicBody(textContent: String): String =
        s"""{"id":"msg-1","content":[{"type":"text","text":"$textContent"}],"model":"claude-sonnet-4-5-20250929","role":"assistant","stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""

    def ceilingStopBody(content: String): String =
        s"""{"id":"msg-1","content":[$content],"model":"m","role":"assistant","stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""

    "a reply that stopped at the output ceiling reports that, and does not decide what it means" in {
        // The incident in fixture form: reasoning consumed the whole ceiling, so the reply carries no
        // tool call. What the backend owes is the reason the wire gave; whether a turn with nothing
        // to act on is fatal is the eval loop's call, since answering it means reading a tool payload
        // and that belongs to the tool loop. LLMTest asserts the failing half against a real
        // generation, including that the ceiling stop is not retried.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            server.enqueueBody(ceilingStopBody("")).andThen {
                Abort.run[AIException](LLM.run(config)(AnthropicCompletion(
                    config,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                ))).map {
                    case Result.Success(reply) =>
                        assert(
                            reply.stopReason == Completion.StopReason.MaxOutputTokens,
                            s"the wire's stop reason must be carried out of the backend: ${reply.stopReason}"
                        )
                    case other => fail(s"expected the reply to be delivered with its stop reason, got: $other")
                }
            }
        }
    }

    "an unrecognized stop value never fails a reply whose content is usable" in {
        // Vocabulary a provider adds later must not turn a good reply into a failure. Implemented on
        // both HTTP backends, so it is pinned on both.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val body =
                """{"id":"msg-1","content":[{"type":"text","text":"fine"}],"model":"m","role":"assistant","stop_reason":"pause_turn","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
            server.enqueueBody(body).andThen {
                Abort.run[AIException](LLM.run(config)(AnthropicCompletion(
                    config,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                ))).map {
                    case Result.Success(reply) => assert(reply.messages.nonEmpty, "the reply must be delivered")
                    case other                 => fail(s"an unknown stop value must not fail a usable reply, got: $other")
                }
            }
        }
    }

    "a reply that stopped at the ceiling but still carries a usable call stays a partial turn" in {
        // Truncation is only fatal when nothing is actionable. With a complete call the loop can run
        // it, and the next turn starts against a fresh ceiling, so failing here would throw away work
        // the provider already paid for.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val call   = """{"type":"tool_use","id":"t1","name":"result_tool","input":{"resultValue":1}}"""
            server.enqueueBody(ceilingStopBody(call)).andThen {
                Abort.run[AIException](LLM.run(config)(AnthropicCompletion(
                    config,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                ))).map {
                    case Result.Success(reply) =>
                        assert(reply.messages.nonEmpty, "a truncated-but-actionable turn must still be delivered")
                    case other => fail(s"expected the partial turn to be delivered, got: $other")
                }
            }
        }
    }

    "the outgoing request does head-is-system extraction and tail mapping" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx = Context.empty
                .systemMessage("you are X")
                .userMessage("hello from user")
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            AnthropicCompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.size == 1, s"expected 1 captured request, got ${caps.size}")
                        val body = caps.head.body
                        assert(body.contains("\"system\""), s"request should contain top-level system field: $body")
                        assert(body.contains("you are X"), s"request should contain system content: $body")
                        assert(body.contains("hello from user"), s"request should contain user message: $body")
                        val systemCount = body.split("\"system\"").length - 1
                        assert(
                            systemCount == 1,
                            s"system should appear exactly once at the top level, found $systemCount occurrences in: $body"
                        )
                    }
                }
            }
        }
    }

    "a conversation with no leading system message keeps the opening user turn (regression: head was dropped)" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            // No system message: the FIRST message is the user's question. Dropping the head unconditionally
            // discarded it; the opening user turn must survive and there must be no top-level system field.
            val ctx = Context.empty.userMessage("where is my order 4242")
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(config, ctx, Chunk.empty)))
                }.andThen {
                    server.captured.map { caps =>
                        val body = caps.head.body
                        assert(body.contains("where is my order 4242"), s"the opening user message must reach Anthropic: $body")
                        assert(!body.contains("\"system\""), s"no leading system message means no top-level system field: $body")
                    }
                }
            }
        }
    }

    "a user Prompt drives a real gen end-to-end: its instruction reaches Anthropic and the user turn survives" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            // Anthropic result-tool reply so the gen completes in one round.
            val body =
                """{"id":"m1","content":[{"type":"tool_use","id":"r1","name":"result_tool","input":{"resultValue":"ok"}}],"model":"claude-sonnet-4-5-20250929","role":"assistant","stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
            server.enqueueBody(body).andThen {
                LLM.run(config) {
                    AI.enable(Prompt.init("You are a terse pirate."))(AI.gen[String]("greet me"))
                }.andThen {
                    server.captured.map { caps =>
                        val req = caps.head.body
                        assert(req.contains("\"system\""), s"a Prompt primary must produce a top-level system field: $req")
                        assert(req.contains("You are a terse pirate"), s"the Prompt instruction must reach Anthropic: $req")
                        assert(req.contains("greet me"), s"the user turn must survive alongside the system prompt: $req")
                    }
                }
            }
        }
    }

    "empty assistant text block is filtered before tool_use in the outgoing request" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx = Context.empty
                .userMessage("call a tool")
                .assistantMessage("", Chunk(Call(CallId("c1"), "some_tool", "{}")))
                .toolMessage(CallId("c1"), "tool result")
            server.enqueueBody(minimalAnthropicBody("done")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            AnthropicCompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.size == 1, s"expected 1 captured request, got ${caps.size}")
                        val body = caps.head.body
                        assert(body.contains("\"tool_use\""), s"assistant message should contain tool_use block: $body")
                        val textBlocks = body.split("\"type\":\"text\"").length - 1
                        assert(
                            textBlocks == 0 || !body.contains("\"text\":\"\""),
                            s"empty text block should be filtered from assistant message: $body"
                        )
                    }
                }
            }
        }
    }

    "a ToolMessage serializes as a user-role tool_result in the outgoing request" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx = Context.empty
                .userMessage("call tool")
                .assistantMessage("", Chunk(Call(CallId("c1"), "some_tool", "{}")))
                .toolMessage(CallId("c1"), "result text")
            server.enqueueBody(minimalAnthropicBody("done")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            AnthropicCompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.size == 1, s"expected 1 captured request, got ${caps.size}")
                        val body = caps.head.body
                        assert(body.contains("\"tool_result\""), s"ToolMessage should serialize as tool_result type: $body")
                        assert(body.contains("\"tool_use_id\":\"c1\""), s"tool_use_id should be c1: $body")
                        assert(body.contains("result text"), s"content should contain result text: $body")
                    }
                }
            }
        }
    }

    "heterogeneous tool_use.input in the real reply decodes via Structure.Value" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx    = Context.empty.userMessage("call tool")
            val anthropicToolResponse =
                """{"id":"msg-1","content":[{"type":"tool_use","id":"tu-1","name":"my_tool","input":{"x":1,"y":"a"}}],"model":"claude-sonnet-4-5-20250929","role":"assistant","stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
            server.enqueueBody(anthropicToolResponse).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            AnthropicCompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.map { result =>
                    result match
                        case Result.Success(Result.Success(Completion.Reply(Chunk(msg: AssistantMessage), _, _))) =>
                            assert(msg.calls.size == 1, s"expected 1 call, got ${msg.calls.size}")
                            val call = msg.calls.head
                            assert(call.id == CallId("tu-1"), s"expected call id 'tu-1', got ${call.id}")
                            assert(call.function == "my_tool", s"expected function 'my_tool', got ${call.function}")
                            assert(call.arguments.contains("\"x\""), s"arguments should contain x field: ${call.arguments}")
                            assert(call.arguments.contains("\"y\""), s"arguments should contain y field: ${call.arguments}")
                        case other =>
                            assert(false, s"expected success, got: $other")
                }
            }
        }
    }

    "a user message with an image serializes as an image source block" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val image  = Image.fromBytes(Span.from(Array[Byte](1, 2, 3)))
            val ctx    = Context.empty.systemMessage("vision assistant").userMessage("look at this", Present(image))
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            AnthropicCompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.size == 1, s"expected 1 captured request, got ${caps.size}")
                        val body = caps.head.body
                        assert(body.contains("\"type\":\"image\""), s"vision request should contain image type: $body")
                        assert(body.contains("\"data\":\"AQID\""), s"vision request should contain base64 data: $body")
                        assert(body.contains("image/jpeg"), s"vision request should contain media type: $body")
                        assert(body.contains("look at this"), s"vision request should contain text content: $body")
                    }
                }
            }
        }
    }

    "streaming: parseDeltaArguments extracts input_json_delta partial_json and ignores other events" in {
        def frag(line: String): Maybe[String] =
            AnthropicCompletion.parseDeltaArguments(line) match
                case Result.Success(Completion.Delta.Fragment(f)) => Present(f)
                case Result.Success(_)                            => Absent
                case other                                        => sys.error(s"unexpected parse failure for $line: $other")
        val toolDelta =
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"x\":1}"}}"""
        assert(frag(toolDelta) == Present("""{"x":1}"""), s"tool fragment: ${frag(toolDelta)}")
        // text deltas, lifecycle events, and pings carry no tool-argument fragment
        assert(frag("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""").isEmpty)
        assert(frag("""{"type":"message_stop"}""").isEmpty)
        assert(frag("""{"type":"ping"}""").isEmpty)
        assert(AnthropicCompletion.parseDeltaArguments("not a json event").isFailure)
    }

    "streaming: streamRequest targets /messages with x-api-key auth and a stream-flagged body" in {
        Abort.run[AIStreamException](
            AnthropicCompletion.streamRequest(
                keyedConfig("https://anthropic.test/v1"),
                Context.empty.userMessage("hi"),
                Json.jsonSchema[String],
                Chunk.empty
            )
        ).map {
            case Result.Success(req) =>
                assert(req.url == "https://anthropic.test/v1/messages", s"url: ${req.url}")
                assert(req.headers.exists { case (k, v) => k == "x-api-key" && v == "test-key" }, s"headers: ${req.headers}")
                assert(req.headers.exists(_._1 == "anthropic-version"), s"headers: ${req.headers}")
                assert(req.body.contains("\"stream\":true"), s"stream flag in body: ${req.body}")
            case other =>
                fail(s"expected success, got: $other")
        }
    }

    "streaming: streamRequest adds the interleaved-thinking beta when thinking is enabled and omits it otherwise" in {
        val withThinking = keyedConfig("https://anthropic.test/v1").reasoningBudget(4000)
        val noThinking   = keyedConfig("https://anthropic.test/v1").disableReasoning
        Abort.run[AIStreamException](
            AnthropicCompletion.streamRequest(withThinking, Context.empty.userMessage("hi"), Json.jsonSchema[String], Chunk.empty)
        ).map {
            case Result.Success(req) =>
                assert(
                    req.headers.exists { case (k, v) => k == "anthropic-beta" && v == "interleaved-thinking-2025-05-14" },
                    s"a thinking budget must add the interleaved-thinking beta header: ${req.headers}"
                )
            case other =>
                fail(s"expected success, got: $other")
        }.andThen {
            Abort.run[AIStreamException](
                AnthropicCompletion.streamRequest(noThinking, Context.empty.userMessage("hi"), Json.jsonSchema[String], Chunk.empty)
            ).map {
                case Result.Success(req) =>
                    assert(
                        !req.headers.exists(_._1 == "anthropic-beta"),
                        s"no thinking budget must omit the beta header: ${req.headers}"
                    )
                case other =>
                    fail(s"expected success, got: $other")
            }
        }
    }

    "streaming: streamRequest without an API key fails typed with AIMissingApiKeyException and produces no request" in {
        Abort.run[AIStreamException](
            AnthropicCompletion.streamRequest(
                Config.Anthropic.default, // no apiKey
                Context.empty.userMessage("hi"),
                Json.jsonSchema[String],
                Chunk.empty
            )
        ).map {
            case Result.Failure(_: AIMissingApiKeyException) => succeed
            case other                                       => fail(s"expected AIMissingApiKeyException, got: $other")
        }
    }

    "a gen runs end-to-end against the Anthropic backend, extracting the result" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            // The model calls result_tool with the result envelope as the tool_use input.
            val body =
                """{"id":"m1","content":[{"type":"tool_use","id":"r1","name":"result_tool","input":{"resultValue":42}}],"model":"claude-sonnet-4-5-20250929","role":"assistant","stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
            server.enqueueBody(body).andThen {
                LLM.run(config)(AI.gen[Int]).map { result =>
                    assert(result == 42, s"the Anthropic gen should extract resultValue 42, got: $result")
                }
            }
        }
    }

    "a non-head system message serializes as a system-reminder user message" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx = Context.empty
                .systemMessage("primary") // head -> top-level system
                .userMessage("hi")
                .systemMessage("a reminder") // non-head -> system-reminder user turn (Anthropic has no system tail)
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(config, ctx, Chunk.empty)))
                }.andThen {
                    server.captured.map { caps =>
                        val body = caps.head.body
                        // JSON-encoded, so the wrapper's newlines appear as the \n escape.
                        assert(
                            body.contains("<system-reminder>\\na reminder\\n</system-reminder>"),
                            s"a non-head system must map to a system-reminder user turn: $body"
                        )
                    }
                }
            }
        }
    }

    "a contiguous leading system run merges into the one system slot, both instructions delivered" in {
        // The F5 fix: two leading system messages both reach the single system slot, where the wire hoisted
        // exactly one before and demoted the second to a reminder. The shared transform merges the run;
        // this impl lifts the merged message into `system`. No <system-reminder> is produced for the second.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx = Context.empty
                .systemMessage("first instruction")
                .systemMessage("second instruction")
                .userMessage("hi")
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(config, ctx, Chunk.empty)))
                }.andThen {
                    server.captured.map { caps =>
                        val body = caps.head.body
                        assert(body.contains("first instruction"), s"the first instruction rides the system slot: $body")
                        assert(body.contains("second instruction"), s"the second does too, not lost: $body")
                        assert(
                            !body.contains("system-reminder"),
                            s"a contiguous leading run merges rather than demoting the second: $body"
                        )
                    }
                }
            }
        }
    }

    "a missing API key surfaces as AIMissingApiKeyException" in {
        val config = Config.Anthropic.default // no apiKey
        LLM.run(config) {
            Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(config, Context.empty, Chunk.empty)))
        }.map { result =>
            result match
                case Result.Success(Result.Failure(ex: AIMissingApiKeyException)) =>
                    assert(ex.getMessage.contains("claude"), s"message: ${ex.getMessage}")
                case other => assert(false, s"expected AIMissingApiKeyException, got: $other")
        }
    }

    "parallel tool results merge into one user message, never consecutive same-role (agent-framework #4328)" in {
        // Anthropic rejects consecutive same-role messages (microsoft/agent-framework #4328, litellm #22946). An
        // assistant turn with two tool calls produces two tool results, which must be merged into ONE user message
        // with two tool_result blocks, not two consecutive user messages.
        def rolesOf(body: String): List[String] =
            Json.decode[Structure.Value](body).toMaybe.flatMap(r =>
                Structure.Path.field("messages").get(r).toMaybe.flatMap(_.headMaybe)
            ) match
                case Present(Structure.Value.Sequence(msgs)) =>
                    msgs.toList.flatMap {
                        case Structure.Value.Record(fields) => fields.collectFirst { case ("role", Structure.Value.Str(r)) => r }
                        case _                              => None
                    }
                case _ => Nil
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx = Context.empty
                .userMessage("do two things")
                .assistantMessage("", Chunk(Call(CallId("c1"), "tool_a", "{}"), Call(CallId("c2"), "tool_b", "{}")))
                .toolMessage(CallId("c1"), "result a")
                .toolMessage(CallId("c2"), "result b")
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(config, ctx, Chunk.empty))))
            }.andThen {
                server.captured.map { caps =>
                    val roles = rolesOf(caps.head.body)
                    assert(
                        roles.sliding(2).forall { case List(a, b) => a != b; case _ => true },
                        s"parallel tool_results must not be consecutive same-role; roles=$roles body=${caps.head.body}"
                    )
                    assert(
                        caps.head.body.split("\"tool_result\"").length - 1 == 2,
                        s"both tool_result blocks must be present: ${caps.head.body}"
                    )
                }
            }
        }
    }

    "temperature carriage: omitted when unset, sent on a pre-4.7 model without thinking, omitted on 4.7+ and under thinking" in {
        // An unset temperature must be absent from the wire entirely, not serialized as null. A set
        // temperature reaches the wire only where the API accepts it: 4.7+ models (the default
        // claude-opus-4-8 included) removed the parameter and 400 on it, and with thinking enabled
        // (the default) Anthropic requires temperature 1, so it is omitted in both of those cases.
        TestCompletionServer.run { server =>
            val unset       = keyedConfig(server.baseUrl).disableReasoning
            val legacySet   = Config.Anthropic.sonnet_4_6.apiKey("test-key").apiUrl(server.baseUrl).disableReasoning.temperature(0.5)
            val removedSet  = keyedConfig(server.baseUrl).disableReasoning.temperature(0.5)
            val thinkingSet = keyedConfig(server.baseUrl).temperature(0.5)
            server.enqueueBody(minimalAnthropicBody("ok"))
                .andThen(LLM.run(unset)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                    unset,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalAnthropicBody("ok")))
                .andThen(LLM.run(legacySet)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                    legacySet,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalAnthropicBody("ok")))
                .andThen(LLM.run(removedSet)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                    removedSet,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalAnthropicBody("ok")))
                .andThen(LLM.run(thinkingSet)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                    thinkingSet,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        assert(!caps(0).body.contains("temperature"), s"an unset temperature must be omitted entirely: ${caps(0).body}")
                        assert(
                            caps(1).body.contains("\"temperature\":0.5"),
                            s"a set temperature must be sent on a model that accepts it (sonnet-4-6): ${caps(1).body}"
                        )
                        assert(
                            !caps(2).body.contains("\"temperature\""),
                            s"a 4.7+ model rejects temperature, so a set value must be omitted: ${caps(2).body}"
                        )
                        assert(
                            !caps(3).body.contains("\"temperature\""),
                            s"under the thinking default even a set temperature is omitted: ${caps(3).body}"
                        )
                    }
                }
        }
    }

    "the request carries max_tokens: the model's maximum by default, and a small ask raised to clear the budget" in {
        TestCompletionServer.run { server =>
            // Declared budgeted, so the budget bounds reasoning and the ceiling makes room for it.
            // model() re-points apiUrl at the provider's real base URL, so the test server's URL is
            // re-applied after it.
            val budgeted = keyedConfig(server.baseUrl)
                .model(
                    Config.Anthropic,
                    "test-model",
                    200000,
                    Config.OutputMaximum.Verified(64000),
                    Config.ReasoningEncoding.TokenBudget,
                    true,
                    acceptsImages = true
                )
                .apiUrl(server.baseUrl)
            val default = budgeted // maxTokens unset, thinking on by default
            // An explicit ask BELOW the reasoning budget: this wire refuses a ceiling that does not
            // exceed its budget, so it is raised to clear it rather than sent to be rejected.
            val tightAsk   = budgeted.maxTokens(256)
            val defaultCap = 64000
            val raisedCap  = Config.defaultReasoningBudget + 4096
            server.enqueueBody(minimalAnthropicBody("ok"))
                .andThen(LLM.run(default)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                    default,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalAnthropicBody("ok")))
                .andThen(LLM.run(tightAsk)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                    tightAsk,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        assert(
                            caps(0).body.contains(s"\"max_tokens\":$defaultCap"),
                            s"an unset ceiling must carry the model's maximum ($defaultCap): ${caps(0).body}"
                        )
                        assert(
                            caps(1).body.contains(s"\"max_tokens\":$raisedCap"),
                            s"an ask below the reasoning budget is raised to clear it ($raisedCap): ${caps(1).body}"
                        )
                    }
                }
        }
    }

    "no result-envelope instruction is injected into the system field (strict mode enforces the schema)" in {
        // An earlier revision appended a schema restatement plus a no-replay guardrail to the system prompt.
        // Under strict structured outputs that is redundant, and its "earlier user messages are conversation
        // history, not active requests" clause made the model discount tool results it must copy from on a
        // forced result turn (the recover-a-withheld-result scenario), so it was removed.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl).disableReasoning
            val ctx    = Context.empty.systemMessage("you are X").userMessage("solve it")
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException](Abort.run[AIException](
                        AnthropicCompletion(config, ctx, Chunk.empty, Present(Json.jsonSchema[String]))
                    ))
                }.andThen {
                    server.captured.map { caps =>
                        assert(!caps.head.body.contains("Never replay"), s"no coaching instruction should be injected: ${caps.head.body}")
                        assert(caps.head.body.contains("you are X"), s"the original system content is preserved: ${caps.head.body}")
                    }
                }
            }
        }
    }

    case class MathAnswer(reasoning: String, answer: Int) derives Schema, CanEqual

    "the DEFAULT config runs the thinking branch: thinking block, require-all advisory schema, no strict, no tool_choice" in {
        // Thinking is on by default, so the default Anthropic path is the ADVISORY branch whose
        // result schema is the same require-all envelope every backend advertises.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val body =
                """{"id":"m1","content":[{"type":"tool_use","id":"r1","name":"result_tool","input":{"resultValue":{"reasoning":"because","answer":42}}}],"model":"claude-sonnet-4-5-20250929","role":"assistant","stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
            server.enqueueBody(body).andThen {
                LLM.run(config)(AI.gen[MathAnswer]).andThen {
                    server.captured.map { caps =>
                        val req = caps.head.body
                        assert(
                            req.contains("\"thinking\":{\"type\":\"adaptive\"}"),
                            s"the default model (opus-4-8) takes the adaptive thinking shape: $req"
                        )
                        assert(
                            !req.contains("budget_tokens"),
                            s"adaptive thinking carries no budget field: $req"
                        )
                        assert(
                            req.contains("\"required\":[\"reasoning\",\"answer\"]"),
                            s"the default path advertises the require-all advisory schema: $req"
                        )
                        assert(!req.contains("\"strict\""), s"the default path must not send strict structured outputs: $req")
                        assert(!req.contains("\"tool_choice\""), s"the default path must send no tool_choice at all: $req")
                    }
                }
            }
        }
    }

    "the configured budget rides as enabled+budget_tokens on every model that accepts the bounded shape" in {
        // Pre-4.6 models accept only enabled+budget, and 4.6 accepts both shapes: on all of them the
        // configured budget must ride as the BOUNDED enabled form (adaptive would silently discard the
        // budget and let thinking run unbounded); see supportsThinkingBudget.
        def legacyShapeSent(config: Config, label: String) =
            TestCompletionServer.run { server =>
                val cfg = config.apiKey("test-key").apiUrl(server.baseUrl)
                server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                    LLM.run(cfg)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                        cfg,
                        Context.empty.userMessage("hi"),
                        Chunk.empty
                    )))).andThen {
                        server.captured.map { caps =>
                            assert(
                                caps.head.body.contains(
                                    s"\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":${Config.defaultReasoningBudget}}"
                                ),
                                s"$label must send the legacy enabled+budget shape: ${caps.head.body}"
                            )
                        }
                    }
                }
            }
        legacyShapeSent(Config.Anthropic.haiku_4_5, "the haiku-4-5 catalog entry")
            .andThen(legacyShapeSent(
                Config.Anthropic.default.model(
                    Config.Anthropic,
                    "claude-sonnet-4-5-20250929",
                    200000,
                    Config.OutputMaximum.Verified(64000),
                    Config.ReasoningEncoding.TokenBudget,
                    true,
                    acceptsImages = true
                ),
                "a sonnet-4-5 pre-4.6 id"
            ))
            .andThen(legacyShapeSent(
                Config.Anthropic.default.model(
                    Config.Anthropic,
                    "claude-opus-4-5-20251101",
                    200000,
                    Config.OutputMaximum.Verified(64000),
                    Config.ReasoningEncoding.TokenBudget,
                    true,
                    acceptsImages = true
                ),
                "an opus-4-5 pre-4.6 id"
            ))
            .andThen(legacyShapeSent(Config.Anthropic.sonnet_4_6, "the sonnet-4-6 catalog entry (accepts both shapes)"))
    }

    "an explicit budget on a both-shapes model rides verbatim; 4.7+ models send adaptive without a budget" in {
        // Regression guard: sending adaptive on a model that accepts the bounded shape silently
        // discarded an explicit budget, so thinking ran unbounded and long completions never returned.
        def shapeSent(config: Config, expected: String, label: String) =
            TestCompletionServer.run { server =>
                val cfg = config.apiKey("test-key").apiUrl(server.baseUrl)
                server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                    LLM.run(cfg)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                        cfg,
                        Context.empty.userMessage("hi"),
                        Chunk.empty
                    )))).andThen {
                        server.captured.map { caps =>
                            assert(caps.head.body.contains(expected), s"$label: expected $expected in: ${caps.head.body}")
                        }
                    }
                }
            }
        shapeSent(
            Config.Anthropic.sonnet_4_6.reasoningBudget(9000),
            "\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":9000}",
            "an explicit budget on sonnet-4-6 must stay bounded"
        )
            .andThen(shapeSent(
                Config.Anthropic.default.model(
                    Config.Anthropic,
                    "claude-opus-4-7",
                    1000000,
                    Config.OutputMaximum.Verified(128000),
                    Config.ReasoningEncoding.Adaptive,
                    false,
                    acceptsImages = true
                ).reasoningBudget(9000),
                "\"thinking\":{\"type\":\"adaptive\"}",
                "a 4-7 id rejects the bounded shape and takes adaptive"
            ))
            .andThen(shapeSent(
                Config.Anthropic.sonnet_5.reasoningBudget(9000),
                "\"thinking\":{\"type\":\"adaptive\"}",
                "sonnet-5 rejects the bounded shape and takes adaptive"
            ))
            .andThen(shapeSent(
                Config.Anthropic.fable_5.reasoningBudget(9000),
                "\"thinking\":{\"type\":\"adaptive\"}",
                "fable-5 rejects the bounded shape and takes adaptive"
            ))
    }

    "the thinking branch sends the require-all advisory schema without strict or tool_choice" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl).reasoningBudget(4000)
            val body =
                """{"id":"m1","content":[{"type":"tool_use","id":"r1","name":"result_tool","input":{"resultValue":{"reasoning":"because","answer":42}}}],"model":"claude-sonnet-4-5-20250929","role":"assistant","stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
            server.enqueueBody(body).andThen {
                LLM.run(config)(AI.gen[MathAnswer]).andThen {
                    server.captured.map { caps =>
                        val req = caps.head.body
                        assert(req.contains("\"required\":[\"resultValue\"]"), s"the envelope's resultValue must be required: $req")
                        assert(
                            req.contains("\"required\":[\"reasoning\",\"answer\"]"),
                            s"the inner object's own properties must all be required too: $req"
                        )
                        assert(!req.contains("\"strict\""), s"the thinking branch must not send strict structured outputs: $req")
                        // The API defaults to auto, the same absent-auto the Claude Code CLI's requests carry.
                        assert(!req.contains("\"tool_choice\""), s"the thinking branch must send no tool_choice at all: $req")
                    }
                }
            }
        }
    }

    "extended thinking reasons before returning a correct structured answer (real Anthropic API)" in {
        // Extended thinking (Config.reasoningBudget) switches the result tool to the advisory schema and gives
        // the model a reasoning pass before it emits the structured result, the native-API analog of the Claude
        // Code backend's reason-then-answer turn. Runs against the real API when ANTHROPIC_API_KEY is set.
        val key = sys.env.getOrElse("ANTHROPIC_API_KEY", "")
        assume(key.nonEmpty, "ANTHROPIC_API_KEY is not available")
        val config = Config.Anthropic.haiku_4_5.apiKey(key).reasoningBudget(4000)
        LLM.run(config) {
            Abort.run[AIException] {
                AI.initWith { ai =>
                    for
                        _ <- ai.userMessage(
                            "A train travels 60 miles in 1.5 hours, then 40 miles in 0.5 hours. What is its " +
                                "average speed in mph over the whole trip? Reason step by step, then give the integer answer."
                        )
                        ans <- ai.gen[MathAnswer]
                    yield ans
                }
            }
        }.map {
            case Result.Success(ans) =>
                assert(ans.answer == 50, s"expected 50 mph, got ${ans.answer}; reasoning: ${ans.reasoning.take(300)}")
            case other =>
                fail(s"thinking gen failed: $other")
        }
    }

    "a configured temperature on a 4.7+ model is omitted from the wire and the gen succeeds (real Anthropic API)" in {
        // The default catalog model (claude-opus-4-8) rejects a request carrying temperature with a 400,
        // so the backend omits the parameter from 4.7 on; a configured temperature must not fail the gen.
        val key = sys.env.getOrElse("ANTHROPIC_API_KEY", "")
        assume(key.nonEmpty, "ANTHROPIC_API_KEY is not available")
        val config = Config.Anthropic.default.apiKey(key).disableReasoning.temperature(0.5)
        LLM.run(config) {
            Abort.run[AIException] {
                AI.initWith { ai =>
                    ai.userMessage("What is 21 + 21? Return the integer.").andThen(ai.gen[Int])
                }
            }
        }.map {
            case Result.Success(n) => assert(n == 42, s"expected 42, got $n")
            case other             => fail(s"a temperature-set gen on a 4.7+ model must succeed with the parameter omitted: $other")
        }
    }

    "an explicit thinking budget on a both-shapes model stays bounded and the gen returns (real Anthropic API)" in {
        // Regression guard: sending adaptive on sonnet-4-6 silently discarded a configured budget, so
        // thinking ran unbounded and long completions never returned. The bounded enabled+budget shape
        // must ride the wire and the generation must complete.
        val key = sys.env.getOrElse("ANTHROPIC_API_KEY", "")
        assume(key.nonEmpty, "ANTHROPIC_API_KEY is not available")
        val config = Config.Anthropic.sonnet_4_6.apiKey(key).reasoningBudget(12000)
        LLM.run(config) {
            Abort.run[AIException] {
                AI.initWith { ai =>
                    ai.userMessage("What is 21 + 21? Return the integer.").andThen(ai.gen[Int])
                }
            }
        }.map {
            case Result.Success(n) => assert(n == 42, s"expected 42, got $n")
            case other             => fail(s"a bounded-budget gen on sonnet-4-6 must return: $other")
        }
    }

    "usage sums the cache fields into the input total: creation counts as read, not cached" in {
        // This wire reports cache traffic BESIDE input_tokens: the input total is all three summed,
        // cachedInputTokens is the cache_read side alone, and reasoning is never broken out.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val body =
                """{"id":"msg-1","content":[{"type":"text","text":"ok"}],"model":"m","role":"assistant","stop_reason":"end_turn","stop_sequence":null,""" +
                    """"usage":{"input_tokens":50,"output_tokens":7,"cache_read_input_tokens":30,"cache_creation_input_tokens":5}}"""
            server.enqueueBody(body).andThen {
                LLM.run(config) {
                    AnthropicCompletion(config, Context.empty.userMessage("hi"), Chunk.empty, Absent)
                }.map { reply =>
                    assert(reply.usage == AIStats(85L, Present(30L), 7L, Absent, 1), s"got ${reply.usage}")
                }
            }
        }
    }

end AnthropicCompletionTest
