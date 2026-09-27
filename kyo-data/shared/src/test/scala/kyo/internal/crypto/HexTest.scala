package kyo.internal.crypto

import kyo.*

class HexTest extends kyo.test.Test[Any]:

    private val allBytes: Array[Byte] = Array.tabulate(256)(_.toByte)

    private def decoded(text: String): Maybe[Seq[Int]] =
        Hex.decode(text).map(_.toSeq.map(_ & 0xff))

    "encode" - {
        "an empty array is the empty string" in {
            assert(Hex.encode(Array.emptyByteArray) == "")
        }

        "renders each byte as two zero-padded lowercase digits" in {
            assert(Hex.encode(Array[Byte](0x00, 0x0f, 0xab.toByte, 0xff.toByte)) == "000fabff")
            assert(Hex.encode(Array[Byte](0x01)) == "01")
            assert(Hex.encode(Array[Byte](0x10)) == "10")
            assert(Hex.encode(Array[Byte](0x7f, 0x80.toByte)) == "7f80")
        }

        "renders every byte value" in {
            val expected = (0 until 256).map(v => f"$v%02x").mkString
            assert(Hex.encode(allBytes) == expected)
        }
    }

    "decode" - {
        "the empty string is an empty array" in {
            assert(decoded("") == Present(Seq.empty))
        }

        "reads lowercase digits" in {
            assert(decoded("000fabff") == Present(Seq(0x00, 0x0f, 0xab, 0xff)))
            assert(decoded("7f80") == Present(Seq(0x7f, 0x80)))
        }

        "reads uppercase digits" in {
            assert(decoded("000FABFF") == Present(Seq(0x00, 0x0f, 0xab, 0xff)))
        }

        "reads mixed case within one byte and across bytes" in {
            assert(decoded("aB") == Present(Seq(0xab)))
            assert(decoded("Ab") == Present(Seq(0xab)))
            assert(decoded("0aBcDeF9") == Present(Seq(0x0a, 0xbc, 0xde, 0xf9)))
        }

        "reads the boundary digits of each range" in {
            assert(decoded("09") == Present(Seq(0x09)))
            assert(decoded("af") == Present(Seq(0xaf)))
            assert(decoded("AF") == Present(Seq(0xaf)))
            assert(decoded("90") == Present(Seq(0x90)))
        }

        "round-trips every byte value in both cases" in {
            val text = Hex.encode(allBytes)
            assert(Hex.decode(text).map(_.sameElements(allBytes)) == Present(true))
            assert(Hex.decode(text.toUpperCase).map(_.sameElements(allBytes)) == Present(true))
        }

        "rejects an odd length" in {
            assert(Hex.decode("0").isEmpty)
            assert(Hex.decode("abc").isEmpty)
            assert(Hex.decode("000fabf").isEmpty)
        }

        "rejects the characters adjacent to each digit range" in {
            assert(Hex.decode("0/").isEmpty)
            assert(Hex.decode("0:").isEmpty)
            assert(Hex.decode("0@").isEmpty)
            assert(Hex.decode("0G").isEmpty)
            assert(Hex.decode("0`").isEmpty)
            assert(Hex.decode("0g").isEmpty)
        }

        "rejects a non-hex character in the high and in the low position" in {
            assert(Hex.decode("x0").isEmpty)
            assert(Hex.decode("0x").isEmpty)
            assert(Hex.decode("00ff0z").isEmpty)
        }

        "rejects whitespace, signs and a prefix" in {
            assert(Hex.decode("0 ").isEmpty)
            assert(Hex.decode(" 0").isEmpty)
            assert(Hex.decode("-1").isEmpty)
            assert(Hex.decode("+1").isEmpty)
            assert(Hex.decode("0x00").isEmpty)
        }

        "rejects non-ASCII digits and letters" in {
            assert(Hex.decode("００").isEmpty)
            assert(Hex.decode("0٠").isEmpty)
            assert(Hex.decode("éa").isEmpty)
        }
    }

end HexTest
