package kyo

import java.nio.charset.StandardCharsets

class HttpHeadersPackedTest extends BaseHttpTest:

    /** Lays the fields out the way a parser hands them over: names and values concatenated after `prefix` filler bytes, described by
      * offsets counted from the end of the filler.
      */
    private def wire(prefix: Int, headers: (String, String)*): (Array[Byte], Array[Int]) =
        val rawBuf = new java.io.ByteArrayOutputStream()
        rawBuf.write(new Array[Byte](prefix))
        val fields = new Array[Int](headers.size * 4)
        headers.zipWithIndex.foreach { case ((name, value), i) =>
            val nameBytes = name.getBytes(StandardCharsets.UTF_8)
            val valBytes  = value.getBytes(StandardCharsets.UTF_8)
            fields(i * 4) = rawBuf.size() - prefix
            fields(i * 4 + 1) = nameBytes.length
            rawBuf.write(nameBytes)
            fields(i * 4 + 2) = rawBuf.size() - prefix
            fields(i * 4 + 3) = valBytes.length
            rawBuf.write(valBytes)
        }
        (rawBuf.toByteArray, fields)
    end wire

    private def parsedHeaders(headers: (String, String)*): HttpHeaders =
        val (raw, fields) = wire(0, headers*)
        HttpHeaders.parsed(raw, 0, raw.length, fields, headers.size)

    "packed headers read-only" - {

        "get from packed headers" in {
            val headers = parsedHeaders("Content-Type" -> "text/plain", "Host" -> "example.com")
            assert(headers.get("Content-Type") == Present("text/plain"))
            assert(headers.get("Host") == Present("example.com"))
        }

        "get case insensitive from packed" in {
            val headers = parsedHeaders("Content-Type" -> "text/plain")
            assert(headers.get("content-type") == Present("text/plain"))
            assert(headers.get("CONTENT-TYPE") == Present("text/plain"))
            assert(headers.get("Content-type") == Present("text/plain"))
        }

        "get missing header from packed returns Absent" in {
            val headers = parsedHeaders("Content-Type" -> "text/plain")
            assert(headers.get("X-Missing") == Absent)
        }

        "getAll from packed" in {
            val headers = parsedHeaders(
                "Set-Cookie" -> "a=1",
                "Host"       -> "example.com",
                "Set-Cookie" -> "b=2"
            )
            val result = headers.getAll("Set-Cookie")
            assert(result.length == 2)
            assert(result(0) == "a=1")
            assert(result(1) == "b=2")
        }

        "contains from packed" in {
            val headers = parsedHeaders("Host" -> "example.com", "Accept" -> "*/*")
            assert(headers.contains("Host") == true)
            assert(headers.contains("host") == true)
            assert(headers.contains("Missing") == false)
        }

        "size from packed" in {
            val headers = parsedHeaders("A" -> "1", "B" -> "2", "C" -> "3")
            assert(headers.size == 3)
        }

        "isEmpty from packed" in {
            val headers = parsedHeaders()
            assert(headers.isEmpty == true)
        }

        "nonEmpty from packed" in {
            val headers = parsedHeaders("Host" -> "example.com")
            assert(headers.nonEmpty == true)

            val emptyHeaders = parsedHeaders()
            assert(emptyHeaders.nonEmpty == false)
        }

        "foreach from packed" in {
            val headers = parsedHeaders(
                "Content-Type" -> "text/html",
                "Host"         -> "example.com",
                "Accept"       -> "*/*"
            )
            val buf = scala.collection.mutable.ListBuffer[(String, String)]()
            headers.foreach((n, v) => buf += ((n, v)))
            assert(buf.length == 3)
            assert(buf(0) == ("Content-Type", "text/html"))
            assert(buf(1) == ("Host", "example.com"))
            assert(buf(2) == ("Accept", "*/*"))
        }

        "foldLeft from packed" in {
            val headers = parsedHeaders(
                "A" -> "1",
                "B" -> "2",
                "C" -> "3"
            )
            val result = headers.foldLeft("")((acc, name, value) => acc + name + "=" + value + ";")
            assert(result == "A=1;B=2;C=3;")
        }
    }

    "packed mutation" - {

        "add to packed produces headers with all original plus new" in {
            val headers = parsedHeaders("Host" -> "example.com", "Accept" -> "*/*")
            val updated = headers.add("X-Custom", "value")
            assert(updated.size == 3)
            assert(updated.get("Host") == Present("example.com"))
            assert(updated.get("Accept") == Present("*/*"))
            assert(updated.get("X-Custom") == Present("value"))
        }

        "set on packed" in {
            val headers = parsedHeaders("Content-Type" -> "text/plain", "Host" -> "example.com")
            val updated = headers.set("Content-Type", "text/html")
            assert(updated.size == 2)
            assert(updated.get("Content-Type") == Present("text/html"))
            assert(updated.get("Host") == Present("example.com"))
        }

        "remove from packed" in {
            val headers = parsedHeaders("A" -> "1", "B" -> "2", "C" -> "3")
            val updated = headers.remove("B")
            assert(updated.size == 2)
            assert(updated.get("A") == Present("1"))
            assert(updated.get("B") == Absent)
            assert(updated.get("C") == Present("3"))
        }

        "concat packed with chunk" in {
            val packedHdrs = parsedHeaders("A" -> "1", "B" -> "2")
            val chunkHdrs  = HttpHeaders.empty.add("C", "3").add("D", "4")
            val combined   = packedHdrs.concat(chunkHdrs)
            assert(combined.size == 4)
            assert(combined.get("A") == Present("1"))
            assert(combined.get("B") == Present("2"))
            assert(combined.get("C") == Present("3"))
            assert(combined.get("D") == Present("4"))
        }
    }

    "parsed" - {

        "roundtrips" in {
            val headers = parsedHeaders("Content-Type" -> "application/json", "Authorization" -> "Bearer token123")
            assert(headers.get("Content-Type") == Present("application/json"))
            assert(headers.get("Authorization") == Present("Bearer token123"))
            assert(headers.size == 2)
            assert(headers.nonEmpty == true)
            assert(headers.isEmpty == false)
        }

        // The server hands over a slice of a larger request buffer whose path and query bytes precede the headers.
        "reads fields counted from a raw start past the beginning of the buffer" in {
            val (raw, fields) = wire(7, "Host" -> "example.com", "Accept" -> "*/*")
            val headers       = HttpHeaders.parsed(raw, 7, raw.length - 7, fields, 2)
            assert(headers.foldLeft(Chunk.empty[(String, String)])((acc, n, v) => acc.append((n, v))) ==
                Chunk("Host" -> "example.com", "Accept" -> "*/*"))
        }

        // Both parsers reuse their buffers for the next message on the connection.
        "keeps its fields when the caller reuses both arrays" in {
            val (raw, fields) = wire(0, "Host" -> "example.com")
            val headers       = HttpHeaders.parsed(raw, 0, raw.length, fields, 1)
            java.util.Arrays.fill(raw, 'x'.toByte)
            java.util.Arrays.fill(fields, 0)
            assert(headers.get("Host") == Present("example.com"))
        }
    }

    "invalidField" - {

        // Packed headers are written back as the raw octets they were parsed from. Both parsers reject CR, LF and NUL
        // before a header reaches this form, so a packed value needs no per-write check and keeps its zero-allocation
        // write path. Catches a predicate that decodes packed headers and tests them anyway, which would reject a
        // request whose headers the serializer writes byte-for-byte and break plain proxying.
        "reports nothing for a packed non-ASCII value the serializer writes as raw bytes" in {
            val headers = parsedHeaders("X-Trace" -> "café")
            assert(headers.get("X-Trace") == Present("café"), "the packed value must decode to the peer's string")
            assert(headers.invalidField == Absent)
        }

        // A modification decodes the packed bytes to Strings and the collection is written char-by-char from then on,
        // so the check has to follow the representation the serializer will actually write, not the one it was handed.
        // A peer's obs-text value stays legal across the conversion; only the write path changes.
        "reports nothing for a non-ASCII value once a modification converts packed to chunk-backed" in {
            val packed = parsedHeaders("X-Trace" -> "café")
            assert(packed.invalidField == Absent, "the packed form is writable")
            val modified = packed.add("X-Request-Id", "abc123")
            assert(modified.invalidField == Absent, "obs-text stays legal once the collection is chunk-backed")
        }

        // The conversion is what exposes a value the serializer must refuse. A packed CRLF-bearing value cannot arrive
        // from either parser, but a test can build one, and `add` turns it into the chunk-backed form the check covers.
        "reports a CRLF-bearing value once a modification converts packed to chunk-backed" in {
            val packed   = parsedHeaders("X-Trace" -> "bar\r\nX-Admin: true")
            val modified = packed.add("X-Request-Id", "abc123")
            assert(modified.invalidField == Present("the value of header 'X-Trace'"))
        }
    }

    "name lookup folds ASCII case only, the same as the chunk form" in {
        val h = parsedHeaders("ſet-Cookie" -> "a=1", "Keep-Alive" -> "timeout=5", "Content-Type" -> "text/plain")
        assert(h.get("Set-Cookie") == Absent)
        assert(h.get("Keep-Alive") == Absent)
        assert(h.get("CONTENT-TYPE") == Present("text/plain"))
    }

end HttpHeadersPackedTest
