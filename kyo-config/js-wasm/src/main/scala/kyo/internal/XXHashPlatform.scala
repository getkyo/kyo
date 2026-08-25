package kyo.internal

private[kyo] object XXHashPlatform {

    /** JLS string hash via `String.hashCode`: memoized per instance by the runtime, so hashing a reused string is constant-time and
      * allocation-free.
      */
    def stringHash(s: String): Int = s.hashCode
}
