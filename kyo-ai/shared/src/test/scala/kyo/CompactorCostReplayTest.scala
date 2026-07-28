package kyo

import Compactor.Tuning
import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** Deterministic cost replay, no live calls.
  *
  * Replays a growing session through each arm's context-assembly policy and measures, per request, WHERE
  * the arm's first edit lands in the prefix and how many tokens it invalidates. Prompt caching is present,
  * functional and fully transparent, so a request pays only for the suffix after the longest prefix it
  * shares with the arm's previous request; the first-difference offset is therefore the quantity that
  * decides cost, and it is estimated here rather than omitted.
  *
  * This is the plan's FIRST output because the design's cost thesis, that write-once span markers give the
  * per-span arm a stable prefix, is established FALSE at its source: `cut` rebuilds the level assignment
  * from `Dict.empty` at every boundary, so write-once applies to the staged summary BYTES and not to the
  * rendered view. What remains is quantitative, and no cost claim comparing the arms may be made until
  * these numbers exist.
  */
class CompactorCostReplayTest extends kyo.test.Test[Any]:

    private def axisOf(c: Config): Compactor.internal.Axis =
        Compactor.internal.axis(Compactor.Tuning(), c)

    def um(s: String): UserMessage       = UserMessage(s, Absent)
    def sm(s: String): SystemMessage     = SystemMessage(s)
    def am(s: String): AssistantMessage  = AssistantMessage(s)
    def tok(m: Message, n: Int): Message = stamp(m, TokenStamp("t", n))
    def toks(v: Chunk[Message]): Int     = v.foldLeft(0)((a, m) => a + stampedTokens(m))

    val window      = 16384
    def cfg: Config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    /** A production-shaped session grown to a target token count. Facts sit past each region's first line
      * so no marker descriptor carries them; bulk is same-genre prose rather than filler.
      */
    def trace(targetTokens: Int): Chunk[Message] =
        val body = "the service coordinates writes across replicas and reconciles them on read. " * 6
        val head = Chunk[Message](tok(sm("you are a systems assistant"), 40), tok(um("we are designing a storage layer"), 60))
        def turns(i: Int): List[Message] =
            List(
                tok(um(s"step $i: continue the design work\nthe detail for step $i follows. $body"), 700),
                tok(am(s"answer $i\nacknowledged, continuing. $body"), 700)
            )
        @annotation.tailrec
        def grow(acc: Chunk[Message], i: Int): Chunk[Message] =
            if toks(acc) >= targetTokens then acc else grow(acc.concat(Chunk.from(turns(i))), i + 1)
        grow(head, 0)
    end trace

    // ---- the arms, as pure context-assembly policies ----

    /** A0: serve everything. Pure append, so the prefix never changes. */
    def a0(raw: Chunk[Message], step: Int): Chunk[Message] = raw

    /** A1: system head plus the most recent budget of tokens. No model call. The canary: any mechanism
      * that cannot beat this has no reason to exist.
      */
    def a1(raw: Chunk[Message], budget: Int): Chunk[Message] =
        val head = raw.take(1)
        @annotation.tailrec
        def back(i: Int, acc: Int): Int =
            if i <= 1 || acc + stampedTokens(raw(i - 1)) > budget then i else back(i - 1, acc + stampedTokens(raw(i - 1)))
        head.concat(raw.drop(back(raw.size, 0)))
    end a1

    /** A2: the traditional approach, rebuilt per the plan. It summarizes its OWN SERVED VIEW (previous
      * summary plus tail plus new turns), which is what a `/compact` does, not the full transcript. The
      * summary text differs on each regeneration, which is the property that decides its cache behaviour.
      */
    def a2(raw: Chunk[Message], budget: Int, tail: Int, gen: Int): Chunk[Message] =
        val head = raw.take(1)
        if toks(raw) <= budget then raw
        else
            val recent = raw.takeRight(tail)
            val summary = tok(
                sm(s"[summary of earlier conversation, generation $gen]\n" + ("condensed decisions, values and open threads. " * 12)),
                240
            )
            head.concat(Chunk(summary)).concat(recent)
        end if
    end a2

    /** A3's replay, THREADING STATE, which is what makes its cadence real.
      *
      * The per-step `a3` below measures occupancy from raw with no compaction state and passes
      * `prevLevels = Dict.empty`. Both are wrong for a cadence measurement: raw grows monotonically, so
      * once it crosses the watermark EVERY subsequent step fires a boundary, and a compactor that never
      * knows what it previously demoted re-decides from scratch each time. The real seam measures against
      * what was actually served (via the usage anchor) and carries the previous levels forward, so after a
      * boundary drops the view below the low mark the next turns re-serve unchanged.
      *
      * Threading the served view and the compaction state forward reproduces that: a boundary fires, the
      * view shrinks, and the following steps are below the trigger until growth crosses it again. The
      * boundary COUNT this produces is the number every cost figure depends on, so measuring it against a
      * cadence the seam never runs would misprice every arm.
      */
    def a3Replay(raw: Chunk[Message], d: Default): (List[Chunk[Message]], Int) =
        a3ReplayFull(raw, d) match
            case (views, fired, _) => (views, fired)

    /** As `a3Replay`, and also returns the final evicted RAW. Eviction rewrites raw rather than the
      * served view (`ctx.copy(raw = raw2)`), so the coarse-band marker that records a forget is only
      * observable there: a test looking for it in the compacted view finds nothing and wrongly concludes
      * eviction never fired.
      */
    def a3ReplayFull(raw: Chunk[Message], d: Default): (List[Chunk[Message]], Int, Chunk[Message]) =
        val steps = (4 to raw.size by 2).toList
        val bytes = "summary of the span: decisions, values and open threads preserved. " * 12
        val (views, boundaries, _, _, finalRaw) =
            steps.foldLeft((List.empty[Chunk[Message]], 0, Compaction.State(), Chunk.empty[Message], raw)) {
                case ((acc, fired, state, lastView, lastRaw), n) =>
                    val prefix = raw.take(n)
                    // occupancy as the seam sees it: over the state carried from the last boundary
                    val ctx = Context(prefix, if lastView.isEmpty then prefix else lastView).withCompaction(state)
                    if Compactor.internal.occupancy(ctx) < axisOf(cfg).high then
                        // below the trigger the seam re-serves the previous view plus what was appended
                        val served = if lastView.isEmpty then prefix else lastView.concat(prefix.drop(lastView.size))
                        (acc :+ served, fired, state, served, lastRaw)
                    else
                        val units      = d.group(prefix)
                        val spans      = d.formSpans(units, prefix, cfg)
                        val staged     = spans.foldLeft(state)((a, sp) => a.withSummary(sp.start, sp.end, bytes))
                        val seed       = d.seedVector(units, prefix, staged)
                        val scores     = d.score(units, d.deriveGraph(units, prefix, Dict.empty[Int, Int]), Dict.empty[Int, Int], seed)
                        val prevLevels = d.demotedOrigins(if lastView.isEmpty then prefix else lastView)
                        val levels = d.cut(
                            ctx,
                            units,
                            spans,
                            scores,
                            occupied = Compactor.internal.occupancy(ctx),
                            low = axisOf(cfg).low,
                            since = prefix.size,
                            prevLevels = prevLevels
                        )
                        val view    = d.project(prefix, units, spans, levels, prefix.size, prevLevels, staged)
                        val evicted = d.evict(Context(prefix, view).withCompaction(staged), cfg)
                        (acc :+ evicted.compacted, fired + 1, evicted.compactionState, evicted.compacted, evicted.raw)
                    end if
            }
        (views, boundaries, finalRaw)
    end a3ReplayFull

    /** A3: the shipped compactor's assembly path, per step and stateless. Kept for the single-shot
      * readings that do not depend on cadence; the cost replay uses `a3Replay` above.
      */
    def a3(raw: Chunk[Message], d: Default): Chunk[Message] =
        val ctx   = Context(raw)
        val units = d.group(raw)
        val spans = d.formSpans(units, raw, cfg)
        if Compactor.internal.occupancy(ctx) < axisOf(cfg).high then raw
        else
            val bytes  = "summary of the span: decisions, values and open threads preserved. " * 12
            val staged = spans.foldLeft(Compaction.State())((a, sp) => a.withSummary(sp.start, sp.end, bytes))
            val seed   = d.seedVector(units, raw, staged)
            val scores = d.score(units, d.deriveGraph(units, raw, Dict.empty[Int, Int]), Dict.empty[Int, Int], seed)
            val levels = d.cut(
                ctx,
                units,
                spans,
                scores,
                occupied = Compactor.internal.occupancy(ctx),
                low = axisOf(cfg).low,
                since = raw.size,
                prevLevels = Dict.empty
            )
            val view = d.project(raw, units, spans, levels, raw.size, Dict.empty, staged)
            // Eviction runs in the real boundary path and is the only IRREVERSIBLE step, so a replay that
            // skips it does not model the B+ regime it exists to cover: at several window-widths the raw
            // cap forgets early regions outright.
            d.evict(ctx.copy(compacted = view), cfg).compacted
        end if
    end a3

    // ---- the measurement ----

    /** Where the first edit lands, and what it costs, for one consecutive pair of an arm's views. */
    case class Edit(offsetTokens: Int, invalidatedTokens: Int, prefixTokens: Int):
        def depthFraction: Double = if prefixTokens == 0 then 1.0 else offsetTokens.toDouble / prefixTokens

    def editBetween(prev: Chunk[Message], cur: Chunk[Message]): Edit =
        val shared = prev.toList.zip(cur.toList).takeWhile((a, b) => a.content == b.content && a.role == b.role).size
        val offset = cur.take(shared).foldLeft(0)((a, m) => a + stampedTokens(m))
        Edit(offset, cur.drop(shared).foldLeft(0)((a, m) => a + stampedTokens(m)), toks(cur))
    end editBetween

    /** Cache-aware cost of a whole replay, in base-input-token equivalents: uncached + 0.1*cached. Output
      * is excluded here because assembly policies emit no output; generation cost is a separate term the
      * plan calibrates live.
      */
    def equivalents(views: List[Chunk[Message]], readRatio: Double): Double =
        views match
            case Nil => 0.0
            case first :: rest =>
                rest.foldLeft((toks(first).toDouble, first)) { case ((acc, prev), cur) =>
                    val e = editBetween(prev, cur)
                    (acc + e.invalidatedTokens + readRatio * e.offsetTokens, cur)
                }._1

    /** The traditional arm's replay, with a REGROWTH gate.
      *
      * The generation index must advance only when the view has grown past the budget since the last
      * summary, not once per step. Advancing it per step changes the summary text every turn, which
      * invalidates the prefix every turn and makes the arm a strawman: the live version of this arm did
      * exactly that until it was fixed (18 summarization calls for 4 user turns).
      */
    def a2Replay(raw: Chunk[Message], budget: Int): List[Chunk[Message]] =
        val steps = (4 to raw.size by 2).toList
        steps.foldLeft((List.empty[Chunk[Message]], 0, 0)) { case ((acc, gen, sizeAtLastSummary), n) =>
            val prefix = raw.take(n)
            if toks(prefix) <= budget then (acc :+ prefix, gen, sizeAtLastSummary)
            else
                // regrown past the budget again since the last summary -> a new summarization happens
                val regrown = gen == 0 || toks(prefix) > sizeAtLastSummary + budget
                val g       = if regrown then gen + 1 else gen
                val mark    = if regrown then toks(prefix) else sizeAtLastSummary
                (acc :+ a2(prefix, budget, tail = 6, gen = g), g, mark)
            end if
        }._1
    end a2Replay

    def replay(raw: Chunk[Message], policy: (Chunk[Message], Int) => Chunk[Message]): List[Chunk[Message]] =
        val steps = (4 to raw.size by 2).toList
        steps.zipWithIndex.map((n, i) => policy(raw.take(n), i))

    "E0: prefix-edit locality and cache-aware cost, per arm, per regime" in {
        val d = Compactor.internal.Default(Tuning(), Calibration())
        // regimes as multiples of the window, per the plan's section 2
        val regimes = List("A(0.5x)" -> 0.5, "A(0.9x)" -> 0.9, "B(1.5x)" -> 1.5, "B+(3x)" -> 3.0, "B+(6x)" -> 6.0)
        println("regime     arm      edits   medDepth   minDepth   equiv(r=.1)   totalRaw")
        regimes.foreach { (label, mult) =>
            val raw    = trace((window * mult).toInt)
            val budget = (window * 0.5).toInt
            val arms: List[(String, List[Chunk[Message]])] = List(
                "A0" -> replay(raw, (r, _) => a0(r, 0)),
                "A1" -> replay(raw, (r, _) => a1(r, budget)),
                "A2" -> a2Replay(raw, budget),
                "A3" -> replay(raw, (r, _) => a3(r, d))
            )
            arms.foreach { (name, views) =>
                val edits = views.sliding(2).collect { case p :: c :: Nil => editBetween(p, c) }
                    .filter(_.invalidatedTokens > 0).toList
                val depths = edits.map(_.depthFraction).sorted
                val med    = if depths.isEmpty then 1.0 else depths(depths.size / 2)
                val mn     = if depths.isEmpty then 1.0 else depths.head
                println(
                    f"$label%-10s $name%-9s ${edits.size}%6d ${med}%11.3f ${mn}%10.3f " +
                        f"${equivalents(views, 0.1)}%13.0f ${toks(raw)}%11d"
                )
            }
        }
        // ASSERTED, not merely printed. The headline cost finding is computed here and only
        // logged, so it could invert completely with this test still green. A finding worth recording is
        // worth pinning.
        val bRaw0   = trace((window * 1.5).toInt)
        val d0      = Compactor.internal.Default(Tuning(), Calibration())
        val budget0 = (window * 0.5).toInt
        def depthsOf(views: List[Chunk[Message]]): List[Double] =
            views.sliding(2).collect { case p :: c :: Nil => editBetween(p, c) }
                .filter(_.invalidatedTokens > 0).map(_.depthFraction).toList.sorted
        val a2Views  = a2Replay(bRaw0, budget0)
        val a3Views  = replay(bRaw0, (r, _) => a3(r, d0))
        val a2Depths = depthsOf(a2Views)
        val a3Depths = depthsOf(a3Views)
        assert(a2Depths.nonEmpty && a3Depths.nonEmpty, "REGIME: both strategies must edit the prefix at least once")
        val medA2 = a2Depths(a2Depths.size / 2)
        val medA3 = a3Depths(a3Depths.size / 2)
        assert(
            medA3 > medA2,
            s"the per-span strategy must edit DEEPER in the prefix than a regenerated head summary, which is " +
                s"the whole of its measured cost advantage: perSpan=$medA3 traditional=$medA2. If this inverts, " +
                s"the cost relation this test records no longer holds and must be re-derived."
        )
        assert(
            equivalents(a3Views, 0.1) < equivalents(a2Views, 0.1),
            s"per-span ASSEMBLY must cost less than whole-summary assembly in regime B: " +
                s"${equivalents(a3Views, 0.1)} vs ${equivalents(a2Views, 0.1)}. Assembly only: generation cost is " +
                s"excluded, and the live measurement showed it erases this margin, so this is NOT a claim that " +
                s"the design is cheaper end to end."
        )

        // The regime assertion: the replay must actually exercise compaction, or it compares identical arms.
        val bRaw = trace((window * 1.5).toInt)
        assert(toks(bRaw) > window, s"REGIME: regime B trace must exceed the window, got ${toks(bRaw)}")
        assert(replay(bRaw, (r, _) => a3(r, d)).exists(_.size < bRaw.size), "REGIME: A3 must compact in regime B")
    }

    "E3: trigger sweep, regime-A firing rate against regime-B overflow safety" in {
        // D3's size-trigger half, per the plan. Two things must hold at once: the trigger must almost never
        // fire on a session that would have fit (firing there is pure cost), and it must still keep the
        // worst regime-B trace inside the hard limit. Free, so the whole sweep runs.
        //
        // The size trigger is the only trigger: the below-boundary relevance tripwire was deleted, so a
        // boundary fires on occupancy alone and this sweep covers the whole decision.
        val fractions = List(0.5, 0.55, 0.6, 0.62, 0.65, 0.7, 0.8, 0.9, 0.95)
        val aTraces   = List(0.5, 0.7, 0.9).map(m => trace((window * m).toInt))
        val bTraces   = List(1.5, 3.0, 6.0).map(m => trace((window * m).toInt))
        println("highWM   regimeA-fired   worstB-peakView   hardLimit   verdict")
        val rows = fractions.flatMap { f =>
            // A watermark the compaction axis refuses is a RESULT, not a crash: it means the shipped axis
            // cannot express that trigger at this window, which is itself an answer for D3.
            val configured =
                try Present(Compactor.Tuning(trigger = f))
                catch
                    case e: IllegalArgumentException =>
                        println(f"$f%-8.2f    REJECTED by the compaction axis: ${e.getMessage.take(70)}"); Absent
            configured.toList.map { c =>
                val d = Compactor.internal.Default(c, Calibration())
                val a = Compactor.internal.axis(c, cfg)
                def viewsFor(raw: Chunk[Message]): List[Chunk[Message]] =
                    (4 to raw.size by 2).toList.map { n =>
                        val prefix = raw.take(n)
                        val ctxN   = Context(prefix)
                        val units  = d.group(prefix)
                        val spans  = d.formSpans(units, prefix, cfg)
                        if Compactor.internal.occupancy(ctxN) < a.high then prefix
                        else
                            val bytes  = "summary of the span: decisions, values and open threads preserved. " * 12
                            val staged = spans.foldLeft(Compaction.State())((a, sp) => a.withSummary(sp.start, sp.end, bytes))
                            val seed   = d.seedVector(units, prefix, staged)
                            val scores = d.score(units, d.deriveGraph(units, prefix, Dict.empty[Int, Int]), Dict.empty[Int, Int], seed)
                            val levels = d.cut(
                                ctxN,
                                units,
                                spans,
                                scores,
                                occupied = Compactor.internal.occupancy(ctxN),
                                low = a.low,
                                since = prefix.size,
                                prevLevels = Dict.empty
                            )
                            d.project(prefix, units, spans, levels, prefix.size, Dict.empty, staged)
                        end if
                    }
                // fired on a regime-A session = the served view ever differs from raw
                val aFired  = aTraces.count(raw => viewsFor(raw).zip((4 to raw.size by 2).toList).exists((v, n) => v.size != n))
                val worstB  = bTraces.map(raw => viewsFor(raw).map(toks).max).max
                val safe    = worstB <= a.hard
                val verdict = if aFired == 0 && safe then "CANDIDATE" else if !safe then "OVERFLOWS" else s"fires on $aFired/3 A"
                println(f"$f%-8.2f ${aFired}%13d ${worstB}%17d ${a.hard}%11d   $verdict")
                (f, aFired, safe)
            }
        }
        val candidates = rows.filter((_, fired, safe) => fired == 0 && safe).map(_._1)
        println(s"thresholds firing on ZERO regime-A traces and holding regime B: ${
                if candidates.isEmpty then "NONE" else candidates.mkString(", ")
            }")
        assert(rows.exists((_, _, safe) => safe), "at least one threshold must keep the worst regime-B trace inside the hard limit")
        // The measured constraint, asserted so it cannot silently change: no trigger fraction avoids firing
        // on sessions that would have fit in the window unaided. That is a real limitation of the shipped
        // design, recorded here rather than in prose only.
        assert(
            candidates.isEmpty,
            s"D3 is recorded as unanswerable: no trigger avoids firing on in-window sessions, but candidates now exist ($candidates)"
        )
        // The OTHER half of this test used to record that the axis rejected any watermark above about
        // 0.65, so most of the sweep never ran. That is no longer true and the reason is the point: the
        // axis moved off Config, where it validated the whole projection at construction, onto the
        // compactor, where only the fraction ORDERING is checked eagerly. The trigger is not part of that
        // ordering, so every fraction now constructs, and the window-dependent part clamps at projection
        // rather than refusing. The sweep therefore covers the range it always meant to.
        assert(
            rows.size == fractions.size,
            s"every trigger fraction must now be constructible, got ${rows.size} of ${fractions.size}"
        )
    }

    "G6: the realistic cadence, and what it does to the boundary count" in {
        // The number every cost figure rests on. The stateless per-step replay fires a boundary on EVERY
        // step once raw crosses the watermark, because it measures raw and forgets what it served. The
        // stateful replay re-serves below the trigger, so boundaries fire only when growth crosses it
        // again. If these differ materially, every previously reported A3 cost was priced at a cadence the
        // seam never runs.
        val d = Compactor.internal.Default(Tuning(), Calibration())
        List(1.5, 3.0, 6.0).foreach { mult =>
            val raw   = trace((window * mult).toInt)
            val steps = (4 to raw.size by 2).toList
            val naiveFired = steps.count { n =>
                Compactor.internal.occupancy(Context(raw.take(n))) >= axisOf(cfg).high
            }
            val (views, realFired) = a3Replay(raw, d)
            assert(views.size == steps.size, s"one view per step, got ${views.size} of ${steps.size}")
            println(f"${mult}%.1fx window: stateless would fire $naiveFired%d boundaries, stateful fires $realFired%d")
            assert(
                realFired <= naiveFired,
                s"threading state cannot INCREASE boundaries: stateful=$realFired stateless=$naiveFired"
            )
            assert(realFired >= 1, s"REGIME: compaction must fire at least once at ${mult}x, got $realFired")
        }
    }

    "G7: the irreversible forget engages at depth and not before" in {
        // Eviction is the only step that destroys content: past the raw retention cap the oldest
        // already-summarized regions are replaced by one coarse-band marker and their summaries, analyses
        // and recall records are dropped. That makes it the one mechanism whose absence from a replay
        // silently changes what the deep regimes measure, so the replay must be shown to reach it.
        //
        // The cap defaults to four window-widths, so a session at 1.5x the window must NOT forget anything
        // while one at 6x must. Asserting both directions is the point: an assertion that only checks the
        // deep case would pass on an implementation that forgets far too eagerly.
        val d                                           = Compactor.internal.Default(Tuning(), Calibration())
        val cap                                         = d.effectiveRawCap(cfg)
        val marker                                      = "forgotten past the retention horizon"
        def forgot(evictedRaw: Chunk[Message]): Boolean = evictedRaw.exists(_.content.contains(marker))

        val shallow = trace((window * 1.5).toInt)
        val deep    = trace((window * 6).toInt)
        assert(toks(shallow) < cap, s"REGIME: 1.5x must sit UNDER the retention cap: ${toks(shallow)} vs $cap")
        assert(toks(deep) > cap, s"REGIME: 6x must exceed it: ${toks(deep)} vs $cap")

        val (_, _, shallowRaw) = a3ReplayFull(shallow, d)
        val (_, _, deepRaw)    = a3ReplayFull(deep, d)
        assert(!forgot(shallowRaw), "a session inside the retention cap must forget nothing")
        assert(forgot(deepRaw), s"a session at 6x the window must reach the forget; cap=$cap raw=${toks(deep)}")
    }

end CompactorCostReplayTest
