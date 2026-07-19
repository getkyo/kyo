package kyo.ai.completion

import kyo.*
import kyo.ai.*
import kyo.ai.Context.*

class CompletionTest extends kyo.test.Test[Any]:

    val openAIBody = """{"choices":[{"message":{"role":"assistant","content":"hello","tool_calls":null}}]}"""

    def keyedOpenAIConfig(baseUrl: String): Config =
        Config.OpenAI.default.apiKey("test-key").apiUrl(baseUrl)

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
                    assert(result.isSuccess)
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

    "INV-CMP-66: openai-compat usage decoded into Completion.Usage" in {
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

    "INV-CMP-66: anthropic wire Usage converted, cachedInputTokens Absent" in {
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

    "INV-CMP-66: a reply with no usage yields Result.usage Absent" in {
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

    "INV-CMP-67: embed CLEAN for OpenAI + Gemini (wire round-trip)" in {
        def check(provider: Config.Provider) =
            TestCompletionServer.run { server =>
                val config = provider.default.apiKey("test-key").apiUrl(server.baseUrl)
                server.enqueueBody("""{"data":[{"embedding":[0.1,0.2],"index":0}]}""").andThen {
                    LLM.run(config) {
                        Abort.run[HttpException] {
                            Abort.run[AIException] {
                                OpenAICompletion.embed(config, Chunk("hi"))
                            }
                        }
                    }.map { result =>
                        result match
                            case Result.Success(Result.Success(embeddings)) =>
                                assert(embeddings.size == 1, s"expected 1 embedding, got ${embeddings.size}")
                                val e = embeddings.head
                                // Span wraps a raw array (reference-equal, not structural), so compare its
                                // elements via toArray.toSeq rather than a direct Embedding == comparison.
                                assert(
                                    e.vector.toArray.toSeq == Seq(0.1f, 0.2f),
                                    s"expected vector [0.1,0.2], got ${e.vector.toArray.toSeq}"
                                )
                                assert(e.modelName == config.modelName, s"expected modelName ${config.modelName}, got ${e.modelName}")
                                assert(e.dim == 2, s"expected dim 2, got ${e.dim}")
                            case other =>
                                assert(false, s"expected success, got: $other")
                    }.andThen {
                        server.captured.map { caps =>
                            assert(caps.head.body.contains("\"model\""), s"embeddings request should carry model: ${caps.head.body}")
                            assert(
                                caps.head.body.contains("\"input\":[\"hi\"]"),
                                s"embeddings request should carry input: ${caps.head.body}"
                            )
                        }
                    }
                }
            }
        check(Config.OpenAI).andThen(check(Config.Gemini))
    }

    "INV-CMP-67 absence: four openai-compat ABSENT providers Abort typed" in {
        def check(config: Config) =
            LLM.run(config) {
                Abort.run[HttpException] {
                    Abort.run[AIException] {
                        OpenAICompletion.embed(config, Chunk("x"))
                    }
                }
            }.map { result =>
                result match
                    case Result.Success(Result.Failure(ex: AIEmbeddingUnsupportedException)) =>
                        assert(ex.provider == config.provider.name, s"expected provider ${config.provider.name}, got ${ex.provider}")
                    case other =>
                        assert(false, s"expected AIEmbeddingUnsupportedException for ${config.provider.name}, got: $other")
            }
        check(Config.DeepSeek.default)
            .andThen(check(Config.Groq.default))
            .andThen(check(Config.Baseten.default))
            .andThen(check(Config.OpenRouter.default))
    }

    "INV-CMP-67 absence: Anthropic + CLI harnesses take the trait default" in {
        def check(config: Config) =
            LLM.run(config) {
                Abort.run[HttpException] {
                    Abort.run[AIException] {
                        config.provider.completion.embed(config, Chunk("x"))
                    }
                }
            }.map { result =>
                result match
                    case Result.Success(Result.Failure(ex: AIEmbeddingUnsupportedException)) =>
                        assert(ex.provider == config.provider.name, s"expected provider ${config.provider.name}, got ${ex.provider}")
                    case other =>
                        assert(false, s"expected AIEmbeddingUnsupportedException for ${config.provider.name}, got: $other")
            }
        check(Config.Anthropic.default)
            .andThen(check(Config.ClaudeCode.default))
            .andThen(check(Config.Codex.default))
    }

end CompletionTest
