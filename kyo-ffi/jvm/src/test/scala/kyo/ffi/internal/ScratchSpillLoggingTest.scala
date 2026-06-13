package kyo.ffi.internal

import kyo.*
import kyo.discard
import kyo.ffi.Test
import kyo.ffi.internal.Scratch

/** Verifies the `-Dkyo.ffi.scratch.logSpills=true` system-property path.
  *
  * A "spill" occurs when a scratch allocation request exceeds the current per-thread block capacity, causing [[Scratch]] to fall back to a
  * fresh confined arena. When `kyo.ffi.scratch.logSpills` is set to `"true"`, the spill is announced to stderr via
  * [[FfiErrors.scratchSpilled]].
  *
  * Note: spill-logging tests are also present in [[kyo.ffi.JvmScratchSpec]] under the "spillover" group. This spec focuses exclusively on
  * the system-property-driven logging path and provides a stable home for property-isolation testing should the two specs diverge.
  */
class ScratchSpillLoggingTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    private val propKey = "kyo.ffi.scratch.logSpills"

    // Each leaf mutates the `kyo.ffi.scratch.logSpills` system property; save it before the body and restore it after,
    // isolating leaves (the kyo-test equivalent of the old beforeAll/afterAll save-restore pair).
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Sync.defer {
            val savedProp = java.lang.System.getProperty(propKey)
            Scope.ensure {
                if savedProp == null then discard(java.lang.System.clearProperty(propKey))
                else discard(java.lang.System.setProperty(propKey, savedProp))
            }.andThen(body)
        }

    "kyo.ffi.scratch.logSpills system property" - {

        "spill path does not throw when logSpills is false (default)" in {
            discard(java.lang.System.clearProperty(propKey))
            // logSpills() must return false when the property is absent.
            assert(Scratch.logSpills == false)
            // Trigger a spill; must not throw.
            val s = new Scratch.Scratch(64L)
            val m = s.mark()
            try
                discard(s.alloc(1024L, 8L, "kyo.test.Binding", "testMethod"))
            finally s.reset(m)
            end try
            succeed
        }

        "spill path does not throw when logSpills is true" in {
            discard(java.lang.System.setProperty(propKey, "true"))
            // logSpills() must return true when the property is set.
            assert(Scratch.logSpills == true)
            // Redirect stderr so the diagnostic does not pollute the test output,
            // and to verify that the spill path runs to completion without exceptions.
            val prevErr = java.lang.System.err
            val baos    = new java.io.ByteArrayOutputStream()
            val ps      = new java.io.PrintStream(baos)
            java.lang.System.setErr(ps)
            try
                val s = new Scratch.Scratch(64L)
                val m = s.mark()
                try
                    discard(s.alloc(1024L, 8L, "kyo.test.Binding", "testMethod"))
                finally s.reset(m)
                end try
                ps.flush()
                val captured = baos.toString("UTF-8").nn
                // The log line must mention the spill and identify the binding + method.
                assert(captured.contains("Scratch spill"))
                assert(captured.contains("kyo.test.Binding"))
                assert(captured.contains("testMethod"))
            finally
                java.lang.System.setErr(prevErr)
                discard(java.lang.System.clearProperty(propKey))
            end try
            succeed
        }

        "logSpills is case-insensitive (TRUE and True are treated as true)" in {
            discard(java.lang.System.setProperty(propKey, "TRUE"))
            assert(Scratch.logSpills == true)
            discard(java.lang.System.setProperty(propKey, "True"))
            assert(Scratch.logSpills == true)
            discard(java.lang.System.clearProperty(propKey))
            succeed
        }

        "logSpills returns false for any non-true value" in {
            discard(java.lang.System.setProperty(propKey, "false"))
            assert(Scratch.logSpills == false)
            discard(java.lang.System.setProperty(propKey, "yes"))
            assert(Scratch.logSpills == false)
            discard(java.lang.System.setProperty(propKey, "1"))
            assert(Scratch.logSpills == false)
            discard(java.lang.System.clearProperty(propKey))
            succeed
        }
    }
end ScratchSpillLoggingTest
