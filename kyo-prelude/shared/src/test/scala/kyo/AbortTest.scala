package kyo

class AbortsTest extends Test:

    case class Ex1() extends RuntimeException derives CanEqual
    case class Ex2() derives CanEqual

    val ex1 = new Ex1
    val ex2 = new Ex2

    "pure" - {
        "handle" in {
            assert(
                Abort.run[Ex1](Abort.get[Ex1](Right(1))).eval ==
                    Result.success(1)
            )
        }
        "handle + transform" in {
            assert(
                Abort.run[Ex1](Abort.get[Ex1](Right(1)).map(_ + 1)).eval ==
                    Result.success(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                Abort.run[Ex1](Abort.get[Ex1](Right(1)).map(i =>
                    Abort.get[Ex1](Right(i + 1))
                )).eval ==
                    Result.success(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                Abort.run[Ex1](Abort.get[Ex1](Right(1)).map(_ + 1).map(i =>
                    Abort.get[Ex1](Right(i + 1))
                )).eval ==
                    Result.success(3)
            )
        }
        "handle + transform + failed effectful transform" in {
            val fail = Left[Ex1, Int](ex1)
            assert(
                Abort.run[Ex1](Abort.get[Ex1](Right(1)).map(_ + 1).map(_ =>
                    Abort.get(fail)
                )).eval ==
                    Result.fail(ex1)
            )
        }
        "union tags" - {
            "in suspend 1" in {
                val effect1: Int < Abort[String | Boolean] =
                    Abort.fail("failure")
                val handled1: Result[String, Int] < Abort[Boolean] =
                    Abort.run[String](effect1)
                val handled2: Result[Boolean, Result[String, Int]] =
                    Abort.run[Boolean](handled1).eval
                assert(handled2 == Result.success(Result.fail("failure")))
            }
            "in suspend 2" in {
                val effect1: Int < Abort[String | Boolean] =
                    Abort.fail("failure")
                val handled1: Result[Boolean, Int] < Abort[String] =
                    Abort.run[Boolean](effect1)
                val handled2: Result[String, Result[Boolean, Int]] =
                    Abort.run[String](handled1).eval
                assert(handled2 == Result.fail("failure"))
            }
            "in handle" in {
                val effect: Int < Abort[String | Boolean] =
                    Abort.fail("failure")
                val handled = Abort.run[String | Boolean](effect).eval
                assert(handled == Result.fail("failure"))
            }
        }
        "try" in {
            import scala.util.Try

            assert(Abort.run(Abort.get(Try(throw ex1))).eval == Result.fail(ex1))
            assert(Abort.run(Abort.get(Try("success!"))).eval == Result.success("success!"))
        }
    }

    "get" - {
        "either" in {
            assert(Abort.run(Abort.get(Left(1))).eval == Result.fail(1))
            assert(Abort.run(Abort.get[Ex1](Right(1))).eval == Result.success(1))
        }
        "result" in {
            assert(Abort.run(Abort.get(Result.success[Ex1, Int](1))).eval == Result.success(1))
            assert(Abort.run(Abort.get(Result.fail(ex1))).eval == Result.fail(ex1))
        }
        "option" in {
            assert(Abort.run(Abort.get(Option.empty)).eval == Result.fail(Absent))
            assert(Abort.run(Abort.get(Some(1))).eval == Result.success(1))
        }
        "maybe" in {
            assert(Abort.run(Abort.get(Maybe.empty)).eval == Result.fail(Absent))
            assert(Abort.run(Abort.get(Maybe(1))).eval == Result.success(1))
        }
    }

    "effectful" - {
        "success" - {
            val v: Int < Abort[Ex1] = Abort.get[Ex1](Right(1))
            "handle" in {
                assert(
                    Abort.run[Ex1](v).eval ==
                        Result.success(1)
                )
            }
            "handle + transform" in {
                assert(
                    Abort.run[Ex1](v.map(_ + 1)).eval ==
                        Result.success(2)
                )
            }
            "handle + effectful transform" in {
                assert(
                    Abort.run[Ex1](v.map(i => Abort.get[Ex1](Right(i + 1)))).eval ==
                        Result.success(2)
                )
            }
            "handle + transform + effectful transform" in {
                assert(
                    Abort.run[Ex1](v.map(_ + 1).map(i => Abort.get[Ex1](Right(i + 1)))).eval ==
                        Result.success(3)
                )
            }
            "handle + transform + failed effectful transform" in {
                val fail = Left[Ex1, Int](ex1)
                assert(
                    Abort.run[Ex1](v.map(_ + 1).map(_ => Abort.get(fail))).eval ==
                        Result.fail(ex1)
                )
            }
        }
        "failure" - {
            val v: Int < Abort[Ex1] = Abort.get(Left(ex1))
            "handle" in {
                assert(
                    Abort.run[Ex1](v).eval ==
                        Result.fail(ex1)
                )
            }
            "handle + transform" in {
                assert(
                    Abort.run[Ex1](v.map(_ + 1)).eval ==
                        Result.fail(ex1)
                )
            }
            "handle + effectful transform" in {
                assert(
                    Abort.run[Ex1](v.map(i => Abort.get[Ex1](Right(i + 1)))).eval ==
                        Result.fail(ex1)
                )
            }
            "handle + transform + effectful transform" in {
                assert(
                    Abort.run[Ex1](v.map(_ + 1).map(i => Abort.get[Ex1](Right(i + 1)))).eval ==
                        Result.fail(ex1)
                )
            }
            "handle + transform + failed effectful transform" in {
                val fail = Left[Ex1, Int](ex1)
                assert(
                    Abort.run[Ex1](v.map(_ + 1).map(_ => Abort.get(fail))).eval ==
                        Result.fail(ex1)
                )
            }
        }
    }

    "Abort" - {
        def test(v: Int): Int < Abort[Ex1] =
            v match
                case 0 =>
                    Abort.fail(ex1)
                case i => 10 / i
        "run" - {
            "success" in {
                assert(
                    Abort.run[Ex1](test(2)).eval ==
                        Result.success(5)
                )
            }
            "failure" in {
                assert(
                    Abort.run[Ex1](test(0)).eval ==
                        Result.fail(ex1)
                )
            }
            "panic" in {
                val p = new Exception
                assert(
                    Abort.run[Ex1](throw p).eval ==
                        Result.panic(p)
                )
            }
            "suspension + panic" in {
                val p = new Exception
                assert(
                    Abort.run[Ex1](Abort.get(Right(1)).map(_ => throw p)).eval ==
                        Result.panic(p)
                )
            }
            "inference" in {
                def t1(v: Int < Abort[Int | String]) =
                    Abort.run[Int](v)
                val _: Result[Int, Int] < Abort[String] =
                    t1(42)
                def t2(v: Int < (Abort[Int] & Abort[String])) =
                    Abort.run[String](v)
                val _: Result[String, Int] < Abort[Int] =
                    t2(42)
                def t3(v: Int < Abort[Int]) =
                    Abort.run[Int](v).eval
                val _: Result[Int, Int] =
                    t3(42)
                succeed
            }
            "super" in {
                val ex                        = new Exception
                val a: Int < Abort[Exception] = Abort.fail(ex)
                val b: Result[Throwable, Int] = Abort.run[Throwable](a).eval
                assert(b == Result.fail(ex))
            }
            "super success" in {
                val a: Int < Abort[Exception] = 24
                val b: Result[Throwable, Int] = Abort.run[Throwable](a).eval
                assert(b == Result.success(24))
            }
            "reduce large union incrementally" in {
                val t1: Int < Abort[Int | String | Boolean | Float | Char | Double] =
                    18
                val t2 = Abort.run[Int](t1)
                val t3 = Abort.run[String](t2)
                val t4 = Abort.run[Boolean](t3)
                val t5 = Abort.run[Float](t4)
                val t6 = Abort.run[Char](t5)
                val t7 = Abort.run[Double](t6)
                assert(t7.eval == Result.success(Result.success(Result.success(Result.success(Result.success(Result.success(18)))))))
            }
            "reduce large union in a single expression" in {
                val t: Int < Abort[Int | String | Boolean | Float | Char | Double] = 18
                // NB: Adding type annotation leads to compilation error
                val res =
                    Abort.run[Double](
                        Abort.run[Char](
                            Abort.run[Float](
                                Abort.run[Boolean](
                                    Abort.run[String](
                                        Abort.run[Int](t)
                                    )
                                )
                            )
                        )
                    ).eval
                val expected: Result[Double, Result[Char, Result[Float, Result[Boolean, Result[String, Result[Int, Int]]]]]] =
                    Result.success(Result.success(Result.success(Result.success(Result.success(Result.success(18))))))
                assert(res == expected)
            }
            "doesn't produce Fail if E isn't Throwable" in run {
                val ex = new Exception
                Abort.run[Any](throw ex).map(result => assert(result == Result.panic(ex)))
            }
            "nested panic to fail" - {
                "converts matching Panic to Fail" in {
                    val ex     = new RuntimeException("Test exception")
                    val result = Abort.run[RuntimeException](Abort.panic(ex)).eval
                    assert(result == Result.panic(ex))
                }

                "leaves non-matching Panic as Panic" in {
                    val ex     = new IllegalArgumentException("Test exception")
                    val result = Abort.run[NoSuchElementException](Abort.panic(ex)).eval
                    assert(result == Result.panic(ex))
                }

                "doesn't affect Success" in {
                    val result = Abort.run[RuntimeException](42).eval
                    assert(result == Result.success(42))
                }

                "doesn't affect Fail" in {
                    val ex     = new RuntimeException("Test exception")
                    val result = Abort.run[RuntimeException](Abort.fail(ex)).eval
                    assert(result == Result.fail(ex))
                }

                "works with nested Aborts" in {
                    val ex = new RuntimeException("Inner exception")
                    val nested = Abort.run[IllegalArgumentException] {
                        Abort.run[RuntimeException](Abort.panic(ex))
                    }
                    val result = nested.eval
                    assert(result == Result.success(Result.panic(ex)))
                }
            }
        }
        "fail" in {
            val ex: Throwable = new Exception("throwable failure")
            val a             = Abort.fail(ex)
            assert(Abort.run[Throwable](a).eval == Result.fail(ex))
        }
        "fail inferred" in {
            val e = "test"
            val f = Abort.fail(e)
            assert(Abort.run(f).eval == Result.fail(e))
        }
        "error" - {
            "fail" in {
                val ex: Throwable = new Exception("throwable failure")
                val a             = Abort.error(Result.Fail(ex))
                assert(Abort.run[Throwable](a).eval == Result.fail(ex))
            }
            "panic" in {
                val ex: Throwable = new Exception("throwable failure")
                val a             = Abort.error(Result.Panic(ex))
                assert(Abort.run[Throwable](a).eval == Result.panic(ex))
            }
        }
        "when" - {
            "basic usage" in {
                def test(b: Boolean) = Abort.run[String](Abort.when(b)("FAIL!")).eval

                assert(test(true) == Result.fail("FAIL!"))
                assert(test(false) == Result.success(()))
            }

            "with Env" in {
                def test(env: Int) = Env.run(env) {
                    Abort.run[String](
                        for
                            x <- Env.get[Int]
                            _ <- Abort.when(x > 5)("Too big")
                            _ <- Abort.when(x < 0)("Negative")
                        yield x
                    )
                }.eval

                assert(test(3) == Result.success(3))
                assert(test(7) == Result.fail("Too big"))
                assert(test(-1) == Result.fail("Negative"))
            }

            "with Var" in {
                def test(initial: Int) = Var.run(initial) {
                    Abort.run[String] {
                        for
                            v  <- Var.get[Int]
                            _  <- Abort.when(v % 2 == 0)("Even")
                            _  <- Var.update[Int](_ + 1)
                            v2 <- Var.get[Int]
                            _  <- Abort.when(v2 > 5)("Too big")
                        yield Var.get[Int]
                    }
                }.eval

                assert(test(1) == Result.success(2))
                assert(test(2) == Result.fail("Even"))
                assert(test(5) == Result.fail("Too big"))
            }

            "short-circuiting" in {
                var sideEffect = 0
                def test(b: Boolean) = Abort.run[String] {
                    for
                        _ <- Abort.when(b)("FAIL!")
                        _ <- Env.use[Unit](_ => sideEffect += 1)
                    yield ()
                }

                assert(Env.run(())(test(true)).eval == Result.fail("FAIL!"))
                assert(sideEffect == 0)
                assert(Env.run(())(test(false)).eval == Result.success(()))
                assert(sideEffect == 1)
            }
        }

        "ensuring" - {
            "basic usage" in {
                def test(x: Int) = Abort.run[String](Abort.ensuring(x > 0, x)("Non-positive")).eval

                assert(test(5) == Result.success(5))
                assert(test(0) == Result.fail("Non-positive"))
                assert(test(-3) == Result.fail("Non-positive"))
            }

            "with Env" in {
                def test(env: Int) = Env.run(env) {
                    Abort.run[String] {
                        for
                            x      <- Env.get[Int]
                            result <- Abort.ensuring(x >= 0 && x <= 10, x)("Out of range")
                        yield result * 2
                    }
                }.eval

                assert(test(5) == Result.success(10))
                assert(test(0) == Result.success(0))
                assert(test(10) == Result.success(20))
                assert(test(-1) == Result.fail("Out of range"))
                assert(test(11) == Result.fail("Out of range"))
            }

            "with Var" in {
                def test(initial: Int) = Var.run(initial) {
                    Abort.run[String] {
                        for
                            x      <- Var.get[Int]
                            _      <- Abort.ensuring(x % 2 == 0, ())("Odd")
                            _      <- Var.update[Int](_ * 2)
                            result <- Var.get[Int]
                        yield result
                    }
                }.eval

                assert(test(2) == Result.success(4))
                assert(test(4) == Result.success(8))
                assert(test(1) == Result.fail("Odd"))
                assert(test(3) == Result.fail("Odd"))
            }

        }
        "catching" - {
            "only effect" - {
                def test(v: Int): Int =
                    v match
                        case 0 => throw ex1
                        case i => 10 / i
                "success" in {
                    assert(
                        Abort.run[Ex1](Abort.catching[Ex1](test(2))).eval ==
                            Result.success(5)
                    )
                }
                "failure" in {
                    assert(
                        Abort.run[Ex1](Abort.catching[Ex1](test(0))).eval ==
                            Result.fail(ex1)
                    )
                }
                "subclass" in {
                    assert(
                        Abort.run[RuntimeException](Abort.catching[RuntimeException](test(0))).eval ==
                            Result.fail(ex1)
                    )
                }
                "distinct" in {
                    class Distinct1 extends Throwable derives CanEqual
                    val d1 = new Distinct1
                    class Distinct2 extends Throwable derives CanEqual
                    val d2 = new Distinct2

                    val distinct: Boolean < Abort[Distinct1 | Distinct2] =
                        for
                            _ <- Abort.catching[Distinct1](throw d1)
                            _ <- Abort.catching[Distinct2](throw d2)
                        yield true

                    val a = Abort.run[Distinct1](distinct)
                    val b = Abort.run[Distinct2](a).eval
                    assert(b == Result.success(Result.fail(d1)))
                    val c = Abort.run[Distinct1 | Distinct2](distinct).eval
                    assert(c == Result.fail(d1))
                }
                "ClassTag inference" in {
                    val r = Abort.run(Abort.catching(throw new RuntimeException)).eval
                    assert(r.isPanic)
                }
            }
            "with other effect" - {
                def test(v: Int < Env[Int]): Int < Env[Int] =
                    v.map {
                        case 0 =>
                            throw ex1
                        case i =>
                            10 / i
                    }
                "success" in {
                    assert(
                        Env.run(2)(
                            Abort.run[Ex1](Abort.catching[Ex1](test(Env.get)))
                        ).eval ==
                            Result.success(5)
                    )
                }
                "failure" in {
                    assert(
                        Env.run(0)(
                            Abort.run[Ex1](Abort.catching[Ex1](test(Env.get)))
                        ).eval ==
                            Result.fail(ex1)
                    )
                }
            }

            "nested Abort effects" - {
                "should propagate the innermost failure" in {
                    val nested = Abort.run[String](
                        Abort.run[Int](
                            Abort.fail[String]("inner").map(_ => Abort.fail[Int](42))
                        )
                    )
                    assert(nested.eval == Result.fail("inner"))
                }

                "should propagate the outermost failure if there are no inner failures" in {
                    val nested = Abort.run(Abort.run[String](
                        Abort.run[Int](Abort.get[Int](Right(42)))
                    ).map(_ => Abort.fail("outer")))
                    assert(nested.eval == Result.fail("outer"))
                }
            }

            "interactions with Env" - {
                "should have access to the environment within Abort" in {
                    val env    = "test"
                    val result = Env.run(env)(Abort.run[String](Env.get[String]))
                    assert(result.eval == Result.success(env))
                }

                "should propagate Abort failures within Env" in {
                    val result = Env.run("test")(Abort.run[String](Abort.fail("failure")))
                    assert(result.eval == Result.fail("failure"))
                }
            }

            "interactions with Var" - {
                "should have access to the state within Abort" in {
                    val result = Var.run(42)(
                        Abort.run[String](
                            Var.get[Int].map(_.toString)
                        )
                    )
                    assert(result.eval == Result.success("42"))
                }

                "should not modify state on Abort failures" in {
                    val result = Var.run(42)(
                        Abort.run[String](
                            Var.set[Int](24).map(_ => Abort.fail("failure"))
                        )
                    )
                    assert(result.eval == Result.fail("failure"))
                    assert(Var.run(42)(Var.get[Int]).eval == 42)
                }
            }

            "short-circuiting with map" - {
                "should not execute subsequent operations on failure" in {
                    var executed = false
                    val result = Abort.run[String](
                        Abort.fail("failure").map(_ => executed = true)
                    )
                    assert(result.eval == Result.fail("failure"))
                    assert(!executed)
                }

                "should execute subsequent operations on success" in {
                    var executed = false
                    val result = Abort.run(Abort.run[String](
                        Abort.get[Int](Right(42)).map(_ => executed = true)
                    ))
                    assert(result.eval == Result.success(Result.success(())))
                    assert(executed)
                }
            }

            "handle non-union exceptions as panic" in {
                val result = Abort.run[IllegalArgumentException | NumberFormatException](
                    Abort.catching[IllegalArgumentException | NumberFormatException](throw new RuntimeException)
                ).eval

                assert(result.isInstanceOf[Result.Panic])
            }

            "catch exceptions in nested computations" in {
                def nestedComputation(): Int < Abort[ArithmeticException | IllegalArgumentException] =
                    for
                        _ <- Abort.catching[ArithmeticException](10 / 0)
                        _ <- Abort.catching[IllegalArgumentException](throw new IllegalArgumentException)
                    yield 42

                val result = Abort.run[ArithmeticException | IllegalArgumentException](nestedComputation()).eval

                assert(result.failure.exists {
                    case ex: ArithmeticException => true
                    case _                       => false
                })
            }

            "handle success case with union types" in {
                val result = Abort.run[IllegalArgumentException | NumberFormatException](
                    Abort.catching[IllegalArgumentException | NumberFormatException](42)
                ).eval

                assert(result == Result.success(42))
            }
        }
    }

    "interactions with Env and Var" - {
        "nested Abort and Env" in {
            val result = Env.run(5) {
                Abort.run[String] {
                    for
                        x <- Env.get[Int]
                        _ <- if x > 10 then Abort.fail("Too big") else Env.get[Int]
                        y <- Env.use[Int](_ * 2)
                    yield y
                }
            }
            assert(result.eval == Result.success(10))
        }

        "Abort failure through Env and Var" in {
            val result = Env.run(15) {
                Var.run(0) {
                    Abort.run[String] {
                        for
                            x <- Env.get[Int]
                            _ <- Var.update[Int](_ + x)
                            _ <- if x > 10 then Abort.fail("Too big") else Var.get[Int]
                        yield ()
                    }
                }
            }
            assert(result.eval == Result.fail("Too big"))
        }
    }

    "edge cases" - {
        "Abort within map" in {
            val result = Abort.run[String] {
                Env.get[Int].map { x =>
                    if x > 5 then Abort.fail("Too big")
                    else Env.get[Int]
                }
            }
            assert(Env.run(10)(result).eval == Result.fail("Too big"))
        }

        "multiple Aborts in for-comprehension" in {
            val result = Abort.run[String] {
                for
                    x <- Env.get[Int]
                    _ <- if x > 5 then Abort.fail("Too big") else Env.get[Int]
                    y <- Var.get[Int]
                    _ <- if y < 0 then Abort.fail("Negative") else Var.get[Int]
                yield (x, y)
            }
            val finalResult = Env.run(3) {
                Var.run(-1)(result)
            }
            assert(finalResult.eval == Result.fail("Negative"))
        }

        "Abort within Abort" in {
            val innerAbort = Abort.run[Int] {
                Abort.fail[String]("Inner error").map(_ => 42)
            }
            val outerAbort = Abort.run[String] {
                innerAbort.map(x => if x.value.exists(_ > 50) then Abort.fail("Outer error") else x)
            }
            assert(outerAbort.eval == Result.fail("Inner error"))
        }

        "deeply nested Aborts" in {
            def nestedAborts(depth: Int): Int < Abort[Int] =
                if depth == 0 then 0
                else Abort.get(Right(depth)).map(_ => nestedAborts(depth - 1))

            val result = Abort.run(nestedAborts(10000))
            assert(result.eval == Result.success(0))
        }
    }

    "type inference with multiple effects" in {
        val result = Abort.run[String] {
            for
                x <- Env.get[Int]
                y <- Var.get[Int]
                _ <- if x + y > 10 then Abort.fail("Sum too large") else Env.get[Int]
            yield x + y
        }
        val finalResult: Result[String, Int] < (Env[Int] & Var[Int]) = result
        val _                                                        = finalResult
        succeed
    }

    "handling of Abort[Nothing]" - {
        val ex = new RuntimeException("Panic!")

        "handle Abort[Nothing]" in {
            val computation: Int < Abort[Nothing] = Abort.panic(ex)
            val result                            = Abort.run(computation).eval
            val _: Result[Nothing, Int]           = result
            assert(result == Result.panic(ex))
        }

        "handle Abort[Nothing] with custom error type" in {
            val computation: Int < Abort[Nothing] = Abort.panic(ex)
            val result                            = Abort.run[String](computation).eval
            val _: Result[String, Int]            = result
            assert(result == Result.panic(ex))
        }

        "allow handling of pure values" in {
            val computation: Int < Abort[Nothing] = 42
            val result                            = Abort.run(computation).eval
            val _: Result[Nothing, Int]           = result
            assert(result == Result.success(42))
        }

        "work with other effect" in {
            val computation: Int < (Abort[Nothing] & Env[Int]) =
                Env.use[Int](_ => Abort.panic(ex))

            val result =
                Env.run(42)(Abort.run(computation)).eval

            val _: Result[Nothing, Int] = result

            assert(result == Result.panic(ex))
        }
    }

    "Abort.run with parametrized type" in pendingUntilFixed {
        class Test[A]
        assertCompiles("Abort.run(Abort.fail(new Test[Int]))")
    }

    "Abort.run with type unions" - {
        case class CustomError(message: String) derives CanEqual

        "handle part of union types" in {
            val computation: Int < Abort[String | Int | CustomError] = Abort.fail("String error")
            val result                                               = Abort.run[String](computation)
            val finalResult                                          = Abort.run[Int | CustomError](result).eval
            assert(finalResult == Result.success(Result.fail("String error")))
        }

        "handle multiple types from union" in {
            def test(failWithString: Boolean): Result[String | Int, Int] < Abort[CustomError] =
                val computation: Int < Abort[String | Int | CustomError] =
                    if failWithString then Abort.fail("String error")
                    else Abort.fail(42)

                Abort.run[String | Int](computation)
            end test

            assert(Abort.run[CustomError](test(true)).eval == Result.success(Result.fail("String error")))
            assert(Abort.run[CustomError](test(false)).eval == Result.success(Result.fail(42)))
        }

        "handle all types from union" in {
            def test(failureType: Int): Result[String | Int | CustomError, Int] =
                val computation: Int < Abort[String | Int | CustomError] =
                    failureType match
                        case 0 => Abort.fail("String error")
                        case 1 => Abort.fail(42)
                        case 2 => Abort.fail(CustomError("Custom error"))

                Abort.run[String | Int | CustomError](computation).eval
            end test

            assert(test(0) == Result.fail("String error"))
            assert(test(1) == Result.fail(42))
            assert(test(2) == Result.fail(CustomError("Custom error")))
        }

        "handle part of union types with success case" in {
            def test(succeed: Boolean): Result[String | Int, Int] < Abort[CustomError] =
                val computation: Int < Abort[String | Int | CustomError] =
                    if succeed then 100
                    else Abort.fail(CustomError("Custom error"))

                Abort.run[String | Int](computation)
            end test

            assert(Abort.run[CustomError](test(true)).eval == Result.success(Result.success(100)))
            assert(Abort.run[CustomError](test(false)).eval == Result.fail(CustomError("Custom error")))
        }

        "nested handling of union types" in {
            val computation: Int < Abort[String | Int | CustomError | Boolean] =
                Abort.fail("String error")

            val result1 = Abort.run[String](computation)
            val result2 = Abort.run[Int](result1)
            val result3 = Abort.run[CustomError](result2)
            val finalResult: Result[CustomError, Result[Int, Result[String, Int]]] < Abort[Boolean] =
                result3

            assert(Abort.run[Boolean](finalResult).eval == Result.success(Result.success(Result.success(Result.fail("String error")))))
        }
    }

end AbortsTest
