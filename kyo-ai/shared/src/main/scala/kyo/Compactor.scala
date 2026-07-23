package kyo

import kyo.Schema
import kyo.ai.*
import kyo.ai.Context.*
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
      * (`Config.Compaction`, §6).
      */
    def init: Compactor[Any] = Default

    /** The pass-through off switch (§6): always serves the raw context unchanged, so the session
      * runs with no compaction and no overflow protection and the caller owns the context bound.
      */
    def none: Compactor[Any] = Disabled

    private object Disabled extends Compactor[Any]:
        def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
            Kyo.lift(ctx.raw)
    end Disabled

    private[kyo] object internal:

        // --- structural + scoring constants (replay-tunable, §10.4; provisional seeds owner-confirm) ---
        val adjacencyWeight: Double     = 1.0   // EdgeKind.Adjacency (§5c)
        val referenceWeight: Double     = 3.0   // EdgeKind.Reference, damped by document frequency (§5c)
        val dependencyWeight: Double    = 3.0   // EdgeKind.Dependency, the analysis DependsOn edge (§5c, P4)
        val relatednessWeight: Double   = 0.5   // EdgeKind.Relatedness, the analysis Relates edge (§5c, P4)
        val restartWeight: Double       = 0.15  // PPR restart probability
        val pprIterations: Int          = 20    // stability bound; the demotion decision converges by ~12 (§5c)
        val supersessionPenalty: Double = 0.2   // provisional, replay-tunable, v4 §5c, owner-confirm
        val seedObjective: Double       = 0.35  // last user turn (§5c)
        val seedTask: Double            = 0.20  // first user turn / task origin
        val seedTail: Double            = 0.25  // geometrically decayed recent tail
        val seedUnresolved: Double      = 0.15  // unresolved tool calls
        val seedSystem: Double          = 0.05  // system head
        val seedTailTurns: Int          = 10    // recent regions carrying the geometric tail seed (§5c)
        val seedTailTokens: Int         = 12000 // token bound on the geometric tail seed (§5c)
        val recallSeedWeight: Double    = 0.20  // provisional, replay-tunable, v4 §5e, owner-confirm
        val recallDecay: Double         = 0.5   // provisional, replay-tunable, v4 §5e, owner-confirm
        // keep(p) = keepBase + keepScaling * (p - 1), floored at keepBase (§5d)
        val keepBase: Double    = 0.03 // provisional, replay-tunable, v4 §5d, owner-confirm
        val keepScaling: Double = 0.06 // provisional, replay-tunable, v4 §5d, owner-confirm
        // span formation (§5b) and the tail band
        val spanCapTokens: Int       = 4000 // provisional, replay-tunable, v4 §5b, owner-confirm
        val spanCapRegions: Int      = 8    // provisional, replay-tunable, v4 §5b, owner-confirm
        val tailBandFraction: Double = 0.25 // provisional, replay-tunable, v4 §5b, owner-confirm
        // presentation byte budgets (§5d); char units, biased over the token size class
        val tersePrefixChars: Int       = 200   // ~50-token terse prefix (§5d); provisional owner-confirm
        val substituteElisionChars: Int = 800   // fixed-size summary-level substitute (§5d role 1); provisional owner-confirm
        val generousElisionChars: Int   = 24000 // exact-surface pinned-oversized (§5d role 2); provisional owner-confirm
        val imageSurchargeChars: Int    = 6000  // ~2000-token vision surcharge, char units (§5a)
        // drift (§5g), seated for P5
        val driftRefractory: Int = 4 // provisional, replay-tunable, v4 §5g, owner-confirm
        // raw-retention eviction hysteresis (§10.5), seated for P6
        val rawHighWatermark: Double = 0.9 // provisional, replay-tunable, v4 §10.5, owner-confirm
        val rawLowWatermark: Double  = 0.5 // provisional, replay-tunable, v4 §10.5, owner-confirm

        // The four detail states (§5d). Verbatim is not "demoted": it never enters a demotions map,
        // so project only ever renders the three demoted states. Summary is span grain, Pointer is
        // region grain, Terse is a descent-only prefix of the summary bytes.
        enum Level derives CanEqual:
            case Verbatim, Summary, Terse, Pointer

        // The atomic REGION (§5b): an assistant message fused with its answering tool results, held as
        // fused INDICES into raw, its unresolved flag, and its apportioned stamped token size.
        final case class Region(id: Int, indices: Chunk[Int], unresolved: Boolean, tokens: Int)

        final case class Building(id: Int, indices: Chunk[Int], open: Set[String])

        // A formed SPAN (§5b): a contiguous run of regions, the summary level's grain, identified by
        // its raw ordinal range [start, end). Identity is a deterministic model-free function of
        // frozen content (SPAN-FREEZING i); the write-once summary slot is keyed by (start, end).
        final case class Span(start: Int, end: Int, regionIds: Chunk[Int])

        enum EdgeKind derives CanEqual:
            case Adjacency, Reference, Dependency, Relatedness

        final case class Edge(target: Int, kind: EdgeKind, weight: Double)

        final case class Graph(edges: Dict[Int, Chunk[Edge]]):
            def isEmpty: Boolean = edges.isEmpty
        object Graph:
            val empty: Graph = Graph(Dict.empty)

        /** The recall tool's typed input, object-wrapped so the wire schema is
          * `{"id":{"type":"integer"}}` (providers reject a bare integer parameter schema).
          */
        final case class Recall(id: Int) derives Schema, CanEqual

        // --- usage-anchored occupancy, apportionment, and stamp reads (§5a) ---
        // The demotion loop reads STORED stamps and makes zero requests; counting narrows to
        // apportionment (stamping, below) and the offline suffix/bootstrap estimate.

        // The stored apportioned count for a message. A message with no stamp yet (a fresh synthetic
        // marker, or a bootstrap message before the first apportionment) falls back to the offline estimate.
        def stampedTokens(msg: Message): Int =
            msg.tokens match
                case Present(stamp) => stamp.count
                case Absent         => offlineEstimate(msg)

        // The offline conservative char-based estimate (bootstrap + suffix delta, §5a): ~1 token per 3
        // chars plus a per-message envelope, biased to over-count so occupancy never under-reads. A
        // user-message image adds a fixed conservative surcharge.
        def offlineEstimate(msg: Message): Int =
            val chars = msg match
                case AssistantMessage(c, calls, _, _) => c.length + calls.foldLeft(0)((n, x) => n + x.arguments.length)
                case UserMessage(c, image, _, _)      => c.length + (if image.isDefined then imageSurchargeChars else 0)
                case m                                => m.content.length
            (chars + 2) / 3 + 4
        end offlineEstimate

        // Usage-anchored occupancy (§5a): the last provider-reported request total plus the offline
        // estimate of the messages appended to the served view since that anchor; bootstrap / no-usage
        // falls wholly to the offline estimate over the served view. maxOutputTokens is NOT part of this
        // (it is counted once on the hard-limit side, §7).
        def occupancy(ctx: Context): Int =
            val state = ctx.compactionState
            state.lastUsage match
                case Present(total) =>
                    val suffix = ctx.compacted.drop(state.lastUsageRawSize).foldLeft(0)((n, m) => n + offlineEstimate(m))
                    total + suffix
                case Absent =>
                    ctx.compacted.foldLeft(0)((n, m) => n + stampedTokens(m))
            end match
        end occupancy

        // The keep threshold at pressure p (§5d): monotone increasing in pressure, evaluated never
        // below its base (the keep floor, §5d/§5g), where callers pass max(pressure, 1).
        def keep(pressure: Double): Double = keepBase + keepScaling * math.max(0.0, pressure - 1.0)

        // Apportionment (§5a): count each served-view message with the ACTIVE tokenizer, then normalize
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

        // The active tokenizer and its vocabulary id (§5a): the user override when set, else the offline
        // tiktoken default (o200k for the openai-compatible tail). The id tags every apportioned stamp so
        // counts never cross vocabularies; a provider switch changes the id and the next anchor re-stamps.
        def activeTokenizer(config: Config): (Tokenizer, String) =
            config.tokenizer match
                case Present(t) => (t, s"${config.provider.name}:user")
                case Absent     => (Tokenizer.tiktoken(Tokenizer.Encoding.O200kBase), "o200k")

        // The fused usage re-anchor (§5a), the ONE step every usage-consumption site runs so the anchor
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
                anchored.copy(raw = propagateStamps(anchored.raw, stamped))
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

        // ---------------------------------------------------------------------------------------
        // The single shipped default. render returns only the rebuilt compacted Chunk[Message]; raw
        // never appears in its signature. It reads the ACTIVE kyo.ai.Config live, makes ZERO model
        // calls, and forks NO fibers. The P2 boundary is synchronous: measure -> derive fresh ->
        // demote (the unified rule) -> project -> hard-limit backstop. The preparation fiber, the
        // analysis pass, the validity gate, and the drift trigger layer on in P3/P4/P5.
        // ---------------------------------------------------------------------------------------
        object Default extends Compactor[Any]:

            def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
                AI.config.map { config =>
                    val window   = config.modelMaxTokens
                    val low      = config.effectiveLow
                    val hard     = config.hardLimitTokens
                    val occupied = occupancy(ctx)
                    val pressure = if low <= 0 then 1.0 else occupied.toDouble / low.toDouble
                    val units    = group(ctx.raw)
                    val spans    = formSpans(units, ctx.raw, config)
                    superKeys(units, ctx.raw).map { keys =>
                        val superseded = supersession(units, keys)
                        val graph      = deriveGraph(units, ctx.raw, superseded)
                        val seed       = seedVector(units, ctx.raw, ctx.compactionState)
                        val scores     = score(units, graph, superseded, seed)
                        val prevLevels = demotedOrigins(ctx.compacted)
                        val since      = ctx.raw.size
                        val demotions  = cut(ctx, units, spans, scores, pressure, occupied, low, since, prevLevels)
                        val view       = project(ctx.raw, units, spans, demotions, since, prevLevels, ctx.compactionState, keys)
                        if viewTokens(view) <= hard then view
                        else
                            val forcedView = forced(ctx.raw, units, spans, scores, pressure, hard, since, prevLevels)
                            if viewTokens(forcedView) > hard then
                                Abort.fail(AIContextOverflowException(viewTokens(forcedView), window))
                            else forcedView
                        end if
                    }
                }
            end render

            override def tools(ai: AI)(using Frame): Chunk[Tool[LLM]] = Chunk(recallTool(ai))

            // ---- grouping (fuse assistant + answering tool messages into REGIONS over raw, §5b) ----
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
                Chunk.from(byId.values.toList.sortBy(_.id).map { b =>
                    val toks = b.indices.foldLeft(0)((n, idx) => n + stampedTokens(raw(idx)))
                    Region(b.id, b.indices, b.open.nonEmpty, toks)
                })
            end group

            // ---- span formation (SPAN-FREEZING i, §5b): partition the CLOSED prefix into spans by a
            // deterministic model-free rule. Splits at user-turn boundaries; closes a pending run early
            // when it would exceed the formation cap (spanCapTokens tokens or spanCapRegions regions); a
            // single over-cap region forms an oversized singleton span. The tail band is EXCLUDED
            // (SPAN-FREEZING iii): only regions aged into the closed prefix are eligible.
            def formSpans(units: Chunk[Region], raw: Chunk[Message], config: Config): Chunk[Span] =
                val ordered = units.toList.sortBy(_.id)
                val closed  = closedRegions(ordered, config)
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

            // The closed prefix (§5b, SPAN-FREEZING iii): regions outside the tail band
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
                Span(members.map(_.id).min, members.flatMap(_.indices.toList).max + 1, ids)

            // ---- key supersession (typed compactionKey via the Info closure, no cast) ----
            def superKeys(units: Chunk[Region], raw: Chunk[Message])(using Frame): Dict[Int, (String, Tool.Kind)] < LLM =
                Tool.internal.infos.map { infos =>
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
                }
            end superKeys

            def supersession(units: Chunk[Region], keys: Dict[Int, (String, Tool.Kind)]): Dict[Int, Int] =
                val (result, _) =
                    units.toList.sortBy(_.id).foldLeft((Dict.empty[Int, Int], Dict.empty[String, (Int, Tool.Kind)])) {
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

            // ---- graph: structural Adjacency + Reference edges (§5c); the analysis pass's Dependency and
            // Relatedness edges (P4) merge in via `analyzed`, empty until then. Reference and analyzed edges
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
                    // recall as a decaying seed (§5e): each recall record contributes to its region's seed
                    // entry, decaying geometrically per boundary since the recall. The record lives in state,
                    // never inferred from the view, so clearing the recall exchange never drops the signal.
                    val recallContribs = state.recalls.toList.map { r =>
                        (r.region, recallSeedWeight * math.pow(recallDecay, (state.boundaryCounter - r.boundaryStamp).toDouble))
                    }
                    val merged = (singleContribs ++ tailContribs ++ recallContribs).foldLeft(Map.empty[Int, Double]) {
                        case (m, (id, v)) => m.updated(id, m.getOrElse(id, 0.0) + v)
                    }
                    Dict.from(merged)
            end seedVector

            // ---- the seed's decayed tail set (§5c): the most recent regions carrying the geometric tail
            // seed. v4 has no separate roots-pin or co-pin machinery: the keep threshold plus span pinning
            // (§5d) subsume it, and unresolved-turn regions are excluded from spans (never demotable).
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

            // ---- region bookkeeping derived from compacted, no string parsing ----
            // A demoted unit/span is exactly one synthetic entry carrying Present(origin); origin.start is
            // the unit/span id, origin.since is the raw index at the boundary that demoted it. Promotion is
            // no longer a flag: recall is a decaying liveness seed (§5e), so a recalled region reinstates
            // through scoring, not a separate promotion set (the flag is deleted).
            def demotedOrigins(compacted: Chunk[Message]): Dict[Int, Context.Origin] =
                compacted.foldLeft(Dict.empty[Int, Context.Origin]) { (m, msg) =>
                    msg.origin match
                        case Present(o) => m.update(o.start, o)
                        case Absent     => m
                }

            // The coldest member liveness of a span (pass-2 ascending order) and its hottest member
            // (the pinning test): a span is demotable iff EVERY member is below the floored keep, i.e. its
            // hottest member is below keep (§5d span-demotion rule).
            def spanLiveness(sp: Span, scores: Dict[Int, Double]): Double =
                sp.regionIds.foldLeft(Double.MaxValue)((m, id) => math.min(m, scores.get(id).getOrElse(0.0)))
            def spanMaxLiveness(sp: Span, scores: Dict[Int, Double]): Double =
                sp.regionIds.foldLeft(0.0)((m, id) => math.max(m, scores.get(id).getOrElse(0.0)))

            // ---- the cut: the unified DEMOTION RULE (§5d), one rule for size-fired and drift-fired
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

            // ---- forced path (§5d, §7): a single giant append over the hard limit. Byte-for-byte the
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
                prevLevels: Dict[Int, Context.Origin]
            )(using Frame): Chunk[Message] =
                val ascending = spans.toList.sortBy(sp => spanLiveness(sp, scores))
                @tailrec def pointerAll(rem: List[Span], dem: Dict[Int, Level]): Dict[Int, Level] =
                    if viewTokens(project(raw, units, spans, dem, since, prevLevels)) <= hard then dem
                    else
                        rem match
                            case Nil        => dem
                            case sp :: rest => pointerAll(rest, dem.update(sp.start, Level.Pointer))
                val view = project(raw, units, spans, pointerAll(ascending, Dict.empty), since, prevLevels)
                if viewTokens(view) <= hard then view
                else elideOversizedTail(view, hard)
            end forced

            // Replace the single largest tail message with its generous exact-surface elision (§5d role 2)
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

            // A pinned unit rendered verbatim, with the role-2 within-verbatim elision applied when its own
            // content exceeds the generous budget (§5d); a smaller unit renders unchanged. The stamp is dropped
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
                val bySegId = units.toList.map(u => u.id -> u).toMap
                val idxToSeg =
                    units.toList.foldLeft(Map.empty[Int, Int])((m, u) => u.indices.foldLeft(m)((mm, idx) => mm.updated(idx, u.id)))
                val spanFor = spans.toList.flatMap { sp =>
                    demotions.get(sp.start) match
                        case Present(level) => sp.regionIds.toList.map(rid => rid -> (sp, level))
                        case Absent         => Nil
                }.toMap
                // §5e conditional clearing: a recall exchange whose target region is reinstated verbatim at
                // this boundary (not a member of any demoted span) reproduces content the view now carries in
                // place, so it is dropped; under pressure that keeps the target demoted the tail copy stays.
                val cleared = reinstatedRecallIndices(raw, spanFor.keySet, state)
                // Role 2 elision (§5d): a pinned unit kept verbatim whose content alone exceeds the generous
                // budget is rendered exact-surface elided (head+tail), a within-verbatim treatment priced at
                // its elided size. Deterministic over frozen raw, so it re-renders byte-identically each boundary.
                val oversized = raw.exists(_.content.length > generousElisionChars)
                if demotions.isEmpty && cleared.isEmpty && !oversized then raw
                else
                    val (out, _) =
                        raw.zipWithIndex.foldLeft((Chunk.empty[Message], Set.empty[String])) {
                            case ((acc, emitted), (m, i)) =>
                                val segId = idxToSeg.getOrElse(i, i)
                                spanFor.get(segId) match
                                    case None =>
                                        if cleared.contains(i) then (acc, emitted)
                                        else (acc.append(keepVerbatim(m)), emitted)
                                    case Some((sp, level)) =>
                                        level match
                                            case Level.Pointer =>
                                                val key = s"p$segId"
                                                if emitted.contains(key) then (acc, emitted)
                                                else
                                                    val seg = bySegId.getOrElse(segId, Region(segId, Chunk(i), false, 0))
                                                    (acc.append(pointerMarker(seg, raw, since, prevLevels, keys)), emitted + key)
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

            // §5e :1120-1126 the recall exchange indices to clear: for each tail recall exchange (an
            // assistant `recall` call fused with its answering tool result) whose target region was recalled
            // AND is reinstated verbatim (absent from the demoted-region set) at this boundary, the call and
            // its result index. The recall RECORD lives in state and is untouched here, so clearing the view
            // copy never drops the decaying liveness seed (§5e). Under pressure that keeps the target demoted,
            // its id is in demotedRegionIds and the exchange is retained.
            def reinstatedRecallIndices(
                raw: Chunk[Message],
                demotedRegionIds: Set[Int],
                state: Context.CompactionState
            )(using Frame): Set[Int] =
                val recalled = state.recalls.toList.map(_.region).toSet
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

            // A span's summary/terse marker (§5d): the mechanical descriptor plus the write-once summary
            // bytes (Summary) or a fixed-budget prefix of them (Terse). With no fill route (P2), an empty
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
                        // No fill route (§5d role 1, §7 no-fill): render the fixed-size substitute elision, but
                        // never inflate. A demotion is a size reduction; when the elided substitute would not
                        // render smaller than the span's own verbatim content, fall back to the degenerate note
                        // alone (recall restores the exact bytes), so a demoted span is never larger than raw.
                        val content = spanContent(sp, raw)
                        val full    = s"$descr\n${substituteElision(content, substituteElisionChars)}"
                        if full.length < content.length then full
                        else s"$descr\n$substituteNote"
                SystemMessage(body, origin = origin)
            end summaryMarker

            // A region's pointer marker (§5d): the mechanical descriptor plus recall id, no content.
            // origin.since is PRESERVED across re-renders (prevLevels) so recall decay is not reset.
            def pointerMarker(
                seg: Region,
                raw: Chunk[Message],
                since: Int,
                prevLevels: Dict[Int, Context.Origin],
                keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
            ): Message =
                val endExcl = seg.indices.lastOption.map(_ + 1).getOrElse(seg.id + 1)
                val since2  = prevLevels.get(seg.id).map(_.since).getOrElse(since)
                SystemMessage(regionDescriptor(seg, raw, keys), origin = Present(Context.Origin(seg.id, endExcl, since2)))
            end pointerMarker

            def spanOrigin(sp: Span, since: Int, prevLevels: Dict[Int, Context.Origin]): Context.Origin =
                Context.Origin(sp.start, sp.end, prevLevels.get(sp.start).map(_.since).getOrElse(since))

            // Mechanical descriptors (§5d :942-945): the id range, the tool name and compaction key the
            // render already knows (the recall-decision signal), a bounded first-line snippet, the stamped
            // token count, and the recall id. Never model-generated text.
            def regionDescriptor(seg: Region, raw: Chunk[Message], keys: Dict[Int, (String, Tool.Kind)] = Dict.empty): String =
                val tag = descriptorTag(toolName(seg, raw), keys.get(seg.id).map(_._1))
                s"[region ${seg.id}:$tag ${firstLine(unitContent(seg, raw))}, ${seg.tokens} tokens; recall(${seg.id}) restores verbatim]"

            def spanDescriptor(
                sp: Span,
                raw: Chunk[Message],
                units: Chunk[Region],
                keys: Dict[Int, (String, Tool.Kind)] = Dict.empty
            ): String =
                val toks  = units.toList.filter(u => sp.regionIds.toList.contains(u.id)).foldLeft(0)((n, u) => n + u.tokens)
                val headU = units.filter(_.id == sp.start).headMaybe
                val tag   = descriptorTag(headU.flatMap(u => toolName(u, raw)), keys.get(sp.start).map(_._1))
                s"[regions ${sp.start}-${sp.end - 1}:$tag ${firstLine(spanContent(sp, raw))}, $toks tokens; recall(${sp.start}) restores verbatim]"
            end spanDescriptor

            // The tool name a region's exchange invoked (§5d descriptor component): the first tool call's
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

            // The descriptor's tool-name + compaction-key tag (§5d :942-945): both are recall-decision
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

            // ---- recall (§5e): typed decode, instance-bound, ROLE-TAGGED, span/region grain via origin.
            // Records a decaying liveness seed so the recalled region reinstates through scoring (§5e), the
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
                            case Present(o) => roleTagged(ctx.raw.drop(o.start).take(o.end - o.start))
                            case Absent =>
                                group(ctx.raw).filter(_.id == arg.id).headMaybe match
                                    case Present(u) => roleTagged(u.indices.map(ctx.raw.apply))
                                    case Absent     => s"no such recallable region: ${arg.id}"
                        ai.setContext(ctx.withCompaction(ctx.compactionState.withRecall(arg.id))).andThen(restored)
                    }
                }
            end recallTool

            // Role-tagged, byte-exact restoration (§5e): each covered message prefixed with its role, never
            // a role-flattened join.
            def roleTagged(msgs: Chunk[Message]): String =
                msgs.map(m => s"${m.role.name}: ${m.content}").mkString("\n")

            // ---- pure derivation helpers ----
            // viewTokens reads STORED stamps (§5a): the demotion loop and hard-limit check make zero requests.
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
            // len >= 3. Interior signal only (design §5c): structural punctuation or a digit anywhere, or an
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

            // Line-aware, code-point-safe elision (§5d): keep the exact head and tail around an elision
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

            // The terse render (§5d): a fixed-budget prefix of the write-once summary bytes, code-point-safe.
            def tersePrefix(bytes: String): String =
                if bytes.length <= tersePrefixChars then bytes else safeCut(bytes, tersePrefixChars, fromEnd = false)

            // The degenerate-summary note (§5d role 1): the fixed marker announcing an absent fill route.
            val substituteNote: String = "[summary unavailable: no fill route reachable; recall restores verbatim]"

            // The fixed-size substitute elision at the summary level (§5d role 1): a deterministic function
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
