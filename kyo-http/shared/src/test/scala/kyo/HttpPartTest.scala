package kyo

import kyo.*

class HttpRequestPartTest extends Test:

    "construction" - {
        "minimal" in {
            val p = HttpRequest.Part("file", Absent, Absent, Span.empty[Byte])
            assert(p.name == "file")
            assert(p.filename == Absent)
            assert(p.contentType == Absent)
            assert(p.data.size == 0)
        }

        "with all fields" in {
            val data = Span.from(Array[Byte](1, 2, 3))
            val p    = HttpRequest.Part("upload", Present("photo.jpg"), Present("image/jpeg"), data)
            assert(p.name == "upload")
            assert(p.filename == Present("photo.jpg"))
            assert(p.contentType == Present("image/jpeg"))
            assert(p.data.size == 3)
        }

        "with filename only" in {
            val p = HttpRequest.Part("doc", Present("readme.txt"), Absent, Span.empty[Byte])
            assert(p.filename == Present("readme.txt"))
            assert(p.contentType == Absent)
        }

        "with content type only" in {
            val p = HttpRequest.Part("data", Absent, Present("application/json"), Span.empty[Byte])
            assert(p.contentType == Present("application/json"))
            assert(p.filename == Absent)
        }
    }

    "equality" - {
        "same parts are equal" in {
            val p1 = HttpRequest.Part("f", Absent, Absent, Span.empty[Byte])
            val p2 = HttpRequest.Part("f", Absent, Absent, Span.empty[Byte])
            assert(p1 == p2)
        }

        "different names are not equal" in {
            val p1 = HttpRequest.Part("a", Absent, Absent, Span.empty[Byte])
            val p2 = HttpRequest.Part("b", Absent, Absent, Span.empty[Byte])
            assert(p1 != p2)
        }
    }

end HttpRequestPartTest
