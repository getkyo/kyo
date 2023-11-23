package kyo

import scala.Console

import ios._
import envs._

object consoles {

  abstract class Console {
    def readln: String > IOs
    def print[T](s: => T): Unit > IOs
    def printErr[T](s: => T): Unit > IOs
    def println[T](s: => T): Unit > IOs
    def printlnErr[T](s: => T): Unit > IOs
  }

  object Console {
    implicit val default: Console =
      new Console {
        val readln                 = IOs(scala.Console.in.readLine())
        def print[T](s: => T)      = IOs(scala.Console.out.print(s))
        def printErr[T](s: => T)   = IOs(scala.Console.err.print(s))
        def println[T](s: => T)    = IOs(scala.Console.out.println(s))
        def printlnErr[T](s: => T) = IOs(scala.Console.err.println(s))
      }
  }

  type Consoles >: Consoles.Effects <: Consoles.Effects

  object Consoles {

    type Effects = Envs[Console] with IOs

    private val envs = Envs[Console]

    def run[T, S](c: Console)(f: => T > (Consoles with S))(implicit
        flat: Flat[T, Consoles with S]
    ): T > (IOs with S) =
      envs.run[T, IOs with S](c)(f)

    def run[T, S](f: => T > (Consoles with S))(implicit
        c: Console,
        flat: Flat[T, Consoles with S]
    ): T > (IOs with S) =
      run[T, IOs with S](c)(f)

    val readln: String > Consoles =
      envs.get.map(_.readln)

    def print[T, S](s: => T > S): Unit > (S with Consoles) =
      s.map(s => envs.get.map(_.print(s)))

    def printErr[T, S](s: => T > S): Unit > (S with Consoles) =
      s.map(s => envs.get.map(_.printErr(s)))

    def println[T, S](s: => T > S): Unit > (S with Consoles) =
      s.map(s => envs.get.map(_.println(s)))

    def printlnErr[T, S](s: => T > S): Unit > (S with Consoles) =
      s.map(s => envs.get.map(_.printlnErr(s)))
  }
}
