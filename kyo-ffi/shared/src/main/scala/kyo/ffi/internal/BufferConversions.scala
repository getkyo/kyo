package kyo.ffi.internal

import java.nio.charset.StandardCharsets
import kyo.ffi.Buffer
import kyo.internal.UnsafeLayout

/** Shared [[Buffer]] conversion helpers (`fromArray`, `copyToArray`, `fromUtf8`), parameterized over the platform's allocation function. */
private[ffi] object BufferConversions:

    /** Copy an on-heap [[scala.Array]] into a freshly allocated buffer using `alloc`. */
    def fromArray[A](a: Array[A], alloc: (Int, UnsafeLayout[A]) => Buffer[A], l: UnsafeLayout[A]): Buffer[A] =
        // Unsafe: bridges to Buffer.set which requires AllowUnsafe at the public boundary; this is an internal implementation.
        import kyo.AllowUnsafe.embrace.danger
        val buf = alloc(a.length, l)
        var i   = 0
        while i < a.length do
            buf.set(i, a(i))
            i += 1
        end while
        buf
    end fromArray

    /** Copy a range `[from, from + len)` of `b` into a freshly allocated on-heap [[scala.Array]]. */
    def copyToArray[A](b: Buffer[A], from: Int, len: Int)(using ct: kyo.ConcreteTag[A]): Array[A] =
        // Unsafe: bridges to Buffer accessors which require AllowUnsafe at the public boundary; this is an internal implementation.
        import kyo.AllowUnsafe.embrace.danger
        // Byte buffers take the bulk path: the generic loop below boxes every element through the
        // erased `get`, and Buffer[Byte] reads sit on the network receive paths.
        if ct.toClass eq java.lang.Byte.TYPE then
            val arr = new Array[Byte](len)
            b.asInstanceOf[Buffer[Byte]].copyToArray(arr, 0, from, len)
            arr.asInstanceOf[Array[A]]
        else
            val arr = kyo.ConcreteTag.newArray[A](len)
            var i   = 0
            while i < len do
                arr(i) = b.get(from + i)
                i += 1
            end while
            arr
        end if
    end copyToArray

    /** Encode `s` as UTF-8 and store it null-terminated in a fresh [[Buffer]] of [[Byte]] using `alloc`. */
    def fromUtf8(s: String, alloc: (Int, UnsafeLayout[Byte]) => Buffer[Byte], byteLayout: UnsafeLayout[Byte]): Buffer[Byte] =
        // Unsafe: bridges to Buffer.set which requires AllowUnsafe at the public boundary; this is an internal implementation.
        import kyo.AllowUnsafe.embrace.danger
        val bytes = s.getBytes(StandardCharsets.UTF_8)
        val buf   = alloc(bytes.length + 1, byteLayout)
        var i     = 0
        while i < bytes.length do
            buf.set(i, bytes(i))
            i += 1
        end while
        buf.set(bytes.length, 0: Byte)
        buf
    end fromUtf8
end BufferConversions
