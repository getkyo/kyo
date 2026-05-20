package kyo.internal

import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/** JVM implementation of zero-copy ASCII bytes → String via the non-public `String(byte[], byte)` constructor (LATIN1 coder). Shares the
  * byte[] directly without copying.
  */
private[internal] object AsciiStringFactory:

    private val newStringHandle: MethodHandle =
        val lookup = MethodHandles.privateLookupIn(classOf[String], MethodHandles.lookup())
        lookup.findConstructor(
            classOf[String],
            MethodType.methodType(classOf[Unit], classOf[Array[Byte]], classOf[Byte])
        )
    end newStringHandle

    /** Construct a String from a byte array known to contain only ASCII bytes. The returned String shares the underlying byte[]
      * (zero-copy).
      */
    def fromAsciiBytes(bytes: Array[Byte]): String =
        newStringHandle.invoke(bytes, 0.toByte).asInstanceOf[String]
end AsciiStringFactory
