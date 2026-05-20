package kyo.internal

import java.nio.charset.StandardCharsets

/** Scala.js implementation of ASCII bytes → String. Uses the standard String constructor with US_ASCII charset (copies the bytes; no
  * zero-copy path available on Scala.js).
  */
private[internal] object AsciiStringFactory:

    /** Construct a String from a byte array known to contain only ASCII bytes. */
    def fromAsciiBytes(bytes: Array[Byte]): String =
        new String(bytes, StandardCharsets.US_ASCII)
end AsciiStringFactory
