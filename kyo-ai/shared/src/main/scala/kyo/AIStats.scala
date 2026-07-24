package kyo

/** Token usage and turn counts for completed model turns, aggregated by addition.
  *
  * Field names follow the subset convention: a field sharing another's suffix counts a subset of it
  * (`cachedInputTokens` is part of `inputTokens`; `reasoningOutputTokens` is part of `outputTokens`).
  * A subset is `Maybe` because providers report it optionally: `Present` when the wire broke the
  * subset out (possibly as zero), `Absent` when it did not, so "reported zero" and "not reported"
  * stay distinguishable. A total a wire does not report is 0.
  *
  * One wire-decoded reply carries `turns = 1`; a synthetic reply that reached no model carries 0.
  * Aggregation over any scope is then plain [[add]]. Produced per turn on `Completion.Reply` and
  * collected over a scope by `Observe.withStats`.
  */
case class AIStats(
    inputTokens: Long,
    cachedInputTokens: Maybe[Long],
    outputTokens: Long,
    reasoningOutputTokens: Maybe[Long],
    turns: Int
) derives Schema, CanEqual:

    /** Everything read plus everything produced. */
    def totalTokens: Long = inputTokens + outputTokens

    /** Field-wise sum. A subset sums when both sides report it and keeps the reporting side when only
      * one does, so a mixed-wire aggregate is a stated lower bound rather than a lost number.
      */
    def add(that: AIStats): AIStats =
        AIStats(
            inputTokens + that.inputTokens,
            AIStats.addSubset(cachedInputTokens, that.cachedInputTokens),
            outputTokens + that.outputTokens,
            AIStats.addSubset(reasoningOutputTokens, that.reasoningOutputTokens),
            turns + that.turns
        )
end AIStats

object AIStats:

    /** No tokens, no turns, subsets unreported. The identity of [[AIStats.add]]. */
    val empty: AIStats = AIStats(0L, Absent, 0L, Absent, 0)

    private def addSubset(a: Maybe[Long], b: Maybe[Long]): Maybe[Long] =
        a match
            case Present(x) => Present(x + b.getOrElse(0L))
            case Absent     => b
end AIStats
