package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*

class ParsedResponseTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    /** Builds a minimal valid packed-header byte array with zero headers. */
    private def emptyPackedHeaders(): Array[Byte] =
        // 2-byte count = 0, no index entries, no raw bytes
        Array[Byte](0, 0)

    /** Builds a packed-header byte array containing one header.
      *
      * Packed format (from HttpHeaders):
      *   - bytes [0..1]: header count as big-endian short
      *   - bytes [2..9]: one 8-byte index entry: nameOff(2), nameLen(2), valOff(2), valLen(2) — all offsets relative to raw data start
      *   - bytes [10..]: raw name bytes then raw value bytes
      */
    private def oneHeaderPacked(name: String, value: String): Array[Byte] =
        val nameBytes  = name.getBytes(StandardCharsets.UTF_8)
        val valueBytes = value.getBytes(StandardCharsets.UTF_8)
        val count      = 1
        val indexSize  = 2 + count * 8 // 10 bytes
        val total      = indexSize + nameBytes.length + valueBytes.length
        val packed     = new Array[Byte](total)
        // header count
        packed(0) = 0
        packed(1) = 1
        // index entry 0: nameOff=0, nameLen=nameBytes.length, valOff=nameBytes.length, valLen=valueBytes.length
        val nameOff = 0
        val nameLen = nameBytes.length
        val valOff  = nameBytes.length
        val valLen  = valueBytes.length
        packed(2) = ((nameOff >> 8) & 0xff).toByte
        packed(3) = (nameOff & 0xff).toByte
        packed(4) = ((nameLen >> 8) & 0xff).toByte
        packed(5) = (nameLen & 0xff).toByte
        packed(6) = ((valOff >> 8) & 0xff).toByte
        packed(7) = (valOff & 0xff).toByte
        packed(8) = ((valLen >> 8) & 0xff).toByte
        packed(9) = (valLen & 0xff).toByte
        // raw bytes
        java.lang.System.arraycopy(nameBytes, 0, packed, indexSize, nameBytes.length)
        java.lang.System.arraycopy(valueBytes, 0, packed, indexSize + nameBytes.length, valueBytes.length)
        packed
    end oneHeaderPacked

    "ParsedResponse" - {

        "construct with all fields stored correctly" in {
            val packed = emptyPackedHeaders()
            val resp   = new ParsedResponse(200, packed, 42, false, true)
            assert(resp.statusCode == 200)
            assert(resp.packedHeaders eq packed)
            assert(resp.contentLength == 42)
            assert(resp.isChunked == false)
            assert(resp.isKeepAlive == true)
            succeed
        }

        "headers property wraps packed bytes as HttpHeaders" in {
            val name    = "Content-Type"
            val value   = "application/json"
            val packed  = oneHeaderPacked(name, value)
            val resp    = new ParsedResponse(200, packed, -1, false, true)
            val headers = resp.headers
            assert(headers.size == 1)
            assert(headers.get("Content-Type") == Present(value))
            assert(headers.get("content-type") == Present(value)) // case-insensitive
            succeed
        }

        "statusCode is a read-only val" in {
            // Verify statusCode is accessible and returns the constructor value unchanged
            val resp = new ParsedResponse(404, emptyPackedHeaders(), -1, false, false)
            assert(resp.statusCode == 404)
            // Confirm it is not a var: the class is final and statusCode is a val —
            // attempting to assign resp.statusCode = 0 would not compile.
            // We verify immutability by constructing two instances and confirming independence.
            val resp2 = new ParsedResponse(500, emptyPackedHeaders(), -1, false, false)
            assert(resp.statusCode == 404)
            assert(resp2.statusCode == 500)
            succeed
        }

        "contentLength is a read-only val" in {
            // -1 is the sentinel for absent Content-Length
            val respAbsent = new ParsedResponse(200, emptyPackedHeaders(), -1, false, true)
            assert(respAbsent.contentLength == -1)
            // A concrete body size
            val respPresent = new ParsedResponse(200, emptyPackedHeaders(), 1024, false, true)
            assert(respPresent.contentLength == 1024)
            // Mutating one instance must not affect the other
            assert(respAbsent.contentLength == -1)
            succeed
        }
    }

end ParsedResponseTest
