package kyo.internal

private[kyo] object XXHashPlatform {

    /** JLS string hash computed without calling `String.hashCode`.
      *
      * Scala Native's `String.hashCode` memoizes by storing into `cachedHashCode` whenever that field reads 0 and the string is non-empty.
      * A non-empty string whose JLS hash is 0 (for example "\u0000") therefore recomputes and re-stores on every call, and when the string
      * is a literal interned in the binary's read-only data section that store faults (SIGBUS on macOS, SIGSEGV on Linux with read-only
      * relocations). Computing the hash here performs no store, at the cost of an O(length) walk per call on this platform.
      */
    def stringHash(s: String): Int = {
        var hash = 0
        var i    = 0
        val len  = s.length
        while (i < len) {
            hash = hash * 31 + s.charAt(i).toInt
            i += 1
        }
        hash
    }
}
