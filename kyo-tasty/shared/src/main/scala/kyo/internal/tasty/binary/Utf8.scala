package kyo.internal.tasty.binary

import java.nio.charset.StandardCharsets

/** UTF-8 decode for `Array[Byte]` slices.
  *
  * All platforms expose the JDK String constructor with the UTF_8 charset: JVM uses the JDK runtime, Scala Native uses its
  * javalib charset support, and Scala.js implements the same constructor in its javalib. There is no need for a platform split.
  *
  * Note on 4-byte sequences: JVM and Native produce a UTF-16 surrogate pair (`String.length == 2`); Scala.js' javalib follows
  * the same UTF-16 representation, so all three platforms agree on the character count.
  */
object Utf8:
    def decode(bytes: Array[Byte], offset: Int, length: Int): String =
        new String(bytes, offset, length, StandardCharsets.UTF_8)
end Utf8
