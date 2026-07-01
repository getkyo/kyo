package kyo.internal

import kyo.Span

/** Span overloads for the internal xxHash helpers.
  *
  * The base `XXHash` implementation lives in kyo-config and intentionally exposes only types available at that layer. These extensions live
  * in kyo-data because `Span` is defined here.
  *
  * The overloads preserve the same contract as `XXHash`'s array methods: seedless variants use seed `0`, seeded variants hash the full input,
  * and slice variants validate `offset` and `length` through the core array implementation.
  */
extension (xxHash: XXHash.type)

    /** Hashes all bytes in the span with XXH32 and seed `0`.
      */
    def hash32(bytes: Span[Byte]): Int =
        xxHash.hash32(bytes, 0)

    /** Hashes all bytes in the span with XXH32 and the supplied seed.
      */
    def hash32(bytes: Span[Byte], seed: Int): Int =
        xxHash.hash32(bytes, 0, bytes.size, seed)

    /** Hashes a span slice with XXH32.
      */
    def hash32(bytes: Span[Byte], offset: Int, length: Int, seed: Int): Int =
        xxHash.hash32(bytes.toArrayUnsafe, offset, length, seed)

    /** Hashes all bytes in the span with XXH64 and seed `0`.
      */
    def hash64(bytes: Span[Byte]): Long =
        xxHash.hash64(bytes, 0L)

    /** Hashes all bytes in the span with XXH64 and the supplied seed.
      */
    def hash64(bytes: Span[Byte], seed: Long): Long =
        xxHash.hash64(bytes, 0, bytes.size, seed)

    /** Hashes a span slice with XXH64.
      */
    def hash64(bytes: Span[Byte], offset: Int, length: Int, seed: Long): Long =
        xxHash.hash64(bytes.toArrayUnsafe, offset, length, seed)
end extension
