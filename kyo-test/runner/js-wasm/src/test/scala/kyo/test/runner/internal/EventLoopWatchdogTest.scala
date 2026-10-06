package kyo.test.runner.internal

import kyo.Absent
import kyo.Duration
import kyo.Present
import kyo.discard
import kyo.millis
import org.scalajs.macrotaskexecutor.MacrotaskExecutor
import org.scalatest.Assertion
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.scalajs.js

// These leaves block the real event loop, which is the condition under test, so they run on the wall clock: no virtual clock can stand
// in for a loop that runs no timers.
class EventLoopWatchdogTest extends AsyncFunSuite with NonImplicitAssertions:

    implicit override def executionContext: ExecutionContext = MacrotaskExecutor

    private def blockEventLoop(millis: Double): Unit =
        val end = js.Date.now() + millis
        while js.Date.now() < end do ()

    private def after(millis: Double): Future[Unit] =
        val p = Promise[Unit]()
        discard(js.timers.setTimeout(millis)(p.success(())))
        p.future

    private def ready(watchdog: EventLoopWatchdog.Instance): Future[Unit] =
        val p = Promise[Unit]()
        watchdog.onReady(() => discard(p.trySuccess(())))
        p.future

    private def firstReport(watchdog: EventLoopWatchdog.Instance, kind: String): Future[String] =
        val p = Promise[String]()
        watchdog.onReport(text => if text.startsWith(s"[$kind]") then discard(p.trySuccess(text)))
        p.future

    private def watched(f: EventLoopWatchdog.Instance => Future[Assertion]): Future[Assertion] =
        val watchdog = EventLoopWatchdog.Instance.start(stopProcess = false, pollMillis = 50, killGraceMillis = 200)
        ready(watchdog).flatMap(_ => f(watchdog)).andThen(_ => watchdog.close())

    test("a leaf that blocks the event loop past its timeout is reported as TIMEOUT, with the blocking call on the stack") {
        watched { watchdog =>
            val report = firstReport(watchdog, "TIMEOUT")
            val id     = watchdog.leafStarted("Suite › blocks", Present(200.millis), Duration.Infinity)
            blockEventLoop(4000)
            watchdog.leafFinished(id)
            report.map { text =>
                assert(text.startsWith("[TIMEOUT] Suite › blocks  (limit: 0.2s) *** FAILED ***"), text)
                assert(text.contains("the event loop has been blocked for"), text)
                assert(text.contains("blockEventLoop"), text)
            }
        }
    }

    test("a leaf that blocks the event loop past its heartbeat interval is reported as STUCK") {
        watched { watchdog =>
            val report = firstReport(watchdog, "STUCK")
            val id     = watchdog.leafStarted("Suite › stalls", Absent, 300.millis)
            blockEventLoop(3000)
            watchdog.leafFinished(id)
            report.map { text =>
                assert(text.startsWith("[STUCK] Suite › stalls"), text)
                assert(text.contains("blockEventLoop"), text)
            }
        }
    }

    test("a leaf that outlives its timeout on a live event loop is left to its own timeout") {
        watched { watchdog =>
            var reports = List.empty[String]
            watchdog.onReport(text => reports = text :: reports)
            val id = watchdog.leafStarted("Suite › waits", Present(100.millis), 300.millis)
            after(1500).map { _ =>
                watchdog.leafFinished(id)
                assert(reports == Nil, reports.mkString("\n"))
            }
        }
    }

end EventLoopWatchdogTest
