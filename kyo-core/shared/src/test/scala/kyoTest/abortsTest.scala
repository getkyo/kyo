package kyoTest

import kyo.*

class abortsTest extends KyoTest:

    class Ex1 extends RuntimeException
    class Ex2

    val ex1 = new Ex1
    val ex2 = new Ex2

    "pure" - {
        "handle" in {
            assert(
                Aborts[Ex1].run(Aborts[Ex1].get(Right(1))) ==
                    Right(1)
            )
        }
        "handle + transform" in {
            assert(
                Aborts[Ex1].run(Aborts[Ex1].get(Right(1)).map(_ + 1)) ==
                    Right(2)
            )
        }
        "handle + effectful transform" in {
            assert(
                Aborts[Ex1].run(Aborts[Ex1].get(Right(1)).map(i =>
                    Aborts[Ex1].get(Right(i + 1))
                )) ==
                    Right(2)
            )
        }
        "handle + transform + effectful transform" in {
            assert(
                Aborts[Ex1].run(Aborts[Ex1].get(Right(1)).map(_ + 1).map(i =>
                    Aborts[Ex1].get(Right(i + 1))
                )) ==
                    Right(3)
            )
        }
        "handle + transform + failed effectful transform" in {
            val fail = Left[Ex1, Int](ex1)
            assert(
                Aborts[Ex1].run(Aborts[Ex1].get(Right(1)).map(_ + 1).map(_ =>
                    Aborts[Ex1].get(fail)
                )) ==
                    fail
            )
        }
    }

    "effectful" - {
        "success" - {
            val v = Aborts[Ex1].get(Right(1))
            "handle" in {
                assert(
                    Aborts[Ex1].run(v) ==
                        Right(1)
                )
            }
            "handle + transform" in {
                assert(
                    Aborts[Ex1].run(v.map(_ + 1)) ==
                        Right(2)
                )
            }
            "handle + effectful transform" in {
                assert(
                    Aborts[Ex1].run(v.map(i => Aborts[Ex1].get(Right(i + 1)))) ==
                        Right(2)
                )
            }
            "handle + transform + effectful transform" in {
                assert(
                    Aborts[Ex1].run(v.map(_ + 1).map(i => Aborts[Ex1].get(Right(i + 1)))) ==
                        Right(3)
                )
            }
            "handle + transform + failed effectful transform" in {
                val fail = Left[Ex1, Int](ex1)
                assert(
                    Aborts[Ex1].run(v.map(_ + 1).map(_ => Aborts[Ex1].get(fail))) ==
                        fail
                )
            }
        }
        "failure" - {
            val v: Int < Aborts[Ex1] = Aborts[Ex1].get(Left(ex1))
            "handle" in {
                assert(
                    Aborts[Ex1].run(v) ==
                        Left(ex1)
                )
            }
            "handle + transform" in {
                assert(
                    Aborts[Ex1].run(v.map(_ + 1)) ==
                        Left(ex1)
                )
            }
            "handle + effectful transform" in {
                assert(
                    Aborts[Ex1].run(v.map(i => Aborts[Ex1].get(Right(i + 1)))) ==
                        Left(ex1)
                )
            }
            "handle + transform + effectful transform" in {
                assert(
                    Aborts[Ex1].run(v.map(_ + 1).map(i => Aborts[Ex1].get(Right(i + 1)))) ==
                        Left(ex1)
                )
            }
            "handle + transform + failed effectful transform" in {
                val fail = Left[Ex1, Int](ex1)
                assert(
                    Aborts[Ex1].run(v.map(_ + 1).map(_ => Aborts[Ex1].get(fail))) ==
                        fail
                )
            }
        }
    }

    "Aborts" - {
        def test(v: Int): Int < Aborts[Ex1] =
            v match
                case 0 => Aborts[Ex1].fail(ex1)
                case i => 10 / i
        "run" - {
            "success" in {
                assert(
                    Aborts[Ex1].run(test(2)) ==
                        Right(5)
                )
            }
            "failure" in {
                assert(
                    Aborts[Ex1].run(test(0)) ==
                        Left(ex1)
                )
            }
            "inference" in {
                def t1(v: Int < Aborts[Int | String]) =
                    Aborts[Int].run(v)
                val _: Either[Int, Int] < Aborts[String] =
                    t1(42)
                def t2(v: Int < (Aborts[Int] & Aborts[String])) =
                    Aborts[String].run(v)
                val _: Either[String, Int] < Aborts[Int] =
                    t2(42)
                def t3(v: Int < Aborts[Int]) =
                    Aborts[Int].run(v)
                val _: Either[Int, Int] < Any =
                    t3(42)
                succeed
            }
        }
        "fail" in {
            val ex = new Exception("throwable failure")
            val a  = Aborts[Throwable].fail(ex)
            assert(Aborts[Throwable].run(a) == Left(ex))
        }
        "catching" - {
            "only effect" - {
                def test(v: Int): Int =
                    v match
                        case 0 => throw ex1
                        case i => 10 / i
                "success" in {
                    assert(
                        Aborts[Ex1].run(Aborts[Ex1].catching(test(2))) ==
                            Right(5)
                    )
                }
                "failure" in {
                    assert(
                        Aborts[Ex1].run(Aborts[Ex1].catching(test(0))) ==
                            Left(ex1)
                    )
                }
                "subclass" in {
                    assert(
                        Aborts[RuntimeException].run(Aborts[RuntimeException].catching(test(0))) ==
                            Left(ex1)
                    )
                }
            }
            "with other effect" - {
                def test(v: Int < Envs[Int]): Int < Envs[Int] =
                    v.map {
                        case 0 => throw ex1
                        case i => 10 / i
                    }
                "success" in {
                    assert(
                        Envs[Int].run(2)(
                            Aborts[Ex1].run(Aborts[Ex1].catching(test(Envs[Int].get)))
                        ) ==
                            Right(5)
                    )
                }
                "failure" in {
                    assert(
                        Envs[Int].run(0)(
                            Aborts[Ex1].run(Aborts[Ex1].catching(test(Envs[Int].get)))
                        ) ==
                            Left(ex1)
                    )
                }
            }
        }
    }
end abortsTest
