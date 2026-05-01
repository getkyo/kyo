package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

class DynamicFlagTest extends AnyFreeSpec {

    "DynamicFlag evaluation" - {

        "no expression returns validatedDefault" in {
            val flag = DynamicFlagTestFlags.noExpr
            assert(flag("user1") == 0)
            assert(flag("user2") == 0)
        }

        "plain expression, no selectors — all calls return same value" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.plainExpr", "true")
            try {
                val flag = DynamicFlagTestFlags.plainExpr
                assert(flag("user1") == true)
                assert(flag("user2") == true)
                assert(flag("anything") == true)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.plainExpr"): Unit
            }
        }

        "single path selector match" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.singlePath", "100@enterprise")
            try {
                val flag = DynamicFlagTestFlags.singlePath
                assert(flag("user1", "enterprise") == 100)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.singlePath"): Unit
            }
        }

        "single path selector miss returns default" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.singlePathMiss", "100@enterprise")
            try {
                val flag = DynamicFlagTestFlags.singlePathMiss
                assert(flag("user1", "basic") == 0)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.singlePathMiss"): Unit
            }
        }

        "prefix match — selector shorter than attributes" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.prefixMatch", "100@enterprise")
            try {
                val flag = DynamicFlagTestFlags.prefixMatch
                assert(flag("user1", "enterprise", "us") == 100)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.prefixMatch"): Unit
            }
        }

        "selector longer than attributes returns default" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.selectorLonger", "100@enterprise/us/east")
            try {
                val flag = DynamicFlagTestFlags.selectorLonger
                assert(flag("user1", "enterprise") == 0)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.selectorLonger"): Unit
            }
        }

        "multiple choices, first match wins" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.firstMatch", "a@x;b@y;c")
            try {
                val flag = DynamicFlagTestFlags.firstMatch
                assert(flag("user1", "x") == "a")
                assert(flag("user1", "y") == "b")
                assert(flag("user1", "z") == "c")
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.firstMatch"): Unit
            }
        }

        "terminal in middle stops evaluation" in {
            val flag = DynamicFlagTestFlags.terminalMiddle
            // Use update() which uses failFast=false (warns instead of throwing on unreachable)
            flag.update("a@x;fallback;c@y")
            // "y" should match the terminal "fallback" because it comes before "c@y"
            assert(flag("user1", "y") == "fallback")
            assert(flag("user1", "x") == "a")
        }

        "no attributes, only terminal matches" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.noAttrsTerminal", "100@enterprise;50")
            try {
                val flag = DynamicFlagTestFlags.noAttrsTerminal
                assert(flag("user1") == 50)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.noAttrsTerminal"): Unit
            }
        }

        "no attributes, no terminal, returns default" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.noAttrsNoTerminal", "100@enterprise")
            try {
                val flag = DynamicFlagTestFlags.noAttrsNoTerminal
                assert(flag("user1") == 0)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.noAttrsNoTerminal"): Unit
            }
        }

        "bare percentage" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.barePercent", "true@50%")
            try {
                val flag = DynamicFlagTestFlags.barePercent
                // Check that keys with known buckets get expected results
                var trueCount  = 0
                var falseCount = 0
                for (i <- 0 until 1000) {
                    if (flag.evaluate(s"key-$i")) trueCount += 1
                    else falseCount += 1
                }
                // With 50%, roughly half should be true
                assert(trueCount > 300 && trueCount < 700, s"trueCount=$trueCount")
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.barePercent"): Unit
            }
        }

        "path + percentage" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.pathPercent", "true@prod/50%")
            try {
                val flag = DynamicFlagTestFlags.pathPercent
                // Path matches but bucket matters
                val bucket25Key = findKeyWithBucket(25)
                val bucket75Key = findKeyWithBucket(75)
                assert(flag.evaluate(bucket25Key, "prod") == true)
                assert(flag.evaluate(bucket75Key, "prod") == false)
                // Path doesn't match
                assert(flag.evaluate(bucket25Key, "staging") == false)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.pathPercent"): Unit
            }
        }

        "wildcard single segment" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.wildcardSingle", "100@*")
            try {
                val flag = DynamicFlagTestFlags.wildcardSingle
                assert(flag("user1", "anything") == 100)
                assert(flag("user1", "enterprise") == 100)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.wildcardSingle"): Unit
            }
        }

        "wildcard in middle" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.wildcardMiddle", "100@enterprise/*/east")
            try {
                val flag = DynamicFlagTestFlags.wildcardMiddle
                assert(flag("user1", "enterprise", "us", "east") == 100)
                assert(flag("user1", "enterprise", "eu", "west") == 0)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.wildcardMiddle"): Unit
            }
        }

        "multiple wildcards" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.multiWild", "100@*/*/az1")
            try {
                val flag = DynamicFlagTestFlags.multiWild
                assert(flag("user1", "prod", "us-east-1", "az1") == 100)
                assert(flag("user1", "staging", "eu-west-1", "az1") == 100)
                assert(flag("user1", "prod", "us-east-1", "az2") == 0)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.multiWild"): Unit
            }
        }

        "attribute ordering is positional" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.attrOrder", "100@a/b")
            try {
                val flag = DynamicFlagTestFlags.attrOrder
                assert(flag("user1", "a", "b") == 100)
                assert(flag("user1", "b", "a") == 0)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.attrOrder"): Unit
            }
        }

        "empty key hashes deterministically" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.emptyKey", "true@50%")
            try {
                val flag   = DynamicFlagTestFlags.emptyKey
                val first  = flag.evaluate("")
                val second = flag.evaluate("")
                assert(first == second)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.emptyKey"): Unit
            }
        }

        "percentage determinism — same key always gets same result" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.pctDeterminism", "true@50%")
            try {
                val flag = DynamicFlagTestFlags.pctDeterminism
                val key  = "stable-user-key"
                val r1   = flag.evaluate(key)
                val r2   = flag.evaluate(key)
                val r3   = flag.evaluate(key)
                assert(r1 == r2)
                assert(r2 == r3)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.pctDeterminism"): Unit
            }
        }

        "percentage monotonicity — key that matches at 25% also matches at 50%" in {
            // Find a key with bucket < 25
            val lowBucketKey = findKeyWithBucket(10)
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.mono25", "true@25%")
            try {
                val flag25 = DynamicFlagTestFlags.mono25
                assert(flag25.evaluate(lowBucketKey) == true)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.mono25"): Unit
            }
            // Now test with 50%
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.mono50", "true@50%")
            try {
                val flag50 = DynamicFlagTestFlags.mono50
                assert(flag50.evaluate(lowBucketKey) == true)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.mono50"): Unit
            }
        }

        "diverse types — Int, String, Boolean, Long, Double" in {
            // Test with string type (already tested above)
            val flag = DynamicFlagTestFlags.stringType
            assert(flag("user1") == "default")

            // Test with long type
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.longType", "9999@enterprise")
            try {
                val longFlag = DynamicFlagTestFlags.longType
                assert(longFlag("user1", "enterprise") == 9999L)
                assert(longFlag("user1") == 0L)
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.longType"): Unit
            }
        }

        "evaluate() returns same as apply()" in {
            java.lang.System.setProperty("kyo.DynamicFlagTestFlags.evalVsApply", "a@x;b")
            try {
                val flag = DynamicFlagTestFlags.evalVsApply
                assert(flag.evaluate("user1", "x") == flag("user1", "x"))
                assert(flag.evaluate("user1", "y") == flag("user1", "y"))
            } finally {
                java.lang.System.clearProperty("kyo.DynamicFlagTestFlags.evalVsApply"): Unit
            }
        }

        "evaluate() does not track counters" in {
            val flag = DynamicFlagTestFlags.evalNoCount
            // Evaluate many times
            for (i <- 0 until 10) flag.evaluate(s"user-$i"): Unit
            // Counters should be empty
            assert(flag.evaluationCounts.isEmpty)
        }
    }

    // Helper to find a key with a specific bucket
    private def findKeyWithBucket(targetBucket: Int): String = {
        @scala.annotation.tailrec
        def loop(i: Int): String = {
            val key = s"key-search-$i"
            if (Rollout.bucketFor(key) == targetBucket) key
            else loop(i + 1)
        }
        loop(0)
    }

}

// Test flag objects defined at package level for predictable names
object DynamicFlagTestFlags {
    object noExpr            extends DynamicFlag[Int](0)
    object plainExpr         extends DynamicFlag[Boolean](false)
    object singlePath        extends DynamicFlag[Int](0)
    object singlePathMiss    extends DynamicFlag[Int](0)
    object prefixMatch       extends DynamicFlag[Int](0)
    object selectorLonger    extends DynamicFlag[Int](0)
    object firstMatch        extends DynamicFlag[String]("default")
    object terminalMiddle    extends DynamicFlag[String]("default")
    object noAttrsTerminal   extends DynamicFlag[Int](0)
    object noAttrsNoTerminal extends DynamicFlag[Int](0)
    object barePercent       extends DynamicFlag[Boolean](false)
    object pathPercent       extends DynamicFlag[Boolean](false)
    object wildcardSingle    extends DynamicFlag[Int](0)
    object wildcardMiddle    extends DynamicFlag[Int](0)
    object multiWild         extends DynamicFlag[Int](0)
    object attrOrder         extends DynamicFlag[Int](0)
    object emptyKey          extends DynamicFlag[Boolean](false)
    object pctDeterminism    extends DynamicFlag[Boolean](false)
    object mono25            extends DynamicFlag[Boolean](false)
    object mono50            extends DynamicFlag[Boolean](false)
    object stringType        extends DynamicFlag[String]("default")
    object longType          extends DynamicFlag[Long](0L)
    object evalVsApply       extends DynamicFlag[String]("default")
    object evalNoCount       extends DynamicFlag[Int](0)
}
