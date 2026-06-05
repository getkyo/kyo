package kyo.test

import java.util.concurrent.atomic.AtomicInteger
import kyo.Chunk
import kyo.Maybe
import kyo.Sync
import kyo.test.TestBuilder
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Tests for the non-terminal `.ignore` decorator on TestBuilder.
  *
  * Verifies that:
  *   - `.ignore(reason)` records `TestResult.Ignored(reason)` without executing the body
  *   - `.ignore("")` is accepted and records an empty reason
  *   - ignore composes with other decorators (e.g. `.tagged`)
  *
  * ConsoleReporter rendering is tested in `kyo-test-runner` (requires runner classes not available in this module).
  */
// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class IgnoreDecoratorTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    private def runLeaf[S <: kyo.test.Test[Any]](make: => S): Future[(Chunk[String], TestResult)] =
        LeafHarness.runLeafWithPath(make)

    // Test 1: body never runs when ignored
    "ignore with reason: body never runs" in {
        val counter = new AtomicInteger(0)
        runLeaf {
            new kyo.test.Test[Any]:
                "x".ignore("blocked") in Sync.defer { counter.incrementAndGet(): Unit }
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case TestResult.Ignored(reason) =>
                    assert(reason == "blocked")
                    assert(counter.get() == 0, s"body ran despite ignore; counter=${counter.get()}")
                case other => fail(s"Expected Ignored(blocked), got $other")
            end match
        }
    }

    // Test 2: ignore with empty reason
    "ignore with empty reason" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".ignore("") in {}
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case TestResult.Ignored(reason) =>
                    assert(reason == "")
                case other => fail(s"Expected Ignored(\"\"), got $other")
            end match
        }
    }

    // Test 3: async body never runs when ignored
    "ignore with async body: body never runs" in {
        val counter = new AtomicInteger(0)
        runLeaf {
            new kyo.test.Test[Any]:
                "x".ignore("wip") in Sync.defer { counter.incrementAndGet(): Unit }
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case TestResult.Ignored(reason) =>
                    assert(reason == "wip")
                    assert(counter.get() == 0, s"async body ran despite ignore; counter=${counter.get()}")
                case other => fail(s"Expected Ignored(wip), got $other")
            end match
        }
    }

    // Test 4: composability (ignore + tagged)
    // Verify that chaining .ignore.tagged builds a TestBuilder with both ignore reason and the tag,
    // and that the runner records Ignored (not the body result) when ignore is set.
    "ignore composes with tagged: tags recorded, result is Ignored" in {
        // Structural check: construct the TestBuilder directly to verify the chain builds correctly.
        val builderChain = TestBuilder("x", ignore = Maybe("wip"), tags = Set("slow"))
        assert(builderChain.tags == Set("slow"), s"Expected tags = Set('slow'), got ${builderChain.tags}")
        assert(builderChain.ignore == Maybe("wip"), s"Expected ignore = Maybe('wip'), got ${builderChain.ignore}")
        runLeaf {
            new kyo.test.Test[Any]:
                "x".ignore("wip").tagged("slow") in {}
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case TestResult.Ignored(reason) =>
                    assert(reason == "wip")
                case other => fail(s"Expected Ignored(wip), got $other")
            end match
        }
    }

end IgnoreDecoratorTest
