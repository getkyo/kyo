package kyo

import kyo._
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

    def run[T, S](v: T > (Aspects with S)): T > S =
      envs.run[T, S](Map.empty)(v)

    def init[T, U, S]: Aspect[T, U, S] =
      init(new Cut[T, U, S] {
        def apply[S2](v: T > S2)(f: T => U > (Aspects with S)) =
          v.map(f)
      })

    def init[T, U, S](default: Cut[T, U, S]): Aspect[T, U, S] =
      new Aspect[T, U, S](default)
  }

  trait Cut[T, U, S] {
    def apply[S2](v: T > S2)(f: T => U > (Aspects with S)): U > (Aspects with S with S2)

    def andThen(other: Cut[T, U, S]): Cut[T, U, S] =
      new Cut[T, U, S] {
        def apply[S2](v: T > S2)(f: T => U > (Aspects with S)): U > (Aspects with S with S2) =
          Cut.this(v)(other(_)(f))
      }
  }

  final class Aspect[T, U, S] private[aspects] (default: Cut[T, U, S]) extends Cut[T, U, S] {

    def apply[S2](v: T > S2)(f: T => U > (Aspects with S)): U > (Aspects with S with S2) =
      envs.get.map { map =>
        map.get(this) match {
          case Some(a: Cut[T, U, S] @unchecked) =>
            envs.run[U, Aspects with S with S2](map - this) {
              a(v)(f)
            }
          case _ =>
            default(v)(f)
        }
      }

    def sandbox[S](v: T > S): T > (Aspects with S) =
      envs.get.map { map =>
        map.get(this) match {
          case Some(a: Cut[T, U, S] @unchecked) =>
            envs.run[T, (Aspects with S)](map - this) {
              v
            }
          case _ =>
            v
        }
      }

    def let[V, S2](a: Cut[T, U, S])(v: V > (Aspects with S2)): V > (Aspects with S with S2) =
      envs.get.map { map =>
        val cut =
          map.get(this) match {
            case Some(b: Cut[T, U, S] @unchecked) =>
              b.andThen(a)
            case _ =>
              a
          }
        envs.run[V, Aspects with S with S2](map + (this -> cut))(v)
      }
  }
  private val envs = Envs[Map[Aspect[_, _, _], Cut[_, _, _]]]
}
