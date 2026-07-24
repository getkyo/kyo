package kyo

class AIStatsTest extends kyo.test.Test[Any]:

    "empty is the identity of add" in {
        val stats = AIStats(100L, Present(30L), 40L, Present(10L), 2)
        assert(AIStats.empty.add(stats) == stats)
        assert(stats.add(AIStats.empty) == stats)
        assert(AIStats.empty == AIStats(0L, Absent, 0L, Absent, 0))
    }

    "add sums totals, turns, and reported subsets" in {
        val a = AIStats(100L, Present(30L), 40L, Present(10L), 1)
        val b = AIStats(50L, Present(20L), 15L, Present(5L), 1)
        assert(a.add(b) == AIStats(150L, Present(50L), 55L, Present(15L), 2))
    }

    "add keeps the reporting side of a subset one wire broke out and the other did not" in {
        // A mixed-wire aggregate: the sum is a stated lower bound, never a lost number.
        val reporting = AIStats(100L, Present(30L), 40L, Present(10L), 1)
        val silent    = AIStats(50L, Absent, 15L, Absent, 1)
        assert(reporting.add(silent) == AIStats(150L, Present(30L), 55L, Present(10L), 2))
        assert(silent.add(reporting) == AIStats(150L, Present(30L), 55L, Present(10L), 2))
    }

    "add keeps Absent when neither wire broke the subset out" in {
        val a = AIStats(10L, Absent, 5L, Absent, 1)
        val b = AIStats(20L, Absent, 5L, Absent, 1)
        assert(a.add(b) == AIStats(30L, Absent, 10L, Absent, 2))
    }

    "a reported zero stays distinguishable from not reported" in {
        val zero   = AIStats(10L, Present(0L), 5L, Present(0L), 1)
        val silent = AIStats(10L, Absent, 5L, Absent, 1)
        assert(zero.add(zero).cachedInputTokens == Present(0L))
        assert(zero.add(zero).reasoningOutputTokens == Present(0L))
        assert(silent.add(silent).cachedInputTokens == Absent)
        assert(zero != silent)
    }

    "totalTokens sums input and output" in {
        assert(AIStats(100L, Present(30L), 40L, Present(10L), 1).totalTokens == 140L)
        assert(AIStats.empty.totalTokens == 0L)
    }

end AIStatsTest
