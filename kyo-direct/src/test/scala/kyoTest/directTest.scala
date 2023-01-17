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
import kyo.lists._
import kyo.concurrent.refs._

class directTest extends KyoTest {

  "one run" in {
    val io = Defer {
      val a = Run(IOs("hello"))
      a + " world"
    }
    assert(IOs.run(io) == "hello world")
  }

  "two runs" in {
    val io =
      Defer {
        val a = Run(IOs("hello"))
        val b = Run(IOs("world"))
        a + " " + b
      }
    assert(IOs.run(io) == "hello world")
  }

  "two effects" in {
    val io: String > (IOs | Options) =
      Defer {
        val a = Run(Options.get(Some("hello")))
        val b = Run(IOs("world"))
        a + " " + b
      }
    assert(IOs.run(io < Options) == Some("hello world"))
  }

  "if" in {
    var calls = List.empty[Int]
    val io: Boolean > IOs =
      Defer {
        if (Run(IOs { calls :+= 1; true }))
          Run(IOs { calls :+= 2; true })
        else
          Run(IOs { calls :+= 3; true })
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
          Defer {
            (Run(IOs { calls :+= 1; true }) && Run(IOs { calls :+= 2; true }))
          }
        assert(IOs.run(io))
        assert(calls == List(2, 1))
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
          Defer {
            (Run(IOs { calls :+= 1; true }) || Run(IOs { calls :+= 2; true }))
          }
        assert(IOs.run(io))
        assert(calls == List(2, 1))
      }
    }
  }

  "options" in {
    def test[T](opt: Option[T]) =
      assert(opt == Defer(Run(opt > Options)) < Options)
    test(Some(1))
    test(None)
    test(Some("a"))
  }
  "tries" in {
    def test[T](t: Try[T]) =
      assert(t == Defer(Run(t > Tries)) < Tries)
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
    val io: String > IOs = Consoles.run(console)(Defer(Run(Consoles.readln)))
    assert(IOs.run(io) == "hello")
  }

  // case class Person(name: String, age: Int, minor: Boolean)

  // def test(
  //     nameOptions: Option[String],
  //     ageString: String
  // ): Person > (Options | Tries | Consoles | Fibers | IOs) =
  //   Defer {
  //     val name   = Run(nameOptions > Options)
  //     val age    = Run(Tries(Integer.parseInt(ageString)))
  //     val minAge = Run(Consoles.readln(i => Tries(Integer.parseInt(i))))
  //     val minor  = Run(Fibers.fork(age < minAge))
  //     Person(name, age, minor)
  //   }

  // val a: Person > (Fibers | Consoles | (IOs | Options | Tries)) = test(Some("John"), "10")
  // val b: Option[Person] > (Fibers | Consoles | (IOs | Tries))   = a < Options
  // val c: Try[Option[Person]] > (Fibers | Consoles | IOs)        = b < Tries
  // val d: Try[Option[Person]] > (IOs | Fibers)                   = Consoles.run(c)
  // val e                                                         = Fibers.block(d)
  // val f: Try[Option[Person]]                                    = IOs.run(e)
  // println(f)
}
