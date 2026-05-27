package kyo.internal.tasty.binary

/** Cross-platform UTF-8 decode interface.
  *
  * Concrete implementations live in platform-specific source directories:
  *   - jvm: uses java.nio.charset.StandardCharsets.UTF_8
  *   - js: uses the browser/Node.js TextDecoder API
  *   - native: uses java.nio.charset.StandardCharsets.UTF_8 (available in Scala Native 0.5+)
  *
  * Each platform provides `object Utf8 extends Utf8Impl` in its own source directory.
  */
abstract private[binary] class Utf8Impl:
    /** Decode `length` bytes from `bytes` starting at `offset` as UTF-8. */
    def decode(bytes: Array[Byte], offset: Int, length: Int): String
end Utf8Impl
