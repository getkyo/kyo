package kyo.internal.crypto

import kyo.crypto.Ed25519Reference

/** [[Ed25519Scalar]] against `BigInt` arithmetic modulo `L`: the reduction on the multiples of `L` and their neighbours, the bound check
  * on both sides of `L`, and the signed-digit recoding checked as a sum and for the digit properties the multiplication relies on.
  */
class Ed25519ScalarTest extends kyo.test.Test[Any]:

    import Ed25519ScalarTest.*

    "reduce" - {
        "is the 64-byte value modulo L on 0, the multiples of L, their neighbours, and the top of the range" in {
            val values =
                Seq(
                    BigInt(0),
                    BigInt(1),
                    L - 1,
                    L,
                    L + 1,
                    (BigInt(1) << 252),
                    (BigInt(1) << 255) - 1,
                    (BigInt(1) << 256) - 1,
                    (BigInt(1) << 512) - 1
                ) ++
                    Seq(2, 3, 7, 8, 255, 256).flatMap(k => Seq(L * k - 1, L * k, L * k + 1)) ++
                    Seq(((BigInt(1) << 512) / L) * L, ((BigInt(1) << 512) / L) * L - 1)
            values.foreach { v =>
                assert(reduced(v) == v.mod(L), s"value $v")
            }
        }

        "matches on seeded 64-byte inputs and on 32-byte inputs below and above L" in {
            val random = new java.util.Random(0x4013)
            (0 until 200).foreach { _ =>
                val bytes = new Array[Byte](64)
                random.nextBytes(bytes)
                val v = Ed25519Reference.littleEndian(bytes, 0, 64)
                assert(reduced(v) == v.mod(L))
            }
            (0 until 100).foreach { _ =>
                val bytes = new Array[Byte](32)
                random.nextBytes(bytes)
                val v = Ed25519Reference.littleEndian(bytes, 0, 32)
                assert(reduced(v) == v.mod(L))
            }
        }

        "leaves the hash unchanged and reads only its 64 bytes" in {
            val hash = Array.tabulate[Byte](64)(i => (i * 13 + 7).toByte)
            val kept = hash.clone()
            val out  = new Array[Byte](32)
            Ed25519Scalar.reduce(out, hash, new Array[Double](64))
            assert(hash.toSeq == kept.toSeq)
            assert(Ed25519Reference.littleEndian(out, 0, 32) == Ed25519Reference.littleEndian(hash, 0, 64).mod(L))
        }
    }

    "isBelowL" - {
        "is true below L and false from L up, read at the offset" in {
            val prefix                    = Array[Byte](1, 2, 3, 4)
            def below(v: BigInt): Boolean = Ed25519Scalar.isBelowL(prefix ++ le32(v), prefix.length)
            assert(below(BigInt(0)))
            assert(below(BigInt(1)))
            assert(below(L - 1))
            assert(!below(L))
            assert(!below(L + 1))
            assert(!below(L * 2))
            assert(!below((BigInt(1) << 253)))
            assert(!below((BigInt(1) << 255) - 1))
            assert(!below((BigInt(1) << 256) - 1))
        }

        "compares from the most significant byte" in {
            val topAbove = le32(BigInt(0))
            topAbove(31) = 0x11
            assert(!Ed25519Scalar.isBelowL(topAbove, 0))
            val topBelow = le32((BigInt(1) << 248) - 1)
            topBelow(31) = 0x0f
            assert(Ed25519Scalar.isBelowL(topBelow, 0))
            val lowByteDecides = le32(L)
            lowByteDecides(0) = 0xec.toByte
            assert(Ed25519Scalar.isBelowL(lowByteDecides, 0))
        }
    }

    "slide" - {
        "gives digits that sum to the scalar, each zero or odd in [-15, 15], with no other nonzero digit within four positions" in {
            val random  = new java.util.Random(0x8032)
            val scalars =
                Seq(
                    BigInt(0),
                    BigInt(1),
                    BigInt(2),
                    BigInt(15),
                    BigInt(16),
                    BigInt(17),
                    BigInt(31),
                    L - 1,
                    L,
                    (BigInt(1) << 252),
                    (BigInt(1) << 255) - 1,
                    (BigInt(1) << 254) + (BigInt(1) << 253)
                ) ++
                    (0 until 200).map(_ => BigInt(255, random)) ++
                    (0 until 50).map(_ => BigInt(253, random).mod(L))
            scalars.foreach { s =>
                val digits = new Array[Byte](256)
                Ed25519Scalar.slide(digits, le32(s))
                assert(digitSum(digits) == s, s"scalar $s")
                digits.zipWithIndex.foreach { (d, i) =>
                    assert(d >= -15 && d <= 15 && (d == 0 || (d & 1) == 1), s"digit $i of $s is $d")
                    if d != 0 then (1 to 4).foreach(b => if i + b < 256 then assert(digits(i + b) == 0, s"digits $i and ${i + b} of $s"))
                }
            }
        }

        "leaves the scalar unchanged" in {
            val scalar = le32(L - 1)
            val kept   = scalar.clone()
            Ed25519Scalar.slide(new Array[Byte](256), scalar)
            assert(scalar.toSeq == kept.toSeq)
        }
    }

end Ed25519ScalarTest

object Ed25519ScalarTest:

    val L: BigInt = Ed25519Reference.L

    def le32(v: BigInt): Array[Byte] = Ed25519Reference.littleEndianBytes(v, 32)

    /** [[Ed25519Scalar.reduce]] of `v` in `[0, 2^512)` as 64 little-endian bytes, back as a number. */
    def reduced(v: BigInt): BigInt =
        val out = new Array[Byte](32)
        Ed25519Scalar.reduce(out, Ed25519Reference.littleEndianBytes(v, 64), new Array[Double](64))
        Ed25519Reference.littleEndian(out, 0, 32)
    end reduced

    def digitSum(digits: Array[Byte]): BigInt =
        digits.zipWithIndex.map((d, i) => BigInt(d.toInt) << i).sum

end Ed25519ScalarTest
