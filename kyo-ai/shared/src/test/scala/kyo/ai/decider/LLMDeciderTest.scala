package kyo.ai.decider

import kyo.*
import kyo.Decider.*
import kyo.ai.Config
import kyo.ai.Context.*
import kyo.ai.DeciderConfig
import kyo.schema.doc

object LLMDeciderTest:
    enum Tool derives Schema, CanEqual:
        case Shell, Database, None

    enum Handler derives Schema, CanEqual:
        @doc("Runs a shell command") case Shell
        @doc("Queries the users database") case Database
        case Browser
    end Handler
end LLMDeciderTest

/** The completion backend end to end against the fake completion endpoint: what the one generation
  * carries, how the reported probabilities become answers, the repair turn, and what the transcript holds
  * afterwards.
  */
class LLMDeciderTest extends kyo.test.Test[Any]:
    import LLMDeciderTest.*

    def config(server: TestCompletionServer): Config =
        Config.OpenAI.default
            .apiKey("test")
            .model(Config.OpenAI, "gpt-4o", 128000, Config.OutputMaximum.Verified(16384), Config.ReasoningEncoding.Unavailable, true, true)
            .apiUrl(server.baseUrl)
            .retrySchedule(Schedule.done)

    /** An OpenAI completion body whose assistant calls `result_tool` with an Answers envelope: one list of
      * (key, probability) pairs per question.
      */
    def answersBody(answers: Seq[(String, Double)]*): String =
        val weights = answers.map { ps =>
            ps.map((k, p) => s"""{"key":"$k","probability":$p}""").mkString("""{"probabilities":[""", ",", "]}")
        }.mkString("""{"resultValue":{"answers":[""", ",", "]}}")
        val escaped = Json.encode(weights)
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$escaped}}]}}],""" +
            s""""usage":{"prompt_tokens":50,"completion_tokens":5}}"""
    end answersBody

    val yes = Seq("true" -> 0.8, "false" -> 0.2)

    "choose asks for a probability per option key, with the descriptions, and takes the most probable" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("Shell" -> 0.1, "Database" -> 0.85, "None" -> 0.05))).andThen {
                LLM.run(config(server))(Decider.choose("Which tool handles the next step?", Tool.values.toSeq)).map { tool =>
                    server.captured.map { caps =>
                        assert(tool == Tool.Database)
                        assert(caps.size == 1)
                        val body = caps(0).body
                        assert(body.contains("Which tool handles the next step?"), body)
                        assert(
                            body.contains(
                                """\"keys\":[{\"key\":\"Shell\",\"meaning\":null},{\"key\":\"Database\",\"meaning\":null},{\"key\":\"None\",\"meaning\":null}]"""
                            ),
                            body
                        )
                        assert(body.contains("make each question's probabilities sum to 1"), body)
                        assert(
                            body.contains("result_tool") && body.contains("\"probabilities\""),
                            "the result tool must force the Answers shape: " + body
                        )
                    }
                }
            }
        }
    }

    "check asks for true and false and thresholds the reported probability" in {
        TestCompletionServer.run { server =>
            Kyo.foreachDiscard(0 until 2)(_ => server.enqueueBody(answersBody(yes))).andThen {
                LLM.run(config(server)) {
                    Decider.check("Is it broken?", 0.7).map(low => Decider.check("Is it broken?", 0.9).map(high => (low, high)))
                }.map { results =>
                    server.captured.map { caps =>
                        assert(results == (true, false))
                        assert(
                            caps(0).body.contains(
                                """\"keys\":[{\"key\":\"true\",\"meaning\":\"the question holds\"},{\"key\":\"false\",\"meaning\":\"the question does not hold\"}]"""
                            ),
                            caps(0).body
                        )
                    }
                }
            }
        }
    }

    "noul returns the reported probability of true, normalized" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("true" -> 0.6, "false" -> 0.2))).andThen {
                LLM.run(config(server))(Decider.noul("Is it broken?")).map(p => assert(math.abs(p - 0.75) < 1e-9))
            }
        }
    }

    "score asks for the level indices lowest first and answers the probability-weighted index" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("0" -> 0.0, "1" -> 0.7, "2" -> 0.3))).andThen {
                LLM.run(config(server))(Decider.score("How healthy?", Seq("Healthy", "Degraded", "Corrupt"))).map { score =>
                    server.captured.map { caps =>
                        assert(math.abs(score - 1.3) < 1e-9)
                        assert(
                            caps(0).body.contains(
                                """\"keys\":[{\"key\":\"0\",\"meaning\":\"Healthy\"},{\"key\":\"1\",\"meaning\":\"Degraded\"},{\"key\":\"2\",\"meaning\":\"Corrupt\"}]"""
                            ),
                            caps(0).body
                        )
                        assert(caps(0).body.contains("ordered from lowest to highest"), caps(0).body)
                    }
                }
            }
        }
    }

    "query returns the full distribution, with the confidence as one minus the normalized entropy" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("Shell" -> 0.0, "Database" -> 1.0, "None" -> 0.0)))
                .andThen(server.enqueueBody(answersBody(Seq("Shell" -> 0.5, "Database" -> 0.5, "None" -> 0.0)))).andThen {
                    LLM.run(config(server)) {
                        Decider.query(Query.choice("Which?", Tool.values.toSeq)).map { peaked =>
                            Decider.query(Query.choice("Which?", Tool.values.toSeq)).map(split => (peaked, split))
                        }
                    }.map { (peaked, split) =>
                        assert(peaked == Decision(Tool.Database, 1.0, Chunk((Tool.Shell, 0.0), (Tool.Database, 1.0), (Tool.None, 0.0))))
                        assert(split.best == Tool.Shell, "a tie goes to the first of the tied options")
                        // Two equiprobable keys out of three: entropy ln 2 over ln 3.
                        assert(math.abs(split.confidence - (1.0 - math.log(2) / math.log(3))) < 1e-9, s"confidence: ${split.confidence}")
                        assert(split.isAmbiguous)
                    }
                }
        }
    }

    "a batch is one generation for every question, answered in order" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(
                Seq("true"  -> 0.9, "false"    -> 0.1),
                Seq("Shell" -> 0.2, "Database" -> 0.3, "None" -> 0.5),
                Seq("0"     -> 1.0, "1"        -> 0.0)
            )).andThen {
                LLM.run(config(server)) {
                    Decider.batch(Query.noul("Done?"), Query.choice("Which?", Tool.values.toSeq), Query.score("How?", Seq("low", "high")))
                }.map { (done, which, how) =>
                    server.captured.map { caps =>
                        assert(caps.size == 1, "one generation for the whole batch")
                        assert(math.abs(done - 0.9) < 1e-9)
                        assert(which.best == Tool.None)
                        assert(how.level == "low" && how.value == 0.0)
                        assert(
                            caps(0).body.contains("Done?") && caps(0).body.contains("Which?") && caps(0).body.contains("How?"),
                            caps(0).body
                        )
                    }
                }
            }
        }
    }

    "a homogeneous batch of any size is one generation, and an empty one asks nothing" in {
        TestCompletionServer.run { server =>
            val queries = (1 to 20).map(i => Query.noul(s"Line $i is relevant"))
            server.enqueueBody(answersBody((1 to 20).map(i => Seq("true" -> i / 20.0, "false" -> (1 - i / 20.0)))*)).andThen {
                LLM.run(config(server))(Decider.batch(queries).map(ps => Decider.batch(Seq.empty[Query[Double]]).map(empty => (ps, empty))))
                    .map { (ps, empty) =>
                        server.captured.map { caps =>
                            assert(ps.size == 20 && math.abs(ps(19) - 1.0) < 1e-9 && math.abs(ps(0) - 0.05) < 1e-9)
                            assert(empty == Chunk.empty)
                            assert(caps.size == 1)
                        }
                    }
            }
        }
    }

    "a context rides the generation as the first user message" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("Shell" -> 1.0, "Database" -> 0.0, "None" -> 0.0))).andThen {
                LLM.run(config(server))(Decider.choose(Map("task" -> "run the tests"), "Which tool?", Tool.values.toSeq)).map { tool =>
                    server.captured.map { caps =>
                        assert(tool == Tool.Shell)
                        assert(caps(0).body.contains("""{\"task\":\"run the tests\"}"""), caps(0).body)
                    }
                }
            }
        }
    }

    "a malformed answer set gets one repair turn naming the problem, then fails" in {
        TestCompletionServer.run { server =>
            val good = answersBody(Seq("Shell" -> 0.0, "Database" -> 0.0, "None" -> 1.0))
            server.enqueueBody(answersBody(Seq("Shell" -> 0.5, "Printer" -> 0.5))).andThen(server.enqueueBody(good)).andThen {
                LLM.run(config(server))(Decider.choose("Which tool?", Tool.values.toSeq)).map { repaired =>
                    server.enqueueBody(answersBody(Seq("Shell" -> 0.5))).andThen(server.enqueueBody(answersBody(Seq(
                        "Shell"    -> 2.0,
                        "Database" -> 0.0,
                        "None"     -> 0.0
                    ))))
                        .andThen {
                            Abort.run[AIException](LLM.run(config(server))(Decider.choose("Which tool?", Tool.values.toSeq))).map {
                                failed =>
                                    server.captured.map { caps =>
                                        assert(repaired == Tool.None)
                                        assert(caps.size == 4)
                                        assert(
                                            caps(1).body.contains(
                                                "Answer 1 weighs 'Printer', which is not one of its keys: Shell, Database, None."
                                            ),
                                            caps(1).body
                                        )
                                        assert(caps(3).body.contains("Answer 1 must weigh 'Database' exactly once."), caps(3).body)
                                        assert(failed.failure.exists(e =>
                                            e.isInstanceOf[AIDecodeException] && e.getMessage.contains("probability outside [0, 1]")
                                        ))
                                    }
                            }
                        }
                }
            }
        }
    }

    "a wrong number of answers and an all-zero distribution are malformed too" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(yes, yes)).andThen(server.enqueueBody(answersBody(Seq("true" -> 0.0, "false" -> 0.0)))).andThen {
                Abort.run[AIException](LLM.run(config(server))(Decider.noul("Done?"))).map { failed =>
                    server.captured.map { caps =>
                        assert(caps(1).body.contains("Expected 1 answer(s), got 2."), caps(1).body)
                        assert(failed.failure.exists(_.getMessage.contains("gives every key a probability of 0")))
                    }
                }
            }
        }
    }

    "an instance decision leaves only the two canonical messages in the transcript" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("Shell" -> 0.05, "Database" -> 0.95, "None" -> 0.0))).andThen {
                LLM.run(config(server)) {
                    AI.initWith { ai =>
                        for
                            _    <- ai.userMessage("the last commit")
                            tool <- ai.choose("Which tool?", Tool.values.toSeq)
                            ctx  <- ai.context
                        yield (tool, ctx)
                    }
                }.map { (tool, ctx) =>
                    server.captured.map { caps =>
                        assert(tool == Tool.Database)
                        assert(ctx.messages.size == 3, s"the generation's own turn must not reach the history: ${ctx.messages}")
                        assert(ctx.messages(0) == UserMessage("the last commit", Absent))
                        assert(ctx.messages(1) == UserMessage(
                            """{"questions":[{"type":"choice","instructions":"Which tool?","criteria":{"Shell":null,"Database":null,"None":null}}]}""",
                            Absent
                        ))
                        assert(
                            ctx.messages(2).content.startsWith("""{"answers":[{"type":"choice","choice":"Database","confidence":"""),
                            ctx.messages(2).content
                        )
                        assert(
                            ctx.messages(2).content.endsWith(""""probabilities":{"Shell":0.05,"Database":0.95,"None":0.0}}]}"""),
                            ctx.messages(2).content
                        )
                        // The generation saw the conversation so far: the state is the instance's history.
                        assert(caps(0).body.contains("the last commit"), caps(0).body)
                    }
                }
            }
        }
    }

    "withStats counts the generation's real spend, and the decision record adds no turn" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("Shell" -> 1.0, "Database" -> 0.0, "None" -> 0.0))).andThen {
                LLM.run(config(server))(Observe.withStats(AI.initWith(_.choose("Which tool?", Tool.values.toSeq)))).map { (stats, tool) =>
                    assert(tool == Tool.Shell)
                    assert(stats.inputTokens == 50L && stats.outputTokens == 5L, s"stats: $stats")
                    assert(stats.turns == 1, s"one generation turn, the record itself is not a turn: $stats")
                }
            }
        }
    }

    "scope tools apply to the generation as they do to any gen" in {
        TestCompletionServer.run { server =>
            val lookup = kyo.Tool.init[Int]("lookup_order", "Looks up an order")(id => s"order $id")
            server.enqueueBody(answersBody(Seq("Shell" -> 0.0, "Database" -> 0.0, "None" -> 1.0))).andThen {
                LLM.run(config(server))(AI.enable(lookup)(Decider.choose("Which tool?", Tool.values.toSeq))).map { tool =>
                    server.captured.map { caps =>
                        assert(tool == Tool.None)
                        assert(caps(0).body.contains("lookup_order"), caps(0).body)
                    }
                }
            }
        }
    }

    "scope prompts apply to the generation as they do to any gen" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("Shell" -> 0.0, "Database" -> 0.0, "None" -> 1.0))).andThen {
                LLM.run(config(server))(AI.enable(Prompt.init("Prefer the least invasive tool."))(Decider.choose(
                    "Which tool?",
                    Tool.values.toSeq
                )))
                    .map { tool =>
                        server.captured.map { caps =>
                            assert(tool == Tool.None)
                            assert(caps(0).body.contains("Prefer the least invasive tool."), caps(0).body)
                        }
                    }
            }
        }
    }

    "documented options reach the generation with their @doc as the meaning" in {
        TestCompletionServer.run { server =>
            server.enqueueBody(answersBody(Seq("Shell" -> 0.0, "Database" -> 1.0, "Browser" -> 0.0))).andThen {
                LLM.run(config(server))(Decider.choose("Which handler?", Handler.values.toSeq)).map { handler =>
                    server.captured.map { caps =>
                        assert(handler == Handler.Database)
                        assert(
                            caps(0).body.contains(
                                """\"keys\":[{\"key\":\"Shell\",\"meaning\":\"Runs a shell command\"},{\"key\":\"Database\",\"meaning\":\"Queries the users database\"},{\"key\":\"Browser\",\"meaning\":null}]"""
                            ),
                            caps(0).body
                        )
                    }
                }
            }
        }
    }

    "an instance config override selects the backend for that instance's decisions" in {
        TestCompletionServer.run { server =>
            // The scope has no decider, the instance overrides the config with a TypeSafe decider without
            // a key: the instance's decision reaches the TypeSafe backend (and fails on the missing key
            // before any request) while a scope-level decision still goes to the config's own model.
            server.enqueueBody(answersBody(Seq("Shell" -> 1.0, "Database" -> 0.0, "None" -> 0.0))).andThen {
                val sys = System(new TestUnsafeSystem())
                System.let(sys) {
                    for
                        scoped <- LLM.run(config(server))(Decider.choose("Which tool?", Tool.values.toSeq))
                        instance <- Abort.run[AIException](LLM.run(config(server)) {
                            AI.init(config(server).decider(DeciderConfig.TypeSafe.default)).map(_.choose("Which tool?", Tool.values.toSeq))
                        })
                        caps <- server.captured
                    yield
                        assert(scoped == Tool.Shell)
                        assert(instance.failure.exists(_.isInstanceOf[AIMissingApiKeyException]), s"instance: $instance")
                        assert(caps.size == 1)
                    end for
                }
            }
        }
    }

    private class TestUnsafeSystem extends System.Unsafe:
        def env(name: String)(using AllowUnsafe): Maybe[String]      = Absent
        def property(name: String)(using AllowUnsafe): Maybe[String] = Absent
        def lineSeparator()(using AllowUnsafe): String               = "\n"
        def userName()(using AllowUnsafe): String                    = "test"
        def operatingSystem()(using AllowUnsafe): System.OS          = System.OS.Unknown
        def architecture()(using AllowUnsafe): System.Arch           = System.Arch.Unknown
        def availableProcessors()(using AllowUnsafe): Int            = 1
    end TestUnsafeSystem

end LLMDeciderTest
