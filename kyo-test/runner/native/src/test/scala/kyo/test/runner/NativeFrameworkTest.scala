package kyo.test.runner

import kyo.test.internal.TestBase
import kyo.test.runner.internal.NativeRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite
import sbt.testing.Event
import sbt.testing.EventHandler
import sbt.testing.Status
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef

// ── Top-level suite classes for Native reflection instantiation ──────────────────────────────────
// Must extend kyo.test.internal.TestBase[Any] so the new runner's Instantiate can
// wire the NEW TestContext thread-local. Extend TestBase directly (no SuiteFingerprintMarker)
// so sbt does NOT auto-discover them.
// @EnableReflectiveInstantiation is inherited via KyoTestReflect in TestBase.

class NativeNextSingleLeafSuite extends TestBase[Any]:
    "leaf" in succeed
end NativeNextSingleLeafSuite

class NativeNextAsyncLeafSuite extends TestBase[Any]:
    "async-leaf" in succeed
end NativeNextAsyncLeafSuite

class NativeNextParallelSuite extends TestBase[Any]:
    "leaf-a" in succeed
    "leaf-b" in succeed
    "leaf-c" in succeed
end NativeNextParallelSuite

// ── Test infrastructure ──────────────────────────────────────────────────────────────────────────

class NativeCapturingEventHandler extends EventHandler:
    private val _events = new java.util.concurrent.CopyOnWriteArrayList[Event]()
    def handle(e: Event): Unit =
        _events.add(e)
        ()
    def events: List[Event] =
        import scala.jdk.CollectionConverters.*
        _events.asScala.toList
end NativeCapturingEventHandler

class NativeNoopLogger extends sbt.testing.Logger:
    def ansiCodesSupported(): Boolean = false
    def error(msg: String): Unit      = ()
    def warn(msg: String): Unit       = ()
    def info(msg: String): Unit       = ()
    def debug(msg: String): Unit      = ()
    def trace(t: Throwable): Unit     = ()
end NativeNoopLogger

// ── NativeFrameworkTest ──────────────────────────────────────────────────────────────────────────

/** Tests for [[NativeFramework]] and [[NativeTask]] on Scala Native. */
// ScalaTest bootstrap: this file tests NativeFramework using sbt.testing types; cannot self-host via kyo-test (classloader isolation).
class NativeFrameworkTest extends AnyFunSuite with NonImplicitAssertions:

    private val framework = new NativeFramework
    private val loggers   = Array(new NativeNoopLogger: sbt.testing.Logger)

    private def taskDefFor(cls: Class[?]): TaskDef =
        new TaskDef(
            cls.getName,
            NativeSuiteFingerprint,
            false,
            Array(new SuiteSelector)
        )

    private def makeRunner(args: String*): NativeRunner =
        framework.runner(args.toArray, Array.empty, getClass.getClassLoader)
            .asInstanceOf[NativeRunner]

    private def runTask(cls: Class[?], args: String*): List[Event] =
        val runner  = makeRunner(args*)
        val handler = new NativeCapturingEventHandler
        val task    = runner.tasks(Array(taskDefFor(cls)))(0)
        task.execute(handler, loggers)
        handler.events
    end runTask

    // ── Test 1: fingerprint matches SuiteFingerprintMarker subclasses ──────────────────────────

    test("fingerprint matches SuiteFingerprintMarker subclasses") {
        val fp = framework.fingerprints()(0).asInstanceOf[SubclassFingerprint]
        assert(fp.superclassName() == "kyo.test.SuiteFingerprintMarker"): Unit
        assert(fp.isModule() == false): Unit
        assert(fp.requireNoArgConstructor() == true): Unit
        val markerClass = classOf[kyo.test.SuiteFingerprintMarker]
        assert(markerClass.isAssignableFrom(classOf[kyo.test.Test[?]])): Unit
        assert(!markerClass.isAssignableFrom(classOf[NativeNextSingleLeafSuite])): Unit
    }

    // ── Test 2: task.execute runs single suite ──────────────────────────────────────────────────

    test("task.execute runs single suite") {
        val evts = runTask(classOf[NativeNextSingleLeafSuite])
        assert(evts.size == 1): Unit
        assert(evts(0).status() eq Status.Success): Unit
        assert(evts(0).fullyQualifiedName() == classOf[NativeNextSingleLeafSuite].getName): Unit
    }

    // ── Test 3: task.execute runs async ─────────────────────────────────────────────────────────

    test("task.execute runs async") {
        val evts = runTask(classOf[NativeNextAsyncLeafSuite])
        assert(evts.size == 1): Unit
        assert(evts(0).status() eq Status.Success): Unit
    }

    // ── Test 4: parallelism > 1 is silently sequential ───────────────────────────────────────────

    test("parallelism > 1 is silently sequential") {
        val evts = runTask(classOf[NativeNextParallelSuite], "--parallel=4")
        assert(evts.size == 3): Unit
        assert(evts.forall(_.status() eq Status.Success)): Unit
    }

    // ── Test 17: NativeFramework.fingerprints has exactly 1 fingerprint ─────────────────────────

    test("NativeFramework.fingerprints() has exactly 1 fingerprint") {
        val fps = framework.fingerprints()
        assert(fps.length == 1, s"Expected 1 fingerprint in NativeFramework, got ${fps.length}")
        val names = fps.collect { case fp: SubclassFingerprint => fp.superclassName() }
        assert(names.contains("kyo.test.SuiteFingerprintMarker"), s"Missing SuiteFingerprintMarker in: ${names.mkString(", ")}")
    }

end NativeFrameworkTest
