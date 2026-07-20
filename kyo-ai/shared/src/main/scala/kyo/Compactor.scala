package kyo

import kyo.Schema
import kyo.ai.*
import kyo.ai.Context.*
import kyo.ai.completion.Completion
import scala.annotation.tailrec

/** Automatic context compaction as an `AI.Enablement`: keeps what the model SEES small and
  * high-signal while the transcript stays the complete, immutable record.
  *
  * Enable it like any enablement, `Compactor.init.map(ai.enable(_))`, which sets one
  * `Maybe[Compactor]` field on the env and registers a `recall` tool. With none enabled a
  * generation is byte-identical to today (default-off). When enabled, `LLM.eval` /
  * `streamAgainst` consult it at one seam between the context read and request assembly: it
  * returns a bounded, projected VIEW of the transcript (never mutating `AISession.context`).
  * The view is byte-identical between updates so the provider prompt cache survives; an
  * update pays cache invalidation once, from its edit point.
  *
  * PRIVACY: with a compactor enabled, unit content is sent to the embedding provider
  * (`Config.embedder` or the chat provider) and ambiguous-band content to the judge model
  * (`provider.small` or `Config.judge`). With no compactor, nothing changes. `Config.embedder
  * = Absent` plus a same-provider `small` keep data within the already-chosen provider.
  */
final class Compactor private (
    private[kyo] val cell: AtomicRef[Dict[LLM.internal.AIRef, Compactor.internal.CompactorState]],
    private[kyo] val config: Compactor.Config,
    // Provider names whose embeddings endpoint returned AIEmbeddingUnsupportedException once already, so
    // the doomed embed fork is attempted at most once per provider rather than re-firing every render.
    private[kyo] val embedUnsupported: AtomicRef[Set[String]]
) extends AI.Enablement[Any]:

    import Compactor.internal.*

    /** The mechanism seam: consulted by `LLM.eval`/`streamAgainst` between the context read and
      * `enrichedContext`. Runs the synchronous model-free steps (group, queue embeddings,
      * occupancy check), then either the fast path (emit current renderings unchanged) or a
      * deterministic update, forking all model work in the background. Returns the projected
      * view; the forced path aborts with `AIContextOverflowException` rather than send an
      * over-limit request. Never mutates the transcript.
      */
    private[kyo] def render(ai: AI, transcript: Context)(using
        Frame
    ): Context < (LLM & Async & Abort[AIGenException]) =
        // Read the active env-merged config once (its window is the model's hard token limit,
        // reachable only through the LLM effect) and thread the window into the pure helpers.
        AI.config.map { active =>
            val window = active.modelMaxTokens
            stateFor(ai, transcript).map { state =>
                // group + queue embeddings for new units; `state` already carries any background
                // results landed since the last render (surfaced by stateFor's cell read) WITHOUT
                // touching renderings (view byte-stable at this step).
                val units = group(transcript, state.book)
                queueEmbeddings(ai, transcript, units, state).map { afterQueue =>
                    val e        = effectiveLength(config, window)
                    val occupied = viewTokens(project(transcript, afterQueue), afterQueue.book)
                    if occupied < (config.updateTriggerFraction * e) then
                        // FAST PATH: emit current renderings unchanged, cache warm.
                        commit(ai, transcript, afterQueue).andThen(project(transcript, afterQueue))
                    else if occupied >= (config.hardWindowFraction * window) then
                        forced(ai, transcript, units, afterQueue, window)
                    else
                        update(ai, transcript, units, afterQueue, e)
                    end if
                }
            }
        }
    end render

    /** Calibrates the byte->token estimator from a completed request's provider-reported usage.
      * `viewTokens` is byte-based scaled by `book.tokensPerByte`; reported usage measures the whole
      * enriched request, so it calibrates the ratio, never replaces `viewTokens`. The new observed
      * ratio is EWMA-blended into the prior calibration so a single outlier request nudges, never
      * swings, occupancy. A no-op when usage is Absent (the estimator keeps its prior calibration).
      */
    private[kyo] def calibrate(ai: AI, usage: Maybe[Completion.Usage], request: Context)(using
        Frame
    ): Unit < Sync =
        usage match
            case Absent => Kyo.unit
            case Present(u) =>
                val requestBytes = byteSize(request)
                landWith(ai) { st =>
                    val obs =
                        if requestBytes <= 0 then st.book.tokensPerByte
                        else u.inputTokens.toDouble / requestBytes.toDouble
                    val blended = (1.0 - calibrationSmoothing) * st.book.tokensPerByte + calibrationSmoothing * obs
                    st.copy(book = st.book.copy(tokensPerByte = blended))
                }
    end calibrate

    private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        // Sets the single active compactor. The `recall` tool is NOT registered here: it is bound to the
        // CALLING instance in the gen seam (LLM.eval), so a scope-wide compactor serving many instances
        // resolves each recall against the instance that issued it, never another's state.
        env.compactor(this)

    private[kyo] def enableIn(session: AISession)(using Frame): AISession =
        session.copy(env = enableIn(session.env))

    // ---- synchronous, model-free derivation: pure over the transcript + landed results ----

    /** One fold over the transcript building units via a pending-calls map: an AssistantMessage with
      * non-empty calls opens a unit and registers its call ids; a ToolMessage joins the unit that
      * issued its callId wherever it arrives (a pair is never severed); every other message is a
      * singleton. id = index of the unit's first message (stable, append-only).
      */
    private[kyo] def group(transcript: Context, book: Book): Chunk[Segment] =
        val messages = transcript.messages
        val (byId, _) =
            messages.zipWithIndex.foldLeft((Map.empty[Int, Building], Map.empty[String, Int])) {
                case ((units, owner), (msg, i)) =>
                    msg match
                        case AssistantMessage(_, calls) if calls.nonEmpty =>
                            val callIds = calls.foldLeft(Set.empty[String])((s, c) => s + c.id.id)
                            val owner2  = calls.foldLeft(owner)((o, c) => o.updated(c.id.id, i))
                            (units.updated(i, Building(i, Chunk(i), callIds)), owner2)
                        case ToolMessage(callId, _) =>
                            owner.get(callId.id) match
                                case Some(uid) =>
                                    val b  = units(uid)
                                    val b2 = b.copy(indices = b.indices.append(i), open = b.open - callId.id)
                                    (units.updated(uid, b2), owner)
                                case None =>
                                    (units.updated(i, Building(i, Chunk(i), Set.empty)), owner)
                        case _ =>
                            (units.updated(i, Building(i, Chunk(i), Set.empty)), owner)
            }
        Chunk.from(
            byId.values.toList.sortBy(_.id).map { b =>
                val bytes = b.indices.foldLeft(0)((n, idx) => n + messageBytes(messages(idx)))
                Segment(b.id, b.indices, b.open.nonEmpty, math.round(bytes * book.tokensPerByte).toInt)
            }
        )
    end group

    // ---- graph derivation (fresh, never stored) ----

    private[kyo] def deriveGraph(
        units: Chunk[Segment],
        transcript: Context,
        state: CompactorState,
        superseded: Dict[Int, Int]
    ): Graph =
        if units.isEmpty then Graph.empty
        else
            val ordered = units.toList.sortBy(_.id)
            // Per-unit token map (name -> structured?), deduped keeping structured=true if any hit is.
            val perUnit: List[(Int, Map[String, Boolean])] =
                ordered.map { u =>
                    val toks = extractTokens(unitContent(u, transcript))
                    val m = toks.foldLeft(Map.empty[String, Boolean]) { case (acc, (t, s)) =>
                        acc.updated(t, acc.getOrElse(t, false) || s)
                    }
                    (u.id, m)
                }
            // Introducer index (first unit mentioning each token) and mention counts.
            val introducer = perUnit.foldLeft(Map.empty[String, Int]) { case (idx, (id, toks)) =>
                toks.keys.foldLeft(idx)((ix, t) => if ix.contains(t) then ix else ix.updated(t, id))
            }
            val mentions = perUnit.foldLeft(Map.empty[String, Int]) { case (mc, (_, toks)) =>
                toks.keys.foldLeft(mc)((m, t) => m.updated(t, m.getOrElse(t, 0) + 1))
            }
            // Adjacency: each unit points at the previous one.
            val adj: List[(Int, Edge)] =
                ordered.sliding(2).toList.collect { case prev :: cur :: Nil =>
                    (cur.id, Edge(prev.id, EdgeKind.Adj, config.adjacencyWeight))
                }
            // Reference: a later mention edges to the introducer's current (repointed) mapping.
            val ref: List[(Int, Edge)] =
                perUnit.flatMap { case (id, toks) =>
                    toks.toList.flatMap { case (t, structured) =>
                        introducer.get(t) match
                            case Some(intro) if intro != id =>
                                val target = repoint(intro, superseded)
                                if target == id then Nil
                                else
                                    val base = if structured then config.referenceWeight else bareRefWeight
                                    val hub  = 1.0 + math.log(1.0 + mentions.getOrElse(t, 1).toDouble)
                                    List((id, Edge(target, EdgeKind.Ref, base / hub)))
                                end if
                            case _ => Nil
                    }
                }
            // Semantic: symmetric mutual-kNN over landed vectors above the floor, decayed by gap.
            val vecUnits                       = ordered.filter(u => state.vectors.contains(u.id))
            def vec(id: Int): Maybe[Embedding] = state.vectors.get(id)
            def neighbors(u: Segment): List[(Int, Double)] =
                vecUnits.filter(_.id != u.id).flatMap { v =>
                    vec(u.id).flatMap(a => vec(v.id).flatMap(a.cosine)) match
                        case Present(c) if c >= config.semanticFloor => List((v.id, c))
                        case _                                       => Nil
                }.sortBy(-_._2).take(config.semanticNeighbors)
            val nbrs: Map[Int, List[(Int, Double)]] = vecUnits.map(u => (u.id, neighbors(u))).toMap
            val sem: List[(Int, Edge)] =
                vecUnits.flatMap { u =>
                    nbrs.getOrElse(u.id, Nil).flatMap { case (vId, c) =>
                        val mutual = nbrs.getOrElse(vId, Nil).exists(_._1 == u.id)
                        if !mutual || vId <= u.id then Nil
                        else
                            val gap = math.abs(u.id - vId).toDouble
                            val w   = config.semanticWeight * c * math.pow(0.5, gap / config.semanticDecayHalfLife.toDouble)
                            List((u.id, Edge(vId, EdgeKind.Sem, w)), (vId, Edge(u.id, EdgeKind.Sem, w)))
                        end if
                    }
                }
            val edges = (adj ++ ref ++ sem).foldLeft(Map.empty[Int, Chunk[Edge]]) { case (m, (from, e)) =>
                m.updated(from, m.getOrElse(from, Chunk.empty).append(e))
            }
            Graph(Dict.from(edges))
    end deriveGraph

    /** Key-based supersession: from each unit's tool `compactionKey`/`kind`, record superseded(old)=new
      * (a re-read supersedes the prior read; a write supersedes prior reads) and repoint old's index
      * entries to new. A keyless tool never supersedes and is never superseded. Never a graph edge
      * (row-stochastic PPR: an edge only ADDS mass); applied as a multiplicative penalty + repoint
      * outside the walk.
      */
    private[kyo] def supersession(units: Chunk[Segment], keys: Dict[Int, (String, Tool.Kind)]): Dict[Int, Int] =
        val ordered = units.toList.sortBy(_.id)
        val (result, _) =
            ordered.foldLeft((Dict.empty[Int, Int], Map.empty[String, (Int, Tool.Kind)])) { case ((sup, last), u) =>
                keys.get(u.id) match
                    case Absent => (sup, last)
                    case Present((k, curKind)) =>
                        last.get(k) match
                            case Some((prevId, prevKind)) =>
                                // A re-read supersedes a prior read; a write supersedes any prior same-key unit.
                                // The one case that does NOT supersede: a read after a write. The write stays live
                                // (the authoritative state-establishing record), so the kind is load-bearing here.
                                val supersedes = curKind == Tool.Kind.Write || prevKind == Tool.Kind.Read
                                val sup2       = if supersedes then sup.update(prevId, u.id) else sup
                                (sup2, last.updated(k, (u.id, curKind)))
                            case None => (sup, last.updated(k, (u.id, curKind)))
            }
        result
    end supersession

    // Resolves each unit's compaction key (if any) by matching its tool calls to the registered
    // tools, decoding the arguments through the tool's own input schema, and applying its extractor.
    private[kyo] def superKeys(units: Chunk[Segment], transcript: Context)(using Frame): Dict[Int, (String, Tool.Kind)] < LLM =
        Tool.internal.infos.map { infos =>
            val byName = infos.foldLeft(Map.empty[String, Tool.internal.Info[?, ?, LLM]])((m, i) => m.updated(i.name, i))
            units.foldLeft(Dict.empty[Int, (String, Tool.Kind)]) { (acc, u) =>
                val calls = u.messages.flatMap { idx =>
                    transcript.messages(idx) match
                        case AssistantMessage(_, cs) => cs
                        case _                       => Chunk.empty
                }
                val keyed = calls.foldLeft(Absent: Maybe[(String, Tool.Kind)]) { (found, call) =>
                    found match
                        case Present(_) => found
                        case Absent =>
                            byName.get(call.function) match
                                case None => Absent
                                case Some(info) =>
                                    Json.decode[Any](call.arguments)(using summon, info.inputSchema.asInstanceOf[Schema[Any]], summon) match
                                        case Result.Success(in) =>
                                            info.compactionKey(in) match
                                                case Present(k) => Present((k, info.kind))
                                                case Absent     => Absent
                                        case _ => Absent
                }
                keyed match
                    case Present(kk) => acc.update(u.id, kk)
                    case Absent      => acc
            }
        }
    end superKeys

    // ---- scoring (Personalized PageRank) ----

    /** Scores every unit by one power iteration of Personalized PageRank over the row-stochastic
      * transition matrix, seeded at the live frontier, then multiplies each superseded unit's score by
      * `supersessionPenalty` as the FINAL step. Recency is the Adj-only degenerate case
      * r_j = restartWeight*(1-restartWeight)^j, not a separate prior. Scores are used ordinally
      * only.
      */
    private[kyo] def score(
        units: Chunk[Segment],
        graph: Graph,
        superseded: Dict[Int, Int],
        seed: Dict[Int, Double]
    ): Dict[Int, Double] =
        if units.isEmpty then Dict.empty
        else
            val ids   = units.toList.map(_.id)
            val alpha = config.restartWeight
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
            val penalized = ranked.map { case (id, v) => id -> (if superseded.contains(id) then v * config.supersessionPenalty else v) }
            Dict.from(penalized)
    end score

    // The seed vector: objective/task/tail/unresolved/system shares over their target units; an
    // absent category folds its share into the tail (never dropped, never spread uniformly).
    private[kyo] def seedVector(units: Chunk[Segment], transcript: Context, book: Book): Dict[Int, Double] =
        if units.isEmpty then Dict.empty
        else
            val s        = config.seeds
            val ordered  = units.toList.sortBy(_.id)
            val systemId = ordered.headOption.filter(u => isSystemHead(u, transcript)).map(_.id)
            val userIds  = ordered.filter(u => hasUser(u, transcript)).map(_.id)
            val taskId   = userIds.headOption
            val objId    = userIds.lastOption
            val unresIds = ordered.filter(_.unresolved).map(_.id)
            val tailIds  = tailUnits(units, book).toList.sorted
            val singles: List[(List[Int], Double)] =
                List(
                    (objId.toList, s.objective),
                    (taskId.toList, s.task),
                    (unresIds, s.unresolved),
                    (systemId.toList, s.system)
                )
            val folded         = singles.foldLeft(0.0) { case (acc, (t, w)) => if t.isEmpty then acc + w else acc }
            val tailShare      = s.tail + folded
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

    // ---- view / project (the rendering ladder) ----

    /** Pure: walks units in transcript order, emitting each unit's original messages (no renderings
      * entry) or its `Rendered.replacement`. A summary's entry sits on its run's first unit with
      * covers=n; the walk skips the next n-1 units, so it is total and nothing double-renders. Markers
      * are plain SystemMessages; whole-unit replacement keeps tool_use/tool_result paired, so the view
      * is always provider-legal.
      */
    private[kyo] def project(transcript: Context, state: CompactorState): Context =
        if state.renderings.isEmpty then transcript
        else projectFrom(transcript, state, 0)
    end project

    // `project` with the pass's units threaded in, so the demote loop's per-candidate occupancy checks do
    // not re-group the transcript each iteration.
    private def projectWith(transcript: Context, state: CompactorState, units: Chunk[Segment]): Context =
        if state.renderings.isEmpty then transcript
        else projectFrom(transcript, state, 0, units)

    // The projected view restricted to the units whose id is at or after `fromId` (fromId 0 yields the
    // whole view). Shares project's covers-skip rule so a suffix token count matches the emitted view
    // exactly: a summary entry with covers > 1 absorbs the next covers-1 units, never double-counted, and
    // a summary anchored before the cut keeps its absorbed units out of the suffix.
    private[kyo] def projectFrom(transcript: Context, state: CompactorState, fromId: Int): Context =
        projectFrom(transcript, state, fromId, group(transcript, state.book))

    // The units-threaded projection: callers in the per-candidate demote loop pass the units already
    // grouped once for the pass, so `project`/`projectFrom` do not re-run `group` (a full transcript fold
    // plus sort) on every candidate. Grouping is stable across a pass (book.tokensPerByte is constant).
    private def projectFrom(transcript: Context, state: CompactorState, fromId: Int, units: Chunk[Segment]): Context =
        val rendering = state.renderings
        val ordered   = units.toList.sortBy(_.id)
        val msgToId   = ordered.foldLeft(Map.empty[Int, Int])((m, u) => u.messages.foldLeft(m)((mm, idx) => mm.updated(idx, u.id)))
        // Units dropped because a preceding summary's covers-run subsumes them.
        val covered = ordered.zipWithIndex.foldLeft(Set.empty[Int]) { case (acc, (u, p)) =>
            rendering.get(u.id) match
                case Present(r) if r.covers > 1 =>
                    acc ++ ordered.slice(p + 1, math.min(p + r.covers, ordered.size)).map(_.id)
                case _ => acc
        }
        val (out, _) =
            transcript.messages.zipWithIndex.foldLeft((Chunk.empty[Message], Set.empty[Int])) {
                case ((acc, emitted), (m, i)) =>
                    val uid = msgToId.getOrElse(i, i)
                    if uid < fromId then (acc, emitted)
                    else if covered.contains(uid) then (acc, emitted)
                    else if emitted.contains(uid) then (acc, emitted)
                    else
                        rendering.get(uid) match
                            case Present(r) => (acc ++ r.replacement, emitted + uid)
                            case Absent     => (acc.append(m), emitted)
                    end if
            }
        Context(out)
    end projectFrom

    // ---- lifecycle helpers ----

    private def update(ai: AI, transcript: Context, units: Chunk[Segment], state: CompactorState, e: Double)(using
        Frame
    ): Context < (LLM & Async & Abort[AIGenException]) =
        superKeys(units, transcript).map { keys =>
            val superseded = supersession(units, keys)
            val graph      = deriveGraph(units, transcript, state, superseded)
            val seed       = seedVector(units, transcript, state.book)
            val scores     = score(units, graph, superseded, seed)
            val rootSet    = roots(units, transcript, state.book)
            val referrers  = coPinReferrers(units, graph)
            val target     = config.updateTargetFraction * e
            val updateIdx  = transcript.messages.size
            // The judge band is derived supersession-free (its own graph/scores), then reused by
            // dispatchBackground rather than re-derived there. rootSet already holds this pass's roots.
            val bandGraph  = deriveGraph(units, transcript, state, Dict.empty)
            val bandScores = score(units, bandGraph, Dict.empty, seed)
            val bandList   = bandFrom(units, bandScores, rootSet, state.book.inflight)
            val band       = bandList.map(_.id).toSet
            val dupOf      = duplicateMap(units, transcript)
            // ascending score, nudged by BAND-LOCAL verdicts (Stale demotes earlier, Keep holds back). A
            // verdict for a unit outside the fresh band is discarded (never consulted).
            def effectiveScore(u: Segment): Double =
                val base = scores.get(u.id).getOrElse(0.0)
                if band.contains(u.id) then
                    state.verdicts.get(u.id) match
                        case Present(Verdict.Stale) => base - 1e6
                        case Present(Verdict.Keep)  => base + 1e6
                        case _                      => base
                else base
                end if
            end effectiveScore
            val candidates =
                units.toList
                    .filter(u => !rootSet.contains(u.id) && !u.unresolved)
                    .sortBy(effectiveScore)
            @tailrec def demote(rem: List[Segment], st: CompactorState): CompactorState =
                if viewTokens(projectWith(transcript, st, units), st.book) <= target then st
                else
                    rem match
                        case Nil       => st
                        case u :: rest =>
                            // co-pin: keep a live-referenced target if a root referrer would be left dangling.
                            val coPinned =
                                referrers.getOrElse(u.id, Set.empty).exists(r => rootSet.contains(r) && !st.renderings.contains(r))
                            if coPinned then demote(rest, st)
                            else
                                ladderStep(u, st, transcript, updateIdx, dupOf.get(u.id).fold(Absent: Maybe[Int])(Present(_))) match
                                    case Absent => demote(rest, st)
                                    case Present(r) =>
                                        if r.level >= 3 then
                                            // a distorting (L3) or removing (L4) step is a DEEP edit: gate it by the
                                            // cache break-even OR the rot rule (re-fetch count since the unit's Rendered.at,
                                            // OR budget exhaustion). A blocked deep edit is simply not applied; the pass's
                                            // deterministic re-derivation re-evaluates it next turn under the live occupancy,
                                            // so no separate suppression record is needed.
                                            val occupied = viewTokens(projectWith(transcript, st, units), st.book)
                                            val postEdit = st.copy(renderings = st.renderings.update(u.id, r))
                                            val after    = viewTokens(projectWith(transcript, postEdit, units), st.book)
                                            val saved    = math.max(0, occupied - after)
                                            // L_cut is the rendered tail AFTER the edit point: the suffix from u onward under the
                                            // post-edit state, the tokens whose cache the edit actually invalidates (a shallow edit
                                            // near the tail invalidates little; a deep edit into the frozen prefix invalidates most).
                                            val lCut    = viewTokens(projectFrom(transcript, postEdit, u.id, units), postEdit.book)
                                            val curAt   = st.renderings.get(u.id).map(_.at).getOrElse(0)
                                            val refetch = refetchCount(u.id, transcript, curAt)
                                            val allowed = cacheGatePasses(saved, lCut, deepCacheDiscount, deepWritePremium) || rotFires(
                                                refetch,
                                                occupied,
                                                e
                                            )
                                            if allowed then demote(rest, postEdit)
                                            else demote(rest, st)
                                            end if
                                        else
                                            demote(rest, st.copy(renderings = st.renderings.update(u.id, r)))
                                        end if
                            end if
            val demoted = demote(candidates, state)
            dispatchBackground(ai, transcript, units, demoted, bandList, rootSet).map { dispatched =>
                commit(ai, transcript, dispatched).andThen(projectWith(transcript, dispatched, units))
            }
        }
    end update

    // Selects a unit's NEXT ladder step under the two-touch rule (one level per pass). First touch is
    // non-distorting: a byte-identical repeat dedups to a Reference (L2), else L1 Compressed when it
    // reduces and is not whitespace-significant, else L2 Elide (oversized) or Mask. A unit that stood at
    // L1-2 since a PRIOR update may deepen to a re-validated prepared Summary (L3) or Omitted (L4); a unit
    // reduced THIS pass, or already at L3-4, is left alone.
    private[kyo] def ladderStep(
        unit: Segment,
        st: CompactorState,
        transcript: Context,
        updateIdx: Int,
        dupOf: Maybe[Int]
    ): Maybe[Rendered] =
        val content = unitContent(unit, transcript)
        st.renderings.get(unit.id) match
            case Absent =>
                dupOf match
                    case Present(orig) => Present(Rendered(2, 1, updateIdx, Chunk(SystemMessage(referenceMarker(unit, orig)))))
                    case Absent =>
                        compress(content) match
                            // L1 only when compression MEANINGFULLY reduces (a real JSON/repeat/whitespace win),
                            // not a one-character whitespace trim on prose, which would leave the view unshrunk.
                            case Present(comp) if comp.length <= (content.length * 0.85).toInt =>
                                Present(Rendered(1, 1, updateIdx, Chunk(SystemMessage(comp))))
                            case _ =>
                                elide(content, config.elisionThreshold) match
                                    case Present(el) => Present(Rendered(2, 1, updateIdx, Chunk(SystemMessage(el))))
                                    case Absent => Present(Rendered(2, 1, updateIdx, Chunk(SystemMessage(maskMarker(unit, transcript)))))
            case Present(cur) if cur.level >= 3 => Absent
            case Present(cur) =>
                if cur.at >= updateIdx then Absent
                else
                    st.prepared.get(unit.id) match
                        case Present(prep) => Present(prep.copy(level = 3, at = updateIdx))
                        case Absent        => Present(Rendered(4, 1, updateIdx, Chunk(SystemMessage(maskMarker(unit, transcript)))))
        end match
    end ladderStep

    // Byte-identical repeats: a later unit whose content equals an earlier unit's maps to that earlier id.
    private[kyo] def duplicateMap(units: Chunk[Segment], transcript: Context): Map[Int, Int] =
        val ordered = units.toList.sortBy(_.id)
        val (_, dup) =
            ordered.foldLeft((Map.empty[String, Int], Map.empty[Int, Int])) { case ((seen, dup), u) =>
                val content = unitContent(u, transcript)
                seen.get(content) match
                    case Some(orig) => (seen, dup.updated(u.id, orig))
                    case None       => (seen.updated(content, u.id), dup)
            }
        dup
    end duplicateMap

    private def forced(ai: AI, transcript: Context, units: Chunk[Segment], state: CompactorState, window: Int)(using
        Frame
    ): Context < (LLM & Async & Abort[AIGenException]) =
        // At the hard window compaction stops being optional: deterministically Omit the least-live
        // non-root units under the co-pin check, with NO model calls, until the request fits; the SOLE
        // exemption from the two-touch rule. If even roots+tail+unresolved cannot fit, abort loudly.
        val omitted = forcedOmit(transcript, units, state, window)
        val vt      = viewTokens(project(transcript, omitted), omitted.book)
        if vt > window then
            Abort.fail(AIContextOverflowException(vt, window))
        else
            commit(ai, transcript, omitted).andThen(project(transcript, omitted))
        end if
    end forced

    private def forcedOmit(transcript: Context, units: Chunk[Segment], state: CompactorState, hard: Int): CompactorState =
        val graph     = deriveGraph(units, transcript, state, Dict.empty)
        val seed      = seedVector(units, transcript, state.book)
        val scores    = score(units, graph, Dict.empty, seed)
        val rootSet   = roots(units, transcript, state.book)
        val referrers = coPinReferrers(units, graph)
        val candidates =
            units.toList
                .filter(u => !rootSet.contains(u.id) && !u.unresolved)
                .sortBy(u => scores.get(u.id).getOrElse(0.0))
        @tailrec def omit(rem: List[Segment], st: CompactorState): CompactorState =
            if viewTokens(project(transcript, st), st.book) <= hard then st
            else
                rem match
                    case Nil => st
                    case u :: rest =>
                        val coPinned = referrers.getOrElse(u.id, Set.empty).exists(r => rootSet.contains(r))
                        if coPinned then omit(rest, st)
                        else
                            val r = Rendered(4, 1, transcript.messages.size, Chunk(SystemMessage(maskMarker(u, transcript))))
                            omit(rest, st.copy(renderings = st.renderings.update(u.id, r)))
                        end if
        omit(candidates, state)
    end forcedOmit

    // ---- background work: forked through LLM.isolate, results to the cell only ----

    private def queueEmbeddings(ai: AI, transcript: Context, units: Chunk[Segment], state: CompactorState)(using
        Frame
    ): CompactorState < (LLM & Async) =
        // Dedup by landed VECTORS AND by units already dispatched-not-landed (book.embedInflight), so a slow
        // endpoint is not re-issued the same paid batch every render. embedInflight is separate from the judge
        // batch's `inflight`, so embedding work never empties the ambiguous band the judge consults.
        val need = units.toList.filter(u => !state.vectors.contains(u.id) && !state.book.embedInflight.contains(u.id))
        if need.isEmpty then Kyo.lift(state)
        else
            val ids   = need.map(_.id)
            val batch = Chunk.from(need.map(u => unitContent(u, transcript)))
            AI.config.map { chatCfg =>
                // Resolve the embedder config ONCE and dispatch through THAT provider's own backend,
                // never the chat provider's backend with the embedder config as an argument.
                val e = chatCfg.embedder.getOrElse(chatCfg)
                embedUnsupported.get.map { unsupported =>
                    // A provider that already answered "no embeddings endpoint" is never re-attempted: the
                    // doomed fork would otherwise re-fire every render, allocating a fiber and an exception
                    // per turn for no semantic edges.
                    if unsupported.contains(e.provider.name) then Kyo.lift(state)
                    else
                        val work: Unit < (LLM & Async & Abort[HttpException | AIGenException]) =
                            AI.fresh(e.provider.completion.embed(e, batch)).map(embeddings => landEmbeddings(ai, ids, embeddings))
                        // Mark dispatched, fork, and clear embedInflight on EVERY outcome (success clears in
                        // landEmbeddings; a failure clears here). An embed-unsupported failure additionally
                        // records the provider so it is attempted at most once, with a single warning.
                        val guarded: Unit < (LLM & Async) =
                            Abort.run[HttpException | AIGenException](work).map {
                                case Result.Failure(_: AIEmbeddingUnsupportedException) =>
                                    markEmbedUnsupported(e.provider.name).andThen(clearEmbedInflight(ai, ids))
                                case _ =>
                                    clearEmbedInflight(ai, ids)
                            }
                        landWith(ai)(st => st.copy(book = st.book.copy(embedInflight = st.book.embedInflight ++ ids.toSet)))
                            .andThen(forkToCell(guarded))
                            .andThen(Kyo.lift(state))
                    end if
                }
            }
        end if
    end queueEmbeddings

    // The ambiguous band: the K non-root demotable units nearest the cut (lowest score), capped at
    // bandSize; an overflow waits rather than growing the band. Only these go to the judge.
    private[kyo] def judgeBand(units: Chunk[Segment], transcript: Context, state: CompactorState): List[Segment] =
        val graph  = deriveGraph(units, transcript, state, Dict.empty)
        val seed   = seedVector(units, transcript, state.book)
        val scores = score(units, graph, Dict.empty, seed)
        val roots0 = roots(units, transcript, state.book)
        bandFrom(units, scores, roots0, state.book.inflight)
    end judgeBand

    // The band selection over already-computed scores/roots: the K non-root demotable units nearest the cut
    // (lowest score), capped at bandSize, excluding units already in flight. Shared by judgeBand (which
    // derives its own graph/scores) and the update pass (which passes its precomputed ones).
    private def bandFrom(units: Chunk[Segment], scores: Dict[Int, Double], roots0: Set[Int], inflight: Set[Int]): List[Segment] =
        units.toList
            .filter(u => !roots0.contains(u.id) && !u.unresolved && !inflight.contains(u.id))
            .sortBy(u => scores.get(u.id).getOrElse(0.0))
            .take(config.bandSize)

    // Contiguous runs (>= 2 units) of non-root demotable units, each one summarization candidate the
    // background summarizer prepares (adopted at the next update after re-validation). A run breaks at roots,
    // unresolved units, and intermediate User boundaries; when every unit in a run carries a landed vector it
    // is split further at the widest pairwise-cosine gap until each sub-run's mean pairwise cosine clears
    // coherenceFloor, so only coherent regions co-summarize.
    private[kyo] def summaryCandidates(units: Chunk[Segment], transcript: Context, state: CompactorState): List[List[Segment]] =
        summaryCandidates(units, transcript, state, roots(units, transcript, state.book))

    private def summaryCandidates(
        units: Chunk[Segment],
        transcript: Context,
        state: CompactorState,
        rootSet: Set[Int]
    ): List[List[Segment]] =
        val ordered = units.toList.sortBy(_.id)
        def boundary(u: Segment): Boolean =
            rootSet.contains(u.id) || u.unresolved || hasUser(u, transcript)
        @tailrec def group(rem: List[Segment], cur: List[Segment], acc: List[List[Segment]]): List[List[Segment]] =
            rem match
                case Nil => (if cur.nonEmpty then cur.reverse :: acc else acc).reverse
                case u :: rest =>
                    if boundary(u) then
                        group(rest, Nil, if cur.nonEmpty then cur.reverse :: acc else acc)
                    else group(rest, u :: cur, acc)
        group(ordered, Nil, Nil).flatMap(run => coherenceSplit(run, state)).filter(_.size >= 2)
    end summaryCandidates

    // Splits a run at the widest adjacent pairwise-cosine gap until every sub-run's mean pairwise cosine
    // clears coherenceFloor, applied only when every unit in the run carries a landed vector (with no
    // vectors the run is left to the structural split alone). An Absent cosine (a cross-space mismatch)
    // counts as a maximal gap and a below-floor contribution, so it forces a split rather than passing.
    private def coherenceSplit(run: List[Segment], state: CompactorState): List[List[Segment]] =
        if run.size < 2 || !run.forall(u => state.vectors.contains(u.id)) then List(run)
        else
            def vec(u: Segment): Maybe[Embedding] = state.vectors.get(u.id)
            def meanCosine(sub: List[Segment]): Double =
                val cosines = sub.combinations(2).toList.map {
                    case a :: b :: Nil => vec(a).flatMap(x => vec(b).flatMap(x.cosine)).getOrElse(0.0)
                    case _             => 0.0
                }
                if cosines.isEmpty then 1.0 else cosines.sum / cosines.size
            end meanCosine
            def split(sub: List[Segment]): List[List[Segment]] =
                if sub.size < 2 || meanCosine(sub) >= config.coherenceFloor then List(sub)
                else
                    val gaps = sub.sliding(2).toList.map {
                        case a :: b :: Nil =>
                            vec(a).flatMap(x => vec(b).flatMap(x.cosine)).map(1.0 - _).getOrElse(Double.MaxValue)
                        case _ => 0.0
                    }
                    val cut           = gaps.zipWithIndex.maxBy(_._1)._2 + 1
                    val (left, right) = sub.splitAt(cut)
                    split(left) ++ split(right)
            split(run)
    end coherenceSplit

    private def dispatchBackground(
        ai: AI,
        transcript: Context,
        units: Chunk[Segment],
        state: CompactorState,
        band: List[Segment],
        rootSet: Set[Int]
    )(using Frame): CompactorState < (LLM & Async) =
        val runs = summaryCandidates(units, transcript, state, rootSet)
            .filter(run => !state.prepared.contains(run.head.id) && !state.book.summaryInflight.contains(run.head.id))
        if band.isEmpty && runs.isEmpty then Kyo.lift(state)
        else
            val ids     = band.map(_.id)
            val runHead = runs.headOption.map(_.head.id)
            AI.config.map { chatCfg =>
                // The default judge/summarizer inherits the ACTIVE chat config's transport (credentials,
                // apiUrl) and only adopts the provider's cheap-tier MODEL, so it produces a real
                // authenticated request rather than a credential-less catalog literal (which fails before
                // egress). Config.judge overrides it wholesale.
                val judgeCfg = config.judge.getOrElse(chatCfg.modelFrom(chatCfg.provider.small))
                // The judge scores the ambiguous band; the summarizer prepares one candidate run. Both run
                // fresh + withConfig(judge) so their prompts never touch the transcript, and both land in the
                // cell only, consulted as caches by the next fresh update (never as authority).
                val judgeWork: Unit < (LLM & Async & Abort[HttpException | AIGenException]) =
                    if band.isEmpty then Kyo.unit
                    else
                        AI.fresh(AI.withConfig(judgeCfg)(judgeCfg.provider.completion.apply(
                            judgeCfg,
                            judgeContext(band, transcript),
                            Chunk.empty
                        )))
                            .map(result => landVerdicts(ai, ids, result))
                // Clear the judge inflight ids on EVERY outcome (success, transport/decode failure, missing
                // key): only success used to release them, so any failure left the band permanently in
                // flight, monotonically poisoning every later band. The guard restores them regardless.
                val judgeGuarded: Unit < (LLM & Async) =
                    Abort.run[HttpException | AIGenException](judgeWork).map(_ => clearInflight(ai, ids))
                val summaryWork: Unit < (LLM & Async & Abort[HttpException | AIGenException]) =
                    runs.headOption match
                        case None => Kyo.unit
                        case Some(run) =>
                            AI.fresh(AI.withConfig(judgeCfg)(judgeCfg.provider.completion.apply(
                                judgeCfg,
                                summaryContext(run, transcript),
                                Chunk.empty
                            )))
                                .map(result => landSummary(ai, run, result, transcript.messages.size))
                val summaryGuarded: Unit < (LLM & Async) =
                    Abort.run[HttpException | AIGenException](summaryWork).map(_ => clearSummaryInflight(ai, runHead.toList))
                // Persist the inflight additions to the CURRENT cell (merged, never overwritten from the
                // render snapshot), then fork both jobs.
                landWith(ai)(st =>
                    st.copy(book =
                        st.book.copy(
                            inflight = st.book.inflight ++ ids.toSet,
                            summaryInflight = st.book.summaryInflight ++ runHead.toList.toSet
                        )
                    )
                )
                    .andThen(forkToCell(judgeGuarded))
                    .andThen(forkToCell(summaryGuarded))
                    .andThen(Kyo.lift(state))
            }
        end if
    end dispatchBackground

    // Forks LLM work through the isolate so it runs detached; results reach the shared cell via a Sync
    // write. Every backend failure (a missing key, a transport error, an undecodable reply) is contained
    // inside the fiber and discarded: a background failure just leaves structural-only edges, never
    // blocking or failing the turn.
    private def forkToCell(work: Unit < (LLM & Async & Abort[HttpException | AIGenException]))(using Frame): Unit < (LLM & Async) =
        val safe: Unit < (LLM & Async) = Abort.run[HttpException | AIGenException](work).map(_ => ())
        LLM.isolate.capture { state =>
            Fiber.initUnscoped(LLM.isolate.isolate(state, safe)).unit
        }
    end forkToCell

    private def landEmbeddings(ai: AI, ids: List[Int], embeddings: Chunk[Embedding])(using Frame): Unit < Sync =
        landWith(ai) { st =>
            val withVecs = ids.zip(embeddings.toList).foldLeft(st.vectors) { case (v, (id, emb)) => v.update(id, emb) }
            st.copy(vectors = withVecs, book = st.book.copy(embedInflight = ids.foldLeft(st.book.embedInflight)(_ - _)))
        }
    end landEmbeddings

    private def landVerdicts(ai: AI, ids: List[Int], result: Completion.Reply)(using Frame): Unit < Sync =
        // One verdict PER region, parsed from the judge's per-region reply lines; a region the reply does not
        // answer cleanly lands Uncertain, never Stale. inflight is cleared by the fork's guard, not here.
        val parsed = parseVerdicts(result.messages.map(_.content).mkString("\n"))
        landWith(ai)(st => st.copy(verdicts = ids.foldLeft(st.verdicts)((m, id) => m.update(id, parsed.getOrElse(id, Verdict.Uncertain)))))
    end landVerdicts

    // Parses the judge's reply into one verdict per region id. Only a line matching EXACTLY
    // `region <id>: STALE|SUPERSEDED|KEEP` (case-insensitive) yields a verdict; a line with any extra
    // text (a negation like "region 2 is not stale") does not match and leaves that region unanswered, so
    // substring "stale" can never invert a keep. STALE/SUPERSEDED map to Stale, KEEP to Keep.
    private[kyo] def parseVerdicts(text: String): Map[Int, Verdict] =
        text.linesIterator.foldLeft(Map.empty[Int, Verdict]) { (acc, raw) =>
            verdictLine.findFirstMatchIn(raw.trim) match
                case Some(m) =>
                    val id      = m.group(1).toInt
                    val verdict = if m.group(2).toLowerCase == "keep" then Verdict.Keep else Verdict.Stale
                    acc.updated(id, verdict)
                case None => acc
        }

    private val verdictLine = "(?i)^region\\s+(\\d+)\\s*:\\s*(stale|superseded|keep)\\s*$".r

    // The band-only judge context: a near-verifiable superseded/stale prompt plus each band unit's
    // content, so the judge sees nothing of the transcript beyond the units under evaluation. The strict
    // per-region output format is what landVerdicts parses into one verdict per region.
    private[kyo] def judgeContext(band: List[Segment], transcript: Context): Context =
        val head = Context(Chunk(SystemMessage(
            "You judge context regions for compaction. For EACH region, answer on its own line in EXACTLY the " +
                "form `region <id>: STALE` or `region <id>: KEEP` (STALE when its information is fully captured " +
                "elsewhere or no longer accurate, KEEP otherwise). Answer only that near-verifiable question; " +
                "do not judge importance or usefulness."
        )))
        band.foldLeft(head)((ctx, u) => ctx.add(UserMessage(s"region ${u.id}: ${unitContent(u, transcript)}", Absent)))
    end judgeContext

    // The run-only summarizer context: an extractive, preserve-facts prompt over the ORIGINAL unit
    // content, so a summary is produced from the transcript and never re-summarized from a prior summary.
    private def summaryContext(run: List[Segment], transcript: Context): Context =
        val head = Context(Chunk(SystemMessage(
            "Summarize the following regions extractively at temperature 0. Preserve every fact, identifier, " +
                "and decision; add nothing. Produce one compact factual summary of the original content."
        )))
        run.foldLeft(head)((ctx, u) => ctx.add(UserMessage(s"region ${u.id}: ${unitContent(u, transcript)}", Absent)))
    end summaryContext

    private def landSummary(ai: AI, run: List[Segment], result: Completion.Reply, at: Int)(using Frame): Unit < Sync =
        val text    = result.messages.map(_.content).mkString(" ").trim
        val summary = if text.isEmpty then s"[summary of regions ${run.head.id}..${run.last.id}]" else text
        val r       = Rendered(3, run.size, at, Chunk(SystemMessage(summary)))
        landWith(ai)(st =>
            st.copy(
                prepared = st.prepared.update(run.head.id, r),
                book = st.book.copy(summaryInflight = st.book.summaryInflight - run.head.id)
            )
        )
    end landSummary

    // ---- state cell access ----

    // The single cell read-modify-write shape: apply `f` to the CURRENT entry (present only), leaving the
    // Dict untouched when the entry is absent. All landers and commit go through this, so a write always
    // folds into the value concurrently landed since the caller's snapshot, never a blind overwrite.
    private def landWith(ai: AI)(f: CompactorState => CompactorState)(using Frame): Unit < Sync =
        val ref = LLM.internal.AIRef(ai)
        cell.updateAndGet { d =>
            d.get(ref) match
                case Absent      => d
                case Present(st) => d.update(ref, f(st))
        }.unit
    end landWith

    private def clearInflight(ai: AI, ids: List[Int])(using Frame): Unit < Sync =
        if ids.isEmpty then Kyo.unit
        else landWith(ai)(st => st.copy(book = st.book.copy(inflight = ids.foldLeft(st.book.inflight)(_ - _))))

    private def clearEmbedInflight(ai: AI, ids: List[Int])(using Frame): Unit < Sync =
        if ids.isEmpty then Kyo.unit
        else landWith(ai)(st => st.copy(book = st.book.copy(embedInflight = ids.foldLeft(st.book.embedInflight)(_ - _))))

    private def clearSummaryInflight(ai: AI, ids: List[Int])(using Frame): Unit < Sync =
        if ids.isEmpty then Kyo.unit
        else landWith(ai)(st => st.copy(book = st.book.copy(summaryInflight = ids.foldLeft(st.book.summaryInflight)(_ - _))))

    private def markEmbedUnsupported(provider: String)(using Frame): Unit < Sync =
        embedUnsupported.getAndUpdate(_ + provider).map { prior =>
            if prior.contains(provider) then Kyo.unit
            else Log.warn(s"kyo-ai compaction: provider $provider has no embeddings endpoint; skipping semantic edges for it")
        }

    private def stateFor(ai: AI, transcript: Context)(using Frame): CompactorState < Sync =
        // AIRef equality/hash is by the AI's stable id (LLM.scala), so a freshly-built ref looks up and
        // updates the stored entry without holding the referent. Every render sweeps GC'd (cleared) refs
        // (mirrors LLM.State.pruned) so a long-lived scope compactor serving many short-lived instances
        // never accumulates dead entries.
        val ref = LLM.internal.AIRef(ai)
        cell.updateAndGet { d0 =>
            val d = d0.filter((r, _) => r.isValid)
            d.get(ref) match
                case Present(st) =>
                    // Non-append guard: if history is no longer a prefix (setContext/forget/fresh rewrote
                    // it), reset and re-derive; safe because CompactorState is rebuildable.
                    if transcript.messages.size < st.book.seen then d.update(ref, CompactorState.empty)
                    else d
                case Absent =>
                    d.update(ref, CompactorState.empty)
            end match
        }.map(_.get(ref).getOrElse(CompactorState.empty))
    end stateFor

    private def commit(ai: AI, transcript: Context, state: CompactorState)(using Frame): Unit < Sync =
        // Merge the render's authority (renderings + observed transcript length) into the CURRENT cell
        // value, preserving vectors/verdicts/prepared/inflight landed by background fibers between this
        // render's snapshot and now. A blind overwrite would resurrect a cleared inflight without its
        // verdicts (stranding those ids) and drop concurrently-landed vectors (re-paying for them).
        landWith(ai)(current =>
            current.copy(renderings = state.renderings, book = current.book.copy(seen = transcript.messages.size))
        )
    end commit

    private[kyo] def viewTokens(view: Context, book: Book): Int =
        // Char-based estimate scaled by the calibrated ratio. "bytes"/"tokensPerByte" throughout this file
        // count String.length (UTF-16 code units), not encoded bytes; for non-ASCII the two differ, but the
        // EWMA calibration (calibrate) absorbs any consistent ratio, so ranking is unaffected.
        (byteSize(view) * book.tokensPerByte).toInt

    private def byteSize(ctx: Context): Int =
        ctx.messages.foldLeft(0)((n, m) => n + messageBytes(m))

    private def messageBytes(m: Message): Int =
        m match
            case AssistantMessage(c, calls) => c.length + calls.foldLeft(0)((n, x) => n + x.arguments.length)
            case ToolMessage(_, c)          => c.length
            case UserMessage(c, _)          => c.length
            case SystemMessage(c)           => c.length

    private[kyo] def effectiveLength(config: Compactor.Config, window: Int): Double =
        math.min(config.windowFraction * window, config.effectiveCap.toDouble)

    // ---- pure derivation helpers ----

    // The concatenated content of a unit's messages (assistant call arguments included), for token
    // extraction and recall slicing.
    private[kyo] def unitContent(seg: Segment, transcript: Context): String =
        seg.messages.map { idx =>
            transcript.messages(idx) match
                case AssistantMessage(c, calls) => if calls.isEmpty then c else c + " " + calls.map(_.arguments).mkString(" ")
                case m                          => m.content
        }.mkString(" ")

    private[kyo] def segmentBytes(seg: Segment, transcript: Context): Int =
        seg.messages.foldLeft(0)((n, idx) => n + messageBytes(transcript.messages(idx)))

    // Identifier-like tokens (min length 3, stoplisted words dropped). The Boolean marks a structured
    // token (path, dotted/camel/snake name, backticked span, id with digits) which weighs higher than
    // a bare word.
    private[kyo] def extractTokens(content: String): Chunk[(String, Boolean)] =
        Chunk.from(tokenRegex.findAllIn(content).toList).flatMap { raw =>
            val backticked = raw.length >= 2 && raw.startsWith("`") && raw.endsWith("`")
            val tok        = if backticked then raw.substring(1, raw.length - 1) else raw
            if tok.length < 3 || refStoplist.contains(tok.toLowerCase) then Chunk.empty
            else
                val structured =
                    backticked || tok.exists(c => c == '/' || c == '.' || c == '_' || c == ':' || c == '-' || c.isDigit) || tok.exists(
                        _.isUpper
                    )
                Chunk((tok, structured))
            end if
        }

    private def repoint(id: Int, superseded: Dict[Int, Int]): Int =
        @tailrec def loop(cur: Int): Int =
            superseded.get(cur) match
                case Present(next) if next != cur => loop(next)
                case _                            => cur
        loop(id)
    end repoint

    private def isSystemHead(seg: Segment, transcript: Context): Boolean =
        seg.messages.headOption match
            case Some(idx) =>
                transcript.messages(idx) match
                    case _: SystemMessage => true
                    case _                => false
            case None => false

    private def hasUser(seg: Segment, transcript: Context): Boolean =
        seg.messages.exists { idx =>
            transcript.messages(idx) match
                case _: UserMessage => true
                case _              => false
        }

    // The set of pinned roots (never demoted): leading system, first and latest user, unresolved
    // units, and the recent tail.
    private[kyo] def roots(units: Chunk[Segment], transcript: Context, book: Book): Set[Int] =
        val ordered = units.toList.sortBy(_.id)
        val sys     = ordered.headOption.filter(u => isSystemHead(u, transcript)).map(_.id).toSet
        val users   = ordered.filter(u => hasUser(u, transcript)).map(_.id)
        val task    = users.headOption.toSet
        val obj     = users.lastOption.toSet
        val unres   = ordered.filter(_.unresolved).map(_.id).toSet
        sys ++ task ++ obj ++ unres ++ tailUnits(units, book)
    end roots

    // The active tail: the most recent units, bounded by BOTH tailTurns and tailTokens (the oldest
    // fall out when the token bound is exceeded), always keeping at least the newest unit.
    private[kyo] def tailUnits(units: Chunk[Segment], book: Book): Set[Int] =
        val ordered = units.toList.sortBy(_.id).reverse
        @tailrec def loop(rem: List[Segment], count: Int, tokens: Int, acc: Set[Int]): Set[Int] =
            rem match
                case Nil => acc
                case u :: rest =>
                    if count >= config.tailTurns then acc
                    else if tokens + u.tokens > config.tailTokens && acc.nonEmpty then acc
                    else loop(rest, count + 1, tokens + u.tokens, acc + u.id)
        loop(ordered, 0, 0, Set.empty)
    end tailUnits

    // Referrers: a Ref-target unit id mapped to the set of units that reference it (co-pin candidates).
    private[kyo] def coPinReferrers(units: Chunk[Segment], graph: Graph): Map[Int, Set[Int]] =
        units.toList.foldLeft(Map.empty[Int, Set[Int]]) { (m, u) =>
            graph.edges.get(u.id).getOrElse(Chunk.empty).filter(_.kind == EdgeKind.Ref).foldLeft(m) { (mm, e) =>
                mm.updated(e.target, mm.getOrElse(e.target, Set.empty) + u.id)
            }
        }

    // ---- rendering-ladder level operations (pure) ----

    // L1 Compressed: strip trailing whitespace and collapse consecutive identical lines; NEVER applied
    // to whitespace-significant content (diffs/patches), which returns Absent.
    private[kyo] def compress(content: String): Maybe[String] =
        if isDiff(content) then Absent
        else
            val lines     = content.split("\n", -1).toList.map(_.replaceAll("[ \\t]+$", ""))
            val collapsed = collapseRepeats(lines)
            Present(collapsed.mkString("\n"))

    private[kyo] def isDiff(content: String): Boolean =
        content.startsWith("diff ") || content.linesIterator.exists(_.startsWith("@@"))

    private def collapseRepeats(lines: List[String]): List[String] =
        @tailrec def loop(rem: List[String], acc: List[String]): List[String] =
            rem match
                case Nil => acc.reverse
                case h :: t =>
                    val same = t.takeWhile(_ == h)
                    val rest = t.drop(same.size)
                    val n    = same.size + 1
                    loop(rest, (if n > 1 then s"$h  (x$n)" else h) :: acc)
        loop(lines, Nil)
    end collapseRepeats

    // L2 Elision: keep the head and tail of oversized content with a middle marker; Absent when the
    // content is within the threshold.
    private[kyo] def elide(content: String, threshold: Int): Maybe[String] =
        if content.length <= threshold then Absent
        else
            val lines = content.split("\n", -1)
            if lines.length <= 4 then
                Present(content.take(threshold / 2) + "\n...[elided]...\n" + content.takeRight(threshold / 2))
            else
                Present(
                    (lines.take(2).toList ++ List(s"...[${lines.length - 4} lines elided]...") ++ lines.takeRight(2).toList).mkString("\n")
                )
            end if

    // L2 Masking marker, assembled MECHANICALLY from the unit id and byte count, no model-generated
    // text; names what was removed and carries the recall id.
    private[kyo] def maskMarker(seg: Segment, transcript: Context): String =
        s"[compacted region ${seg.id}: ${segmentBytes(seg, transcript)} bytes omitted; call recall(${seg.id}) to restore]"

    // L2 Reference marker: a byte-identical repeat dedups to a pointer at the original region.
    private[kyo] def referenceMarker(seg: Segment, originalId: Int): String =
        s"[region ${seg.id} duplicates region $originalId; call recall(${seg.id}) to restore]"

    // The cache gate: T*r*S > w*(L_cut - S) - r*L_cut decides whether an edit saving S tokens above a
    // rendered tail of L_cut tokens is worth its cache-invalidation cost over the horizon.
    private[kyo] def cacheGatePasses(saved: Int, lCut: Int, cachedReadDiscount: Double, writePremium: Double): Boolean =
        config.horizonTurns * cachedReadDiscount * saved > writePremium * (lCut - saved) - cachedReadDiscount * lCut

    // The rot rule: a deferred deep edit is permitted once re-fetches reach the threshold OR the view
    // is at the effective budget; answer quality is never a trigger.
    private[kyo] def rotFires(refetchCount: Int, occupied: Int, e: Double): Boolean =
        refetchCount >= config.refetchThreshold || occupied.toDouble >= e

    // Re-fetch count for a demoted unit: recall calls for this unit id landed in the transcript AFTER the
    // unit's Rendered.at. Derived from the transcript (never stored, so the locked state shape is
    // untouched), which is what makes refetchThreshold a live rot-rule trigger: a demoted region the model
    // keeps recalling has churned enough to earn a deferred deep edit. The recall argument is the object
    // wire shape (`{"id":<n>}`), decoded through the same Recall schema the tool registers.
    private[kyo] def refetchCount(unitId: Int, transcript: Context, since: Int): Int =
        transcript.messages.zipWithIndex.foldLeft(0) { case (n, (m, i)) =>
            if i < since then n
            else
                m match
                    case AssistantMessage(_, calls) =>
                        n + calls.count(c => c.function == "recall" && recallArgId(c.arguments).contains(unitId))
                    case _ => n
        }

    // Extracts the recall unit id from the tool call's object-shaped arguments (`{"id":<n>}`). A pure,
    // Frame-free parse (the kyo package cannot derive a Frame for Json.decode in a pure helper), sufficient
    // for the rot-rule counting heuristic.
    private def recallArgId(arguments: String): Maybe[Int] =
        Maybe.fromOption(recallIdRegex.findFirstMatchIn(arguments).map(_.group(1).toInt))

    private val recallIdRegex = "\"id\"\\s*:\\s*(-?\\d+)".r

    // EWMA weight on the newest observed tokens-per-byte ratio (0.3: the new sample nudges, the
    // running calibration holds most of the weight). Internal, not a Config knob.
    private val calibrationSmoothing: Double = 0.3

    private val bareRefWeight: Double = 1.0

    // Cache-gate coefficients for a deep edit: r (cached-read discount) and w (write premium). Fixed
    // economics constants, not Config knobs.
    private val deepCacheDiscount: Double = 0.1
    private val deepWritePremium: Double  = 1.0

    private val tokenRegex = "`[^`]+`|[A-Za-z0-9_][A-Za-z0-9_./:-]*".r

    private val refStoplist: Set[String] = Set(
        "the",
        "and",
        "for",
        "that",
        "with",
        "this",
        "from",
        "are",
        "was",
        "not",
        "but",
        "you",
        "all",
        "can",
        "has",
        "its",
        "our",
        "out",
        "use",
        "have",
        "will",
        "your",
        "they",
        "them",
        "then",
        "than",
        "when",
        "what",
        "which",
        "would",
        "could",
        "should",
        "into",
        "over",
        "only",
        "also",
        "some",
        "such",
        "been",
        "were",
        "here",
        "there",
        "about",
        "after",
        "before"
    )
end Compactor

object Compactor:

    import internal.*

    /** A default-configured compactor. Allocates the atomic per-instance state cell (Sync). */
    def init(using Frame): Compactor < Sync =
        init(identity)

    /** A compactor with customized config. Applies `f` to the default `Config`, then allocates the
      * atomic per-instance state cell (Sync).
      */
    def init(f: Config => Config)(using Frame): Compactor < Sync =
        val config = validated(f(Config()))
        AtomicRef.init(Dict.empty[LLM.internal.AIRef, CompactorState]).map { cell =>
            AtomicRef.init(Set.empty[String]).map(unsupported => new Compactor(cell, config, unsupported))
        }
    end init

    // Validates the config's ordering and positivity invariants at construction (the sibling kyo.ai.Config
    // clamps temperature; here an out-of-order or negative knob is a caller error surfaced loudly rather
    // than a silently thrashing compactor). Returns the config unchanged when valid.
    private def validated(c: Config): Config =
        def require(cond: Boolean, msg: String): Unit =
            if !cond then throw IllegalArgumentException(s"Compactor.Config: $msg")
        require(
            c.updateTargetFraction < c.updateTriggerFraction,
            "updateTargetFraction must be below updateTriggerFraction (the hysteresis band)"
        )
        require(c.updateTargetFraction > 0.0, "updateTargetFraction must be positive")
        require(c.hardWindowFraction > 0.0, "hardWindowFraction must be positive")
        require(c.windowFraction > 0.0, "windowFraction must be positive")
        require(c.effectiveCap > 0, "effectiveCap must be positive")
        require(c.tailTurns > 0, "tailTurns must be positive")
        require(c.tailTokens > 0, "tailTokens must be positive")
        require(c.bandSize > 0, "bandSize must be positive")
        require(c.restartWeight > 0.0 && c.restartWeight < 1.0, "restartWeight must be in (0, 1)")
        require(c.supersessionPenalty >= 0.0, "supersessionPenalty must be non-negative")
        require(c.referenceWeight >= 0.0 && c.adjacencyWeight >= 0.0 && c.semanticWeight >= 0.0, "edge weights must be non-negative")
        require(c.semanticNeighbors > 0, "semanticNeighbors must be positive")
        require(c.semanticDecayHalfLife > 0, "semanticDecayHalfLife must be positive")
        require(c.elisionThreshold > 0, "elisionThreshold must be positive")
        c
    end validated

    /** The overridable-defaults record. Defaults are untuned against real traces; they affect ranking
      * quality, not correctness. `init` validates the ordering and positivity invariants (for example
      * `updateTargetFraction` must sit below `updateTriggerFraction`).
      */
    final case class Config(
        windowFraction: Double = 0.5,
        effectiveCap: Int = 48000,
        updateTriggerFraction: Double = 0.7,
        updateTargetFraction: Double = 0.45,
        hardWindowFraction: Double = 0.9,
        tailTurns: Int = 10,
        tailTokens: Int = 12000,
        bandSize: Int = 16,
        restartWeight: Double = 0.15,
        supersessionPenalty: Double = 0.2,
        referenceWeight: Double = 3.0,
        adjacencyWeight: Double = 1.0,
        semanticWeight: Double = 0.5,
        semanticNeighbors: Int = 5,
        semanticFloor: Double = 0.7,
        semanticDecayHalfLife: Int = 200,
        coherenceFloor: Double = 0.55,
        refetchThreshold: Int = 2,
        horizonTurns: Int = 10,
        elisionThreshold: Int = 8000,
        seeds: Config.SeedWeights = Config.SeedWeights(),
        judge: Maybe[kyo.ai.Config] = Absent
    ) derives CanEqual

    object Config:
        /** The five Personalized-PageRank seed-vector shares. An absent category folds its share into
          * the tail rather than being dropped or redistributed uniformly.
          */
        final case class SeedWeights(
            objective: Double = 0.35,
            task: Double = 0.20,
            tail: Double = 0.25,
            unresolved: Double = 0.15,
            system: Double = 0.05
        ) derives CanEqual
    end Config

    private[kyo] object internal:

        // Accumulator for the grouping fold: the growing message indices of one unit plus its still
        // open (unanswered) call ids.
        final private[kyo] case class Building(id: Int, indices: Chunk[Int], open: Set[String])

        /** The atomic node (the design's "unit"): the fused messages of one logical unit, its
          * unresolved flag, and its token estimate. Named `Segment` to avoid shadowing `scala.Unit`.
          * `messages` holds the transcript INDICES of the segment's messages (derive-don't-store: the
          * content lives in the transcript). id = first-message index.
          */
        final case class Segment(id: Int, messages: Chunk[Int], unresolved: Boolean, tokens: Int)

        /** A frozen view decision for a demoted unit. level 0..4 (Verbatim..Omitted); covers = units a
          * summary replaces; at = the update index the decision was taken (rot-rule accounting);
          * replacement = the messages project emits in the unit's place.
          */
        final case class Rendered(level: Int, covers: Int, at: Int, replacement: Chunk[Message])

        /** What the compactor did and when (underivable). seen = transcript length at last render
          * (append-only guard); tokensPerByte = estimator calibration; inflight = band units dispatched
          * for judging (dedupe, one batch at a time); embedInflight = units dispatched for embedding but
          * not yet landed; summaryInflight = run heads dispatched for summarization but not yet landed.
          * The three inflight sets stop a slow endpoint from re-issuing the same paid batch every render.
          */
        final case class Book(
            seen: Int,
            tokensPerByte: Double,
            inflight: Set[Int],
            embedInflight: Set[Int],
            summaryInflight: Set[Int]
        )

        /** Per-instance persisted state: the four derived-result caches plus the book. Everything else
          * (grouping, edges, supersession, scores, occupancy) is derived fresh.
          */
        final case class CompactorState(
            renderings: Dict[Int, Rendered],
            vectors: Dict[Int, Embedding],
            verdicts: Dict[Int, Verdict],
            prepared: Dict[Int, Rendered],
            book: Book
        )

        object CompactorState:
            val empty: CompactorState =
                CompactorState(Dict.empty, Dict.empty, Dict.empty, Dict.empty, Book(0, 0.25, Set.empty, Set.empty, Set.empty))

        /** The cheap-tier judge's near-verifiable verdict on a band unit. */
        enum Verdict derives CanEqual:
            case Keep, Stale, Uncertain

        /** The derived unit graph (fresh each pass): out-edges per unit, by type. Never stored. */
        final case class Graph(edges: Dict[Int, Chunk[Edge]]):
            def isEmpty: Boolean = edges.isEmpty
        object Graph:
            val empty: Graph = Graph(Dict.empty)

        enum EdgeKind derives CanEqual:
            case Adj, Ref, Sem

        final case class Edge(target: Int, kind: EdgeKind, weight: Double)

        /** The recall tool's single input, wrapped in an object so the wire tool schema is
          * `{"id":{"type":"integer"}}`: providers reject a bare `{"type":"integer"}` parameter schema (the
          * same object-schema constraint the result_tool envelope satisfies).
          */
        final case class Recall(id: Int) derives Schema, CanEqual

        /** The recall tool the Compactor registers per CALLING instance: kind = Read, NO compactionKey (a
          * recall read is a rot-rule re-fetch signal, never a supersession trigger). It is bound to `ai` at
          * the gen seam (LLM.eval), so it resolves against ONLY that instance's own cell entry and
          * transcript, never another session's (unit ids are transcript indices and collide across
          * sessions). Its run slices the LIVE transcript for the requested unit id and returns the verbatim
          * content as a fresh ToolMessage at the tail, never editing the frozen prefix, never duplicating a
          * historical call id.
          */
        def recallTool(compactor: Compactor, ai: AI)(using Frame): Tool[LLM] =
            val ref = LLM.internal.AIRef(ai)
            Tool.init[Recall](
                name = "recall",
                description =
                    "Recall the full original content of a demoted region by its unit id (the id carried " +
                        "in the region's marker). Returns the verbatim content as a fresh tool result.",
                kind = Tool.Kind.Read
            ) { (arg: Recall) =>
                val id = arg.id
                // Resolve against ONLY the calling instance's own state and transcript.
                LLM.state.map { st =>
                    compactor.cell.get.map { d =>
                        val found =
                            d.get(ref) match
                                case Present(cs) if cs.renderings.contains(id) =>
                                    st.instances.get(ref) match
                                        case Present(session) =>
                                            compactor.group(session.context, cs.book).filter(_.id == id).headMaybe match
                                                case Present(u) => Present(compactor.unitContent(u, session.context))
                                                case Absent     => Absent
                                        case Absent => Absent
                                case _ => Absent
                        found.getOrElse(s"no such recallable region: $id")
                    }
                }
            }.asInstanceOf[Tool[LLM]]
        end recallTool

    end internal
end Compactor
