package kyo.internal.signing

import kyo.*

class PrivateOperationTest extends kyo.test.Test[Any]:

    import PrivateOperationTest.*

    "apply" - {
        "is input ^ d mod n, as the modulus's length in bytes, whatever the blinding" in {
            val random = new scala.util.Random(0x70726976)
            val key    = TestKey(random)
            Seq(BigInt(0), BigInt(1), BigInt(2), key.n - 1, BigInt(key.n.bitLength - 1, random)).foreach { input =>
                Seq(blinding(key, random), blinding(key, random)).foreach { drawn =>
                    val result = PrivateOperation(key.operation, bytes(input, key.length), drawn)
                    assert(result.map(_.toSeq) == Present(bytes(input.modPow(key.d, key.n), key.length).toSeq), s"input=$input")
                }
            }
        }

        "is absent when r * u has no inverse modulo n" in {
            val random = new scala.util.Random(0x61627365)
            val key    = TestKey(random)
            val zero   = new Array[Byte](2 * (key.length + 8))
            assert(PrivateOperation(key.operation, bytes(BigInt(5), key.length), zero) == Absent)
            val sharesP = bytes(key.p, key.length + 8) ++ bytes(BigInt(3), key.length + 8)
            assert(PrivateOperation(key.operation, bytes(BigInt(5), key.length), sharesP) == Absent)
        }

        "modifies neither the input nor the blinding" in {
            val random = new scala.util.Random(0x6d6f6469)
            val key    = TestKey(random)
            val input  = bytes(BigInt(key.n.bitLength - 1, random), key.length)
            val drawn  = blinding(key, random)
            val before = (input.toSeq, drawn.toSeq)
            PrivateOperation(key.operation, input, drawn): Unit
            assert((input.toSeq, drawn.toSeq) == before)
        }
    }

end PrivateOperationTest

object PrivateOperationTest:

    /** A two-prime key of 2048 bits from seeded primes, its exponents and coefficient computed here, apart from `RsaSigning`'s reader. */
    final case class TestKey(p: BigInt, q: BigInt, e: BigInt):
        val n: BigInt   = p * q
        val d: BigInt   = e.modInverse((p - 1) * (q - 1) / (p - 1).gcd(q - 1))
        val length: Int = (n.bitLength + 7) / 8
        val operation   = PrivateOperation.Key.init(n, e, p, q, d.mod(p - 1), d.mod(q - 1), q.modInverse(p))
    end TestKey

    object TestKey:
        def apply(random: scala.util.Random): TestKey =
            TestKey(BigInt.probablePrime(1024, random), BigInt.probablePrime(1024, random), BigInt(65537))

    def blinding(key: TestKey, random: scala.util.Random): Array[Byte] =
        val out = new Array[Byte](2 * (key.length + 8))
        random.nextBytes(out)
        out
    end blinding

    /** `value` as `length` big-endian bytes. */
    def bytes(value: BigInt, length: Int): Array[Byte] =
        val raw = value.toByteArray.dropWhile(_ == 0)
        Array.fill[Byte](length - raw.length)(0) ++ raw

end PrivateOperationTest
