package kyo.test.prop

import kyo.Chunk
import kyo.Maybe
import kyo.Maybe.Present
import kyo.test.TestResult
import kyo.test.prop.PropertyTestBase
import kyo.test.prop.internal.Seed
import kyo.test.prop.internal.Tree
import kyo.test.runner.TestRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Fixture suite: forAll(Gen.int) with a trivially passing body (always succeeds).
  *
  * Used to verify that a passing forAll registers a Passed leaf (Test 1 in GenShrinkChunkTest).
  */
class GSCPassSuite extends PropertyTestBase[Any]:
    forAll(Gen.int) { _ => succeed }
end GSCPassSuite

/** Fixture suite: forAll(Gen.int) with a property that fails for n <= -51.
  *
  * Used to verify shrinking behavior: n >= -50 fails for samples in [-100, -51] from Gen.int. After shrinking (halve toward zero), the
  * minimal counterexample must be <= -51 (Test 2 in GenShrinkChunkTest).
  */
class GSCShrinkSuite extends PropertyTestBase[Any]:
    forAll(Gen.int) { n => assert(n >= -50) }
end GSCShrinkSuite

/** Fixture suite: forAll(customGen) where customGen always samples 10, property n < 5 fails.
  *
  * The custom Gen builds a rose tree whose children halve toward zero by one (10 -> 9 -> 8 ...). Walking the tree depth-first taking the
  * first still-failing child yields minimal counterexample 5 (5 fails n<5, its child 4 passes). (Test 5 in GenShrinkChunkTest).
  */
class GSCCustomGenSuite extends PropertyTestBase[Any]:
    private val customGen: Gen[Int] = new Gen[Int]:
        def sample(seed: Seed, size: Int): Tree[Int] =
            Tree.unfold(10)(v => if v > 0 then Chunk(v - 1) else Chunk.empty)
    forAll(customGen) { n => assert(n < 5) }
end GSCCustomGenSuite

/** Tests confirming that the shrink algorithms produce the expected candidate sequences, both through the public Shrink API and through the
  * rose-tree children of built-in generators.
  *
  * Also validates the shrink sequences end-to-end via integration with forAll via TestRunner.runToFuture (kyo-test-runner is a test
  * dependency of kyo-test-prop).
  */
class GenShrinkChunkTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // ── Shrink algorithm sequences (public Shrink API) ───────────────────────

    "Shrink.int returns Chunk[Int]" in {
        val result: Chunk[Int] = Shrink.int(100)
        assert(result.nonEmpty, "Shrink.int(100) should be non-empty")
        Future.successful(succeed)
    }

    "Shrink.long returns Chunk[Long]" in {
        val result: Chunk[Long] = Shrink.long(100L)
        assert(result.nonEmpty, "Shrink.long(100L) should be non-empty")
        Future.successful(succeed)
    }

    "Shrink.string returns Chunk[String]" in {
        val result: Chunk[String] = Shrink.string("hello")
        assert(result.nonEmpty, "Shrink.string(\"hello\") should be non-empty")
        Future.successful(succeed)
    }

    "Shrink.list returns Chunk[Chunk[Int]]" in {
        val result: Chunk[Chunk[Int]] = Shrink.list(Chunk(1, 2, 3), Shrink.int)
        assert(result.nonEmpty, "Shrink.list(Chunk(1,2,3)) should be non-empty")
        Future.successful(succeed)
    }

    // ── Test 3: exact shrink sequence for Shrink.int(100) ────────────────────

    "Shrink.int(100) returns Chunk(50, 25, 12, 6, 3, 1, 0)" in {
        val result = Shrink.int(100)
        assert(result == Chunk(50, 25, 12, 6, 3, 1, 0), s"Unexpected shrink sequence: $result")
        Future.successful(succeed)
    }

    // ── Test 4: Shrink.list includes drop-phase result Chunk(1, 2) ───────────

    "Shrink.list(Chunk(1, 2, 3)) is non-empty and includes Chunk(1, 2)" in {
        val result = Shrink.list(Chunk(1, 2, 3), Shrink.int)
        assert(result.nonEmpty, s"Expected non-empty shrink candidates, got empty Chunk")
        assert(
            result.exists(_ == Chunk(1, 2)),
            s"Expected shrink candidates to include Chunk(1, 2) (drop last), got: $result"
        )
        Future.successful(succeed)
    }

    // ── Long and tuple sequences ──────────────────────────────────────────────

    "Shrink.long(100L) returns expected sequence" in {
        val result = Shrink.long(100L)
        assert(result.nonEmpty, "Shrink.long(100L) should be non-empty")
        assert(result.last == 0L, s"Last shrunk value should be 0L, got ${result.last}")
        assert(result.head == 50L, s"First shrunk value should be 50L, got ${result.head}")
        Future.successful(succeed)
    }

    "Shrink.string produces non-empty Chunk ending with empty string" in {
        val result = Shrink.string("hello")
        assert(result.nonEmpty, "Shrink.string(\"hello\") should be non-empty")
        assert(result.last == "", s"Last shrunk value should be \"\", got '${result.last}'")
        Future.successful(succeed)
    }

    // ── Built-in generator trees expose shrink children ──────────────────────

    "Gen.int sample tree's children shrink toward zero" in {
        val tree     = Gen.int.sample(Seed(42L), 100)
        val children = tree.shrinks().map(_.value).toList
        if tree.value == 0 then assert(children.isEmpty, s"value 0 should have no shrinks, got $children"): Unit
        else assert(children.nonEmpty, s"non-zero value ${tree.value} should have shrink children"): Unit
        Future.successful(succeed)
    }

    "zip sample tree shrinks both components" in {
        val zipped                 = Gen.zip(Gen.int, Gen.int)
        val tree: Tree[(Int, Int)] = zipped.sample(Seed(7L), 50)
        // A non-zero tuple has at least one shrink child; assert tree is well-formed.
        val children = tree.shrinks().toList
        val (a, b)   = tree.value
        if a != 0 || b != 0 then assert(children.nonEmpty, s"tuple ${tree.value} should shrink"): Unit
        Future.successful(succeed)
    }

    // ── Test 1: forAll passes when property always holds ────────────────────

    "forAll(Gen.int)(_ => succeed) passes" in {
        TestRunner.runToFuture(classOf[GSCPassSuite]).map { report =>
            assert(report.suiteReports.nonEmpty, "Expected at least one suite report")
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result")
            val (_, result) = allResults.head
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    // ── Test 2: forAll shrinks to a counterexample ────────────────────────────

    "forAll(Gen.int)(n => assert(n >= -50)) shrinks to a counterexample" in {
        // Gen.int samples from [-size, size], size grows 1..100.
        // The property n >= -50 fails for values <= -51 in the [-100, -51] range.
        TestRunner.runToFuture(classOf[GSCShrinkSuite]).map { report =>
            assert(report.suiteReports.nonEmpty, "Expected at least one suite report")
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result")
            val (_, result) = allResults.head
            result match
                case f: TestResult.Failed =>
                    f.cause match
                        case Present(e: PropertyFailedException) =>
                            val shrunk = e.shrunkValue match
                                case n: Int => n
                                case other  => fail(s"Expected Int shrunkValue, got: $other")
                            assert(shrunk <= -51, s"Shrunk counterexample $shrunk should be <= -51")
                        case other => fail(s"Expected Present(PropertyFailedException) but got: $other")
                case _: TestResult.Passed =>
                    fail("Expected forAll to fail (n >= -50 violated by seed 42 samples)")
                case other => fail(s"Expected Failed, got $other")
            end match
        }
    }

    // ── Test 5: custom Gen with a rose-tree shrink is used by forAll ────────

    "custom Gen[Int] with a halving rose tree is used during shrinking" in {
        // customGen always samples 10; property n < 5 fails.
        // Tree children: 10 -> 9 -> ... -> 5 (fails) -> 4 (passes). Minimal counterexample = 5.
        TestRunner.runToFuture(classOf[GSCCustomGenSuite]).map { report =>
            assert(report.suiteReports.nonEmpty, "Expected at least one suite report")
            val allResults = report.suiteReports.flatMap(_.leafResults)
            assert(allResults.nonEmpty, "Expected a leaf result")
            val (_, result) = allResults.head
            result match
                case f: TestResult.Failed =>
                    f.cause match
                        case Present(e: PropertyFailedException) =>
                            val shrunk = e.shrunkValue match
                                case n: Int => n
                                case other  => fail(s"Expected Int shrunkValue, got: $other")
                            assert(shrunk == 5, s"Expected shrunk counterexample to be 5 (minimal failing), got $shrunk")
                        case other => fail(s"Expected Present(PropertyFailedException) but got: $other")
                case _: TestResult.Passed =>
                    fail("Expected forAll to fail (customGen always samples 10, property n < 5 fails)")
                case other => fail(s"Expected Failed, got $other")
            end match
        }
    }

end GenShrinkChunkTest
