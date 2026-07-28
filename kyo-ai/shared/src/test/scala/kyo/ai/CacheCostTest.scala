package kyo.ai

import kyo.*
import kyo.ai.Context.*

/** Calibration for [[CacheCost]], against known ground truth.
  *
  * "The instrument produces a number" is passed by an instrument that returns 0, or one that counts
  * cached and uncached tokens interchangeably, which is exactly the confusion this instrument exists to
  * end. So every case below constructs a situation whose correct answer is known by hand, and asserts
  * that exact answer. Downstream cost rulings are made in this unit; if the unit is wrong they are all
  * wrong together and nothing else would notice.
  */
class CacheCostTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage      = UserMessage(s, Absent)
    def am(s: String): AssistantMessage = AssistantMessage(s)

    /** One token per character, so every expected number below is countable by eye. */
    val size: Message => Int = _.content.length

    "a pure append charges only the appended suffix" in {
        // The ideal case, and the baseline any compaction strategy is compared against. The prefix never
        // changes, so every turn after the first pays for its own new message and nothing else.
        val a = Chunk[Message](um("aaaa"), am("bbbb")) // 8
        val b = a.append(um("cc"))                     // +2
        val c = b.append(am("ddd"))                    // +3
        val s = CacheCost.estimate(List(a, b, c), size)
        assert(s.requests.size == 3, "one request per view")
        assert(s.requests(0) == CacheCost.Request(8, 0, 0), s"the first request is entirely uncached, got ${s.requests(0)}")
        assert(s.requests(1) == CacheCost.Request(2, 8, 0), s"an append pays for the new message only, got ${s.requests(1)}")
        assert(s.requests(2) == CacheCost.Request(3, 10, 0), s"and again, got ${s.requests(2)}")
        assert(s.invalidations == 0, "an append never invalidates: nothing before the new message changed")
    }

    "a prefix break charges from the break onward, exactly" in {
        // The compaction case. One message near the head is REPLACED, so everything from it on is billed
        // fresh even though the tail is untouched. The known answer: the first message is still cached
        // (4 chars), the rewritten message and both following it are not (2 + 4 + 4 = 10).
        val before          = Chunk[Message](um("aaaa"), am("bbbb"), um("cccc"), am("dddd"))
        val after           = Chunk[Message](um("aaaa"), am("XX"), um("cccc"), am("dddd"))
        val (fresh, cached) = CacheCost.split(before, after, size)
        assert(cached == 4, s"only the untouched head is cached, got $cached")
        assert(fresh == 10, s"the rewritten message and everything after it is fresh, got $fresh")
    }

    "the head being rewritten cancels the cache entirely" in {
        // The worst case, and the one a whole-conversation summary hits every time it regenerates: the
        // change is at position 0, so nothing at all can be reused.
        val before          = Chunk[Message](um("aaaa"), am("bbbb"))
        val after           = Chunk[Message](um("ZZZZ"), am("bbbb"))
        val (fresh, cached) = CacheCost.split(before, after, size)
        assert(cached == 0, s"a head rewrite caches nothing, got $cached")
        assert(fresh == 8, s"the whole view is billed fresh, got $fresh")
    }

    "a same-length different-content message is NOT treated as cached" in {
        // The failure this instrument must not have. Comparing by size rather than content would call
        // these identical and report a full cache hit on a view that changed completely. Eviction
        // substitutes tombstones IN PLACE, preserving length, so this is the realistic shape of the bug.
        val before          = Chunk[Message](um("aaaa"), am("bbbb"))
        val after           = Chunk[Message](um("aaaa"), am("cccc"))
        val (fresh, cached) = CacheCost.split(before, after, size)
        assert(cached == 4, s"the identical head is cached, got $cached")
        assert(fresh == 4, s"a same-length rewrite is NOT cached, got $fresh")
    }

    "role changes break the match even when content is identical" in {
        val before          = Chunk[Message](um("same"))
        val after           = Chunk[Message](am("same"))
        val (fresh, cached) = CacheCost.split(before, after, size)
        assert(cached == 0 && fresh == 4, s"a role change is a different message: fresh=$fresh cached=$cached")
    }

    "equivalents price the three token kinds at their stated ratios" in {
        val r = CacheCost.Request(uncached = 100, cached = 200, output = 10)
        val e = r.equivalents(CacheCost.Rates(cachedRead = 0.1, output = 5.0))
        assert(e == 100 + 20 + 50, s"100 + 0.1*200 + 5*10 = 170, got $e")
        // and the ratios are parameters, so a conclusion that only holds at one price is visible
        val cheap = r.equivalents(CacheCost.Rates(cachedRead = 0.05, output = 5.0))
        val dear  = r.equivalents(CacheCost.Rates(cachedRead = 0.25, output = 5.0))
        assert(cheap < e && e < dear, s"the read ratio must move the total: $cheap < $e < $dear")
    }

    "a reported split reconciles with what the provider billed" in {
        // The leg that makes the estimate trustworthy: when a provider reports its own cached count, the
        // instrument must use it rather than re-deriving one. cachedInputTokens is PART OF inputTokens,
        // so the uncached remainder is the difference, not the sum.
        val s = CacheCost.reported(List(
            AIStats(inputTokens = 1000, cachedInputTokens = Present(600), outputTokens = 50, reasoningOutputTokens = Absent, turns = 1),
            AIStats(inputTokens = 1200, cachedInputTokens = Present(1100), outputTokens = 30, reasoningOutputTokens = Absent, turns = 1)
        ))
        assert(s.requests(0) == CacheCost.Request(400, 600, 50), s"1000 total of which 600 cached leaves 400, got ${s.requests(0)}")
        assert(s.requests(1) == CacheCost.Request(100, 1100, 30), s"got ${s.requests(1)}")
        assert(s.inputTokens == 2200, s"input is the reported total, got ${s.inputTokens}")
        assert(math.abs(s.hitRate - 1700.0 / 2200.0) < 1e-9, s"hit rate is cached over input, got ${s.hitRate}")
    }

    "an absent cached count is treated as none cached, never as all cached" in {
        // A provider that reports nothing must read as the expensive case, so a missing measurement can
        // never flatter a strategy.
        val s = CacheCost.reported(List(
            AIStats(inputTokens = 500, cachedInputTokens = Absent, outputTokens = 10, reasoningOutputTokens = Absent, turns = 1)
        ))
        assert(s.requests.head == CacheCost.Request(500, 0, 10), s"absent means nothing cached, got ${s.requests.head}")
        assert(s.hitRate == 0.0, "and the hit rate reads zero rather than undefined")
    }

    "invalidations count rewrites and not appends, which is the whole distinction" in {
        // The diagnostic that separates a compaction strategy from an append-only conversation. An
        // earlier version of it tested "has both cached and uncached tokens", which is true of every
        // ordinary append, so it reported append-only traffic as invalidating on every turn: the one
        // number that was supposed to distinguish the two strategies could not.
        val a = Chunk[Message](um("aaaa"), am("bbbb"))
        val b = a.append(um("cc"))
        val c = b.append(am("ddd"))
        assert(CacheCost.estimate(List(a, b, c), size).invalidations == 0, "three appends invalidate nothing")

        // now rewrite a message in the middle, as a demotion does
        val d           = Chunk[Message](um("aaaa"), am("XX"), um("cc"), am("ddd"))
        val withRewrite = CacheCost.estimate(List(a, b, c, d), size)
        assert(withRewrite.invalidations == 1, s"one rewrite is one invalidation, got ${withRewrite.invalidations}")
    }

end CacheCostTest
