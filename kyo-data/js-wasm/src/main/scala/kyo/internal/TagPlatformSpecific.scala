package kyo.internal

/** The JS and Wasm half of [[kyo.Tag]]'s companion: how the fast path rejects two unequal encodings.
  *
  * `String.equals` compiles to `===` on a primitive string, and two distinct internalized strings reject on identity without reading their
  * contents, so the compare does not grow with the encoding: a 26,459 character pair costs what a 73 character pair costs. There is
  * nothing left for a hash to reject more cheaply.
  *
  * Asking for one costs instead. Nothing memoizes `String.hashCode` here, so [[TagHash]] answers from a map keyed by the very string being
  * compared, and probing it twice adds about 29ns per comparison: under Node, one million comparisons take 49.2ms through `equals` alone
  * against 83.8ms through the hash first.
  *
  * The `jvm-native` sibling inverts this, and carries why.
  */
private[kyo] trait TagPlatformSpecific:

    /** Whether two encoded tags are the same type, exactly.
      *
      * The result is exact, never a hash verdict: `=:=` treats a false from the fast path as final for a concrete tag, so a false negative
      * would report two identical types as different and a handler would stop matching its own effect.
      */
    private[kyo] def equalEncodings(a: String, b: String): Boolean =
        a.equals(b)

end TagPlatformSpecific
