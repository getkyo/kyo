package kyo

import kyo.ai.*
import kyo.ai.Context.*

/** The two reference strategies the default compactor is measured against.
  *
  * Ablating the default against itself can say what each mechanism contributes to THIS design, but it
  * cannot say whether the design beats what anyone would reach for first. These are the outside
  * comparisons: one whole-session summarization (what a `/compact` command does) and brutal truncation
  * (the zero-intelligence floor). Both are ordinary `Compactor`s, so they run through the same seam and
  * the same probes and differ only in strategy.
  *
  * These are LIVE arms: `Naive` calls the model. The deterministic cost-shape stand-ins used by the
  * offline batteries live with their tests; these exist to be run against a real provider, where the
  * quality side of the comparison is decided.
  */
object CompactorBaselines:

    /** One whole-session summarization on the main model: the common approach.
      *
      * When the window fills, send the history to the model, ask for a summary, continue with that
      * summary plus the recent turns. Uses the MAIN model, as the common approach does; the kyo default
      * instead routes fills to `provider.small` with a bounded per-span input, which is exactly the cost
      * difference under test.
      */
    final class Naive(keepRecent: Int, budgetTokens: Int) extends Compactor[Any]:

        type State = Unit
        def initState(using Frame): Unit < Sync = Kyo.unit

        def compact(ctx: Context, state: Unit)(using Frame): Compactor.Decision < (LLM & Async & Abort[AIGenException]) =
            // Summarizes its OWN SERVED VIEW, not the whole transcript. Summarizing `ctx.raw` is what a
            // first draft of this arm did, and it cannot run the regimes it exists to be measured in: for
            // a non-default compactor `raw` is never shrunk and never evicted, so the summarization input
            // grows without bound and the nested call overflows at about one window. A real `/compact`
            // carries its previous summary forward and folds in only what arrived since.
            val served = if ctx.compacted.isEmpty then ctx.raw else ctx.compacted
            val head   = served.take(1)
            val recent = if served.size > keepRecent + 1 then served.takeRight(keepRecent) else Chunk.empty
            val middle = served.drop(head.size).dropRight(recent.size)
            val size   = served.foldLeft(0)((a, m) => a + Compactor.internal.stampedTokens(m))
            // Re-summarize only when the view has REGROWN past the budget, which is what a real `/compact`
            // does. The seam consults the compactor every turn, so summarizing whenever the view merely
            // exceeds the budget regenerates on every single turn: 18 summarization calls for 4 user turns
            // in a first run of this arm. That is not the traditional strategy, it is a strawman of it,
            // and a strawman baseline invalidates the comparison it exists to anchor.
            if size <= budgetTokens || middle.isEmpty then Kyo.lift(Compactor.Decision.Unchanged)
            else
                AI.config.map { config =>
                    val transcript = middle.map(m => s"${m.role.name}: ${m.content}").mkString("\n")
                    LLM.run(config.disableReasoning) {
                        AI.init.map { ai =>
                            ai.userMessage(
                                "Summarize the conversation below so a later reader can continue the work without it. " +
                                    "Preserve every decision, identifier, and numeric value exactly.\n\n" + transcript
                            ).andThen(ai.gen[String])
                        }
                    }.handle(Abort.run[Throwable]).map {
                        case Result.Success(summary) => summary
                        case other                   =>
                            // A summarization that fails or times out must not kill the session. The real
                            // strategy has the same exposure: its ONE call is a single point of failure for
                            // the whole conversation, where a per-span design loses only the span it was
                            // filling. Degrades to dropping the middle, which is what the strategy can
                            // still honestly do.
                            Log.warn(s"[naive] summarization failed, degrading to drop-middle: $other")
                            "[earlier conversation omitted: summarization unavailable]"
                    }.map { summary =>
                        // The summary CARRIES AN ORIGIN over everything it replaces, so this arm is
                        // accountable rather than silently lossy: the model can tell that history was
                        // folded and how much. A cost comparison against an arm that is NOT accountable
                        // measures nothing, because it would be cheaper only by discarding the
                        // conversation. Real `/compact` implementations are accountable in the same way.
                        val covered = math.max(served.size - recent.size, 1)
                        val folded = SystemMessage(
                            s"[summary of earlier conversation]\n$summary",
                            origin = Present(Context.Origin(head.size, covered, ctx.raw.size))
                        )
                        Compactor.Decision.Compacted(ctx.copy(compacted = head.concat(Chunk(folded)).concat(recent)))
                    }
                }
            end if
        end compact
    end Naive

    /** The zero-intelligence canary: keep the system head plus the most recent budget of tokens, drop the
      * rest outright. No model call, no scoring, no summaries.
      *
      * It exists so the comparison has a floor. Any mechanism that cannot beat brutal truncation on
      * quality has no reason to exist, and if truncation TIES the smart arms then the scenario was never
      * exercising memory and the result is void rather than a win.
      */
    final class Truncation(budgetTokens: Int) extends Compactor[Any]:

        type State = Unit
        def initState(using Frame): Unit < Sync = Kyo.unit

        def compact(ctx: Context, state: Unit)(using Frame): Compactor.Decision < (LLM & Async & Abort[AIGenException]) =
            val raw  = ctx.raw
            val head = raw.take(1)
            @annotation.tailrec
            def back(i: Int, acc: Int): Int =
                if i <= 1 then i
                else
                    val t = Compactor.internal.stampedTokens(raw(i - 1))
                    if acc + t > budgetTokens then i else back(i - 1, acc + t)
            val from = back(raw.size, 0)
            val kept = raw.drop(from)
            if from <= head.size then Kyo.lift(Compactor.Decision.Unchanged)
            else
                // No origin, deliberately. Truncation is silently lossy by definition, and that is the
                // property being measured: it is the floor precisely because the model cannot tell what
                // it lost.
                Kyo.lift(Compactor.Decision.Compacted(ctx.copy(compacted = head.concat(kept))))
            end if
        end compact
    end Truncation

end CompactorBaselines
