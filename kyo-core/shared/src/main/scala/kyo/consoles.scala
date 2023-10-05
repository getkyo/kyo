package kyo

import scala.Console

import ios._
import envs._

object consoles {

  abstract class Console {

    def readln: String > IOs

    def print(s: => String): Unit > IOs

    def printErr(s: => String): Unit > IOs

    def println(s: => String): Unit > IOs

    def printlnErr(s: => String): Unit > IOs
  }

  object Console {
    implicit val default: Console =
      new Console {
        val readln                   = IOs(scala.Console.in.readLine())
        def print(s: => String)      = IOs(scala.Console.out.print(s))
        def printErr(s: => String)   = IOs(scala.Console.err.print(s))
        def println(s: => String)    = IOs(scala.Console.out.println(s))
        def printlnErr(s: => String) = IOs(scala.Console.err.println(s))
      }
  }

  type Consoles = Envs[Console] with IOs

  object Consoles {

    private val envs = Envs[Console]

    def run[T, S](c: Console)(f: => T > (Consoles with S)): T > (IOs with S) =
      envs.run[T, IOs with S](c)(f)

    def run[T, S](f: => T > (Consoles with S))(implicit c: Console): T > (IOs with S) =
      run[T, IOs with S](c)(f)

    val readln: String > Consoles =
      envs.get.map(_.readln)

    def print[S](s: => String > S): Unit > (S with Consoles) =
      s.map(s => envs.get.map(_.print(s)))

    def printErr[S](s: => String > S): Unit > (S with Consoles) =
      s.map(s => envs.get.map(_.printErr(s)))

    def println[S](s: => String > S): Unit > (S with Consoles) =
      s.map(s => envs.get.map(_.println(s)))

    def printlnErr[S](s: => String > S): Unit > (S with Consoles) =
      s.map(s => envs.get.map(_.printlnErr(s)))
  }
}
