package kyo

import kyo.Schedule
import kyo.ai.Config
import kyo.ai.Context
import kyo.ai.Context.*
import kyo.ai.Image

class AITest extends kyo.test.Test[Any]:

    case class Ack(ok: Boolean) derives Schema

    case class Answer(text: String) derives Schema, CanEqual

    /** A config pointing the OpenAI backend at the test server, with a dummy key so the backend proceeds. */
    def serverConfig(baseUrl: String): Config =
        Config.OpenAI.default
            .apiKey("test")
            .model(Config.OpenAI, "gpt-4o", 128000)
            .apiUrl(baseUrl)

    /** An OpenAI completion body whose assistant calls `result_tool` with the supplied envelope JSON. */
    def resultToolBody(envelopeJson: String): String =
        val escaped = Json.encode(envelopeJson)
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$escaped}}]}}]}"""

    "init-mints-distinct" in {
        LLM.runTuple {
            AI.init.map { a =>
                AI.init.map { b =>
                    (a, b)
                }
            }
        }.map { case (state, (a, b)) =>
            assert(a != b, s"two AI.init should yield distinct ids, got a=$a b=$b")
            assert(state.instances.contains(a.ref), s"state should contain key a=$a")
            assert(state.instances.contains(b.ref), s"state should contain key b=$b")
            assert(state.contextOf(a).isEmpty, s"a's context should be empty, got: ${state.contextOf(a)}")
            assert(state.contextOf(b).isEmpty, s"b's context should be empty, got: ${state.contextOf(b)}")
        }
    }

    "initWith-no-leak" in {
        LLM.run {
            AI.initWith { ai =>
                ai.userMessage("x").andThen(ai.context)
            }
        }.map { ctx =>
            assert(ctx.raw == Chunk(UserMessage("x", Absent)), s"context should contain exactly one UserMessage, got: ${ctx.raw}")
        }
    }

    "explicit-target-isolates-instances" in {
        LLM.run {
            AI.init.map { a =>
                AI.init.map { b =>
                    b.userMessage("toB").andThen {
                        a.context.map { aCtx =>
                            b.context.map { bCtx =>
                                (aCtx, bCtx)
                            }
                        }
                    }
                }
            }
        }.map { case (aCtx, bCtx) =>
            assert(aCtx.isEmpty, s"a's context should be empty when the message targets b explicitly, got: ${aCtx.raw}")
            assert(bCtx.raw == Chunk(UserMessage("toB", Absent)), s"b's context should carry the message, got: ${bCtx.raw}")
        }
    }

    "message-builders-append-order" in {
        LLM.run {
            AI.initWith { ai =>
                ai.systemMessage("s")
                    .andThen(ai.userMessage("u"))
                    .andThen(ai.assistantMessage("a"))
                    .andThen(ai.context)
            }
        }.map { ctx =>
            assert(ctx.raw.size == 3, s"expected 3 messages, got: ${ctx.raw.size}")
            assert(ctx.raw(0) == SystemMessage("s"), s"first should be SystemMessage(s), got: ${ctx.raw(0)}")
            assert(ctx.raw(1) == UserMessage("u", Absent), s"second should be UserMessage(u), got: ${ctx.raw(1)}")
            assert(ctx.raw(2) == AssistantMessage("a"), s"third should be AssistantMessage(a), got: ${ctx.raw(2)}")
        }
    }

    "userMessage-image-overload" in {
        val img = Image.fromBase64("abc")
        LLM.run {
            AI.initWith { ai =>
                ai.userMessage("look", img).andThen(ai.context)
            }
        }.map { ctx =>
            assert(
                ctx.raw == Chunk(UserMessage("look", Present(img))),
                s"context should carry the image message, got: ${ctx.raw}"
            )
        }
    }

    "reset-removes-slot" in {
        LLM.runTuple {
            AI.initWith { ai =>
                ai.userMessage("x").andThen(ai.reset).andThen(ai)
            }
        }.map { case (state, ai) =>
            assert(
                !state.instances.contains(ai.ref),
                s"final State should not contain ai=$ai after reset, got keys: ${state.instances.toMap.keys}"
            )
        }
    }

    "init(config) carries a config override on the instance" in {
        val cfg = Config.OpenAI.default.temperature(0.123)
        LLM.runTuple(AI.init(cfg)).map { case (state, ai) =>
            state.sessionOf(ai).env.config match
                case Present(c) => assert(c.temperature == Present(0.123), s"override temp: ${c.temperature}")
                case Absent     => assert(false, "the instance should carry a config override")
        }
    }

    "setContext replaces the conversation wholesale, keeping enablements" in {
        LLM.runTuple {
            AI.initWith { ai =>
                ai.userMessage("old")
                    .andThen(ai.enable(Tool.init[Int]("t")(_ => 1)))
                    .andThen(ai.setContext(Context.empty.userMessage("new")))
                    .andThen(ai)
            }
        }.map { case (state, ai) =>
            assert(state.contextOf(ai).raw == Chunk(UserMessage("new", Absent)), s"context: ${state.contextOf(ai).raw}")
            assert(state.sessionOf(ai).env.tools.size == 1, "the enabled tool must survive a setContext")
        }
    }

    "fresh blanks every instance's history but keeps enablements, then restores on exit" in {
        LLM.run {
            AI.initWith { ai =>
                for
                    _ <- ai.userMessage("seeded")
                    _ <- ai.enable(Tool.init[Int]("t")(_ => 1))
                    (innerMsgs, innerTools) <- AI.fresh(
                        for
                            c    <- ai.context
                            snap <- ai.snapshot
                        yield (c.raw.size, snap.env.tools.size)
                    )
                    outer <- ai.context
                yield (innerMsgs, innerTools, outer.raw.size)
            }
        }.map { case (innerMsgs, innerTools, outerMsgs) =>
            assert(innerMsgs == 0, s"inside fresh the history is blanked, got $innerMsgs")
            assert(innerTools == 1, s"enablements are kept inside fresh, got $innerTools")
            assert(outerMsgs == 1, s"history is restored on exit, got $outerMsgs")
        }
    }

    "fresh(ais*) blanks only the named instances" in {
        LLM.run {
            AI.init.map { a =>
                AI.init.map { b =>
                    for
                        _ <- a.userMessage("a-seed")
                        _ <- b.userMessage("b-seed")
                        (aInner, bInner) <- AI.fresh(a)(
                            for
                                ac <- a.context
                                bc <- b.context
                            yield (ac.raw.size, bc.raw.size)
                        )
                        aOuter <- a.context
                    yield (aInner, bInner, aOuter.raw.size)
                }
            }
        }.map { case (aInner, bInner, aOuter) =>
            assert(aInner == 0, s"the named instance a is blanked inside fresh, got $aInner")
            assert(bInner == 1, s"the unnamed instance b keeps its history, got $bInner")
            assert(aOuter == 1, s"a's history is restored on exit, got $aOuter")
        }
    }

    "forget(ais*) rolls back only the named instances; other writes persist" in {
        LLM.run {
            AI.init.map { a =>
                AI.init.map { b =>
                    for
                        _  <- AI.forget(a)(a.userMessage("a-write").andThen(b.userMessage("b-write")))
                        ac <- a.context
                        bc <- b.context
                    yield (ac.raw.size, bc.raw.size)
                }
            }
        }.map { case (aSize, bSize) =>
            assert(aSize == 0, s"the named instance a is rolled back, got $aSize")
            assert(bSize == 1, s"the unnamed instance b's write persists, got $bSize")
        }
    }

    "snapshot captures an instance's full state; recover restores it in a fresh run" in {
        val tool = Tool.init[Int]("t")(_ => 1)
        val cfg  = Config.OpenAI.default.temperature(0.42)
        // Run A: build an instance with history + an enabled tool + a config override, then snapshot it.
        LLM.run {
            AI.init(cfg).map { ai =>
                ai.userMessage("turn-1").andThen(ai.enable(tool)).andThen(ai.snapshot)
            }
        }.map { session =>
            // Run B (a fresh run, fresh owner): recover the snapshot and assert the state is restored.
            LLM.run {
                AI.recover(session).map { restored =>
                    for
                        ctx  <- restored.context
                        snap <- restored.snapshot
                    yield (ctx.raw, snap.env.tools.size, snap.env.config)
                }
            }.map { case (messages, toolCount, config) =>
                assert(messages == Chunk(UserMessage("turn-1", Absent)), s"recovered history: $messages")
                assert(toolCount == 1, s"recovered the enabled tool, got $toolCount")
                config match
                    case Present(c) => assert(c.temperature == Present(0.42), s"recovered config override temp: ${c.temperature}")
                    case Absent     => assert(false, "the recovered instance should carry the config override")
            }
        }
    }

    "AI.enable scopes a pure body without dispatching a generation" in {
        // AI.enable scopes a pure body without dispatching Gen (a placeholder Gen would panic).
        // The body's userMessage lands in ai's context, proving the binder threaded the body without suspending.
        LLM.run {
            AI.init.map { ai =>
                AI.enable(Tool.empty)(ai.userMessage("hi")).andThen(ai.context)
            }
        }.map { ctx =>
            assert(
                ctx.raw.size == 1,
                s"binders scope the pure body without Gen dispatch; expected 1 message, got: ${ctx.raw.size}"
            )
        }
    }

    "AI.enable of a tool, prompt, thought, or mode adds LLM to the row" in {
        // AI.enable of a thought, prompt, mode, or tool each produce a row containing LLM.
        val thought: Unit < LLM = AI.enable(Thought.reflective)(Kyo.unit)
        val prompt: Unit < LLM  = AI.enable(Prompt.empty)(Kyo.unit)
        val mode: Unit < LLM = AI.enable(new Mode[Any]:
            def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using
                Frame
            ): Maybe[A] < (LLM & Async & Abort[AIGenException]) = gen)(Kyo.unit)
        val tool: Unit < LLM = AI.enable(Tool.empty)(Kyo.unit)
        // The `: Unit < LLM` ascriptions above are the compile-time proof each enable row gains LLM;
        // at runtime the composed chain runs under LLM.run and threads a value through.
        LLM.run(thought.andThen(prompt).andThen(mode).andThen(tool).map(_ => 42)).map { result =>
            assert(result == 42, s"the enable chain runs under LLM.run and threads its value, got: $result")
        }
    }

    "AI.enable varargs unify mixed-capability enablements to their intersection" in {
        // Enablement is contravariant in S, so one varargs AI.enable mixing enablements with DIFFERENT
        // capabilities unifies S to their intersection (the GLB), not their union. The ascription below is the
        // compile-time proof: a Tool[Check] and a Tool[Var[Int]] enabled together yield a `Check & Var[Int]`
        // row. Covariance would infer `Check | Var[Int]`, which would not satisfy this ascription. The runtime
        // run-under-both-handlers confirms the unified row composes and threads its value.
        given Isolate[Var[Int], Any, Var[Int]]      = Var.isolate.update[Int]
        val checkTool: Tool[Check]                  = Tool.init[Int][Int, Check]("c")((_: Int) => Check.require(true, "ok").andThen(0))
        val varTool: Tool[Var[Int]]                 = Tool.init[Int][Int, Var[Int]]("v")((n: Int) => Var.update[Int](_ + n).andThen(0))
        val unified: Int < (Check & Var[Int] & LLM) = AI.enable(checkTool, varTool)(42)
        Check.runDiscard(Var.run(0)(LLM.run(unified))).map(r =>
            assert(r == 42, s"the unified mixed-capability enable threads its value, got: $r")
        )
    }

    "a one-shot gen discards its ephemeral instance on success, concurrency, and abort" in {
        // A one-shot gen mints a fresh ephemeral instance and discards its slot on success, so the final
        // State instances is empty after a successful gen and after concurrent gens; a transport abort fails
        // the run, so no ephemeral can survive (there is no final State to leak into).
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            // The one-shot row (with an input) is Int < (Async & Abort[AIGenException]) at run's residual.
            def oneShotRow: Int < (Async & Abort[AIGenException]) = LLM.run(config)(AI.gen[Int]("What is 6 times 7?"))
            val _                                                 = oneShotRow
            // success: one forgetful gen leaves instances empty (the ephemeral was discarded).
            server.enqueueBody(resultToolBody("""{"resultValue":7}""")).andThen {
                LLM.runTuple(AI.withConfig(config)(AI.gen[Int])).map { case (state, value) =>
                    assert(value == 7, s"forgetful gen should yield the scripted Int, got: $value")
                    assert(
                        state.instances.isEmpty,
                        s"a successful forgetful gen must leave instances empty, got: ${state.instances.toMap.keys}"
                    )
                    // concurrent: two forgetful gens via Async.fill leave instances empty.
                    server.enqueueBody(resultToolBody("""{"resultValue":1}""")).andThen {
                        server.enqueueBody(resultToolBody("""{"resultValue":2}""")).andThen {
                            LLM.runTuple(AI.withConfig(config)(Async.fill(2)(AI.gen[Int]))).map { case (state2, values) =>
                                assert(values.size == 2, s"two concurrent forgetful gens should both complete, got: $values")
                                assert(
                                    state2.instances.isEmpty,
                                    s"two concurrent forgetful gens must leave instances empty, got: ${state2.instances.toMap.keys}"
                                )
                                // abort: a transport decode failure fails the run; the ephemeral cannot leak.
                                server.enqueueBody("not json").andThen {
                                    Abort.run[AIException](
                                        LLM.run(config.retrySchedule(Schedule.done))(AI.gen[Int])
                                    ).map { aborted =>
                                        assert(
                                            aborted.isFailure,
                                            s"a forgetful gen that aborts must fail (no surviving State to leak the ephemeral), got: $aborted"
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

    "AI.forget drops a fork's writes without erasing the slot" in {
        // forget snapshots and restores State; the fork's changes drop, the slot is NOT erased.
        LLM.runTuple {
            AI.initWith { ai =>
                ai.userMessage("base").andThen {
                    AI.forget(ai.userMessage("inside"))
                }.andThen(ai)
            }
        }.map { case (state, ai) =>
            val msgs = state.contextOf(ai).raw
            assert(msgs == Chunk(UserMessage("base", Absent)), s"forget should drop 'inside'; slot should retain 'base', got: $msgs")
            assert(msgs.size == 1, s"exactly one message ('base') should remain, got: ${msgs.size}")
        }
    }

    "ids are not reused after a forget rollback" in {
        // forget restores the whole State; the id counter must NOT roll back, so an instance minted after a
        // forget can never reuse an id minted (and discarded) inside it. Without the high-water guard a3 would
        // alias escaped's slot.
        LLM.run {
            AI.init.map { a1 =>
                AI.forget(AI.init).map { escaped =>
                    AI.init.map { a3 =>
                        assert(a1.id == 0L, s"a1 id, got ${a1.id}")
                        assert(escaped.id == 1L, s"escaped (minted inside forget) id, got ${escaped.id}")
                        assert(a3.id == 2L, s"post-forget init must not reuse the rolled-back id, got ${a3.id}")
                    }
                }
            }
        }
    }

    "AI.fresh does not rewind the id counter" in {
        // fresh restores the whole State; the id high-water (Op.SetState math.max) must hold, so an instance
        // minted after a fresh can never reuse an id minted (and rolled away) inside it.
        LLM.run {
            AI.init.map { a0 =>
                AI.fresh(AI.init).map { inside =>
                    AI.init.map(after => (a0.id, inside.id, after.id))
                }
            }
        }.map { case (id0, idInside, idAfter) =>
            assert(id0 == 0L, s"a0 id: $id0")
            assert(idInside == 1L, s"instance minted inside fresh: $idInside")
            assert(idAfter == 2L, s"post-fresh init must not reuse the id minted inside fresh, got: $idAfter")
        }
    }

    "a multi-turn instance recalls the first turn on the second gen, leaving one instance" in {
        // Multi-turn persistence: a second gen on the same instance sees the first turn's message history,
        // and exactly one instance (the assistant) remains in the final State.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":{"ok":true}}""")).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":"Ada"}""")).andThen {
                    LLM.runTuple(AI.withConfig(config) {
                        AI.initWith { assistant =>
                            for
                                _        <- assistant.userMessage("My name is Ada.")
                                _        <- assistant.gen[Ack]
                                recalled <- assistant.gen[String]
                            yield recalled
                        }
                    }).map { case (state, recalled) =>
                        assert(recalled == "Ada", s"the second-turn gen should recall the scripted name, got: $recalled")
                        assert(state.instances.size == 1, s"a single instance should remain, got: ${state.instances.toMap.keys}")
                        server.captured.map { caps =>
                            assert(caps.size == 2, s"two gens should produce two requests, got: ${caps.size}")
                            assert(
                                caps(1).body.contains("My name is Ada"),
                                s"the second gen's request context must carry the first turn's user message, got: ${caps(1).body}"
                            )
                        }
                    }
                }
            }
        }
    }

    "two instances generate independently with no cross-contamination" in {
        // Multi-agent independence: two AI.init instances (researcher + critic) gen explicitly
        // with no ambient; each instance's history is its own.
        TestCompletionServer.run { server =>
            val config = serverConfig(server.baseUrl)
            server.enqueueBody(resultToolBody("""{"resultValue":"research"}""")).andThen {
                server.enqueueBody(resultToolBody("""{"resultValue":"critique"}""")).andThen {
                    LLM.runTuple(AI.withConfig(config) {
                        AI.init.map { researcher =>
                            AI.init.map { critic =>
                                researcher.userMessage("investigate CRDTs")
                                    .andThen(researcher.gen[String])
                                    .andThen {
                                        critic.userMessage("critique the approach")
                                            .andThen(critic.gen[String])
                                            .map(criticOut => (researcher, critic, criticOut))
                                    }
                            }
                        }
                    }).map { case (state, (researcher, critic, criticOut)) =>
                        assert(criticOut == "critique", s"the critic gen should yield its scripted result, got: $criticOut")
                        assert(state.instances.size == 2, s"exactly two instances should remain, got: ${state.instances.toMap.keys}")
                        val researcherContents = state.contextOf(researcher).raw.map(_.content)
                        val criticContents     = state.contextOf(critic).raw.map(_.content)
                        assert(
                            researcherContents.exists(_.contains("investigate CRDTs")),
                            s"the researcher should retain its own user message, got: $researcherContents"
                        )
                        assert(
                            !researcherContents.exists(_.contains("critique the approach")),
                            s"the researcher must not see the critic's messages, got: $researcherContents"
                        )
                        assert(
                            !criticContents.exists(_.contains("investigate CRDTs")),
                            s"the critic must not see the researcher's messages, got: $criticContents"
                        )
                    }
                }
            }
        }
    }

end AITest
