package kyoTest

import kyo.*

class envsTest extends KyoTest:

    "value" in {
        val v1 =
            Envs[Int].get.map(_ + 1)
        val v2: Int < Envs[Int] = v1
        assert(
            Envs[Int].run(1)(v2) ==
                2
        )
    }

    "use" in {
        val v1 =
            Envs[Int].use(_ + 1)
        val v2: Int < Envs[Int] = v1
        assert(
            Envs[Int].run(1)(v2) ==
                2
        )
    }

    "inference" in {
        def t1(v: Int < Envs[Int & String]) =
            Envs[Int].run(1)(v)
        val _: Int < Envs[String] =
            t1(42)
        def t2(v: Int < (Envs[Int] & Envs[String])) =
            Envs[String].run("s")(v)
        val _: Int < Envs[Int] =
            t2(42)
        def t3(v: Int < Envs[String]) =
            Envs[String].run("a")(v)
        val _: Int < Any =
            t3(42)
        succeed
    }

    "invalid inference" in pendingUntilFixed {
        assertDoesNotCompile("""
            def t1(v: Int < Envs[Int & String]) =
                Envs[Int].run[Int, Any, Nothing](1)(v)
            val _: Int < Any = t1(42)
        """)
    }

    "no transformations" in {
        assert(Envs[Int].run(1)(Envs[Int].get) == 1)
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
                Envs[Service1].get.map(_(1))
            assert(
                Envs[Service1].run(service1)(a) ==
                    2
            )
        }
        "two services" - {
            val a =
                Envs[Service1].get.map(_(1)).map { i =>
                    Envs[Service2].get.map(_(i))
                }
            val v: Int < (Envs[Service1] & Envs[Service2]) = a
            "same handling order" in {
                assert(
                    Envs[Service1].run(service1)(Envs[Service2].run(service2)(v)) ==
                        4
                )
            }
            "reverse handling order" in {
                assert(
                    Envs[Service2].run(service2)(Envs[Service1].run(service1)(v)) ==
                        4
                )
            }
            "dependent services" in {
                val v1 =
                    Envs[Service1].run(service1)(v)
                assert(
                    Envs[Service2].run(service2)(v1) ==
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
                    Envs[Service1].get.map(_(1))
                assert(
                    Options.run(Envs[Service1].run(service1)(a)) ==
                        Some(2)
                )
            }
            "short circuit" in {
                val a =
                    Envs[Service1].get.map(_(0))
                assert(
                    Options.run(Envs[Service1].run(service1)(a)) ==
                        None
                )
            }
        }
        "two services" - {
            "continue" - {
                val a =
                    Envs[Service1].get.map(_(1)).map { i =>
                        Envs[Service2].get.map(_(i))
                    }
                val v: Int < (Envs[Service1] & Envs[Service2] & Options) = a
                "same handling order" in {
                    val b = Envs[Service2].run(service2)(v)
                    val c = Envs[Service1].run(service1)(b)
                    assert(
                        Options.run(c) == Option(3)
                    )
                }
                "reverse handling order" in {
                    val b = Envs[Service1].run(service1)(v)
                    val c = Envs[Service2].run(service2)(b)
                    assert(
                        Options.run(c) == Option(3)
                    )
                }
                "dependent services" in {
                    val v2: Int < (Envs[Service2] & Options) = Envs[Service1].run(service1)(v)
                    assert(
                        Options.run(Envs[Service2].run(service2)(v2)) ==
                            Some(3)
                    )
                }
            }
        }
    }
end envsTest
