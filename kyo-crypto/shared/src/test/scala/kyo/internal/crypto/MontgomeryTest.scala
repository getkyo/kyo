package kyo.internal.crypto

/** [[Montgomery.modPow]] against `BigInt.modPow`: moduli from one limb to RSA's 8192-bit ceiling, the bases and exponents at the edges of
  * their ranges, and moduli whose limbs sit at the carry boundaries.
  */
class MontgomeryTest extends kyo.test.Test[Any]:

    import MontgomeryTest.*

    "modPow" - {
        "matches BigInt.modPow on seeded odd moduli of 1 to 64 limbs" in {
            val random = new scala.util.Random(0x6d6f6e74)
            Seq(2, 31, 32, 33, 64, 65, 96, 544, 1024, 2047, 2048).foreach { bits =>
                (0 until 8).foreach { _ =>
                    val n = oddModulus(bits, random)
                    val e = BigInt(64, random).setBit(0)
                    val b = BigInt(bits + 8, random).mod(n)
                    assert(Montgomery.modPow(b, e, n) == b.modPow(e, n), s"bits=$bits n=$n e=$e b=$b")
                }
            }
        }

        "matches on the edge bases and exponents" in {
            val random    = new scala.util.Random(0x65646765)
            val exponents = Seq(BigInt(1), BigInt(2), BigInt(3), BigInt(65537), (BigInt(1) << 64) - 1, BigInt(1) << 63)
            Seq(oddModulus(2048, random), oddModulus(3072, random)).foreach { n =>
                Seq(BigInt(0), BigInt(1), BigInt(2), n - 1, n - 2, n >> 1).foreach { b =>
                    exponents.foreach { e =>
                        assert(Montgomery.modPow(b, e, n) == b.modPow(e, n), s"n=$n e=$e b=$b")
                    }
                }
            }
        }

        "is 1 mod n for a zero exponent, and 0 for the modulus 1" in {
            assert(Montgomery.modPow(BigInt(5), BigInt(0), BigInt(7)) == BigInt(1))
            assert(Montgomery.modPow(BigInt(0), BigInt(0), BigInt(1)) == BigInt(0))
            assert(Montgomery.modPow(BigInt(0), BigInt(65537), BigInt(1)) == BigInt(0))
        }

        "matches on moduli whose limbs are all ones or nearly zero" in {
            val random = new scala.util.Random(0x6c696d62)
            val moduli = Seq(
                (BigInt(1) << 2048) - 1,
                (BigInt(1) << 2047) + 1,
                (BigInt(1) << 2048) - (BigInt(1) << 1024) - 1,
                (BigInt(1) << 64) - 1,
                BigInt(0xffffffffL)
            )
            moduli.foreach { n =>
                (0 until 4).foreach { _ =>
                    val b = BigInt(n.bitLength + 8, random).mod(n)
                    Seq(BigInt(3), BigInt(65537), BigInt(64, random).setBit(0)).foreach { e =>
                        assert(Montgomery.modPow(b, e, n) == b.modPow(e, n), s"n=$n e=$e b=$b")
                    }
                }
            }
        }

        "matches at RSA's 8192-bit ceiling" in {
            val random = new scala.util.Random(0x38313932)
            (0 until 2).foreach { _ =>
                val n = oddModulus(8192, random)
                val b = BigInt(8192, random).mod(n)
                assert(Montgomery.modPow(b, BigInt(65537), n) == b.modPow(BigInt(65537), n), s"n=$n b=$b")
            }
        }
    }

end MontgomeryTest

object MontgomeryTest:

    /** A random odd modulus of exactly `bits` bits. */
    def oddModulus(bits: Int, random: scala.util.Random): BigInt =
        BigInt(bits, random).setBit(bits - 1).setBit(0)
end MontgomeryTest
