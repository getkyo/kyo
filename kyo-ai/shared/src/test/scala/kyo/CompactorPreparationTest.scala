package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** The background preparation model: the single-flight fiber (arming, disarm, one run per session),
  * need-shaped fills, the join in its invisible / running / huge-turn-synchronous forms, run-level
  * lifecycle leak-freedom, and the fill-failure degrade. Deterministic throughout: occupancy is pinned via
  * the usage anchor, fills are scripted through TestCompletionServer, and every wait is an async suspension
  * (Channel/Fiber), never a sleep.
  */
class CompactorPreparationTest extends kyo.test.Test[Any]:

    private def axisOf(c: Config): Compactor.internal.Axis =
        Compactor.internal.axis(Compactor.Tuning(), c)
    // The shipped policy and calibration, so these tests read the same values the default compactor runs
    // re-bound because it now takes the tuning that owns its floor constants.
    private val tuning      = Compactor.Tuning()
    private val calibration = Compactor.internal.Calibration()
    import calibration.*
    import tuning.*
    private def keepFloor(regions: Int): Double = Compactor.internal.keepFloor(regions, tuning)

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
        Context(c).withCompaction(Compaction.State(lastUsage = Present(occ), lastUsageRawSize = c.size))

    // A summarizer pinned at the test server; resolveFillConfig(Present) routes every degraded fill here.
    def pinnedCfg(server: TestCompletionServer): Config =
        cfg().apiUrl(server.baseUrl)

    // The fill runs through the typed ai.gen[String] API (like the analysis pass), satisfied by a RESULT-TOOL
    // call carrying the summary as its resultValue, so a command harness (which rejects a schemaless completion)
    // produces a summary too. Not bare content: the raw-completion path was the QA-6/runFill defect.
    def fillBody(summary: String): String =
        val args = s"""{"resultValue":${Json.encode(summary)}}"""
        val esc  = args.replace("\\", "\\\\").replace("\"", "\\\"")
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":"$esc"}}]}}]}"""
    end fillBody

    def sameFiber(a: Maybe[Fiber[Unit, Any]], b: Maybe[Fiber[Unit, Any]]): Boolean =
        (a, b) match
            // cast: fiber-identity reference check, no Fiber identity API to compare on
            case (Present(x), Present(y)) => x.asInstanceOf[AnyRef] eq y.asInstanceOf[AnyRef]
            case _                        => false

    // ==== single-flight arming ====

    "single-flight: three arming passes share ONE run" in {
        val ctx    = forcedCtx(7000, sm("s"), um("u"), am("a"))
        val config = cfg()
        LLM.run(config) {
            AI.init.map { ai =>
                Preparation.init.map { prep =>
                    Default.armBelowBoundary(ctx, config, prep).map { s1 =>
                        prep.inFlight.get.map { f1 =>
                            Default.armBelowBoundary(ctx, config, prep).map { s2 =>
                                Default.armBelowBoundary(ctx, config, prep).map { s3 =>
                                    prep.inFlight.get.map { f3 =>
                                        prep.armed.get.map { armed =>
                                            assert(f1.isDefined, "the first arming pass forks a single-flight fiber into inFlight")
                                            assert(
                                                sameFiber(f1, f3),
                                                "a pass with a run already in flight never forks a second; the handle is identical"
                                            )
                                            assert(armed, s"the prepare band is latched as armed, got $armed")
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

    "re-arm tops up the delta only; write-once leaves already-filled spans untouched" in {
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

    "the fill set is exactly the summary-level spans of A_prep (skip pinned + pointer)" in {
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

    "the projected-summary set is a superset of the size boundary's consumed summary set" in {
        val ctx      = closedCtx(Chunk.from((0 until 6).map(i => tok(am(s"region $i " + ("x" * 60)), 700))))
        val units    = Default.group(ctx.raw)
        val spans    = Default.formSpans(units, ctx.raw, cfg())
        val scores   = Dict.from(units.toList.map(u => u.id -> 0.001).toMap) // all demotable
        val config   = cfg()
        val aPrep    = Default.projectedAssignment(ctx, units, spans, scores, config, ctx.raw.size, Dict.empty)
        val prepNeed = Default.fillNeed(spans, aPrep).map(_.start).toSet
        // A_fresh at a strictly HIGHER actual occupancy than the projected boundary.
        val aFresh       = Default.cut(ctx, units, spans, scores, axisOf(config).high * 2, axisOf(config).low, ctx.raw.size, Dict.empty)
        val freshSummary = spans.filter(sp => aFresh.get(sp.start).contains(Level.Summary)).map(_.start)
        assert(spans.nonEmpty, "the closed prefix forms at least one demotable span")
        assert(
            freshSummary.forall(s => prepNeed.contains(s)),
            s"every span the actual boundary renders at the summary level was already bought by A_prep, got fresh=$freshSummary prep=$prepNeed"
        )
    }

    // ==== the join ====

    "the invisible case: an empty-need join returns instantly with no fill" in {
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

    "the huge-turn synchronous case: no run armed, the boundary starts + joins the exact need" in {
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

    // ==== a fired boundary arms the analysis (BUG-A) + the join never blocks on the run (BUG-B) ====

    "a fired boundary arms the single-flight run so the analysis engages, even with no prior arm (BUG-A)" in {
        // Recent-only messages: no closed regions, so the analysis and fill need are both empty and the
        // forked run completes with no wire call. The point is purely that a fired boundary ARMS the run at
        // all: before the fix the boundary path never forked, so a session that jumped the prepare band (or
        // lived at/above effectiveHigh) never analyzed. occ 9000 >= effectiveHigh 8192.
        val ctx    = forcedCtx(9000, sm("s"), um("u"), am("a"))
        val config = cfg()
        LLM.run(config) {
            AI.init.map { ai =>
                Preparation.init.map { prep =>
                    prep.inFlight.get.map { before =>
                        Default.boundaryPrepare(ctx, config, prep).map { _ =>
                            prep.inFlight.get.map { after =>
                                assert(before.isEmpty, "precondition: no run is armed before the boundary")
                                assert(after.isDefined, "a fired boundary forks the single-flight run, arming the analysis (BUG-A)")
                            }
                        }
                    }
                }
            }
        }
    }

    "a fired boundary re-arms when the cell still holds a COMPLETED handle (BUG-A completed-handle subtlety)" in {
        // forkPreparation's onComplete deregisters but does not clear the cell, so a finished run can leave a
        // Present handle behind. A fired boundary must NOT count that as live: it re-arms with a fresh run.
        val ctx    = forcedCtx(9000, sm("s"), um("u"), am("a"))
        val config = cfg()
        LLM.run(config) {
            AI.init.map { ai =>
                Preparation.init.map { prep =>
                    Fiber.initUnscoped(Kyo.unit).map { doneFiber =>
                        doneFiber.get.andThen {
                            prep.inFlight.set(Present(doneFiber)).andThen {
                                Default.boundaryPrepare(ctx, config, prep).map { _ =>
                                    prep.inFlight.get.map { after =>
                                        assert(after.isDefined, "the run is armed after the boundary")
                                        assert(
                                            !sameFiber(Present(doneFiber), after),
                                            "a completed handle is replaced by a fresh run, not treated as live (BUG-A)"
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

    "the boundary join awaits ONLY its fill need, never the in-flight run: a parked run does not block (BUG-B)".notJs in {
        // A fiber parked forever on an empty Channel stands in for a run mid-analysis. If the join did
        // fiber.get on it (the pre-fix behavior) this test would hang; the fix fills the exact need directly
        // (write-once fillRemaining) and returns while the run finishes in the background.
        val raw = Chunk[Message](am("r0 " + ("x" * 30)))
        Channel.initUnscoped[Unit](1).map { gate =>
            TestCompletionServer.run { server =>
                server.enqueueBody(fillBody("s0")).andThen {
                    Preparation.init.map { prep =>
                        Fiber.initUnscoped(Abort.run[Closed](gate.take).unit).map { parked =>
                            prep.inFlight.set(Present(parked)).andThen {
                                val need = Chunk(Span(0, 1, Chunk(0)))
                                Default.joinPreparation(Context(raw), pinnedCfg(server), prep, need, Chunk.empty).map { staged =>
                                    parked.interrupt.andThen {
                                        assert(
                                            staged.summaryOf(SpanKey(0, 1)) == Present("s0"),
                                            "the join fills its exact need without blocking on the in-flight run (BUG-B)"
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

    // ==== lifecycle leak-freedom ====

    "lifecycle: run teardown releases every seated compaction state, so no fiber outlives the run" in {
        // The property survived the boundary rewrite; its mechanism changed. It used to assert that a
        // run-level registry of FIBERS was swept at exit. Teardown now releases compactor-and-state PAIRS,
        // which is what lets it work for a compactor whose state the framework cannot read.
        //
        // Per-session release alone cannot cover this and that is why the run-level sweep still exists:
        // the threaded LLM.State lives inside the handler, so on an abnormal exit the continuation never
        // runs, and the finalizer is closed over BEFORE the run so it cannot see the final state. The
        // registry is the bridge, a mutable cell the finalizer captures and each seating writes into.
        Channel.initUnscoped[Unit](1).map { gate => // never put: the fiber parks on take until interrupted
            Fiber.initUnscoped(Abort.run[Closed](gate.take).unit).map { parked =>
                // A compactor whose entire state IS the parked fiber, so releasing it is observable.
                val probe = new Compactor[Any]:
                    type State = Fiber[Unit, Any]
                    def initState(using Frame): Fiber[Unit, Any] < Sync = Kyo.lift(parked)
                    def compact(ctx: Context, state: Fiber[Unit, Any])(using
                        Frame
                    ): Compactor.Decision < (LLM & Async & Abort[AIGenException]) =
                        Kyo.lift(Compactor.Decision.Unchanged)
                    override def release(state: Fiber[Unit, Any])(using Frame): Unit < Sync = state.interrupt.unit
                LLM.run(cfg()) {
                    LLM.env.map { env =>
                        Compactor.Seated.init(probe).map { seated =>
                            env.compactions match
                                case Present(r) => r.getAndUpdate(_ + seated).unit
                                case Absent     => Kyo.lift(assert(false, "LLM.run must seat the release registry"))
                        }
                    }
                }.andThen {
                    parked.getResult.map { res =>
                        assert(
                            !res.isSuccess,
                            "the run-level sweep released the seated state on exit, interrupting its fiber (no leak past the run)"
                        )
                    }
                }
            }
        }
    }

    // ==== fill-failure degrade + no blocking ====

    "a failed fill degrades to the substitute elision; no auxiliary failure fails; no thread blocks".notJs in {
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
                            Default.summaryMarker(Span(0, 1, Chunk(0)), raw, units, Level.Summary, raw.size, Dict.empty, Compaction.State())
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

    // The no-blocking-construct grep gate over the touched main sources; the caller is .notJs gated since
    // the file scan uses java.io.File. Every wait in the preparation path is a Fiber.get/Channel suspension.
    def noBlockingConstructs()(using kyo.test.AssertScope): Unit =
        val banned = List("Thread.sleep", "synchronized", "CountDownLatch", "Future.await", ".await(", "Await.", "AllowUnsafe")
        List("Compactor.scala", "LLM.scala").foreach { name =>
            readMainSourceOpt(name).foreach { text =>
                banned.foreach(b => assert(!text.contains(b), s"$name must carry no blocking construct: $b"))
            }
        }
    end noBlockingConstructs

    def readMainSourceOpt(fileName: String): Maybe[String] =
        try
            val relative   = s"shared/src/main/scala/kyo/$fileName"
            val candidates = Chunk(new java.io.File(relative), new java.io.File("kyo-ai", relative), new java.io.File(s"../$relative"))
            Maybe.fromOption(candidates.find(_.exists()).map(f => scala.io.Source.fromFile(f, "UTF-8").mkString))
        catch case ex: Throwable if scala.util.control.NonFatal(ex) => Absent

    // ==== the validity gate, adoption, and the write-once slot ====
    //
    // Folded in from a separate file: these assert preparation's own contract, so they belong with the
    // fiber tests rather than beside them. The gate and the slot are pure structural data, so most of
    // this is deterministic and in-memory; the zero-new-calls property is witnessed through the server's
    // capture log.

    "adopt on pinning-partition agreement (depth may differ, the partition is equal)" in {
        val spans = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)), Span(2, 3, Chunk(2)))
        // Both assign the SAME pinned set {0} and the SAME demoted set {1,2}; only the pass-2 depth differs.
        val aPrep  = Dict[Int, Level]((1, Level.Summary), (2, Level.Summary))
        val aFresh = Dict[Int, Level]((1, Level.Summary), (2, Level.Pointer))
        assert(
            Default.validityGate(aPrep, aFresh, spans),
            "agreement holds on the pinned-vs-demoted partition (span 0 pinned in both; 1,2 demoted in both); a summary-vs-pointer depth difference never gates"
        )
        // A depth-only difference across every demoted span still agrees.
        val allTerse = Dict[Int, Level]((1, Level.Terse), (2, Level.Terse))
        assert(Default.validityGate(aPrep, allTerse, spans), "the gate compares definedness (pinned vs demoted), not the Level")
    }

    "both directions render fresh, and a false invalidation makes ZERO new model calls" in {
        val spans = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)))
        // (soundness) A_prep demotes span (1,2) but A_fresh PINS it (absent from the assignment).
        val aPrepSound  = Dict[Int, Level]((1, Level.Summary))
        val aFreshSound = Dict.empty[Int, Level]
        assert(
            !Default.validityGate(aPrepSound, aFreshSound, spans),
            "soundness: prepared demotes but fresh pins -> invalidate (adopting would demote live content)"
        )
        // (completeness) A_prep pins span (1,2) but A_fresh DEMOTES it.
        val aPrepComplete  = Dict.empty[Int, Level]
        val aFreshComplete = Dict[Int, Level]((1, Level.Summary))
        assert(
            !Default.validityGate(aPrepComplete, aFreshComplete, spans),
            "completeness: prepared pins but fresh demotes -> invalidate (adopting would strand below-keep mass)"
        )
        // ZERO new model calls: the prepared summary already sits write-once in staging, so the boundary
        // reuses it and issues no completion even though the assignment it derived is discarded.
        val raw = Chunk[Message](am("r0"), am("r1"))
        TestCompletionServer.run { server =>
            Preparation.init.map { prep =>
                prep.staged.set(Staged().withSummary(SpanKey(1, 2), "prepared")).andThen {
                    Default.joinPreparation(Context(raw), pinnedCfg(server), prep, Chunk(Span(1, 2, Chunk(1))), Chunk.empty).map { staged =>
                        server.captured.map { cap =>
                            assert(cap.isEmpty, "the write-once staged bytes are reused; a false invalidation issues zero completions")
                            assert(staged.summaryOf(SpanKey(1, 2)) == Present("prepared"), "the reused summary is the already-staged blob")
                        }
                    }
                }
            }
        }
    }

    "the adoption splice is prepared-prefix ++ fresh-remainder ++ verbatim-tail" in {
        val raw   = Chunk.from((0 until 6).map(i => am(s"region $i CONTENT")))
        val units = Default.group(raw)
        // spans 0..3 are the closed prefix (demoted); regions 4,5 are the verbatim tail (no span demotes them).
        val spans     = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)), Span(2, 3, Chunk(2)), Span(3, 4, Chunk(3)))
        val demotions = Dict[Int, Level]((0, Level.Summary), (1, Level.Summary), (2, Level.Summary), (3, Level.Summary))
        // the prepared prefix (0..2) carries staged summaries; the newly-closed remainder (3,4) is empty (fresh).
        val state = Compaction.State().withSummary(0, 1, "S0").withSummary(1, 2, "S1").withSummary(2, 3, "S2")
        val view  = Default.project(raw, units, spans, demotions, raw.size, Dict.empty, state)
        val text  = view.map(_.content).mkString("\n")
        assert(
            text.contains("S0") && text.contains("S1") && text.contains("S2"),
            "the prepared prefix renders its write-once summary bytes"
        )
        val i0         = text.indexOf("S0")
        val i2         = text.indexOf("S2")
        val iRemainder = text.indexOf("summary unavailable") // the fresh, unfilled remainder span (3,4)
        val iTail      = text.indexOf("region 4 CONTENT")    // the verbatim tail
        assert(i0 >= 0 && i2 > i0, "the prepared-prefix summaries render in order")
        assert(iRemainder > i2, "the fresh remainder (empty slot -> substitute elision) renders after the prepared prefix")
        assert(iTail > iRemainder, "the verbatim tail renders last, after the fresh remainder")
        assert(text.contains("region 5 CONTENT"), "the verbatim tail is preserved (no message dropped across the splice)")
    }

    // ==== write-once first-writer-wins ====

    "write-once first-writer-wins across a fiber/boundary race (either order)" in {
        // background stages "bg" first, boundary "fg" second -> bg wins.
        val bgFirst = Compaction.State().withSummary(3, 7, "bg").withSummary(3, 7, "fg")
        // boundary stages "fg" first, background "bg" second -> fg wins.
        val fgFirst = Compaction.State().withSummary(3, 7, "fg").withSummary(3, 7, "bg")
        assert(bgFirst.summaryOf(3, 7) == Present("bg"), "whichever bytes reach the slot first are permanent (bg-first)")
        assert(fgFirst.summaryOf(3, 7) == Present("fg"), "whichever bytes reach the slot first are permanent (fg-first)")
        // the staging cell mirrors the same first-writer-wins discipline.
        val staged = Staged().withSummary(SpanKey(3, 7), "bg").withSummary(SpanKey(3, 7), "fg")
        assert(
            staged.summaryOf(SpanKey(3, 7)) == Present("bg"),
            "Staged.withSummary is first-writer-wins, so the race is idempotent (no lock needed)"
        )
    }

    "a second write / re-emitted artifact to a filled slot is discarded" in {
        val filled = Compaction.State().withSummary(3, 7, "first")
        val second = filled.withSummary(3, 7, "second")
        assert(second.summaryOf(3, 7) == Present("first"), "a second write to a filled slot is discarded (state)")
        val staged = Staged().withSummary(SpanKey(3, 7), "first").withSummary(SpanKey(3, 7), "second")
        assert(staged.summaryOf(SpanKey(3, 7)) == Present("first"), "a re-emitted artifact to a filled slot is discarded (staging cell)")
        // the loser never renders.
        val raw = Chunk.from((0 until 8).map(i => am(s"r$i")))
        val marker =
            Default.summaryMarker(Span(3, 7, Chunk(3, 4, 5, 6)), raw, Default.group(raw), Level.Summary, raw.size, Dict.empty, second)
        assert(
            marker.content.contains("first") && !marker.content.contains("second"),
            "the served view carries the first bytes and never the discarded loser"
        )
    }

    // ==== terse-REAL ====

    "terse-REAL a landed summary blob renders terse as marker + code-point-safe prefix of the REAL bytes" in {
        val sp    = Span(3, 7, Chunk(3, 4, 5, 6))
        val raw   = Chunk.from((0 until 8).map(i => am(s"r$i " + ("y" * 20))))
        val units = Default.group(raw)
        val real  = "R" * (tersePrefixChars + 100)
        val state = Compaction.State().withSummary(3, 7, real)
        val terse = Default.summaryMarker(sp, raw, units, Level.Terse, raw.size, Dict.empty, state)
        assert(terse.content.contains("R" * tersePrefixChars), "terse carries a real prefix of the landed fill bytes")
        assert(!terse.content.contains("R" * (tersePrefixChars + 1)), "the terse prefix truncates at tersePrefixChars")
        assert(
            !terse.content.contains("summary unavailable"),
            "with a real slot filled, terse is a real prefix, not the blob-less substitute"
        )
        assert(terse.content.contains("recall(3)"), "terse carries the same recall id as the summary render")
        // the blob-less path is unchanged: an EMPTY slot at the summary level is still the substitute elision.
        val empty = Default.summaryMarker(sp, raw, units, Level.Summary, raw.size, Dict.empty, Compaction.State())
        assert(empty.content.contains("summary unavailable"), "an empty slot renders the fixed-size substitute elision (P2 unchanged)")
    }

    "a session whose AI is collected is released at the next prune, not left to the run-end sweep" in {
        // Op.Discard releases a seat; State.pruned did not. Prune is a PURE function, so the GC path
        // dropped a session's compaction seat with NO release, and the only backstop was the run-end
        // sweep. That made the eager teardown the handler claims for itself false on the ordinary way an
        // instance ends, which is dropping the reference: there is no public discard, only ai.reset, and
        // the whole State design keys on a WeakReference precisely so a dropped AI is collectable.
        //
        // WeakReference.clear() is the JDK's own deterministic stand-in for collection, and it is what
        // makes this testable at all: after it isValid is false forever, so prune sees exactly the slot
        // it would see after a real collection, with no GC forcing and no timing assumption. Equality on
        // AIRef is by the AI's id, which survives the clear, so the slot stays addressable.
        AtomicInt.init(0).map { releases =>
            val probe = new Compactor[Any]:
                type State = Unit
                def initState(using Frame): Unit < Sync = Kyo.unit
                def compact(ctx: Context, state: Unit)(using
                    Frame
                ): Compactor.Decision < (LLM & Async & Abort[AIGenException]) =
                    Kyo.lift(Compactor.Decision.Unchanged)
                override def release(state: Unit)(using Frame): Unit < Sync = releases.incrementAndGet.unit
            TestCompletionServer.run { server =>
                server.enqueueBody(fillBody("seated")).andThen {
                    LLM.run(cfg().apiUrl(server.baseUrl)) {
                        AI.init.map(_.enable(probe)).map { ai =>
                            ai.gen[String]("seat the compactor").andThen {
                                LLM.state.map { before =>
                                    val seat = before.sessionOf(ai).compaction
                                    assert(seat.isDefined, "REGIME: the gen must have seated the compactor, or there is nothing to release")
                                    // Minting is the prune point: Op.Init is where dead slots are dropped.
                                    Sync.defer(ai.ref.clear())
                                        .andThen(AI.init)
                                        .andThen(LLM.env)
                                        .map { env =>
                                            val enrolled = env.compactions match
                                                case Present(r) => r.get.map(seats => seat.exists(seats.contains))
                                                case Absent     => Kyo.lift(false)
                                            enrolled.map { stillEnrolled =>
                                                releases.get.map { n =>
                                                    assert(
                                                        n == 1,
                                                        s"a collected session must be released at the prune rather than waiting for run exit, got $n"
                                                    )
                                                    assert(
                                                        !stillEnrolled,
                                                        "and unenrolled from the run registry, or its staged summaries and analyses stay reachable for the whole run"
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

end CompactorPreparationTest
