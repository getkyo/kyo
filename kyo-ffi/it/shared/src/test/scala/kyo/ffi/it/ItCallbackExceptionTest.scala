package kyo.ffi.it

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import kyo.AllowUnsafe
import kyo.discard
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Cross-platform callback exception contract.
  *
  * Every platform (JVM Panama, Scala Native CFuncPtr trampolines, Scala.js koffi) wraps the user callback in a try/catch. On exception the
  * runtime reports via `FfiErrors.reportCallbackFailed` and returns a typed-zero default to C, user exceptions MUST NOT propagate into the
  * C call stack. These tests capture stderr and assert the diagnostic is present; they also assert the FFI call completes rather than
  * crashing the process.
  */
class ItCallbackExceptionTest extends ItTestBase:

    // Touches process-global state (global stderr/system property, or the shared CallbackRegistry pool/hooks) and so
    // must run alone: under the default parallel leaf execution a sibling leaf observes or mutates the same global.
    override def config = super.config.sequential

    import AllowUnsafe.embrace.danger

    /** Run [[body]] with `System.err` redirected to a buffer; return the captured stderr as a String. */
    private def captureStderr(body: => Any): String =
        val baos     = new ByteArrayOutputStream()
        val captured = new PrintStream(baos)
        val original = java.lang.System.err
        java.lang.System.setErr(captured)
        try
            discard(body)
            ()
        finally java.lang.System.setErr(original)
        end try
        captured.flush()
        new String(baos.toByteArray(), "UTF-8")
    end captureStderr

    "transient callback: throwing comparator does not crash the process, diagnostic logged, call completes" in {
        val b = Ffi.load[ItCallbacksBindings]
        val stderr = captureStderr {
            Buffer.use[Int, Unit](3) { buf =>
                buf.set(0, 3)
                buf.set(1, 1)
                buf.set(2, 2)
                // qsort's comparator will fire multiple times. Throwing on every call forces the
                // runtime's per-platform catch handler to absorb the exception and return 0, the
                // resulting sort order is undefined but the process MUST NOT crash.
                b.kyoItSortInts(buf, 3, (_, _) => throw new RuntimeException("boom from cmp"))
                succeed
            }
        }
        // Diagnostic wording is shared across platforms via `FfiErrors.callbackFailed`.
        assert(stderr.contains("callback failed in 'kyo.ffi.it.ItCallbacksBindings.kyoItSortInts'"))
        assert(stderr.contains("transient"))
        assert(stderr.contains("boom from cmp"))
    }

    "retained callback: throwing listener does not crash the process, diagnostic logged, fire returns" in {
        val b = Ffi.load[ItCallbacksBindings]
        val stderr = captureStderr {
            Ffi.Guard.use[Unit] { g =>
                b.kyoItRegisterListener((_: Int) => throw new IllegalStateException("boom from listener"), g)
                b.kyoItFireListener(7)
                succeed
            }
        }
        assert(stderr.contains("callback failed in 'kyo.ffi.it.ItCallbacksBindings.kyoItRegisterListener'"))
        assert(stderr.contains("retained"))
        assert(stderr.contains("boom from listener"))
    }

end ItCallbackExceptionTest
