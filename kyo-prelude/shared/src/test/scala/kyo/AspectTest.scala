package kyo

import kyo.Aspect.Cut

class AspectTest extends Test:

    "one aspect" - {
        val aspect       = Aspect.init[Int, Int, Any]
        def test(v: Int) = aspect(v)(_ + 1)

        "default" in run {
            test(1).map(v => assert(v == 2))
        }

        "with cut" in run {
            val cut = new Cut[Int, Int, Any]:
                def apply(v: Int)(f: Int => Int < Any) =
                    f(v + 1)
            aspect.let[Assertion, Any](cut) {
                test(1).map(v => assert(v == 3))
            }
        }

        "sandboxed" in run {
            val cut = new Cut[Int, Int, Any]:
                def apply(v: Int)(f: Int => Int < Any) =
                    f(v + 1)
            aspect.let[Assertion, Any](cut) {
                aspect.sandbox {
                    test(1)
                }.map(v => assert(v == 2))
            }
        }

        "nested cuts" in run {
            val cut1 = new Cut[Int, Int, Any]:
                def apply(v: Int)(f: Int => Int < Any) =
                    f(v * 3)
            val cut2 = new Cut[Int, Int, Any]:
                def apply(v: Int)(f: Int => Int < Any) =
                    f(v + 5)
            aspect.let[Assertion, Any](cut1) {
                aspect.let[Assertion, Any](cut2) {
                    test(2).map(v => assert(v == 2 * 3 + 5 + 1))
                }
            }
        }
    }

    "multiple aspects" - {
        "independent aspects" in run {
            val aspect1 = Aspect.init[Int, Int, Any]
            val aspect2 = Aspect.init[Int, Int, Any]

            def test(v: Int) =
                for
                    v1 <- aspect1(v)(_ + 1)
                    v2 <- aspect2(v)(_ + 1)
                yield (v1, v2)

            val cut1 = new Cut[Int, Int, Any]:
                def apply(v: Int)(f: Int => Int < Any) =
                    f(v * 3)
            val cut2 = new Cut[Int, Int, Any]:
                def apply(v: Int)(f: Int => Int < Any) =
                    f(v + 5)
            aspect1.let[Assertion, Any](cut1) {
                aspect2.let[Assertion, Any](cut2) {
                    test(2).map(v => assert(v == (2 * 3 + 1, 2 + 5 + 1)))
                }
            }
        }

        "chained aspects" in run {
            val aspect1 = Aspect.init[Int, Int, Any]
            val aspect2 = Aspect.init[Int, Int, Any]

            def test(v: Int) =
                aspect1(v) { v1 =>
                    aspect2(v1) { v2 =>
                        v2 + 1
                    }
                }

            val cut1 = new Cut[Int, Int, Any]:
                def apply(v: Int)(f: Int => Int < Any) =
                    f(v * 2)
            val cut2 = new Cut[Int, Int, Any]:
                def apply(v: Int)(f: Int => Int < Any) =
                    f(v + 3)

            aspect1.let[Assertion, Any](cut1) {
                aspect2.let[Assertion, Any](cut2) {
                    test(2).map(v => assert(v == ((2 * 2) + 3) + 1))
                }
            }
        }
    }

    "use aspect as a cut" in run {
        val aspect1 = Aspect.init[Int, Int, Any]
        val aspect2 = Aspect.init[Int, Int, Any]

        def test(v: Int) =
            for
                v1 <- aspect1(v)(_ + 1)
                v2 <- aspect2(v)(_ + 1)
            yield (v1, v2)

        val cut = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v * 3)
        aspect1.let[Assertion, Any](cut) {
            aspect2.let[Assertion, Any](aspect1) {
                test(2).map(v => assert(v == (2 * 3 + 1, 2 * 3 + 1)))
            }
        }
    }

    "aspect chain" in run {
        val aspect = Aspect.init[Int, Int, Any]

        val cut1 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v * 2)
        val cut2 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v + 3)
        val cut3 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v - 1)

        val chainedCut = Aspect.chain(cut1, Seq(cut2, cut3))

        aspect.let[Assertion, Any](chainedCut) {
            aspect(5) { v => v }.map(v => assert(v == ((5 * 2 + 3) - 1)))
        }
    }

    "aspect sandbox with multiple aspects" in run {
        val aspect1 = Aspect.init[Int, Int, Any]
        val aspect2 = Aspect.init[Int, Int, Any]

        def test(v: Int) =
            for
                v1 <- aspect1(v)(_ + 1)
                v2 <- aspect2(v1)(_ * 2)
            yield v2

        val cut1 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v * 3)
        val cut2 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v + 5)
        aspect1.let[Assertion, Any](cut1) {
            aspect2.let[Assertion, Any](cut2) {
                aspect1.sandbox {
                    test(2)
                }.map(v => assert(v == (2 + 5 + 1) * 2))
            }
        }
    }

    "nested aspect lets" in run {
        val aspect = Aspect.init[Int, Int, Any]

        def test(v: Int) = aspect(v)(_ + 1)

        val cut1 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v * 2)
        val cut2 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v + 3)

        aspect.let[Assertion, Any](cut1) {
            aspect.let[Assertion, Any](cut2) {
                test(2).map(v => assert(v == (2 * 2 + 3) + 1))
            }
        }
    }

    "aspect order of application" in run {
        val aspect = Aspect.init[Int, Int, Any]

        def test(v: Int) = aspect(v)(_ + 1)

        val cut1 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v * 2)
        val cut2 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v + 3)

        aspect.let[Assertion, Any](cut1) {
            aspect.let[Assertion, Any](cut2) {
                test(2).map(v => assert(v == (2 * 2 + 3) + 1))
            }
        }

        aspect.let[Assertion, Any](cut2) {
            aspect.let[Assertion, Any](cut1) {
                test(2).map(v => assert(v == (2 + 3) * 2 + 1))
            }
        }
    }

    "aspect reuse after let" in run {
        val aspect = Aspect.init[Int, Int, Any]

        def test(v: Int) = aspect(v)(_ + 1)

        val cut = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v * 2)

        aspect.let[Assertion, Any](cut) {
            test(2).map(v => assert(v == 2 * 2 + 1))
        }

        test(2).map(v => assert(v == 3))
    }

    "aspect chain with identity cut" in run {
        val aspect = Aspect.init[Int, Int, Any]

        val cut1 = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v * 2)

        val identityCut = new Cut[Int, Int, Any]:
            def apply(v: Int)(f: Int => Int < Any) =
                f(v)

        val chainedCut = Aspect.chain(cut1, Seq(identityCut))

        aspect.let[Assertion, Any](chainedCut) {
            aspect(5) { v => v }.map(v => assert(v == 5 * 2))
        }
    }

    "aspect interaction with effects" in run {
        val aspect = Aspect.init[Int, Int, Var[Int]]

        def test(v: Int) =
            for
                _      <- Var.update[Int](_ + v)
                result <- aspect(v)(_ + 1)
                s      <- Var.get[Int]
            yield (result, s)

        val cut = new Cut[Int, Int, Var[Int]]:
            def apply(v: Int)(f: Int => Int < Var[Int]) =
                for
                    _      <- Var.update[Int](_ * 2)
                    result <- f(v * 2)
                yield result

        Var.run(0) {
            aspect.let(cut) {
                test(3).map { case (result, s) =>
                    assert(result == 3 * 2 + 1)
                    assert(s == 6) // (0 + 3) * 2
                }
            }
        }
    }

end AspectTest
