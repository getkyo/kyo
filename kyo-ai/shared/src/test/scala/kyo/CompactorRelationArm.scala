package kyo

import kyo.Compactor.Mechanism
import kyo.Compactor.Tuning
import kyo.Compactor.internal.*
import kyo.ai.*
import kyo.ai.Compaction.*
import kyo.ai.Context.*

/** The model-quality arm for the analysis layer, with session divergence removed.
  *
  * Whether the analysis layer's relations earn their cost is the one question the offline batteries
  * cannot settle: a scripted fixture can prove the relations are CONSUMED (the levels move), but not that
  * consuming them makes the model answer better. That needs a live provider.
  *
  * A live on/off pair run as two conversations cannot settle it either, and that is the failure this arm
  * exists to correct: each arm generated its own conversation, so the two differed in length and content
  * far more than the ablation changed anything, and the verbatim fractions overlapped. The fix is to stop
  * letting the arms diverge. ONE fixed context is built here, projected to two views that differ ONLY in
  * whether the relations were allowed to act, and the SAME probes are asked against each. Same content,
  * same staged summaries, same probes, so a difference in the answers is attributable.
  *
  * Run: `sbt "kyo-aiJVM/Test/runMain kyo.CompactorRelationArm on"` (and again with `off`).
  */
object CompactorRelationArm extends KyoApp:
    private given Frame = Frame.internal

    private def um(s: String): UserMessage       = UserMessage(s, Absent)
    private def sm(s: String): SystemMessage     = SystemMessage(s)
    private def am(s: String): AssistantMessage  = AssistantMessage(s)
    private def tok(m: Message, n: Int): Message = stamp(m, TokenStamp("t", n))

    // Facts sit past each region's FIRST line, because a marker descriptor carries that first line: a
    // fact planted there would survive at every level and be credited to retention it never came from.
    private val pad = "The team keeps a long design journal recording context for each decision. " * 5

    private val raw: Chunk[Message] =
        Chunk[Message](tok(sm("you are a systems assistant"), 40), tok(um("we are designing a storage layer"), 60)) ++
            Chunk.from((0 until 10).flatMap { i =>
                val fact =
                    if i == 1 then s"\nOPERATIONAL VALUE: the ingest batch size is 384 records.\n$pad"
                    else if i == 4 then s"\nOPERATIONAL VALUE: the failover grace period is 26 seconds.\n$pad"
                    else if i == 7 then s"\nOPERATIONAL VALUE: the archive bucket is vault-nine.\n$pad"
                    else s"\n$pad"
                List(
                    tok(um(s"step $i: continue the design work$fact"), 700),
                    tok(am(s"answer $i\nacknowledged, continuing the design work. $pad"), 700)
                )
            })

    private def cfg = Config.Codex.auto.maxTokens(400).model(Config.Codex, "", 8000).timeout(6.minutes)

    run {
        val arm = args.headOption.getOrElse("on")
        // OFF silences the analysis layer's two channels: the relation edge weights, and the supersession
        // it infers. Everything else, including span formation and the staged summaries below, is held
        // identical, so the relations are the only surviving difference between the two views.
        val (tuning, calibration) =
            if arm == "off" then
                (
                    Tuning(mechanisms = Mechanism.all - Mechanism.AnalyzedSupersession),
                    Calibration(dependencyWeight = 0.0, relatednessWeight = 0.0)
                )
            else (Tuning(), Calibration())
        val d     = Default(tuning, calibration)
        val ctx   = Context(raw)
        val units = d.group(raw)
        val spans = d.formSpans(units, raw, cfg)
        // Identical staged summaries in BOTH arms, so summary CONTENT is held constant and only the level
        // assignment can differ.
        val bytes  = "summary of the span: decisions, values and open threads preserved. " * 12
        val staged = spans.foldLeft(Compaction.State())((a, sp) => a.withSummary(sp.start, sp.end, bytes))
        val ids    = units.toSeq.map(_.id).sorted
        // The relations point AT the fact-bearing regions. That is the layer's actual claim: a region
        // other regions depend on stays live. A topology that only chained neighbours produced two views
        // in which BOTH arms had lost every fact, so it could not discriminate; if the layer cannot
        // protect a referenced region even when the reference is explicit, it is inert for retention.
        val factRegions = raw.zipWithIndex.collect { case (m, i) if m.content.contains("OPERATIONAL VALUE") => i }
        val analyses = Chunk.from(ids.zipWithIndex.drop(2).flatMap { (id, idx) =>
            val deps = factRegions.toList.filter(_ < id).map(t => Relation(t, RelationKind.DependsOn))
            val rels = List(ids(idx - 1)).filter(_ < id).map(t => Relation(t, RelationKind.Relates))
            val all  = deps ++ rels
            if all.isEmpty then Nil else List(RegionAnalysis(id, Chunk.from(all)))
        })
        val withAnalyses = staged.copy(analyses = analyses)

        val sup    = d.mergeSupersession(Dict.empty[Int, Int], d.analyzedSupersession(analyses))
        val graph  = d.deriveGraph(units, raw, sup, d.analyzedEdges(analyses))
        val seed   = d.seedVector(units, raw, withAnalyses)
        val scores = d.score(units, graph, sup, seed)
        val levels = d.cut(ctx, units, spans, scores, occupied = 14000, low = 3000, since = raw.size, prevLevels = Dict.empty)
        val view   = d.project(raw, units, spans, levels, raw.size, Dict.empty, withAnalyses)

        println(s"[arm=$arm] viewMsgs=${view.size} viewTokens=${d.viewTokens(view)} demoted=${levels.toMap.size} of ${spans.size} spans")
        for f <- List("384", "26 seconds", "vault-nine") do
            println(s"[arm=$arm] fact '$f' in view: ${view.exists(_.content.contains(f))}")
        // The decisive numbers: what score do the referenced fact regions reach, against the keep floor a
        // region must cross to stay verbatim?
        val floor = keepFloor(units.size, tuning)
        println(s"[scores] keepFloor=$floor")
        for r <- factRegions do
            println(
                s"[scores] factRegion=$r score=${scores.get(r).getOrElse(0.0)} " +
                    s"inboundDependsOn=${analyses.count(_.relations.exists(rel => rel.target == r))}"
            )
        end for
        val top = scores.toMap.toList.sortBy { case (_, v) => -v }.take(3)
        println(s"[scores] hottest=$top")

        val probes = List(
            "What exact ingest batch size did we record, in records? Answer with the number only.",
            "What exact failover grace period did we record, in seconds? Answer with the number only.",
            "What exact archive bucket did we record? Answer with the identifier only.",
            "In two sentences, summarize every operational value recorded in this conversation."
        )
        LLM.run(cfg) {
            Kyo.foreachDiscard(probes) { p =>
                AI.init.map { ai =>
                    // A FRESH instance per probe, seeded with the same fixed view, so probes cannot
                    // contaminate one another.
                    ai.setContext(Context(view, view)).andThen(ai.userMessage(p)).andThen(ai.gen[String]).map { answer =>
                        Sync.defer(println(s"[probe] $p\n[answer] $answer"))
                    }
                }
            }
        }
    }
end CompactorRelationArm
