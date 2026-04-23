package kyo

import java.nio.charset.StandardCharsets

class HttpHeadersPackedTest extends Test:

    /** Builds a standalone packed headers array from name-value pairs.
      *
      * Layout:
      * {{{
      * [headerCount: 2 bytes]
      * [nameOff:2, nameLen:2, valOff:2, valLen:2] per header (8 bytes each)
      * [raw bytes: concatenated header names and values]
      * }}}
      *
      * All offsets are relative to the raw bytes section start.
      */
    private def buildPackedHeaders(headers: (String, String)*): Array[Byte] =
        val count   = headers.size
        val rawBuf  = new java.io.ByteArrayOutputStream()
        val offsets = new Array[Int](count * 4) // nameOff, nameLen, valOff, valLen per header
        var idx     = 0
        headers.foreach { (name, value) =>
            val nameBytes = name.getBytes(StandardCharsets.UTF_8)
            val valBytes  = value.getBytes(StandardCharsets.UTF_8)
            offsets(idx * 4) = rawBuf.size()        // nameOff
            offsets(idx * 4 + 1) = nameBytes.length // nameLen
            rawBuf.write(nameBytes)
            offsets(idx * 4 + 2) = rawBuf.size()   // valOff
            offsets(idx * 4 + 3) = valBytes.length // valLen
            rawBuf.write(valBytes)
            idx += 1
        }
        val rawBytes  = rawBuf.toByteArray
        val indexSize = 2 + count * 8
        val result    = new Array[Byte](indexSize + rawBytes.length)
        // Write headerCount (big-endian)
        result(0) = ((count >> 8) & 0xff).toByte
        result(1) = (count & 0xff).toByte
        // Write per-header offsets
        var p = 2
        for i <- 0 until count do
            val nameOff = offsets(i * 4)
            val nameLen = offsets(i * 4 + 1)
            val valOff  = offsets(i * 4 + 2)
            val valLen  = offsets(i * 4 + 3)
            result(p) = ((nameOff >> 8) & 0xff).toByte; p += 1
            result(p) = (nameOff & 0xff).toByte; p += 1
            result(p) = ((nameLen >> 8) & 0xff).toByte; p += 1
            result(p) = (nameLen & 0xff).toByte; p += 1
            result(p) = ((valOff >> 8) & 0xff).toByte; p += 1
            result(p) = (valOff & 0xff).toByte; p += 1
            result(p) = ((valLen >> 8) & 0xff).toByte; p += 1
            result(p) = (valLen & 0xff).toByte; p += 1
        end for
        // Write raw bytes
        java.lang.System.arraycopy(rawBytes, 0, result, indexSize, rawBytes.length)
        result
    end buildPackedHeaders

    "packed headers read-only" - {

        "get from packed headers" in {
            val packed  = buildPackedHeaders("Content-Type" -> "text/plain", "Host" -> "example.com")
            val headers = HttpHeaders.fromPacked(packed)
            assert(headers.get("Content-Type") == Present("text/plain"))
            assert(headers.get("Host") == Present("example.com"))
        }

        "get case insensitive from packed" in {
            val packed  = buildPackedHeaders("Content-Type" -> "text/plain")
            val headers = HttpHeaders.fromPacked(packed)
            assert(headers.get("content-type") == Present("text/plain"))
            assert(headers.get("CONTENT-TYPE") == Present("text/plain"))
            assert(headers.get("Content-type") == Present("text/plain"))
        }

        "get missing header from packed returns Absent" in {
            val packed  = buildPackedHeaders("Content-Type" -> "text/plain")
            val headers = HttpHeaders.fromPacked(packed)
            assert(headers.get("X-Missing") == Absent)
        }

        "getAll from packed" in {
            val packed = buildPackedHeaders(
                "Set-Cookie" -> "a=1",
                "Host"       -> "example.com",
                "Set-Cookie" -> "b=2"
            )
            val headers = HttpHeaders.fromPacked(packed)
            val result  = headers.getAll("Set-Cookie")
            assert(result.length == 2)
            assert(result(0) == "a=1")
            assert(result(1) == "b=2")
        }

        "contains from packed" in {
            val packed  = buildPackedHeaders("Host" -> "example.com", "Accept" -> "*/*")
            val headers = HttpHeaders.fromPacked(packed)
            assert(headers.contains("Host") == true)
            assert(headers.contains("host") == true)
            assert(headers.contains("Missing") == false)
        }

        "size from packed" in {
            val packed  = buildPackedHeaders("A" -> "1", "B" -> "2", "C" -> "3")
            val headers = HttpHeaders.fromPacked(packed)
            assert(headers.size == 3)
        }

        "isEmpty from packed" in {
            val packed  = buildPackedHeaders()
            val headers = HttpHeaders.fromPacked(packed)
            assert(headers.isEmpty == true)
        }

        "nonEmpty from packed" in {
            val packed  = buildPackedHeaders("Host" -> "example.com")
            val headers = HttpHeaders.fromPacked(packed)
            assert(headers.nonEmpty == true)

            val emptyPacked  = buildPackedHeaders()
            val emptyHeaders = HttpHeaders.fromPacked(emptyPacked)
            assert(emptyHeaders.nonEmpty == false)
        }

        "foreach from packed" in {
            val packed = buildPackedHeaders(
                "Content-Type" -> "text/html",
                "Host"         -> "example.com",
                "Accept"       -> "*/*"
            )
            val headers = HttpHeaders.fromPacked(packed)
            val buf     = scala.collection.mutable.ListBuffer[(String, String)]()
            headers.foreach((n, v) => buf += ((n, v)))
            assert(buf.length == 3)
            assert(buf(0) == ("Content-Type", "text/html"))
            assert(buf(1) == ("Host", "example.com"))
            assert(buf(2) == ("Accept", "*/*"))
        }

        "foldLeft from packed" in {
            val packed = buildPackedHeaders(
                "A" -> "1",
                "B" -> "2",
                "C" -> "3"
            )
            val headers = HttpHeaders.fromPacked(packed)
            val result  = headers.foldLeft("")((acc, name, value) => acc + name + "=" + value + ";")
            assert(result == "A=1;B=2;C=3;")
        }
    }

    "packed mutation converts to Chunk" - {

        "add to packed produces headers with all original plus new" in {
            val packed  = buildPackedHeaders("Host" -> "example.com", "Accept" -> "*/*")
            val headers = HttpHeaders.fromPacked(packed)
            val updated = headers.add("X-Custom", "value")
            assert(updated.size == 3)
            assert(updated.get("Host") == Present("example.com"))
            assert(updated.get("Accept") == Present("*/*"))
            assert(updated.get("X-Custom") == Present("value"))
        }

        "set on packed" in {
            val packed  = buildPackedHeaders("Content-Type" -> "text/plain", "Host" -> "example.com")
            val headers = HttpHeaders.fromPacked(packed)
            val updated = headers.set("Content-Type", "text/html")
            assert(updated.size == 2)
            assert(updated.get("Content-Type") == Present("text/html"))
            assert(updated.get("Host") == Present("example.com"))
        }

        "remove from packed" in {
            val packed  = buildPackedHeaders("A" -> "1", "B" -> "2", "C" -> "3")
            val headers = HttpHeaders.fromPacked(packed)
            val updated = headers.remove("B")
            assert(updated.size == 2)
            assert(updated.get("A") == Present("1"))
            assert(updated.get("B") == Absent)
            assert(updated.get("C") == Present("3"))
        }

        "concat packed with chunk" in {
            val packed     = buildPackedHeaders("A" -> "1", "B" -> "2")
            val packedHdrs = HttpHeaders.fromPacked(packed)
            val chunkHdrs  = HttpHeaders.empty.add("C", "3").add("D", "4")
            val combined   = packedHdrs.concat(chunkHdrs)
            assert(combined.size == 4)
            assert(combined.get("A") == Present("1"))
            assert(combined.get("B") == Present("2"))
            assert(combined.get("C") == Present("3"))
            assert(combined.get("D") == Present("4"))
        }
    }

    "factory" - {

        "fromPacked roundtrips" in {
            val packed  = buildPackedHeaders("Content-Type" -> "application/json", "Authorization" -> "Bearer token123")
            val headers = HttpHeaders.fromPacked(packed)
            assert(headers.get("Content-Type") == Present("application/json"))
            assert(headers.get("Authorization") == Present("Bearer token123"))
            assert(headers.size == 2)
            assert(headers.nonEmpty == true)
            assert(headers.isEmpty == false)
        }
    }

end HttpHeadersPackedTest
