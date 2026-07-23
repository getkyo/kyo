package kyo

import kyo.*
import kyo.ai.*
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.completion.Completion

class LLMStreamTest extends kyo.test.Test[Any]:

    /** A deterministic tokenizer for the re-anchor leaves: every text counts as a fixed 10, so a
      * suffix count and an apportioned share are stable across platforms with no live endpoint.
      */
    val fixedTok: Tokenizer = new Tokenizer:
        def count(texts: Chunk[String])(using Frame): Chunk[Int] < (LLM & Async & Abort[HttpException | AIGenException]) =
            Kyo.lift(texts.map(_ => 10))

    /** A config with a large window so a re-anchored 50000/42000 stays below the compaction trigger and
      * renderView re-serves the context unchanged (the re-anchor is the only rewrite under test).
      */
    def wideServerConfig(baseUrl: String): Config =
        Config.OpenAI.default.apiKey("test").model(Config.OpenAI, "gpt-4o", 1000000).apiUrl(baseUrl)

    def wideAnthropicConfig(baseUrl: String): Config =
        Config.Anthropic.default.apiKey("test").model(Config.Anthropic, "claude-sonnet-4-5", 1000000).apiUrl(baseUrl)

    /** The OpenAI include_usage final chunk: empty choices plus a top-level usage object, the shape that
      * arrives before [DONE] on a stream_options.include_usage request.
      */
    def openAiUsageChunk(promptTokens: Int, completionTokens: Int): String =
        s"""{"choices":[],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens}}"""

    /** The Anthropic message_start event carrying message.usage.input_tokens (no opt-in required). */
    def anthropicMessageStart(inputTokens: Int): String =
        s"""{"type":"message_start","message":{"usage":{"input_tokens":$inputTokens}}}"""

    case class Answer(text: String) derives Schema, CanEqual
    case class Nested(name: String, inner: Answer) derives Schema, CanEqual
    case class WithList(name: String, xs: List[Int]) derives Schema, CanEqual

    enum Shape derives Schema, CanEqual:
        case Circle(radius: Int)
        case Square(side: Int)

    /** A config pointing the OpenAI backend at the test server with a dummy key. */
    def serverConfig(baseUrl: String): Config =
        Config.OpenAI.default
            .apiKey("test")
            .model(Config.OpenAI, "gpt-4o", 128000, Config.OutputMaximum.Verified(16384), Config.ReasoningEncoding.Unavailable, true, true)
            .apiUrl(baseUrl)

    /** Splits a string into fragments at `cuts` random byte boundaries, for boundary-invariance tests. */
    def randomSplit(s: String, rng: scala.util.Random): Chunk[String] =
        val n = s.length
        if n <= 1 then Chunk(s)
        else
            val cuts   = (0 until rng.nextInt(8)).map(_ => 1 + rng.nextInt(n - 1)).distinct.sorted
            val bounds = (0 +: cuts :+ n).distinct
            Chunk.from(bounds.sliding(2).collect { case Seq(a, b) => s.substring(a, b) }.toList)
        end if
    end randomSplit

    /** Wraps an argument JSON fragment in a real OpenAI streaming chunk envelope. */
    def argDelta(fragment: String): String =
        val escaped = fragment.replace("\\", "\\\\").replace("\"", "\\\"")
        s"""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$escaped","name":""}}]}}]}"""

    "a stalled stream fails typed at the configured timeout after delivering its fragments" in {
        TestCompletionServer.runStreaming { server =>
            // The provider emits part of the envelope and then stops producing without a terminator. The
            // fragments must REACH the consumer and the call must still end at its deadline, so the
            // assertion is arrived-then-failed: a bound covering only the response headers would fail
            // before anything was delivered, and a stream with no bound would never end at all.
            val config = serverConfig(server.baseUrl).timeout(300.millis)
            for
                seen <- AtomicRef.init(Chunk.empty[String])
                _    <- server.enqueueStreamStall(Chunk(argDelta("""{"resultValue":""""), argDelta("partial")))
                result <- Abort.run[AIException](
                    LLM.run(config)(Scope.run(AI.stream[String].map(_.foreach(s => seen.updateAndGet(_.append(s)).unit))))
                )
                delivered <- seen.get
            yield
                assert(
                    delivered.mkString == "partial",
                    s"the emitted fragments must reach the consumer before the deadline: $delivered"
                )
                result match
                    case Result.Failure(_: AICompletionTimeoutException) => succeed
                    case other => fail(s"expected the streaming deadline to fire on a stalled stream, got: $other")
                end match
            end for
        }
    }

    "a slow consumer does not spend the streaming deadline" in {
        TestCompletionServer.runStreaming { server =>
            // The deadline is a budget on the provider's production, not on wall-clock: a consumer that
            // pauses between elements for longer than the timeout must still receive the WHOLE stream, so
            // the assertion is on the delivered value. Asserting only that the call completed would also
            // be satisfied by an empty stream, which proves nothing about the bound.
            val config   = serverConfig(server.baseUrl).timeout(300.millis)
            val expected = List(Answer("ok"), Answer("two"))
            val args     = elementArgs(expected)
            val split    = Chunk(args.substring(0, 12), args.substring(12, 24), args.substring(24))
            for
                seen <- AtomicRef.init(Chunk.empty[Answer])
                _    <- server.enqueueStream(split.map(argDelta))
                _ <- LLM.run(config)(
                    Scope.run(AI.stream[Answer].map(_.foreach(a => Async.sleep(700.millis).andThen(seen.updateAndGet(_.append(a)).unit))))
                )
                delivered <- seen.get
            yield assert(
                delivered == Chunk.from(expected),
                s"a consumer slower than the timeout must still receive the whole stream, got: $delivered"
            )
            end for
        }
    }

    /** A config pointing the Anthropic backend at the test server with a dummy key. */
    def anthropicServerConfig(baseUrl: String): Config =
        Config.Anthropic.default
            .apiKey("test")
            .model(
                Config.Anthropic,
                "claude-sonnet-4-5",
                200000,
                Config.OutputMaximum.Verified(64000),
                Config.ReasoningEncoding.TokenBudget,
                true,
                acceptsImages = true
            )
            .apiUrl(baseUrl)

    /** Wraps an argument JSON fragment in a real Anthropic content_block_delta / input_json_delta event. */
    def anthropicArgDelta(fragment: String): String =
        val escaped = fragment.replace("\\", "\\\\").replace("\"", "\\\"")
        s"""{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"$escaped"}}"""

    /** The full {resultValue: ...} tool-call argument JSON for a streamed result, encoded via the schema so all
      * escaping is correct. `elementArgs` wraps a sequence (element mode); `prefixArgs` wraps a String.
      */
    def elementArgs[A](items: List[A])(using Schema[A]): String = s"""{"resultValue":${Json.encode(items)}}"""
    def prefixArgs(text: String): String                        = s"""{"resultValue":${Json.encode(text)}}"""

    /** One delta per character, the most adversarial split: every escape and every delimiter lands on a boundary. */
    def chars(s: String): Chunk[String] = Chunk.from(s.map(_.toString))

    "completePartialJson closes the open string and balances brackets so a prefix decodes" in {
        assert(LLM.completePartialJson("{\"resultValue\":\"the sky is bl") == "{\"resultValue\":\"the sky is bl\"}")
        assert(LLM.completePartialJson("{\"resultValue\":[{\"x\":1},{\"x\":2") == "{\"resultValue\":[{\"x\":1},{\"x\":2}]}")
        assert(LLM.completePartialJson("{\"resultValue\":123") == "{\"resultValue\":123}")
        assert(LLM.completePartialJson("{\"resultValue\":123}") == "{\"resultValue\":123}")
    }

    "stream[String] emits text chunks as the answer arrives" in {
        // A String result streams in text mode: the resultValue text arrives split mid-word and the completer
        // closes each prefix internally, but the public stream emits only the newly decoded suffix. Concatenating
        // the elements yields the full answer.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":\"The"),
                argDelta(" sky"),
                argDelta(" is blue\"}")
            )).andThen {
                LLM.run(config) {
                    AI.stream[String].map(_.run.map { collected =>
                        assert(collected == Chunk("The", " sky", " is blue"), s"expected String chunks, got: $collected")
                        assert(collected.mkString == "The sky is blue", s"chunks should concatenate to the final text: $collected")
                    })
                }
            }
        }
    }

    "stream[Int] streams array elements object by object, each emitted once" in {
        // A non-String result streams in element mode: resultValue is an array, and each element is emitted once
        // it is complete. The deltas build [1,2,3]; the stream yields 1, 2, 3 as distinct values, not prefixes.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":[1"),
                argDelta(",2"),
                argDelta(",3]}")
            )).andThen {
                LLM.run(config) {
                    AI.stream[Int].map(_.run.map { collected =>
                        assert(collected == Chunk(1, 2, 3), s"expected each Int emitted once, got: $collected")
                    })
                }
            }
        }
    }

    "stream[Answer] streams complete objects one by one, never a partial object" in {
        // Element mode over a record: resultValue is an array of Answer. Each Answer is emitted only once its
        // object closes, so a half-built {"text":"alp ...} is never emitted. The deltas split mid-object, proving
        // the partial tail is held back until complete.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":[{\"text\":\"alpha\"}"),
                argDelta(",{\"text\":\"be"),
                argDelta("ta\"},{\"text\":\"gamma\"}]}")
            )).andThen {
                LLM.run(config) {
                    AI.stream[Answer].map(_.run.map { collected =>
                        assert(
                            collected == Chunk(Answer("alpha"), Answer("beta"), Answer("gamma")),
                            s"expected three complete Answers object by object, got: $collected"
                        )
                        assert(
                            !collected.contains(Answer("be")),
                            s"a partial object must never be emitted, got: $collected"
                        )
                    })
                }
            }
        }
    }

    "stream[Answer] keeps complete elements when the stream ends on a trailing comma" in {
        // The stream is cut off right after a complete element, leaving a trailing comma: [{alpha},{beta},. Both
        // alpha and beta are complete (each followed by a comma); neither may be dropped just because the array
        // never closed. This is the data-loss case: a naive complete-then-decode turns [...,] into invalid JSON.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":[{\"text\":\"alpha\"}"),
                argDelta(",{\"text\":\"beta\"},")
            )).andThen {
                LLM.run(config) {
                    AI.stream[Answer].map(_.run.map { collected =>
                        assert(
                            collected == Chunk(Answer("alpha"), Answer("beta")),
                            s"both complete elements must survive a trailing-comma cutoff, got: $collected"
                        )
                    })
                }
            }
        }
    }

    "stream[Answer] yields an empty stream for an empty array result, not an incomplete failure" in {
        // The model legitimately returns no items: resultValue is []. That is a valid (empty) result, so the
        // stream completes empty rather than failing with AIStreamIncompleteException.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":[]}"))).andThen {
                LLM.run(config) {
                    AI.stream[Answer].map(_.run.map { collected =>
                        assert(collected == Chunk.empty, s"an empty array must yield an empty stream, got: $collected")
                    })
                }
            }
        }
    }

    "stream[Answer] drops a truncated final element when the stream ends mid-object" in {
        // The stream is cut off mid-element (a max-tokens cutoff): the array never closes, ending at {"text":"be.
        // The completer would force-close that into Answer("be"), but it is a partial value, so it must be
        // dropped: only the genuinely-closed "alpha" is emitted.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":[{\"text\":\"alpha\"}"),
                argDelta(",{\"text\":\"be")
            )).andThen {
                LLM.run(config) {
                    AI.stream[Answer].map(_.run.map { collected =>
                        assert(
                            collected == Chunk(Answer("alpha")),
                            s"a truncated final element must be dropped, got: $collected"
                        )
                    })
                }
            }
        }
    }

    "stream uses the POST-to-SSE route with stream:true in the wire body" in {
        // stream:true in the JSON body is the field that activates SSE on the provider side.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"ok\"}"))).andThen {
                LLM.run(config) {
                    AI.stream[String].map(_.run)
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.nonEmpty, "server should have captured at least one request")
                        val cap = caps.head
                        assert(
                            cap.path.contains("chat/completions"),
                            s"expected path containing 'chat/completions', got '${cap.path}'"
                        )
                        assert(
                            cap.body.contains("\"stream\""),
                            s"request body must carry the 'stream' field for SSE: ${cap.body}"
                        )
                        assert(
                            cap.body.contains("true"),
                            s"request body must carry stream:true for SSE: ${cap.body}"
                        )
                    }
                }
            }
        }
    }

    "an instance-enabled compactor is consulted on the stream seam (not only gen)" in {
        // ai.enable(compactor) must take effect on the STREAM path too: streamAgainst resolves the effective
        // compactor the same way genLoop does (instance-over-scope). Enable a compacting compactor on the
        // named instance, stream against it, and assert the outbound stream request carries the compaction
        // marker (proving the seam consulted the instance compactor, not only the scope env).
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl).compaction(_.contextCeiling(3))
            val marker = "[compacted region 0: stream seam marker]"
            val c = new Compactor[Any]:
                def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
                    Chunk(SystemMessage(marker))
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"ok\"}"))).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        val ctx = Context(Chunk(
                            SystemMessage("s"),
                            UserMessage("first", Absent),
                            AssistantMessage("big " + ("x" * 400)),
                            UserMessage("latest", Absent)
                        ))
                        ai.enable(c).andThen(ai.setContext(ctx)).andThen(ai.stream[String].map(_.run)).andThen {
                            server.captured.map { caps =>
                                assert(
                                    caps.exists(cap => cap.path.contains("chat/completions") && cap.body.contains("compacted region")),
                                    s"the instance-enabled compactor compacted the stream request: ${caps.map(_.body)}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "stream fails with AIStreamDeltaException on a malformed SSE delta that is not a valid OpenAI chunk" in {
        // The malformed chunk fails parseDeltaArguments, which the stream raises as a typed AIStreamDeltaException.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk("{{{not valid json}}}")).andThen {
                Abort.run[AIException] {
                    LLM.run(config) {
                        AI.stream[String].map(_.run)
                    }
                }.map { result =>
                    assert(result.isFailure, s"expected a typed failure on malformed delta, got: $result")
                    result match
                        case Result.Failure(ex: AIStreamDeltaException) =>
                            assert(
                                !ex.getMessage.contains("stream not yet wired"),
                                s"failure must come from SSE parsing, not an unwired placeholder: ${ex.getMessage}"
                            )
                        case _ =>
                            assert(false, s"expected AIStreamDeltaException, got: $result")
                    end match
                }
            }
        }
    }

    "stream fails with AITransportException wrapping HttpException on a transport failure" in {
        // Point the config at a port that is always closed (port 1 / tcpmux is never open in
        // test environments). The HTTP layer raises HttpConnectException, which is caught by
        // Abort.recover[HttpException] inside the stream and mapped to a typed Abort.fail(AITransportException).
        val config = Config.OpenAI.default
            .apiKey("test")
            .model(Config.OpenAI, "gpt-4o", 128000, Config.OutputMaximum.Verified(16384), Config.ReasoningEncoding.Unavailable, true, true)
            .apiUrl("http://127.0.0.1:1/v1")
        Abort.run[AIException] {
            LLM.run(config) {
                AI.stream[String].map(_.run)
            }
        }.map { result =>
            assert(result.isFailure, s"expected a typed failure on transport failure, got: $result")
            result match
                case Result.Failure(ex: AITransportException) =>
                    assert(
                        ex.cause.isInstanceOf[HttpException],
                        s"AITransportException cause should be HttpException, got: ${ex.cause}"
                    )
                case _ =>
                    assert(false, s"expected AITransportException, got: $result")
            end match
        }
    }

    "stream fails with AIStreamIncompleteException when the buffered envelope never decodes" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            // A fragment that never completes to a valid envelope: the resultValue key has no value, so even the
            // completed buffer ({"resultValue"}) fails to decode and nothing is ever emitted.
            server.enqueueStream(Chunk(argDelta("{\"resultValue"))).andThen {
                Abort.run[AIException] {
                    LLM.run(config)(AI.stream[String].map(_.run))
                }.map { result =>
                    assert(result.isFailure, s"expected a typed failure, got: $result")
                    result match
                        case Result.Failure(ex: AIStreamIncompleteException) =>
                            assert(
                                ex.getMessage.contains("resultValue"),
                                s"the buffered args must be in the message, got: ${ex.getMessage}"
                            )
                        case _ =>
                            assert(false, s"expected AIStreamIncompleteException, got: $result")
                    end match
                }
            }
        }
    }

    "stream surfaces a provider error event as the declared tool-call rejection, never a swallowed empty stream" in {
        // A provider can enforce a forced tool choice by ending the SSE with an error event carrying its
        // rejection code (Groq: {"error":{...,"code":"tool_use_failed",...}}) rather than an HTTP status.
        // That event decodes as a StreamChunk whose fields are all absent, so a parser that only reads
        // choices treats it as a skip and the stream ends with an empty buffer, hiding the provider's
        // message behind an opaque incomplete failure. It must instead surface as the same typed leaf the
        // non-streaming 400 produces, driven by the entry's InvalidToolCalls declaration.
        TestCompletionServer.runStreaming { server =>
            val config = Config.Groq.gpt_oss_20b.apiKey("test").apiUrl(server.baseUrl)
            val errorEvent =
                """{"error":{"message":"Tool choice is required, but model did not call a tool","type":"invalid_request_error","code":"tool_use_failed","status_code":400}}"""
            server.enqueueStream(Chunk(errorEvent)).andThen {
                Abort.run[AIException] {
                    LLM.run(config)(AI.stream[String].map(_.run))
                }.map { result =>
                    result match
                        case Result.Failure(ex: AIToolCallRejectedException) =>
                            assert(
                                ex.getMessage.contains("Tool choice is required"),
                                s"the provider's message must be carried, got: ${ex.getMessage}"
                            )
                        case _ =>
                            assert(false, s"expected AIToolCallRejectedException, got: $result")
                    end match
                }
            }
        }
    }

    "stream classifies a non-tool provider error event by its status, never swallowing it" in {
        // An error event whose code the entry does not declare as a tool-call rejection still surfaces
        // loudly, classified by its status the way an HTTP failure is: a mid-stream 429 is a rate limit,
        // not a silently skipped delta.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            val errorEvent =
                """{"error":{"message":"slow down","type":"rate_limit_error","status_code":429}}"""
            server.enqueueStream(Chunk(errorEvent)).andThen {
                Abort.run[AIException] {
                    LLM.run(config)(AI.stream[String].map(_.run))
                }.map { result =>
                    result match
                        case Result.Failure(ex: AIRateLimitException) =>
                            assert(ex.getMessage.contains("slow down"), s"the provider's message must be carried, got: ${ex.getMessage}")
                        case _ =>
                            assert(false, s"expected AIRateLimitException, got: $result")
                    end match
                }
            }
        }
    }

    "stream fails closed: an undeclared rejection code is classified by status, never as a tool-call rejection" in {
        // The OpenAI entry does not declare tool_use_failed as its rejection code, so the same error bytes
        // Groq's entry reads as a tool-call rejection must here stay an ordinary status-classified
        // rejection. This is the streaming mirror of classifyHttp's fail-closed 400 handling: the rejection
        // leaf appears only when the entry declares the code and the body carries exactly it.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            val errorEvent =
                """{"error":{"message":"Tool choice is required, but model did not call a tool","type":"invalid_request_error","code":"tool_use_failed","status_code":400}}"""
            server.enqueueStream(Chunk(errorEvent)).andThen {
                Abort.run[AIException] {
                    LLM.run(config)(AI.stream[String].map(_.run))
                }.map { result =>
                    result match
                        case Result.Failure(_: AIToolCallRejectedException) =>
                            assert(false, s"OpenAI declares no such code; must not be typed as a tool-call rejection: $result")
                        case Result.Failure(ex: AIRequestRejectedException) =>
                            assert(ex.status == 400, s"classified by the body's status, got: ${ex.status}")
                            assert(ex.getMessage.contains("Tool choice is required"), s"the message must be carried, got: ${ex.getMessage}")
                        case _ =>
                            assert(false, s"expected AIRequestRejectedException, got: $result")
                    end match
                }
            }
        }
    }

    "every streaming request carries the result_tool definition" in {
        // Every streaming request must carry the result_tool definition in its tools array: without it the
        // model has no tool to call and cannot emit structured JSON.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":[{\"text\":\"hi\"}]}"))).andThen {
                LLM.run(config) {
                    AI.initWith(ai => ai.userMessage("explain").andThen(ai.stream[Answer])).map(_.run)
                }.andThen {
                    server.captured.map { caps =>
                        assert(caps.nonEmpty, "the streaming request should have been captured")
                        val body = caps.head.body
                        assert(
                            body.contains("\"tools\""),
                            s"streaming request body must carry a non-empty tools array, got: $body"
                        )
                        assert(
                            body.contains("result_tool"),
                            s"streaming request tools array must include result_tool, got: $body"
                        )
                    }
                }
            }
        }
    }

    "every streaming request carries the request-scoped result directive, trailing and unstored" in {
        // Streaming has no eval loop and no repair turn, so the result must arrive as a result_tool
        // call on the one streamed turn. The HTTP backends compel the call by protocol (tool_choice);
        // the command harnesses have no forcing knob, so the shared directive rides every backend's
        // request identically, like the forced-turn finalize directive. Request-scoped: the stored
        // conversation never contains it.
        val directive = s"Deliver the result by calling the '${kyo.ai.completion.Completion.resultToolName}' tool"
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":[{\"text\":\"hi\"}]}"))).andThen {
                LLM.run(config) {
                    AI.initWith { ai =>
                        ai.userMessage("explain")
                            .andThen(ai.stream[Answer].map(_.run))
                            .map(_ => ai.context)
                    }
                }.map { ctx =>
                    server.captured.map { caps =>
                        assert(caps.nonEmpty, "the streaming request should have been captured")
                        assert(
                            caps.head.body.contains(directive),
                            s"the streaming request must carry the result directive, got: ${caps.head.body}"
                        )
                        assert(
                            !ctx.raw.exists {
                                case Context.SystemMessage(content, _, _) => content.contains("Deliver the result by calling")
                                case _                                    => false
                            },
                            s"the directive must not persist in the conversation: ${ctx.raw}"
                        )
                    }
                }
            }
        }
    }

    "streaming works against the Anthropic backend (input_json_delta deltas, message_stop terminator)" in {
        TestCompletionServer.runStreaming { server =>
            val config = anthropicServerConfig(server.baseUrl)
            // Two Anthropic tool-argument deltas accumulating to an array of two Answers, ended by message_stop
            // (no [DONE]); the Anthropic parser must extract partial_json and stream each complete Answer.
            server.enqueueStream(Chunk(
                anthropicArgDelta("{\"resultValue\":[{\"text\":\"he"),
                anthropicArgDelta("llo\"},{\"text\":\"world\"}]}")
            )).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run)).map { collected =>
                    assert(
                        collected == Chunk(Answer("hello"), Answer("world")),
                        s"the Anthropic stream must yield each complete Answer, got: $collected"
                    )
                }
            }
        }
    }

    "a stream that stops at the output ceiling fails typed, not as an unfinished envelope" in {
        // The streamed reply reports the same stop the non-streamed one does, in its message delta.
        // Without decoding it, a truncated stream looked identical to one that simply never finished,
        // and failed carrying a buffer dump that named neither the cause nor the knob.
        TestCompletionServer.runStreaming { server =>
            val config = anthropicServerConfig(server.baseUrl)
            val ceilingStop =
                """{"type":"message_delta","delta":{"stop_reason":"max_tokens"},"usage":{"output_tokens":1}}"""
            server.enqueueStream(Chunk(
                anthropicArgDelta("{\"resultValue\":[{\"text\":\"par"),
                ceilingStop
            )).andThen {
                Abort.run[AIException](LLM.run(config)(Scope.run(AI.stream[Answer].map(_.run)))).map {
                    case Result.Failure(e: AIOutputLimitException) =>
                        assert(
                            e.maxOutputTokens == Present(config.effectiveMaxOutputTokens),
                            s"the failure must name the ceiling the request carried: ${e.maxOutputTokens}"
                        )
                    case other => fail(s"expected the output-ceiling failure on the stream, got: $other")
                }
            }
        }
    }

    "a streamed generation collects into the fully decoded values" in {
        // (a) The streaming surface composes at its rows: the stream value carries its I/O
        // effects in the element row, and a full collect under LLM.run yields a Chunk on the run residual.
        val tokens: Stream[Answer, LLM & Async & Scope & Abort[AIStreamException]] < LLM = AI.stream[Answer]
        val _                                                                            = tokens
        // (b) A full collect terminates in the fully decoded Answers and the request carried result_tool.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            val collected: Chunk[Answer] < (Async & Scope & Abort[AIStreamException | AIGenException]) =
                server.enqueueStream(Chunk(
                    argDelta("{\"resultValue\":[{\"text\":\"he"),
                    argDelta("llo\"}]}")
                )).andThen {
                    LLM.run(config) {
                        AI.initWith(ai => ai.userMessage("Explain backpressure.").andThen(ai.stream[Answer])).map(_.run)
                    }
                }
            collected.map { chunk =>
                assert(chunk == Chunk(Answer("hello")), s"the collected stream must decode the Answer, got: $chunk")
                server.captured.map { caps =>
                    assert(
                        caps.head.body.contains("result_tool"),
                        s"the streaming request must carry result_tool, got: ${caps.head.body}"
                    )
                }
            }
        }
    }

    // --- text mode (String) ---

    "stream[String] does not re-emit unchanged text when the closing delta arrives" in {
        // After the text decodes to "hi", the closing `"}` delta yields the same "hi"; it must be deduped, so the
        // stream emits "hi" exactly once, not twice.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"hi"), argDelta("\"}"))).andThen {
                LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                    assert(collected == Chunk("hi"), s"unchanged text must be deduped, got: $collected")
                }))
            }
        }
    }

    "stream[String] yields the empty string for an empty text result" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(prefixArgs("")))).andThen {
                LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                    assert(collected == Chunk(""), s"an empty text result must yield one empty chunk, got: $collected")
                }))
            }
        }
    }

    "stream[String] decodes escaped characters in the text" in {
        // The text carries embedded quotes, a backslash, and a newline; the emitted chunk must decode them.
        val text = "she said \"hi\"\\\n done"
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(prefixArgs(text)))).andThen {
                LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                    assert(collected.mkString == text, s"escaped characters must decode, got: $collected")
                }))
            }
        }
    }

    "stream[String] handles the whole text arriving in a single delta" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(prefixArgs("hello")))).andThen {
                LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                    assert(collected == Chunk("hello"), s"a single-delta string must yield the full text, got: $collected")
                }))
            }
        }
    }

    "stream[String] yields the partial text when the stream is cut off mid-text" in {
        // A decodable String prefix is valid, so a truncated text stream yields the shorter text, not a failure.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"hel"))).andThen {
                LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                    assert(collected == Chunk("hel"), s"a truncated text stream must yield the partial text, got: $collected")
                }))
            }
        }
    }

    // --- element mode: delta granularity ---

    "stream[Answer] emits one element per delta when each delta closes an element" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":[{\"text\":\"a\"},"),
                argDelta("{\"text\":\"b\"},"),
                argDelta("{\"text\":\"c\"}]}")
            )).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk(Answer("a"), Answer("b"), Answer("c")), s"got: $collected")
                }))
            }
        }
    }

    "stream[Answer] emits several elements that arrive together in one delta" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(elementArgs(List(Answer("a"), Answer("b"), Answer("c")))))).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk(Answer("a"), Answer("b"), Answer("c")), s"got: $collected")
                }))
            }
        }
    }

    "stream[Answer] assembles one element split across many deltas" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":[{\"te"),
                argDelta("xt\":\"hel"),
                argDelta("lo\"}]}")
            )).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk(Answer("hello")), s"got: $collected")
                }))
            }
        }
    }

    "stream[Answer] yields a single complete element" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(elementArgs(List(Answer("only")))))).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk(Answer("only")), s"got: $collected")
                }))
            }
        }
    }

    // --- element mode: content shapes the walker must not misparse ---

    "stream[Answer] does not split an element on a comma inside a string value" in {
        val items = List(Answer("a,b,c"), Answer("d"))
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(elementArgs(items)))).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk.from(items), s"a comma inside a string is not a delimiter, got: $collected")
                }))
            }
        }
    }

    "stream[Answer] does not split an element on brackets inside a string value" in {
        val items = List(Answer("}{][ ]}"), Answer("x"))
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(elementArgs(items)))).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk.from(items), s"brackets inside a string are not structure, got: $collected")
                }))
            }
        }
    }

    "stream[Answer] decodes escaped quotes and backslashes inside an element string" in {
        val items = List(Answer("she said \"hi\""), Answer("a\\b"))
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(elementArgs(items)))).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk.from(items), s"escapes inside an element string must decode, got: $collected")
                }))
            }
        }
    }

    "stream[Nested] streams elements that contain nested objects" in {
        val items = List(Nested("a", Answer("x")), Nested("b", Answer("y")))
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(elementArgs(items)))).andThen {
                LLM.run(config)(AI.stream[Nested].map(_.run.map { collected =>
                    assert(collected == Chunk.from(items), s"nested objects must not confuse the boundary parse, got: $collected")
                }))
            }
        }
    }

    "stream[WithList] streams elements that contain nested arrays" in {
        val items = List(WithList("a", List(1, 2)), WithList("b", List(3)))
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(elementArgs(items)))).andThen {
                LLM.run(config)(AI.stream[WithList].map(_.run.map { collected =>
                    assert(collected == Chunk.from(items), s"nested arrays must not confuse the boundary parse, got: $collected")
                }))
            }
        }
    }

    "stream[Answer] tolerates whitespace around elements and commas" in {
        val args = s"""{"resultValue": [ ${Json.encode(Answer("a"))} , ${Json.encode(Answer("b"))} ] }"""
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(args))).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk(Answer("a"), Answer("b")), s"whitespace must be tolerated, got: $collected")
                }))
            }
        }
    }

    "stream[Answer] fails with AIStreamIncompleteException when a complete element does not match the schema" in {
        // The element is valid JSON but the wrong shape for Answer (no `text` field), so decoding it fails.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":[{\"nope\":1}]}"))).andThen {
                Abort.run[AIException](LLM.run(config)(AI.stream[Answer].map(_.run))).map { result =>
                    assert(result.isFailure, s"a schema-mismatched element must fail the stream, got: $result")
                    assert(
                        result.failure.exists(_.isInstanceOf[AIStreamIncompleteException]),
                        s"expected AIStreamIncompleteException, got: $result"
                    )
                }
            }
        }
    }

    // --- SSE plumbing ---

    "stream ignores a keep-alive delta that carries no tool-call arguments" in {
        // A role-only delta (no tool_calls) parses to Absent and must be skipped without disturbing the result.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":[{\"text\":\"a\"},"),
                """{"choices":[{"delta":{"role":"assistant"}}]}""",
                argDelta("{\"text\":\"b\"}]}")
            )).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk(Answer("a"), Answer("b")), s"a keep-alive delta must be ignored, got: $collected")
                }))
            }
        }
    }

    "a stream that never decodes a value fails rather than yielding an empty one" in {
        // This asserted the opposite: that no deltas yield an empty stream. That contradicted the
        // documented contract, which says a stream ending without a decodable value raises
        // AIStreamIncompleteException, and the distinction matters because the empty case is reachable.
        // A request no forced choice compels can be answered in prose, which produces no tool-call
        // fragments at all; reading that as an empty success hands a caller who asked for a value
        // nothing, with no failure to notice.
        //
        // An empty resultValue ARRAY remains a legitimate empty stream; the leaf below covers it.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk.empty[String]).andThen {
                Abort.run[AIStreamException](LLM.run(config)(AI.stream[Answer].map(_.run))).map { result =>
                    assert(result.isFailure, s"a stream that decoded nothing must fail: $result")
                    result match
                        case Result.Failure(_: AIStreamIncompleteException) => succeed("incomplete, as documented")
                        case other => assert(false, s"expected AIStreamIncompleteException, got: $other")
                }
            }
        }
    }

    "an empty resultValue array is a legitimate empty stream" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("""{"resultValue":[]}"""))).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk.empty, s"an array that arrived empty yields no elements: $collected")
                }))
            }
        }
    }

    // --- request shape: the result schema reflects the mode ---

    "the streaming result schema is an array in element mode and a bare value in text mode" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(elementArgs(List(Answer("a")))))).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run))
            }.andThen {
                server.captured.map { caps =>
                    assert(
                        caps.head.body.contains("\"items\""),
                        s"element-mode request must carry an array (items) schema: ${caps.head.body}"
                    )
                }
            }
        }
    }

    "the streaming result schema carries no array items in text mode" in {
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta(prefixArgs("hi")))).andThen {
                LLM.run(config)(AI.stream[String].map(_.run))
            }.andThen {
                server.captured.map { caps =>
                    assert(!caps.head.body.contains("\"items\""), s"text-mode request must carry a bare value schema: ${caps.head.body}")
                }
            }
        }
    }

    // --- provider parity ---

    "stream[String] streams text chunks against the Anthropic backend" in {
        TestCompletionServer.runStreaming { server =>
            val config = anthropicServerConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                anthropicArgDelta("{\"resultValue\":\"The"),
                anthropicArgDelta(" sky\"}")
            )).andThen {
                LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                    assert(collected.mkString == "The sky", s"the Anthropic text stream must concatenate to the full text, got: $collected")
                    assert(collected == Chunk("The", " sky"), s"got: $collected")
                }))
            }
        }
    }

    // --- boundary invariance (randomized) ---

    "element streaming yields the same elements regardless of delta boundaries (randomized)" in {
        val items    = List(Answer("alpha"), Answer("be, ta"), Answer("ga}mma"), Answer("delta"))
        val expected = Chunk.from(items)
        val full     = elementArgs(items)
        val rng      = new scala.util.Random(20260623L)
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            Kyo.foreachDiscard(Chunk.from(0 until 40)) { i =>
                val frags = randomSplit(full, rng)
                server.enqueueStream(frags.map(argDelta)).andThen {
                    LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                        assert(collected == expected, s"iteration $i split=${frags.toList} must yield all elements, got: $collected")
                    }))
                }
            }
        }
    }

    "text streaming concatenates to the full text regardless of delta boundaries (randomized)" in {
        val text = "The quick brown fox jumps over the lazy dog."
        val full = prefixArgs(text)
        val rng  = new scala.util.Random(7L)
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            Kyo.foreachDiscard(Chunk.from(0 until 40)) { i =>
                val frags = randomSplit(full, rng)
                server.enqueueStream(frags.map(argDelta)).andThen {
                    LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                        assert(collected.nonEmpty, s"iteration $i must emit at least one text chunk")
                        assert(
                            collected.mkString == text,
                            s"iteration $i split=${frags.toList} must concatenate to the full text, got: $collected"
                        )
                    }))
                }
            }
        }
    }

    // --- ported from streaming-parser bugs in other LLM libraries ---

    "stream[String] survives an escape sequence split across a delta boundary (Instructor #1299)" in {
        // Instructor returned an empty field when a token ended on a backslash. One delta per character forces a
        // boundary inside every "\t / \n / \" / \\" escape; the completer must never decode a half-escape, and the
        // terminal must be the full text.
        val text = "a\tb\nc \"q\" \\d/e"
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(chars(prefixArgs(text)).map(argDelta)).andThen {
                LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                    assert(collected.nonEmpty, "the stream must emit at least one text chunk")
                    assert(collected.mkString == text, s"text chunks must concatenate to the full text, got: $collected")
                }))
            }
        }
    }

    "stream[Answer] survives escape sequences split across delta boundaries, character by character (Instructor #1299)" in {
        val items = List(Answer("a\tb"), Answer("c\"d"), Answer("e\\f"))
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(chars(elementArgs(items)).map(argDelta)).andThen {
                LLM.run(config)(AI.stream[Answer].map(_.run.map { collected =>
                    assert(collected == Chunk.from(items), s"escapes split at every boundary must still decode, got: $collected")
                }))
            }
        }
    }

    "stream[Shape] streams a union/enum element type, character by character (Instructor #1523)" in {
        // Instructor's partial streaming broke on union (|) types. Here the element type is a sealed enum with two
        // variants; each complete variant must decode and stream object by object even under a per-character split.
        val items = List(Shape.Circle(3), Shape.Square(5), Shape.Circle(8))
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(chars(elementArgs(items)).map(argDelta)).andThen {
                LLM.run(config)(AI.stream[Shape].map(_.run.map { collected =>
                    assert(collected == Chunk.from(items), s"a union/enum element type must stream object by object, got: $collected")
                }))
            }
        }
    }

    // --- type level ---

    "AI.stream's row is Stream[A, Async & Scope & Abort[AIStreamException]] < LLM for String and a record" in {
        val asText: Stream[String, LLM & Async & Scope & Abort[AIStreamException]] < LLM = AI.stream[String]
        val asRec: Stream[Answer, LLM & Async & Scope & Abort[AIStreamException]] < LLM  = AI.stream[Answer]
        val _                                                                            = (asText, asRec)
        assert(true)
    }

    // --- streaming re-anchor and the shared stamp-at-creation root ---

    "an OpenAI streaming turn re-anchors occupancy to the reported total at the sent view's size" in {
        TestCompletionServer.runStreaming { server =>
            val config = wideServerConfig(server.baseUrl).tokenizer(fixedTok)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":\"ok\"}"),
                openAiUsageChunk(50000, 12)
            )).andThen(server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"next\"}")))).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        ai.stream[String].map(_.run).andThen {
                            LLM.session(ai).map { session =>
                                session.streamAnchor match
                                    case Present(anchor) =>
                                        anchor.usageSink.get.map { u =>
                                            assert(
                                                u == Present(Completion.Usage(50000, 12, Absent)),
                                                s"the seated sink holds the stream-end usage, got: $u"
                                            )
                                            val sentSize = anchor.sentView.size
                                            ai.stream[String].map(_.run).andThen {
                                                ai.context.map { ctx =>
                                                    val st = ctx.compactionState
                                                    assert(
                                                        st.lastUsage == Present(50000),
                                                        s"the next turn re-anchors on the reported 50000, got: ${st.lastUsage}"
                                                    )
                                                    assert(
                                                        st.lastUsageRawSize == sentSize,
                                                        s"the anchor size is the streamed sent-view size $sentSize, got ${st.lastUsageRawSize}"
                                                    )
                                                    assert(
                                                        Compactor.internal.occupancy(ctx) >= 50000,
                                                        s"occupancy anchors at or above the reported total, got ${Compactor.internal.occupancy(ctx)}"
                                                    )
                                                }
                                            }
                                        }
                                    case Absent =>
                                        Kyo.lift(assert(false, "a streaming turn must seat a StreamAnchor"))
                            }
                        }
                    }
                }
            }
        }
    }

    "a streamed turn followed by a gen turn: the gen turn consumes the seated stream anchor, re-anchoring at the streamed sent-view size with the recorded streamed pair in the suffix" in {
        // The gen path consumes a seated StreamAnchor through the SAME applyStreamMeasure the stream path
        // uses, so a stream-then-gen sequence must compose: the streamed turn seats the anchor and records
        // its pair, and the following gen turn re-anchors at the streamed sent-view size before its own
        // request. The stream turn needs the SSE server; the gen turn needs the JSON server, so the gen
        // turn re-aims apiUrl with AI.withConfig while keeping the same session (and its seated anchor).
        TestCompletionServer.run { jsonServer =>
            TestCompletionServer.runStreaming { sseServer =>
                val config = wideServerConfig(sseServer.baseUrl).tokenizer(fixedTok)
                val prior  = Context(Chunk(UserMessage("q1", Absent), UserMessage("q2", Absent)))
                // the stream turn reports usage 50000/12 at stream end, seating the anchor
                sseServer.enqueueStream(Chunk(
                    argDelta("{\"resultValue\":\"streamed\"}"),
                    openAiUsageChunk(50000, 12)
                )).andThen {
                    // the gen turn returns a result_tool call, no usage, so the anchor stays at the streamed total
                    val genArgs = Json.encode("""{"resultValue":"gen"}""")
                    val genBody =
                        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"g1","type":"function","function":{"name":"result_tool","arguments":$genArgs}}]}}]}"""
                    jsonServer.enqueueBody(genBody).andThen {
                        LLM.run(config) {
                            AI.init.map { ai =>
                                ai.setContext(prior).andThen(ai.stream[String].map(_.run)).andThen {
                                    LLM.session(ai).map { session =>
                                        session.streamAnchor match
                                            case Absent =>
                                                Kyo.lift(assert(false, "the streamed turn must seat a StreamAnchor"))
                                            case Present(anchor) =>
                                                val sentSize = anchor.sentView.size
                                                assert(
                                                    sentSize == prior.compacted.size,
                                                    s"the anchor captures the non-enriched streamed sent view (${prior.compacted.size}), got $sentSize"
                                                )
                                                // the gen turn re-aims at the JSON server; applyStreamMeasure consumes the
                                                // seated anchor at the turn start, before the gen completion
                                                AI.withConfig(_.apiUrl(jsonServer.baseUrl)) {
                                                    ai.gen[String]
                                                }.map { genResult =>
                                                    ai.context.map { ctx =>
                                                        val st = ctx.compactionState
                                                        assert(genResult == "gen", s"the gen turn returns its own result, got '$genResult'")
                                                        assert(
                                                            st.lastUsage == Present(50000),
                                                            s"the gen turn re-anchors on the streamed turn's reported total (50000), got ${st.lastUsage}"
                                                        )
                                                        assert(
                                                            st.lastUsageRawSize == sentSize,
                                                            s"the applied anchor sizes at the streamed sent-view size ($sentSize), got ${st.lastUsageRawSize}"
                                                        )
                                                        // the streamed turn's recorded result_tool call survives into the
                                                        // conversation the gen turn read and extended
                                                        val calls = ctx.raw.collect { case AssistantMessage(_, cs, _, _) => cs }.flatten
                                                        assert(
                                                            calls.exists(_.arguments.contains("streamed")),
                                                            s"the streamed turn's recorded call stays in the conversation: ${ctx.raw}"
                                                        )
                                                        assert(
                                                            Compactor.internal.occupancy(ctx) >= 50000,
                                                            s"occupancy anchors at or above the streamed total, got ${Compactor.internal.occupancy(ctx)}"
                                                        )
                                                    }
                                                }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a streamed turn joins the conversation" - {

        "a fully consumed stream records the result_tool call it made, so a later turn can read it" in {
            val text = "streamed answer"
            val full = prefixArgs(text)
            TestCompletionServer.runStreaming { server =>
                val config = serverConfig(server.baseUrl)
                val frags  = Chunk(full.substring(0, 18), full.substring(18))
                server.enqueueStream(frags.map(argDelta)).andThen {
                    LLM.run(config)(AI.initWith { ai =>
                        ai.stream[String].map(_.run.map { chunks =>
                            ai.context.map { ctx =>
                                assert(chunks.mkString == text, s"the stream must deliver its value: $chunks")
                                // The streamed fragments were the result_tool call's arguments, so the turn
                                // joins the conversation as that call closed by a synthetic result, exactly
                                // as a generated turn does, never as plain assistant prose.
                                val calls = ctx.raw.collect { case AssistantMessage(_, cs, _, _) => cs }.flatten
                                assert(calls.size == 1, s"the streamed turn records one result_tool call: ${ctx.raw}")
                                assert(calls.head.function == "result_tool", s"recorded as a result_tool call: ${calls.head}")
                                assert(
                                    calls.head.arguments.contains(text),
                                    s"the call carries the streamed value so a later turn can read it: ${calls.head.arguments}"
                                )
                                val resultIds = ctx.raw.collect { case ToolMessage(id, _, _, _) => id }
                                assert(resultIds == Chunk(calls.head.id), s"a matching synthetic result closes the call: ${ctx.raw}")
                            }
                        })
                    })
                }
            }
        }

        "an abandoned stream records nothing" in {
            val args = elementArgs(List(Answer("one"), Answer("two")))
            TestCompletionServer.runStreaming { server =>
                val config = serverConfig(server.baseUrl)
                // Split so taking one element stops the source part way: the fold never reaches its end,
                // which is what abandonment means here.
                val frags = Chunk(args.substring(0, 20), args.substring(20))
                server.enqueueStream(frags.map(argDelta)).andThen {
                    LLM.run(config)(AI.initWith { ai =>
                        ai.stream[Answer].map(_.take(1).run.map { taken =>
                            ai.context.map { ctx =>
                                assert(taken.size <= 1, s"only the taken element is delivered: $taken")
                                assert(
                                    ctx.raw.isEmpty,
                                    s"an abandoned stream must leave the conversation untouched: ${ctx.raw}"
                                )
                            }
                        })
                    })
                }
            }
        }

        "a stream that never yields a decodable value records nothing" in {
            TestCompletionServer.runStreaming { server =>
                val config = serverConfig(server.baseUrl)
                // Argument bytes carrying no resultValue at all, so nothing ever decodes. A merely
                // truncated value would not do: the partial-JSON completion closes it and it decodes.
                server.enqueueStream(Chunk(argDelta("""{"somethingElse":"x\""""))).andThen {
                    LLM.run(config)(AI.initWith { ai =>
                        Abort.run[AIStreamException](ai.stream[String].map(_.run)).map { result =>
                            ai.context.map { ctx =>
                                assert(result.isFailure, s"a stream that never decodes fails: $result")
                                assert(
                                    ctx.raw.isEmpty,
                                    s"a failed stream records no half turn: ${ctx.raw}"
                                )
                            }
                        }
                    })
                }
            }
        }
    }

    "the stream anchor captures the non-enriched sent view, so a later turn's occupancy counts the appended suffix and is not deferred by the prompt/reminder count" in {
        TestCompletionServer.runStreaming { server =>
            val config = wideServerConfig(server.baseUrl).tokenizer(fixedTok)
            // Three live messages sent on the first turn; the prompt/reminder enrichment prepends one system
            // prompt and appends one reminder, so the enriched request is five messages, strictly larger than
            // the non-enriched view the anchor must record.
            val prior = Context(Chunk(
                UserMessage("q1", Absent),
                UserMessage("q2", Absent),
                UserMessage("q3", Absent)
            ))
            val prompt = Prompt.init("system instruction", "reminder text")
            // Four live messages appended after the first turn seats its anchor: the second turn drops exactly
            // the recorded sent-view size, so a correct anchor leaves all four in the suffix.
            val appended = Chunk(
                UserMessage("a1", Absent),
                UserMessage("a2", Absent),
                UserMessage("a3", Absent),
                UserMessage("a4", Absent)
            )
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":\"ok\"}"),
                openAiUsageChunk(50000, 12)
            )).andThen(server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"next\"}")))).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        AI.enable(prompt) {
                            ai.setContext(prior).andThen(ai.stream[String].map(_.run)).andThen {
                                LLM.session(ai).map { session =>
                                    session.streamAnchor match
                                        case Present(anchor) =>
                                            assert(
                                                anchor.sentView.size == prior.compacted.size,
                                                s"the anchor captures the non-enriched sent view (${prior.compacted.size}), not the prompt/reminder-enriched request; got ${anchor.sentView.size}"
                                            )
                                            ai.context.map(c => ai.setContext(appended.foldLeft(c)(_.add(_)))).andThen {
                                                ai.stream[String].map(_.run).andThen {
                                                    ai.context.map { ctx =>
                                                        assert(
                                                            ctx.compactionState.lastUsageRawSize == prior.compacted.size,
                                                            s"the applied anchor sizes at the non-enriched sent view (${prior.compacted.size}), got ${ctx.compactionState.lastUsageRawSize}"
                                                        )
                                                        // occupancy drops lastUsageRawSize from compacted: the non-enriched anchor
                                                        // leaves every appended message in the suffix. An enriched anchor would size
                                                        // at prior.size + 2 (prompt + reminder), dropping two real appended messages.
                                                        // Each streamed turn also records a synthetic result pair (assistant
                                                        // result-tool call + tool result) into the conversation, so the suffix holds
                                                        // the four appended messages plus the two recorded streamed-turn pairs; the
                                                        // B-1 contract is that no appended message is deferred by the enrichment count.
                                                        val suffix = ctx.compacted.drop(ctx.compactionState.lastUsageRawSize)
                                                        // Compared on content: the live suffix messages are stamped by the turn's
                                                        // apportionment, so the stored copies differ from the freshly built appended
                                                        // values only by their token stamp.
                                                        assert(
                                                            appended.forall(a => suffix.exists(_.content == a.content)),
                                                            s"the non-enriched anchor keeps every appended message in the suffix, none deferred by the enrichment count; suffix: $suffix"
                                                        )
                                                        assert(
                                                            suffix.size == appended.size + 4,
                                                            s"the suffix holds the ${appended.size} appended messages plus the two recorded streamed-turn pairs; got ${suffix.size}"
                                                        )
                                                        assert(
                                                            ctx.compactionState.lastUsage == Present(50000),
                                                            s"the anchor scalar is turn one's provider total, unchanged by turn two which reported no usage; got ${ctx.compactionState.lastUsage}"
                                                        )
                                                        assert(
                                                            Compactor.internal.occupancy(ctx) == 50106,
                                                            // 50000 anchored + the eight suffix messages: the turn-one recorded pair and
                                                            // the four appended messages are stamped at 14 each (six times 14 = 84), and
                                                            // the still-unstamped turn-two recorded pair falls to the offline estimate
                                                            // (12 + 10), so 84 + 22 = 106.
                                                            s"occupancy is the anchored 50000 plus the suffix estimate (50106); got ${Compactor.internal.occupancy(ctx)}"
                                                        )
                                                    }
                                                }
                                            }
                                        case Absent =>
                                            Kyo.lift(assert(false, "a streaming turn must seat a StreamAnchor"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "an Anthropic streaming turn re-anchors from the message_start input_tokens" in {
        TestCompletionServer.runStreaming { server =>
            val config = wideAnthropicConfig(server.baseUrl).tokenizer(fixedTok)
            server.enqueueStream(Chunk(
                anthropicMessageStart(42000),
                anthropicArgDelta("{\"resultValue\":\"ok\"}")
            )).andThen(server.enqueueStream(Chunk(anthropicArgDelta("{\"resultValue\":\"next\"}")))).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        ai.stream[String].map(_.run).andThen {
                            LLM.session(ai).map { session =>
                                session.streamAnchor match
                                    case Present(anchor) =>
                                        anchor.usageSink.get.map { u =>
                                            assert(
                                                u == Present(Completion.Usage(42000, 0, Absent)),
                                                s"the seated sink holds the message_start usage, got: $u"
                                            )
                                            ai.stream[String].map(_.run).andThen {
                                                ai.context.map { ctx =>
                                                    assert(
                                                        ctx.compactionState.lastUsage == Present(42000),
                                                        s"the next turn re-anchors on the Anthropic 42000, got: ${ctx.compactionState.lastUsage}"
                                                    )
                                                    assert(
                                                        Compactor.internal.occupancy(ctx) >= 42000,
                                                        s"occupancy anchors on the Anthropic reported total, got ${Compactor.internal.occupancy(ctx)}"
                                                    )
                                                }
                                            }
                                        }
                                    case Absent =>
                                        Kyo.lift(assert(false, "an Anthropic streaming turn must seat a StreamAnchor"))
                            }
                        }
                    }
                }
            }
        }
    }

    "a streaming turn reporting NO usage leaves the anchor untouched (the no-usage / harness MUST-DEGRADE path)" in {
        TestCompletionServer.runStreaming { server =>
            val config = wideServerConfig(server.baseUrl).tokenizer(fixedTok)
            val prior = Context(Chunk(SystemMessage("s"), UserMessage("hi", Absent)))
                .withCompaction(CompactionState(lastUsage = Present(30000), lastUsageRawSize = 2))
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"ok\"}"))).andThen(
                server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"next\"}")))
            ).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        ai.setContext(prior).andThen(ai.stream[String].map(_.run)).andThen {
                            LLM.session(ai).map { session =>
                                session.streamAnchor match
                                    case Present(anchor) =>
                                        anchor.usageSink.get.map { u =>
                                            assert(u.isEmpty, s"a no-usage stream leaves the sink Absent, got: $u")
                                            ai.stream[String].map(_.run).andThen {
                                                ai.context.map { ctx =>
                                                    assert(
                                                        ctx.compactionState.lastUsage == Present(30000),
                                                        s"a no-usage stream must not re-anchor: the prior 30000 is unchanged, got: ${ctx.compactionState.lastUsage}"
                                                    )
                                                }
                                            }
                                        }
                                    case Absent =>
                                        Kyo.lift(assert(false, "a streaming turn must seat a StreamAnchor even when it reports no usage"))
                            }
                        }
                    }
                }
            }
        }
    }

    "the streaming re-anchor apportions the sent view: live twins and the synthetic marker stamped, nothing escapes its share (integration)" in {
        TestCompletionServer.runStreaming { server =>
            val config = wideServerConfig(server.baseUrl).tokenizer(fixedTok)
            val marker = SystemMessage("[summary of earlier region]", Absent, Present(Origin(0, 2, 0)))
            val live   = UserMessage("current question", Absent)
            val seeded = Context(Chunk(live), Chunk(marker, live))
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":\"ok\"}"),
                openAiUsageChunk(1000, 8)
            )).andThen(server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"next\"}")))).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        ai.setContext(seeded).andThen(ai.stream[String].map(_.run)).andThen {
                            ai.stream[String].map(_.run).andThen {
                                ai.context.map { ctx =>
                                    assert(
                                        ctx.compactionState.lastUsage == Present(1000),
                                        s"the re-anchor seats the reported total 1000, got: ${ctx.compactionState.lastUsage}"
                                    )
                                    val stampedMarker = ctx.compacted.find(_.origin == Present(Origin(0, 2, 0)))
                                    assert(
                                        stampedMarker.exists(_.tokens.isDefined),
                                        s"the synthetic marker carries its apportioned stamp, not Absent, got: ${stampedMarker.map(_.tokens)}"
                                    )
                                    assert(
                                        ctx.raw.exists(m => m.origin.isEmpty && m.tokens.isDefined),
                                        s"a live raw twin the reported total covers carries an apportioned stamp, got: ${ctx.raw.map(_.tokens)}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "the streaming re-anchor + stamp path is model-free (zero extra completions, no fiber)" in {
        TestCompletionServer.runStreaming { server =>
            val config = wideServerConfig(server.baseUrl)
            server.enqueueStream(Chunk(
                argDelta("{\"resultValue\":\"ok\"}"),
                openAiUsageChunk(1000, 8)
            )).andThen(server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"next\"}")))).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        ai.stream[String].map(_.run).andThen(ai.stream[String].map(_.run))
                    }
                }.andThen {
                    server.captured.map { caps =>
                        assert(
                            caps.size == 2,
                            s"two stream turns issue exactly two completions; the re-anchor / apportion / suffix-stamp step adds none, got ${caps.size}"
                        )
                    }
                }
            }
        }
    }

    "a keepalive between fragments does not fail the stream" in {
        // A stream may hold the connection open with a payload-free event. It carries no chunk, and
        // reading it as a malformed one fails a generation that was proceeding normally.
        val text = "keepalive tolerated"
        val full = prefixArgs(text)
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            val frags  = Chunk(argDelta(full.substring(0, 20)), "", argDelta(full.substring(20)))
            server.enqueueStream(frags).andThen {
                LLM.run(config)(AI.stream[String].map(_.run.map { collected =>
                    assert(collected.mkString == text, s"the keepalive must be skipped, not decoded: $collected")
                }))
            }
        }
    }

end LLMStreamTest
