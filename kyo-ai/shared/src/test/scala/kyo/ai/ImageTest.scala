package kyo.ai

import kyo.*

class ImageTest extends kyo.test.Test[Any]:

    "base64 round-trips through fromBase64/base64" in {
        val enc = Base64.encode(Span.from(Array[Byte](1, 2, 3)))
        assert(Image.fromBase64(enc).base64 == enc)
        assert(Image.fromBytes(Span.from(Array[Byte](1, 2, 3))).base64 == enc)
    }

    "equal bytes compare equal (CanEqual)" in {
        val a = Image.fromBase64("AAAA")
        val b = Image.fromBase64("AAAA")
        assert(a == b)
    }

    "Image Schema round-trips its base64 payload" in {
        val img = Image.fromBase64("SGVsbG8=")
        Json.decode[Image](Json.encode(img)) match
            case Result.Success(decoded) => assert(decoded == img, s"round-trip mismatch: $decoded")
            case other                   => assert(false, s"Image decode failed: $other")
    }

end ImageTest
