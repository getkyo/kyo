package kyo

import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.NotGiven

import core._
import tries._
import options._
import frames._
import scala.runtime.AbstractFunction0
import scala.util.control.NonFatal
import scala.Conversion
import java.io.Closeable
import scala.util.Try

object ios {

  trait Preempt extends Safepoint[IOs] {

    def ensure(f: Unit > IOs): Unit
    def apply[T, S](v: => T > (S | IOs)) =
      IOs(v)
  }
  object Preempt {
    val never: Preempt =
      new Preempt {
        def ensure(f: Unit > IOs) = ()
        def apply()               = false
      }
  }

  opaque type IO[+T] = T

  private[kyo] abstract class KyoIO[T, S]
      extends Kyo[IO, IOs, Unit, T, (S | IOs)] {
    def value  = ()
    def effect = ios.IOs
  }

  private[kyo] trait Ensure

  final class IOs private[ios] () extends Effect[IO] {

    val unit: Unit > IOs = ()

    /*inline(3)*/
    def value[T](v: T): T > IOs = v

    /*inline(3)*/
    def attempt[T, S](v: => T > S): Try[T] > S =
      v < Tries

    /*inline(3)*/
    def collect[T](l: List[T > IOs]): List[T] > IOs =
      def collectLoop(l: List[T > IOs], acc: List[T]): List[T] > IOs =
        l match {
          case Nil          => acc.reverse
          case head :: tail => head(v => collectLoop(tail, v :: acc))
        }
      collectLoop(l, Nil)

    private[kyo] /*inline(3)*/ def ensure[T, S](f: => Unit > IOs)(v: => T > S)(using
        /*inline(3)*/ fr: Frame["IOs.ensure"]
    ): T > (S | IOs) =
      type M2[_]
      type E2 <: Effect[M2]
      def ensureLoop(v: T > (S | IOs)): T > (S | IOs) =
        v match {
          case kyo: Kyo[M2, E2, Any, T, S | IOs] @unchecked =>
            new KyoCont[M2, E2, Any, T, S | IOs](kyo) with Ensure {
              def frame = fr
              def apply(v: Any, s: Safepoint[E2]) =
                s match {
                  case s: Preempt =>
                    s.ensure(IOs(f))
                    kyo(v, s)
                  case _ =>
                    ensureLoop(kyo(v, s))
                }
            }
          case _ =>
            IOs(f)(_ => v)
        }
      ensureLoop(v)

    /*inline(3)*/
    def apply[T, S](
        /*inline(3)*/ f: => T > (S | IOs)
    )(using /*inline(3)*/ fr: Frame["IOs"]): T > (S | IOs) =
      new KyoIO[T, S] {
        def frame = fr
        def apply(v: Unit, s: Safepoint[IOs]) =
          f
      }

    /*inline(3)*/
    def run[T](v: T > IOs): T =
      val safepoint = Safepoint.noop[IOs]
      @tailrec def runLoop(v: T > IOs): T =
        v match {
          case kyo: Kyo[IO, IOs, Unit, T, IOs] @unchecked =>
            runLoop(kyo((), safepoint))
          case _ =>
            v.asInstanceOf[T]
        }

      runLoop(v)

    /*inline(3)*/
    def lazyRun[T, S](v: T > (S | IOs))(using /*inline(3)*/ fr: Frame["IOs.lazyRun"]): T > S =
      type M2[_]
      type E2 <: Effect[M2]
      @tailrec def lazyRunLoop(v: T > (S | IOs)): T > S =
        val safepoint = Safepoint.noop[IOs]
        v match {
          case kyo: Kyo[IO, IOs, Unit, T, S | IOs] @unchecked if (kyo.effect eq IOs) =>
            lazyRunLoop(kyo((), safepoint))
          case kyo: Kyo[M2, E2, Any, T, S | IOs] @unchecked =>
            new KyoCont[M2, E2, Any, T, S](kyo) {
              def frame = fr
              def apply(v: Any, s: Safepoint[E2]) =
                lazyRunLoop(kyo(v, s))
            }
          case _ =>
            v.asInstanceOf[T]
        }

      lazyRunLoop(v)
  }
  val IOs: IOs = new IOs
}
