package kyo.ffi.internal

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kyo.discard
import kyo.ffi.Test

/** Pins the per-thread isolation underlying errno capture on JVM.
  *
  * Panama captures errno into a segment allocated from the per-thread [[Scratch]]; each thread therefore owns an independent capture site
  * backing [[kyo.ffi.Ffi.Outcome]]. This test pins that invariant by asserting two threads obtain distinct [[Scratch]] instances, a process-global
  * regression would collapse them to one and race the capture. Upgrade to direct errno assertions when a public test helper surfaces the
  * captured value.
  */
class ErrnoCaptureIsolationTest extends Test:

    "two threads observe independent per-thread errno-capture storage" in {
        // placeholder, tests assume errno capture is ThreadLocal; upgrade when a test helper surfaces the value.
        val ready   = new CountDownLatch(2)
        val release = new CountDownLatch(1)
        val done    = new CountDownLatch(2)
        val refA    = new AtomicReference[Scratch.Scratch](null)
        val refB    = new AtomicReference[Scratch.Scratch](null)
        val errors  = new AtomicReference[Throwable](null)

        def worker(ref: AtomicReference[Scratch.Scratch], name: String): Thread =
            val r: Runnable = new Runnable:
                def run(): Unit =
                    try
                        val s = Scratch.current
                        ref.set(s)
                        ready.countDown()
                        discard(release.await(5, TimeUnit.SECONDS))
                        // Read again after the rendezvous to ensure the ThreadLocal is stable per thread.
                        if !(Scratch.current eq s) then throw new AssertionError("Scratch.current is not stable per thread")
                    catch
                        case th: Throwable => discard(errors.compareAndSet(null, th))
                    finally done.countDown()
                    end try
                end run
            val t = new Thread(r, name)
            t.setDaemon(true)
            t
        end worker

        val tA = worker(refA, "errno-iso-a")
        val tB = worker(refB, "errno-iso-b")
        tA.start()
        tB.start()

        assert(ready.await(5, TimeUnit.SECONDS) == true)
        release.countDown()
        assert(done.await(10, TimeUnit.SECONDS) == true)
        tA.join(5000)
        tB.join(5000)

        val err = errors.get()
        if err != null then fail(s"worker thread threw: $err")

        val a = refA.get()
        val b = refB.get()
        assert(a != null)
        assert(b != null)
        assert((a eq b) == false)
    }
end ErrnoCaptureIsolationTest
