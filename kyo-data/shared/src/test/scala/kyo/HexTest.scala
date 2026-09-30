package kyo

class HexTest extends kyo.test.Test[Any]:

    private val allBytes: Array[Byte] = Array.tabulate(256)(_.toByte)

    private def decoded(text: String): Result[Hex.Failure, Seq[Int]] =
        Hex.decode(text).map(_.toArray.toSeq.map(_ & 0xff))

    private def failure(text: String): Maybe[Hex.Failure] =
        Hex.decode(text).failure

    "encode" - {
        "an empty span is the empty string" in {
            assert(Hex.encode(Span.empty[Byte]) == "")
        }

        "renders each byte as two zero-padded lowercase digits" in {
            assert(Hex.encode(Span.from(Array[Byte](0x00, 0x0f, 0xab.toByte, 0xff.toByte))) == "000fabff")
            assert(Hex.encode(Span.from(Array[Byte](0x01))) == "01")
            assert(Hex.encode(Span.from(Array[Byte](0x10))) == "10")
            assert(Hex.encode(Span.from(Array[Byte](0x7f, 0x80.toByte))) == "7f80")
        }

        "renders every byte value" in {
            val expected = (0 until 256).map(v => f"$v%02x").mkString
            assert(Hex.encode(Span.from(allBytes)) == expected)
        }

        "the array tier renders the same text" in {
            assert(Hex.encodeArray(Array.emptyByteArray) == "")
            assert(Hex.encodeArray(allBytes) == Hex.encode(Span.from(allBytes)))
        }
    }

    "decode" - {
        "the empty string is an empty span" in {
            assert(decoded("") == Result.succeed(Seq.empty))
        }

        "reads lowercase digits" in {
            assert(decoded("000fabff") == Result.succeed(Seq(0x00, 0x0f, 0xab, 0xff)))
            assert(decoded("7f80") == Result.succeed(Seq(0x7f, 0x80)))
        }

        "reads uppercase digits" in {
            assert(decoded("000FABFF") == Result.succeed(Seq(0x00, 0x0f, 0xab, 0xff)))
        }

        "reads mixed case within one byte and across bytes" in {
            assert(decoded("aB") == Result.succeed(Seq(0xab)))
            assert(decoded("Ab") == Result.succeed(Seq(0xab)))
            assert(decoded("0aBcDeF9") == Result.succeed(Seq(0x0a, 0xbc, 0xde, 0xf9)))
        }

        "reads the boundary digits of each range" in {
            assert(decoded("09") == Result.succeed(Seq(0x09)))
            assert(decoded("af") == Result.succeed(Seq(0xaf)))
            assert(decoded("AF") == Result.succeed(Seq(0xaf)))
            assert(decoded("90") == Result.succeed(Seq(0x90)))
        }

        "round-trips every byte value in both cases" in {
            val text = Hex.encode(Span.from(allBytes))
            assert(Hex.decode(text).map(_.toArray.toSeq) == Result.succeed(allBytes.toSeq))
            assert(Hex.decode(text.toUpperCase).map(_.toArray.toSeq) == Result.succeed(allBytes.toSeq))
        }

        "rejects an odd length, naming it" in {
            assert(failure("0") == Present(Hex.Failure.OddLength(1)))
            assert(failure("abc") == Present(Hex.Failure.OddLength(3)))
            assert(failure("000fabf") == Present(Hex.Failure.OddLength(7)))
        }

        "an odd length is reported before an illegal character" in {
            assert(failure("0g0") == Present(Hex.Failure.OddLength(3)))
        }

        "rejects the characters adjacent to each digit range, at their offset" in {
            assert(failure("0/") == Present(Hex.Failure.IllegalCharacter(1)))
            assert(failure("0:") == Present(Hex.Failure.IllegalCharacter(1)))
            assert(failure("0@") == Present(Hex.Failure.IllegalCharacter(1)))
            assert(failure("0G") == Present(Hex.Failure.IllegalCharacter(1)))
            assert(failure("0`") == Present(Hex.Failure.IllegalCharacter(1)))
            assert(failure("0g") == Present(Hex.Failure.IllegalCharacter(1)))
        }

        "rejects a non-hex character in the high and in the low position" in {
            assert(failure("x0") == Present(Hex.Failure.IllegalCharacter(0)))
            assert(failure("0x") == Present(Hex.Failure.IllegalCharacter(1)))
            assert(failure("00ff0z") == Present(Hex.Failure.IllegalCharacter(5)))
        }

        "names the first illegal character when both digits of a byte are illegal" in {
            assert(failure("00zz") == Present(Hex.Failure.IllegalCharacter(2)))
        }

        "rejects whitespace, signs and a prefix" in {
            assert(failure("0 ") == Present(Hex.Failure.IllegalCharacter(1)))
            assert(failure(" 0") == Present(Hex.Failure.IllegalCharacter(0)))
            assert(failure("-1") == Present(Hex.Failure.IllegalCharacter(0)))
            assert(failure("+1") == Present(Hex.Failure.IllegalCharacter(0)))
            assert(failure("0x00") == Present(Hex.Failure.IllegalCharacter(1)))
        }

        "rejects non-ASCII digits and letters" in {
            assert(failure("００") == Present(Hex.Failure.IllegalCharacter(0)))
            assert(failure("0٠") == Present(Hex.Failure.IllegalCharacter(1)))
            assert(failure("éa") == Present(Hex.Failure.IllegalCharacter(0)))
        }
    }

    "Hex.Failure.message" - {
        "names the odd length" in {
            assert(Hex.Failure.OddLength(3).message == "Hex input length must be even (got 3)")
        }

        "names the offset of the illegal character" in {
            assert(Hex.Failure.IllegalCharacter(5).message == "Illegal hex character in input at offset 5")
        }
    }

end HexTest
