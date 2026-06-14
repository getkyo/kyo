package kyo.ffi

import kyo.ffi.internal.JsRawSegment
import scala.scalajs.js.typedarray.DataView
import scala.scalajs.js.typedarray.Float32Array
import scala.scalajs.js.typedarray.Float64Array
import scala.scalajs.js.typedarray.Int16Array
import scala.scalajs.js.typedarray.Int32Array
import scala.scalajs.js.typedarray.Int8Array
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js as sjs

/** Validates that [[Buffer]] reads/writes are observable at the expected byte offsets in the underlying `ArrayBuffer`.
  *
  * Now that Buffer delegates to UnsafeBuffer (backed by DataView), these tests confirm the byte-level correctness of the DataView path.
  */
class JsBufferTypedViewTest extends Test:

    "Buffer.alloc[Int] writes observable through Int32Array view over the same ArrayBuffer" in {
        val b = Buffer.alloc[Int](4)
        try
            b.set(0, 0x11223344)
            b.set(1, -1)
            b.set(2, 0)
            b.set(3, Int.MinValue)

            val u8a  = Buffer.Raw.unwrap(b.raw).asInstanceOf[JsRawSegment].u8a
            val i32  = new Int32Array(u8a.buffer, u8a.byteOffset, 4)
            val view = new DataView(u8a.buffer, u8a.byteOffset, u8a.byteLength)

            assert(i32(0).asInstanceOf[Int] == 0x11223344)
            assert(i32(1).asInstanceOf[Int] == -1)
            assert(i32(2).asInstanceOf[Int] == 0)
            assert(i32(3).asInstanceOf[Int] == Int.MinValue)

            // DataView little-endian interpretation must match (JS typed arrays use host LE on supported runtimes).
            assert(view.getInt32(0, littleEndian = true) == 0x11223344)
            assert(view.getInt32(4, littleEndian = true) == -1)
            assert(view.getInt32(8, littleEndian = true) == 0)
            assert(view.getInt32(12, littleEndian = true) == Int.MinValue)
        finally b.close()
        end try
    }

    "Buffer.alloc[Int] alignment -- index 1 corresponds to byte offset 4" in {
        val b = Buffer.alloc[Int](4)
        try
            b.set(0, 0)
            b.set(1, 0x7fffffff)
            b.set(2, 0)
            b.set(3, 0)

            val u8a = Buffer.Raw.unwrap(b.raw).asInstanceOf[JsRawSegment].u8a
            // bytes 0..3 are zero (index 0), bytes 4..7 hold 0x7fffffff little-endian.
            assert(u8a(0).asInstanceOf[Int] == 0)
            assert(u8a(1).asInstanceOf[Int] == 0)
            assert(u8a(2).asInstanceOf[Int] == 0)
            assert(u8a(3).asInstanceOf[Int] == 0)

            assert(u8a(4).asInstanceOf[Int] == 0xff)
            assert(u8a(5).asInstanceOf[Int] == 0xff)
            assert(u8a(6).asInstanceOf[Int] == 0xff)
            assert(u8a(7).asInstanceOf[Int] == 0x7f)
        finally b.close()
        end try
    }

    "Buffer.alloc[Int] is zero-initialized" in {
        val b = Buffer.alloc[Int](8)
        try
            (0 until 8).foreach { i => assert(b.get(i) == 0) }
        finally b.close()
        end try
    }

    "Buffer.alloc[Short] stays byte-consistent" in {
        val b = Buffer.alloc[Short](3)
        try
            b.set(0, 0x1122.toShort)
            b.set(1, (-1).toShort)
            b.set(2, Short.MaxValue)

            val u8a = Buffer.Raw.unwrap(b.raw).asInstanceOf[JsRawSegment].u8a
            val i16 = new Int16Array(u8a.buffer, u8a.byteOffset, 3)

            assert(i16(0).asInstanceOf[Int] == 0x1122)
            assert(i16(1).asInstanceOf[Int] == -1)
            assert(i16(2).asInstanceOf[Int] == Short.MaxValue)
        finally b.close()
        end try
    }

    "Buffer.alloc[Byte] stays byte-consistent" in {
        val b = Buffer.alloc[Byte](4)
        try
            b.set(0, 0x7f.toByte)
            b.set(1, (-1).toByte)
            b.set(2, 0.toByte)
            b.set(3, Byte.MinValue)

            val u8a = Buffer.Raw.unwrap(b.raw).asInstanceOf[JsRawSegment].u8a
            val i8  = new Int8Array(u8a.buffer, u8a.byteOffset, 4)

            assert(i8(0).asInstanceOf[Int] == 0x7f)
            assert(i8(1).asInstanceOf[Int] == -1)
            assert(i8(2).asInstanceOf[Int] == 0)
            assert(i8(3).asInstanceOf[Int] == Byte.MinValue)
        finally b.close()
        end try
    }

    "Buffer.alloc[Float] stays byte-consistent with DataView" in {
        val b = Buffer.alloc[Float](2)
        try
            b.set(0, 3.14f)
            b.set(1, -2.71f)

            val u8a  = Buffer.Raw.unwrap(b.raw).asInstanceOf[JsRawSegment].u8a
            val f32  = new Float32Array(u8a.buffer, u8a.byteOffset, 2)
            val view = new DataView(u8a.buffer, u8a.byteOffset, u8a.byteLength)

            assert(f32(0).asInstanceOf[Float] == 3.14f)
            assert(f32(1).asInstanceOf[Float] == -2.71f)

            assert(view.getFloat32(0, littleEndian = true) == 3.14f)
            assert(view.getFloat32(4, littleEndian = true) == -2.71f)
        finally b.close()
        end try
    }

    "Buffer.alloc[Double] stays byte-consistent with DataView" in {
        val b = Buffer.alloc[Double](2)
        try
            b.set(0, 1.0e100)
            b.set(1, -0.0)

            val u8a  = Buffer.Raw.unwrap(b.raw).asInstanceOf[JsRawSegment].u8a
            val f64  = new Float64Array(u8a.buffer, u8a.byteOffset, 2)
            val view = new DataView(u8a.buffer, u8a.byteOffset, u8a.byteLength)

            assert(f64(0).asInstanceOf[Double] == 1.0e100)
            assert(f64(1).asInstanceOf[Double] == -0.0)

            assert(view.getFloat64(0, littleEndian = true) == 1.0e100)
            assert(view.getFloat64(8, littleEndian = true) == -0.0)
        finally b.close()
        end try
    }
end JsBufferTypedViewTest
