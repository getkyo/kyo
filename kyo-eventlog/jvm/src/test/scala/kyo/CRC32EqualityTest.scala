package kyo

import kyo.internal.CRC32

/** Verifies that [[kyo.internal.CRC32]] produces bit-identical output to [[java.util.zip.CRC32]]
  * for all 256 single-byte inputs and a multi-byte sequence. This is the explicit cross-check that
  * the shared pure implementation cannot silently diverge from the IEEE 802.3 standard; it runs on
  * JVM only (where both implementations are available) and acts as the byte-identity anchor for
  * segment files produced by the shared codec on any platform.
  */
class CRC32EqualityTest extends kyo.test.Test[Any]:

    "pure CRC32 matches java.util.zip.CRC32" - {
        "for every single-byte input" in {
            val mismatches = (0 until 256).filter { b =>
                val ref = new java.util.zip.CRC32(); ref.update(b); val refVal = ref.getValue
                val our = new CRC32(); our.update(Array(b.toByte), 0, 1); our.value != refVal
            }
            assert(mismatches.isEmpty)
        }
        "for a multi-byte sequence" in {
            val bytes = "KJN1 journal segment byte-identity check".getBytes("UTF-8")
            val ref   = new java.util.zip.CRC32(); ref.update(bytes)
            val our   = new CRC32(); our.update(bytes)
            assert(our.value == ref.getValue)
        }
        "for an empty input" in {
            val ref = new java.util.zip.CRC32()
            val our = new CRC32()
            assert(our.value == ref.getValue)
        }
        "the CRC32.of helper matches for arbitrary bytes" in {
            val bytes = Array[Byte](0x4b, 0x4a, 0x4e, 0x31, 0x01) // KJN1 + version byte
            val ref   = new java.util.zip.CRC32(); ref.update(bytes)
            assert((CRC32.of(bytes) & 0xffffffffL) == ref.getValue)
        }
    }
end CRC32EqualityTest
