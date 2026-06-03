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

/** Tests for the non-terminal `.pending` decorator on TestBuilder.
  *
  * Verifies that:
  *   - `.pending(reason) - { body }` records `TestResult.Pending(reason)` without executing the body
  *   - `.pending("")` is accepted and records an empty reason
  *   - pending composes with other decorators (e.g. `.tagged`)
  *
  * ConsoleReporter rendering is tested in `kyo-test-runner` (requires runner classes not available in this module).
  */
// ScalaTest bootstrap: kyo-test-api cannot depend on kyo-test-runner (circular); only ScalaTest is available here.
class PendingDecoratorTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    private def runLeaf[S <: kyo.test.Test[Any]](make: => S): Future[(Chunk[String], TestResult)] =
        LeafHarness.runLeafWithPath(make)

    // Test 1: body never runs when pending
    "pending with reason: body never runs" in {
        val counter = new AtomicInteger(0)
        runLeaf {
            new kyo.test.Test[Any]:
                "x".pending("blocked") in Sync.defer { counter.incrementAndGet(): Unit }
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case TestResult.Pending(reason) =>
                    assert(reason == "blocked")
                    assert(counter.get() == 0, s"body ran despite pending; counter=${counter.get()}")
                case other => fail(s"Expected Pending(blocked), got $other")
            end match
        }
    }

    // Test 2: pending with empty reason
    "pending with empty reason" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".pending("") in {}
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case TestResult.Pending(reason) =>
                    assert(reason == "")
                case other => fail(s"Expected Pending(\"\"), got $other")
            end match
        }
    }

    // Test 3: async body never runs when pending
    "pending with async body: body never runs" in {
        val counter = new AtomicInteger(0)
        runLeaf {
            new kyo.test.Test[Any]:
                "x".pending("wip") in Sync.defer { counter.incrementAndGet(): Unit }
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case TestResult.Pending(reason) =>
                    assert(reason == "wip")
                    assert(counter.get() == 0, s"async body ran despite pending; counter=${counter.get()}")
                case other => fail(s"Expected Pending(wip), got $other")
            end match
        }
    }

    // Test 4: composability (pending + tagged)
    // Verify that chaining .pending.tagged builds a TestBuilder with both pending reason and the tag,
    // and that the runner records Pending (not the body result) when pending is set.
    "pending composes with tagged: tags recorded, result is Pending" in {
        // Structural check: construct the TestBuilder directly to verify the chain builds correctly.
        val builderChain = TestBuilder("x", pending = Maybe("wip"), tags = Set("slow"))
        assert(builderChain.tags == Set("slow"), s"Expected tags = Set('slow'), got ${builderChain.tags}")
        assert(builderChain.pending == Maybe("wip"), s"Expected pending = Maybe('wip'), got ${builderChain.pending}")
        runLeaf {
            new kyo.test.Test[Any]:
                "x".pending("wip").tagged("slow") in {}
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case TestResult.Pending(reason) =>
                    assert(reason == "wip")
                case other => fail(s"Expected Pending(wip), got $other")
            end match
        }
    }

end PendingDecoratorTest
