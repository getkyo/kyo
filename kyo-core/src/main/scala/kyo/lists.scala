package kyo

import core._

object lists {

  final class Lists private[lists] () extends Effect[List]
  val Lists = new Lists

  /*inline(1)*/
  given DeepHandler[List, Lists] with {
    def pure[T](v: T): List[T] = List(v)
    override def map[T, U](m: List[T], f: T => U): List[U] =
      m.map(f)
    def flatMap[T, U](m: List[T], f: T => List[U]): List[U] =
      m.flatMap(f)
  }
}
