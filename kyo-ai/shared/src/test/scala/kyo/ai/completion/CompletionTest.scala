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

end CompletionTest
