package kyo

import core._

object options {

  final class Options private[options] () extends Effect[Option] {
    private val none          = None > this
    def empty[T]: T > Options = none

    /*inline(2)*/
    def apply[T](v: T): T > Options =
      if (v == null)
        none
      else
        v

    /*inline(2)*/
    def get[T, S](v: Option[T] > S): T > (Options & S) =
      v > Options

    /*inline(2)*/
    def getOrElse[T, S1, S2](v: Option[T] > S1, default: => T > S2): T > (S1 & S2) =
      v.map {
        case None    => default
        case Some(v) => v
      }

    def run[T, S](v: T > (Options & S)): Option[T] > S =
      v < Options

    def orElse[T, S](l: (T > (Options & S))*): T > (Options & S) =
      l.toList match {
        case Nil => Options.empty
        case h :: t =>
          run(h).map {
            case None => orElse(t: _*)
            case v    => v > Options
          }
      }
  }
  val Options = new Options

  /*inline(2)*/
  given Handler[Option, Options] with {
    def pure[T](v: T) =
      Option(v)
    def apply[T, U, S](
        m: Option[T],
        f: T => U > (Options & S)
    ): U > (Options & S) =
      m match {
        case None    => Options.empty
        case Some(v) => f(v)
      }
  }
}
