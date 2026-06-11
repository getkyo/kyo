package kyo.ffi.internal

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kyo.Frame
import kyo.ffi.Ffi
import kyo.ffi.Test

/** Pins [[JvmLeakDetector]] behaviour: the deterministic `testForceLeak` path and the close-time cancellation of the pending warning. */
class JvmLeakDetectorTest extends Test:

    // Exercises the process-global leak detector and captures process stderr, so it must run alone: a parallel leaf
    // emitting its own leak warning would leak into this capture and break the emits-nothing assertion.
    override def config = super.config.sequential

    private def captureStderr[A](body: => A): (A, String) =
        val buf    = new ByteArrayOutputStream()
        val stream = new PrintStream(buf, true, "UTF-8")
        val prev   = java.lang.System.err
        java.lang.System.setErr(stream)
        try
            val a = body
            stream.flush()
            (a, new String(buf.toByteArray.nn, "UTF-8"))
        finally java.lang.System.setErr(prev)
        end try
    end captureStderr

    "open guard, testForceLeak emits stderr leakWarning" in {
        given frame: Frame = Frame.internal
        val (_, captured) = captureStderr {
            val g = Ffi.Guard.open()
            JvmLeakDetector.testForceLeak(g.asInstanceOf[JvmGuard])
        }
        assert(captured.contains(FfiErrors.leakWarning(frame.show)))
    }

    "open guard, close it, then testForceLeak emits nothing" in {
        given frame: Frame = Frame.internal
        val (_, captured) = captureStderr {
            val g = Ffi.Guard.open()
            discardOutcome(g.close())
            JvmLeakDetector.testForceLeak(g.asInstanceOf[JvmGuard])
        }
        assert(captured == "")
    }

    "testForceLeak is idempotent after the warning fired once" in {
        // After the first testForceLeak fires, the AtomicInteger remains at StateOpen, the warning Runnable itself is
        // state-aware and will emit again if invoked again. We assert both invocations produce output: this pins that
        // `testForceLeak` does not clear its own state (that is the Cleanable's job on legitimate close).
        given frame: Frame = Frame.internal
        val (_, captured) = captureStderr {
            val g = Ffi.Guard.open()
            JvmLeakDetector.testForceLeak(g.asInstanceOf[JvmGuard])
            JvmLeakDetector.testForceLeak(g.asInstanceOf[JvmGuard])
        }
        val count = countOccurrences(captured, FfiErrors.leakWarning(frame.show))
        assert(count == 2)
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

    // Avoid importing kyo.discard here, the guard close() outcome is not what we're asserting.
    private def discardOutcome(o: Ffi.CloseOutcome): Unit =
        val _ = o
end JvmLeakDetectorTest
