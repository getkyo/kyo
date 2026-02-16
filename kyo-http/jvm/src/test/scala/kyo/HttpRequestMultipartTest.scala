package kyo

import HttpRequest.*

/** Multipart tests are JVM-only because HttpRequest.multipart uses java.util.UUID.randomUUID() which doesn't link on JS. */
class HttpRequestMultipartTest extends Test:

    "multipart constructor" - {
        "with single part" in {
            val parts   = Seq(Part("file", Present("test.txt"), Present("text/plain"), "content".getBytes))
            val request = HttpRequest.multipart("http://example.com/upload", parts)
            assert(request.method == Method.POST)
            assert(request.parts.size == 1)
        }

        "with multiple parts" in {
            val parts = Seq(
                Part("file1", Present("a.txt"), Present("text/plain"), "a".getBytes),
                Part("file2", Present("b.txt"), Present("text/plain"), "b".getBytes)
            )
            val request = HttpRequest.multipart("http://example.com/upload", parts)
            assert(request.parts.size == 2)
        }

        "with empty parts" in {
            val request = HttpRequest.multipart("http://example.com/upload", Seq.empty)
            assert(request.parts.isEmpty)
        }

        "with headers" in {
            val parts = Seq(Part("data", Absent, Absent, Array.empty[Byte]))
            val request = HttpRequest.multipart(
                "http://example.com/upload",
                parts,
                HttpHeaders.empty.add("Authorization", "Bearer token")
            )
            assert(request.header("Authorization") == Present("Bearer token"))
        }

        "sets content-type to multipart/form-data" in {
            val parts   = Seq(Part("file", Absent, Absent, Array.empty[Byte]))
            val request = HttpRequest.multipart("http://example.com/upload", parts)
            val ct      = request.contentType
            assert(ct.isDefined)
            assert(ct.get.startsWith("multipart/form-data"))
            assert(ct.get.contains("boundary="))
        }

        "sets method to POST" in {
            val request = HttpRequest.multipart("http://example.com/upload", Seq(Part("f", Absent, Absent, Array.empty[Byte])))
            assert(request.method == Method.POST)
        }

        "sets correct URL" in {
            val request = HttpRequest.multipart("http://example.com/upload", Seq(Part("f", Absent, Absent, Array.empty[Byte])))
            assert(request.path == "/upload")
        }
    }

    "multipart parts parsing" - {
        "returns multipart parts" in {
            val parts = Seq(
                Part("file", Present("test.txt"), Present("text/plain"), "content".getBytes)
            )
            val request = HttpRequest.multipart("http://example.com/upload", parts)
            // Call parts once and store result - buffer may be consumed
            val parsedParts = request.parts
            assert(parsedParts.size == 1)
            assert(parsedParts(0).name == "file")
        }

        "preserves part order" in {
            val parts = Seq(
                Part("first", Absent, Absent, Array.empty[Byte]),
                Part("second", Absent, Absent, Array.empty[Byte]),
                Part("third", Absent, Absent, Array.empty[Byte])
            )
            val request = HttpRequest.multipart("http://example.com/upload", parts)
            // Call parts once and store result - buffer is consumed on read
            val parsedParts = request.parts
            assert(parsedParts(0).name == "first")
            assert(parsedParts(1).name == "second")
            assert(parsedParts(2).name == "third")
        }

        "preserves filename" in {
            val parts = Seq(
                Part("doc", Present("report.pdf"), Present("application/pdf"), "data".getBytes)
            )
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(parsedParts(0).filename == Present("report.pdf"))
        }

        "preserves content type" in {
            val parts = Seq(
                Part("image", Present("photo.png"), Present("image/png"), "pixels".getBytes)
            )
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(parsedParts(0).contentType == Present("image/png"))
        }

        "preserves content bytes" in {
            val content = "hello world".getBytes("UTF-8")
            val parts   = Seq(Part("file", Present("test.txt"), Present("text/plain"), content))
            val request = HttpRequest.multipart("http://example.com/upload", parts)
            val parsed  = request.parts(0)
            assert(new String(parsed.content, "UTF-8") == "hello world")
        }

        "handles part without filename" in {
            val parts       = Seq(Part("field", Absent, Absent, "value".getBytes))
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(parsedParts(0).name == "field")
            assert(parsedParts(0).filename == Absent)
            assert(new String(parsedParts(0).content, "UTF-8") == "value")
        }

        "handles part without content type" in {
            val parts       = Seq(Part("field", Present("data.bin"), Absent, Array[Byte](1, 2, 3)))
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(parsedParts(0).contentType == Absent)
        }

        "handles binary content" in {
            // Binary data including bytes that could look like boundary markers
            val binaryContent = Array.tabulate[Byte](256)(i => i.toByte)
            val parts         = Seq(Part("bin", Present("data.bin"), Present("application/octet-stream"), binaryContent))
            val request       = HttpRequest.multipart("http://example.com/upload", parts)
            val parsed        = request.parts(0)
            assert(parsed.content.length == 256)
            assert(parsed.content.sameElements(binaryContent))
        }

        "handles mixed form fields and file parts" in {
            val parts = Seq(
                Part("description", Absent, Absent, "A photo of sunset".getBytes),
                Part("file", Present("sunset.jpg"), Present("image/jpeg"), Array[Byte](0xff.toByte, 0xd8.toByte, 0xff.toByte)),
                Part("tags", Absent, Absent, "nature,sunset".getBytes)
            )
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(parsedParts.size == 3)

            // Form field
            assert(parsedParts(0).name == "description")
            assert(parsedParts(0).filename == Absent)
            assert(new String(parsedParts(0).content, "UTF-8") == "A photo of sunset")

            // File
            assert(parsedParts(1).name == "file")
            assert(parsedParts(1).filename == Present("sunset.jpg"))
            assert(parsedParts(1).contentType == Present("image/jpeg"))

            // Another form field
            assert(parsedParts(2).name == "tags")
            assert(new String(parsedParts(2).content, "UTF-8") == "nature,sunset")
        }

        "handles large content" in {
            val largeContent = new Array[Byte](10000)
            java.util.Arrays.fill(largeContent, 'X'.toByte)
            val parts       = Seq(Part("big", Present("large.dat"), Present("application/octet-stream"), largeContent))
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(parsedParts(0).content.length == 10000)
            assert(parsedParts(0).content.forall(_ == 'X'.toByte))
        }

        "handles empty content" in {
            val parts       = Seq(Part("empty", Present("empty.txt"), Present("text/plain"), Array.empty[Byte]))
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(parsedParts(0).content.isEmpty)
        }

        "handles content with CRLF bytes" in {
            val content     = "line1\r\nline2\r\nline3".getBytes("UTF-8")
            val parts       = Seq(Part("text", Present("lines.txt"), Present("text/plain"), content))
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(new String(parsedParts(0).content, "UTF-8") == "line1\r\nline2\r\nline3")
        }

        "handles many parts" in {
            val parts = (1 to 20).map { i =>
                Part(s"field$i", Present(s"file$i.txt"), Present("text/plain"), s"content$i".getBytes)
            }
            val request     = HttpRequest.multipart("http://example.com/upload", parts)
            val parsedParts = request.parts
            assert(parsedParts.size == 20)
            assert(parsedParts(0).name == "field1")
            assert(new String(parsedParts(0).content, "UTF-8") == "content1")
            assert(parsedParts(19).name == "field20")
            assert(new String(parsedParts(19).content, "UTF-8") == "content20")
        }
    }

    "Part validation" - {
        "rejects empty name" in {
            assertThrows[IllegalArgumentException] {
                Part("", Absent, Absent, Array.empty[Byte])
            }
        }
    }

    "non-multipart request" - {
        "parts returns empty for plain POST" in {
            val request = HttpRequest.post("http://example.com/data", "plain body")
            assert(request.parts.isEmpty)
        }

        "parts returns empty for GET" in {
            val request = HttpRequest.get("http://example.com/data")
            assert(request.parts.isEmpty)
        }
    }

end HttpRequestMultipartTest
