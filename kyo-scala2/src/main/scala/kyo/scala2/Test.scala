package kyo.scala2

import kyo.core._
import kyo.options._
import kyo.tries._
import scala.util.Try

object Test extends App {

  val a: Try[Int] > Options =
    Options.get(Some(Try(1)))

  val b: Int > (Options with Tries) =
    a.map(Tries.get(_))

  val c: Option[Int] > Tries =
    Options.run(b)

  val d: Try[Option[Int]] =
    Tries.run(c)

  println(d)

  implicit def fromPure[T](v: T): T > Any = v.asInstanceOf[T > Any]
  implicit def toPure[T](v: T > Any): T   = v.asInstanceOf[T]

  implicit class KyoOps[T, S](v: T > S) {
    def map[U, S2](f: T => U > S2): U > (S with S2) =
      flatMap(f)
    def flatMap[U, S2](f: T => U > S2): U > (S with S2) =
      transform(v)(f)
  }
}
