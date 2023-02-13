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
    def get[T](v: Option[T]): T > Options =
      v > Options

    def run[T, S](v: T > (S | Options)): Option[T] > S =
      v < Options

    def orElse[T, S](l: (T > (S | Options))*): T > (S | Options) =
      l.toList match {
        case Nil => Options.empty
        case h :: t =>
          run(h) {
            case None => orElse(t: _*)
            case v    => v > Options
          }
      }
  }
  val Options = new Options

  /*inline(2)*/
  given ShallowHandler[Option, Options] with {
    def pure[T](v: T) =
      Option(v)
    def apply[T, U, S](
        m: Option[T],
        f: T => U > (S | Options)
    ): U > (S | Options) =
      m match {
        case None    => Options.empty
        case Some(v) => f(v)
      }
  }
}
