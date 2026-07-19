package kyo.ffi

import kyo.ffi.internal.JsRawSegment
import scala.scalajs.js.typedarray.DataView
import scala.scalajs.js.typedarray.Int32Array
import scala.scalajs.js.typedarray.Uint8Array
import scala.scalajs.js as sjs

/** Validates the Long round-trip through the JS buffer backend.
  *
  * `Long` has no matching native typed-array constructor, so the JS UnsafeLayout falls back to emulating `getBigInt64`/`setBigInt64` with
  * two signed `Int32` halves. Separately, the emitter translates on-wire Longs through `sjs.BigInt` -- that path is exercised in
  * `JsEmitterSpec`; here we confirm the buffer's split-halves path preserves every edge value, and spot-check the BigInt round-trip helper
  * behaviour used by emitted code.
  */
class JsLongBigIntTest extends Test:

    "Buffer.alloc[Long]" - {
        "stores Long.MaxValue and reads back unchanged" in {
            val b = Buffer.alloc[Long](1)
            try
                b.set(0, Long.MaxValue)
                assert(b.get(0) == Long.MaxValue)
            finally b.close()
            end try
        }

        "stores Long.MinValue and reads back unchanged" in {
            val b = Buffer.alloc[Long](1)
            try
                b.set(0, Long.MinValue)
                assert(b.get(0) == Long.MinValue)
            finally b.close()
            end try
        }

        "stores 0L" in {
            val b = Buffer.alloc[Long](1)
            try
                assert(b.get(0) == 0L)
                b.set(0, 0L)
                assert(b.get(0) == 0L)
            finally b.close()
            end try
        }

        "stores several edge values in sequence without cross-talk" in {
            val b = Buffer.alloc[Long](5)
            try
                b.set(0, Long.MinValue)
                b.set(1, -1L)
                b.set(2, 0L)
                b.set(3, 1L)
                b.set(4, Long.MaxValue)
                assert(b.get(0) == Long.MinValue)
                assert(b.get(1) == -1L)
                assert(b.get(2) == 0L)
                assert(b.get(3) == 1L)
                assert(b.get(4) == Long.MaxValue)
            finally b.close()
            end try
        }
    }

    "Long split-halves encoding" - {
        "writes Long.MaxValue as two Int32 halves 0xffffffff, 0x7fffffff" in {
            val b = Buffer.alloc[Long](1)
            try
                b.set(0, Long.MaxValue)
                val u8a = Buffer.Raw.unwrap(b.raw).asInstanceOf[JsRawSegment].u8a
                val v   = new DataView(u8a.buffer, u8a.byteOffset, u8a.byteLength)
                // Little-endian: low half then high half.
                assert(v.getInt32(0, littleEndian = true) == 0xffffffff)
                assert(v.getInt32(4, littleEndian = true) == 0x7fffffff)
            finally b.close()
            end try
        }

        "writes and reads Long.MinValue" in {
            val b = Buffer.alloc[Long](1)
            try
                b.set(0, Long.MinValue)
                assert(b.get(0) == Long.MinValue)
            finally b.close()
            end try
        }

        "round-trips every power-of-two boundary" in {
            val b = Buffer.alloc[Long](1)
            try
                (0 to 62).foreach { sh =>
                    val x = 1L << sh
                    b.set(0, x)
                    assert(b.get(0) == x)
                    b.set(0, -x)
                    assert(b.get(0) == -x)
                }
            finally b.close()
            end try
        }
    }

    "js.BigInt round-trip (emitter wire format)" - {
        "js.BigInt(Long.MaxValue.toString).toString.toLong equals Long.MaxValue" in {
            val s  = Long.MaxValue.toString
            val bi = sjs.BigInt(s)
            assert(bi.toString.toLong == Long.MaxValue)
        }

        "js.BigInt(Long.MinValue.toString).toString.toLong equals Long.MinValue" in {
            val s  = Long.MinValue.toString
            val bi = sjs.BigInt(s)
            assert(bi.toString.toLong == Long.MinValue)
        }

        "0L round-trip" in {
            assert(sjs.BigInt("0").toString.toLong == 0L)
        }
    }
end JsLongBigIntTest
