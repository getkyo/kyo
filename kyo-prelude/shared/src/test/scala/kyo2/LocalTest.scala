package kyo2

import kyo2.*

class LocalTest extends Test:

    "default" - {
        "method" in {
            val l = Local(10)
            assert(l.default == 10)
        }
        "get" in {
            val l = Local(10)
            assert(
                l.get.eval == 10
            )
        }
        "effect + get" in {
            val l = Local(10)
            assert(
                Env.run(1)(Env.use[Int](_ => l.get)).eval == 10
            )
        }
        "effect + get + effect" in {
            val l = Local(10)
            assert(
                Env.run(42)(Env.use(_ => l.get).map(i => Env.use[Int](_ + i))).eval ==
                    52
            )
        }
        "multiple" in {
            val l1 = Local(10)
            val l2 = Local(20)
            assert(
                Kyo.zip(l1.get, l2.get).eval == (10, 20)
            )
        }
    }

    "let" - {
        "get" in {
            val l = Local(10)
            assert(
                l.let(20)(l.get).eval ==
                    20
            )
        }
        "effect + get" in {
            val l = Local(10)
            assert(
                Env.run(1)(Env.use(_ => l.let(20)(l.get))).eval == 20
            )
        }
        "effect + get + effect" in {
            val l = Local(10)
            assert(
                Env.run(1)(Env.use(_ => l.let(20)(l.get).map(i => Env.use[Int](_ + i)))).eval == 21
            )
        }
        "multiple" in {
            val l1 = Local(10)
            val l2 = Local(20)
            assert(
                Kyo.zip(l1.let(30)(l1.get), l2.let(40)(l2.get)).eval ==
                    (30, 40)
            )
        }
    }

    "update" - {
        "get" in {
            val l = Local(10)
            assert(
                l.update(_ + 10)(l.get).eval == 20
            )
        }
        "effect + get" in {
            val l = Local(10)
            assert(
                Env.run(1)(Env.use(_ => l.update(_ + 20)(l.get))).eval == 30
            )
        }
        "effect + get + effect" in {
            val l = Local(10)
            assert(
                Env.run(1)(Env.use(_ => l.update(_ + 20)(l.get).map(i => Env.use[Int](_ + i)))).eval == 31
            )
        }
        "multiple" in {
            val l1 = Local(10)
            val l2 = Local(20)
            assert(
                Kyo.zip(l1.update(_ + 10)(l1.get), l2.update(_ + 10)(l2.get)).eval ==
                    (20, 30)
            )
        }
    }
end LocalTest
