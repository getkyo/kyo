package kyo

import Tagged.*
import kyo.Aspect.Cut

class AspectTest extends Test:

    "one aspect" - {
        val aspect       = Aspect.init[Const[Int], Const[Int], Any]
        def test(v: Int) = aspect(v)(_ + 1)

        "default" taggedAs notNative in run {
            test(1).map(v => assert(v == 2))
        }

        "with cut" taggedAs notNative in run {
            aspect.let([C] => (input, cont) => cont(input + 1)) {
                test(1).map(v => assert(v == 3))
            }
        }

        "sandboxed" taggedAs notNative in run {
            aspect.let([C] => (input, cont) => cont(input + 1)) {
                aspect.sandbox {
                    test(1)
                }.map(v => assert(v == 2))
            }
        }

        "nested cuts" taggedAs notNative in run {
            aspect.let([C] => (input, cont) => cont(input * 3)) {
                aspect.let([C] => (input, cont) => cont(input + 5)) {
                    test(2).map(v => assert(v == 2 * 3 + 5 + 1))
                }
            }
        }
    }

    "multiple aspects" - {
        "independent aspects" taggedAs notNative in run {
            val aspect1 = Aspect.init[Const[Int], Const[Int], Any]
            val aspect2 = Aspect.init[Const[Int], Const[Int], Any]

            def test(v: Int) =
                for
                    v1 <- aspect1(v)(_ + 1)
                    v2 <- aspect2(v)(_ + 1)
                yield (v1, v2)

            aspect1.let([C] => (input, cont) => cont(input * 3)) {
                aspect2.let([C] => (input, cont) => cont(input + 5)) {
                    test(2).map(v => assert(v == (2 * 3 + 1, 2 + 5 + 1)))
                }
            }
        }

        "chained aspects" taggedAs notNative in run {
            val aspect1 = Aspect.init[Const[Int], Const[Int], Any]
            val aspect2 = Aspect.init[Const[Int], Const[Int], Any]

            def test(v: Int) =
                aspect1(v)(v1 => aspect2(v1)(v2 => v2 + 1))

            aspect1.let([C] => (input, cont) => cont(input * 2)) {
                aspect2.let([C] => (input, cont) => cont(input + 3)) {
                    test(2).map(v => assert(v == ((2 * 2) + 3) + 1))
                }
            }
        }
    }

    "use aspect as a cut" taggedAs notNative in run {
        val aspect1 = Aspect.init[Const[Int], Const[Int], Any]
        val aspect2 = Aspect.init[Const[Int], Const[Int], Any]

        def test(v: Int) =
            for
                v1 <- aspect1(v)(_ + 1)
                v2 <- aspect2(v)(_ + 1)
            yield (v1, v2)

        aspect1.let([C] => (input, cont) => cont(input * 3)) {
            aspect2.let(aspect1.asCut) {
                test(2).map(v => assert(v == (2 * 3 + 1, 2 * 3 + 1)))
            }
        }
    }

    "Cut.andThen" taggedAs notNative in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        val chainedCut = Cut.andThen[Const[Int], Const[Int], Any](
            [C] => (input, cont) => cont(input * 2),
            [C] => (input, cont) => cont(input + 3)
        )

        aspect.let(chainedCut) {
            aspect(5)(identity).map(v => assert(v == (5 * 2 + 3)))
        }
    }

    "aspect sandbox with multiple aspects" taggedAs notNative in run {
        val aspect1 = Aspect.init[Const[Int], Const[Int], Any]
        val aspect2 = Aspect.init[Const[Int], Const[Int], Any]

        def test(v: Int) =
            for
                v1 <- aspect1(v)(_ + 1)
                v2 <- aspect2(v1)(_ * 2)
            yield v2

        aspect1.let([C] => (input, cont) => cont(input * 3)) {
            aspect2.let([C] => (input, cont) => cont(input + 5)) {
                aspect1.sandbox {
                    test(2)
                }.map(v => assert(v == (2 + 5 + 1) * 2))
            }
        }
    }

    "nested aspect lets" taggedAs notNative in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        def test(v: Int) = aspect(v)(_ + 1)

        aspect.let([C] => (input, cont) => cont(input * 2)) {
            aspect.let([C] => (input, cont) => cont(input + 3)) {
                test(2).map(v => assert(v == (2 * 2 + 3) + 1))
            }
        }
    }

    "aspect order of application" taggedAs notNative in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        def test(v: Int) = aspect(v)(_ + 1)

        val cut1 = Cut[Const[Int], Const[Int], Any]([C] => (input, cont) => cont(input * 2))
        val cut2 = Cut[Const[Int], Const[Int], Any]([C] => (input, cont) => cont(input + 3))

        aspect.let(cut2) {
            aspect.let(cut1) {
                test(2).map(v => assert(v == (2 + 3) * 2 + 1))
            }
        }
    }

    "aspect reuse after let" taggedAs notNative in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        def test(v: Int) = aspect(v)(_ + 1)

        aspect.let([C] => (input, cont) => cont(input * 2)) {
            test(2).map(v => assert(v == 2 * 2 + 1))
        }

        test(2).map(v => assert(v == 3))
    }

    "aspect chain with identity cut" taggedAs notNative in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        val chainedCut = Cut.andThen[Const[Int], Const[Int], Any](
            [C] => (input, cont) => cont(input * 2),
            [C] => (input, cont) => cont(input)
        )

        aspect.let(chainedCut) {
            aspect(5)(identity).map(v => assert(v == 5 * 2))
        }
    }

    "aspect interaction with effects" taggedAs notNative in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Var[Int]]

        def test(v: Int) =
            for
                _      <- Var.update[Int](_ + v)
                result <- aspect(v)(_ + 1)
                s      <- Var.get[Int]
            yield (result, s)

        val cut =
            Cut[Const[Int], Const[Int], Var[Int]] {
                [C] =>
                    (input, cont) =>
                        for
                            _      <- Var.update[Int](_ * 2)
                            result <- cont(input * 2)
                        yield result
            }

        Var.run(0) {
            aspect.let(cut) {
                test(3).map { case (result, s) =>
                    assert(result == 3 * 2 + 1)
                    assert(s == 6) // (0 + 3) * 2
                }
            }
        }
    }

    "non-Const type parameters" - {
        case class Wrapped[+A, B](value: A, meta: B) derives CanEqual
        case class Container[+A](value: A, meta: String) derives CanEqual

        // TODO SIGSEGV in Scala Native
        "with same input/output wrapper" taggedAs notNative in run {
            val aspect = Aspect.init[Wrapped[*, String], Wrapped[*, String], Any]

            def test[A](v: A) =
                aspect(Wrapped(v, "init"))(w =>
                    Wrapped(w.value, w.meta + "-processed")
                )

            aspect.let([C] => (input, cont) => cont(Wrapped(input.value, input.meta + "-modified"))) {
                test(42).map { result =>
                    assert(result == Wrapped(42, "init-modified-processed"))
                }
            }
        }

        "with different input/output wrappers" taggedAs notNative in run {
            val aspect = Aspect.init[Wrapped[*, String], Container, Any]

            def test[A](v: A) = aspect(Wrapped(v, "init"))(w =>
                Container(w.value, w.meta)
            )

            aspect.let([C] =>
                (input, cont) =>
                    if input.meta == "init" then
                        cont(Wrapped(input.value, "modified"))
                    else cont(input)) {
                test[String]("test").map { result =>
                    assert(result.value == "test")
                    assert(result.meta == "modified")
                }
            }
        }

        "with multiple type parameters" taggedAs notNative in run {
            case class DataResult[+A, +B](data: A, extra: B) derives CanEqual

            val aspect = Aspect.init[Wrapped[*, String], DataResult[*, Int], Any]

            def test[A](v: A) = aspect(Wrapped(v, "meta")) { w =>
                DataResult(w.value, w.meta.length)
            }

            aspect.let([C] => (input, cont) => cont(Wrapped(input.value, input.meta + "!"))) {
                test[String]("hello").map { result =>
                    assert(result == DataResult("hello", 5))
                }
            }
        }

        "sandbox with generic parameters" taggedAs notNative in run {
            val aspect = Aspect.init[[A] =>> Wrapped[A, String], [A] =>> Container[A], Any]

            def test[A](v: A) = aspect(Wrapped(v, "init"))(w =>
                Container(w.value, w.meta)
            )

            aspect.let([C] => (input, cont) => cont(Wrapped(input.value, input.meta + "-modified"))) {
                aspect.sandbox {
                    test[String]("test")
                }.map { result =>
                    assert(result == Container("test", "init")) // Original behavior
                }
            }
        }
    }

    "init" - {
        "with default binding" taggedAs notNative in run {
            val defaultBinding: Cut[Const[Int], Const[Int], Any] =
                [C] => (input, cont) => cont(input * 2)

            val aspect = Aspect.init(defaultBinding)

            aspect(5)(identity).map { result =>
                assert(result == 10)
            }
        }

        "with default cut" taggedAs notNative in run {
            val defaultCut = Cut[Const[Int], Const[Int], Any] {
                [C] =>
                    (input, cont) => cont(input + 10)
            }

            val aspect = Aspect.init(defaultCut)

            aspect(5)(identity).map { result =>
                assert(result == 15)
            }
        }

        "override default behavior" taggedAs notNative in run {
            val defaultBinding: Cut[Const[Int], Const[Int], Any] =
                [C] => (input, cont) => cont(input * 2)

            val aspect = Aspect.init(defaultBinding)

            aspect.let([C] => (input, cont) => cont(input + 3)) {
                aspect(5)(identity).map { result =>
                    assert(result == 8)
                }
            }
        }
    }

end AspectTest
