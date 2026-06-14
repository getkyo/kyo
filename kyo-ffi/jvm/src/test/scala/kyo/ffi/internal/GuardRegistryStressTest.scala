package kyo.ffi.internal

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kyo.Frame
import kyo.discard
import kyo.ffi.Ffi
import kyo.ffi.Test

/** Pins two [[GuardRegistry]] invariants under contention: (1) 64 threads each doing 32 open/close cycles leave the registry balanced, and
  * (2) 16 unclosed guards each forced through [[JvmLeakDetector.testForceLeak]] produce 16 stderr leak-warning lines.
  */
class GuardRegistryStressTest extends Test:

    // The leaves read a global GuardRegistry size baseline then assert an exact delta; run them sequentially so
    // concurrent leaves do not perturb the shared registry between the baseline read and the assertion.
    override def config = super.config.sequential

    "concurrent open + close from 64 threads leaves size 0" in {
        val threadCount  = 64
        val opsPerThread = 32
        val before       = GuardRegistry.size
        val start        = new CountDownLatch(1)
        val done         = new CountDownLatch(threadCount)
        val errors       = new AtomicReference[Throwable](null)
        val threads      = new Array[Thread](threadCount)

        given frame: Frame = Frame.internal

        var t = 0
        while t < threadCount do
            val th = new Thread(
                () =>
                    try
                        start.await(5, TimeUnit.SECONDS)
                        var i = 0
                        while i < opsPerThread do
                            val g = Ffi.Guard.open()
                            g.close()
                            i += 1
                        end while
                    catch
                        case e: Throwable => discard(errors.compareAndSet(null, e))
                    finally done.countDown(),
                s"guard-stress-$t"
            )
            th.setDaemon(true)
            threads(t) = th
            th.start()
            t += 1
        end while

        start.countDown()
        assert(done.await(30, TimeUnit.SECONDS) == true)

        var j = 0
        while j < threadCount do
            threads(j).join(5000)
            j += 1
        end while

        val err = errors.get()
        if err != null then fail(s"worker thread threw: $err")

        // GuardRegistry on JVM is weakly keyed, pre-existing entries (from other tests leaving leaked guards around)
        // may be collected between `before` and here, producing a NEGATIVE delta. What we must pin is that this test's
        // own opens were balanced by its own closes: the delta is never positive.
        assert((GuardRegistry.size - before) <= 0)
    }

    "concurrent opens without close seed leak detection" in {
        // We cannot rely on real GC to reliably clear WeakReferences within a test budget, so we open N guards,
        // keep refs around so we can call `testForceLeak` on each (the deterministic leak path), and assert
        // exactly N stderr leak-warning lines. The real GC path is covered (and intentionally ignored) in
        // JvmLeakDetectorTest.
        val guardCount = 16

        given frame: Frame = Frame.internal
        val expected       = FfiErrors.leakWarning(frame.show)

        val buf    = new ByteArrayOutputStream()
        val stream = new PrintStream(buf, true, "UTF-8")
        val prev   = java.lang.System.err
        java.lang.System.setErr(stream)
        try
            val guards = new Array[JvmGuard](guardCount)
            var i      = 0
            while i < guardCount do
                guards(i) = Ffi.Guard.open().asInstanceOf[JvmGuard]
                i += 1
            end while
            i = 0
            while i < guardCount do
                JvmLeakDetector.testForceLeak(guards(i))
                i += 1
            end while
            stream.flush()
        finally java.lang.System.setErr(prev)
        end try

        val captured = new String(buf.toByteArray.nn, "UTF-8")
        val count    = countOccurrences(captured, expected)
        assert(count == guardCount)
    }

    private def countOccurrences(haystack: String, needle: String): Int =
        if needle.isEmpty then 0
        else
            var idx   = 0
            var count = 0
            while { idx = haystack.indexOf(needle, idx); idx >= 0 } do
                count += 1
                idx += needle.length
            end while
            count
        end if
    end countOccurrences
end GuardRegistryStressTest
