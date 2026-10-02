package kyo

class Base64Test extends kyo.test.Test[Any]:

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
            val decoded = decodeValid(encoded)
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
            assert(decodeFailure("abc") == Maybe(Base64.Failure.BadLength(3)))
            assert(decodeFailure("Zm9vYg==Z") == Maybe(Base64.Failure.BadLength(9)))
        }

        "rejects input with illegal characters" in {
            // '!' is not in the standard alphabet.
            assert(decodeFailure("Zm9!") == Maybe(Base64.Failure.IllegalCharacter(3)))
        }

        "rejects padding before the final quartet" in {
            assert(decodeFailure("Zg==Zg==") == Maybe(Base64.Failure.UnexpectedPadding(2)))
            assert(decodeFailure("Zm8=Zm8=") == Maybe(Base64.Failure.UnexpectedPadding(3)))
            assert(decodeFailure("Zg==Zm9v") == Maybe(Base64.Failure.UnexpectedPadding(2)))
        }

        "rejects a data character after padding in the final quartet" in {
            assert(decodeFailure("Zg=A") == Maybe(Base64.Failure.UnexpectedPadding(2)))
            assert(decodeFailure("Zm9vZg=A") == Maybe(Base64.Failure.UnexpectedPadding(6)))
        }

        "rejects a quartet made only of padding" in {
            assert(decodeFailure("====") == Maybe(Base64.Failure.UnexpectedPadding(0)))
            assert(decodeFailure("Zm9v====") == Maybe(Base64.Failure.UnexpectedPadding(4)))
            assert(decodeFailure("A===") == Maybe(Base64.Failure.UnexpectedPadding(1)))
        }

        "ignores nonzero bits after the last byte" in {
            assert(Base64.decode("Zh==").map(_.toArray.toSeq) == Result.succeed(Seq[Byte](0x66)))
            assert(Base64.decode("Zm9=").map(_.toArray.toSeq) == Result.succeed(Seq[Byte](0x66, 0x6f)))
        }

        "rejects the base64url alphabet" in {
            assert(decodeFailure("-_8=") == Maybe(Base64.Failure.IllegalCharacter(0)))
        }

        "a failure's message names what was found and where" in {
            assert(decodeFailure("****").map(_.message) == Maybe("Illegal Base64 character in input at offset 0"))
        }

        "every failure has its own message" in {
            assert(Base64.Failure.IllegalCharacter(3).message == "Illegal Base64 character in input at offset 3")
            assert(Base64.Failure.UnexpectedPadding(2).message == "Unexpected Base64 padding in input at offset 2")
            assert(Base64.Failure.BadLength(5).message == "Base64 input length must be a multiple of 4 (got 5)")
            assert(Base64.Failure.DanglingCharacter(9).message == "Base64 input of 9 data characters encodes no byte sequence")
            assert(Base64.Failure.NonCanonicalTail.message == "Base64 input has nonzero bits after its last byte")
        }
    }

    "encodeUrl" - {
        "empty input produces empty string" in {
            assert(Base64.encodeUrl(Span.empty[Byte]) == "")
        }

        "RFC 4648 section 10 vectors in the unpadded url-safe form" in {
            assert(Base64.encodeUrl(bytesOf("")) == "")
            assert(Base64.encodeUrl(bytesOf("f")) == "Zg")
            assert(Base64.encodeUrl(bytesOf("fo")) == "Zm8")
            assert(Base64.encodeUrl(bytesOf("foo")) == "Zm9v")
            assert(Base64.encodeUrl(bytesOf("foob")) == "Zm9vYg")
            assert(Base64.encodeUrl(bytesOf("fooba")) == "Zm9vYmE")
            assert(Base64.encodeUrl(bytesOf("foobar")) == "Zm9vYmFy")
        }

        "uses - and _ where the standard alphabet uses + and /" in {
            assert(Base64.encode(Span[Byte](0xfb.toByte, 0xff.toByte)) == "+/8=")
            assert(Base64.encodeUrl(Span[Byte](0xfb.toByte, 0xff.toByte)) == "-_8")
            assert(Base64.encode(Span[Byte](0xfb.toByte, 0xef.toByte, 0xbe.toByte)) == "++++")
            assert(Base64.encodeUrl(Span[Byte](0xfb.toByte, 0xef.toByte, 0xbe.toByte)) == "----")
            assert(Base64.encode(Span[Byte](0xff.toByte, 0xff.toByte, 0xff.toByte)) == "////")
            assert(Base64.encodeUrl(Span[Byte](0xff.toByte, 0xff.toByte, 0xff.toByte)) == "____")
            assert(Base64.encodeUrl(Span[Byte](0x03.toByte, 0xef.toByte, 0xff.toByte, 0xff.toByte)) == "A-___w")
        }

        "matches the standard encoding with the alphabet swapped and padding dropped at every length" in {
            (0 to allBytes.size).foreach { n =>
                val prefix   = allBytes.take(n)
                val expected = Base64.encode(prefix).replace('+', '-').replace('/', '_').replace("=", "")
                assert(Base64.encodeUrl(prefix) == expected)
            }
        }
    }

    "decodeUrl" - {
        "empty input produces empty span" in {
            assert(Base64.decodeUrl("").map(_.size) == Result.succeed(0))
        }

        "RFC 4648 section 10 vectors in the unpadded url-safe form" in {
            assert(decodeUrlText("") == Result.succeed(""))
            assert(decodeUrlText("Zg") == Result.succeed("f"))
            assert(decodeUrlText("Zm8") == Result.succeed("fo"))
            assert(decodeUrlText("Zm9v") == Result.succeed("foo"))
            assert(decodeUrlText("Zm9vYg") == Result.succeed("foob"))
            assert(decodeUrlText("Zm9vYmE") == Result.succeed("fooba"))
            assert(decodeUrlText("Zm9vYmFy") == Result.succeed("foobar"))
        }

        "decodes - and _" in {
            assert(decodeUrlBytes("-_8") == Result.succeed(Seq(0xfb, 0xff)))
            assert(decodeUrlBytes("----") == Result.succeed(Seq(0xfb, 0xef, 0xbe)))
            assert(decodeUrlBytes("____") == Result.succeed(Seq(0xff, 0xff, 0xff)))
            assert(decodeUrlBytes("A-___w") == Result.succeed(Seq(0x03, 0xef, 0xff, 0xff)))
            assert(decodeUrlBytes("_w") == Result.succeed(Seq(0xff)))
        }

        "round-trips every byte value at every length" in {
            (0 to allBytes.size).foreach { n =>
                val prefix = allBytes.take(n)
                assert(Base64.decodeUrl(Base64.encodeUrl(prefix)).map(_.is(prefix)) == Result.succeed(true))
            }
        }

        "rejects padding" in {
            assert(decodeUrlFailure("Zg==") == Maybe(Base64.Failure.UnexpectedPadding(2)))
            assert(decodeUrlFailure("Zg=") == Maybe(Base64.Failure.UnexpectedPadding(2)))
            assert(decodeUrlFailure("Zm8=") == Maybe(Base64.Failure.UnexpectedPadding(3)))
            // A padded body of length 1 mod 4 fails the length check before any character is read.
            assert(decodeUrlFailure("Zm9v=") == Maybe(Base64.Failure.DanglingCharacter(5)))
            assert(decodeUrlFailure("=") == Maybe(Base64.Failure.DanglingCharacter(1)))
        }

        "rejects + and / from the standard alphabet" in {
            assert(decodeUrlFailure("+/8") == Maybe(Base64.Failure.IllegalCharacter(0)))
            assert(decodeUrlFailure("-+8") == Maybe(Base64.Failure.IllegalCharacter(1)))
            assert(decodeUrlFailure("_/8") == Maybe(Base64.Failure.IllegalCharacter(1)))
            assert(decodeUrlFailure("////") == Maybe(Base64.Failure.IllegalCharacter(0)))
            assert(decodeUrlFailure("Zm9v+w") == Maybe(Base64.Failure.IllegalCharacter(4)))
        }

        "rejects a length of 1 modulo 4" in {
            assert(decodeUrlFailure("Z") == Maybe(Base64.Failure.DanglingCharacter(1)))
            assert(decodeUrlFailure("Zm9vY") == Maybe(Base64.Failure.DanglingCharacter(5)))
            assert(decodeUrlFailure("Zm9vYmFyZ") == Maybe(Base64.Failure.DanglingCharacter(9)))
        }

        "rejects nonzero bits after the last byte" in {
            val nonCanonical = Maybe(Base64.Failure.NonCanonicalTail)
            assert(decodeUrlFailure("Zh") == nonCanonical)
            assert(decodeUrlFailure("Zv") == nonCanonical)
            assert(decodeUrlFailure("Zm9") == nonCanonical)
            assert(decodeUrlFailure("Zm_") == nonCanonical)
            assert(decodeUrlFailure("_x") == nonCanonical)
            assert(decodeUrlFailure("Zm9vYh") == nonCanonical)
        }

        "rejects characters outside the alphabet" in {
            assert(decodeUrlFailure("Zm9!") == Maybe(Base64.Failure.IllegalCharacter(3)))
            assert(decodeUrlFailure("Zm9v Zg") == Maybe(Base64.Failure.IllegalCharacter(4)))
            assert(decodeUrlFailure("Zm9v\nZg") == Maybe(Base64.Failure.IllegalCharacter(4)))
            assert(decodeUrlFailure("Zm9é") == Maybe(Base64.Failure.IllegalCharacter(3)))
            assert(decodeUrlFailure("Zm9\u0000") == Maybe(Base64.Failure.IllegalCharacter(3)))
        }

        "reports the offset of the first illegal character" in {
            assert(decodeUrlFailure("Zm9v+w+w") == Maybe(Base64.Failure.IllegalCharacter(4)))
            assert(decodeUrlFailure("Zm9vZm8=") == Maybe(Base64.Failure.UnexpectedPadding(7)))
        }
    }

    private val allBytes: Span[Byte] = Span.from((0 to 255).map(_.toByte).toArray)

    private def decodeFailure(s: String): Maybe[Base64.Failure] =
        Base64.decode(s).failure

    private def decodeValid(s: String): Span[Byte] =
        Base64.decode(s) match
            case Result.Success(bytes) => bytes
            case other                 => throw new IllegalStateException(s"expected valid base64, got $other")

    private def decodeUrlFailure(s: String): Maybe[Base64.Failure] =
        Base64.decodeUrl(s).failure

    private def decodeUrlBytes(s: String): Result[Base64.Failure, Seq[Int]] =
        Base64.decodeUrl(s).map(_.toArray.toSeq.map(_ & 0xff))

    private def decodeUrlText(s: String): Result[Base64.Failure, String] =
        Base64.decodeUrl(s).map(span => new String(span.toArray.map(b => (b & 0xff).toChar)))

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
        val decoded = decodeValid(encoded)
        val arr     = new Array[Char](decoded.size)
        var i       = 0
        while i < decoded.size do
            arr(i) = (decoded(i) & 0xff).toChar
            i += 1
        new String(arr)
    end roundTrip

end Base64Test
