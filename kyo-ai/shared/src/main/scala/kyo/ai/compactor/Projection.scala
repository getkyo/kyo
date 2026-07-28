package kyo.ai.compactor

import kyo.*
import kyo.Compactor
import kyo.Compactor.Mechanism
import kyo.Compactor.Tuning
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*
import scala.annotation.tailrec

/** Projection: turning liveness scores into a served view. The size pass and the presentation pass. cut assigns each span a detail level, descending only as far as the budget requires, and forced is the backstop for a view that still will not fit. Deterministic and model-free: every input is frozen by the time this runs, which is the property the purity tests pin.
  */
private[kyo] trait Projection:
    self: Compactor.internal.Default =>

    import Model.*
    // The same unqualified access to the policy and the measured constants the rest of the default has.
    import calibration.*
    import tuning.*

    // The coldest member liveness of a span (pass-2 ascending order) and its hottest member
    // A span's liveness is its HOTTEST member, and that one grain answers both questions asked of it:
    // whether the span may be demoted at all (every member below the floored keep) and, among those
    // that may, which sheds first. Ordering by the coldest member instead would read a different
    // quantity than the gate: a relation that lifts the member a span is kept for cannot move a
    // minimum, while unrelated mass reaching the span's coldest member moves it freely, so the shed
    // order would respond to noise and ignore the signal.
    def spanMaxLiveness(sp: Span, scores: Dict[Int, Double]): Double =
        sp.regionIds.foldLeft(0.0)((m, id) => math.max(m, scores.get(id).getOrElse(0.0)))

    // A_prep: the speculative level assignment under PROJECTED boundary semantics. The cut carries
    // no cache-gate veto, so the projection is the same cut run at the projected boundary: occupancy
    // at exactly effectiveHigh, so pass 2 descends as it will at the boundary. Pure, in-memory,
    // model-free; recomputed every seam pass, so the gate compares an assignment at most one
    // generation old. Since the PIN floor no longer reads the budget, A_prep and the live cut agree
    // on the pinned set exactly, differing only in pass-2 depth: a span pinned in the projection is
    // pinned at serve time too, so the fill economy no longer buys nothing for a span that then
    // demotes, nor skips a fill for a span that then needs one.
    def projectedAssignment(
        ctx: Context,
        units: Chunk[Region],
        spans: Chunk[Span],
        scores: Dict[Int, Double],
        config: Config,
        since: Int,
        prevLevels: Dict[Int, Context.Origin]
    )(using Frame): Dict[Int, Level] =
        val a    = ax(config)
        val high = a.high
        val low  = a.low
        cut(ctx, units, spans, scores, high, low, since, prevLevels)
    end projectedAssignment

    // the need-shaped fill set: EXACTLY the spans A_prep assigns the summary level.
    // A projected-pinned span is absent from the assignment (never demoted); a projected-pointer
    // span carries Level.Pointer; neither buys a fill. The assignment is the bound: no
    // speculationMargin, no covered-savings ledger.
    def fillNeed(spans: Chunk[Span], assignment: Dict[Int, Level]): Chunk[Span] =
        if !mechanisms.contains(Mechanism.SummaryFills) then Chunk.empty
        else spans.filter(sp => assignment.get(sp.start).contains(Level.Summary))

    // The LLM-free core of keyed supersession: takes the tool infos captured in the
    // FOREGROUND (where LLM is available), so the background fiber, which is LLM-free, computes
    // keyed supersession from the snapshot without reading Tool.internal.infos. The foreground
    // superKeys is unchanged; it reads infos and this is the same pure keying it applies.
    def superKeysFrom(
        units: Chunk[Region],
        raw: Chunk[Message],
        infos: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Dict[Int, (String, Tool.Kind)] =
        val byName = infos.foldLeft(Dict.empty[String, Tool.internal.Info[?, ?, LLM]])((m, i) => m.update(i.name, i))
        units.foldLeft(Dict.empty[Int, (String, Tool.Kind)]) { (acc, u) =>
            val calls = u.indices.flatMap { idx =>
                raw(idx) match
                    case AssistantMessage(_, cs, _, _) => cs
                    case _                             => Chunk.empty
            }
            val keyed = calls.foldLeft(Absent: Maybe[(String, Tool.Kind)]) { (found, call) =>
                found match
                    case Present(_) => found
                    case Absent =>
                        byName.get(call.function) match
                            case Absent        => Absent
                            case Present(info) => info.supersessionKeyFor(call.arguments)
            }
            keyed match
                case Present(kk) => acc.update(u.id, kk)
                case Absent      => acc
        }
    end superKeysFrom

    // the per-region relation cap (proposed 4): bounds a disobedient pass and the output bill.
    val relationCap: Int = 4 // not configuration: it bounds how much a single model reply can inject
    // into the graph, which is a safety limit on untrusted output rather than a tuning choice

    // the analysis's directed relations become the graph's two SEMANTIC edge kinds: DependsOn
    // -> Dependency (weight 3.0), Relates -> Relatedness (weight 0.5). Both point BACKWARD (parse
    // enforces target < ordinal), matching every edge kind; Supersedes yields NO edge here.
    // deriveGraph repoints each target through supersession, so semantic liveness also accrues to
    // current content.
    def analyzedEdges(analyses: Chunk[RegionAnalysis]): Chunk[(Int, Int, EdgeKind)] =
        analyses.flatMap { ra =>
            ra.relations.collect {
                case Relation(t, RelationKind.DependsOn) => (ra.ordinal, t, EdgeKind.Dependency)
                case Relation(t, RelationKind.Relates)   => (ra.ordinal, t, EdgeKind.Relatedness)
            }
        }

    // keyless supersession: a Supersedes relation marks the EARLIER region (the relation's
    // target) superseded by the analyzed region (its ordinal), exactly as a compaction-key rewrite
    // does. Returns superseded-region -> superseding-region, the shape `supersession` also produces.
    def analyzedSupersession(analyses: Chunk[RegionAnalysis]): Dict[Int, Int] =
        if !mechanisms.contains(Mechanism.AnalyzedSupersession) then Dict.empty
        else
            analyses.foldLeft(Dict.empty[Int, Int]) { (m, ra) =>
                ra.relations.foldLeft(m) { (mm, rel) =>
                    rel.kind match
                        case RelationKind.Supersedes => mm.update(rel.target, ra.ordinal)
                        case _                       => mm
                }
            }

    // "one belief, two detectors": union the keyed (deterministic, free) and keyless (analyzed)
    // supersession maps. The keyed detector wins a same-target conflict, being deterministic; the
    // keyless one extends the belief to prose decisions no key can carry.
    def mergeSupersession(keyed: Dict[Int, Int], keyless: Dict[Int, Int]): Dict[Int, Int] =
        keyless.foldLeft(keyed) { (m, target, superseder) =>
            if m.contains(target) then m else m.update(target, superseder)
        }

    // the closed, not-yet-analyzed regions the next arming event covers. Closed =
    // below the tail band and resolved (an unresolved region never ages into the closed
    // prefix). Already-analyzed regions (by ordinal, in adopted state) are excluded:
    // write-once needs no more. Sorted ascending, so the head is the low-water ordinal.
    def analysisPending(ctx: Context, config: Config): Chunk[Region] =
        val units    = group(ctx.raw)
        val tail     = tailUnits(units)
        val analyzed = ctx.compactionState.analyses.map(_.ordinal).toSet
        units.filter(u => !tail.contains(u.id) && !u.unresolved && !analyzed.contains(u.id)).sortBy(_.id)
    end analysisPending

    // The low-water ordinal: the lowest closed unanalyzed region id, or -1 when none pend.
    def analysisLowWater(ctx: Context, config: Config): Int =
        analysisPending(ctx, config).headMaybe.map(_.id).getOrElse(-1)

    // ---- the cut: the DEMOTION RULE, one rule for every boundary.
    // Pinning: a span with any at-or-above-floor member stays verbatim, where the floor is a
    // multiple of the uniform liveness share and does NOT depend on the budget.
    // Pass 1 (relevance, unconditional): every demotable span to its summary level, no stop
    // condition. Pass 2 (size, conditional): only while occupied > low, descend summary-level spans
    // in ascending liveness, one level per round (summary -> terse skipping blob-less/short spans,
    // then -> per-region pointers), stopping the moment occupied <= low. The budget enters ONCE,
    // as pass 2's stop condition; it never sizes any text. The keys are span.start.
    def cut(
        ctx: Context,
        units: Chunk[Region],
        spans: Chunk[Span],
        scores: Dict[Int, Double],
        occupied: Int,
        low: Int,
        since: Int,
        prevLevels: Dict[Int, Context.Origin]
    )(using Frame): Dict[Int, Level] =
        val state     = ctx.compactionState
        val floor     = keepFloor(units.size, tuning)
        val demotable = spans.toList.filter(sp => spanMaxLiveness(sp, scores) < floor)
        val pass1     = demotable.foldLeft(Dict.empty[Int, Level])((d, sp) => d.update(sp.start, Level.Summary))
        if occupied <= low then pass1
        else
            val ascending = demotable.sortBy(sp => spanMaxLiveness(sp, scores))
            def fits(dem: Dict[Int, Level]): Boolean =
                viewTokens(project(ctx.raw, units, spans, dem, since, prevLevels, state)) <= low
            // round 1: summary -> terse where summary bytes exist and exceed the terse budget
            // (a blob-less span or one at/under the budget is a zero-saving step, skipped).
            @tailrec def toTerse(rem: List[Span], dem: Dict[Int, Level]): Dict[Int, Level] =
                if fits(dem) then dem
                else
                    rem match
                        case Nil => dem
                        case sp :: rest =>
                            state.summaryOf(sp.start, sp.end) match
                                case Present(bytes) if bytes.length > tersePrefixChars =>
                                    toTerse(rest, dem.update(sp.start, Level.Terse))
                                case _ => toTerse(rest, dem)
            val tersed = toTerse(ascending, pass1)
            // round 2: remaining summary/terse spans -> per-region pointers, ascending, until fit.
            // A pointer span renders one marker per member REGION where summary and terse render one
            // for the whole span, so the step only sheds for a narrow span; on a wide one it trades a
            // bounded prefix for k descriptors and the view GROWS. Round 1 already skips its own
            // zero-saving step; this is the same guard, and without it the pass can walk the whole
            // list inflating the view it was called to shrink, never reaching fit.
            @tailrec def toPointer(rem: List[Span], dem: Dict[Int, Level]): Dict[Int, Level] =
                if fits(dem) then dem
                else
                    rem match
                        case Nil => dem
                        case sp :: rest =>
                            val stepped = dem.update(sp.start, Level.Pointer)
                            if viewTokens(project(ctx.raw, units, spans, stepped, since, prevLevels, state)) <
                                    viewTokens(project(ctx.raw, units, spans, dem, since, prevLevels, state))
                            then toPointer(rest, stepped)
                            else toPointer(rest, dem)
                            end if
            toPointer(ascending, tersed)
        end if
    end cut

    // ---- forced path: a single giant append over the hard limit. Byte-for-byte the
    // pointer pass with pinning overridden (pointer EVERY span, ascending liveness, until fit), then
    // the generous exact-surface elision of the one oversized pinned-tail unit. render aborts with
    // AIContextOverflowException only when even that cannot fit. NO model calls.
    def forced(
        raw: Chunk[Message],
        units: Chunk[Region],
        spans: Chunk[Span],
        scores: Dict[Int, Double],
        hard: Int,
        since: Int,
        prevLevels: Dict[Int, Context.Origin],
        keys: Dict[Int, (String, Tool.Kind)]
    )(using Frame): Chunk[Message] =
        forcedWithLevels(raw, units, spans, scores, hard, since, prevLevels, keys)._1

    // The canonical form, handing the assignment out beside the view. A forced render overrides pinning
    // and points every span it has to, so its assignment is not the cut's; anything downstream that
    // describes what the model can currently read (automatic recall's index) must read THIS one.
    def forcedWithLevels(
        raw: Chunk[Message],
        units: Chunk[Region],
        spans: Chunk[Span],
        scores: Dict[Int, Double],
        hard: Int,
        since: Int,
        prevLevels: Dict[Int, Context.Origin],
        keys: Dict[Int, (String, Tool.Kind)]
    )(using Frame): (Chunk[Message], Dict[Int, Level]) =
        val ascending = spans.toList.sortBy(sp => spanMaxLiveness(sp, scores))
        @tailrec def pointerAll(rem: List[Span], dem: Dict[Int, Level]): Dict[Int, Level] =
            if viewTokens(project(raw, units, spans, dem, since, prevLevels, keys = keys)) <= hard then dem
            else
                rem match
                    case Nil        => dem
                    case sp :: rest => pointerAll(rest, dem.update(sp.start, Level.Pointer))
        val levels = pointerAll(ascending, Dict.empty)
        val view   = project(raw, units, spans, levels, since, prevLevels, keys = keys)
        if viewTokens(view) <= hard then (view, levels)
        else (elideOversizedTail(view, hard), levels)
    end forcedWithLevels

    // Replace the single largest tail message with its generous exact-surface elision
    // when it alone breaks the hard limit; returns the view unchanged when no single unit dominates.
    def elideOversizedTail(view: Chunk[Message], hard: Int)(using Frame): Chunk[Message] =
        if view.isEmpty then view
        else
            val idx = view.zipWithIndex.maxBy((m, _) => stampedTokens(m))._2
            view.zipWithIndex.map { (m, i) => if i == idx then elideMessage(m, generousElisionChars) else m }

    def elideMessage(m: Message, budget: Int): Message = m match
        case msg: ToolMessage      => msg.copy(content = elide(msg.content, budget))
        case msg: UserMessage      => msg.copy(content = elide(msg.content, budget))
        case msg: AssistantMessage => msg.copy(content = elide(msg.content, budget))
        case msg: SystemMessage    => msg.copy(content = elide(msg.content, budget))
end Projection
