package kyoTest

import kyo.*

class envsTest extends KyoTest:

    "value" in {
        val v1 =
            Envs.get[Int].map(_ + 1)
        val v2: Int < Envs[Int] = v1
        assert(
            Envs.run(1)(v2).pure ==
                2
        )
    }

    "use" in {
        val v1 =
            Envs.use[Int](_ + 1)
        val v2: Int < Envs[Int] = v1
        assert(
            Envs.run(1)(v2).pure ==
                2
        )
    }

    "passing subclass" in {
        trait Super:
            def i = 42
        case class Sub() extends Super
        assert(Envs.run[Super](Sub())(Envs.use[Super](_.i)).pure == 42)
    }

    "inference" in {
        def t1(v: Int < Envs[Int & String]) =
            Envs.run(1)(v)
        val _: Int < Envs[String] =
            t1(42)
        def t2(v: Int < (Envs[Int] & Envs[String])) =
            Envs.run("s")(v)
        val _: Int < Envs[Int] =
            t2(42)
        def t3(v: Int < Envs[String]) =
            Envs.run("a")(v)
        val _: Int < Any =
            t3(42)
        succeed
    }

    "intersection type env" - {
        "explicit" in pendingUntilFixed {
            val a = Envs.run(1)(Envs.get[Int & Double])
            val b = Envs.run(1.2d)(a)
            assert(b.pure == 1)
            ()
        }
        "method inference" in pendingUntilFixed {
            def test[T](v: T < (Envs[Int] & Envs[Double])) =
                v
            val a                                = test(Envs.get)
            val b: (Int & Double) < Envs[Double] = Envs.run(1)(a)
            val c: (Int & Double) < Any          = Envs.run(1.2d)(b)
            assert(c.pure == 1)
            ()
        }
    }

    "reduce large intersection incrementally" in {
        val t1: Int < Envs[Int & String & Boolean & Float & Char & Double] = 18
        val t2                                                             = Envs.run(42)(t1)
        val t3                                                             = Envs.run("a")(t2)
        val t4                                                             = Envs.run(false)(t3)
        val t5                                                             = Envs.run(0.23f)(t4)
        val t6                                                             = Envs.run('a')(t5)
        val t7                                                             = Envs.run(0.23d)(t6)
        assert(t7.pure == 18)
    }

    "reduce large intersection in single expression" in {
        val t: Int < Envs[Int & String & Boolean & Float & Char & Double] = 18
        // NB: Adding a type annotation here leads to compilation error!
        val res =
            Envs.run(0.23d)(
                Envs.run('a')(
                    Envs.run(0.23f)(
                        Envs.run(false)(
                            Envs.run("a")(
                                Envs.run(42)(t)
                            )
                        )
                    )
                )
            )
        assert(res.pure == 18)
    }

    "invalid inference" in {
        assertDoesNotCompile("""
            def t1(v: Int < Envs[Int & String]) =
                Envs[Int].run[Int, Any, Nothing](1)(v)
            val _: Int < Any = t1(42)
        """)
    }

    "no transformations" in {
        assert(Envs.run(1)(Envs.get[Int]).pure == 1)
    }

    "pure services" - {

        trait Service1:
            def apply(i: Int): Int
        trait Service2:
            def apply(i: Int): Int

        val service1 = new Service1:
            def apply(i: Int) = i + 1
        val service2 = new Service2:
            def apply(i: Int) = i + 2

        "one service" in {
            val a =
                Envs.get[Service1].map(_(1))
            assert(
                Envs.run(service1)(a).pure ==
                    2
            )
        }
        "two services" - {
            val a =
                Envs.get[Service1].map(_(1)).map { i =>
                    Envs.get[Service2].map(_(i))
                }
            val v: Int < (Envs[Service1] & Envs[Service2]) = a
            "same handling order" in {
                assert(
                    Envs.run(service1)(Envs.run(service2)(v)).pure ==
                        4
                )
            }
            "reverse handling order" in {
                assert(
                    Envs.run(service2)(Envs.run(service1)(v)).pure ==
                        4
                )
            }
            "dependent services" in {
                val v1 =
                    Envs.run(service1)(v)
                assert(
                    Envs.run(service2)(v1).pure ==
                        4
                )
            }
        }
    }

    "effectful services" - {

        trait Service1:
            def apply(i: Int): Int < Options
        trait Service2:
            def apply(i: Int): Int < Options

        val service1 = new Service1:
            def apply(i: Int) = i match
                case 0 => Options.get(Option.empty[Int])
                case i => i + 1
        val service2 = new Service2:
            def apply(i: Int) = i match
                case 0 => Options.get(Some(1))
                case i => i + 1

        "one service" - {
            "continue" in {
                val a =
                    Envs.get[Service1].map(_(1))
                assert(
                    Options.run(Envs.run(service1)(a)).pure ==
                        Some(2)
                )
            }
            "short circuit" in {
                val a =
                    Envs.get[Service1].map(_(0))
                assert(
                    Options.run(Envs.run(service1)(a)).pure ==
                        None
                )
            }
        }
        "two services" - {
            "continue" - {
                val a =
                    Envs.get[Service1].map(_(1)).map { i =>
                        Envs.get[Service2].map(_(i))
                    }
                val v: Int < (Envs[Service1] & Envs[Service2] & Options) = a
                "same handling order" in {
                    val b = Envs.run(service2)(v)
                    val c = Envs.run(service1)(b)
                    assert(
                        Options.run(c).pure == Option(3)
                    )
                }
                "reverse handling order" in {
                    val b = Envs.run(service1)(v)
                    val c = Envs.run(service2)(b)
                    assert(
                        Options.run(c).pure == Option(3)
                    )
                }
                "dependent services" in {
                    val v2: Int < (Envs[Service2] & Options) = Envs.run(service1)(v)
                    assert(
                        Options.run(Envs.run(service2)(v2)).pure ==
                            Some(3)
                    )
                }
            }
        }
    }
end envsTest
