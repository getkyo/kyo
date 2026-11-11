package kyo.internal.signing

import ConstantTimeMontgomery.Step

class ConstantTimeMontgomeryTest extends kyo.test.Test[Any]:

    import ConstantTimeMontgomeryTest.*

    "limbs and value" - {
        "round-trip a value, little-endian, padded to the width" in {
            val value = BigInt("123456789abcdef0fedcba9876543210", 16)
            val limbs = ConstantTimeMontgomery.limbs(value, 5)
            assert(limbs.toSeq == Seq(0x76543210, 0xfedcba98, 0x9abcdef0, 0x12345678, 0))
            assert(ConstantTimeMontgomery.value(limbs) == value)
        }
    }

    "multiply" - {
        "is a * b * R^-1 mod n on seeded moduli of 1 to 128 limbs" in {
            val random = new scala.util.Random(0x6d756c74)
            Seq(2, 31, 32, 33, 64, 1024, 1025, 2048, 4096).foreach { bits =>
                (0 until 4).foreach { _ =>
                    val n          = oddModulus(bits, random)
                    val arithmetic = ConstantTimeMontgomery.init(n)
                    val a          = BigInt(bits, random).mod(n)
                    val b          = BigInt(bits, random).mod(n)
                    val out        = new Array[Int](arithmetic.size)
                    arithmetic.multiply(limbs(a, arithmetic), limbs(b, arithmetic), out)
                    assert(ConstantTimeMontgomery.value(out) == a * b * radix(arithmetic).modInverse(n) % n, s"n=$n a=$a b=$b")
                }
            }
        }

        "writes into an operand" in {
            val n          = oddModulus(2048, new scala.util.Random(0x616c6961))
            val arithmetic = ConstantTimeMontgomery.init(n)
            val a          = n - 2
            val out        = limbs(a, arithmetic)
            arithmetic.multiply(out, out, out)
            assert(ConstantTimeMontgomery.value(out) == a * a * radix(arithmetic).modInverse(n) % n)
        }

        "is below n on moduli whose limbs are all ones or nearly zero" in {
            Seq((BigInt(1) << 2048) - 1, (BigInt(1) << 2047) + 1, BigInt(0xffffffffL), (BigInt(1) << 64) - 1).foreach { n =>
                val arithmetic = ConstantTimeMontgomery.init(n)
                val out        = new Array[Int](arithmetic.size)
                arithmetic.multiply(limbs(n - 1, arithmetic), limbs(n - 1, arithmetic), out)
                assert(ConstantTimeMontgomery.value(out) == (n - 1) * (n - 1) * radix(arithmetic).modInverse(n) % n, s"n=$n")
            }
        }
    }

    "subtract" - {
        "is a - b mod n, either way round" in {
            val random = new scala.util.Random(0x73756274)
            Seq(64, 1024, 2048).foreach { bits =>
                val n          = oddModulus(bits, random)
                val arithmetic = ConstantTimeMontgomery.init(n)
                Seq((BigInt(bits, random).mod(n), BigInt(bits, random).mod(n)), (BigInt(0), n - 1), (n - 1, BigInt(0)), (n - 1, n - 1))
                    .foreach { (a, b) =>
                        val out = new Array[Int](arithmetic.size)
                        arithmetic.subtract(limbs(a, arithmetic), limbs(b, arithmetic), out)
                        assert(ConstantTimeMontgomery.value(out) == (a - b).mod(n), s"n=$n a=$a b=$b")
                    }
            }
        }
    }

    "toMontgomery and fromMontgomery" - {
        "take a value of the modulus's width to x * R mod n and back" in {
            val random     = new scala.util.Random(0x666f726d)
            val n          = oddModulus(2048, random)
            val arithmetic = ConstantTimeMontgomery.init(n)
            val x          = BigInt(2048, random).mod(n)
            val form       = new Array[Int](arithmetic.size)
            arithmetic.toMontgomery(limbs(x, arithmetic), form)
            assert(ConstantTimeMontgomery.value(form) == x * radix(arithmetic) % n)
            val back = new Array[Int](arithmetic.size)
            arithmetic.fromMontgomery(form, back)
            assert(ConstantTimeMontgomery.value(back) == x)
        }

        "reduce a value of twice the width, below n * R" in {
            val random     = new scala.util.Random(0x77696465)
            val p          = oddModulus(1024, random)
            val arithmetic = ConstantTimeMontgomery.init(p)
            Seq(p * radix(arithmetic) - 1, p * (p - 3) + 5, BigInt(2047, random)).foreach { value =>
                val form = new Array[Int](arithmetic.size)
                arithmetic.toMontgomery(ConstantTimeMontgomery.limbs(value, 2 * arithmetic.size), form)
                assert(ConstantTimeMontgomery.value(form) == value * radix(arithmetic) % p, s"value=$value")
            }
        }
    }

    "power" - {
        "is base ^ exponent mod n, for exponents of one limb and of many" in {
            val random = new scala.util.Random(0x706f7765)
            Seq(64, 1024, 2048).foreach { bits =>
                val n          = oddModulus(bits, random)
                val arithmetic = ConstantTimeMontgomery.init(n)
                val base       = BigInt(bits, random).mod(n)
                Seq(BigInt(0), BigInt(1), BigInt(3), BigInt(65537), (BigInt(1) << 64) - 59, BigInt(bits, random), (BigInt(1) << bits) - 1)
                    .foreach { exponent =>
                        val form = new Array[Int](arithmetic.size)
                        arithmetic.toMontgomery(limbs(base, arithmetic), form)
                        val exponentLimbs = ConstantTimeMontgomery.limbs(exponent, math.max(1, (exponent.bitLength + 31) / 32))
                        arithmetic.power(form, exponentLimbs, form)(_ => ())
                        arithmetic.fromMontgomery(form, form)
                        assert(ConstantTimeMontgomery.value(form) == base.modPow(exponent, n), s"n=$n base=$base exponent=$exponent")
                    }
            }
        }

        "runs the same steps for every exponent of one length, whatever its bits" in {
            val random                             = new scala.util.Random(0x73746570)
            val n                                  = oddModulus(1024, random)
            val arithmetic                         = ConstantTimeMontgomery.init(n)
            val base                               = limbs(BigInt(1024, random).mod(n), arithmetic)
            def trace(exponent: BigInt): Seq[Step] =
                val steps = Seq.newBuilder[Step]
                arithmetic.power(base.clone(), ConstantTimeMontgomery.limbs(exponent, 32), new Array[Int](arithmetic.size))(steps += _)
                steps.result()
            end trace
            val table   = Seq.fill(14)(Step.Multiply)
            val windows =
                Seq.fill(1024 / 4)(Seq(Step.Square, Step.Square, Step.Square, Step.Square, Step.Select(16), Step.Multiply)).flatten
            Seq(BigInt(0), BigInt(1), (BigInt(1) << 1024) - 1, BigInt(1) << 1023, BigInt(1024, random), BigInt(1024, random)).foreach {
                exponent => assert(trace(exponent) == table ++ windows, s"exponent=$exponent")
            }
        }
    }

end ConstantTimeMontgomeryTest

object ConstantTimeMontgomeryTest:

    /** A random odd modulus of exactly `bits` bits. */
    def oddModulus(bits: Int, random: scala.util.Random): BigInt =
        BigInt(bits, random).setBit(bits - 1).setBit(0)

    def limbs(value: BigInt, arithmetic: ConstantTimeMontgomery): Array[Int] =
        ConstantTimeMontgomery.limbs(value, arithmetic.size)

    /** R, the Montgomery radix of the arithmetic's width. */
    def radix(arithmetic: ConstantTimeMontgomery): BigInt =
        BigInt(1) << (32 * arithmetic.size)

end ConstantTimeMontgomeryTest
