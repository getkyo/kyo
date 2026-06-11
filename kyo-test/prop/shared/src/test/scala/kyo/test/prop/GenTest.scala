package kyo.test.prop

import kyo.Chunk
import kyo.Frame
import kyo.test.prop.internal.Seed
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-prop has no KyoTestPlugin (would be circular); only ScalaTest is available here.
class GenTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = ExecutionContext.global

    private val size = 100

    private def seed(i: Int): Seed = Seed(i.toLong * 0x9e3779b97f4a7c15L + 1L)

    "const samples constant value" in {
        val g = Gen.const(42)
        for i <- 0 until 50 do
            assert(g.sample(seed(i), size).value == 42)
        Future.successful(succeed)
    }

    "int samples integer in expected range" in {
        val g = Gen.int
        for i <- 0 until 1000 do
            val v = g.sample(seed(i), size).value
            // Edge bias makes the producible set [-size, size] UNION {Int.MinValue, Int.MaxValue}.
            // The type boundaries are injected with a small decaying probability and fall outside [-size, size],
            // so this checks the extended membership rather than a strict range.
            assert(
                (v >= -size && v <= size) || v == Int.MinValue || v == Int.MaxValue,
                s"$v not in [${-size}, $size] and not an injected type boundary"
            )
        end for
        Future.successful(succeed)
    }

    "list samples lists of varying sizes" in {
        val g             = Gen.list(Gen.int)
        val samples       = (0 until 100).map(i => g.sample(seed(i), 10).value)
        val allWithinSize = samples.forall(_.size <= 10)
        val someNonEmpty  = samples.exists(_.nonEmpty)
        assert(allWithinSize, "Some lists exceed size 10")
        assert(someNonEmpty, "All lists were empty; expected some non-empty")
        Future.successful(succeed)
    }

    "listOfN samples exactly N elements" in {
        val g = Gen.listOfN(5, Gen.int)
        for i <- 0 until 50 do
            val lst = g.sample(seed(i), size).value
            assert(lst.size == 5, s"Expected 5 elements, got ${lst.size}")
        end for
        Future.successful(succeed)
    }

    "oneOf samples one of given values" in {
        val choices = Set(1, 2, 3)
        val g       = Gen.oneOf(1, 2, 3)
        for i <- 0 until 1000 do
            val v = g.sample(seed(i), size).value
            assert(choices.contains(v), s"$v not in $choices")
        end for
        Future.successful(succeed)
    }

    "frequency respects weights" in {
        val g       = Gen.frequency((1, Gen.const("rare")), (9, Gen.const("common")))
        val samples = (0 until 10000).map(i => g.sample(seed(i), size).value)
        val common  = samples.count(_ == "common")
        val inRange = common >= 8000 && common <= 9999
        assert(inRange, s"'common' appeared $common/10000 times, expected [8000,9999]")
        Future.successful(succeed)
    }

    "map transforms values" in {
        val g = Gen.int.map(_ * 2)
        for i <- 0 until 100 do
            val v = g.sample(seed(i), size).value
            assert(v % 2 == 0, s"Expected even number, got $v")
        end for
        Future.successful(succeed)
    }

    "flatMap chains generators" in {
        val g = Gen.int.flatMap(n => Gen.listOfN(math.abs(n) % 5 + 1, Gen.int))
        for i <- 0 until 100 do
            val lst = g.sample(seed(i), size).value
            assert(lst.nonEmpty, "Expected non-empty list from flatMap")
        end for
        Future.successful(succeed)
    }

    "filter discards rejected values" in {
        val g = Gen.int.filter(_ > 0)
        for i <- 0 until 100 do
            val v = g.sample(seed(i), size).value
            assert(v > 0, s"Expected positive value, got $v")
        end for
        Future.successful(succeed)
    }

    "derive for case class produces instances" in {
        case class Point(x: Int, y: Int) derives CanEqual
        given Gen[Int] = Gen.int
        val g          = Gen.derive[Point]
        for i <- 0 until 100 do
            val p = g.sample(seed(i), size).value
            val _ = p.x + p.y
        end for
        Future.successful(succeed)
    }

    "derive resolves the built-in given primitive Gens with no user-provided givens" in {
        // Regression guard for the framework-level given Gen[Int]/Long/Double/String/Boolean: an ordinary
        // case class with primitive fields must derive a generator with NO local `given Gen[...]` in scope.
        case class AllPrims(i: Int, l: Long, d: Double, s: String, b: Boolean) derives CanEqual
        val g       = Gen.derive[AllPrims]
        val samples = (0 until 100).map(i => g.sample(seed(i), size).value)
        assert(samples.size == 100)
        assert(samples.forall(_.s != null))
        assert(samples.map(_.i).distinct.size > 1)
        Future.successful(succeed)
    }

    "given primitive Gens resolve via summon and sample real values" in {
        assert((0 until 100).map(i => summon[Gen[Int]].sample(seed(i), size).value).distinct.size > 1)
        assert(summon[Gen[String]].sample(seed(0), size).value != null)
        val _ = summon[Gen[Long]].sample(seed(0), size).value
        val _ = summon[Gen[Double]].sample(seed(0), size).value
        val _ = summon[Gen[Boolean]].sample(seed(0), size).value
        Future.successful(succeed)
    }

    "phase3-test-1: Gen.list(Gen.int).sample returns Chunk[Int] (static type check)" in {
        val result: Chunk[Int] = Gen.list(Gen.int).sample(Seed(42L), 5).value
        assert(result.size <= 5, s"Expected at most 5 elements, got ${result.size}")
        Future.successful(succeed)
    }

    "phase3-test-2: Shrink.list includes Chunk(1,2)" in {
        val result: Chunk[Chunk[Int]] = Shrink.list(Chunk(1, 2, 3), Shrink.int)
        assert(result.nonEmpty, "shrink(Chunk(1,2,3)) should be non-empty")
        assert(result.exists(_ == Chunk(1, 2)), s"Expected Chunk(1,2) in shrink candidates, got: $result")
        Future.successful(succeed)
    }

    "phase3-test-3: Gen.listOfN(4, Gen.string).sample(seed, 0).value.size == 4" in {
        val result = Gen.listOfN(4, Gen.string).sample(Seed(42L), 0).value
        assert(result.size == 4, s"Expected exactly 4 elements, got ${result.size}")
        Future.successful(succeed)
    }

    "phase6-leaf-6: GenFilterExhaustedException carries typed budget and attempts fields" in {
        // The constructor changed from a free-form string to typed (budget, attempts) fields
        // so the diagnostic is programmatically inspectable. The message still contains the budget number.
        val ex = new GenFilterExhaustedException(budget = 1000, attempts = 1000)
        assert(ex.getMessage.contains("1000"), s"Expected getMessage to contain '1000', got: ${ex.getMessage}")
        assert(ex.budget == 1000, s"Expected .budget == 1000, got: ${ex.budget}")
        assert(ex.attempts == 1000, s"Expected .attempts == 1000, got: ${ex.attempts}")
        Future.successful(succeed)
    }

    "phase6-leaf-7: new PropertyFailedException cause propagation" in {
        val root = new RuntimeException("root")
        val ex   = new PropertyFailedException(1, 1, root, 42L)
        assert(
            ex.getCause.getMessage == "root",
            s"Expected getCause.getMessage == 'root', got: ${ex.getCause.getMessage}"
        )
        Future.successful(succeed)
    }

    // ── Negative and clamped size sampling ──────────────────────────────────────

    "phase7-leaf-1: Gen.int.sample(seed, -1) returns a value without throwing" in {
        val g = Gen.int
        val v = g.sample(Seed(42L), -1).value
        // clampedSize = 0, so sample must return 0 (only value in range [0,0])
        assert(v == 0, s"Expected 0 when size is clamped to 0, got $v")
        Future.successful(succeed)
    }

    "phase7-leaf-2: Gen.list(Gen.int).sample(seed, -5) returns an empty Chunk" in {
        val g      = Gen.list(Gen.int)
        val result = g.sample(Seed(42L), -5).value
        assert(result == Chunk.empty[Int], s"Expected empty Chunk, got $result")
        Future.successful(succeed)
    }

    "phase7-leaf-3: Gen.oneOf() (empty) throws a require-violation" in {
        var threw = false
        try Gen.oneOf[Int]()
        catch
            case e: IllegalArgumentException =>
                threw = true
                assert(
                    e.getMessage.contains("nonEmpty") || e.getMessage.contains("least one"),
                    s"Expected require message about nonEmpty, got: ${e.getMessage}"
                )
        end try
        assert(threw, "Expected IllegalArgumentException from Gen.oneOf() with empty choices")
        Future.successful(succeed)
    }

    "phase7-leaf-4: Gen.frequency((0, Gen.int)) throws a require-violation about zero weight" in {
        var threw = false
        try Gen.frequency((0, Gen.int))
        catch
            case e: IllegalArgumentException =>
                threw = true
                assert(
                    e.getMessage.contains("positive") || e.getMessage.contains("weight") || e.getMessage.contains("0"),
                    s"Expected require message about zero weight, got: ${e.getMessage}"
                )
        end try
        assert(threw, "Expected IllegalArgumentException from Gen.frequency with zero weight")
        Future.successful(succeed)
    }

    // ── Filter budget exhaustion ─────────────────────────────────────────────────

    "phase8-test-7: Gen.filter(_ => false) throws GenFilterExhaustedException after exhausting budget" in {
        // Using Gen.filter(_ => false) ensures every sample is rejected, so the 1000-retry
        // budget is always exhausted. Pins that the budget is enforced and the correct exception type is thrown.
        val g     = Gen.int.filter(_ => false)
        var threw = false
        try g.sample(Seed(42L), 10)
        catch
            case _: GenFilterExhaustedException => threw = true
        end try
        assert(threw, "Expected GenFilterExhaustedException when filter always rejects")
        Future.successful(succeed)
    }

end GenTest
