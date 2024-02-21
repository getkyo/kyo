package kyo.scheduler

import kyo._

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.AbstractQueuedSynchronizer
import scala.annotation.tailrec
import scala.util.control.NonFatal

import IOPromise._
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.Lock
import kyo.scheduler.Scheduler

private[kyo] class IOPromise[T](state: State[T])
    extends AtomicReference(state) {

  def this() = this(Pending())

  final def isDone(): Boolean = {
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
  }

  final def interrupts(p: IOPromise[_]): Unit =
    onComplete { _ =>
      p.interrupt()
    }

  final def interrupt(): Boolean = {
    @tailrec def loop(promise: IOPromise[T]): Boolean =
      promise.get() match {
        case p: Pending[T] @unchecked =>
          promise.complete(p, Fibers.interrupted) || loop(promise)
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case _ =>
          false
      }
    loop(this)
  }

  private def compress(): IOPromise[T] = {
    @tailrec def loop(p: IOPromise[T]): IOPromise[T] =
      p.get() match {
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case _ =>
          p
      }
    loop(this)
  }

  private def merge(p: Pending[T]): Unit = {
    @tailrec def loop(promise: IOPromise[T]): Unit =
      promise.get() match {
        case p2: Pending[T] @unchecked =>
          if (!promise.compareAndSet(p2, p2.merge(p)))
            loop(promise)
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case v =>
          p.flush(v.asInstanceOf[T < IOs])
      }
    loop(this)
  }

  final def become(other: IOPromise[T]): Boolean = {
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
  }

  final def onComplete(f: T < IOs => Unit): Unit = {
    @tailrec def loop(promise: IOPromise[T]): Unit =
      promise.get() match {
        case p: Pending[T] @unchecked =>
          if (!promise.compareAndSet(p, p.add(f)))
            loop(promise)
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case v =>
          try f(v.asInstanceOf[T < IOs])
          catch {
            case ex if NonFatal(ex) =>
              Logs.unsafe.error("uncaught exception", ex)
          }
      }
    loop(this)
  }

  protected def onComplete(): Unit = {}

  private def complete(p: Pending[T], v: T < IOs): Boolean =
    compareAndSet(p, v) && {
      onComplete()
      p.flush(v)
      true
    }

  final def complete(v: T < IOs): Boolean = {
    @tailrec def loop(): Boolean =
      get() match {
        case p: Pending[T] @unchecked =>
          complete(p, v) || loop()
        case _ =>
          false
      }
    loop()
  }

  final def block: T < IOs = {
    def loop(promise: IOPromise[T]): T < IOs =
      promise.get() match {
        case _: Pending[T] @unchecked =>
          IOs {
            Scheduler.flush()
            val b = new (T < IOs => Unit) with (() => T < IOs) {
              @volatile
              private var result: T < IOs = null.asInstanceOf[T < IOs]
              private val waiter          = Thread.currentThread()
              def apply(v: T < IOs) = {
                result = v
                LockSupport.unpark(waiter)
              }
              def apply() = {
                while (result == null) {
                  LockSupport.park()
                }
                result
              }
            }
            onComplete(b)
            b()
          }
        case l: Linked[T] @unchecked =>
          loop(l.p)
        case v =>
          v.asInstanceOf[T < IOs]
      }
    loop(this)
  }
}

private[kyo] object IOPromise {

  type State[T] = (T < IOs) | Pending[T] | Linked[T]

  case class Linked[T](p: IOPromise[T])

  abstract class Pending[T] { self =>

    def run(v: T < IOs): Pending[T]

    def add(f: T < IOs => Unit): Pending[T] =
      new Pending[T] {
        def run(v: T < IOs) = {
          try f(v)
          catch {
            case ex if NonFatal(ex) =>
              Logs.unsafe.error("uncaught exception", ex)
          }
          self
        }
      }

    final def merge(tail: Pending[T]): Pending[T] = {
      @tailrec def loop(p: Pending[T], v: T < IOs): Pending[T] =
        p match {
          case _ if (p eq Pending.Empty) =>
            tail
          case p: Pending[T] =>
            loop(p.run(v), v)
        }
      new Pending[T] {
        def run(v: T < IOs) =
          loop(self, v)
      }
    }

    final def flush(v: T < IOs): Unit = {
      @tailrec def loop(p: Pending[T]): Unit =
        p match {
          case _ if (p eq Pending.Empty) => ()
          case p: Pending[T] =>
            loop(p.run(v))
        }
      loop(this)
    }
  }

  object Pending {
    def apply[T](): Pending[T] = Empty.asInstanceOf[Pending[T]]
    case object Empty extends Pending[Nothing] {
      def run(v: Nothing < IOs) = this
    }
  }
}
