package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec
import scala.util._

import core._
import scheduler._
import kyo.lists.Lists
import scala.concurrent.duration.Duration

object fibers {

  opaque type Fiber[T] = T | Suspended[T]

  final class Fibers private[fibers] () extends Effect[Fiber] {

    inline def value[T](v: T): T > Fibers = v

    inline def promise[T]: (T > Fibers, (T => Unit)) =
      val s = new Suspended[T]
      ((s: Fiber[T]) > Fibers, s.complete)

    inline def forkAndBlock[T](inline v: => T)(using
        scheduler: Scheduler
    ): T = block(fork(v))

    inline def fork[T, S](inline v: T > S)(using
        scheduler: Scheduler
    ): T > (S | Fibers) =
      val p               = new Suspended[T > S]
      val s: Fiber[T > S] = p
      scheduler(p.complete(v))
      s >> Fibers

    inline def block[T, S](v: T > (S | Fibers)): T > S =
      given blockingHandler: ShallowHandler[Fiber, Fibers] =
        new ShallowHandler[Fiber, Fibers] {
          def pure[T](v: T) = v
          def apply[T, U, S](m: Fiber[T], f: T => U > (S | Fibers)) =
            f(m.block())
        }
      (v < Fibers)((_: Fiber[T]).block())
  }

  val Fibers: Fibers = new Fibers

  inline given DeepHandler[Fiber, Fibers] =
    new DeepHandler[Fiber, Fibers] {
      def pure[T](v: T): Fiber[T] = v
      override def map[T, U](m: Fiber[T], f: T => U): Fiber[U] =
        m.map(f)
      def flatMap[T, U](m: Fiber[T], f: T => Fiber[U]): Fiber[U] =
        m.flatMap(f)
    }

  class Suspended[T] extends AtomicReference[T | State[T]](State.Pending) {

    override def toString =
      val state =
        get() match {
          case w: State[T] @unchecked =>
            var curr = w
            var size = 0
            while (curr ne State.Pending) {
              size += 1
              curr = w.asInstanceOf[State.Waiters[T]].tail
            }
            s"waiters=$size"
          case v =>
            s"done=$v"
        }

      s"fibers.Suspended($state)"
  }

  sealed trait State[+T]
  private object State {

    case object Pending
        extends State[Nothing]

    case class Waiters[T](head: T => Unit, tail: State[T])
        extends State[T]

    extension [T](w: State[T]) {
      def apply(f: T => Unit): State[T] =
        Waiters(f, w)
      def apply(v: T): Unit =
        var c: State[T] = w
        while (c ne Pending) {
          val m = c.asInstanceOf[Waiters[T]]
          m.head(v)
          c = m.tail
        }
    }
  }
  private def unwrap[T](s: Fiber[T]): Fiber[T] =
    s match {
      case s: Suspended[_] =>
        s.get() match {
          case _: State[_] =>
            s
          case v =>
            v.asInstanceOf[T]
        }
      case s =>
        s.asInstanceOf[T]
    }

  object Fiber {
    val collect: [T] => List[Fiber[T]] => Fiber[List[T]] =
      [T] =>
        (l: List[Fiber[T]]) => {
          val p = new Suspended[List[T]] with (Int => T => Unit) {
            val size    = l.size
            val results = (new Array[Any](size)).asInstanceOf[Array[T]]
            val pending = new AtomicInteger(size)
            def apply(i: Int): T => Unit =
              (v: T) =>
                this.results(i) = v
                if (this.pending.decrementAndGet() == 0) {
                  var i = size - 1
                  var r = List.empty[T]
                  while (i >= 0) {
                    r ::= this.results(i)
                    i -= 1
                  }
                  (this: Fiber[List[T]]).complete(r)
                }
          }
          var i = 0
          var c = l
          while (c ne Nil) {
            c.head.onComplete(p(i))
            c = c.tail
            i += 1
          }
          p
      }
  }

  import State._

  extension [T](a: Fiber[T]) {

    def map[U](f: T => U): Fiber[U] =
      unwrap(a) match {
        case a: Suspended[_] =>
          val r = new Suspended[U] with (T => Unit) {
            def apply(v: T) = this.complete(f(v))
          }
          onComplete(r)
          r
        case a =>
          f(a.asInstanceOf[T])
      }

    def flatMap[U](f: T => Fiber[U]): Fiber[U] =
      unwrap(a) match {
        case a: Suspended[_] =>
          val r = new Suspended[U] with (T => Unit) { self =>
            def apply(v: T) = f(v).onComplete((u: U) => self.complete(u))
          }
          onComplete(r)
          r
        case a =>
          f(a.asInstanceOf[T])
      }

    def onComplete(f: T => Unit): Unit =
      @tailrec def loop(a: Suspended[T]): Unit =
        a.get() match {
          case w: State[T] @unchecked =>
            if (!a.compareAndSet(w, w(f)))
              loop(a)
          case v =>
            f(v.asInstanceOf[T])
        }
      unwrap(a) match {
        case a: Suspended[T] @unchecked =>
          loop(a)
        case a =>
          f(a.asInstanceOf[T])
      }

    private[fibers] def complete(v: T) =
      @tailrec def loop(a: Suspended[T]): Unit =
        a.get() match {
          case State.Pending =>
            if (!a.compareAndSet(State.Pending, v))
              loop(a)
          case w: State[T] @unchecked =>
            if (!a.compareAndSet(w, v)) loop(a)
            else w(v)
          case _ =>
            ???
        }
      unwrap(a) match {
        case a: Suspended[T] @unchecked =>
          loop(a)
        case _ =>
          ???
      }

    def block(): T =
      unwrap(a) match {
        case a: Suspended[T] @unchecked =>
          val b = new CountDownLatch(1) with (T => Unit) with (() => T) {
            private[this] var result: T = null.asInstanceOf[T]
            def apply(v: T) =
              result = v
              countDown()
            def apply() = result
          }
          onComplete(b)
          b.await()
          b()
        case a =>
          a.asInstanceOf[T]
      }
  }
}
