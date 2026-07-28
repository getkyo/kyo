package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** The analysis's effect on liveness under pressure: the worked-example semantic keep (a scripted
  * Relates edge lifts the pool-sizing region and changes the keep decision) and the degradation order (a
  * failed or absent analysis degrades the graph to structural-only and compaction still works, the
  * analyzed layer strictly additive). Deterministic: the analysis wire is scripted through
  * TestCompletionServer, occupancy is pinned, and every wait is an async suspension, never a sleep.
  */
class CompactorReplayTest extends kyo.test.Test[Any]:

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

    def cfg(window: Int = 16384): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    // The analysis pass runs through the typed ai.gen[Analysis] API, satisfied by a RESULT-TOOL call
    // carrying the Analysis as its resultValue (not bare JSON in the assistant content).
    def analysisReply(a: Analysis): String =
        val args = s"""{"resultValue":${Json.encode(a)}}"""
        val esc  = args.replace("\\", "\\\\").replace("\"", "\\\"")
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":"$esc"}}]}}]}"""
    end analysisReply

    // The experiment-B/C fixture, deliberately vocab-disjoint so m9 (region 8) and
    // the pool-sizing answer (region 2) share NO structural token: only a semantic Relates edge can link
    // them. One distractor (region 4) carries a shared hub token with regions 5,6 so it out-ranks the
    // pool answer under structural-only scoring. Tail seed lands on the recent region 8.
    def experimentRaw(): Chunk[Message] =
        Chunk[Message](
            sm("system prompt"),                                   // 0
            um("please help me size the connection pool"),         // 1 first user (task)
            am("set the maximum to a safe ceiling for stability"), // 2 the pool-sizing answer (m2)
            am("format calendar dates with a locale pattern"),     // 3 distractor
            am("apply the flexbox alignment rule everywhere"),     // 4 distractor hub
            am("write the flexbox alignment rule as a helper"),    // 5 distractor (shares flexbox alignment rule)
            am("reuse the flexbox alignment rule in tests"),       // 6 distractor (shares flexbox alignment rule)
            am("configure the structured logging appender"),       // 7 distractor
            am("bump what we discussed earlier to thirty-two"),    // 8 m9 (vocab-disjoint from region 2)
            um("what did we land on")                              // 9 last user (objective, tail)
        )

    // A snapshot whose seeded head regions (system + first task turn) are closed and pinned while ten
    // recent turns fill the tail band: analysisPending names the closed head regions and fillNeed is
    // empty, so preparationRun fires exactly the ONE analysis call per arming event and no fills.
    def closedCtx(): Context =
        val head = Chunk[Message](sm("system prompt"), um("the first task question"))
        val mids = (0 until 10).map(i => tok(am(s"recent turn $i"), 30))
        Context(head.concat(Chunk.from(mids)))
    end closedCtx

    // The five demotable middle regions of the fixture.
    val middles = List(2, 3, 4, 5, 6)

    def liveness(raw: Chunk[Message], analyses: Chunk[RegionAnalysis]): Dict[Int, Double] =
        val units      = Default.group(raw)
        val superseded = Dict.empty[Int, Int]
        val graph      = Default.deriveGraph(units, raw, superseded, Default.analyzedEdges(analyses))
        val seed       = Default.seedVector(units, raw, Compaction.State())
        Default.score(units, graph, superseded, seed)
    end liveness

    def topMiddle(scores: Dict[Int, Double]): Int =
        middles.maxBy(id => scores.get(id).getOrElse(0.0))

    // ==== the semantic keep ====

    "a scripted Relates edge lifts the pool-sizing region and changes the keep decision under pressure" in {
        val raw = experimentRaw()
        // structural-only: no analyses.
        val structural = liveness(raw, Chunk.empty)
        // WITH a Relates edge from m9 (region 8) to the pool-sizing answer (region 2).
        val analyzed  = liveness(raw, Chunk(RegionAnalysis(8, Chunk(Relation(2, RelationKind.Relates)))))
        val structTop = topMiddle(structural)
        val anaTop    = topMiddle(analyzed)
        assert(structTop != 2, s"structural-only keeps a distractor (region $structTop), not the pool-sizing answer (region 2)")
        assert(anaTop == 2, "with the Relates edge the pool-sizing answer (region 2) becomes the top-ranked keep")
        assert(
            analyzed.get(2).getOrElse(0.0) > structural.get(2).getOrElse(0.0),
            "the Relates edge strictly lifts region 2's liveness (the semantic-only keep the live task was asking about)"
        )
    }

    // ==== the degradation order ====

    "a failed or absent analysis degrades the graph to structural-only; the analyzed layer is strictly additive" in {
        val ctx = closedCtx()
        val raw = ctx.raw
        TestCompletionServer.run { server =>
            val config  = cfg().apiUrl(server.baseUrl)
            val pending = Default.analysisPending(ctx, config)
            // the closed head regions (region 1 relates to region 0), a valid backward in-reach relation.
            val valid = Analysis(Chunk(RegionAnalysis(1, Chunk(Relation(0, RelationKind.Relates)))))
            // Route (i): a VALID analysis is scripted (present). Route (ii): the SAME seam where the call
            // FAILS ("not json" -> HttpException), so nothing stages (equivalently, no analysis staged).
            server.enqueueBody(analysisReply(valid)).andThen(server.enqueueBody("not json")).andThen {
                Preparation.init.map { prepA =>
                    Default.preparationRun(ctx, config, prepA, Chunk.empty).andThen {
                        prepA.staged.get.map { stagedA =>
                            val stateA = Default.adopt(Compaction.State(), stagedA)
                            val unitsR = Default.group(raw)
                            val gA =
                                Default.deriveGraph(unitsR, raw, Dict.empty[Int, Int], Default.analyzedEdges(stateA.analyses))
                            val hasSemanticA =
                                unitsR.flatMap(u => gA.edges.get(u.id).getOrElse(Chunk.empty))
                                    .exists(e => e.kind == EdgeKind.Relatedness || e.kind == EdgeKind.Dependency)
                            assert(pending.nonEmpty, "the fixture has closed pending regions to analyze")
                            assert(stateA.analyses.nonEmpty, "route (i): the valid analysis is adopted into compaction state")
                            assert(hasSemanticA, "route (i): the analyzed Relatedness edge appears in the derived graph")
                            Preparation.init.map { prepB =>
                                Default.preparationRun(ctx, config, prepB, Chunk.empty).andThen {
                                    prepB.staged.get.map { stagedB =>
                                        val stateB = Default.adopt(Compaction.State(), stagedB)
                                        val gB =
                                            Default.deriveGraph(unitsR, raw, Dict.empty[Int, Int], Default.analyzedEdges(stateB.analyses))
                                        val hasSemanticB =
                                            unitsR.flatMap(u => gB.edges.get(u.id).getOrElse(Chunk.empty))
                                                .exists(e => e.kind == EdgeKind.Relatedness || e.kind == EdgeKind.Dependency)
                                        assert(
                                            stateB.analyses.isEmpty,
                                            "route (ii): the failed analysis leaves compaction state unanalyzed"
                                        )
                                        assert(
                                            !hasSemanticB,
                                            "route (ii): the derived graph carries only structural (Adjacency + Reference) edges"
                                        )
                                        // gen still returns a valid, hard-limit-bounded compacted view on the degraded route.
                                        LLM.run(config)(Default.renderView(ctx.withCompaction(stateB), config)).map { view =>
                                            assert(
                                                view.nonEmpty,
                                                "route (ii): compaction still produces a valid view with no analysis staged"
                                            )
                                            assert(
                                                Default.viewTokens(view) <= axisOf(config).hard,
                                                "route (ii): the degraded view stays within the hard limit"
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

    val noInfos: Chunk[Tool.internal.Info[?, ?, LLM]] = Chunk.empty

    // Ten unique lowercase words per region, seeded off the index, so no two regions share a token.
    def distinctWords(seed: Int): String =
        val letters = "abcdefghijklmnopqrstuvwxyz"
        Chunk.from((0 until 10).map { k =>
            val n = seed * 31 + k * 7 + 3
            List(letters((n) % 26), letters((n / 26 + 5) % 26), letters((n / 7 + 11) % 26), letters((n / 3 + 19) % 26))
                .mkString
        }).mkString(" ")
    end distinctWords

    // A long, vocab-disjoint transcript: a seeded head and recent tail, a deep middle band far enough from
    // both that its regions fall below keep and form clean stale-verbatim spans. Each region is one message.
    def staleBandRaw(): Chunk[Message] =
        val head = Chunk[Message](
            tok(sm("orientation preface for the working session"), 50),
            tok(um("please assist with the primary objective outcome"), 50)
        )
        val middle = Chunk.from((0 until 30).map(i => tok(am(distinctWords(i)), 400)))
        val recent = Chunk.from((0 until 10).map(i => tok(am(distinctWords(100 + i)), 400)))
        val tail   = Chunk[Message](tok(am(distinctWords(900) + " closing recap"), 1400))
        head.concat(middle).concat(recent).concat(tail)
    end staleBandRaw

    // A Context whose usage anchor pins occupancy to `occ` (compacted == raw, so the offline suffix is zero).
    def atOccupancy(raw: Chunk[Message], occ: Int): Context =
        Context(raw, raw, Present(Compaction.State(lastUsage = Present(occ), lastUsageRawSize = raw.size)))

    /** The STALE MASS over a served view: the summed token count of every span the cut deems demotable
      * (its hottest member below the keep floor) that the view has not already demoted.
      *
      * This is derived here rather than called, because it is the one quantity that names exactly what
      * pass 1 is obliged to consume. It was previously computed in production as the input to a
      * below-boundary relevance tripwire; that trigger is deleted (it never fired in any observation, and
      * its success mode was more cache-invalidating boundaries), but the OBLIGATION it measured belongs to
      * the cut and is asserted below.
      */
    def staleMass(ctx: Context, config: Config)(using Frame): Int =
        val units      = Default.group(ctx.raw)
        val spans      = Default.formSpans(units, ctx.raw, config)
        val superseded = Default.supersession(units, Default.superKeysFrom(units, ctx.raw, noInfos))
        val graph      = Default.deriveGraph(units, ctx.raw, superseded, Chunk.empty)
        val seed       = Default.seedVector(units, ctx.raw, ctx.compactionState)
        val scores     = Default.score(units, graph, superseded, seed)
        val low        = axisOf(config).low
        val floor      = keepFloor(units.size)
        val demoted    = Default.demotedOrigins(ctx.compacted)
        val byId       = units.foldLeft(Dict.empty[Int, Region])((m, u) => m.update(u.id, u))
        spans
            .filter(sp => Default.spanMaxLiveness(sp, scores) < floor && !sp.regionIds.exists(id => demoted.contains(id)))
            .foldLeft(0)((n, sp) => n + sp.regionIds.foldLeft(0)((t, id) => t + byId.get(id).map(_.tokens).getOrElse(0)))
    end staleMass

    "a boundary demotes EVERY stale-eligible span, so the stale mass restarts at zero" in {
        // Pass 1 of the cut is unconditional and has no stop condition: every demotable span goes to its
        // summary level whether or not size requires it. The observable form of that is this: measure the
        // stale mass before and after a boundary, and nothing demotable may survive. A weaker check ("the
        // view carries some demotion marker") passes an implementation that demotes one span and stops.
        val raw    = staleBandRaw()
        val config = cfg()
        // Occupancy above effectiveHigh (8192 at window 16384), so the next boundary fires by size. render
        // is model-free; the mock wire is up so no live provider is reached and no completion is scripted.
        val ctx = atOccupancy(raw, 9000)
        assert(occupancy(ctx) > axisOf(config).high, "REGIME: the fixture sits above effectiveHigh, so the boundary fires")
        val before = staleMass(ctx, config)
        assert(before > 0, s"REGIME: the fixture must carry a stale-verbatim band before the boundary, got $before")
        TestCompletionServer.run { server =>
            LLM.run(config.apiUrl(server.baseUrl))(Default.renderView(ctx, config)).map { view =>
                val after = Context(raw, view, ctx.compaction)
                assert(
                    view.exists(_.origin.isDefined),
                    "the boundary demotes the stale spans, so the view carries demotion markers"
                )
                assert(
                    staleMass(after, config) == 0,
                    s"pass 1 must leave nothing demotable behind, stale mass went $before -> ${staleMass(after, config)}"
                )
            }
        }
    }

end CompactorReplayTest
