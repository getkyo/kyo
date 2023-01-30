package kyoTest

import kyo.core._
import kyo.tries._
import kyo.options._
import kyo.direct._
import kyo.ios._
import kyo.envs._
import kyo.concurrent.fibers._
import scala.util.Try
import kyo.consoles._
import kyo.concurrent.atomics._

class directTest extends KyoTest {

  "one run" in {
    val io = Kyo.direct {
      val a = Kyo(IOs("hello"))
      a + " world"
    }
    assert(IOs.run(io) == "hello world")
  }

  "two runs" in {
    val io =
      Kyo.direct {
        val a = Kyo(IOs("hello"))
        val b = Kyo(IOs("world"))
        a + " " + b
      }
    assert(IOs.run(io) == "hello world")
  }

  "two effects" in {
    val io: String > (IOs | Options) =
      Kyo.direct {
        val a = Kyo(Options.get(Some("hello")))
        val b = Kyo(IOs("world"))
        a + " " + b
      }
    assert(IOs.run(io < Options) == Some("hello world"))
  }

  "if" in {
    var calls = List.empty[Int]
    val io: Boolean > IOs =
      Kyo.direct {
        if (Kyo(IOs { calls :+= 1; true }))
          Kyo(IOs { calls :+= 2; true })
        else
          Kyo(IOs { calls :+= 3; true })
      }
    assert(IOs.run(io))
    assert(calls == List(1, 2))
  }

  "booleans" - {
    "&&" - {
      "plain" in {
        var calls = List.empty[Int]
        { calls :+= 1; true } && { calls :+= 2; true }
        assert(calls == List(1, 2))
      }
      "direct" in {
        var calls = List.empty[Int]
        val io: Boolean > IOs =
          Kyo.direct {
            (Kyo(IOs { calls :+= 1; true }) && Kyo(IOs { calls :+= 2; true }))
          }
        assert(IOs.run(io))
        assert(calls == List(1, 2))
      }
    }
    "||" - {
      "plain" in {
        var calls = List.empty[Int]
        { calls :+= 1; true } || { calls :+= 2; true }
        assert(calls == List(1))
      }
      "direct" in {
        var calls = List.empty[Int]
        val io: Boolean > IOs =
          Kyo.direct {
            (Kyo(IOs { calls :+= 1; true }) || Kyo(IOs { calls :+= 2; true }))
          }
        assert(IOs.run(io))
        assert(calls == List(1))
      }
    }
  }

  "options" in {
    def test[T](opt: Option[T]) =
      assert(opt == Kyo.direct(Kyo(opt > Options)) < Options)
    test(Some(1))
    test(None)
    test(Some("a"))
  }
  "tries" in {
    def test[T](t: Try[T]) =
      assert(t == Kyo.direct(Kyo(t > Tries)) < Tries)
    test(Try(1))
    test(Try(throw new Exception("a")))
    test(Try("a"))
  }
  "consoles" in {
    object console extends Console {

      def printErr(s: => String): Unit > IOs = ???

      def println(s: => String): Unit > IOs = ???

      def print(s: => String): Unit > IOs = ???

      def readln: String > IOs = "hello"

      def printlnErr(s: => String): Unit > IOs = ???
    }
    val io: String > IOs = Consoles.run(console)(Kyo.direct(Kyo(Consoles.readln)))
    assert(IOs.run(io) == "hello")
  }

  "kyo computations must be within a run block" in {
    assertDoesNotCompile("Kyo.direct(IOs(1))")
    assertDoesNotCompile("""
      Kyo.direct {
        val a = IOs(1)
        10
      }
    """)
    assertDoesNotCompile("""
      Kyo.direct {
        val a = {
          val b = IOs(1)
          10
        }
        10
      }
    """)
  }

  "choices" in {
    import kyo.choices._

    val x = Choices(1, -2, -3)
    val y = Choices("ab", "cde")

    val v: Int > Choices =
      Kyo.direct {
        val xx = Kyo(x)
        xx + (
            if (xx > 0) then Kyo(y).length * Kyo(x)
            else Kyo(y).length
        )
      }

    val a: List[Int] = Choices.run(v)
    assert(a == List(3, 4, -3, -5, -5, -8, 0, 1, -1, 0))
  }

  "choices + ensure" in {
    import kyo.choices._

    val x = Choices(1, -2, -3)
    val y = Choices("ab", "cde")

    val v: Int > Choices =
      Kyo.direct {
        val xx = Kyo(x)
        val r =
          xx + (
              if (xx > 0) then Kyo(y).length * Kyo(x)
              else Kyo(y).length
          )
        Kyo(Choices.ensure(r > 0))
        r
      }

    val a: List[Int] = Choices.run(v)
    assert(a == List(3, 4, 1))
  }

}
