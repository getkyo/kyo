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
                        case Result.Success(Result.Success(Completion.Reply(Chunk(msg: AssistantMessage), _))) =>
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
                case Result.Success(m) => m
                case other             => sys.error(s"unexpected parse failure for $line: $other")
        val toolDelta =
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"x\":1}"}}"""
        assert(frag(toolDelta) == Present("""{"x":1}"""), s"tool fragment: ${frag(toolDelta)}")
        // text deltas, lifecycle events, and pings carry no tool-argument fragment
        assert(frag("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""").isEmpty)
        assert(frag("""{"type":"message_stop"}""").isEmpty)
        assert(frag("""{"type":"ping"}""").isEmpty)
        // a non-event line is a parse failure
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

    "a non-head system message serializes as an [INTERNAL SYSTEM INSTRUCTION] user message" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx = Context.empty
                .systemMessage("primary") // head -> top-level system
                .userMessage("hi")
                .systemMessage("a reminder") // non-head -> internal instruction (Anthropic has no system tail)
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(config, ctx, Chunk.empty)))
                }.andThen {
                    server.captured.map { caps =>
                        val body = caps.head.body
                        assert(
                            body.contains("[INTERNAL SYSTEM INSTRUCTION]"),
                            s"a non-head system must map to an internal instruction: $body"
                        )
                        assert(body.contains("a reminder"), s"the reminder content must appear: $body")
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

    "the request omits temperature when unset and sends it when set" in {
        // claude-opus-4-8 returns 400 "temperature is deprecated for this model", so an unset temperature must be
        // absent from the wire entirely, not serialized as null.
        TestCompletionServer.run { server =>
            val unset = keyedConfig(server.baseUrl)
            val set   = keyedConfig(server.baseUrl).temperature(0.5)
            server.enqueueBody(minimalAnthropicBody("ok"))
                .andThen(LLM.run(unset)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                    unset,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalAnthropicBody("ok")))
                .andThen(LLM.run(set)(Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                    set,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        assert(!caps(0).body.contains("temperature"), s"an unset temperature must be omitted entirely: ${caps(0).body}")
                        assert(caps(1).body.contains("\"temperature\":0.5"), s"a set temperature must be sent: ${caps(1).body}")
                    }
                }
        }
    }

    "the request carries max_tokens, defaulting to 8192 when unset" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl) // maxTokens unset
            server.enqueueBody(minimalAnthropicBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException](Abort.run[AIException](AnthropicCompletion(
                        config,
                        Context.empty.userMessage("hi"),
                        Chunk.empty
                    )))
                }.andThen {
                    server.captured.map { caps =>
                        assert(
                            caps.head.body.contains("\"max_tokens\":8192"),
                            s"request must carry the default max_tokens 8192: ${caps.head.body}"
                        )
                    }
                }
            }
        }
    }

end AnthropicCompletionTest
