package kyo

import java.io.IOException
import java.io.EOFException
import pprint.TPrint

abstract class Console {
  def readln: String < IOs
  def print(s: String): Unit < IOs
  def printErr(s: String): Unit < IOs
  def println(s: String): Unit < IOs
  def printlnErr(s: String): Unit < IOs
}

object Console {
  val default: Console =
    new Console {
      val readln =
        IOs {
          val line = scala.Console.in.readLine()
          if (line == null)
            throw new EOFException("Consoles.readln failed.")
          else
            line
        }
      def print(s: String)      = IOs(scala.Console.out.print(s))
      def printErr(s: String)   = IOs(scala.Console.err.print(s))
      def println(s: String)    = IOs(scala.Console.out.println(s))
      def printlnErr(s: String) = IOs(scala.Console.err.println(s))
    }
}

object Consoles {

  private val local = Locals.init(Console.default)

  def let[T, S](c: Console)(v: T < S): T < (S & IOs) =
    local.let(c)(v)

  val readln: String < IOs =
    local.get.map(_.readln)

  private def toString(v: Any): String =
    v match {
      case v: String =>
        v
      case v =>
        pprint.apply(v).plainText
    }

  def print[T](v: T): Unit < IOs =
    local.get.map(_.print(toString(v)))

  def printErr[T](v: T): Unit < IOs =
    local.get.map(_.printErr(toString(v)))

  def println[T](v: T): Unit < IOs =
    local.get.map(_.println(toString(v)))

  def printlnErr[T](v: T): Unit < IOs =
    local.get.map(_.printlnErr(toString(v)))
}
