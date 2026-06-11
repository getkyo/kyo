package kyo.test.prop

import kyo.Chunk
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for Shrink helpers. All assertions are pure synchronous; uses ScalaTest directly. */
class ShrinkTest extends AnyFunSuite with NonImplicitAssertions:

    test("int shrinks toward zero") {
        val candidates = Shrink.int(100).toList
        // Must be non-empty
        assert(candidates.nonEmpty, "Shrink.int(100) produced no candidates")
        // The last element must be 0
        assert(candidates.last == 0, s"Expected last shrunk value to be 0, got ${candidates.last}")
        // First candidate: 50
        assert(candidates.head == 50, s"Expected first shrunk value to be 50, got ${candidates.head}")
        // All values must be non-negative (positive v shrinks through positive values)
        assert(candidates.forall(_ >= 0), s"Expected all positive shrunk values, got: $candidates")
    }

    test("list shrinks element-wise and length-wise") {
        val original   = Chunk(3, 5, 7)
        val candidates = Shrink.list(original, Shrink.int).toList

        // Drop-phase: should include chunks shorter by one element
        val shorterChunks = candidates.filter(_.size < original.size)
        assert(shorterChunks.nonEmpty, s"Expected some shorter chunks in shrink output, got: $candidates")
        // Element-wise shrink: should include chunks with one element changed
        val sameLengthChunks = candidates.filter(_.size == original.size)
        assert(sameLengthChunks.nonEmpty, s"Expected some same-length chunks with shrunken elements, got: $candidates")
    }

    test("string shrinks toward empty") {
        val original   = "hello"
        val candidates = Shrink.string(original).toList
        // Must be non-empty
        assert(candidates.nonEmpty, s"Shrink.string('hello') produced no candidates")
        // Last element must be ""
        assert(candidates.last == "", s"Expected last shrunk value to be '', got '${candidates.last}'")
        // All candidates must be shorter than the original
        assert(
            candidates.forall(s => s.length < original.length || s == ""),
            s"All candidates should be shorter than original: $candidates"
        )
    }

    // Regression: shrinkInt(Int.MinValue) must NOT contain Int.MinValue itself (the overflow bug where
    // -Int.MinValue == Int.MinValue in two's complement would cause the greedy shrink walk to loop).
    // The fix drops the mirror candidate for MinValue and starts with the halving sequence instead.
    test("shrinkInt(Int.MinValue) does not contain Int.MinValue and reaches 0") {
        val candidates = Shrink.int(Int.MinValue).toList
        assert(candidates.nonEmpty, "Shrink.int(Int.MinValue) must be non-empty")
        assert(
            !candidates.contains(Int.MinValue),
            s"Shrink.int(Int.MinValue) must not contain Int.MinValue itself (overflow guard): $candidates"
        )
        assert(candidates.last == 0, s"Shrink.int(Int.MinValue) must reach 0; last was ${candidates.last}")
        // The first candidate is the halving step: Int.MinValue / 2 = -1073741824
        assert(
            candidates.head == Int.MinValue / 2,
            s"First shrink candidate for Int.MinValue must be Int.MinValue/2 = ${Int.MinValue / 2}, got ${candidates.head}"
        )
    }

    // Regression: shrinkLong(Long.MinValue) must NOT contain Long.MinValue itself.
    test("shrinkLong(Long.MinValue) does not contain Long.MinValue and reaches 0") {
        val candidates = Shrink.long(Long.MinValue).toList
        assert(candidates.nonEmpty, "Shrink.long(Long.MinValue) must be non-empty")
        assert(
            !candidates.contains(Long.MinValue),
            s"Shrink.long(Long.MinValue) must not contain Long.MinValue itself (overflow guard): $candidates"
        )
        assert(candidates.last == 0L, s"Shrink.long(Long.MinValue) must reach 0; last was ${candidates.last}")
        // The first candidate is the halving step: Long.MinValue / 2 = -4611686018427387904
        assert(
            candidates.head == Long.MinValue / 2L,
            s"First shrink candidate for Long.MinValue must be Long.MinValue/2 = ${Long.MinValue / 2L}, got ${candidates.head}"
        )
    }

    // Double shrink: toward 0.0, integral-neighbor-first, NaN/Infinity handling, no floor

    // Leaf 1: integral neighbor first for a positive finite value
    test("Shrink.double(2.7) first candidate is the integral neighbor 2.0") {
        val candidates = Shrink.double(2.7)
        assert(candidates.nonEmpty, "Shrink.double(2.7) produced no candidates")
        assert(
            candidates.head == 2.0,
            s"Expected integral neighbor 2.0 as first candidate, got ${candidates.head}"
        )
    }

    // Leaf 2: no 0.001 floor; sequence ends at 0.0 and goes below 0.001
    test("Shrink.double(2.7) sequence ends at 0.0 and contains no 0.001 floor stall") {
        val candidates = Shrink.double(2.7).toList
        assert(candidates.last == 0.0, s"Expected last candidate to be 0.0, got ${candidates.last}")
        assert(
            candidates.exists(x => x > 0.0 && x < 0.001),
            s"Expected at least one candidate in (0.0, 0.001) (floor removed), candidates: $candidates"
        )
        assert(
            candidates.forall(java.lang.Double.isFinite),
            s"All candidates must be finite, got: $candidates"
        )
    }

    // Leaf 3: 0.0 base case
    test("Shrink.double(0.0) is empty") {
        assert(Shrink.double(0.0) == Chunk.empty[Double], "Shrink.double(0.0) must be empty")
    }

    // Leaf 4: -0.0 covered by == 0.0 base case (IEEE 754: 0.0 == -0.0)
    test("Shrink.double(-0.0) is empty (negative zero covered)") {
        assert(Shrink.double(-0.0) == Chunk.empty[Double], "Shrink.double(-0.0) must be empty")
    }

    // Leaf 5: NaN -> Chunk(0.0)
    test("Shrink.double(NaN) == Chunk(0.0)") {
        val result = Shrink.double(Double.NaN)
        assert(result == Chunk(0.0), s"Expected Chunk(0.0) for NaN input, got $result")
    }

    // Leaf 6: +Infinity is finite and reaches 0.0 via MaxValue then halving
    test("Shrink.double(+Infinity) is finite and reaches 0.0") {
        val candidates = Shrink.double(Double.PositiveInfinity)
        assert(candidates.head == 0.0, s"Expected head 0.0 for +Infinity, got ${candidates.head}")
        assert(candidates.contains(Double.MaxValue), s"+Infinity shrink must contain Double.MaxValue")
        assert(
            candidates.forall(java.lang.Double.isFinite),
            s"All +Infinity shrink candidates must be finite, got: $candidates"
        )
    }

    // Leaf 7: -Infinity mirrors with -Double.MaxValue and halves toward 0
    test("Shrink.double(-Infinity) mirrors with -Double.MaxValue") {
        val candidates = Shrink.double(Double.NegativeInfinity)
        assert(candidates.head == 0.0, s"Expected head 0.0 for -Infinity, got ${candidates.head}")
        assert(candidates.contains(-Double.MaxValue), s"-Infinity shrink must contain -Double.MaxValue")
        assert(
            candidates.forall(java.lang.Double.isFinite),
            s"All -Infinity shrink candidates must be finite, got: $candidates"
        )
        // The negative-Infinity branch must halve toward 0 symmetrically with +Infinity:
        // there must be an intermediate negative magnitude strictly between -Double.MaxValue and 0.0.
        assert(
            candidates.exists(c => c < 0.0 && c > -Double.MaxValue),
            s"-Infinity shrink must contain an intermediate negative magnitude (halving toward 0), candidates: $candidates"
        )
    }

    // Leaf 8: negative tries positive mirror first then heads toward 0.0
    test("Shrink.double(-2.7) tries the positive mirror first then heads toward 0.0") {
        val candidates = Shrink.double(-2.7).toList
        assert(candidates.head == 2.7, s"Expected positive mirror 2.7 as head for -2.7, got ${candidates.head}")
        assert(candidates.last == 0.0, s"Expected last candidate to be 0.0, got ${candidates.last}")
        assert(
            candidates.forall(java.lang.Double.isFinite),
            s"All candidates must be finite, got: $candidates"
        )
    }

    // Leaf 9: regression smoke - existing int/list/string cases unchanged
    // (The three earlier tests above serve as this regression; no new code needed here.)

end ShrinkTest
