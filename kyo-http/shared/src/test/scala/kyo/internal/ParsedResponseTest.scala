package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*

class ParsedResponseTest extends kyo.BaseHttpTest:

    given CanEqual[Any, Any] = CanEqual.derived

    /** Headers holding one field, built the way Http1ResponseParser builds them. */
    private def oneHeader(name: String, value: String): HttpHeaders =
        val nameBytes  = name.getBytes(StandardCharsets.UTF_8)
        val valueBytes = value.getBytes(StandardCharsets.UTF_8)
        val raw        = nameBytes ++ valueBytes
        HttpHeaders.parsed(raw, 0, raw.length, Array(0, nameBytes.length, nameBytes.length, valueBytes.length), 1)
    end oneHeader

    "ParsedResponse" - {

        "construct with all fields stored correctly" in {
            val headers = oneHeader("Host", "example.com")
            val resp    = new ParsedResponse(200, headers, 42, false, true)
            assert(resp.statusCode == 200)
            assert(resp.headers.get("Host") == Present("example.com"))
            assert(resp.contentLength == 42)
            assert(resp.isChunked == false)
            assert(resp.isKeepAlive == true)
        }

        "headers are looked up case-insensitively" in {
            val value   = "application/json"
            val resp    = new ParsedResponse(200, oneHeader("Content-Type", value), -1, false, true)
            val headers = resp.headers
            assert(headers.size == 1)
            assert(headers.get("Content-Type") == Present(value))
            assert(headers.get("content-type") == Present(value))
        }

        "statusCode is a read-only val" in {
            val resp  = new ParsedResponse(404, HttpHeaders.empty, -1, false, false)
            val resp2 = new ParsedResponse(500, HttpHeaders.empty, -1, false, false)
            assert(resp.statusCode == 404)
            assert(resp2.statusCode == 500)
        }

        "contentLength is a read-only val" in {
            // -1 is the sentinel for absent Content-Length
            val respAbsent  = new ParsedResponse(200, HttpHeaders.empty, -1, false, true)
            val respPresent = new ParsedResponse(200, HttpHeaders.empty, 1024, false, true)
            assert(respAbsent.contentLength == -1)
            assert(respPresent.contentLength == 1024)
        }
    }

end ParsedResponseTest
