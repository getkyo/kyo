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
            .model(Config.OpenAI, "gpt-4o", 128000)
            .apiUrl(baseUrl)

    /** An OpenAI completion body whose assistant calls `result_tool` with the supplied envelope JSON. */
    def resultToolBody(envelopeJson: String): String =
        val escaped = Json.encode(envelopeJson)
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$escaped}}]}}]}"""

    /** An OpenAI completion body with a plain assistant reply and no tool call (the eval loop sees Absent). */
    def noResultBody: String =
        """{"choices":[{"message":{"role":"assistant","content":"thinking","tool_calls":null}}]}"""

    /** The single captured outbound request body for the last scripted turn. */
    def requestBody(server: TestCompletionServer)(using Frame): String < Async =
        server.captured.map(_.head.body)

    /** The committed default-off golden: the enriched-request bytes for the fixed scripted turn in the
      * "default-off matches committed pre-change golden bytes" test below, captured from the pre-seam
      * eval (compactor Absent) and pinned as a source constant. A seam edit that leaked a byte onto the
      * Absent path fails that test against this, not a self-derivation.
      */
    val goldenDefaultOffRequest: String =
        """{"model":"gpt-4o","messages":[{"role":"system","content":"you are precise"},{"role":"user","content":"ping"},{"role":"system","content":"================== REMINDERS ==================\n\n1. Your response must contain ONLY the structured tool call\n2. The arguments must strictly follow the tool's json schema, including its semantics\n3. Always perform at least one tool call; it is the only agency you have\n4. Do not output regular text replies, especially empty ones\n5. Do not use a json code block; follow the tool-call format\n6. Generate valid json strictly following the json schema. Do NOT generate xml-like content"}],"tools":[{"function":{"description":"Call this tool with the result. Do not make parallel calls to this tool in the same completion. Only the first invocation will be considered.","name":"result_tool","strict":false,"parameters":{"type":"object","properties":{"resultValue":{"type":"string"}},"required":["resultValue"]}},"type":"function"}],"tool_choice":"required"}"""

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

    "the eval loop fails with AIEvalExhaustedException at maxIterations * 2" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl).maxIterations(2)
            // Enqueue one valid-but-no-result body per iteration the loop runs before the hard stop
            // (maxIterations * 2 + 1 evals). Each keeps the loop climbing without populating the result tool;
            // the empty-choices server default would otherwise abort the backend before the iteration cap.
            Kyo.foreachDiscard(0 until (config.maxIterations * 2 + 1))(_ => server.enqueueBody(noResultBody)).andThen {
                // Eval exhaustion is a typed AIGenException on run's residual (an AIEvalExhaustedException),
                // observed by Abort.run[AIException] at the run boundary.
                Abort.run[AIException](LLM.run(config)(AI.gen[String])).map { result =>
                    assert(result.isFailure, s"expected a typed failure at the iteration cap, got: $result")
                    result match
                        case Result.Failure(ex: AIEvalExhaustedException) =>
                            assert(ex.getMessage.contains("exceeded"), s"message: ${ex.getMessage}")
                        case _ => assert(false, s"expected AIEvalExhaustedException, got: $result")
                    end match
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

    "gen delivers the defaultGuidance reminder into the request body" in {
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"ok"}""")).andThen {
                LLM.run(config)(AI.gen[String]).andThen {
                    server.captured.map { caps =>
                        assert(caps.nonEmpty, "expected at least one captured request")
                        val body = caps.head.body
                        assert(
                            body.contains("Do NOT generate xml-like content"),
                            s"gen should wrap the loop in AI.enable(defaultGuidance) so the reminder reaches the request: $body"
                        )
                    }
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
        // LLM effect row must not include Async; operations composed with < LLM should not require Async.
        // Compile-time assertion: NotGiven[LLM <:< Async] must be derivable.
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
        // gen's own row is Int < LLM (no Async); run's residual is Int < (Async & Abort[AIGenException]).
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
        // AI.initWith + two messages + read: LLM.run threads State across Init, two Add, Read.
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
                // seed parent with p1
                shared.userMessage("p1").andThen {
                    AI.withConfig(_.temperature(0.2)) {
                        // spawn a parallel fork via Async.fill(1) which uses the LLM isolate
                        Async.fill(1) {
                            AI.init.map { born =>
                                // in fork: append to shared + add to born + change config
                                shared.userMessage("f1").andThen {
                                    born.userMessage("b1").andThen {
                                        AI.withConfig(_.temperature(0.9))(Kyo.unit)
                                    }
                                }
                            }
                        }.andThen {
                            // back in parent: read config (should still be 0.2) and shared context
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
            val cfg         = serverConfig(server.baseUrl).compactionBudget(1)
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

end LLMTest
