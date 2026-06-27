package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.ai.*
import kyo.ai.Context.*

class OpenAICompletionTest extends kyo.test.Test[Any]:

    def keyedConfig(baseUrl: String): Config =
        Config.OpenAI.default.apiKey("test-key").apiUrl(baseUrl)

    def minimalOpenAIBody(content: String): String =
        s"""{"choices":[{"message":{"role":"assistant","content":"$content","tool_calls":null}}]}"""

    "the request omits temperature when unset and sends it when set" in {
        // Newer models reject any temperature parameter (claude-opus-4-8 returns 400 "temperature is deprecated";
        // gpt-5 forces 1.0), so an unset temperature must be absent from the wire, not sent as null.
        TestCompletionServer.run { server =>
            val unset = keyedConfig(server.baseUrl)
            val set   = keyedConfig(server.baseUrl).temperature(0.5)
            server.enqueueBody(minimalOpenAIBody("ok"))
                .andThen(LLM.run(unset)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    unset,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalOpenAIBody("ok")))
                .andThen(LLM.run(set)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
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

    "the outgoing request reproduces OpenAI wire field names" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx = Context.empty
                .systemMessage("you are a test assistant")
                .userMessage("hello")
                .assistantMessage("", Chunk(Call(CallId("call-1"), "my_tool", """{"x":1}""")))
                .toolMessage(CallId("call-1"), "tool result")
            val toolInfo = Tool.init[Int]("my_tool", "a test tool")(_ => 0).infos.head
            server.enqueueBody(minimalOpenAIBody("done")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            OpenAICompletion(config, ctx, Chunk(toolInfo))
                        }
                    }
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.size == 1, s"expected 1 captured request, got ${caps.size}")
                        val body = caps.head.body
                        assert(body.contains("\"tool_choice\""), s"request should contain tool_choice field: $body")
                        assert(body.contains("\"required\""), s"request should contain required value: $body")
                        assert(body.contains("\"type\":\"function\""), s"request should contain type:function for tool def: $body")
                        assert(body.contains("\"strict\":false"), s"request should contain strict:false: $body")
                        assert(body.contains("\"parameters\""), s"request should contain parameters field: $body")
                        assert(body.contains("\"tool_call_id\""), s"request should contain tool_call_id for tool message: $body")
                    }
                }
            }
        }
    }

    "null-tolerant read of the real response (absent content and tool_calls)" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx    = Context.empty.userMessage("hello")
            server.enqueueBody("""{"choices":[{"message":{"role":"assistant"}}]}""").andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            OpenAICompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.map { result =>
                    result match
                        case Result.Success(Result.Success(msg)) =>
                            assert(msg.content == "", s"expected empty content, got: ${msg.content}")
                            assert(msg.calls == Chunk.empty, s"expected no calls, got: ${msg.calls}")
                        case other =>
                            assert(false, s"expected success, got: $other")
                }
            }
        }
    }

    "empty choices fails with AIDecodeException" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx    = Context.empty.userMessage("hello")
            server.enqueueBody("""{"choices":[]}""").andThen {
                LLM.run(config) {
                    Abort.run[AIException] {
                        Abort.run[HttpException] {
                            OpenAICompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.map { result =>
                    assert(result.isFailure, s"empty choices should fail with AIDecodeException, got: $result")
                    result match
                        case Result.Failure(ex: AIDecodeException) =>
                            assert(ex.getMessage.contains("no choices"), s"message: ${ex.getMessage}")
                        case _ => assert(false, s"expected AIDecodeException, got: $result")
                    end match
                }
            }
        }
    }

    "a tool call in the real reply decodes to a Context.Call" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ctx    = Context.empty.userMessage("hello")
            val toolBody =
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"tid-1","type":"function","function":{"name":"my_fn","arguments":"{\"x\":42}"}}]}}]}"""
            server.enqueueBody(toolBody).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            OpenAICompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.map { result =>
                    result match
                        case Result.Success(Result.Success(msg)) =>
                            assert(msg.calls.size == 1, s"expected 1 call, got ${msg.calls.size}")
                            val call = msg.calls.head
                            assert(call.id == CallId("tid-1"), s"expected call id 'tid-1', got ${call.id}")
                            assert(call.function == "my_fn", s"expected function 'my_fn', got ${call.function}")
                            assert(call.arguments == "{\"x\":42}", s"expected arguments, got ${call.arguments}")
                        case other =>
                            assert(false, s"expected success, got: $other")
                }
            }
        }
    }

    "a user message with an image serializes as a content-parts array with image_url" in {
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val image  = Image.fromBytes(Span.from(Array[Byte](1, 2, 3)))
            val ctx    = Context.empty.userMessage("look at this", Present(image))
            server.enqueueBody(minimalOpenAIBody("ok")).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            OpenAICompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.size == 1, s"expected 1 captured request, got ${caps.size}")
                        val body = caps.head.body
                        assert(body.contains("image_url"), s"vision request should contain image_url: $body")
                        assert(
                            body.contains("data:image/jpeg;base64,AQID"),
                            s"vision request should contain base64 payload: $body"
                        )
                        assert(body.contains("look at this"), s"vision request should contain text content: $body")
                    }
                }
            }
        }
    }

    "streaming: parseDeltaArguments extracts the arguments delta; [DONE] and content-only deltas carry none" in {
        def frag(line: String): Maybe[String] =
            OpenAICompletion.parseDeltaArguments(line) match
                case Result.Success(m) => m
                case other             => sys.error(s"unexpected parse failure for $line: $other")
        val toolDelta =
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"x\":1}"}}]}}]}"""
        assert(frag(toolDelta) == Present("""{"x":1}"""), s"tool fragment: ${frag(toolDelta)}")
        // the [DONE] terminator and a content-only/empty-tool-calls delta carry no argument fragment
        assert(frag("[DONE]").isEmpty)
        assert(frag("""{"choices":[{"delta":{"tool_calls":null}}]}""").isEmpty)
        // a line that is not a parseable streaming chunk is a failure
        assert(OpenAICompletion.parseDeltaArguments("not a streaming chunk").isFailure)
    }

end OpenAICompletionTest
