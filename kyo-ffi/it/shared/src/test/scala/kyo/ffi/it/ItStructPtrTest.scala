package kyo.ffi.it

import java.util.concurrent.atomic.AtomicInteger
import kyo.AllowUnsafe
import kyo.ffi.Ffi

/** Cross-platform struct pointer field spec.
  *
  * Exercises three pointer-typed struct fields:
  *   - Opaque handle field (`ItHandle`), round-trips through C via a `void*` struct member.
  *   - String field (`label: String`), round-trips through C via a `const char*` struct member.
  *   - Function pointer field (`callback: Int => Unit`), written as a function pointer, C invokes it.
  *
  * Each test creates a `TaggedResource` with known values, passes it to a C function that reads the relevant field, and verifies the
  * result.
  */
class ItStructPtrTest extends ItTestBase:

    import AllowUnsafe.embrace.danger

    "kyo_it_struct_read_handle" - {
        "struct with opaque field: C reads handle and returns stored int value" in {
            val opaque = Ffi.load[ItOpaqueBindings]
            val b      = Ffi.load[ItStructPtrBindings]
            val h      = opaque.kyo_it_create_handle(42)
            try
                val res = TaggedResource(h, "unused", (_: Int) => ())
                assert(b.kyo_it_struct_read_handle(res) == 42)
            finally opaque.kyo_it_destroy_handle(h)
            end try
        }

        "table-driven: varied handle values round-trip through struct" in {
            val opaque     = Ffi.load[ItOpaqueBindings]
            val b          = Ffi.load[ItStructPtrBindings]
            val values     = Seq(0, 1, -1, 100, 12345, Int.MaxValue, Int.MinValue)
            var last: Unit = succeed
            values.foreach { v =>
                val h = opaque.kyo_it_create_handle(v)
                try
                    val res = TaggedResource(h, "", (_: Int) => ())
                    last = assert(b.kyo_it_struct_read_handle(res) == v)
                finally opaque.kyo_it_destroy_handle(h)
                end try
            }
            last
        }
    }

    "kyo_it_struct_label_len" - {
        "struct with string field: C reads label and returns its length" in {
            val opaque = Ffi.load[ItOpaqueBindings]
            val b      = Ffi.load[ItStructPtrBindings]
            val h      = opaque.kyo_it_create_handle(0)
            try
                val res = TaggedResource(h, "hello", (_: Int) => ())
                assert(b.kyo_it_struct_label_len(res) == 5)
            finally opaque.kyo_it_destroy_handle(h)
            end try
        }

        "table-driven: varied labels" in {
            val opaque = Ffi.load[ItOpaqueBindings]
            val b      = Ffi.load[ItStructPtrBindings]
            val h      = opaque.kyo_it_create_handle(0)
            try
                val cases = Seq(
                    ""                         -> 0,
                    "a"                        -> 1,
                    "ab"                       -> 2,
                    "hello"                    -> 5,
                    "struct"                   -> 6,
                    "kyo ffi integration test" -> 24
                )
                var last: Unit = succeed
                cases.foreach { case (label, expectedLen) =>
                    val res = TaggedResource(h, label, (_: Int) => ())
                    last = assert(b.kyo_it_struct_label_len(res) == expectedLen)
                }
                last
            finally opaque.kyo_it_destroy_handle(h)
            end try
        }
    }

    "kyo_it_struct_fire_callback" - {
        "struct with callback field: C invokes callback with given value" in {
            val opaque   = Ffi.load[ItOpaqueBindings]
            val b        = Ffi.load[ItStructPtrBindings]
            val h        = opaque.kyo_it_create_handle(0)
            val observed = new AtomicInteger(-1)
            try
                val res = TaggedResource(h, "", (x: Int) => observed.set(x))
                b.kyo_it_struct_fire_callback(res, 42)
                assert(observed.get() == 42)
            finally opaque.kyo_it_destroy_handle(h)
            end try
        }

        "table-driven: varied callback values" in {
            val opaque   = Ffi.load[ItOpaqueBindings]
            val b        = Ffi.load[ItStructPtrBindings]
            val h        = opaque.kyo_it_create_handle(0)
            val observed = new AtomicInteger(0)
            try
                val fireValues = Seq(0, 1, -1, 42, 100, -500, 12345, 7)
                var last: Unit = succeed
                fireValues.foreach { v =>
                    val res = TaggedResource(h, "", (x: Int) => observed.set(x))
                    b.kyo_it_struct_fire_callback(res, v)
                    last = assert(observed.get() == v)
                }
                last
            finally opaque.kyo_it_destroy_handle(h)
            end try
        }
    }
end ItStructPtrTest
