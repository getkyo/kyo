package kyo.internal.tasty.scala2

import kyo.*

/** ZLIB inflate for the Scala 2 pickle attribute.
  *
  * Delegates to `PortableInflate`, a pure-Scala RFC 1950 implementation that runs cross-platform. The JDK's
  * `java.util.zip.InflaterInputStream` (native ZLIB) is a few microseconds faster per call on JVM, but this code path is taken
  * once per Scala 2 class during cold classpath load and never on a hot loop; the simplification and the consistent error
  * taxonomy across platforms outweigh the negligible perf difference.
  *
  * `PortableInflate.InflateException` maps to `TastyError.MalformedSection` (the input bytes are structurally invalid).
  */
object InflateHook:
    def inflate(compressed: Array[Byte])(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
        Sync.Unsafe.defer {
            try kyo.internal.tasty.scala2.PortableInflate.inflate(compressed)
            catch
                case ex: kyo.internal.tasty.scala2.PortableInflate.InflateException =>
                    Abort.fail(TastyError.MalformedSection("Scala2Inflate", ex.getMessage, ex.byteOffset))
        }

    /** Unsafe-tier sibling of `inflate`. Runs the suspension synchronously under the caller's `AllowUnsafe` and surfaces failures as
      * `Result[TastyError, Array[Byte]]`. Used by inventory-site migrations that need to call inflate from a non-`< S` body.
      */
    def inflateUnsafe(compressed: Array[Byte])(using Frame, AllowUnsafe): Result[TastyError, Array[Byte]] =
        Sync.Unsafe.evalOrThrow(Abort.run(inflate(compressed)))
end InflateHook
