package kyo.test.prop

import kyo.test.prop.internal.Seed
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

// ScalaTest bootstrap: kyo-test-prop has no KyoTestPlugin (would be circular); only ScalaTest is available here.
class GenFilterBudgetTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = ExecutionContext.global

    "budget=8 exhaustion throws with budget and attempts == 8" in {
        // An always-rejecting predicate with budget=8 must exhaust all 8 slots and report
        // budget == 8 and attempts == 8 in the typed fields, proving the loop counted correctly.
        val g                                   = Gen.int.filter(_ => false, budget = 8)
        var caught: GenFilterExhaustedException = null
        try g.samples(1L, 10, 1)
        catch
            case ex: GenFilterExhaustedException => caught = ex
        end try
        assert(caught != null, "Expected GenFilterExhaustedException but no exception was thrown")
        assert(caught.budget == 8, s"Expected .budget == 8, got: ${caught.budget}")
        assert(caught.attempts == 8, s"Expected .attempts == 8, got: ${caught.attempts}")
        Future.successful(succeed)
    }

    "budget=5000 widens the retry, producing only matching values" in {
        val g      = Gen.int.filter(_ % 2 == 0, budget = 5000)
        val result = g.samples(42L, 50, 20)
        assert(result.size == 20, s"Expected 20 values, got ${result.size}")
        result.foreach { v =>
            assert(v % 2 == 0, s"Expected all values even, but got $v")
        }
        Future.successful(succeed)
    }

    "default filter delegates to budget 1000 and still throws on always-reject" in {
        // filter(p) with no budget must throw with .budget == 1000, proving delegation.
        val g                                   = Gen.int.filter(_ => false)
        var caught: GenFilterExhaustedException = null
        try g.sample(Seed(42L), 10)
        catch
            case ex: GenFilterExhaustedException => caught = ex
        end try
        assert(caught != null, "Expected GenFilterExhaustedException but no exception was thrown")
        assert(caught.budget == 1000, s"Expected .budget == 1000 from default filter, got: ${caught.budget}")
        Future.successful(succeed)
    }

    "enriched exception message names the budget and attempts" in {
        val ex  = new GenFilterExhaustedException(budget = 1000, attempts = 1000)
        val msg = ex.getMessage
        assert(msg.contains("1000"), s"Expected message to contain '1000', got: $msg")
        assert(msg.contains("budget"), s"Expected message to contain 'budget', got: $msg")
        assert(
            msg.contains("map") || msg.contains("construct") || msg.contains("valid"),
            s"Expected message to contain a map-based construction suggestion, got: $msg"
        )
        Future.successful(succeed)
    }

    "successful filter still propagates shrinking (regression)" in {
        // Find a seed that produces a positive root for Gen.int at size 50, then verify the
        // filtered tree has non-empty shrink children all satisfying the predicate.
        val seedVal  = 1L
        val filtered = Gen.int.filter(_ >= 0, budget = 1000)
        val tree     = filtered.sample(Seed(seedVal), 50)
        val root     = tree.value
        assert(root >= 0, s"Expected root to satisfy >= 0, got $root")
        val children = tree.shrinks().take(20).toList
        assert(children.nonEmpty, "Expected shrink children for filtered generator on a non-zero positive root")
        children.foreach { child =>
            assert(child.value >= 0, s"Shrink child ${child.value} does not satisfy the >= 0 predicate")
        }
        Future.successful(succeed)
    }

    "budget=0 throws IllegalArgumentException with a message mentioning budget" in {
        var caught: IllegalArgumentException = null
        try Gen.int.filter(_ => true, 0)
        catch
            case ex: IllegalArgumentException => caught = ex
        end try
        assert(caught != null, "Expected IllegalArgumentException for budget=0 but no exception was thrown")
        assert(
            caught.getMessage.contains("budget") || caught.getMessage.contains("positive"),
            s"Expected message to contain 'budget' or 'positive', got: ${caught.getMessage}"
        )
        Future.successful(succeed)
    }

    "budget=-1 throws IllegalArgumentException with a message mentioning budget" in {
        var caught: IllegalArgumentException = null
        try Gen.int.filter(_ => true, -1)
        catch
            case ex: IllegalArgumentException => caught = ex
        end try
        assert(caught != null, "Expected IllegalArgumentException for budget=-1 but no exception was thrown")
        assert(
            caught.getMessage.contains("budget") || caught.getMessage.contains("positive"),
            s"Expected message to contain 'budget' or 'positive', got: ${caught.getMessage}"
        )
        Future.successful(succeed)
    }

    "exhaustion throws a typed exception catchable at the call site" in {
        // Proves the exception is a KyoException subclass with typed budget/attempts fields,
        // observable by any caller that catches GenFilterExhaustedException at the call site.
        val g                                   = Gen.int.filter(_ => false, budget = 8)
        var caught: GenFilterExhaustedException = null
        try g.samples(1L, 10, 1)
        catch
            case ex: GenFilterExhaustedException => caught = ex
        end try
        assert(caught != null, "Expected GenFilterExhaustedException")
        assert(caught.budget == 8, s"Expected .budget == 8, got: ${caught.budget}")
        assert(caught.attempts == 8, s"Expected .attempts == 8, got: ${caught.attempts}")
        // GenFilterExhaustedException extends kyo.KyoException (Gen.scala), so the Kyo Abort machinery can
        // handle it; that subtyping is a compile-time guarantee, asserting it at runtime would be tautological.
        val _: kyo.KyoException = caught
        Future.successful(succeed)
    }

end GenFilterBudgetTest
