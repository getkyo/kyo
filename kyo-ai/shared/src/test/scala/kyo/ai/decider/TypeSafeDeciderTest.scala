package kyo.ai.decider

import kyo.*
import kyo.Decider.internal.*
import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.DeciderConfig
import kyo.ai.completion.Completion

class TypeSafeDeciderTest extends kyo.test.Test[Any]:

    val str = Structure.Value.Str

    def llmConfig: Config = Config.Anthropic.default.apiKey("unused").retrySchedule(Schedule.done)

    def deciderConfig(server: TestDeciderServer): DeciderConfig =
        DeciderConfig.TypeSafe.default.apiKey("test-key").apiUrl(server.baseUrl)

    // Runs one backend call as the glue would: the effective config carries the decider, the state is
    // the conversation given.
    def decide(config: Config, decider: DeciderConfig, context: Context, questions: Chunk[Question])(using Frame) =
        val effective = config.decider(decider)
        LLM.run(effective) {
            AI.init.map(_ => TypeSafeDecider.decide(effective, decider, context, questions))
        }
    end decide

    val noul   = Question.Noul(str("Is the customer asking for a human agent?"), Absent, Absent, Absent)
    val choice = Question.Choice(str("Which team?"), Chunk(("billing", str("Charges")), ("orders", str("Delivery"))))
    val score  = Question.Score(str("How frustrated?"), Chunk(str("Calm"), str("Frustrated"), str("Very angry")))

    // The body verified against the live endpoint (design doc, Appendix A.1), with the three ids this
    // backend assigns, plus a field the DTOs do not model.
    val liveBody =
        """{"model":"jev-1.13.0","answers":{""" +
            """"q1":{"type":"noul","noul":0.15},""" +
            """"q2":{"type":"choice","choice":"orders","confidence":0.44,"probabilities":{"billing":0.28,"orders":0.72}},""" +
            """"q3":{"type":"score","score":0.98,"confidence":0.97,"legend":{"0":"Calm","1":"Frustrated","2":"Very angry"},"probabilities":{"0":0.02,"1":0.98,"2":0.0}}},""" +
            """"usage":{"input_tokens":392,"output_tokens":66},"future":{"field":true}}"""

    "request shape" - {
        "every question kind, positional ids, criteria in wire shape, never a threshold" in {
            val withCriteria =
                Question.Noul(
                    str("Repeat contact?"),
                    Present(str("mentions a prior ticket")),
                    Present(str("no sign of one")),
                    Absent
                )
            val picked = Question.Noul(str("Done?"), Absent, Absent, Present(0.9))
            val body   = Json.encode(TypeSafeDecider.request("jev-latest", str("hello"), Chunk(noul, withCriteria, choice, score, picked)))
            val expected =
                """{"model":"jev-latest","state":"hello","questions":{""" +
                    """"q1":{"type":"noul","instructions":"Is the customer asking for a human agent?"},""" +
                    """"q2":{"type":"noul","instructions":"Repeat contact?","criteria":{"true":"mentions a prior ticket","false":"no sign of one"}},""" +
                    """"q3":{"type":"choice","instructions":"Which team?","criteria":{"billing":"Charges","orders":"Delivery"}},""" +
                    """"q4":{"type":"score","instructions":"How frustrated?","criteria":["Calm","Frustrated","Very angry"]},""" +
                    """"q5":{"type":"noul","instructions":"Done?"}}}"""
            assert(body == expected)
        }
        "null descriptions and object descriptions ride the criteria as given" in {
            val q = Question.Choice(
                str("Which tool?"),
                Chunk(("Shell", Structure.Value.Null), ("c1", Structure.Value.Record(Chunk(("name", str("db"))))))
            )
            val body = Json.encode(TypeSafeDecider.request("jev-latest", str(""), Chunk(q)))
            assert(body.contains(""""criteria":{"Shell":null,"c1":{"name":"db"}}"""))
        }
        "an object state and structured instructions are sent as JSON, not strings" in {
            val state        = Structure.Value.Record(Chunk(("manifest", Structure.Value.Sequence(Chunk(str("a"), str("b"))))))
            val instructions = Structure.Value.Record(Chunk(("question", str("Corrupted?")), ("focus", str("the manifest"))))
            val body = Json.encode(TypeSafeDecider.request(
                "jev-latest",
                state,
                Chunk(Question.Noul(instructions, Absent, Absent, Absent))
            ))
            assert(
                body == """{"model":"jev-latest","state":{"manifest":["a","b"]},"questions":{"q1":{"type":"noul","instructions":{"question":"Corrupted?","focus":"the manifest"}}}}"""
            )
        }
    }

    "state encoding" - {
        def state(ctx: Context)                            = TypeSafeDecider.stateOf(ctx)
        def record(role: String, content: Structure.Value) = Structure.Value.Record(Chunk(("role", str(role)), ("content", content)))

        "an empty conversation sends the empty string" in {
            assert(state(Context.empty) == str(""))
        }
        "a single user message is a one-record chat log, its text as given" in {
            val value = Structure.Value.Record(Chunk(("task", str("list rows")), ("n", Structure.Value.Integer(3L))))
            assert(state(Context.empty.userMessage(Json.encode(value))) ==
                Structure.Value.Sequence(Chunk(record("user", str("""{"task":"list rows","n":3}""")))))
            assert(state(Context.empty.userMessage("42")) == Structure.Value.Sequence(Chunk(record("user", str("42")))))
            assert(state(Context.empty.userMessage("null")) == Structure.Value.Sequence(Chunk(record("user", str("null")))))
        }
        "a user's own text is sent as text, whatever it looks like" in {
            assert(state(Context.empty.userMessage("42").assistantMessage("ok")) ==
                Structure.Value.Sequence(Chunk(record("user", str("42")), record("assistant", str("ok")))))
            assert(state(Context.empty.userMessage("null").assistantMessage("ok")) ==
                Structure.Value.Sequence(Chunk(record("user", str("null")), record("assistant", str("ok")))))
        }
        "a prompt around a user message rides the chat log as a system record" in {
            val ctx = Context.empty.systemMessage("be brief").userMessage("hi")
            assert(state(ctx) == Structure.Value.Sequence(Chunk(record("system", str("be brief")), record("user", str("hi")))))
        }
        "a longer conversation sends a chat log of role and content" in {
            val ctx = Context.empty.userMessage("hi").assistantMessage("hello").systemMessage("be brief")
            assert(state(ctx) == Structure.Value.Sequence(Chunk(
                record("user", str("hi")),
                record("assistant", str("hello")),
                record("system", str("be brief"))
            )))
        }
        "tool calls ride the assistant turn as they are, a tool result its call id" in {
            val ctx = Context.empty
                .userMessage("summarise the incident")
                .assistantMessage(
                    "",
                    Chunk(Call(CallId("c1"), Completion.resultToolName, """{"resultValue":{"cause":"disk full","fixed":true}}"""))
                )
                .toolMessage(CallId("c1"), "Result received.")
                .assistantMessage(
                    "checking",
                    Chunk(Call(CallId("c2"), "lookup_order", """{"id":"A-1"}"""), Call(CallId("c3"), "raw", "not json"))
                )
                .toolMessage(CallId("c2"), """{"status":"shipped"}""")
            def call(id: String, function: String, arguments: String) =
                Structure.Value.Record(Chunk(("id", str(id)), ("function", str(function)), ("arguments", str(arguments))))
            def tool(callId: String, content: String) =
                Structure.Value.Record(Chunk(("role", str("tool")), ("content", str(content)), ("callId", str(callId))))
            assert(state(ctx) == Structure.Value.Sequence(Chunk(
                record("user", str("summarise the incident")),
                Structure.Value.Record(Chunk(
                    ("role", str("assistant")),
                    ("content", str("")),
                    (
                        "calls",
                        Structure.Value.Sequence(Chunk(call(
                            "c1",
                            Completion.resultToolName,
                            """{"resultValue":{"cause":"disk full","fixed":true}}"""
                        )))
                    )
                )),
                tool("c1", "Result received."),
                Structure.Value.Record(Chunk(
                    ("role", str("assistant")),
                    ("content", str("checking")),
                    (
                        "calls",
                        Structure.Value.Sequence(Chunk(call("c2", "lookup_order", """{"id":"A-1"}"""), call("c3", "raw", "not json")))
                    )
                )),
                tool("c2", """{"status":"shipped"}""")
            )))
        }
    }

    "decoding" - {
        "the live body decodes into answers in question order, with usage as one turn" in {
            val reply = TypeSafeDecider.decode(liveBody, Chunk(noul, choice, score))
            assert(reply == Result.succeed(Reply(
                Chunk(
                    Answer.Noul(0.15),
                    Answer.Choice("orders", 0.44, Chunk(("billing", 0.28), ("orders", 0.72))),
                    Answer.Score(0.98, 0.97, Chunk(0.02, 0.98, 0.0))
                ),
                AIStats(392L, Absent, 66L, Absent, 1)
            )))
        }
        "a noul at or above 0.5 is a yes, below it a no" in {
            def body(p: String) =
                s"""{"model":"m","answers":{"q1":{"type":"noul","noul":$p}},"usage":{"input_tokens":1,"output_tokens":1}}"""
            assert(TypeSafeDecider.decode(
                body("0.5"),
                Chunk(noul)
            ).map(_.answers) == Result.succeed(Chunk(Answer.Noul(0.5))))
            assert(TypeSafeDecider.decode(
                body("0.49"),
                Chunk(noul)
            ).map(_.answers) == Result.succeed(Chunk(Answer.Noul(0.49))))
            assert(TypeSafeDecider.decode(body("1"), Chunk(noul)).map(_.answers) == Result.succeed(Chunk(Answer.Noul(1.0))))
        }
        "a missing answer fails by question position" in {
            val r = TypeSafeDecider.decode(liveBody, Chunk(noul, choice, score, noul))
            assert(r.failure.exists(_.getMessage.contains("no answer for question 4")))
        }
        "an answer of the wrong kind fails" in {
            val r = TypeSafeDecider.decode(liveBody, Chunk(choice, noul, score))
            assert(r.failure.exists(_.getMessage.contains("question 1 is a choice but the answer is a noul")))
        }
        "a choice answer without its fields fails naming the first missing one" in {
            def body(fields: String) =
                s"""{"model":"m","answers":{"q1":{"type":"choice"$fields}},"usage":{"input_tokens":1,"output_tokens":1}}"""
            assert(TypeSafeDecider.decode(body(""), Chunk(choice)).failure.exists(_.getMessage.contains("carries no 'choice'")))
            assert(TypeSafeDecider.decode(
                body(""","choice":"orders""""),
                Chunk(choice)
            ).failure.exists(_.getMessage.contains("carries no 'confidence'")))
            assert(TypeSafeDecider.decode(body(""","choice":"orders","confidence":0.5"""), Chunk(choice)).failure.exists(
                _.getMessage.contains("carries no 'probabilities'")
            ))
        }
        "a score answer missing a level probability fails" in {
            val body =
                """{"model":"m","answers":{"q1":{"type":"score","score":1.0,"confidence":1.0,"probabilities":{"0":0.0,"1":1.0}}},"usage":{"input_tokens":1,"output_tokens":1}}"""
            assert(TypeSafeDecider.decode(
                body,
                Chunk(score)
            ).failure.exists(_.getMessage.contains("lacks a probability for one of 3 levels")))
        }
        "a body that is not the response shape fails as a decode error" in {
            assert(TypeSafeDecider.decode(
                """{"detail":"nope"}""",
                Chunk(noul)
            ).failure.exists(_.getMessage.contains("undecodable decider response")))
        }
    }

    "over the wire" - {
        "sends the bearer key, the model and the conversation, and decodes the reply" in {
            TestDeciderServer.run { server =>
                server.enqueueBody(liveBody).andThen {
                    val ctx = Context.empty.userMessage("""{"order":"A-1"}""")
                    decide(llmConfig, deciderConfig(server).modelName("jev-preview"), ctx, Chunk(noul, choice, score)).map {
                        reply =>
                            server.captured.map { bodies =>
                                assert(reply.answers.size == 3)
                                assert(reply.answers(1) == Answer.Choice("orders", 0.44, Chunk(("billing", 0.28), ("orders", 0.72))))
                                assert(reply.usage == AIStats(392L, Absent, 66L, Absent, 1))
                                assert(bodies.size == 1)
                                assert(bodies(0).startsWith(
                                    """{"model":"jev-preview","state":[{"role":"user","content":"{\"order\":\"A-1\"}"}],"questions":{"q1":"""
                                ))
                            }
                    }
                }
            }
        }
        "a missing key fails before any request, naming the model and the variable to set" in {
            TestDeciderServer.run { server =>
                val sys = System(new TestUnsafeSystem())
                System.let(sys) {
                    Abort.run[AIException](decide(
                        llmConfig,
                        DeciderConfig.TypeSafe.default.apiUrl(server.baseUrl),
                        Context.empty,
                        Chunk(noul)
                    ))
                }.map { result =>
                    server.captured.map { bodies =>
                        result match
                            case Result.Failure(e: AIMissingApiKeyException) =>
                                assert(e.getMessage.contains("jev-latest") && e.getMessage.contains("set TYPESAFE_API_KEY"))
                            case other => fail(s"expected AIMissingApiKeyException, got: $other")
                        end match
                        assert(bodies.isEmpty)
                    }
                }
            }
        }
        "a key from the environment is used when the config has none" in {
            TestDeciderServer.run { server =>
                val sys = System(new TestUnsafeSystem(envVars = Map("TYPESAFE_API_KEY" -> "env-key")))
                System.let(sys)(decide(llmConfig, DeciderConfig.TypeSafe.default.apiUrl(server.baseUrl), Context.empty, Chunk(noul))).map {
                    reply =>
                        assert(reply.answers == Chunk(Answer.Noul(0.9)))
                }
            }
        }
        "401 and 400 and 422 are rejections that do not retry, carrying the body and request id" in {
            TestDeciderServer.run { server =>
                val config = llmConfig.retrySchedule(Schedule.repeat(2))
                def attempt(code: Int) =
                    server.enqueueStatus(code, s"""{"detail":"status $code"}""", Seq("x-typesafe-request-id" -> s"req_$code")).andThen {
                        Abort.run[AIException](decide(config, deciderConfig(server), Context.empty, Chunk(noul)))
                    }
                for
                    r401   <- attempt(401)
                    r400   <- attempt(400)
                    r422   <- attempt(422)
                    bodies <- server.captured
                yield
                    r401 match
                        case Result.Failure(e: AIProviderAuthException) => assert(e.getMessage.contains("req_401"))
                        case other                                      => fail(s"expected AIProviderAuthException, got: $other")
                    r400 match
                        case Result.Failure(e: AIRequestRejectedException) =>
                            assert(e.status == 400 && e.getMessage.contains("status 400") && e.getMessage.contains("req_400"))
                        case other => fail(s"expected AIRequestRejectedException, got: $other")
                    end match
                    r422 match
                        case Result.Failure(e: AIRequestRejectedException) => assert(e.status == 422)
                        case other                                         => fail(s"expected AIRequestRejectedException, got: $other")
                    assert(bodies.size == 3, s"rejections must not retry, expected 3 requests, got ${bodies.size}")
                end for
            }
        }
        "the request id survives a rejection body longer than the message keeps" in {
            TestDeciderServer.run { server =>
                val long = "x" * 2000
                server.enqueueStatus(422, s"""{"detail":"$long"}""", Seq("x-typesafe-request-id" -> "req_long")).andThen {
                    Abort.run[AIException](decide(llmConfig, deciderConfig(server), Context.empty, Chunk(noul))).map {
                        case Result.Failure(e: AIRequestRejectedException) =>
                            assert(e.getMessage.contains("[request id req_long]"))
                            assert(!e.getMessage.contains("x" * 600), "the body is truncated; the id must not be what gets cut")
                        case other => fail(s"expected AIRequestRejectedException, got: $other")
                    }
                }
            }
        }
        "429, 529, 500 and 408 are transient and retry on the schedule" in {
            TestDeciderServer.run { server =>
                val config = llmConfig.retrySchedule(Schedule.repeat(1))
                def attempt(code: Int) =
                    server.enqueueStatus(code, "busy").andThen(server.enqueueBody(liveBody)).andThen {
                        decide(config, deciderConfig(server), Context.empty, Chunk(noul, choice, score))
                    }
                for
                    r429   <- attempt(429)
                    r529   <- attempt(529)
                    r500   <- attempt(500)
                    r408   <- attempt(408)
                    bodies <- server.captured
                yield
                    assert(r429.answers.head == Answer.Noul(0.15))
                    assert(r529.answers.head == Answer.Noul(0.15))
                    assert(r500.answers.head == Answer.Noul(0.15))
                    assert(r408.answers.head == Answer.Noul(0.15))
                    assert(bodies.size == 8, s"each transient failure retries once, expected 8 requests, got ${bodies.size}")
                end for
            }
        }
        "a transient failure past the schedule surfaces as its leaf" in {
            TestDeciderServer.run { server =>
                val config = llmConfig.retrySchedule(Schedule.repeat(1))
                server.enqueueStatus(429, "slow down").andThen(server.enqueueStatus(429, "slow down")).andThen {
                    Abort.run[AIException](decide(config, deciderConfig(server), Context.empty, Chunk(noul))).map { result =>
                        server.captured.map { bodies =>
                            result match
                                case Result.Failure(e: AIRateLimitException) =>
                                    assert(e.getMessage.contains("slow down"))
                                    assert(e.retryAfter.isEmpty)
                                case other => fail(s"expected AIRateLimitException, got: $other")
                            end match
                            assert(bodies.size == 2)
                        }
                    }
                }
            }
        }
        "a 429 with Retry-After is waited out under the deadline before the next attempt" in {
            // A Retry-After past the deadline: the wait is cut to the deadline, which fires with the one
            // request made (the immediate schedule would otherwise have sent a second at once). Then a
            // short Retry-After: the retry follows it and succeeds. Virtual time advances in steps until
            // the fiber settles, so the outcome does not depend on when the sleeper registers.
            TestDeciderServer.run { server =>
                val config = llmConfig.retrySchedule(Schedule.repeat(1))
                Clock.withTimeControl { control =>
                    for
                        _ <- server.enqueueStatus(429, "slow down", Seq("retry-after" -> "60"))
                        long <- Fiber.init(Abort.run[AIException](decide(
                            config.timeout(3.seconds),
                            deciderConfig(server),
                            Context.empty,
                            Chunk(noul)
                        )))
                        _          <- awaitCaptured(server, control, 1)
                        _          <- settle(control, long)
                        longResult <- long.get
                        afterLong  <- server.captured
                        _          <- server.enqueueStatus(429, "slow down", Seq("retry-after" -> "2"))
                        _          <- server.enqueueBody(liveBody)
                        short <- Fiber.init(Abort.run[AIException](decide(
                            config.timeout(1.minute),
                            deciderConfig(server),
                            Context.empty,
                            Chunk(noul, choice, score)
                        )))
                        _           <- awaitCaptured(server, control, 2)
                        _           <- settle(control, short)
                        shortResult <- short.get
                        bodies      <- server.captured
                    yield
                        assert(longResult.failure.exists(_.isInstanceOf[AICompletionTimeoutException]), s"long: $longResult")
                        assert(afterLong.size == 1, "a Retry-After past the deadline makes no second request")
                        assert(shortResult.map(_.answers.head) == Result.succeed(Answer.Noul(0.15)), s"short: $shortResult")
                        assert(bodies.size == 3, "a short Retry-After is followed by the retry")
                    end for
                }
            }
        }
        "the decider's own timeout, meter and schedule apply over the config's" in {
            TestDeciderServer.run { server =>
                // The config would retry twice on a 1 hour deadline; the decider says no retries. One request.
                val config  = llmConfig.timeout(1.hour).retrySchedule(Schedule.repeat(2))
                val decider = deciderConfig(server).retrySchedule(Schedule.done)
                server.enqueueStatus(500, "down").andThen {
                    Abort.run[AIException](decide(config, decider, Context.empty, Chunk(noul))).map { result =>
                        server.captured.map { bodies =>
                            assert(result.failure.exists(_.isInstanceOf[AIProviderUnavailableException]))
                            assert(bodies.size == 1, "the decider's schedule (no retries) must win over the config's")
                        }
                    }
                }
            }
        }
        "the decider's own timeout bounds the call when the config's is longer" in {
            TestDeciderServer.run { server =>
                val config  = llmConfig.timeout(1.hour).retrySchedule(Schedule.repeat(1))
                val decider = deciderConfig(server).timeout(10.seconds)
                Clock.withTimeControl { control =>
                    server.enqueueNeverRespond.andThen {
                        for
                            fiber  <- Fiber.init(Abort.run[AIException](decide(config, decider, Context.empty, Chunk(noul))))
                            _      <- awaitCaptured(server, control, 1)
                            _      <- control.advance(11.seconds, 500.millis)
                            result <- fiber.get
                            bodies <- server.captured
                        yield
                            result match
                                case Result.Failure(e: AICompletionTimeoutException) => assert(e.timeout == 10.seconds)
                                case other => fail(s"expected AICompletionTimeoutException, got: $other")
                            assert(bodies.size == 1)
                        end for
                    }
                }
            }
        }
        "a client-side timeout halts as AICompletionTimeoutException without retry" in {
            TestDeciderServer.run { server =>
                val config = llmConfig.timeout(10.seconds).retrySchedule(Schedule.repeat(1))
                Clock.withTimeControl { control =>
                    server.enqueueNeverRespond.andThen {
                        for
                            fiber  <- Fiber.init(Abort.run[AIException](decide(config, deciderConfig(server), Context.empty, Chunk(noul))))
                            _      <- awaitCaptured(server, control, 1)
                            _      <- control.advance(config.timeout + 1.second, 500.millis)
                            result <- fiber.get
                            bodies <- server.captured
                        yield
                            result match
                                case Result.Failure(_: AICompletionTimeoutException) => ()
                                case other => fail(s"expected AICompletionTimeoutException, got: $other")
                            assert(bodies.size == 1)
                        end for
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

    /** Advances virtual time one second at a time, bounded, until the fiber settles, so an outcome that
      * depends on a sleeper registered at an unknown moment (a Retry-After wait) is reached whichever
      * step it registers in; the sleeper registered up front (the deadline) fires on schedule regardless.
      */
    private def settle[A](control: Clock.TimeControl, fiber: Fiber[A, Any])(using Frame): Unit < Async =
        Loop(0) { i =>
            fiber.done.map { done =>
                if done || i >= 90 then Loop.done
                else control.advance(1.second, 50.millis).andThen(Loop.continue(i + 1))
            }
        }

    private class TestUnsafeSystem(
        envVars: Map[String, String] = Map.empty,
        properties: Map[String, String] = Map.empty
    ) extends System.Unsafe:
        def env(name: String)(using AllowUnsafe): Maybe[String] =
            Maybe.fromOption(envVars.get(name))
        def property(name: String)(using AllowUnsafe): Maybe[String] =
            Maybe.fromOption(properties.get(name))
        def lineSeparator()(using AllowUnsafe): String      = "\n"
        def userName()(using AllowUnsafe): String           = "test"
        def operatingSystem()(using AllowUnsafe): System.OS = System.OS.Unknown
        def architecture()(using AllowUnsafe): System.Arch  = System.Arch.Unknown
        def availableProcessors()(using AllowUnsafe): Int   = 1
    end TestUnsafeSystem

end TypeSafeDeciderTest
