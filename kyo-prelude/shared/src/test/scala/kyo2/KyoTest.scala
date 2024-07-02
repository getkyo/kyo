package kyo2

import Tagged.*
import scala.annotation.tailrec

class KyoTest extends Test:

    def widen[A](v: A): A < Any = v

    "toString JVM" taggedAs jvmOnly in run {
        assert(Env.use[Int](_ + 1).toString() ==
            "<(Kyo(Tag[kyo2.kernel.package$.internal$.Defer], Input(()), KyoTest.scala:11:16, assert(Env.use[Int](_ + 1)))")
        assert(
            Env.get[Int].map(_ + 1).toString() ==
                "<(Kyo(Tag[kyo2.kernel.package$.internal$.Defer], Input(()), KyoTest.scala:14:36, Env.get[Int].map(_ + 1)))"
        )
    }

    "toString JS" taggedAs jsOnly in run {
        assert(Env.use[Int](_ + 1).toString() ==
            "<(Kyo(Tag[kyo2.kernel.package$.internal$.Defer], Input(undefined), KyoTest.scala:20:16, assert(Env.use[Int](_ + 1)))")
        assert(
            Env.get[Int].map(_ + 1).toString() ==
                "<(Kyo(Tag[kyo2.kernel.package$.internal$.Defer], Input(undefined), KyoTest.scala:23:36, Env.get[Int].map(_ + 1)))"
        )
    }

    "eval" in {
        assert(Env.run(1)(Env.use[Int](_ + 1)).eval == 2)
        assertDoesNotCompile("Env.get[Int].eval")
        assert(widen(TypeMap(1, true)).eval.get[Boolean])
    }

    "eval widened" in {
        val v = widen(Env.use[Int](_ + 1)).eval
        assert(Env.run(1)(v).eval == 2)
    }

    "map" in {
        assert(Env.run(1)(Env.get[Int].map(_ + 1)).eval == 2)
        assert(Env.run(1)(Env.get[Int].map(v => Env.use[Int](_ + v))).eval == 2)
    }

    "flatMap" in {
        assert(Env.run(1)(Env.get[Int].flatMap(_ + 1)).eval == 2)
        assert(Env.run(1)(Env.get[Int].flatMap(v => Env.use[Int](_ + v))).eval == 2)
    }

    "unit" in {
        assert(Env.run(1)(Env.get[Int].unit).eval == ())
    }

    "flatten" in {
        def test[T](v: T)                = Env.use[Int](_ => v)
        val a: Int < Env[Int] < Env[Int] = test(Env.get[Int])
        val b: Int < Env[Int]            = a.flatten
        assert(Env.run(1)(b).eval == 1)
    }

    "andThen" in {
        assert(Var.run(())(Var.get[Unit]).andThen(2).eval == 2)
    }

    "repeat" in {
        assert(Var.run(0)(Var.update[Int](_ + 1).repeat(3).andThen(Var.get[Int])).eval == 3)
    }

    "zip" in {
        assert(Env.run(1)(Kyo.zip(Env.get[Int], Env.use[Int](_ + 1))).eval == (1, 2))
        assert(Var.run(1)(Kyo.zip(Var.get[Int], Var.use[Int](_ + 1), Var.get[Int])).eval == (1, 2, 1))
        assert(Var.run(1)(Env.run(2)(Kyo.zip(Var.get[Int], Var.use[Int](_ + 1), Var.get[Int], Env.use[Int](_ + 2)))).eval == (1, 2, 1, 4))
    }

    "nested" - {
        def lift[T](v: T): T < Env[Unit]                                = Env.use[Unit](_ => v)
        def add(v: Int < Env[Unit])                                     = v.map(_ + 1)
        def transform[T, U](v: T < Env[Unit], f: T => U): U < Env[Unit] = v.map(f(_))
        val io: Int < Env[Unit] < Env[Unit]                             = lift(lift(1))
        "map + flatten" in {
            val a: Int < Env[Unit] < Env[Unit] =
                transform[Int < Env[Unit], Int < Env[Unit]](io, add(_))
            val b: Int < Env[Unit] = a.flatten
            assert(Env.run(())(b).eval == 2)
        }
        "eval nested" in {
            val a: Int < Env[Unit] = Env.run(())(io).eval
            assert(Env.run(())(a).eval == 1)
        }
    }

    "stack safety" - {
        val n = 100000

        "recursive method" - {

            def countDown[S](v: Int < S): Int < S =
                v.map {
                    case 0 => 0
                    case n => countDown(n - 1)
                }

            "no effect" in {
                assert(countDown(n).eval == 0)
            }
            "effect at the start" in {
                assert(Env.run(n)(countDown(Env.get[Int])).eval == 0)
            }
            "effect at the end" in {
                assert(Env.run(n)(countDown(n).map(n => Env.use[Int](_ + n))).eval == n)
            }
            "multiple effects" in {
                def countDown(v: Int < Env[Unit]): Int < Env[Unit] =
                    v.map {
                        case 0 => 0
                        case n if n % 32 == 0 => Env.use[Unit](_ => countDown(n - 1))
                        case n => countDown(n - 1)
                    }
                assert(Env.run(())(countDown(n)).eval == 0)
            }
        }
        "dynamic computation" - {
            @tailrec def incr[S](v: Int < S, n: Int): Int < S =
                n match
                    case 0 => v
                    case n => incr(v.map(_ + 1), n - 1)

            "no effect" in {
                assert(incr(0, n).eval == n)
            }
            "suspension at the start" in pendingUntilFixed {
                try
                    assert(Env.run(n)(incr(Env.get[Int], n)).eval == 0)
                catch
                    case ex: StackOverflowError => fail()
                end try
                ()
            }

            "effect at the end" in {
                assert(Env.run(n)(incr(n, n).map(n => Env.use[Int](_ + n))).eval == n * 3)
            }
            "multiple effects" in pendingUntilFixed {
                @tailrec def incr(v: Int < Env[Unit], n: Int): Int < Env[Unit] =
                    n match
                        case 0 => v
                        case n if n % 32 == 0 =>
                            incr(v.map(v => Env.use[Unit](_ => v + 1)), n - 1)
                        case n => incr(v.map(_ + 1), n - 1)

                try assert(Env.run(())(incr(0, n)).eval == 0)
                catch
                    case ex: StackOverflowError => fail()
                end try
                ()
            }
        }
    }

    "seq" - {

        "collect" in {
            assert(kyo2.Kyo.seq.collect(Seq.empty).eval == Seq.empty)
            assert(kyo2.Kyo.seq.collect(Seq(1)).eval == Seq(1))
            assert(kyo2.Kyo.seq.collect(Seq(1, 2)).eval == Seq(1, 2))
            assert(kyo2.Kyo.seq.collect(Seq.fill(100)(1)).eval == Seq.fill(100)(1))
            assert(kyo2.Kyo.seq.collect(List(1, 2, 3)).eval == List(1, 2, 3))
            assert(kyo2.Kyo.seq.collect(Vector(1, 2, 3)).eval == Vector(1, 2, 3))
        }

        "collectUnit" in {
            var count = 0
            val io    = kyo2.Env.use[Unit](_ => count += 1)
            kyo2.Env.run(())(kyo2.Kyo.seq.collectUnit(Seq.empty)).eval
            assert(count == 0)
            kyo2.Env.run(())(kyo2.Kyo.seq.collectUnit(Seq(io))).eval
            assert(count == 1)
            kyo2.Env.run(())(kyo2.Kyo.seq.collectUnit(List.fill(42)(io))).eval
            assert(count == 43)
            kyo2.Env.run(())(kyo2.Kyo.seq.collectUnit(Vector.fill(10)(io))).eval
            assert(count == 53)
        }

        "map" in {
            assert(kyo2.Kyo.seq.map(Seq.empty[Int])(_ + 1).eval == Seq.empty)
            assert(kyo2.Kyo.seq.map(Seq(1))(_ + 1).eval == Seq(2))
            assert(kyo2.Kyo.seq.map(Seq(1, 2))(_ + 1).eval == Seq(2, 3))
            assert(kyo2.Kyo.seq.map(Seq.fill(100)(1))(_ + 1).eval == Seq.fill(100)(2))
            assert(kyo2.Kyo.seq.map(List(1, 2, 3))(_ + 1).eval == List(2, 3, 4))
            assert(kyo2.Kyo.seq.map(Vector(1, 2, 3))(_ + 1).eval == Vector(2, 3, 4))
        }

        "foreach" in {
            var acc = Seq.empty[Int]
            kyo2.Kyo.seq.foreach(Seq.empty[Int])(v => acc :+= v).eval
            assert(acc == Seq.empty)
            acc = Seq.empty[Int]
            kyo2.Kyo.seq.foreach(Seq(1))(v => acc :+= v).eval
            assert(acc == Seq(1))
            acc = Seq.empty[Int]
            kyo2.Kyo.seq.foreach(Seq(1, 2))(v => acc :+= v).eval
            assert(acc == Seq(1, 2))
            acc = Seq.empty[Int]
            kyo2.Kyo.seq.foreach(Seq.fill(100)(1))(v => acc :+= v).eval
            assert(acc == Seq.fill(100)(1))
            acc = Seq.empty[Int]
            kyo2.Kyo.seq.foreach(List(1, 2, 3))(v => acc :+= v).eval
            assert(acc == List(1, 2, 3))
            acc = Seq.empty[Int]
            kyo2.Kyo.seq.foreach(Vector(1, 2, 3))(v => acc :+= v).eval
            assert(acc == Vector(1, 2, 3))
        }

        "foldLeft" in {
            assert(kyo2.Kyo.seq.foldLeft(Seq.empty[Int])(0)(_ + _).eval == 0)
            assert(kyo2.Kyo.seq.foldLeft(Seq(1))(0)(_ + _).eval == 1)
            assert(kyo2.Kyo.seq.foldLeft(Seq(1, 2, 3))(0)(_ + _).eval == 6)
            assert(kyo2.Kyo.seq.foldLeft(Seq.fill(100)(1))(0)(_ + _).eval == 100)
            assert(kyo2.Kyo.seq.foldLeft(List(1, 2, 3))(0)(_ + _).eval == 6)
            assert(kyo2.Kyo.seq.foldLeft(Vector(1, 2, 3))(0)(_ + _).eval == 6)
        }

        "fill" in {
            assert(kyo2.Kyo.seq.fill(0)(1).eval == Seq.empty)
            assert(kyo2.Kyo.seq.fill(1)(1).eval == Seq(1))
            assert(kyo2.Kyo.seq.fill(3)(1).eval == Seq(1, 1, 1))
            assert(kyo2.Kyo.seq.fill(100)(1).eval == Seq.fill(100)(1))
        }

        "stack safety" - {
            val n = 1000

            "collect" in {
                assert(kyo2.Kyo.seq.collect(Seq.fill(n)(1)).eval == Seq.fill(n)(1))
            }

            "collectUnit" in {
                var count = 0
                val io    = kyo2.Env.use[Unit](_ => count += 1)
                kyo2.Env.run(())(kyo2.Kyo.seq.collectUnit(Seq.fill(n)(io))).eval
                assert(count == n)
            }

            "map" in {
                val largeSeq = Seq.fill(n)(1)
                assert(kyo2.Kyo.seq.map(largeSeq)(_ + 1).eval == Seq.fill(n)(2))
            }

            "foreach" in {
                var acc = Seq.empty[Int]
                kyo2.Kyo.seq.foreach(Seq.fill(n)(1))(v => acc :+= v).eval
                assert(acc == Seq.fill(n)(1))
            }

            "foldLeft" in {
                val largeSeq = Seq.fill(n)(1)
                assert(kyo2.Kyo.seq.foldLeft(largeSeq)(0)(_ + _).eval == n)
            }

            "fill" in {
                assert(kyo2.Kyo.seq.fill(n)(1).eval == Seq.fill(n)(1))
            }
        }
    }
end KyoTest
