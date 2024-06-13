package kyo2

class AbortsTest extends Test:

    case class Ex1() extends RuntimeException derives CanEqual
    case class Ex2() derives CanEqual

    val ex1 = new Ex1
    val ex2 = new Ex2

    "pure" - {
        "handle" in {
            assert(
                kyo2.Abort.run[Ex1](kyo2.Abort.get[Ex1](Right(1))).eval ==
                    Right(1)
            )
        }
        "handle + transform" in {
            assert(
                kyo2.Abort.run[Ex1](kyo2.Abort.get[Ex1](Right(1)).map(_ + 1)).eval ==
                    Right(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                kyo2.Abort.run[Ex1](kyo2.Abort.get[Ex1](Right(1)).map(i =>
                    kyo2.Abort.get[Ex1](Right(i + 1))
                )).eval ==
                    Right(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                kyo2.Abort.run[Ex1](kyo2.Abort.get[Ex1](Right(1)).map(_ + 1).map(i =>
                    kyo2.Abort.get[Ex1](Right(i + 1))
                )).eval ==
                    Right(3)
            )
        }
        "handle + transform + failed effectful transform" in {
            val fail = Left[Ex1, Int](ex1)
            assert(
                kyo2.Abort.run[Ex1](kyo2.Abort.get[Ex1](Right(1)).map(_ + 1).map(_ =>
                    kyo2.Abort.get(fail)
                )).eval ==
                    fail
            )
        }
        "union tags" - {
            "in suspend 1" in {
                val effect1: Int < Abort[String | Boolean] =
                    kyo2.Abort.fail("failure")
                val handled1: Either[String, Int] < Abort[Boolean] =
                    kyo2.Abort.run[String](effect1)
                val handled2: Either[Boolean, Either[String, Int]] =
                    kyo2.Abort.run[Boolean](handled1).eval
                assert(handled2 == Right(Left("failure")))
            }
            "in suspend 2" in {
                val effect1: Int < Abort[String | Boolean] =
                    kyo2.Abort.fail("failure")
                val handled1: Either[Boolean, Int] < Abort[String] =
                    kyo2.Abort.run[Boolean](effect1)
                val handled2: Either[String, Either[Boolean, Int]] =
                    kyo2.Abort.run[String](handled1).eval
                assert(handled2 == Left("failure"))
            }
            "in handle" in {
                val effect: Int < Abort[String | Boolean] =
                    kyo2.Abort.fail("failure")
                val handled: Either[String | Boolean, Int] =
                    kyo2.Abort.run[String | Boolean](effect).eval
                assert(handled == Left("failure"))
            }
        }
        "try" in {
            import scala.util.Try

            assert(kyo2.Abort.run(kyo2.Abort.get(Try(throw ex1))).eval == Left(ex1))
            assert(kyo2.Abort.run(kyo2.Abort.get(Try("success!"))).eval == Right("success!"))
        }
    }

    "effectful" - {
        "success" - {
            val v: Int < Abort[Ex1] = kyo2.Abort.get[Ex1](Right(1))
            "handle" in {
                assert(
                    kyo2.Abort.run[Ex1](v).eval ==
                        Right(1)
                )
            }
            "handle + transform" in {
                assert(
                    kyo2.Abort.run[Ex1](v.map(_ + 1)).eval ==
                        Right(2)
                )
            }
            "handle + effectful transform" in {
                assert(
                    kyo2.Abort.run[Ex1](v.map(i => kyo2.Abort.get[Ex1](Right(i + 1)))).eval ==
                        Right(2)
                )
            }
            "handle + transform + effectful transform" in {
                assert(
                    kyo2.Abort.run[Ex1](v.map(_ + 1).map(i => kyo2.Abort.get[Ex1](Right(i + 1)))).eval ==
                        Right(3)
                )
            }
            "handle + transform + failed effectful transform" in {
                val fail = Left[Ex1, Int](ex1)
                assert(
                    kyo2.Abort.run[Ex1](v.map(_ + 1).map(_ => kyo2.Abort.get(fail))).eval ==
                        fail
                )
            }
        }
        "failure" - {
            val v: Int < Abort[Ex1] = kyo2.Abort.get(Left(ex1))
            "handle" in {
                assert(
                    kyo2.Abort.run[Ex1](v).eval ==
                        Left(ex1)
                )
            }
            "handle + transform" in {
                assert(
                    kyo2.Abort.run[Ex1](v.map(_ + 1)).eval ==
                        Left(ex1)
                )
            }
            "handle + effectful transform" in {
                assert(
                    kyo2.Abort.run[Ex1](v.map(i => kyo2.Abort.get[Ex1](Right(i + 1)))).eval ==
                        Left(ex1)
                )
            }
            "handle + transform + effectful transform" in {
                assert(
                    kyo2.Abort.run[Ex1](v.map(_ + 1).map(i => kyo2.Abort.get[Ex1](Right(i + 1)))).eval ==
                        Left(ex1)
                )
            }
            "handle + transform + failed effectful transform" in {
                val fail = Left[Ex1, Int](ex1)
                assert(
                    kyo2.Abort.run[Ex1](v.map(_ + 1).map(_ => kyo2.Abort.get(fail))).eval ==
                        Left(ex1)
                )
            }
        }
    }

    "Abort" - {
        def test(v: Int): Int < Abort[Ex1] =
            v match
                case 0 =>
                    kyo2.Abort.fail(ex1)
                case i => 10 / i
        "run" - {
            "success" in {
                assert(
                    kyo2.Abort.run[Ex1](test(2)).eval ==
                        Right(5)
                )
            }
            "failure" in {
                assert(
                    kyo2.Abort.run[Ex1](test(0)).eval ==
                        Left(ex1)
                )
            }
            "inference" in {
                def t1(v: Int < Abort[Int | String]) =
                    kyo2.Abort.run[Int](v)
                val _: Either[Int, Int] < Abort[String] =
                    t1(42)
                def t2(v: Int < (Abort[Int] & Abort[String])) =
                    kyo2.Abort.run[String](v)
                val _: Either[String, Int] < Abort[Int] =
                    t2(42)
                def t3(v: Int < Abort[Int]) =
                    kyo2.Abort.run[Int](v).eval
                val _: Either[Int, Int] =
                    t3(42)
                succeed
            }
            "super" in {
                val ex                        = new Exception
                val a: Int < Abort[Exception] = kyo2.Abort.fail(ex)
                val b: Either[Throwable, Int] = kyo2.Abort.run[Throwable](a).eval
                assert(b == Left(ex))
            }
            "super success" in {
                val a: Int < Abort[Exception] = 24
                val b: Either[Throwable, Int] = kyo2.Abort.run[Throwable](a).eval
                assert(b == Right(24))
            }
            "reduce large union incrementally" in {
                val t1: Int < Abort[Int | String | Boolean | Float | Char | Double] =
                    18
                val t2 = kyo2.Abort.run[Int](t1)
                val t3 = kyo2.Abort.run[String](t2)
                val t4 = kyo2.Abort.run[Boolean](t3)
                val t5 = kyo2.Abort.run[Float](t4)
                val t6 = kyo2.Abort.run[Char](t5)
                val t7 = kyo2.Abort.run[Double](t6)
                assert(t7.eval == Right(Right(Right(Right(Right(Right(18)))))))
            }
            "reduce large union in a single expression" in {
                val t: Int < Abort[Int | String | Boolean | Float | Char | Double] = 18
                // NB: Adding type annotation leads to compilation error
                val res =
                    kyo2.Abort.run[Double](
                        kyo2.Abort.run[Char](
                            kyo2.Abort.run[Float](
                                kyo2.Abort.run[Boolean](
                                    kyo2.Abort.run[String](
                                        kyo2.Abort.run[Int](t)
                                    )
                                )
                            )
                        )
                    ).eval
                val expected: Either[Double, Either[Char, Either[Float, Either[Boolean, Either[String, Either[Int, Int]]]]]] =
                    Right(Right(Right(Right(Right(Right(18))))))
                assert(res == expected)
            }
        }
        "fail" in {
            val ex: Throwable = new Exception("throwable failure")
            val a             = kyo2.Abort.fail(ex)
            assert(kyo2.Abort.run[Throwable](a).eval == Left(ex))
        }
        "fail inferred" in {
            val e = "test"
            val f = kyo2.Abort.fail(e)
            assert(kyo2.Abort.run(f).eval == Left(e))
        }
        "when" in {
            def test(b: Boolean) = kyo2.Abort.run[String](kyo2.Abort.when(b)("FAIL!")).eval

            assert(test(true) == Left("FAIL!"))
            assert(test(false) == Right(()))
        }
        "catching" - {
            "only effect" - {
                def test(v: Int): Int =
                    v match
                        case 0 => throw ex1
                        case i => 10 / i
                "success" in {
                    assert(
                        kyo2.Abort.run[Ex1](kyo2.Abort.catching[Ex1](test(2))).eval ==
                            Right(5)
                    )
                }
                "failure" in {
                    assert(
                        kyo2.Abort.run[Ex1](kyo2.Abort.catching[Ex1](test(0))).eval ==
                            Left(ex1)
                    )
                }
                "subclass" in {
                    assert(
                        kyo2.Abort.run[RuntimeException](kyo2.Abort.catching[RuntimeException](test(0))).eval ==
                            Left(ex1)
                    )
                }
                "distinct" in {
                    class Distinct1 extends Throwable derives CanEqual
                    val d1 = new Distinct1
                    class Distinct2 extends Throwable derives CanEqual
                    val d2 = new Distinct2

                    val distinct: Boolean < Abort[Distinct1 | Distinct2] =
                        for
                            _ <- kyo2.Abort.catching[Distinct1](throw d1)
                            _ <- kyo2.Abort.catching[Distinct2](throw d2)
                        yield true

                    val a = kyo2.Abort.run[Distinct1](distinct)
                    val b = kyo2.Abort.run[Distinct2](a).eval
                    assert(b == Right(Left(d1)))

                    val c = kyo2.Abort.run(distinct).eval
                    assert(c == Left(d1))
                }
                "ClassTag inference" in pendingUntilFixed {
                    assertCompiles("""
                        val r = Abort.run(Abort.catching(throw new RuntimeException)).eval
                        assert(r.isLeft)
                    """)
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
                        kyo2.Env.run(2)(
                            kyo2.Abort.run[Ex1](kyo2.Abort.catching[Ex1](test(kyo2.Env.get)))
                        ).eval ==
                            Right(5)
                    )
                }
                "failure" in {
                    assert(
                        kyo2.Env.run(0)(
                            kyo2.Abort.run[Ex1](kyo2.Abort.catching[Ex1](test(kyo2.Env.get)))
                        ).eval ==
                            Left(ex1)
                    )
                }
            }

            "nested Abort effects" - {
                "should propagate the innermost failure" in {
                    val nested = kyo2.Abort.run[String](
                        kyo2.Abort.run[Int](
                            kyo2.Abort.fail[String]("inner").map(_ => kyo2.Abort.fail[Int](42))
                        )
                    )
                    assert(nested.eval == Left("inner"))
                }

                "should propagate the outermost failure if there are no inner failures" in {
                    val nested = kyo2.Abort.run(kyo2.Abort.run[String](
                        kyo2.Abort.run[Int](kyo2.Abort.get[Int](Right(42)))
                    ).map(_ => kyo2.Abort.fail("outer")))
                    assert(nested.eval == Left("outer"))
                }
            }

            "interactions with Env" - {
                "should have access to the environment within Abort" in {
                    val env    = "test"
                    val result = kyo2.Env.run(env)(kyo2.Abort.run[String](kyo2.Env.get[String]))
                    assert(result.eval == Right(env))
                }

                "should propagate Abort failures within Env" in {
                    val result = kyo2.Env.run("test")(kyo2.Abort.run[String](kyo2.Abort.fail("failure")))
                    assert(result.eval == Left("failure"))
                }
            }

            "interactions with Var" - {
                "should have access to the state within Abort" in {
                    val result = kyo2.Var.run(42)(
                        kyo2.Abort.run[String](
                            kyo2.Var.get[Int].map(_.toString)
                        )
                    )
                    assert(result.eval == Right("42"))
                }

                "should not modify state on Abort failures" in {
                    val result = kyo2.Var.run(42)(
                        kyo2.Abort.run[String](
                            kyo2.Var.set[Int](24).map(_ => kyo2.Abort.fail("failure"))
                        )
                    )
                    assert(result.eval == Left("failure"))
                    assert(kyo2.Var.run(42)(kyo2.Var.get[Int]).eval == 42)
                }
            }

            "short-circuiting with map" - {
                "should not execute subsequent operations on failure" in {
                    var executed = false
                    val result = kyo2.Abort.run[String](
                        kyo2.Abort.fail("failure").map(_ => executed = true)
                    )
                    assert(result.eval == Left("failure"))
                    assert(!executed)
                }

                "should execute subsequent operations on success" in {
                    var executed = false
                    val result = kyo2.Abort.run(kyo2.Abort.run[String](
                        kyo2.Abort.get[Int](Right(42)).map(_ => executed = true)
                    ))
                    assert(result.eval == Right(Right(())))
                    assert(executed)
                }
            }
        }
    }
end AbortsTest
