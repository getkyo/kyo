package kyo

import java.util.ArrayList
import java.util.HashMap
import scala.annotation.tailrec
import scala.collection.MapView
import scala.collection.immutable
import scala.util.Try

import core._

object batches {

  sealed trait Batch[T] {
    def run: List[T]
  }

  final class Batches private[batches] () extends Effect[Batch] {
    inline def foreach[T, U](inline l: List[T]): T > Batches =
      Batch.Foreach(l) > Batches
    inline def forall[T, U](f: List[T] => List[U]): T => U > Batches =
      v => Batch.Call(v, f) > Batches
  }
  val Batches = new Batches

  inline given DeepHandler[Batch, Batches] with {
    def pure[T](v: T) =
      Batch.Foreach(List(v))
    def flatMap[T, U](
        m: Batch[T],
        cont: T => Batch[U]
    ): Batch[U] =
      Batch.Continue(m, cont)
  }

  private object Batch {
    case class Foreach[T](l: List[T]) extends Batch[T] {
      def run = l
    }
    case class Call[T, U](v: T, f: List[T] => List[U]) extends Batch[U] {
      def run = f(List(v))
    }
    case class Continue[T, U](v: Batch[T], f: T => Batch[U]) extends Batch[U] {
      // TODO refactor/optimize
      def run =
        val a =
          v.run.zipWithIndex.flatMap { (v, idx) =>
            def loop(b: Batch[U])
                : List[(Unit |(List[Any] => List[Any]), (Any, Int, Any => Batch[U]))] =
              (normalize(b): @unchecked) match {
                case Foreach(l) =>
                  l.map(() -> (_, idx, v => Foreach[U](List(v.asInstanceOf[U]))))
                case Call[Any, U](v, f) =>
                  List(f -> (v, idx, v => Foreach[U](List(v.asInstanceOf[U]))))
                case Continue(Call[Any, Any](v, f), g) =>
                  List(f -> (v, idx, g))
                case Continue(Foreach(l), f: (Any => Batch[U]) @unchecked) =>
                  l.flatMap(v => loop(f(v)))
              }
            loop(f(v))
          }
        val b = a.groupBy(_._1).mapValues(_.map(_._2)).toMap
        val c: List[(Any, Int, Any => Batch[U])] =
          b.toList.flatMap {
            case ((), l) =>
              l.asInstanceOf[List[(Any, Int, Any => Batch[U])]]
            case (f: (List[Any] => List[Any]), l) =>
              f(l.map(_._1)).zip(l.map(_._2)).zip(l.map(_._3))
                .collect { case ((a, b), c) =>
                  (a, b, c)
                }
          }
        c.sortBy(_._2)
          .flatMap { case (a, b, c) =>
            c(a).run
          }
    }

    @tailrec def normalize[T](b: Batch[T]): Batch[T] =
      b match {
        case Continue(Continue(v, f), g) =>
          normalize(Continue(v, f.andThen(Continue(_, g))))
        case b =>
          b
      }
  }
}
