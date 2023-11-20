package kyo.concurrent

import kyo._
import scala.annotation.implicitNotFound
import kyo._
import kyo.sums._
import kyo.envs._
import izumi.reflect._
import kyo.core._
import kyo.lists._
import kyo.ios._
import kyo.concurrent.fibers._

object joins {

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

  abstract class Joiner[E] { self =>

    type M[T]

    def one[T, S, S2](
        l: T > (E with S)
    )(
        f: M[T] > S => M[T] > S2
    ): T > (E with S2)

    def seq[T, S, S2](
        l: Seq[T > (E with S)]
    )(
        f: Seq[M[T] > S] => Seq[M[T]] > S2
    ): Seq[T] > (E with S2)

    def andThen[E2](other: Joiner[E2]): Joiner[E with E2] = {
      new Joiner[E with E2] {
        type M[T] = self.M[other.M[T]]

        def one[T, S, S2](
            l: T > (E with E2 with S)
        )(
            f: self.M[other.M[T]] > S => self.M[other.M[T]] > S2
        ) =
          other.one[T, E with S, E with S2](l)(self.one[other.M[T], S, E with S2](_)(f))

        def seq[T, S, S2](
            l: Seq[T > (E with E2 with S)]
        )(
            f: Seq[self.M[other.M[T]] > S] => Seq[self.M[other.M[T]]] > S2
        ) =
          other.seq[T, E with S, E with S2](l)(self.seq[other.M[T], S, E with S2](_)(f))
      }
    }
  }

  object Joiner {

    implicit val identity: Joiner[Any] =
      new Joiner[Any] {
        type M[T] = T

        def one[T, S, S2](l: T > (Any with S))(f: M[T] > S => T > S2) =
          f(l)

        def seq[T, S, S2](l: Seq[T > (Any with S)])(f: Seq[M[T] > S] => Seq[T] > S2) =
          f(l)
      }

    implicit def envs[E](implicit tag: Tag[E]): Joiner[Envs[E]] =
      new Joiner[Envs[E]] {
        type M[T] = T
        val envs = Envs[E]

        def one[T, S, S2](
            l: T > (Envs[E] with S)
        )(
            f: T > S => T > S2
        ) =
          envs.get.map { e =>
            f(envs.run[T, S](e)(l))
          }

        def seq[T, S, S2](
            l: Seq[T > (Envs[E] with S)]
        )(
            f: Seq[T > S] => Seq[T] > S2
        ) =
          envs.get.map { e =>
            f(l.map(envs.run[T, S](e)))
          }
      }

    implicit def sums[V](
        implicit
        tag: Tag[V],
        summer: Summer[V]
    ): Joiner[Sums[V]] = {
      new Joiner[Sums[V]] {
        val sums = Sums[V]
        type M[T] = (T, V)

        def one[T, S, S2](
            l: T > (Sums[V] with S)
        )(
            f: (T, V) > S => (T, V) > S2
        ) =
          sums.get.map { st =>
            f(sums.run[T, S](st)(l)).map {
              case (t, v) =>
                sums.add(v).map(_ => t)
            }
          }

        def seq[T, S, S2](
            l: Seq[T > (Sums[V] with S)]
        )(
            f: Seq[(T, V) > S] => Seq[(T, V)] > S2
        ) =
          sums.get.map { st =>
            f(l.map(sums.run[T, S](st))).map { rl =>
              Lists.traverse(rl.toList) {
                case (t, v) =>
                  sums.add(v).map(_ => t)
              }
            }
          }
      }
    }
  }

}
