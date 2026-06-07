package kyo.test.prop

import kyo.Chunk
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-prop has no KyoTestPlugin (would be circular); only ScalaTest is available here.
class GenIntrospectionTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = ExecutionContext.global

    "samples returns exactly count values" in {
        val result = Gen.int.samples(42L, 50, 10)
        assert(result.size == 10, s"Expected 10 samples, got ${result.size}")
        Future.successful(succeed)
    }

    "samples is deterministic from (seed, size, count)" in {
        val first  = Gen.int.samples(42L, 50, 10)
        val second = Gen.int.samples(42L, 50, 10)
        assert(first == second, s"Two identical samples(42L, 50, 10) calls returned different results")
        Future.successful(succeed)
    }

    "samples is total at count <= 0" in {
        val zero     = Gen.int.samples(42L, 50, 0)
        val negative = Gen.int.samples(42L, 50, -3)
        assert(zero == Chunk.empty[Int], s"Expected Chunk.empty for count=0, got $zero")
        assert(negative == Chunk.empty[Int], s"Expected Chunk.empty for count=-3, got $negative")
        Future.successful(succeed)
    }

    "samples does not throw at negative size (clamps)" in {
        // Gen.int internally clamps size to max(0, size), so all values must be 0.
        val result = Gen.int.samples(42L, -5, 5)
        assert(result.size == 5, s"Expected 5 values, got ${result.size}")
        result.foreach { v =>
            assert(v == 0, s"Expected 0 (size clamped to 0), got $v")
        }
        Future.successful(succeed)
    }

    "classify tallies into labelled buckets summing to count" in {
        val counts = Gen.oneOf("a", "b").classify(42L, 10, 100)(identity)
        val total  = counts.values.sum
        assert(total == 100, s"Expected counts to sum to 100, got $total")
        counts.foreach { case (k, v) =>
            assert(k == "a" || k == "b", s"Unexpected key: $k")
            assert(v > 0, s"Unexpected zero count for key: $k")
        }
        Future.successful(succeed)
    }

end GenIntrospectionTest
