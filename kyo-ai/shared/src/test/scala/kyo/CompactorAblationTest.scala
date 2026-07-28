package kyo

import Compactor.Tuning
import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** Per-mechanism ablation: does silencing one mechanism change what the compactor decides?
  *
  * The deterministic half of the compaction validation: every verdict here is reached without a provider,
  * on the VIEW VALIDITY basis
  * (level assignment, served tokens, fill set) and never on model quality: a fixture cannot say whether
  * the model still answers correctly, only whether different content survives at different levels. The
  * live arms carry that bridge.
  *
  * Two rules from the plan shape every reading here. First, this PPR is NOT stochastic: a dangling node
  * loses its mass and nothing renormalizes, so zeroing an edge class or a seed changes the system's total
  * mass, not only its structure. Against the ABSOLUTE keep floor that mass loss alone flips keep decisions
  * globally, so a score-side mechanism is read as a RANK change, never as a floor crossing, or every
  * mechanism carrying any mass would score as "effective". Second, each fixture asserts the regime it
  * needs (a mechanism that is inert in this fixture must fail loudly rather than read as ineffective).
  *
  * Fixture shape follows what live sessions actually look like, 20-36 messages,
  * 12k-22k raw tokens, a 4000-token high watermark, identifiers recurring across turns.
  */
class CompactorAblationTest extends kyo.test.Test[Any]:

    private def axisOf(c: Config): Compactor.internal.Axis =
        Compactor.internal.axis(Compactor.Tuning(), c)

    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def tok(m: Message, n: Int): Message              = stamp(m, TokenStamp("t", n))

    def eps(a: Double, b: Double, tol: Double = 1e-9): Boolean = math.abs(a - b) < tol

    def cfg(window: Int = 16384): Config =
        Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    /** A production-shaped conversation: alternating turns, per-corpus token sizes, a structural identifier
      * (`Widget.field`) reintroduced mid-history, and a distinctive content word ("quorum") recurring in
      * prose so the content-reference tier has something to admit. Facts sit past each region's first line,
      * per the plan's descriptor-carriage rule.
      */
    val raw: Chunk[Message] =
        val body = "the service coordinates writes across replicas and reconciles them on read. " * 6
        Chunk[Message](tok(sm("you are a systems assistant"), 40), tok(um("we are designing a storage layer"), 60)) ++
            Chunk.from((0 until 12).flatMap { i =>
                val ident = if i % 4 == 1 then s"we touch `Widget.field` again here. $body" else body
                val rare  = if i % 3 == 0 then s"$ident the quorum setting matters at this step." else ident
                List(
                    tok(um(s"step $i: continue the design work"), 60),
                    tok(am(s"answer $i\nthe detail for step $i follows. $rare"), 1150)
                )
            })
    end raw

    val ctx: Context       = Context(raw)
    val state              = Compaction.State()
    val defaultCalibration = Calibration()

    /** One arm: the whole decision pipeline at a given tuning, plus the readings the plan defines. */
    case class Arm(scores: Dict[Int, Double], levels: Dict[Int, Level], served: Int, spans: Chunk[Span]):
        /** Region ids ordered hottest-first. The rank-based reading the non-stochastic PPR forces. */
        def ranking: List[Int] = scores.toMap.toList.sortBy { case (id, v) => (-v, id) }.map(_._1)
        def demoted: Set[Int]  = levels.toMap.keySet
    end Arm

    def arm(c: Calibration = defaultCalibration, t: Tuning = Tuning(), staged: Compaction.State = Compaction.State()): Arm =
        val d      = Compactor.internal.Default(t, c)
        val units  = d.group(raw)
        val spans  = d.formSpans(units, raw, cfg())
        val seed   = d.seedVector(units, raw, staged)
        val g      = d.deriveGraph(units, raw, Dict.empty[Int, Int])
        val sc     = d.score(units, g, Dict.empty[Int, Int], seed)
        val levels = d.cut(ctx, units, spans, sc, occupied = 14000, low = 4000, since = raw.size, prevLevels = Dict.empty)
        val served = d.viewTokens(d.project(raw, units, spans, levels, raw.size, Dict.empty, staged))
        Arm(sc, levels, served, spans)
    end arm

    /** Summary bytes for every span, longer than `tersePrefixChars`, so round 1 is not a no-op: `toTerse`
      * SKIPS a span whose staged bytes do not exceed the terse prefix, which would silence M15 entirely.
      */
    def stagedSummaries(spans: Chunk[Span]): Compaction.State =
        val bytes = "summary of the span: decisions, values and open threads preserved. " * 12
        spans.foldLeft(Compaction.State())((a, sp) => a.withSummary(sp.start, sp.end, bytes))

    "the fixture sits in the regime these measurements need" in {
        val base   = arm()
        val rawTok = raw.foldLeft(0)((a, m) => a + stampedTokens(m))
        assert(rawTok >= 12000 && rawTok <= 22000, s"REGIME: corpus-shaped raw token count, got $rawTok")
        assert(raw.size >= 20 && raw.size <= 36, s"REGIME: corpus-shaped message count, got ${raw.size}")
        assert(base.spans.nonEmpty, "REGIME: spans must form, or nothing can be demoted")
        assert(base.demoted.nonEmpty, s"REGIME: the default arm must demote something, or every ablation reads null")
        assert(base.scores.toMap.size >= 8, s"REGIME: enough regions for a rank reading, got ${base.scores.toMap.size}")
    }

    "score-side mechanisms, read as rank changes (never floor crossings)" - {

        "M05 adjacency edges carry mid-history mass: zeroing them reorders the ranking" in {
            val on  = arm()
            val off = arm(defaultCalibration.copy(adjacencyWeight = 0.0))
            assert(on.ranking != off.ranking, "silencing adjacency must reorder the liveness ranking")
        }

        "M06 identifier reference edges reorder the ranking" in {
            val on  = arm()
            val off = arm(defaultCalibration.copy(referenceWeight = 0.0))
            assert(on.ranking != off.ranking, s"silencing identifier references must reorder the ranking")
        }

        "M07 the content-reference tier reorders the ranking on prose-only recurrence" in {
            // The tier's whole claim is that a recurring DISTINCTIVE WORD ("quorum") is a reference even
            // with no identifier syntax. If zeroing it changes nothing on a fixture that contains exactly
            // that pattern, the tier is inert on production-shaped prose.
            val on  = arm()
            val off = arm(defaultCalibration.copy(contentReferenceWeight = 0.0))
            assert(on.ranking != off.ranking, "the content-reference tier must move the ranking on recurring prose terms")
        }

        "M08 propagation is what makes scores differ from seeds" in {
            val propagated = arm()
            val seedOnly   = arm(defaultCalibration.copy(pprIterations = 0))
            assert(propagated.ranking != seedOnly.ranking, "propagation must reorder relative to the raw seed vector")
            assert(
                seedOnly.scores.toMap.count { case (_, v) => v > 0.0 } < propagated.scores.toMap.count { case (_, v) => v > 0.0 },
                "without propagation only seeded regions carry mass"
            )
        }

        "M04 seeds are read with the remaining mass renormalized, isolating placement from total mass" in {
            // Zeroing a seed both removes that seed AND deflates total mass by its share, and the second
            // effect alone moves every score against an absolute floor. Renormalizing the survivors to the
            // same total isolates the placement question, which is the one the mechanism is about.
            val zeroed = defaultCalibration.copy(seedObjective = 0.0)
            val share  = defaultCalibration.seedObjective
            val scale  = 1.0 / (1.0 - share)
            val renorm = zeroed.copy(
                seedTask = zeroed.seedTask * scale,
                seedTail = zeroed.seedTail * scale,
                seedUnresolved = zeroed.seedUnresolved * scale,
                seedSystem = zeroed.seedSystem * scale
            )
            val on    = arm()
            val off   = arm(renorm)
            val total = renorm.seedTask + renorm.seedTail + renorm.seedUnresolved + renorm.seedSystem
            assert(math.abs(total - 1.0) < 1e-9, s"REGIME: the renormalized seeds must still sum to 1.0, got $total")
            assert(on.ranking != off.ranking, "moving the objective seed's mass to the other seeds must reorder the ranking")
        }
    }

    "view-side mechanisms, read on level assignment and served tokens" - {

        "M14 vs M14b: staged summary bytes and the substitute elision are different views, and which is\n         smaller is a measurement, not an assumption" in {
            // The counterfactual of "summaries on" is NOT "terse": toTerse SKIPS a blob-less span, so it
            // stays at Summary and renders substituteElision, 800 chars of RAW head+tail bytes. So the two
            // arms differ in CONTENT KIND (paraphrase vs raw bytes) and their sizes are set by
            // substituteElisionChars against the staged byte length. Asserting a direction here would
            // encode an assumption; the plan's L2 arm is what prices the quality difference.
            val base      = arm()
            val staged    = stagedSummaries(base.spans)
            val withBytes = arm(defaultCalibration, staged = staged)
            val bytesLen  = ("summary of the span: decisions, values and open threads preserved. " * 12).length
            assert(
                bytesLen > defaultCalibration.tersePrefixChars,
                s"REGIME: staged bytes must exceed tersePrefixChars or round 1 is a no-op, got $bytesLen"
            )
            assert(
                withBytes.served != base.served,
                s"the two arms must render different views: staged=${withBytes.served} substitute=${base.served}"
            )
            assert(base.demoted.nonEmpty && withBytes.demoted.nonEmpty, "both arms must demote, or neither view is a compaction")
        }

        "M24 span grain sets the unit of loss: the cap binds only where a turn holds several regions" in {
            // Spans break at a turn start FIRST, so in an alternating chat every span is already narrow and
            // the region cap never binds. Exercising it needs a turn carrying several regions, which is what
            // a tool-using turn looks like: one user message, then a run of assistant steps.
            val body = "the step performs part of the work and reports what it found. " * 6
            val wide = Chunk[Message](tok(sm("head"), 40), tok(um("do the whole job in many steps"), 60)) ++
                Chunk.from((0 until 14).map(i => tok(am(s"step $i\n$body"), 900)))
            def spansOf(c: Calibration): Chunk[Span] =
                val d = Compactor.internal.Default(Tuning(), c)
                d.formSpans(d.group(wide), wide, cfg())
            val wideSpans   = spansOf(defaultCalibration)
            val cappedSpans = spansOf(defaultCalibration.copy(spanCapRegions = 2))
            assert(
                wideSpans.exists(_.regionIds.size >= 3),
                s"REGIME: the fixture must produce spans wider than the cap under test, got ${wideSpans.toList.map(_.regionIds.size)}"
            )
            assert(
                cappedSpans.forall(_.regionIds.size <= 2),
                s"the region cap must bound every span, got ${cappedSpans.toList.map(_.regionIds.size)}"
            )
            assert(
                cappedSpans.size > wideSpans.size,
                s"a tighter cap must split spans: capped=${cappedSpans.size} wide=${wideSpans.size}"
            )
        }

        "M24 the structural tail band excludes regions from demotion independently of the tail seed" in {
            // seedTail = 0 does NOT expose the tail: closedRegions excludes the tail band from span
            // formation entirely, at any score. These are two different protections and the plan treats
            // them as separate mechanisms because of exactly this.
            val noTailSeed = arm(defaultCalibration.copy(seedTail = 0.0))
            val d          = Compactor.internal.Default(Tuning(), defaultCalibration)
            val units      = d.group(raw)
            val spansAll   = d.formSpans(units, raw, cfg())
            val covered    = spansAll.toList.flatMap(_.regionIds.toList).toSet
            val excluded   = units.toList.map(_.id).filterNot(covered.contains).toSet
            assert(excluded.nonEmpty, s"REGIME: the tail band must exclude some region, got none of ${units.size}")
            assert(
                excluded.intersect(noTailSeed.demoted).isEmpty,
                s"a region excluded by the tail band must not demote even with the tail seed zeroed: ${excluded.intersect(noTailSeed.demoted)}"
            )
        }

        "M23 the oversized-verbatim elision rewrites the view with zero demotions" in {
            // Role 2: a pinned message longer than generousElisionChars is elided even when nothing is
            // demoted, so it changes the served view through a path no other row covers.
            val huge = Chunk[Message](
                tok(sm("head"), 20),
                tok(um("task"), 20),
                tok(am("X" * (defaultCalibration.generousElisionChars + 5000)), 100)
            )
            val d      = Compactor.internal.Default(Tuning(), defaultCalibration)
            val units  = d.group(huge)
            val spans  = d.formSpans(units, huge, cfg())
            val view   = d.project(huge, units, spans, Dict.empty[Int, Level], huge.size, Dict.empty)
            val joined = view.foldLeft("")((a, m) => a + m.content)
            assert(
                !joined.contains("X" * (defaultCalibration.generousElisionChars + 1)),
                "an oversized pinned message is elided even with no demotions"
            )
            val bigger  = Compactor.internal.Default(Tuning(), defaultCalibration.copy(generousElisionChars = 40000))
            val view2   = bigger.project(huge, units, spans, Dict.empty[Int, Level], huge.size, Dict.empty)
            val joined2 = view2.foldLeft("")((a, m) => a + m.content)
            assert(joined2.length > joined.length, "raising the elision budget must serve more of the oversized message")
        }
    }

    "the supersession family, ablated by SOURCE crossed with CHANNEL" - {

        // Supersession has two SOURCES (keyed, from matching compaction keys; analyzed, from a model
        // Supersedes relation) that merge into ONE map, and two CHANNELS out of that map (a score penalty,
        // and repointing other regions' edges onto the superseder). The penalty constant alone separates
        // none of it: setting it to 1.0 silences BOTH sources' penalties and still leaves BOTH sources
        // repointing. That confound is what made an earlier round's conclusion unattributable, so each
        // source and each channel gets its own lever and the four cells are pinned here.

        val identRaw = Chunk[Message](
            am("intro `Widget.field` in the opening turn"),
            am("mid turn that references `Widget.field` again"),
            am("update `Widget.field` with the new value"),
            am("later mention of `Widget.field`")
        )
        val keys     = Dict[Int, (String, Tool.Kind)]((0, ("db.yaml", Tool.Kind.Read)), (2, ("db.yaml", Tool.Kind.Write)))
        val analyses = Chunk(RegionAnalysis(3, Chunk(Relation(1, RelationKind.Supersedes))))

        def cell(c: Calibration = defaultCalibration, t: Tuning = Tuning()): (Dict[Int, Int], Dict[Int, Double], Int) =
            val d      = Compactor.internal.Default(t, c)
            val units  = d.group(identRaw)
            val merged = d.mergeSupersession(d.supersession(units, keys), d.analyzedSupersession(analyses))
            val g      = d.deriveGraph(units, identRaw, merged)
            val seed   = Dict[Int, Double]((3, 1.0))
            val sc     = d.score(units, g, merged, seed)
            // how many edges were redirected away from their literal target by repointing
            val repointed = units.toList.count(u => d.repoint(u.id, merged) != u.id)
            (merged, sc, repointed)
        end cell

        "both sources populate the merged map, and each lever removes exactly its own contribution" in {
            val (bothMap, _, _)   = cell()
            val (keyedOnly, _, _) = cell(t = Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.AnalyzedSupersession))
            val (anlOnly, _, _)   = cell(t = Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.KeyedSupersession))
            val (neither, _, _) =
                cell(t =
                    Tuning(mechanisms =
                        Compactor.Mechanism.all -- Set(Compactor.Mechanism.KeyedSupersession, Compactor.Mechanism.AnalyzedSupersession)
                    )
                )
            assert(bothMap.get(0) == Present(2), s"REGIME: keyed supersession must mark region 0, got $bothMap")
            assert(bothMap.get(1) == Present(3), s"REGIME: analyzed supersession must mark region 1, got $bothMap")
            assert(keyedOnly.get(0) == Present(2) && keyedOnly.get(1) == Absent, s"analyzed off leaves only the keyed mark, got $keyedOnly")
            assert(anlOnly.get(1) == Present(3) && anlOnly.get(0) == Absent, s"keyed off leaves only the analyzed mark, got $anlOnly")
            assert(neither.isEmpty, s"both sources off empties the map, got $neither")
        }

        "the penalty channel is exactly multiplicative, per source, and is 1/N-immune" in {
            // The attribution assertion the predecessor plan carried: a superseded region's score is
            // EXACTLY penalty-times its unsuperseded value. Multiplicative, so unlike an additive lift
            // against an absolute floor it does not vanish as the history grows.
            val d        = Compactor.internal.Default(Tuning(), defaultCalibration)
            val units    = d.group(identRaw)
            val g        = d.deriveGraph(units, identRaw, Dict.empty[Int, Int])
            val seed     = Dict[Int, Double]((3, 1.0))
            val plain    = d.score(units, g, Dict.empty[Int, Int], seed)
            val keyedMap = d.supersession(units, keys)
            val pen      = d.score(units, g, keyedMap, seed)
            assert(
                eps(pen.get(0).getOrElse(0.0), plain.get(0).getOrElse(0.0) * defaultCalibration.supersessionPenalty),
                s"the penalty must be exactly ${defaultCalibration.supersessionPenalty}x: plain=${plain.get(0)} penalized=${pen.get(0)}"
            )
            val noPenalty = d.score(units, g, keyedMap, seed)
            val unpenalized = Compactor.internal.Default(Tuning(), defaultCalibration.copy(supersessionPenalty = 1.0))
                .score(units, g, keyedMap, seed)
            assert(
                eps(unpenalized.get(0).getOrElse(0.0), plain.get(0).getOrElse(0.0)),
                s"penalty 1.0 restores the unsuperseded score, got ${unpenalized.get(0)} vs ${plain.get(0)}"
            )
            assert(noPenalty.get(0) != unpenalized.get(0), "the penalty lever must be the thing that moved the score")
        }

        "penalty = 1.0 does NOT stop repointing, which is why the two channels need separate levers" in {
            val (_, _, repointedAtPenaltyOne) = cell(defaultCalibration.copy(supersessionPenalty = 1.0))
            val (_, _, repointedDefault)      = cell()
            val (_, _, repointedOff)          = cell(t = Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.Repoint))
            assert(repointedDefault > 0, s"REGIME: repointing must be active by default, got $repointedDefault")
            assert(
                repointedAtPenaltyOne == repointedDefault,
                s"neutralizing the penalty leaves repointing untouched: penalty1=$repointedAtPenaltyOne default=$repointedDefault"
            )
            assert(repointedOff == 0, s"only the repoint lever stops repointing, got $repointedOff")
        }
    }

    "M13/M16 pointer reachability: the question F1 raised from the captured corpus" - {

        // Across 8 of 8 live census observations spanning 6 sessions and two window settings, the census
        // reported pointer=0: round 2 never ran in production. Two explanations were open, and they have
        // opposite consequences. Either round 1 simply sufficed at the pressure those sessions reached
        // (benign: pointer is a deeper reserve), or pointering cannot shed for the spans production forms
        // (a dead tier, plus a dead ordering channel and a dead guard). This decides it by driving pressure
        // to a target no assignment can reach, so round 2 must run to exhaustion.

        "under a target no assignment can reach, pointer engages for the spans production actually forms" in {
            val base   = arm()
            val staged = stagedSummaries(base.spans)
            val d      = Compactor.internal.Default(Tuning(), defaultCalibration)
            val units  = d.group(raw)
            val spans  = d.formSpans(units, raw, cfg())
            val seed   = d.seedVector(units, raw, staged)
            val g      = d.deriveGraph(units, raw, Dict.empty[Int, Int])
            val sc     = d.score(units, g, Dict.empty[Int, Int], seed)
            // low = 1 is unreachable, so cut escalates as far as the ladder permits
            val maxed  = d.cut(ctx, units, spans, sc, occupied = 40000, low = 1, since = raw.size, prevLevels = Dict.empty)
            val widths = spans.toList.map(_.regionIds.size)
            assert(widths.nonEmpty, s"REGIME: spans must form, got $widths")
            val levels    = maxed.toMap.values.toList
            val pointered = levels.count(_ == Level.Pointer)
            // Pointering a span trades one bounded prefix for one descriptor per member region, so it sheds
            // only for narrow spans. Production spans break at a turn start, which is what makes them narrow
            // enough for this to be reachable at all.
            assert(
                pointered > 0,
                s"pointer must be reachable under maximum pressure for turn-shaped spans; widths=$widths levels=$levels"
            )
        }

        "the guard holds: escalation never ends above where round 1 started" in {
            // The monotonicity property the pointer-level guard exists for, on production-shaped input.
            val base   = arm()
            val staged = stagedSummaries(base.spans)
            val d      = Compactor.internal.Default(Tuning(), defaultCalibration)
            val units  = d.group(raw)
            val spans  = d.formSpans(units, raw, cfg())
            val seed   = d.seedVector(units, raw, staged)
            val g      = d.deriveGraph(units, raw, Dict.empty[Int, Int])
            val sc     = d.score(units, g, Dict.empty[Int, Int], seed)
            def view(dem: Dict[Int, Level]): Int =
                d.viewTokens(d.project(raw, units, spans, dem, raw.size, Dict.empty, staged))
            val maxed = d.cut(ctx, units, spans, sc, occupied = 40000, low = 1, since = raw.size, prevLevels = Dict.empty)
            val pass1 = spans.toList.filter(sp => d.spanMaxLiveness(sp, sc) < Compactor.internal.keepFloor(units.size, Tuning()))
                .foldLeft(Dict.empty[Int, Level])((acc, sp) => acc.update(sp.start, Level.Summary))
            assert(
                view(maxed) <= view(pass1),
                s"escalation must not inflate the view it was called to shrink: pass1=${view(pass1)} final=${view(maxed)}"
            )
        }
    }

    "M02 usage-anchored occupancy changes the trigger reading, not just the estimate" in {
        // The offline path multiplies a re-sum by noUsageMargin (1.15), so the comparison is
        // anchored-vs-(estimate * 1.15) and the margin may dominate. Report both components rather than
        // attributing the whole delta to the estimator.
        val anchored = Context(raw).withCompaction(Compaction.State().withUsage(9000, raw.size))
        val offline  = Context(raw)
        val occA     = occupancy(anchored)
        val occO     = occupancy(offline)
        assert(occA != occO, s"the anchor must change the occupancy reading: anchored=$occA offline=$occO")
        val rawSum = raw.foldLeft(0)((a, m) => a + stampedTokens(m))
        assert(
            occO >= rawSum,
            s"REGIME: the offline reading carries the no-usage margin over the re-sum: offline=$occO rawSum=$rawSum"
        )
    }

    "the analysis layer, ablated on a FIXED context with the relation topology a live model produced" - {

        // The live L3 arm could not establish an effect: sessions diverge in length and content because the
        // model is nondeterministic, and that divergence moved the reading more than the ablation did.
        // Holding the context fixed removes the confound entirely.
        //
        // The relations below are NOT invented. They are the topology codex actually emitted in a live
        // session (qa/runs/l3-on-r3.log, after the wire-form fix that first let relations arrive at all),
        // remapped onto this fixture's region ordinals. Earlier deterministic work on this layer was fairly
        // criticized for injecting IDEAL relations; this injects observed ones, including the Supersedes
        // that live traffic produced.
        def liveShapedAnalyses(units: Chunk[Region]): Chunk[RegionAnalysis] =
            val ids                    = units.toList.map(_.id).sorted
            def at(i: Int): Maybe[Int] = if i >= 0 && i < ids.size then Present(ids(i)) else Absent
            // observed pattern: later regions depend on their immediate predecessor and relate back to the
            // task origin, with one supersession of an earlier region by a later one.
            val built =
                ids.zipWithIndex.drop(2).flatMap { (id, idx) =>
                    val deps = at(idx - 1).toList.filter(_ < id).map(t => Relation(t, RelationKind.DependsOn))
                    val rels = at(0).toList.filter(_ < id).map(t => Relation(t, RelationKind.Relates))
                    val sup =
                        if idx == ids.size - 2 then at(idx - 3).toList.filter(_ < id).map(t => Relation(t, RelationKind.Supersedes))
                        else Nil
                    val all = deps ++ rels ++ sup
                    if all.isEmpty then Nil else List(RegionAnalysis(id, Chunk.from(all)))
                }
            Chunk.from(built)
        end liveShapedAnalyses

        def armWithAnalyses(
            c: Calibration = defaultCalibration,
            t: Tuning = Tuning(),
            analyses: Chunk[RegionAnalysis]
        ): (Dict[Int, Level], Int, List[Int]) =
            val d      = Compactor.internal.Default(t, c)
            val units  = d.group(raw)
            val spans  = d.formSpans(units, raw, cfg())
            val staged = stagedSummaries(spans)
            val seed   = d.seedVector(units, raw, staged)
            val sup    = d.mergeSupersession(Dict.empty[Int, Int], d.analyzedSupersession(analyses))
            val g      = d.deriveGraph(units, raw, sup, d.analyzedEdges(analyses))
            val sc     = d.score(units, g, sup, seed)
            val levels =
                d.cut(ctx, units, spans, sc, occupied = 14000, low = 4000, since = raw.size, prevLevels = Dict.empty)
            val served  = d.viewTokens(d.project(raw, units, spans, levels, raw.size, Dict.empty, staged))
            val ranking = sc.toMap.toList.sortBy { case (id, v) => (-v, id) }.map(_._1)
            (levels, served, ranking)
        end armWithAnalyses

        "the injected topology is the shape live traffic produced, and it is non-trivial" in {
            val units    = Compactor.internal.Default(Tuning(), defaultCalibration).group(raw)
            val analyses = liveShapedAnalyses(units)
            val relCount = analyses.foldLeft(0)((n, ra) => n + ra.relations.size)
            assert(analyses.nonEmpty, "REGIME: the fixture must carry analyses to ablate")
            assert(relCount >= 10, s"REGIME: enough relations for the layer to act through, got $relCount")
            assert(
                analyses.exists(_.relations.exists(_.kind == RelationKind.Supersedes)),
                "REGIME: the topology must include the Supersedes that live traffic emitted"
            )
            assert(
                analyses.forall(ra => ra.relations.forall(_.target < ra.ordinal)),
                "REGIME: every injected relation is backward-only, as validation requires"
            )
        }

        "on one fixed context, the layer's relations change the liveness ranking" in {
            // Same raw content, same spans, same seeds, same staged summaries: the ONLY difference is
            // whether the layer's relations are allowed to act. This is the reading the live arm could not
            // produce, because there the two arms were different conversations.
            val units                           = Compactor.internal.Default(Tuning(), defaultCalibration).group(raw)
            val analyses                        = liveShapedAnalyses(units)
            val (onLevels, onServed, onRank)    = armWithAnalyses(analyses = analyses)
            val offCalibration                  = defaultCalibration.copy(dependencyWeight = 0.0, relatednessWeight = 0.0)
            val offTuning                       = Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.AnalyzedSupersession)
            val (offLevels, offServed, offRank) = armWithAnalyses(offCalibration, offTuning, analyses)
            assert(onRank != offRank, s"the layer's relations must move the ranking on a fixed context")
            // and the effect reaches the DECISION, not only the scores
            assert(
                onLevels.toMap != offLevels.toMap || onServed != offServed,
                s"the ranking change must reach the assignment or the served size: " +
                    s"onServed=$onServed offServed=$offServed levelsEqual=${onLevels.toMap == offLevels.toMap}"
            )
        }

        "the supersession relation is what carries the effect at scale, not the edge weights" in {
            // Mid-history scores run near 1/N, so an ADDITIVE lift from a dependency edge moves a region only
            // a little relative to its neighbours, while the supersession penalty is MULTIPLICATIVE and so
            // reorders regardless of how diluted the mass is. Ablating each separately shows which one acts.
            // (Under the old absolute keep floor the additive lift could not cross the floor AT ALL once the
            // history grew; the floor is a share of the uniform mass now, so the difference is one of degree.)
            val units            = Compactor.internal.Default(Tuning(), defaultCalibration).group(raw)
            val analyses         = liveShapedAnalyses(units)
            val (_, _, fullRank) = armWithAnalyses(analyses = analyses)
            val (_, _, noEdges) =
                armWithAnalyses(defaultCalibration.copy(dependencyWeight = 0.0, relatednessWeight = 0.0), analyses = analyses)
            val (_, _, noSuper) =
                armWithAnalyses(
                    t = Tuning(mechanisms = Compactor.Mechanism.all - Compactor.Mechanism.AnalyzedSupersession),
                    analyses = analyses
                )
            assert(fullRank != noEdges, "silencing the semantic edge weights must move the ranking")
            assert(fullRank != noSuper, "silencing analyzed supersession must move the ranking")
        }

        "the WEAK tier isolated: what Relates at 0.5 changes on its own" in {
            // Every measurement above moves dependencyWeight and relatednessWeight TOGETHER, which cannot
            // answer whether the WEAK tier earns its keep: DependsOn at 3.0 could be carrying the entire
            // effect while Relates at 0.5 rides along. This holds the strong tier at its shipped weight and
            // moves only the weak one.
            //
            // The observed topology is what makes this a real question rather than a formality: live traffic
            // points Relates at the TASK ORIGIN, and the task origin already carries seedTask, the second
            // largest seed share. A tier whose edges land on an already-hot region can be arithmetically
            // present and decision-irrelevant.
            val units    = Compactor.internal.Default(Tuning(), defaultCalibration).group(raw)
            val analyses = liveShapedAnalyses(units)
            val relates  = analyses.foldLeft(0)((n, ra) => n + ra.relations.count(_.kind == RelationKind.Relates))
            assert(relates >= 5, s"REGIME: the fixture must carry Relates edges to ablate, got $relates")

            val (onLevels, onServed, onRank)    = armWithAnalyses(analyses = analyses)
            val weakOff                         = defaultCalibration.copy(relatednessWeight = 0.0)
            val (offLevels, offServed, offRank) = armWithAnalyses(weakOff, analyses = analyses)

            val rankMoved   = onRank != offRank
            val levelsMoved = onLevels.toMap != offLevels.toMap
            val servedMoved = onServed != offServed
            println(
                s"[weak-tier] relates=$relates rankMoved=$rankMoved levelsMoved=$levelsMoved " +
                    s"servedMoved=$servedMoved (on=$onServed off=$offServed)"
            )
            // The strong tier must still be doing something in this same arm, or a null result below would
            // be unattributable: it could mean the fixture cannot register any edge effect at all.
            val strongOff             = defaultCalibration.copy(dependencyWeight = 0.0)
            val (_, _, strongOffRank) = armWithAnalyses(strongOff, analyses = analyses)
            assert(
                onRank != strongOffRank,
                "REGIME: silencing the STRONG tier must move the ranking on this fixture, or a null result " +
                    "for the weak tier says nothing about the weak tier"
            )
            assert(
                rankMoved,
                s"the weak Relates tier at ${defaultCalibration.relatednessWeight} does not move the ranking " +
                    s"on the topology live traffic produces, so it is decision-irrelevant here and its weight " +
                    s"is unearned: rankMoved=$rankMoved levelsMoved=$levelsMoved servedMoved=$servedMoved"
            )
        }
    }

    "M14b the substitute elision is only reachable while a span STAYS at Summary" in {
        // The live L2 off arm (fills disabled) rendered census[summary/terse=0 pointer=31]: with no staged
        // bytes the ladder did not settle on the substitute elision, it escalated past terse to pointer,
        // because terse cannot shorten a span it has no bytes for and round 2 then sheds. So the plan's
        // assumption that "no summary" means "the model sees the 800-char substitute elision" holds only
        // BELOW the pressure that triggers round 2. Above it, the model sees a descriptor and nothing else.
        // This pins both halves, since the difference decides what an unfilled slot actually costs.
        val d       = Compactor.internal.Default(Tuning(), defaultCalibration)
        val units   = d.group(raw)
        val spans   = d.formSpans(units, raw, cfg())
        val noBytes = Compaction.State()
        def render(dem: Dict[Int, Level]): String =
            d.project(raw, units, spans, dem, raw.size, Dict.empty, noBytes).foldLeft("")((a, m) => a + m.content)
        val atSummary   = spans.foldLeft(Dict.empty[Int, Level])((acc, sp) => acc.update(sp.start, Level.Summary))
        val atPointer   = spans.foldLeft(Dict.empty[Int, Level])((acc, sp) => acc.update(sp.start, Level.Pointer))
        val summaryView = render(atSummary)
        val pointerView = render(atPointer)
        // a span held at Summary with no bytes carries raw content through the substitute elision
        assert(
            summaryView.length > pointerView.length,
            s"an unfilled Summary span must carry elided raw bytes, more than a descriptor: ${summaryView.length} vs ${pointerView.length}"
        )
        assert(
            summaryView.contains("the service coordinates writes"),
            "the substitute elision carries real raw bytes, not a paraphrase"
        )
        // Read on SIZE, not on the presence of a chosen phrase. Two things make a phrase probe ill-posed
        // here: regions outside any span stay verbatim in both views, and the substitute elision keeps only
        // a head and a tail window, so whether any particular phrase survives depends on where it happens
        // to sit rather than on the mechanism.
        // Strict direction only. An earlier draft demanded a 20% margin, which was a number invented to
        // sound material rather than derived from anything: measured here it is about 15%, because the
        // spanned portion is a minority of a view whose tail stays verbatim. What the mechanism guarantees
        // is the direction, so that is what is asserted, with the magnitude recorded for the reader.
        assert(
            pointerView.length < summaryView.length,
            s"pointering must shed against an unfilled Summary: pointer=${pointerView.length} summary=${summaryView.length}"
        )
    }

    "the remaining rows, measured so every mechanism carries a verdict" - {

        "M21 the fill set responds to the assignment it is derived from" in {
            // FS is a set, so its effectiveness reading is a set difference: a mechanism that changes the
            // projected assignment must change which spans get an LLM call spent on them. That is the cost
            // channel, independent of quality.
            val d          = Compactor.internal.Default(Tuning(), defaultCalibration)
            val units      = d.group(raw)
            val spans      = d.formSpans(units, raw, cfg())
            val allSummary = spans.foldLeft(Dict.empty[Int, Level])((acc, sp) => acc.update(sp.start, Level.Summary))
            val halfSummary = spans.toList.zipWithIndex.foldLeft(Dict.empty[Int, Level]) { case (acc, (sp, i)) =>
                if i % 2 == 0 then acc.update(sp.start, Level.Summary) else acc.update(sp.start, Level.Pointer)
            }
            val allNeed  = d.fillNeed(spans, allSummary)
            val halfNeed = d.fillNeed(spans, halfSummary)
            assert(allNeed.size == spans.size, s"every summary-level span needs a fill, got ${allNeed.size} of ${spans.size}")
            assert(halfNeed.size < allNeed.size, s"a pointered span needs no fill: ${halfNeed.size} vs ${allNeed.size}")
            assert(
                halfNeed.toList.forall(sp => halfSummary.get(sp.start).contains(Level.Summary)),
                "the fill set is exactly the summary-level spans, never a pointered one"
            )
        }

        "M17 the forced path's whole job is that the view fits, and it does" in {
            // The forced path is a backstop, so it has no meaningful "off" arm: its effectiveness IS the
            // overflow-safety property. Driven here on production-shaped input rather than restated from
            // the existing unit assertions, so the row carries a measurement on this fixture too.
            val d      = Compactor.internal.Default(Tuning(), defaultCalibration)
            val units  = d.group(raw)
            val spans  = d.formSpans(units, raw, cfg())
            val staged = stagedSummaries(spans)
            val hard   = 3000 // a hard limit far below the raw size, so the path must actually shed to fit
            val seed   = d.seedVector(units, raw, staged)
            val scores = d.score(units, d.deriveGraph(units, raw, Dict.empty[Int, Int]), Dict.empty[Int, Int], seed)
            val view = d.forced(
                raw,
                units,
                spans,
                scores,
                hard = hard,
                since = raw.size,
                prevLevels = Dict.empty,
                keys = Dict.empty
            )
            assert(
                d.viewTokens(view) <= hard,
                s"the forced path must land within the hard limit: ${d.viewTokens(view)} vs $hard"
            )
        }
    }

    "semantic referencing PINS a mid-history region, which the absolute floor could not do" in {
        // The behaviour this guard exists for, which an earlier implementation had inverted.
        //
        // The analysis layer's central claim is that a region other regions DEPEND ON stays live. Under the
        // old ABSOLUTE keep floor it could not, at any scale worth having: scores are shares of one unit of
        // liveness spread over N regions, so they run near 1/N, while the floor was a fixed number. The
        // additive lift an edge provides could not reach it however many edges arrived. Measured on this
        // same fixture, a region with 17 inbound DependsOn edges scored 0.055 against a floor of 0.09, and
        // the hottest region in the whole context reached only 0.0906 while being seed-adjacent.
        //
        // The floor is a share of the uniform mass now, so a lift is weighed on the scale it lives on and
        // the channel is open. This test asserted the CLOSED channel until that change; its predecessor
        // said failing it would be good news rather than a regression, and this is that failure, inverted.
        val d      = Compactor.internal.Default(Tuning(), defaultCalibration)
        val units  = d.group(raw)
        val spans  = d.formSpans(units, raw, cfg())
        val staged = stagedSummaries(spans)
        val ids    = units.toList.map(_.id).sorted
        // pick a mid-history region and point every later region's DependsOn at it
        val target   = ids(ids.size / 2)
        val analyses = Chunk.from(ids.filter(_ > target).map(id => RegionAnalysis(id, Chunk(Relation(target, RelationKind.DependsOn)))))
        val inbound  = analyses.count(_.relations.exists(_.target == target))
        assert(inbound >= 4, s"REGIME: the target must carry several inbound references, got $inbound")

        val sup    = d.mergeSupersession(Dict.empty[Int, Int], d.analyzedSupersession(analyses))
        val graph  = d.deriveGraph(units, raw, sup, d.analyzedEdges(analyses))
        val seed   = d.seedVector(units, raw, staged)
        val scores = d.score(units, graph, sup, seed)
        val floor  = Compactor.internal.keepFloor(units.size, Tuning())
        val score  = scores.get(target).getOrElse(0.0)
        assert(
            score >= floor,
            s"a mid-history region with $inbound inbound DependsOn edges must reach the keep floor and stay " +
                s"verbatim: score=$score floor=$floor"
        )
        // The pin has to be EARNED, or the fix is worthless in the other direction: a floor low enough to
        // pin everything preserves nothing in particular and leaves the size pass with nothing to shed.
        // Same context, same floor, a mid-history region that NOTHING references must still demote.
        val unreferenced = ids.filter(id => id > ids.head + 2 && id < ids.last - 2 && id != target)
        val coldest      = unreferenced.map(id => scores.get(id).getOrElse(0.0)).minOption.getOrElse(0.0)
        assert(
            coldest < floor,
            s"an unreferenced mid-history region must still be demotable, or the floor pins indiscriminately: " +
                s"coldest=$coldest floor=$floor"
        )
        assert(
            unreferenced.count(id => scores.get(id).getOrElse(0.0) < floor) > unreferenced.size / 2,
            "and MOST unreferenced mid-history regions are demotable, so the channel is selective rather than open to all"
        )
    }

    "THE COST QUESTION: uncached input tokens, compacted vs not, under prompt caching" in {
        // The question the other cost readings do not answer. Each of those uses served tokens or call
        // counts; under the project's standing assumption that prompt caching is present and transparent,
        // the price is UNCACHED INPUT TOKENS: a request pays only for the suffix after the longest prefix
        // it shares with what was sent before.
        //
        // Compaction is structurally hostile to that. It rewrites the OLDEST part of the view, so a
        // demotion invalidates from near the head onward. An append-only conversation, by contrast, keeps a
        // perfect prefix and pays only for the new turn.
        //
        // Simulated turn by turn on the production-shaped fixture: this is arithmetic over the views the
        // two strategies actually emit, not an estimate.
        def uncachedRun(views: List[Chunk[Message]]): Int =
            views.sliding(2).foldLeft(views.headOption.map(v => v.foldLeft(0)((a, m) => a + stampedTokens(m))).getOrElse(0)) {
                case (acc, prev :: cur :: Nil) =>
                    val shared = prev.toList.zip(cur.toList).takeWhile((a, b) => a.content == b.content && a.role == b.role).size
                    acc + cur.drop(shared).foldLeft(0)((a, m) => a + stampedTokens(m))
                case (acc, _) => acc
            }

        val d      = Compactor.internal.Default(Tuning(), defaultCalibration)
        val config = cfg()
        // grow the conversation two messages at a time, exactly as a session does
        val steps = (4 to raw.size by 2).toList
        val compactedViews = steps.map { n =>
            val prefix = raw.take(n)
            val ctxN   = Context(prefix)
            val units  = d.group(prefix)
            val spans  = d.formSpans(units, prefix, config)
            val staged = stagedSummaries(spans)
            if Compactor.internal.occupancy(ctxN) < axisOf(config).high then prefix
            else
                val seed   = d.seedVector(units, prefix, staged)
                val scores = d.score(units, d.deriveGraph(units, prefix, Dict.empty[Int, Int]), Dict.empty[Int, Int], seed)
                val levels = d.cut(
                    ctxN,
                    units,
                    spans,
                    scores,
                    occupied = Compactor.internal.occupancy(ctxN),
                    low = axisOf(config).low,
                    since = prefix.size,
                    prevLevels = Dict.empty
                )
                d.project(prefix, units, spans, levels, prefix.size, Dict.empty, staged)
            end if
        }
        val rawViews = steps.map(raw.take)

        val compactedCost = uncachedRun(compactedViews)
        val rawCost       = uncachedRun(rawViews)
        val finalRaw      = raw.foldLeft(0)((a, m) => a + stampedTokens(m))
        val rewrites      = compactedViews.sliding(2).count { case p :: c :: Nil => p.headOption != c.headOption; case _ => false }

        // REGIME: the fixture must actually cross the boundary, or this compares two identical strategies
        assert(
            compactedViews.exists(_.size < rawViews.last.size),
            "REGIME: compaction must engage, or both arms are the same conversation"
        )
        assert(
            finalRaw < config.modelContextWindow,
            s"REGIME: this measures an IN-WINDOW session, where not compacting is a real option: raw=$finalRaw window=${config.modelContextWindow}"
        )

        // The finding is the RATIO, recorded here so a change in the machinery moves a number a reader sees.
        val ratio = compactedCost.toDouble / math.max(rawCost, 1)
        assert(
            compactedCost > 0 && rawCost > 0,
            s"both strategies must have a cost to compare: compacted=$compactedCost raw=$rawCost"
        )
        assert(
            ratio > 1.0,
            s"MEASURED: compaction costs ${ratio}x the uncached input of not compacting on an in-window session " +
                s"(compacted=$compactedCost raw=$rawCost, head rewrites=$rewrites). If this drops below 1.0 the " +
                s"cost case for compacting in-window has changed and the caching analysis must be re-derived."
        )
        println(
            f"[cost] uncached input: compacted=$compactedCost%d raw=$rawCost%d ratio=$ratio%.2fx headRewrites=$rewrites%d finalRaw=$finalRaw%d"
        )
    }

end CompactorAblationTest
