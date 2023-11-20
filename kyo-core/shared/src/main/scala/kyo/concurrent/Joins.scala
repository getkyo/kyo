package kyo.concurrent

import kyo._
import scala.annotation.implicitNotFound

trait Joins[E] {

  def race[T](l: Seq[T > E]): T > E

  def await[T](l: Seq[T > E]): Unit > E

  def parallel[T](l: Seq[T > E]): Seq[T] > E

  def race[T](
      v1: => T > E,
      v2: => T > E
  ): T > E =
    race(List(v1, v2))

  def race[T](
      v1: => T > E,
      v2: => T > E,
      v3: => T > E
  ): T > E =
    race(List(v1, v2, v3))

  def race[T](
      v1: => T > E,
      v2: => T > E,
      v3: => T > E,
      v4: => T > E
  ): T > E =
    race(List(v1, v2, v3, v4))

  def await[T](
      v1: => T > E,
      v2: => T > E
  ): Unit > E =
    await(List(v1, v2))

  def await[T](
      v1: => T > E,
      v2: => T > E,
      v3: => T > E
  ): Unit > E =
    await(List(v1, v2, v3))

  def await[T](
      v1: => T > E,
      v2: => T > E,
      v3: => T > E,
      v4: => T > E
  ): Unit > E =
    await(List(v1, v2, v3, v4))

  def parallel[T1, T2](
      v1: => T1 > E,
      v2: => T2 > E
  ): (T1, T2) > E =
    parallel(List(v1, v2)).map(s =>
      (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2])
    )

  def parallel[T1, T2, T3](
      v1: => T1 > E,
      v2: => T2 > E,
      v3: => T3 > E
  ): (T1, T2, T3) > E =
    parallel(List(v1, v2, v3)).map(s =>
      (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
    )

  def parallel[T1, T2, T3, T4](
      v1: => T1 > E,
      v2: => T2 > E,
      v3: => T3 > E,
      v4: => T4 > E
  ): (T1, T2, T3, T4) > E =
    parallel(List(v1, v2, v3, v4)).map(s =>
      (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3], s(3).asInstanceOf[T4])
    )
}
