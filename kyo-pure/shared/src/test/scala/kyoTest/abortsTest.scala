package kyoTest

import kyo.*

class abortsTest extends KyoPureTest:

    case class Ex1() extends RuntimeException derives CanEqual
    case class Ex2() derives CanEqual

    val ex1 = new Ex1
    val ex2 = new Ex2

    "pure" - {
        "handle" in {
            assert(
                Aborts.run[Ex1](Aborts.get(Right(1))).pure ==
                    Right(1)
            )
        }
        "handle + transform" in {
            assert(
                Aborts.run[Ex1](Aborts.get(Right(1)).map(_ + 1)).pure ==
                    Right(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                Aborts.run[Ex1](Aborts.get(Right(1)).map(i =>
                    Aborts.get(Right(i + 1))
                )).pure ==
                    Right(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                Aborts.run[Ex1](Aborts.get(Right(1)).map(_ + 1).map(i =>
                    Aborts.get(Right(i + 1))
                )).pure ==
                    Right(3)
            )
        }
        "handle + transform + failed effectful transform" in {
            val fail = Left[Ex1, Int](ex1)
            assert(
                Aborts.run[Ex1](Aborts.get(Right(1)).map(_ + 1).map(_ =>
                    Aborts.get(fail)
                )).pure ==
                    fail
            )
        }
        "union tags" - {
            "in suspend 1" in {
                val effect1: Int < Aborts[String | Boolean] =
                    Aborts.fail("failure")
                val handled1: Either[String, Int] < Aborts[Boolean] =
                    Aborts.run[String](effect1)
                val handled2: Either[Boolean, Either[String, Int]] =
                    Aborts.run[Boolean](handled1).pure
                assert(handled2 == Right(Left("failure")))
            }
            "in suspend 2" in {
                val effect1: Int < Aborts[String | Boolean] =
                    Aborts.fail("failure")
                val handled1: Either[Boolean, Int] < Aborts[String] =
                    Aborts.run[Boolean](effect1)
                val handled2: Either[String, Either[Boolean, Int]] =
                    Aborts.run[String](handled1).pure
                assert(handled2 == Left("failure"))
            }
            "in handle" in {
                val effect: Int < Aborts[String | Boolean] =
                    Aborts.fail("failure")
                val handled: Either[String | Boolean, Int] =
                    Aborts.run[String | Boolean](effect).pure
                assert(handled == Left("failure"))
            }
        }
    }

    "effectful" - {
        "success" - {
            val v = Aborts.get[Ex1, Int](Right(1))
            "handle" in {
                assert(
                    Aborts.run[Ex1](v).pure ==
                        Right(1)
                )
            }
            "handle + transform" in {
                assert(
                    Aborts.run[Ex1](v.map(_ + 1)).pure ==
                        Right(2)
                )
            }
            "handle + effectful transform" in {
                assert(
                    Aborts.run[Ex1](v.map(i => Aborts.get(Right(i + 1)))).pure ==
                        Right(2)
                )
            }
            "handle + transform + effectful transform" in {
                assert(
                    Aborts.run[Ex1](v.map(_ + 1).map(i => Aborts.get(Right(i + 1)))).pure ==
                        Right(3)
                )
            }
            "handle + transform + failed effectful transform" in {
                val fail = Left[Ex1, Int](ex1)
                assert(
                    Aborts.run[Ex1](v.map(_ + 1).map(_ => Aborts.get(fail))).pure ==
                        fail
                )
            }
        }
        "failure" - {
            val v: Int < Aborts[Ex1] = Aborts.get(Left(ex1))
            "handle" in {
                assert(
                    Aborts.run[Ex1](v).pure ==
                        Left(ex1)
                )
            }
            "handle + transform" in {
                assert(
                    Aborts.run[Ex1](v.map(_ + 1)).pure ==
                        Left(ex1)
                )
            }
            "handle + effectful transform" in {
                assert(
                    Aborts.run[Ex1](v.map(i => Aborts.get(Right(i + 1)))).pure ==
                        Left(ex1)
                )
            }
            "handle + transform + effectful transform" in {
                assert(
                    Aborts.run[Ex1](v.map(_ + 1).map(i => Aborts.get(Right(i + 1)))).pure ==
                        Left(ex1)
                )
            }
            "handle + transform + failed effectful transform" in {
                val fail = Left[Ex1, Int](ex1)
                assert(
                    Aborts.run[Ex1](v.map(_ + 1).map(_ => Aborts.get(fail))).pure ==
                        Left(ex1)
                )
            }
        }
    }

    "Aborts" - {
        def test(v: Int): Int < Aborts[Ex1] =
            v match
                case 0 => Aborts.fail(ex1)
                case i => 10 / i
        "run" - {
            "success" in {
                assert(
                    Aborts.run[Ex1](test(2)).pure ==
                        Right(5)
                )
            }
            "failure" in {
                assert(
                    Aborts.run[Ex1](test(0)).pure ==
                        Left(ex1)
                )
            }
            "inference" in {
                def t1(v: Int < Aborts[Int | String]) =
                    Aborts.run[Int](v)
                val _: Either[Int, Int] < Aborts[String] =
                    t1(42)
                def t2(v: Int < (Aborts[Int] & Aborts[String])) =
                    Aborts.run[String](v)
                val _: Either[String, Int] < Aborts[Int] =
                    t2(42)
                def t3(v: Int < Aborts[Int]) =
                    Aborts.run[Int](v).pure
                val _: Either[Int, Int] =
                    t3(42)
                succeed
            }
            "super" in {
                val ex                         = new Exception
                val a: Int < Aborts[Exception] = Aborts.fail(ex)
                val b: Either[Throwable, Int]  = Aborts.run[Throwable](a).pure
                assert(b == Left(ex))
            }
            "super success" in {
                val a: Int < Aborts[Exception] = 24
                val b: Either[Throwable, Int]  = Aborts.run[Throwable](a).pure
                assert(b == Right(24))
            }
            "reduce large union incrementally" in {
                val t1: Int < Aborts[Int | String | Boolean | Float | Char | Double] =
                    18
                val t2 = Aborts.run[Int](t1)
                val t3 = Aborts.run[String](t2)
                val t4 = Aborts.run[Boolean](t3)
                val t5 = Aborts.run[Float](t4)
                val t6 = Aborts.run[Char](t5)
                val t7 = Aborts.run[Double](t6).pure
                assert(t7.pure == Right(Right(Right(Right(Right(Right(18)))))))
            }
            "reduce large union in a single expression" in {
                val t: Int < Aborts[Int | String | Boolean | Float | Char | Double] = 18
                // NB: Adding type annotation leads to compilation error
                val res =
                    Aborts.run[Double](
                        Aborts.run[Char](
                            Aborts.run[Float](
                                Aborts.run[Boolean](
                                    Aborts.run[String](
                                        Aborts.run[Int](t)
                                    )
                                )
                            )
                        )
                    ).pure
                val expected: Either[Double, Either[Char, Either[Float, Either[Boolean, Either[String, Either[Int, Int]]]]]] =
                    Right(Right(Right(Right(Right(Right(18))))))
                assert(res == expected)
            }
        }
        "fail" in {
            val ex: Throwable = new Exception("throwable failure")
            val a             = Aborts.fail(ex)
            assert(Aborts.run[Throwable](a).pure == Left(ex))
        }
        "fail inferred" in {
            val e = "test"
            val f = Aborts.fail(e)
            assert(Aborts.run(f).pure == Left(e))
        }
        "when" in {
            def abort(b: Boolean) = Aborts.run[String](Aborts.when(b)("FAIL!")).pure

            assert(abort(true) == Left("FAIL!"))
            assert(abort(false) == Right(()))
        }
    }
end abortsTest
