package kyo.internal.tasty.binary

import java.nio.charset.StandardCharsets

/** JVM platform implementation of UTF-8 decode.
  *
  * Delegates directly to the JVM runtime String constructor with the UTF_8 charset, which handles all Unicode code points including 4-byte
  * sequences (as surrogate pairs).
  */
object Utf8 extends Utf8Impl:
    def decode(bytes: Array[Byte], offset: Int, length: Int): String =
        new String(bytes, offset, length, StandardCharsets.UTF_8)
end Utf8
