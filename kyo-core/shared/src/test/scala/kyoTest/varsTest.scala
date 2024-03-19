package kyoTest

import kyo.*

class varsTest extends KyoTest:

    "get" in {
        val r = Vars[Int].run(1)(Vars[Int].get.map(_ + 1)).pure
        assert(r == 2)
    }

    "set, get" in {
        val r = Vars[Int].run(1)(Vars[Int].set(2).andThen(Vars[Int].get)).pure
        assert(r == 2)
    }

    "get, set, get" in {
        val r = Vars[Int].run(1)(
            Vars[Int].get.map(i => Vars[Int].set(i + 1)).andThen(Vars[Int].get)
        ).pure
        assert(r == 2)
    }

    "update" in {
        val r = Vars[Int].run(1)(Vars[Int].update(_ + 1).andThen(Vars[Int].get)).pure
        assert(r == 2)
    }

    "nested let" in {
        IOs.run {
            Vars[Int].run(1) {
                IOs {
                    Vars[Int].run(2) {
                        Vars[Int].get.map { innerValue =>
                            assert(innerValue == 2)
                        }
                    }
                }.unit.andThen(Vars[Int].get)
                    .map { outerValue =>
                        assert(outerValue == 1)
                    }
            }
        }
    }

    "string value" in {
        val result = Vars[String].run("a")(Vars[String].set("b").andThen(Vars[String].get)).pure
        assert(result == "b")
    }

    "side effect" in {
        var calls = 0
        val result =
            Vars[Int].run(1) {
                for
                    _ <- Vars[Int].update { value =>
                        calls += 1
                        value + 1
                    }
                    result <- Vars[Int].get
                yield result
            }.pure
        assert(result == 2 && calls == 1)
    }

    "inference" in {
        val a: Int < Vars[Int]                   = Vars[Int].get
        val b: Unit < (Vars[Int] & Vars[String]) = a.map(i => Vars[String].set(i.toString()))
        val c: Unit < Vars[String]               = Vars[Int].run(1)(b)
        val d: Unit < Any                        = Vars[String].run("t")(c)
        assert(d.pure == ())
    }
end varsTest
