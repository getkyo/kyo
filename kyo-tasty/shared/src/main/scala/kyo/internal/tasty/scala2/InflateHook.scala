package kyo.internal.tasty.scala2

import kyo.*

/** Platform-specific ZLIB inflation hook for Scala 2 "Scala" attribute decoding.
  *
  * On JVM, delegates to java.util.zip.InflaterInputStream. On JS and Native, fails with TastyError.NotImplemented because ZLIB inflation is
  * not available without JVM or a bundled C library.
  *
  * Cross-platform structure: this abstract base lives in shared/; each platform provides a concrete
  * `object InflateHook extends InflateHookImpl`.
  */
abstract private[scala2] class InflateHookImpl:
    /** Decompress ZLIB-compressed bytes.
      *
      * Returns the decompressed bytes, or Abort.fail(TastyError.NotImplemented) on JS/Native.
      */
    def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError])
end InflateHookImpl
