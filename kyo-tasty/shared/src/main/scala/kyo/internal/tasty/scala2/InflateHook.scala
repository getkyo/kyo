package kyo.internal.tasty.scala2

import kyo.*

/** Platform-specific ZLIB inflation hook for Scala 2 "Scala" attribute decoding.
  *
  * On JVM and Native, delegates to java.util.zip.InflaterInputStream (available in scala-native javalib). On JS, delegates to
  * PortableInflate, the pure-Scala RFC 1950 inflate. All three platforms are now functional.
  *
  * Cross-platform structure: this abstract base lives in shared/; each platform provides a concrete
  * `object InflateHook extends InflateHookImpl`.
  */
abstract private[scala2] class InflateHookImpl:
    /** Decompress ZLIB-compressed bytes.
      *
      * Returns the decompressed bytes on all platforms (JVM, Native, JS).
      */
    def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError])
end InflateHookImpl
