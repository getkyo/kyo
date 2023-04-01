package kyo

import kyo.loggers.Loggers

import java.io.Closeable
import java.util.concurrent.ThreadLocalRandom
import scala.Conversion
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.runtime.AbstractFunction0
import scala.util.NotGiven
import scala.util.Try
import scala.util.control.NonFatal

import core._
import tries._
import options._
import frames._
import locals._

object ios {

  trait Preempt extends Safepoint[IOs] {
    def ensure(f: () => Unit): Unit
    def apply[T, S](v: => T > (S | IOs)) =
      IOs(v)
  }
  object Preempt {
    val never: Preempt =
      new Preempt {
        def ensure(f: () => Unit) = ()
        def apply()               = false
      }
  }

  opaque type IO[+T] = T

  private[kyo] abstract class KyoIO[T, S]
      extends Kyo[IO, IOs, Unit, T, (S | IOs)] {
    final def value  = ()
    final def effect = ios.IOs
  }

  final class IOs private[ios] () extends Effect[IO] {

    private[this] val log = Loggers(getClass())

    val unit: Unit > IOs = ()

    /*inline(3)*/
    def value[T](v: T): T > IOs = v

    /*inline(3)*/
    def attempt[T, S](v: => T > S): Try[T] > S =
      Tries(v) < Tries

    private[kyo] /*inline(3)*/ def ensure[T, S]( /*inline(3)*/ f: => Unit > IOs)(v: => T > S)(using
        /*inline(3)*/ fr: Frame["IOs.ensure"]
    ): T > (S | IOs) =
      type M2[_]
      type E2 <: Effect[M2]
      lazy val run: Unit =
        try IOs.run(f)
        catch {
          case ex if NonFatal(ex) =>
            log.error(s"IOs.ensure function failed at frame $fr", ex)
        }
      val ensure = () => run
      def ensureLoop(v: T > (S | IOs)): T > (S | IOs) =
        v match {
          case kyo: Kyo[M2, E2, Any, T, S | IOs] @unchecked =>
            new KyoCont[M2, E2, Any, T, S | IOs](kyo) {
              def apply() = run
              def frame   = fr
              def apply(v: Any, s: Safepoint[E2], l: Locals.State) =
                s match {
                  case s: Preempt =>
                    s.ensure(ensure)
                  case _ =>
                }
                ensureLoop(kyo(v, s, l))
            }
          case _ =>
            IOs(run)(_ => v)
        }
      ensureLoop(v)

    /*inline(3)*/
    def apply[T, S](
        /*inline(3)*/ f: => T > (S | IOs)
    )(using /*inline(3)*/ fr: Frame["IOs"]): T > (S | IOs) =
      new KyoIO[T, S] {
        def frame = fr
        def apply(v: Unit, s: Safepoint[IOs], l: Locals.State) =
          f
      }

    /*inline(3)*/
    def run[T](v: T > IOs): T =
      val safepoint = Safepoint.noop[IOs]
      @tailrec def runLoop(v: T > IOs): T =
        v match {
          case kyo: Kyo[IO, IOs, Unit, T, IOs] @unchecked =>
            runLoop(kyo((), safepoint, Locals.State.empty))
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
            lazyRunLoop(kyo((), safepoint, Locals.State.empty))
          case kyo: Kyo[M2, E2, Any, T, S | IOs] @unchecked =>
            new KyoCont[M2, E2, Any, T, S](kyo) {
              def frame = fr
              def apply(v: Any, s: Safepoint[E2], l: Locals.State) =
                lazyRunLoop(kyo(v, s, l))
            }
          case _ =>
            v.asInstanceOf[T]
        }

      lazyRunLoop(v)
  }
  val IOs: IOs = new IOs
}
