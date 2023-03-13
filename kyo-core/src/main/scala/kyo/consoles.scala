package kyo

import scala.Console

import core._
import ios._
import envs._

object consoles {

  trait Console {
    def readln: String > IOs
    def print(s: => String): Unit > IOs
    def printErr(s: => String): Unit > IOs
    def println(s: => String): Unit > IOs
    def printlnErr(s: => String): Unit > IOs
  }

  object Console {
    given default: Console with {
      def readln: String > IOs =
        IOs(scala.Console.in.readLine())
      def print(s: => String): Unit > IOs =
        IOs(scala.Console.out.print(s))
      def printErr(s: => String): Unit > IOs =
        IOs(scala.Console.err.print(s))
      def println(s: => String): Unit > IOs =
        IOs(scala.Console.out.println(s))
      def printlnErr(s: => String): Unit > IOs =
        IOs(scala.Console.err.println(s))
    }
  }

  type Consoles = Envs[Console] | IOs

  object Consoles {
    def run[T, S](c: Console)(f: => T > (S | Consoles)): T > (S | IOs) =
      Envs.let(c)(f)
    def run[T, S](f: => T > (S | Consoles))(using c: Console): T > (S | IOs) =
      run(c)(f)
    def readln: String > Consoles =
      Envs[Console](_.readln)
    def print(s: => String): Unit > Consoles =
      Envs[Console](_.print(s))
    def printErr(s: => String): Unit > Consoles =
      Envs[Console](_.printErr(s))
    def println(s: => String): Unit > Consoles =
      Envs[Console](_.println(s))
    def printlnErr(s: => String): Unit > Consoles =
      Envs[Console](_.printlnErr(s))
  }
}
