package kyo.concurrent.scheduler

import kyo.core._
import kyo.frames._
import kyo.ios._

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.control.NoStackTrace

import IOPromise._

private[kyo] class IOPromise[T] extends AtomicReference[(T > IOs) | Pending[T]](Pending()) {

  /*inline(2)*/
  def isDone(): Boolean = !get().isInstanceOf[Pending[_]]

  /*inline(2)*/
  def interrupts(p: IOPromise[_]): Unit =
    onComplete { _ =>
      p.interrupt()
    }

  /*inline(2)*/
  def interrupt()(using frame: Frame["interrupt"]): Boolean =
    complete(IOs(throw Interrupted(frame)))

  /*inline(2)*/
  def onComplete( /*inline(2)*/ f: T > IOs => Unit): Unit =
    @tailrec def loop(): Unit =
      get() match {
        case p: Pending[T] @unchecked =>
          if (!compareAndSet(p, p.add(f)))
            loop()
        case v =>
          f(v.asInstanceOf[T > IOs])
      }
    loop()

  protected def onComplete(): Unit = {}

  /*inline(2)*/
  def complete(v: T > IOs): Boolean =
    @tailrec def loop(): Boolean =
      get() match {
        case p: Pending[T] @unchecked =>
          if (!compareAndSet(p, v)) {
            loop()
          } else {
            onComplete()
            p.flush(v)
            true
          }
        case _ =>
          false
      }
    loop()

  /*inline(2)*/
  def block(): T =
    get() match {
      case p: Pending[T] @unchecked =>
        val b = new CountDownLatch(1) with (T > IOs => Unit) with (() => T > IOs) {
          private[this] var result: T > IOs = null.asInstanceOf[T]
          def apply(v: T > IOs) =
            result = v
            countDown()
          def apply() = result
        }
        onComplete(b)
        b.await()
        IOs.run(b())
      case v =>
        v.asInstanceOf[T]
    }
}

private[kyo] object IOPromise {
  case class Interrupted(frame: Frame[String]) extends NoStackTrace
  sealed trait Pending[+T] {
    import Pending._
    /*inline(2)*/
    def flush[B >: T](v: B > IOs): Unit =
      var c: Pending[B] = this
      while (c ne Empty) {
        val w = c.asInstanceOf[Waiter[B]]
        w(v)
        c = w.tail
      }
    /*inline(2)*/
    def add( /*inline(2)*/ f: T > IOs => Unit): Pending[T] =
      new Waiter(this) {
        def apply(v: T > IOs) = f(v)
      }
  }
  object Pending {
    def apply[T](): Pending[T] = Empty
    case object Empty extends Pending[Nothing]
    abstract class Waiter[T](val tail: Pending[T]) extends Pending[T] {
      def apply(v: T > IOs): Unit
    }
  }
}
