package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Round-trip `Buffer[A]` exercises.
  *
  * `kyoItSumInts` is a read-only consumer: the C side reads `n` int32 elements and returns their int64 sum. `kyoItFillInts` is a write-only
  * producer: the C side writes `value` to every element. `kyoItCopyInts` exercises two `Buffer[Int]` parameters (dst + src) in a single
  * call.
  *
  * All buffers carry an explicit length because kyo-ffi's `Buffer[A]` does not transmit size across the FFI boundary, the Scala side
  * guarantees the C side only reads/writes `n` elements. Out-of-bounds access is undefined behavior.
  */
trait ItBuffersBindings extends Ffi:
    /** Sum of the first `n` int32 elements of `buf`, returned as int64 to avoid overflow on large inputs. */
    def kyoItSumInts(buf: Buffer[Int], n: Long)(using AllowUnsafe): Long

    /** Overwrite every element in the first `n` slots of `buf` with `value`. */
    def kyoItFillInts(buf: Buffer[Int], n: Long, value: Int)(using AllowUnsafe): Unit

    /** Copy the first `n` int32 elements from `src` into `dst`. Does not overlap-check. */
    def kyoItCopyInts(dst: Buffer[Int], src: Buffer[Int], n: Long)(using AllowUnsafe): Unit
end ItBuffersBindings

object ItBuffersBindings extends Ffi.Config(library = "kyo_it_bundled")

/** Borrowed-Buffer return surface.
  *
  * `kyoItMallocChunk` returns a borrowed `Buffer[Byte]` of `n` elements; the C side `kyo_it_malloc_chunk` allocates + fills + INTENTIONALLY
  * LEAKS the region so the borrowed buffer stays alive for the duration of the test assertion. Because the buffer is borrowed, calling
  * `close()` on it is a no-op, the caller must NOT free the memory. The size parameter is inferred from the single `Long` parameter `n`.
  */
trait ItBorrowedBindings extends Ffi:
    def kyoItMallocChunk(n: Long)(using AllowUnsafe): Ffi.Borrowed[Buffer[Byte]]
end ItBorrowedBindings

object ItBorrowedBindings extends Ffi.Config(library = "kyo_it_bundled")

/** Borrowed-String return surface.
  *
  * Exercises `Borrowed[String]` return, the C side returns a `const char*` pointing to static or leaked storage. The generator decodes the
  * pointer into a Scala `String` (copying the bytes), leaving the C-owned original intact. Edge cases tested: non-empty ASCII, NULL (maps
  * to Scala `null`), empty string, and multi-byte UTF-8.
  */
trait ItBorrowedStringBindings extends Ffi:
    /** Returns a static non-empty ASCII string ("hello from C"). */
    def kyoItBorrowedString()(using AllowUnsafe): Ffi.Borrowed[String]

    /** Returns NULL, should map to Scala `null`. */
    def kyoItBorrowedStringNull()(using AllowUnsafe): Ffi.Borrowed[String]

    /** Returns an empty NUL-terminated string (""). */
    def kyoItBorrowedStringEmpty()(using AllowUnsafe): Ffi.Borrowed[String]

    /** Returns a UTF-8 multi-byte string containing 2-, 3-, and 4-byte sequences. */
    def kyoItBorrowedStringUtf8()(using AllowUnsafe): Ffi.Borrowed[String]
end ItBorrowedStringBindings

object ItBorrowedStringBindings extends Ffi.Config(library = "kyo_it_bundled")
