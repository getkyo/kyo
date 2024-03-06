package kyoTest

import kyo.*

class varsTest extends KyoTest:

    // workaround for inference issues
    override def convertToEqualizer[T](left: T): Equalizer[T] = super.convertToEqualizer(left)

    "get" in run {
        Vars.run {
            Vars.let(1)(Vars.get[Int].map(_ + 1)).map { r =>
                assert(r == 2)
            }
        }
    }

    "set, get" in run {
        Vars.run {
            Vars.let(1)(Vars.set(2).andThen(Vars.get[Int])).map { r =>
                assert(r == 2)
            }
        }
    }

    "get, set, get" in run {
        Vars.run {
            Vars.let(1)(Vars.get[Int].map(i => Vars.set(i + 1)).andThen(Vars.get[Int])).map { r =>
                assert(r == 2)
            }
        }
    }

    "update" in run {
        Vars.run {
            Vars.let(1)(Vars.update[Int](_ + 1).andThen(Vars.get[Int])).map { r =>
                assert(r == 2)
            }
        }
    }

    "nested let" in run {
        Vars.run {
            Vars.let(1) {
                Vars.let(2) {
                    Vars.get[Int].map { innerValue =>
                        assert(innerValue == 2)
                    }
                }.unit.andThen(Vars.get[Int])
                    .map { outerValue =>
                        assert(outerValue == 1)
                    }
            }
        }
    }

    "string value" in run {
        Vars.run {
            Vars.let("a") {
                for
                    _      <- Vars.set("b")
                    result <- Vars.get[String]
                yield assert(result == "b")
            }
        }
    }

    "side effect" in run {
        var sideEffectCounter = 0
        Vars.run {
            Vars.let(1) {
                for
                    _ <- Vars.update[Int] { value =>
                        sideEffectCounter += 1
                        value + 1
                    }
                    result <- Vars.get[Int]
                yield assert(result == 2 && sideEffectCounter == 1)
            }
        }
    }

    "with ios" in run {
        Vars.run {
            IOs {
                Vars.let(1)(
                    IOs(Vars.get[Int]).map(i =>
                        IOs(Vars.update[Int](_ + i + 1)).andThen(Vars.get[Int])
                    )
                ).map { r =>
                    assert(r == 3)
                }
            }
        }
    }

    "inference" in {
        val a: Int < Vars[Int]           = Vars.get[Int]
        val b: Unit < Vars[Int | String] = a.map(i => Vars.set(i.toString()))
        val c: Unit < Vars[String]       = Vars.let(1)(b)
        val d: Unit < Vars[Nothing]      = Vars.let("t")(c)
        val e: Unit < Any                = Vars.run(d)
        assert(e.pure == ())
    }
end varsTest
