package kyo

import kyo.internal.BaseKyoCoreTest
import org.scalatest.Assertions
import org.scalatest.freespec.AnyFreeSpec
import scala.collection.IterableOps
import scala.util.Try

class ShiftHygieneTest extends Test:
    "invalid nested" in {
        typeCheckFailure(
            """
               val x1: Seq[Int < Any] = Seq[Int < Any](1, 2, 3)
               val x2 = direct {
                 x1.map(x =>
                   def innerF = x.now
                   innerF)
                 }"""
        )(
            "Method definitions containing .now are not supported inside `direct` blocks"
        )
    }
end ShiftHygieneTest

class ShiftTest extends AnyFreeSpec with Assertions:

    "basic" in {
        val x1: Seq[Int < Any] = Seq[Int < Any](1, 2, 3)
        val x2: Seq[Int] < Any = direct:
            x1.map(_.now)

        assert(x2.eval == Seq(1, 2, 3))
    }

    "error" in {
        val x1: Seq[Int < Abort[String]] = Seq(1, Abort.fail("error"), 3)
        val x2: Seq[Int] < Abort[String] = direct:
            x1.map(_.now)

        Abort.run(x2).map: result =>
            assert(result == Result.fail("error"))
    }

    "valid nested" in {
        val x1: Seq[Int < Any] = Seq[Int < Any](1, 2, 3)
        val x2 = direct:
            x1.map(x =>
                def innerF(i: Int) = i + 1
                innerF(x.now)
            )

        val forReference = direct:
            def innerF(i: Int) = i + 1
            Sync.defer(innerF(1)).now

    }

end ShiftTest

class ShiftMethodSupportTest extends AnyFreeSpec with Assertions:
    "Option" - {
        val x: Option[Int < Any] = Option(1)
        val y: Option[Int]       = Option(1)
        val z: Option[Int] < Any = None

        "filter" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                y.filter(i => f(i).now)

            assert(d.eval == Option(1))
        }

        "orElse" in {
            val d = direct:
                y.orElse(z.now)

            assert(d.eval == Option(1))
        }

        "getOrElse" in {
            val w: Int < Any = 3
            val d = direct:
                z.now.getOrElse(w.now)

            assert(d.eval == 3)
        }

        "map" in {
            val d = direct:
                x.map(_.now + 1)

            assert(d.eval == Option(2))
        }
    }

    "Try" - {
        val x: Try[Int < Any] = Try(1)
        val y: Try[Int]       = Try(1)
        val z: Try[Int]       = Try(throw new Exception("z"))

        given [X]: CanEqual[Try[X], Try[X]] = CanEqual.derived

        "filter" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                y.filter(i => f(i).now)

            assert(d.eval == Try(1))
        }

        "orElse" in {
            val default: Try[Int] < Any = Try(2)

            val d = direct:
                z.orElse(default.now)

            assert(d.eval == Try(2))
        }

        "getOrElse" in {
            val default: Int < Any = 2
            val d = direct:
                z.getOrElse(default.now)

            assert(d.eval == 2)
        }
    }

    "Maybe" - {
        val xMaybeEffect: Maybe[Int < Any] = Maybe(1)
        val yMaybe: Maybe[Int]             = Maybe(1)
        val zMaybe: Maybe[Int]             = Maybe.empty

        "map" in {
            val d = direct:
                xMaybeEffect.map(_.now + 1)

            assert(d.eval == Maybe(2))
        }

        "flatMap" in {
            val d = direct:
                xMaybeEffect.flatMap(i => Maybe(i.now + 1))

            assert(d.eval == Maybe(2))
        }

        "orElse" in {
            val d = direct:
                zMaybe.orElse(yMaybe)

            assert(d.eval == Maybe(1))
        }

        "filter" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                yMaybe.filter(i => f(i).now)

            assert(d.eval == Maybe(1))
        }

        "filterNot" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                yMaybe.filterNot(i => f(i).now)

            assert(d.eval == Maybe.empty)
        }

        "exists" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                yMaybe.exists(i => f(i).now)

            assert(d.eval)
        }

        "forall" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                yMaybe.forall(i => f(i).now)

            assert(d.eval)
        }

        "foreach" in {

            def plus(i: Int) = Var.update[Int](_ + i)

            val d = direct:
                yMaybe.foreach(i => plus(i).unit.now)

            assert(Var.run(0)(d.andThen(Var.get[Int])).eval == 1)
        }

        "collect" in {
            def f(i: Int): Int < Any = i + 1

            val d = direct:
                yMaybe.collect:
                    case i if i < 3 => f(i).now

            assert(d.eval == Maybe(2))
        }

        "fold" in {
            def identity(i: Int): Int < Any = i
            val d = direct:
                yMaybe.fold(0)(i => identity(i).now)

            assert(d.eval == 1)
        }

        "getOrElse" in {
            val default: Int < Any = 2
            val d = direct:
                zMaybe.getOrElse(default.now)

            assert(d.eval == 2)
        }

    }

    "Result" - {
        val xResultEffect: Result[Throwable, Int < Any] = Result.succeed(1)
        val yResult: Result[Nothing, Int]               = Result.succeed(1)
        val zResult: Result[String, Nothing]            = Result.fail("fail")

        "map" in {
            val d = direct:
                xResultEffect.map(_.now + 1)

            assert(d.eval == Result(2))
        }

        "flatMap" in {
            val d = direct:
                xResultEffect.flatMap(i => Result.succeed(i.now + 1))

            assert(d.eval == Result(2))
        }

        "orElse" in {
            def e: Result[Nothing, Int] < Any = Result.succeed(1)

            val d = direct:
                zResult.orElse(e.now)

            assert(d.eval == Result(1))
        }

        "filter" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                yResult.filter(i => f(i).now)

            assert(d.eval == Result(1))
        }

        "exists" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                yResult.exists(i => f(i).now)

            assert(d.eval)
        }

        "forall" in {
            def f(i: Int): Boolean < Any = i < 3
            val d = direct:
                yResult.forall(i => f(i).now)

            assert(d.eval)
        }

        "foreach" in {
            def f(i: Int): Unit < Var[Int] = Var.setDiscard(i)
            val d = direct:
                yResult.foreach(i => f(i).now)

            Var.runTuple(0)(d).map((v, _) => assert(v == 1))
        }

        "fold" in {
            def identity(i: Int): Int < Any = i
            val d = direct:
                yResult.fold(i => identity(i).now, x => 0, _ => -1)

            assert(d.eval == 1)
        }

        "getOrElse" in {
            val default: Int < Any = 2
            val d = direct:
                zResult.getOrElse(default.now)

            assert(d.eval == 2)
        }

        "panic" in {
            val x: Result[Throwable, Int] = Result.Panic(new Exception("panic"))

            def f(i: Int): Int < Any        = ???
            def pred(i: Int): Boolean < Any = ???

            val prg = direct:
                val x1 = x.map(i => f(i).now)
                val x2 = x1.filter(i => pred(i).now)
                val x3 = x2.flatMap(i => Result.succeed(f(i).now))

        }

    }

end ShiftMethodSupportTest
