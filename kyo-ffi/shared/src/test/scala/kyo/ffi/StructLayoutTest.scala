package kyo.ffi

import kyo.internal.UnsafeLayout

/** Cross-platform tests for [[StructLayout]], the `UnsafeLayout` derivation that makes a `Buffer[Struct]` of a flat FFI struct case class
  * allocatable and field-addressable.
  *
  * Covers: the derived size/alignment matching the codegen's natural-alignment model, the packed variant collapsing alignment to 1, and a
  * field round-trip through `Buffer.alloc` / `Buffer.get` / `Buffer.set` for a multi-element struct array.
  *
  * Runs identically on JVM, Native, and JS.
  */
class StructLayoutTest extends Test:

    // Mirrors kyo-net's EpollEvent (packed, 12 bytes) and KEvent (flat, 32 bytes) shapes without depending on kyo-net.
    final case class Ev(events: Int, data: Long) derives CanEqual
    final case class Kv(ident: Long, filter: Short, flags: Short, fflags: Int, data: Long, udata: Long) derives CanEqual
    final case class Ts(sec: Long, nsec: Long) derives CanEqual
    final case class Cqe(userData: Long, res: Int, flags: Int) derives CanEqual
    final case class Mixed(b: Byte, i: Int, d: Double, fl: Boolean) derives CanEqual

    "derived natural-alignment sizes match the codegen model" - {
        "Ev (Int + Long, naturally aligned) is 16 bytes" in {
            val l: UnsafeLayout[Ev] = StructLayout.derived[Ev]
            // Int at 0..3, 4 bytes padding to align the Long, Long at 8..15. Rounded to alignment 8 => 16.
            assert(l.size == 16)
            assert(l.alignment == 8)
        }
        "Kv (kevent shape) is 32 bytes" in {
            val l: UnsafeLayout[Kv] = StructLayout.derived[Kv]
            assert(l.size == 32)
            assert(l.alignment == 8)
        }
        "Ts (two Longs) is 16 bytes" in {
            assert(StructLayout.derived[Ts].size == 16)
        }
        "Cqe (Long + Int + Int) is 16 bytes" in {
            assert(StructLayout.derived[Cqe].size == 16)
        }
        "Mixed (Byte, Int, Double, Boolean) is 24 bytes" in {
            // Byte@0; pad to 4; Int@4..7; Double@8..15; Boolean(int32)@16..19; round to align 8 => 24.
            val l = StructLayout.derived[Mixed]
            assert(l.size == 24)
            assert(l.alignment == 8)
        }
    }

    "derivedPacked collapses alignment to 1" - {
        "Ev packed is 12 bytes (no padding between Int and Long)" in {
            val l = StructLayout.derivedPacked[Ev]
            assert(l.size == 12)
            assert(l.alignment == 1)
        }
        "Kv packed is 32 bytes (already contiguous)" in {
            assert(StructLayout.derivedPacked[Kv].size == 32)
        }
    }

    "field round-trip through a Buffer of structs" - {
        "naturally-aligned Ev array reads back what was written" in {
            given UnsafeLayout[Ev] = StructLayout.derived[Ev]
            val buf                = Buffer.alloc[Ev](3)
            try
                buf.set(0, Ev(1, 0x1122334455667788L))
                buf.set(1, Ev(0x004, 42L))
                buf.set(2, Ev(-1, Long.MaxValue))
                assert(buf.get(0) == Ev(1, 0x1122334455667788L))
                assert(buf.get(1) == Ev(0x004, 42L))
                assert(buf.get(2) == Ev(-1, Long.MaxValue))
            finally buf.close()
            end try
        }
        "packed Ev array reads back what was written" in {
            given UnsafeLayout[Ev] = StructLayout.derivedPacked[Ev]
            val buf                = Buffer.alloc[Ev](2)
            try
                buf.set(0, Ev(7, 700L))
                buf.set(1, Ev(8, 800L))
                assert(buf.byteSize == 24L) // 2 * 12
                assert(buf.get(0) == Ev(7, 700L))
                assert(buf.get(1) == Ev(8, 800L))
            finally buf.close()
            end try
        }
        "Kv array round-trips all six fields including the two Shorts" in {
            given UnsafeLayout[Kv] = StructLayout.derived[Kv]
            val buf                = Buffer.alloc[Kv](1)
            try
                val kv = Kv(ident = 5L, filter = -1, flags = 0x0001, fflags = 0, data = 99L, udata = 0L)
                buf.set(0, kv)
                assert(buf.get(0) == kv)
            finally buf.close()
            end try
        }
        "Mixed array round-trips Byte/Int/Double/Boolean" in {
            given UnsafeLayout[Mixed] = StructLayout.derived[Mixed]
            val buf                   = Buffer.alloc[Mixed](1)
            try
                val m = Mixed(b = -3, i = 12345, d = 2.5, fl = true)
                buf.set(0, m)
                assert(buf.get(0) == m)
            finally buf.close()
            end try
        }
    }
end StructLayoutTest
