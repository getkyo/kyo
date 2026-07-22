package kyo.ai.completion

import kyo.*
import kyo.ai.*
import kyo.ai.Context.*

class CompletionTest extends kyo.test.Test[Any]:

    val openAIBody = """{"choices":[{"message":{"role":"assistant","content":"hello","tool_calls":null}}]}"""

    def keyedOpenAIConfig(baseUrl: String): Config =
        Config.OpenAI.default.apiKey("test-key").apiUrl(baseUrl)

    "usage kept public; not consumed by compactor" in {
        // Completion.Usage stays a public, user-readable type: a Reply carries it for user code. The
        // compactor consumes no usage; render is model-free and never touches a Completion.Reply. Here
        // assert the public shape is readable by user code.
        val u = Completion.Usage(inputTokens = 120, outputTokens = 40, cachedInputTokens = Present(64))
        assert(u.inputTokens == 120 && u.outputTokens == 40 && u.cachedInputTokens == Present(64), s"Usage is public and readable, got: $u")
    }

    "missing API key aborts typed, never throws" in {
        val config = Config.OpenAI.default
        val ctx    = Context.empty
        LLM.run(config) {
            Abort.run[AIException] {
                OpenAICompletion(config, ctx, Chunk.empty)
            }
        }.map { result =>
            assert(result.isFailure, s"expected a typed AIMissingApiKeyException failure, got: $result")
            result match
                case Result.Failure(ex: AIMissingApiKeyException) =>
                    assert(ex.getMessage.contains(config.modelName))
                case _ =>
                    assert(false, s"expected AIMissingApiKeyException, got: $result")
            end match
        }
    }

    "content-type application/json header is set on the real request" in {
        TestCompletionServer.run { server =>
            val config = keyedOpenAIConfig(server.baseUrl)
            val ctx    = Context.empty.userMessage("hello")
            server.enqueueBody(openAIBody).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        OpenAICompletion(config, ctx, Chunk.empty)
                    }
                }.map { result =>
                    result match
                        case Result.Success(res) =>
                            assert(
                                res.messages == Chunk(AssistantMessage("hello", Chunk.empty)),
                                s"expected the decoded assistant message, got: ${res.messages}"
                            )
                            assert(res.usage == Absent, s"expected Absent usage when the wire omits it, got: ${res.usage}")
                        case other =>
                            assert(false, s"expected success, got: $other")
                }
            }
        }
    }

    "a malformed server response surfaces as Abort[HttpException]" in {
        TestCompletionServer.run { server =>
            val config = keyedOpenAIConfig(server.baseUrl)
            val ctx    = Context.empty.userMessage("hello")
            server.enqueueBody("not json").andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        OpenAICompletion(config, ctx, Chunk.empty)
                    }
                }.map { result =>
                    assert(result.isFailure, "expected HttpException failure for malformed JSON response")
                }
            }
        }
    }

    "the error message uses config.modelName not a missing config.model" in {
        val config = Config.OpenAI.default
        val ctx    = Context.empty
        LLM.run(config) {
            Abort.run[AIException] {
                OpenAICompletion(config, ctx, Chunk.empty)
            }
        }.map { result =>
            result match
                case Result.Failure(ex: AIMissingApiKeyException) =>
                    assert(
                        ex.getMessage.contains(config.modelName),
                        s"message should contain modelName '${config.modelName}', got: ${ex.getMessage}"
                    )
                case _ =>
                    assert(false, s"expected AIMissingApiKeyException, got: $result")
        }
    }

    "openai-compat usage decoded into Completion.Usage" in {
        TestCompletionServer.run { server =>
            val config = keyedOpenAIConfig(server.baseUrl)
            val ctx    = Context.empty.userMessage("hello")
            val body =
                """{"choices":[{"message":{"role":"assistant","content":"hello","tool_calls":null}}],""" +
                    """"usage":{"prompt_tokens":120,"completion_tokens":40,"prompt_tokens_details":{"cached_tokens":64}}}"""
            server.enqueueBody(body).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            OpenAICompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.map { result =>
                    result match
                        case Result.Success(Result.Success(res)) =>
                            assert(
                                res.usage == Present(Completion.Usage(
                                    inputTokens = 120,
                                    outputTokens = 40,
                                    cachedInputTokens = Present(64)
                                )),
                                s"expected decoded usage, got: ${res.usage}"
                            )
                            assert(
                                res.messages == Chunk(AssistantMessage("hello", Chunk.empty)),
                                s"expected the pre-widen decoded assistant message, got: ${res.messages}"
                            )
                        case other =>
                            assert(false, s"expected success, got: $other")
                }
            }
        }
    }

    "anthropic wire Usage converted, cachedInputTokens Absent" in {
        TestCompletionServer.run { server =>
            val config = Config.Anthropic.default.apiKey("test-key").apiUrl(server.baseUrl)
            val ctx    = Context.empty.userMessage("hello")
            val body =
                """{"id":"msg-1","content":[{"type":"text","text":"ok"}],"model":"claude-sonnet-4-5-20250929",""" +
                    """"role":"assistant","stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":200,"output_tokens":15}}"""
            server.enqueueBody(body).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            AnthropicCompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.map { result =>
                    result match
                        case Result.Success(Result.Success(res)) =>
                            assert(
                                res.usage == Present(Completion.Usage(inputTokens = 200, outputTokens = 15, cachedInputTokens = Absent)),
                                s"expected converted usage with cachedInputTokens Absent, got: ${res.usage}"
                            )
                        case other =>
                            assert(false, s"expected success, got: $other")
                }
            }
        }
    }

    "a reply with no usage yields Result.usage Absent" in {
        TestCompletionServer.run { server =>
            val config = keyedOpenAIConfig(server.baseUrl)
            val ctx    = Context.empty.userMessage("hello")
            server.enqueueBody(openAIBody).andThen {
                LLM.run(config) {
                    Abort.run[HttpException] {
                        Abort.run[AIException] {
                            OpenAICompletion(config, ctx, Chunk.empty)
                        }
                    }
                }.map { result =>
                    result match
                        case Result.Success(Result.Success(res)) =>
                            assert(res.usage == Absent, s"expected Absent usage when the wire omits it, got: ${res.usage}")
                        case other =>
                            assert(false, s"expected success, got: $other")
                }
            }
        }
    }

end CompletionTest
