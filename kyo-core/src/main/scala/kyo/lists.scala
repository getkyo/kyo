package kyo

import java.nio.channels.AsynchronousFileChannel
import java.nio.file.Paths
import scala.util._

import core._

object lists {

  final class Lists private[lists] () extends Effect[List]
  val Lists = new Lists

  inline given DeepHandler[List, Lists] =
    new DeepHandler[List, Lists] {
      def pure[T](v: T): List[T] = List(v)
      override def map[T, U](m: List[T], f: T => U): List[U] =
        m.map(f)
      def flatMap[T, U](m: List[T], f: T => List[U]): List[U] =
        m.flatMap(f)
    }
}

