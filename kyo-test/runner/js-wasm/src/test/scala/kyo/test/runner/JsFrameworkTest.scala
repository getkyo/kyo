package kyo.test.runner

import kyo.test.internal.TestBase
import kyo.test.runner.internal.JsRunner
import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AsyncFunSuite
import sbt.testing.Event
import sbt.testing.EventHandler
import sbt.testing.Status
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

// ── Top-level suite classes for JS reflection instantiation ─────────────────────────────────────
// Must extend kyo.test.internal.TestBase[Any] so the new runner's Instantiate can
// wire the NEW TestContext thread-local. Extend TestBase directly (no SuiteFingerprintMarker)
// so sbt does NOT auto-discover them.
// @EnableReflectiveInstantiation is inherited via KyoTestReflect in TestBase.

class JsNextSingleLeafSuite extends TestBase[Any]:
    "leaf" in succeed
end JsNextSingleLeafSuite

class JsNextAsyncLeafSuite extends TestBase[Any]:
    "async-leaf" in succeed
end JsNextAsyncLeafSuite

class JsNextParallelSuite extends TestBase[Any]:
    "leaf-a" in succeed
    "leaf-b" in succeed
    "leaf-c" in succeed
end JsNextParallelSuite

class JsNextFailingSuite extends TestBase[Any]:
    "group" - {
        "fails" in assert(1 == 2)
    }
end JsNextFailingSuite

// ── Test infrastructure ──────────────────────────────────────────────────────────────────────────

class JsCapturingEventHandler extends EventHandler:
    private val _events        = scala.collection.mutable.ListBuffer.empty[Event]
    def handle(e: Event): Unit =
        _events += e
        ()
    def events: List[Event] = _events.toList
end JsCapturingEventHandler

class JsNoopLogger extends sbt.testing.Logger:
    def ansiCodesSupported(): Boolean = false
    def error(msg: String): Unit      = ()
    def warn(msg: String): Unit       = ()
    def info(msg: String): Unit       = ()
    def debug(msg: String): Unit      = ()
    def trace(t: Throwable): Unit     = ()
end JsNoopLogger

// ── JsFrameworkTest ──────────────────────────────────────────────────────────────────────────────

/** Tests for [[JsFramework]] and [[JsTask]] on Scala.js. */
// ScalaTest bootstrap: this file tests JsRunner using sbt.testing types; cannot self-host via kyo-test (classloader isolation).
class JsFrameworkTest extends AsyncFunSuite with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext =
        MacrotaskExecutor

    private val framework = new JsFramework
    private val loggers   = Array(new JsNoopLogger: sbt.testing.Logger)

    private def taskDefFor(cls: Class[?]): TaskDef =
        new TaskDef(
            cls.getName,
            JsSuiteFingerprint,
            false,
            Array(new SuiteSelector)
        )

    private def makeRunner(args: String*): JsRunner =
        // On Scala.js, ClassLoader is not available; pass null (JsTask uses Reflect.lookupInstantiatableClass, not classLoader.loadClass)
        framework.runner(args.toArray, Array.empty, null)
            .asInstanceOf[JsRunner]

    private def runTask(cls: Class[?], args: String*): Future[List[Event]] =
        val runner  = makeRunner(args*)
        val handler = new JsCapturingEventHandler
        val done    = Promise[Unit]()
        val task    = runner.jsTasksTyped(Array(taskDefFor(cls)))(0)
        task.execute(
            handler,
            loggers,
            _ =>
                done.success(())
                scala.runtime.BoxedUnit.UNIT
        )
        done.future.map(_ => handler.events)
    end runTask

    // ── Test 1: fingerprint matches SuiteFingerprintMarker subclasses ──────────────────────────

    test("fingerprint matches SuiteFingerprintMarker subclasses") {
        val fp = framework.fingerprints()(0).asInstanceOf[SubclassFingerprint]
        assert(fp.superclassName() == "kyo.test.SuiteFingerprintMarker"): Unit
        assert(fp.isModule() == false): Unit
        assert(fp.requireNoArgConstructor() == true): Unit
        val markerClass = classOf[kyo.test.SuiteFingerprintMarker]
        assert(markerClass.isAssignableFrom(classOf[kyo.test.Test[?]])): Unit
        assert(!markerClass.isAssignableFrom(classOf[JsNextSingleLeafSuite])): Unit
        Future.successful(succeed)
    }

    // ── Test 2: task.execute runs single suite ──────────────────────────────────────────────────

    test("task.execute runs single suite") {
        runTask(classOf[JsNextSingleLeafSuite]).map { evts =>
            assert(evts.size == 1): Unit
            assert(evts(0).status() eq Status.Success): Unit
            assert(evts(0).fullyQualifiedName() == classOf[JsNextSingleLeafSuite].getName): Unit
            succeed
        }
    }

    // ── Test 3: task.execute runs async ─────────────────────────────────────────────────────────

    test("task.execute runs async") {
        runTask(classOf[JsNextAsyncLeafSuite]).map { evts =>
            assert(evts.size == 1): Unit
            assert(evts(0).status() eq Status.Success): Unit
            succeed
        }
    }

    // ── Test 4: parallelism > 1 is accepted and all leaves run (JS) ────────────────────────────

    test("parallelism > 1 is accepted and all leaves run") {
        runTask(classOf[JsNextParallelSuite], "--parallel=4").map { evts =>
            assert(evts.size == 3): Unit
            assert(evts.forall(_.status() eq Status.Success)): Unit
            succeed
        }
    }

    // The Scala.js test adapter runs one JS environment per sbt thread: the runner from `runner` is the controller, and a task that
    // sbt executes on another thread runs in a worker from `slaveRunner`, whose `send` the adapter routes to the controller's
    // `receiveMessage`. Only the controller's `done()` reaches the log, so it must count every worker's leaves.

    private def executeOn(runner: sbt.testing.Runner, cls: Class[?]): Future[Unit] =
        val done = Promise[Unit]()
        runner.asInstanceOf[JsRunner].jsTasksTyped(Array(taskDefFor(cls)))(0).execute(
            new JsCapturingEventHandler,
            loggers,
            _ =>
                done.success(())
                scala.runtime.BoxedUnit.UNIT
        )
        done.future
    end executeOn

    test("a worker's leaves are counted in the controller's summary") {
        val controller = makeRunner()
        val worker     = framework.slaveRunner(Array.empty, Array.empty, null, msg => kyo.discard(controller.receiveMessage(msg)))
        for
            _ <- executeOn(worker, classOf[JsNextParallelSuite])
            _ <- executeOn(worker, classOf[JsNextFailingSuite])
        yield
            val _       = worker.done()
            val summary = controller.done()
            assert(summary.startsWith("kyo-test: 4 tests, 3 passed, 1 failed"), summary): Unit
            assert(summary.contains("TOTAL FAILURES (1)"), summary): Unit
            assert(summary.contains("group > fails  [FAIL]"), summary)
        end for
    }

    test("the controller counts its own leaves together with a worker's") {
        val controller = makeRunner()
        val worker     = framework.slaveRunner(Array.empty, Array.empty, null, msg => kyo.discard(controller.receiveMessage(msg)))
        for
            _ <- executeOn(controller, classOf[JsNextSingleLeafSuite])
            _ <- executeOn(worker, classOf[JsNextParallelSuite])
        yield
            val _       = worker.done()
            val summary = controller.done()
            assert(summary.startsWith("kyo-test: 4 tests, 4 passed, 0 failed"), summary)
        end for
    }

    // ── Test 17: JsFramework.fingerprints has exactly 1 fingerprint ─────────────────────────────

    test("JsFramework.fingerprints() has exactly 1 fingerprint") {
        val fps = framework.fingerprints()
        assert(fps.length == 1, s"Expected 1 fingerprint in JsFramework, got ${fps.length}")
        val names = fps.collect { case fp: sbt.testing.SubclassFingerprint => fp.superclassName() }
        assert(names.contains("kyo.test.SuiteFingerprintMarker"), s"Missing SuiteFingerprintMarker in: ${names.mkString(", ")}")
        Future.successful(succeed)
    }

end JsFrameworkTest
