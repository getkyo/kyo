package kyo.ffi

import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kyo.discard
import kyo.ffi.internal.Scratch

class JvmScratchTest extends Test:

    // Mutates process-global state (System.setErr and/or a system property) and restores it, so the leaves must run
    // alone: under the default parallel leaf execution a sibling leaf observes the mutated global.
    override def config = super.config.sequential

    "mark/alloc/reset" - {
        "slices from the base arena" in {
            val s = Scratch.current
            val m = s.mark()
            try
                val seg = s.alloc(16L, 8L)
                assert(seg.byteSize == 16L)
                seg.set(ValueLayout.JAVA_INT, 0L, 42)
                assert(seg.get(ValueLayout.JAVA_INT, 0L) == 42)
            finally s.reset(m)
            end try
            succeed
        }

        "reset releases position" in {
            val s  = Scratch.current
            val m1 = s.mark()
            val _  = s.alloc(100L, 1L)
            val m2 = s.mark()
            assert(m2 > m1)
            s.reset(m1)
            val m3 = s.mark()
            assert(m3 == m1)
        }
    }

    "allocUtf8" - {
        "encodes + null-terminates" in {
            val s = Scratch.current
            val m = s.mark()
            try
                val seg = s.allocUtf8("hello")
                assert(seg.byteSize == 6L)
                (0 until 5).foreach(i => assert(seg.get(ValueLayout.JAVA_BYTE, i.toLong).toChar == "hello" (i)))
                assert(seg.get(ValueLayout.JAVA_BYTE, 5L) == (0: Byte))
            finally s.reset(m)
            end try
            succeed
        }
    }

    "spillover" - {
        "allocates fresh arena when scratch exhausted" in {
            val s = Scratch.current
            val m = s.mark()
            try
                val big = s.alloc(Scratch.configuredSize + 1024L, 8L)
                assert(big.byteSize == (Scratch.configuredSize + 1024L))
            finally s.reset(m)
            end try
            succeed
        }

        "logs the spill to stderr under -Dkyo.ffi.scratch.logSpills=true" in {
            // Capture stderr during the spill, set the system property first so `logSpills` reads `true`.
            val prev = sys.props.get("kyo.ffi.scratch.logSpills")
            sys.props.put("kyo.ffi.scratch.logSpills", "true")
            val prevErr = System.err
            val baos    = new java.io.ByteArrayOutputStream()
            val ps      = new java.io.PrintStream(baos)
            System.setErr(ps)
            try
                // Run on a fresh scratch so we don't collide with other tests' state.
                val s = new Scratch.Scratch(64L)
                val m = s.mark()
                try
                    discard(s.alloc(1024L, 8L, "kyo.example.MyBinding", "myMethod"))
                finally s.reset(m)
                end try
                ps.flush()
                val captured = baos.toString("UTF-8").nn
                assert(captured.contains("Scratch spill"))
                assert(captured.contains("kyo.example.MyBinding.myMethod"))
                assert(captured.contains("1024 bytes"))
            finally
                System.setErr(prevErr)
                prev match
                    case Some(v) => discard(sys.props.put("kyo.ffi.scratch.logSpills", v))
                    case None    => discard(sys.props.remove("kyo.ffi.scratch.logSpills"))
            end try
            succeed
        }

        "omits the spill log when -Dkyo.ffi.scratch.logSpills is unset (default-off)" in {
            val prev = sys.props.get("kyo.ffi.scratch.logSpills")
            sys.props.remove("kyo.ffi.scratch.logSpills")
            val prevErr = System.err
            val baos    = new java.io.ByteArrayOutputStream()
            val ps      = new java.io.PrintStream(baos)
            System.setErr(ps)
            try
                val s = new Scratch.Scratch(64L)
                val m = s.mark()
                try
                    discard(s.alloc(1024L, 8L, "kyo.example.MyBinding", "myMethod"))
                finally s.reset(m)
                end try
                ps.flush()
                val captured = baos.toString("UTF-8").nn
                assert(!captured.contains("Scratch spill"))
            finally
                System.setErr(prevErr)
                prev.foreach(v => sys.props.put("kyo.ffi.scratch.logSpills", v))
            end try
            succeed
        }
    }

    "currentFor" - {
        "delegates to current, returns the same instance regardless of binding" in {
            val a = Scratch.currentFor("kyo.example.BindingA", 4L * 1024L)
            val b = Scratch.currentFor("kyo.example.BindingB", 8L * 1024L)
            val c = Scratch.current
            assert(a eq c)
            assert(b eq c)
        }
    }

    "arenaCleaner" - {
        "ArenaCleanup swallows already-closed, safe after closeArena" in {
            // Confined arenas throw IllegalStateException on double-close.
            // The ArenaCleanup (used by the Cleaner) must swallow this so
            // thread-death cleanup is safe after auto-grow's closeArena().
            val arena   = java.lang.foreign.Arena.ofConfined().nn
            val cleanup = new Scratch.ArenaCleanup(arena)
            arena.close()
            // ArenaCleanup.run must not throw even though the arena is already closed.
            cleanup.run()
            succeed
        }
    }

    "takeSpills" - {
        "returns nothing when no spills occurred" in {
            val s = new Scratch.Scratch(64L * 1024L)
            assert(s.takeSpills() == Nil)
        }

        "returns spill arenas in allocation order and clears the spill list" in {
            // Capacity is 128 bytes; force two spills.
            val s = new Scratch.Scratch(128L)
            val m = s.mark()
            try
                val _     = s.alloc(1024L, 8L, "kyo.example.T", "first")
                val _     = s.alloc(2048L, 8L, "kyo.example.T", "second")
                val taken = s.takeSpills()
                assert(taken.size == 2)
                // takeSpills should not double-close, subsequent reset must not throw.
                s.reset(m)
            finally
                // Explicitly close the arenas we took ownership of.
                s.takeSpills().foreach(a =>
                    try a.close()
                    catch case _: Throwable => ()
                )
            end try
            succeed
        }
    }
end JvmScratchTest
