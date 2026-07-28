package kyo

import Compactor.Tuning
import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** Every lever must actually silence the mechanism it names.
  *
  * Two surfaces are covered. [[Compactor.Mechanism]] is the caller's: dropping a member from
  * [[Compactor.Tuning.mechanisms]] must stop that mechanism's own work and nothing else's.
  * `Compactor.internal.Calibration` is the ablation surface: moving one measured constant must move what
  * the compactor decides.
  *
  * These are guards on the levers themselves, not on the mechanisms. A study reads a mechanism's value
  * from the difference between an arm with it on and one with it off, so a lever that quietly does nothing
  * does not fail loudly: it reports every mechanism as worthless and concludes the subsystem is inert.
  * That failure mode is silent and total, which is why each lever is pinned to an observable it must move,
  * plus the identity property that the shipped defaults change nothing.
  */
class CompactorTuningTest extends kyo.test.Test[Any]:

    private def axisOf(c: Config): Compactor.internal.Axis =
        Compactor.internal.axis(Compactor.Tuning(), c)

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def reg(id: Int, tokens: Int = 1): Region         = Region(id, Chunk(id), false, tokens)
    def tok(m: Message, n: Int): Message              = stamp(m, TokenStamp("t", n))

    def graphOf(es: (Int, List[Edge])*): Graph =
        Graph(es.foldLeft(Dict.empty[Int, Chunk[Edge]]) { case (d, (from, edges)) => d.update(from, Chunk.from(edges)) })

    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    "the shipped defaults are the identity" - {

        "Tuning() ships every mechanism ON, so it is the shipped behavior" in {
            val t = Tuning()
            assert(t.mechanisms == Compactor.Mechanism.all, s"every mechanism ships enabled, got ${t.mechanisms}")
            assert(
                Compactor.Mechanism.values.forall(t.mechanisms.contains),
                "and `all` really is every member, so adding a mechanism cannot silently ship it off"
            )
            assert(t.keepShare == 1.35, "the shipped keep share, measured rather than chosen; see CompactorTest for the two obligations")
            assert(t.summaryOutputCap == 512, "the fill output cap")
            assert(Compactor.Mechanism.none.isEmpty, "and `none` is the empty set, the every-mechanism-off arm")
        }

        "Calibration() carries exactly the measured values the default compactor runs at" in {
            val c = Calibration()
            assert(c.adjacencyWeight == 1.0 && c.referenceWeight == 3.0, "structural edge weights")
            assert(c.dependencyWeight == 3.0 && c.relatednessWeight == 0.5, "analysis edge weights")
            assert(c.contentReferenceWeight == 1.5 && c.contentRarityFloor == 5000, "content-reference tier")
            assert(c.supersessionPenalty == 0.2, "supersession penalty")
            assert(
                eps(c.seedObjective + c.seedTask + c.seedTail + c.seedUnresolved + c.seedSystem, 1.0),
                s"the seed vector sums to 1.0, so a lever that zeroes one seed deflates total mass by that share"
            )
            assert(c.pprIterations == 20 && c.restartWeight == 0.15, "propagation")
        }

        "the two records are separate, so a policy change cannot disturb the calibration" in {
            // The split exists so a caller reads eleven policy fields instead of forty-six, and so the
            // measured constants are not presented as choices. Pinning that they do not leak into each other
            // is what keeps that true: a Tuning field silently shadowing a Calibration field would put a
            // fitted constant back on the caller's surface without anyone noticing.
            val policy = Tuning().productElementNames.toSet
            val calib  = Calibration().productElementNames.toSet
            assert(policy.intersect(calib).isEmpty, s"no field name may appear on both, got ${policy.intersect(calib)}")
            assert(policy.size == 10, s"the policy surface is small on purpose, got ${policy.size}: $policy")
            assert(
                calib.size > policy.size * 2,
                s"and the calibrated constants outnumber it several times over, which is the whole reason for the "
                    + s"split: ${calib.size} calibrated against ${policy.size} policy"
            )
        }

        "an ablated instance is a distinct compactor and the shipped one is untouched" in {
            // Ablation must not mutate shared state: the default instance has to keep answering with the
            // shipped values after an ablated instance exists, or arms would contaminate each other.
            val ablated = Compactor.internal.Default(Tuning(), Calibration(adjacencyWeight = 0.0))
            assert(!ablated.eq(Compactor.init), "an ablated instance is separate, not the shared singleton")
            val units = Chunk(reg(0), reg(1))
            val g     = graphOf((1, List(Edge(0, EdgeKind.Adjacency, Calibration().adjacencyWeight))))
            val seed  = Dict[Int, Double]((1, 1.0))
            assert(Default.score(units, g, Dict.empty[Int, Int], seed).get(0).exists(_ > 0.0), "the shipped instance still propagates")
        }
    }

    "the keyed-supersession source lever" - {

        "dropping KeyedSupersession empties a map that is otherwise populated" in {
            // Two regions carrying the same compaction key: the earlier is superseded by the later.
            val units = Chunk(reg(0), reg(1), reg(2))
            val keys  = Dict[Int, (String, Tool.Kind)]((0, ("db.yaml", Tool.Kind.Read)), (2, ("db.yaml", Tool.Kind.Write)))
            val on    = Default.supersession(units, keys)
            val off = Compactor.internal.Default(
                Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.KeyedSupersession),
                Calibration()
            ).supersession(units, keys)
            assert(on.nonEmpty, s"REGIME: the fixture must produce a keyed supersession to ablate, got $on")
            assert(off.isEmpty, s"the lever must empty the keyed map, got $off")
        }
    }

    "the analyzed-supersession source lever" - {

        "dropping AnalyzedSupersession empties the keyless map" in {
            val analyses = Chunk(RegionAnalysis(41, Chunk(Relation(14, RelationKind.Supersedes))))
            val on       = Default.analyzedSupersession(analyses)
            val off = Compactor.internal.Default(
                Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.AnalyzedSupersession),
                Calibration()
            ).analyzedSupersession(analyses)
            assert(on.get(14) == Present(41), s"REGIME: the fixture must detect supersession to ablate, got $on")
            assert(off.isEmpty, s"the lever must empty the analyzed map, got $off")
        }

        "the lever is independent of the keyed source" in {
            // The two sources merge into one map, so a lever that silenced both would confound every
            // per-source reading. Each must survive the other's ablation.
            val analyses = Chunk(RegionAnalysis(41, Chunk(Relation(14, RelationKind.Supersedes))))
            val keyedOff = Compactor.internal.Default(
                Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.KeyedSupersession),
                Calibration()
            )
            assert(
                keyedOff.analyzedSupersession(analyses).get(14) == Present(41),
                "silencing the keyed source must leave the analyzed source intact"
            )
        }
    }

    "the repoint channel lever" - {

        "dropping Repoint leaves an id at its stale target while the penalty still applies" in {
            // The two channels supersession acts through are the score penalty and edge repointing. This
            // pins that they are separately controllable: without it, "analyzed supersession off" would
            // still act through repointing, which is exactly the confound that voided an earlier round.
            val superseded = Dict[Int, Int]((14, 41))
            assert(Default.repoint(14, superseded) == 41, "REGIME: repointing must be active by default")
            val off = Compactor.internal.Default(Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Repoint), Calibration())
            assert(off.repoint(14, superseded) == 14, "the lever must leave the id at its stale target")
            // the penalty channel is untouched by the repoint lever
            val units = Chunk(reg(14), reg(15), reg(41))
            val g     = graphOf((15, List(Edge(14, EdgeKind.Reference, 1.0))))
            val seed  = Dict[Int, Double]((15, 1.0))
            val plain = off.score(units, g, Dict.empty[Int, Int], seed)
            val pen   = off.score(units, g, superseded, seed)
            assert(
                eps(pen.get(14).getOrElse(0.0), plain.get(14).getOrElse(0.0) * Calibration().supersessionPenalty),
                "with repointing off the penalty still multiplies the superseded region's score"
            )
        }
    }

    "the propagation lever" - {

        "pprIterations = 0 makes scores exactly the seed, isolating propagation from seed values" in {
            // The lever for "is PageRank propagation doing anything, as distinct from the seed placement".
            // A separate boolean would duplicate this, so the magnitude IS the lever.
            val units      = Chunk(reg(0), reg(1), reg(2))
            val g          = graphOf((1, List(Edge(0, EdgeKind.Adjacency, 1.0))), (2, List(Edge(1, EdgeKind.Adjacency, 1.0))))
            val seed       = Dict[Int, Double]((2, 1.0))
            val propagated = Default.score(units, g, Dict.empty[Int, Int], seed)
            val seedOnly = Compactor.internal.Default(Tuning(), Calibration(pprIterations = 0)).score(units, g, Dict.empty[Int, Int], seed)
            assert(eps(seedOnly.get(2).getOrElse(0.0), 1.0), s"with no iterations the seeded region keeps its seed, got ${seedOnly.get(2)}")
            assert(eps(seedOnly.get(0).getOrElse(0.0), 0.0), s"an unseeded region gets nothing without propagation, got ${seedOnly.get(0)}")
            assert(propagated.get(0).exists(_ > 0.0), "REGIME: propagation must reach region 0 by default, or there is nothing to ablate")
        }
    }

    "the edge-weight levers" - {

        "zeroing an edge class makes it inert, which is read on weight and score, never on edge presence" in {
            // A zeroed weight does NOT delete the edge: deriveGraph still emits it, with weight 0.0, and
            // `score` then normalizes it to a 0 share. So the observable for any edge-weight lever is the
            // weight (and the score it stops producing), never the presence of the EdgeKind, which stays.
            // Reading presence would report every edge lever as broken.
            val raw     = Chunk[Message](am("intro `Widget.field` here"), am("mid turn text"), am("later `Widget.field` again"))
            val units   = Default.group(raw)
            val ablated = Compactor.internal.Default(Tuning(), Calibration(referenceWeight = 0.0, contentReferenceWeight = 0.0))
            def refWeights(g: Graph): List[Double] =
                units.toList.flatMap(u =>
                    g.edges.get(u.id).getOrElse(Chunk.empty).toList.filter(_.kind == EdgeKind.Reference).map(_.weight)
                )
            val on  = refWeights(Default.deriveGraph(units, raw, Dict.empty[Int, Int]))
            val off = refWeights(ablated.deriveGraph(units, raw, Dict.empty[Int, Int]))
            assert(on.nonEmpty && on.forall(_ > 0.0), s"REGIME: the fixture must produce weighted reference edges, got $on")
            assert(off.nonEmpty && off.forall(_ == 0.0), s"the lever must zero every reference weight, got $off")

            // and the zeroing is what a score reading sees: the referenced region loses the mass the
            // reference edge was carrying to it.
            val seed     = Dict[Int, Double]((2, 1.0))
            val scoreOn  = Default.score(units, Default.deriveGraph(units, raw, Dict.empty[Int, Int]), Dict.empty[Int, Int], seed)
            val scoreOff = ablated.score(units, ablated.deriveGraph(units, raw, Dict.empty[Int, Int]), Dict.empty[Int, Int], seed)
            assert(
                scoreOn.get(0).getOrElse(0.0) != scoreOff.get(0).getOrElse(0.0),
                s"the ablation must move the referenced region's score: on=${scoreOn.get(0)} off=${scoreOff.get(0)}"
            )
        }
    }

    "the summary-fill lever" - {

        "dropping SummaryFills asks for no fills, so spans render the substitute elision" in {
            // The counterfactual the summary tier must be measured against. A span with no staged bytes
            // does NOT become terse: the terse step skips a span whose bytes it cannot shorten, so the span
            // stays at Summary level and renders substituteElision, raw head+tail bytes rather than a
            // paraphrase. A lever that quietly still filled would make the two arms identical.
            val spans      = Chunk(Span(0, 1, Chunk(0)), Span(1, 2, Chunk(1)))
            val assignment = Dict[Int, Level]((0, Level.Summary), (1, Level.Summary))
            assert(Default.fillNeed(spans, assignment).size == 2, "REGIME: both spans must need a fill by default")
            val off =
                Compactor.internal.Default(Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.SummaryFills), Calibration())
            assert(off.fillNeed(spans, assignment).isEmpty, "the lever must ask for no fills at all")
        }

        "the lever also holds at the point where a fill actually happens" in {
            // Gating only where the NEED is computed was not enough: a live off arm still staged 12
            // summaries, because a route can arrive at fillRemaining with a need derived some other way.
            // Every fill in the subsystem passes through fillRemaining, so the lever is enforced there too,
            // and this pins that the disabled path stages nothing even when handed a non-empty need.
            val raw   = Chunk[Message](tok(am("a"), 10), tok(am("b"), 10))
            val spans = Chunk(Span(0, 1, Chunk(0)))
            val off =
                Compactor.internal.Default(Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.SummaryFills), Calibration())
            Preparation.init.map { prep =>
                off.fillRemaining(
                    Context(raw),
                    Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 16384),
                    prep,
                    spans,
                    Chunk.empty
                )
                    .handle(Abort.run[AIGenException]).map { staged =>
                        assert(
                            staged.exists(_.summaries.isEmpty),
                            s"a disabled fill route must stage no summary even for a non-empty need, got $staged"
                        )
                    }
            }
        }
    }

    "the analysis-pass lever" - {

        "dropping Analysis means the pass never runs, which the consumption levers do not achieve" in {
            // The gap this closes. Zeroing dependencyWeight/relatednessWeight or disabling a supersession
            // SOURCE silences what the layer's relations DO, while the pass still fires and still spends its
            // call. Measured on the live off arms, which paid 15 to 25 analysis calls each while reporting
            // themselves as "analysis off". Cost is the whole question for this layer, so it needs a lever
            // that stops production, not just consumption.
            val ctx = Context(Chunk.from((0 until 12).map(i => tok(am(s"region $i body text here"), 200))))
            val cfg = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 16384)
            val off = Compactor.internal.Default(Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Analysis), Calibration())
            val pending = off.analysisPending(ctx, cfg)
            assert(pending.nonEmpty, s"REGIME: regions must be pending analysis, or the gate proves nothing")
            Preparation.init.map { prep =>
                off.runAnalysis(ctx, pending, cfg, prep, pending.toList.map(_.id).toSet).andThen(prep.staged.get).map { staged =>
                    assert(
                        staged.analyses.isEmpty,
                        s"a disabled pass must stage nothing even with regions pending, got ${staged.analyses}"
                    )
                }
            }
        }
    }

    "the cheap slot carries the caller's endpoint and key" - {

        "a fill inherits the caller's apiUrl and apiKey, not the provider's public defaults" in {
            // The summary tier was silently dead on any HTTP provider that needs a key. Fills resolve to
            // `provider.small`, which is a CATALOG entry: purely constructed, key absent, base URL the
            // provider's public endpoint. `credentialed` runs only at config-construction entry points, never
            // on the fill path, so unless a deployment explicitly pinned `compaction.summarizer` every fill
            // went out unauthenticated to the public endpoint, 401'd, and degraded to the substitute elision
            // without a word. Invisible on codex, because a CLI harness uses no key, which is why a whole
            // a live run never saw it.
            val caller   = Config.OpenAI.default.model(Config.OpenAI, "m", 16384).apiUrl("http://127.0.0.1:9/v1").apiKey("caller-key")
            val resolved = Default.resolveFillConfig(caller)
            assert(resolved.apiUrl == "http://127.0.0.1:9/v1", s"the fill must go where the caller points, got ${resolved.apiUrl}")
            assert(resolved.apiKey == Present("caller-key"), s"the fill must carry the caller's key, got ${resolved.apiKey}")
            assert(resolved.apiUrl != Config.OpenAI.baseUrl, "the fill must not fall back to the provider's public endpoint")
        }

        "an explicitly pinned summarizer still wins" in {
            val pinned = Config.OpenAI.default.model(Config.OpenAI, "m", 16384).apiUrl("http://127.0.0.1:9/v1").apiKey("k")
            val caller = Compactor.Tuning(summarizer = Present(pinned.apiUrl("http://127.0.0.1:10/v1")))
            assert(
                Compactor.internal.Default(caller, Calibration()).resolveFillConfig(pinned).apiUrl == "http://127.0.0.1:10/v1",
                "a configured summarizer is the deliberate override and takes precedence"
            )
        }
    }

    "the three mechanisms that previously had NO lever at all" - {

        // Preparation, ContentReferences and Recall could not be switched off before the disable set
        // existed: Preparation only by setting a watermark to 1.0, ContentReferences only by zeroing a
        // weight (which left its whole admission scan running), Recall by nothing whatsoever. Each is
        // pinned to the work it must stop doing, since a member of the set that silences nothing is worse
        // than no member: it reports a mechanism as worthless while the mechanism keeps running.

        "dropping Preparation arms no speculative work, while a boundary still does its own" in {
            // Scoped deliberately. "Off means no fibers" is the WRONG reading and would fail on correct
            // behaviour: armAnalysisAtBoundary forks for whatever remains enabled when a boundary fires.
            // What Preparation owns is the SPECULATIVE arming below the boundary, and that is what stops.
            val raw    = Chunk[Message](tok(am("r0 " + ("x" * 300)), 700), tok(am("r1 " + ("x" * 300)), 700))
            val config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 16384)
            val ctx    = Context(raw, raw, Present(Compaction.State(lastUsage = Present(7000), lastUsageRawSize = raw.size)))
            assert(
                Compactor.internal.occupancy(ctx) >= axisOf(config).prepare &&
                    Compactor.internal.occupancy(ctx) < axisOf(config).high,
                s"REGIME: the fixture must sit INSIDE the prepare band, or there is nothing to arm: " +
                    s"occ=${Compactor.internal.occupancy(ctx)} prepare=${axisOf(config).prepare} high=${axisOf(config).high}"
            )
            val off =
                Compactor.internal.Default(Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Preparation), Calibration())
            LLM.run(config) {
                AI.init.map { ai =>
                    Preparation.init.map { prep =>
                        Default.armBelowBoundary(ctx, config, prep).map { _ =>
                            prep.inFlight.get.map { onFiber =>
                                Preparation.init.map { prep2 =>
                                    off.armBelowBoundary(ctx, config, prep2).map { _ =>
                                        prep2.inFlight.get.map { offFiber =>
                                            prep2.armed.get.map { offArmed =>
                                                assert(
                                                    onFiber.isDefined,
                                                    "REGIME: with Preparation on, the band arms a single-flight fiber"
                                                )
                                                assert(offFiber.isEmpty, s"the lever must fork nothing below the boundary, got $offFiber")
                                                assert(!offArmed, s"and must not latch the band as armed, got $offArmed")
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

        "dropping ContentReferences stops the SCAN, not merely the edges it mints" in {
            // The distinction that makes this its own member. Zeroing contentReferenceWeight leaves every
            // document frequency, the rarity test and the per-region cap running and then discards the
            // result, so an arm measured that way pays full price for production and reads as free. Off,
            // the tier contributes no edge at all.
            val raw = Chunk[Message](
                am("the quorum threshold governs how many replicas must acknowledge"),
                am("unrelated formatting and locale discussion with no shared terms"),
                am("raising the quorum threshold again for the ledger service")
            )
            val units = Default.group(raw)
            def contentEdges(d: Compactor.internal.Default): List[Edge] =
                val g = d.deriveGraph(units, raw, Dict.empty[Int, Int])
                units.toList.flatMap(u => g.edges.get(u.id).getOrElse(Chunk.empty).toList)
                    .filter(e => e.kind == EdgeKind.Reference && e.weight <= Calibration().contentReferenceWeight)
            end contentEdges
            val on = contentEdges(Default)
            val off = contentEdges(
                Compactor.internal.Default(
                    Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.ContentReferences),
                    Calibration()
                )
            )
            assert(on.nonEmpty, s"REGIME: the fixture must mint content-reference edges to ablate, got $on")
            assert(off.isEmpty, s"the lever must mint no content-reference edge at all, got $off")
            // and the contrast with the weight knob, which is why this is a separate member: zeroing the
            // weight keeps the edges present, it only makes them carry nothing.
            val zeroed = contentEdges(Compactor.internal.Default(Tuning(), Calibration(contentReferenceWeight = 0.0)))
            assert(zeroed.nonEmpty, s"zeroing the WEIGHT leaves the edges in the graph, which is the confound, got $zeroed")
        }

        "dropping Recall removes the tool AND stops an existing record steering liveness" in {
            // Two halves, because either alone leaves the mechanism half-live. A leftover record from a
            // session that ran with Recall enabled must not keep lifting a region once the tool that could
            // justify it is gone.
            val raw   = Chunk.from((0 until 16).map(i => am(s"region $i")))
            val units = Default.group(raw)
            val off   = Compactor.internal.Default(Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Recall), Calibration())
            LLM.run(Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 16384)) {
                AI.init.map { ai =>
                    assert(Default.tools(ai).nonEmpty, "REGIME: with Recall on, the compactor contributes its recall tool")
                    assert(off.tools(ai).isEmpty, s"the lever must register no recall tool, got ${off.tools(ai).size}")
                }
            }.andThen {
                val state    = Compaction.State(boundaryCounter = 5).withRecall(14)
                val baseline = Compaction.State(boundaryCounter = 5)
                val onLift = Default.seedVector(units, raw, state).get(14).getOrElse(0.0) -
                    Default.seedVector(units, raw, baseline).get(14).getOrElse(0.0)
                val offLift = off.seedVector(units, raw, state).get(14).getOrElse(0.0) -
                    off.seedVector(units, raw, baseline).get(14).getOrElse(0.0)
                assert(onLift > 0.0, s"REGIME: with Recall on, a record lifts its region's seed, got $onLift")
                assert(math.abs(offLift) < 1e-12, s"the lever must leave an existing record inert, got $offLift")
            }
        }
    }

    "disabling a mechanism must not raise the bill" - {

        // Not obvious and not tautological. Dropping SummaryFills makes a demoted span render the
        // fixed-size substitute elision instead of summary bytes, and CompactorAblationTest records
        // explicitly that which of those is smaller "is a measurement, not an assumption". A larger view
        // crosses the trigger sooner, which fires boundaries more often, which invalidates the prefix more
        // often. So removing work can plausibly RAISE the bill while removing model calls, and nothing
        // else in the suite checks it.
        //
        // This consumes the per-lever guards above rather than re-proving them: those establish that each
        // lever reaches its code path, this establishes what removing it costs.

        val window          = 16384
        def costCfg: Config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)
        def bulk(i: Int) =
            s"step $i: continue the design work. " +
                ("the service coordinates writes across replicas and reconciles them on read, " +
                    "and the reconciliation order decides which write wins on conflict. ") * 24
        def body(v: String) =
            val env = Json.encode(s"""{"resultValue":${Json.encode(v)}}""")
            s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$env}}]}}]}"""

        /** One arm: the same conversation grown THROUGH THE SEAM under one tuning, priced in billed units. */
        def priced(t: Compactor.Tuning, turns: Int)(using Frame): (CacheCost.Session, Int, Int) < (Async & Abort[Any] & Scope) =
            TestCompletionServer.run { server =>
                Kyo.foreachDiscard(0 until turns * 12 + 40)(i => server.enqueueBody(body(s"answer $i"))).andThen {
                    LLM.run(costCfg.apiUrl(server.baseUrl)) {
                        AI.init.map(_.enable(Compactor.init(t))).map { ai =>
                            ai.setContext(Context(Chunk[Message](SystemMessage("you are a systems assistant")))).andThen {
                                Kyo.foreach(0 until turns) { i =>
                                    ai.userMessage(bulk(i)).andThen(ai.gen[String]).handle(Abort.run[Any]).andThen(ai.context)
                                }.map { ctxs =>
                                    val size: Message => Int = m => m.tokens.map(_.count).getOrElse(m.content.length / 3 + 4)
                                    val views                = ctxs.toList.map(_.compacted)
                                    val peak                 = views.map(v => v.foldLeft(0)((a, m) => a + size(m))).max
                                    val boundaries =
                                        views.map(v => v.foldLeft(0)((a, m) => a + size(m)))
                                            .sliding(2).count { case a :: b :: Nil => b < a; case _ => false }
                                    (CacheCost.estimate(views, size, outputPerTurn = t.summaryOutputCap), peak, boundaries)
                                }
                            }
                        }
                    }
                }
            }

        "removing a mechanism is priced, and the one that costs money to remove is recorded" in {
            // Gathers EVERY arm before asserting, so one adverse mechanism does not hide the others.
            val turns = 40
            val rates = CacheCost.Rates()
            priced(Compactor.Tuning(), turns).map { (shipped, shippedPeak, shippedBnd) =>
                // (1) REGIME: the shipped arm must actually compact, or every comparison below is between
                // two sessions that never demoted anything.
                assert(shippedBnd >= 1, s"REGIME: the shipped arm must fire a boundary, got $shippedBnd")
                Kyo.foreach(Compactor.Mechanism.values.toList) { m =>
                    priced(Compactor.Tuning(mechanisms = Compactor.Mechanism.all - m), turns).map { (arm, peak, bnd) =>
                        println(
                            f"[lever-cost] -$m%-22s equivalents=${arm.equivalents(rates)}%9.0f " +
                                f"inval=${arm.invalidations}%3d hit=${arm.hitRate}%.3f peak=$peak%6d bnd=$bnd%3d"
                        )
                        (m, arm, peak, bnd)
                    }
                }.map { arms =>
                    println(
                        f"[lever-cost] shipped${" "}%18s equivalents=${shipped.equivalents(rates)}%9.0f " +
                            f"inval=${shipped.invalidations}%3d hit=${shipped.hitRate}%.3f peak=$shippedPeak%6d bnd=$shippedBnd%3d"
                    )
                    // ASSERTED ON INVALIDATIONS, not on the money figure. Prefix rewrites are discrete
                    // and structural; the equivalents differ by ~0.3% across four arms purely because a
                    // ranking perturbation shifts which spans demote, and asserting on that would need a
                    // slack figure, which is how a threshold quietly becomes whatever the code does.
                    val extraRewrites = arms.filter((_, arm, _, _) => arm.invalidations > shipped.invalidations).map(_._1).toSet

                    // THE RECORDED EXCEPTIONS, and they share one mechanism. Measured against a shipped
                    // arm at 148757 equivalents / 5 invalidations:
                    //
                    //   -SummaryFills   161749  (+9%)   7 invalidations
                    //   -Preparation    156272  (+5%)   6 invalidations
                    //
                    // Both remove model calls and both cost MORE. Without staged summary bytes a demoted
                    // span renders the fixed-size substitute elision, which is larger than the summary it
                    // replaces; a larger view crosses the trigger sooner; each extra boundary is a paid
                    // head-of-prompt invalidation. Dropping Preparation reaches the same state by a
                    // different route, since a boundary that has staged nothing renders those substitutes
                    // too. CompactorAblationTest flagged the size relation as "a measurement, not an
                    // assumption"; this is that measurement.
                    //
                    // The result is a genuine one about the summary tier: it pays for itself in cache
                    // terms, and a caller disabling it to save generation calls is trading them for a
                    // larger token bill. Recorded, not asserted away.
                    assert(
                        extraRewrites == Set(Compactor.Mechanism.SummaryFills, Compactor.Mechanism.Preparation),
                        s"exactly two mechanisms are known to cost extra prefix rewrites when removed " +
                            s"(SummaryFills, Preparation); the set is now $extraRewrites. A mechanism LEAVING is good " +
                            "news; one JOINING means its off-path is broken or newly mispriced. Re-derive either way."
                    )

                    // THE PRICE OF AUTOMATIC RECALL, asserted rather than left to the log, because it is a
                    // mechanism that SPENDS and the only honest way to hold that claim is to bound it.
                    // Measured here at about 0.3 percent of equivalents and NO extra boundary, which is a
                    // long way from where it started: at first draft it cost two extra boundaries and 2.2
                    // percent, and both of those turned out to be defects rather than price. One priced a
                    // verbatim delivery by the region's stamped token count while emitting role-tagged
                    // bytes under a header, so the budget clamp was not clamping; the other bounded the
                    // damped seed per region but not in aggregate, so dozens of live records together
                    // rivalled the whole designed seed budget and held the view near the trigger.
                    val withoutExpansion = arms.filter((m, _, _, _) => m == Compactor.Mechanism.Expansion).head
                    assert(
                        shipped.invalidations <= withoutExpansion._2.invalidations,
                        s"automatic recall must not cost a boundary on this fixture: ${shipped.invalidations} against " +
                            s"${withoutExpansion._2.invalidations} without it. An extra boundary here has twice meant a defect " +
                            "in what the mechanism spends rather than a price worth paying, so re-derive before recording it."
                    )
                    assert(
                        shipped.equivalents(rates) < withoutExpansion._2.equivalents(rates) * 1.02,
                        s"and it must stay within two percent of the arm that never fires: ${shipped.equivalents(rates)} " +
                            s"against ${withoutExpansion._2.equivalents(rates)}"
                    )

                    // AND THE PROPERTY THE APPEND CHANNEL RESTS ON: an expansion appends, and an append is
                    // not an invalidation. Machine-checkable as invalidations EQUALLING boundaries, on the
                    // delivering arm as well as on every other, which no compactor can satisfy by accident:
                    // a served-list rewrite below the trigger would show up here as an invalidation with no
                    // boundary behind it.
                    assert(
                        shipped.invalidations == shippedBnd,
                        s"the delivering configuration must invalidate exactly once per boundary and never for an append, " +
                            s"got ${shipped.invalidations} invalidations against $shippedBnd boundaries"
                    )
                    arms.foreach { (m, arm, _, bnd) =>
                        assert(
                            arm.invalidations == bnd,
                            s"and the same holds with $m removed: ${arm.invalidations} invalidations against $bnd boundaries"
                        )
                    }

                    // For every other mechanism the property holds with no slack: removing work must not
                    // rewrite the prefix more often.
                    arms.filter((m, _, _, _) => !extraRewrites.contains(m)).foreach { (m, arm, peak, bnd) =>
                        assert(
                            arm.invalidations <= shipped.invalidations,
                            s"dropping $m must not invalidate the cache more often: ${arm.invalidations} vs ${shipped.invalidations}"
                        )
                    }

                    // A REGIME limitation worth stating rather than hiding: dropping AnalyzedSupersession
                    // priced identically to shipped, because the scripted replies here carry no decodable
                    // Analysis, so that lever has nothing to silence on this fixture. Its cost is unmeasured,
                    // not zero.
                    assert(
                        arms.exists((m, arm, _, _) =>
                            m == Compactor.Mechanism.AnalyzedSupersession && arm.invalidations == shipped.invalidations
                        ),
                        "AnalyzedSupersession is expected to be inert on a fixture with no live analysis output"
                    )
                }
            }
        }
    }

    "the cheap slot carries the caller's endpoint and key" - {

        "a fill inherits the caller's apiUrl and apiKey, not the provider's public defaults" in {
            // The summary tier was silently dead on any HTTP provider that needs a key. Fills resolve to
            // `provider.small`, which is a CATALOG entry: purely constructed, key absent, base URL the
            // provider's public endpoint. `credentialed` runs only at config-construction entry points, never
            // on the fill path, so unless a deployment explicitly pinned `compaction.summarizer` every fill
            // went out unauthenticated to the public endpoint, 401'd, and degraded to the substitute elision
            // without a word. Invisible on codex, because a CLI harness uses no key, which is why a whole
            // a live run never saw it.
            val caller   = Config.OpenAI.default.model(Config.OpenAI, "m", 16384).apiUrl("http://127.0.0.1:9/v1").apiKey("caller-key")
            val resolved = Default.resolveFillConfig(caller)
            assert(resolved.apiUrl == "http://127.0.0.1:9/v1", s"the fill must go where the caller points, got ${resolved.apiUrl}")
            assert(resolved.apiKey == Present("caller-key"), s"the fill must carry the caller's key, got ${resolved.apiKey}")
            assert(resolved.apiUrl != Config.OpenAI.baseUrl, "the fill must not fall back to the provider's public endpoint")
        }

        "an explicitly pinned summarizer still wins" in {
            val pinned = Config.OpenAI.default.model(Config.OpenAI, "m", 16384).apiUrl("http://127.0.0.1:9/v1").apiKey("k")
            val caller = Compactor.Tuning(summarizer = Present(pinned.apiUrl("http://127.0.0.1:10/v1")))
            assert(
                Compactor.internal.Default(caller, Calibration()).resolveFillConfig(pinned).apiUrl == "http://127.0.0.1:10/v1",
                "a configured summarizer is the deliberate override and takes precedence"
            )
        }
    }

    "the three mechanisms that previously had NO lever at all" - {

        // Preparation, ContentReferences and Recall could not be switched off before the disable set
        // existed: Preparation only by setting a watermark to 1.0, ContentReferences only by zeroing a
        // weight (which left its whole admission scan running), Recall by nothing whatsoever. Each is
        // pinned to the work it must stop doing, since a member of the set that silences nothing is worse
        // than no member: it reports a mechanism as worthless while the mechanism keeps running.

        "dropping Preparation arms no speculative work, while a boundary still does its own" in {
            // Scoped deliberately. "Off means no fibers" is the WRONG reading and would fail on correct
            // behaviour: armAnalysisAtBoundary forks for whatever remains enabled when a boundary fires.
            // What Preparation owns is the SPECULATIVE arming below the boundary, and that is what stops.
            val raw    = Chunk[Message](tok(am("r0 " + ("x" * 300)), 700), tok(am("r1 " + ("x" * 300)), 700))
            val config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 16384)
            val ctx    = Context(raw, raw, Present(Compaction.State(lastUsage = Present(7000), lastUsageRawSize = raw.size)))
            assert(
                Compactor.internal.occupancy(ctx) >= axisOf(config).prepare &&
                    Compactor.internal.occupancy(ctx) < axisOf(config).high,
                s"REGIME: the fixture must sit INSIDE the prepare band, or there is nothing to arm: " +
                    s"occ=${Compactor.internal.occupancy(ctx)} prepare=${axisOf(config).prepare} high=${axisOf(config).high}"
            )
            val off =
                Compactor.internal.Default(Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Preparation), Calibration())
            LLM.run(config) {
                AI.init.map { ai =>
                    Preparation.init.map { prep =>
                        Default.armBelowBoundary(ctx, config, prep).map { _ =>
                            prep.inFlight.get.map { onFiber =>
                                Preparation.init.map { prep2 =>
                                    off.armBelowBoundary(ctx, config, prep2).map { _ =>
                                        prep2.inFlight.get.map { offFiber =>
                                            prep2.armed.get.map { offArmed =>
                                                assert(
                                                    onFiber.isDefined,
                                                    "REGIME: with Preparation on, the band arms a single-flight fiber"
                                                )
                                                assert(offFiber.isEmpty, s"the lever must fork nothing below the boundary, got $offFiber")
                                                assert(!offArmed, s"and must not latch the band as armed, got $offArmed")
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

        "dropping ContentReferences stops the SCAN, not merely the edges it mints" in {
            // The distinction that makes this its own member. Zeroing contentReferenceWeight leaves every
            // document frequency, the rarity test and the per-region cap running and then discards the
            // result, so an arm measured that way pays full price for production and reads as free. Off,
            // the tier contributes no edge at all.
            val raw = Chunk[Message](
                am("the quorum threshold governs how many replicas must acknowledge"),
                am("unrelated formatting and locale discussion with no shared terms"),
                am("raising the quorum threshold again for the ledger service")
            )
            val units = Default.group(raw)
            def contentEdges(d: Compactor.internal.Default): List[Edge] =
                val g = d.deriveGraph(units, raw, Dict.empty[Int, Int])
                units.toList.flatMap(u => g.edges.get(u.id).getOrElse(Chunk.empty).toList)
                    .filter(e => e.kind == EdgeKind.Reference && e.weight <= Calibration().contentReferenceWeight)
            end contentEdges
            val on = contentEdges(Default)
            val off = contentEdges(
                Compactor.internal.Default(
                    Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.ContentReferences),
                    Calibration()
                )
            )
            assert(on.nonEmpty, s"REGIME: the fixture must mint content-reference edges to ablate, got $on")
            assert(off.isEmpty, s"the lever must mint no content-reference edge at all, got $off")
            // and the contrast with the weight knob, which is why this is a separate member: zeroing the
            // weight keeps the edges present, it only makes them carry nothing.
            val zeroed = contentEdges(Compactor.internal.Default(Tuning(), Calibration(contentReferenceWeight = 0.0)))
            assert(zeroed.nonEmpty, s"zeroing the WEIGHT leaves the edges in the graph, which is the confound, got $zeroed")
        }

        "dropping Recall removes the tool AND stops an existing record steering liveness" in {
            // Two halves, because either alone leaves the mechanism half-live. A leftover record from a
            // session that ran with Recall enabled must not keep lifting a region once the tool that could
            // justify it is gone.
            val raw   = Chunk.from((0 until 16).map(i => am(s"region $i")))
            val units = Default.group(raw)
            val off   = Compactor.internal.Default(Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Recall), Calibration())
            LLM.run(Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", 16384)) {
                AI.init.map { ai =>
                    assert(Default.tools(ai).nonEmpty, "REGIME: with Recall on, the compactor contributes its recall tool")
                    assert(off.tools(ai).isEmpty, s"the lever must register no recall tool, got ${off.tools(ai).size}")
                }
            }.andThen {
                val state    = Compaction.State(boundaryCounter = 5).withRecall(14)
                val baseline = Compaction.State(boundaryCounter = 5)
                val onLift = Default.seedVector(units, raw, state).get(14).getOrElse(0.0) -
                    Default.seedVector(units, raw, baseline).get(14).getOrElse(0.0)
                val offLift = off.seedVector(units, raw, state).get(14).getOrElse(0.0) -
                    off.seedVector(units, raw, baseline).get(14).getOrElse(0.0)
                assert(onLift > 0.0, s"REGIME: with Recall on, a record lifts its region's seed, got $onLift")
                assert(math.abs(offLift) < 1e-12, s"the lever must leave an existing record inert, got $offLift")
            }
        }
    }
end CompactorTuningTest
