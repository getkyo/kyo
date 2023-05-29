package kyo

import locals._
import envs._
import kyo.clocks.Clocks
import kyo.consoles.Consoles
import kyo.concurrent.fibers.Fibers
import kyo.ios.IOs
import kyo.randoms.Randoms
import kyo.resources.Resources
import kyo.concurrent.timers.Timers

object aspects {

  type Aspects = Envs[Map[Aspect[_, _, _], Cut[_, _, _]]]

  object Aspects {

    def run[T, S](v: => T > (Aspects with S)): T > S =
      envs.run(Map.empty)(v)

    def init[T, U, S]: Aspect[T, U, S] =
      init(new Cut[T, U, S] {
        def apply[S2, S3](v: T > S2)(f: T => U > (Aspects with S3))
            : U > (S with S2 with S3 with Aspects) =
          v.map(f)
      })

    def init[T, U, S](default: Cut[T, U, S]): Aspect[T, U, S] =
      new Aspect[T, U, S](default)
  }

  trait Cut[T, U, S1] {
    def apply[S2, S3](v: T > S2)(f: T => U > (Aspects with S3))
        : U > (S1 with S2 with S3 with Aspects)

    def andThen(other: Cut[T, U, S1]): Cut[T, U, S1] =
      new Cut[T, U, S1] {
        def apply[S2, S3](v: T > S2)(f: T => U > (Aspects with S3))
            : U > (S1 with S2 with S3 with Aspects) =
          Cut.this(v)(other(_)(f))
      }
  }

  final class Aspect[T, U, S1] private[aspects] (default: Cut[T, U, S1]) extends Cut[T, U, S1] {

    def apply[S2, S3](v: T > S2)(f: T => U > (Aspects with S3))
        : U > (S1 with S2 with S3 with Aspects) =
      envs.get.map { map =>
        map.get(this) match {
          case Some(a: Cut[T, U, S1] @unchecked) =>
            envs.run(map - this) {
              a(v)(f)
            }
          case _ =>
            default(v)(f)
        }
      }

    def sandbox[S](v: T > S): T > (Aspects with S) =
      envs.get.map { map =>
        map.get(this) match {
          case Some(a: Cut[T, U, S1] @unchecked) =>
            envs.run(map - this) {
              v
            }
          case _ =>
            v
        }
      }

    def let[V, S2](a: Cut[T, U, S1])(v: V > (Aspects with S2)): V > (S1 with S2 with Aspects) =
      envs.get.map { map =>
        val cut =
          map.get(this) match {
            case Some(b: Cut[T, U, S1] @unchecked) =>
              new Cut[T, U, S1] {
                def apply[S3, S4](v: T > S3)(f: T => U > (S4 with Aspects)) =
                  b(v) { v =>
                    envs.run(map) {
                      a(v)(f)
                    }
                  }
              }
            case _ =>
              a
          }
        envs.run(map + (this -> cut))(v)
      }
  }
  private val envs = Envs[Map[Aspect[_, _, _], Cut[_, _, _]]]
}
