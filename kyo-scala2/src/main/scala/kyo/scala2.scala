package kyo

import kyo.core._
import kyo.options._
import kyo.ios._
import kyo.concurrent.fibers._
import kyo.tries._
import scala.util.Try
import scala.util.NotGiven

trait LowPriorityImplicits {
  this: scala2.type =>
  implicit class PureOps[+T](v: T) {
    def map[U, S2](f: T => U > S2): U > S2 =
      flatMap(f)
    def flatMap[U, S2](f: T => U > S2): U > S2 =
      kyo.core.transform(v)(f)
    def withFilter(f: T => Boolean): T > Any =
      kyo.core.withFilter(v)(f)
    def unit: Unit > Any =
      map(_ => ())
  }
}

object scala2 extends LowPriorityImplicits {

  type >[+T, -S] = core.>[T, S]

  implicit def fromPure[T](v: T)(implicit ng: NotGiven[T <:< (Any > Nothing)]): T > Any =
    v.asInstanceOf[T > Any]
  implicit def toPure[T](v: T > Any)(implicit ng: NotGiven[T <:< (Any > Nothing)]): T =
    v.asInstanceOf[T]

  def zip[T1, T2, S](v1: T1 > S, v2: T2 > S): (T1, T2) > S =
    v1.map(t1 => v2.map(t2 => (t1, t2)))

  def zip[T1, T2, T3, S](v1: T1 > S, v2: T2 > S, v3: T3 > S): (T1, T2, T3) > S =
    v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

  def zip[T1, T2, T3, T4, S](
      v1: T1 > S,
      v2: T2 > S,
      v3: T3 > S,
      v4: T4 > S
  ): (T1, T2, T3, T4) > S =
    v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))

  implicit class KyoOps[+T, -S](v: T > S) {
    def map[U, S2](f: T => U > S2): U > (S with S2) =
      flatMap(f)
    def flatMap[U, S2](f: T => U > S2): U > (S with S2) =
      kyo.core.transform(v)(f)
    def withFilter(f: T => Boolean): T > S =
      kyo.core.withFilter(v)(f)
    def unit: Unit > S =
      map(_ => ())
  }
}
