package kyo

import kyo.internal.SizeScale

/** Unit tests for SizeScale sqrt-area mapping (INV-015, catalog #22/D7).
  *
  * All tests are pure deterministic math (AF1 from prep.md).
  */
class SizeScaleTest extends Test:

    private val Tol = 1e-9

    "sqrt-area scale: r(magMin) == rMin and r(magMax) == rMax (test T5 from prep.md, INV-015)" in {
        val s    = SizeScale(1.0, 100.0, 2.0, 20.0)
        val rMin = s.radius(1.0)   // t = (1-1)/(100-1) = 0, r = 2 + 18*sqrt(0) = 2
        val rMax = s.radius(100.0) // t = (100-1)/(100-1) = 1, r = 2 + 18*sqrt(1) = 20
        assert(math.abs(rMin - 2.0) < Tol, s"Expected rMin=2.0, got $rMin")
        assert(math.abs(rMax - 20.0) < Tol, s"Expected rMax=20.0, got $rMax")
        assert(rMax > rMin, "Larger magnitude must produce larger radius")
    }

    "sqrt-area scale: mid-point is sqrt-proportional, not linear (INV-015)" in {
        val s      = SizeScale(0.0, 100.0, 0.0, 10.0)
        val r50    = s.radius(50.0)
        val linear = 5.0
        val sqrtV  = 10.0 * math.sqrt(0.5)
        assert(math.abs(r50 - sqrtV) < Tol, s"Expected sqrt-area r=$sqrtV, got $r50")
        assert(math.abs(r50 - linear) > 0.01, s"Radius should not be linearly proportional; got $r50 vs linear $linear")
    }

    "equal magnitudes yield rMin with no div-by-zero (test T6 from prep.md, INV-015)" in {
        val s = SizeScale(5.0, 5.0, 2.0, 20.0)
        assert(s.radius(5.0) == 2.0, "Degenerate extent: all equal magnitudes must return rMin")
        assert(s.radius(3.0) == 2.0, "Any magnitude with degenerate extent must return rMin")
        assert(s.radius(100.0) == 2.0, "Out-of-range magnitude with degenerate extent must return rMin")
    }

    "magMax <= magMin (inverted extent) is handled as degenerate, returns rMin (INV-015)" in {
        val s = SizeScale(10.0, 5.0, 2.0, 20.0) // magMax < magMin
        assert(s.radius(7.0) == 2.0, "Inverted extent must return rMin without crash")
    }

    "out-of-range magnitudes are clamped to [0,1] before sqrt (INV-015)" in {
        val s      = SizeScale(0.0, 10.0, 2.0, 20.0)
        val rBelow = s.radius(-5.0)
        assert(math.abs(rBelow - 2.0) < Tol, s"Below-range magnitude should yield rMin, got $rBelow")
        val rAbove = s.radius(100.0)
        assert(math.abs(rAbove - 20.0) < Tol, s"Above-range magnitude should yield rMax, got $rAbove")
    }

    "DefaultRMin and DefaultRMax constants are 2.0 and 20.0 (INV-015)" in {
        assert(SizeScale.DefaultRMin == 2.0, s"DefaultRMin should be 2.0, got ${SizeScale.DefaultRMin}")
        assert(SizeScale.DefaultRMax == 20.0, s"DefaultRMax should be 20.0, got ${SizeScale.DefaultRMax}")
    }

    "radius is monotonically non-decreasing for increasing magnitudes (INV-015)" in {
        val s          = SizeScale(0.0, 100.0, 2.0, 20.0)
        val mags       = Seq(0.0, 10.0, 25.0, 50.0, 75.0, 100.0)
        val radii      = mags.map(s.radius)
        val violations = (0 until radii.size - 1).filter(i => radii(i) > radii(i + 1) + 1e-12)
        assert(violations.isEmpty, s"Radius must be non-decreasing; violations at indices $violations, radii=$radii")
    }

end SizeScaleTest
