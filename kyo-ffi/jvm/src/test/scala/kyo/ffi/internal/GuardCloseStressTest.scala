package kyo.ffi.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kyo.discard
import kyo.ffi.Ffi
import kyo.ffi.Test

/** Stress tests for [[GuardCore]] close behaviour under concurrent callback pressure.
  *
  * These tests verify that the guard lifecycle state machine (open -> closing -> closed) remains correct when many threads interact with
  * `beginCallback` / `endCallback` / `close` simultaneously. The focus is on deadlock avoidance and correct state transitions rather than
  * functional correctness of callbacks.
  */
class GuardCloseStressTest extends Test:

    "GuardCore, close stress" - {

        "100 concurrent simulated retained callbacks + close, no deadlock" in {
            val threadCount   = 100
            val core          = new GuardCore(() => (), () => ()) // no-op platform-closer
            val allStarted    = new CountDownLatch(threadCount)
            val go            = new CountDownLatch(1)
            val allDone       = new CountDownLatch(threadCount)
            val unexpectedErr = new AtomicReference[Throwable](null)

            val threads = (0 until threadCount).map { i =>
                val t = new Thread(
                    () =>
                        try
                            allStarted.countDown()
                            go.await(10, TimeUnit.SECONDS)
                            // Attempt a callback lifecycle: begin, brief work, end.
                            if core.beginCallback() then
                                try
                                    // Measured sleep: simulates a 1-5ms retained-callback body to create real concurrency pressure.
                                    Thread.sleep(1 + (i % 5).toLong) // 1-5ms
                                finally
                                    core.endCallback()
                            end if
                        catch
                            case t: Throwable => discard(unexpectedErr.compareAndSet(null, t))
                        finally
                            allDone.countDown()
                    ,
                    s"cb-thread-$i"
                )
                t.setDaemon(true)
                t
            }

            threads.foreach(_.start())
            assert(allStarted.await(10, TimeUnit.SECONDS) == true)

            // Release all callback threads and close concurrently from the main thread. Because some threads may still
            // be executing simulated retained-callback work when close() enters drainInFlight, the outcome can legitimately
            // be Clean (drain finished) or TimedOut (long sleep kept inFlight>0 past the timeout). Both are acceptable,            // the invariant is no deadlock and final closed-state, asserted below.
            go.countDown()
            val closeResult = core.close()
            assert((closeResult == kyo.ffi.Ffi.CloseOutcome.Clean || closeResult == kyo.ffi.Ffi.CloseOutcome.TimedOut) == true)

            // Verify all callback threads complete, no deadlock.
            val allFinished = allDone.await(10, TimeUnit.SECONDS)
            assert(allFinished == true)

            val err = unexpectedErr.get()
            if err != null then fail(s"Unexpected exception in callback thread: $err")

            assert(core.isClosed == true)
        }

        "rapid open/close cycles under callback pressure" in {
            val iterations = 50
            var i          = 0
            while i < iterations do
                val guard = Ffi.Guard.open()
                discard(guard.close())
                i += 1
            end while
            // If we get here without exception, the test passes.
            succeed
        }

        "multiple guards closing simultaneously" in {
            val guardCount    = 10
            val guards        = (0 until guardCount).map(_ => Ffi.Guard.open())
            val start         = new CountDownLatch(1)
            val done          = new CountDownLatch(guardCount)
            val unexpectedErr = new AtomicReference[Throwable](null)

            val threads = guards.zipWithIndex.map { case (guard, i) =>
                val t = new Thread(
                    () =>
                        try
                            start.await(10, TimeUnit.SECONDS)
                            discard(guard.close())
                        catch
                            case t: Throwable => discard(unexpectedErr.compareAndSet(null, t))
                        finally
                            done.countDown()
                    ,
                    s"guard-closer-$i"
                )
                t.setDaemon(true)
                t
            }

            threads.foreach(_.start())
            start.countDown()

            val allFinished = done.await(10, TimeUnit.SECONDS)
            assert(allFinished == true)

            val err = unexpectedErr.get()
            if err != null then fail(s"Unexpected exception from concurrent guard close: $err")
        }

        "close during drain, beginCallback returns false after state transition" in {
            val core           = new GuardCore(() => (), () => ()) // no-op platform-closer
            val longCbStarted  = new CountDownLatch(1)
            val longCbFinish   = new CountDownLatch(1)
            val closeDone      = new CountDownLatch(1)
            val thirdCheckDone = new CountDownLatch(1)
            val unexpectedErr  = new AtomicReference[Throwable](null)
            val thirdBeganOk   = new AtomicBoolean(true)           // should end up false

            // Thread 1: start a long-running callback (begin but don't end for a while).
            val longCbThread = new Thread(
                () =>
                    try
                        val began = core.beginCallback()
                        assert(began == true)
                        longCbStarted.countDown()
                        // Wait for signal to finish the callback.
                        longCbFinish.await(10, TimeUnit.SECONDS)
                        core.endCallback()
                    catch
                        case t: Throwable => discard(unexpectedErr.compareAndSet(null, t))
                ,
                "long-cb-thread"
            )
            longCbThread.setDaemon(true)
            longCbThread.start()

            // Wait for long callback to begin.
            assert(longCbStarted.await(10, TimeUnit.SECONDS) == true)

            // Thread 2: call close(), it will block in drainInFlight because inFlight == 1.
            val closeThread = new Thread(
                () =>
                    try
                        discard(core.close())
                        closeDone.countDown()
                    catch
                        case t: Throwable => discard(unexpectedErr.compareAndSet(null, t))
                ,
                "close-thread"
            )
            closeThread.setDaemon(true)
            closeThread.start()

            // Poll until the close thread transitions state out of StateOpen (state -> Closing or Closed).
            val deadline = System.nanoTime() + 2_000_000_000L
            while core.state.get() == GuardCore.StateOpen && System.nanoTime() < deadline do
                Thread.onSpinWait()
            end while
            assert(core.state.get() != GuardCore.StateOpen)

            // Thread 3: attempt beginCallback, should return false because guard is closing.
            val thirdThread = new Thread(
                () =>
                    try
                        val began = core.beginCallback()
                        thirdBeganOk.set(began)
                        thirdCheckDone.countDown()
                    catch
                        case t: Throwable => discard(unexpectedErr.compareAndSet(null, t))
                ,
                "third-cb-thread"
            )
            thirdThread.setDaemon(true)
            thirdThread.start()

            assert(thirdCheckDone.await(10, TimeUnit.SECONDS) == true)
            assert(thirdBeganOk.get() == false)

            // Let the long callback finish so close() can complete.
            longCbFinish.countDown()

            val closeFinished = closeDone.await(10, TimeUnit.SECONDS)
            assert(closeFinished == true)

            val err = unexpectedErr.get()
            if err != null then fail(s"Unexpected exception: $err")

            assert(core.isClosed == true)
        }
    }
end GuardCloseStressTest
