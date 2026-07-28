package kyo.ai.compactor

import kyo.*
import kyo.Compactor
import kyo.Compactor.Mechanism
import kyo.Compactor.Tuning
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*
import scala.annotation.tailrec

/** Speculative preparation: the background half of the default compactor.
  *
  * A boundary needs summary bytes for the spans it is about to demote, and an analysis of the regions that
  * have closed since the last one. Both are model calls, and neither can be made ON the boundary without
  * paying its latency in the user's turn. So they are armed BELOW the boundary, run on a forked fiber, and
  * publish into write-once cells that the next boundary adopts if they landed in time and ignores if they
  * did not. That is the whole design: a boundary is never blocked on a fill, and a fill that arrives late
  * is never wasted, it just serves a later boundary.
  *
  * This is the LIFECYCLE half: when to arm, when to interrupt, what a boundary actually needs, and what to
  * adopt. The calls themselves live in [[Fills]]. Split out of [[Compactor]] because it is the
  * concurrent part, which is what lets the projection stay a pure function.
  *
  * Mixed into the default compactor with a self-type rather than parameterized, because working out what is
  * worth preparing means running the whole decision pipeline: grouping, ranking, and the projected
  * assignment.
  */
private[kyo] trait Preparation:
    self: Compactor.internal.Default =>

    import Model.*
    // The same unqualified access to the policy and the measured constants that the rest of the default
    // has, so the code below reads identically to when it lived inside the class.
    import calibration.*
    import tuning.*

    def adopt(state: Compaction.State, staged: Staged): Compaction.State =
        val withSummaries = staged.summaries.foldLeft(state)((s, key, bytes) => s.withSummary(key.start, key.end, bytes))
        staged.analyses.foldLeft(withSummaries)((s, _, ra) => s.withAnalysis(ra))

    // the VALIDITY GATE (pinning partition). Agree iff, for every span in the
    // prepared domain, A_prep and A_fresh agree on pinned-verbatim (absent from the assignment)
    // vs demoted (Present at ANY level): pass-2 depth is demoted-to-demoted and never gates. BOTH
    // disagreement directions break agreement (prepared-demoted/fresh-pinned = soundness,
    // prepared-pinned/fresh-demotable = completeness). Pure; used to select the join need source
    // so a false invalidation costs zero new model calls (the ingredients survive write-once).
    def validityGate(aPrep: Dict[Int, Level], aFresh: Dict[Int, Level], spans: Chunk[Span]): Boolean =
        spans.forall(sp => aPrep.get(sp.start).isDefined == aFresh.get(sp.start).isDefined)

    // The transcript-derived half of a boundary's work, computed once for both foreground runs. See
    // Model.BoundaryFacts for why each field is invariant across the pair and what is deliberately
    // absent from it.
    def boundaryFacts(ctx: Context, config: Config, infos: Chunk[Tool.internal.Info[?, ?, LLM]])(using Frame): BoundaryFacts =
        val units = group(ctx.raw)
        BoundaryFacts(
            units = units,
            spans = formSpans(units, ctx.raw, config),
            keys = superKeysFrom(units, ctx.raw, infos),
            index = tokenIndex(units, ctx.raw),
            seed = seedVector(units, ctx.raw, ctx.compactionState),
            prevLevels = demotedOrigins(ctx.compacted),
            since = ctx.raw.size,
            occupied = occupancy(ctx)
        )
    end boundaryFacts

    // the boundary's exact need, gated: A_fresh over the adopted state, then the fill
    // set of the gate-selected assignment (adopt -> A_prep, invalidate -> A_fresh) restricted to
    // spans whose write-once slot is still empty. LLM-free (infos captured in the foreground).
    def boundaryNeed(ctx: Context, config: Config, facts: BoundaryFacts)(using Frame): Chunk[Span] =
        val units      = facts.units
        val spans      = facts.spans
        val analyses   = ctx.compactionState.analyses
        val superseded = mergeSupersession(supersession(units, facts.keys), analyzedSupersession(analyses))
        val graph      = deriveGraph(facts.index, superseded, analyzedEdges(analyses))
        val scores     = score(units, graph, superseded, facts.seed)
        val prevLevels = facts.prevLevels
        val since      = facts.since
        val low        = ax(config).low
        val aFresh     = cut(ctx, units, spans, scores, facts.occupied, low, since, prevLevels)
        val aPrep      = projectedAssignment(ctx, units, spans, scores, config, since, prevLevels)
        val source     = if validityGate(aPrep, aFresh, spans) then aPrep else aFresh
        val state      = ctx.compactionState
        fillNeed(spans, source).filter(sp => state.summaryOf(sp.start, sp.end).isEmpty)
    end boundaryNeed

    // Per-pass arming below the boundary. Reconciles the prepare band against its wanted state: a
    // fresh cross into the band with no run in flight forks the single-flight run, and a pass that
    // leaves the band eagerly interrupts it. Returns the possibly-seated session so the seam
    // threads it back through setSession.
    def armBelowBoundary(
        ctx: Context,
        config: Config,
        prep: Model.Preparation
    )(using Frame): Unit < (LLM & Async & Abort[AIGenException]) =
        Kyo.lift(prep).map { prep =>
            val a        = ax(config)
            val occupied = occupancy(ctx)
            // Scoped to SPECULATIVE work only: with Preparation off nothing arms in advance, while a
            // fired boundary still does its own work for whatever remains enabled. The blanket
            // reading ("off means no fibers") would call that correct behaviour a violation.
            val wanted =
                mechanisms.contains(Mechanism.Preparation) &&
                    occupied >= a.prepare && occupied < a.high
            Tool.internal.infos.map { infos =>
                updateArm(prep, wanted, ctx, config, infos)
            }
        }
    end armBelowBoundary

    // Reconciles the prepare band against its wanted state over the single-flight run. On a fresh
    // want it forks the run (or latches onto an in-flight one); on a cleared want it interrupts.
    // A no-op when wantedness is unchanged, so a pass that stays inside the band re-forks nothing.
    def updateArm(
        prep: Model.Preparation,
        wanted: Boolean,
        ctx: Context,
        config: Config,
        infos: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Unit < Async =
        prep.armed.get.map { current =>
            if wanted && !current then
                prep.inFlight.get.map {
                    case Present(_) => prep.armed.set(true)
                    case Absent     => forkPreparation(ctx, config, prep, infos).andThen(prep.armed.set(true))
                }
            else if !wanted && current then
                prep.armed.set(false).andThen {
                    prep.inFlight.getAndSet(Absent).map {
                        case Present(f) => f.interrupt.unit
                        case Absent     => Kyo.unit
                    }
                }
            else Kyo.unit
        }
    end updateArm

    // The boundary: (1) adopt staged -> ctx, (2) derive the exact need, (3) join the fiber for
    // that need, (4) adopt what the join wrote. Returns the ctx to render (with filled state) and
    // the possibly-seated session.
    def boundaryPrepare(
        ctx: Context,
        config: Config,
        prep: Model.Preparation
    )(using Frame): (Context, BoundaryFacts) < (LLM & Async & Abort[AIGenException]) =
        Kyo.lift(prep).map { prep =>
            Tool.internal.infos.map { infos =>
                // A fired boundary is an analysis arming event (design 1306-1310): fork the
                // single-flight preparationRun (which carries runAnalysis over the not-yet-analyzed
                // regions, plus its projected fills) when no LIVE run is in flight, so the semantic
                // layer engages even when the prepare band was skipped or the session lives at or
                // above effectiveHigh. Fire-and-forget: the boundary never blocks on the analysis
                // (design 1313-1316); it awaits only its fill need through joinPreparation below.
                armAnalysisAtBoundary(ctx, config, prep, infos).andThen {
                    prep.staged.get.map { staged0 =>
                        val adopted0 = ctx.withCompaction(adopt(ctx.compactionState, staged0))
                        // Computed HERE, over adopted0, and threaded out to the projection. The join
                        // below adopts more summaries and analyses, which is exactly what the facts do
                        // not depend on, so the projection reusing them is a removal of repeated work
                        // rather than a change of what it sees.
                        val facts = boundaryFacts(adopted0, config, infos)
                        val need  = boundaryNeed(adopted0, config, facts)
                        joinPreparation(adopted0, config, prep, need, infos).map { filled =>
                            (adopted0.withCompaction(adopt(adopted0.compactionState, filled)), facts)
                        }
                    }
                }
            }
        }
    end boundaryPrepare

    // Arms the analysis at a fired boundary by forking the single-flight preparationRun when no
    // LIVE run is in flight. A handle the cell still holds from a COMPLETED run does not count as
    // live, so a fired boundary reliably re-arms; a genuinely in-flight run is left to finish
    // (single-flight preserved). Fire-and-forget into the per-session cell, torn down by release.
    def armAnalysisAtBoundary(
        ctx: Context,
        config: Config,
        prep: Model.Preparation,
        infos: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Unit < Async =
        prep.inFlight.get.map {
            case Absent => forkPreparation(ctx, config, prep, infos)
            case Present(fiber) =>
                fiber.done.map(d => if d then forkPreparation(ctx, config, prep, infos) else Kyo.unit)
        }
    end armAnalysisAtBoundary

    // JOIN. Empty need returns instantly (the invisible case). Otherwise the boundary awaits ONLY
    // its exact fill need, never the whole in-flight run: preparationRun sequences runAnalysis
    // BEFORE its fills, so blocking on the fiber would block the boundary on an analysis round trip
    // (design 1313-1316, 1414-1418). Write-once staging (fillRemaining, summaryOf guard) makes this
    // idempotent with any in-flight run's fills, so we fill the exact need directly and let the
    // in-flight run finish in the background. A missing analysis is never a blocking need.
    def joinPreparation(
        ctx: Context,
        config: Config,
        prep: Model.Preparation,
        need: Chunk[Span],
        infos: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Staged < (Async & Abort[AIGenException]) =
        if need.isEmpty then prep.staged.get
        else fillRemaining(ctx, config, prep, need, infos)
    end joinPreparation

    // Fills each still-empty span in `need` (write-once), degrading a failed fill to an absent
    // slot -> the substitute elision at render, never failing the user's generation. Runs in dependency
    // order (oldest first) so the degraded route feeds earlier summaries to later fills.
    def fillRemaining(
        ctx: Context,
        config: Config,
        prep: Model.Preparation,
        need: Chunk[Span],
        infos: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Staged < (Async & Abort[AIGenException]) =
        // Gated at the point of ACTION, not only where the need is computed. Every fill in the
        // subsystem passes through here, so a route that derives its need some other way still
        // cannot spend an LLM call while fills are off.
        if !mechanisms.contains(Mechanism.SummaryFills) then prep.staged.get
        else fillEach(ctx, config, prep, need, infos)
    end fillRemaining

    private def fillEach(
        ctx: Context,
        config: Config,
        prep: Model.Preparation,
        need: Chunk[Span],
        infos: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Staged < (Async & Abort[AIGenException]) =
        val spans = formSpans(group(ctx.raw), ctx.raw, config)
        Kyo.foreachDiscard(need.sortBy(_.start)) { sp =>
            val key = SpanKey(sp.start, sp.end)
            prep.staged.get.map { staged =>
                if staged.summaryOf(key).isDefined then Kyo.unit
                else
                    runFill(sp, spans, ctx, config, prep).handle(Abort.run[AIGenException]).map {
                        case Result.Success(bytes) => prep.staged.getAndUpdate(_.withSummary(key, bytes)).unit
                        case _                     => Kyo.unit
                    }
            }
        }.andThen(prep.staged.get)
    end fillEach

    // The single-flight fiber body: LLM-free wrt the foreground handler.
    // Recomputes the need-shaped fill set over the SNAPSHOT (raw/config/compaction state in ctx,
    // tool infos captured) and fills each summary-level span, staging results write-once
    // INCREMENTALLY as each completes so a partial run is still useful. Every failure recovered
    // here so no auxiliary failure ever escapes the fiber.
    def preparationRun(
        ctx: Context,
        config: Config,
        prep: Model.Preparation,
        infos: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Unit < Async =
        val units    = group(ctx.raw)
        val spans    = formSpans(units, ctx.raw, config)
        val analyses = ctx.compactionState.analyses
        val superseded =
            mergeSupersession(supersession(units, superKeysFrom(units, ctx.raw, infos)), analyzedSupersession(analyses))
        val graph      = deriveGraph(units, ctx.raw, superseded, analyzedEdges(analyses))
        val seed       = seedVector(units, ctx.raw, ctx.compactionState)
        val scores     = score(units, graph, superseded, seed)
        val prevLevels = demotedOrigins(ctx.compacted)
        val since      = ctx.raw.size
        val aPrep      = projectedAssignment(ctx, units, spans, scores, config, since, prevLevels)
        val need       = fillNeed(spans, aPrep).filter(sp => ctx.compactionState.summaryOf(sp.start, sp.end).isEmpty)
        // the analysis rides THIS arming event: one typed call covers every closed
        // not-yet-analyzed region (the low-water ordinal), targeting only the reachable
        // (summary-or-verbatim) region set, staged write-once. Every
        // failure is recovered inside runAnalysis: a failed analysis leaves its regions
        // unanalyzed, the graph degrades to structural-only, and gen never fails. Then the
        // need-shaped fills, each staged write-once and incrementally.
        val reachable = analysisReach(units, spans, aPrep, tailUnits(units))
        val analysis  = runAnalysis(ctx, analysisPending(ctx, config), config, prep, reachable)
        // The need-shaped fills run through the shared fillRemaining body (write-once, each fill's
        // failure degraded to an absent slot). Any residual Abort is already discharged per fill, so
        // the fiber body stays < Async.
        analysis.andThen(fillRemaining(ctx, config, prep, need, infos).handle(Abort.run[AIGenException]).unit)
    end preparationRun

    // Forks the single-flight run from the snapshot via Fiber.initUnscoped (no Scope cascade into
    // the seam row) and stores the handle in the per-session cell. LLM-free (infos already
    // captured). The cell holds at most one handle, so arming many times over one session cannot
    // accumulate them, and release interrupts whatever is live at teardown.
    def forkPreparation(
        ctx: Context,
        config: Config,
        prep: Model.Preparation,
        infos: Chunk[Tool.internal.Info[?, ?, LLM]]
    )(using Frame): Unit < Async =
        Fiber.initUnscoped(preparationRun(ctx, config, prep, infos)).map { fiber =>
            prep.inFlight.set(Present(fiber))
        }
end Preparation
