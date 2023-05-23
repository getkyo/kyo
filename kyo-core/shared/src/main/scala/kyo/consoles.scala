package kyo

import scala.Console

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
      val readln: String > IOs =
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

  opaque type Consoles = Envs[Console] & IOs

  object Consoles {
    type Iso = Consoles & IOs
    def run[T, S](c: Console)(f: => T > (Iso & S)): T > (S & IOs) =
      Envs[Console].let(c)(f)
    def run[T, S](f: => T > (Iso & S))(using c: Console): T > (S & IOs) =
      run(c)(f)
    def readln: String > Consoles =
      Envs[Console].get.map(_.readln)
    def print[S](s: => String > (Iso & S)): Unit > (S & Consoles) =
      s.map(s => Envs[Console].get.map(_.print(s)))
    def printErr[S](s: => String > (Iso & S)): Unit > (S & Consoles) =
      s.map(s => Envs[Console].get.map(_.printErr(s)))
    def println[S](s: => String > (Iso & S)): Unit > (S & Consoles) =
      s.map(s => Envs[Console].get.map(_.println(s)))
    def printlnErr[S](s: => String > (Iso & S)): Unit > (S & Consoles) =
      s.map(s => Envs[Console].get.map(_.printlnErr(s)))
  }
}
