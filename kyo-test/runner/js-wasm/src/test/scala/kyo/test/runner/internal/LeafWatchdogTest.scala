package kyo.test.runner.internal

import kyo.*
import kyo.test.internal.TestBase
import kyo.test.runner.JsFramework
import kyo.test.runner.JsSuiteFingerprint
import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AsyncFunSuite
import sbt.testing.Event
import sbt.testing.EventHandler
import sbt.testing.SuiteSelector
import sbt.testing.TaskDef
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** Run by the child process only. It extends TestBase without the fingerprint marker, so sbt never discovers it. */
class LeafWatchdogStallSuite extends TestBase[Any]:

    // Sequential, so the parked leaf's own timeout fires and disarms it before the spin starts.
    override def config = super.config.sequential

    "parks until its timeout".timeout(500.millis) in {
        Async.never[Unit]
    }

    // The bound is two minutes of spinning against a half-second timeout: without the watchdog the leaf holds the event loop for all of it.
    "holds the event loop".timeout(500.millis) in {
        val spun = LeafWatchdogStallSuite.spin(java.lang.System.nanoTime() + 120L * 1000 * 1000 * 1000)
        assert(spun > 0L)
    }

end LeafWatchdogStallSuite

object LeafWatchdogStallSuite:
    def spin(untilNanos: Long): Long =
        var iterations = 0L
        while java.lang.System.nanoTime() < untilNanos do iterations += 1
        iterations
    end spin
end LeafWatchdogStallSuite

/** The entry point the child process calls: runs [[LeafWatchdogStallSuite]] through the sbt task. Events arrive only when the suite ends. */
object LeafWatchdogFixture:

    val finished = "[leaf-watchdog-fixture] task finished"

    @JSExportTopLevel("kyoTestLeafWatchdogFixture")
    def run(): Unit =
        val runner = new JsFramework().runner(Array.empty, Array.empty, null).asInstanceOf[JsRunner]
        val task   = runner.jsTasksTyped(Array(
            new TaskDef(classOf[LeafWatchdogStallSuite].getName, JsSuiteFingerprint, false, Array(new SuiteSelector))
        ))(0)
        val handler = new EventHandler:
            def handle(event: Event): Unit =
                println(s"[leaf-watchdog-fixture] event ${event.status()} ${event.selector()}")
        task.execute(
            handler,
            Array.empty,
            _ =>
                println(finished)
                scala.runtime.BoxedUnit.UNIT
        )
    end run

end LeafWatchdogFixture

// ScalaTest bootstrap: the watchdog ends the Node process it runs in, so the suite under watch runs in a child process this test observes.
class LeafWatchdogTest extends AsyncFunSuite with NonImplicitAssertions:

    implicit override val executionContext: ExecutionContext = MacrotaskExecutor

    final private case class ChildRun(stdout: String, stderr: String, exit: String)

    private val process = js.Dynamic.global.process

    /** The child loads the linked test module the way the Scala.js NodeJSEnv does, with a stand-in for the JSEnv's com channel so the test
      * bridge the module starts stays idle, then calls [[LeafWatchdogFixture.run]].
      */
    private def runFixture(): Future[ChildRun] =
        val module = process.env.KYO_TEST_LINKED_MODULE.asInstanceOf[String]
        val kind   = process.env.KYO_TEST_LINKED_MODULE_KIND.asInstanceOf[String]
        // A pipe is asynchronous on macOS, so the reporter's lines from before the stall would still be queued when the process is killed.
        val stubCom =
            "globalThis.scalajsCom = { init() {}, send() {}, close() {} }; process.stdout._handle?.setBlocking?.(true);"
        val path                = js.JSON.stringify(module)
        val (script, inputType) =
            if kind == "ESModule" then
                (
                    s"$stubCom const m = await import((await import('node:url')).pathToFileURL($path).href); m.kyoTestLeafWatchdogFixture();",
                    js.Array("--input-type=module")
                )
            else
                (
                    s"$stubCom const vm = require('node:vm'); vm.runInThisContext(require('node:fs').readFileSync($path, 'utf8'), { filename: $path }); vm.runInThisContext('kyoTestLeafWatchdogFixture()');",
                    js.Array[String]()
                )
        val args = process.execArgv.asInstanceOf[js.Array[String]].concat(inputType, js.Array("-e", script))
        val env  = js.Object.assign(
            js.Object(),
            process.env.asInstanceOf[js.Object],
            js.Dynamic.literal(KYO_TEST_RUNNER_WATCHDOGMARGINMS = "1000")
        )
        val child = process.getBuiltinModule("node:child_process").spawn(process.execPath, args, js.Dynamic.literal(env = env))
        val out   = new StringBuilder
        val err   = new StringBuilder
        val done  = Promise[ChildRun]()
        child.stdout.on("data", (chunk: js.Dynamic) => kyo.discard(out.append(chunk.toString())))
        child.stderr.on("data", (chunk: js.Dynamic) => kyo.discard(err.append(chunk.toString())))
        child.on(
            "close",
            (code: js.Any, signal: js.Any) => kyo.discard(done.success(ChildRun(out.toString, err.toString, s"code=$code signal=$signal")))
        )
        done.future
    end runFixture

    test("a leaf holding the event loop past its timeout is named with its stack and its process is ended") {
        runFixture().map { run =>
            val suite = classOf[LeafWatchdogStallSuite].getName
            assert(
                run.stdout.linesIterator.exists(l => l.contains("[TIMEOUT]") && l.contains("parks until its timeout")),
                s"${run.stdout}\n${run.stderr}"
            ): Unit
            val report = run.stderr.linesIterator.dropWhile(!_.startsWith("[kyo-test] watchdog: the event loop has been held")).toList
            val leaves = report.drop(1).takeWhile(!_.startsWith("[kyo-test] watchdog:"))
            val stack  =
                report.dropWhile(_ != "[kyo-test] watchdog: stack of the main thread:").drop(1).takeWhile(!_.startsWith("[kyo-test]"))
            assert(leaves == List(s"  $suite > holds the event loop (timeout 500 ms)"), run.stderr): Unit
            assert(stack.exists(_.contains("LeafWatchdogStallSuite$.spin")), run.stderr): Unit
            assert(!run.stdout.contains(LeafWatchdogFixture.finished), run.stdout): Unit
            assert(run.exit != "code=0 signal=null", run.exit)
        }
    }

end LeafWatchdogTest
