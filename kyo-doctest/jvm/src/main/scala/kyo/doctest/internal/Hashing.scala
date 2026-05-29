package kyo.doctest.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Hashing utilities shared across kyo-doctest internals.
  *
  * Shared between `WrappedBlock` and `CompileUnit` so both call sites use the same implementation.
  */
private[internal] object Hashing:

    /** Returns the first 8 hex characters of the SHA-256 of the input string.
      *
      * Used to derive short, deterministic identifiers from file paths (e.g. synthetic source names, object names) without pulling in the
      * full 64-character digest.
      */
    def sha256First8(input: String): String =
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(input.getBytes(StandardCharsets.UTF_8))
        bytes.take(4).map(b => f"${b & 0xff}%02x").mkString
    end sha256First8

end Hashing
