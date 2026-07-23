package kyo

import kyo.Schema
import kyo.ai.*
import kyo.ai.Context.*
import kyo.ai.completion.Completion
import scala.annotation.tailrec

/** Automatic context compaction as a swappable `AI.Enablement`: keeps what the model SEES small
  * and high-signal while `Context.raw` stays the complete transcript that compaction never rewrites
  * (render rebuilds only `compacted`; a Compactor never touches `raw`). Tool dispatch reconciles the
  * transient "Processing tool call" placeholder in the tail of BOTH lists symmetrically; no other path
  * rewrites `raw`.
  *
  * `Compactor` is a pure trait carrying the enablement family's `[-S]` capability parameter (like
  * `Tool`/`Thought`/`Mode`). Enable the default with `ai.enable(Compactor.init)`; implement the
  * trait for a custom policy that needs an extra capability on `S`. With none enabled a generation
  * is byte-identical to the default-off path. When enabled, `LLM.eval`/`streamAgainst` re-serve
  * `ctx.compacted` unchanged below the occupancy trigger and consult `render` only at a boundary,
  * so the emitted view is byte-identical between updates for ANY implementation (the provider
  * prompt cache survives). `render` returns only the rebuilt `compacted` list: `raw` never appears
  * in its signature, so a custom compactor cannot dead-edit it; the framework installs the result
  * via `ctx.copy(compacted = rendered)`. Region bookkeeping is derived from `(raw, compacted)`,
  * never stored. Tuning is via `kyo.ai.Config`'s compaction fields.
  */
trait Compactor[-S] extends AI.Enablement[S]:

    /** Rebuilds and returns the new `compacted` list from `ctx` (which carries the current `raw`
      * and `compacted`); never touches `raw`. The seam consults this only at an update boundary and
      * installs the result via `ctx.copy(compacted = rendered)`, so `raw` immutability is
      * structural. The default is pure and model-free; the `LLM & Async` breadth is the extension
      * ceiling for a richer user policy, and `S` carries any extra capability that policy needs.
      */
    def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException] & S)

    /** The per-instance tools this compactor contributes (the default: a `recall` tool bound to
      * `ai`). A compactor contributing none inherits the empty default.
      */
    def tools(ai: AI)(using Frame): Chunk[Tool[LLM]] = Chunk.empty

    // Discriminates the built-in Default strategy from Compactor.none and any user-supplied
    // compactor. The seam runs Default's occupancy trigger, background preparation, drift, and
    // eviction machinery only for the Default instance; every other compactor owns its own view
    // through `render` alone, so the off switch forks no fiber and issues no model call.
    private[kyo] def isDefault: Boolean = false

    // The sanctioned erased-carrier discharge every enablement kind uses (Mode.scala:24-26
    // this.asInstanceOf[Mode[Any]]); Compactor is contravariant in S, so widening to the
    // Compactor[Any] env slot needs this one cast, matching the sibling kinds exactly.
    final private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        env.compactor(this.asInstanceOf[Compactor[Any]])
    final private[kyo] def enableIn(session: AISession)(using Frame): AISession =
        session.copy(env = enableIn(session.env))
end Compactor

object Compactor:

    import internal.*

    /** The stateless default compactor. Tuning is via `kyo.ai.Config`'s compaction knobs
      * (`Config.Compaction`).
      */
    def init: Compactor[Any] = Default

    /** The pass-through off switch: always serves the raw context unchanged, so the session
      * runs with no compaction and no overflow protection and the caller owns the context bound.
      */
    def none: Compactor[Any] = Disabled

    private object Disabled extends Compactor[Any]:
        def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
            Kyo.lift(ctx.raw)
    end Disabled

    private[kyo] object internal:

        // --- structural + scoring constants (tunable defaults) ---
        val adjacencyWeight: Double     = 1.0   // EdgeKind.Adjacency
        val referenceWeight: Double     = 3.0   // EdgeKind.Reference, damped by document frequency
        val dependencyWeight: Double    = 3.0   // EdgeKind.Dependency, the analysis DependsOn edge
        val relatednessWeight: Double   = 0.5   // EdgeKind.Relatedness, the analysis Relates edge
        val restartWeight: Double       = 0.15  // PPR restart probability
        val pprIterations: Int          = 20    // stability bound; the demotion decision converges by ~12
        val supersessionPenalty: Double = 0.2   // conservative default; tunable
        val seedObjective: Double       = 0.35  // last user turn
        val seedTask: Double            = 0.20  // first user turn / task origin
        val seedTail: Double            = 0.25  // geometrically decayed recent tail
        val seedUnresolved: Double      = 0.15  // unresolved tool calls
        val seedSystem: Double          = 0.05  // system head
        val seedTailTurns: Int          = 10    // recent regions carrying the geometric tail seed
        val seedTailTokens: Int         = 12000 // token bound on the geometric tail seed
        val recallSeedWeight: Double    = 0.20  // conservative default; tunable
        val recallDecay: Double         = 0.5   // conservative default; tunable
        // keep(p) = keepBase + keepScaling * (p - 1), floored at keepBase
        val keepBase: Double    = 0.03 // conservative default; tunable
        val keepScaling: Double = 0.06 // conservative default; tunable
        // span formation and the tail band
        val spanCapTokens: Int       = 4000 // conservative default; tunable
        val spanCapRegions: Int      = 8    // conservative default; tunable
        val tailBandFraction: Double = 0.25 // conservative default; tunable
        // presentation byte budgets; char units, biased over the token size class
        val tersePrefixChars: Int       = 200   // ~50-token terse prefix
        val substituteElisionChars: Int = 800   // fixed-size summary-level substitute
        val generousElisionChars: Int   = 24000 // exact-surface pinned-oversized
        val imageSurchargeChars: Int    = 6000  // ~2000-token vision surcharge, char units
        // the fill's summary output cap, token units; bounds summary size and its miss price in the
        // demotion arithmetic, so a demoted summary never inflates the served view unboundedly
        val summaryOutputCap: Int = 512 // conservative default; tunable
        // drift
        val driftRefractory: Int = 4 // conservative default; tunable
        // raw-retention eviction hysteresis
        val rawHighWatermark: Double = 0.9 // conservative default; tunable
        val rawLowWatermark: Double  = 0.5 // conservative default; tunable
        // The raw cap when the user leaves rawRetentionCap Absent: several window-widths, since raw is
        // only a verbatim cache behind the persisted summaries.
        val rawRetentionWidths: Int = 4 // conservative default; tunable

        // The four detail states. Verbatim is not "demoted": it never enters a demotions map,
        // so project only ever renders the three demoted states. Summary is span grain, Pointer is
        // region grain, Terse is a descent-only prefix of the summary bytes.
        enum Level derives CanEqual:
            case Verbatim, Summary, Terse, Pointer

        // The atomic REGION: an assistant message fused with its answering tool results, held as
        // fused INDICES into raw, its unresolved flag, and its apportioned stamped token size.
        final case class Region(id: Int, indices: Chunk[Int], unresolved: Boolean, tokens: Int)

        final case class Building(id: Int, indices: Chunk[Int], open: Set[String])

        // A formed SPAN: a contiguous run of regions, the summary level's grain, identified by
        // its raw ordinal range [start, end). Identity is a deterministic model-free function of
        // frozen content; the write-once summary slot is keyed by (start, end).
        final case class Span(start: Int, end: Int, regionIds: Chunk[Int])

        enum EdgeKind derives CanEqual:
            case Adjacency, Reference, Dependency, Relatedness

        final case class Edge(target: Int, kind: EdgeKind, weight: Double)

        final case class Graph(edges: Dict[Int, Chunk[Edge]]):
            def isEmpty: Boolean = edges.isEmpty
        object Graph:
            val empty: Graph = Graph(Dict.empty)

        // preparation batching constants (tunable defaults).
        val fillBatchSpans: Int     = 6 // conservative default; tunable
        val priorSummaryBudget: Int = 4 // conservative default; tunable

        // The write-once staging cell key: a span's raw ordinal range. Ordinals are
        // stable under the retention forget, so keys never alias across a session.
        final case class SpanKey(start: Int, end: Int) derives CanEqual

        // staging cell. Write-once summaries keyed by SpanKey and write-once analyses
        // keyed by region ordinal; the background fiber CAS-updates both as calls complete.
        final case class Staged(
            summaries: Dict[SpanKey, String] = Dict.empty[SpanKey, String],
            analyses: Dict[Int, RegionAnalysis] = Dict.empty[Int, RegionAnalysis]
        ):
            // First-writer-wins: a summary lands only into an EMPTY slot; a re-emission to a filled
            // slot is discarded, so whichever bytes reach the slot first are permanent.
            def withSummary(key: SpanKey, bytes: String): Staged =
                if summaries.contains(key) then this
                else copy(summaries = summaries.update(key, bytes))
            def summaryOf(key: SpanKey): Maybe[String] = summaries.get(key)
            // write-once analysis staging by region ordinal; a re-emission to a filled ordinal is
            // discarded, so whichever analysis lands first is permanent (idempotent under re-arming).
            def withAnalysis(ordinal: Int, ra: RegionAnalysis): Staged =
                if analyses.contains(ordinal) then this
                else copy(analyses = analyses.update(ordinal, ra))
            def analysisOf(ordinal: Int): Maybe[RegionAnalysis] = analyses.get(ordinal)
        end Staged

        // The two arming causes, the prepare line and the drift tripwire:
        // both share ONE single-flight run.
        enum ArmCause derives CanEqual:
            case Prepare, Drift

        // One per session: the staging cell plus the single-flight handle and the
        // set of causes currently in their armed band (latched on a line-cross, cleared on a
        // recross-below, so a cause re-arms only when its own line is recrossed). The
        // background fiber writes ONLY `staged` (CAS updates); the foreground adopts staged entries
        // into write-once compaction state and joins the fiber through `inFlight` at the boundary.
        final case class Preparation(
            staged: AtomicRef[Staged],
            inFlight: AtomicRef[Maybe[Fiber[Unit, Any]]],
            armed: AtomicRef[Set[ArmCause]]
        )

        object Preparation:
            def init(using Frame): Preparation < Sync =
                for
                    s <- AtomicRef.init(Staged())
                    f <- AtomicRef.init(Absent: Maybe[Fiber[Unit, Any]])
                    a <- AtomicRef.init(Set.empty[ArmCause])
                yield Preparation(s, f, a)
        end Preparation

        // Lifecycle: interrupt every in-flight preparation fiber. LLM.run wraps the
        // run body in Sync.ensure(interruptAll(registry)) so no fiber leaks past the run on ANY exit
        // (normal, abort, panic, interrupt); a boundary never calls this (it JOINS), only teardown and
        // per-cause disarm interrupt. Interrupting a completed fiber is a harmless no-op.
        def interruptAll(registry: AtomicRef[Set[Fiber[Unit, Any]]])(using Frame): Unit < Sync =
            registry.get.map(fs => Kyo.foreachDiscard(fs)(f => f.interrupt))

        /** The recall tool's typed input, object-wrapped so the wire schema is
          * `{"id":{"type":"integer"}}` (providers reject a bare integer parameter schema).
          */
        final case class Recall(id: Int) derives Schema, CanEqual

        // --- usage-anchored occupancy, apportionment, and stamp reads ---
        // The demotion loop reads STORED stamps and makes zero requests; counting narrows to
        // apportionment (stamping, below) and the offline suffix/bootstrap estimate.

        // The stored apportioned count for a message. A message with no stamp yet (a fresh synthetic
        // marker, or a bootstrap message before the first apportionment) falls back to the offline estimate.
        def stampedTokens(msg: Message): Int =
            msg.tokens match
                case Present(stamp) => stamp.count
                case Absent         => offlineEstimate(msg)

        // The offline conservative char-based estimate (bootstrap + suffix delta): ~1 token per 3
        // chars plus a per-message envelope, biased to over-count so occupancy never under-reads. A
        // user-message image adds a fixed conservative surcharge.
        def offlineEstimate(msg: Message): Int =
            val chars = msg match
                case AssistantMessage(c, calls, _, _) => c.length + calls.foldLeft(0)((n, x) => n + x.arguments.length)
                case UserMessage(c, image, _, _)      => c.length + (if image.isDefined then imageSurchargeChars else 0)
                case m                                => m.content.length
            (chars + 2) / 3 + 4
        end offlineEstimate

        // Usage-anchored occupancy: the last provider-reported request total plus the offline
        // estimate of the messages appended to the served view since that anchor; bootstrap / no-usage
        // falls wholly to the offline estimate over the served view. maxOutputTokens is NOT part of this
        // (it is counted once on the hard-limit side).
        def occupancy(ctx: Context): Int =
            val state = ctx.compactionState
            state.lastUsage match
                case Present(total) =>
                    // The suffix rides in tokenizer units: stampLiveSuffix stamps each live
                    // appended message with its tokenizer count at the turn start, so stampedTokens reads
                    // the tokenizer count, not the char/3 estimate the doc rejects; a still-unstamped
                    // message falls back to offlineEstimate.
                    val suffix = ctx.compacted.drop(state.lastUsageRawSize).foldLeft(0)((n, m) => n + stampedTokens(m))
                    total + suffix
                case Absent =>
                    // The wholly-offline path (a provider that never reports usage): tokenizer-stamped sum
                    // under a DISTINCT widened margin, so the never-corrected whole-session
                    // case carries extra overflow headroom the each-turn-corrected anchored suffix does not.
                    val offline = ctx.compacted.foldLeft(0)((n, m) => n + stampedTokens(m))
                    (offline * noUsageMargin).toInt
            end match
        end occupancy

        // The keep threshold at pressure p: monotone increasing in pressure, evaluated never
        // below its base (the keep floor/), where callers pass max(pressure, 1).
        def keep(pressure: Double): Double = keepBase + keepScaling * math.max(0.0, pressure - 1.0)

        // Apportionment: count each served-view message with the ACTIVE tokenizer, then normalize
        // those exact counts to sum EXACTLY to the provider's reported total via a largest-remainder
        // distribution (structural overhead absorbed by the normalization, relative ordering preserved,
        // no token lost or invented). Each message is stamped (tokenizerId, count), never a bare count,
        // so a stamp never mixes vocabularies across a provider switch. The tokenizer counts, never the
        // offline char estimate: the chars/3 estimate is content-dependent and shifts apportioned mass
        // from prose regions to tool-result regions, so the demotion loop must read the tokenizer's exact
        // per-message sizes (offlineEstimate stays only the bootstrap/suffix fallback).
        def apportion(messages: Chunk[Message], reportedTotal: Int, tokenizer: Tokenizer, tokenizerId: String)(
            using Frame
        ): Chunk[Message] < (LLM & Async & Abort[HttpException | AIGenException]) =
            if messages.isEmpty || reportedTotal <= 0 then Kyo.lift(messages)
            else
                Tokenizer.internal.countMessages(tokenizer, messages).map { counts =>
                    val total = counts.foldLeft(0)((n, x) => n + x)
                    if total <= 0 then messages
                    else
                        val exact  = counts.map(c => c.toDouble * reportedTotal / total)
                        val floors = exact.map(_.toInt)
                        val used   = floors.foldLeft(0)((n, x) => n + x)
                        val order  = exact.zipWithIndex.sortBy((v, _) => -(v - math.floor(v))).map(_._2)
                        val bump   = order.take(math.max(0, reportedTotal - used)).toSet
                        messages.zipWithIndex.map { (m, i) =>
                            val count = floors(i) + (if bump.contains(i) then 1 else 0)
                            stamp(m, TokenStamp(tokenizerId, count))
                        }
                    end if
                }

        def stamp(m: Message, s: TokenStamp): Message = m match
            case msg: SystemMessage    => msg.copy(tokens = Present(s))
            case msg: UserMessage      => msg.copy(tokens = Present(s))
            case msg: AssistantMessage => msg.copy(tokens = Present(s))
            case msg: ToolMessage      => msg.copy(tokens = Present(s))

        // The active tokenizer and its vocabulary id: the user override when set, else the offline
        // tiktoken default (o200k for the openai-compatible tail). The id tags every apportioned stamp so
        // counts never cross vocabularies; a provider switch changes the id and the next anchor re-stamps.
        def activeTokenizer(config: Config): (Tokenizer, String) =
            config.tokenizer match
                case Present(t) => (t, s"${config.provider.name}:user")
                case Absent     => (Tokenizer.tiktoken(Tokenizer.Encoding.O200kBase), "o200k")

        // The fused usage re-anchor, the ONE step every usage-consumption site runs so the anchor
        // scalar and the per-message stamps it covers can never disagree: record the provider's exact
        // reported total as the anchor at the sent view's size, apportion EXACTLY the sent view (the
        // messages that total covers, never the post-response tail), and propagate each apportioned stamp
        // onto the identical raw entry. The full sent view is re-stamped every anchor (not only where a
        // stamp is Absent) so a mid-session provider switch converges in one round-trip.
        def reanchor(ctx: Context, sentView: Chunk[Message], reportedTotal: Int, tokenizer: Tokenizer, tokenizerId: String)(
            using Frame
        ): Context < (LLM & Async & Abort[HttpException | AIGenException]) =
            apportion(sentView, reportedTotal, tokenizer, tokenizerId).map { stamped =>
                val anchored = ctx.withCompaction(ctx.compactionState.withUsage(reportedTotal, sentView.size))
                // Live raw twins take their apportioned stamp through propagateStamps; synthetic markers
                // (origin Present) have no raw twin, so their apportioned share is written back into the
                // SERVED view here, so viewTokens and demotion
                // sizing price a marker by its share, never the char estimate.
                anchored.copy(
                    raw = propagateStamps(anchored.raw, stamped),
                    compacted = propagateMarkerStamps(anchored.compacted, stamped)
                )
            }

        // Propagate the sent view's apportioned stamps onto the identical raw entries by core identity
        // (Context.coreEq ignores tokens, so a freshly stamped copy still matches its raw twin). Only a
        // LIVE raw entry (origin Absent) is eligible: a coarse band head or content-freed tombstone
        // (origin Present, stamp 0) is projection-skipped and must never be overwritten. A synthetic
        // marker in the sent view (origin Present) has no raw twin and is excluded from the match.
        def propagateStamps(raw: Chunk[Message], stampedView: Chunk[Message]): Chunk[Message] =
            val live = stampedView.filter(_.origin.isEmpty)
            raw.map { m =>
                if m.origin.isDefined then m
                else
                    live.find(s => Context.coreEq(s, m)) match
                        case Some(s) =>
                            s.tokens match
                                case Present(t) => stamp(m, t)
                                case Absent     => m
                        case None => m
            }
        end propagateStamps

        // Install each synthetic marker's apportioned stamp into the served view. A marker
        // (origin Present) is matched by its Origin, unique to a marker; a live entry (origin Absent) is
        // stamped through raw propagation + the suffix pass, never here.
        def propagateMarkerStamps(compacted: Chunk[Message], stampedView: Chunk[Message]): Chunk[Message] =
            val markers = stampedView.filter(_.origin.isDefined)
            compacted.map { m =>
                if m.origin.isEmpty then m
                else
                    markers.find(s => s.origin == m.origin) match
                        case Some(s) =>
                            s.tokens match
                                case Present(t) => stamp(m, t)
                                case Absent     => m
                        case None => m
            }
        end propagateMarkerStamps

        // The distinct widened margin for a provider that never reports usage: the wholly
        // offline occupancy carries extra overflow headroom the each-turn-corrected anchored suffix does
        // not need.
        val noUsageMargin: Double = 1.15 // conservative default; tunable

        // The seated stream-usage carrier: a prior streaming turn's reported-usage SINK (written
        // by the adapter SSE projection at stream end, OUTSIDE the LLM handler, hence an AtomicRef), plus
        // the rendered sent view and the active tokenizer/id captured when the stream request was
        // assembled. applyStreamMeasure consumes it at the next turn's start. Ephemeral, never serialized.
        case class StreamAnchor(
            usageSink: AtomicRef[Maybe[Completion.Usage]],
            sentView: Chunk[Message],
            tokenizer: Tokenizer,
            tokenizerId: String
        )

        // the suffix in tokenizer units: tokenizer-count every LIVE message (origin Absent)
        // with no stamp yet and stamp it in BOTH compacted and raw twins (by coreEq, mirroring
        // propagateStamps). A raw un-normalized count the next anchor apportions and overwrites; a marker
        // (origin Present) is owned by the anchor's apportioned share, never stamped here. Returns ctx by
        // reference when nothing needs stamping, so a below-trigger re-serve stays reference-identical.
        // Model-free: the offline tiktoken count is a pure local call.
        def stampLiveSuffix(ctx: Context, tokenizer: Tokenizer, tokenizerId: String)(using
            Frame
        ): Context < (LLM & Async & Abort[HttpException | AIGenException]) =
            val pending = ctx.compacted.filter(m => m.origin.isEmpty && m.tokens.isEmpty)
            if pending.isEmpty then Kyo.lift(ctx)
            else
                Tokenizer.internal.countMessages(tokenizer, pending).map { counts =>
                    val stampedPending = pending.zipWithIndex.map((m, i) => stamp(m, TokenStamp(tokenizerId, counts(i))))
                    def restamp(m: Message): Message =
                        if m.origin.isDefined || m.tokens.isDefined then m
                        else
                            stampedPending.find(s => Context.coreEq(s, m)) match
                                case Some(s) =>
                                    s.tokens match
                                        case Present(t) => stamp(m, t)
                                        case Absent     => m
                                case None => m
                    ctx.copy(compacted = ctx.compacted.map(restamp), raw = ctx.raw.map(restamp))
                }
            end if
        end stampLiveSuffix

        // the turn-start measure step, shared by gen (eval) and stream
        // (streamAgainst). FIRST apply a pending stream re-anchor (a prior streaming turn seated its
        // reported-usage sink + sent view; the anchor updates HERE, the next turn's start, the only point
        // the LLM handler is live, observationally equivalent to gen's post-response re-anchor since
        // occupancy's sole consumer is this same turn). THEN stamp any live suffix in tokenizer units.
        // Idempotent: no pending anchor + every live message already stamped makes no write, so a
        // below-trigger re-serve is byte-identical. Model-free: zero completions,
        // no fiber.
        def applyStreamMeasure(ai: AI, config: Config)(using
            Frame
        ): Unit < (LLM & Async & Abort[HttpException | AIGenException]) =
            ai.context.map { ctx =>
                LLM.session(ai).map { session =>
                    val anchored: (Context, Boolean) < (LLM & Async & Abort[HttpException | AIGenException]) =
                        session.streamAnchor match
                            case Present(anchor) =>
                                anchor.usageSink.get.map {
                                    case Present(u) =>
                                        reanchor(ctx, anchor.sentView, u.inputTokens, anchor.tokenizer, anchor.tokenizerId)
                                            .map(rc => (rc, true))
                                    case Absent => Kyo.lift((ctx, false))
                                }
                            case Absent => Kyo.lift((ctx, false))
                    anchored.map { (ctx1, reanchored) =>
                        val (tokenizer, tokenizerId) = activeTokenizer(config)
                        stampLiveSuffix(ctx1, tokenizer, tokenizerId).map { ctx2 =>
                            val session1       = if reanchored then session.clearStreamAnchor else session
                            val ctxChanged     = !(ctx2 eq ctx)
                            val sessionChanged = !(session1 eq session)
                            val writeSession   = if sessionChanged then LLM.setSession(ai, session1) else Kyo.unit
                            val writeContext   = if ctxChanged then ai.setContext(ctx2) else Kyo.unit
                            writeSession.andThen(writeContext)
                        }
                    }
                }
            }
        end applyStreamMeasure

        // ---------------------------------------------------------------------------------------
        // The single shipped default. render returns only the rebuilt compacted Chunk[Message]; raw
        // never appears in its signature. It reads the ACTIVE kyo.ai.Config live, makes ZERO model
        // calls, and forks NO fibers ITSELF: it derives A_fresh and PROJECTS from the write-once
        // summaries already ADOPTED into ctx.compactionState by the seam (LLM.renderView runs
        // adopt -> derive-need -> join around this call, so every summary-level slot render reads is
        // filled). The validity gate selects the join need source in the seam; the
        // rendered assignment is always A_fresh, so a false invalidation costs zero new model calls.
        // ---------------------------------------------------------------------------------------
        object Default extends Compactor[Any]:

            override private[kyo] def isDefault: Boolean = true

            def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
                AI.config.map { config =>
                    val window   = config.modelMaxTokens
                    val low      = config.effectiveLow
                    val hard     = config.hardLimitTokens
                    val occupied = occupancy(ctx)
                    val pressure = if low <= 0 then 1.0 else occupied.toDouble / low.toDouble
                    val state    = ctx.compactionState
                    val analyses = state.analyses
                    val units    = group(ctx.raw)
                    val spans    = formSpans(units, ctx.raw, config)
                    superKeys(units, ctx.raw).map { keys =>
                        val superseded = mergeSupersession(supersession(units, keys), analyzedSupersession(analyses))
                        val graph      = deriveGraph(units, ctx.raw, superseded, analyzedEdges(analyses))
                        val seed       = seedVector(units, ctx.raw, state)
                        val scores     = score(units, graph, superseded, seed)
                        val prevLevels = demotedOrigins(ctx.compacted)
                        val since      = ctx.raw.size
                        val demotions  = cut(ctx, units, spans, scores, pressure, occupied, low, since, prevLevels)
                        val view       = project(ctx.raw, units, spans, demotions, since, prevLevels, state, keys)
                        if viewTokens(view) <= hard then view
                        else
                            val forcedView = forced(ctx.raw, units, spans, scores, pressure, hard, since, prevLevels, keys)
                            if viewTokens(forcedView) > hard then
                                Abort.fail(AIContextOverflowException(viewTokens(forcedView), window))
                            else forcedView
                        end if
                    }
                }
            end render

            override def tools(ai: AI)(using Frame): Chunk[Tool[LLM]] = Chunk(recallTool(ai))

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
                val bandTokens = (tailBandFraction * config.effectiveLow).toInt
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

            // ---- key supersession (typed compactionKey via the Info closure, no cast) ----
            def superKeys(units: Chunk[Region], raw: Chunk[Message])(using Frame): Dict[Int, (String, Tool.Kind)] < LLM =
                Tool.internal.infos.map(infos => superKeysFrom(units, raw, infos))

            def supersession(units: Chunk[Region], keys: Dict[Int, (String, Tool.Kind)]): Dict[Int, Int] =
                val (result, _) =
                    units.sortBy(_.id).foldLeft((Dict.empty[Int, Int], Dict.empty[String, (Int, Tool.Kind)])) {
                        case ((sup, last), u) =>
                            keys.get(u.id) match
                                case Absent => (sup, last)
                                case Present((k, curKind)) =>
                                    last.get(k) match
                                        case Present((prevId, prevKind)) =>
                                            val supersedes = curKind == Tool.Kind.Write || prevKind == Tool.Kind.Read
                                            val sup2       = if supersedes then sup.update(prevId, u.id) else sup
                                            (sup2, last.update(k, (u.id, curKind)))
                                        case Absent => (sup, last.update(k, (u.id, curKind)))
                    }
                result
            end supersession

            // ---- graph: structural Adjacency + Reference edges; the analysis pass's Dependency and
            // Relatedness edges merge in via `analyzed`, empty until then. Reference and analyzed edges
            // repoint through supersession so liveness accrues to current content. The identifier extractor
            // (extractTokens) requires interior signal, so sentence-initial capitalized words never mint
            // identifiers; the hub damping is the document-frequency cutoff.
            def deriveGraph(
                units: Chunk[Region],
                raw: Chunk[Message],
                superseded: Dict[Int, Int],
                analyzed: Chunk[(Int, Int, EdgeKind)] = Chunk.empty
            ): Graph =
                if units.isEmpty then Graph.empty
                else
                    val ordered = units.toList.sortBy(_.id)
                    val perUnit: List[(Int, Set[String])] =
                        ordered.map(u => (u.id, extractTokens(unitContent(u, raw)).toSet))
                    val introducer = perUnit.foldLeft(Dict.empty[String, Int]) { case (idx, (id, toks)) =>
                        toks.foldLeft(idx)((ix, t) => if ix.contains(t) then ix else ix.update(t, id))
                    }
                    val mentions = perUnit.foldLeft(Map.empty[String, Int]) { case (mc, (_, toks)) =>
                        toks.foldLeft(mc)((m, t) => m.updated(t, m.getOrElse(t, 0) + 1))
                    }
                    val adj: List[(Int, Edge)] =
                        ordered.sliding(2).toList.collect { case prev :: cur :: Nil =>
                            (cur.id, Edge(prev.id, EdgeKind.Adjacency, adjacencyWeight))
                        }
                    val ref: List[(Int, Edge)] =
                        perUnit.flatMap { case (id, toks) =>
                            toks.toList.flatMap { t =>
                                introducer.get(t) match
                                    case Present(intro) if intro != id =>
                                        val target = repoint(intro, superseded)
                                        if target == id then Nil
                                        else
                                            val hub = 1.0 + math.log(1.0 + mentions.getOrElse(t, 1).toDouble)
                                            List((id, Edge(target, EdgeKind.Reference, referenceWeight / hub)))
                                        end if
                                    case _ => Nil
                            }
                        }
                    val semantic: List[(Int, Edge)] =
                        analyzed.toList.collect {
                            case (from, target, EdgeKind.Dependency) =>
                                (from, Edge(repoint(target, superseded), EdgeKind.Dependency, dependencyWeight))
                            case (from, target, EdgeKind.Relatedness) =>
                                (from, Edge(repoint(target, superseded), EdgeKind.Relatedness, relatednessWeight))
                        }
                    val edges = (adj ++ ref ++ semantic).foldLeft(Map.empty[Int, Chunk[Edge]]) { case (m, (from, e)) =>
                        m.updated(from, m.getOrElse(from, Chunk.empty).append(e))
                    }
                    Graph(Dict.from(edges))
            end deriveGraph

            // ---- scoring (one-shot Personalized PageRank; supersession penalty applied outside) ----
            def score(units: Chunk[Region], graph: Graph, superseded: Dict[Int, Int], seed: Dict[Int, Double]): Dict[Int, Double] =
                if units.isEmpty then Dict.empty
                else
                    val ids   = units.toList.map(_.id)
                    val alpha = restartWeight
                    val normEdges: List[(Int, Int, Double)] =
                        ids.flatMap { id =>
                            val es  = graph.edges.get(id).getOrElse(Chunk.empty)
                            val sum = es.foldLeft(0.0)((a, e) => a + e.weight)
                            if sum <= 0.0 then Nil else es.toList.map(e => (id, e.target, e.weight / sum))
                        }
                    def seedOf(id: Int): Double = seed.get(id).getOrElse(0.0)
                    @tailrec def iterate(r: Map[Int, Double], n: Int): Map[Int, Double] =
                        if n <= 0 then r
                        else
                            val base = ids.map(id => id -> alpha * seedOf(id)).toMap
                            val next = normEdges.foldLeft(base) { case (acc, (from, to, w)) =>
                                acc.updated(to, acc.getOrElse(to, 0.0) + (1.0 - alpha) * w * r.getOrElse(from, 0.0))
                            }
                            iterate(next, n - 1)
                    val ranked    = iterate(ids.map(id => id -> seedOf(id)).toMap, pprIterations)
                    val penalized = ranked.map { case (id, v) => id -> (if superseded.contains(id) then v * supersessionPenalty else v) }
                    Dict.from(penalized)
            end score

            def seedVector(units: Chunk[Region], raw: Chunk[Message], state: Context.CompactionState): Dict[Int, Double] =
                if units.isEmpty then Dict.empty
                else
                    val ordered  = units.toList.sortBy(_.id)
                    val systemId = ordered.headOption.filter(u => isSystemHead(u, raw)).map(_.id)
                    val userIds  = ordered.filter(u => hasUser(u, raw)).map(_.id)
                    val taskId   = userIds.headOption
                    val objId    = userIds.lastOption
                    val unresIds = ordered.filter(_.unresolved).map(_.id)
                    val tailIds  = tailUnits(units).toList.sorted
                    val singles: List[(List[Int], Double)] =
                        List(
                            (objId.toList, seedObjective),
                            (taskId.toList, seedTask),
                            (unresIds, seedUnresolved),
                            (systemId.toList, seedSystem)
                        )
                    val folded         = singles.foldLeft(0.0) { case (acc, (t, w)) => if t.isEmpty then acc + w else acc }
                    val tailShare      = seedTail + folded
                    val singleContribs = singles.flatMap { case (t, w) => if t.isEmpty then Nil else t.map(id => (id, w / t.size)) }
                    val tailOrder      = tailIds.reverse
                    val geo            = tailOrder.zipWithIndex.map { case (id, k) => (id, math.pow(0.5, k.toDouble)) }
                    val geoSum         = geo.foldLeft(0.0)((a, g) => a + g._2)
                    val tailContribs   = if geoSum <= 0.0 then Nil else geo.map { case (id, g) => (id, tailShare * g / geoSum) }
                    // recall as a decaying seed: each recall record contributes to its region's seed
                    // entry, decaying geometrically per boundary since the recall. The record lives in state,
                    // never inferred from the view, so clearing the recall exchange never drops the signal.
                    val recallContribs = state.recalls.map { r =>
                        (r.region, recallSeedWeight * math.pow(recallDecay, (state.boundaryCounter - r.boundaryStamp).toDouble))
                    }
                    val merged = (singleContribs ++ tailContribs ++ recallContribs).foldLeft(Map.empty[Int, Double]) {
                        case (m, (id, v)) => m.updated(id, m.getOrElse(id, 0.0) + v)
                    }
                    Dict.from(merged)
            end seedVector

            // ---- the seed's decayed tail set: the most recent regions carrying the geometric tail
            // seed. v4 has no separate roots-pin or co-pin machinery: the keep threshold plus span pinning
            // subsume it, and unresolved-turn regions are excluded from spans (never demotable).
            def tailUnits(units: Chunk[Region]): Set[Int] =
                val ordered = units.toList.sortBy(_.id).reverse
                @tailrec def loop(rem: List[Region], count: Int, tokens: Int, acc: Set[Int]): Set[Int] =
                    rem match
                        case Nil => acc
                        case u :: rest =>
                            if count >= seedTailTurns then acc
                            else if tokens + u.tokens > seedTailTokens && acc.nonEmpty then acc
                            else loop(rest, count + 1, tokens + u.tokens, acc + u.id)
                loop(ordered, 0, 0, Set.empty)
            end tailUnits

            // The retention working-set tail band, trimmed at eviction time so the head and tail bands
            // together leave room under the low watermark, letting eviction of the frozen demoted middle
            // reach the target. A runtime guard, not a config default, since the band is dynamic. It
            // only shrinks the POSITIONAL tail protection; the evictable filter still forgets nothing that is
            // not currently demoted, so live content is never forgotten.
            def retentionTail(units: Chunk[Region], raw: Chunk[Message], headTokens: Int, low: Int): Set[Int] =
                val ordered                     = units.toList.sortBy(_.id).reverse
                def regionStamp(u: Region): Int = u.indices.foldLeft(0)((n, i) => n + stampedTokens(raw(i)))
                @tailrec def loop(rem: List[Region], count: Int, tokens: Int, acc: Set[Int]): Set[Int] =
                    rem match
                        case Nil => acc
                        case u :: rest =>
                            val ut = regionStamp(u)
                            if count >= seedTailTurns then acc
                            else if tokens + ut > seedTailTokens && acc.nonEmpty then acc
                            else if headTokens + tokens + ut > low && acc.nonEmpty then acc
                            else loop(rest, count + 1, tokens + ut, acc + u.id)
                            end if
                loop(ordered, 0, 0, Set.empty)
            end retentionTail

            // ---- region bookkeeping derived from compacted, no string parsing ----
            // A demoted unit/span is exactly one synthetic entry carrying Present(origin); origin.start is
            // the unit/span id, origin.since is the raw index at the boundary that demoted it. Promotion is
            // not tracked by a flag: recall is a decaying liveness seed, so a recalled region reinstates
            // through scoring, not a separate promotion set.
            def demotedOrigins(compacted: Chunk[Message]): Dict[Int, Context.Origin] =
                compacted.foldLeft(Dict.empty[Int, Context.Origin]) { (m, msg) =>
                    msg.origin match
                        case Present(o) => m.update(o.start, o)
                        case Absent     => m
                }

            // ---- raw retention: a heap backstop for a pathological session length, orthogonal to the
            // compacted view, the graph, and the boundary. raw is only a verbatim cache behind the
            // persisted summaries, so it can be bounded. Tested only at a boundary against raw's running
            // stamp sum, never on the hot path. Ordinals stay stable (a forgotten run is replaced IN
            // PLACE), so survivors keep their positions and every stored Origin / recall id still resolves.
            // ZERO model calls, no fiber.

            // The cap when the user leaves rawRetentionCap Absent: several window-widths.
            def effectiveRawCap(config: Config): Int =
                config.compaction.rawRetentionCap.getOrElse(rawRetentionWidths * config.modelMaxTokens)

            // The fixed head band: the system head plus the task-origin (first user) turn, never touched.
            // A live region stays verbatim (never demoted) and so is excluded by the demotion check below
            // without re-running the graph; the tail band / working set is tailUnits.
            def headBand(units: Chunk[Region], raw: Chunk[Message]): Set[Int] =
                val ordered = units.sortBy(_.id)
                val sys     = ordered.headOption.filter(u => isSystemHead(u, raw)).map(_.id).toSet
                val taskId  = ordered.filter(u => hasUser(u, raw)).map(_.id).headOption.toSet
                sys ++ taskId
            end headBand

            // Contiguous index runs from a sorted ordinal list: each maximal [start, endExcl) run becomes
            // one coarse band.
            def contiguousRuns(sorted: List[Int]): List[(Int, Int)] =
                sorted match
                    case Nil => Nil
                    case first :: _ =>
                        @tailrec def loop(rem: List[Int], start: Int, prev: Int, acc: List[(Int, Int)]): List[(Int, Int)] =
                            rem match
                                case Nil => ((start, prev + 1) :: acc).reverse
                                case x :: rest =>
                                    if x == prev + 1 then loop(rest, start, x, acc) else loop(rest, x, x, (start, prev + 1) :: acc)
                        loop(sorted.tail, first, first, Nil)

            // The coarse between-turns band head: one synthetic marker standing for the forgotten run
            // [start, end), carrying an Origin and a stamp (Absent -> the small offline estimate) but NO
            // recall id, so the model is never invited to recall forgotten content.
            def coarseBand(start: Int, end: Int, regions: Int, since: Int): Message =
                SystemMessage(
                    s"[$regions region(s) from an earlier stretch forgotten past the retention horizon; no longer recallable]",
                    origin = Present(Context.Origin(start, end, since))
                )

            // A content-freed tombstone for a non-first ordinal of a forgotten run: the large raw bytes
            // are released (stamp 0), the slot is kept so survivors' ordinals never renumber, and the band
            // Origin marks it a band member (folded into the band region by group, skipped by project).
            def tombstone(origin: Context.Origin): Message =
                SystemMessage("", tokens = Present(TokenStamp("<forgotten>", 0)), origin = Present(origin))

            // Drop the forgotten regions' persisted state entries: the write-once summary slots, the
            // analysis slots, and the recall records whose ordinal is forgotten. The coarse band replaces
            // them; pruning the analysis slots alongside the summaries keeps a summary key and an analysis
            // key from aliasing across a forget.
            def dropForgottenState(state: Context.CompactionState, forgotten: Set[Int]): Context.CompactionState =
                state.copy(
                    summaries = state.summaries.filter(s => !forgotten.contains(s.start)),
                    analyses = state.analyses.filter(a => !forgotten.contains(a.ordinal)),
                    recalls = state.recalls.filter(r => !forgotten.contains(r.region))
                )

            // Evict at a boundary AFTER the view is rendered: if raw's running stamp sum crosses the high
            // watermark, forget the oldest evictable regions wholesale down to the low watermark. A region
            // is evictable iff it is CURRENTLY DEMOTED in the just-rendered view (a persisted marker, the
            // one state-map lookup, so a still-live verbatim region is never evictable), outside the head
            // and tail bands, and not already a forgotten band. Best-effort against dead history: if the
            // middle is all live the sum may stay above low, which is correct.
            def evict(ctx: Context, config: Config): Context =
                val cap = effectiveRawCap(config)
                if cap <= 0 then ctx
                else
                    val rawSum = ctx.raw.foldLeft(0)((n, m) => n + stampedTokens(m))
                    val high   = (rawHighWatermark * cap).toInt
                    if rawSum <= high then ctx
                    else
                        val low     = (rawLowWatermark * cap).toInt
                        val units   = group(ctx.raw)
                        val demoted = demotedOrigins(ctx.compacted)
                        val headIds = headBand(units, ctx.raw)
                        val headTokens = units.filter(u => headIds.contains(u.id))
                            .foldLeft(0)((n, u) => n + u.indices.foldLeft(0)((s, i) => s + stampedTokens(ctx.raw(i))))
                        val tailIds = retentionTail(units, ctx.raw, headTokens, low)
                        val evictable =
                            units.toList.sortBy(_.id).filter { u =>
                                demoted.contains(u.id) && !headIds.contains(u.id) && !tailIds.contains(u.id) &&
                                !u.indices.headOption.exists(i => ctx.raw(i).origin.isDefined)
                            }
                        @tailrec def pick(rem: List[Region], freed: Int, acc: List[Region]): List[Region] =
                            if rawSum - freed <= low then acc
                            else
                                rem match
                                    case Nil => acc
                                    case u :: rest =>
                                        val save = u.indices.foldLeft(0)((n, i) => n + stampedTokens(ctx.raw(i)))
                                        pick(rest, freed + save, u :: acc)
                        val forget = pick(evictable, 0, Nil)
                        if forget.isEmpty then ctx
                        else
                            val forgotten = forget.flatMap(_.indices).toSet
                            val runs      = contiguousRuns(forgotten.toList.sorted)
                            val counts    = runs.map((a, b) => a -> forget.count(u => u.id >= a && u.id < b)).toMap
                            val idxToRun  = Dict.from(runs.flatMap((a, b) => (a until b).toList.map(i => i -> ((a, b)))).toMap)
                            val since     = ctx.raw.size
                            val raw2 = ctx.raw.zipWithIndex.map { (m, i) =>
                                idxToRun.get(i) match
                                    case Present((a, b)) =>
                                        if i == a then coarseBand(a, b, counts.getOrElse(a, 1), since)
                                        else tombstone(Context.Origin(a, b, since))
                                    case Absent => m
                            }
                            ctx.copy(raw = raw2).withCompaction(dropForgottenState(ctx.compactionState, forgotten))
                        end if
                    end if
                end if
            end evict

            // The coldest member liveness of a span (pass-2 ascending order) and its hottest member
            // (the pinning test): a span is demotable iff EVERY member is below the floored keep, i.e. its
            // hottest member is below keep.
            def spanLiveness(sp: Span, scores: Dict[Int, Double]): Double =
                sp.regionIds.foldLeft(Double.MaxValue)((m, id) => math.min(m, scores.get(id).getOrElse(0.0)))
            def spanMaxLiveness(sp: Span, scores: Dict[Int, Double]): Double =
                sp.regionIds.foldLeft(0.0)((m, id) => math.max(m, scores.get(id).getOrElse(0.0)))

            // A_prep: the speculative level assignment under PROJECTED boundary semantics.
            // The cut carries no cache-gate veto, so the projection is the
            // same cut run at the projected boundary: for the SIZE cause a boundary at exactly
            // effectiveHigh, so projected pressure is effectiveHigh/effectiveLow and pass 2 runs as at the
            // boundary; for the drift cause current occupancy with pressure floored at 1. Pure,
            // in-memory, model-free; recomputed every seam pass, so the gate compares an
            // assignment at most one generation old. `driftCause` selects the projected pressure basis.
            def projectedAssignment(
                ctx: Context,
                units: Chunk[Region],
                spans: Chunk[Span],
                scores: Dict[Int, Double],
                config: Config,
                since: Int,
                prevLevels: Dict[Int, Context.Origin],
                driftCause: Boolean
            )(using Frame): Dict[Int, Level] =
                val high     = config.effectiveHigh
                val low      = config.effectiveLow
                val occupied = occupancy(ctx)
                val projPressure =
                    if driftCause then math.max(occupied.toDouble / math.max(low, 1).toDouble, 1.0)
                    else if low <= 0 then 1.0
                    else high.toDouble / low.toDouble
                val projOccupied = if driftCause then occupied else high
                cut(ctx, units, spans, scores, projPressure, projOccupied, low, since, prevLevels)
            end projectedAssignment

            // the need-shaped fill set: EXACTLY the spans A_prep assigns the summary level.
            // A projected-pinned span is absent from the assignment (never demoted); a projected-pointer
            // span carries Level.Pointer; neither buys a fill. The assignment is the bound: no
            // speculationMargin, no covered-savings ledger.
            def fillNeed(spans: Chunk[Span], assignment: Dict[Int, Level]): Chunk[Span] =
                spans.filter(sp => assignment.get(sp.start).contains(Level.Summary))

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
                                    case Absent => Absent
                                    case Present(info) =>
                                        info.compactionKeyFor(call.arguments) match
                                            case Present(k) => Present((k, info.kind))
                                            case Absent     => Absent
                    }
                    keyed match
                        case Present(kk) => acc.update(u.id, kk)
                        case Absent      => acc
                }
            end superKeysFrom

            // the per-region relation cap (proposed 4): bounds a disobedient pass and the output bill.
            val relationCap: Int = 4 // conservative default; tunable

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

            // The relevance-drift decision: a model-free tripwire under the size boundary.
            enum DriftDecision derives CanEqual:
                case Fire, Arm, Idle

            // Merges the adopted analyses with the fiber-staged ones, adopted taking precedence on a
            // shared ordinal, so the drift measure reads every relation currently produced.
            def mergeAnalyses(adopted: Chunk[RegionAnalysis], staged: Chunk[RegionAnalysis]): Chunk[RegionAnalysis] =
                adopted ++ staged.filterNot(s => adopted.exists(_.ordinal == s.ordinal))

            // The drift signal S: one graph derivation, one PPR, one sweep, relations read from
            // state and never fetched. S sums the stamped token counts of the STALE SET, the literal
            // complement of the cut's own demotable filter (a span whose max member liveness falls
            // below the keep floor) minus any span already demoted in the served view.
            def driftSignal(
                ctx: Context,
                config: Config,
                infos: Chunk[Tool.internal.Info[?, ?, LLM]],
                analyses: Chunk[RegionAnalysis]
            )(using Frame): Int =
                val units = group(ctx.raw)
                val spans = formSpans(units, ctx.raw, config)
                val superseded =
                    mergeSupersession(supersession(units, superKeysFrom(units, ctx.raw, infos)), analyzedSupersession(analyses))
                val graph     = deriveGraph(units, ctx.raw, superseded, analyzedEdges(analyses))
                val seed      = seedVector(units, ctx.raw, ctx.compactionState)
                val scores    = score(units, graph, superseded, seed)
                val occupied  = occupancy(ctx)
                val low       = config.effectiveLow
                val pressure  = if low <= 0 then 1.0 else occupied.toDouble / low.toDouble
                val keepFloor = keep(math.max(pressure, 1.0))
                val demoted   = demotedOrigins(ctx.compacted)
                val byId      = units.foldLeft(Dict.empty[Int, Region])((m, u) => m.update(u.id, u))
                val staleSet =
                    spans.filter(sp => spanMaxLiveness(sp, scores) < keepFloor && !sp.regionIds.exists(id => demoted.contains(id)))
                staleSet.foldLeft(0)((n, sp) => n + sp.regionIds.foldLeft(0)((t, id) => t + byId.get(id).map(_.tokens).getOrElse(0)))
            end driftSignal

            // The refractory guard: a fire is allowed only when none has fired yet (lastDriftFire
            // < 0, the fresh-session escape hatch) or driftRefractory boundary generations have elapsed.
            def refractoryAllows(state: Context.CompactionState): Boolean =
                state.lastDriftFire < 0 || (state.boundaryCounter - state.lastDriftFire) >= driftRefractory

            // The drift decision: structural-arm then analysis-confirm then fire. No model call
            // runs here; S is recomputed over the adopted-plus-staged relations at the confirm. A
            // crossing that clears the threshold and the refractory fires when already pending-confirm,
            // arms otherwise; a sub-threshold or refractory-blocked crossing stays idle.
            def driftDecision(
                ctx: Context,
                config: Config,
                infos: Chunk[Tool.internal.Info[?, ?, LLM]],
                staged: Chunk[RegionAnalysis]
            )(using Frame): DriftDecision =
                config.compaction.driftThreshold match
                    case Absent => DriftDecision.Idle
                    case Present(threshold) =>
                        val state   = ctx.compactionState
                        val s       = driftSignal(ctx, config, infos, mergeAnalyses(state.analyses, staged))
                        val crosses = s.toDouble >= threshold * config.effectiveLow.toDouble
                        if !crosses then DriftDecision.Idle
                        else if !refractoryAllows(state) then DriftDecision.Idle
                        else if state.driftPendingConfirm then DriftDecision.Fire
                        else DriftDecision.Arm
                        end if
            end driftDecision

            // ---- the cut: the unified DEMOTION RULE, one rule for size-fired and drift-fired
            // boundaries. Pinning: a span with any at-or-above-keep(max(pressure,1)) member stays verbatim.
            // Pass 1 (relevance, unconditional): every demotable span to its summary level, no stop
            // condition. Pass 2 (size, conditional): only while occupied > low, descend summary-level spans
            // in ascending liveness, one level per round (summary -> terse skipping blob-less/short spans,
            // then -> per-region pointers), stopping the moment occupied <= low. Pressure enters exactly
            // twice (the floored keep, and pass-2 depth); it never sizes any text. The keys are span.start.
            def cut(
                ctx: Context,
                units: Chunk[Region],
                spans: Chunk[Span],
                scores: Dict[Int, Double],
                pressure: Double,
                occupied: Int,
                low: Int,
                since: Int,
                prevLevels: Dict[Int, Context.Origin]
            )(using Frame): Dict[Int, Level] =
                val state     = ctx.compactionState
                val keepFloor = keep(math.max(pressure, 1.0))
                val demotable = spans.toList.filter(sp => spanMaxLiveness(sp, scores) < keepFloor)
                val pass1     = demotable.foldLeft(Dict.empty[Int, Level])((d, sp) => d.update(sp.start, Level.Summary))
                if occupied <= low then pass1
                else
                    val ascending = demotable.sortBy(sp => spanLiveness(sp, scores))
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
                    @tailrec def toPointer(rem: List[Span], dem: Dict[Int, Level]): Dict[Int, Level] =
                        if fits(dem) then dem
                        else
                            rem match
                                case Nil        => dem
                                case sp :: rest => toPointer(rest, dem.update(sp.start, Level.Pointer))
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
                pressure: Double,
                hard: Int,
                since: Int,
                prevLevels: Dict[Int, Context.Origin],
                keys: Dict[Int, (String, Tool.Kind)]
            )(using Frame): Chunk[Message] =
                val ascending = spans.toList.sortBy(sp => spanLiveness(sp, scores))
                @tailrec def pointerAll(rem: List[Span], dem: Dict[Int, Level]): Dict[Int, Level] =
                    if viewTokens(project(raw, units, spans, dem, since, prevLevels, keys = keys)) <= hard then dem
                    else
                        rem match
                            case Nil        => dem
                            case sp :: rest => pointerAll(rest, dem.update(sp.start, Level.Pointer))
                val view = project(raw, units, spans, pointerAll(ascending, Dict.empty), since, prevLevels, keys = keys)
                if viewTokens(view) <= hard then view
                else elideOversizedTail(view, hard)
            end forced

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

            // =========================================================================================
            // preparation machinery: the seam-facing arm (below the boundary) and boundaryPrepare
            // (adopt + join at the boundary), the single-flight fiber, the fill routes, and the gate.
            // Everything here is model-free EXCEPT runFill (a degraded typed completion) and the join.
            // =========================================================================================

            // Materializes the per-session Preparation cell on first use.
            def ensurePreparation(session: AISession)(using Frame): (Preparation, AISession) < Sync =
                session.preparation match
                    case Present(prep) => Kyo.lift((prep, session))
                    case Absent        => Preparation.init.map(prep => (prep, session.withPreparation(prep)))

            // ADOPT: move every staged summary AND analysis into write-once compaction
            // state; state wins, duplicates discarded, so a fiber/boundary race is idempotent by
            // construction. Analyses freeze by ordinal exactly like summaries freeze by span range.
            def adopt(state: Context.CompactionState, staged: Staged): Context.CompactionState =
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

            // the boundary's exact need, gated: A_fresh over the adopted state, then the fill
            // set of the gate-selected assignment (adopt -> A_prep, invalidate -> A_fresh) restricted to
            // spans whose write-once slot is still empty. LLM-free (infos captured in the foreground).
            def boundaryNeed(ctx: Context, config: Config, infos: Chunk[Tool.internal.Info[?, ?, LLM]])(using Frame): Chunk[Span] =
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
                val occupied   = occupancy(ctx)
                val low        = config.effectiveLow
                val pressure   = if low <= 0 then 1.0 else occupied.toDouble / low.toDouble
                val aFresh     = cut(ctx, units, spans, scores, pressure, occupied, low, since, prevLevels)
                val aPrep      = projectedAssignment(ctx, units, spans, scores, config, since, prevLevels, driftCause = false)
                val source     = if validityGate(aPrep, aFresh, spans) then aPrep else aFresh
                val state      = ctx.compactionState
                fillNeed(spans, source).filter(sp => state.summaryOf(sp.start, sp.end).isEmpty)
            end boundaryNeed

            // Per-pass arming below the boundary. Reconciles the prepare cause and the
            // drift cause against their wanted state; both share the same single-flight run. A
            // fresh cross into an armed band with no run in flight forks the single-flight run; a pass
            // whose last cause cleared eagerly interrupts the in-flight run. Returns the possibly-seated
            // session so the seam threads it back through setSession.
            def armBelowBoundary(
                ai: AI,
                ctx: Context,
                config: Config,
                session: AISession,
                registry: Maybe[AtomicRef[Set[Fiber[Unit, Any]]]],
                driftArm: Boolean
            )(using Frame): AISession < (LLM & Async & Abort[AIGenException]) =
                ensurePreparation(session).map { (prep, session2) =>
                    val occupied     = occupancy(ctx)
                    val prepareArmed = occupied >= config.prepareLine && occupied < config.effectiveHigh
                    Tool.internal.infos.map { infos =>
                        updateArm(prep, ArmCause.Prepare, prepareArmed, ctx, config, infos, registry, driftCause = false).andThen {
                            updateArm(prep, ArmCause.Drift, driftArm, ctx, config, infos, registry, driftCause = true)
                        }.andThen(session2)
                    }
                }
            end armBelowBoundary

            // Reconciles one arming cause against its wanted state over the shared single-flight run. On
            // a fresh want it forks the run (or joins an in-flight one, latching the cause); on a cleared
            // want it drops the cause and interrupts only when no cause remains armed. A no-op when the
            // cause's wantedness is unchanged.
            def updateArm(
                prep: Preparation,
                cause: ArmCause,
                wanted: Boolean,
                ctx: Context,
                config: Config,
                infos: Chunk[Tool.internal.Info[?, ?, LLM]],
                registry: Maybe[AtomicRef[Set[Fiber[Unit, Any]]]],
                driftCause: Boolean
            )(using Frame): Unit < Async =
                prep.armed.get.map { current =>
                    if wanted && !current.contains(cause) then
                        prep.inFlight.get.map {
                            case Present(_) => prep.armed.getAndUpdate(_ + cause).unit
                            case Absent =>
                                forkPreparation(ctx, config, prep, infos, registry, driftCause)
                                    .andThen(prep.armed.getAndUpdate(_ + cause).unit)
                        }
                    else if !wanted && current.contains(cause) then
                        val remaining = current - cause
                        prep.armed.set(remaining).andThen {
                            if remaining.isEmpty then
                                prep.inFlight.getAndSet(Absent).map {
                                    case Present(f) => f.interrupt.andThen(deregister(registry, f))
                                    case Absent     => Kyo.unit
                                }
                            else Kyo.unit
                        }
                    else Kyo.unit
                }
            end updateArm

            // The boundary: (1) adopt staged -> ctx, (2) derive the exact need, (3) join the fiber for
            // that need, (4) adopt what the join wrote. Returns the ctx to render (with filled state) and
            // the possibly-seated session.
            def boundaryPrepare(
                ai: AI,
                ctx: Context,
                config: Config,
                session: AISession,
                registry: Maybe[AtomicRef[Set[Fiber[Unit, Any]]]]
            )(using Frame): (Context, AISession) < (LLM & Async & Abort[AIGenException]) =
                ensurePreparation(session).map { (prep, session2) =>
                    prep.staged.get.map { staged0 =>
                        val adopted0 = ctx.withCompaction(adopt(ctx.compactionState, staged0))
                        Tool.internal.infos.map { infos =>
                            val need = boundaryNeed(adopted0, config, infos)
                            joinPreparation(adopted0, config, prep, need, infos).map { filled =>
                                val adopted1 = adopted0.withCompaction(adopt(adopted0.compactionState, filled))
                                (adopted1, session2)
                            }
                        }
                    }
                }
            end boundaryPrepare

            // JOIN. Armed-and-finished (empty need) returns instantly (the invisible case);
            // a run still in flight is awaited (Fiber.get, Async suspension, never a thread block) then the
            // remainder topped up; no run in flight (the huge turn) starts the fills against the exact need
            // and joins them through the SAME fill code. A missing analysis is never a blocking need.
            def joinPreparation(
                ctx: Context,
                config: Config,
                prep: Preparation,
                need: Chunk[Span],
                infos: Chunk[Tool.internal.Info[?, ?, LLM]]
            )(using Frame): Staged < (Async & Abort[AIGenException]) =
                if need.isEmpty then prep.staged.get
                else
                    prep.inFlight.get.map {
                        case Present(fiber) => fiber.get.andThen(fillRemaining(ctx, config, prep, need, infos))
                        case Absent         => fillRemaining(ctx, config, prep, need, infos)
                    }
            end joinPreparation

            // Fills each still-empty span in `need` (write-once), degrading a failed fill to an absent
            // slot -> the substitute elision at render, never failing the user's generation. Runs in dependency
            // order (oldest first) so the degraded route feeds earlier summaries to later fills.
            def fillRemaining(
                ctx: Context,
                config: Config,
                prep: Preparation,
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
            end fillRemaining

            // The single-flight fiber body: LLM-free wrt the foreground handler.
            // Recomputes the need-shaped fill set over the SNAPSHOT (raw/config/compaction state in ctx,
            // tool infos captured) and fills each summary-level span, staging results write-once
            // INCREMENTALLY as each completes so a partial run is still useful. Every failure recovered
            // here so no auxiliary failure ever escapes the fiber.
            def preparationRun(
                ctx: Context,
                config: Config,
                prep: Preparation,
                infos: Chunk[Tool.internal.Info[?, ?, LLM]],
                driftCause: Boolean
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
                val aPrep      = projectedAssignment(ctx, units, spans, scores, config, since, prevLevels, driftCause)
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
            // the seam row); stores the handle in the per-session cell and the run-level registry (swept
            // by LLM.run's Sync.ensure). LLM-free (infos already captured). The fiber self-deregisters on
            // completion (onComplete), so the registry holds only live handles and stays bounded across
            // the many arming events of one LLM.run; interruptAll no-ops on a completed handle regardless.
            def forkPreparation(
                ctx: Context,
                config: Config,
                prep: Preparation,
                infos: Chunk[Tool.internal.Info[?, ?, LLM]],
                registry: Maybe[AtomicRef[Set[Fiber[Unit, Any]]]],
                driftCause: Boolean
            )(using Frame): Unit < Async =
                Fiber.initUnscoped(preparationRun(ctx, config, prep, infos, driftCause)).map { fiber =>
                    prep.inFlight.set(Present(fiber))
                        .andThen(register(registry, fiber))
                        .andThen(fiber.onComplete(_ => deregister(registry, fiber)))
                }

            def register(registry: Maybe[AtomicRef[Set[Fiber[Unit, Any]]]], fiber: Fiber[Unit, Any])(using Frame): Unit < Sync =
                registry match
                    case Present(r) => r.getAndUpdate(_ + fiber).unit
                    case Absent     => Kyo.unit

            def deregister(registry: Maybe[AtomicRef[Set[Fiber[Unit, Any]]]], fiber: Fiber[Unit, Any])(using Frame): Unit < Sync =
                registry match
                    case Present(r) => r.getAndUpdate(_ - fiber).unit
                    case Absent     => Kyo.unit

            // summarizer knob -> fill config: Present pins the fill model; Absent
            // selects the warm route with provider.small as the degraded fallback. The degraded/pinned
            // route runs here (the warm prompt-cache route is future work), so
            // Absent resolves to provider.small.
            def resolveFillConfig(config: Config): Config =
                val base = config.compaction.summarizer match
                    case Present(cfg) => cfg
                    case Absent       => config.provider.small
                // the fill caps its summary output at summaryOutputCap so summary size stays in the demotion
                // arithmetic's output-cap class and a demoted summary never inflates the served view.
                base.maxTokens(summaryOutputCap)
            end resolveFillConfig

            // the degraded packing rule: the summarizer has its own (smaller) window, so the
            // input is BOUNDED and relevance-selected, never the whole history: the raw span, a
            // size-bounded set of earlier prior-span summaries (priorSummaryBudget, recency-first), and the
            // task anchor (system head + first user turn). The instruction asks for labeled sections, most
            // load-bearing first, the one thing terse asks of the fill.
            def buildFillContext(sp: Span, spans: Chunk[Span], ctx: Context, staged: Staged)(using Frame): Context =
                val raw      = ctx.raw
                val anchor   = raw.take(1) ++ raw.filter(_.role == Role.User).take(1)
                val spanMsgs = raw.slice(sp.start, sp.end)
                val priors =
                    spans.filter(_.end <= sp.start).reverse.foldLeft(Chunk.empty[Message]) { (acc, p) =>
                        if acc.size >= priorSummaryBudget then acc
                        else
                            staged.summaryOf(SpanKey(p.start, p.end)) match
                                case Present(b) => acc.append(SystemMessage(b))
                                case Absent     => acc
                    }
                val instruction: Message = SystemMessage(
                    "Summarize the following span of a conversation into labeled sections, most load-bearing " +
                        "first. Preserve identifiers, decisions, and unresolved threads; omit pleasantries."
                )
                val body = Chunk(instruction) ++ anchor ++ priors ++ spanMsgs
                Context(body, body)
            end buildFillContext

            // A single degraded-route fill. Runs a self-contained nested LLM.run over the
            // snapshot: the wire entry (Completion.apply) carries LLM, so the nested run discharges it,
            // coupling to NO foreground state (the LLM-free property :1320). A transport failure maps
            // to AITransportException; the caller recovers it to an absent artifact.
            def runFill(sp: Span, spans: Chunk[Span], ctx: Context, config: Config, prep: Preparation)(using
                Frame
            ): String < (Async & Abort[AIGenException]) =
                val fillConfig = resolveFillConfig(config)
                prep.staged.get.map { staged =>
                    val fillCtx = buildFillContext(sp, spans, ctx, staged)
                    LLM.run(fillConfig) {
                        Abort.recover[HttpException](e => Abort.fail(AITransportException(e))) {
                            fillConfig.provider.completion(fillConfig, fillCtx, Chunk.empty, Absent)
                        }
                    }.map { reply =>
                        reply.messages.headMaybe match
                            case Present(m) => m.content
                            case Absent     => Abort.fail(AIDecodeException("empty fill reply"))
                    }
                }
            end runFill

            // A marker-grade one-line descriptor of a region: the region's raw content
            // flattened to one line and bounded. Names content without carrying it.
            def descriptorLine(u: Region, raw: Chunk[Message]): String =
                val c = unitContent(u, raw).replace('\n', ' ').trim
                if c.length <= 80 then c else c.take(80)

            // the analysis input: the SERVED VIEW (the warm bytes the foreground request carried,
            // ctx.compacted) plus a mechanical instruction suffix naming the low-water ordinal, the regions
            // to analyze, and a compact region index (each REACHABLE region's ordinal beside its
            // marker-grade descriptor). The reach is summary-or-verbatim only: a pointer-level region (its
            // descriptor names content without carrying it) is out of the analysis's reach by design, so it
            // is excluded from the index and can never be a relation target. The suffix adds not one byte to
            // the view the foreground model sees. It asks for the typed Analysis JSON.
            def buildAnalysisContext(ctx: Context, pending: Chunk[Region], config: Config, lowWater: Int, reachable: Set[Int])(using
                Frame
            ): Context =
                val units   = group(ctx.raw)
                val closed  = units.filter(u => reachable.contains(u.id)).sortBy(_.id)
                val index   = closed.map(u => s"${u.id}: ${descriptorLine(u, ctx.raw)}").mkString("\n")
                val targets = pending.map(_.id).mkString(", ")
                val instruction: Message = SystemMessage(
                    "Analyze how each listed region depends on, relates to, or supersedes an EARLIER region " +
                        "in this conversation. Emit ONLY a JSON object of shape " +
                        "{\"regions\":[{\"ordinal\":<int>,\"relations\":[{\"target\":<int>,\"kind\":\"DependsOn|Relates|Supersedes\"}]}]}. " +
                        s"Every target MUST be an earlier ordinal (target < ordinal). Low-water ordinal: $lowWater. " +
                        s"Analyze exactly these region ordinals: $targets. Region index:\n$index"
                )
                val body = ctx.compacted.append(instruction)
                Context(body, body)
            end buildAnalysisContext

            // the five load-bearing properties, enforced by DISCARDING, never throwing. Decode the
            // typed Analysis over model-controlled output; a whole-batch decode failure (malformed JSON,
            // unknown RelationKind discriminator) yields no analyses. Per member: drop a member whose
            // ordinal is not a closed region in the suffix index; keep only relations that are backward-only
            // (target < ordinal) AND name an in-index target AND carry a known kind; cap survivors at
            // relationCap in emission order. The member survives with its first cap-many valid relations, or
            // with none, never as a throw.
            def parseAnalysis(text: String, validOrdinals: Set[Int])(using Frame): Chunk[RegionAnalysis] =
                Json.decode[Analysis](text) match
                    case Result.Success(a) =>
                        a.regions.collect {
                            case ra if validOrdinals.contains(ra.ordinal) =>
                                val kept =
                                    ra.relations.filter(r => r.target < ra.ordinal && validOrdinals.contains(r.target)).take(relationCap)
                                RegionAnalysis(ra.ordinal, kept)
                        }
                    case _ => Chunk.empty
            end parseAnalysis

            // the analysis pass: ONE typed generation on the conversation's OWN model (the main
            // config), reading the served view warm (transparent prompt caching is
            // future work; the pass is functional whether or not the completion impl caches). Runs a
            // self-contained nested LLM.run over the snapshot so it never couples to the foreground
            // handler-threaded LLM.State (LLM-free), decodes the typed Analysis, and stages each
            // surviving region write-once by ordinal. EVERY failure is recovered here: a failed analysis
            // leaves its regions unanalyzed, the graph runs on structural edges, and gen never fails;
            // nothing ever waits for it (the boundary join is for fills only).
            def runAnalysis(ctx: Context, pending: Chunk[Region], config: Config, prep: Preparation, reachable: Set[Int])(using
                Frame
            ): Unit < Async =
                if pending.isEmpty then Kyo.unit
                else
                    // the valid target set is the REACHABLE (summary-or-verbatim) region set: a relation
                    // targeting a pointer-level region is dropped by parseAnalysis, so a semantic edge can
                    // never lift pointer-level liveness (the coldest history stays out of reach).
                    val valid       = reachable
                    val lowWater    = pending.headMaybe.map(_.id).getOrElse(-1)
                    val analysisCtx = buildAnalysisContext(ctx, pending, config, lowWater, reachable)
                    LLM.run(config) {
                        Abort.recover[HttpException](e => Abort.fail(AITransportException(e))) {
                            config.provider.completion(config, analysisCtx, Chunk.empty, Absent)
                        }
                    }.map { reply =>
                        parseAnalysis(reply.messages.headMaybe.map(_.content).getOrElse(""), valid)
                    }.handle(Abort.run[AIGenException]).map {
                        case Result.Success(regions) =>
                            Kyo.foreachDiscard(regions)(ra => prep.staged.getAndUpdate(_.withAnalysis(ra.ordinal, ra)).unit)
                        case _ => Kyo.unit
                    }
                end if
            end runAnalysis

            // the analysis's reach: the closed, non-tail regions that are summary-or-verbatim at the
            // projected boundary (levels = A_prep). A region a demoted span projects to Level.Pointer is out
            // of reach (its descriptor names content without carrying it), so it is excluded from both the
            // region index and the valid target set. Pure over the projected assignment the caller holds.
            def analysisReach(units: Chunk[Region], spans: Chunk[Span], levels: Dict[Int, Level], tail: Set[Int]): Set[Int] =
                val pointerRegions = spans.flatMap { sp =>
                    levels.get(sp.start) match
                        case Present(Level.Pointer) => sp.regionIds.toList
                        case _                      => Nil
                }.toSet
                units.filterNot(u => tail.contains(u.id) || pointerRegions.contains(u.id)).map(_.id).toSet
            end analysisReach

            // A pinned unit rendered verbatim, with the role-2 within-verbatim elision applied when its own
            // content exceeds the generous budget; a smaller unit renders unchanged. The stamp is dropped
            // on elision so the demotion arithmetic and the hard-limit check price the unit at its elided size.
            def keepVerbatim(m: Message)(using Frame): Message =
                if m.content.length <= generousElisionChars then m
                else
                    val elided = elideMessage(m, generousElisionChars)
                    stamp(elided, TokenStamp("elided", offlineEstimate(elided)))

            // ---- project: build the view. A summary/terse span renders ONE synthetic marker (span grain);
            // a pointer span renders one marker per member region (region grain); a pinned span or a
            // tail-band region renders verbatim. A demoted marker carries Present(origin) so the next
            // boundary recognizes it without parsing marker text. demotions keys are span.start.
            def project(
                raw: Chunk[Message],
                units: Chunk[Region],
                spans: Chunk[Span],
                demotions: Dict[Int, Level],
                since: Int,
                prevLevels: Dict[Int, Context.Origin],
                state: Context.CompactionState = Context.CompactionState(),
                keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
            )(using Frame): Chunk[Message] =
                val byRegionId = units.map(u => u.id -> u).toMap
                val idxToRegion =
                    units.foldLeft(Map.empty[Int, Int])((m, u) => u.indices.foldLeft(m)((mm, idx) => mm.updated(idx, u.id)))
                val spanFor = spans.flatMap { sp =>
                    demotions.get(sp.start) match
                        case Present(level) => sp.regionIds.toList.map(rid => rid -> (sp, level))
                        case Absent         => Nil
                }.toMap
                // conditional clearing: a recall exchange whose target region is reinstated verbatim at
                // this boundary (not a member of any demoted span) reproduces content the view now carries in
                // place, so it is dropped; under pressure that keeps the target demoted the tail copy stays.
                val cleared = reinstatedRecallIndices(raw, spanFor.keySet, state)
                // Role 2 elision: a pinned unit kept verbatim whose content alone exceeds the generous
                // budget is rendered exact-surface elided (head+tail), a within-verbatim treatment priced at
                // its elided size. Deterministic over frozen raw, so it re-renders byte-identically each boundary.
                val oversized = raw.exists(_.content.length > generousElisionChars)
                if demotions.isEmpty && cleared.isEmpty && !oversized then raw
                else
                    val (out, _) =
                        raw.zipWithIndex.foldLeft((Chunk.empty[Message], Set.empty[String])) {
                            case ((acc, emitted), (m, i)) =>
                                val regionId = idxToRegion.getOrElse(i, i)
                                spanFor.get(regionId) match
                                    // A forgotten retention band's tombstone: an empty raw slot carrying a
                                    // band Origin, kept for ordinal stability, never emitted; the band head
                                    // (its non-empty coarse text) renders verbatim as the one band marker
                                    // for the run.
                                    case None if m.content.isEmpty && m.origin.isDefined => (acc, emitted)
                                    case None =>
                                        if cleared.contains(i) then (acc, emitted)
                                        else (acc.append(keepVerbatim(m)), emitted)
                                    case Some((sp, level)) =>
                                        level match
                                            case Level.Pointer =>
                                                val key = s"p$regionId"
                                                if emitted.contains(key) then (acc, emitted)
                                                else
                                                    val region = byRegionId.getOrElse(regionId, Region(regionId, Chunk(i), false, 0))
                                                    (acc.append(pointerMarker(region, raw, since, prevLevels, keys)), emitted + key)
                                                end if
                                            case Level.Summary | Level.Terse =>
                                                val key = s"s${sp.start}"
                                                if emitted.contains(key) then (acc, emitted)
                                                else
                                                    (
                                                        acc.append(summaryMarker(sp, raw, units, level, since, prevLevels, state, keys)),
                                                        emitted + key
                                                    )
                                                end if
                                            case Level.Verbatim => (acc.append(keepVerbatim(m)), emitted)
                                end match
                        }
                    out
                end if
            end project

            // the recall exchange indices to clear: for each tail recall exchange (an
            // assistant `recall` call fused with its answering tool result) whose target region was recalled
            // AND is reinstated verbatim (absent from the demoted-region set) at this boundary, the call and
            // its result index. The recall RECORD lives in state and is untouched here, so clearing the view
            // copy never drops the decaying liveness seed. Under pressure that keeps the target demoted,
            // its id is in demotedRegionIds and the exchange is retained.
            def reinstatedRecallIndices(
                raw: Chunk[Message],
                demotedRegionIds: Set[Int],
                state: Context.CompactionState
            )(using Frame): Set[Int] =
                val recalled = state.recalls.map(_.region).toSet
                if recalled.isEmpty then Set.empty
                else
                    val toolIdxByCallId = raw.zipWithIndex.foldLeft(Dict.empty[String, Int]) {
                        case (m, (ToolMessage(cid, _, _, _), j)) => m.update(cid.id, j)
                        case (m, _)                              => m
                    }
                    raw.zipWithIndex.foldLeft(Set.empty[Int]) {
                        case (acc, (msg, i)) =>
                            msg match
                                case AssistantMessage(_, calls, _, _) =>
                                    val recallCalls = calls.filter(_.function == "recall")
                                    val target = recallCalls.foldLeft(Absent: Maybe[Int]) { (f, c) =>
                                        f match
                                            case Present(_) => f
                                            case Absent     => recallArgId(c.arguments)
                                    }
                                    target match
                                        case Present(r) if recalled.contains(r) && !demotedRegionIds.contains(r) =>
                                            recallCalls.foldLeft(acc + i) { (s, c) =>
                                                toolIdxByCallId.get(c.id.id) match
                                                    case Present(j) => s + j
                                                    case Absent     => s
                                            }
                                        case _ => acc
                                    end match
                                case _ => acc
                    }
                end if
            end reinstatedRecallIndices

            // A span's summary/terse marker: the mechanical descriptor plus the write-once summary
            // bytes (Summary) or a fixed-budget prefix of them (Terse). With no fill route, an empty
            // slot renders the FIXED-SIZE substitute elision (role 1); the cut never assigns Terse to a
            // blob-less span, so a Terse marker always has bytes.
            def summaryMarker(
                sp: Span,
                raw: Chunk[Message],
                units: Chunk[Region],
                level: Level,
                since: Int,
                prevLevels: Dict[Int, Context.Origin],
                state: Context.CompactionState,
                keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
            )(using Frame): Message =
                val origin = Present(spanOrigin(sp, since, prevLevels))
                val descr  = spanDescriptor(sp, raw, units, keys)
                val body = state.summaryOf(sp.start, sp.end) match
                    case Present(bytes) => s"$descr\n${if level == Level.Terse then tersePrefix(bytes) else bytes}"
                    case Absent         =>
                        // No fill route: render the fixed-size substitute elision, but
                        // never inflate. A demotion is a size reduction; when the elided substitute would not
                        // render smaller than the span's own verbatim content, fall back to the degenerate note
                        // alone (recall restores the exact bytes), so a demoted span is never larger than raw.
                        val content = spanContent(sp, raw)
                        val full    = s"$descr\n${substituteElision(content, substituteElisionChars)}"
                        if full.length < content.length then full
                        else s"$descr\n$substituteNote"
                SystemMessage(body, origin = origin)
            end summaryMarker

            // A region's pointer marker: the mechanical descriptor plus recall id, no content.
            // origin.since is PRESERVED across re-renders (prevLevels) so recall decay is not reset.
            def pointerMarker(
                region: Region,
                raw: Chunk[Message],
                since: Int,
                prevLevels: Dict[Int, Context.Origin],
                keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
            ): Message =
                val endExcl = region.indices.lastOption.map(_ + 1).getOrElse(region.id + 1)
                val since2  = prevLevels.get(region.id).map(_.since).getOrElse(since)
                SystemMessage(regionDescriptor(region, raw, keys), origin = Present(Context.Origin(region.id, endExcl, since2)))
            end pointerMarker

            def spanOrigin(sp: Span, since: Int, prevLevels: Dict[Int, Context.Origin]): Context.Origin =
                Context.Origin(sp.start, sp.end, prevLevels.get(sp.start).map(_.since).getOrElse(since))

            // Mechanical descriptors: the id range, the tool name and compaction key the
            // render already knows (the recall-decision signal), a bounded first-line snippet, the stamped
            // token count, and the recall id. Never model-generated text.
            def regionDescriptor(region: Region, raw: Chunk[Message], keys: Dict[Int, (String, Tool.Kind)] = Dict.empty): String =
                val tag = descriptorTag(toolName(region, raw), keys.get(region.id).map(_._1))
                s"[region ${region.id}:$tag ${firstLine(unitContent(region, raw))}, ${region.tokens} tokens; recall(${region.id}) restores verbatim]"

            def spanDescriptor(
                sp: Span,
                raw: Chunk[Message],
                units: Chunk[Region],
                keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
            ): String =
                val toks  = units.filter(u => sp.regionIds.contains(u.id)).foldLeft(0)((n, u) => n + u.tokens)
                val headU = units.filter(_.id == sp.start).headMaybe
                val tag   = descriptorTag(headU.flatMap(u => toolName(u, raw)), keys.get(sp.start).map(_._1))
                s"[regions ${sp.start}-${sp.end - 1}:$tag ${firstLine(spanContent(sp, raw))}, $toks tokens; recall(${sp.start}) restores verbatim]"
            end spanDescriptor

            // The tool name a region's exchange invoked: the first tool call's
            // function name in the region's assistant message, Absent for a call-less region. Pure over raw.
            def toolName(u: Region, raw: Chunk[Message]): Maybe[String] =
                u.indices.foldLeft(Absent: Maybe[String]) { (found, idx) =>
                    found match
                        case Present(_) => found
                        case Absent =>
                            raw(idx) match
                                case AssistantMessage(_, calls, _, _) => calls.headMaybe.map(_.function)
                                case _                                => Absent
                }

            // The descriptor's tool-name + compaction-key tag: both are recall-decision
            // signal, each omitted when Absent (a keyless or call-less region carries neither).
            def descriptorTag(tool: Maybe[String], key: Maybe[String]): String =
                val t = tool.map(n => s" $n").getOrElse("")
                val k = key.map(kk => s", key $kk").getOrElse("")
                s"$t$k"
            end descriptorTag

            def firstLine(s: String): String =
                val line = s.takeWhile(_ != '\n')
                if line.length <= 80 then line else line.take(77) + "..."

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
                            "Returns the covered messages verbatim, each prefixed with its role, as a fresh tool result.",
                    kind = Tool.Kind.Read
                ) { (arg: Recall) =>
                    ai.context.map { ctx =>
                        val range = ctx.compacted.filter(_.origin.exists(_.start == arg.id)).headMaybe.flatMap(_.origin)
                        val restored = range match
                            case Present(o) =>
                                // A forgotten retention band fails cleanly: raw's origin at o.start is
                                // Present only for a band member, so a recall of forgotten content is
                                // refused rather than returning the coarse band head or an empty tombstone.
                                if o.start >= 0 && o.start < ctx.raw.size && ctx.raw(o.start).origin.isDefined then
                                    s"region ${arg.id} was forgotten past the retention horizon and is no longer recallable"
                                else roleTagged(ctx.raw.drop(o.start).take(o.end - o.start))
                            case Absent =>
                                group(ctx.raw).filter(_.id == arg.id).headMaybe match
                                    case Present(u) => roleTagged(u.indices.map(ctx.raw.apply))
                                    case Absent     => s"no such recallable region: ${arg.id}"
                        ai.setContext(ctx.withCompaction(ctx.compactionState.withRecall(arg.id))).andThen(restored)
                    }
                }
            end recallTool

            // Role-tagged, byte-exact restoration: each covered message prefixed with its role, never
            // a role-flattened join.
            def roleTagged(msgs: Chunk[Message]): String =
                msgs.map(m => s"${m.role.name}: ${m.content}").mkString("\n")

            // ---- pure derivation helpers ----
            // viewTokens reads STORED stamps: the demotion loop and hard-limit check make zero requests.
            def viewTokens(view: Chunk[Message]): Int =
                view.foldLeft(0)((n, m) => n + stampedTokens(m))

            def unitContent(u: Region, raw: Chunk[Message]): String =
                u.indices.map { idx =>
                    raw(idx) match
                        case AssistantMessage(c, calls, _, _) =>
                            if calls.isEmpty then c else c + " " + calls.map(_.arguments).mkString(" ")
                        case m => m.content
                }.mkString(" ")

            // Structural identifier extraction: backticked / path-dotted-snake-camel / digit-bearing,
            // len >= 3. Interior signal only: structural punctuation or a digit anywhere, or an
            // uppercase letter at an INTERIOR position (never the token's first char), so a sentence-initial
            // capitalized word like "The" mints no identifier while camelCase / HTTPServer / value42 /
            // Config.timeout do. No stop-word list, no bare words, no pattern matching: a hand character-class
            // scan expressed as a @tailrec walk with a start-index sentinel (no bare while, no cross-scope var).
            def extractTokens(content: String): Chunk[String] =
                val out = Chunk.newBuilder[String]
                def emit(tok0: String): Unit =
                    val backticked = tok0.length >= 2 && tok0.head == '`' && tok0.last == '`'
                    val tok        = if backticked then tok0.substring(1, tok0.length - 1) else tok0
                    val punctOrDigit = tok.exists(c =>
                        c == '/' || c == '.' || c == '_' || c == ':' || c == '-' || c.isDigit
                    )
                    val interiorUpper = tok.drop(1).exists(_.isUpper)
                    val structured    = backticked || punctOrDigit || interiorUpper
                    if tok.length >= 3 && structured then out += tok
                end emit
                @tailrec def loop(i: Int, start: Int): Unit =
                    if i >= content.length then
                        if start >= 0 then emit(content.substring(start))
                    else
                        val c = content.charAt(i)
                        val isTokenChar =
                            c == '`' || c == '/' || c == '.' || c == '_' || c == ':' || c == '-' || c.isLetterOrDigit
                        if isTokenChar then loop(i + 1, if start >= 0 then start else i)
                        else
                            if start >= 0 then emit(content.substring(start, i))
                            loop(i + 1, -1)
                        end if
                loop(0, -1)
                out.result()
            end extractTokens

            // The recall id from an object-shaped tool argument, decoded through the SAME typed Recall
            // schema the tool registers (no regex): a pure typed decode for the refetch count.
            def recallArgId(arguments: String)(using Frame): Maybe[Int] =
                Json.decode[Recall](arguments) match
                    case Result.Success(r) => Present(r.id)
                    case _                 => Absent

            // Line-aware, code-point-safe elision: keep the exact head and tail around an elision
            // mark, never severing a surrogate pair or splitting mid-line. Returns the content unchanged
            // when it already fits the budget (char units).
            def elide(content: String, budget: Int): String =
                if content.length <= budget then content
                else
                    val half = math.max(1, budget / 2)
                    s"${safeCut(content, half, fromEnd = false)}\n...[elided]...\n${safeCut(content, half, fromEnd = true)}"

            // A code-point + line safe cut of about `n` chars from the start (fromEnd=false) or end (true):
            // snaps to the nearest line boundary within the budget and never lands inside a surrogate pair.
            def safeCut(s: String, n: Int, fromEnd: Boolean): String =
                if fromEnd then
                    val start = adjust(s, math.max(0, s.length - n))
                    val nl    = s.indexOf('\n', start)
                    s.substring(if nl >= 0 then nl + 1 else start)
                else
                    val end = adjust(s, math.min(s.length, n))
                    val nl  = s.lastIndexOf('\n', end)
                    s.substring(0, if nl > 0 then nl else end)

            // java.lang.Character.isLowSurrogate is a pure char-classification predicate (no kyo primitive
            // covers surrogate detection); stepping off a low surrogate keeps the cut code-point-safe.
            def adjust(s: String, i: Int): Int =
                if i > 0 && i < s.length && Character.isLowSurrogate(s.charAt(i)) then i + 1 else i

            // The terse render: a fixed-budget prefix of the write-once summary bytes, code-point-safe.
            def tersePrefix(bytes: String): String =
                if bytes.length <= tersePrefixChars then bytes else safeCut(bytes, tersePrefixChars, fromEnd = false)

            // The degenerate-summary note: the fixed marker announcing an absent fill route.
            val substituteNote: String = "[summary unavailable: no fill route reachable; recall restores verbatim]"

            // The fixed-size substitute elision at the summary level: a deterministic function
            // of the frozen span, so it re-renders byte-identically without persistence.
            def substituteElision(content: String, budget: Int): String =
                s"${elide(content, budget)}\n$substituteNote"

            def repoint(id: Int, superseded: Dict[Int, Int]): Int =
                @tailrec def loop(cur: Int): Int =
                    superseded.get(cur) match
                        case Present(next) if next != cur => loop(next)
                        case _                            => cur
                loop(id)
            end repoint

            def isSystemHead(u: Region, raw: Chunk[Message]): Boolean =
                u.indices.headOption.exists(idx => raw(idx).isInstanceOf[SystemMessage])

            def hasUser(u: Region, raw: Chunk[Message]): Boolean =
                u.indices.exists(idx => raw(idx).isInstanceOf[UserMessage])

        end Default
    end internal
end Compactor
