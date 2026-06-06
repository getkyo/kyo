package kyo.test.prop

import kyo.Abort
import kyo.Async
import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Maybe.Absent
import kyo.Maybe.Present
import kyo.Scope
import kyo.kernel.<
import kyo.test.internal.TestBase
import kyo.test.internal.TestContext
import kyo.test.prop.PropertyTest
import kyo.test.runner.TestRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Fixture suite for PropertyMaybeTest: extends TestBase[Any] (not Test[Any]) to opt out of sbt auto-discovery via SuiteFingerprintMarker.
  *
  * In package kyo.test.prop for access to private[prop] methods tryBody and tryFirstFailing on PropertyTest[S].
  */
class PropertyMaybeTestSuite extends TestBase[Any]:

    private def makeHarness(): PropertyTest[Any] =
        val nextCtx = new TestContext(Chunk.empty)
        TestContext.setForInstantiation(nextCtx)
        new PropertyTest[Any] {}
    end makeHarness

    // ── tryBody ──────────────────────────────────────────────────────────────

    "tryBody returns Absent when synchronous body succeeds" in {
        val pt: PropertyTest[Any] = makeHarness()
        pt.tryBody(42, (n: Int) => if n != 42 then Abort.fail(new RuntimeException(s"expected 42, got $n")) else ()).map {
            result => assert(result == Absent, s"Expected Absent but got $result")
        }
    }

    "tryBody returns Present(t) when synchronous body throws" in {
        val pt  = makeHarness()
        val err = new RuntimeException("forced failure")
        pt.tryBody(42, (_: Int) => throw err).map {
            case Present(t) if t eq err => assert(t.getMessage == "forced failure")
            case other                  => fail(s"Expected Present(err) but got $other")
        }
    }

    "tryBody returns Absent when Kyo body succeeds" in {
        val pt = makeHarness()
        pt.tryBody(1, (_: Int) => ()).map { result =>
            assert(result == Absent, s"Expected Absent but got $result")
        }
    }

    "tryBody returns Present(t) when Kyo body fails via Abort" in {
        val pt  = makeHarness()
        val err = new RuntimeException("kyo abort failure")
        pt.tryBody(1, (_: Int) => Abort.fail(err)).map {
            case Present(t) if t eq err => assert(t.getMessage == "kyo abort failure")
            case other                  => fail(s"Expected Present(err) but got $other")
        }
    }

    // Test 6 (compile-only): verify the static return type is Maybe[Throwable] < (Async & Scope)
    "tryBody has return type Maybe[Throwable] < (Async & Scope) (compile-only)" in {
        val pt                                              = makeHarness()
        val typed: Maybe[Throwable] < (Any & Async & Scope) = pt.tryBody(0, (_: Int) => ())
        typed.map { result =>
            assert(result == Absent)
        }
    }

    // ── tryFirstFailing ──────────────────────────────────────────────────────

    "tryFirstFailing returns Absent for empty Chunk" in {
        val pt = makeHarness()
        pt.tryFirstFailing(Chunk.empty[Int], (_: Int) => ()).map { result =>
            assert(result == Absent, s"Expected Absent but got $result")
        }
    }

    "tryFirstFailing returns Present with first failing element" in {
        val pt = makeHarness()
        val body: Int => Unit < (Any & Async & Abort[Throwable] & Scope) =
            n => if n >= 2 then Abort.fail(new RuntimeException(s"n=$n >= 2")) else ()
        pt.tryFirstFailing(Chunk(1, 2, 3), body).map {
            case Present((value, _: RuntimeException)) =>
                assert(value == 2, s"Expected first failing element to be 2, got $value")
            case other => fail(s"Expected Present((2, RuntimeException)) but got $other")
        }
    }

    "tryFirstFailing returns Absent when all candidates pass" in {
        val pt = makeHarness()
        pt.tryFirstFailing(Chunk(1, 2, 3), (_: Int) => ()).map { result =>
            assert(result == Absent, s"Expected Absent but got $result")
        }
    }

end PropertyMaybeTestSuite

/** Tests for the Maybe-returning internal helpers in PropertyTest[S].
  *
  * ScalaTest bootstrap: wraps PropertyMaybeTestSuite (extends TestBase[Any], not auto-discovered) via TestRunner.runToFuture so each leaf
  * runs through the new Kyo-discharging runner. In package kyo.test.prop for access to private[prop] methods.
  */
// ScalaTest bootstrap: cannot self-host since PropertyMaybeTestSuite uses TestBase[Any].
class PropertyMaybeTest extends AsyncFreeSpec with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = TestExecutionContext.executionContext

    "PropertyMaybeTest suite passes (all tryBody and tryFirstFailing leaves green)" in {
        TestRunner.runToFuture(classOf[PropertyMaybeTestSuite]).map { report =>
            val results = report.suiteReports.flatMap(_.leafResults)
            assert(results.nonEmpty, "Expected at least one leaf result")
            val failures = results.collect { case (path, f: kyo.test.TestResult.Failed) => (path, f) }
            assert(
                failures.isEmpty,
                s"Expected all PropertyMaybeTest leaves to pass, but got failures:\n${failures.map { case (p, f) =>
                        s"  ${p.mkString("/")} - ${f.diagram}"
                    }.mkString("\n")}"
            )
            succeed
        }
    }

end PropertyMaybeTest
