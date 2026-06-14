package kyo.internal

import kyo.AllowUnsafe
import scala.scalajs.js.typedarray.DataView
import scala.scalajs.js.typedarray.Uint8Array

/** JS implementation of [[UnsafeBuffer]] backed by a `Uint8Array` and `DataView`.
  *
  * Uses `DataView` for typed get/set and `Uint8Array.set` for bulk JS-to-JS copies. `Long` is emulated via two `Int32` halves since
  * Scala.js 1.x does not expose `getBigInt64`/`setBigInt64` on `DataView`.
  */
final private[kyo] class JsUnsafeBuffer(
    private val u8a: Uint8Array,
    private val view: DataView,
    byteSize: Long,
    closer: () => Unit
) extends UnsafeBuffer(byteSize, closer):

    def getByte(offset: Long)(using AllowUnsafe): Byte              = view.getInt8(offset.toInt)
    def setByte(offset: Long, value: Byte)(using AllowUnsafe): Unit = view.setInt8(offset.toInt, value)

    def getShort(offset: Long)(using AllowUnsafe): Short              = view.getInt16(offset.toInt, littleEndian = true)
    def setShort(offset: Long, value: Short)(using AllowUnsafe): Unit = view.setInt16(offset.toInt, value, littleEndian = true)

    def getInt(offset: Long)(using AllowUnsafe): Int              = view.getInt32(offset.toInt, littleEndian = true)
    def setInt(offset: Long, value: Int)(using AllowUnsafe): Unit = view.setInt32(offset.toInt, value, littleEndian = true)

    // Long: emulate with two Int32 halves (no getBigInt64 in Scala.js 1.x)
    def getLong(offset: Long)(using AllowUnsafe): Long =
        val lo = view.getInt32(offset.toInt, littleEndian = true).toLong & 0xffffffffL
        val hi = view.getInt32(offset.toInt + 4, littleEndian = true).toLong
        (hi << 32) | lo
    end getLong

    def setLong(offset: Long, value: Long)(using AllowUnsafe): Unit =
        view.setInt32(offset.toInt, value.toInt, littleEndian = true)
        view.setInt32(offset.toInt + 4, (value >>> 32).toInt, littleEndian = true)

    def getFloat(offset: Long)(using AllowUnsafe): Float              = view.getFloat32(offset.toInt, littleEndian = true)
    def setFloat(offset: Long, value: Float)(using AllowUnsafe): Unit = view.setFloat32(offset.toInt, value, littleEndian = true)

    def getDouble(offset: Long)(using AllowUnsafe): Double              = view.getFloat64(offset.toInt, littleEndian = true)
    def setDouble(offset: Long, value: Double)(using AllowUnsafe): Unit = view.setFloat64(offset.toInt, value, littleEndian = true)

    def copyTo(target: UnsafeBuffer, srcOffset: Long, targetOffset: Long, bytes: Long)(using AllowUnsafe): Unit =
        target match
            case js: JsUnsafeBuffer =>
                // Typed array set for JS-to-JS
                val src = new Uint8Array(u8a.buffer, u8a.byteOffset + srcOffset.toInt, bytes.toInt)
                js.u8a.set(src, targetOffset.toInt)
            case _ =>
                // Byte-by-byte fallback for cross-platform copies
                var i = 0L
                while i < bytes do
                    target.setByte(targetOffset + i, getByte(srcOffset + i))
                    i += 1
    end copyTo

    def copyToArray(arr: Array[Byte], srcOffset: Long, len: Int)(using AllowUnsafe): Unit =
        var i = 0
        while i < len do
            arr(i) = view.getInt8(srcOffset.toInt + i)
            i += 1
    end copyToArray

    def view(offset: Long, byteSize: Long)(using AllowUnsafe): UnsafeBuffer =
        val newU8a  = new Uint8Array(u8a.buffer, u8a.byteOffset + offset.toInt, byteSize.toInt)
        val newView = new DataView(u8a.buffer, u8a.byteOffset + offset.toInt, byteSize.toInt)
        new JsUnsafeBuffer(newU8a, newView, byteSize, () => ())
    end view

    def raw(using AllowUnsafe): AnyRef = u8a.asInstanceOf[AnyRef]
end JsUnsafeBuffer
