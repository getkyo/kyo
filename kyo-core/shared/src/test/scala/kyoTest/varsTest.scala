package kyoTest

import kyo.*

class varsTest extends KyoTest:

    "get" in run {
        val r = Vars.run(1)(Vars.get[Int].map(_ + 1)).pure
        assert(r == 2)
    }

    "set, get" in run {
        val r = Vars.run(1)(Vars.set(2).andThen(Vars.get[Int])).pure
        assert(r == 2)
    }

    "get, set, get" in run {
        val r = Vars.run(1)(Vars.get[Int].map(i => Vars.set(i + 1)).andThen(Vars.get[Int])).pure
        assert(r == 2)
    }

    "update" in run {
        val r = Vars.run(1)(Vars.update[Int](_ + 1).andThen(Vars.get[Int])).pure
        assert(r == 2)
    }

    "nested let" in run {
        Vars.run(1) {
            IOs {
                Vars.run(2) {
                    Vars.get[Int].map { innerValue =>
                        assert(innerValue == 2)
                    }
                }
            }.unit.andThen(Vars.get[Int])
                .map { outerValue =>
                    assert(outerValue == 1)
                }
        }
    }

    "string value" in run {
        Vars.run("a") {
            for
                _      <- Vars.set("b")
                result <- Vars.get[String]
            yield assert(result == "b")
        }
    }

    "side effect" in run {
        var sideEffectCounter = 0
        Vars.run(1) {
            for
                _ <- Vars.update[Int] { value =>
                    sideEffectCounter += 1
                    value + 1
                }
                result <- Vars.get[Int]
            yield assert(result == 2 && sideEffectCounter == 1)
        }
    }

    "inference" in {
        val a: Int < Vars[Int]                   = Vars.get[Int]
        val b: Unit < (Vars[Int] & Vars[String]) = a.map(i => Vars.set(i.toString()))
        val c: Unit < Vars[String]               = Vars.run(1)(b)
        val d: Unit < Any                        = Vars.run("t")(c)
        assert(d.pure == ())
    }
end varsTest
