package kyo2

class VarTest extends Test:

    "get" in {
        val r = kyo2.Var.run(1)(kyo2.Var.get[Int].map(_ + 1)).eval
        assert(r == 2)
    }

    "use" in {
        val r = kyo2.Var.run(1)(kyo2.Var.use[Int](_ + 1)).eval
        assert(r == 2)
    }

    "set, get" in {
        val r = kyo2.Var.run(1)(kyo2.Var.set(2).andThen(kyo2.Var.get[Int])).eval
        assert(r == 2)
    }

    "get, set, get" in {
        val r = kyo2.Var.run(1)(
            kyo2.Var.get[Int].map(i => kyo2.Var.set[Int](i + 1)).andThen(kyo2.Var.get[Int])
        ).eval
        assert(r == 2)
    }

    "update" in {
        val r = kyo2.Var.run(1)(kyo2.Var.update[Int](_ + 1).unit.andThen(kyo2.Var.get[Int])).eval
        assert(r == 2)
    }

    "runTuple" in {
        val r = kyo2.Var.runTuple(1)(kyo2.Var.update[Int](_ + 1).unit.andThen(kyo2.Var.get[Int]).map(_ + 1)).eval
        assert(r == (2, 3))
    }

    "scope" - {
        "should not affect the outer state" in {
            val result = kyo2.Var.run(42)(
                kyo2.Var.run(24)(kyo2.Var.get[Int]).map(_ => kyo2.Var.get[Int])
            )
            assert(result.eval == 42)
        }

        "should allow updating the local state" in {
            val result = kyo2.Var.run(42)(
                kyo2.Var.run(24)(kyo2.Var.update[Int](_ + 1).map(_ => kyo2.Var.get[Int]))
            )
            assert(result.eval == 25)
        }
    }

    "nested let" in {
        kyo2.Var.run(1) {
            kyo2.Var.run(2) {
                kyo2.Var.get[Int].map { innerValue =>
                    assert(innerValue == 2)
                }
            }.unit.andThen(kyo2.Var.get[Int])
                .map { outerValue =>
                    assert(outerValue == 1)
                }
        }.eval
    }

    "string value" in {
        val result = kyo2.Var.run("a")(kyo2.Var.set("b").andThen(kyo2.Var.get[String])).eval
        assert(result == "b")
    }

    "side effect" in {
        var calls = 0
        val result =
            kyo2.Var.run(1) {
                for
                    _ <- kyo2.Var.update[Int] { value =>
                        calls += 1
                        value + 1
                    }
                    result <- kyo2.Var.get[Int]
                yield result
            }.eval
        assert(result == 2 && calls == 1)
    }

    "inference" in {
        val a: Int < Var[Int]                  = kyo2.Var.get[Int]
        val b: Unit < (Var[Int] & Var[String]) = a.map(i => kyo2.Var.set(i.toString()))
        val c: Unit < Var[String]              = kyo2.Var.run(1)(b)
        val d: Unit < Any                      = kyo2.Var.run("t")(c)
        assert(d.eval == ())
    }
end VarTest
