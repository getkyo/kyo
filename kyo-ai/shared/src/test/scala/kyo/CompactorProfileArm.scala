package kyo

import Compactor.internal.stamp
import kyo.ai.*
import kyo.ai.Context.*

/** Where a boundary's non-model time actually goes, measured rather than argued.
  *
  * This exists because a design was written proposing to reduce per-boundary work, and nothing in this
  * suite had ever measured per-boundary work. The design aimed its machinery at `score` and never
  * mentioned `tokenIndex`, while the implementation's own comment says the two tokenizations "are the
  * bulk of a boundary's non-model work". One of those is wrong, and a profile settles it.
  *
  * Wall-clock on one machine is a weak instrument: JIT warmup, GC and allocation noise all land in it.
  * It is used here only to RANK stages and to read the growth exponent against session length, both of
  * which survive a noisy constant factor. No absolute number from this file should be quoted.
  *
  * A MANUAL ARM rather than a test, for the same reason the numbers are not quotable. There is no
  * assertion a wall-clock reading can carry that is both meaningful and stable enough for CI: a
  * threshold tight enough to catch a real regression flakes on a loaded runner, and one loose enough
  * not to flake catches nothing. Run it when a change might have moved where boundary time goes:
  *
  * {{{
  * sbt 'kyo-aiJVM/Test/runMain kyo.CompactorProfileArm'
  * }}}
  */
object CompactorProfileArm extends KyoApp:
    // Same as the sibling arm: a KyoApp body is outside a test's implicit Frame, and this is
    // instrumentation rather than library code, so the internal Frame is the right one.
    private given Frame = Frame.internal

    val window = 16384

    def cfg: Config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    def am(s: String, calls: Call*): AssistantMessage = AssistantMessage(s, Chunk.from(calls))
    def um(s: String): UserMessage                    = UserMessage(s, Absent)
    def sm(s: String): SystemMessage                  = SystemMessage(s)
    def tok(m: Message, n: Int): Message              = stamp(m, TokenStamp("t", n))

    /** Prose with distinct per-region vocabulary, so the ranking's content tier is exercised the way a
      * real conversation exercises it rather than funnelling onto one region.
      */
    def transcript(regions: Int): Chunk[Message] =
        val head = Chunk[Message](tok(sm("you are a systems assistant"), 40), tok(um("design a storage layer"), 60))
        val body = (0 until regions).flatMap { i =>
            val subject = s"component$i"
            val verb    = s"reconciles$i"
            val obj     = s"manifest$i"
            val text    = s"the $subject $verb its $obj and the record explains why for reviewer $i. " * 12
            List(
                tok(um(s"question $i about the $subject"), 300),
                tok(am(s"answer $i\n$text"), 700)
            )
        }
        head.concat(Chunk.from(body))
    end transcript

    def timeOf[A](reps: Int)(f: => A): Double =
        var i    = 0
        var sink = 0
        while i < 3 do
            sink += f.hashCode(); i += 1 // warm
        val t0 = java.lang.System.nanoTime()
        i = 0
        while i < reps do
            sink += f.hashCode(); i += 1
        val elapsed = (java.lang.System.nanoTime() - t0).toDouble / reps / 1e6
        if sink == Int.MinValue then -1.0 else elapsed
    end timeOf

    run {
        val d = Compactor.internal.Default(Compactor.Tuning(), Compactor.internal.Calibration())
        println("[profile] regions  group formSpans superKeys tokenIndex seedVector deriveGraph score    cut  project | total")
        val sizes = List(20, 40, 80)
        val rows = sizes.map { n =>
            val raw    = transcript(n)
            val ctx    = Context(raw)
            val reps   = if n >= 80 then 3 else 8
            val units  = d.group(raw)
            val spans  = d.formSpans(units, raw, cfg)
            val keys   = Dict.empty[Int, (String, Tool.Kind)]
            val index  = d.tokenIndex(units, raw)
            val seed   = d.seedVector(units, raw, Compaction.State())
            val sup    = Dict.empty[Int, Int]
            val graph  = d.deriveGraph(index, sup, Chunk.empty)
            val scores = d.score(units, graph, sup, seed)
            val axis   = Compactor.internal.axis(Compactor.Tuning(), cfg)
            val occ    = Compactor.internal.occupancy(ctx)
            val prev   = d.demotedOrigins(ctx.compacted)

            val tGroup = timeOf(reps)(d.group(raw))
            val tSpans = timeOf(reps)(d.formSpans(units, raw, cfg))
            val tKeys  = timeOf(reps)(d.superKeysFrom(units, raw, Chunk.empty))
            val tIndex = timeOf(reps)(d.tokenIndex(units, raw))
            val tSeed  = timeOf(reps)(d.seedVector(units, raw, Compaction.State()))
            val tGraph = timeOf(reps)(d.deriveGraph(index, sup, Chunk.empty))
            val tScore = timeOf(reps)(d.score(units, graph, sup, seed))
            val tCut   = timeOf(reps)(d.cut(ctx, units, spans, scores, occ, axis.low, raw.size, prev))
            val demo   = d.cut(ctx, units, spans, scores, occ, axis.low, raw.size, prev)
            val tProj  = timeOf(reps)(d.project(raw, units, spans, demo, raw.size, prev, Compaction.State(), keys))
            val total  = tGroup + tSpans + tKeys + tIndex + tSeed + tGraph + tScore + tCut + tProj
            println(
                f"[profile] $n%7d $tGroup%6.2f $tSpans%9.2f $tKeys%9.2f $tIndex%10.2f $tSeed%10.2f " +
                    f"$tGraph%11.2f $tScore%5.2f $tCut%6.2f $tProj%8.2f | $total%6.2f"
            )
            (n, tIndex, tScore, tCut, tProj, total)
        }

        val (n1, i1, s1, c1, p1, t1) = rows.head
        val (n2, i2, s2, c2, p2, t2) = rows.last
        def exponent(a: Double, b: Double): Double =
            math.log(math.max(b, 1e-6) / math.max(a, 1e-6)) / math.log(n2.toDouble / n1.toDouble)
        println(
            f"[profile] growth exponent over ${n1}x to ${n2}x regions: tokenIndex ${exponent(i1, i2)}%.2f " +
                f"score ${exponent(s1, s2)}%.2f cut ${exponent(c1, c2)}%.2f project ${exponent(p1, p2)}%.2f " +
                f"total ${exponent(t1, t2)}%.2f"
        )
        val (_, iL, sL, cL, pL, tL) = rows.last
        println(
            f"[profile] share of total at ${n2} regions: tokenIndex ${iL / tL * 100}%.0f%% score ${sL / tL * 100}%.0f%% " +
                f"cut ${cL / tL * 100}%.0f%% project ${pL / tL * 100}%.0f%%"
        )
        Kyo.unit
    }
end CompactorProfileArm
