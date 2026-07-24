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

    "classifyHttp maps a client timeout to AICompletionTimeoutException (fail fast)" in {
        // The rendered provider comes from the entry classifyHttp is given, so the expectation reads it
        // from the same place rather than repeating a literal that can drift from it.
        val entry = Config.OpenAI.default
        val ex    = Completion.classifyHttp(entry, HttpTimeoutException(30.seconds, "POST", "https://example.test"))
        assert(ex == AICompletionTimeoutException(entry.provider.name, 30.seconds))
        assert(!ex.isInstanceOf[AITransientException], "a client timeout fails this call, it does not retry")
    }

    "classifyHttp maps 401/403 to auth, 429 to throttle, 5xx to unavailable, and any other status to AIRequestRejectedException" in {
        def status(code: Int) =
            Completion.classifyHttp(Config.OpenAI.default, HttpStatusException(HttpStatus(code), "POST", "https://example.test"))
        assert(status(401).isInstanceOf[AIProviderAuthException])
        assert(status(403).isInstanceOf[AIProviderAuthException])
        assert(status(429).isInstanceOf[AIRateLimitException])
        assert(status(503).isInstanceOf[AIProviderUnavailableException])
        assert(status(400).isInstanceOf[AIRequestRejectedException])
    }

    "classifyHttp maps every other transport error (connect/DNS/decode) to a transient AITransportException" in {
        val ex = Completion.classifyHttp(Config.OpenAI.default, HttpConnectException("host", 80, RuntimeException("boom")))
        assert(ex.isInstanceOf[AITransportException])
        assert(ex.isInstanceOf[AITransientException], "a transport failure retries")
    }

    "the streaming error path types an HTTP failure identically to gen (routed through classifyHttp, not blanket AITransportException)" in {
        val route = HttpRoute.postRaw("unauthorized").request(_.bodyText).handler { _ => HttpResponse.unauthorized }
        HttpServer.initWith(HttpServerConfig.default)(route) { server =>
            val request = Completion.StreamRequest(s"http://127.0.0.1:${server.port}/unauthorized", Seq.empty, "{}")
            Completion.sseFragments(Config.OpenAI.default, request, _ => Result.Success(Completion.Delta.Skip), Absent).map { stream =>
                Abort.run[AIStreamException](stream.run).map {
                    case Result.Failure(_: AIProviderAuthException) => succeed
                    case other =>
                        fail(s"expected AIProviderAuthException (the same leaf AI.gen types via classifyHttp), got: $other")
                }
            }
        }
    }

    "fitSystemMessages" - {
        // The impls pass their own conversion for a demoted system message; these tests use the
        // OpenAI-family prefix so the asserted output is concrete.
        val convert = (content: String) => UserMessage(s"${Completion.systemInstructionPrefix} $content", Absent)
        // Config.Gemini declares SystemMessages.FirstOnly; Config.OpenAI declares AllDelivered. The entries are
        // read for their declaration only, which is the point: the transform never asks who they are.
        val single = Config.Gemini.default
        val many   = Config.OpenAI.default

        def roles(messages: Chunk[Message]): List[String] = messages.map(_.role.toString.toLowerCase).toList

        "leaves a context untouched when the wire delivers many" in {
            val messages = Chunk[Message](
                SystemMessage("first"),
                UserMessage("ask", Absent),
                SystemMessage("second")
            )
            assert(Completion.fitSystemMessages(many, messages, convert) == messages)
        }

        "merges the leading run and converts every later system message" in {
            val messages = Chunk[Message](
                SystemMessage("alpha"),
                SystemMessage("beta"),
                UserMessage("ask", Absent),
                SystemMessage("reminder")
            )
            val fitted = Completion.fitSystemMessages(single, messages, convert)
            assert(roles(fitted) == List("system", "user", "user"), s"roles: ${roles(fitted)}")
            assert(
                fitted.head == SystemMessage("alpha\n\nbeta"),
                s"the leading run merges in order: ${fitted.head}"
            )
            assert(
                fitted.last == UserMessage(s"${Completion.systemInstructionPrefix} reminder", Absent),
                s"the trailing one converts behind the prelude: ${fitted.last}"
            )
        }

        "converts a mid-conversation system message in place, never hoisting it" in {
            // Position is the whole content of a mid-conversation instruction: it governs from where it
            // appears. Moving it into the leading block would make it govern the turns before it.
            val messages = Chunk[Message](
                SystemMessage("standing"),
                UserMessage("first ask", Absent),
                SystemMessage("from now on, be terse"),
                UserMessage("second ask", Absent)
            )
            val fitted = Completion.fitSystemMessages(single, messages, convert)
            assert(roles(fitted) == List("system", "user", "user", "user"), s"roles: ${roles(fitted)}")
            assert(fitted(1) == UserMessage("first ask", Absent), "the first ask keeps its place")
            assert(
                fitted(2) == UserMessage(s"${Completion.systemInstructionPrefix} from now on, be terse", Absent),
                s"the instruction stays between the asks it separates: ${fitted(2)}"
            )
            assert(fitted(3) == UserMessage("second ask", Absent), "the second ask still follows it")
        }

        "keeps exactly one system message whatever the input" in {
            val messages = Chunk[Message](
                UserMessage("ask", Absent),
                SystemMessage("one"),
                SystemMessage("two"),
                UserMessage("more", Absent),
                SystemMessage("three")
            )
            val fitted = Completion.fitSystemMessages(single, messages, convert)
            val systems = fitted.count {
                case SystemMessage(_) => true
                case _                => false
            }
            assert(systems == 1, s"exactly one system message survives: ${roles(fitted)}")
            // The run is contiguous here, so "one" and "two" merge and only "three" converts.
            assert(fitted(1) == SystemMessage("one\n\ntwo"), s"contiguous run merges: ${fitted(1)}")
        }

        "leaves a context with no system message alone" in {
            val messages = Chunk[Message](UserMessage("ask", Absent))
            assert(Completion.fitSystemMessages(single, messages, convert) == messages)
        }

        "preserves non-system messages and their order" in {
            val messages = Chunk[Message](
                SystemMessage("s"),
                UserMessage("u", Absent),
                AssistantMessage("a"),
                ToolMessage(CallId("c1"), "t")
            )
            val fitted = Completion.fitSystemMessages(single, messages, convert)
            assert(fitted.drop(1) == messages.drop(1), s"the tail is untouched: $fitted")
        }
    }

    "elideBody" - {
        "leaves a body at or under the cap untouched" in {
            val small = "x" * 8192
            assert(Completion.elideBody(small) == small, "8192 is not over the cap")
            assert(Completion.elideBody("short") == "short")
        }
        "caps an oversized body and names its true length" in {
            val big    = "y" * 20000
            val elided = Completion.elideBody(big)
            assert(elided.startsWith("y" * 8192), "keeps the leading structure")
            assert(elided.contains("[truncated from 20000 chars]"), s"names the true length: ${elided.takeRight(40)}")
            assert(elided.length < big.length, "and is shorter than the original")
        }
    }

end CompletionTest
