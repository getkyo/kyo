package kyoTest

import kyo.*

class varsTest extends KyoPureTest:

    "get" in {
        val r = Vars.run(1)(Vars.get[Int].map(_ + 1)).pure
        assert(r == 2)
    }

    "use" in {
        val r = Vars.run(1)(Vars.use[Int](_ + 1)).pure
        assert(r == 2)
    }

    "set, get" in {
        val r = Vars.run(1)(Vars.set(2).andThen(Vars.get[Int])).pure
        assert(r == 2)
    }

    "get, set, get" in {
        val r = Vars.run(1)(
            Vars.get[Int].map(i => Vars.set[Int](i + 1)).andThen(Vars.get[Int])
        ).pure
        assert(r == 2)
    }

    "update" in {
        val r = Vars.run(1)(Vars.update[Int](_ + 1).andThen(Vars.get[Int])).pure
        assert(r == 2)
    }

    "nested let" in {
        assert(
            Defers.run {
                Vars.run(1) {
                    Defers {
                        Vars.run(2) {
                            Vars.get[Int].map { innerValue =>
                                assert(innerValue == 2)
                            }
                        }
                    }.unit.andThen(Vars.get[Int])
                }
            }.pure == 1
        )
    }

    "string value" in {
        val result = Vars.run("a")(Vars.set("b").andThen(Vars.get[String])).pure
        assert(result == "b")
    }

    "side effect" in {
        var calls = 0
        val result =
            Vars.run(1) {
                for
                    _ <- Vars.update[Int] { value =>
                        calls += 1
                        value + 1
                    }
                    result <- Vars.get[Int]
                yield result
            }.pure
        assert(result == 2 && calls == 1)
    }

    "inference" in {
        val a: Int < Vars[Int]                   = Vars.get[Int]
        val b: Unit < (Vars[Int] & Vars[String]) = a.map(i => Vars.set(i.toString()))
        val c: Unit < Vars[String]               = Vars.run(1)(b)
        val d: Unit < Any                        = Vars.run("t")(c)
        assert(d.pure == ())
    }
end varsTest
