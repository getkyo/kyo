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
    }

end HttpRequestMultipartTest
