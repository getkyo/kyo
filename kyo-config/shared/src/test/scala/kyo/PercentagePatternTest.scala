package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

class PercentagePatternTest extends AnyFreeSpec {

    // Helper to find a key with a specific bucket
    private def findKeyWithBucket(targetBucket: Int): String = {
        @scala.annotation.tailrec
        def loop(i: Int): String = {
            val key = s"pct-key-$i"
            if (Rollout.bucketFor(key) == targetBucket) key
            else loop(i + 1)
        }
        loop(0)
    }

    "Percentage patterns and mutual exclusion" - {

        "three-arm split — 33/33/34" in {
            val flag = PctTestFlags.threeArm
            flag.update("a@33%;b@33%;c")
            // bucket 0-32 → a
            val key0  = findKeyWithBucket(0)
            val key32 = findKeyWithBucket(32)
            assert(flag.evaluate(key0) == "a")
            assert(flag.evaluate(key32) == "a")
            // bucket 33-65 → b
            val key33 = findKeyWithBucket(33)
            val key65 = findKeyWithBucket(65)
            assert(flag.evaluate(key33) == "b")
            assert(flag.evaluate(key65) == "b")
            // bucket 66-99 → c (terminal)
            val key66 = findKeyWithBucket(66)
            val key99 = findKeyWithBucket(99)
            assert(flag.evaluate(key66) == "c")
            assert(flag.evaluate(key99) == "c")
        }

        "two-arm split — 50/50" in {
            val flag = PctTestFlags.twoArm
            flag.update("a@50%;b")
            val key25 = findKeyWithBucket(25)
            val key75 = findKeyWithBucket(75)
            assert(flag.evaluate(key25) == "a")
            assert(flag.evaluate(key75) == "b")
        }

        "90/10 holdout" in {
            val flag = PctTestFlags.holdout
            flag.update("treatment@90%;holdout")
            val key10 = findKeyWithBucket(10)
            val key95 = findKeyWithBucket(95)
            assert(flag.evaluate(key10) == "treatment")
            assert(flag.evaluate(key95) == "holdout")
        }

        "uneven three-way — 10/20/70" in {
            val flag = PctTestFlags.unevenThree
            flag.update("a@10%;b@20%;c")
            // a: buckets 0-9
            val key5 = findKeyWithBucket(5)
            assert(flag.evaluate(key5) == "a")
            // b: buckets 10-29
            val key15 = findKeyWithBucket(15)
            assert(flag.evaluate(key15) == "b")
            // c: buckets 30-99 (terminal)
            val key50 = findKeyWithBucket(50)
            assert(flag.evaluate(key50) == "c")
        }

        "percentage stacking — increasing weight never disables" in {
            val flag = PctTestFlags.stacking
            flag.update("a@33%;b")
            val key10 = findKeyWithBucket(10)
            assert(flag.evaluate(key10) == "a")
            // Increase weight to 50%
            flag.update("a@50%;b")
            // Same key should still get "a" (bucket 10 < 50)
            assert(flag.evaluate(key10) == "a")
        }

        "single 100% — all get value" in {
            val flag = PctTestFlags.hundred
            flag.update("a@100%")
            for (i <- 0 until 100) {
                val key = findKeyWithBucket(i)
                assert(flag.evaluate(key) == "a", s"bucket=$i")
            }
        }

        "single 0% — all get default or terminal" in {
            val flag = PctTestFlags.zero
            flag.update("a@0%;b")
            for (i <- 0 until 100) {
                val key = findKeyWithBucket(i)
                assert(flag.evaluate(key) == "b", s"bucket=$i")
            }
        }

        "path + percentage weights" in {
            val flag = PctTestFlags.pathPct
            flag.update("a@prod/33%;b@prod/33%;c@prod;d")
            val key10 = findKeyWithBucket(10) // in a range
            val key40 = findKeyWithBucket(40) // in b range
            val key70 = findKeyWithBucket(70) // in c range (path-only match, catches rest of prod)
            assert(flag.evaluate(key10, "prod") == "a")
            assert(flag.evaluate(key40, "prod") == "b")
            assert(flag.evaluate(key70, "prod") == "c")
            // Non-prod → terminal d
            assert(flag.evaluate(key10, "staging") == "d")
        }

        "weights > 100% normalized at update (not init)" in {
            val flag = PctTestFlags.normalized
            // At update time, 60% + 60% = 120%, should be normalized to 50% + 50%
            flag.update("a@60%;b@60%")
            // After normalization, all 100 buckets should be covered by a or b
            var aCount = 0
            var bCount = 0
            var other  = 0
            for (i <- 0 until 100) {
                val key = findKeyWithBucket(i)
                flag.evaluate(key) match {
                    case "a" => aCount += 1
                    case "b" => bCount += 1
                    case _   => other += 1
                }
            }
            // Both a and b should be reachable
            assert(aCount > 0, s"aCount=$aCount")
            assert(bCount > 0, s"bCount=$bCount")
        }

        "weights > 100% preserves proportions" in {
            val flag = PctTestFlags.proportions
            // 90% + 30% = 120%, normalized to 75% + 25%
            flag.update("a@90%;b@30%;c")
            var aCount = 0
            var bCount = 0
            var cCount = 0
            for (i <- 0 until 100) {
                val key = findKeyWithBucket(i)
                flag.evaluate(key) match {
                    case "a" => aCount += 1
                    case "b" => bCount += 1
                    case "c" => cCount += 1
                }
            }
            // a should get ~75% and b should get ~25%
            assert(aCount > bCount, s"a=$aCount, b=$bCount — a should get more than b")
        }

        "single weight > 100% clamped at update" in {
            val flag = PctTestFlags.clamped
            // At update, 150% should be clamped
            flag.update("a@150%")
            // Should match all buckets
            for (i <- 0 until 100) {
                val key = findKeyWithBucket(i)
                assert(flag.evaluate(key) == "a", s"bucket=$i")
            }
        }

        "weights sum exactly 100% — all buckets covered" in {
            val flag = PctTestFlags.exact100
            flag.update("a@50%;b@50%")
            var aCount = 0
            var bCount = 0
            for (i <- 0 until 100) {
                val key = findKeyWithBucket(i)
                flag.evaluate(key) match {
                    case "a" => aCount += 1
                    case "b" => bCount += 1
                    case _   => ()
                }
            }
            assert(aCount + bCount == 100, s"a=$aCount, b=$bCount, sum=${aCount + bCount}")
        }

        "weights sum < 100% with no terminal — falls to default" in {
            val flag = PctTestFlags.under100
            flag.update("a@30%;b@30%")
            val key80 = findKeyWithBucket(80)
            // bucket 80 is beyond 60% cumulative → falls to constructor default
            assert(flag.evaluate(key80) == "default")
        }

        "numeric path segment warning" in {
            // This test just verifies that the expression parses correctly
            // The warning goes to stderr — we can't easily capture it in a test
            val flag = PctTestFlags.numericWarn
            flag.update("a@50")
            // "50" is treated as a path segment, not a percentage
            // So it only matches attribute "50"
            assert(flag.evaluate("user1", "50") == "a")
            assert(flag.evaluate("user1", "other") == "default")
        }

        "empty value before @ is a parse error" in {
            val flag = PctTestFlags.emptyValue
            val ex = intercept[FlagException] {
                flag.update("@prod/50%")
            }
            assert(ex.getMessage.contains("empty value"))
        }
    }

}

object PctTestFlags {
    object threeArm    extends DynamicFlag[String]("default")
    object twoArm      extends DynamicFlag[String]("default")
    object holdout     extends DynamicFlag[String]("default")
    object unevenThree extends DynamicFlag[String]("default")
    object stacking    extends DynamicFlag[String]("default")
    object hundred     extends DynamicFlag[String]("default")
    object zero        extends DynamicFlag[String]("default")
    object pathPct     extends DynamicFlag[String]("default")
    object normalized  extends DynamicFlag[String]("default")
    object proportions extends DynamicFlag[String]("default")
    object clamped     extends DynamicFlag[String]("default")
    object exact100    extends DynamicFlag[String]("default")
    object under100    extends DynamicFlag[String]("default")
    object numericWarn extends DynamicFlag[String]("default")
    object emptyValue  extends DynamicFlag[String]("default")
}
