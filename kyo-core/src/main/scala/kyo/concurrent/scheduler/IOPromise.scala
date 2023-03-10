package kyo.concurrent.scheduler

import kyo.core._
import kyo.frames._
import kyo.ios._
import kyo.loggers.Loggers

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.util.control.NoStackTrace
import scala.util.control.NonFatal

import IOPromise._

private[kyo] class IOPromise[T](s: State[T])
    extends AtomicReference(s) {

  def this() = this(Pending())

  /*inline(2)*/
  def isDone(): Boolean =
    @tailrec def loop(promise: IOPromise[T]): Boolean =
      promise.get() match {
        case p: Pending[T] @unchecked =>
          false
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case _ =>
          true
      }
    loop(this)

  /*inline(2)*/
  def interrupts(p: IOPromise[_])(using frame: Frame["interrupt"]): Unit =
    onComplete { _ =>
      p.interrupt("")
    }

  /*inline(2)*/
  def interrupt(reason: String)(using frame: Frame["interrupt"]): Boolean =
    @tailrec def loop(promise: IOPromise[T]): Boolean =
      promise.get() match {
        case p: Pending[T] @unchecked =>
          promise.complete(p, IOs(throw Interrupted(reason, frame))) || loop(promise)
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case _ =>
          false
      }
    loop(this)

  private def compress(): IOPromise[T] =
    @tailrec def loop(p: IOPromise[T]): IOPromise[T] =
      p.get() match {
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case _ =>
          p
      }
    loop(this)

  private def merge(p: Pending[T]): Unit =
    @tailrec def loop(promise: IOPromise[T]): Unit =
      promise.get() match {
        case p2: Pending[T] @unchecked =>
          if (!promise.compareAndSet(p2, p2.merge(p)))
            loop(promise)
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case v =>
          p.flush(v.asInstanceOf[T > IOs])
      }
    loop(this)

  def become(other: IOPromise[T]): Boolean =
    @tailrec def loop(other: IOPromise[T]): Boolean =
      get() match {
        case p: Pending[T] @unchecked =>
          if (compareAndSet(p, Linked(other))) {
            other.merge(p)
            true
          } else {
            loop(other)
          }
        case _ =>
          false
      }
    loop(other.compress())

  /*inline(2)*/
  def onComplete( /*inline(2)*/ f: T > IOs => Unit): Unit =
    @tailrec def loop(promise: IOPromise[T]): Unit =
      promise.get() match {
        case p: Pending[T] @unchecked =>
          if (!promise.compareAndSet(p, p.add(f)))
            loop(promise)
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case v =>
          try f(v.asInstanceOf[T > IOs])
          catch {
            case ex if NonFatal(ex) =>
              log.error("uncaught exception", ex)
          }
      }
    loop(this)

  protected def onComplete(): Unit = {}

  private inline def complete(p: Pending[T], v: T > IOs): Boolean =
    compareAndSet(p, v) && {
      onComplete()
      p.flush(v)
      true
    }

  /*inline(2)*/
  def complete(v: T > IOs): Boolean =
    @tailrec def loop(): Boolean =
      get() match {
        case p: Pending[T] @unchecked =>
          complete(p, v) || loop()
        case _ =>
          false
      }
    loop()

  def block(): T =
    def loop(promise: IOPromise[T]): T =
      promise.get() match {
        case _: Pending[T] @unchecked =>
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
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case v =>
          v.asInstanceOf[T]
      }
    Scheduler.flush()
    loop(this)
}

private[kyo] object IOPromise {

  private val log = Loggers(getClass())

  type State[T] = (T > IOs) | Pending[T] | Linked[T]

  case class Interrupted(reason: String, frame: Frame[String])
      extends RuntimeException(s"reason=$reason, frame=$frame")
      with NoStackTrace

  case class Linked[T](p: IOPromise[T])

  abstract class Pending[T] { self =>

    def run(v: T > IOs): Pending[T]

    inline def add(inline f: T > IOs => Unit): Pending[T] =
      new Pending[T] {
        def run(v: T > IOs) = {
          try f(v)
          catch {
            case ex if NonFatal(ex) =>
              IOs.run(log.error("uncaught exception", ex))
          }
          self
        }
      }

    def merge(tail: Pending[T]): Pending[T] =
      @tailrec def loop(p: Pending[T], v: T > IOs): Pending[T] =
        p match {
          case Pending.Empty =>
            tail
          case p: Pending[T] =>
            loop(p.run(v), v)
        }
      new Pending[T] {
        def run(v: T > IOs) =
          loop(self, v)
      }

    def flush(v: T > IOs): Unit =
      @tailrec def loop(p: Pending[T]): Unit =
        p match {
          case Pending.Empty => ()
          case p: Pending[T] =>
            loop(p.run(v))
        }
      loop(this)
  }

  object Pending {
    def apply[T](): Pending[T] = Empty.asInstanceOf[Pending[T]]
    case object Empty extends Pending[Nothing] {
      def run(v: Nothing > IOs)                  = this
      override def merge(tail: Pending[Nothing]) = tail
    }
  }
}
