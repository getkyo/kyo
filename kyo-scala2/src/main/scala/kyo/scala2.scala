package kyo

import kyo.core._
import kyo.options._
import kyo.ios._
import kyo.concurrent.fibers._
import kyo.tries._
import scala.util.Try
import scala.util.NotGiven

object scala2 {

  type >[+T, -S] = core.>[T, S]

  implicit def fromPure[T](v: T)(implicit ng: NotGiven[T <:< (Any > Nothing)]): T > Any =
    v.asInstanceOf[T > Any]
  implicit def toPure[T](v: T > Any)(implicit ng: NotGiven[T <:< (Any > Nothing)]): T =
    v.asInstanceOf[T]

  implicit class KyoOps[T, S](v: T > S) {
    def map[U, S2](f: T => U > S2): U > (S with S2) =
      flatMap(f)
    def flatMap[U, S2](f: T => U > S2): U > (S with S2) =
      kyo.core.transform(v)(f)
  }
}
