package kyo

import kyo.Schema
import kyo.ai.*
import kyo.ai.Context.*
import scala.annotation.tailrec

/** Automatic context compaction as a swappable `AI.Enablement`: keeps what the model SEES small
  * and high-signal while `Context.raw` stays the complete, immutable transcript.
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

    /** The stateless default compactor. Tuning is via `kyo.ai.Config`'s compaction fields. */
    def init: Compactor[Any] = Default

    /** A pure, deterministic token accountant. The seam and the default read the active one from
      * `kyo.ai.Config.tokenizer`.
      */
    trait Tokenizer:
        def count(message: Message): Int

    object Tokenizer:
        /** The conservative character-based estimator: biases toward OVER-counting so, with the
          * `compactionHardLimit` margin, the forced path never lets an over-limit request through.
          * No calibration, no provider usage, no EWMA.
          */
        val default: Tokenizer = ConservativeTokenizer
    end Tokenizer

    private[kyo] object internal:

        // --- internal tuning constants: fixed default weights and thresholds for compaction scoring ---
        val referenceWeight: Double     = 3.0
        val adjacencyWeight: Double     = 1.0
        val restartWeight: Double       = 0.15
        val supersessionPenalty: Double = 0.2
        val seedObjective: Double       = 0.35
        val seedTask: Double            = 0.20
        val seedTail: Double            = 0.25
        val seedUnresolved: Double      = 0.15
        val seedSystem: Double          = 0.05
        val elisionThreshold: Int       = 8000
        val horizonTurns: Int           = 10
        val refetchThreshold: Int       = 2
        val deepCacheDiscount: Double   = 0.1
        val deepWritePremium: Double    = 1.25
        val tailTurns: Int              = 10
        val tailTokens: Int             = 12000

        enum Level derives CanEqual:
            case Verbatim, Reduced, Omitted

        // The atomic node: fused message INDICES into raw, its unresolved flag, its token size.
        final case class Segment(id: Int, indices: Chunk[Int], unresolved: Boolean, tokens: Int)

        final case class Building(id: Int, indices: Chunk[Int], open: Set[String])

        enum EdgeKind derives CanEqual:
            case Adj, Ref

        final case class Edge(target: Int, kind: EdgeKind, weight: Double)

        final case class Graph(edges: Dict[Int, Chunk[Edge]]):
            def isEmpty: Boolean = edges.isEmpty
        object Graph:
            val empty: Graph = Graph(Dict.empty)

        /** The recall tool's typed input, object-wrapped so the wire schema is
          * `{"id":{"type":"integer"}}` (providers reject a bare integer parameter schema).
          */
        final case class Recall(id: Int) derives Schema, CanEqual

        /** The conservative default tokenizer: ~1 token per 3 chars rounded up plus a small
          * per-message envelope, biased to OVER-count so occupancy never under-reads the provider.
          */
        object ConservativeTokenizer extends Tokenizer:
            def count(message: Message): Int =
                val chars = message match
                    case AssistantMessage(c, calls, _, _, _) => c.length + calls.foldLeft(0)((n, x) => n + x.arguments.length)
                    case ToolMessage(_, c, _, _, _)          => c.length
                    case UserMessage(c, _, _, _, _)          => c.length
                    case SystemMessage(c, _, _, _)           => c.length
                (chars + 2) / 3 + 4
            end count
        end ConservativeTokenizer

        // ---------------------------------------------------------------------------------------
        // The single shipped default. render returns only the rebuilt compacted Chunk[Message];
        // raw never appears in its signature. It reads the ACTIVE kyo.ai.Config live (never a
        // construction snapshot), makes ZERO model calls, and forks NO fibers. Region bookkeeping
        // is derived from (raw, compacted).
        // ---------------------------------------------------------------------------------------
        object Default extends Compactor[Any]:

            def render(ctx: Context)(using Frame): Chunk[Message] < (LLM & Async & Abort[AIGenException]) =
                AI.config.map { config =>
                    val tokenizer = config.tokenizer
                    val window    = config.modelMaxTokens
                    val budget    = config.effectiveCompactionBudget
                    val target    = math.min(config.compactionLowWatermark, config.compactionHighWatermark)
                    val low       = (target * budget).toInt
                    val hard      = (config.compactionHardLimit * window).toInt
                    val units     = group(ctx.raw)
                    superKeys(units, ctx.raw).map { keys =>
                        val superseded = supersession(units, keys)
                        val graph      = deriveGraph(units, ctx.raw, superseded)
                        val seed       = seedVector(units, ctx.raw, tokenizer)
                        val scores     = score(units, graph, superseded, seed)
                        val rootSet    = roots(units, ctx.raw, tokenizer)
                        val referrers  = coPinReferrers(units, graph)
                        val prevLevels = demotedOrigins(ctx.compacted)
                        val promoted   = promotionSet(units, ctx.raw, prevLevels)
                        val since      = ctx.raw.size
                        val demotions  = cut(ctx, units, scores, referrers, rootSet, promoted, tokenizer, low, since)
                        val view       = project(ctx.raw, units, demotions, since)
                        if viewTokens(view, tokenizer) <= hard then view
                        else
                            val forcedDem  = forced(ctx.raw, units, scores, referrers, rootSet, tokenizer, hard, since)
                            val forcedView = project(ctx.raw, units, forcedDem, since)
                            if viewTokens(forcedView, tokenizer) > hard then
                                Abort.fail(AIContextOverflowException(viewTokens(forcedView, tokenizer), window))
                            else forcedView
                        end if
                    }
                }
            end render

            override def tools(ai: AI)(using Frame): Chunk[Tool[LLM]] = Chunk(recallTool(ai))

            // ---- grouping (fuse assistant + answering tool messages into units over raw) ----
            def group(raw: Chunk[Message]): Chunk[Segment] =
                val (byId, _) =
                    raw.zipWithIndex.foldLeft((Map.empty[Int, Building], Dict.empty[String, Int])) {
                        case ((units, owner), (msg, i)) =>
                            msg match
                                case AssistantMessage(_, calls, _, _, _) if calls.nonEmpty =>
                                    val callIds = calls.foldLeft(Set.empty[String])((s, c) => s + c.id.id)
                                    val owner2  = calls.foldLeft(owner)((o, c) => o.update(c.id.id, i))
                                    (units.updated(i, Building(i, Chunk(i), callIds)), owner2)
                                case ToolMessage(callId, _, _, _, _) =>
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
                    val toks = b.indices.foldLeft(0)((n, idx) => n + ConservativeTokenizer.count(raw(idx)))
                    Segment(b.id, b.indices, b.open.nonEmpty, toks)
                })
            end group

            // ---- key supersession (typed compactionKey via the Phase-02 Info closure, no cast) ----
            def superKeys(units: Chunk[Segment], raw: Chunk[Message])(using Frame): Dict[Int, (String, Tool.Kind)] < LLM =
                Tool.internal.infos.map { infos =>
                    val byName = infos.foldLeft(Dict.empty[String, Tool.internal.Info[?, ?, LLM]])((m, i) => m.update(i.name, i))
                    units.foldLeft(Dict.empty[Int, (String, Tool.Kind)]) { (acc, u) =>
                        val calls = u.indices.flatMap { idx =>
                            raw(idx) match
                                case AssistantMessage(_, cs, _, _, _) => cs
                                case _                                => Chunk.empty
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

            def supersession(units: Chunk[Segment], keys: Dict[Int, (String, Tool.Kind)]): Dict[Int, Int] =
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

            // ---- graph: Adj + structural Ref edges only, no semantic edge ----
            def deriveGraph(units: Chunk[Segment], raw: Chunk[Message], superseded: Dict[Int, Int]): Graph =
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
                            (cur.id, Edge(prev.id, EdgeKind.Adj, adjacencyWeight))
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
                                            List((id, Edge(target, EdgeKind.Ref, referenceWeight / hub)))
                                        end if
                                    case _ => Nil
                            }
                        }
                    val edges = (adj ++ ref).foldLeft(Map.empty[Int, Chunk[Edge]]) { case (m, (from, e)) =>
                        m.updated(from, m.getOrElse(from, Chunk.empty).append(e))
                    }
                    Graph(Dict.from(edges))
            end deriveGraph

            // ---- scoring (one-shot Personalized PageRank; supersession penalty applied outside) ----
            def score(units: Chunk[Segment], graph: Graph, superseded: Dict[Int, Int], seed: Dict[Int, Double]): Dict[Int, Double] =
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
                    val ranked    = iterate(ids.map(id => id -> seedOf(id)).toMap, 40)
                    val penalized = ranked.map { case (id, v) => id -> (if superseded.contains(id) then v * supersessionPenalty else v) }
                    Dict.from(penalized)
            end score

            def seedVector(units: Chunk[Segment], raw: Chunk[Message], tokenizer: Tokenizer): Dict[Int, Double] =
                if units.isEmpty then Dict.empty
                else
                    val ordered  = units.toList.sortBy(_.id)
                    val systemId = ordered.headOption.filter(u => isSystemHead(u, raw)).map(_.id)
                    val userIds  = ordered.filter(u => hasUser(u, raw)).map(_.id)
                    val taskId   = userIds.headOption
                    val objId    = userIds.lastOption
                    val unresIds = ordered.filter(_.unresolved).map(_.id)
                    val tailIds  = tailUnits(units, tokenizer).toList.sorted
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
                    val merged = (singleContribs ++ tailContribs).foldLeft(Map.empty[Int, Double]) { case (m, (id, v)) =>
                        m.updated(id, m.getOrElse(id, 0.0) + v)
                    }
                    Dict.from(merged)
            end seedVector

            // ---- pins ----
            def roots(units: Chunk[Segment], raw: Chunk[Message], tokenizer: Tokenizer): Set[Int] =
                val ordered = units.toList.sortBy(_.id)
                val sys     = ordered.headOption.filter(u => isSystemHead(u, raw)).map(_.id).toSet
                val users   = ordered.filter(u => hasUser(u, raw)).map(_.id)
                sys ++ users.headOption.toSet ++ users.lastOption.toSet ++ ordered.filter(_.unresolved).map(_.id).toSet ++ tailUnits(
                    units,
                    tokenizer
                )
            end roots

            def tailUnits(units: Chunk[Segment], tokenizer: Tokenizer): Set[Int] =
                val ordered = units.toList.sortBy(_.id).reverse
                @tailrec def loop(rem: List[Segment], count: Int, tokens: Int, acc: Set[Int]): Set[Int] =
                    rem match
                        case Nil => acc
                        case u :: rest =>
                            if count >= tailTurns then acc
                            else if tokens + u.tokens > tailTokens && acc.nonEmpty then acc
                            else loop(rest, count + 1, tokens + u.tokens, acc + u.id)
                loop(ordered, 0, 0, Set.empty)
            end tailUnits

            def coPinReferrers(units: Chunk[Segment], graph: Graph): Map[Int, Set[Int]] =
                units.toList.foldLeft(Map.empty[Int, Set[Int]]) { (m, u) =>
                    graph.edges.get(u.id).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Ref).foldLeft(m) { (mm, e) =>
                        mm.updated(e.target, mm.getOrElse(e.target, Set.empty) + u.id)
                    }
                }

            // ---- region bookkeeping derived from compacted, no string parsing ----
            // A demoted unit is exactly one synthetic entry carrying Present(origin); origin.start is
            // the unit id, origin.since is the raw index at the boundary that demoted it.
            def demotedOrigins(compacted: Chunk[Message]): Dict[Int, Context.Origin] =
                compacted.foldLeft(Dict.empty[Int, Context.Origin]) { (m, msg) =>
                    msg.origin match
                        case Present(o) => m.update(o.start, o)
                        case Absent     => m
                }

            // recall(id) calls in raw at index >= since: the exact since-demotion count.
            def refetchCount(unitId: Int, raw: Chunk[Message], since: Int)(using Frame): Int =
                raw.zipWithIndex.foldLeft(0) { case (n, (m, i)) =>
                    if i < since then n
                    else
                        m match
                            case AssistantMessage(_, calls, _, _, _) =>
                                n + calls.count(c => c.function == "recall" && recallArgId(c.arguments).contains(unitId))
                            case _ => n
                }

            // A demoted unit recalled >= refetchThreshold times SINCE its demotion is PROMOTED
            // (rendered up to verbatim) and excluded from this pass's demotion set (anti-thrash).
            def promotionSet(units: Chunk[Segment], raw: Chunk[Message], prev: Dict[Int, Context.Origin])(using Frame): Set[Int] =
                units.toList.filter { u =>
                    prev.get(u.id) match
                        case Present(o) => refetchCount(u.id, raw, o.since) >= refetchThreshold
                        case Absent     => false
                }.map(_.id).toSet

            // ---- the cut: ascending-score demotion to the low watermark, cache-gated + co-pinned ----
            def cut(
                ctx: Context,
                units: Chunk[Segment],
                scores: Dict[Int, Double],
                referrers: Map[Int, Set[Int]],
                rootSet: Set[Int],
                promoted: Set[Int],
                tokenizer: Tokenizer,
                low: Int,
                since: Int
            ): Dict[Int, Level] =
                val candidates =
                    units.toList
                        .filter(u => !rootSet.contains(u.id) && !u.unresolved && !promoted.contains(u.id))
                        .sortBy(u => scores.get(u.id).getOrElse(0.0))
                @tailrec def demote(rem: List[Segment], dem: Dict[Int, Level]): Dict[Int, Level] =
                    if viewTokens(project(ctx.raw, units, dem, since), tokenizer) <= low then dem
                    else
                        rem match
                            case Nil => dem
                            case u :: rest =>
                                val coPinned = referrers.getOrElse(u.id, Set.empty).exists(r => rootSet.contains(r) && !dem.contains(r))
                                if coPinned then demote(rest, dem)
                                else
                                    val proposed = dem.update(u.id, Level.Reduced)
                                    if cacheGatePasses(ctx, units, dem, proposed, tokenizer, since) then demote(rest, proposed)
                                    else demote(rest, dem)
                                end if
                // second pass: deepen Reduced -> Omitted under remaining pressure (never Verbatim -> Omitted)
                val reduced = demote(candidates, Dict.empty)
                @tailrec def deepen(rem: List[Segment], dem: Dict[Int, Level]): Dict[Int, Level] =
                    if viewTokens(project(ctx.raw, units, dem, since), tokenizer) <= low then dem
                    else
                        rem match
                            case Nil => dem
                            case u :: rest =>
                                dem.get(u.id) match
                                    case Present(Level.Reduced) => deepen(rest, dem.update(u.id, Level.Omitted))
                                    case _                      => deepen(rest, dem)
                deepen(candidates, reduced)
            end cut

            // The cache gate: an edit is applied only when its saving over the horizon beats the
            // cache-invalidation cost of the rendered tail from the edit point (deep edits deferred).
            def cacheGatePasses(
                ctx: Context,
                units: Chunk[Segment],
                cur: Dict[Int, Level],
                proposed: Dict[Int, Level],
                tokenizer: Tokenizer,
                since: Int
            ): Boolean =
                val before = project(ctx.raw, units, cur, since)
                val after  = project(ctx.raw, units, proposed, since)
                val saved  = math.max(0, viewTokens(before, tokenizer) - viewTokens(after, tokenizer))
                val editAt = before.zip(after).takeWhile((a, b) => Context.coreEq(a, b)).size
                val lCut   = after.drop(editAt).foldLeft(0)((n, m) => n + tokenizer.count(m))
                horizonTurns * deepCacheDiscount * saved > deepWritePremium * (lCut - saved) - deepCacheDiscount * lCut
            end cacheGatePasses

            // ---- forced path: Omit least-live non-root units, NO model calls, until it fits ----
            def forced(
                raw: Chunk[Message],
                units: Chunk[Segment],
                scores: Dict[Int, Double],
                referrers: Map[Int, Set[Int]],
                rootSet: Set[Int],
                tokenizer: Tokenizer,
                hard: Int,
                since: Int
            ): Dict[Int, Level] =
                val candidates =
                    units.toList
                        .filter(u => !rootSet.contains(u.id) && !u.unresolved)
                        .sortBy(u => scores.get(u.id).getOrElse(0.0))
                @tailrec def omit(rem: List[Segment], dem: Dict[Int, Level]): Dict[Int, Level] =
                    if viewTokens(project(raw, units, dem, since), tokenizer) <= hard then dem
                    else
                        rem match
                            case Nil => dem
                            case u :: rest =>
                                val coPinned = referrers.getOrElse(u.id, Set.empty).exists(r => rootSet.contains(r))
                                if coPinned then omit(rest, dem)
                                else omit(rest, dem.update(u.id, Level.Omitted))
                omit(candidates, Dict.empty)
            end forced

            // Two units are byte-identical when they cover the same number of messages and each message
            // is CORE-field equal (content/role/image/calls/callId, via Context.coreEq) to its counterpart,
            // NOT merely the same flattened content: a SystemMessage and a ToolMessage sharing a content
            // string are different messages and must never fold. This is the same core-field comparison
            // merge and the cache gate use for deduplication.
            def unitCoreEq(a: Segment, b: Segment, raw: Chunk[Message]): Boolean =
                a.indices.size == b.indices.size &&
                    a.indices.zip(b.indices).forall((ia, ib) => Context.coreEq(raw(ia), raw(ib)))

            // A byte-identical repeat of an earlier unit maps to that earlier unit's id, so a demoted
            // repeat renders as a Reference pointer (Reduced-Reference) rather than a second copy. A
            // near-duplicate is not core-field equal, so it never folds (it routes through key supersession
            // instead). This is also the fold that collapses a promoted region's recall tail-copies.
            def duplicateTargets(units: Chunk[Segment], raw: Chunk[Message]): Map[Int, Int] =
                units.toList.sortBy(_.id).foldLeft((Map.empty[Int, Int], List.empty[Segment])) {
                    case ((dt, seen), u) =>
                        seen.find(prev => unitCoreEq(prev, u, raw)) match
                            case Some(prev) => (dt.updated(u.id, prev.id), seen :+ u)
                            case None       => (dt, seen :+ u)
                }._1

            // ---- project: build the view; a demoted unit becomes ONE synthetic entry carrying origin ----
            def project(raw: Chunk[Message], units: Chunk[Segment], demotions: Dict[Int, Level], since: Int): Chunk[Message] =
                if demotions.isEmpty then raw
                else
                    val ordered = units.toList.sortBy(_.id)
                    val msgToId = ordered.foldLeft(Map.empty[Int, Int])((m, u) => u.indices.foldLeft(m)((mm, idx) => mm.updated(idx, u.id)))
                    val byId    = ordered.map(u => u.id -> u).toMap
                    val dupTarget = duplicateTargets(units, raw)
                    val (out, _) =
                        raw.zipWithIndex.foldLeft((Chunk.empty[Message], Set.empty[Int])) {
                            case ((acc, emitted), (m, i)) =>
                                val uid = msgToId.getOrElse(i, i)
                                demotions.get(uid) match
                                    case Absent => (acc.append(m), emitted)
                                    case Present(level) =>
                                        if emitted.contains(uid) then (acc, emitted)
                                        else
                                            val u      = byId(uid)
                                            val marker = renderMarker(u, raw, level, since, dupTarget)
                                            (acc.append(marker), emitted + uid)
                                end match
                        }
                    out
            end project

            // A synthetic SystemMessage standing for a demoted unit, assembled MECHANICALLY (no
            // model-generated text) and carrying Present(origin) so recall + level derivation are typed.
            // A byte-identical repeat renders as a Reference pointer to the earlier unit's id (the
            // Reduced-Reference sub-mechanism); every other demoted unit uses Reduced-Elide/Mask or Omit.
            def renderMarker(u: Segment, raw: Chunk[Message], level: Level, since: Int, dupTarget: Map[Int, Int]): Message =
                val endExcl = u.indices.lastOption.map(_ + 1).getOrElse(u.id + 1)
                val origin  = Present(Context.Origin(u.id, endExcl, since))
                val bytes   = u.indices.foldLeft(0)((n, idx) => n + raw(idx).content.length)
                dupTarget.get(u.id) match
                    case Some(target) =>
                        SystemMessage(
                            s"[compacted region ${u.id}: identical to region $target; call recall(${u.id}) to restore]",
                            origin = origin
                        )
                    case None =>
                        level match
                            case Level.Verbatim => raw(u.indices.head)
                            case Level.Reduced =>
                                val content = unitContent(u, raw)
                                val body = elide(
                                    content,
                                    elisionThreshold
                                ).getOrElse(s"[compacted region ${u.id}: $bytes bytes reduced; call recall(${u.id}) to restore]")
                                SystemMessage(body, origin = origin)
                            case Level.Omitted =>
                                SystemMessage(
                                    s"[compacted region ${u.id}: $bytes bytes omitted; call recall(${u.id}) to restore]",
                                    origin = origin
                                )
                        end match
                end match
            end renderMarker

            // ---- recall (typed decode; tail-only; instance-bound; keyless) ----
            def recallTool(ai: AI)(using Frame): Tool[LLM] =
                Tool.init[Recall](
                    name = "recall",
                    description =
                        "Recall the full original content of a demoted region by its unit id (the id carried " +
                            "in the region's marker). Returns the verbatim content as a fresh tool result.",
                    kind = Tool.Kind.Read
                ) { (arg: Recall) =>
                    // Resolve against ONLY the calling instance's own transcript (raw).
                    ai.context.map { ctx =>
                        val units = group(ctx.raw)
                        units.filter(_.id == arg.id).headMaybe match
                            case Present(u) => unitContent(u, ctx.raw)
                            case Absent     => s"no such recallable region: ${arg.id}"
                    }
                }
            end recallTool

            // ---- pure derivation helpers ----
            def viewTokens(view: Chunk[Message], tokenizer: Tokenizer): Int =
                view.foldLeft(0)((n, m) => n + tokenizer.count(m))

            def unitContent(u: Segment, raw: Chunk[Message]): String =
                u.indices.map { idx =>
                    raw(idx) match
                        case AssistantMessage(c, calls, _, _, _) =>
                            if calls.isEmpty then c else c + " " + calls.map(_.arguments).mkString(" ")
                        case m => m.content
                }.mkString(" ")

            // Structural identifier extraction: backticked / path-dotted-snake-camel / digit-bearing,
            // len >= 3. No stop-word list, no bare words, no pattern matching: a hand character-class scan expressed as
            // a @tailrec walk with a start-index sentinel (no bare while, no cross-scope var).
            def extractTokens(content: String): Chunk[String] =
                val out = Chunk.newBuilder[String]
                def emit(tok0: String): Unit =
                    val backticked = tok0.length >= 2 && tok0.head == '`' && tok0.last == '`'
                    val tok        = if backticked then tok0.substring(1, tok0.length - 1) else tok0
                    val structured = backticked || tok.exists(c =>
                        c == '/' || c == '.' || c == '_' || c == ':' || c == '-' || c.isDigit || c.isUpper
                    )
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

            def elide(content: String, threshold: Int): Maybe[String] =
                if content.length <= threshold then Absent
                else Present(content.take(threshold / 2) + "\n...[elided]...\n" + content.takeRight(threshold / 2))

            def repoint(id: Int, superseded: Dict[Int, Int]): Int =
                @tailrec def loop(cur: Int): Int =
                    superseded.get(cur) match
                        case Present(next) if next != cur => loop(next)
                        case _                            => cur
                loop(id)
            end repoint

            def isSystemHead(u: Segment, raw: Chunk[Message]): Boolean =
                u.indices.headOption.exists(idx => raw(idx).isInstanceOf[SystemMessage])

            def hasUser(u: Segment, raw: Chunk[Message]): Boolean =
                u.indices.exists(idx => raw(idx).isInstanceOf[UserMessage])

        end Default
    end internal
end Compactor
