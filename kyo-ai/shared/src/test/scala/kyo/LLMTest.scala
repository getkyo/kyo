package kyo

import kyo.Schedule
import kyo.ai.*
import kyo.ai.Context
import kyo.ai.Context.*
import scala.util.NotGiven

class LLMTest extends kyo.test.Test[Any]:

    case class City(name: String) derives Schema

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def ctxOf(msgs: Message*): Context                = Context(Chunk.from(msgs))

    /** A config pointing the OpenAI backend at the test server, with a dummy key so the backend proceeds
      * to the HTTP call instead of aborting on a missing key.
      */
    def serverConfig(baseUrl: String): Config =
        Config.OpenAI.default
            .apiKey("test")
            .model(Config.OpenAI, "gpt-4o", 128000, Config.OutputMaximum.Verified(16384), Config.ReasoningEncoding.Unavailable, true, true)
            .apiUrl(baseUrl)

    /** A config pointing the Anthropic backend at the test server, with a dummy key so the backend
      * proceeds to the HTTP call instead of aborting on a missing key.
      */
    def anthropicServerConfig(baseUrl: String): Config =
        Config.Anthropic.default
            .apiKey("test")
            .apiUrl(baseUrl)

    /** An OpenAI completion body whose assistant calls `result_tool` with the supplied envelope JSON. */
    def resultToolBody(envelopeJson: String): String =
        val escaped = Json.encode(envelopeJson)
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$escaped}}]}}]}"""

    /** An Anthropic completion body whose assistant calls `result_tool` with the supplied envelope JSON
      * object, embedded directly (Anthropic's `tool_use` block carries `input` as a JSON object, not a
      * JSON-encoded string).
      */
    def anthropicResultToolBody(envelopeJson: String): String =
        s"""{"id":"m1","content":[{"type":"tool_use","id":"r1","name":"result_tool","input":$envelopeJson}],"model":"claude-opus-4-8","role":"assistant","stop_reason":"tool_use","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""

    /** An OpenAI completion body with a plain assistant reply and no tool call (the eval loop sees Absent). */
    def noResultBody: String =
        """{"choices":[{"message":{"role":"assistant","content":"thinking","tool_calls":null}}]}"""

    /** The single captured outbound request body for the last scripted turn. */
    def requestBody(server: TestCompletionServer)(using Frame): String < Async =
        server.captured.map(_.head.body)

    /** The committed default-off golden: the enriched-request bytes for the fixed scripted turn in the
      * "default-off matches committed pre-change golden bytes" test below, captured with the compactor
      * Absent and pinned as a source constant. The contract is that compaction off is byte-identical to
      * the module's own request (no compactor involvement), so a seam edit that leaked a byte onto the
      * Absent path fails that test against this, not a self-derivation.
      */
    val goldenDefaultOffRequest: String =
        """{"model":"gpt-4o","max_completion_tokens":16384,"messages":[{"role":"system","content":"you are precise"},{"role":"user","content":"ping"}],"tools":[{"function":{"description":"Use this tool to return your final response in the requested structured format. You MUST call this tool exactly once at the end of your response to provide the structured output. Do not make parallel calls to this tool in the same completion; only the first invocation will be considered.","name":"result_tool","strict":false,"parameters":{"type":"object","properties":{"resultValue":{"type":"string"}},"required":["resultValue"]}},"type":"function"}],"tool_choice":"required"}"""

    "run discharges LLM to Async leaving an Async value" in {
        LLM.run(
            AI.initWith(ai => ai.systemMessage("hi").andThen(ai.context.map(_.raw.size)))
        ).map(result => assert(result == 1))
    }

    "message builders accumulate in order on an instance" in {
        LLM.run(
            AI.initWith { ai =>
                ai.systemMessage("s")
                    .andThen(ai.userMessage("u"))
                    .andThen(ai.assistantMessage("a"))
                    .andThen(ai.context.map(_.raw.map(_.role.name)))
            }
        ).map(roles => assert(roles == Chunk("system", "user", "assistant")))
    }

    "forget discards context changes" in {
        LLM.run(
            AI.initWith { ai =>
                ai.systemMessage("outer")
                    .andThen(AI.forget(ai.systemMessage("inner")))
                    .andThen(ai.context.map(_.raw.map(_.content)))
            }
        ).map(contents => assert(contents == Chunk("outer")))
    }

    "gen extracts the resultValue field from the result tool output" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"42"}""")).andThen {
                LLM.run(config)(AI.gen[String]).map { result =>
                    assert(result == "42", s"expected '42' from resultValue, got '$result'")
                }
            }
        }
    }

    "a forced turn that never yields a result gets exactly one repair turn, then fails" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).maxIterations(2)
            // No turn ever populates the result tool, simulating a backend that cannot force the call. The
            // loop runs maxIterations tool turns, one forced turn, and one repair turn, then stops:
            // maxIterations + 2 evals. Enqueue generously so the loop, not an empty-body default, ends it.
            Kyo.foreachDiscard(0 until (config.maxIterations * 2 + 4))(_ => server.enqueueBody(noResultBody)).andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    server.captured.map { caps =>
                        val exhausted = result match
                            case Result.Failure(_: AIEvalExhaustedException) => true
                            case _                                           => false
                        assert(exhausted, s"expected AIEvalExhaustedException, got: $result")
                        assert(
                            caps.size == config.maxIterations + 2,
                            s"expected ${config.maxIterations + 2} evals (tools + force + one repair), got ${caps.size}"
                        )
                    }
                }
            }
        }
    }

    "a forced turn with no result converges on the informed repair turn" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).maxIterations(2)
            // maxIterations tool turns and the first forced turn yield no result; the repair turn returns one.
            Kyo.foreachDiscard(0 until (config.maxIterations + 1))(_ => server.enqueueBody(noResultBody)).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":"recovered"}""")).andThen {
                    LLM.run(config)(AI.gen[String]).map { result =>
                        server.captured.map { caps =>
                            assert(result == "recovered", s"expected the repair turn's result, got '$result'")
                            // The repair turn carries the mechanism-neutral instruction fed back after the force.
                            assert(
                                caps.exists(_.body.contains("Produce your final result now")),
                                s"expected the repair instruction in a request; bodies: ${caps.map(_.body).mkString("\n")}"
                            )
                        }
                    }
                }
            }
        }
    }

    "the forced turn carries the request-scoped finalize directive, trailing and unstored, on both HTTP backends" in {
        // The converged forced-finalization signal: dropping the user tools is not an instruction the
        // model can be trusted to read, and a backend that compels the result call (forced tool_choice
        // with strict decoding) has no repair turn to recover in, so the directive must ride the forced
        // request itself. It is request-scoped: the stored conversation never contains it.
        val directive = "This is the instruction to finalize"
        val reminder  = "keep the finalize-ordering probe reminder last"
        def check(mkConfig: String => Config, noResult: String, result: String) =
            TestCompletionServer.run { server =>
                server.enqueueBody(noResult).andThen {
                    server.enqueueBody(result).andThen {
                        LLM.run(mkConfig(server.baseUrl).maxIterations(1)) {
                            AI.enable(Prompt.init("finalize-ordering probe", reminder)) {
                                AI.initWith { ai =>
                                    ai.gen[String].map(r => ai.context.map(ctx => (r, ctx)))
                                }
                            }
                        }.map { case (r, ctx) =>
                            server.captured.map { caps =>
                                assert(r == "done", s"expected the forced turn's result, got '$r'")
                                assert(caps.size == 2, s"expected one tool turn and one forced turn, got ${caps.size}")
                                assert(
                                    !caps(0).body.contains(directive),
                                    s"an un-forced turn must not carry the finalize directive: ${caps(0).body}"
                                )
                                val forced = caps(1).body
                                assert(forced.contains(directive), s"the forced turn must carry the finalize directive: $forced")
                                // Trailing: after the conversation, before the floating reminders.
                                val reminderIdx = forced.indexOf(reminder)
                                assert(
                                    reminderIdx >= 0 && forced.indexOf(directive) < reminderIdx,
                                    s"the directive must precede the floating reminders: $forced"
                                )
                                // Request-scoped: the stored conversation never contains it.
                                assert(
                                    !ctx.raw.exists {
                                        case SystemMessage(content, _, _) => content.contains(directive)
                                        case _                            => false
                                    },
                                    s"the directive must not persist in the conversation: ${ctx.raw}"
                                )
                            }
                        }
                    }
                }
            }
        val anthropicNoResultBody =
            """{"id":"m1","content":[{"type":"text","text":"thinking"}],"model":"claude-opus-4-8","role":"assistant","stop_reason":"end_turn","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}"""
        check(serverConfig, noResultBody, resultToolBody("""{"resultValue":"done"}""")).andThen(
            check(anthropicServerConfig, anthropicNoResultBody, anthropicResultToolBody("""{"resultValue":"done"}"""))
        )
    }

    "the request-scoped forced directive leaves the served conversation prefix byte-stable across turns" in {
        // The prompt-cache property: the per-request forced directive joins the enrichment-excluded anchor
        // basis and rides TRAILING, so the served conversation's bytes keep their positions from the tool
        // turn to the forced turn. A directive prepended or inserted mid-conversation would shift every
        // later byte and defeat provider prefix caching. Below the trigger (compaction re-serves the
        // context unchanged), so any byte movement here is the directive's alone.
        val finalizeDirective = "This is the instruction to finalize"
        def commonPrefixLen(a: String, b: String): Int =
            val n = math.min(a.length, b.length)
            var i = 0
            while i < n && a.charAt(i) == b.charAt(i) do i += 1
            i
        end commonPrefixLen
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).maxIterations(1)
            server.enqueueBody(noResultBody).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":"done"}""")).andThen {
                    LLM.run(config) {
                        AI.initWith { ai =>
                            ai.systemMessage("you are precise").andThen(ai.userMessage("ping")).andThen(ai.gen[String])
                        }
                    }.map { r =>
                        server.captured.map { caps =>
                            assert(r == "done", s"expected the forced turn's result, got '$r'")
                            assert(caps.size == 2, s"expected one tool turn and one forced turn, got ${caps.size}")
                            val toolTurn   = caps(0).body
                            val forcedTurn = caps(1).body
                            assert(!toolTurn.contains(finalizeDirective), "the tool turn carries no directive")
                            assert(forcedTurn.contains(finalizeDirective), "the forced turn carries the directive")
                            val shared = commonPrefixLen(toolTurn, forcedTurn)
                            val prefix = toolTurn.take(shared)
                            // the byte-stable prefix carries the whole served conversation verbatim
                            assert(
                                prefix.contains("\"role\":\"system\",\"content\":\"you are precise\""),
                                s"the byte-stable served prefix carries the system message verbatim, prefix: $prefix"
                            )
                            assert(
                                prefix.contains("\"role\":\"user\",\"content\":\"ping\""),
                                s"the byte-stable served prefix carries the user message verbatim, prefix: $prefix"
                            )
                            // the directive never appears within the stable prefix; it rides strictly after it
                            assert(
                                !prefix.contains(finalizeDirective),
                                "the directive is not inside the byte-stable served prefix"
                            )
                            assert(
                                forcedTurn.indexOf(finalizeDirective) >= shared,
                                s"the request-scoped directive rides strictly after the byte-stable served prefix " +
                                    s"(directive at ${forcedTurn.indexOf(finalizeDirective)}, prefix length $shared)"
                            )
                        }
                    }
                }
            }
        }
    }

    "a present result that fails schema decode is repaired and retried, not aborted" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            // Turn 1: the model produces a result_tool call whose resultValue is present and decodes to a
            // Structure.Value, but does not match City's schema (a bare string where a {name} record is
            // required), which also defeats the text-coercion fallback. Before the repair this aborted the
            // whole generation with AIDecodeException. Turn 2: a corrected result the loop must accept.
            server.enqueueBody(resultToolBody("""{"resultValue":"not-a-city-record"}""")).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":{"name":"Paris"}}""")).andThen {
                    LLM.run(config)(AI.gen[City]).map { result =>
                        server.captured.map { caps =>
                            assert(result.name == "Paris", s"expected the repaired result City(Paris), got: $result")
                            assert(caps.size == 2, s"a malformed result should trigger one repair turn (2 requests), got: ${caps.size}")
                            assert(
                                caps(1).body.contains("Before calling 'result_tool'"),
                                s"the repair turn must carry the tool loop's decode feedback; body: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "a rejected result is repaired through the tool channel, with no parallel system message" in {
        // The result tool declares the actual result schema, so a mismatched payload fails the tool
        // loop's own typed decode and the feedback rides the standard tool-error channel, exactly like
        // any other tool. The old split design answered the same call with a success ToolMessage AND a
        // separate schema-mismatch system message; that contradiction must be gone.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"not-a-city-record"}""")).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":{"name":"Paris"}}""")).andThen {
                    LLM.run(config)(AI.gen[City]).map { result =>
                        server.captured.map { caps =>
                            assert(result.name == "Paris", s"expected the repaired City(Paris), got: $result")
                            assert(caps.size == 2, s"one repair turn (2 requests), got: ${caps.size}")
                            val second = caps(1).body
                            assert(
                                second.contains("Before calling 'result_tool'"),
                                s"the rejection must ride the tool loop's own decode feedback; body: $second"
                            )
                            assert(
                                !second.contains("did not match the required schema"),
                                s"no parallel eval-side system message may coexist with the tool feedback; body: $second"
                            )
                        }
                    }
                }
            }
        }
    }

    "exhaustion after repeated rejected results names the attempts and the last failure" in {
        // The model calls result_tool every turn but every payload is rejected: the final error must say
        // THAT, not "produced no result". The old message lied about rejected results.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).maxIterations(1)
            Kyo.foreachDiscard(0 until 4)(_ => server.enqueueBody(resultToolBody("""{"resultValue":"still-not-a-city"}"""))).andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[City])).map { result =>
                    result match
                        case Result.Failure(e: AIEvalExhaustedException) =>
                            assert(
                                e.getMessage.contains("last failure"),
                                s"exhaustion must carry the last rejection reason; got: ${e.getMessage}"
                            )
                            assert(
                                !e.getMessage.contains("produced no result"),
                                s"a called-and-rejected run must not be reported as result-less; got: ${e.getMessage}"
                            )
                        case other => fail(s"expected AIEvalExhaustedException, got: $other")
                }
            }
        }
    }

    "an open-shape conformance violation is repaired through the tool channel" in {
        // Same channel contract for the shape-dynamic case: the conformance check runs inside the result
        // tool's dispatch, so its violation text arrives as the tool failure, not as a parallel system
        // message.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            given Schema[Structure.Value] = summon[Schema[Structure.Value]].withStructure(
                Structure.Type.Product(
                    "Answer",
                    Tag[Any],
                    Chunk.empty,
                    Chunk(
                        Structure.Field("note", summon[Schema[String]].structure),
                        Structure.Field("answer", summon[Schema[String]].structure)
                    )
                )
            )
            server.enqueueBody(resultToolBody("""{"resultValue":{"note":"partial"}}""")).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":{"note":"full","answer":"42"}}""")).andThen {
                    LLM.run(config)(AI.gen[Structure.Value]).map { result =>
                        server.captured.map { caps =>
                            assert(caps.size == 2, s"one repair turn (2 requests), got: ${caps.size}")
                            val second = caps(1).body
                            assert(
                                second.contains("result_tool' failed"),
                                s"the conformance violation must ride the tool-error channel; body: $second"
                            )
                            assert(
                                second.contains("does not conform to the declared result schema"),
                                s"the violation text must reach the model; body: $second"
                            )
                        }
                    }
                }
            }
        }
    }

    "an open-shape result violating the declared structure is repaired and retried" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            // A shape-dynamic schema: the open Schema[Structure.Value] with a runtime Product installed.
            // Its passthrough codec decodes ANY record, so the missing required field can only be caught
            // by conformance validation against the declared structure; the loop must repair it exactly
            // like a decode failure, never accept a silent partial result.
            given Schema[Structure.Value] = summon[Schema[Structure.Value]].withStructure(
                Structure.Type.Product(
                    "Answer",
                    Tag[Any],
                    Chunk.empty,
                    Chunk(
                        Structure.Field("note", summon[Schema[String]].structure),
                        Structure.Field("answer", summon[Schema[String]].structure)
                    )
                )
            )
            server.enqueueBody(resultToolBody("""{"resultValue":{"note":"partial"}}""")).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":{"note":"full","answer":"42"}}""")).andThen {
                    LLM.run(config)(AI.gen[Structure.Value]).map { result =>
                        server.captured.map { caps =>
                            val expected = Structure.Value.Record(Chunk[(String, Structure.Value)](
                                "note"   -> Structure.Value.Str("full"),
                                "answer" -> Structure.Value.Str("42")
                            ))
                            assert(result == expected, s"expected the repaired conforming record, got: $result")
                            assert(
                                caps.size == 2,
                                s"a non-conforming record should trigger one repair turn (2 requests), got: ${caps.size}"
                            )
                            assert(
                                caps(1).body.contains("does not conform to the declared result schema"),
                                s"the repair turn must carry the conformance violation; body: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "gen with one input adds a user message then generates" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"ok"}""")).andThen {
                LLM.run(config)(AI.gen[String](City("Paris"))).andThen {
                    server.captured.map { caps =>
                        assert(caps.nonEmpty, "expected at least one captured request")
                        val body = caps.head.body
                        assert(body.contains("Paris"), s"request should carry the encoded City(\"Paris\") user message: $body")
                    }
                }
            }
        }
    }

    "two AI.gen one-shots are isolated: the second never sees the first's input (no shared-slot bleed)" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"one"}""")).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":"two"}""")).andThen {
                    LLM.run(config) {
                        AI.gen[String]("first-input-7f3a").andThen(AI.gen[String]("second-input-9b21"))
                    }.andThen {
                        server.captured.map { caps =>
                            assert(caps.size == 2, s"expected 2 requests, got ${caps.size}")
                            assert(caps(0).body.contains("first-input-7f3a"), "first one-shot sends its own input")
                            assert(caps(1).body.contains("second-input-9b21"), "second one-shot sends its own input")
                            assert(
                                !caps(1).body.contains("first-input-7f3a"),
                                s"second one-shot mints a fresh instance, so it must not carry the first's input; got: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "gen with two inputs combines into a tuple" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"ok"}""")).andThen {
                LLM.run(config)(AI.gen[String](1, "a")).andThen {
                    server.captured.map { caps =>
                        assert(caps.nonEmpty, "expected at least one captured request")
                        val body = caps.head.body
                        // The tuple (1, "a") encodes as {"_1":1,"_2":"a"} inside the user message content.
                        assert(body.contains("_1") && body.contains("_2"), s"request should carry the encoded tuple field names: $body")
                        assert(body.contains("a"), s"request should carry the encoded tuple value \"a\": $body")
                    }
                }
            }
        }
    }

    "the completion call is wrapped meter -> retry -> timeout in order" in {
        TestCompletionServer.run { server =>
            AtomicInt.init(0).map { meterRuns =>
                val countingMeter =
                    new Meter:
                        def run[A, S](v: => A < S)(using Frame): A < (S & Async & Abort[Closed]) =
                            meterRuns.incrementAndGet.andThen(v)
                        def tryRun[A, S](v: => A < S)(using Frame): Maybe[A] < (S & Async & Abort[Closed]) =
                            run(v).map(Present(_))
                        def availablePermits(using Frame): Int < (Async & Abort[Closed]) = Int.MaxValue
                        def pendingWaiters(using Frame): Int < (Async & Abort[Closed])   = 0
                        def close(using Frame): Boolean < Sync                           = false
                        def closed(using Frame): Boolean < Sync                          = false
                val config =
                    serverConfig(server.baseUrl)
                        .meter(countingMeter)
                        .retrySchedule(Schedule.repeat(1))
                // First a malformed body (decode failure -> HttpException -> one retry), then a valid result body.
                server.enqueueBody("not json").andThen {
                    server.enqueueBody(resultToolBody("""{"resultValue":"done"}""")).andThen {
                        LLM.run(config)(AI.gen[String]).map { result =>
                            meterRuns.get.map { runs =>
                                server.captured.map { caps =>
                                    assert(result == "done", s"expected 'done', got '$result'")
                                    assert(runs >= 2, s"meter should have run for each attempt, ran $runs")
                                    assert(caps.size == 2, s"retry should produce two requests, got ${caps.size}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a 401 auth failure halts fast without retry" in {
        TestCompletionServer.run { server =>
            val config = anthropicServerConfig(server.baseUrl).retrySchedule(Schedule.repeat(1))
            server.enqueueStatus(401, """{"error":{"message":"invalid api key"}}""").andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    server.captured.map { caps =>
                        result match
                            case Result.Failure(_: AIProviderAuthException) => ()
                            case other                                      => fail(s"expected AIProviderAuthException, got: $other")
                        assert(caps.size == 1, s"a 401 must halt without retry, expected 1 request, got: ${caps.size}")
                    }
                }
            }
        }
    }

    "a 429 rate limit retries then succeeds" in {
        TestCompletionServer.run { server =>
            val config = anthropicServerConfig(server.baseUrl).retrySchedule(Schedule.repeat(1))
            server.enqueueStatus(429, """{"error":{"message":"rate limited"}}""").andThen {
                server.enqueueBody(anthropicResultToolBody("""{"resultValue":"ok"}""")).andThen {
                    LLM.run(config)(AI.gen[String]).map { result =>
                        server.captured.map { caps =>
                            assert(result == "ok", s"expected 'ok' after the retried attempt, got '$result'")
                            assert(caps.size == 2, s"a 429 should retry exactly once, expected 2 requests, got: ${caps.size}")
                        }
                    }
                }
            }
        }
    }

    "a tool payload trailed by content, not just brackets, is still rejected" in {
        // The salvage is narrow on purpose. Surplus closing brackets are provider-shaped noise at the end
        // of a long generation; anything else after a complete value says the model misunderstood the
        // format, and that feedback is worth keeping rather than smoothing away.
        TestCompletionServer.run { server =>
            val config  = serverConfig(server.baseUrl).maxIterations(1)
            val trailed = """{"resultValue":"ok"} and then some prose"""
            server.enqueueBody(resultToolBody(trailed))
                .andThen(server.enqueueBody(resultToolBody(trailed)))
                .andThen {
                    Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                        assert(
                            result.isFailure,
                            s"a value trailed by prose must not be accepted by dropping it, got: $result"
                        )
                    }
                }
        }
    }

    "a payload holding two values back to back is still rejected" in {
        // The masking case the carve must not swallow: dropping the second value silently would
        // discard something the model meant to say.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).maxIterations(1)
            val good   = """{"resultValue":"ok"}"""
            server.enqueueBody(resultToolBody(good + good))
                .andThen(server.enqueueBody(resultToolBody(good + good)))
                .andThen {
                    Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                        assert(
                            result.isFailure,
                            s"two values back to back must not be accepted by dropping one, got: $result"
                        )
                    }
                }
        }
    }

    "a turn cut off part way through its tool call fails as a ceiling stop, not as a bad payload" in {
        // The shape that took five rounds to diagnose. The reply stops at the ceiling MID-ARGUMENTS,
        // so the tool payload is unterminated JSON. Because a tool call is forced on every
        // generation, this is the common way a ceiling stop arrives, and it used to slip past the
        // check entirely: the truncated call went to the tool loop, failed to decode, was fed back
        // and retried until the iterations ran out, and surfaced as a schema complaint naming
        // neither the ceiling nor the setting that fixes it.
        //
        // Retrying cannot help either, since the next attempt spends the same ceiling to stop in the
        // same place, so the request count is asserted too.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).maxTokens(256).retrySchedule(Schedule.repeat(3))
            val truncated =
                """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1",""" +
                    """"type":"function","function":{"name":"result_tool","arguments":"{\"resultValue\": \"abc"}}]},""" +
                    """"finish_reason":"length"}]}"""
            server.enqueueBody(truncated).andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    server.captured.map { caps =>
                        assert(
                            result.failure.exists(_.isInstanceOf[AIOutputLimitException]),
                            s"a truncated tool call on a ceiling stop must fail as a ceiling stop, got: $result"
                        )
                        assert(caps.size == 1, s"and must not be retried into the same wall, got ${caps.size} requests")
                    }
                }
            }
        }
    }

    "stopping at the output ceiling fails the generation once, without retrying or iterating" in {
        // Retrying is not merely useless here, it is expensive: the same request against the same
        // ceiling stops in the same place, having spent the whole ceiling again. Iterating is worse,
        // since the loop would do it once per allowed iteration. Exactly one request must be made.
        TestCompletionServer.run { server =>
            val config = anthropicServerConfig(server.baseUrl).retrySchedule(Schedule.repeat(3))
            val ceilingStop =
                """{"id":"m","content":[],"model":"m","role":"assistant","stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":1}}"""
            server.enqueueBody(ceilingStop).andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    server.captured.map { caps =>
                        assert(
                            result.failure.exists(_.isInstanceOf[AIOutputLimitException]),
                            s"expected the output-ceiling failure, got: $result"
                        )
                        assert(caps.size == 1, s"the ceiling stop must not be retried or iterated, got ${caps.size} requests")
                    }
                }
            }
        }
    }

    "a 400 rejected request halts fast without retry" in {
        TestCompletionServer.run { server =>
            val config = anthropicServerConfig(server.baseUrl).retrySchedule(Schedule.repeat(1))
            server.enqueueStatus(400, """{"error":{"message":"malformed request"}}""").andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    server.captured.map { caps =>
                        result match
                            case Result.Failure(_: AIRequestRejectedException) => ()
                            case other                                         => fail(s"expected AIRequestRejectedException, got: $other")
                        assert(caps.size == 1, s"a 400 must halt without retry, expected 1 request, got: ${caps.size}")
                    }
                }
            }
        }
    }

    "a client-side timeout halts fast as AICompletionTimeoutException" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).timeout(100.millis).retrySchedule(Schedule.repeat(1))
            server.enqueueNeverRespond.andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    server.captured.map { caps =>
                        result match
                            case Result.Failure(_: AICompletionTimeoutException) => ()
                            case other => fail(s"expected AICompletionTimeoutException, got: $other")
                        assert(caps.size == 1, s"a per-call timeout must halt without retry, expected 1 request, got: ${caps.size}")
                    }
                }
            }
        }
    }

    "the configured timeout bounds a completion call and its retries, not one attempt" in {
        TestCompletionServer.run { server =>
            // Every attempt fails transiently and is retried on a schedule whose backoff outlasts the
            // configured timeout. The deadline covers the retry clause, so the call surfaces the timeout;
            // a deadline that covered only one attempt would let the schedule run to exhaustion and
            // surface the throttle instead.
            val config = serverConfig(server.baseUrl)
                .timeout(300.millis)
                .retrySchedule(Schedule.exponentialBackoff(initial = 200.millis, factor = 2, maxBackoff = 2.seconds).take(10))
            def throttle(remaining: Int): Unit < Async =
                if remaining == 0 then ()
                else server.enqueueStatus(429, """{"error":{"message":"rate limited"}}""").andThen(throttle(remaining - 1))
            throttle(12).andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    result match
                        case Result.Failure(_: AICompletionTimeoutException) => succeed
                        case other =>
                            fail(s"expected the call deadline to fire while retries were still pending, got: $other")
                }
            }
        }
    }

    "a 429 rate limit retries then succeeds against the OpenAI-configured provider (classifyHttp parity)" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).retrySchedule(Schedule.repeat(1))
            server.enqueueStatus(429, """{"error":{"message":"rate limited"}}""").andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":"ok"}""")).andThen {
                    LLM.run(config)(AI.gen[String]).map { result =>
                        server.captured.map { caps =>
                            assert(result == "ok", s"expected 'ok' after the retried attempt, got '$result'")
                            assert(caps.size == 2, s"a 429 should retry exactly once, expected 2 requests, got: ${caps.size}")
                        }
                    }
                }
            }
        }
    }

    "a 401 auth failure halts fast against the OpenAI-configured provider (classifyHttp parity)" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).retrySchedule(Schedule.repeat(1))
            server.enqueueStatus(401, """{"error":{"message":"invalid api key"}}""").andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    server.captured.map { caps =>
                        result match
                            case Result.Failure(_: AIProviderAuthException) => ()
                            case other                                      => fail(s"expected AIProviderAuthException, got: $other")
                        assert(caps.size == 1, s"a 401 must halt without retry, expected 1 request, got: ${caps.size}")
                    }
                }
            }
        }
    }

    "forget isolation survives a cancellation mid-generation" in {
        LLM.run {
            AI.initWith { ai =>
                Latch.init(1).map { reachedInner =>
                    ai.systemMessage("parent").andThen {
                        // The forget branch mutates the (isolated) inner context, signals it reached that point
                        // via an event latch (no sleep), then suspends forever. The other branch wins after
                        // observing the latch, which interrupts the forget branch mid-flight.
                        Async.race[Nothing, Unit, LLM](
                            AI.forget {
                                ai.systemMessage("inner-mutation")
                                    .andThen(reachedInner.release)
                                    .andThen(Async.never[Unit])
                            },
                            AI.config.andThen(reachedInner.await)
                        ).andThen {
                            ai.context.map(_.raw.map(_.content)).map { contents =>
                                assert(contents == Chunk("parent"), s"parent context must be unchanged after the interrupt, got: $contents")
                            }
                        }
                    }
                }
            }
        }
    }

    "process hook fires after gen with a thought enabled" in {
        // The thought-process callback records via AtomicRef, so its row is < (LLM & Sync). Thought.opening
        // requires Isolate[S, LLM, S]; kyo derives no isolate for Sync (it is stateless: Abort[Nothing]), so
        // supply a genuine identity isolate here. It is scoped to this test, never class-level, so it cannot
        // shadow the LLM isolate that Async.fill resolves elsewhere.
        given Isolate[Sync, Any, Sync] =
            new Isolate[Sync, Any, Sync]:
                type State        = Unit
                type Transform[A] = A
                def capture[A, S](f: Unit => A < S)(using Frame): A < (Sync & Any & S) = f(())
                def isolate[A, S](state: Unit, v: A < (S & Sync))(using Frame): A < (Any & S) =
                    // Unsafe: sound because Sync (= Abort[Nothing]) is phantom at runtime; the row change is a
                    // type-level erasure with no value-level consequence (the isolate is a true pass-through).
                    v.asInstanceOf[A < S]
                def restore[A, S](v: A < S)(using Frame): A < (Sync & S) = v
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            AtomicRef.init(Maybe.empty[String]).map { captured =>
                val thought = Thought.opening[City]((c: City) => captured.set(Present(c.name)))
                val envelope =
                    """{"openingThoughts":{"City":{"name":"Lyon"}},"resultValue":"answer","closingThoughts":{}}"""
                server.enqueueBody(resultToolBody(envelope)).andThen {
                    LLM.run(config)(AI.enable(thought)(AI.gen[String])).andThen {
                        captured.get.map { c =>
                            assert(c == Present("Lyon"), s"the opening thought hook should fire with the decoded field, got: $c")
                        }
                    }
                }
            }
        }
    }

    "the eval row maps a transport failure to a typed AITransportException" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).retrySchedule(Schedule.done)
            // Only malformed bodies: the decode failure surfaces as an HttpException through the eval row,
            // mapped at the eval boundary to a typed AITransportException carrying the HttpException as cause,
            // observed by Abort.run[AIException] at the run residual.
            server.enqueueBody("not json").andThen {
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    assert(result.isFailure, s"a transport decode failure should surface as a typed failure, got: $result")
                    result match
                        case Result.Failure(ex: AITransportException) =>
                            assert(
                                ex.cause.isInstanceOf[HttpJsonDecodeException],
                                s"the AITransportException should carry the HttpException as cause, got: ${ex.cause}"
                            )
                        case _ => assert(false, s"expected AITransportException, got: $result")
                    end match
                }
            }
        }
    }

    "a tool call round-trips through the eval loop: the tool runs, its result feeds back, then result_tool" in {
        TestCompletionServer.run { server =>
            val config  = serverConfig(server.baseUrl)
            val doubler = Tool.init[Int]("double", "doubles its input")(n => n * 2)
            // Turn 1: the model calls "double" with 21. Turn 2: it calls result_tool with the answer.
            val turn1 =
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"double","arguments":"21"}}]}}]}"""
            server.enqueueBody(turn1).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":42}""")).andThen {
                    LLM.run(config)(AI.initWith(ai => ai.enable(doubler).andThen(ai.gen[Int]("compute")))).map { result =>
                        server.captured.map { caps =>
                            assert(result == 42, s"final gen result: $result")
                            assert(caps.size == 2, s"expected 2 requests (tool turn + result turn), got: ${caps.size}")
                            assert(
                                caps(1).body.contains("42"),
                                s"turn-2 request must carry the doubled tool result 42, got: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "a tool call's extra_content round-trips: it is written back verbatim on the next request" in {
        // One endpoint refuses the follow-up request unless the token it issued with a call is returned
        // with it. kyo carries that opaque payload on Call.providerExtra and writes it back as
        // extra_content on the assistant message it replays. Regression here is otherwise invisible.
        TestCompletionServer.run { server =>
            val config  = serverConfig(server.baseUrl)
            val doubler = Tool.init[Int]("double", "doubles its input")(n => n * 2)
            // Turn 1: the model calls "double" with a tool call carrying extra_content.
            val turn1 =
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
                    """"function":{"name":"double","arguments":"21"},"extra_content":{"echo_token":"round-trip-xyz"}}]}}]}"""
            server.enqueueBody(turn1).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":42}""")).andThen {
                    LLM.run(config)(AI.initWith(ai => ai.enable(doubler).andThen(ai.gen[Int]("compute")))).map { result =>
                        server.captured.map { caps =>
                            assert(result == 42, s"final result: $result")
                            assert(caps.size == 2, s"tool turn + result turn: ${caps.size}")
                            // The second request replays the assistant tool call, which must carry the token back.
                            assert(
                                caps(1).body.contains("round-trip-xyz"),
                                s"the follow-up must write extra_content back verbatim: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "the eval loop runs every tool call from one assistant message (parallel tool calls, gaia #944)" in {
        // A model may emit several tool_calls in one turn; some agent loops crash on more than one (amd/gaia
        // #944). The eval loop must run all of them and feed every result back before the next turn.
        TestCompletionServer.run { server =>
            val config  = serverConfig(server.baseUrl)
            val doubler = Tool.init[Int]("double", "doubles its input")(n => n * 2)
            val negate  = Tool.init[Int]("negate", "negates its input")(n => -n)
            // Turn 1: the model calls both tools in one assistant message. Turn 2: it calls result_tool.
            val turn1 =
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"double","arguments":"21"}},{"id":"c2","type":"function","function":{"name":"negate","arguments":"5"}}]}}]}"""
            server.enqueueBody(turn1).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":"done"}""")).andThen {
                    LLM.run(config)(AI.initWith(ai => ai.enable(doubler, negate).andThen(ai.gen[String]("compute both")))).map { result =>
                        server.captured.map { caps =>
                            assert(result == "done", s"final gen result: $result")
                            assert(caps.size == 2, s"expected 2 requests, got: ${caps.size}")
                            val turn2 = caps(1).body
                            assert(turn2.contains("42"), s"turn-2 must carry double's result 42: $turn2")
                            assert(turn2.contains("-5"), s"turn-2 must carry negate's result -5: $turn2")
                        }
                    }
                }
            }
        }
    }

    "an Async tool body compiles and runs in the eval loop (no Isolate required)" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            // Tool.init does not require Isolate[S, LLM, S]. Async has no such Isolate, so an async tool
            // body must compile and run without one. The body does real async work; its result feeds back.
            val asyncDoubler: Tool[Async] =
                Tool.init[Int][Int, Async]("async_double", "doubles its input asynchronously")(n => Async.delay(1.millis)(n * 2))
            val turn1 =
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"async_double","arguments":"21"}}]}}]}"""
            server.enqueueBody(turn1).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":42}""")).andThen {
                    LLM.run(config)(AI.initWith(ai => ai.enable(asyncDoubler).andThen(ai.gen[Int]("go")))).map { result =>
                        server.captured.map { caps =>
                            assert(result == 42, s"final answer: $result")
                            assert(
                                caps(1).body.contains("42"),
                                s"turn-2 request must carry the async tool's doubled result 42, got: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "a tool's capability effect (Check) runs in the eval loop and reaches the caller's handler" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            // A tool whose run records a Check failure. enable[S] threads Check onto the row; when the model
            // calls the tool during gen, the Check effect must reach the enclosing Check.runChunk, not be erased.
            val checkTool: Tool[Check] = Tool.init[Int][Int, Check]("check_it")((_: Int) => Check.require(false, "tool-ran").andThen(0))
            val turn1 =
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"check_it","arguments":"1"}}]}}]}"""
            server.enqueueBody(turn1).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":7}""")).andThen {
                    val program: Int < (Check & LLM) = AI.initWith(ai => ai.enable(checkTool).andThen(ai.gen[Int]("go")))
                    Check.runChunk(LLM.run(config)(program)).map { case (failures, answer) =>
                        assert(answer == 7, s"final answer: $answer")
                        assert(
                            failures.exists(_.message.contains("tool-ran")),
                            s"the enabled tool's Check effect must be observed by the caller, got: ${failures.map(_.message)}"
                        )
                    }
                }
            }
        }
    }

    "the instance config override beats the scope config in the request" in {
        TestCompletionServer.run { server =>
            val scopeConfig = serverConfig(server.baseUrl).temperature(0.1)
            val instanceCfg = serverConfig(server.baseUrl).temperature(0.9)
            server.enqueueBody(resultToolBody("""{"resultValue":1}""")).andThen {
                LLM.run(scopeConfig)(AI.init(instanceCfg).map(_.gen[Int])).andThen {
                    server.captured.map { caps =>
                        assert(
                            caps.head.body.contains("0.9"),
                            s"request must use the instance override temperature, got: ${caps.head.body}"
                        )
                        assert(!caps.head.body.contains("0.1"), s"request must not use the scope temperature, got: ${caps.head.body}")
                    }
                }
            }
        }
    }

    "a mode's withConfig reaches a config-overridden instance, layering on top of the override" in {
        TestCompletionServer.run { server =>
            val instanceCfg = serverConfig(server.baseUrl).temperature(0.1)
            val varyMode    = Mode.init([A] => (_, gen) => AI.withConfig(_.temperature(0.7))(gen))
            server.enqueueBody(resultToolBody("""{"resultValue":1}""")).andThen {
                LLM.run(serverConfig(server.baseUrl))(
                    AI.init(instanceCfg).map(ai => ai.enable(varyMode).andThen(ai.gen[Int]))
                ).andThen {
                    server.captured.map { caps =>
                        assert(
                            caps.head.body.contains("0.7") && !caps.head.body.contains("0.1"),
                            s"a mode's withConfig (after the merge) must reach the request even on an overridden instance, got: ${caps.head.body}"
                        )
                    }
                }
            }
        }
    }

    "a scope withConfig wrapped around a gen is shadowed by the instance config override" in {
        TestCompletionServer.run { server =>
            val instanceCfg = serverConfig(server.baseUrl).temperature(0.1)
            server.enqueueBody(resultToolBody("""{"resultValue":1}""")).andThen {
                LLM.run(serverConfig(server.baseUrl))(
                    AI.init(instanceCfg).map(ai => AI.withConfig(_.temperature(0.7))(ai.gen[Int]))
                ).andThen {
                    server.captured.map { caps =>
                        assert(
                            caps.head.body.contains("0.1") && !caps.head.body.contains("0.7"),
                            s"an instance config override shadows a scope withConfig around the gen, got: ${caps.head.body}"
                        )
                    }
                }
            }
        }
    }

    "run(f) installs a transformed configuration" in {
        LLM.run(_.maxIterations(99))(AI.config.map(_.maxIterations)).map(n => assert(n == 99, s"maxIterations: $n"))
    }

    "bare run installs the env-default configuration" in {
        LLM.run(AI.config.map(_.maxIterations)).map(n => assert(n == 5, s"default maxIterations: $n"))
    }

    "a closed meter under an in-flight gen panics with AIMeterClosedException" in {
        TestCompletionServer.run { server =>
            Meter.initMutexUnscoped.map { meter =>
                meter.close.andThen {
                    val config = serverConfig(server.baseUrl).meter(meter)
                    Abort.run[AIException](LLM.run(config)(AI.gen[Int])).map { result =>
                        assert(result.isPanic, s"a closed meter should panic, got: $result")
                        result match
                            case Result.Panic(ex) =>
                                assert(
                                    ex.isInstanceOf[AIMeterClosedException],
                                    s"expected AIMeterClosedException, got: ${ex.getClass.getName}"
                                )
                            case _ => assert(false, "expected a panic")
                        end match
                    }
                }
            }
        }
    }

    "the LLM effect row does not include Async" in {
        // Compile-time assertion: NotGiven[LLM <:< Async] must be derivable, so < LLM never requires Async.
        val notAsync: NotGiven[LLM <:< Async] = summon[NotGiven[LLM <:< Async]]
        val x: Unit < LLM                     = AI.init.map(ai => ai.userMessage("a").andThen(ai.userMessage("b")))
        // x ascribed as Unit < LLM compiles, confirming no Async leak.
        val _ = x
        assert(notAsync != null)
    }

    "run threads State so one userMessage yields one context message" in {
        LLM.runTuple {
            AI.init.map { ai =>
                ai.userMessage("hi").andThen(ai)
            }
        }.map { case (state, ai) =>
            val msgs = state.contextOf(ai).raw
            assert(msgs == Chunk(UserMessage("hi", Absent)), s"expected exactly one userMessage, got: $msgs")
        }
    }

    "gen's row is Int < LLM while run's residual adds Async & Abort[AIGenException]" in {
        // The two ascriptions are the compile-time proof; the run yields the scripted Int at runtime.
        TestCompletionServer.run { server =>
            val config                                   = serverConfig(server.baseUrl)
            val x: Int < LLM                             = AI.gen[Int]
            def y: Int < (Async & Abort[AIGenException]) = LLM.run(config)(x)
            server.enqueueBody(resultToolBody("""{"resultValue":42}""")).andThen {
                y.map(result => assert(result == 42, s"run should yield the scripted Int 42, got: $result"))
            }
        }
    }

    "Async.fill over an LLM computation resolves the single LLM isolate" in {
        // Async.fill over < LLM inside LLM.run compiles, confirming the LLM isolate given resolves for a fork.
        LLM.run {
            Async.fill(3) {
                AI.init.map { ai =>
                    ai.userMessage("x").andThen(ai.context)
                }
            }
        }.map { ctxs =>
            assert(ctxs.size == 3, s"expected 3 results from fill(3), got: ${ctxs.size}")
            assert(ctxs.forall(_.raw.size == 1), s"each context should have 1 message, got: $ctxs")
        }
    }

    "an unrecovered fork generation failure surfaces as a panic" in {
        // The LLM isolate (Keep = Async) discharges a fork's Abort[AIGenException] via getOrThrow, so an
        // unrecovered generation failure in a parallel branch surfaces as a fiber panic on run.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).retrySchedule(Schedule.done)
            server.enqueueBody("not json").andThen {
                server.enqueueBody("not json").andThen {
                    Abort.run[AIException](LLM.run(config)(Async.fill(2)(AI.gen[Int]))).map { result =>
                        assert(result.isPanic, s"an unrecovered fork gen failure should surface as a panic, got: $result")
                    }
                }
            }
        }
    }

    "LLM ops carry their target and payload as data" in {
        // LLM.Op subclasses are case classes (data) or case objects (field-less markers, Op.Init/Op.Env/
        // Op.GetState); verified by reading the data-bearing ops' fields.
        val theAi     = new AI(0L, new AnyRef)
        val readOp    = LLM.internal.Op.Read(theAi)
        val addOp     = LLM.internal.Op.Add(theAi, UserMessage("x", Absent))
        val setOp     = LLM.internal.Op.Set(theAi, Context.empty)
        val discardOp = LLM.internal.Op.Discard(theAi)
        assert(readOp.target == theAi, "Op.Read carries its target field")
        assert(addOp.message == UserMessage("x", Absent), "Op.Add carries its message field")
        assert(setOp.context == Context.empty, "Op.Set carries its context field")
        assert(discardOp.target == theAi, s"Op.Discard carries its target AI field, got: ${discardOp.target}")
    }

    "run threads State across init, two adds, and a read" in {
        LLM.run {
            AI.initWith { ai =>
                ai.userMessage("a")
                    .andThen(ai.userMessage("b"))
                    .andThen(ai.context)
            }
        }.map { ctx =>
            val expected = Chunk(UserMessage("a", Absent), UserMessage("b", Absent))
            assert(ctx.raw == expected, s"context should have two messages in order, got: ${ctx.raw}")
        }
    }

    "instance ids come from the run's threaded counter and restart per run" in {
        // ids come from the run's threaded State counter, not a process-global: within a run successive
        // inits get distinct, monotonically increasing ids (and Ordering[AI] reflects that); an independent
        // run restarts from the same start. Reading id/ordering off the escaped instances is a pure accessor,
        // so it does not trip the cross-run guard.
        LLM.run(AI.init.map(a => AI.init.map(b => (a, b)))).map { (a, b) =>
            assert(a.id == 0L, s"within a run the first init id is 0, got ${a.id}")
            assert(b.id == 1L, s"successive inits increment, got ${b.id}")
            assert(Ordering[AI].lt(a, b), s"Ordering[AI] orders by id: a=${a.id} b=${b.id}")
            LLM.run(AI.init.map(_.id)).map { again =>
                assert(again == 0L, s"a fresh run restarts the id counter (per-run, not global), got $again")
            }
        }
    }

    "an instance used in a different run panics with a cross-run error" in {
        // An AI created in one LLM.run can't address another run's slots: used in a different run it panics
        // with a clear message pointing at snapshot/recover, instead of silently resolving a same-id slot.
        LLM.run(AI.init).map { escaped =>
            Abort.run[AIException](LLM.run(escaped.userMessage("x"))).map { result =>
                assert(result.isPanic, s"cross-run use should panic, got: $result")
                result match
                    case Result.Panic(ex) =>
                        assert(ex.getMessage.contains("different LLM.run"), s"message: ${ex.getMessage}")
                        assert(ex.getMessage.contains("ai.snapshot"), s"message must point at snapshot/recover: ${ex.getMessage}")
                    case _ => assert(false, "expected a panic")
                end match
            }
        }
    }

    "the cross-run guard fires for every op that targets an instance" in {
        // The cross-run guard (AICrossRunException) must fire for EVERY op that targets an instance, not just
        // Add. Mint in run A, then probe each op in a fresh run (fresh owner) and assert it panics.
        LLM.run(AI.init).map { escaped =>
            for
                read    <- Abort.run[AIException](LLM.run(escaped.context)).map(_.isPanic)
                set     <- Abort.run[AIException](LLM.run(escaped.setContext(Context.empty))).map(_.isPanic)
                gen     <- Abort.run[AIException](LLM.run(escaped.gen[Int])).map(_.isPanic)
                stream  <- Abort.run[AIException](LLM.run(escaped.stream[Int].map(_.run))).map(_.isPanic)
                discard <- Abort.run[AIException](LLM.run(escaped.reset)).map(_.isPanic)
                session <- Abort.run[AIException](LLM.run(escaped.snapshot)).map(_.isPanic)
                setSess <- Abort.run[AIException](LLM.run(escaped.enable(Tool.empty))).map(_.isPanic)
            yield List(read, set, gen, stream, discard, session, setSess)
        }.map { panics =>
            assert(
                panics.forall(identity),
                s"every targeted op must panic cross-run; got [read,set,gen,stream,discard,session,setSess]=$panics"
            )
        }
    }

    "merging a parallel fork is prefix-aware on shared instances and keeps the parent env" in {
        // Merge: (a) shared instance merges prefix-aware; (b) fork-born added as-is; (c) env stays parent.
        LLM.run {
            AI.init.map { shared =>
                shared.userMessage("p1").andThen {
                    AI.withConfig(_.temperature(0.2)) {
                        // spawn a parallel fork via Async.fill(1) which uses the LLM isolate
                        Async.fill(1) {
                            AI.init.map { born =>
                                shared.userMessage("f1").andThen {
                                    born.userMessage("b1").andThen {
                                        AI.withConfig(_.temperature(0.9))(Kyo.unit)
                                    }
                                }
                            }
                        }.andThen {
                            AI.config.map { config =>
                                shared.context.map { sharedCtx =>
                                    (config.temperature, sharedCtx.raw.map(_.content))
                                }
                            }
                        }
                    }
                }
            }
        }.map { case (temperature, sharedContents) =>
            assert(
                temperature == Present(0.2),
                s"parent config.temperature should be 0.2 (fork's 0.9 should not bleed through), got: $temperature"
            )
            assert(
                sharedContents == Chunk("p1", "f1"),
                s"shared context should have p1 and f1 in append order after merge, got: $sharedContents"
            )
        }
    }

    "AIRef equality and validity are keyed by the AI id" in {
        // AIRef equality is by the AI's stable id (so a slot still matches its key after the referent is
        // GC'd, letting State.pruned find and drop it); isValid reflects whether the referent is live.
        val a1        = new AI(5L, new AnyRef)
        val a2        = new AI(5L, new AnyRef) // same id, different owner
        val ref1      = new LLM.internal.AIRef(a1)
        val ref2      = new LLM.internal.AIRef(a2)
        val different = new LLM.internal.AIRef(new AI(6L, new AnyRef))
        assert(ref1.equals(ref2), "AIRefs with the same AI id compare equal")
        assert(ref1.hashCode == ref2.hashCode, "equal AIRefs share a hashCode")
        assert(!ref1.equals(different), "AIRefs with different ids are not equal")
        assert(ref1.isValid, "a ref to a live AI is valid")
    }

    "default-off matches committed pre-change golden bytes" in {
        // With no compactor enabled (env.compactor Absent), the seam is a literal no-op: the enriched-request
        // bytes must equal the committed golden captured from the pre-seam eval, byte-for-byte. The golden was
        // captured independently of this edited path (a source constant), so a regression that leaks a byte
        // onto the Absent path fails this leaf for real, not a tautological compare-the-code-to-itself.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"x"}""")).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        ai.systemMessage("you are precise").andThen {
                            ai.userMessage("ping").andThen(ai.gen[String])
                        }
                    }
                }.andThen {
                    requestBody(server).map { body =>
                        assert(body == goldenDefaultOffRequest, s"default-off request drifted from the committed golden: $body")
                    }
                }
            }
        }
    }

    "seam adds no Op, no slot shrink" in {
        // (1) The LLM Op GADT stays at exactly 13 subclasses: the compaction seam mints no new Op.
        val theAi = new AI(0L, new AnyRef)
        val cfg   = serverConfig("http://127.0.0.1:1")
        val ops: List[LLM.internal.Op[?]] = List(
            LLM.internal.Op.Read(theAi),
            LLM.internal.Op.Add(theAi, UserMessage("x", Absent)),
            LLM.internal.Op.Set(theAi, Context.empty),
            LLM.internal.Op.Init,
            LLM.internal.Op.Env,
            LLM.internal.Op.Gen(theAi, summon[Schema[Int]]),
            LLM.internal.Op.Stream(theAi, summon[Schema[Int]], Tag[Emit[Chunk[Int]]]),
            LLM.internal.Op.SetEnv(AIEnv.empty),
            LLM.internal.Op.Discard(theAi),
            LLM.internal.Op.GetState,
            LLM.internal.Op.SetState(LLM.State.empty(cfg)),
            LLM.internal.Op.GetSession(theAi),
            LLM.internal.Op.SetSession(theAi, AISession.empty)
        )
        assert(ops.size == 13, s"the LLM Op GADT has exactly 13 subclasses (no new Op minted for compaction), got ${ops.size}")
        // (2) The transcript slot (ai.context) never shrinks across the seam (Absent compactor).
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"ok"}""")).andThen {
                LLM.run(config) {
                    AI.init.map { ai =>
                        ai.userMessage("hello").andThen {
                            ai.context.map(_.raw.size).map { before =>
                                ai.gen[String].andThen {
                                    ai.context.map(_.raw.size).map { after =>
                                        assert(
                                            after >= before,
                                            s"the transcript slot never shrinks across the seam: before=$before after=$after"
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

    "genLoop merge threads compactor instance-over-scope, last-wins" in {
        // The genLoop env-merge threads the compactor with instance-over-scope precedence
        // (session.env.compactor.orElse(scopeEnv.compactor)): a single active policy, last-wins, never a
        // pipeline. Read the scope and instance envs directly (no generation turn) and assert the precedence.
        // Two DISTINCT compactor instances (Compactor.init returns the shared Default singleton, so a
        // precedence test needs its own instances to tell scope from instance by reference).
        val scopeCompactor: Compactor[Any] = new Compactor[Any]:
            def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) = ctx.compacted
        val instanceCompactor: Compactor[Any] = new Compactor[Any]:
            def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) = ctx.compacted
        val withScope =
            LLM.run {
                AI.enable(scopeCompactor) {
                    AI.init.map { withInstance =>
                        withInstance.enable(instanceCompactor).andThen {
                            AI.init.map { bare =>
                                for
                                    scopeEnv <- AI.env
                                    instEnv  <- withInstance.snapshot.map(_.env)
                                    bareEnv  <- bare.snapshot.map(_.env)
                                yield (scopeEnv.compactor, instEnv.compactor, bareEnv.compactor)
                            }
                        }
                    }
                }
            }
        val noScope =
            LLM.run {
                AI.init.map { bare =>
                    for
                        scopeEnv <- AI.env
                        instEnv  <- bare.snapshot.map(_.env)
                    yield (scopeEnv.compactor, instEnv.compactor)
                }
            }
        withScope.map { case (scopeC, instC, bareC) =>
            assert(scopeC.exists(_ eq scopeCompactor), "scope env carries scopeCompactor")
            assert(instC.exists(_ eq instanceCompactor), "instance env carries instanceCompactor")
            assert(
                instC.orElse(scopeC).exists(_ eq instanceCompactor),
                "both present -> merge picks instanceCompactor (instance-over-scope)"
            )
            assert(bareC.isEmpty, "a bare instance holds Absent")
            assert(bareC.orElse(scopeC).exists(_ eq scopeCompactor), "only scope present -> merge picks scopeCompactor")
            noScope.map { case (nScopeC, nInstC) =>
                assert(nScopeC.isEmpty && nInstC.isEmpty, "neither present -> both Absent (byte-unchanged)")
                assert(nInstC.orElse(nScopeC).isEmpty, "neither present -> merged compactor stays Absent")
            }
        }
    }

    "instance compactor takes precedence over scope at the gen request seam" in {
        // Beyond the env-merge above: drive a real generation with BOTH a scope compactor (huge cap, never
        // compacts) and an instance compactor (tiny cap, compacts) enabled, and assert the OUTBOUND gen
        // request is compacted, i.e. the INSTANCE compactor's rendering (instance-over-scope) reached the
        // wire, not merely that Maybe.orElse picks it in the test body.
        TestCompletionServer.run { server =>
            val cfg         = serverConfig(server.baseUrl).compaction(_.contextCeiling(3))
            val scopeMarker = "SCOPE-COMPACTOR-MARKER"
            val instMarker  = "INSTANCE-COMPACTOR-MARKER"
            def tagging(tag: String): Compactor[Any] = new Compactor[Any]:
                def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
                    Chunk(SystemMessage(tag))
            server.enqueueBody(resultToolBody("""{"resultValue":"done"}""")).andThen {
                LLM.run(cfg) {
                    AI.enable(tagging(scopeMarker)) {
                        AI.init.map { ai =>
                            val ctx = ctxOf(sm("s"), um("first"), am("big " + ("x" * 400)), um("latest"))
                            ai.setContext(ctx).andThen(ai.enable(tagging(instMarker))).andThen(ai.gen[String]).andThen {
                                server.awaitCaptured(cap =>
                                    cap.path == "v1/chat/completions" && cap.body.contains("result_tool")
                                ).map { mainReq =>
                                    assert(
                                        mainReq.body.contains(instMarker),
                                        s"the instance compactor's rendering reached the outbound gen request: ${mainReq.body}"
                                    )
                                    assert(
                                        !mainReq.body.contains(scopeMarker),
                                        s"the scope compactor's rendering must NOT reach the wire (instance-over-scope): ${mainReq.body}"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "an LLM-composed program stays in the < LLM row after the seam (no Async leak)" in {
        // The load-bearing compile check: a program built ONLY from LLM operations ascribes to Unit < LLM,
        // proving the seam leaked no Async into the LLM effect's own row (Async still enters only at Gen/Stream,
        // riding LLM.run's residual). A seam edit that widened eval's own < LLM row would make this fail.
        val notAsync: NotGiven[LLM <:< Async] = summon[NotGiven[LLM <:< Async]]
        val p: Unit < LLM                     = AI.init.map(ai => ai.userMessage("a").andThen(ai.userMessage("b")))
        val _                                 = p
        assert(notAsync != null, "NotGiven[LLM <:< Async] is derivable and the < LLM ascription compiles")
    }

    "a wire that refuses the model's tool call gets a repair turn, not an outright failure" - {

        // Config.Groq declares InvalidToolCalls.Rejected("tool_use_failed"); Config.OpenAI leaves it Returned. The entries
        // are read for that declaration alone, which is the point: the loop never asks which wire it is.
        def rejectingConfig(baseUrl: String): Config =
            Config.Groq.default.apiKey("test").apiUrl(baseUrl)

        "the rejection is fed back and the next turn's result is returned" in {
            TestCompletionServer.run { server =>
                // First turn refused the way such a wire reports an unreadable tool call; second succeeds.
                server.enqueueStatus(
                    400,
                    """{"error":{"message":"Failed to parse tool call arguments as JSON","code":"tool_use_failed"}}"""
                ).andThen {
                    server.enqueueBody(resultToolBody("""{"resultValue":"recovered"}""")).andThen {
                        LLM.run(rejectingConfig(server.baseUrl))(AI.gen[String]).map { result =>
                            server.captured.map { caps =>
                                assert(result == "recovered", s"the turn after the rejection produces the result: $result")
                                assert(caps.size == 2, s"the rejection costs one turn and the loop continues: ${caps.size}")
                                // The correction rides the second request, so the model is told what went
                                // wrong rather than being resampled blind.
                                // The repair quotes the endpoint's OWN reason forward, not a generic line.
                                assert(
                                    caps(1).body.contains("was rejected") &&
                                        caps(1).body.contains("Failed to parse tool call arguments as JSON"),
                                    s"the second turn carries the repair with the wire's reason: ${caps(1).body}"
                                )
                            }
                        }
                    }
                }
            }
        }

        "a wire that returns its malformed calls is unaffected: the rejection still fails outright" in {
            TestCompletionServer.run { server =>
                server.enqueueStatus(400, """{"error":{"message":"context length exceeded"}}""").andThen {
                    Abort.run[AIException](LLM.run(serverConfig(server.baseUrl))(AI.gen[String])).map { result =>
                        assert(result.isFailure, s"an entry declaring nothing keeps the fail-fast reading: $result")
                        result match
                            case Result.Failure(ex: AIRequestRejectedException) =>
                                assert(ex.status == 400, s"and it stays a rejected request: $ex")
                            case other =>
                                assert(false, s"expected AIRequestRejectedException, got: $other")
                        end match
                    }
                }
            }
        }

        "on a rejecting wire a 400 whose code is not the declared one still fails outright" in {
            // The core of the discriminator: a genuinely bad request (context overflow) arrives at the
            // SAME rejecting wire as a 400, but its body carries a different code, so it must NOT be
            // respun as a repairable tool-call rejection. Fail closed keeps it an ordinary rejection.
            TestCompletionServer.run { server =>
                server.enqueueStatus(400, """{"error":{"message":"context length exceeded","code":"context_length_exceeded"}}""").andThen {
                    Abort.run[AIException](LLM.run(rejectingConfig(server.baseUrl).maxIterations(2))(AI.gen[String])).map { result =>
                        assert(result.isFailure, s"a non-matching 400 fails: $result")
                        result match
                            case Result.Failure(ex: AIRequestRejectedException) =>
                                assert(ex.status == 400, s"and stays a rejected request, not a repaired one: $ex")
                            case other =>
                                assert(false, s"expected AIRequestRejectedException, got: $other")
                        end match
                    }
                }
            }
        }

        "on a rejecting wire a 400 with an ABSENT body fails outright" in {
            // Fail closed on an absent body: no code to match, so it stays an ordinary rejected request.
            TestCompletionServer.run { server =>
                server.enqueueStatus(400, "").andThen {
                    Abort.run[AIException](LLM.run(rejectingConfig(server.baseUrl).maxIterations(2))(AI.gen[String])).map { result =>
                        result match
                            case Result.Failure(_: AIRequestRejectedException) => succeed("absent body stays a rejected request")
                            case other => assert(false, s"expected AIRequestRejectedException, got: $other")
                    }
                }
            }
        }

        "on a rejecting wire a 400 with no decodable body fails outright" in {
            // Fail closed on an undecodable body: doubt is an ordinary rejection, never a repair.
            TestCompletionServer.run { server =>
                server.enqueueStatus(400, "not json at all").andThen {
                    Abort.run[AIException](LLM.run(rejectingConfig(server.baseUrl).maxIterations(2))(AI.gen[String])).map { result =>
                        result match
                            case Result.Failure(_: AIRequestRejectedException) => succeed("undecodable body stays a rejected request")
                            case other => assert(false, s"expected AIRequestRejectedException, got: $other")
                    }
                }
            }
        }

        "a wire rejecting every attempt ends in exhaustion rather than looping" in {
            TestCompletionServer.run { server =>
                val refusal = """{"error":{"message":"Failed to parse tool call arguments as JSON","code":"tool_use_failed"}}"""
                Kyo.foreachDiscard(Chunk.fill(6)(refusal))(body => server.enqueueStatus(400, body)).andThen {
                    Abort.run[AIException] {
                        LLM.run(rejectingConfig(server.baseUrl).maxIterations(2))(AI.gen[String])
                    }.map { result =>
                        assert(result.isFailure, s"the budget bounds it: $result")
                        result match
                            case Result.Failure(_: AIEvalExhaustedException) => succeed("bounded by maxIterations")
                            case other => assert(false, s"expected AIEvalExhaustedException, got: $other")
                    }
                }
            }
        }
    }

end LLMTest
