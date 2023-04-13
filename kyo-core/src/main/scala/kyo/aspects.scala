package kyo

import locals._
import core._
import envs._
import kyo.clocks.Clocks
import kyo.consoles.Consoles
import kyo.concurrent.fibers.Fibers
import kyo.ios.IOs
import kyo.randoms.Randoms
import kyo.resources.Resources
import kyo.concurrent.timers.Timers

object aspects {

  opaque type Aspects = Envs[Map[Aspect[_, _, _], Cut[_, _, _]]]

  object Aspects {

    def run[T, S](v: T > (S | Aspects)): T > S =
      envs.let(Map.empty)(v)

    def init[T, U, S1]: Aspect[T, U, S1] =
      new Aspect[T, U, S1]
  }

  trait Cut[T, U, S1] {
    def apply[S2, S3](v: T > S2)(f: T => U > (S3 | Aspects)): U > (S1 | S2 | S3 | Aspects)
  }

  final class Aspect[T, U, S1] private[aspects] () extends Cut[T, U, S1] {

    def apply[S2, S3](v: T > S2)(f: T => U > (S3 | Aspects)): U > (S1 | S2 | S3 | Aspects) =
      envs.get.map { map =>
        map.get(this) match {
          case Some(a: Cut[T, U, S1] @unchecked) =>
            envs.let(map - this) {
              a(v)(f)
            }
          case _ =>
            v.map(f)
        }
      }

    def let[V, S2](a: Cut[T, U, S1])(v: V > (S2 | Aspects)): V > (S1 | S2 | Aspects) =
      envs.get.map { map =>
        val cut =
          map.get(this) match {
            case Some(b: Cut[T, U, S1] @unchecked) =>
              new Cut[T, U, S1] {
                def apply[S3, S4](v: T > S3)(f: T => U > (S4 | Aspects)) =
                  b(v) { v =>
                    envs.let(map) {
                      a(v)(f)
                    }
                  }
              }
            case _ =>
              a
          }
        envs.let(map + (this -> cut))(v)
      }
  }
  private val envs = Envs[Map[Aspect[_, _, _], Cut[_, _, _]]]
}
