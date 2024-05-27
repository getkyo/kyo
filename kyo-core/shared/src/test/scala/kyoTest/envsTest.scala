package kyoTest

import kyo.*
import scala.util.NotGiven

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

    "intersection type env" in {
        assertDoesNotCompile("Envs.get[Int & Double]")
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

    "provide" - {
        "providing env maps" in {
            val kyo =
                for
                    string <- Envs.get[String]
                    int    <- Envs.get[Int]
                    bool   <- Envs.get[Boolean]
                yield (string, int, bool)

            val envMap = TypeMap("Hello", 123, true)
            assert(
                Envs.provide(envMap)(kyo).pure == ("Hello", 123, true)
            )
        }

        "leaving off one service" in {
            val kyo =
                for
                    string <- Envs.get[String]
                    int    <- Envs.get[Int]
                    bool   <- Envs.get[Boolean]
                yield (string, int, bool)

            val envMap: TypeMap[String & Int]                       = TypeMap("Hello", 123)
            val withTypeMap: (String, Int, Boolean) < Envs[Boolean] = Envs.provide(envMap)(kyo)
            val withBool: (String, Int, Boolean) < Any              = Envs.run(true)(withTypeMap)
            assert(
                withBool.pure == ("Hello", 123, true)
            )
        }

        "multiple provide calls" in {
            val kyo =
                for
                    string <- Envs.get[String]
                    int    <- Envs.get[Int]
                    bool   <- Envs.get[Boolean]
                yield (string, int, bool)

            val stringTypeMap = TypeMap("Hello")
            val intTypeMap    = TypeMap(123)
            val boolTypeMap   = TypeMap(true)
            assert(
                Envs.run(true)(
                    Envs.provide(stringTypeMap)(Envs.provide(intTypeMap)(Envs.provide(boolTypeMap)(kyo)))
                ).pure == ("Hello", 123, true)
            )
        }

        "providing the wrong env map" in {
            assertDoesNotCompile("""
                val kyo: String < Envs[String] = Envs.get[String]
                val envMap: TypeMap[Int]        = TypeMap(12)
                Envs.provide(envMap)(kyo).pure
            """)
        }

        "providing an empty env map" in {
            assertDoesNotCompile("""
                val kyo = Envs.get[String]
                val envMap = TypeMap.empty
                Envs.provide(envMap)(kyo).pure
            """)
        }

        "providing only a subset of the required services" in {
            assertDoesNotCompile("""
                val kyo =
                    for
                        string <- Envs.get[String]
                        int    <- Envs.get[Int]
                    yield (string, int)
                val envMap = TypeMap("Hello")
                Envs.provide(envMap)(kyo).pure
            """)
        }

        "providing a superset of the required services" in {
            val kyo =
                for
                    string <- Envs.get[String]
                    int    <- Envs.get[Int]
                yield (string, int)

            val envMap = TypeMap("Hello", 123, true)
            assert(
                Envs.provide(envMap)(kyo).pure == ("Hello", 123)
            )
        }

    }
end envsTest
