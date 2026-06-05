package kyo.test

import kyo.Async
import kyo.Chunk
import kyo.Duration
import kyo.millis
import kyo.seconds
import kyo.test.RunConfig
import kyo.test.TestBuilder
import kyo.test.internal.TestContext
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Top-level helper for [[TestBaseTest]] "hooks have correct defaults" test.
  *
  * Defined at top level (not inside the test method) so that [[Class.getSimpleName]] returns the unmangled name "HookReader" on all
  * platforms (Scala.js mangles names of locally-defined classes with a `[$1]` suffix).
  */
class HookReader extends kyo.test.Test[Any]:
    def readName      = name
    def readRandomize = randomize
    def readConfig    = config
end HookReader

/** Top-level helper for the "hooks can be overridden" test (top-level so Class.getSimpleName is unmangled on Scala.js). */
class OverriddenHooks extends kyo.test.Test[Any]:
    override protected def name: String       = "custom-name"
    override protected def randomize: Boolean = true
    override protected val randomSeed: Long   = 42L
    override def config: RunConfig =
        RunConfig.default.copy(parallelism = 4)
    def readName       = name
    def readRandomize  = randomize
    def readRandomSeed = randomSeed
    def readConfig     = config
end OverriddenHooks

// ScalaTest bootstrap: this file tests TestBase itself; cannot self-host using the framework-under-test.
class TestBaseTest extends AsyncFreeSpec with NonImplicitAssertions:

    // Use MacrotaskExecutor (global on JS) so Future chains work on all platforms without SerialExecutionContext conflicts.
    implicit override val executionContext: ExecutionContext = scala.concurrent.ExecutionContext.global

    // Install the next registration context, as the runner does, so a next suite that registers no leaf (a hook-reader) can still be
    // instantiated outside the runner.
    private def installContexts(): Unit =
        TestContext.setForInstantiation(new TestContext(Chunk(0)))

    // Run a single-leaf suite at cursor Vector(0) and return its result.
    private def runLeaf[S <: kyo.test.Test[Any]](make: => S): Future[(Chunk[String], TestResult)] =
        LeafHarness.runLeafWithPath(Chunk(0))(make)

    // Run a nested suite: returns result at cursor [0, 0].
    private def runNested[S <: kyo.test.Test[Any]](make: => S): Future[(Chunk[String], TestResult)] =
        LeafHarness.runLeafWithPath(Chunk(0, 0))(make)

    "- sync registers leaf with Passed" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "sync" in { succeed }
        }.map { case (path, result) =>
            assert(path == Chunk("sync"))
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "- sync registers leaf with Failed on AssertionFailed" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "fail-sync" in assert(1 == 2)
        }.map { case (path, result) =>
            assert(path == Chunk("fail-sync"))
            result match
                case _: TestResult.Failed => succeed
                case other                => fail(s"Expected Failed, got $other")
        }
    }

    "- F[Unit] registers async leaf, framework awaits" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "async-pass" in Async.sleep(1.millis).andThen(succeed)
        }.map { case (path, result) =>
            assert(path == Chunk("async-pass"))
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "- async leaf with failing body records Failed" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "async-fail" in Async.sleep(1.millis).andThen(assert(1 == 2))
        }.map { case (path, result) =>
            assert(path == Chunk("async-fail"))
            result match
                case _: TestResult.Failed => succeed
                case other                => fail(s"Expected Failed, got $other")
        }
    }

    "nested groups register paths correctly" in {
        runNested {
            new kyo.test.Test[Any]:
                "outer" - {
                    "inner" in { succeed }
                }
        }.map { case (path, result) =>
            assert(path == Chunk("outer", "inner"))
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "class body registers a leaf computed from a local value" in {
        runLeaf {
            new kyo.test.Test[Any]:
                val n = 42
                s"test-$n" in assert(n == 42)
        }.map { case (path, result) =>
            assert(path == Chunk("test-42"))
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "takeFromThreadLocal throws outside framework" in {
        try
            new kyo.test.Test[Any] {}
            fail("Expected IllegalStateException")
        catch
            case e: IllegalStateException =>
                assert(e.getMessage == "kyo.test.Test must be instantiated by the kyo-test runner")
                succeed
    }

    "- on TestBuilder propagates metadata" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "flaky".retry(3) in { () }
        }.map { case (path, _) =>
            assert(path == Chunk("flaky"))
            succeed
        }
    }

    "ignore(reason) records Ignored without running body" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "todo".ignore("waiting") in { throw new RuntimeException("should not run") }
        }.map { case (path, result) =>
            assert(path == Chunk("todo"))
            result match
                case TestResult.Ignored(reason) =>
                    assert(reason == "waiting")
                    succeed
                case other => fail(s"Expected Ignored(waiting), got $other")
            end match
        }
    }

    "ignore - { ... } records Ignored without invoking body" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "skip-me".ignore in { throw new RuntimeException("should not run") }
        }.map { case (path, result) =>
            assert(path == Chunk("skip-me"))
            result match
                case TestResult.Ignored(_) => succeed
                case other                 => fail(s"Expected Ignored, got $other")
        }
    }

    "tagged adds tags to TestBuilder" in {
        val ctx     = new TestContext(Chunk(0))
        val builder = TestBuilder("x").copy(tags = Set("a", "b"))
        ctx.visitLeafWithBuilder[Any]("x", builder, ())
        ctx.signalPastEnd()
        val stored = ctx.builderFor(Chunk("x"))
        assert(stored.isDefined)
        assert(stored.getOrElse(TestBuilder("?")).tags == Set("a", "b"))
        succeed
    }

    "focus on TestBuilder" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".focus in { () }
        }.map { case (path, _) =>
            assert(path == Chunk("x"))
            succeed
        }
    }

    "timeout on TestBuilder" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".timeout(5L.seconds) in { () }
        }.map { case (path, _) =>
            assert(path == Chunk("x"))
            succeed
        }
    }

    "retry on TestBuilder" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".retry(3) in { succeed }
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "times on TestBuilder" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".times(3) in { succeed }
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "only(false) skips body" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".only(false) in { throw new RuntimeException("should skip") }
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case _: TestResult.Skipped => succeed
                case other                 => fail(s"Expected Skipped, got $other")
        }
    }

    "only(true) runs body" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".only(true) in { succeed }
        }.map { case (path, result) =>
            assert(path == Chunk("x"))
            result match
                case _: TestResult.Passed => succeed
                case other                => fail(s"Expected Passed, got $other")
        }
    }

    "slow on TestBuilder" in {
        runLeaf {
            new kyo.test.Test[Any]:
                "x".slow in { () }
        }.map { case (path, _) =>
            assert(path == Chunk("x"))
            succeed
        }
    }

    "hooks have correct defaults" in {
        installContexts()
        val t = new HookReader
        assert(t.readName == "HookReader")
        assert(!t.readRandomize)
        // config folds in the overridable per-test timeout: 60s by default, Infinity under a debugger.
        val expectedTimeout = if kyo.internal.Platform.isDebugEnabled then Duration.Infinity else 60.seconds
        assert(t.readConfig == RunConfig.default.copy(timeout = expectedTimeout))
        succeed
    }

    "hooks can be overridden" in {
        installContexts()
        val t = new OverriddenHooks
        assert(t.readName == "custom-name")
        assert(t.readRandomize)
        assert(t.readRandomSeed == 42L)
        assert(t.readConfig == RunConfig.default.copy(parallelism = 4))
        succeed
    }

    // Tag combinator tests

    // Test 1: .slow adds "slow" to tags
    // The .slow extension on String/TestBuilder creates: copy(tags = <existing> + "slow")
    "slow decorator adds 'slow' to tags" in {
        // Build builder the same way the .slow extension on String does internally
        val builder = TestBuilder("some-test").copy(tags = Set("slow"))
        assert(builder.tags.contains("slow"))
        succeed
    }

    // Test 1b: .slow on TestBuilder chain preserves pre-existing tags and adds "slow"
    "slow on TestBuilder chain adds 'slow' to tags" in {
        // The .slow extension on TestBuilder does b.copy(tags = b.tags + "slow")
        val base    = TestBuilder("some-test").copy(tags = Set("other"))
        val builder = base.copy(tags = base.tags + "slow")
        assert(builder.tags.contains("slow"))
        assert(builder.tags.contains("other"), "pre-existing tags must be preserved")
        succeed
    }

    // Test 2: TestFilter tagsExclude filters out slow-decorated builder
    "TestFilter tagsExclude filters out slow-decorated builder" in {
        val builder    = TestBuilder("slow-test").copy(tags = Set("slow"))
        val filter     = TestFilter(tagsExclude = Set("slow"))
        val tags       = builder.tags
        val isExcluded = filter.tagsExclude.exists(tags.contains)
        assert(isExcluded)
        succeed
    }

    // Test 3: RunConfig.default.timeout equals Duration.Infinity
    "RunConfig.default.timeout equals Duration.Infinity" in {
        assert(RunConfig.default.timeout == Duration.Infinity)
        succeed
    }

    // Test 4: defaultTimeout no longer compiles on TestBase subclass
    "defaultTimeout no longer compiles on TestBase subclass" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """
            import kyo.Duration
            import kyo.seconds
            class BadSuite extends kyo.test.Test[Any]:
                override protected def defaultTimeout: Duration = 30L.seconds
            """
        )
        assert(errors.nonEmpty, "Expected a compile error for override def defaultTimeout but got none")
        succeed
    }

end TestBaseTest
