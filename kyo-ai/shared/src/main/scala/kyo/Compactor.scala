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
    private[kyo] val config: Compactor.Config
) extends AI.Enablement[Any]:

    import Compactor.internal.*

    /** The mechanism seam: consulted by `LLM.eval`/`streamAgainst` between the context read and
      * `enrichedContext`. Runs the synchronous model-free steps (group, queue embeddings, stash
      * landed results, occupancy check), then either the fast path (emit current renderings
      * unchanged) or a deterministic update, forking all model work in the background. Returns the
      * projected view; the forced path aborts with `AIContextOverflowException` rather than send an
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
                // group + queue embeddings for new units, then stash landed background results WITHOUT
                // touching renderings (view byte-stable at this step).
                val units   = group(transcript, state.book)
                val stashed = stash(state)
                queueEmbeddings(ai, transcript, units, stashed).map { afterQueue =>
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
            case Absent => ()
            case Present(u) =>
                val ref          = LLM.internal.AIRef(ai)
                val requestBytes = byteSize(request)
                cell.updateAndGet { d =>
                    d.get(ref) match
                        case Absent => d
                        case Present(st) =>
                            val obs = if requestBytes <= 0 then st.book.tokensPerByte
                            else u.inputTokens.toDouble / requestBytes.toDouble
                            val blended =
                                (1.0 - calibrationSmoothing) * st.book.tokensPerByte + calibrationSmoothing * obs
                            d.update(ref, st.copy(book = st.book.copy(tokensPerByte = blended)))
                }.unit
    end calibrate

    private[kyo] def enableIn(env: AIEnv)(using Frame): AIEnv =
        env.compactor(this).addTools(Chunk(recallTool(this).asInstanceOf[Tool[Any]]))

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
            val vecUnits                = ordered.filter(u => state.vectors.contains(u.id))
            def vec(id: Int): Embedding = state.vectors.get(id).getOrElse(sys.error("missing vector"))
            def neighbors(u: Segment): List[(Int, Double)] =
                vecUnits.filter(_.id != u.id).flatMap { v =>
                    vec(u.id).cosine(vec(v.id)) match
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
            ordered.foldLeft((Dict.empty[Int, Int], Map.empty[String, Int])) { case ((sup, last), u) =>
                keys.get(u.id) match
                    case Absent => (sup, last)
                    case Present((k, _)) =>
                        last.get(k) match
                            case Some(prev) => (sup.update(prev, u.id), last.updated(k, u.id))
                            case None       => (sup, last.updated(k, u.id))
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

    // The row sums of the row-normalized transition matrix (1.0 for a unit with out-edges, 0.0 for a
    // dangling unit whose mass stays on the restart/seed rather than dividing by zero).
    private[kyo] def transitionRows(units: Chunk[Segment], graph: Graph): Dict[Int, Double] =
        val rows = units.toList.map { u =>
            val es  = graph.edges.get(u.id).getOrElse(Chunk.empty)
            val sum = es.foldLeft(0.0)((a, e) => a + e.weight)
            if sum <= 0.0 then (u.id, 0.0) else (u.id, es.foldLeft(0.0)((a, e) => a + e.weight / sum))
        }
        Dict.from(rows.toMap)
    end transitionRows

    // ---- view / project (the rendering ladder) ----

    /** Pure: walks units in transcript order, emitting each unit's original messages (no renderings
      * entry) or its `Rendered.replacement`. A summary's entry sits on its run's first unit with
      * covers=n; the walk skips the next n-1 units, so it is total and nothing double-renders. Markers
      * are plain SystemMessages; whole-unit replacement keeps tool_use/tool_result paired, so the view
      * is always provider-legal.
      */
    private[kyo] def project(transcript: Context, state: CompactorState): Context =
        val rendering = state.renderings
        if rendering.isEmpty then transcript
        else
            val units   = group(transcript, state.book)
            val ordered = units.toList.sortBy(_.id)
            val msgToId = ordered.foldLeft(Map.empty[Int, Int])((m, u) => u.messages.foldLeft(m)((mm, idx) => mm.updated(idx, u.id)))
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
                        if covered.contains(uid) then (acc, emitted)
                        else if emitted.contains(uid) then (acc, emitted)
                        else
                            rendering.get(uid) match
                                case Present(r) => (acc ++ r.replacement, emitted + uid)
                                case Absent     => (acc.append(m), emitted)
                        end if
                }
            Context(out)
        end if
    end project

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
            val band       = judgeBand(units, transcript, state).map(_.id).toSet
            val dupOf      = duplicateMap(units, transcript)
            // ascending score, nudged by BAND-LOCAL verdicts (Stale demotes earlier, Keep holds back). A
            // verdict for a unit outside the fresh band is discarded (never consulted).
            def effScore(u: Segment): Double =
                val base = scores.get(u.id).getOrElse(0.0)
                if band.contains(u.id) then
                    state.verdicts.get(u.id) match
                        case Present(Verdict.Stale) => base - 1e6
                        case Present(Verdict.Keep)  => base + 1e6
                        case _                      => base
                else base
                end if
            end effScore
            val candidates =
                units.toList
                    .filter(u => !rootSet.contains(u.id) && !u.unresolved)
                    .sortBy(effScore)
            @tailrec def demote(rem: List[Segment], st: CompactorState): CompactorState =
                if viewTokens(project(transcript, st), st.book) <= target then st
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
                                            // OR budget exhaustion); a blocked deep edit records book.skip so it does not
                                            // re-fire every turn.
                                            val occupied = viewTokens(project(transcript, st), st.book)
                                            val after = viewTokens(
                                                project(transcript, st.copy(renderings = st.renderings.update(u.id, r))),
                                                st.book
                                            )
                                            val saved   = math.max(0, occupied - after)
                                            val curAt   = st.renderings.get(u.id).map(_.at).getOrElse(0)
                                            val refetch = refetchCount(u.id, transcript, curAt)
                                            val allowed = cacheGatePasses(saved, occupied, deepCacheDiscount, deepWritePremium) || rotFires(
                                                refetch,
                                                occupied,
                                                e
                                            )
                                            if allowed then
                                                demote(
                                                    rest,
                                                    st.copy(
                                                        renderings = st.renderings.update(u.id, r),
                                                        book = st.book.copy(lastDeepEdit = updateIdx)
                                                    )
                                                )
                                            else
                                                demote(rest, st.copy(book = st.book.copy(skip = st.book.skip.update(u.id, occupied))))
                                            end if
                                        else
                                            demote(rest, st.copy(renderings = st.renderings.update(u.id, r)))
                                        end if
                            end if
            val demoted = demote(candidates, state)
            dispatchBackground(ai, transcript, units, demoted).map { dispatched =>
                commit(ai, transcript, dispatched).andThen(project(transcript, dispatched))
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
        // Dedup embeddings by landed VECTORS only; `book.inflight` is reserved for the judge batch, so
        // embedding work never empties the ambiguous band that the judge and verdict-nudging consult.
        val need = units.toList.filter(u => !state.vectors.contains(u.id))
        if need.isEmpty then Kyo.lift(state)
        else
            val ids   = need.map(_.id)
            val batch = Chunk.from(need.map(u => unitContent(u, transcript)))
            AI.config.map { chatCfg =>
                // Resolve the embedder config ONCE and dispatch through THAT provider's own backend,
                // never the chat provider's backend with the embedder config as an argument.
                val e = chatCfg.embedder.getOrElse(chatCfg)
                val work: Unit < (LLM & Async & Abort[HttpException | AIGenException]) =
                    AI.fresh(e.provider.completion.embed(e, batch)).map(embeddings => landEmbeddings(ai, ids, embeddings))
                forkToCell(work).andThen(Kyo.lift(state))
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
        units.toList
            .filter(u => !roots0.contains(u.id) && !u.unresolved && !state.book.inflight.contains(u.id))
            .sortBy(u => scores.get(u.id).getOrElse(0.0))
            .take(config.bandSize)
    end judgeBand

    // Contiguous runs (>= 2 units) of non-root demotable units, split at roots: each is one summarization
    // candidate the background summarizer prepares (adopted at the next update after re-validation).
    private[kyo] def summaryCandidates(units: Chunk[Segment], transcript: Context, state: CompactorState): List[List[Segment]] =
        val rootSet = roots(units, transcript, state.book)
        val ordered = units.toList.sortBy(_.id)
        @tailrec def group(rem: List[Segment], cur: List[Segment], acc: List[List[Segment]]): List[List[Segment]] =
            rem match
                case Nil => (if cur.nonEmpty then cur.reverse :: acc else acc).reverse
                case u :: rest =>
                    if rootSet.contains(u.id) || u.unresolved then
                        group(rest, Nil, if cur.nonEmpty then cur.reverse :: acc else acc)
                    else group(rest, u :: cur, acc)
        group(ordered, Nil, Nil).filter(_.size >= 2)
    end summaryCandidates

    private def dispatchBackground(ai: AI, transcript: Context, units: Chunk[Segment], state: CompactorState)(using
        Frame
    ): CompactorState < (LLM & Async) =
        val band = judgeBand(units, transcript, state)
        val runs = summaryCandidates(units, transcript, state).filter(run => !state.prepared.contains(run.head.id))
        if band.isEmpty && runs.isEmpty then Kyo.lift(state)
        else
            val ids    = band.map(_.id)
            val marked = state.copy(book = state.book.copy(inflight = state.book.inflight ++ ids.toSet))
            AI.config.map { chatCfg =>
                val judgeCfg = config.judge.getOrElse(chatCfg.provider.small)
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
                forkToCell(judgeWork).andThen(forkToCell(summaryWork)).andThen(Kyo.lift(marked))
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
        val ref = LLM.internal.AIRef(ai)
        cell.updateAndGet { d =>
            d.get(ref) match
                case Absent => d
                case Present(st) =>
                    val withVecs = ids.zip(embeddings.toList).foldLeft(st.vectors) { case (v, (id, emb)) => v.update(id, emb) }
                    d.update(ref, st.copy(vectors = withVecs))
        }.unit
    end landEmbeddings

    private def landVerdicts(ai: AI, ids: List[Int], result: Completion.Result)(using Frame): Unit < Sync =
        val text = result.messages.map(_.content).mkString(" ").toLowerCase
        val v =
            if text.contains("stale") || text.contains("superseded") then Verdict.Stale
            else if text.contains("keep") then Verdict.Keep
            else Verdict.Uncertain
        val ref = LLM.internal.AIRef(ai)
        cell.updateAndGet { d =>
            d.get(ref) match
                case Absent => d
                case Present(st) =>
                    val withV   = ids.foldLeft(st.verdicts)((m, id) => m.update(id, v))
                    val cleared = ids.foldLeft(st.book.inflight)((s, id) => s - id)
                    d.update(ref, st.copy(verdicts = withV, book = st.book.copy(inflight = cleared)))
        }.unit
    end landVerdicts

    // The band-only judge context: a near-verifiable superseded/stale prompt plus each band unit's
    // content, so the judge sees nothing of the transcript beyond the units under evaluation.
    private def judgeContext(band: List[Segment], transcript: Context): Context =
        val head = Context(Chunk(SystemMessage(
            "You judge context regions for compaction. For each region, answer only whether it is " +
                "SUPERSEDED or STALE (its information is fully captured elsewhere or no longer accurate). " +
                "Answer only that near-verifiable question; do not judge importance or usefulness."
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

    private def landSummary(ai: AI, run: List[Segment], result: Completion.Result, at: Int)(using Frame): Unit < Sync =
        val text    = result.messages.map(_.content).mkString(" ").trim
        val summary = if text.isEmpty then s"[summary of regions ${run.head.id}..${run.last.id}]" else text
        val r       = Rendered(3, run.size, at, Chunk(SystemMessage(summary)))
        val ref     = LLM.internal.AIRef(ai)
        cell.updateAndGet { d =>
            d.get(ref) match
                case Absent      => d
                case Present(st) => d.update(ref, st.copy(prepared = st.prepared.update(run.head.id, r)))
        }.unit
    end landSummary

    // ---- state cell access ----

    private def stateFor(ai: AI, transcript: Context)(using Frame): CompactorState < Sync =
        // AIRef equality/hash is by the AI's stable id (LLM.scala), so a freshly-built ref looks up,
        // updates, and reset the stored entry without holding the referent; GC pruning drops stale keys.
        val ref = LLM.internal.AIRef(ai)
        cell.updateAndGet { d =>
            d.get(ref) match
                case Present(st) =>
                    // Non-append guard: if history is no longer a prefix (setContext/forget/fresh rewrote
                    // it), reset and re-derive; safe because CompactorState is rebuildable.
                    if transcript.messages.size < st.book.seen then d.update(ref, CompactorState.empty)
                    else d
                case Absent =>
                    d.update(ref, CompactorState.empty)
        }.map(_.get(ref).getOrElse(CompactorState.empty))
    end stateFor

    private def commit(ai: AI, transcript: Context, state: CompactorState)(using Frame): Unit < Sync =
        // Record the transcript length observed at this render so the next render's non-append guard
        // can detect a shrunk/rewritten history (setContext/forget/fresh) and reset.
        val ref = LLM.internal.AIRef(ai)
        cell.updateAndGet { d =>
            if d.contains(ref) then d.update(ref, state.copy(book = state.book.copy(seen = transcript.messages.size)))
            else d
        }.unit
    end commit

    private[kyo] def stash(state: CompactorState): CompactorState =
        // Landed background results (vectors/verdicts/prepared) are read into `state` by `stateFor`'s
        // cell read and surface at the next render; this pass keeps the state, so no rendering changes
        // and the emitted view stays byte-identical at this step.
        state

    private def viewTokens(view: Context, book: Book): Int =
        // byte-based estimate scaled by the calibrated ratio.
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

    // Re-fetch count for a demoted unit: recall(id) calls landed in the transcript AFTER the unit's
    // Rendered.at. Derived from the transcript (never stored, so the locked state shape is untouched),
    // which is what makes refetchThreshold a live rot-rule trigger: a demoted region the model keeps
    // recalling has churned enough to earn a deferred deep edit.
    private[kyo] def refetchCount(unitId: Int, transcript: Context, since: Int): Int =
        transcript.messages.zipWithIndex.foldLeft(0) { case (n, (m, i)) =>
            if i < since then n
            else
                m match
                    case AssistantMessage(_, calls) => n + calls.count(c => c.function == "recall" && c.arguments.trim == unitId.toString)
                    case _                          => n
        }

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
        AtomicRef.init(Dict.empty[LLM.internal.AIRef, CompactorState]).map(cell => new Compactor(cell, f(Config())))

    /** The overridable-defaults record. Every knob ships with the seed design's extrapolated default
      * (untuned against real traces; correctness is unaffected, only ranking quality). See each field's
      * requirement in `02-public-api.yaml §3.1`.
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
          * (append-only guard); lastDeepEdit = where rot-rule re-fetch counting starts; skip = blocked
          * deep edits (unit -> occupancy at block time); tokensPerByte = estimator calibration;
          * inflight = units dispatched for embedding/judging (dedupe).
          */
        final case class Book(seen: Int, lastDeepEdit: Int, skip: Dict[Int, Int], tokensPerByte: Double, inflight: Set[Int])

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
                CompactorState(Dict.empty, Dict.empty, Dict.empty, Dict.empty, Book(0, 0, Dict.empty, 0.25, Set.empty))

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

        /** The recall tool the Compactor registers: kind = Read, NO compactionKey (a recall read is a
          * rot-rule re-fetch signal, never a supersession trigger). Its run slices the LIVE
          * transcript for the requested unit id and returns the verbatim content as a fresh ToolMessage
          * at the tail, never editing the frozen prefix, never duplicating a historical call id.
          */
        def recallTool(compactor: Compactor)(using Frame): Tool[LLM] =
            Tool.init[Int](
                name = "recall",
                description =
                    "Recall the full original content of a demoted region by its unit id (the id carried " +
                        "in the region's marker). Returns the verbatim content as a fresh tool result.",
                kind = Tool.Kind.Read
            ) { (id: Int) =>
                // Find the instance whose compaction state marks `id` demoted, slice its live transcript
                // for that unit, and return the original content; the tool machinery lands it as a fresh
                // ToolMessage at the tail, so no frozen prefix is edited and no historical id is reused.
                LLM.state.map { st =>
                    compactor.cell.get.map { d =>
                        val found = d.foldLeft(Absent: Maybe[String]) { (acc, ref, cs) =>
                            acc match
                                case Present(_) => acc
                                case Absent =>
                                    if !cs.renderings.contains(id) then Absent
                                    else
                                        st.instances.get(ref) match
                                            case Absent => Absent
                                            case Present(session) =>
                                                compactor.group(session.context, cs.book).filter(_.id == id).headMaybe match
                                                    case Present(u) => Present(compactor.unitContent(u, session.context))
                                                    case Absent     => Absent
                        }
                        found.getOrElse(s"no such recallable region: $id")
                    }
                }
            }.asInstanceOf[Tool[LLM]]

    end internal
end Compactor
