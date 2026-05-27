package kyo.internal.tasty.binary

import java.nio.charset.StandardCharsets

/** Scala Native platform implementation of UTF-8 decode.
  *
  * Uses the JDK charset API which is available in Scala Native 0.5+ with JDK charset support. This is identical to the JVM implementation;
  * both delegate to the String constructor.
  */
object Utf8 extends Utf8Impl:
    def decode(bytes: Array[Byte], offset: Int, length: Int): String =
        new String(bytes, offset, length, StandardCharsets.UTF_8)
end Utf8
