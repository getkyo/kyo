package kyo

import kyo.logs._

import java.io.Closeable
import java.util.concurrent.ThreadLocalRandom
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.runtime.AbstractFunction0
import scala.util.Try
import scala.util.control.NonFatal

import kyo._
import core._
import core.internal._
import tries._
import options._
import locals._
import scala.annotation.implicitNotFound
import java.util.concurrent.atomic.AtomicReference
import kyo.locals.Locals.State

object ios {

  trait Preempt extends Safepoint[IO, IOs] {
    def ensure(f: () => Unit): Unit
    def remove(f: () => Unit): Unit
    def suspend[T, S](v: => T < S): T < (IOs with S) =
      IOs[T, S](v)
  }
  object Preempt {
    val never: Preempt =
      new Preempt {
        def ensure(f: () => Unit) = ()
        def remove(f: () => Unit) = ()
        def check()               = false
      }
  }

  type IO[+T] = T

  private[kyo] abstract class KyoIO[T, S]
      extends Kyo[IO, IOs, Unit, T, (IOs with S)] {
    final def value  = ()
    final def effect = ios.IOs
  }

  final class IOs private[ios] () extends Effect[IO, IOs] {

    val unit: Unit < IOs = ()

    def value[T](v: T): T < IOs = v

    def fail[T](ex: Throwable): T < IOs =
      IOs(throw ex)

    def fail[T](msg: String): T < IOs =
      fail(new Exception(msg))

    /*inline*/
    def apply[T, S](
        /*inline*/ f: => T < (IOs with S)
    ): T < (IOs with S) =
      new KyoIO[T, S] {
        def apply(v: Unit < (IOs with S), s: Safepoint[IO, IOs], l: Locals.State) =
          f
      }

    /*inline*/
    def run[T](v: T < IOs)(implicit f: Flat[T < IOs]): T = {
      val safepoint = Safepoint.noop[IO, IOs]
      @tailrec def runLoop(v: T < IOs): T =
        v match {
          case kyo: Kyo[IO, IOs, Unit, T, IOs] @unchecked =>
            require(kyo.effect == IOs, "Unhandled effect: " + kyo.effect)
            runLoop(kyo((), safepoint, Locals.State.empty))
          case _ =>
            v.asInstanceOf[T]
        }
      runLoop(v)
    }

    /*inline*/
    def runLazy[T, S](v: T < (IOs with S))(implicit f: Flat[T < (IOs with S)]): T < S = {
      def runLazyLoop(v: T < (IOs with S)): T < S = {
        val safepoint = Safepoint.noop[IO, IOs]
        v match {
          case kyo: Kyo[IO, IOs, Unit, T, S with IOs] @unchecked if (kyo.effect eq IOs) =>
            runLazyLoop(kyo((), safepoint, Locals.State.empty))
          case kyo: Kyo[MX, EX, Any, T, S with IOs] @unchecked =>
            new KyoCont[MX, EX, Any, T, S](kyo) {
              def apply(v: Any < S, s: Safepoint[MX, EX], l: Locals.State) =
                runLazyLoop(kyo(v, s, l))
            }
          case _ =>
            v.asInstanceOf[T]
        }
      }
      runLazyLoop(v)
    }

    object internal {

      abstract class Ensure
          extends AtomicReference[Any]
          with Function0[Unit] {

        protected def run: Unit < IOs

        def apply(): Unit =
          if (compareAndSet(null, ())) {
            try IOs.run(run)
            catch {
              case ex if NonFatal(ex) =>
                Logs.unsafe.error(s"IOs.ensure function failed", ex)
            }
          }
      }
    }

    /*inline*/
    def ensure[T, S]( /*inline*/ f: => Unit < IOs)(v: T < S): T < (IOs with S) = {
      val ensure = new internal.Ensure {
        def run = f
      }
      def ensureLoop(v: T < (IOs with S), p: Preempt): T < (IOs with S) =
        v match {
          case kyo: Kyo[MX, EX, Any, T, S with IOs] @unchecked =>
            new KyoCont[MX, EX, Any, T, S with IOs](kyo) {
              def apply(v: Any < (S with IOs), s: Safepoint[MX, EX], l: State) = {
                val np =
                  (s: Any) match {
                    case s: Preempt =>
                      s.ensure(ensure)
                      s
                    case _ =>
                      Preempt.never
                  }
                val v2 =
                  try kyo(v, s, l)
                  catch {
                    case ex if (NonFatal(ex)) =>
                      ensure()
                      throw ex
                  }
                ensureLoop(v2, np)
              }
            }
          case _ =>
            p.remove(ensure)
            IOs[T, S] {
              ensure()
              v
            }
        }
      ensureLoop(v, Preempt.never)
    }
  }
  val IOs: IOs = new IOs
}
