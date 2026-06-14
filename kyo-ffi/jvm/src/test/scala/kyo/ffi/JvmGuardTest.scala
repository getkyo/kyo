package kyo.ffi

import java.util.concurrent.atomic.AtomicInteger
import kyo.ffi.internal.GuardCore

/** JVM-only tests for machinery that has no cross-platform analog: the leak-warning runnable registered with [[java.lang.ref.Cleaner]].
  * Cross-platform [[Ffi.Guard]] behavior, open/close lifecycle, registerBuffer, use bracket, and the process-wide
  * [[kyo.ffi.internal.GuardRegistry]], lives in the shared [[GuardTest]].
  */
class JvmGuardTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    "LeakWarning" - {
        "prints a warning when closed flag is false" in {
            val baos = new java.io.ByteArrayOutputStream()
            val ps   = new java.io.PrintStream(baos, true, "UTF-8")
            val orig = java.lang.System.err
            java.lang.System.setErr(ps)
            try
                val frame: kyo.Frame = summon[kyo.Frame]
                val state            = new AtomicInteger(GuardCore.StateOpen)
                new kyo.ffi.internal.JvmGuard.LeakWarning(frame, state).run()
            finally java.lang.System.setErr(orig)
            end try
            val out = baos.toString("UTF-8")
            assert(out.contains("[kyo-ffi] Ffi.Guard opened at "))
            assert(out.contains("garbage-collected without close()"))
        }

        "prints nothing when closed flag is true" in {
            val baos = new java.io.ByteArrayOutputStream()
            val ps   = new java.io.PrintStream(baos, true, "UTF-8")
            val orig = java.lang.System.err
            java.lang.System.setErr(ps)
            try
                val frame: kyo.Frame = summon[kyo.Frame]
                val state            = new AtomicInteger(GuardCore.StateClosed)
                new kyo.ffi.internal.JvmGuard.LeakWarning(frame, state).run()
            finally java.lang.System.setErr(orig)
            end try
            assert(baos.toString("UTF-8") == "")
        }
    }

    "adoptArena" - {
        "adopted arenas stay open until close() and then get closed" in {
            val g     = Ffi.Guard.open().asInstanceOf[kyo.ffi.internal.JvmGuard]
            val arena = java.lang.foreign.Arena.ofConfined().nn
            val seg   = arena.allocate(1024L).nn
            // Memory is writable before adoption.
            seg.set(java.lang.foreign.ValueLayout.JAVA_INT, 0L, 42)
            g.adoptArena(arena)
            assert(g.adoptedArenaCount == 1)
            // Memory is still readable AFTER adoption, adoption just transfers ownership, does not invalidate.
            assert(seg.get(java.lang.foreign.ValueLayout.JAVA_INT, 0L) == 42)
            g.close()
            // After close, the adopted arena is closed too, reading it should throw IllegalStateException.
            val ex = intercept[Exception](seg.get(java.lang.foreign.ValueLayout.JAVA_INT, 0L))
            assert(ex.getClass.getName.contains("IllegalStateException") || ex.getClass.getName.contains("WrongThread"))
        }

        "adopting into an already-closed guard closes the arena immediately (defensive)" in {
            val g = Ffi.Guard.open().asInstanceOf[kyo.ffi.internal.JvmGuard]
            g.close()
            val arena = java.lang.foreign.Arena.ofConfined().nn
            val seg   = arena.allocate(8L).nn
            seg.set(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L, 1: Byte)
            g.adoptArena(arena)
            // adoptArena saw `isClosed` and closed the arena itself.
            val ex = intercept[Exception](seg.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0L))
            assert(ex.getClass.getName.contains("IllegalStateException") || ex.getClass.getName.contains("WrongThread"))
        }
    }
end JvmGuardTest
