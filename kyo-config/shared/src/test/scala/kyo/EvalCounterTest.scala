package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

class EvalCounterTest extends AnyFreeSpec {

    "DynamicFlag evaluation counters" - {

        "counter increments on apply()" in {
            val flag = EvalCounterTestFlags.counterIncr
            for (_ <- 0 until 5) flag("user1"): Unit
            val counts = flag.evaluationCounts
            assert(counts.values.sum == 5)
        }

        "separate counters per value" in {
            val flag = EvalCounterTestFlags.perValue
            flag.update("a@premium;b")
            for (_ <- 0 until 3) flag("user1", "premium"): Unit
            for (_ <- 0 until 7) flag("user1", "basic"): Unit
            val counts = flag.evaluationCounts
            assert(counts("a") == 3)
            assert(counts("b") == 7)
        }

        "counters survive update()" in {
            val flag = EvalCounterTestFlags.surviveUpdate
            flag.update("a@x")
            flag("user1", "x"): Unit
            flag("user1", "x"): Unit
            val countsBefore = flag.evaluationCounts("a")
            assert(countsBefore == 2)
            flag.update("b@x")
            flag("user1", "x"): Unit
            val countsAfter = flag.evaluationCounts
            assert(countsAfter("a") == 2) // old counter preserved
            assert(countsAfter("b") == 1) // new counter added
        }

        "counter includes default value" in {
            val flag = EvalCounterTestFlags.includesDefault
            // No matching choices — returns default (42)
            flag.update("100@enterprise")
            flag("user1", "basic"): Unit
            flag("user2", "free"): Unit
            val counts = flag.evaluationCounts
            assert(counts("42") == 2)
        }

        "evaluationCounts() returns all values" in {
            val flag = EvalCounterTestFlags.allValues
            flag.update("a@x;b@y;c")
            flag("user1", "x"): Unit
            flag("user1", "y"): Unit
            flag("user1", "z"): Unit
            val counts = flag.evaluationCounts
            assert(counts.contains("a"))
            assert(counts.contains("b"))
            assert(counts.contains("c"))
            assert(counts.size == 3)
        }

        "counter bounded at maxCounterKeys (100)" in {
            val flag = EvalCounterTestFlags.bounded
            // Generate 101+ distinct values using String flag
            for (i <- 0 until 110) {
                flag.update(s"value$i")
                flag("user1"): Unit
            }
            val counts = flag.evaluationCounts
            // Should have at most 101 keys (100 individual + "other")
            assert(counts.size <= 101)
        }

        "overflow counter accumulates under 'other'" in {
            val flag = EvalCounterTestFlags.overflow
            // First fill up 100 unique keys using String flag
            for (i <- 0 until 100) {
                flag.update(s"value$i")
                flag("user1"): Unit
            }
            assert(flag.evaluationCounts.size == 100)
            // Now add more — they should go to "other"
            for (i <- 100 until 110) {
                flag.update(s"value$i")
                flag("user1"): Unit
            }
            val counts = flag.evaluationCounts
            assert(counts.contains("other"))
            assert(counts("other") == 10)
        }

        "counter is approximate under many calls" in {
            val flag = EvalCounterTestFlags.concurrent
            flag.update("a@x;b")
            for (t <- 0 until 10) {
                for (_ <- 0 until 100) {
                    flag(s"user-$t", "x"): Unit
                }
            }
            val counts = flag.evaluationCounts
            val total  = counts.values.sum
            // Should have exactly 1000 counts in sequential execution
            assert(total == 1000)
        }

        "evaluate() does NOT increment counter" in {
            val flag = EvalCounterTestFlags.noEvalCount
            for (_ <- 0 until 10) flag.evaluate("user1"): Unit
            assert(flag.evaluationCounts.isEmpty)
            // But apply() does
            flag("user1"): Unit
            assert(flag.evaluationCounts.nonEmpty)
        }

        "counter is cross-platform (Map + Long)" in {
            // This test verifies the types used are stdlib types
            val flag = EvalCounterTestFlags.crossPlatform
            flag("user1"): Unit
            val counts: Map[String, Long] = flag.evaluationCounts
            assert(counts.isInstanceOf[Map[?, ?]])
        }
    }

}

object EvalCounterTestFlags {
    object counterIncr     extends DynamicFlag[Int](0)
    object perValue        extends DynamicFlag[String]("default")
    object surviveUpdate   extends DynamicFlag[String]("default")
    object includesDefault extends DynamicFlag[Int](42)
    object allValues       extends DynamicFlag[String]("default")
    object bounded         extends DynamicFlag[String]("default")
    object overflow        extends DynamicFlag[String]("default")
    object concurrent      extends DynamicFlag[String]("default")
    object noEvalCount     extends DynamicFlag[Int](0)
    object crossPlatform   extends DynamicFlag[Int](0)
}
