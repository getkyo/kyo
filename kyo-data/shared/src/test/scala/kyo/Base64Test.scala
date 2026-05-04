package kyo

class Base64Test extends Test:

    "encode" - {
        "empty input produces empty string" in {
            assert(Base64.encode(Span.empty[Byte]) == "")
        }

        "RFC 4648 standard test vectors" in {
            assert(Base64.encode(bytesOf("")) == "")
            assert(Base64.encode(bytesOf("f")) == "Zg==")
            assert(Base64.encode(bytesOf("fo")) == "Zm8=")
            assert(Base64.encode(bytesOf("foo")) == "Zm9v")
            assert(Base64.encode(bytesOf("foob")) == "Zm9vYg==")
            assert(Base64.encode(bytesOf("fooba")) == "Zm9vYmE=")
            assert(Base64.encode(bytesOf("foobar")) == "Zm9vYmFy")
        }

        "encodes binary bytes covering the full byte range" in {
            // 0..255 inclusive, exercising every byte value.
            val bytes   = Span.from((0 to 255).map(_.toByte).toArray)
            val encoded = Base64.encode(bytes)
            val decoded = Base64.decodeOrThrow(encoded)
            assert(decoded.size == bytes.size)
            var equal = true
            var i     = 0
            while i < bytes.size do
                if decoded(i) != bytes(i) then equal = false
                i += 1
            assert(equal)
        }
    }

    "decode" - {
        "empty input produces empty span" in {
            val r = Base64.decode("")
            r match
                case Result.Success(span) => assert(span.size == 0)
                case other                => fail(s"expected Success, got $other")
        }

        "RFC 4648 standard test vectors round-trip" in {
            assert(roundTrip("") == "")
            assert(roundTrip("f") == "f")
            assert(roundTrip("fo") == "fo")
            assert(roundTrip("foo") == "foo")
            assert(roundTrip("foob") == "foob")
            assert(roundTrip("fooba") == "fooba")
            assert(roundTrip("foobar") == "foobar")
        }

        "rejects input whose length is not a multiple of 4" in {
            val r = Base64.decode("abc")
            r match
                case Result.Failure(_) => succeed
                case other             => fail(s"expected Failure, got $other")
        }

        "rejects input with illegal characters" in {
            // '!' is not in the standard alphabet.
            val r = Base64.decode("Zm9!")
            r match
                case Result.Failure(_) => succeed
                case other             => fail(s"expected Failure, got $other")
        }

        "decodeOrThrow throws on malformed input" in {
            try
                val _ = Base64.decodeOrThrow("***")
                fail("expected IllegalArgumentException")
            catch case _: IllegalArgumentException => succeed
        }
    }

    private def bytesOf(s: String): Span[Byte] =
        // Build a Span[Byte] from a String's ASCII codepoints without depending on java.nio.charset.
        val arr = new Array[Byte](s.length)
        var i   = 0
        while i < s.length do
            arr(i) = s.charAt(i).toByte
            i += 1
        Span.fromUnsafe(arr)
    end bytesOf

    private def roundTrip(s: String): String =
        val encoded = Base64.encode(bytesOf(s))
        val decoded = Base64.decodeOrThrow(encoded)
        val arr     = new Array[Char](decoded.size)
        var i       = 0
        while i < decoded.size do
            arr(i) = (decoded(i) & 0xff).toChar
            i += 1
        new String(arr)
    end roundTrip

end Base64Test
