package kyo.ffi.it

import kyo.ffi.Ffi

/** Cross-platform libm spec. Exercises the `library = "m"` resolution on JVM, Native, and JS.
  *
  * Double-equality uses an absolute tolerance of 1e-9; `sqrt(4)` and `pow(2, 10)` are exactly representable in IEEE-754 double and tested
  * for strict equality. `sin(0)` / `cos(0)` / `floor(3.7)` / `fabs(-1.5)` are also exactly representable.
  *
  * Adds invariant-style rows (`sin² + cos² = 1`, `sqrt(x)² ≈ x`, …) and table-driven inputs. Each row invokes a binding method at least
  * once so every assertion crosses the FFI boundary.
  */
class LibMTest extends ItTestBase:

    // absolute tolerance for inexact IEEE-754 results; libm functions are
    // correctly rounded to last ULP on common platforms, but chaining two
    // calls (e.g. sqrt then squaring) yields up to a few ULPs of drift.
    private val eps      = 1e-9
    private val tightEps = 1e-12

    private def approxEqual(a: Double, b: Double, tol: Double = eps): Boolean =
        math.abs(a - b) <= tol

    "sqrt(4) == 2.0" in {
        val libm = Ffi.load[LibMBindings]
        assert(libm.sqrt(4.0) == 2.0)
    }

    "pow(2, 10) == 1024.0" in {
        val libm = Ffi.load[LibMBindings]
        assert(libm.pow(2.0, 10.0) == 1024.0)
    }

    "sin(0) == 0.0" in {
        val libm = Ffi.load[LibMBindings]
        assert(libm.sin(0.0) == 0.0)
    }

    "cos(0) == 1.0" in {
        val libm = Ffi.load[LibMBindings]
        assert(libm.cos(0.0) == 1.0)
    }

    "floor(3.7) == 3.0" in {
        val libm = Ffi.load[LibMBindings]
        assert(libm.floor(3.7) == 3.0)
    }

    "fabs(-1.5) == 1.5" in {
        val libm = Ffi.load[LibMBindings]
        assert(libm.fabs(-1.5) == 1.5)
    }

    // ---------------- Table-driven rows ----------------

    "sqrt" - {
        "table-driven: exactly representable squares" in {
            val libm = Ffi.load[LibMBindings]
            val cases: Seq[(Double, Double)] = Seq(
                0.0     -> 0.0,
                1.0     -> 1.0,
                4.0     -> 2.0,
                9.0     -> 3.0,
                16.0    -> 4.0,
                25.0    -> 5.0,
                100.0   -> 10.0,
                10000.0 -> 100.0
            )
            var last: Unit = succeed
            cases.foreach { case (input, expected) =>
                last = assert(libm.sqrt(input) == expected)
            }
            last
        }

        "invariant: sqrt(x) * sqrt(x) ≈ x" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val xs         = Seq(0.25, 0.5, 2.0, 3.0, 5.0, 7.5, 13.0, 42.0, 123.456, 1e6)
            xs.foreach { x =>
                val r = libm.sqrt(x)
                // allow a larger epsilon for large x: chained inexact ops drift.
                assert(approxEqual(r * r, x, tol = math.max(eps, x * 1e-14)) == true, s"sqrt($x)^2 vs $x: ")
                last = succeed
            }
            last
        }
    }

    "pow" - {
        "table-driven: integer exponents" in {
            val libm = Ffi.load[LibMBindings]
            val cases: Seq[((Double, Double), Double)] = Seq(
                (2.0, 0.0)  -> 1.0,
                (2.0, 1.0)  -> 2.0,
                (2.0, 2.0)  -> 4.0,
                (2.0, 3.0)  -> 8.0,
                (2.0, 10.0) -> 1024.0,
                (3.0, 2.0)  -> 9.0,
                (5.0, 3.0)  -> 125.0,
                (10.0, 0.0) -> 1.0
            )
            var last: Unit = succeed
            cases.foreach { case ((base, exp), expected) =>
                last = assert(libm.pow(base, exp) == expected)
            }
            last
        }

        "invariant: pow(base, 0) == 1 for several bases" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val bases      = Seq(1.0, 2.0, 3.0, 7.0, 17.0, 100.0, 1e6)
            bases.foreach { base =>
                last = assert(libm.pow(base, 0.0) == 1.0)
            }
            last
        }

        "invariant: pow(0, n) == 0 for positive n" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val exponents  = Seq(1.0, 2.0, 3.0, 7.5, 12.0)
            exponents.foreach { n =>
                last = assert(libm.pow(0.0, n) == 0.0)
            }
            last
        }

        "invariant: pow(1, n) == 1 for any n" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val exponents  = Seq(0.0, 1.0, 2.0, 7.0, 100.0, -3.0, 0.5)
            exponents.foreach { n =>
                last = assert(libm.pow(1.0, n) == 1.0)
            }
            last
        }
    }

    "sin/cos" - {
        "invariant: sin(x)^2 + cos(x)^2 ≈ 1 across x" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val xs         = Seq(0.0, 0.1, 0.5, 1.0, 1.5, 2.0, math.Pi / 4, math.Pi / 2, math.Pi, math.Pi * 1.25, 3.0, -1.0)
            xs.foreach { x =>
                val s = libm.sin(x)
                val c = libm.cos(x)
                assert(approxEqual(s * s + c * c, 1.0, tol = 1e-12) == true, s"sin^2 + cos^2 at x=$x: ")
                last = succeed
            }
            last
        }

        "invariant: sin(-x) == -sin(x) across x" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val xs         = Seq(0.0, 0.25, 0.5, 1.0, 1.5, 2.0, 3.0)
            xs.foreach { x =>
                val a = libm.sin(x)
                val b = libm.sin(-x)
                assert(approxEqual(b, -a, tol = tightEps) == true, s"sin(-$x) vs -sin($x): ")
                last = succeed
            }
            last
        }

        "invariant: cos(-x) == cos(x) across x" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val xs         = Seq(0.0, 0.25, 0.5, 1.0, 1.5, 2.0, 3.0)
            xs.foreach { x =>
                val a = libm.cos(x)
                val b = libm.cos(-x)
                assert(approxEqual(b, a, tol = tightEps) == true, s"cos(-$x) vs cos($x): ")
                last = succeed
            }
            last
        }

        "cos(pi) ≈ -1 and sin(pi/2) ≈ 1" in {
            val libm = Ffi.load[LibMBindings]
            assert(approxEqual(libm.cos(math.Pi), -1.0, tol = eps) == true)
            assert(approxEqual(libm.sin(math.Pi / 2), 1.0, tol = eps) == true)
        }
    }

    "floor" - {
        "table-driven: varied inputs" in {
            val libm = Ffi.load[LibMBindings]
            val cases: Seq[(Double, Double)] = Seq(
                0.0     -> 0.0,
                0.5     -> 0.0,
                0.99    -> 0.0,
                1.0     -> 1.0,
                1.5     -> 1.0,
                3.7     -> 3.0,
                -0.5    -> -1.0,
                -1.0    -> -1.0,
                -1.1    -> -2.0,
                42.0    -> 42.0,
                100.999 -> 100.0
            )
            var last: Unit = succeed
            cases.foreach { case (input, expected) =>
                last = assert(libm.floor(input) == expected)
            }
            last
        }

        "invariant: floor of an integer-valued double is itself" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val ints       = Seq(0.0, 1.0, 2.0, 10.0, 100.0, -1.0, -10.0, 1e6)
            ints.foreach { x =>
                last = assert(libm.floor(x) == x)
            }
            last
        }
    }

    "fabs" - {
        "table-driven: varied signs" in {
            val libm = Ffi.load[LibMBindings]
            val cases: Seq[(Double, Double)] = Seq(
                0.0   -> 0.0,
                1.0   -> 1.0,
                -1.0  -> 1.0,
                -1.5  -> 1.5,
                3.14  -> 3.14,
                -3.14 -> 3.14,
                -1e9  -> 1e9,
                1e-9  -> 1e-9,
                -1e-9 -> 1e-9
            )
            var last: Unit = succeed
            cases.foreach { case (input, expected) =>
                last = assert(libm.fabs(input) == expected)
            }
            last
        }

        "invariant: fabs(-x) == fabs(x) across x" in {
            val libm       = Ffi.load[LibMBindings]
            var last: Unit = succeed
            val xs         = Seq(0.0, 1.0, 2.5, 7.0, 42.0, 1e6)
            xs.foreach { x =>
                last = assert(libm.fabs(-x) == libm.fabs(x))
            }
            last
        }
    }
end LibMTest
