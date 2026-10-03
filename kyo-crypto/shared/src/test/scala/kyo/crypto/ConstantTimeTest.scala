package kyo.crypto

import kyo.*

class ConstantTimeTest extends kyo.test.Test[Any]:

    private val tag: Array[Byte] = Array.tabulate(32)(i => (i * 37 + 11).toByte)

    "ConstantTime.isEqual" - {
        cases((a, b) => ConstantTime.isEqual(Span.from(a), Span.from(b)))
    }

    "ConstantTime.isEqualArrays" - {
        cases(ConstantTime.isEqualArrays)
    }

    private def cases(isEqual: (Array[Byte], Array[Byte]) => Boolean): Unit =
        "equal contents" - {
            "two empty arrays" in {
                assert(isEqual(Array.emptyByteArray, Array.emptyByteArray))
            }

            "identical arrays that are distinct instances" in {
                assert(isEqual(Array[Byte](1, 2, 3, 4), Array[Byte](1, 2, 3, 4)))
            }

            "the same instance" in {
                assert(isEqual(tag, tag))
            }

            "a copy of a 32-byte tag" in {
                assert(isEqual(tag, tag.clone()))
            }

            "bytes spanning the sign bit" in {
                assert(isEqual(Array[Byte](0x7f, 0x80.toByte, 0xff.toByte), Array[Byte](0x7f, 0x80.toByte, 0xff.toByte)))
            }
        }

        "different contents" - {
            "the first byte differs" in {
                assert(!isEqual(Array[Byte](9, 2, 3, 4), Array[Byte](1, 2, 3, 4)))
            }

            "the last byte differs" in {
                assert(!isEqual(Array[Byte](1, 2, 3, 4), Array[Byte](1, 2, 3, 5)))
            }

            "a middle byte differs" in {
                assert(!isEqual(Array[Byte](1, 2, 3, 4), Array[Byte](1, 7, 3, 4)))
            }

            "only the sign bit differs" in {
                assert(!isEqual(Array[Byte](0x00), Array[Byte](0x80.toByte)))
            }

            "any single bit flipped at any position of a 32-byte tag" in {
                val mismatches =
                    for
                        position <- 0 until tag.length
                        bit      <- 0 until 8
                    yield
                        val flipped = tag.clone()
                        flipped(position) = (flipped(position) ^ (1 << bit)).toByte
                        isEqual(tag, flipped) || isEqual(flipped, tag)
                assert(mismatches.size == 256)
                assert(!mismatches.contains(true))
            }
        }

        "different lengths" - {
            "an empty array against a non-empty one, in both orders" in {
                assert(!isEqual(Array.emptyByteArray, Array[Byte](0)))
                assert(!isEqual(Array[Byte](0), Array.emptyByteArray))
            }

            "a proper prefix, in both orders" in {
                assert(!isEqual(Array[Byte](1, 2, 3), Array[Byte](1, 2, 3, 4)))
                assert(!isEqual(Array[Byte](1, 2, 3, 4), Array[Byte](1, 2, 3)))
            }

            "a longer array whose extra bytes are zero" in {
                assert(!isEqual(Array[Byte](1, 2), Array[Byte](1, 2, 0)))
                assert(!isEqual(Array[Byte](1, 2, 0, 0), Array[Byte](1, 2)))
            }

            "a truncated tag" in {
                assert(!isEqual(tag, tag.take(16)))
            }
        }
    end cases

end ConstantTimeTest
