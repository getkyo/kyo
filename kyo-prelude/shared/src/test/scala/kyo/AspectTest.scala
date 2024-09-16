package kyo

class AspectTest extends Test:

    "one aspect" - {
        val aspect       = Aspect.init[Int, Int, Any]
        def test(v: Int) = aspect(v)(_ + 1)

        "default" in run {
            test(1).map(v => assert(v == 2))
        }

        "with cut" in run {
            val cut = new Cut[Int, Int, Any]:
                def apply[S](v: Int < S)(f: Int => Int < Any) =
                    v.map(v => f(v + 1))
            aspect.let[Assertion, Any](cut) {
                test(1).map(v => assert(v == 3))
            }
        }

        "sandboxed" in run {
            val cut = new Cut[Int, Int, Any]:
                def apply[S](v: Int < S)(f: Int => Int < Any) =
                    v.map(v => f(v + 1))
            aspect.let[Assertion, Any](cut) {
                aspect.sandbox {
                    test(1)
                }.map(v => assert(v == 2))
            }
        }

        "nested cuts" in run {
            val cut1 = new Cut[Int, Int, Any]:
                def apply[S](v: Int < S)(f: Int => Int < Any) =
                    v.map(v => f(v * 3))
            val cut2 = new Cut[Int, Int, Any]:
                def apply[S](v: Int < S)(f: Int => Int < Any) =
                    v.map(v => f(v + 5))
            aspect.let[Assertion, Any](cut1) {
                aspect.let[Assertion, Any](cut2) {
                    test(2).map(v => assert(v == 2 * 3 + 5 + 1))
                }
            }
        }
    }

    "multiple aspects" in run {
        val aspect1 = Aspect.init[Int, Int, Any]
        val aspect2 = Aspect.init[Int, Int, Any]

        def test(v: Int) =
            for
                v1 <- aspect1(v)(_ + 1)
                v2 <- aspect2(v)(_ + 1)
            yield (v1, v2)

        val cut1 = new Cut[Int, Int, Any]:
            def apply[S](v: Int < S)(f: Int => Int < Any) =
                v.map(v => f(v * 3))
        val cut2 = new Cut[Int, Int, Any]:
            def apply[S](v: Int < S)(f: Int => Int < Any) =
                v.map(v => f(v + 5))
        aspect1.let[Assertion, Any](cut1) {
            aspect2.let[Assertion, Any](cut2) {
                test(2).map(v => assert(v == (2 * 3 + 1, 2 + 5 + 1)))
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
            def apply[S](v: Int < S)(f: Int => Int < Any) =
                v.map(v => f(v * 3))
        aspect1.let[Assertion, Any](cut) {
            aspect2.let[Assertion, Any](aspect1) {
                test(2).map(v => assert(v == (2 * 3 + 1, 2 * 3 + 1)))
            }
        }
    }
end AspectTest
