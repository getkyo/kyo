package kyo

class VarTest extends Test:

    "get" in {
        val r = Var.run(1)(Var.get[Int].map(_ + 1)).eval
        assert(r == 2)
    }

    "use" in {
        val r = Var.run(1)(Var.use[Int](_ + 1)).eval
        assert(r == 2)
    }

    "setUnit, get" in {
        val r = Var.run(1)(Var.setDiscard(2).andThen(Var.get[Int])).eval
        assert(r == 2)
    }

    "set, get" in {
        val r = Var.run(1)(Var.set(2)).eval
        assert(r == 1)
    }

    "get, setUnit, get" in {
        val r = Var.run(1)(
            Var.get[Int].map(i => Var.setDiscard[Int](i + 1)).andThen(Var.get[Int])
        ).eval
        assert(r == 2)
    }

    "get, set" in {
        val r = Var.run(1)(
            Var.get[Int].map(i => Var.set[Int](i + 1))
        ).eval
        assert(r == 1)
    }

    "update" in {
        val r = Var.run(1)(Var.update[Int](_ + 1)).eval
        assert(r == 2)
    }

    "updateUnit" in {
        val r = Var.run(1)(Var.updateDiscard[Int](_ + 1).andThen(Var.get[Int])).eval
        assert(r == 2)
    }

    "runTuple" in {
        val r = Var.runTuple(1)(Var.update[Int](_ + 1).andThen(Var.get[Int]).map(_ + 1)).eval
        assert(r == (2, 3))
    }

    "scope" - {
        "should not affect the outer state" in {
            val result = Var.run(42)(
                Var.run(24)(Var.get[Int]).map(_ => Var.get[Int])
            )
            assert(result.eval == 42)
        }

        "should allow updating the local state" in {
            val result = Var.run(42)(
                Var.run(24)(Var.update[Int](_ + 1).map(_ => Var.get[Int]))
            )
            assert(result.eval == 25)
        }
    }

    "nested let" in {
        Var.run(1) {
            Var.run(2) {
                Var.get[Int].map { innerValue =>
                    assert(innerValue == 2)
                }
            }.andThen(Var.get[Int])
                .map { outerValue =>
                    assert(outerValue == 1)
                }
        }.eval
    }

    "string value" in {
        val result = Var.run("a")(Var.setDiscard("b").andThen(Var.get[String])).eval
        assert(result == "b")
    }

    "side effect" in {
        var calls = 0
        val result =
            Var.run(1) {
                for
                    u <- Var.update[Int] { value =>
                        calls += 1
                        value + 1
                    }
                    result <- Var.get[Int]
                yield (u, result)
            }.eval
        assert(result == (2, 2) && calls == 1)
    }

    "inference" in {
        val a: Int < Var[Int]                    = Var.get[Int]
        val b: Unit < (Var[Int] & Var[String])   = a.map(i => Var.setDiscard(i.toString()))
        val c: String < (Var[Int] & Var[String]) = b.andThen(Var.set("c"))
        val d: String < Var[String]              = Var.run(1)(c)
        val e: String < Any                      = Var.run("t")(d)
        assert(e.eval == "1")
    }
end VarTest
