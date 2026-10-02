package kyo.internal.crypto

import kyo.crypto.Ed25519Reference
import kyo.internal.crypto.Ed25519Field.*

/** Every function of [[Ed25519Field]] against `BigInt` arithmetic modulo `p`, read from the limbs directly (`sum fi 2^(16 i)`, exact
  * and signed, never through [[Ed25519Field.pack]]), on the values where a carry, a fold or a conditional subtraction turns: `0`, `1`,
  * `p - 1`, the 19 values in `[p, 2^255)`, `2^255`, `2^256 - 1`, negative limbs, and limbs at the bounds the scaladoc pins.
  */
class Ed25519FieldTest extends kyo.test.Test[Any]:

    import Ed25519FieldTest.*

    "constants" - {
        "D, D2, BaseX, BaseY and SqrtMinusOne are the reference's values" in {
            assert(value(D) == Ed25519Reference.D)
            assert(value(D2) == Ed25519Reference.D2)
            assert(value(BaseX) == Ed25519Reference.Base.x)
            assert(value(BaseY) == Ed25519Reference.Base.y)
            assert(value(BaseY) == (BigInt(4) * Ed25519Reference.inverse(BigInt(5))).mod(P))
            assert(value(SqrtMinusOne) == Ed25519Reference.SqrtMinusOne)
            assert((raw(SqrtMinusOne) * raw(SqrtMinusOne)).mod(P) == P - 1)
            assert(raw(Zero) == 0)
            assert(raw(One) == 1)
        }

        "every constant is held in limbs in [0, 2^16)" in {
            Seq(D, D2, BaseX, BaseY, SqrtMinusOne, Zero, One).foreach(fe => assert(inUnpackedRange(fe)))
        }
    }

    "pack" - {
        "gives the canonical bytes of the limbs' value for every special value and every limb pattern" in {
            (specialValues.map(limbs) ++ limbPatterns).foreach { fe =>
                val bytes = packed(fe)
                assert(Ed25519Reference.littleEndian(bytes, 0, 32) == raw(fe).mod(P), s"limbs ${fe.toSeq}")
                assert((bytes(31) & 0x80) == 0)
                assert(Ed25519Reference.littleEndian(bytes, 0, 32) < P)
            }
        }

        "leaves the limbs it packs unchanged" in {
            val fe   = limbs(P + 7)
            val kept = fe.clone()
            packed(fe)
            assert(fe.toSeq == kept.toSeq)
        }

        "needs its third carry only beyond the callers' bound: a top limb near 2^27 whose fold lands limb 0 above 2^16 after the second pass" in {
            val crafted = Array.fill(16)(65535.0)
            crafted(0) = 65500
            crafted(15) = 65535 + 65536.0 * 1725
            assert(raw(crafted).mod(P) == BigInt(65552))
            assert(value(crafted) == BigInt(65552))
            carryBoundaryPatterns.foreach { fe =>
                assert(value(fe) == raw(fe).mod(P), s"limbs ${fe.toSeq}")
            }
        }

        "brings a value in [2p, 2^256) to canonical form, which takes both conditional subtractions" in {
            val twoP = P * 2
            Seq(twoP, twoP + 1, twoP + 18, (BigInt(1) << 256) - 1).foreach { v =>
                assert(Ed25519Reference.littleEndian(packed(limbs(v)), 0, 32) == v.mod(P))
            }
        }
    }

    "unpack" - {
        "reads 32 little-endian bytes at an offset into limbs in [0, 2^16), dropping bit 255" in {
            val prefix = Array[Byte](9, 9, 9)
            Seq(BigInt(0), BigInt(1), P - 1, P, (BigInt(1) << 255) - 1).foreach { v =>
                val bytes = prefix ++ le32(v)
                val out   = fe()
                unpack(out, bytes, 3)
                assert(raw(out) == v)
                assert(inUnpackedRange(out))
                val signed = prefix ++ le32(v.setBit(255))
                unpack(out, signed, 3)
                assert(raw(out) == v)
            }
        }

        "is the inverse of pack on canonical values" in {
            specialValues.filter(_ < P).foreach { v =>
                val out = fe()
                unpack(out, packed(limbs(v)), 0)
                assert(raw(out) == v)
            }
        }
    }

    "carry" - {
        "keeps the value modulo p and leaves limbs 1 to 15 in [0, 2^16), for limbs up to 2^51 in magnitude" in {
            (limbPatterns ++ carryBoundaryPatterns ++ specialValues.map(limbs)).foreach { fe =>
                val before = raw(fe).mod(P)
                carry(fe)
                assert(raw(fe).mod(P) == before, s"limbs ${fe.toSeq}")
                (1 until 16).foreach(i => assert(fe(i) >= 0 && fe(i) < 65536, s"limb $i of ${fe.toSeq}"))
                assert(fe(0) == Math.floor(fe(0)))
            }
        }

        "three passes on limbs up to 32 r in magnitude leave every limb in [0, 2^16)" in {
            (limbPatterns ++ specialValues.map(limbs)).foreach { fe =>
                val before = raw(fe).mod(P)
                carry(fe)
                carry(fe)
                carry(fe)
                assert(raw(fe).mod(P) == before)
                assert(inUnpackedRange(fe), s"limbs ${fe.toSeq}")
            }
        }

        "folds the top carry as 38 times the carry into limb 0" in {
            val out = fe()
            out(15) = 65536 * 3
            carry(out)
            assert(out(15) == 0)
            assert(out(0) == 38 * 3)
            assert(raw(out) == (BigInt(3) << 256) - 3 * P * 2)
        }
    }

    "select" - {
        "swaps for 1 and keeps for 0, on every limb" in {
            val a  = limbs(P - 1)
            val b  = limbs(BigInt("123456789abcdef0123456789abcdef0", 16))
            val ac = a.clone()
            val bc = b.clone()
            select(a, b, 0)
            assert(a.toSeq == ac.toSeq && b.toSeq == bc.toSeq)
            select(a, b, 1)
            assert(a.toSeq == bc.toSeq && b.toSeq == ac.toSeq)
        }

        "swaps negative limbs" in {
            val a = Array.fill(16)(-5.0)
            val b = Array.fill(16)(65535.0)
            select(a, b, 1)
            assert(a.forall(_ == 65535.0) && b.forall(_ == -5.0))
        }
    }

    "add and sub" - {
        "are exact on the limbs, no carry" in {
            pairs.foreach { (a, b) =>
                val sum  = fe()
                val diff = fe()
                add(sum, a, b)
                sub(diff, a, b)
                (0 until 16).foreach { i =>
                    assert(sum(i) == a(i) + b(i))
                    assert(diff(i) == a(i) - b(i))
                }
                assert(raw(sum) == raw(a) + raw(b))
                assert(raw(diff) == raw(a) - raw(b))
            }
        }

        "may alias the output with either input" in {
            val a        = limbs(P - 2)
            val b        = limbs(BigInt(5))
            val expected = raw(a) + raw(b)
            add(a, a, b)
            assert(raw(a) == expected)
            val c = limbs(BigInt(7))
            add(b, c, b)
            assert(raw(b) == 12)
            sub(c, c, c)
            assert(raw(c) == 0)
        }
    }

    "mul" - {
        "is the product modulo p for special values and limb patterns, with reduced output" in {
            pairs.foreach { (a, b) =>
                val o = fe()
                mul(o, a, b, scratch)
                assert(raw(o).mod(P) == (raw(a) * raw(b)).mod(P), s"${a.toSeq} * ${b.toSeq}")
                assertReduced(o)
            }
        }

        "may alias the output with either input, or both" in {
            pairs.foreach { (a0, b0) =>
                val expected = (raw(a0) * raw(b0)).mod(P)
                val a        = a0.clone()
                val b        = b0.clone()
                mul(a, a, b, scratch)
                assert(raw(a).mod(P) == expected)
                val a1 = a0.clone()
                val b1 = b0.clone()
                mul(b1, a1, b1, scratch)
                assert(raw(b1).mod(P) == expected)
                val a2 = a0.clone()
                mul(a2, a2, a2, scratch)
                assert(raw(a2).mod(P) == (raw(a0) * raw(a0)).mod(P))
            }
        }

        "leaves its inputs unchanged" in {
            val a  = limbs(P - 1)
            val b  = limbs(BigInt(1) << 200)
            val ac = a.clone()
            val bc = b.clone()
            mul(fe(), a, b, scratch)
            assert(a.toSeq == ac.toSeq && b.toSeq == bc.toSeq)
        }

        "every limb at 2^21, the pinned precondition, gives the product with the fold below 2^52 in magnitude" in {
            val a = Array.fill(16)(2097152.0)
            val b = Array.fill(16)(-2097152.0)
            val o = fe()
            mul(o, a, a, scratch)
            assert(raw(o).mod(P) == (raw(a) * raw(a)).mod(P))
            assertReduced(o)
            mul(o, a, b, scratch)
            assert(raw(o).mod(P) == (raw(a) * raw(b)).mod(P))
            assertReduced(o)
            mul(o, b, b, scratch)
            assert(raw(o).mod(P) == (raw(b) * raw(b)).mod(P))
            assertReduced(o)
        }
    }

    "square" - {
        "is mul with itself" in {
            (specialValues.map(limbs) ++ limbPatterns).foreach { a =>
                val o = fe()
                square(o, a, scratch)
                assert(raw(o).mod(P) == (raw(a) * raw(a)).mod(P))
                assertReduced(o)
                val aliased = a.clone()
                square(aliased, aliased, scratch)
                assert(raw(aliased).mod(P) == (raw(a) * raw(a)).mod(P))
            }
        }
    }

    "invert" - {
        "is a^(p - 2): the inverse of every nonzero value, and 0 for 0" in {
            (specialValues.map(limbs) ++ limbPatterns).foreach { a =>
                val o = fe()
                invert(o, a, scratch)
                val v = raw(a).mod(P)
                assert(raw(o).mod(P) == v.modPow(P - 2, P), s"limbs ${a.toSeq}")
                if v == 0 then assert(raw(o).mod(P) == 0)
                else assert((raw(o) * v).mod(P) == 1)
                assertReduced(o)
            }
        }

        "may alias its output with its input" in {
            val a = limbs(BigInt(3))
            invert(a, a, scratch)
            assert(raw(a).mod(P) == BigInt(3).modPow(P - 2, P))
        }
    }

    "pow2523" - {
        "is a^((p - 5) / 8)" in {
            val exponent = (P - 5) / 8
            (specialValues.map(limbs) ++ limbPatterns).foreach { a =>
                val o = fe()
                pow2523(o, a, scratch)
                assert(raw(o).mod(P) == raw(a).mod(P).modPow(exponent, P), s"limbs ${a.toSeq}")
                assertReduced(o)
            }
            val aliased = limbs(BigInt(3))
            pow2523(aliased, aliased, scratch)
            assert(raw(aliased).mod(P) == BigInt(3).modPow(exponent, P))
        }
    }

    "isEqual, parity and isZero" - {
        "decide on the value, not the limbs" in {
            assert(isEqual(limbs(P + 1), One, scratch))
            assert(isEqual(limbs(P), Zero, scratch))
            assert(isEqual(limbs(P * 2 + 5), limbs(BigInt(5)), scratch))
            assert(isEqual(negativeP, Zero, scratch))
            assert(!isEqual(limbs(BigInt(1)), limbs(BigInt(2)), scratch))
            assert(!isEqual(limbs(P - 1), limbs(BigInt(1)), scratch))
            assert(parity(limbs(P + 1), scratch) == 1)
            assert(parity(limbs(P + 2), scratch) == 0)
            assert(parity(limbs(P - 1), scratch) == 0)
            assert(parity(negativeP, scratch) == 0)
            assert(parity(limbs(BigInt(1) << 255), scratch) == 1)
            assert(isZero(limbs(P), scratch))
            assert(isZero(limbs(P * 2), scratch))
            assert(isZero(negativeP, scratch))
            assert(isZero(Zero, scratch))
            assert(!isZero(One, scratch))
            assert(!isZero(limbs(P - 1), scratch))
            assert(!isZero(limbs(BigInt(1) << 255), scratch))
        }
    }

end Ed25519FieldTest

object Ed25519FieldTest:

    val P: BigInt = Ed25519Reference.P

    /** A fresh scratch per use: the leaves run in parallel and every pattern below is fresh per call for the same reason. */
    def scratch: Scratch = new Scratch

    /** Limbs of a value in `[0, 2^256)`, each in `[0, 2^16)`: the natural unreduced representation. */
    def limbs(v: BigInt): Fe =
        require(v >= 0 && v.bitLength <= 256, s"$v does not fit 16 limbs")
        Array.tabulate(16)(i => ((v >> (16 * i)) & 0xffff).toDouble)
    end limbs

    /** The exact signed integer the limbs hold, `sum fi 2^(16 i)`. */
    def raw(fe: Fe): BigInt =
        fe.zipWithIndex.map((f, i) => BigInt(f.toLong) << (16 * i)).sum

    def value(fe: Fe): BigInt = Ed25519Reference.littleEndian(packed(fe), 0, 32)

    def packed(fe: Fe): Array[Byte] =
        val out = new Array[Byte](32)
        pack(out, fe, scratch)
        out
    end packed

    def le32(v: BigInt): Array[Byte] = Ed25519Reference.littleEndianBytes(v, 32)

    /** `-p` as limbs: `19 - 2^15 2^240`. */
    def negativeP: Fe =
        val out = fe()
        out(0) = 19
        out(15) = -32768
        out
    end negativeP

    val specialValues: Seq[BigInt] =
        Seq(BigInt(0), BigInt(1), BigInt(2), BigInt(19), P - 2, P - 1) ++
            (0 until 19).map(i => P + i) ++
            Seq(BigInt(1) << 255, (BigInt(1) << 255) + 18, (BigInt(1) << 255) + 19, P * 2 - 1, P * 2, P * 2 + 1, (BigInt(1) << 256) - 1)

    /** Limb patterns within the multiplication precondition: `2^21` in magnitude. */
    def limbPatterns: Seq[Fe] =
        val bound  = 2097152.0
        val random = new java.util.Random(0x25519)
        Seq(
            Array.fill(16)(bound),
            Array.fill(16)(-bound),
            Array.tabulate(16)(i => if i % 2 == 0 then bound else -bound),
            Array.fill(16)(65535.0),
            Array.fill(16)(-65535.0),
            Array.fill(16)(65536.0),
            negativeP
        ) ++
            (0 until 16).map(i => Array.tabulate(16)(j => if j == i then bound else 0.0)) ++
            (0 until 16).map(i => Array.tabulate(16)(j => if j == i then -bound else 0.0)) ++
            (0 until 40).map(_ => Array.fill(16)((random.nextLong() % 2097153).toDouble))
    end limbPatterns

    /** Limb patterns at the carry's own precondition, `2^51`, beyond what any caller produces. */
    def carryBoundaryPatterns: Seq[Fe] =
        val bound = 2251799813685248.0
        Seq(Array.fill(16)(bound), Array.fill(16)(-bound), Array.tabulate(16)(i => if i % 3 == 0 then bound else -bound)) ++
            (0 until 16).map(i => Array.tabulate(16)(j => if j == i then bound else 0.0)) ++
            (0 until 16).map(i => Array.tabulate(16)(j => if j == i then -bound else 0.0))
    end carryBoundaryPatterns

    def pairs: Seq[(Fe, Fe)] =
        val inputs = specialValues.map(limbs) ++ limbPatterns
        val random = new java.util.Random(0x8032)
        inputs.zip(inputs.reverse) ++ (0 until 60).map(_ => (inputs(random.nextInt(inputs.size)), inputs(random.nextInt(inputs.size))))
    end pairs

    def inUnpackedRange(fe: Fe): Boolean = fe.forall(f => f >= 0 && f < 65536 && f == Math.floor(f))

    /** The pinned output bound of [[Ed25519Field.mul]]: limbs 1 to 15 in `[0, 2^16)`, limb 0 in `[-38, 2^16 + 38)`. */
    def assertReduced(fe: Fe)(using kyo.test.AssertScope): Unit =
        (1 until 16).foreach(i => assert(fe(i) >= 0 && fe(i) < 65536 && fe(i) == Math.floor(fe(i)), s"limb $i of ${fe.toSeq}"))
        assert(fe(0) >= -38 && fe(0) < 65536 + 38 && fe(0) == Math.floor(fe(0)), s"limb 0 of ${fe.toSeq}")
    end assertReduced

end Ed25519FieldTest
