package kyo2

import kernel.Runtime
import kyo2.kernel.Runtime

class EnvTest extends Test:

    "value" in {
        val v1 =
            kyo2.Env.get[Int].map(_ + 1)
        val v2: Int < Env[Int] = v1
        assert(
            kyo2.Env.run(1)(v2).eval ==
                2
        )
    }

    "use" in {
        val v1 =
            kyo2.Env.use[Int](_ + 1)
        val v2: Int < Env[Int] = v1
        assert(
            kyo2.Env.run(1)(v2).eval ==
                2
        )
    }

    "passing subclass" in {
        trait Super:
            def i = 42
        case class Sub() extends Super
        assert(kyo2.Env.run(Sub())(kyo2.Env.use[Super](_.i)).eval == 42)
    }

    "inference" in {
        def t1(v: Int < Env[Int & String]) =
            kyo2.Env.run(1)(v)
        val _: Int < Env[String] =
            t1(42)
        def t2(v: Int < (Env[Int] & Env[String])) =
            kyo2.Env.run("s")(v)
        val _: Int < Env[Int] =
            t2(42)
        def t3(v: Int < Env[String]) =
            kyo2.Env.run("a")(v)
        val _: Int < Any =
            t3(42)
        succeed
    }

    "intersection type env" in {
        assertDoesNotCompile("Env.get[Int & Double]")
    }

    "reduce large intersection incrementally" in {
        val t1: Int < Env[Int & String & Boolean & Float & Char & Double] = 18
        val t2                                                            = kyo2.Env.run(42)(t1)
        val t3                                                            = kyo2.Env.run("a")(t2)
        val t4                                                            = kyo2.Env.run(false)(t3)
        val t5                                                            = kyo2.Env.run(0.23f)(t4)
        val t6                                                            = kyo2.Env.run('a')(t5)
        val t7                                                            = kyo2.Env.run(0.23d)(t6)
        assert(t7.eval == 18)
    }

    "reduce large intersection in single expression" in {
        val t: Int < Env[Int & String & Boolean & Float & Char & Double] = 18
        // NB: Adding a type annotation here leads to compilation error!
        val res =
            kyo2.Env.run(0.23d)(
                kyo2.Env.run('a')(
                    kyo2.Env.run(0.23f)(
                        kyo2.Env.run(false)(
                            kyo2.Env.run("a")(
                                kyo2.Env.run(42)(t)
                            )
                        )
                    )
                )
            )
        assert(res.eval == 18)
    }

    "invalid inference" in {
        assertDoesNotCompile("""
        def t1(v: Int < Env[Int & String]) =
            Env.run(1)(v)
        val _: Int < Any = t1(42)
        """)
    }

    "no transformations" in {
        assert(kyo2.Env.run(1)(kyo2.Env.get[Int]).eval == 1)
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
                kyo2.Env.get[Service1].map(_(1))
            assert(
                kyo2.Env.run(service1)(a).eval ==
                    2
            )
        }
        "two services" - {
            val a =
                kyo2.Env.get[Service1].map(_(1)).map { i =>
                    kyo2.Env.get[Service2].map(_(i))
                }
            val v: Int < (Env[Service1] & Env[Service2]) = a
            "same handling order" in {
                assert(
                    kyo2.Env.run(service1)(kyo2.Env.run(service2)(v)).eval ==
                        4
                )
            }
            "reverse handling order" in {
                assert(
                    kyo2.Env.run(service2)(kyo2.Env.run(service1)(v)).eval ==
                        4
                )
            }
            "dependent services" in {
                val v1 =
                    kyo2.Env.run(service1)(v)
                assert(
                    kyo2.Env.run(service2)(v1).eval ==
                        4
                )
            }
        }
    }

    "effectful services" - {

        trait Service1:
            def apply(i: Int): Int < Abort[None.type]
        trait Service2:
            def apply(i: Int): Int < Abort[None.type]

        val service1 = new Service1:
            def apply(i: Int) = i match
                case 0 => kyo2.Abort.get(Option.empty[Int])
                case i => i + 1
        val service2 = new Service2:
            def apply(i: Int) = i match
                case 0 => kyo2.Abort.get(Some(1))
                case i => i + 1

        "one service" - {
            "continue" in {
                val a =
                    kyo2.Env.get[Service1].map(_(1))
                assert(
                    kyo2.Abort.run(kyo2.Env.run(service1)(a)).eval ==
                        Right(2)
                )
            }
            "short circuit" in {
                val a =
                    kyo2.Env.get[Service1].map(_(0))
                assert(
                    kyo2.Abort.run(kyo2.Env.run(service1)(a)).eval ==
                        Left(None)
                )
            }
        }
        "two services" - {
            "continue" - {
                val a =
                    kyo2.Env.get[Service1].map(_(1)).map { i =>
                        kyo2.Env.get[Service2].map(_(i))
                    }
                val v: Int < (Env[Service1] & Env[Service2] & Abort[None.type]) = a
                "same handling order" in {
                    val b = kyo2.Env.run(service2)(v)
                    val c = kyo2.Env.run(service1)(b)
                    assert(
                        kyo2.Abort.run(c).eval == Right(3)
                    )
                }
                "reverse handling order" in {
                    val b = kyo2.Env.run(service1)(v)
                    val c = kyo2.Env.run(service2)(b)
                    assert(
                        kyo2.Abort.run(c).eval == Right(3)
                    )
                }
                "dependent services" in {
                    val v2: Int < (Env[Service2] & Abort[None.type]) = kyo2.Env.run(service1)(v)
                    assert(
                        kyo2.Abort.run(kyo2.Env.run(service2)(v2)).eval ==
                            Right(3)
                    )
                }
            }
        }
    }

    "runTypeMap" - {
        "providing env maps" in {
            val kyo =
                for
                    string <- kyo2.Env.get[String]
                    int    <- kyo2.Env.get[Int]
                    bool   <- kyo2.Env.get[Boolean]
                yield (string, int, bool)

            val envMap = TypeMap("Hello", 123, true)
            assert(
                kyo2.Env.runTypeMap(envMap)(kyo).eval == ("Hello", 123, true)
            )
        }

        "leaving off one service" in {
            val kyo =
                for
                    string <- kyo2.Env.get[String]
                    int    <- kyo2.Env.get[Int]
                    bool   <- kyo2.Env.get[Boolean]
                yield (string, int, bool)

            val envMap: TypeMap[String & Int]                      = TypeMap("Hello", 123)
            val withTypeMap: (String, Int, Boolean) < Env[Boolean] = kyo2.Env.runTypeMap(envMap)(kyo)
            val withBool: (String, Int, Boolean) < Any             = kyo2.Env.run(true)(withTypeMap)
            assert(
                withBool.eval == ("Hello", 123, true)
            )
        }

        "multiple provide calls" in {
            val kyo =
                for
                    string <- kyo2.Env.get[String]
                    int    <- kyo2.Env.get[Int]
                    bool   <- kyo2.Env.get[Boolean]
                yield (string, int, bool)

            val stringTypeMap = TypeMap("Hello")
            val intTypeMap    = TypeMap(123)
            val boolTypeMap   = TypeMap(true)
            assert(
                kyo2.Env.run(true)(
                    kyo2.Env.runTypeMap(stringTypeMap)(kyo2.Env.runTypeMap(intTypeMap)(kyo2.Env.runTypeMap(boolTypeMap)(kyo)))
                ).eval == ("Hello", 123, true)
            )
        }

        "providing the wrong env map" in {
            val kyo: String < Env[String]    = kyo2.Env.get[String]
            val envMap: TypeMap[Int]         = TypeMap(12)
            val result: String < Env[String] = kyo2.Env.runTypeMap(envMap)(kyo)
            assert(kyo2.Env.run("a")(result).eval == "a")
        }

        "providing an empty env map" in {
            val kyo    = kyo2.Env.get[String]
            val envMap = TypeMap.empty
            val result = kyo2.Env.runTypeMap(envMap)(kyo)
            assert(kyo2.Env.run("a")(result).eval == "a")
        }

        "providing only a subset of the required services" in {
            val kyo =
                for
                    string <- kyo2.Env.get[String]
                    int    <- kyo2.Env.get[Int]
                yield (string, int)
            val envMap = TypeMap("Hello")
            val result: (String, Int) < Env[Int] =
                kyo2.Env.runTypeMap(envMap)(kyo)
            assert(kyo2.Env.run(42)(result).eval == ("Hello", 42))
        }

        "providing a superset of the required services" in {
            val kyo =
                for
                    string <- kyo2.Env.get[String]
                    int    <- kyo2.Env.get[Int]
                yield (string, int)

            val envMap = TypeMap("Hello", 123, true)
            assert(
                kyo2.Env.runTypeMap(envMap)(kyo).eval == ("Hello", 123)
            )
        }

    }

    "interactions with Abort" - {
        "should propagate Abort failures within Env" in {
            val result = kyo2.Env.run("test")(kyo2.Abort.run[String](kyo2.Abort.fail("failure")))
            assert(result.eval == Left("failure"))
        }

        "should have access to the environment within Abort" in {
            val env    = "test"
            val result = kyo2.Env.run(env)(kyo2.Abort.run[String](kyo2.Env.get[String]))
            assert(result.eval == Right(env))
        }
    }

end EnvTest
