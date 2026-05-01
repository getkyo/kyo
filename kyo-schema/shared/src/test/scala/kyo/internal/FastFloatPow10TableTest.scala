package kyo.internal

import kyo.Test

/** Spot-check tests for the generated [[FastFloatPow10Table]].
  *
  * Reference values cross-checked against Go's `strconv/atofeisel.go` table
  * ([[https://github.com/golang/go/blob/master/src/strconv/atofeisel.go BSD-3-Clause]]).
  */
class FastFloatPow10TableTest extends Test:

    "table length" - {
        "pow10Hi has 696 entries" in {
            assert(FastFloatPow10Table.pow10Hi.length == 696)
        }
        "pow10Lo has 696 entries" in {
            assert(FastFloatPow10Table.pow10Lo.length == 696)
        }
    }

    "bounds" - {
        "pow10Min is -348" in {
            assert(FastFloatPow10Table.pow10Min == -348)
        }
        "pow10Max is 347" in {
            assert(FastFloatPow10Table.pow10Max == 347)
        }
    }

    "spot-checks against Go pow10tab" - {
        // All reference values taken from Go's src/internal/strconv/pow10tab.go.

        "10^-348 (index 0)" in {
            assert(FastFloatPow10Table.pow10Hi(0) == 0xfa8fd5a0081c0288L)
            assert(FastFloatPow10Table.pow10Lo(0) == 0x1732c869cd60e453L)
        }

        "10^0 (index 348)" in {
            assert(FastFloatPow10Table.pow10Hi(348) == 0x8000000000000000L)
            assert(FastFloatPow10Table.pow10Lo(348) == 0x0000000000000000L)
        }

        "10^1 (index 349)" in {
            assert(FastFloatPow10Table.pow10Hi(349) == 0xa000000000000000L)
            assert(FastFloatPow10Table.pow10Lo(349) == 0x0000000000000000L)
        }

        // 10^22 is the boundary for the compute_float_64 "easy" range check.
        // Go: {0x878678326eac9000, 0x0000000000000000} (index 370 = 348 + 22)
        "10^22 (index 370)" in {
            assert(FastFloatPow10Table.pow10Hi(370) == 0x878678326eac9000L)
            assert(FastFloatPow10Table.pow10Lo(370) == 0x0000000000000000L)
        }

        // 10^347 is the last entry.
        // Go: {0xd13eb46469447567, 0x4b7195f2d2d1a9fb} (index 695 = 348 + 347)
        "10^347 (index 695)" in {
            assert(FastFloatPow10Table.pow10Hi(695) == 0xd13eb46469447567L)
            assert(FastFloatPow10Table.pow10Lo(695) == 0x4b7195f2d2d1a9fbL)
        }
    }

end FastFloatPow10TableTest
