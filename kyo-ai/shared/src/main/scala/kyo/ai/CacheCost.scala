package kyo.ai

import kyo.*
import kyo.ai.Context.*

/** What a sequence of requests costs when the provider caches prompt prefixes.
  *
  * Prompt caching is a given on this project: a request whose leading tokens match the previous
  * request's is billed at a steep discount for the shared part, and at the full rate from the first
  * differing token onward. Matching is exact and positional, so ONE changed token near the head
  * invalidates everything after it however much later content is unchanged.
  *
  * That makes the interesting quantity neither "tokens sent" nor "context size". A large but stable
  * prefix is nearly free; a small prefix rewritten every turn is billed repeatedly. This object exists
  * because measuring the wrong one of those is easy and has happened: a compaction cost comparison was
  * reported three times in three different units (served tokens, then call counts, then uncached tokens
  * with no amortization) before anyone asked what the provider actually bills.
  *
  * Two ways to get the split, and both are cache-aware:
  *
  *   - REPORTED, when a provider returns its own cached count (codex does, carried on
  *     [[kyo.AIStats.cachedInputTokens]]). Preferred, since it is ground truth.
  *   - ESTIMATED, from the longest common prefix between a request and its predecessor. Used offline,
  *     where no provider is involved. Labelled an estimate wherever it is reported, but never replaced
  *     by a cache-blind number: "we could not measure caching" is not a reason to report tokens sent.
  */
object CacheCost:

    /** Relative prices, as multiples of one uncached input token.
      *
      * Defaults are a common shape at the time of writing (a cached read at a tenth, output at five
      * times). They are parameters rather than constants because the ratios differ per provider and a
      * conclusion that only holds at one ratio is worth knowing about: sweep them.
      */
    final case class Rates(cachedRead: Double = 0.1, output: Double = 5.0) derives CanEqual

    /** One request's billable shape. `cached` is the part covered by the prefix match, so
      * `uncached + cached` is everything sent as input.
      */
    final case class Request(uncached: Int, cached: Int, output: Int) derives CanEqual:
        def inputTokens: Int = uncached + cached

        /** Whether this request REWROTE part of the previous prefix rather than merely extending it.
          *
          * An append leaves the whole previous input cached and pays only for what is new, so its cached
          * count equals the previous request's input. An invalidation caches LESS than that, because the
          * provider's match stopped before the end of what was previously sent. Deciding this needs the
          * previous size, which is why it is a function of both rather than a property of one: an earlier
          * version tested `uncached > 0 && cached > 0`, which is true of every ordinary append and would
          * have reported an append-only conversation as invalidating on every turn.
          */
        def rewroteFrom(previous: Request): Boolean = cached < previous.inputTokens

        /** Cost in uncached-input-token equivalents. */
        def equivalents(rates: Rates): Double =
            uncached + rates.cachedRead * cached + rates.output * output
    end Request

    /** A whole session's cost, plus the two diagnostics that explain it. */
    final case class Session(requests: Chunk[Request]) derives CanEqual:
        def equivalents(rates: Rates): Double = requests.foldLeft(0.0)((a, r) => a + r.equivalents(rates))
        def inputTokens: Int                  = requests.foldLeft(0)((a, r) => a + r.inputTokens)
        def cachedTokens: Int                 = requests.foldLeft(0)((a, r) => a + r.cached)

        /** Fraction of input billed at the cached rate. Explains the cost; not itself a decision metric. */
        def hitRate: Double = if inputTokens == 0 then 0.0 else cachedTokens.toDouble / inputTokens

        /** Requests whose first differing message is not simply an append: the prefix was REWRITTEN, so
          * the cache was invalidated rather than extended. This is the count that distinguishes a
          * compaction strategy from an append-only conversation.
          */
        def invalidations: Int =
            requests.toList.sliding(2).count {
                case prev :: cur :: Nil => cur.rewroteFrom(prev)
                case _                  => false
            }
    end Session

    /** Where two consecutive views first differ, in tokens.
      *
      * Positional and exact, matching how the provider matches: compare message by message and stop at
      * the first that differs in role or content. Everything before that point is cached; everything
      * from it onward is not.
      */
    def split(previous: Chunk[Message], current: Chunk[Message], size: Message => Int): (Int, Int) =
        val shared = previous.toList.zip(current.toList).takeWhile((a, b) => a.role == b.role && a.content == b.content).size
        val cached = current.take(shared).foldLeft(0)((a, m) => a + size(m))
        val fresh  = current.drop(shared).foldLeft(0)((a, m) => a + size(m))
        (fresh, cached)
    end split

    /** ESTIMATED cost of a replayed sequence of views. The first request is entirely uncached, since
      * there is nothing before it to match.
      */
    def estimate(views: Seq[Chunk[Message]], size: Message => Int, outputPerTurn: Int = 0): Session =
        views.toList match
            case Nil => Session(Chunk.empty)
            case first :: rest =>
                val head = Request(first.foldLeft(0)((a, m) => a + size(m)), 0, outputPerTurn)
                val (_, out) =
                    rest.foldLeft((first, Chunk(head))) { case ((prev, acc), cur) =>
                        val (fresh, cached) = split(prev, cur, size)
                        (cur, acc.append(Request(fresh, cached, outputPerTurn)))
                    }
                Session(out)
    end estimate

    /** REPORTED cost, from what the provider billed. `cachedInputTokens` is part of `inputTokens`, so
      * the uncached remainder is the difference.
      */
    def reported(stats: Seq[AIStats]): Session =
        Session(Chunk.from(stats.map { s =>
            val cached = s.cachedInputTokens.getOrElse(0L).toInt
            Request((s.inputTokens.toInt - cached).max(0), cached, s.outputTokens.toInt)
        }))

end CacheCost
