package kyo.internal

object XXHash:
    def hash32(s: String): Int = scala.util.hashing.MurmurHash3.stringHash(s)
