package kyo

import kyo.ai.*

/** An interactive compaction chat: the live observation tool for the compactor.
  *
  * Reads user messages from stdin and prints live completions with a compactor enabled, exactly like a
  * normal AI chat, but with the observation surface the offline batteries cannot provide: what the model
  * actually receives, at which detail level, and what each turn cost including the cached split.
  *
  * The deterministic tests grade the compactor against fixtures they built. This grades it against a real
  * conversation with a real provider, which is where the questions that decide the design get answered:
  * whether a planted fact is still reachable twenty turns later, and what the strategy cost to keep it.
  *
  * Commands:
  *   - `/dump`         : compaction detail (occupancy vs axis, level census, served-token total)
  *   - `/dump full`    : also print every message in raw and in the compacted view
  *   - `/find <token>` : residency proof: where a literal string lives (raw ordinal, or compacted
  *     verbatim / marker-descriptor / marker-bytes)
  *   - `/snapshot` / `/recover` : capture and restore an `AISession`
  *   - `/quit`         : exit (EOF also exits)
  *
  * A small context window (arg 1) makes compaction trigger within a few turns. `package kyo` for the
  * `private[kyo]` observation surface (occupancy, compactionState). Interactive stdin, so JVM only.
  *
  * Args: `<window> [mode=default|none|off|naive|truncate] [ablate=field=value,...]`
  *
  * Run: `sbt "kyo-aiJVM/Test/runMain kyo.CompactorChatDemo 3000 default"`
  */
object CompactorChatDemo extends KyoApp:
    private given Frame = Frame.internal
    private type Chat = LLM & Async

    run {
        val window = args.headOption.flatMap(_.toIntOption).getOrElse(3000)
        val mode   = args.lift(1).getOrElse("default") // default | none | off | naive | truncate
        // Ablation arms pass overrides after the mode, e.g.
        //   default ablate=Analysis=false,contentReferenceWeight=0
        // An unknown name is a hard error rather than a silently ignored flag, because a silently ignored
        // ablation reports the mechanism as worthless.
        val ablations: Map[String, String] =
            args.drop(2).filter(_.startsWith("ablate=")).flatMap(_.drop(7).split(",")).flatMap { kv =>
                kv.split("=") match
                    case Array(k, v) => Some(k.trim -> v.trim)
                    case _           => None
            }.toMap
        // Two ablation surfaces, because the design separates them: a MECHANISM is named by its
        // `Mechanism` case and switched wholesale (`ablate=Analysis=false`), while a ranking WEIGHT is a
        // measured constant on `Calibration` and is set by value (`ablate=referenceWeight=0`). A caller
        // has no reason to turn a weight, which is why it is not on `Tuning`; an ablation arm does, which
        // is why it reaches for the calibration directly here.
        val (tuning, calibration) =
            ablations.foldLeft((Compactor.Tuning(), Compactor.internal.Calibration())) { case ((t, c), (k, v)) =>
                k match
                    case "adjacencyWeight"        => (t, c.copy(adjacencyWeight = v.toDouble))
                    case "referenceWeight"        => (t, c.copy(referenceWeight = v.toDouble))
                    case "contentReferenceWeight" => (t, c.copy(contentReferenceWeight = v.toDouble))
                    case "dependencyWeight"       => (t, c.copy(dependencyWeight = v.toDouble))
                    case "relatednessWeight"      => (t, c.copy(relatednessWeight = v.toDouble))
                    case "supersessionPenalty"    => (t, c.copy(supersessionPenalty = v.toDouble))
                    case "pprIterations"          => (t, c.copy(pprIterations = v.toInt))
                    case "keepShare"              => (t.copy(keepShare = v.toDouble), c)
                    case name =>
                        Compactor.Mechanism.values.find(_.toString == name) match
                            case Some(m) =>
                                (t.copy(mechanisms = if v.toBoolean then t.mechanisms + m else t.mechanisms - m), c)
                            case None =>
                                throw IllegalArgumentException(
                                    s"unknown ablation field '$name'; mechanisms are " +
                                        Compactor.Mechanism.values.map(_.toString).mkString(", ")
                                )
            }
        // 6-minute timeout (vs the 2-minute default): codex reasons on every turn AND on every summary
        // fill (the endpoint exposes no reasoning-off), so a boundary turn under compaction load can
        // exceed 2 minutes and the default timeout kills the session. Raised here to OBSERVE retention;
        // the 2-minute-exceeded fact is itself a recorded robustness finding for compaction on codex.
        val cfg  = Config.Codex.auto.maxTokens(800).model(Config.Codex, "", window).timeout(6.minutes)
        val axis = Compactor.internal.axis(tuning, cfg)
        println(
            s"[chat] codex, window=$window mode=$mode  axis: low=${axis.low} prepare=${axis.prepare} high=${axis.high} hard=${axis.hard}"
        )
        if ablations.nonEmpty then
            println(s"[arm] ABLATED: ${ablations.toSeq.sortBy(_._1).map((k, v) => s"$k=$v").mkString(", ")}")
        else println("[arm] shipped tuning (no ablation)")
        println("[chat] message | /dump [full] | /find <token> | /snapshot | /recover | /quit")

        def stamped(ctx: Context): Int =
            ctx.compacted.foldLeft(0)((a, m) => a + Compactor.internal.stampedTokens(m))

        LLM.run(cfg) {
            // Per-reply usage, including the CACHED split. Without it the cost side cannot be read at all:
            // the billed unit is cache-aware, and codex reports cachedInputTokens. Every generation the
            // session makes passes through here, foreground and maintenance alike, so summarizer and
            // analysis calls are logged too.
            val usageObserver = Observe.init { (_, reply) =>
                val u = reply.usage
                Sync.defer(println(
                    s"[usage] input=${u.inputTokens} cached=${u.cachedInputTokens.getOrElse(-1L)} " +
                        s"output=${u.outputTokens} turns=${u.turns}"
                ))
            }

            def enable(a: AI): AI < Chat =
                mode match
                    case "off"  => a.enable(usageObserver) // no compactor enabled (default-off path)
                    case "none" => a.enable(Compactor.none, usageObserver)
                    // the reference strategy: one whole-session summarization on the main model
                    case "naive" =>
                        a.enable(CompactorBaselines.Naive(keepRecent = 6, budgetTokens = axis.high), usageObserver)
                    // the zero-intelligence floor
                    case "truncate" =>
                        a.enable(CompactorBaselines.Truncation(budgetTokens = axis.high), usageObserver)
                    // Constructed directly rather than through `Compactor.init` so an arm can ablate the
                    // calibration; with no ablation this is exactly what `init(tuning)` builds.
                    case _ => a.enable(Compactor.internal.Default(tuning, calibration), usageObserver)

            AI.init.map(enable).map { ai0 =>
                AtomicRef.init(ai0).map { cur =>
                    AtomicRef.init(Absent: Maybe[AISession]).map { snap =>
                        // The cumulative sum of served-view stamped tokens over gens: the cost side.
                        AtomicRef.init(0L).map { servedTotal =>
                            AtomicRef.init(0).map { genCount =>

                                // Per-level census of the compacted (model-visible) view. A live message
                                // has an empty origin (verbatim); a demoted region renders a marker with a
                                // Present origin. A marker carrying summary bytes has content beyond its
                                // `]` bracket; a pointer-only marker ends at the bracket.
                                def census(ctx: Context): String =
                                    val cmp      = ctx.compacted
                                    val verbatim = cmp.count(_.origin.isEmpty)
                                    val markers  = cmp.filter(_.origin.isDefined)
                                    val pointer  = markers.count(_.content.trim.endsWith("]"))
                                    val withBody = markers.size - pointer
                                    s"verbatim=$verbatim summary=$withBody pointer=$pointer"
                                end census

                                def dump(ai: AI, full: Boolean): Unit < Chat =
                                    ai.context.map { ctx =>
                                        servedTotal.get.map { served =>
                                            genCount.get.map { gens =>
                                                val occ    = Compactor.internal.occupancy(ctx)
                                                val st     = ctx.compactionState
                                                val rawTok = ctx.raw.foldLeft(0)((a, m) => a + Compactor.internal.stampedTokens(m))
                                                val cmpTok = stamped(ctx)
                                                val anchor = st.lastUsage match
                                                    case Present(total) => s"anchored(lastUsage=$total@rawSize=${st.lastUsageRawSize})"
                                                    case Absent         => s"offline(estimated from ${rawTok + cmpTok})"
                                                println(
                                                    s"[dump] occupancy=$occ [$anchor] (high=${axis.high}, low=${axis.low}) | " +
                                                        s"raw ${ctx.raw.size}msgs/${rawTok}tok | compacted ${ctx.compacted.size}msgs/${cmpTok}tok | " +
                                                        s"census[${census(ctx)}] | servedSum=${served}tok over ${gens}gens | " +
                                                        s"boundary=${st.boundaryCounter} summaries=${st.summaries.size} " +
                                                        s"analyses=${st.analyses.size} recalls=${st.recalls.size}"
                                                )
                                                // Relation CONTENT, not the count: the question is whether the model emits
                                                // relations of usable quality, which a tally cannot answer.
                                                if st.analyses.nonEmpty then
                                                    val rels = st.analyses.toSeq
                                                        .sortBy(_.ordinal)
                                                        .flatMap(ra => ra.relations.map(r => s"${ra.ordinal}-${r.kind}->${r.target}"))
                                                    println(
                                                        s"[relations] ${
                                                                if rels.isEmpty then "(analyses present, zero relations)"
                                                                else rels.mkString(", ")
                                                            }"
                                                    )
                                                end if
                                                if full then
                                                    println("  --- raw ---")
                                                    ctx.raw.foreach(m => println(s"    ${m.role.name}: ${m.content.take(160)}"))
                                                    println("  --- compacted (what the model sees) ---")
                                                    ctx.compacted.foreach(m => println(s"    ${m.role.name}: ${m.content.take(160)}"))
                                                end if
                                            }
                                        }
                                    }

                                // Residency proof for a literal token. Reports raw ordinals (flagged
                                // demoted) and compacted positions (verbatim vs inside-marker) so a
                                // retention probe can prove which detail level a planted fact sits at
                                // BEFORE the question is spent.
                                def find(ai: AI, token: String): Unit < Chat =
                                    ai.context.map { ctx =>
                                        val inRaw = ctx.raw.zipWithIndex.collect {
                                            case (m, i) if m.content.contains(token) =>
                                                s"raw[$i:${m.role.name}${if m.origin.isDefined then ",demoted" else ""}]"
                                        }
                                        val inCmp = ctx.compacted.zipWithIndex.collect {
                                            case (m, i) if m.content.contains(token) =>
                                                val where =
                                                    if m.origin.isEmpty then "verbatim"
                                                    else
                                                        // A marker's descriptor is its bracketed head; anything after the
                                                        // closing bracket is staged summary bytes. A fact planted in a
                                                        // region's first line is carried by the DESCRIPTOR and survives
                                                        // even at pointer level, which is not retention by the summary
                                                        // tier and must not be credited to it.
                                                        val close = m.content.indexOf(']')
                                                        if close >= 0 && m.content.take(close).contains(token) then "marker-DESCRIPTOR"
                                                        else "marker-bytes"
                                                s"cmp[$i:${m.role.name}:$where]"
                                        }
                                        println(
                                            s"[find '$token'] raw: ${if inRaw.isEmpty then "ABSENT" else inRaw.mkString(", ")} | " +
                                                s"compacted: ${if inCmp.isEmpty then "ABSENT" else inCmp.mkString(", ")}"
                                        )
                                    }

                                def loop: Unit < Chat =
                                    Sync.defer(scala.io.StdIn.readLine()).map { line =>
                                        cur.get.map { ai =>
                                            if line == null || line == "/quit" then Sync.defer(println("[bye]"))
                                            else if line == "/dump" then dump(ai, false).andThen(loop)
                                            else if line == "/dump full" then dump(ai, true).andThen(loop)
                                            else if line.startsWith("/find ") then find(ai, line.drop(6).trim).andThen(loop)
                                            else if line == "/snapshot" then
                                                ai.snapshot.map(s => snap.set(Present(s)))
                                                    .map(_ => println("[snapshot taken]")).andThen(loop)
                                            else if line == "/recover" then
                                                snap.get.map {
                                                    case Present(s) =>
                                                        AI.recover(s).map(r => cur.set(r))
                                                            .map(_ => println("[recovered]")).andThen(loop)
                                                    case Absent =>
                                                        Sync.defer(println("[no snapshot]")).andThen(loop)
                                                }
                                            else if line.trim.isEmpty then loop
                                            else
                                                // The reader is line-oriented, but a fact must be placeable past a
                                                // region's FIRST line: a marker descriptor carries that line, so a
                                                // first-line fact stays visible at every level and would be credited
                                                // to retention it never came from. A literal \n escape expands here.
                                                val text = line.replace("\\n", "\n")
                                                // Echo what was RECEIVED. Without it a log carries replies but not the
                                                // questions, so pairing a probe to its answer is inferred rather than
                                                // proven, and a shifted reply would be scored as a retention miss (or a
                                                // hit) that the log cannot substantiate.
                                                println(s"[you] ${text.replace("\n", " / ")}")
                                                // The codex app-server drops connections transiently ("closed before
                                                // completing the turn", and 503s under load). Unretried, one drop kills
                                                // the whole session, and a plan that needs dozens of sessions cannot
                                                // absorb that. Retries the TURN, not the run, so the conversation
                                                // continues from where it was; a turn that fails every attempt is
                                                // reported and the session goes on, because losing one turn is
                                                // recoverable evidence and losing the session is not.
                                                def attempt(n: Int): Unit < Chat =
                                                    ai.gen[String].handle(Abort.run[Throwable]).map {
                                                        case Result.Success(reply) => Sync.defer(println(s"[ai] $reply"))
                                                        case other if n > 1 =>
                                                            Sync.defer(println(s"[retry] turn failed, ${n - 1} left: $other"))
                                                                .andThen(Async.sleep(3.seconds))
                                                                .andThen(attempt(n - 1))
                                                        case other =>
                                                            Sync.defer(println(s"[ai] TURN FAILED after retries: $other"))
                                                    }
                                                ai.userMessage(text).andThen(attempt(3)).andThen(
                                                    // Accumulate the served view AFTER the gen, so every arm is
                                                    // measured with the same stamps and the cumulative ratio between
                                                    // arms is internally consistent.
                                                    ai.context.map(ctx2 => servedTotal.getAndUpdate(_ + stamped(ctx2))).andThen(
                                                        genCount.getAndUpdate(_ + 1).unit
                                                    )
                                                ).andThen(loop)
                                            end if
                                        }
                                    }

                                loop
                            }
                        }
                    }
                }
            }
        }
    }
end CompactorChatDemo
