package kyo

import Tagged.*
import kyo.Aspect.Cut

class AspectTest extends Test:

    "one aspect" - {
        val aspect       = Aspect.init[Const[Int], Const[Int], Any]
        def test(v: Int) = aspect(v)(_ + 1)

        "default" in run {
            test(1).map(v => assert(v == 2))
        }

        "with cut" in run {
            aspect.let([C] => (input, cont) => cont(input + 1)) {
                test(1).map(v => assert(v == 3))
            }
        }

        "sandboxed" in run {
            aspect.let([C] => (input, cont) => cont(input + 1)) {
                aspect.sandbox {
                    test(1)
                }.map(v => assert(v == 2))
            }
        }

        "nested cuts" in run {
            aspect.let([C] => (input, cont) => cont(input * 3)) {
                aspect.let([C] => (input, cont) => cont(input + 5)) {
                    test(2).map(v => assert(v == 2 * 3 + 5 + 1))
                }
            }
        }
    }

    "multiple aspects" - {
        "independent aspects" in run {
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

        "chained aspects" in run {
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

    "use aspect as a cut" in run {
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

    "Cut.andThen" in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        val chainedCut = Cut.andThen[Const[Int], Const[Int], Any](
            [C] => (input, cont) => cont(input * 2),
            [C] => (input, cont) => cont(input + 3)
        )

        aspect.let(chainedCut) {
            aspect(5)(identity).map(v => assert(v == (5 * 2 + 3)))
        }
    }

    "aspect sandbox with multiple aspects" in run {
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

    "nested aspect lets" in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        def test(v: Int) = aspect(v)(_ + 1)

        aspect.let([C] => (input, cont) => cont(input * 2)) {
            aspect.let([C] => (input, cont) => cont(input + 3)) {
                test(2).map(v => assert(v == (2 * 2 + 3) + 1))
            }
        }
    }

    "aspect order of application" in run {
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

    "aspect reuse after let" in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        def test(v: Int) = aspect(v)(_ + 1)

        aspect.let([C] => (input, cont) => cont(input * 2)) {
            test(2).map(v => assert(v == 2 * 2 + 1))
        }

        test(2).map(v => assert(v == 3))
    }

    "aspect chain with identity cut" in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        val chainedCut = Cut.andThen[Const[Int], Const[Int], Any](
            [C] => (input, cont) => cont(input * 2),
            [C] => (input, cont) => cont(input)
        )

        aspect.let(chainedCut) {
            aspect(5)(identity).map(v => assert(v == 5 * 2))
        }
    }

    "aspect interaction with effects" in run {
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

        "with same input/output wrapper" in run {
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

        "with different input/output wrappers" in run {
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

        "with multiple type parameters" in run {
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

        "sandbox with generic parameters" in run {
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

    "no binding" in run {
        val aspect = Aspect.init[Const[Int], Const[Int], Any]

        aspect(5)(identity).map { result =>
            assert(result == 5)
        }
    }

    "parametrized generic aspects" - {

        "different type instantiations maintain separate cuts" in run {
            def genericAspect[A: Tag]: Aspect[Const[A], Const[A], Any] =
                Aspect.init[Const[A], Const[A], Any]

            val intAspect    = genericAspect[Int]
            val stringAspect = genericAspect[String]

            def testInt(v: Int)       = intAspect(v)(x => x + 1)
            def testString(v: String) = stringAspect(v)(x => x + "!")

            intAspect.let([C] => (input, cont) => cont(input * 10)) {
                stringAspect.let([C] => (input, cont) => cont(input.toUpperCase)) {
                    for
                        intResult    <- testInt(5)
                        stringResult <- testString("hello")
                    yield
                        assert(intResult == 5 * 10 + 1)
                        assert(stringResult == "HELLO!")
                }
            }
        }

        "same generic aspect with different types don't interfere" in run {
            def processingAspect[A: Tag]: Aspect[Const[A], Const[Option[A]], Any] =
                Aspect.init[Const[A], Const[Option[A]], Any]

            val intProcessor    = processingAspect[Int]
            val stringProcessor = processingAspect[String]

            intProcessor.let([C] =>
                (input, cont) =>
                    if input > 0 then cont(input * 2) else None) {
                stringProcessor.let([C] =>
                    (input, cont) =>
                        if input.nonEmpty then cont(input.toUpperCase) else None) {
                    for
                        r1 <- intProcessor(5)(Some(_))
                        r2 <- intProcessor(-5)(Some(_))
                        r3 <- stringProcessor("test")(Some(_))
                        r4 <- stringProcessor("")(Some(_))
                    yield
                        assert(r1 == Some(10))
                        assert(r2 == None)
                        assert(r3 == Some("TEST"))
                        assert(r4 == None)
                }
            }
        }

        "nested generic aspects with same type parameter but different frames" in run {
            def loggingAspect[A: Tag](using Frame): Aspect[Const[A], Const[(A, String)], Any] =
                Aspect.init[Const[A], Const[(A, String)], Any]

            val outer = loggingAspect[Int]
            val inner = loggingAspect[Int]

            outer.let([C] => (input, cont) => cont(input * 10)) {
                inner.let([C] => (input, cont) => cont(input * 100)) {
                    for
                        r1 <- outer(10)(x => (x * 2, "processed"))
                        r2 <- inner(20)(x => (x * 2, "processed"))
                    yield
                        assert(r1 == (200, "processed"))
                        assert(r2 == (4000, "processed"))
                }
            }
        }

        "generic aspect factory with complex types" in run {
            def validationAspect[A: Tag]: Aspect[Const[A], Const[Either[String, A]], Any] =
                Aspect.init[Const[A], Const[Either[String, A]], Any]

            case class User(name: String, age: Int)

            val userValidator = validationAspect[User]
            val intValidator  = validationAspect[Int]

            userValidator.let([C] =>
                (input, cont) =>
                    if input.age >= 0 && input.name.nonEmpty then
                        cont(input)
                    else
                        Left("Invalid user")) {
                intValidator.let([C] =>
                    (input, cont) =>
                        if input >= 0 then cont(input) else Left("Negative number")) {
                    for
                        r1 <- userValidator(User("Alice", 25))(Right(_))
                        r2 <- userValidator(User("", -1))(Right(_))
                        r3 <- intValidator(42)(Right(_))
                        r4 <- intValidator(-5)(Right(_))
                    yield
                        assert(r1 == Right(User("Alice", 25)))
                        assert(r2 == Left("Invalid user"))
                        assert(r3 == Right(42))
                        assert(r4 == Left("Negative number"))
                }
            }
        }
    }

end AspectTest
