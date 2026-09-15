package kyo.internal

import scala.scalajs.js.typedarray.ArrayBuffer
import scala.scalajs.js.typedarray.DataView
import scala.scalajs.js.typedarray.Uint8Array

/** JS factory implementations for [[UnsafeBuffer]].
  *
  * Allocates via `ArrayBuffer`; memory is reclaimed by the JS garbage collector. `close` is effectively a no-op on JS (the closer passed is
  * `() => ()`) but the shared `closed` flag still flips so post-close access can be detected.
  */
private[kyo] object UnsafeBufferPlatform:

    def alloc(byteSize: Long): UnsafeBuffer =
        val ab  = new ArrayBuffer(byteSize.toInt)
        val u8a = new Uint8Array(ab)
        val dv  = new DataView(ab)
        new JsUnsafeBuffer(u8a, dv, byteSize, () => ())
    end alloc

    /** JS has no confined/shared distinction -- same as [[alloc]]. */
    def allocConfined(byteSize: Long): UnsafeBuffer = alloc(byteSize)

    def fromArray(arr: Array[Byte]): UnsafeBuffer =
        val ab  = new ArrayBuffer(arr.length)
        val u8a = new Uint8Array(ab)
        var i   = 0
        while i < arr.length do
            u8a(i) = arr(i)
            i += 1
        val dv = new DataView(ab)
        new JsUnsafeBuffer(u8a, dv, arr.length.toLong, () => ())
    end fromArray

    def fromUtf8(s: String): UnsafeBuffer =
        val bytes     = s.getBytes("UTF-8")
        val totalSize = bytes.length + 1 // NUL terminator
        val ab        = new ArrayBuffer(totalSize)
        val u8a       = new Uint8Array(ab)
        var i         = 0
        while i < bytes.length do
            u8a(i) = bytes(i)
            i += 1
        // NUL terminator already 0 from ArrayBuffer zero-initialization
        val dv = new DataView(ab)
        new JsUnsafeBuffer(u8a, dv, totalSize.toLong, () => ())
    end fromUtf8

    def mmapReadOnly(path: String, offset: Long, size: Long): UnsafeBuffer =
        throw new UnsupportedOperationException("mmap not available on JS")

    def mmapReadWrite(path: String, offset: Long, size: Long): UnsafeBuffer =
        throw new UnsupportedOperationException("mmap not available on JS")

    def wrapBorrowed(raw: AnyRef, byteSize: Long): UnsafeBuffer =
        raw match
            case u8a: Uint8Array =>
                val dv = new DataView(u8a.buffer, u8a.byteOffset, u8a.byteLength)
                new JsUnsafeBuffer(u8a, dv, byteSize, () => ())
            case _ =>
                throw new IllegalArgumentException(
                    s"wrapBorrowed expects a Uint8Array on JS, got: ${raw.getClass.getName}"
                )
    end wrapBorrowed

end UnsafeBufferPlatform
