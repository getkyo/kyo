package kyo.test.prop

import kyo.Chunk
import kyo.test.prop.internal.Seed
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-prop has no KyoTestPlugin (would be circular); only ScalaTest is available here.
class GenEdgeBiasTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = ExecutionContext.global

    "int produces the type boundaries across seeds 0..2000" in {
        val roots = (0 until 2001).map(i => Gen.int.sample(Seed(i.toLong), 100).value).toSet
        assert(roots.contains(Int.MinValue), s"Int.MinValue not found in roots")
        assert(roots.contains(Int.MaxValue), s"Int.MaxValue not found in roots")
        assert(roots.exists(v => math.abs(v) <= 100), "No in-band value found (all roots were type boundaries)")
        Future.successful(succeed)
    }

    "every int sample is in the documented producible set" in {
        val size = 100
        for i <- 0 until 2001 do
            val v = Gen.int.sample(Seed(i.toLong), size).value
            assert(
                (v >= -size && v <= size) || v == Int.MinValue || v == Int.MaxValue,
                s"$v is outside the documented producible set ([-$size, $size] UNION {Int.MinValue, Int.MaxValue})"
            )
        end for
        Future.successful(succeed)
    }

    "int distribution stays varied (injection probability < 1)" in {
        val roots = (0 until 501).map(i => Gen.int.sample(Seed(i.toLong), 100).value)
        assert(
            roots.distinct.size > 1,
            s"Only ${roots.distinct.size} distinct values in 501 samples; injection probability appears to be 1.0"
        )
        Future.successful(succeed)
    }

    "long produces Long.MinValue/MaxValue and stays in the producible set" in {
        val size  = 100
        val roots = (0 until 2001).map(i => Gen.long.sample(Seed(i.toLong), size).value)
        assert(roots.exists(_ == Long.MinValue), "Long.MinValue not found in roots")
        assert(roots.exists(_ == Long.MaxValue), "Long.MaxValue not found in roots")
        for v <- roots do
            assert(
                (v >= -size.toLong && v <= size.toLong) || v == Long.MinValue || v == Long.MaxValue,
                s"$v is outside the documented producible set ([-$size, $size] UNION {Long.MinValue, Long.MaxValue})"
            )
        end for
        Future.successful(succeed)
    }

    "string edge bias hits empty and max-length, stays in-band" in {
        val size  = 8
        val roots = (0 until 2001).map(i => Gen.string.sample(Seed(i.toLong), size).value)
        assert(roots.exists(_.isEmpty), "Empty string not found in roots")
        assert(roots.exists(_.length == size), s"No length-$size string found in roots")
        for s <- roots do
            assert(s.length >= 0 && s.length <= size, s"String '$s' has length ${s.length} outside [0, $size]")
        end for
        Future.successful(succeed)
    }

    "list edge bias hits empty and singleton, stays in-band" in {
        val size  = 10
        val roots = (0 until 2001).map(i => Gen.list(Gen.int).sample(Seed(i.toLong), size).value)
        assert(roots.exists(_.isEmpty), "Empty chunk not found in list roots")
        assert(roots.exists(_.size == 1), "Singleton chunk not found in list roots")
        for c <- roots do
            assert(c.size >= 0 && c.size <= size, s"Chunk of size ${c.size} is outside [0, $size]")
        end for
        Future.successful(succeed)
    }

    "map edge bias hits empty and singleton, stays in-band" in {
        val size  = 10
        val roots = (0 until 2001).map(i => Gen.map(Gen.int, Gen.int).sample(Seed(i.toLong), size).value)
        assert(roots.exists(_.isEmpty), "Empty map not found in roots")
        assert(roots.exists(_.size == 1), "Singleton map not found in roots")
        for m <- roots do
            assert(m.size >= 0 && m.size <= size, s"Map of size ${m.size} is outside [0, $size]")
        end for
        Future.successful(succeed)
    }

    "an injected non-trivial edge still shrinks toward the trivial value" in {
        // Search seeds for one whose Gen.int root is Int.MaxValue (a known injected edge).
        val maxValueTree = (0 until 1000).iterator
            .map(i => Gen.int.sample(Seed(i.toLong), 100))
            .find(_.value == Int.MaxValue)
        assert(maxValueTree.isDefined, "No seed in 0..999 produced Int.MaxValue at size 100 (try increasing range)")
        val tree     = maxValueTree.get
        val children = tree.shrinks().map(_.value).toList
        assert(children.nonEmpty, "Int.MaxValue tree should have shrink children")
        // Follow the halving path to the last reachable candidate; it should be 0.
        def lastCandidate(t: kyo.test.prop.internal.Tree[Int]): Int =
            val ch = t.shrinks().toList
            if ch.isEmpty then t.value
            else lastCandidate(ch.last)
        end lastCandidate
        assert(lastCandidate(tree) == 0, s"Last shrink candidate of Int.MaxValue should be 0 (same shrinkInt tree)")
        Future.successful(succeed)
    }

    "size-0 int is degenerate (no out-of-band boundary)" in {
        for i <- 0 until 501 do
            val v0 = Gen.int.sample(Seed(i.toLong), 0).value
            val vn = Gen.int.sample(Seed(i.toLong), -1).value
            assert(v0 == 0, s"At size 0, seed $i: expected 0 but got $v0")
            assert(vn == 0, s"At size -1 (clamped to 0), seed $i: expected 0 but got $vn")
        end for
        Future.successful(succeed)
    }

    "size-0 list/listOfN degenerate behavior preserved" in {
        for i <- 0 until 50 do
            val listRoot = Gen.list(Gen.int).sample(Seed(i.toLong), -5).value
            assert(listRoot == Chunk.empty[Int], s"At size -5, Gen.list should be empty but got $listRoot")
            val listOfNRoot = Gen.listOfN(4, Gen.string).sample(Seed(i.toLong), 0).value
            assert(listOfNRoot.size == 4, s"Gen.listOfN(4) at size 0, seed $i: expected 4 elements, got ${listOfNRoot.size}")
        end for
        Future.successful(succeed)
    }

    "edge injection is deterministic from the seed" in {
        val tree1 = Gen.int.sample(Seed(7L), 50)
        val tree2 = Gen.int.sample(Seed(7L), 50)
        assert(tree1.value == tree2.value, s"Root values differ: ${tree1.value} vs ${tree2.value}")
        val children1 = tree1.shrinks().map(_.value).take(5).toList
        val children2 = tree2.shrinks().map(_.value).take(5).toList
        assert(children1 == children2, s"First 5 shrink children differ: $children1 vs $children2")
        Future.successful(succeed)
    }

    // Gen.double edge-case behavioral checks

    // Leaf 10: Gen.double size-0 no longer forces 0.0 for all seeds (INV-009)
    "Gen.double size-0 no longer forces 0.0 for all seeds" in {
        val roots = (0 until 501).map(i => Gen.double.sample(Seed(i.toLong), 0).value)
        assert(
            roots.exists(_ != 0.0),
            "Expected at least one non-zero size-0 root (size-0 collapse fix); all were 0.0"
        )
        assert(
            roots.forall(java.lang.Double.isFinite),
            s"Every size-0 root must be finite (in-band edge {0.0} only at size 0); found non-finite"
        )
        Future.successful(succeed)
    }

    // Leaf 11: Gen.double produces special-value edges and finite non-edge values (INV-009)
    "Gen.double produces special-value edges and finite non-edge values" in {
        val roots = (0 until 3001).map(i => Gen.double.sample(Seed(i.toLong), 50).value)
        assert(roots.exists(_.isNaN), "Expected at least one NaN root in Gen.double samples (size 50, seeds 0..3000)")
        assert(roots.exists(_.isInfinite), "Expected at least one infinite root in Gen.double samples")
        assert(roots.exists(_ == Double.MaxValue), "Expected at least one Double.MaxValue root in Gen.double samples")
        assert(
            roots.exists(d => java.lang.Double.isFinite(d) && d != 0.0 && math.abs(d) <= 50),
            "Expected at least one finite non-edge root with abs <= 50 in Gen.double samples"
        )
        Future.successful(succeed)
    }

end GenEdgeBiasTest
