package kyo.kernel.internal

import kyo.Const
import kyo.Render
import kyo.Result
import kyo.Tag
import kyo.kernel.*
import kyo.render

final class ImplicitsTestWrapper(val value: Int) extends AnyVal

class ImplicitsTest extends kyo.test.Test[Any]:

    sealed trait TestEffect1 extends ArrowEffect[Const[Int], Const[Int]]
    sealed trait TestEffect2 extends ArrowEffect[Const[Int], Const[Int]]

    final case class Box(value: Int) derives CanEqual
    case object Marker

    "lift" - {
        "primitives" in {
            val i: Int < Any     = 5
            val l: Long < Any    = 5L
            val d: Double < Any  = 5.0
            val b: Boolean < Any = true
            val c: Char < Any    = 'x'
            assert(i.eval == 5)
            assert(l.eval == 5L)
            assert(d.eval == 5.0)
            assert(b.eval == true)
            assert(c.eval == 'x')
        }

        "String" in {
            val s: String < Any = "hello"
            assert(s.eval == "hello")
        }

        "Unit" in {
            val u: Unit < Any = ()
            assert(u.eval == ())
        }

        "concrete class" in {
            val v: Box < Any = Box(42)
            assert(v.eval == Box(42))
        }

        "AnyVal wrapper" in {
            val v: ImplicitsTestWrapper < Any = new ImplicitsTestWrapper(42)
            assert(v.eval.value == 42)
        }

        "case object" in {
            val v: Marker.type < Any = Marker
            assert(v.eval eq Marker)
        }

        "collection" in {
            val v: List[Int] < Any = List(1, 2, 3)
            assert(v.eval == List(1, 2, 3))
        }

        "generic value" in {
            def liftGeneric[A](a: A): A < Any = a
            assert(liftGeneric(42).eval == 42)
            assert(liftGeneric("s").eval == "s")
        }

        "generic value holding a computation nests" in {
            def liftGeneric[A](a: A): A < Any = a
            val inner: Int < Any              = (1: Int < Any).map(_ + 1)
            val nested: (Int < Any) < Any     = liftGeneric(inner)
            assert(nested.eval.eval == 2)
        }
    }

    "lift rejections" - {
        "inference widening does not nest implicitly" in {
            typeCheckFailure("val _: Int < Any < Any = (1: Int < Any)")("Required: Int < Any < Any")
        }

        "generic method effect mismatch" in {
            typeCheckFailure(
                "def test1[A](v: A < Any) = v; test1(1: Int < TestEffect1)"
            )("Required: Any < Any")
        }

        "kyo modules do not lift" in {
            // the macro's guided message names the rejected singleton
            typeCheckFailure("val bad: ArrowEffect.type < Any = ArrowEffect")(
                "Cannot lift 'kyo.kernel.ArrowEffect$' to a 'ArrowEffect$ < S'"
            )
            typeCheckFailure("val bad: Loop.type < Any = Loop")("Cannot lift 'kyo.kernel.Loop$' to a 'Loop$ < S'")
        }

        // the abortCastUnit trap (a Unit row mismatch aborts with the issue-903
        // guidance) cannot be pinned here: the abort fires during inline
        // expansion inside compiletime.testing, whose capture is
        // zinc-state-dependent and flips between runs. The deterministic pin
        // compiles the shape with a real dotc: kyo-compile-bench
        // CompileBenchNegativeTest.
    }

    "lifted functions" - {
        "one param" in {
            val f: Int => String            = _.toString
            val lifted: Int => String < Any = f
            assert(lifted(42).eval == "42")
        }

        "two params" in {
            val f: (Int, Int) => String            = (a, b) => (a + b).toString
            val lifted: (Int, Int) => String < Any = f
            assert(lifted(20, 22).eval == "42")
        }

        "three params" in {
            val f: (Int, Int, Int) => String            = (a, b, c) => (a + b + c).toString
            val lifted: (Int, Int, Int) => String < Any = f
            assert(lifted(10, 20, 12).eval == "42")
        }

        "four params" in {
            val f: (Int, Int, Int, Int) => String            = (a, b, c, d) => (a + b + c + d).toString
            val lifted: (Int, Int, Int, Int) => String < Any = f
            assert(lifted(10, 20, 10, 2).eval == "42")
        }

        "do not lift into nested computations" in {
            typeCheckFailure(
                "val f1: Int => String < Any = _ => \"test\"; val bad: Int => String < Any < Any = f1"
            )("Required: Int => String < Any < Any")
        }
    }

    "Render instance" - {
        "displays pure values wrapped, inner types via their own Render" in {
            val i: Result[String, Int] < Any         = Result.succeed(23)
            val r: Render[Result[String, Int] < Any] = Render.apply
            assert(r.asString(i) == "Kyo(Success(23))")
            assert(render"$i" == "Kyo(Success(23))")
        }

        "displays a computation that has not settled as the operation it waits on" in {
            val i: Int < TestEffect1         = ArrowEffect.suspend[Any](Tag[TestEffect1], 1)
            val r: Render[Int < TestEffect1] = Render.apply
            assert(r.asString(i).startsWith("Kyo("))
            assert(r.asString(i).contains("TestEffect1"))
        }
    }

end ImplicitsTest
