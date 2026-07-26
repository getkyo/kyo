package kyo.ai.completion

import kyo.*
import kyo.Json
import kyo.ai.*
import kyo.ai.Context.*

class OpenAICompletionTest extends kyo.test.Test[Any]:

    case class RequireAllProbe(answer: String, note: Maybe[String]) derives Schema
    case class UserToolInputProbe(q: String, hint: Maybe[String]) derives Schema

    "the request leaves a user tool's optional field OUT of required (require-all is result-tool-only)" in {
        val userTool     = Tool.init[UserToolInputProbe]("lookup", "an optional-field tool")(_ => 1)
        val tools        = userTool.infos.concat(Tool.internal.resultToolDefinition.infos)
        val resultSchema = Thought.internal.resultJson(Chunk.empty, Json.jsonSchema[RequireAllProbe])
        OpenAICompletion.streamRequest(keyedConfig("http://127.0.0.1:9"), Context.empty.userMessage("q"), resultSchema, tools).map {
            req =>
                assert(
                    req.body.contains("\"required\":[\"q\"]"),
                    s"a user tool's schema keeps only its non-optional fields required: ${req.body}"
                )
                assert(
                    !req.body.contains("\"required\":[\"q\",\"hint\"]"),
                    s"a user tool must never be require-all'd: ${req.body}"
                )
        }
    }

    def keyedConfig(baseUrl: String): Config =
        Config.OpenAI.default.apiKey("test-key").apiUrl(baseUrl)

    "the result tool advertises the require-all envelope on the advisory schema (strict stays false)" in {
        // The same StrictSchema.requireAll shape every backend applies to its advisory result
        // schema, so the result contract is identical across all four backends: an optional field
        // is marked required as schema pressure while strict:false keeps the shape advisory.
        val resultSchema = Thought.internal.resultJson(Chunk.empty, Json.jsonSchema[RequireAllProbe])
        val resultTool   = Tool.internal.resultToolDefinition.infos
        OpenAICompletion.streamRequest(keyedConfig("http://127.0.0.1:9"), Context.empty.userMessage("q"), resultSchema, resultTool).map {
            req =>
                assert(
                    req.body.contains("\"required\":[\"answer\",\"note\"]"),
                    s"the optional field must be require-all'd on the result tool's parameters: ${req.body}"
                )
                assert(req.body.contains("\"strict\":false"), s"the schema must stay advisory: ${req.body}")
        }
    }

    def minimalOpenAIBody(content: String): String =
        s"""{"choices":[{"message":{"role":"assistant","content":"$content","tool_calls":null}}]}"""

    "a reply that stopped at the output ceiling reports that, and does not decide what it means" in {
        // The backend's job here is to say how the wire ended, not to rule on it. Whether a ceiling
        // stop is fatal depends on whether the turn carries anything usable, which means reading a
        // tool payload, and payload reading belongs to the tool loop. So the reason is reported and
        // the eval loop adjudicates; that a stop with nothing to act on FAILS is asserted there,
        // against a real generation, in LLMTest.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl).maxTokens(256)
            val body   = """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":null},"finish_reason":"length"}]}"""
            server.enqueueBody(body).andThen {
                Abort.run[AIException](LLM.run(config)(OpenAICompletion(
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

    "the wire's ordinary ways of finishing are recognized, so the unfamiliar-value signal stays meaningful" in {
        // The signal exists to catch vocabulary this decode has not seen before. Leaving the everyday
        // terminal values out of the recognized set fired it on every successful reply, which is the
        // same as not having it: a genuinely new value arrives indistinguishable from routine traffic.
        // Observed live, once per completion, against a provider whose replies end in tool_calls.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val ended  = """{"choices":[{"message":{"role":"assistant","content":"ok","tool_calls":null},"finish_reason":"stop"}]}"""
            val called =
                """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function",""" +
                    """"function":{"name":"result_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
            server.enqueueBody(ended)
                .andThen(LLM.run(config)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    config,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(called))
                .andThen(LLM.run(config)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    config,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .map {
                    case Result.Success(Result.Success(reply)) =>
                        assert(
                            reply.stopReason == Completion.StopReason.Completed,
                            s"a reply that ended by calling a tool is a completed reply: ${reply.stopReason}"
                        )
                    case other => fail(s"expected the reply to be delivered, got: $other")
                }
        }
    }

    "an unrecognized stop value never fails a reply whose content is usable" in {
        // Vocabulary a provider adds later must not turn a good reply into a failure.
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl)
            val body =
                """{"choices":[{"message":{"role":"assistant","content":"fine","tool_calls":null},"finish_reason":"something_new"}]}"""
            server.enqueueBody(body).andThen {
                Abort.run[AIException](LLM.run(config)(OpenAICompletion(
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

    "an ask above the model's own maximum is clamped to it before it reaches the wire" in {
        // The other HTTP backend already clamps. Sending more than the model can produce buys nothing
        // and costs two ways: one endpoint refuses the request over it, and another takes it and
        // silently applies its own smaller limit, which then looks like the model stopping early for
        // no stated reason.
        TestCompletionServer.run { server =>
            val entry  = Config.OpenAI.gpt_5_4
            val config = entry.apiKey("test-key").apiUrl(server.baseUrl).maxTokens(entry.modelMaxOutputTokens * 4)
            server.enqueueBody(minimalOpenAIBody("ok"))
                .andThen(LLM.run(config)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    config,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        assert(
                            caps(0).body.contains(s""""max_completion_tokens":${entry.modelMaxOutputTokens}"""),
                            s"the ask must be clamped to the model's maximum: ${caps(0).body}"
                        )
                    }
                }
        }
    }

    "a reply that stops below the ceiling it was given says so" in {
        // The exact signature of a ceiling that never applied: the endpoint stopped AT a limit while
        // spending far fewer tokens than the request asked for, which means the limit that stopped it
        // was not this request's. That went unnoticed once already, because a reply stopping at the
        // endpoint's own default is indistinguishable from an ordinary long answer unless the two
        // numbers are compared. Comparing them is the whole guard.
        // Unsafe: observing a logging side effect from this test's own run needs a plain cell, and
        // this one is the cross-platform primitive rather than a JDK class, since the suite also runs
        // where no JDK exists.
        val captured = AtomicRef.Unsafe.init("")(using AllowUnsafe.embrace.danger)
        class CapturingLog extends Log.Unsafe.ConsoleLogger("test", Log.Level.warn):
            override def emit(event: Log.Event)(using allow: AllowUnsafe): Unit =
                if event.level == Log.Level.warn then captured.set(event.message)(using allow)
        end CapturingLog
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl).maxTokens(64000)
            val body =
                """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":null},""" +
                    """"finish_reason":"length"}],"usage":{"completion_tokens":8192}}"""
            Log.let(Log(new CapturingLog)) {
                server.enqueueBody(body)
                    .andThen(LLM.run(config)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                        config,
                        Context.empty.userMessage("hi"),
                        Chunk.empty
                    )))))
                    .andThen(Log.flush)
                    .map { _ =>
                        val warned = captured.get()(using AllowUnsafe.embrace.danger)
                        assert(warned.contains("8192"), s"the warning must name what was spent: $warned")
                        assert(warned.contains("64000"), s"and what was asked for: $warned")
                    }
            }
        }
    }

    "the output ceiling rides the field its endpoint reads, whether configured or defaulted" in {
        // These endpoints do not read the ceiling from the same field, and one handed the name it
        // does not read drops it in silence and applies its own default, so the configured ask
        // disappears with nothing on the wire or in the reply to show for it. That is not
        // hypothetical: it truncated real replies at a default the caller never chose. Sending both
        // names is not a way out either, since one family refuses the older name even alongside the
        // current one, so exactly one has to be chosen before the request is built.
        TestCompletionServer.run { server =>
            val readsMaxTokens  = Config.DeepSeek.default.apiKey("test-key").apiUrl(server.baseUrl).maxTokens(256)
            val readsCompletion = keyedConfig(server.baseUrl).maxTokens(256)
            val unset           = keyedConfig(server.baseUrl)
            server.enqueueBody(minimalOpenAIBody("ok"))
                .andThen(LLM.run(readsMaxTokens)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    readsMaxTokens,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalOpenAIBody("ok")))
                .andThen(LLM.run(readsCompletion)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    readsCompletion,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalOpenAIBody("ok")))
                .andThen(LLM.run(unset)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    unset,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        assert(
                            caps(0).body.contains("\"max_tokens\":256"),
                            s"an endpoint reading max_tokens must be sent it: ${caps(0).body}"
                        )
                        assert(
                            !caps(0).body.contains("max_completion_tokens"),
                            s"and must not also be sent the name it does not read: ${caps(0).body}"
                        )
                        assert(
                            caps(1).body.contains("\"max_completion_tokens\":256"),
                            s"an endpoint reading max_completion_tokens must be sent it: ${caps(1).body}"
                        )
                        assert(
                            !caps(1).body.contains("\"max_tokens\""),
                            s"and must not also be sent the deprecated name, which it refuses: ${caps(1).body}"
                        )
                        // An unset ceiling is the model's own maximum, and it rides the same declared
                        // field name. Sending nothing here left the endpoint's undeclared default in
                        // force while this module reported the declared maximum as the applied limit.
                        assert(
                            caps(2).body.contains(s""""max_completion_tokens":${unset.modelMaxOutputTokens}"""),
                            s"an unset ceiling sends the model's own maximum: ${caps(2).body}"
                        )
                        assert(
                            !caps(2).body.contains("\"max_tokens\""),
                            s"and still only through the name this endpoint reads: ${caps(2).body}"
                        )
                    }
                }
        }
    }

    "a graded entry states its level and unforces; an entry with no expressible amount states nothing and stays forced" in {
        // Reasoning is on by default, so a graded entry states its declared level. That level is an
        // ACTIVATION, and one wire in this family refuses a forced tool choice whenever an activation
        // rides, so the turn must go out unforced. An entry whose wire has no field for stating an
        // amount states nothing and keeps forcing, because nothing activated.
        TestCompletionServer.run { server =>
            val disabled = Config.DeepSeek.default.apiKey("test-key").apiUrl(server.baseUrl)
            val other    = keyedConfig(server.baseUrl)
            server.enqueueBody(minimalOpenAIBody("ok"))
                .andThen(LLM.run(disabled)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    disabled,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen(server.enqueueBody(minimalOpenAIBody("ok")))
                .andThen(LLM.run(other)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    other,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        // Reasoning is on by default, so a graded entry states its declared level and
                        // takes the unforced turn: this wire refuses a forced tool choice whenever an
                        // ACTIVATION field rides, which is the whole reason the level is expressible
                        // here at all.
                        assert(
                            caps(0).body.contains("\"reasoning_effort\":\"low\""),
                            s"a graded entry must state its declared default level: ${caps(0).body}"
                        )
                        assert(
                            caps(0).body.contains("\"tool_choice\":\"auto\""),
                            s"and an activated request must not force the tool call: ${caps(0).body}"
                        )
                        assert(
                            !caps(0).body.contains("thinking"),
                            s"the level alone activates; no separate activation object rides: ${caps(0).body}"
                        )
                        // An entry whose wire has no field for stating an amount says nothing about
                        // reasoning, and keeps forcing, because nothing activated.
                        assert(
                            !caps(1).body.contains("thinking") && !caps(1).body.contains("reasoning_effort"),
                            s"an entry with no expressible amount must mention neither parameter: ${caps(1).body}"
                        )
                        assert(
                            caps(1).body.contains("\"tool_choice\":\"required\""),
                            s"and an unactivated request keeps the forced tool call: ${caps(1).body}"
                        )
                    }
                }
        }
    }

    "an entry whose endpoint cannot disable reasoning sends its lowest level, and says so by activating" in {
        // Asked not to reason, a wire with no way to comply gets the least its levels names. The request
        // must ACTIVATE even though it is the off path: a level word is an activation, and a turn
        // carrying one on a wire that refuses the pair must not also carry a forced choice. This entry's
        // wire honors the pair, so the choice still rides; the two facts are independent and both asserted.
        TestCompletionServer.run { server =>
            val cannotDisable = Config.Groq.default.apiKey("test-key").apiUrl(server.baseUrl).disableReasoning
            server.enqueueBody(minimalOpenAIBody("ok"))
                .andThen(LLM.run(cannotDisable)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    cannotDisable,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        val body = caps.head.body
                        assert(
                            body.contains("\"reasoning_effort\":\"low\""),
                            s"the declared lowest level must ride: $body"
                        )
                        assert(
                            !body.contains("\"none\""),
                            s"and no word for none, which this wire refuses: $body"
                        )
                        assert(
                            body.contains("\"tool_choice\":\"required\""),
                            s"this wire honors the pair, so the choice still rides: $body"
                        )
                    }
                }
        }
    }

    "an unset ceiling sends the model's own maximum, and sends nothing where that maximum is a stand-in" in {
        // Withholding the ceiling left the ENDPOINT's undeclared default in force while this module
        // reported the declared maximum as the limit that applied. Measured on one wire: 65536 actually
        // applied against 393216 reported, and a generation that needed 66509 produced no answer at
        // all. The documented contract is that an unset ceiling is the model's own maximum, so it has
        // to ride.
        //
        // It rides only where the maximum is the provider's own. Where the entry's number is a
        // stand-in, sending it would put an invented limit on the wire, so nothing is sent and an
        // over-large ask is left unclamped for the endpoint to refuse and name the real bound.
        TestCompletionServer.run { server =>
            val verified = keyedConfig(server.baseUrl)
            val standIn = keyedConfig(server.baseUrl)
                .model(
                    Config.OpenAI,
                    "unverified-model",
                    128000,
                    Config.OutputMaximum.Unverified(128000),
                    Config.ReasoningEncoding.Managed,
                    true,
                    acceptsImages = true
                )
                .apiKey("test-key").apiUrl(server.baseUrl)
            def gen(cfg: Config) =
                server.enqueueBody(minimalOpenAIBody("ok")).andThen(
                    LLM.run(cfg)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                        cfg,
                        Context.empty.userMessage("hi"),
                        Chunk.empty
                    ))))
                )
            gen(verified)
                .andThen(gen(standIn))
                .andThen(gen(standIn.maxTokens(999999)))
                .andThen {
                    server.captured.map { caps =>
                        assert(
                            caps(0).body.contains(s""""max_completion_tokens":${verified.modelMaxOutputTokens}"""),
                            s"an unset ceiling sends the verified maximum: ${caps(0).body}"
                        )
                        assert(
                            !caps(1).body.contains("max_completion_tokens"),
                            s"a stand-in maximum sends no ceiling at all: ${caps(1).body}"
                        )
                        // Unclamped on purpose: the refusal names the real bound, which is the probe
                        // that settles the entry. Clamping to a stand-in could cut below the true max.
                        assert(
                            caps(2).body.contains(""""max_completion_tokens":999999"""),
                            s"a stated ceiling is not clamped against a stand-in: ${caps(2).body}"
                        )
                    }
                }
        }
    }

    "the entry whose endpoint default stopped a generation now carries its own maximum by default" in {
        // Concrete regression for the failure that drove this: on this entry the endpoint's own
        // undeclared default was measured at 65536, a generation needed 66509, and it returned no
        // answer at all while this module reported the declared 393216 as the limit in force. The
        // shortfall was 1.5%. Pinned on the real catalog entry rather than a synthetic one, because
        // the bug needed BOTH the default path and this endpoint's ceiling encoding to appear.
        TestCompletionServer.run { server =>
            val entry = Config.DeepSeek.default.apiKey("test-key").apiUrl(server.baseUrl)
            server.enqueueBody(minimalOpenAIBody("ok"))
                .andThen(LLM.run(entry)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    entry,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        assert(
                            entry.modelMaxOutputTokens == 393216,
                            s"the declared maximum is the provider's own limit response: ${entry.modelMaxOutputTokens}"
                        )
                        // This endpoint reads the older field name, so the defaulted ceiling has to
                        // ride THAT one: sending it under the name it does not read would drop it in
                        // silence, which is the same defect one layer along.
                        assert(
                            caps(0).body.contains(""""max_tokens":393216"""),
                            s"an unset ceiling sends the declared maximum, in this endpoint's encoding: ${caps(0).body}"
                        )
                        assert(
                            !caps(0).body.contains("max_completion_tokens"),
                            s"and not the name this endpoint ignores: ${caps(0).body}"
                        )
                    }
                }
        }
    }

    "a ceiling above the model's maximum does not report kyo's own clamp as an endpoint anomaly" in {
        // The request carries the ask CLAMPED to the model's declared maximum. A reply that then stops
        // at that clamp has spent less than the RAW ask, which is the exact shape of the symptom this
        // detector exists to report, so comparing against the raw ask blamed the endpoint for kyo's own
        // documented clamp. The detector must compare against what was sent.
        // Unsafe: observing this test's own logging side effect needs a plain cell, and this is the
        // cross-platform primitive rather than a JDK class, since the suite also runs without a JDK.
        val captured = AtomicRef.Unsafe.init("")(using AllowUnsafe.embrace.danger)
        class CapturingLog extends Log.Unsafe.ConsoleLogger("test", Log.Level.warn):
            override def emit(event: Log.Event)(using allow: AllowUnsafe): Unit =
                if event.level == Log.Level.warn then captured.set(event.message)(using allow)
        end CapturingLog
        TestCompletionServer.run { server =>
            val config = keyedConfig(server.baseUrl).maxTokens(999999)
            val sent   = config.effectiveMaxOutputTokens
            val body =
                """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":null},""" +
                    s""""finish_reason":"length"}],"usage":{"completion_tokens":$sent}}"""
            Log.let(Log(new CapturingLog)) {
                server.enqueueBody(body)
                    .andThen(LLM.run(config)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                        config,
                        Context.empty.userMessage("hi"),
                        Chunk.empty
                    )))))
                    .andThen(Log.flush)
                    .map { _ =>
                        assert(sent == config.modelMaxOutputTokens, s"the ask is clamped to the model maximum: $sent")
                        val warned = captured.get()(using AllowUnsafe.embrace.danger)
                        assert(
                            !warned.contains("did not apply"),
                            s"stopping at the clamp is the ceiling working, not an endpoint that ignored it: $warned"
                        )
                    }
            }
        }
    }

    "the forced result turn states reasoning off so that it can actually force" in {
        // The loop's last-resort turn carries only the result tool and exists to COMPEL the call. This
        // wire refuses a forced choice while reasoning is activated, so a graded entry that kept
        // reasoning on could only ask: observed live, a model told to withhold its result kept
        // withholding until the loop ran out of turns.
        TestCompletionServer.run { server =>
            val graded     = Config.DeepSeek.default.apiKey("test-key").apiUrl(server.baseUrl)
            val resultOnly = Tool.internal.resultTool[String](Chunk.empty)
            val schema     = Thought.internal.resultJson(Chunk.empty, Json.jsonSchema[String])
            resultOnly.map { (resultTools, _) =>
                server.enqueueBody(minimalOpenAIBody("ok"))
                    .andThen(LLM.run(graded)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                        graded,
                        Context.empty.userMessage("hi"),
                        resultTools,
                        Present(schema)
                    )))))
                    .andThen {
                        server.captured.map { caps =>
                            assert(
                                caps(0).body.contains("\"tool_choice\":\"required\""),
                                s"the forced turn must compel the call: ${caps(0).body}"
                            )
                            // Off is STATED, not omitted: this wire reasons by default, so an omitted
                            // field would leave it activated and the forced choice refused.
                            assert(
                                caps(0).body.contains("\"thinking\":{\"type\":\"disabled\"}"),
                                s"and states reasoning off in this endpoint's encoding: ${caps(0).body}"
                            )
                            assert(
                                !caps(0).body.contains("reasoning_effort"),
                                s"with no level riding alongside it: ${caps(0).body}"
                            )
                        }
                    }
            }
        }
    }

    "a deactivated request carries its endpoint's off encoding and STAYS forced" in {
        // A deactivation is not an activation. Every statement of this rule lives in prose, and the
        // request shape it governs is the one a wire refuses outright when it is wrong, so the bytes
        // are pinned here: which field says off, and that the tool call is still compelled.
        TestCompletionServer.run { server =>
            // Two off encodings and one entry that does not reason at all, driven through the same path.
            val thinkingTypeOff = Config.DeepSeek.default.apiKey("test-key").apiUrl(server.baseUrl).disableReasoning
            val levelNone       = keyedConfig(server.baseUrl).disableReasoning
            val doesNotReason = keyedConfig(server.baseUrl)
                .model(
                    Config.OpenAI,
                    "no-reasoning-model",
                    128000,
                    Config.OutputMaximum.Verified(16384),
                    Config.ReasoningEncoding.Unavailable,
                    true,
                    acceptsImages = true
                )
                .apiKey("test-key").apiUrl(server.baseUrl).disableReasoning
            def gen(cfg: Config) =
                server.enqueueBody(minimalOpenAIBody("ok")).andThen(
                    LLM.run(cfg)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                        cfg,
                        Context.empty.userMessage("hi"),
                        Chunk.empty
                    ))))
                )
            gen(thinkingTypeOff).andThen(gen(levelNone)).andThen(gen(doesNotReason)).andThen {
                server.captured.map { caps =>
                    assert(
                        caps(0).body.contains("\"thinking\":{\"type\":\"disabled\"}") && !caps(0).body.contains("reasoning_effort"),
                        s"the thinking-type encoding sends its deactivation object and no level: ${caps(0).body}"
                    )
                    assert(
                        caps(0).body.contains("\"tool_choice\":\"required\""),
                        s"and a deactivated request is still forced: ${caps(0).body}"
                    )
                    assert(
                        caps(1).body.contains("\"reasoning_effort\":\"none\"") && !caps(1).body.contains("thinking"),
                        s"the level encoding sends its off word and no activation object: ${caps(1).body}"
                    )
                    assert(
                        caps(1).body.contains("\"tool_choice\":\"required\""),
                        s"and it is forced too: ${caps(1).body}"
                    )
                    // The off field of a reasoning entry is refused outright by a non-reasoning entry on
                    // the same endpoint, so an entry that does not reason must send nothing at all.
                    assert(
                        !caps(2).body.contains("reasoning_effort") && !caps(2).body.contains("thinking"),
                        s"an entry that does not reason sends no off bytes: ${caps(2).body}"
                    )
                    assert(
                        caps(2).body.contains("\"tool_choice\":\"required\""),
                        s"and stays forced: ${caps(2).body}"
                    )
                }
            }
        }
    }

    "temperature carriage: omitted when unset, sent on a model that accepts it, omitted on the reasoning line" in {
        // An unset temperature must be absent from the wire, not sent as null. A set temperature is
        // carried where the model accepts it (the default gpt-5.4 does) and omitted on the reasoning
        // line (gpt-5.5), which rejects a non-default value with a hard 400.
        TestCompletionServer.run { server =>
            val unset        = keyedConfig(server.baseUrl)
            val set          = keyedConfig(server.baseUrl).temperature(0.5)
            val reasoningSet = Config.OpenAI.gpt_5_5.apiKey("test-key").apiUrl(server.baseUrl).temperature(0.5)
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
                .andThen(server.enqueueBody(minimalOpenAIBody("ok")))
                .andThen(LLM.run(reasoningSet)(Abort.run[HttpException](Abort.run[AIException](OpenAICompletion(
                    reasoningSet,
                    Context.empty.userMessage("hi"),
                    Chunk.empty
                )))))
                .andThen {
                    server.captured.map { caps =>
                        assert(!caps(0).body.contains("temperature"), s"an unset temperature must be omitted entirely: ${caps(0).body}")
                        assert(
                            caps(1).body.contains("\"temperature\":0.5"),
                            s"a set temperature must be sent on a model that accepts it (gpt-5.4): ${caps(1).body}"
                        )
                        assert(
                            !caps(2).body.contains("\"temperature\""),
                            s"a reasoning model rejects temperature, so a set value must be omitted: ${caps(2).body}"
                        )
                    }
                }
        }
    }

    "a configured temperature on a reasoning model is omitted from the wire and the gen succeeds (real OpenAI API)" in {
        // gpt-5-mini rejects a request carrying a non-default temperature with a 400, so the backend
        // omits the parameter on the reasoning line; a configured temperature must not fail the gen.
        val key = sys.env.getOrElse("OPENAI_API_KEY", "")
        assume(key.nonEmpty, "OPENAI_API_KEY is not available")
        val config = Config.OpenAI.gpt_5_5.apiKey(key).temperature(0.5)
        LLM.run(config) {
            Abort.run[AIException] {
                AI.initWith { ai =>
                    ai.userMessage("What is 21 + 21? Return the integer.").andThen(ai.gen[Int])
                }
            }
        }.map {
            case Result.Success(n) => assert(n == 42, s"expected 42, got $n")
            case other             => fail(s"a temperature-set gen on a reasoning model must succeed with the parameter omitted: $other")
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
                        case Result.Success(Result.Success(Completion.Reply(Chunk(msg: AssistantMessage), _, _))) =>
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
                        case Result.Success(Result.Success(Completion.Reply(Chunk(msg: AssistantMessage), _, _))) =>
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

    "streaming: streamRequest without an API key fails typed with AIMissingApiKeyException and produces no request" in {
        Abort.run[AIStreamException](
            OpenAICompletion.streamRequest(
                Config.OpenAI.default, // no apiKey
                Context.empty.userMessage("hi"),
                Json.jsonSchema[String],
                Chunk.empty
            )
        ).map {
            case Result.Failure(_: AIMissingApiKeyException) => succeed
            case other                                       => fail(s"expected AIMissingApiKeyException, got: $other")
        }
    }

    "streaming: a chunk reporting the output ceiling is distinguished from one carrying nothing" in {
        // The streamed reply reports the ceiling the same way the non-streamed one does. Without this
        // the stream could only say "a fragment" or "nothing", so a truncated stream was
        // indistinguishable from one that simply produced no arguments.
        def outcome(line: String): Completion.Delta =
            OpenAICompletion.parseDeltaArguments(line) match
                case Result.Success(d) => d
                case other             => sys.error(s"unexpected parse failure for $line: $other")

        val ceiling = """{"choices":[{"delta":{},"finish_reason":"length"}]}"""
        val normal  = """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        val unknown = """{"choices":[{"delta":{},"finish_reason":"content_filter"}]}"""
        val frag    = """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}"""

        assert(outcome(ceiling) == Completion.Delta.OutputLimit, s"a ceiling stop must be its own outcome: ${outcome(ceiling)}")
        assert(outcome(normal) == Completion.Delta.Skip, s"a normal stop carries no fragment and no failure: ${outcome(normal)}")
        assert(outcome(unknown) == Completion.Delta.Skip, s"an unfamiliar stop must not be read as a ceiling: ${outcome(unknown)}")
        assert(outcome(frag) == Completion.Delta.Fragment("{}"), s"a fragment still rides: ${outcome(frag)}")
    }

    "streaming: parseDeltaArguments extracts the arguments delta; [DONE] and content-only deltas carry none" in {
        def frag(line: String): Maybe[String] =
            OpenAICompletion.parseDeltaArguments(line) match
                case Result.Success(Completion.Delta.Fragment(f)) => Present(f)
                case Result.Success(_)                            => Absent
                case other                                        => sys.error(s"unexpected parse failure for $line: $other")
        val toolDelta =
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"x\":1}"}}]}}]}"""
        assert(frag(toolDelta) == Present("""{"x":1}"""), s"tool fragment: ${frag(toolDelta)}")
        // the [DONE] terminator and a content-only/empty-tool-calls delta carry no argument fragment
        assert(frag("[DONE]").isEmpty)
        assert(frag("""{"choices":[{"delta":{"tool_calls":null}}]}""").isEmpty)
        assert(OpenAICompletion.parseDeltaArguments("not a streaming chunk").isFailure)
    }

end OpenAICompletionTest
