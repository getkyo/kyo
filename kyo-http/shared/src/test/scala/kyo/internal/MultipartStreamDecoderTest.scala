package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class MultipartStreamDecoderTest extends Test:
    import AllowUnsafe.embrace.danger

    private def makeMultipartBody(boundary: String, parts: Seq[(String, Option[String], Option[String], Array[Byte])]): Array[Byte] =
        val out = new java.io.ByteArrayOutputStream()
        parts.foreach { case (name, filename, contentType, content) =>
            out.write(s"--$boundary\r\n".getBytes(StandardCharsets.UTF_8))
            val disposition = new StringBuilder("Content-Disposition: form-data; name=\"").append(name).append("\"")
            filename.foreach(f => disposition.append("; filename=\"").append(f).append("\""))
            out.write(disposition.append("\r\n").toString.getBytes(StandardCharsets.UTF_8))
            contentType.foreach(ct => out.write(s"Content-Type: $ct\r\n".getBytes(StandardCharsets.UTF_8)))
            out.write("\r\n".getBytes(StandardCharsets.UTF_8))
            out.write(content)
            out.write("\r\n".getBytes(StandardCharsets.UTF_8))
        }
        out.write(s"--$boundary--\r\n".getBytes(StandardCharsets.UTF_8))
        out.toByteArray
    end makeMultipartBody

    "single part in single chunk" in {
        val boundary = "testboundary"
        val body     = makeMultipartBody(boundary, Seq(("field1", None, None, "hello".getBytes)))
        val decoder  = MultipartStreamDecoder.init(boundary)
        val parts    = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        assert(parts(0).name == "field1")
        assert(new String(parts(0).data.toArrayUnsafe, "UTF-8") == "hello")
        assert(parts(0).filename == Absent)
        assert(parts(0).contentType == Absent)
        succeed
    }

    "single part split into 1-byte chunks" in {
        val boundary = "testboundary"
        val body     = makeMultipartBody(boundary, Seq(("field1", None, None, "hello".getBytes)))
        val decoder  = MultipartStreamDecoder.init(boundary)
        val allParts = Seq.newBuilder[HttpRequest.Part]
        body.foreach { b =>
            allParts ++= decoder.decode(Span.fromUnsafe(Array(b)))
        }
        val parts = allParts.result()
        assert(parts.size == 1)
        assert(parts(0).name == "field1")
        assert(new String(parts(0).data.toArrayUnsafe, "UTF-8") == "hello")
        succeed
    }

    "multiple parts in single chunk" in {
        val boundary = "myboundary"
        val body = makeMultipartBody(
            boundary,
            Seq(
                ("name", None, None, "Alice".getBytes),
                ("age", None, None, "30".getBytes),
                ("city", None, None, "NYC".getBytes)
            )
        )
        val decoder = MultipartStreamDecoder.init(boundary)
        val parts   = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 3)
        assert(parts(0).name == "name")
        assert(new String(parts(0).data.toArrayUnsafe, "UTF-8") == "Alice")
        assert(parts(1).name == "age")
        assert(new String(parts(1).data.toArrayUnsafe, "UTF-8") == "30")
        assert(parts(2).name == "city")
        assert(new String(parts(2).data.toArrayUnsafe, "UTF-8") == "NYC")
        succeed
    }

    "multiple parts across multiple chunks" in {
        val boundary = "split"
        val body = makeMultipartBody(
            boundary,
            Seq(
                ("a", None, None, "AAA".getBytes),
                ("b", None, None, "BBB".getBytes)
            )
        )
        val mid     = body.length / 2
        val chunk1  = java.util.Arrays.copyOfRange(body, 0, mid)
        val chunk2  = java.util.Arrays.copyOfRange(body, mid, body.length)
        val decoder = MultipartStreamDecoder.init(boundary)
        val parts1  = decoder.decode(Span.fromUnsafe(chunk1))
        val parts2  = decoder.decode(Span.fromUnsafe(chunk2))
        val all     = parts1 ++ parts2
        assert(all.size == 2)
        assert(all(0).name == "a")
        assert(new String(all(0).data.toArrayUnsafe, "UTF-8") == "AAA")
        assert(all(1).name == "b")
        assert(new String(all(1).data.toArrayUnsafe, "UTF-8") == "BBB")
        succeed
    }

    "boundary split across chunks" in {
        val boundary = "BOUNDARY"
        val body     = makeMultipartBody(boundary, Seq(("x", None, None, "data".getBytes)))
        // Split right in the middle of the first boundary marker
        val boundaryStr = s"--$boundary"
        val boundaryIdx = new String(body, StandardCharsets.UTF_8).indexOf(boundaryStr)
        val splitAt     = boundaryIdx + boundaryStr.length / 2
        val chunk1      = java.util.Arrays.copyOfRange(body, 0, splitAt)
        val chunk2      = java.util.Arrays.copyOfRange(body, splitAt, body.length)
        val decoder     = MultipartStreamDecoder.init(boundary)
        val parts1      = decoder.decode(Span.fromUnsafe(chunk1))
        val parts2      = decoder.decode(Span.fromUnsafe(chunk2))
        val all         = parts1 ++ parts2
        assert(all.size == 1)
        assert(all(0).name == "x")
        assert(new String(all(0).data.toArrayUnsafe, "UTF-8") == "data")
        succeed
    }

    "header split across chunks" in {
        val boundary = "hdrsplit"
        val body     = makeMultipartBody(boundary, Seq(("field", Some("file.txt"), Some("text/plain"), "content".getBytes)))
        // Split in the header area (after boundary line, in the middle of Content-Disposition)
        val bodyStr = new String(body, StandardCharsets.UTF_8)
        val dispIdx = bodyStr.indexOf("Content-Disposition")
        val splitAt = dispIdx + 10 // middle of "Content-Disposition"
        val chunk1  = java.util.Arrays.copyOfRange(body, 0, splitAt)
        val chunk2  = java.util.Arrays.copyOfRange(body, splitAt, body.length)
        val decoder = MultipartStreamDecoder.init(boundary)
        val parts1  = decoder.decode(Span.fromUnsafe(chunk1))
        val parts2  = decoder.decode(Span.fromUnsafe(chunk2))
        val all     = parts1 ++ parts2
        assert(all.size == 1)
        assert(all(0).name == "field")
        assert(all(0).filename == Present("file.txt"))
        assert(all(0).contentType == Present("text/plain"))
        assert(new String(all(0).data.toArrayUnsafe, "UTF-8") == "content")
        succeed
    }

    "empty content part" in {
        val boundary = "emptycontent"
        val body     = makeMultipartBody(boundary, Seq(("empty", None, None, Array.empty[Byte])))
        val decoder  = MultipartStreamDecoder.init(boundary)
        val parts    = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        assert(parts(0).name == "empty")
        assert(parts(0).data.isEmpty)
        succeed
    }

    "part with filename and content-type" in {
        val boundary = "filect"
        val body     = makeMultipartBody(boundary, Seq(("photo", Some("sunset.jpg"), Some("image/jpeg"), Array[Byte](1, 2, 3))))
        val decoder  = MultipartStreamDecoder.init(boundary)
        val parts    = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        assert(parts(0).name == "photo")
        assert(parts(0).filename == Present("sunset.jpg"))
        assert(parts(0).contentType == Present("image/jpeg"))
        assert(parts(0).data.toArrayUnsafe.toSeq == Seq[Byte](1, 2, 3))
        succeed
    }

    "part with only name" in {
        val boundary = "nameonly"
        val body     = makeMultipartBody(boundary, Seq(("just_name", None, None, "value".getBytes)))
        val decoder  = MultipartStreamDecoder.init(boundary)
        val parts    = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        assert(parts(0).name == "just_name")
        assert(parts(0).filename == Absent)
        assert(parts(0).contentType == Absent)
        succeed
    }

    "binary content preserved exactly" in {
        val boundary   = "bintest"
        val binaryData = Array[Byte](0x00, 0x01, 0x7f, 0x80.toByte, 0xff.toByte, 0x0d, 0x0a)
        val body       = makeMultipartBody(boundary, Seq(("bin", Some("data.bin"), Some("application/octet-stream"), binaryData)))
        val decoder    = MultipartStreamDecoder.init(boundary)
        val parts      = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        assert(parts(0).data.toArrayUnsafe.toSeq == binaryData.toSeq)
        succeed
    }

    "final boundary terminates - further decode returns empty" in {
        val boundary = "finalbnd"
        val body     = makeMultipartBody(boundary, Seq(("x", None, None, "y".getBytes)))
        val decoder  = MultipartStreamDecoder.init(boundary)
        val parts    = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        val moreParts = decoder.decode(Span.fromUnsafe("extra data".getBytes))
        assert(moreParts.isEmpty)
        succeed
    }

    "preamble before first boundary is ignored" in {
        val boundary = "preambletest"
        val preamble = "This is the preamble. It should be ignored.\r\n".getBytes(StandardCharsets.UTF_8)
        val body     = makeMultipartBody(boundary, Seq(("field", None, None, "value".getBytes)))
        val combined = new Array[Byte](preamble.length + body.length)
        java.lang.System.arraycopy(preamble, 0, combined, 0, preamble.length)
        java.lang.System.arraycopy(body, 0, combined, preamble.length, body.length)
        val decoder = MultipartStreamDecoder.init(boundary)
        val parts   = decoder.decode(Span.fromUnsafe(combined))
        assert(parts.size == 1)
        assert(parts(0).name == "field")
        assert(new String(parts(0).data.toArrayUnsafe, "UTF-8") == "value")
        succeed
    }

    "large binary file (>64KB)" in {
        val boundary  = "largebinary"
        val largeData = new Array[Byte](100 * 1024) // 100KB
        scala.util.Random.nextBytes(largeData)
        val body    = makeMultipartBody(boundary, Seq(("file", Some("large.bin"), Some("application/octet-stream"), largeData)))
        val decoder = MultipartStreamDecoder.init(boundary)
        val parts   = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        assert(parts(0).name == "file")
        assert(parts(0).filename == Present("large.bin"))
        assert(parts(0).data.size == largeData.length)
        assert(parts(0).data.toArrayUnsafe.toSeq == largeData.toSeq)
        succeed
    }

    "large binary file streamed in 8KB chunks" in {
        val boundary  = "chunkedlarge"
        val largeData = new Array[Byte](100 * 1024) // 100KB
        scala.util.Random.nextBytes(largeData)
        val body      = makeMultipartBody(boundary, Seq(("file", Some("big.dat"), Some("application/octet-stream"), largeData)))
        val decoder   = MultipartStreamDecoder.init(boundary)
        val allParts  = Seq.newBuilder[HttpRequest.Part]
        val chunkSize = 8192
        var offset    = 0
        while offset < body.length do
            val end   = Math.min(offset + chunkSize, body.length)
            val chunk = java.util.Arrays.copyOfRange(body, offset, end)
            allParts ++= decoder.decode(Span.fromUnsafe(chunk))
            offset = end
        end while
        val parts = allParts.result()
        assert(parts.size == 1)
        assert(parts(0).data.size == largeData.length)
        assert(parts(0).data.toArrayUnsafe.toSeq == largeData.toSeq)
        succeed
    }

    "non-ASCII filename" in {
        val boundary = "unicodename"
        val body     = makeMultipartBody(boundary, Seq(("file", Some("文档.pdf"), Some("application/pdf"), "pdf-data".getBytes)))
        val decoder  = MultipartStreamDecoder.init(boundary)
        val parts    = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        assert(parts(0).filename == Present("文档.pdf"))
        succeed
    }

    "part content containing boundary-like string" in {
        val boundary     = "mybnd"
        val trickContent = s"--mybnd\r\nContent-Disposition: form-data; name=\"fake\"\r\n\r\nfake data".getBytes(StandardCharsets.UTF_8)
        val body         = makeMultipartBody(boundary, Seq(("real", None, None, trickContent)))
        val decoder      = MultipartStreamDecoder.init(boundary)
        val parts        = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 1)
        assert(parts(0).name == "real")
        assert(parts(0).data.toArrayUnsafe.toSeq == trickContent.toSeq)
        succeed
    }

    "many small form fields" in {
        val boundary = "manyfields"
        val fields   = (1 to 50).map(i => (s"field$i", None, None, s"value$i".getBytes(StandardCharsets.UTF_8)))
        val body     = makeMultipartBody(boundary, fields)
        val decoder  = MultipartStreamDecoder.init(boundary)
        val parts    = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 50)
        (1 to 50).foreach { i =>
            assert(parts(i - 1).name == s"field$i")
            assert(new String(parts(i - 1).data.toArrayUnsafe, "UTF-8") == s"value$i")
        }
        succeed
    }

    "mixed text fields and binary files" in {
        val boundary   = "mixedcontent"
        val binaryData = Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47) // PNG magic bytes
        val body = makeMultipartBody(
            boundary,
            Seq(
                ("title", None, None, "My Photo".getBytes(StandardCharsets.UTF_8)),
                ("description", None, None, "A sunset".getBytes(StandardCharsets.UTF_8)),
                ("image", Some("sunset.png"), Some("image/png"), binaryData)
            )
        )
        val decoder = MultipartStreamDecoder.init(boundary)
        val parts   = decoder.decode(Span.fromUnsafe(body))
        assert(parts.size == 3)
        assert(parts(0).name == "title")
        assert(parts(0).filename == Absent)
        assert(new String(parts(0).data.toArrayUnsafe, "UTF-8") == "My Photo")
        assert(parts(2).name == "image")
        assert(parts(2).filename == Present("sunset.png"))
        assert(parts(2).contentType == Present("image/png"))
        assert(parts(2).data.toArrayUnsafe.toSeq == binaryData.toSeq)
        succeed
    }

end MultipartStreamDecoderTest
