package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Context.*

/** The §5f background preparation model: the single-flight fiber (arming, disarm, one run per session),
  * need-shaped fills, the join in its invisible / running / huge-turn-synchronous forms, run-level
  * lifecycle leak-freedom, and the fill-failure degrade. Deterministic throughout: occupancy is pinned via
  * the usage anchor, fills are scripted through TestCompletionServer, and every wait is an async suspension
  * (Channel/Fiber), never a sleep.
  */
class CompactorPreparationTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def tok(m: Message, n: Int): Message              = stamp(m, TokenStamp("t", n))

    // window 16384 => effectiveHigh 8192, effectiveLow 4915, prepareLine 6553 (the prepare band is [6553, 8192)).
    def cfg(window: Int = 16384): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    // A context whose usage anchor pins occupancy to exactly `occ`: compacted size equals the anchor raw
    // size, so the offline suffix is zero. The messages are recent (no closed spans), so the projected fill
    // need is empty and an armed fiber runs to completion with zero fills (no wire needed).
    def forcedCtx(occ: Int, msgs: Message*): Context =
        val c = Chunk.from(msgs)
        Context(c).withCompaction(CompactionState(lastUsage = Present(occ), lastUsageRawSize = c.size))

    // A summarizer pinned at the test server; resolveFillConfig(Present) routes every degraded fill here.
    def pinnedCfg(server: TestCompletionServer): Config =
        cfg().compaction(_.summarizer(cfg().apiUrl(server.baseUrl)))

    // A plain-content completion body: the degraded fill reads reply.messages.head.content as the summary.
    def fillBody(summary: String): String =
        s"""{"choices":[{"message":{"role":"assistant","content":"$summary"}}]}"""

    def sameFiber(a: Maybe[Fiber[Unit, Any]], b: Maybe[Fiber[Unit, Any]]): Boolean =
        (a, b) match
            case (Present(x), Present(y)) => x.asInstanceOf[AnyRef] eq y.asInstanceOf[AnyRef]
            case _                        => false

    // ==== single-flight arming ====

    "INV-039 single-flight: three arming passes share ONE run" in {
        val ctx    = forcedCtx(7000, sm("s"), um("u"), am("a"))
        val config = cfg()
        LLM.run(config) {
            AI.init.map { ai =>
                Preparation.init.map { prep =>
                    val session = AISession.empty.withPreparation(prep)
                    Default.armBelowBoundary(ai, ctx, config, session, Absent, driftArm = false).map { s1 =>
                        prep.inFlight.get.map { f1 =>
                            Default.armBelowBoundary(ai, ctx, config, s1, Absent, driftArm = false).map { s2 =>
                                Default.armBelowBoundary(ai, ctx, config, s2, Absent, driftArm = false).map { s3 =>
                                    prep.inFlight.get.map { f3 =>
                                        prep.armed.get.map { armed =>
                                            assert(f1.isDefined, "the first arming pass forks a single-flight fiber into inFlight")
                                            assert(
                                                sameFiber(f1, f3),
                                                "a pass with a run already in flight never forks a second; the handle is identical"
                                            )
                                            assert(armed == Set(ArmCause.Prepare), s"exactly the Prepare cause is armed, got $armed")
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

    "INV-039b re-arm tops up the delta only; write-once leaves already-filled spans untouched" in {
        val raw = Chunk[Message](am("r0 " + ("x" * 30)), am("r1 " + ("x" * 30)), am("r2 " + ("x" * 30)))
        TestCompletionServer.run { server =>
            server.enqueueBody(fillBody("newC")).andThen {
                Preparation.init.map { prep =>
                    prep.staged.set(Staged().withSummary(SpanKey(0, 1), "oldA").withSummary(SpanKey(1, 2), "oldB")).andThen {
                        val need = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)), Span(2, 3, Chunk(2)))
                        Default.fillRemaining(Context(raw), pinnedCfg(server), prep, need, Chunk.empty).map { staged =>
                            server.captured.map { cap =>
                                assert(
                                    staged.summaryOf(SpanKey(0, 1)) == Present("oldA"),
                                    "the first already-filled slot keeps its bytes (write-once)"
                                )
                                assert(
                                    staged.summaryOf(SpanKey(1, 2)) == Present("oldB"),
                                    "the second already-filled slot keeps its bytes (write-once)"
                                )
                                assert(
                                    staged.summaryOf(SpanKey(2, 3)) == Present("newC"),
                                    "only the still-empty span is filled by the delta run"
                                )
                                assert(
                                    cap.size == 1,
                                    s"exactly one completion is issued (the delta span); the filled spans buy no re-fill, got ${cap.size}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ==== need-shaped fills ====

    "INV-040 the fill set is exactly the summary-level spans of A_prep (skip pinned + pointer)" in {
        val spans = Chunk.from((0 until 8).map(i => Span(i, i + 1, Chunk(i))))
        // spans 0 and 7 pinned (absent from the assignment); 1..4 Summary; 5,6 Pointer.
        val assignment = Dict[Int, Level](
            (1, Level.Summary),
            (2, Level.Summary),
            (3, Level.Summary),
            (4, Level.Summary),
            (5, Level.Pointer),
            (6, Level.Pointer)
        )
        val need = Default.fillNeed(spans, assignment)
        assert(need.map(_.start).toList == List(1, 2, 3, 4), s"exactly the four Level.Summary spans are the need, got ${need.map(_.start)}")
        assert(!need.exists(sp => sp.start == 0 || sp.start == 7), "a projected-pinned span (absent from the assignment) buys no fill")
        assert(!need.exists(sp => sp.start == 5 || sp.start == 6), "a projected-pointer span (the coldest content) buys no fill")
        assert(need.size == 4, "no speculationMargin widens the set beyond the summary-level assignment")
    }

    // A context whose given prefix regions age into the closed set before one large tail region (window
    // 16384 => tail band ~1228 tokens), so the prefix forms demotable spans.
    def closedCtx(prefix: Chunk[Message]): Context =
        Context(prefix.append(tok(am("tail region " + ("z" * 100)), 2000)))

    "INV-040b the projected-summary set is a superset of the size boundary's consumed summary set" in {
        val ctx      = closedCtx(Chunk.from((0 until 6).map(i => tok(am(s"region $i " + ("x" * 60)), 700))))
        val units    = Default.group(ctx.raw)
        val spans    = Default.formSpans(units, ctx.raw, cfg())
        val scores   = Dict.from(units.toList.map(u => u.id -> 0.001).toMap) // all demotable
        val config   = cfg()
        val aPrep    = Default.projectedAssignment(ctx, units, spans, scores, config, ctx.raw.size, Dict.empty, false)
        val prepNeed = Default.fillNeed(spans, aPrep).map(_.start).toSet
        // A_fresh at a strictly HIGHER actual occupancy than the projected boundary.
        val aFresh = Default.cut(ctx, units, spans, scores, 6.0, config.effectiveHigh * 2, config.effectiveLow, ctx.raw.size, Dict.empty)
        val freshSummary = spans.filter(sp => aFresh.get(sp.start).contains(Level.Summary)).map(_.start)
        assert(spans.nonEmpty, "the closed prefix forms at least one demotable span")
        assert(
            freshSummary.forall(s => prepNeed.contains(s)),
            s"every span the actual boundary renders at the summary level was already bought by A_prep, got fresh=$freshSummary prep=$prepNeed"
        )
    }

    // ==== the join ====

    "INV-042 the invisible case: an empty-need join returns instantly with no fill" in {
        TestCompletionServer.run { server =>
            Preparation.init.map { prep =>
                prep.staged.set(Staged().withSummary(SpanKey(0, 1), "done")).andThen {
                    Default.joinPreparation(Context(Chunk(am("r0"))), pinnedCfg(server), prep, Chunk.empty, Chunk.empty).map { staged =>
                        server.captured.map { cap =>
                            assert(
                                staged.summaryOf(SpanKey(0, 1)) == Present("done"),
                                "the join returns the staged cell carrying the real summary"
                            )
                            assert(cap.isEmpty, "an empty need awaits no fiber and issues zero completions (the invisible case)")
                        }
                    }
                }
            }
        }
    }

    "INV-042b the huge-turn synchronous case: no run armed, the boundary starts + joins the exact need" in {
        val raw = Chunk[Message](am("r0 " + ("x" * 30)), am("r1 " + ("x" * 30)))
        TestCompletionServer.run { server =>
            server.enqueueBody(fillBody("s0")).andThen(server.enqueueBody(fillBody("s1"))).andThen {
                Preparation.init.map { prep => // inFlight starts Absent: no run ever armed
                    val need = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)))
                    Default.joinPreparation(Context(raw), pinnedCfg(server), prep, need, Chunk.empty).map { staged =>
                        server.captured.map { cap =>
                            assert(
                                staged.summaryOf(SpanKey(0, 1)) == Present("s0"),
                                "the first needed span is filled synchronously at the boundary"
                            )
                            assert(
                                staged.summaryOf(SpanKey(1, 2)) == Present("s1"),
                                "the second needed span is filled synchronously at the boundary"
                            )
                            assert(
                                cap.size == 2,
                                s"with no fiber in flight the join fills the exact need through the same fillRemaining code, got ${cap.size}"
                            )
                        }
                    }
                }
            }
        }
    }

    // ==== lifecycle leak-freedom ====

    "INV-043 lifecycle: run teardown interrupts the in-flight fiber; a staged summary is durable" in {
        Channel.initUnscoped[Unit](1).map { gate => // never put: the fiber parks on take until interrupted
            AtomicRef.init(Absent: Maybe[Fiber[Unit, Any]]).map { probe =>
                AtomicRef.init(Staged()).map { stagedProbe =>
                    LLM.run(cfg()) {
                        LLM.env.map { env =>
                            Fiber.initUnscoped(Abort.run[Closed](gate.take).unit).map { fiber =>
                                probe.set(Present(fiber)).andThen {
                                    val reg = env.preparations match
                                        case Present(r) => r.getAndUpdate(_ + fiber).unit
                                        case Absent     => Kyo.unit
                                    reg.andThen(stagedProbe.set(Staged().withSummary(SpanKey(0, 1), "durable")))
                                }
                            }
                        }
                    }.andThen {
                        probe.get.map {
                            case Present(fiber) =>
                                fiber.getResult.map { res =>
                                    stagedProbe.get.map { staged =>
                                        assert(
                                            !res.isSuccess,
                                            "the run-level Sync.ensure interrupted the in-flight fiber on exit (no leak past the run)"
                                        )
                                        assert(
                                            staged.summaryOf(SpanKey(0, 1)) == Present("durable"),
                                            "an already-staged summary survives the teardown, adoptable"
                                        )
                                    }
                                }
                            case Absent =>
                                Kyo.lift(assert(false, "the probe fiber was seated into the run-level registry"))
                        }
                    }
                }
            }
        }
    }

    // ==== fill-failure degrade + no blocking ====

    "INV-011 a failed fill degrades to the substitute elision; no auxiliary failure fails; no thread blocks (INV-008)" in {
        val raw = Chunk[Message](am("r0 " + ("x" * 30)), am("r1 " + ("x" * 30)))
        TestCompletionServer.run { server =>
            // span (0,1): an empty-choices reply -> AIDecodeException -> recovered to an absent slot;
            // span (1,2): a real fill that still lands.
            server.enqueueBody("""{"choices":[]}""").andThen(server.enqueueBody(fillBody("realB"))).andThen {
                Preparation.init.map { prep =>
                    val need = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)))
                    Default.fillRemaining(Context(raw), pinnedCfg(server), prep, need, Chunk.empty).map { staged =>
                        val units = Default.group(raw)
                        val markerA =
                            Default.summaryMarker(Span(0, 1, Chunk(0)), raw, units, Level.Summary, raw.size, Dict.empty, CompactionState())
                        assert(
                            staged.summaryOf(SpanKey(0, 1)).isEmpty,
                            "the failed fill leaves its slot empty (a dropped artifact, not an error)"
                        )
                        assert(
                            staged.summaryOf(SpanKey(1, 2)) == Present("realB"),
                            "the sibling fill still lands; one failure never poisons the batch"
                        )
                        assert(
                            markerA.content.contains("summary unavailable"),
                            "an empty slot renders the fixed-size substitute elision at the summary level"
                        )
                        noBlockingConstructs()
                    }
                }
            }
        }
    }

    // The no-blocking-construct grep gate over the touched main sources (jvm-only file scan; a no-op where the source
    // is not reachable off the JVM). Every wait in the preparation path is a Fiber.get/Channel suspension.
    def noBlockingConstructs()(using kyo.test.AssertScope): Unit =
        val banned = List("Thread.sleep", "synchronized", "CountDownLatch", "Future.await", ".await(", "Await.", "AllowUnsafe")
        List("Compactor.scala", "LLM.scala").foreach { name =>
            readMainSourceOpt(name).foreach { text =>
                banned.foreach(b => assert(!text.contains(b), s"$name must carry no blocking construct: $b (INV-008/INV-002)"))
            }
        }
    end noBlockingConstructs

    def readMainSourceOpt(fileName: String): Option[String] =
        try
            val relative   = s"shared/src/main/scala/kyo/$fileName"
            val candidates = List(new java.io.File(relative), new java.io.File("kyo-ai", relative), new java.io.File(s"../$relative"))
            candidates.find(_.exists()).map(f => scala.io.Source.fromFile(f, "UTF-8").mkString)
        catch case _: Throwable => None

end CompactorPreparationTest
