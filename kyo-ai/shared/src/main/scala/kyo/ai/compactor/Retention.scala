package kyo.ai.compactor

import kyo.*
import kyo.Compactor
import kyo.Compactor.Mechanism
import kyo.Compactor.Tuning
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*
import scala.annotation.tailrec

/** Raw retention: the irreversible half. Everything else the compactor does is recoverable, because raw still holds the text and recall can fetch it back. Past the retention cap that stops being true: the oldest content is EVICTED, replaced in place by a coarse band marker plus content-freed tombstones. This is the only step that writes raw, and a watermark band keeps it rare and bulky rather than per-turn.
  */
private[kyo] trait Retention:
    self: Compactor.internal.Default =>

    import Model.*
    // The same unqualified access to the policy and the measured constants the rest of the default has.
    import calibration.*
    import tuning.*

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
        rawRetentionCap.getOrElse(rawRetentionWidths * config.modelContextWindow)

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
    // analysis slots, and the recall and expansion records whose ordinal is forgotten. The coarse
    // band replaces them; pruning the analysis slots alongside the summaries keeps a summary key and
    // an analysis key from aliasing across a forget. Both need records go, for the same reason: a
    // record that outlived its content seeds liveness into bytes that no longer exist.
    def dropForgottenState(state: Compaction.State, forgotten: Set[Int]): Compaction.State =
        state.copy(
            summaries = state.summaries.filter(s => !forgotten.contains(s.start)),
            analyses = state.analyses.filter(a => !forgotten.contains(a.ordinal)),
            recalls = state.recalls.filter(r => !forgotten.contains(r.region)),
            expansions = state.expansions.filter(e => !forgotten.contains(e.region))
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
                // Evictability is decided at the MARKER's grain, not the region's. A summary marker
                // stands for a whole span, but only the span's HEAD region appears in `demoted`, so a
                // region-grain filter could forget the head alone: recall would then refuse for the
                // whole span while most of its bytes were still live, the span's write-once summary
                // would be dropped though its surviving members still need one, and the rest of the
                // span could only be reclaimed at some later boundary that re-spanned it. A whole
                // origin range goes or none of it does.
                val byId = units.foldLeft(Dict.empty[Int, Region])((m, u) => m.update(u.id, u))
                val ranges =
                    demoted.toMap.toList.sortBy(_._1).flatMap { (start, o) =>
                        val members = (o.start until o.end).toList.flatMap(id => byId.get(id).toList)
                        val whole =
                            members.nonEmpty &&
                                members.forall(u =>
                                    !headIds.contains(u.id) && !tailIds.contains(u.id) &&
                                        !u.indices.headOption.exists(i => ctx.raw(i).origin.isDefined)
                                )
                        if whole then List(members) else Nil
                    }
                // Need-aware ORDERING, not a veto, and the distinction is what keeps the retention cap
                // unconditional. With the framework as a second producer of need records, "has a record"
                // becomes framework-producible, so a veto keyed on it could be held open by a false fire
                // and under cap pressure would either starve eviction or break its own promise. Preferring
                // evidence-free victims spends the cap on content nobody has asked for, and when those
                // cannot free enough, eviction continues into evidenced ranges in ordinal order.
                //
                // Evidence means a model recall record OR a framework expansion record. The recall half is
                // admitted deliberately: an earlier change that ordered by recall evidence alone was
                // reverted pending this decision, so the ordering ships with both producers or neither.
                val state = ctx.compactionState
                // Each producer's records are read only while its mechanism is enabled, matching what the
                // seed path already does with a leftover record: a disabled mechanism must not keep
                // steering, here or there.
                val evidenced =
                    (if mechanisms.contains(Mechanism.Recall) then state.recalls.map(_.region).toSet else Set.empty) ++
                        (if mechanisms.contains(Mechanism.Expansion) then state.expansions.map(_.region).toSet else Set.empty)
                val (unasked, asked) = ranges.partition(members => !members.exists(u => evidenced.contains(u.id)))
                val evictable        = unasked ++ asked
                // Picked a WHOLE RANGE at a time, never a region, for the same reason `evictable` is built
                // per range: a partly-forgotten range leaves recall refusing for content still largely
                // live, drops a summary slot its survivors still need, and hands the view repair a marker
                // whose range is only partly gone.
                @tailrec def pick(rem: List[List[Region]], freed: Int, acc: List[Region]): List[Region] =
                    if rawSum - freed <= low then acc
                    else
                        rem match
                            case Nil => acc
                            case members :: rest =>
                                val save =
                                    members.foldLeft(0)((n, u) => n + u.indices.foldLeft(0)((k, i) => k + stampedTokens(ctx.raw(i))))
                                pick(rest, freed + save, members.reverse ::: acc)
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
                    // The view repair, and the reason it belongs HERE rather than anywhere else.
                    //
                    // Every region this forgets was, by construction, demoted by the view being
                    // served this turn: `evictable` requires it. So each one has a fresh marker
                    // whose descriptor ends "recall(N) restores verbatim" for bytes that no longer
                    // exist. Left alone, the view invites a call that can only be refused, and it
                    // keeps inviting it until the next boundary re-renders, which is precisely the
                    // stretch the cache-stability guarantee makes long.
                    //
                    // The replacement is not a new kind of marker: it is the coarse band head this
                    // same pass just minted into raw, which carries the band's own origin and says
                    // "no longer recallable". That is byte-identical to what the next boundary would
                    // render for the band, so the repair removes churn rather than adding any, and
                    // the prefix invalidation is already paid by this boundary.
                    //
                    // Never strip the origin instead: with no matching view origin, a recall falls
                    // through to the by-region lookup, finds the folded band, and returns the coarse
                    // head plus empty tombstones as a SUCCESS, which is worse than the refusal.
                    val (compacted2, _) =
                        ctx.compacted.foldLeft((Chunk.empty[Message], Set.empty[Int])) {
                            case ((acc, seen), m) =>
                                val run = m.origin match
                                    case Present(o) => idxToRun.get(o.start)
                                    case Absent     => Absent
                                run match
                                    case Present((a, _)) =>
                                        if seen.contains(a) then (acc, seen)
                                        else (acc.append(raw2(a)), seen + a)
                                    case Absent => (acc.append(m), seen)
                                end match
                        }
                    ctx.copy(raw = raw2, compacted = compacted2)
                        .withCompaction(dropForgottenState(ctx.compactionState, forgotten))
                end if
            end if
        end if
    end evict
end Retention
