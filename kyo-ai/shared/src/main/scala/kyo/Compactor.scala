package kyo

import kyo.Schema
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*
import kyo.ai.completion.Completion
import scala.annotation.tailrec

/** Automatic context compaction as a swappable `AI.Enablement`: keeps what the model SEES small and
  * high-signal while `Context.raw` stays the transcript of record.
  *
  * `Compactor` is a pure trait carrying the enablement family's `[-S]` capability parameter (like
  * `Tool`/`Thought`/`Mode`). Enable the default with `ai.enable(Compactor.init)`; implement the trait for a
  * custom policy that needs an extra capability on `S`. With none enabled a generation is byte-identical to
  * the default-off path.
  *
  * The boundary is one decision method plus one opaque state cell. `LLM.eval`/`streamAgainst` consult
  * [[compact]] on every turn and hold NO trigger of their own, so when a boundary fires is the strategy's
  * choice, not the framework's. Answering [[Compactor.Decision.Unchanged]] re-serves the previous context
  * BY REFERENCE: a compactor reporting `Unchanged` has no channel through which to return modified bytes,
  * so that much is structural rather than checked.
  *
  * The property that keeps the provider's prompt cache alive is therefore stated as **the served PREFIX is
  * stable and any change is an append**, rather than as byte-identity between boundaries. Caching is
  * positional, so an append is not an invalidation. Byte-identity was the stronger claim and is not one a
  * compactor can be held to: the framework has no trigger, so it cannot tell a below-boundary pass from a
  * boundary, and both shipped compactors legitimately answer `Compacted` below one. The off switch
  * restores `raw` after a caller switches compactors mid-session, and the default appends an
  * automatic-recall expansion at the tail. A compactor that needs the append-only property enforced
  * enforces it where its own trigger is known.
  *
  * `Decision.Compacted` hands back a whole `Context`, so `raw` is protected by a CHECK rather than by the
  * signature: the framework admits the result only if `raw` is preserved, meaning the same length with each
  * entry either identical or replaced by an origin-carrying marker, and aborts the turn otherwise. Within
  * the default compactor, retention eviction is the only step that writes `raw` at all. Tool dispatch
  * reconciles the transient "Processing tool call" placeholder in the tail of BOTH lists symmetrically; no
  * other path rewrites `raw`.
  *
  * Region bookkeeping is derived from `(raw, compacted)`, never stored. Everything a caller sets, triggers
  * and retention policy included, is on [[Compactor.Tuning]]; the measured constants behind the ranking are
  * internal to the module.
  */
trait Compactor[-S] extends AI.Enablement[S]:

    /** Decides this turn: either nothing changes, or here is the new context.
      *
      * Called on every generation, so the compactor owns the trigger as well as the rewrite. `state` is
      * the per-session handle for work that must outlive one call but must not be persisted (staged
      * summaries, an in-flight preparation fiber, a pending usage anchor); it is created and owned by the
      * framework and handed back on every call, so a compactor writes through it rather than returning it.
      *
      * Returning [[Compactor.Decision.Unchanged]] preserves the provider's prompt cache STRUCTURALLY rather
      * than by anyone checking: a compactor that took no boundary has no way to return a modified view, so
      * the framework re-serves the exact context it passed in. Returning [[Compactor.Decision.Compacted]]
      * hands back the rewritten context, and the framework verifies the one thing a returned `Context`
      * could get wrong, that `raw` was preserved.
      *
      * A `Compacted` answer BELOW a boundary is legal and both shipped compactors give one. What such an
      * answer owes the cache is that the served list it hands back EXTENDS the one it was given: an append
      * costs nothing, a rewritten prefix invalidates from the edit onward. The framework does not check
      * this, because it holds no trigger and so cannot tell the two situations apart; a compactor that
      * appends checks it where its own trigger is known.
      *
      * On `raw`: every entry of the returned `raw` must be IDENTICAL to the entry at that index, or a
      * marker carrying an `origin` standing for what it replaced, and the length may not change. That is
      * the eviction backstop's shape and nothing else's. A compactor that rewrites history fails the turn
      * through `Abort[AIGenException]` rather than corrupting the transcript.
      */
    /** This compactor's ephemeral per-session state. `Unit` for a compactor that needs none.
      *
      * A type MEMBER rather than one shared type, so a custom compactor keeps whatever it needs and the
      * framework keeps none of it. The framework never reads this: it creates one per session through
      * [[initState]], hands the same value back on every [[compact]], and passes it to [[release]] at
      * teardown.
      */
    type State

    /** Creates this compactor's per-session state. Called once per session, on first use. */
    def initState(using Frame): State < Sync

    def compact(ctx: Context, state: State)(using
        Frame
    ): Compactor.Decision < (LLM & Async & Abort[AIGenException] & S)

    /** The per-instance tools this compactor contributes (the default: a `recall` tool bound to
      * `ai`). A compactor contributing none inherits the empty default.
      */
    def tools(ai: AI)(using Frame): Chunk[Tool[LLM]] = Chunk.empty

    /** Releases whatever the session's [[Compactor.State]] holds: interrupts an in-flight preparation
      * fiber, and lets go of whatever it staged. Called once per session teardown.
      *
      * Called AT LEAST once, not exactly once, and THREE things produce a call, which is why idempotence is
      * required rather than encouraged:
      *
      *   - a session discarded explicitly is released at the discard;
      *   - a session whose `AI` has been COLLECTED is released at the next instance mint, which is the
      *     first point that can observe the slot is dead. This is the ordinary way an instance ends,
      *     since nothing obliges a caller to discard one;
      *   - any run exit, normal or not, sweeps whatever is still seated.
      *
      * The three overlap freely: a discarded session may also be swept, and a collected one may be
      * released at a mint and swept again at exit.
      *
      * The `Unit < Sync` row is load-bearing in two ways: at sweep time the LLM handler is gone, so release
      * cannot ask for one, and `Sync.ensure` accepts only a Sync finalizer, so a release that could await is
      * a release that could hang teardown.
      *
      * A compactor INSTANCE is shared across sessions and runs (`Compactor.init` returns a singleton), so
      * every per-session mutation belongs in [[State]] and an implementation must otherwise be stateless.
      */
    def release(state: State)(using Frame): Unit < Sync = Kyo.unit

    // The sanctioned erased-carrier discharge every enablement kind uses (Mode.scala
    // this.asInstanceOf[Mode[Any]]); Compactor is contravariant in S, so widening to the
    // Compactor[Any] env slot needs this one cast, matching the sibling kinds exactly.
    final private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        env.compactor(this.asInstanceOf[Compactor[Any]])
    final private[kyo] def enableIn(session: AISession)(using Frame): AISession =
        session.copy(env = enableIn(session.env))
end Compactor

object Compactor:

    import internal.*

    /** The default compactor at the shipped policy.
      *
      * Watermarks, the raw cap, the summarizer entry, which mechanisms run, how much liveness earns a
      * verbatim pin, and what a summary fill may spend all come from [[Tuning]]; pass one to the
      * [[init(tuning:Tuning)*]] overload to change them. The seam consults none of it: it hands the
      * context to [[compact]] and installs what comes back.
      */
    def init: Compactor[Any] = Default

    /** The default compactor at a modified [[Tuning]]. `init(Tuning())` is exactly [[init]].
      *
      * Validates the fraction ordering here, which is the only place it can be enforced for a caller who
      * built the record directly: the per-field builders keep `prepare` above `target` as they set it, but
      * the case class constructor and `copy` accept any pair. An inverted pair would otherwise be silently
      * corrected when the axis is projected, so a typo in a fraction became a policy the caller never
      * chose. This is the single public entry that takes a `Tuning`, so one check covers that path.
      */
    def init(tuning: Tuning): Compactor[Any] = Default(tuning.validated, internal.Calibration())

    /** The pass-through off switch: always serves the raw context unchanged, so the session
      * runs with no compaction and no overflow protection and the caller owns the context bound.
      */
    def none: Compactor[Any] = Disabled

    /** What one `compact` call decided.
      *
      * Two cases and no third, because the absence of a third is the guarantee: a compactor that reports
      * `Unchanged` cannot also hand back a modified view, so re-serving by reference holds by construction
      * rather than by a check the framework has to get right. What that buys is the cache property, and the
      * cache property survives an append, which is why a below-boundary `Compacted` is legal.
      *
      * There is deliberately no `StateOnly` case for a pass that wrote only ephemeral state. Every
      * persisted write in the default compactor happens at a boundary; below one it arms a background
      * fiber and writes nothing to the `Context`, and that arming travels through the interior-mutable
      * [[State]] rather than through this return.
      */
    enum Decision derives CanEqual:
        /** Nothing to do: the framework re-serves the context it passed in, byte for byte. */
        case Unchanged

        /** The rewritten context, which the framework installs after checking `raw` was preserved. */
        case Compacted(ctx: Context)
    end Decision

    /** A compactor paired with the state IT created, which is the only way the framework can hold both
      * without knowing either.
      *
      * The pairing is the point. `state`'s type is path-dependent on `compactor`, so the two cannot come
      * apart and no cast is needed to put them back together. Storing the state alone would also make
      * [[Compactor.release]] undispatchable at teardown, where the session is in hand but the compactor
      * that seated its state is not.
      */
    sealed abstract private[kyo] class Seated:
        val compactor: Compactor[Any]
        val state: compactor.State
        def release(using Frame): Unit < Sync = compactor.release(state)
    end Seated

    private[kyo] object Seated:
        def init(c: Compactor[Any])(using Frame): Seated < Sync =
            c.initState.map { s =>
                new Seated:
                    val compactor: c.type = c
                    val state             = s
            }
    end Seated

    private object Disabled extends Compactor[Any]:
        type State = Unit
        def initState(using Frame): Unit < Sync = Kyo.unit

        // Serves raw, which is not the same as serving whatever is already there: a session that ran with
        // a real compactor and then switched to the off switch carries a demoted view, and re-serving that
        // would be compaction the caller has just turned off. So it reports Unchanged only when the view
        // IS raw, which is the common case and keeps the prefix byte-stable, and restores raw otherwise.
        def compact(ctx: Context, state: Unit)(using Frame): Decision < (LLM & Async & Abort[AIGenException]) =
            if ctx.compacted == ctx.raw then Kyo.lift(Decision.Unchanged)
            else Kyo.lift(Decision.Compacted(ctx.copy(compacted = ctx.raw)))
    end Disabled

    /** One mechanism of the default compactor, each independently disableable.
      *
      * Compaction is not one algorithm but nine cooperating mechanisms, and a caller has a legitimate
      * reason to switch any of them off: to stop paying for a model call, to remove a source of ranking
      * signal they do not trust, or to measure what a mechanism is actually worth. Membership in
      * [[Tuning.mechanisms]] is the whole of the surface; there is no per-mechanism knob to find.
      *
      * Each member's absence is scoped to that mechanism's own work, never to "no calls and no fibers"
      * globally: a fired boundary legitimately forks a fiber for whatever remains enabled, so a blanket
      * reading would call correct behaviour a violation.
      *
      *   - `Analysis`: the per-region relation pass. Off, no analysis call is ever issued and no relation
      *     is produced, so the graph degrades to structural edges alone.
      *   - `SummaryFills`: the per-span summary generation. Off, no fill call is issued and a demoted span
      *     renders the fixed-size substitute elision instead of summary bytes.
      *   - `Preparation`: speculative work below the boundary. Off, nothing arms in advance; the boundary
      *     does its own work when it fires.
      *   - `KeyedSupersession`: supersession from a tool's own [[Tool.Supersession]] declaration.
      *   - `AnalyzedSupersession`: supersession from the analysis pass's `Supersedes` relations.
      *   - `Repoint`: routing a reference through supersession so liveness accrues to current content.
      *   - `ContentReferences`: the plain-word second reference tier. Off, its admission scan does not run
      *     at all, rather than running and having its edges silently discounted to nothing.
      *   - `Recall`: the `recall` tool and its decaying liveness seed. Off, no recall tool is registered.
      *   - `Expansion`: automatic recall. The framework detects from the newest user turn that
      *     not-fully-presented content is relevant and re-presents it at the tail, moving it up the detail
      *     ladder from material already in hand, so it makes NO model calls. Off, no index is retained,
      *     nothing is detected or delivered, and an already-recorded expansion contributes no seed, the
      *     same way `Recall` treats a leftover record. Deliberately its OWN entry rather than riding
      *     `Recall`: the two are producers of need evidence with different confidence, and a deployment
      *     that removes the tool must not thereby silence the framework's own path.
      */
    enum Mechanism derives CanEqual:
        case Analysis, SummaryFills, Preparation, KeyedSupersession, AnalyzedSupersession, Repoint, ContentReferences, Recall,
            Expansion

    object Mechanism:
        /** Every mechanism, which is the shipped default. */
        val all: Set[Mechanism] = Mechanism.values.toSet

        /** No mechanism: structural ranking and presentation only, and not one model call. */
        val none: Set[Mechanism] = Set.empty
    end Mechanism

    /** The default compactor's POLICY: what a caller decides.
      *
      * Every field defaults to the shipped value, so `Tuning()` is behavior-identical to [[init]]. What is
      * here is what a caller has a reason to set: which mechanisms run, how much liveness earns a verbatim
      * pin, and what one summary fill may spend. The calibrated values behind the ranking itself (edge
      * weights, seed shares, admission gates, byte budgets) are NOT here: they are measured constants of
      * this particular implementation, not choices, and the escape hatch for different mechanics is
      * implementing [[Compactor]] rather than turning a further knob.
      *
      * @param mechanisms
      *   which mechanisms run; see [[Mechanism]]
      * @param keepShare
      *   the liveness a span's hottest member must hold to stay VERBATIM, as a multiple of the NOMINAL
      *   uniform share `1/N`. Liveness is Personalized PageRank over a seed vector summing to 1.0, so a
      *   score is a share of one unit of mass spread over N regions. Expressed as a share rather than an
      *   absolute score because an absolute floor is arithmetically certain to exceed every typical score
      *   once N passes roughly its reciprocal, which closed the pin channel entirely on any long session.
      *   (Nominal, not realized: `score` drops the dangling mass of a region with no out-edges each
      *   iteration and the supersession penalty shrinks totals further, so realized scores sum to slightly
      *   under 1.0. The measured window below absorbs that.)
      *
      *   The floor does NOT scale with the budget, which is what makes one constant work everywhere: the
      *   pin answers "is this content live", a property of the content, while the budget is pass 2's job
      *   and pass 2 already descends levels until the view fits.
      *
      *   The shipped value was measured, not chosen, against two obligations that pull in opposite
      *   directions: a heavily referenced mid-history region must stay pinned (which wants a LOW share) and
      *   compaction must still shed most of the transcript (which wants a HIGH one). On production-shaped
      *   transcripts the referenced region pins only at or below 1.48 and shedding collapses at or below
      *   1.21, so the viable window is `(1.21, 1.48]` and 1.35 sits in the middle with about ten percent of
      *   margin either way. `CompactorTest` holds both obligations with the measured numbers, so a change
      *   to this value that closes the pin channel or stops the shedding fails there rather than in a
      *   session.
      * @param summaryOutputCap
      *   the token ceiling on one summary fill's output, which bounds both the summary's size and its miss
      *   price in the demotion arithmetic, so a demoted summary can never inflate the served view
      * @param trigger
      *   where a boundary fires, as a fraction of the model's context window. This is the only trigger:
      *   the below-boundary relevance tripwire was deleted, so a boundary fires on occupancy alone.
      *
      *   COMPACTION WILL FIRE ON SESSIONS THAT WOULD HAVE FIT UNAIDED, and that is deliberate policy
      *   rather than an oversight, because the alternative is worse. The two obligations are two-sided and
      *   have no common solution: a trigger low enough to keep the largest sessions inside the hard limit
      *   necessarily also fires on sessions that never needed it. Swept on production-shaped traces
      *   (`CompactorCostReplayTest`, "E3"), the highest fraction that still holds the worst oversized trace
      *   is 0.70, and it fires on two of three in-window traces; 0.80 already overflows (a peak view of
      *   11300 against a hard limit of 11059); and no fraction up to 0.95 reaches zero false firing. The
      *   sweep is asserted to find NO candidate, so this limitation cannot silently change.
      *
      *   The shipped 0.5 buys the most headroom on the side where failure is unrecoverable. Firing early
      *   costs summarization work and one head-of-prompt cache invalidation; failing to fire risks an
      *   overflow, which fails the turn outright. The design fails safe. Occupancy is in any case an
      *   ESTIMATE until the provider reports usage, so no trigger could know a session was going to fit.
      * @param contextCeiling
      *   an absolute token clamp on the trigger, applied after the fraction is projected: the boundary
      *   fires at `min(trigger * window, contextCeiling)`. Present because a fraction of a very large
      *   window is a worse policy than it looks, attention degrading well before the window ends. `Absent`
      *   makes the trigger a pure fraction.
      * @param target
      *   the render-down depth: how far a boundary compacts, as a fraction of the effective trigger. Lower
      *   means fewer boundaries (more headroom is recovered per boundary) at the price of a smaller served
      *   view between them.
      * @param prepare
      *   where speculative summary fills arm, as a fraction of the effective trigger, so summaries for the
      *   spans a boundary is about to demote are already staged when it fires. Must exceed `target` so a
      *   boundary's render-down always disarms speculation; `1.0` turns speculation off.
      * @param hardLimit
      *   the overflow backstop, as a fraction of the window LESS the output reservation. A projected view
      *   above it is refused rather than sent, which is what turns a provider-side overflow into a
      *   local, attributable failure.
      * @param summarizer
      *   the config summary fills run on. `Absent` takes the warm route (the session's own provider, at
      *   its small model); `Present` pins fills to one config, which is what a caller wants when fills
      *   should not touch the main model's quota or when a cheaper provider is preferred for them.
      * @param rawRetentionCap
      *   the ceiling on retained RAW history, the backstop against unbounded memory growth in a long
      *   session. Beyond it the oldest raw content is irreversibly evicted, leaving coarse markers.
      *   `Absent` is several window-widths, which no ordinary session reaches.
      */
    final case class Tuning(
        mechanisms: Set[Mechanism] = Mechanism.all,
        keepShare: Double = 1.35,
        summaryOutputCap: Int = 512,
        // --- the occupancy axis: fractions, projected onto the model's window where it is known ---
        trigger: Double = 0.5,
        contextCeiling: Maybe[Int] = Present(128_000),
        target: Double = 0.6,
        prepare: Double = 0.8,
        hardLimit: Double = 0.9,
        summarizer: Maybe[Config] = Absent,
        rawRetentionCap: Maybe[Int] = Absent
    ):
        // Per-field clamps, so a value out of range is corrected at construction rather than at a boundary.
        def trigger(f: Double): Tuning     = copy(trigger = f.max(0.0).min(1.0))
        def contextCeiling(t: Int): Tuning = copy(contextCeiling = Present(t.max(1)))
        def noContextCeiling: Tuning       = copy(contextCeiling = Absent)
        def target(f: Double): Tuning      = copy(target = f.max(0.0).min(1.0))
        // Above `target` so a boundary's render-down always disarms speculation, and 1.0 turns it off.
        def prepare(f: Double): Tuning      = copy(prepare = f.max(math.nextUp(target)).min(1.0))
        def hardLimit(f: Double): Tuning    = copy(hardLimit = f.max(math.nextUp(0.0)).min(1.0))
        def summarizer(c: Config): Tuning   = copy(summarizer = Present(c))
        def rawRetentionCap(t: Int): Tuning = copy(rawRetentionCap = Present(t.max(1)))

        /** The FRACTION ordering, checked here because it is the part that holds without a model.
          *
          * `target < prepare <= 1` is a property of the numbers alone. The rest of the axis is a
          * projection onto a window this record does not know, so it is checked where it is computed
          * (see `internal.axis`): a window is a per-model fact and one Tuning can serve many models.
          */
        private[kyo] def validated: Tuning =
            require(target < prepare, s"compaction axis: target ($target) must be < prepare ($prepare)")
            require(prepare <= 1.0, s"compaction axis: prepare ($prepare) must be <= 1.0")
            require(hardLimit > 0.0, s"compaction axis: hardLimit ($hardLimit) must be > 0")
            this
        end validated
    end Tuning

    private[kyo] object internal:

        // The data model and token accounting live in kyo.ai.compactor.Model so neither file carries the
        // whole subsystem. Re-exported wholesale, so every Compactor.internal.<name> reference,
        // here and in tests, resolves unchanged.
        export kyo.ai.compactor.Model.*

        object Default extends Default(Tuning(), Calibration())

        class Default(val tuning: Tuning, val calibration: Calibration)
            extends Compactor[Any],
              kyo.ai.compactor.Content,
              kyo.ai.compactor.Ranking,
              kyo.ai.compactor.Retention,
              kyo.ai.compactor.Projection,
              kyo.ai.compactor.Preparation,
              kyo.ai.compactor.Fills,
              kyo.ai.compactor.Expansion:

            import calibration.*
            import tuning.*

            type State = Preparation
            def initState(using Frame): Preparation < Sync = Preparation.init

            // This instance's axis projected onto the model in hand. The seam no longer knows any of these
            // numbers: it asks the compactor to compact, and the compactor decides where its own lines are.
            def ax(config: Config): Axis = axis(tuning, config)

            /** The whole decision for one turn: below this instance's trigger nothing changes, at or above
              * it a boundary prepares, renders, installs and evicts.
              *
              * The trigger lives HERE now. The seam used to compare occupancy against a watermark it read
              * off the config before any compactor was involved, which meant the framework owned a policy
              * decision belonging to the strategy, and a custom compactor could not change when it ran.
              */
            def compact(ctx: Context, prep: Preparation)(using Frame): Decision < (LLM & Async & Abort[AIGenException]) =
                AI.config.map { config =>
                    if occupancy(ctx) < ax(config).high then
                        // Below the trigger: arm the background fiber for whatever band this pass sits in,
                        // then give automatic recall its one chance to act. With nothing to expand this
                        // reports Unchanged, which re-serves the view byte for byte and keeps the
                        // provider's prefix cache alive; with something to expand it APPENDS, which is not
                        // an invalidation either.
                        armBelowBoundary(ctx, config, prep).andThen(expandForTurn(ctx, config, prep))
                    else
                        // The boundary: tick the recall-decay clock, drop expansion records past their
                        // horizon (after the tick, so ages count against the new counter), adopt what
                        // preparation staged, render, install, then bound raw's heap. Eviction is the
                        // only step that touches raw, and automatic recall's index is rebuilt AFTER it,
                        // because eviction here can forget regions this same cut has just demoted.
                        // The interval's usage verdicts are read HERE, before the tick: it is the first
                        // point where every fire of the closing interval has a complete reply window
                        // behind it, and the last point the index describing those regions still exists.
                        logExpansionUsage(ctx, prep).andThen {
                            val ticked =
                                ctx.withCompaction(ctx.compactionState.tickBoundary.pruneExpansions(expansionPruneHorizon))
                            boundaryPrepare(ticked, config, prep).map { (prepared, facts) =>
                                renderView(prepared, config, facts).map { (rebuilt, levels) =>
                                    val evicted = evict(prepared.copy(compacted = rebuilt), config)
                                    // The fire runs AFTER the render, against the fresh index and the
                                    // post-render occupancy. Without this clause the mechanism is blind on
                                    // exactly the turns where the most content was just demoted: the dispatch
                                    // is a two-way fork, so a turn at or above the trigger never reaches the
                                    // below-trigger arm, and by this design's own argument the boundary's
                                    // ranking will not catch the lexical match either.
                                    retainExpansionIndex(evicted, facts, levels, prep).andThen {
                                        // `rewritten = true`: the render just replaced the served list, so the
                                        // usage anchor still describes the PRE-boundary view and reading
                                        // occupancy through it would report the trigger with no headroom left.
                                        expandForTurn(evicted, config, prep, rewritten = true).map {
                                            case Decision.Unchanged           => Decision.Compacted(evicted)
                                            case expanded: Decision.Compacted => expanded
                                        }
                                    }
                                }
                            }
                        }
                    end if
                }
            end compact

            // The convenience form, working the transcript out fresh. A fired boundary uses the
            // canonical form below with the facts its preparation half already computed.
            def renderView(ctx: Context, config: Config)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
                Tool.internal.infos.map(infos => renderView(ctx, config, boundaryFacts(ctx, config, infos)).map(_._1))

            // Hands the level ASSIGNMENT out beside the view, because automatic recall's index is a
            // filter of that assignment and deriving it a second time would be a second cut: the
            // expensive step, and one that must agree with the served view exactly rather than
            // approximately. The convenience overload above keeps its signature by discarding it.
            def renderView(ctx: Context, config: Config, facts: BoundaryFacts)(using
                Frame
            ): (Chunk[Message], Dict[Int, Level]) < (LLM & Async & Abort[AIGenException]) =
                Kyo.lift(config).map { config =>
                    val a          = ax(config)
                    val low        = a.low
                    val hard       = a.hard
                    val state      = ctx.compactionState
                    val analyses   = state.analyses
                    val units      = facts.units
                    val spans      = facts.spans
                    val keys       = facts.keys
                    val since      = facts.since
                    val prevLevels = facts.prevLevels
                    // Re-run, every time, and not candidates for threading: supersession merges in the
                    // analyses adopted since the facts were taken, structural edges repoint through it,
                    // and the cut and the projection legitimately see the newly adopted summaries.
                    val superseded = mergeSupersession(supersession(units, keys), analyzedSupersession(analyses))
                    val graph      = deriveGraph(facts.index, superseded, analyzedEdges(analyses))
                    val scores     = score(units, graph, superseded, facts.seed)
                    val demotions  = cut(ctx, units, spans, scores, facts.occupied, low, since, prevLevels)
                    val view       = project(ctx.raw, units, spans, demotions, since, prevLevels, state, keys)
                    if viewTokens(view) <= hard then (view, demotions)
                    else
                        // The forced path overrides pinning and points EVERY span, so the assignment it
                        // served is not `demotions`; handing the cut's assignment out beside a forced
                        // view would describe a view that was never sent.
                        val (forcedView, forcedLevels) =
                            forcedWithLevels(ctx.raw, units, spans, scores, hard, since, prevLevels, keys)
                        if viewTokens(forcedView) > hard then
                            Abort.fail(AIContextOverflowException(viewTokens(forcedView), hard))
                        else (forcedView, forcedLevels)
                    end if
                }
            end renderView

            override def release(state: Preparation)(using Frame): Unit < Sync = releasePreparation(state)

            override def tools(ai: AI)(using Frame): Chunk[Tool[LLM]] =
                if mechanisms.contains(Mechanism.Recall) then Chunk(recallTool(ai)) else Chunk.empty

            // ---- grouping (fuse assistant + answering tool messages into REGIONS over raw) ----
            // Region.tokens reads the stored apportioned stamp (stampedTokens), so tail-window selection,
            // the cut, and occupancy all account on the same per-message sizes; an unstamped message falls
            // back to the offline estimate.
            def group(raw: Chunk[Message]): Chunk[Region] =
                val (byId, _) =
                    raw.zipWithIndex.foldLeft((Map.empty[Int, Building], Dict.empty[String, Int])) {
                        case ((units, owner), (msg, i)) =>
                            msg match
                                case AssistantMessage(_, calls, _, _) if calls.nonEmpty =>
                                    val callIds = calls.foldLeft(Set.empty[String])((s, c) => s + c.id.id)
                                    val owner2  = calls.foldLeft(owner)((o, c) => o.update(c.id.id, i))
                                    (units.updated(i, Building(i, Chunk(i), callIds)), owner2)
                                case ToolMessage(callId, _, _, _) =>
                                    owner.get(callId.id) match
                                        case Present(uid) =>
                                            val b  = units(uid)
                                            val b2 = b.copy(indices = b.indices.append(i), open = b.open - callId.id)
                                            (units.updated(uid, b2), owner)
                                        case Absent =>
                                            (units.updated(i, Building(i, Chunk(i), Set.empty)), owner)
                                case _ =>
                                    (units.updated(i, Building(i, Chunk(i), Set.empty)), owner)
                    }
                val fused = Chunk.from(byId.values.toList.sortBy(_.id).map { b =>
                    val toks = b.indices.foldLeft(0)((n, idx) => n + stampedTokens(raw(idx)))
                    Region(b.id, b.indices, b.open.nonEmpty, toks)
                })
                foldForgottenBands(fused, raw)
            end group

            // A forgotten retention band: eviction replaces a forgotten region run's raw messages IN
            // PLACE with a coarse head at the run's first ordinal plus content-freed tombstones, ALL
            // carrying the band's Origin. In raw a Present(origin) is unique to a band member (a live raw
            // message always carries Absent), so consecutive members sharing an origin.start fuse into ONE
            // region keyed by that start ordinal; survivors keep their ordinals (no renumber).
            def foldForgottenBands(regions: Chunk[Region], raw: Chunk[Message]): Chunk[Region] =
                def bandStart(region: Region): Maybe[Int] =
                    region.indices.headMaybe.flatMap(i => if i >= 0 && i < raw.size then raw(i).origin.map(_.start) else Absent)
                @tailrec def loop(rem: List[Region], acc: List[Region]): List[Region] =
                    rem match
                        case Nil => acc.reverse
                        case u :: _ =>
                            bandStart(u) match
                                case Present(bs) =>
                                    val run  = rem.takeWhile(s => bandStart(s).exists(_ == bs))
                                    val idxs = Chunk.from(run.flatMap(_.indices).sorted)
                                    val toks = run.foldLeft(0)((n, s) => n + s.tokens)
                                    loop(rem.drop(run.size), Region(bs, idxs, false, toks) :: acc)
                                case Absent =>
                                    loop(rem.tail, u :: acc)
                Chunk.from(loop(regions.toList.sortBy(_.id), Nil))
            end foldForgottenBands

            // ---- span formation: partition the CLOSED prefix into spans by a
            // deterministic model-free rule. Splits at user-turn boundaries; closes a pending run early
            // when it would exceed the formation cap (spanCapTokens tokens or spanCapRegions regions); a
            // single over-cap region forms an oversized singleton span. The tail band is EXCLUDED:
            // only regions aged into the closed prefix are eligible.
            def formSpans(units: Chunk[Region], raw: Chunk[Message], config: Config): Chunk[Span] =
                val ordered = units.toList.sortBy(_.id)
                // A forgotten retention band is a terminal coarse marker, excluded from span formation
                // like the tail band, so it is never re-demoted or re-summarized.
                val closed = closedRegions(ordered, config).filterNot(u => u.indices.headOption.exists(i => raw(i).origin.isDefined))
                def flush(members: List[Region], rest: List[Span]): List[Span] =
                    members match
                        case Nil => rest
                        case _   => spanOf(members) :: rest
                @tailrec def build(rem: List[Region], run: List[Region], runToks: Int, acc: List[Span]): List[Span] =
                    rem match
                        case Nil => flush(run.reverse, acc).reverse
                        case u :: rest =>
                            val startsTurn = run.nonEmpty && hasUser(u, raw)
                            val overCap    = run.nonEmpty && (run.size >= spanCapRegions || runToks + u.tokens > spanCapTokens)
                            if startsTurn || overCap then build(rest, u :: Nil, u.tokens, flush(run.reverse, acc))
                            else build(rest, u :: run, runToks + u.tokens, acc)
                Chunk.from(build(closed, Nil, 0, Nil))
            end formSpans

            // The closed prefix: regions outside the tail band
            // (tailBandFraction * effectiveLow tokens back from the newest region) whose turn is complete
            // (no region ages out while its own tool calls are unresolved).
            def closedRegions(ordered: List[Region], config: Config): List[Region] =
                val bandTokens = (tailBandFraction * ax(config).low).toInt
                @tailrec def tailCount(rem: List[Region], toks: Int, count: Int): Int =
                    rem match
                        case Nil => count
                        case u :: rest =>
                            if toks + u.tokens > bandTokens && count > 0 then count
                            else tailCount(rest, toks + u.tokens, count + 1)
                val closed = ordered.dropRight(tailCount(ordered.reverse, 0, 0))
                closed.reverse.dropWhile(_.unresolved).reverse
            end closedRegions

            def spanOf(members: List[Region]): Span =
                val ids = Chunk.from(members.map(_.id))
                Span(members.map(_.id).min, members.flatMap(_.indices).max + 1, ids)

            // =========================================================================================
            // preparation machinery: the seam-facing arm (below the boundary) and boundaryPrepare
            // (adopt + join at the boundary), the single-flight fiber, the fill routes, and the gate.
            // Everything here is model-free EXCEPT runFill (a degraded typed completion) and the join.
            // =========================================================================================

            // Materializes the per-session Preparation cell on first use.
            // ADOPT: move every staged summary AND analysis into write-once compaction
            // state; state wins, duplicates discarded, so a fiber/boundary race is idempotent by
            // construction. Analyses freeze by ordinal exactly like summaries freeze by span range.
            def spanContent(sp: Span, raw: Chunk[Message]): String =
                (sp.start until sp.end).toList.filter(i => i >= 0 && i < raw.size).map(i => raw(i).content).mkString(" ")

            // ---- recall: typed decode, instance-bound, ROLE-TAGGED, span/region grain via origin.
            // Records a decaying liveness seed so the recalled region reinstates through scoring, the
            // record living in state (never inferred from the view). Resolves against ONLY the calling
            // instance's own transcript (raw).
            def recallTool(ai: AI)(using Frame): Tool[LLM] =
                Tool.init[Recall](
                    name = "recall",
                    description =
                        "Recall the full original content of a compacted region or span by the id in its marker. " +
                            "Returns the covered messages verbatim, each prefixed with its role, as a fresh tool result."
                ) { (arg: Recall) =>
                    ai.context.map { ctx =>
                        val range = ctx.compacted.filter(_.origin.exists(_.start == arg.id)).headMaybe.flatMap(_.origin)
                        val restored = if residentVerbatim(ctx, arg.id, range) then residentNote(arg.id)
                        else
                            range match
                                case Present(o) =>
                                    // A forgotten retention band fails cleanly: raw's origin at o.start is
                                    // Present only for a band member, so a recall of forgotten content is
                                    // refused rather than returning the coarse band head or an empty tombstone.
                                    if o.start >= 0 && o.start < ctx.raw.size && ctx.raw(o.start).origin.isDefined then
                                        s"region ${arg.id} was forgotten past the retention horizon and is no longer recallable"
                                    else roleTagged(ctx.raw.drop(o.start).take(o.end - o.start))
                                case Absent =>
                                    group(ctx.raw).filter(_.id == arg.id).headMaybe match
                                        // A band the view no longer advertises is still reachable by an id the
                                        // model remembers, and `group` folds a band into one region, so without
                                        // this check the restore returns the coarse head plus empty tombstones
                                        // as a SUCCESS: a garbage restoration, strictly worse than refusing.
                                        case Present(u) if u.indices.headOption.exists(i => ctx.raw(i).origin.isDefined) =>
                                            s"region ${arg.id} was forgotten past the retention horizon and is no longer recallable"
                                        case Present(u) => roleTagged(u.indices.map(ctx.raw.apply))
                                        case Absent     => s"no such recallable region: ${arg.id}"
                        ai.setContext(ctx.withCompaction(ctx.compactionState.withRecall(arg.id))).andThen(restored)
                    }
                }
            end recallTool

            // Role-tagged, byte-exact restoration: each covered message prefixed with its role, never
            // a role-flattened join.
        end Default
    end internal
end Compactor
