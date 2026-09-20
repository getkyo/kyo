package kyo.internal

/** The JVM and Native half of [[kyo.Tag]]'s companion: how the fast path rejects two unequal encodings.
  *
  * Rejecting on the hash first pays for itself here because both platforms memoize `String.hashCode` in the string, so the comparison is
  * two field reads, while `String.equals` walks the contents. Tags reach sizes where that difference is the whole cost: `Tag[List[Aa]]`
  * for an empty local class encodes to 10,351 characters, because parent chains are embedded, and two tags of the same shape have the same
  * length and a long shared prefix. On JMH with JDK 25, a pair diverging 9,000 characters in rejects at 235.7M ops/s through the hash
  * against 0.671M through `equals` alone.
  *
  * On Native the hash also primes what `String.equals` itself consults: that implementation rejects on `cachedHashCode` before its
  * `memcmp`, but only when both hashes are already computed, and on the concrete-tag path nothing else computes them, since `=:=` never
  * reaches the subtype cache there.
  *
  * The `js-wasm` sibling inverts this, and carries why.
  */
private[kyo] trait TagPlatformSpecific:

    /** Whether two encoded tags are the same type, exactly.
      *
      * The result is exact, never a hash verdict: `=:=` treats a false from the fast path as final for a concrete tag, so a false negative
      * would report two identical types as different and a handler would stop matching its own effect.
      */
    private[kyo] def equalEncodings(a: String, b: String): Boolean =
        TagHash.of(a) == TagHash.of(b) && a.equals(b)

end TagPlatformSpecific
