package kyo.test.runner

import java.io.ByteArrayInputStream
import java.net.URL
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kyo.test.internal.TestBase
import kyo.test.runner.internal.SbtRunner
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite
import sbt.testing.Event
import sbt.testing.EventHandler
import sbt.testing.Status
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef

// ── Top-level suite classes for reflection instantiation ────────────────────────────────────────
// Must extend kyo.test.internal.TestBase[Any] so the new runner's Instantiate can
// wire the NEW TestContext thread-local. These extend TestBase directly (no SuiteFingerprintMarker)
// so sbt does NOT auto-discover them outside this test.

class NextSingleLeafSuite extends TestBase[Any]:
    "leaf" in succeed
end NextSingleLeafSuite

class NextMultiLeafSuite extends TestBase[Any]:
    "first" in succeed
    "second" in succeed
end NextMultiLeafSuite

class NextAsyncLeafSuite extends TestBase[Any]:
    "async-leaf" in succeed
end NextAsyncLeafSuite

class NextFailingSuite extends TestBase[Any]:
    "leaf" in assert(1 == 2)
end NextFailingSuite

class NextConstructorFailSuite extends TestBase[Any]:
    throw new RuntimeException("boom")
    "never" in succeed
end NextConstructorFailSuite

class NextSuiteA extends TestBase[Any]:
    "test-a" in succeed
end NextSuiteA

class NextSuiteB extends TestBase[Any]:
    "test-b" in assert(1 == 2)
end NextSuiteB

// ── Test infrastructure ─────────────────────────────────────────────────────────────────────────

class CapturingEventHandler extends EventHandler:
    private val _events = new java.util.concurrent.CopyOnWriteArrayList[Event]()
    def handle(e: Event): Unit =
        _events.add(e)
        ()
    def events: List[Event] =
        import scala.jdk.CollectionConverters.*
        _events.asScala.toList
end CapturingEventHandler

class NoopLogger extends sbt.testing.Logger:
    def ansiCodesSupported(): Boolean = false
    def error(msg: String): Unit      = ()
    def warn(msg: String): Unit       = ()
    def info(msg: String): Unit       = ()
    def debug(msg: String): Unit      = ()
    def trace(t: Throwable): Unit     = ()
end NoopLogger

// ── SbtFrameworkTest ────────────────────────────────────────────────────────────────────────────

// ScalaTest bootstrap: this file tests SbtFramework using sbt.testing types; cannot self-host via kyo-test (classloader isolation).
class SbtFrameworkTest extends AnyFunSuite with NonImplicitAssertions:

    private val framework = new SbtFramework

    private val loggers = Array(new NoopLogger: sbt.testing.Logger)

    private def taskDefFor(cls: Class[?]): TaskDef =
        new TaskDef(
            cls.getName,
            SuiteFingerprint,
            false,
            Array(new SuiteSelector)
        )

    private def makeRunner(args: String*): SbtRunner =
        framework.runner(args.toArray, Array.empty, getClass.getClassLoader)
            .asInstanceOf[SbtRunner]

    // ── Test 1: fingerprint matches SuiteFingerprintMarker subclasses ──────────────────────────

    test("fingerprint matches SuiteFingerprintMarker subclasses") {
        val fp = framework.fingerprints()(0).asInstanceOf[SubclassFingerprint]
        assert(fp.superclassName() == "kyo.test.SuiteFingerprintMarker"): Unit
        assert(fp.isModule() == false): Unit
        assert(fp.requireNoArgConstructor() == true): Unit
        // kyo.test.Test extends SuiteFingerprintMarker; NextSingleLeafSuite extends TestBase directly (no marker).
        val markerClass = Class.forName("kyo.test.SuiteFingerprintMarker", false, getClass.getClassLoader)
        assert(markerClass.isAssignableFrom(classOf[kyo.test.Test[?]])): Unit
        assert(!markerClass.isAssignableFrom(classOf[NextSingleLeafSuite])): Unit
    }

    // ── Test 2: runner.tasks returns one task per suite class ────────────────────────────────────

    test("runner.tasks returns one task per suite class") {
        val runner = makeRunner()
        val defs = Array(
            taskDefFor(classOf[NextSingleLeafSuite]),
            taskDefFor(classOf[NextMultiLeafSuite]),
            taskDefFor(classOf[NextAsyncLeafSuite])
        )
        val tasks = runner.tasks(defs)
        assert(tasks.length == 3): Unit
    }

    // ── Test 3: task.execute runs the suite and emits Events ────────────────────────────────────

    test("task.execute runs the suite and emits Events") {
        val runner  = makeRunner()
        val handler = new CapturingEventHandler
        val task    = runner.tasks(Array(taskDefFor(classOf[NextSingleLeafSuite])))(0)
        task.execute(handler, loggers)
        val evts = handler.events
        assert(evts.size == 1): Unit
        assert(evts(0).status() eq Status.Success): Unit
        assert(evts(0).fullyQualifiedName() == classOf[NextSingleLeafSuite].getName): Unit
    }

    // ── Test 4: task.execute blocks until all leaves complete ────────────────────────────────────

    test("task.execute blocks until all leaves complete") {
        val runner  = makeRunner()
        val handler = new CapturingEventHandler
        val task    = runner.tasks(Array(taskDefFor(classOf[NextAsyncLeafSuite])))(0)
        task.execute(handler, loggers)
        val evts = handler.events
        assert(evts.size == 1): Unit
        assert(evts(0).status() eq Status.Success): Unit
    }

    // ── Test 5: task.execute handles suite construction failure ──────────────────────────────────

    test("task.execute handles suite construction failure") {
        val runner  = makeRunner()
        val handler = new CapturingEventHandler
        val task    = runner.tasks(Array(taskDefFor(classOf[NextConstructorFailSuite])))(0)
        task.execute(handler, loggers)
        val evts = handler.events
        assert(evts.size == 1): Unit
        assert(evts(0).status() eq Status.Failure): Unit
    }

    // ── Test 9: runner.done returns summary ─────────────────────────────────────────────────────

    test("runner.done returns summary") {
        val runner   = makeRunner()
        val handler1 = new CapturingEventHandler
        val handler2 = new CapturingEventHandler
        val task1    = runner.tasks(Array(taskDefFor(classOf[NextSuiteA])))(0)
        val task2    = runner.tasks(Array(taskDefFor(classOf[NextSuiteB])))(0)
        task1.execute(handler1, loggers)
        task2.execute(handler2, loggers)
        val summary = runner.done()
        assert(summary.contains("2 tests")): Unit
        assert(summary.contains("1 passed")): Unit
        assert(summary.contains("1 failed")): Unit
    }

    // ── Test 10: done() summary loses a run executed by a separate (forked) runner ──────────────
    // Under `fork := true`, sbt runs SbtTask.execute in the forked JVM's runner, but logs the
    // main-JVM runner's done(), whose results queue never received the reports, so the kyo-test
    // summary line and TOTAL FAILURES block report zero. This is modelled by executing the suite
    // on a separate runner from the one that produces the summary. pendingUntilFixed runs the body
    // and inverts: a still-failing body reports Pending. Remove the marker once the fork-side
    // summary is surfaced and the body passes (if the fix routes the fork's own done() rather than
    // sharing results across instances, remove the marker by hand when that lands).

    test("done() summary reflects a run executed by a separate (forked) runner instance") {
        pendingUntilFixed {
            val summaryRunner = makeRunner()
            val execRunner    = makeRunner()
            execRunner.tasks(Array(taskDefFor(classOf[NextSuiteA])))(0).execute(new CapturingEventHandler, loggers)
            val summary = summaryRunner.done()
            assert(summary.contains("1 tests")): Unit
            assert(summary.contains("1 passed")): Unit
        }
    }

    // ── Test 11: SbtRunner.discoveryErrors is overwritten by successive calls ───────────────────

    test("discoveryErrors is overwritten by successive calls to tasks(), not accumulated") {
        val ServiceFile = "META-INF/services/kyo.test.Test"

        def makeBytes(content: String): Array[Byte] = content.getBytes(StandardCharsets.UTF_8)

        val firstBogus  = "com.nonexistent.FirstGenerationError"
        val secondBogus = "com.nonexistent.SecondGenerationError"

        val callCount = new AtomicInteger(0)
        val parent    = getClass.getClassLoader

        val statefulLoader = new URLClassLoader(Array.empty[URL], parent):
            override def getResources(name: String): java.util.Enumeration[URL] =
                if name == ServiceFile then
                    val generation = callCount.getAndIncrement()
                    val content    = if generation == 0 then firstBogus + "\n" else secondBogus + "\n"
                    val bytes      = makeBytes(content)
                    // The 5-arg URL constructor is deprecated, but it is the only way to attach a custom
                    // URLStreamHandler (URI has no equivalent).
                    @scala.annotation.nowarn("cat=deprecation")
                    val url = new URL(
                        "jar",
                        null,
                        0,
                        s"synthetic-gen-$generation",
                        new java.net.URLStreamHandler:
                            def openConnection(u: URL): java.net.URLConnection =
                                new java.net.URLConnection(u):
                                    def connect(): Unit = ()
                                    override def getInputStream: java.io.InputStream =
                                        new ByteArrayInputStream(bytes)
                    )
                    Collections.enumeration(java.util.List.of(url))
                else
                    super.getResources(name)

        val runner = framework.runner(Array.empty, Array.empty, statefulLoader)
            .asInstanceOf[SbtRunner]

        runner.tasks(Array(taskDefFor(classOf[NextSingleLeafSuite])))
        val errorsAfterFirst = runner.discoveryErrors.get()

        runner.tasks(Array(taskDefFor(classOf[NextSingleLeafSuite])))
        val errorsAfterSecond = runner.discoveryErrors.get()

        assert(
            errorsAfterFirst.exists(_.contains(firstBogus)),
            s"Expected first error set to mention '$firstBogus'; got: $errorsAfterFirst"
        )
        assert(
            errorsAfterSecond.exists(_.contains(secondBogus)),
            s"Expected second error set to mention '$secondBogus'; got: $errorsAfterSecond"
        )
        assert(
            !errorsAfterSecond.exists(_.contains(firstBogus)),
            s"Expected second error set NOT to mention '$firstBogus' (overwrite, not accumulate); got: $errorsAfterSecond"
        )
    }

    // ── Test 17: SbtFramework.fingerprints().length ─────────────────────────────────────────────

    test("SbtFramework.fingerprints() has exactly 1 fingerprint") {
        val fps = framework.fingerprints()
        assert(
            fps.length == 1,
            s"Expected 1 fingerprint in SbtFramework (SuiteFingerprint for SuiteFingerprintMarker), got ${fps.length}"
        )
    }

end SbtFrameworkTest
