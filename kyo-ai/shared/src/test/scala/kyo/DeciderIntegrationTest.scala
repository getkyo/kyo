package kyo

import kyo.Decider.*
import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.DeciderConfig
import kyo.ai.completion.Completion

object DeciderIntegrationTest:
    enum Tool derives Schema, CanEqual:
        case Shell, Database, None

    case class Fs(manifest: Chunk[String], lastWrite: String) derives Schema, CanEqual

    case class Summary(cause: String) derives Schema, CanEqual
end DeciderIntegrationTest

/** The public surface end to end against the fake TypeSafe endpoint: every overload, what the request
  * carries, what is recorded where, and what observers see.
  */
class DeciderIntegrationTest extends kyo.test.Test[Any]:
    import DeciderIntegrationTest.*

    def config(server: TestDeciderServer): Config =
        Config.Anthropic.default.apiKey("unused").retrySchedule(Schedule.done)
            .decider(DeciderConfig.TypeSafe.default.apiKey("test-key").apiUrl(server.baseUrl))

    def completionConfig(completion: TestCompletionServer, decider: TestDeciderServer): Config =
        Config.OpenAI.default
            .apiKey("test")
            .model(Config.OpenAI, "gpt-4o", 128000, Config.OutputMaximum.Verified(16384), Config.ReasoningEncoding.Unavailable, true, true)
            .apiUrl(completion.baseUrl)
            .retrySchedule(Schedule.done)
            .decider(DeciderConfig.TypeSafe.default.apiKey("test-key").apiUrl(decider.baseUrl))

    val fs = Fs(Chunk("a.txt", "b.txt"), "b.txt")

    def body(answers: String, inputTokens: Int = 10, outputTokens: Int = 2): String =
        s"""{"model":"jev-1.13.0","answers":{$answers},"usage":{"input_tokens":$inputTokens,"output_tokens":$outputTokens}}"""

    val noulAnswer   = """"q1":{"type":"noul","noul":0.93}"""
    val choiceAnswer =
        """"q1":{"type":"choice","choice":"Database","confidence":0.9,"probabilities":{"Shell":0.05,"Database":0.95,"None":0.0}}"""
    val scoreAnswer = """"q1":{"type":"score","score":1.3,"confidence":0.8,"legend":{},"probabilities":{"0":0.0,"1":0.7,"2":0.3}}"""

    "one-shot" - {
        "check thresholds the noul, at 0.5 by default" in {
            TestDeciderServer.run { server =>
                Kyo.foreachDiscard(0 until 4)(_ => server.enqueueBody(body(noulAnswer))).andThen {
                    LLM.run(config(server)) {
                        for
                            a <- Decider.check("Is it broken?")
                            b <- Decider.check("Is it broken?", 0.95)
                            c <- Decider.check(fs, "Is it broken?")
                            d <- Decider.check(fs, "Is it broken?", 0.93)
                        yield (a, b, c, d)
                    }.map { results =>
                        server.captured.map { bodies =>
                            assert(results == (true, false, true, true))
                            assert(bodies(0).contains(""""state":"""""), s"no context sends the empty string: ${bodies(0)}")
                            assert(
                                bodies(2).contains(
                                    """"state":[{"role":"user","content":"{\"manifest\":[\"a.txt\",\"b.txt\"],\"lastWrite\":\"b.txt\"}"}]"""
                                ),
                                s"a context is the one-shot's user message: ${bodies(2)}"
                            )
                            assert(bodies(2).contains(""""q1":{"type":"noul","instructions":"Is it broken?"}"""), bodies(2))
                        }
                    }
                }
            }
        }
        "choose returns the option named by the answer, with enum keys on the wire" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(choiceAnswer)).andThen(server.enqueueBody(body(choiceAnswer))).andThen {
                    LLM.run(config(server)) {
                        Decider.choose("Which tool?", Tool.values.toSeq).map { a =>
                            Decider.choose(fs, "Which tool?", Tool.values.toSeq).map(b => (a, b))
                        }
                    }.map { results =>
                        server.captured.map { bodies =>
                            assert(results == (Tool.Database, Tool.Database))
                            assert(bodies(0).contains(""""criteria":{"Shell":null,"Database":null,"None":null}"""), bodies(0))
                        }
                    }
                }
            }
        }
        "score returns the position, with the levels as an array" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(scoreAnswer)).andThen(server.enqueueBody(body(scoreAnswer))).andThen {
                    LLM.run(config(server)) {
                        Decider.score("How healthy?", Seq("Healthy", "Degraded", "Corrupt")).map { a =>
                            Decider.score(fs, "How healthy?", Seq("Healthy", "Degraded", "Corrupt")).map(b => (a, b))
                        }
                    }.map { results =>
                        server.captured.map { bodies =>
                            assert(results == (1.3, 1.3))
                            assert(bodies(1).contains(""""criteria":["Healthy","Degraded","Corrupt"]"""), bodies(1))
                        }
                    }
                }
            }
        }
        "noul returns the probability" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(noulAnswer)).andThen(server.enqueueBody(body(noulAnswer))).andThen {
                    LLM.run(config(server)) {
                        Decider.noul("Is it broken?").map(a => Decider.noul(fs, "Is it broken?").map(b => (a, b)))
                    }.map(results => assert(results == (0.93, 0.93)))
                }
            }
        }
        "query returns the full answer" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(choiceAnswer)).andThen(server.enqueueBody(body(scoreAnswer))).andThen {
                    LLM.run(config(server)) {
                        Decider.query(Query.choice("Which tool?", Tool.values.toSeq)).map { a =>
                            Decider.query(fs, Query.score("How healthy?", Seq("Healthy", "Degraded", "Corrupt"))).map(b => (a, b))
                        }
                    }.map { (decision, score) =>
                        assert(decision == Decision(Tool.Database, 0.9, Chunk((Tool.Shell, 0.05), (Tool.Database, 0.95), (Tool.None, 0.0))))
                        assert(score == Score(1.3, "Degraded", 0.8, Chunk(("Healthy", 0.0), ("Degraded", 0.7), ("Corrupt", 0.3))))
                    }
                }
            }
        }
        "batch sends every question in one request and answers in order, at every arity" in {
            TestDeciderServer.run { server =>
                val q1   = Query.noul("Done?")
                val q2   = Query.choice("Which tool?", Tool.values.toSeq)
                val q3   = Query.score("How healthy?", Seq("Healthy", "Degraded", "Corrupt"))
                val q4   = Query.noul("Corrupt?", "manifest and tree disagree", "they agree")
                val four = body(
                    """"q1":{"type":"noul","noul":0.1},""" +
                        """"q2":{"type":"choice","choice":"Shell","confidence":0.5,"probabilities":{"Shell":0.6,"Database":0.4,"None":0.0}},""" +
                        """"q3":{"type":"score","score":2.0,"confidence":1.0,"probabilities":{"0":0.0,"1":0.0,"2":1.0}},""" +
                        """"q4":{"type":"noul","noul":0.2}"""
                )
                Kyo.foreachDiscard(0 until 6)(_ => server.enqueueBody(four)).andThen {
                    LLM.run(config(server)) {
                        for
                            r2 <- Decider.batch(q1, q2)
                            r3 <- Decider.batch(q1, q2, q3)
                            r4 <- Decider.batch(q1, q2, q3, q4)
                            c2 <- Decider.batch(fs, q1, q2)
                            c3 <- Decider.batch(fs, q1, q2, q3)
                            c4 <- Decider.batch(fs, q1, q2, q3, q4)
                        yield (r2, r3, r4, c2, c3, c4)
                    }.map { (r2, r3, r4, c2, c3, c4) =>
                        server.captured.map { bodies =>
                            val decision = Decision(Tool.Shell, 0.5, Chunk((Tool.Shell, 0.6), (Tool.Database, 0.4), (Tool.None, 0.0)))
                            val score    = Score(2.0, "Corrupt", 1.0, Chunk(("Healthy", 0.0), ("Degraded", 0.0), ("Corrupt", 1.0)))
                            assert(r2 == (0.1, decision))
                            assert(r3 == (0.1, decision, score))
                            assert(r4 == (0.1, decision, score, 0.2))
                            assert(c2 == r2 && c3 == r3 && c4 == r4)
                            assert(bodies.size == 6)
                            assert(
                                bodies(2).contains(
                                    """"q4":{"type":"noul","instructions":"Corrupt?","criteria":{"true":"manifest and tree disagree","false":"they agree"}}"""
                                ),
                                bodies(2)
                            )
                            assert(bodies(5).contains(""""state":[{"role":"user","content":"{\"manifest\":"""), bodies(5))
                        }
                    }
                }
            }
        }
        "records nothing and leaves no instance behind" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(noulAnswer)).andThen {
                    LLM.runWith(LLM.State.empty(config(server)))(Decider.check(fs, "Is it broken?"))((s, a) => (s, a)).map {
                        (state, result) =>
                            assert(result)
                            assert(state.instances.isEmpty, s"a one-shot must discard its instance, found ${state.instances.size}")
                    }
                }
            }
        }
        "a bad question or threshold fails before any request" in {
            TestDeciderServer.run { server =>
                // A decision's failures ride LLM.run's residual, like a generation's, so they are recovered outside it.
                val cfg = config(server)
                for
                    empty  <- Abort.run[AIException](LLM.run(cfg)(Decider.choose("Which?", Seq.empty[Tool])))
                    one    <- Abort.run[AIException](LLM.run(cfg)(Decider.score("How?", Seq("only"))))
                    dup    <- Abort.run[AIException](LLM.run(cfg)(Decider.choose("Which?", Seq("dup", "dup"))))
                    high   <- Abort.run[AIException](LLM.run(cfg)(Decider.check("Is it?", 1.5)))
                    low    <- Abort.run[AIException](LLM.run(cfg)(Decider.check(fs, "Is it?", -0.5)))
                    bodies <- server.captured
                yield
                    assert(empty.failure.exists(_.isInstanceOf[AIInvalidQuestionException]))
                    assert(one.failure.exists(_.isInstanceOf[AIInvalidQuestionException]))
                    assert(dup.failure.exists(_.getMessage.contains("share the key 'dup'")))
                    assert(high.failure.exists(_.getMessage.contains("within [0, 1], got 1.5")))
                    assert(low.failure.exists(_.getMessage.contains("within [0, 1], got -0.5")))
                    assert(bodies.isEmpty)
                end for
            }
        }
        "withStats counts a one-shot's spend as one turn" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(noulAnswer, 300, 20)).andThen {
                    LLM.run(config(server))(Observe.withStats(Decider.check(fs, "Is it broken?"))).map { (stats, result) =>
                        assert(result)
                        assert(stats == AIStats(300L, Absent, 20L, Absent, 1), s"a one-shot's spend must reach the observers: $stats")
                    }
                }
            }
        }
        "a homogeneous batch of any size is one request, answered in order" in {
            TestDeciderServer.run { server =>
                val n       = 200
                val queries = (1 to n).map(i => Query.noul(s"Line $i is relevant"))
                val answers = (1 to n).map(i => s""""q$i":{"type":"noul","noul":${i.toDouble / n}}""").mkString(",")
                server.enqueueBody(body(answers)).andThen(server.enqueueBody(body(answers))).andThen {
                    LLM.run(config(server)) {
                        Decider.batch(queries).map(plain => Decider.batch(fs, queries).map(withContext => (plain, withContext)))
                    }.map { (plain, withContext) =>
                        server.captured.map { bodies =>
                            assert(plain.size == n)
                            assert(plain(0) == 1.0 / n && plain(n - 1) == 1.0)
                            assert(withContext == plain)
                            assert(bodies.size == 2)
                            assert(
                                bodies(0).contains(s""""q$n":{"type":"noul","instructions":"Line $n is relevant"}"""),
                                bodies(0).takeRight(200)
                            )
                            assert(bodies(1).contains(""""state":[{"role":"user","content":"{\"manifest\":"""), bodies(1).take(200))
                        }
                    }
                }
            }
        }
        "a scope prompt is part of the state, around the one-shot's context" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(noulAnswer)).andThen(server.enqueueBody(body(noulAnswer))).andThen {
                    LLM.run(config(server)) {
                        AI.enable(Prompt.init("Judge strictly.", "Answer from the state only.")) {
                            Decider.check(fs, "Is it broken?").map(a => Decider.check("Is it broken?").map(b => (a, b)))
                        }
                    }.map { results =>
                        server.captured.map { bodies =>
                            assert(results == (true, true))
                            assert(
                                bodies(0).contains(
                                    """"state":[{"role":"system","content":"Judge strictly."},{"role":"user","content":"{\"manifest\":[\"a.txt\",\"b.txt\"],\"lastWrite\":\"b.txt\"}"},{"role":"system","content":"""
                                ),
                                bodies(0)
                            )
                            assert(bodies(0).contains("Answer from the state only."), bodies(0))
                            assert(bodies(1).contains(""""state":[{"role":"system","content":"Judge strictly."}"""), bodies(1))
                        }
                    }
                }
            }
        }
        "a decoding failure surfaces as AIDecodeException" in {
            TestDeciderServer.run { server =>
                val unknown = body(""""q1":{"type":"choice","choice":"Browser","confidence":1.0,"probabilities":{"Browser":1.0}}""")
                server.enqueueBody(unknown).andThen {
                    Abort.run[AIException](LLM.run(config(server))(Decider.choose("Which?", Tool.values.toSeq))).map { result =>
                        assert(result.failure.exists(e =>
                            e.isInstanceOf[AIDecodeException] && e.getMessage.contains("unknown option 'Browser'")
                        ))
                    }
                }
            }
        }
        "the decider switches inside a run with AI.withConfig: the config's own model without one, TypeSafe with one" in {
            TestDeciderServer.run { decider =>
                TestCompletionServer.run { completion =>
                    val weights =
                        """{"resultValue":{"answers":[{"probabilities":[{"key":"true","probability":0.7},{"key":"false","probability":0.3}]}]}}"""
                    val completionBody =
                        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":${Json.encode(
                                weights
                            )}}}]}}]}"""
                    completion.enqueueBody(completionBody).andThen(decider.enqueueBody(body(noulAnswer))).andThen {
                        LLM.run(completionConfig(completion, decider).decider(Absent)) {
                            Decider.noul("Is it?").map { own =>
                                AI.withConfig(_.decider(DeciderConfig.TypeSafe.default.apiKey("k").apiUrl(decider.baseUrl))) {
                                    Decider.noul("Is it?")
                                }.map(typeSafe => (own, typeSafe))
                            }
                        }.map { (own, typeSafe) =>
                            assert(own == 0.7)
                            assert(typeSafe == 0.93)
                        }
                    }
                }
            }
        }
    }

    "instance" - {
        "the conversation is the state and the exchange is recorded as two messages, after the observers fired" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(choiceAnswer, 392, 66)).andThen {
                    AtomicRef.init(Chunk.empty[(Chunk[Message], Completion.Reply)]).map { seen =>
                        val observer = Observe.init[Any] { (ai, reply) =>
                            ai.context.map(ctx => seen.getAndUpdate(_.append((ctx.messages, reply))).unit)
                        }
                        LLM.run(config(server)) {
                            AI.enable(observer) {
                                AI.initWith { ai =>
                                    for
                                        _      <- ai.userMessage("list which rows of the users table changed")
                                        _      <- ai.assistantMessage("Which commit?")
                                        _      <- ai.userMessage("the last one")
                                        tool   <- ai.choose("Which tool handles the next step?", Tool.values.toSeq)
                                        ctx    <- ai.context
                                        events <- seen.get
                                    yield (tool, ctx, events)
                                }
                            }
                        }.map { case (tool, ctx, events) =>
                            server.captured.map { bodies =>
                                assert(tool == Tool.Database)
                                assert(
                                    bodies(0).contains(
                                        """"state":[{"role":"user","content":"list which rows of the users table changed"},{"role":"assistant","content":"Which commit?"},{"role":"user","content":"the last one"}]"""
                                    ),
                                    bodies(0)
                                )
                                val expectedQuestion =
                                    """{"questions":[{"type":"choice","instructions":"Which tool handles the next step?","criteria":{"Shell":null,"Database":null,"None":null}}]}"""
                                val expectedAnswer =
                                    """{"answers":[{"type":"choice","choice":"Database","confidence":0.9,"probabilities":{"Shell":0.05,"Database":0.95,"None":0.0}}]}"""
                                assert(ctx.messages.size == 5, s"expected the 3 turns plus 2 recorded messages, got ${ctx.messages.size}")
                                assert(ctx.messages(3) == UserMessage(expectedQuestion, Absent))
                                assert(ctx.messages(4) == AssistantMessage(expectedAnswer))
                                assert(events.size == 1)
                                val (atCallback, reply) = events(0)
                                assert(atCallback.size == 3, "an observer sees the conversation up to the decision, not the decision")
                                assert(reply.messages == Chunk(UserMessage(expectedQuestion, Absent), AssistantMessage(expectedAnswer)))
                                assert(reply.usage == AIStats(392L, Absent, 66L, Absent, 1))
                            }
                        }
                    }
                }
            }
        }
        "every instance overload records and returns like its one-shot form" in {
            TestDeciderServer.run { server =>
                val q1  = Query.noul("Done?")
                val q2  = Query.choice("Which tool?", Tool.values.toSeq)
                val two = body(
                    """"q1":{"type":"noul","noul":0.1},""" +
                        """"q2":{"type":"choice","choice":"Shell","confidence":0.5,"probabilities":{"Shell":0.6,"Database":0.4,"None":0.0}}"""
                )
                val three = body(two.substring(
                    two.indexOf("\"q1\""),
                    two.indexOf(""","usage"""") - 1
                ) + ""","q3":{"type":"score","score":2.0,"confidence":1.0,"probabilities":{"0":0.0,"1":0.0,"2":1.0}}""")
                val four = body(three.substring(
                    three.indexOf("\"q1\""),
                    three.indexOf(""","usage"""") - 1
                ) + ""","q4":{"type":"noul","noul":0.2}""")
                val q3 = Query.score("How healthy?", Seq("Healthy", "Degraded", "Corrupt"))
                val q4 = Query.noul("Corrupt?")
                server.enqueueBody(body(noulAnswer)).andThen(server.enqueueBody(body(noulAnswer)))
                    .andThen(server.enqueueBody(body(choiceAnswer))).andThen(server.enqueueBody(body(scoreAnswer)))
                    .andThen(server.enqueueBody(body(noulAnswer))).andThen(server.enqueueBody(body(choiceAnswer)))
                    .andThen(server.enqueueBody(two)).andThen(server.enqueueBody(three)).andThen(server.enqueueBody(four)).andThen {
                        LLM.run(config(server)) {
                            AI.initWith { ai =>
                                for
                                    a   <- ai.check("Is it broken?")
                                    b   <- ai.check("Is it broken?", 0.95)
                                    c   <- ai.choose("Which tool?", Tool.values.toSeq)
                                    d   <- ai.score("How healthy?", Seq("Healthy", "Degraded", "Corrupt"))
                                    e   <- ai.noul("Is it broken?")
                                    f   <- ai.query(Query.choice("Which tool?", Tool.values.toSeq))
                                    g   <- ai.batch(q1, q2)
                                    h   <- ai.batch(q1, q2, q3)
                                    i   <- ai.batch(q1, q2, q3, q4)
                                    ctx <- ai.context
                                yield (a, b, c, d, e, f, g, h, i, ctx)
                            }
                        }.map { (a, b, c, d, e, f, g, h, i, ctx) =>
                            val decision = Decision(Tool.Shell, 0.5, Chunk((Tool.Shell, 0.6), (Tool.Database, 0.4), (Tool.None, 0.0)))
                            val score    = Score(2.0, "Corrupt", 1.0, Chunk(("Healthy", 0.0), ("Degraded", 0.0), ("Corrupt", 1.0)))
                            assert(a && !b)
                            assert(c == Tool.Database)
                            assert(d == 1.3)
                            assert(e == 0.93)
                            assert(f == Decision(Tool.Database, 0.9, Chunk((Tool.Shell, 0.05), (Tool.Database, 0.95), (Tool.None, 0.0))))
                            assert(g == (0.1, decision))
                            assert(h == (0.1, decision, score))
                            assert(i == (0.1, decision, score, 0.2))
                            assert(ctx.messages.size == 18, s"9 decisions record 18 messages, got ${ctx.messages.size}")
                            assert(ctx.messages.zipWithIndex.forall((m, idx) =>
                                if idx % 2 == 0 then m.role == Role.User else m.role == Role.Assistant
                            ))
                        }
                    }
            }
        }
        "a later generation carries the recorded decision" in {
            TestCompletionServer.run { completion =>
                TestDeciderServer.run { decider =>
                    val resultBody =
                        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":"{\"resultValue\":\"ok\"}"}}]}}]}"""
                    decider.enqueueBody(body(choiceAnswer)).andThen(completion.enqueueBody(resultBody)).andThen {
                        LLM.run(completionConfig(completion, decider)) {
                            AI.initWith { ai =>
                                ai.userMessage("the last commit").andThen(ai.choose(
                                    "Which tool?",
                                    Tool.values.toSeq
                                )).andThen(ai.gen[String])
                            }
                        }.map { result =>
                            completion.captured.map { caps =>
                                assert(result == "ok")
                                assert(caps.size == 1)
                                assert(caps(0).body.contains("Which tool?") && caps(0).body.contains("Database"), caps(0).body)
                            }
                        }
                    }
                }
            }
        }
        "withStats counts a decision's spend as one turn" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(body(noulAnswer, 300, 20)).andThen(server.enqueueBody(body(noulAnswer, 100, 5))).andThen {
                    LLM.run(config(server)) {
                        Observe.withStats(AI.initWith(ai => ai.check("a").map(_ => ai.check("b"))))
                    }.map { (stats, result) =>
                        assert(result)
                        assert(stats == AIStats(400L, Absent, 25L, Absent, 2), s"stats: $stats")
                    }
                }
            }
        }
        "a generated answer and a tool call are part of the state a later decision sees" in {
            TestCompletionServer.run { completion =>
                TestDeciderServer.run { decider =>
                    val resultBody =
                        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":"{\"resultValue\":{\"cause\":\"disk full\"}}"}}]}}]}"""
                    completion.enqueueBody(resultBody).andThen(decider.enqueueBody(body(noulAnswer))).andThen {
                        LLM.run(completionConfig(completion, decider)) {
                            AI.initWith { ai =>
                                for
                                    _ <- ai.userMessage("summarise the incident")
                                    _ <- ai.gen[Summary]
                                    _ <- ai.updateContext(_.assistantMessage(
                                        "checking",
                                        Chunk(Call(CallId("c2"), "lookup_order", """{"id":"A-1"}"""))
                                    ))
                                    _ <- ai.updateContext(_.toolMessage(CallId("c2"), "shipped"))
                                    r <- ai.check("The summary names a root cause")
                                yield r
                            }
                        }.map { (result: Boolean) =>
                            decider.captured.map { bodies =>
                                assert(result)
                                val state = bodies(0)
                                assert(
                                    state.contains(
                                        """{"role":"assistant","content":"","calls":[{"id":"r1","function":"result_tool","arguments":"{\"resultValue\":{\"cause\":\"disk full\"}}"}]}"""
                                    ),
                                    state
                                )
                                assert(state.contains("""{"role":"tool","content":"\"Result received.\"","callId":"r1"}"""), state)
                                assert(
                                    state.contains(
                                        """{"role":"assistant","content":"checking","calls":[{"id":"c2","function":"lookup_order","arguments":"{\"id\":\"A-1\"}"}]}"""
                                    ),
                                    state
                                )
                                assert(state.contains("""{"role":"tool","content":"shipped","callId":"c2"}"""), state)
                            }
                        }
                    }
                }
            }
        }
        "an instance config override governs its decisions' deadline" in {
            TestDeciderServer.run { server =>
                // The scope allows an hour; the instance overrides to 10 seconds. The instance's decision
                // must fail at the instance's deadline, which the scope's would not have reached.
                val scope    = config(server).timeout(1.hour)
                val instance = config(server).timeout(10.seconds)
                Clock.withTimeControl { control =>
                    server.enqueueNeverRespond.andThen {
                        for
                            fiber  <- Fiber.init(Abort.run[AIException](LLM.run(scope)(AI.init(instance).map(_.check("Is it?")))))
                            _      <- awaitCaptured(server, control, 1)
                            _      <- control.advance(11.seconds, 500.millis)
                            result <- fiber.get
                        yield result match
                            case Result.Failure(e: AICompletionTimeoutException) => assert(e.timeout == 10.seconds)
                            case other => fail(s"expected the instance's 10 second deadline, got: $other")
                    }
                }
            }
        }
        "an instance config override governs its decisions' retry schedule" in {
            TestDeciderServer.run { server =>
                // The scope would retry twice; the instance says no retries. One request.
                val scope    = config(server).retrySchedule(Schedule.repeat(2))
                val instance = config(server).retrySchedule(Schedule.done)
                server.enqueueStatus(500, "down").andThen {
                    Abort.run[AIException](LLM.run(scope)(AI.init(instance).map(_.check("Is it?")))).map { result =>
                        server.captured.map { bodies =>
                            assert(result.failure.exists(_.isInstanceOf[AIProviderUnavailableException]), s"result: $result")
                            assert(bodies.size == 1, "the instance's schedule (no retries) must govern; the scope's would have retried")
                        }
                    }
                }
            }
        }
        "an observer firing for an instance's decision reads the instance config" in {
            TestDeciderServer.run { server =>
                val instance = config(server).temperature(0.3)
                AtomicRef.init(Chunk.empty[Config]).map { seen =>
                    val observer = Observe.init[Any]((_, _) => AI.config.map(c => seen.getAndUpdate(_.append(c)).unit))
                    server.enqueueBody(body(noulAnswer)).andThen {
                        LLM.run(config(server))(AI.enable(observer)(AI.init(instance).map(_.check("Is it?")))).map { result =>
                            seen.get.map { configs =>
                                assert(result)
                                assert(configs.size == 1 && configs(0).temperature == Present(0.3), "an observer reads the instance config")
                            }
                        }
                    }
                }
            }
        }
        "an instance batch of any size records one exchange" in {
            TestDeciderServer.run { server =>
                val queries = (1 to 5).map(i => Query.noul(s"Point $i holds"))
                val answers = (1 to 5).map(i => s""""q$i":{"type":"noul","noul":0.$i}""").mkString(",")
                server.enqueueBody(body(answers)).andThen {
                    LLM.run(config(server)) {
                        AI.initWith(ai => ai.batch(queries).map(rs => ai.context.map(ctx => (rs, ctx))))
                    }.map { (rs, ctx) =>
                        assert(rs == Chunk(0.1, 0.2, 0.3, 0.4, 0.5))
                        assert(ctx.messages.size == 2)
                        assert(ctx.messages(0).content.contains("Point 5 holds"))
                        assert(
                            ctx.messages(1).content ==
                                """{"answers":[{"type":"noul","noul":0.1},{"type":"noul","noul":0.2},{"type":"noul","noul":0.3},{"type":"noul","noul":0.4},{"type":"noul","noul":0.5}]}"""
                        )
                    }
                }
            }
        }
        "forget rolls a recorded decision back, and a snapshot recovers one" in {
            TestDeciderServer.run { server =>
                Kyo.foreachDiscard(0 until 2)(_ => server.enqueueBody(body(noulAnswer))).andThen {
                    LLM.run(config(server)) {
                        AI.initWith { ai =>
                            for
                                _           <- ai.userMessage("hello")
                                _           <- AI.forget(ai)(ai.check("forgotten?"))
                                afterForget <- ai.context
                                _           <- ai.check("kept?")
                                session     <- ai.snapshot
                                recovered   <- AI.recover(session)
                                ctx         <- recovered.context
                            yield (afterForget, ctx)
                        }
                    }.map { (afterForget, ctx) =>
                        assert(afterForget.messages.size == 1, s"forget must drop the recorded decision, got ${afterForget.messages.size}")
                        assert(ctx.messages.size == 3)
                        assert(ctx.messages(1).content.contains("kept?"))
                        assert(ctx.messages(2).content.contains(""""noul":0.93"""))
                    }
                }
            }
        }
        "an instance from another run fails the cross-run guard" in {
            TestDeciderServer.run { server =>
                LLM.run(config(server))(AI.init).map { foreign =>
                    Abort.run[AIException](LLM.run(config(server))(foreign.check("Is it?"))).map { result =>
                        result match
                            case Result.Panic(_: AICrossRunException) => succeed
                            case other                                => fail(s"expected an AICrossRunException panic, got: $other")
                    }
                }
            }
        }
    }

    /** Waits until the server has captured at least `n` requests, advancing only real wall-clock time
      * (never virtual time) under the controlled Clock, so a leaf can defer advancing past the deadline
      * until the request is genuinely in flight rather than racing a fixed millisecond budget for it to
      * reach the server. The pass condition of every test using it is the virtual advance that follows.
      */
    private def awaitCaptured(server: TestDeciderServer, control: Clock.TimeControl, n: Int)(using Frame): Unit < Async =
        Loop(0) { i =>
            server.captured.flatMap { caps =>
                if caps.size >= n then Loop.done
                else if i >= 200 then Loop.done
                else control.advance(Duration.Zero, 20.millis).andThen(Loop.continue(i + 1))
            }
        }

end DeciderIntegrationTest
