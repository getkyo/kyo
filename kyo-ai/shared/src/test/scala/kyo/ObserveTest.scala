package kyo

import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.completion.Completion

class ObserveTest extends kyo.test.Test[Any]:

    /** A config pointing the OpenAI backend at the test server, with a dummy key so the backend proceeds
      * to the HTTP call instead of aborting on a missing key.
      */
    def serverConfig(baseUrl: String): Config =
        Config.OpenAI.default
            .apiKey("test")
            .model(Config.OpenAI, "gpt-4o", 128000, Config.OutputMaximum.Verified(16384), Config.ReasoningEncoding.Unavailable, true, true)
            .apiUrl(baseUrl)

    /** An OpenAI result_tool completion body carrying usage, so a turn reports concrete numbers. */
    def resultBodyWithUsage(value: String, promptTokens: Int, completionTokens: Int): String =
        val escaped = Json.encode(s"""{"resultValue":"$value"}""")
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$escaped}}]}}],""" +
            s""""usage":{"prompt_tokens":$promptTokens,"completion_tokens":$completionTokens,""" +
            s""""prompt_tokens_details":{"cached_tokens":7},"completion_tokens_details":{"reasoning_tokens":0}}}"""
    end resultBodyWithUsage

    /** Wraps an argument JSON fragment in a real OpenAI streaming chunk envelope. */
    def argDelta(fragment: String): String =
        val escaped = fragment.replace("\\", "\\\\").replace("\"", "\\\"")
        s"""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$escaped","name":""}}]}}]}"""

    /** An OpenAI completion body with a plain assistant reply and no tool call (the eval loop iterates). */
    def noResultBody: String =
        """{"choices":[{"message":{"role":"assistant","content":"thinking","tool_calls":null}}]}"""

    val usageOf100and20 = AIStats(100L, Present(7L), 20L, Present(0L), 1)

    "an observer receives the wire reply: messages and usage" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            AtomicRef.init(Chunk.empty[Completion.Reply]).map { seen =>
                val record = Observe.init((_, reply) => seen.getAndUpdate(_.append(reply)).unit)
                server.enqueueBody(resultBodyWithUsage("42", 100, 20)).andThen {
                    LLM.run(config)(AI.enable(record)(AI.gen[String])).map { result =>
                        seen.get.map { replies =>
                            assert(result == "42")
                            assert(replies.size == 1, s"one turn fires one observer, got ${replies.size}")
                            val reply = replies(0)
                            assert(reply.usage == usageOf100and20, s"usage: ${reply.usage}")
                            assert(reply.stopReason == Completion.StopReason.Completed)
                            val calls = reply.messages.collect { case m: AssistantMessage => m.calls }.flattenChunk
                            assert(calls.map(_.function) == Chunk("result_tool"), s"calls: $calls")
                        }
                    }
                }
            }
        }
    }

    "observers fire once per turn across a multi-turn generation" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            AtomicRef.init(0).map { fired =>
                val count = Observe.init((_, _) => fired.getAndUpdate(_ + 1).unit)
                server.enqueueBody(noResultBody).andThen {
                    server.enqueueBody(resultBodyWithUsage("done", 100, 20)).andThen {
                        LLM.run(config)(AI.enable(count)(AI.gen[String])).map { result =>
                            fired.get.map { turns =>
                                assert(result == "done")
                                assert(turns == 2, s"a resultless turn plus the result turn fire twice, got $turns")
                            }
                        }
                    }
                }
            }
        }
    }

    "observers fire in enablement order" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            AtomicRef.init(Chunk.empty[String]).map { recorded =>
                val first  = Observe.init((_, _) => recorded.getAndUpdate(_.append("first")).unit)
                val second = Observe.init((_, _) => recorded.getAndUpdate(_.append("second")).unit)
                server.enqueueBody(resultBodyWithUsage("x", 10, 5)).andThen {
                    LLM.run(config)(AI.enable(first, second)(AI.gen[String])).andThen {
                        recorded.get.map(tags => assert(tags == Chunk("first", "second"), s"order: $tags"))
                    }
                }
            }
        }
    }

    "an instance observer fires for that instance's turns and stays silent for a sibling's" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            AtomicRef.init(Chunk.empty[Long]).map { firedFor =>
                LLM.run(config) {
                    AI.initWith { watched =>
                        AI.initWith { sibling =>
                            val record = Observe.init((ai, _) => firedFor.getAndUpdate(_.append(ai.id)).unit)
                            watched.enable(record).map { _ =>
                                server.enqueueBody(resultBodyWithUsage("a", 10, 5)).andThen {
                                    server.enqueueBody(resultBodyWithUsage("b", 10, 5)).andThen {
                                        watched.gen[String].map(a => sibling.gen[String].map(b => (watched.id, a, b)))
                                    }
                                }
                            }
                        }
                    }
                }.map { (watchedId, a, b) =>
                    firedFor.get.map { ids =>
                        assert(a == "a" && b == "b")
                        assert(ids == Chunk(watchedId), s"only the watched instance's turn fires, got ids: $ids")
                    }
                }
            }
        }
    }

    "an observer whose S aborts is a guardrail: its failure fails the generation, typed at the boundary" in {
        case class BudgetExceeded(spent: Long)
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            val budget: Observe[Abort[BudgetExceeded]] =
                new Observe[Abort[BudgetExceeded]]:
                    def apply(ai: AI, reply: Completion.Reply)(using Frame): Unit < (LLM & Sync & Abort[BudgetExceeded]) =
                        if reply.usage.totalTokens > 50 then Abort.fail(BudgetExceeded(reply.usage.totalTokens))
                        else ()
            server.enqueueBody(resultBodyWithUsage("expensive", 100, 20)).andThen {
                Abort.run[BudgetExceeded] {
                    Abort.run[AIException](LLM.run(config)(AI.enable(budget)(AI.gen[String])))
                }.map { result =>
                    assert(result == Result.Failure(BudgetExceeded(120L)), s"got: $result")
                }
            }
        }
    }

    "a turn that aborts at the output ceiling still fires the observer" in {
        TestCompletionServer.run { server =>
            val config = Config.Anthropic.default.apiKey("test").apiUrl(server.baseUrl)
            AtomicRef.init(Chunk.empty[AIStats]).map { seen =>
                val record = Observe.init((_, reply) => seen.getAndUpdate(_.append(reply.usage)).unit)
                server.enqueueBody(
                    """{"id":"m","content":[],"model":"m","role":"assistant","stop_reason":"max_tokens","stop_sequence":null,"usage":{"input_tokens":9,"output_tokens":4}}"""
                ).andThen {
                    Abort.run[AIException](LLM.run(config)(AI.enable(record)(AI.gen[String]))).map { result =>
                        seen.get.map { usages =>
                            val ceiling = result match
                                case Result.Failure(_: AIOutputLimitException) => true
                                case _                                         => false
                            assert(ceiling, s"expected AIOutputLimitException, got: $result")
                            assert(
                                usages == Chunk(AIStats(9L, Absent, 4L, Absent, 1)),
                                s"the aborted turn's spend must be observed, got: $usages"
                            )
                        }
                    }
                }
            }
        }
    }

    "a rejected tool call fires with empty stats, the repair turn with real ones" in {
        // Config.Groq declares InvalidToolCalls.Rejected("tool_use_failed"): the 400 never reaches the loop
        // as a reply, so the observer sees the synthesized empty turn, then the repair turn's real usage.
        TestCompletionServer.run { server =>
            val config = Config.Groq.default.apiKey("test").apiUrl(server.baseUrl)
            AtomicRef.init(Chunk.empty[AIStats]).map { seen =>
                val record = Observe.init((_, reply) => seen.getAndUpdate(_.append(reply.usage)).unit)
                server.enqueueStatus(400, """{"error":{"code":"tool_use_failed","message":"bad arguments"}}""").andThen {
                    server.enqueueBody(resultBodyWithUsage("repaired", 100, 20)).andThen {
                        LLM.run(config)(AI.enable(record)(AI.gen[String])).map { result =>
                            seen.get.map { usages =>
                                assert(result == "repaired")
                                assert(
                                    usages == Chunk(AIStats.empty, usageOf100and20),
                                    s"expected the synthetic empty turn then the repair turn, got: $usages"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "withStats reports what the computation spent, alongside its result" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultBodyWithUsage("one", 100, 20)).andThen {
                server.enqueueBody(resultBodyWithUsage("two", 100, 20)).andThen {
                    LLM.run(config) {
                        Observe.withStats(AI.gen[String].map(a => AI.gen[String].map(b => (a, b))))
                    }.map { (stats, results) =>
                        assert(results == ("one", "two"))
                        assert(stats == usageOf100and20.add(usageOf100and20), s"stats: $stats")
                        assert(stats.turns == 2)
                    }
                }
            }
        }
    }

    "withStats with no generation reports empty" in {
        LLM.run(Observe.withStats(Kyo.lift(42))).map { (stats, result) =>
            assert(result == 42)
            assert(stats == AIStats.empty)
        }
    }

    "targeted withStats breaks down interleaved instances and excludes the unnamed" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            Kyo.foreachDiscard(0 until 3)(_ => server.enqueueBody(resultBodyWithUsage("r", 100, 20))).andThen {
                LLM.run(config) {
                    AI.initWith { a =>
                        AI.initWith { b =>
                            Observe.withStats {
                                Observe.withStats(a, b) {
                                    // Interleaved: a, then the unnamed one-shot, then b.
                                    a.gen[String].map(_ => AI.gen[String].map(_ => b.gen[String])).map(_ => (a, b))
                                }
                            }
                        }
                    }
                }.map { case (total, (byInstance, (a, b))) =>
                    assert(byInstance.size == 2, s"both named instances appear, got: ${byInstance.size}")
                    assert(byInstance.get(a) == Present(usageOf100and20), s"a: ${byInstance.get(a)}")
                    assert(byInstance.get(b) == Present(usageOf100and20), s"b: ${byInstance.get(b)}")
                    assert(total.turns == 3, s"the scope total counts the unnamed one-shot too, got: $total")
                    assert(total.totalTokens == 360L, s"total: $total")
                }
            }
        }
    }

    "a named instance that completed no turn appears as empty" in {
        LLM.run {
            AI.initWith { idle =>
                Observe.withStats(idle)(Kyo.lift("nothing")).map { (byInstance, result) =>
                    assert(result == "nothing")
                    assert(byInstance.get(idle) == Present(AIStats.empty), s"got: ${byInstance.get(idle)}")
                }
            }
        }
    }

    "nested brackets both see an inner turn" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultBodyWithUsage("inner", 100, 20)).andThen {
                LLM.run(config) {
                    Observe.withStats(Observe.withStats(AI.gen[String]))
                }.map { case (outer, (inner, result)) =>
                    assert(result == "inner")
                    assert(inner == usageOf100and20, s"inner: $inner")
                    assert(outer == usageOf100and20, s"outer: $outer")
                }
            }
        }
    }

    "parallel brackets are disjoint" in {
        // Both branches consume identical bodies, so whichever order the server pops them in, a bracket
        // that leaked the sibling's spend would report 240 tokens; each must report exactly its own 120.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            Kyo.foreachDiscard(0 until 2)(_ => server.enqueueBody(resultBodyWithUsage("r", 100, 20))).andThen {
                LLM.run(config) {
                    Async.zip(
                        Observe.withStats(AI.gen[String]),
                        Observe.withStats(AI.gen[String])
                    )
                }.map { case ((statsA, _), (statsB, _)) =>
                    assert(statsA == usageOf100and20, s"a: $statsA")
                    assert(statsB == usageOf100and20, s"b: $statsB")
                }
            }
        }
    }

    "a losing race branch's completed turn is counted" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultBodyWithUsage("loser", 100, 20)).andThen {
                Latch.init(1).map { genDone =>
                    Latch.init(1).map { never =>
                        // The loser completes a real generation, signals, then blocks forever; the winner
                        // waits for the signal, so the loser's turn always completes before the race ends.
                        val loser: String < (LLM & Async)  = AI.gen[String].map(r => genDone.release.andThen(never.await).andThen(r))
                        val winner: String < (LLM & Async) = genDone.await.andThen(Kyo.lift("winner"))
                        LLM.run(config) {
                            Observe.withStats(Async.race(loser, winner))
                        }.map { (stats, result) =>
                            assert(result == "winner")
                            assert(stats == usageOf100and20, s"the discarded loser's turn must be counted, got: $stats")
                        }
                    }
                }
            }
        }
    }

    "withStats survives forget: a rolled-back branch cannot un-spend" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultBodyWithUsage("rolled-back", 100, 20)).andThen {
                LLM.run(config) {
                    Observe.withStats(AI.forget(AI.gen[String]))
                }.map { (stats, result) =>
                    assert(result == "rolled-back")
                    assert(stats == usageOf100and20, s"forget must not un-spend, got: $stats")
                }
            }
        }
    }

    "a foreign run's instance named as a target panics with the cross-run error" in {
        LLM.run(AI.init).map { escaped =>
            Abort.run[AIException](LLM.run(Observe.withStats(escaped)(Kyo.lift(())))).map { result =>
                assert(result.isPanic, s"cross-run target should panic, got: $result")
            }
        }
    }

    "a streaming observer sees the conversation up to, not including, the turn" in {
        // The documented invariant, path-symmetric with generation: the fire precedes the context
        // append, so ai.context inside the callback excludes the streamed turn the reply carries.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            AtomicRef.init(-1).map { seenSize =>
                val record = Observe.init((ai, _) => ai.context.map(ctx => seenSize.set(ctx.messages.size)))
                server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"streamed\"}"))).andThen {
                    LLM.run(config) {
                        AI.initWith { ai =>
                            ai.userMessage("before").andThen {
                                AI.enable(record)(ai.stream[String].map(_.run)).andThen {
                                    ai.context.map(after => (after.messages.size))
                                }
                            }
                        }
                    }.map { afterSize =>
                        seenSize.get.map { seen =>
                            assert(seen == 1, s"the observer must see only the pre-turn message, got $seen")
                            assert(afterSize == 3, s"the turn joins the context after the fire, got $afterSize")
                        }
                    }
                }
            }
        }
    }

    "a fully consumed stream with no usage element still counts one turn" in {
        // A provider that ignores the usage request reports nothing; the turn count is structural.
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"quiet\"}"))).andThen {
                LLM.run(config) {
                    Observe.withStats(AI.stream[String].map(_.fold("")(_ + _)))
                }.map { (stats, text) =>
                    assert(text == "quiet")
                    assert(stats == AIStats(0L, Absent, 0L, Absent, 1), s"one structural turn, no tokens known; got $stats")
                }
            }
        }
    }

    "a recovered guardrail abort on a streamed turn leaves the scope env clean" in {
        // The streaming fire site runs in the consumer's frames, so a guardrail abort CAN be recovered
        // inside the run; the merged session env (config override and the guard itself) must not leak
        // into the scope. Before the any-exit restore, the second request went out with the leaked
        // "leaky-model" override and the leaked guard fired again.
        case class Tripped()
        TestCompletionServer.runStreaming { server =>
            val config = serverConfig(server.baseUrl)
            val guard: Observe[Abort[Tripped]] =
                new Observe[Abort[Tripped]]:
                    def apply(ai: AI, reply: Completion.Reply)(using Frame): Unit < (LLM & Sync & Abort[Tripped]) =
                        Abort.fail(Tripped())
            server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"first\"}"))).andThen {
                server.enqueueStream(Chunk(argDelta("{\"resultValue\":\"second\"}"))).andThen {
                    LLM.run(config) {
                        AI.init(config.modelName("leaky-model")).map { ai =>
                            ai.enable(guard).map { _ =>
                                Abort.run[Tripped](ai.stream[String].map(_.run)).map { tripped =>
                                    AI.stream[String].map(_.fold("")(_ + _)).map(second => (tripped, second))
                                }
                            }
                        }
                    }.map { (tripped, second) =>
                        server.captured.map { caps =>
                            assert(tripped == Result.Failure(Tripped()), s"the guard must fire and be recoverable: $tripped")
                            assert(second == "second", s"the run must continue cleanly, got: $second")
                            assert(caps.size == 2, s"two requests expected, got ${caps.size}")
                            assert(
                                caps(0).body.contains("\"model\":\"leaky-model\""),
                                s"the guarded instance's stream carries its override: ${caps(0).body}"
                            )
                            assert(
                                caps(1).body.contains("\"model\":\"gpt-4o\"") && !caps(1).body.contains("leaky-model"),
                                s"after recovery the scope config must be restored: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

end ObserveTest
