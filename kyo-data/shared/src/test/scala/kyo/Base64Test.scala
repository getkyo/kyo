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
                case Result.Failure(_) => succeed("input with length not divisible by 4 is rejected; matching Failure is the verification")
                case other             => fail(s"expected Failure, got $other")
        }

        "rejects input with illegal characters" in {
            // '!' is not in the standard alphabet.
            val r = Base64.decode("Zm9!")
            r match
                case Result.Failure(_) =>
                    succeed("input containing illegal character '!' is rejected; matching Failure is the verification")
                case other => fail(s"expected Failure, got $other")
            end match
        }

        "rejects padding before the final quartet" in {
            assert(Base64.decode("Zg==Zg==").isFailure)
            assert(Base64.decode("Zm8=Zm8=").isFailure)
            assert(Base64.decode("Zg==Zm9v").isFailure)
        }

        "rejects a data character after padding in the final quartet" in {
            assert(Base64.decode("Zg=A").isFailure)
            assert(Base64.decode("Zm9vZg=A").isFailure)
        }

        "rejects a quartet made only of padding" in {
            assert(Base64.decode("====").isFailure)
            assert(Base64.decode("Zm9v====").isFailure)
            assert(Base64.decode("A===").isFailure)
        }

        "ignores nonzero bits after the last byte" in {
            assert(Base64.decode("Zh==").map(_.toArray.toSeq) == Result.succeed(Seq[Byte](0x66)))
            assert(Base64.decode("Zm9=").map(_.toArray.toSeq) == Result.succeed(Seq[Byte](0x66, 0x6f)))
        }

        "rejects the base64url alphabet" in {
            assert(Base64.decode("-_8=").isFailure)
        }

        "decodeOrThrow throws on malformed input" in {
            try
                val _ = Base64.decodeOrThrow("***")
                fail("expected IllegalArgumentException")
            catch
                case _: IllegalArgumentException =>
                    succeed("IllegalArgumentException thrown for malformed input; catching it is the verification")
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
            assert(Base64.decodeUrl("Zg==").isFailure)
            assert(Base64.decodeUrl("Zg=").isFailure)
            assert(Base64.decodeUrl("Zm8=").isFailure)
            assert(Base64.decodeUrl("Zm9v=").isFailure)
            assert(Base64.decodeUrl("=").isFailure)
        }

        "rejects + and / from the standard alphabet" in {
            assert(Base64.decodeUrl("+/8").isFailure)
            assert(Base64.decodeUrl("-+8").isFailure)
            assert(Base64.decodeUrl("_/8").isFailure)
            assert(Base64.decodeUrl("////").isFailure)
            assert(Base64.decodeUrl("Zm9v+w").isFailure)
        }

        "rejects a length of 1 modulo 4" in {
            assert(Base64.decodeUrl("Z").isFailure)
            assert(Base64.decodeUrl("Zm9vY").isFailure)
            assert(Base64.decodeUrl("Zm9vYmFyZ").isFailure)
        }

        "rejects nonzero bits after the last byte" in {
            assert(Base64.decodeUrl("Zh").isFailure)
            assert(Base64.decodeUrl("Zv").isFailure)
            assert(Base64.decodeUrl("Zm9").isFailure)
            assert(Base64.decodeUrl("Zm_").isFailure)
            assert(Base64.decodeUrl("_x").isFailure)
            assert(Base64.decodeUrl("Zm9vYh").isFailure)
        }

        "rejects characters outside the alphabet" in {
            assert(Base64.decodeUrl("Zm9!").isFailure)
            assert(Base64.decodeUrl("Zm9v Zg").isFailure)
            assert(Base64.decodeUrl("Zm9v\nZg").isFailure)
            assert(Base64.decodeUrl("Zm9é").isFailure)
            assert(Base64.decodeUrl("Zm9\u0000").isFailure)
        }

        "reports the offset of the first illegal character" in {
            assert(Base64.decodeUrl("Zm9v+w").failure.map(_.getMessage) == Maybe("Illegal Base64 character in input at offset 4"))
            assert(Base64.decodeUrl("Zm8=").failure.map(_.getMessage) == Maybe("Unexpected Base64 padding in input at offset 3"))
        }
    }

    private val allBytes: Span[Byte] = Span.from((0 to 255).map(_.toByte).toArray)

    private def decodeUrlBytes(s: String): Result[IllegalArgumentException, Seq[Int]] =
        Base64.decodeUrl(s).map(_.toArray.toSeq.map(_ & 0xff))

    private def decodeUrlText(s: String): Result[IllegalArgumentException, String] =
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
        val decoded = Base64.decodeOrThrow(encoded)
        val arr     = new Array[Char](decoded.size)
        var i       = 0
        while i < decoded.size do
            arr(i) = (decoded(i) & 0xff).toChar
            i += 1
        new String(arr)
    end roundTrip

end Base64Test
