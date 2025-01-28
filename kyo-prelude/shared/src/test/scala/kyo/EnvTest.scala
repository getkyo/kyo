package kyo

class EnvTest extends Test:

    "value" in {
        val v1 =
            Env.get[Int].map(_ + 1)
        val v2: Int < Env[Int] = v1
        assert(
            Env.run(1)(v2).eval ==
                2
        )
    }

    "use" in {
        val v1 =
            Env.use[Int](_ + 1)
        val v2: Int < Env[Int] = v1
        assert(
            Env.run(1)(v2).eval ==
                2
        )
    }

    "passing subclass" in {
        trait Super:
            def i = 42
        case class Sub() extends Super
        assert(Env.run(Sub())(Env.use[Super](_.i)).eval == 42)
    }

    "inference" in {
        def t1(v: Int < Env[Int & String]) =
            Env.run(1)(v)
        val _: Int < Env[String] =
            t1(42)
        def t2(v: Int < (Env[Int] & Env[String])) =
            Env.run("s")(v)
        val _: Int < Env[Int] =
            t2(42)
        def t3(v: Int < Env[String]) =
            Env.run("a")(v)
        val _: Int < Any =
            t3(42)
        succeed
    }

    "intersection type env" in {
        typeCheckFailure("Env.get[Int & Double]")(
            "Method doesn't accept intersection types"
        )
    }

    "reduce large intersection incrementally" in {
        val t1: Int < Env[Int & String & Boolean & Float & Char & Double] = 18
        val t2                                                            = Env.run(42)(t1)
        val t3                                                            = Env.run("a")(t2)
        val t4                                                            = Env.run(false)(t3)
        val t5                                                            = Env.run(0.23f)(t4)
        val t6                                                            = Env.run('a')(t5)
        val t7                                                            = Env.run(0.23d)(t6)
        assert(t7.eval == 18)
    }

    "reduce large intersection in single expression" in {
        val t: Int < Env[Int & String & Boolean & Float & Char & Double] = 18
        // NB: Adding a type annotation here leads to compilation error!
        val res =
            Env.run(0.23d)(
                Env.run('a')(
                    Env.run(0.23f)(
                        Env.run(false)(
                            Env.run("a")(
                                Env.run(42)(t)
                            )
                        )
                    )
                )
            )
        assert(res.eval == 18)
    }

    "invalid inference" in {
        typeCheckFailure("""
        def t1(v: Int < Env[Int & String]) =
            Env.run(1)(v)
        val _: Int < Any = t1(42)
        """)(
            "Required: Int < Any"
        )
    }

    "no transformations" in {
        assert(Env.run(1)(Env.get[Int]).eval == 1)
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
                Env.get[Service1].map(_(1))
            assert(
                Env.run(service1)(a).eval ==
                    2
            )
        }
        "two services" - {
            val a =
                Env.get[Service1].map(_(1)).map { i =>
                    Env.get[Service2].map(_(i))
                }
            val v: Int < (Env[Service1] & Env[Service2]) = a
            "same handling order" in {
                assert(
                    Env.run(service1)(Env.run(service2)(v)).eval ==
                        4
                )
            }
            "reverse handling order" in {
                assert(
                    Env.run(service2)(Env.run(service1)(v)).eval ==
                        4
                )
            }
            "dependent services" in {
                val v1 =
                    Env.run(service1)(v)
                assert(
                    Env.run(service2)(v1).eval ==
                        4
                )
            }
        }
    }

    "effectful services" - {

        trait Service1:
            def apply(i: Int): Int < Abort[Absent]
        trait Service2:
            def apply(i: Int): Int < Abort[Absent]

        val service1 = new Service1:
            def apply(i: Int) = i match
                case 0 => Abort.get(Option.empty[Int])
                case i => i + 1
        val service2 = new Service2:
            def apply(i: Int) = i match
                case 0 => Abort.get(Some(1))
                case i => i + 1

        "one service" - {
            "continue" in {
                val a =
                    Env.get[Service1].map(_(1))
                assert(
                    Abort.run(Env.run(service1)(a)).eval ==
                        Result.succeed(2)
                )
            }
            "short circuit" in {
                val a =
                    Env.get[Service1].map(_(0))
                assert(
                    Abort.run(Env.run(service1)(a)).eval ==
                        Result.fail(Maybe.empty)
                )
            }
        }
        "two services" - {
            "continue" - {
                val a =
                    Env.get[Service1].map(_(1)).map { i =>
                        Env.get[Service2].map(_(i))
                    }
                val v: Int < (Env[Service1] & Env[Service2] & Abort[Absent]) = a
                "same handling order" in {
                    val b = Env.run(service2)(v)
                    val c = Env.run(service1)(b)
                    assert(
                        Abort.run(c).eval == Result.succeed(3)
                    )
                }
                "reverse handling order" in {
                    val b = Env.run(service1)(v)
                    val c = Env.run(service2)(b)
                    assert(
                        Abort.run(c).eval == Result.succeed(3)
                    )
                }
                "dependent services" in {
                    val v2: Int < (Env[Service2] & Abort[Absent]) = Env.run(service1)(v)
                    assert(
                        Abort.run(Env.run(service2)(v2)).eval ==
                            Result.succeed(3)
                    )
                }
            }
        }
    }

    "runTypeMap" - {
        "providing env maps" in {
            val kyo =
                for
                    string <- Env.get[String]
                    int    <- Env.get[Int]
                    bool   <- Env.get[Boolean]
                yield (string, int, bool)

            val envMap = TypeMap("Hello", 123, true)
            assert(
                Env.runTypeMap(envMap)(kyo).eval == ("Hello", 123, true)
            )
        }

        "leaving off one service" in {
            val kyo =
                for
                    string <- Env.get[String]
                    int    <- Env.get[Int]
                    bool   <- Env.get[Boolean]
                yield (string, int, bool)

            val envMap: TypeMap[String & Int]                      = TypeMap("Hello", 123)
            val withTypeMap: (String, Int, Boolean) < Env[Boolean] = Env.runTypeMap(envMap)(kyo)
            val withBool: (String, Int, Boolean) < Any             = Env.run(true)(withTypeMap)
            assert(
                withBool.eval == ("Hello", 123, true)
            )
        }

        "multiple provide calls" in {
            val kyo =
                for
                    string <- Env.get[String]
                    int    <- Env.get[Int]
                    bool   <- Env.get[Boolean]
                yield (string, int, bool)

            val stringTypeMap = TypeMap("Hello")
            val intTypeMap    = TypeMap(123)
            val boolTypeMap   = TypeMap(true)
            assert(
                Env.run(true)(
                    Env.runTypeMap(stringTypeMap)(Env.runTypeMap(intTypeMap)(Env.runTypeMap(boolTypeMap)(kyo)))
                ).eval == ("Hello", 123, true)
            )
        }

        "providing the wrong env map" in {
            val kyo: String < Env[String]    = Env.get[String]
            val envMap: TypeMap[Int]         = TypeMap(12)
            val result: String < Env[String] = Env.runTypeMap(envMap)(kyo)
            assert(Env.run("a")(result).eval == "a")
        }

        "providing an empty env map" in {
            val kyo    = Env.get[String]
            val envMap = TypeMap.empty
            val result = Env.runTypeMap(envMap)(kyo)
            assert(Env.run("a")(result).eval == "a")
        }

        "providing only a subset of the required services" in {
            val kyo =
                for
                    string <- Env.get[String]
                    int    <- Env.get[Int]
                yield (string, int)
            val envMap = TypeMap("Hello")
            val result: (String, Int) < Env[Int] =
                Env.runTypeMap(envMap)(kyo)
            assert(Env.run(42)(result).eval == ("Hello", 42))
        }

        "providing a superset of the required services" in {
            val kyo =
                for
                    string <- Env.get[String]
                    int    <- Env.get[Int]
                yield (string, int)

            val envMap = TypeMap("Hello", 123, true)
            assert(
                Env.runTypeMap(envMap)(kyo).eval == ("Hello", 123)
            )
        }

    }

    "interactions with Abort" - {
        "should propagate Abort failures within Env" in {
            val result = Env.run("test")(Abort.run[String](Abort.fail("failure")))
            assert(result.eval == Result.fail("failure"))
        }

        "should have access to the environment within Abort" in {
            val env    = "test"
            val result = Env.run(env)(Abort.run[String](Env.get[String]))
            assert(result.eval == Result.succeed(env))
        }
    }

end EnvTest
