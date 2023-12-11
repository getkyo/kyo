package kyo

import kyo._
import locals._
import kyo.clocks.Clocks
import kyo.consoles.Consoles
import kyo.concurrent.fibers.Fibers
import kyo.ios.IOs
import kyo.randoms.Randoms
import kyo.resources.Resources
import kyo.concurrent.timers.Timers

object aspects {

  private val local = Locals.init(Map.empty[Aspect[_, _, _], Cut[_, _, _]])

  object Aspects {

    def init[T, U, S]: Aspect[T, U, S] =
      init(new Cut[T, U, S] {
        def apply[S2](v: T < S2)(f: T => U < (IOs with S)) =
          v.map(f)
      })

    def init[T, U, S](default: Cut[T, U, S]): Aspect[T, U, S] =
      new Aspect[T, U, S](default)
  }

  abstract class Cut[T, U, S] {
    def apply[S2](v: T < S2)(f: T => U < (IOs with S)): U < (IOs with S with S2)

    def andThen(other: Cut[T, U, S]): Cut[T, U, S] =
      new Cut[T, U, S] {
        def apply[S2](v: T < S2)(f: T => U < (IOs with S)) =
          Cut.this(v)(other(_)(f))
      }
  }

  final class Aspect[T, U, S] private[aspects] (default: Cut[T, U, S]) extends Cut[T, U, S] {

    def apply[S2](v: T < S2)(f: T => U < (IOs with S)) =
      local.get.map { map =>
        map.get(this) match {
          case Some(a: Cut[T, U, S] @unchecked) =>
            local.let(map - this) {
              a(v)(f)
            }
          case _ =>
            default(v)(f)
        }
      }

    def sandbox[S](v: T < S): T < (IOs with S) =
      local.get.map { map =>
        map.get(this) match {
          case Some(a: Cut[T, U, S] @unchecked) =>
            local.let(map - this) {
              v
            }
          case _ =>
            v
        }
      }

    def let[V, S2](a: Cut[T, U, S])(v: V < (IOs with S2)): V < (IOs with S with S2) =
      local.get.map { map =>
        val cut =
          map.get(this) match {
            case Some(b: Cut[T, U, S] @unchecked) =>
              b.andThen(a)
            case _ =>
              a
          }
        local.let(map + (this -> cut))(v)
      }
  }
}
