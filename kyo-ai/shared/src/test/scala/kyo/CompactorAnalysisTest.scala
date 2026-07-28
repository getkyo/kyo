package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** The analysis pass mechanics: the typed decode and its five load-bearing properties
  * (backward-only, capped, reachable-target-only, no weights, no summary), the hostile-input
  * drop-not-throw pairing, the two semantic edge kinds, keyless supersession, the event-driven
  * low-water cadence, and the analysis-failure degrade. Deterministic throughout: the analysis wire is
  * scripted through TestCompletionServer, occupancy sits below the fill trigger so only the analysis
  * call hits the server, and every wait is an async suspension (Channel/Fiber), never a sleep.
  */
class CompactorAnalysisTest extends kyo.test.Test[Any]:
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
    def reg(id: Int, tokens: Int = 1): Region         = Region(id, Chunk(id), false, tokens)

    def graphOf(es: (Int, List[Edge])*): Graph =
        Graph(Dict.from(es.map((k, v) => (k, Chunk.from(v))).toMap))

    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    def cfg(window: Int = 16384): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    // The analysis pass now runs through the typed ai.gen[Analysis] API, so it is satisfied by a RESULT-TOOL
    // call carrying the Analysis as its resultValue (not by bare JSON in the assistant content, which command
    // harnesses never produce). This scripts that result-tool completion body.
    def analysisReply(a: Analysis): String =
        val args = s"""{"resultValue":${Json.encode(a)}}"""
        val esc  = args.replace("\\", "\\\\").replace("\"", "\\\"")
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":"$esc"}}]}}]}"""
    end analysisReply

    // A snapshot whose seeded head regions (system + first task turn) age below the tail band (closed) as
    // ten recent turns fill the tail band, while occupancy stays under the fill trigger. The head regions
    // are pinned (seeded), so no span is demotable and fillNeed is empty: only the ONE analysis call
    // reaches the server per arming event, and analysisPending names the closed head regions.
    def closedCtx(): Context =
        val head = Chunk[Message](sm("system prompt"), um("the first task question"))
        val mids = (0 until 10).map(i => tok(am(s"recent turn $i"), 30))
        Context(head.concat(Chunk.from(mids)))
    end closedCtx

    // ==== the two semantic edge kinds ====

    "analyzedEdges maps DependsOn->Dependency(3.0), Relates->Relatedness(0.5), Supersedes->no edge, backward-only" in {
        val analyses =
            Chunk(RegionAnalysis(
                9,
                Chunk(Relation(2, RelationKind.DependsOn), Relation(5, RelationKind.Relates), Relation(1, RelationKind.Supersedes))
            ))
        val edges = Default.analyzedEdges(analyses)
        assert(
            edges == Chunk((9, 2, EdgeKind.Dependency), (9, 5, EdgeKind.Relatedness)),
            "DependsOn and Relates mint edges; Supersedes mints none"
        )
        assert(Default.analyzedSupersession(analyses).get(1) == Present(9), "Supersedes marks region 1 superseded by region 9")
        val raw   = Chunk.from((0 to 9).map(i => am(s"region $i unique$i")))
        val units = Default.group(raw)
        val g     = Default.deriveGraph(units, raw, Dict.empty, edges)
        val e9    = g.edges.get(9).getOrElse(Chunk.empty)
        assert(
            e9.exists(e => e.target == 2 && e.kind == EdgeKind.Dependency && eps(e.weight, dependencyWeight)),
            "the Dependency edge 9->2 carries weight 3.0"
        )
        assert(
            e9.exists(e => e.target == 5 && e.kind == EdgeKind.Relatedness && eps(e.weight, relatednessWeight)),
            "the Relatedness edge 9->5 carries weight 0.5"
        )
        assert(edges.forall((from, target, _) => target < from), "every analyzed edge points backward")
    }

    // ==== keep-decision effect of a semantic edge: does an adopted DependsOn edge CHANGE a
    // demotion decision, or is the analysis layer inert? Pure and deterministic; NO model calls. This settles
    // (deterministically) the question the E5 live control-arm could not: E5 confounded mechanism value with
    // codex analysis-cadence and ran with zero eviction pressure on the seed-anchored first turn. Here the
    // target is a COLD MID-HISTORY region that shares no introduced token with any later region (so it takes
    // no structural Reference edge), and the ONLY difference between the two arms is one injected edge. ====
    "3A: an injected DependsOn edge flips a cold region's keep decision (NOT inert) but only near threshold, marginal under real scarcity" in {
        // Twelve single-message regions. Every region shares "the main task now" (introduced by region 1) and
        // adds ONE word it alone uses, so no region receives a structural Reference edge (a ref edge into R needs
        // a LATER region to mention a token R introduced; each unique word is used exactly once). Target = the
        // cold mid-history region 4; edge source = the recent closed region 9.
        val raw = Chunk[Message](
            tok(sm("the main task now baseline"), 30),   // 0 system head
            tok(um("the main task now begins"), 30),     // 1 first user -> seedTask
            tok(am("the main task now proceeds"), 30),   // 2
            tok(am("the main task now continues"), 30),  // 3
            tok(am("the main task now advances"), 30),   // 4 TARGET (cold mid-history)
            tok(am("the main task now develops"), 30),   // 5
            tok(am("the main task now expands"), 30),    // 6
            tok(am("the main task now matures"), 30),    // 7
            tok(am("the main task now stabilizes"), 30), // 8
            tok(am("the main task now finalizes"), 30),  // 9 SOURCE (recent closed)
            tok(am("the main task now wraps"), 30),      // 10 tail
            tok(am("the main task now ships"), 30)       // 11 tail
        )
        val target = 4
        val source = 9
        val units  = Default.group(raw)
        val seed   = Default.seedVector(units, raw, Compaction.State())

        val graphA = Default.deriveGraph(units, raw, Dict.empty, Chunk.empty)
        val edgesB = Default.analyzedEdges(Chunk(RegionAnalysis(source, Chunk(Relation(target, RelationKind.DependsOn)))))
        val graphB = Default.deriveGraph(units, raw, Dict.empty, edgesB)

        // confound control: in arm A the target takes NO structural Reference edge (adjacency from its successor
        // is identical in both arms and cancels).
        val refIntoTarget =
            units.exists(u => graphA.edges.get(u.id).getOrElse(Chunk.empty).exists(e => e.target == target && e.kind == EdgeKind.Reference))
        assert(!refIntoTarget, "confound control: the target must take no structural Reference edge in arm A")

        val sA = Default.score(units, graphA, Dict.empty, seed).get(target).getOrElse(0.0)
        val sB = Default.score(units, graphB, Dict.empty, seed).get(target).getOrElse(0.0)

        // A single-region span is demotable iff its score falls below the keep floor, which is a fixed
        // multiple of the uniform share and does not move with the budget.
        val floor = keepFloor(units.size)
        // realistic multi-reference case: several closed regions each DependsOn the target.
        def scoreWith(sources: List[Int]): Double =
            val es = Default.analyzedEdges(Chunk.from(sources.map(s => RegionAnalysis(s, Chunk(Relation(target, RelationKind.DependsOn))))))
            Default.score(units, Default.deriveGraph(units, raw, Dict.empty, es), Dict.empty, seed).get(target).getOrElse(0.0)

        // FINDING (deterministic, this reference topology): the analysis layer is NOT inert. One DependsOn edge
        // raises the cold target's PPR score about 1.55x (0.0230 -> 0.0358), and the lift saturates near 0.040
        // even with three edges. What that buys is SHED ORDER, not a pin: the target sits at roughly four tenths
        // of the uniform share both before and after, so it stays demotable, correctly.
        //
        // The pin channel is not closed, it is SELECTIVE: a region has to reach a real share of the liveness
        // mass, not merely improve. CompactorAblationTest measures the other side of the same rule, where a
        // mid-history region carrying many inbound edges reaches roughly 1.5x the uniform share and does pin.
        // Under the older absolute floor neither case could pin at any realistic session length.
        assert(sB > sA, s"NOT inert: the injected DependsOn edge raises the target's score. armA=$sA armB=$sB")
        assert(sB / sA > 1.3, s"the single-edge boost is measurable (~1.55x here). ratio=${sB / sA}")
        assert(
            sB < floor,
            s"a lift that leaves the region far below the uniform share must NOT pin it: sB=$sB floor=$floor " +
                s"uniform=${1.0 / units.size}"
        )
        assert(
            scoreWith(List(9, 8, 7)) < floor,
            s"and it saturates: even three DependsOn edges do not carry this cold region across the floor, " +
                s"score=${scoreWith(List(9, 8, 7))} floor=$floor"
        )
    }

    // ==== the natural-language reference gap (root-cause finding): the structural Reference edge switches on
    // whether the shared term is a STRUCTURAL IDENTIFIER. A concept re-mentioned in plain words mints NO edge
    // (so the liveness-by-reference mechanism is blind to it), while the SAME concept written as an identifier
    // mints edges. Real users rarely camelCase/quote their references, so this is the common-case blind spot
    // behind "the machinery works but is not effective on natural conversation". Deterministic characterization;
    // when the extraction is upgraded, this test documents the change. ====
    "content-tier reference edges catch plain natural-language mentions while common prose stays out" in {
        def refEdgesInto(region: Int, term: String): Int =
            // region 2 introduces `term`; regions 5..9 re-mention it. Count structural Reference edges into region 2.
            val raw = Chunk[Message](
                tok(sm("system header base"), 30),
                tok(um("please set up the config task"), 30),
                tok(am(s"set $term to alpha"), 30),      // 2 introduces the term
                tok(am("unrelated topic delta"), 30),    // 3
                tok(am("unrelated topic epsilon"), 30),  // 4
                tok(am(s"update $term to beta"), 30),    // 5 re-mentions
                tok(am(s"recall $term status now"), 30), // 6 re-mentions
                tok(am(s"check $term once more"), 30),   // 7 re-mentions
                tok(am(s"track $term omega"), 30),       // 8 re-mentions
                tok(am(s"final $term note psi"), 30)     // 9 re-mentions
            )
            val units = Default.group(raw)
            val graph = Default.deriveGraph(units, raw, Dict.empty)
            units.toList.flatMap(u => graph.edges.get(u.id).getOrElse(Chunk.empty)).count(e =>
                e.target == region && e.kind == EdgeKind.Reference
            )
        end refEdgesInto
        // "gizmokey": all lowercase, so the IDENTIFIER extractor refuses it (no interior signal). Before the
        // content tier this produced ZERO edges and the concept was invisible to the liveness layer despite five
        // re-mentions; the content tier now catches it (rare enough per the BPE rarity oracle, recurring, and
        // absent from at least one region).
        assert(refEdgesInto(2, "gizmokey") == 5, "a plain natural-language reference is caught by the content tier (0 before the fix)")
        // "gizmoKey": the same reference written as an identifier is caught by BOTH tiers, so it carries at
        // least as much reference mass as the plain form: the fix removes the gap without weakening identifiers.
        assert(
            refEdgesInto(2, "gizmoKey") >= refEdgesInto(2, "gizmokey"),
            "an identifier reference is never weaker than the plain-word form"
        )
        // the noise bound: generic prose that recurs just as often mints NO content reference, so admitting bare
        // words did not admit connective tissue ("line" ranks 2543 in the vocabulary, below contentRarityFloor).
        assert(refEdgesInto(2, "line") == 0, "a common prose word stays out: the rarity floor is a real stopword filter")
    }

    "an adopted DependsOn edge can flip the PIN decision for a span already near the keep floor" in {
        // Scope, stated because it is narrow and was once overstated here: this exercises the PIN clause
        // (pass 1 of cut), not the escalation order. The target is the system-head span, which sits just
        // under the keep floor through adjacency to the seeded first user turn, so one edge carries it over.
        // It does NOT show that the layer can protect COLD mid-history content, whose score sits ~1/N far
        // below the floor, and it does not exercise the ascending-liveness stop (`low` here is below the
        // view floor, so both arms exhaust the escalation list). The edge source is also a tail region,
        // which analysisPending excludes, so the production pass could not emit this relation at this state.
        // The ordering and supersession channels, the only ones open at scale, remain unmeasured.
        val body = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor incididunt " * 6
        val raw = Chunk[Message](
            tok(sm("system head"), 40),
            tok(um("the task begins"), 40)
        ) ++ Chunk.from((0 until 30).map(i => tok(am(s"turn$i $body"), 400)))
        val units  = Default.group(raw)
        val config = cfg()
        val spans  = Default.formSpans(units, raw, config)
        val seed   = Default.seedVector(units, raw, Compaction.State())
        // LOCATE the boundary case rather than assuming one: the span that sits highest among those still
        // below the floor is the one an edge could plausibly carry over. Assuming a particular span sits
        // just under the floor is how this test broke when the floor changed shape; finding it keeps the
        // property under test (an edge can flip a PIN decision) independent of the floor's constant.
        val floor      = keepFloor(units.size)
        val baseScores = Default.score(units, Default.deriveGraph(units, raw, Dict.empty, Chunk.empty), Dict.empty, seed)
        val belowFloor = spans.toList.filter(sp => Default.spanMaxLiveness(sp, baseScores) < floor)
        assert(belowFloor.nonEmpty, s"REGIME: some span must start below the floor $floor, or there is no flip to make")
        val target = belowFloor.maxBy(sp => Default.spanMaxLiveness(sp, baseScores)).start

        def levelsWith(analyses: Chunk[RegionAnalysis]): Dict[Int, Level] =
            val g = Default.deriveGraph(units, raw, Dict.empty, Default.analyzedEdges(analyses))
            val s = Default.score(units, g, Dict.empty, seed)
            Default.cut(
                Context(raw),
                units,
                spans,
                s,
                occupied = 9000,
                low = 2000,
                since = raw.size,
                prevLevels = Dict.empty
            )
        end levelsWith

        // Enough inbound edges to carry the target across the floor. One is not guaranteed to: the lift a
        // single edge provides is topology-dependent (CompactorAnalysisTest's 3A case measures one that
        // does not), so the property under test is that the CHANNEL exists, not that one edge always wins.
        val sources = units.toList.map(_.id).filter(_ > target).takeRight(6)
        val boostedAnalyses =
            Chunk.from(sources.map(id => RegionAnalysis(id, Chunk(Relation(target, RelationKind.DependsOn)))))
        val plain   = levelsWith(Chunk.empty)
        val boosted = levelsWith(boostedAnalyses)
        assert(spans.size >= 2, s"the fixture must form multiple spans to have an order at all: got ${spans.size}")
        assert(plain.get(target).isDefined, s"REGIME: without the edges the target span must be demoted: ${plain.get(target)}")
        assert(
            boosted.get(target).isEmpty,
            s"the referenced span must cross the floor and stay verbatim: plain=${plain.get(target)} " +
                s"boosted=${boosted.get(target)} floor=$floor"
        )
    }

    "the pointer level sheds only for spans of at most two regions, so round 2 cannot fit through wider spans" in {
        // Summary and Terse render ONE marker per SPAN; Pointer renders one per member REGION. Stepping a
        // k-region span from Terse to Pointer therefore trades one bounded prefix for k descriptors, which
        // shrinks the view only for small k. `project` is span-local and `viewTokens` additive, so the
        // per-span deltas below compose: cut's round 2 walks spans one at a time, and over multi-region
        // spans each step GROWS the view, so the fit-check cannot be reached that way. Round 1 (Summary ->
        // Terse) is unaffected: it sheds strictly, so the ascending-liveness stop still binds there.
        val body = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor " * 8
        val raw = Chunk[Message](tok(sm("system head"), 40), tok(um("the task begins"), 40)) ++
            Chunk.from((0 until 60).map(i => tok(am(s"turn$i $body"), 400)))
        val units  = Default.group(raw)
        val spans  = Default.formSpans(units, raw, cfg())
        val bytes  = "summary of the span: decisions, values and open threads preserved. " * 12
        val staged = spans.foldLeft(Compaction.State())((a, sp) => a.withSummary(sp.start, sp.end, bytes))

        // Baseline: every span at Terse. Then step exactly ONE span to Pointer and read the delta, which is
        // what cut's round 2 does per iteration.
        val allTerse = spans.foldLeft(Dict.empty[Int, Level])((d, sp) => d.update(sp.start, Level.Terse))
        def view(dem: Dict[Int, Level]): Int =
            Default.viewTokens(Default.project(raw, units, spans, dem, raw.size, Dict.empty, staged))
        val base                         = view(allTerse)
        def deltaStepping(sp: Span): Int = view(allTerse.update(sp.start, Level.Pointer)) - base

        val wide   = spans.filter(_.regionIds.size >= 3)
        val narrow = spans.filter(_.regionIds.size <= 2)
        assert(wide.nonEmpty && narrow.nonEmpty, s"the fixture must contain both widths: ${spans.toList.map(_.regionIds.size)}")
        assert(
            wide.forall(sp => deltaStepping(sp) > 0),
            s"a 3+ region span must GROW the view when pointered: ${wide.toList.map(sp => sp.regionIds.size -> deltaStepping(sp))}"
        )
        assert(
            narrow.forall(sp => deltaStepping(sp) < 0),
            s"a 1-2 region span must still shed when pointered: ${narrow.toList.map(sp => sp.regionIds.size -> deltaStepping(sp))}"
        )
        // the pointer view drops content entirely, so its cost is the marker count alone, independent of
        // how much summary text the span had.
        def pointerView(mult: Int): Int =
            val b  = "summary of the span: decisions, values and open threads preserved. " * mult
            val st = spans.foldLeft(Compaction.State())((a, sp) => a.withSummary(sp.start, sp.end, b))
            val d  = spans.foldLeft(Dict.empty[Int, Level])((x, sp) => x.update(sp.start, Level.Pointer))
            Default.viewTokens(Default.project(raw, units, spans, d, raw.size, Dict.empty, st))
        end pointerView
        assert(pointerView(2) == pointerView(48), "the pointer view is independent of the summary budget")
    }

    "a Supersedes relation sheds the stale twin before its replacement, through cut" in {
        // The protected-staleness break the design asks for, driven end to end. Without the relation the
        // structural layer cannot tell the stale definition from the live one: the stale twin is the older
        // introducer, so later mentions of the shared identifier keep IT alive and the replacement is the one
        // shed. Adopting the relation repoints that mass to the replacement and penalises what remains, so
        // the pair's shed order reverses. This only expresses itself because the escalation orders spans by
        // the same member the demote gate reads (their hottest); ordering by the coldest member cannot see a
        // relation that lands on a span's hottest one, which is what a Supersedes twin is by construction.
        val body            = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor " * 8
        def turn(i: Int)    = tok(am(s"turn$i $body"), 400)
        def mention(i: Int) = tok(am(s"revisit poolMax detail$i $body"), 400)
        val raw = Chunk[Message](tok(sm("system head"), 40), tok(um("the task begins"), 40)) ++
            Chunk.from((0 until 8).map(turn)) ++
            Chunk[Message](tok(am(s"set poolMax to 8 $body"), 400)) ++ // 10: the stale definition
            Chunk.from((11 until 26).map(turn)) ++
            Chunk[Message](tok(am(s"update poolMax to 16 $body"), 400)) ++ // 26: the replacement
            Chunk.from((27 until 42).map(turn)) ++
            Chunk.from((42 until 46).map(mention)) ++ // later mentions keep the stale one alive
            Chunk.from((46 until 58).map(turn))
        val units = Default.group(raw)
        val spans = Default.formSpans(units, raw, cfg())
        val seed  = Default.seedVector(units, raw, Compaction.State())
        val stale = 10
        val fresh = 26

        val relation       = Chunk(RegionAnalysis(fresh, Chunk(Relation(stale, RelationKind.Supersedes))))
        val sup            = Default.mergeSupersession(Dict.empty[Int, Int], Default.analyzedSupersession(relation))
        val plainSc        = Default.score(units, Default.deriveGraph(units, raw, Dict.empty), Dict.empty, seed)
        val supSc          = Default.score(units, Default.deriveGraph(units, raw, sup), sup, seed)
        def spanOf(r: Int) = spans.filter(_.regionIds.contains(r)).head.start
        val sStale         = spanOf(stale)
        val sFresh         = spanOf(fresh)

        // PRECONDITION: each twin is its span's hottest member, so the gate and the order read the relation.
        assert(
            spans.filter(_.start == sStale).head.regionIds.forall(r => plainSc.get(r).getOrElse(0.0) <= plainSc.get(stale).getOrElse(0.0)),
            "PRECONDITION: the stale twin must be its span's hottest member"
        )
        // PRECONDITION: the relation must move the mass, not merely exist.
        assert(
            supSc.get(fresh).getOrElse(0.0) > plainSc.get(fresh).getOrElse(0.0) * 1000 &&
                supSc.get(stale).getOrElse(1.0) < plainSc.get(stale).getOrElse(0.0) / 1000,
            s"PRECONDITION: the replacement must inherit the stale twin's protection: " +
                s"stale ${plainSc.get(stale)}->${supSc.get(stale)}, fresh ${plainSc.get(fresh)}->${supSc.get(fresh)}"
        )

        // The pin decision is untouched: both twins sit orders of magnitude below the keep floor in both
        // arms, so what the relation changes is the shed ORDER, not whether either may be shed.
        assert(
            plainSc.get(stale).getOrElse(0.0) < keepFloor(units.size) &&
                supSc.get(fresh).getOrElse(0.0) < keepFloor(units.size),
            s"the twins must stay below the keep floor, so what the relation changes is the shed ORDER and " +
                s"not whether either MAY be shed: stale=${plainSc.get(stale)} fresh=${supSc.get(fresh)} " +
                s"floor=${keepFloor(units.size)}"
        )

        val bytes  = "summary of the span: decisions, values and open threads preserved. " * 12
        val staged = spans.foldLeft(Compaction.State())((a, sp) => a.withSummary(sp.start, sp.end, bytes))
        val ctx    = Context(raw).withCompaction(staged)
        def view(dem: Dict[Int, Level]) =
            Default.viewTokens(Default.project(raw, units, spans, dem, raw.size, Dict.empty, staged))
        val demotable           = spans.toList.filter(sp => Default.spanMaxLiveness(sp, plainSc) < keepFloor(units.size))
        def assignAll(l: Level) = demotable.foldLeft(Dict.empty[Int, Level])((d, sp) => d.update(sp.start, l))
        val lo                  = view(assignAll(Level.Terse))
        val hi                  = view(assignAll(Level.Summary))
        def levelsAt(sc: Dict[Int, Double], l: Int) =
            Default.cut(ctx, units, spans, sc, occupied = 40000, low = l, since = raw.size, prevLevels = Dict.empty)

        // Across the band where the escalation binds partway, find where the twins are treated differently.
        val band = (lo to hi by math.max(1, (hi - lo) / 24)).toList
        val plainShedsFresh = band.count { l =>
            val d = levelsAt(plainSc, l)
            d.get(sFresh).contains(Level.Terse) && !d.get(sStale).contains(Level.Terse)
        }
        val supShedsStale = band.count { l =>
            val d = levelsAt(supSc, l)
            d.get(sStale).contains(Level.Terse) && !d.get(sFresh).contains(Level.Terse)
        }
        assert(
            plainShedsFresh > 0,
            s"without the relation the structural layer sheds the REPLACEMENT while the stale definition " +
                s"stays: that is the staleness the pass exists to break (thresholds: $plainShedsFresh of ${band.size})"
        )
        assert(
            supShedsStale > 0,
            s"adopting the relation must reverse the pair, shedding the stale definition and sparing its " +
                s"replacement (thresholds: $supShedsStale of ${band.size})"
        )
    }

    "the pointer pass never inflates the view it was called to shrink" in {
        // Pointering a wide span costs more than the terse prefix it replaces, so an unguarded round 2 walks
        // the whole list growing the view and still never fits. The pass must leave such a span alone.
        val body = "lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor " * 8
        val raw = Chunk[Message](tok(sm("system head"), 40), tok(um("the task begins"), 40)) ++
            Chunk.from((0 until 60).map(i => tok(am(s"turn$i $body"), 400)))
        val units  = Default.group(raw)
        val spans  = Default.formSpans(units, raw, cfg())
        val bytes  = "summary of the span: decisions, values and open threads preserved. " * 12
        val staged = spans.foldLeft(Compaction.State())((a, sp) => a.withSummary(sp.start, sp.end, bytes))
        val ctx    = Context(raw).withCompaction(staged)
        val seed   = Default.seedVector(units, raw, Compaction.State())
        val scores = Default.score(units, Default.deriveGraph(units, raw, Dict.empty), Dict.empty, seed)
        def view(dem: Dict[Int, Level]) =
            Default.viewTokens(Default.project(raw, units, spans, dem, raw.size, Dict.empty, staged))
        assert(spans.exists(_.regionIds.size >= 3), "the fixture must contain wide spans, where pointering costs more")

        // ask for a target no assignment can reach, so the pass runs to exhaustion: the worst case for the guard
        val assigned =
            Default.cut(ctx, units, spans, scores, occupied = 40000, low = 1, since = raw.size, prevLevels = Dict.empty)
        val pass1 = spans.toList.filter(sp => Default.spanMaxLiveness(sp, scores) < keepFloor(units.size))
            .foldLeft(Dict.empty[Int, Level])((d, sp) => d.update(sp.start, Level.Summary))
        assert(
            view(assigned) <= view(pass1),
            s"escalation must not end above where it started: pass1=${view(pass1)} final=${view(assigned)}"
        )
        // and specifically: no wide span was pointered, since that step grows the view
        val widePointered = spans.toList.filter(sp => sp.regionIds.size >= 3 && assigned.get(sp.start).contains(Level.Pointer))
        assert(
            widePointered.isEmpty,
            s"a wide span was pointered despite costing more than the level above: ${widePointered.map(_.regionIds.size)}"
        )
    }

    "parseAnalysis caps at relationCap, keeps only backward in-reach relations, drops out-of-reach members' relations" in {
        // region 20: six backward relations (targets 1,2,3,4,5,6); target 3 is pointer-level (out of reach).
        // region 8: one FORWARD relation (target 12, target > ordinal).
        val a =
            Analysis(
                Chunk(
                    RegionAnalysis(
                        20,
                        Chunk(
                            Relation(1, RelationKind.Relates),
                            Relation(2, RelationKind.Relates),
                            Relation(3, RelationKind.Relates),
                            Relation(4, RelationKind.Relates),
                            Relation(5, RelationKind.Relates),
                            Relation(6, RelationKind.Relates)
                        )
                    ),
                    RegionAnalysis(8, Chunk(Relation(12, RelationKind.DependsOn)))
                )
            )
        // reachable EXCLUDES the pointer-level ordinal 3 and the out-of-range 12.
        val valid  = Set(1, 2, 4, 5, 6, 8, 20)
        val parsed = Default.parseAnalysis(Json.encode(a), valid)
        assert(parsed.size == 2, "both listed members whose ordinal is in reach survive")
        val r20 = parsed.filter(_.ordinal == 20).head
        assert(
            r20.relations.map(_.target) == Chunk(1, 2, 4, 5),
            "target 3 dropped as out-of-reach; first relationCap (4) of the rest kept in emission order"
        )
        assert(r20.relations.size == Default.relationCap, "capped at relationCap")
        val r8 = parsed.filter(_.ordinal == 8).head
        assert(r8.relations.isEmpty, "region 8's forward relation is dropped, leaving the member with zero relations")
    }

    // ==== the typed decode + write-once staging ====

    "a well-formed Analysis decodes, stages write-once by ordinal, and round-trips through Schema" in {
        TestCompletionServer.run { server =>
            val ctx     = closedCtx()
            val config  = cfg().apiUrl(server.baseUrl)
            val pending = Default.analysisPending(ctx, config)
            val units   = Default.group(ctx.raw)
            val spans   = Default.formSpans(units, ctx.raw, config)
            val reach   = Default.analysisReach(units, spans, Dict.empty, Default.tailUnits(units))
            val reply   = Analysis(pending.map(u => RegionAnalysis(u.id, Chunk.empty)))
            server.enqueueBody(analysisReply(reply)).andThen(server.enqueueBody(analysisReply(reply))).andThen {
                Preparation.init.map { prep =>
                    Default.runAnalysis(ctx, pending, config, prep, reach).andThen {
                        prep.staged.get.map { staged1 =>
                            assert(pending.nonEmpty, "the snapshot has closed pending regions to analyze")
                            assert(pending.forall(u => staged1.analysisOf(u.id).isDefined), "every pending region stages by ordinal")
                            Default.runAnalysis(ctx, pending, config, prep, reach).andThen {
                                prep.staged.get.map { staged2 =>
                                    assert(
                                        pending.forall(u => staged2.analysisOf(u.id) == staged1.analysisOf(u.id)),
                                        "a re-run leaves the staged analyses untouched (write-once first-writer-wins)"
                                    )
                                    val ra =
                                        RegionAnalysis(9, Chunk(Relation(2, RelationKind.DependsOn), Relation(5, RelationKind.Relates)))
                                    val decoded = Json.decode[RegionAnalysis](Json.encode(ra))
                                    assert(
                                        decoded == Result.Success(ra),
                                        "the artifact round-trips through Schema (encode then decode == the original)"
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "the analysis call disables reasoning, so its wire output ceiling is not inflated by the reasoning budget" in {
        // A reasoning-budgeted config whose explicit ceiling sits below the budget: with reasoning ON the
        // wire ceiling would be raised to clear the budget (max(256, 12000 + 4096) = 16096); the analysis
        // is internal compaction maintenance and must stay cheap, so runAnalysis disables reasoning and the
        // ceiling resolves back to the explicit 256.
        TestCompletionServer.run { server =>
            val reasoningCfg =
                Config.OpenAI.default.apiKey("k").model(
                    Config.OpenAI,
                    "m",
                    200000,
                    Config.OutputMaximum.Verified(64000),
                    Config.ReasoningEncoding.TokenBudget,
                    true,
                    true
                ).reasoningBudget(12000).maxTokens(256).apiUrl(server.baseUrl)
            assert(reasoningCfg.effectiveMaxOutputTokens == 16096, "reasoning-on the ceiling clears the budget (baseline)")
            assert(reasoningCfg.disableReasoning.effectiveMaxOutputTokens == 256, "reasoning-off the ceiling is the explicit 256")
            val ctx     = closedCtx()
            val pending = Default.analysisPending(ctx, reasoningCfg)
            val units   = Default.group(ctx.raw)
            val spans   = Default.formSpans(units, ctx.raw, reasoningCfg)
            val reach   = Default.analysisReach(units, spans, Dict.empty, Default.tailUnits(units))
            val reply   = Analysis(pending.map(u => RegionAnalysis(u.id, Chunk.empty)))
            server.enqueueBody(analysisReply(reply)).andThen {
                Preparation.init.map { prep =>
                    Default.runAnalysis(ctx, pending, reasoningCfg, prep, reach).andThen {
                        server.captured.map { cap =>
                            assert(cap.size == 1, "exactly one analysis call fires")
                            val body = cap.head.body
                            assert(
                                body.contains("\"max_completion_tokens\":256"),
                                s"the analysis wire ceiling is the reasoning-off 256, got body: $body"
                            )
                            assert(
                                !body.contains("16096"),
                                s"the reasoning budget never inflates the analysis ceiling, got body: $body"
                            )
                        }
                    }
                }
            }
        }
    }

    "every malformed shape yields a dropped artifact, never a throw (parameterized)" in {
        val validEncoded = Json.encode(Analysis(Chunk(RegionAnalysis(9, Chunk(Relation(2, RelationKind.DependsOn))))))
        val valid        = Set(1, 2, 3, 4, 5, 6, 7, 8, 9, 20)
        // (a) malformed JSON, (e) unknown discriminator: whole-batch drop (decode failure).
        // (b) out-of-range target, (c) backward violation: per-relation drop, member survives.
        // (d) over-cap: keep the first relationCap.
        val overCap = Analysis(Chunk(RegionAnalysis(20, Chunk.from((1 to 6).map(t => Relation(t, RelationKind.Relates))))))
        val cases: List[(String, String, Chunk[RegionAnalysis] => Boolean)] =
            List(
                ("a malformed JSON", "{not valid json", _.isEmpty),
                ("e unknown discriminator", validEncoded.replace("DependsOn", "Mystery"), _.isEmpty),
                (
                    "b out-of-range target",
                    Json.encode(Analysis(Chunk(RegionAnalysis(9, Chunk(Relation(99, RelationKind.Relates)))))),
                    r => r.size == 1 && r.head.ordinal == 9 && r.head.relations.isEmpty
                ),
                (
                    "c backward violation",
                    Json.encode(Analysis(Chunk(RegionAnalysis(5, Chunk(Relation(7, RelationKind.Relates)))))),
                    r => r.size == 1 && r.head.ordinal == 5 && r.head.relations.isEmpty
                ),
                (
                    "d over-cap",
                    Json.encode(overCap),
                    r => r.size == 1 && r.head.relations.size == Default.relationCap
                )
            )
        cases.foldLeft(Kyo.unit) { (acc, c) =>
            val (name, input, check) = c
            acc.andThen {
                val parsed = Default.parseAnalysis(input, valid)
                assert(check(parsed), s"hostile shape [$name] drops correctly without throwing")
            }
        }
    }

    // ==== keyless supersession ====

    "a Supersedes relation penalizes the earlier region and repoints its edges (no compaction key)" in {
        val analyses = Chunk(RegionAnalysis(41, Chunk(Relation(14, RelationKind.Supersedes))))
        val keyless  = Default.analyzedSupersession(analyses)
        assert(keyless.get(14) == Present(41), "the keyless detector marks region 14 superseded by region 41")
        val merged = Default.mergeSupersession(Dict.empty[Int, Int], keyless)
        assert(merged.get(14) == Present(41), "mergeSupersession carries the keyless mark when the keyed map is empty")
        // the supersession penalty multiplies region 14's score by supersessionPenalty (0.2).
        val units = Chunk(reg(14), reg(15), reg(41))
        val g     = graphOf((15, List(Edge(14, EdgeKind.Reference, 1.0))))
        val seed  = Dict[Int, Double]((15, 1.0))
        val plain = Default.score(units, g, Dict.empty[Int, Int], seed)
        val pen   = Default.score(units, g, merged, seed)
        assert(
            eps(pen.get(14).getOrElse(0.0), plain.get(14).getOrElse(0.0) * supersessionPenalty),
            "region 14's score is multiplied by the supersession penalty"
        )
        // reference edges targeting the superseded region repoint to the superseding one.
        val raw = Chunk[Message](am("intro `Widget.field`"), am("mid turn"), am("update `Widget.field`"), am("later `Widget.field`"))
        val u   = Default.group(raw)
        val sup2 = Default.mergeSupersession(
            Dict.empty[Int, Int],
            Default.analyzedSupersession(Chunk(RegionAnalysis(2, Chunk(Relation(0, RelationKind.Supersedes)))))
        )
        val gg    = Default.deriveGraph(u, raw, sup2)
        val u3ref = gg.edges.get(3).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Reference)
        assert(u3ref.exists(_.target == 2), "the Reference edge repoints to the superseding region 2")
        assert(!u3ref.exists(_.target == 0), "it never targets the superseded introducer (region 0)")
    }

    // ==== event-driven cadence ====

    "event-driven: one analysis call per arming event covers every closed unanalyzed region; write-once tops up only the delta" in {
        TestCompletionServer.run { server =>
            val ctx     = closedCtx()
            val config  = cfg().apiUrl(server.baseUrl)
            val pending = Default.analysisPending(ctx, config)
            assert(pending.nonEmpty, "the snapshot has multiple closed pending regions")
            assert(Default.analysisLowWater(ctx, config) == pending.head.id, "analysisLowWater is the lowest closed unanalyzed ordinal")
            assert(pending.map(_.id) == pending.map(_.id).sorted, "analysisPending is sorted ascending")
            val reply = Analysis(pending.map(u => RegionAnalysis(u.id, Chunk.empty)))
            server.enqueueBody(analysisReply(reply)).andThen {
                Preparation.init.map { prep =>
                    Default.preparationRun(ctx, config, prep, Chunk.empty).andThen {
                        server.captured.map { cap =>
                            assert(cap.size == 1, "EXACTLY ONE analysis call fires per arming event, not one per pending region")
                            prep.staged.get.map { staged =>
                                val adopted = Default.adopt(Compaction.State(), staged)
                                val ctx2    = ctx.withCompaction(adopted)
                                assert(
                                    Default.analysisPending(ctx2, config).isEmpty,
                                    "after adoption every prior pending region is analyzed; the re-arm delta is empty when nothing new closed"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "a region newly closed since the last event participates through structural edges only" in {
        val raw   = Chunk.from((0 to 9).map(i => am(s"turn $i tok$i")))
        val units = Default.group(raw)
        // state analyzed an earlier region 3 (Relates->1); the newly closed region 8 is absent from state.
        val analyses = Chunk(RegionAnalysis(3, Chunk(Relation(1, RelationKind.Relates))))
        val edges    = Default.analyzedEdges(analyses)
        assert(!edges.exists((from, _, _) => from == 8), "the not-yet-analyzed region 8 mints no semantic edge")
        val g  = Default.deriveGraph(units, raw, Dict.empty, edges)
        val e8 = g.edges.get(8).getOrElse(Chunk.empty)
        assert(e8.exists(_.kind == EdgeKind.Adjacency), "region 8 still contributes its structural adjacency edge")
        assert(
            !e8.exists(e => e.kind == EdgeKind.Dependency || e.kind == EdgeKind.Relatedness),
            "region 8 carries no semantic edge until the next event analyzes it"
        )
    }

    // ==== the analysis-failure degrade + no blocking ====

    "an analysis-call failure leaves regions unanalyzed, the graph runs structural, gen never fails, no thread blocks".notJs in {
        TestCompletionServer.run { server =>
            val ctx    = closedCtx()
            val config = cfg().apiUrl(server.baseUrl)
            // "not json" fails the provider's Response decode -> HttpException -> AITransportException, all
            // recovered inside runAnalysis. Occupancy is below the fill trigger, so only this call fires.
            server.enqueueBody("not json").andThen {
                Preparation.init.map { prep =>
                    Default.preparationRun(ctx, config, prep, Chunk.empty).andThen {
                        prep.staged.get.map { staged =>
                            assert(staged.analyses.isEmpty, "a failed analysis stages nothing (a dropped artifact, not an error)")
                            val state = Default.adopt(Compaction.State(), staged)
                            assert(state.analyses.isEmpty, "adoption of an empty staging cell leaves compaction state unanalyzed")
                            val units    = Default.group(ctx.raw)
                            val g        = Default.deriveGraph(units, ctx.raw, Dict.empty, Default.analyzedEdges(state.analyses))
                            val allEdges = units.flatMap(u => g.edges.get(u.id).getOrElse(Chunk.empty))
                            val hasSemantic =
                                allEdges.exists(e => e.kind == EdgeKind.Dependency || e.kind == EdgeKind.Relatedness)
                            assert(!hasSemantic, "the boundary graph carries only structural edges (Adjacency + Reference)")
                            noBlockingConstructs()
                        }
                    }
                }
            }
        }
    }

    // The no-blocking-construct grep gate over the touched main sources. Every wait in the analysis path is a
    // Fiber.get/Channel suspension.
    def noBlockingConstructs()(using kyo.test.AssertScope): Unit =
        val banned = List("Thread.sleep", "synchronized", "CountDownLatch", "Future.await", ".await(", "Await.", "AllowUnsafe")
        List("Compactor.scala").foreach { name =>
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

    "the analysis wire form is the one a model actually produces" - {

        // This layer once ran indefinitely without ever delivering a relation. The derived enum
        // encoding wrapped each kind as an object ({"kind":{"DependsOn":{}}}), which no model writes, so
        // every payload carrying a relation failed to decode and the eval loop exhausted its retries. Only
        // relation-FREE analyses survived, because an empty array decodes under either shape, which made
        // the layer look alive while contributing nothing.
        //
        // Every fixture here is a LITERAL payload. Building one with Json.encode is what hid the defect:
        // an encode/decode round trip agrees with itself no matter which shape the encoder chose, so it
        // cannot detect that the shape is one the producer will never emit.

        "a relation kind decodes from its bare name" in {
            val payload = """{"regions":[{"ordinal":5,"relations":[{"target":2,"kind":"DependsOn"}]}]}"""
            val parsed  = Default.parseAnalysis(payload, Set(2, 5))
            assert(parsed.size == 1, s"the analysis must decode, got $parsed")
            assert(
                parsed.head.relations == Chunk(Relation(2, RelationKind.DependsOn)),
                s"the bare name must decode to its kind, got ${parsed.head.relations}"
            )
        }

        "every kind decodes from its bare name" in {
            val kinds =
                List("DependsOn" -> RelationKind.DependsOn, "Relates" -> RelationKind.Relates, "Supersedes" -> RelationKind.Supersedes)
            kinds.foreach { (name, kind) =>
                val payload = s"""{"regions":[{"ordinal":5,"relations":[{"target":2,"kind":"$name"}]}]}"""
                val parsed  = Default.parseAnalysis(payload, Set(2, 5))
                assert(
                    parsed.headOption.exists(_.relations == Chunk(Relation(2, kind))),
                    s"'$name' must decode to $kind, got $parsed"
                )
            }
        }

        "an unknown kind still drops the artifact rather than throwing" in {
            val payload = """{"regions":[{"ordinal":5,"relations":[{"target":2,"kind":"Nonsense"}]}]}"""
            assert(Default.parseAnalysis(payload, Set(2, 5)).isEmpty, "an unrecognized discriminator yields a dropped artifact")
        }

        "the emitted form is the bare name, so what we ask for is what we accept" in {
            val encoded = Json.encode(Analysis(Chunk(RegionAnalysis(5, Chunk(Relation(2, RelationKind.Supersedes))))))
            assert(encoded.contains("\"kind\":\"Supersedes\""), s"the wire form must be the bare name, got $encoded")
            assert(!encoded.contains("{\"Supersedes\""), s"the wrapped variant form must not return, got $encoded")
        }
    }

end CompactorAnalysisTest
