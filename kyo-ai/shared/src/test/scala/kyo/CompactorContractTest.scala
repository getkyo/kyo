package kyo

import kyo.ai.*
import kyo.ai.CacheCost
import kyo.ai.Context.*

/** Cross-turn properties of the shipped compactor, driven through the real serving seam.
  *
  * The distinguishing feature is that the conversation GROWS THROUGH THE SEAM: the context is set once and
  * every later turn arrives as a user message, so each step sees the state the previous step produced.
  * That matters because the cheap alternative gives wrong answers. A replay that re-derives from a raw
  * transcript at every step hands the compactor a context it has never seen, which makes it recompute a
  * boundary on every turn once the conversation is large; measured that way, cadence readings came out
  * about twice their real value, and the error fell on exactly the property being measured.
  *
  * Stated over the public seam: `enable`, `userMessage`, `gen`, and the `Context` that comes back. The
  * only non-public thing used is the token count carried on each message, and it is confined to `toks`
  * below, so this file does not depend on how compaction works internally: spans, levels, scores and
  * staging are all invisible here. Sibling files that reach into `Compactor.internal` grade mechanisms;
  * this one grades behaviour a caller can observe, and it is deliberately the file that survives a
  * mechanism rewrite intact.
  */
class CompactorContractTest extends kyo.test.Test[Any]:

    private def axisOf(c: Config): Compactor.internal.Axis =
        Compactor.internal.axis(Compactor.Tuning(), c)

    // The retention cap is pinned rather than left to its default (several window-widths), so a few dozen
    // scripted turns cross both the compaction trigger and the forget line and the depth property is
    // testable without a long run. The window matches the sibling suites so the watermarks are the
    // familiar ones; a smaller window collides with the output reservation and is unconstructible.
    val window = 16384
    val rawCap = 24000

    def cfg: Config = Config.OpenAI.default.apiKey("k").model(Config.OpenAI, "m", window)

    /** The retention cap is compactor POLICY now, so the arm under test carries it rather than the config. */
    def capped: Compactor[Any] = Compactor.init(Compactor.Tuning(rawRetentionCap = Present(rawCap)))

    /** Token size of a view. The stamp is the seam's own apportioned count, so for anything the seam has
      * processed this agrees exactly with what compaction measured. The fallback covers a message the
      * seam has not stamped yet and is the same shape as the production estimate (roughly a token per
      * three characters, plus a per-message envelope); it is only ever a small correction here.
      */
    def toks(v: Chunk[Message]): Int =
        v.foldLeft(0)((a, m) => a + m.tokens.map(_.count).getOrElse(m.content.length / 3 + 4))

    /** One turn of bulk prose, sized so a few dozen turns clear the retention cap. */
    def turnText(i: Int): String =
        s"step $i: continue the design work. " +
            ("the service coordinates writes across replicas and reconciles them on read, " +
                "and the reconciliation order decides which write wins on conflict. ") * 24

    def genBody(resultValue: String): String =
        val envelope = Json.encode(s"""{"resultValue":$resultValue}""")
        s"""{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"r1","type":"function","function":{"name":"result_tool","arguments":$envelope}}]}}]}"""

    /** Grows a conversation THROUGH the seam for `turns` turns, returning the context after each one.
      *
      * The context is set once, at the start, and never replaced: every subsequent turn is appended by
      * the seam itself, so compaction state accumulates exactly as it does in a live session. Replies are
      * scripted, so the run is deterministic and costs nothing.
      */
    def grow(c: Compactor[Any], turns: Int)(using Frame): Chunk[Context] < (Async & Abort[Any] & Scope) =
        TestCompletionServer.run { server =>
            val scripted = cfg.apiUrl(server.baseUrl)
            Kyo.foreachDiscard(0 until turns * 12 + 40)(i =>
                server.enqueueBody(genBody(Json.encode(s"answer $i: decisions, values and open threads preserved")))
            ).andThen {
                LLM.run(scripted) {
                    AI.init.map(_.enable(c)).map { ai =>
                        ai.setContext(Context(Chunk[Message](SystemMessage("you are a systems assistant"))))
                            .andThen {
                                Kyo.foreach(0 until turns) { i =>
                                    ai.userMessage(turnText(i))
                                        .andThen(ai.gen[String]).handle(Abort.run[Any])
                                        .andThen(ai.context)
                                }.map(Chunk.from)
                            }
                    }
                }
            }
        }

    /** `grow`, plus the requests the server actually received, so the maintenance passes can be counted.
      *
      * The two maintenance passes run on the background preparation fiber, so this is a SNAPSHOT taken
      * when the last turn returns: a call still in flight is not in it, and the counts are lower bounds.
      * That is the right bound for the question they answer, which is what the passes cost a session that
      * ends when the user stops typing.
      */
    def growCapturing(c: Compactor[Any], turns: Int)(using
        Frame
    ): (Chunk[Context], Chunk[TestCompletionServer.Captured]) < (Async & Abort[Any] & Scope) =
        TestCompletionServer.run { server =>
            val scripted = cfg.apiUrl(server.baseUrl)
            Kyo.foreachDiscard(0 until turns * 12 + 40)(i =>
                server.enqueueBody(genBody(Json.encode(s"answer $i: decisions, values and open threads preserved")))
            ).andThen {
                LLM.run(scripted) {
                    AI.init.map(_.enable(c)).map { ai =>
                        ai.setContext(Context(Chunk[Message](SystemMessage("you are a systems assistant"))))
                            .andThen {
                                Kyo.foreach(0 until turns) { i =>
                                    ai.userMessage(turnText(i))
                                        .andThen(ai.gen[String]).handle(Abort.run[Any])
                                        .andThen(ai.context)
                                }.map(Chunk.from)
                            }
                    }
                }.map(ctxs => server.captured.map(rec => (ctxs, rec)))
            }
        }

    "compaction does not fire on every turn once the conversation is large" in {
        // The cadence property, and the one that decides whether compaction is affordable at all. Being
        // large is not itself expensive under prompt caching: a boundary rewrites the prefix and so
        // invalidates the cache, but between boundaries the prefix is stable and the turn costs only its
        // own new bytes. A compactor that recomputed every turn would pay an invalidation continuously.
        grow(capped, 40).map { contexts =>
            val sizes      = contexts.map(c => toks(c.compacted)).toList
            val boundaries = sizes.sliding(2).count { case a :: b :: Nil => b < a; case _ => false }
            val steps      = sizes.size
            val rawEnd     = toks(contexts.last.raw)
            assert(steps == 40, s"REGIME: every turn must produce a context, got $steps")
            // The precondition is that the CONVERSATION crossed the trigger, not that the served view did:
            // the served view is what compaction holds down, so requiring it to be large would require
            // compaction to have failed.
            assert(
                rawEnd > Compactor.internal.axis(Compactor.Tuning(rawRetentionCap = Present(rawCap)), cfg).high,
                s"REGIME: the run must cross the trigger, raw $rawEnd vs ${Compactor.internal.axis(Compactor.Tuning(rawRetentionCap = Present(rawCap)), cfg).high}"
            )
            assert(sizes.max < rawEnd, s"REGIME: the served view must be held below raw, peak ${sizes.max} vs raw $rawEnd")
            assert(boundaries >= 1, s"REGIME: compaction must fire at least once over $steps turns")
            assert(
                boundaries * 2 < steps,
                s"compaction fired on $boundaries of $steps turns; it must re-serve between boundaries, not recompute each turn"
            )
        }
    }

    /** The honest competitor: regenerate a whole-transcript summary at each boundary and serve it.
      *
      * This is what Anthropic's context compaction and Claude Code's `/compact` do, so it is the shape
      * the shipped design is actually competing against. It is also the ONLY legal baseline in overflow:
      * `Compactor.none` would send a request the provider refuses, so any cost computed for it is
      * fiction, and a truncating reference makes no model calls at all, so it wins on cost by
      * construction and proves nothing.
      *
      * It takes a boundary at the same occupancy the shipped compactor does, so the arms are compared on
      * policy rather than on trigger placement.
      */
    final class WholeSummary(summaryChars: Int) extends Compactor[Any]:
        type State = Unit
        def initState(using Frame): Unit < Sync = Kyo.unit

        def compact(ctx: Context, state: Unit)(using Frame): Compactor.Decision < (LLM & Async & Abort[AIGenException]) =
            AI.config.map { config =>
                val a = Compactor.internal.axis(Compactor.Tuning(), config)
                if Compactor.internal.occupancy(ctx) < a.high then Kyo.lift(Compactor.Decision.Unchanged)
                else
                    // Everything before the last turn collapses into ONE regenerated summary, which is the
                    // whole point: the prefix is rewritten from the head every time, so the provider's
                    // cache is invalidated from position zero at every boundary.
                    val tail    = ctx.raw.takeRight(2)
                    val covered = ctx.raw.size - tail.size
                    // The summary CARRIES AN ORIGIN over everything it replaces. Without it this arm is
                    // the silent-loss compactor the conformance battery exists to reject: it would be
                    // cheaper only because it discards the conversation, and a cost comparison against a
                    // competitor that is not accountable measures nothing. Anthropic's compaction and
                    // Claude Code's `/compact` both leave the model able to tell that history was folded,
                    // so an accountable summary is the honest form of this baseline.
                    val summary = SystemMessage(
                        "summary of the conversation so far. " * (summaryChars / 36),
                        origin = Present(Context.Origin(0, math.max(covered, 1), ctx.raw.size))
                    )
                    Kyo.lift(Compactor.Decision.Compacted(ctx.copy(compacted = Chunk(summary).concat(tail))))
                end if
            }
    end WholeSummary

    "the cost comparison against whole-transcript summarization, measured and recorded" in {
        // THE RESULT IS ADVERSE AND IT IS RECORDED HERE RATHER THAN ARGUED AWAY.
        //
        // Measured over 80 turns grown through the seam, against a whole-transcript summarizer that is
        // itself accountable (its summary carries an origin over everything it replaces, so it is not the
        // silent-loss shape the conformance battery rejects):
        //
        //   shipped  371379 equivalents  33 invalidations  hit 0.715  input 467543
        //   whole    280783 equivalents   7 invalidations  hit 0.846  input 318713
        //
        // THE SHIPPED ROW IS ONE SAMPLE, NOT A CONSTANT, and the figures above were recorded as though it
        // were. Seven later runs of the same fixture spread from 357057 to 372655 with 33 to 35
        // invalidations, roughly four percent. The cause is the speculative preparation fiber: a boundary
        // adopts whatever that fiber has managed to stage by the time it reads the cell, so adoption, the
        // cut and the boundary cadence all move a little with thread timing. The competitor row IS a
        // constant, returning 280783 on every one of those runs, because it runs no such fiber. The
        // assertions below are all directional and the gap is an order of magnitude wider than the
        // spread, so the recorded finding is unaffected; what the spread rules out is treating either
        // absolute as a baseline a later change can be diffed against. The sibling decomposition test
        // samples the arm for exactly this reason.
        //
        // The shipped compactor costs MORE, and the gap widens with session length: at 40 turns it was
        // 149272 against 139638 with invalidations nearly level, at 80 turns it is 25% worse on cost and
        // takes almost five times as many boundaries. The cache advantage this design is built around does
        // not materialize against this competitor, because the competitor is ALSO byte-stable between its
        // own boundaries; it simply serves far less and therefore re-crosses the trigger far less often.
        //
        // WHY THIS IS NOT A VERDICT ON THE DESIGN, stated so nobody reads it as one. The only parity this
        // can assert offline is "fits the limit" plus "accounts for what it drops", and that notion is too
        // weak to exclude discarding: a strategy that folds the entire history behind one origin-carrying
        // marker satisfies it perfectly. Both arms also leave `raw` intact, so both remain recallable. What
        // the shipped design buys over the competitor is what the SERVED VIEW still contains without a
        // round trip: verbatim pins on live content, graded detail, per-region stand-ins. Whether that is
        // worth 25% is a quality question, it needs a model, and it is exactly the measurement this suite
        // cannot make. Assertions below therefore pin the COST FACTS and refuse to dress them as a win.
        //
        // If the direction ever flips, that is good news and must be re-derived, not assumed.
        val summaryCap = Compactor.Tuning().summaryOutputCap
        // Sized to the same output budget the shipped path charges itself: a three-word scripted summary
        // would make the competitor free in output and tiny in prefix, and this would grade a literal.
        val whole = WholeSummary(summaryCap * 4)
        val turns = 80
        grow(Compactor.init, turns).map { shippedCtxs =>
            grow(whole, turns).map { wholeCtxs =>
                val shippedViews         = shippedCtxs.map(_.compacted).toList
                val wholeViews           = wholeCtxs.map(_.compacted).toList
                val size: Message => Int = m => m.tokens.map(_.count).getOrElse(m.content.length / 3 + 4)

                // (1) REGIME, named against the WINDOW and not the trigger. A headline ratio was once
                // measured on a session that FIT the window, which made the conclusion a tautology.
                val rawEnd = toks(shippedCtxs.last.raw)
                assert(rawEnd > window, s"REGIME: the session must exceed the model window, raw $rawEnd vs $window")

                // (2) PARITY, the observable half: both arms stay inside the hard limit. This is the whole
                // of what parity can mean without a model, and the comment above says why that is weak.
                val hard = Compactor.internal.axis(Compactor.Tuning(), cfg).hard
                assert(shippedViews.map(v => v.foldLeft(0)((a, m) => a + size(m))).max <= hard, "the shipped arm fits the hard limit")
                assert(wholeViews.map(v => v.foldLeft(0)((a, m) => a + size(m))).max <= hard, "the competitor fits the hard limit")

                val rates       = CacheCost.Rates()
                val shippedCost = CacheCost.estimate(shippedViews, size, outputPerTurn = summaryCap)
                val wholeCost   = CacheCost.estimate(wholeViews, size, outputPerTurn = summaryCap)

                // (3) THE RECORDED FACT. Asserted in the direction measured, so a change in either
                // direction fails here and has to be explained rather than absorbed.
                assert(
                    shippedCost.equivalents(rates) > wholeCost.equivalents(rates),
                    f"RECORDED: whole-transcript summarization is cheaper in billed units " +
                        f"(shipped=${shippedCost.equivalents(rates)}%.0f whole=${wholeCost.equivalents(rates)}%.0f). " +
                        "If the shipped arm now wins, that is good news: re-derive the cost story rather than assuming it."
                )

                // (4) THE EXPLANATION, so the recorded fact is legible: the shipped arm takes more
                // boundaries, each one a paid head-of-prompt invalidation. That is the mechanism, and it is
                // the same critique that retired the drift trigger: a policy whose success mode is MORE
                // boundaries costs money to succeed.
                assert(
                    shippedCost.invalidations > wholeCost.invalidations,
                    s"the cost gap must be explained by boundary count: shipped=${shippedCost.invalidations} " +
                        s"whole=${wholeCost.invalidations}"
                )

                // (5) RATE ROBUSTNESS: the recorded direction is not an artifact of one cache discount.
                val dear = CacheCost.Rates(cachedRead = 0.25)
                assert(
                    shippedCost.equivalents(dear) > wholeCost.equivalents(dear),
                    "the recorded direction must hold at a costlier cached read too, or it is a rate artifact"
                )
            }
        }
    }

    "the cost gap decomposed: residency against rewrite penalty" in {
        // The recorded gap above says the shipped arm costs more. It does not say WHAT it is paying for,
        // and the two candidates call for opposite work. RESIDENCY is content sitting in the served view
        // being re-billed at the cached rate every turn: it is paid for by serving less. REWRITE PENALTY
        // is content re-sent at full price because a boundary moved the prefix: it is paid for by making
        // boundaries rarer or by bounding how far back one reaches.
        //
        // Any cheaper-boundary scheme is worth at most the rewrite term, so this bounds
        // them all before any of them is designed. The billed unit decomposes exactly, with no modelling
        // left over: equivalents = uncached + cachedRead * cached + output * generated. The first term is
        // the rewrite penalty, the second is residency, and the third is identical across the arms
        // because both are charged the same per-turn output over the same number of turns, so it cancels
        // from the gap entirely.
        //
        // The second half is where each rewrite BEGINS. A request's cached prefix length is exactly the
        // token depth at which that turn's rewrite started, so the distribution over the turns that
        // actually rewrote is what a bounded-reach proposal would have to beat, and without it such a
        // proposal cannot be priced at all.
        // MEASURED, and the absolute is a SAMPLE rather than a constant. The shipped arm's cost is not
        // reproducible run to run: a boundary reads the speculative preparation cell as it finds it, and
        // what the background fiber has managed to stage by then depends on thread timing, so adoption,
        // the cut, and therefore the boundary cadence all move a little between runs. Three runs at one
        // commit measured totals of 364227, 371754 and 372655 with 33, 34 and 35 rewrites. The competitor
        // has no such fiber and returned 280783 every single time. So the arm is SAMPLED here, the
        // assertions are on the RATIO, which is stable, and no absolute is asserted anywhere.
        //
        // RESULT. Across the samples the gap decomposes as roughly 90 to 93 percent rewrite penalty
        // against 7 to 10 percent residency. That answers the question in the direction that keeps
        // cheaper-boundary work ON the table rather than closing it: serving less could recover only the
        // residency sliver, while the rewrite term is where essentially the whole gap lives.
        //
        // WHERE REWRITES BEGIN, the second half. The shipped arm rewrote 33 to 35 times at a median depth
        // of roughly 2600 to 3600 tokens; the competitor rewrote 7 times at a median depth of 684. So the
        // shipped arm's individual rewrites are CHEAPER, since it preserves several times more prefix
        // each time, and it is the COUNT that makes it expensive. A bounded-reach proposal is worth the
        // difference between today's depth and the bound it would impose, and that is what it must be
        // priced against.
        val summaryCap = Compactor.Tuning().summaryOutputCap
        val whole      = WholeSummary(summaryCap * 4)
        val turns      = 80
        val samples    = 3
        Kyo.foreach(0 until samples)(_ => grow(Compactor.init, turns)).map { shippedRuns =>
            Kyo.foreach(0 until 2)(_ => grow(whole, turns)).map { wholeRuns =>
                val size: Message => Int = m => m.tokens.map(_.count).getOrElse(m.content.length / 3 + 4)
                val rates                = CacheCost.Rates()
                def cost(ctxs: Chunk[Context]): CacheCost.Session =
                    CacheCost.estimate(ctxs.map(_.compacted).toList, size, outputPerTurn = summaryCap)

                def rewriteTerm(s: CacheCost.Session): Double   = s.requests.foldLeft(0.0)((a, r) => a + r.uncached)
                def residencyTerm(s: CacheCost.Session): Double = rates.cachedRead * s.requests.foldLeft(0.0)((a, r) => a + r.cached)
                def outputTerm(s: CacheCost.Session): Double    = rates.output * s.requests.foldLeft(0.0)((a, r) => a + r.output)
                // Where each rewrite began, in tokens from the head of the view.
                def rewriteDepths(s: CacheCost.Session): List[Int] =
                    s.requests.toList.sliding(2).collect { case List(prev, cur) if cur.rewroteFrom(prev) => cur.cached }.toList
                def med(xs: List[Int]): Int = if xs.isEmpty then 0 else xs.sorted.apply(xs.size / 2)

                val shippedCosts = shippedRuns.toList.map(cost)
                val wholeCosts   = wholeRuns.toList.map(cost)
                val reference    = wholeCosts.head
                val refDepths    = rewriteDepths(reference)

                shippedCosts.zipWithIndex.foreach { (s, i) =>
                    val depths = rewriteDepths(s)
                    println(
                        f"[decomp] shipped#$i rewrite=${rewriteTerm(s)}%.0f residency=${residencyTerm(s)}%.0f " +
                            f"output=${outputTerm(s)}%.0f total=${s.equivalents(rates)}%.0f " +
                            s"rewrites=${depths.size} medDepth=${med(depths)} minDepth=${depths.minOption.getOrElse(0)}"
                    )
                }
                println(
                    f"[decomp] whole     rewrite=${rewriteTerm(reference)}%.0f residency=${residencyTerm(reference)}%.0f " +
                        f"output=${outputTerm(reference)}%.0f total=${reference.equivalents(rates)}%.0f " +
                        s"rewrites=${refDepths.size} medDepth=${med(refDepths)} minDepth=${refDepths.minOption.getOrElse(0)}"
                )

                // (1) The decomposition is exact, which is what lets the terms be read as a budget at all.
                shippedCosts.foreach { s =>
                    val recomposed = rewriteTerm(s) + residencyTerm(s) + outputTerm(s)
                    assert(
                        math.abs(recomposed - s.equivalents(rates)) < 1.0,
                        s"the three terms must recompose the billed total exactly: $recomposed vs ${s.equivalents(rates)}"
                    )
                }

                // (2) The output term cancels, so the gap is rewrite plus residency and nothing else.
                shippedCosts.foreach { s =>
                    assert(
                        math.abs(outputTerm(s) - outputTerm(reference)) < 1.0,
                        "REGIME: both arms must be charged the same output, or the gap is not a policy comparison"
                    )
                }

                // (3) THE RECORDED FINDING, as a ratio because the absolute is a sample. Every sample must
                // put the great majority of the gap in the rewrite term. The floor sits well below the
                // measured 90 to 93 percent so ordinary run-to-run movement does not trip it, while a real
                // shift of the gap into residency still fails here and has to be explained.
                shippedCosts.zipWithIndex.foreach { (s, i) =>
                    val totalGap     = s.equivalents(rates) - reference.equivalents(rates)
                    val rewriteGap   = rewriteTerm(s) - rewriteTerm(reference)
                    val residencyGap = residencyTerm(s) - residencyTerm(reference)
                    assert(totalGap > 0.0, f"REGIME: sample $i must reproduce the adverse direction, gap $totalGap%.0f")
                    assert(
                        rewriteGap / totalGap > 0.8,
                        f"sample $i: the gap must be dominated by the rewrite penalty, got ${rewriteGap / totalGap}%.3f " +
                            f"(rewrite $rewriteGap%.0f residency $residencyGap%.0f of $totalGap%.0f)"
                    )
                }

                // (4) The competitor is deterministic, which is both the control for the sampling above and
                // the evidence for its cause: it runs no speculative fiber, and it does not move.
                assert(
                    wholeCosts.map(_.equivalents(rates)).distinct.size == 1,
                    s"the competitor must be reproducible: ${wholeCosts.map(_.equivalents(rates))}"
                )

                // (5) Rewrites START DEEPER in the shipped arm than in the competitor, and there are more
                // of them: together those two facts are the whole shape of the gap.
                shippedCosts.zipWithIndex.foreach { (s, i) =>
                    assert(
                        med(rewriteDepths(s)) > med(refDepths),
                        s"sample $i: the shipped arm must preserve more prefix per rewrite than the competitor, " +
                            s"got ${med(rewriteDepths(s))} vs ${med(refDepths)}"
                    )
                    assert(
                        rewriteDepths(s).size > refDepths.size,
                        s"sample $i: and must take more of them, got ${rewriteDepths(s).size} vs ${refDepths.size}"
                    )
                }
                succeed
            }
        }
    }

    "the analysis pass's spend when its answers do not parse, measured" in {
        // The throttle half of the carried decision was held pending a number: what the analysis pass
        // costs a session. The number is measured here rather than assumed, because nothing else produces it and
        // the grounds that throttling was premature without it. This produces a number, and it is a
        // number about ONE REGIME, which the test says out loud rather than letting a reader generalize.
        //
        // The regime is the FAILING one, and not by choice: the scripted server answers every request
        // from one FIFO queue of plain string results, so it cannot return a decodable Analysis. The pass
        // generates typed, so every attempt fails to decode, spends the eval loop's five iterations plus
        // the repair turn, and degrades silently by design. This fixture therefore cannot say what a
        // healthy pass costs. What it can say is what a pass costs when the model cannot answer it, which
        // is the case the silent degradation is built for and the one nobody had priced.
        //
        // MEASURED at 80 turns: 346 requests, of which 80 foreground, 36 fills and 230 analysis. The
        // analysis pass issues nearly three calls per user turn and carries about 80 percent of the
        // session's request bytes, and it buys exactly nothing: zero analyses are adopted. Six calls per
        // arming across roughly 38 armings.
        //
        // WHAT THAT DECIDES. The obvious lever is cadence, fewer armings. The
        // measurement points somewhere else first: the multiplier is the retry budget, and spending five
        // iterations plus a repair on a fire-and-forget enrichment whose failure is explicitly designed
        // to be harmless is disproportionate on its face. A pass that may contribute nothing should not
        // be able to cost six times its own nominal price to contribute nothing. Cadence is the second
        // lever, and it is worth less: it scales a term this one divides by six.
        //
        // The fills are the control. They are answerable by this fixture, and they cost one call each,
        // which is what makes the analysis multiplier legible as a multiplier rather than as a count.
        val turns = 80
        growCapturing(Compactor.init, turns).map { (ctxs, received) =>
            def isAnalysis(b: String): Boolean = b.contains("Analyze how each listed region depends on")
            def isFill(b: String): Boolean     = b.contains("Summarize the following span of a conversation")
            val analysis                       = received.filter(c => isAnalysis(c.body))
            val fills                          = received.filter(c => isFill(c.body))
            val foreground                     = received.filter(c => !isAnalysis(c.body) && !isFill(c.body))
            // Request body length stands in for input size: it is the serialized request, so it carries
            // the whole prompt, and the comparison between classes is what matters, not an absolute.
            def chars(cs: Chunk[TestCompletionServer.Captured]): Long = cs.foldLeft(0L)((a, c) => a + c.body.length)
            val total                                                 = chars(received).toDouble

            println(
                s"[spend] turns=$turns requests=${received.size} " +
                    f"foreground=${foreground.size} (${chars(foreground) / total * 100}%.1f%% of input chars) " +
                    f"analysis=${analysis.size} (${chars(analysis) / total * 100}%.1f%%) " +
                    f"fills=${fills.size} (${chars(fills) / total * 100}%.1f%%) " +
                    s"analysesAdopted=${ctxs.last.compactionState.analyses.size}"
            )

            assert(ctxs.size == turns, s"REGIME: every turn must produce a context, got ${ctxs.size}")
            assert(foreground.size >= turns, s"REGIME: the foreground must issue at least one call per turn, got ${foreground.size}")
            // THE REGIME, pinned rather than assumed: this fixture cannot answer a typed analysis, so not
            // one analysis is adopted. Everything below is a statement about that case only, and if the
            // fixture ever gains the ability to answer, this fails and the numbers above must be re-read.
            assert(
                ctxs.last.compactionState.analyses.isEmpty,
                s"REGIME: the scripted fixture cannot answer a typed analysis, so none may be adopted, got ${ctxs.last.compactionState.analyses.size}"
            )
            // The control: fills ARE answerable here, and they do not retry.
            assert(fills.nonEmpty, "REGIME: the fills must run, or there is no control for the multiplier")
            // THE RECORDED FACT. A pass that adopted nothing outspent the entire foreground, in calls and
            // in bytes. If a retry-budget or cadence change ever lands, this is the assertion that should
            // start failing, and its failure is the evidence that the change worked.
            assert(
                analysis.size > foreground.size,
                s"RECORDED: the failing analysis pass outspends the foreground in calls, " +
                    s"analysis ${analysis.size} vs foreground ${foreground.size}"
            )
            assert(
                chars(analysis).toDouble / total > 0.5,
                f"RECORDED: and in input bytes, ${chars(analysis).toDouble / total}%.3f of the session"
            )
            assert(
                analysis.size > 4 * fills.size,
                s"RECORDED: the retry multiplier is visible against the answerable pass, " +
                    s"analysis ${analysis.size} vs fills ${fills.size}"
            )
        }
    }

    "the marker floor, measured against session length" in {
        // The irreducible part of the served view: the stand-ins that remain after everything demotable
        // has been demoted. Our ladder is strictly one marker per span (Summary, Terse and Pointer are all
        // per-span states) and nothing merges adjacent demoted spans into a higher-level node, so the view
        // carries a term that grows with session length and has no floor below Pointer.
        //
        // This exists to convert "we know we lack rollup" into "here is the session length at which
        // lacking it costs us", and to record the growth SHAPE. A linear floor is not a bug today; an
        // unrecorded linear floor is how a session length gets discovered in production instead.
        //
        // MEASURED at 120 turns on this fixture, and the result moderates the concern rather than
        // confirming it. Floor tokens by turn: t29 1654, t59 1470, t89 1574, t119 2626, against a hard
        // limit of 11059. That is 1.59x over 4x the turns, comfortably sublinear, and the marker COUNT
        // even falls mid-run (24, 18, 21, 32).
        //
        // The reason is that the retention forget already performs a crude rollup: when it fires it
        // collapses a whole contiguous run of forgotten spans into ONE coarse band marker, so the far tail
        // of a long session costs a single entry rather than one per span. Cross-span rollup would still
        // help the band BETWEEN the tail and the forget horizon, where spans are demoted but not yet
        // forgotten; it is an optimization there, not the correctness requirement a linear floor would
        // have made it. The final-quarter floor at 24% of the hard limit is the number to watch.
        val turns = 120
        grow(capped, turns).map { ctxs =>
            val size: Message => Int = m => m.tokens.map(_.count).getOrElse(m.content.length / 3 + 4)
            val series = ctxs.toList.zipWithIndex.map { (ctx, i) =>
                val markers = ctx.compacted.filter(_.origin.isDefined)
                (i, toks(ctx.compacted), markers.size, markers.foldLeft(0)((a, m) => a + size(m)))
            }
            val (_, servedEnd, markerCountEnd, floorEnd) = series.last
            val hard = Compactor.internal.axis(Compactor.Tuning(rawRetentionCap = Present(rawCap)), cfg).hard
            println(
                s"[floor] turns=$turns served=$servedEnd markers=$markerCountEnd floor=$floorEnd hard=$hard " +
                    s"| quarter-points: ${series.filter((i, _, _, _) => i % 30 == 29).map((i, s2, c, f) => s"t$i:s$s2/m$c/f$f").mkString(" ")}"
            )

            // (1) REGIME: the run must be in the regime where the floor is the binding term, meaning
            // boundaries fired and the forget engaged. Otherwise this measures a session that never
            // demoted anything and the floor is trivially zero.
            val boundaries = series.map(_._2).sliding(2).count { case a :: b :: Nil => b < a; case _ => false }
            assert(boundaries >= 5, s"REGIME: the run must fire boundaries, got $boundaries over $turns turns")
            assert(
                ctxs.last.raw.exists(_.content.contains("forgotten past the retention horizon")),
                "REGIME: the forget must have engaged, so the tail of the run is past the retention cap"
            )
            assert(markerCountEnd > 0, s"REGIME: the view must carry markers to have a floor at all, got $markerCountEnd")

            // (2) NO OVERFLOW, asserted rather than inferred from the run not throwing: `grow` swallows
            // aborts per turn, so a swallowed AIContextOverflowException would otherwise pass silently.
            assert(
                servedEnd <= hard,
                s"the served view must stay inside the hard limit at turn $turns: $servedEnd > $hard"
            )

            // (3) FLOOR BOUND, stated as MEASURED at this turn count rather than chosen. The fraction is
            // recorded from the run; if it moves, this is where it surfaces.
            assert(
                floorEnd * 2 < hard,
                s"the marker floor must stay well under the hard limit: floor $floorEnd against hard $hard at $turns turns"
            )

            // (4) GROWTH SHAPE. Compare the floor over the last quarter against the floor over the second
            // quarter: a floor growing linearly in session length would roughly double. This is the record
            // of the shape, and the assertion is deliberately loose enough to be about SHAPE rather than
            // about this fixture's constant.
            val q2 = series.filter((i, _, _, _) => i >= turns / 4 && i < turns / 2).map(_._4).maxOption.getOrElse(0)
            val q4 = series.filter((i, _, _, _) => i >= 3 * turns / 4).map(_._4).maxOption.getOrElse(0)
            assert(q2 > 0, s"REGIME: the second quarter must already carry a floor, got $q2")
            assert(
                q4 <= q2 * 3,
                s"the marker floor must not grow faster than the session: second-quarter peak $q2, " +
                    s"final-quarter peak $q4 over $turns turns. If this fails the floor is superlinear and " +
                    "cross-span rollup stops being an optimization and becomes a correctness requirement."
            )
        }
    }

    "a session that cannot render below its trigger does not re-compact every turn" in {
        // The PARKED-ABOVE-TRIGGER case, which carries a claim worth checking:
        // that a parked session pays roughly 1.7 to 1.8 head-of-prompt invalidations per user turn, and
        // that this is the subsystem's dominant cost item. On the ordinary fixture the measured rate is
        // 0.125 per turn (5 invalidations over 40 turns), an order of magnitude lower, so either the claim
        // describes a regime the ordinary fixture never enters, or it is stale. This builds that regime.
        //
        // Parking means occupancy stays above the trigger even AFTER a boundary rendered everything it
        // could. The way to force it is content the cut may not touch: the tail band is protected by
        // construction, so a conversation whose recent turns alone exceed the trigger cannot be rendered
        // below it. If every such turn then re-fires a boundary, each one is a paid prefix rewrite and the
        // session pays continuously rather than at intervals.
        val heavyTail      = "the reconciliation order decides which write wins on conflict. " * 90
        def parked(i: Int) = s"step $i: $heavyTail"
        val a              = Compactor.internal.axis(Compactor.Tuning(), cfg)

        TestCompletionServer.run { server =>
            Kyo.foreachDiscard(0 until 400)(i => server.enqueueBody(genBody(Json.encode(s"answer $i")))).andThen {
                LLM.run(cfg.apiUrl(server.baseUrl)) {
                    AI.init.map(_.enable(Compactor.init)).map { ai =>
                        ai.setContext(Context(Chunk[Message](SystemMessage("you are a systems assistant")))).andThen {
                            Kyo.foreach(0 until 24) { i =>
                                ai.userMessage(parked(i)).andThen(ai.gen[String]).handle(Abort.run[Any]).andThen(ai.context)
                            }.map { ctxs =>
                                val size: Message => Int = m => m.tokens.map(_.count).getOrElse(m.content.length / 3 + 4)
                                val views                = ctxs.toList.map(_.compacted)
                                val cost                 = CacheCost.estimate(views, size, outputPerTurn = 512)
                                val occ                  = views.map(v => v.foldLeft(0)((acc, m) => acc + size(m)))
                                val perTurn              = cost.invalidations.toDouble / views.size
                                println(
                                    f"[parked] turns=${views.size} invalidations=${cost.invalidations} perTurn=$perTurn%.3f " +
                                        f"peak=${occ.max} trigger=${a.high} hard=${a.hard} hit=${cost.hitRate}%.3f"
                                )

                                // THE RESULT: the session does NOT park. Driven by turns whose own size is a
                                // large fraction of the trigger, the served view peaks at 6842 against a
                                // trigger of 8192 and a hard limit of 11059, so a boundary always succeeds
                                // in rendering back below the line.
                                //
                                // That answers the premise this test was built to check. The plan records
                                // B-13 as "a parked session pays ~1.7-1.8 head-of-prompt invalidations per
                                // user turn, the subsystem's dominant cost item". It is not reproducible
                                // here: the measured rate is 0.167 per turn in this stress case and 0.125 in
                                // the ordinary one. The protected bands (head, tail, pinned) are small
                                // relative to the trigger, so the cut always has enough demotable mass to
                                // get back under it.
                                //
                                // Asserted as the stress REGIME plus the non-parking result, so that a
                                // change which does make a session park fails here rather than becoming a
                                // silent per-turn cost.
                                assert(
                                    occ.max > a.low,
                                    s"REGIME: the session must at least exceed the render-down target, peak ${occ.max} vs ${a.low}"
                                )
                                assert(
                                    occ.max < a.high,
                                    s"a boundary must always render back below the trigger, so no session parks and pays " +
                                        s"per-turn invalidations: peak ${occ.max} vs trigger ${a.high}"
                                )
                                // SAFETY holds regardless: parking must never breach the hard limit, which is
                                // the forced path's job.
                                assert(occ.max <= a.hard, s"a parked session must still fit the hard limit: ${occ.max} vs ${a.hard}")
                                // THE MEASURED RATE. Asserted well below one per turn, because "every turn is
                                // a boundary" is the pathology: it would mean the prefix is rewritten
                                // continuously and the cache never survives a single turn.
                                assert(
                                    perTurn < 1.0,
                                    f"a parked session must not rewrite the prefix on every turn: $perTurn%.3f per turn " +
                                        s"over ${views.size} turns"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    "the irreversible forget engages at depth and not before" in {
        // Eviction is the only step that destroys content rather than demoting it. BOTH directions are
        // asserted, because a check that confirms only the deep case would also pass an implementation
        // that forgets far too eagerly, which is the more damaging failure. Read on RAW rather than on the
        // served view: the forget rewrites the transcript in place, so a test watching the view sees
        // nothing and concludes eviction never fires, which is a mistake this assertion once made.
        val marker                     = "forgotten past the retention horizon"
        def forgot(cs: Chunk[Context]) = cs.exists(_.raw.exists(_.content.contains(marker)))
        grow(capped, 8).map { shallow =>
            grow(capped, 60).map { deep =>
                assert(
                    toks(shallow.last.raw) < rawCap,
                    s"REGIME: the shallow run must stay under the retention cap: ${toks(shallow.last.raw)} vs $rawCap"
                )
                assert(!forgot(shallow), "a conversation inside the retention cap must forget nothing")
                assert(forgot(deep), s"a conversation well past the retention cap ($rawCap) must reach the forget")
            }
        }
    }

    "the session reuses its cached prefix, in the unit the provider bills" in {
        // Ties the cadence property to money, through the instrument that prices it. Prompt caching bills
        // the suffix after the longest shared prefix, so cost concentrates in the boundaries that rewrite
        // the prefix. Asserted as a direction rather than a figure: a ratio would encode the fixture's
        // regime, while "some prefix is reused, and not every turn invalidates" is the property itself.
        grow(capped, 40).map { contexts =>
            val views = contexts.map(_.compacted).toList
            val cost  = CacheCost.estimate(views, m => m.tokens.map(_.count).getOrElse(m.content.length / 3 + 4))
            assert(cost.requests.size == views.size, "one priced request per served view")
            assert(cost.hitRate > 0.5, s"a grown conversation must reuse most of its prefix, got hit rate ${cost.hitRate}")
            assert(
                cost.invalidations * 2 < views.size,
                s"${cost.invalidations} of ${views.size} turns invalidated the cache; boundaries must be the exception"
            )
        }
    }

end CompactorContractTest
