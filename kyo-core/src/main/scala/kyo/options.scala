package kyo

import core._

object options {

  final class Options private[options] () extends Effect[Option] {
    inline def apply[T](v: T): T > Options =
      if (v == null)
        Option.empty[T] > Options
      else
        v
  }
  val Options = new Options

  inline given ShallowHandler[Option, Options] =
    new ShallowHandler[Option, Options] {
      def pure[T](v: T) =
        Option(v)
      def apply[T, U, S](
          m: Option[T],
          f: T => U > (S | Options)
      ): U > (S | Options) =
        m match {
          case None    => Option.empty[U] > Options
          case Some(v) => f(v)
        }
    }
}
