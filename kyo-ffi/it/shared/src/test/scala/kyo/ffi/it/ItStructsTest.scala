package kyo.ffi.it

import kyo.*
import kyo.ffi.Ffi

/** Cross-platform struct-feature spec.
  *
  * Exercises:
  *   - nested struct parameter (`Circle` containing a `Center`), `circle_area` + `circle_sum`.
  *   - packed struct parameter, `packed_value`.
  *   - multi-value return via a case class, `make_pair`.
  *   - by-value struct return (`@Ffi.byValue`), `make_circle` (nested struct) + `make_box` (single field). These map to the
  *     C `void f(S* out, ...args)` out-pointer-first ABI and must round-trip identically on JVM, Native, and JS.
  *
  * The tolerance on `kyoItCircleSum` is 1e-9; the inputs are small exactly-representable doubles so the result fits in exact IEEE-754.
  * `circleArea` uses the same `3.141592653589793` literal on both sides so the computed product is bit-identical.
  *
  * Multi-input rows against the bindings, each row crosses the FFI boundary at least once.
  */
class ItStructsTest extends ItTestBase:

    private val pi = 3.141592653589793

    "kyoItCircleArea" - {
        "nested-struct param: radius 2.0 yields 4 * pi" in {
            val b = Ffi.load[ItStructsBindings]
            assert(b.kyoItCircleArea(Circle(Center(0.0, 0.0), 2.0)) == (4.0 * pi))
        }

        "radius 0 yields 0" in {
            val b = Ffi.load[ItStructsBindings]
            assert(b.kyoItCircleArea(Circle(Center(5.0, -7.0), 0.0)) == 0.0)
        }

        "table-driven: varied radii and centers" in {
            val b = Ffi.load[ItStructsBindings]
            // (center, radius, expected area)
            val cases: Seq[(Center, Double, Double)] = Seq(
                (Center(0.0, 0.0), 1.0, 1.0 * 1.0 * pi),
                (Center(0.0, 0.0), 3.0, 9.0 * pi),
                (Center(10.0, 20.0), 4.0, 16.0 * pi),
                (Center(-5.0, 7.0), 5.0, 25.0 * pi),
                (Center(1.5, 2.5), 0.5, 0.25 * pi),
                (Center(0.0, 0.0), 10.0, 100.0 * pi)
            )
            var last: Unit = succeed
            cases.foreach { case (center, radius, expected) =>
                last = assert(b.kyoItCircleArea(Circle(center, radius)) == expected)
            }
            last
        }
    }

    "kyoItCircleSum" - {
        "nested-struct reads + primitive params: (1+10) + (2+20) + 100 = 133" in {
            val b = Ffi.load[ItStructsBindings]
            assert(b.kyoItCircleSum(Circle(Center(1.0, 2.0), 100.0), 10.0, 20.0) == 133.0)
        }

        "table-driven: varied circles + translation vectors" in {
            val b = Ffi.load[ItStructsBindings]
            // (circle, dx, dy, expected sum)
            // expected = (cx + dx) + (cy + dy) + r
            val cases: Seq[(Circle, Double, Double, Double)] = Seq(
                (Circle(Center(0.0, 0.0), 0.0), 0.0, 0.0, 0.0),
                (Circle(Center(1.0, 1.0), 1.0), 0.0, 0.0, 3.0),
                (Circle(Center(0.0, 0.0), 5.0), 1.0, 2.0, 8.0),
                (Circle(Center(-5.0, -5.0), 10.0), 5.0, 5.0, 10.0),
                (Circle(Center(3.0, 4.0), 2.0), -1.0, -2.0, 6.0),
                (Circle(Center(100.0, 200.0), 1.0), 1.0, -1.0, 301.0)
            )
            var last: Unit = succeed
            cases.foreach { case (c, dx, dy, expected) =>
                last = assert(b.kyoItCircleSum(c, dx, dy) == expected)
            }
            last
        }
    }

    "kyoItPackedValue" - {
        "tag == 1 weights value by 100" in {
            val b = Ffi.load[ItStructsBindings]
            assert(b.kyoItPackedValue(Packed(1, 42)) == 4200)
        }

        "tag == 0 returns value unchanged" in {
            val b = Ffi.load[ItStructsBindings]
            assert(b.kyoItPackedValue(Packed(0, 42)) == 42)
        }

        "table-driven: varied tag+value combinations" in {
            val b = Ffi.load[ItStructsBindings]
            // expected = value * 100 when tag == 1, value otherwise.
            val cases: Seq[(Int, Int, Int)] = Seq(
                (0, 0, 0),
                (1, 0, 0),
                (0, 1, 1),
                (1, 1, 100),
                (0, -5, -5),
                (1, -5, -500),
                (0, 12345, 12345),
                (1, 12, 1200),
                (2, 50, 50), // tag != 0 and != 1 → current C impl returns value unchanged
                (1, 1000, 100000)
            )
            var last: Unit = succeed
            cases.foreach { case (tag, value, expected) =>
                last = assert(b.kyoItPackedValue(Packed(tag, value)) == expected)
            }
            last
        }
    }

    "kyoItMakePair" - {
        "multi-value return: sum + product" in {
            val b = Ffi.load[ItStructsBindings]
            val p = b.kyoItMakePair(3, 5)
            assert(p.sum == 8)
            assert(p.product == 15)
        }

        "zero times anything is zero" in {
            val b = Ffi.load[ItStructsBindings]
            val p = b.kyoItMakePair(0, 99)
            assert(p.sum == 99)
            assert(p.product == 0)
        }

        "table-driven: varied (a, b) inputs, sum + product" in {
            val b = Ffi.load[ItStructsBindings]
            val cases: Seq[(Int, Int, Int, Int)] = Seq(
                // (a, b, expectedSum, expectedProduct)
                (1, 1, 2, 1),
                (2, 3, 5, 6),
                (4, 5, 9, 20),
                (7, 8, 15, 56),
                (10, 10, 20, 100),
                (-3, 4, 1, -12),
                (-2, -5, -7, 10),
                (0, 0, 0, 0),
                (100, 0, 100, 0),
                (123, 4, 127, 492)
            )
            var last: Unit = succeed
            cases.foreach { case (a, bb, expSum, expProd) =>
                val p = b.kyoItMakePair(a, bb)
                assert(p.sum == expSum)
                last = assert(p.product == expProd)
            }
            last
        }
    }

    "kyoItMakeCircle (@Ffi.byValue)" - {
        "by-value nested-struct return: fills a Circle(Center(cx, cy), r)" in {
            val b = Ffi.load[ItStructsBindings]
            assert(b.kyoItMakeCircle(1.0, 2.0, 3.0) == Circle(Center(1.0, 2.0), 3.0))
        }

        "table-driven: varied (cx, cy, r) inputs round-trip exactly" in {
            val b = Ffi.load[ItStructsBindings]
            val cases: Seq[(Double, Double, Double)] = Seq(
                (0.0, 0.0, 0.0),
                (1.0, 2.0, 3.0),
                (-5.0, 7.5, 2.25),
                (100.0, -200.0, 0.5),
                (1.5, 2.5, 10.0),
                (-0.25, -0.75, 4.0)
            )
            var last: Unit = succeed
            cases.foreach { case (cx, cy, r) =>
                last = assert(b.kyoItMakeCircle(cx, cy, r) == Circle(Center(cx, cy), r))
            }
            last
        }
    }

    "kyoItMakeBox (@Ffi.byValue single field)" - {
        "by-value single-field struct return: fills a Box(v)" in {
            val b = Ffi.load[ItStructsBindings]
            assert(b.kyoItMakeBox(42) == Box(42))
        }

        "table-driven: varied v inputs round-trip exactly" in {
            val b               = Ffi.load[ItStructsBindings]
            val cases: Seq[Int] = Seq(0, 1, -1, 42, 1000, -2147483648, 2147483647)
            var last: Unit      = succeed
            cases.foreach { v =>
                last = assert(b.kyoItMakeBox(v) == Box(v))
            }
            last
        }
    }

    "kyoItMakeCircleBlocking (@Ffi.blocking @Ffi.byValue)" - {
        // The struct out-buffer must survive the blocking dispatch boundary on every backend: synchronous on the
        // JVM/Native carrier, and (the interesting case) filled on a libuv worker and decoded inside koffi's completion
        // callback on JS. The awaited fiber must yield the exact filled struct, identically on all three backends.
        "blocking by-value nested-struct returns round-trip on every backend" in {
            val b = Ffi.load[ItStructsBindings]
            for
                c1 <- b.kyoItMakeCircleBlocking(1.0, 2.0, 3.0).safe.get
                c2 <- b.kyoItMakeCircleBlocking(-5.0, 7.5, 2.25).safe.get
                c3 <- b.kyoItMakeCircleBlocking(0.0, 0.0, 0.0).safe.get
            yield
                assert(c1 == Circle(Center(1.0, 2.0), 3.0))
                assert(c2 == Circle(Center(-5.0, 7.5), 2.25))
                assert(c3 == Circle(Center(0.0, 0.0), 0.0))
            end for
        }
    }
end ItStructsTest
