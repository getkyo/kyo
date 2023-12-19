package kyo

import kyo.aborts._
import kyo.layers._

object options {

  type Options >: Options.Effects <: Options.Effects

  object Options {

    type Effects = Aborts[Option[Nothing]]

    private val aborts = Aborts[Option[Nothing]]
    private val none   = aborts.fail(None)

    val empty: Nothing < Options = none

    def apply[T](v: T): T < Options =
      if (v == null)
        empty
      else
        v

    def get[T, S](v: Option[T] < S): T < (Options with S) =
      v.map {
        case Some(v) => v
        case None    => empty
      }

    def getOrElse[T, S1, S2](v: Option[T] < S1, default: => T < S2): T < (S1 with S2) =
      v.map {
        case None    => default
        case Some(v) => v
      }

    def run[T, S](v: T < (Options with S))(implicit f: Flat[T < (Options with S)]): Option[T] < S =
      aborts.run[T, S](v).map {
        case Left(e)  => None
        case Right(v) => Some(v)
      }

    def orElse[T, S](l: (T < (Options with S))*)(implicit
        f: Flat[T < (Options with S)]
    ): T < (Options with S) =
      l.toList match {
        case Nil => Options.empty
        case h :: t =>
          run[T, S](h).map {
            case None => orElse[T, S](t: _*)
            case v    => get(v)
          }
      }

    def layer[Se](onEmpty: => Nothing < Se): Layer[Options, Se] =
      new Layer[Options, Se] {
        override def run[T, S](effect: T < (Options with S))(implicit
            fl: Flat[T < (Options with S)]
        ): T < (S with Se) =
          Options.run[T, S](effect).map {
            case None    => onEmpty
            case Some(t) => t
          }
      }
  }
}
