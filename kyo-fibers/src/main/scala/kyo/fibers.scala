package kyo

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import scala.annotation.tailrec
import scala.util._

import core._
import ios._
import scheduler._
import kyo.lists.Lists
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal.apply
import scala.util.control.NonFatal

object fibers {

  class Suspended[T] extends AtomicReference[T | State[T]](State.Pending)

  opaque type Fiber[T] = T | Suspended[T]

  opaque type Promise[T] <: Fiber[T] = Fiber[T]

  final class Fibers private[fibers] () extends Effect[Fiber] {

    inline def value[T](v: T): T > Fibers = v

    inline def promise[T]: Promise[T] > IOs =
      IOs(new Suspended[T])

    inline def forkAndBlock[T](inline v: => T > IOs): T > (Fibers | IOs) =
      fork(v)((f: Fiber[T]) => IOs(f.block()))

    inline def fork[T](inline v: => T > IOs): T > (Fibers | IOs) =
      IOs {
        val p           = new Suspended[T]
        val s: Fiber[T] = p
        def task(v: => T > IOs): Preemptable =
          new Preemptable {
            override def run(preempt: () => Boolean): Preemptable =
              given Safepoint[IOs] =
                new Safepoint[IOs] {
                  def apply(): Boolean = preempt()
                  def apply[T, S](v: => T > (S | IOs)): T > (S | IOs) =
                    IOs(v)
                }
              try {
                (v < IOs).run(preempt) match {
                  case Left(io) =>
                    task(io > IOs)
                  case Right(v) =>
                    IOs.run(s.complete(v))
                    Preemptable.Done
                }
              } catch {
                case ex if NonFatal(ex) =>
                  ex.printStackTrace()
                  s.complete(IOs(throw ex))
                  Preemptable.Done
              }
          }
        Scheduler(task(v))
        s > Fibers
      }

    inline def block[T, S](v: T > (S | Fibers)): T > (S | IOs) =
      given blockingHandler: ShallowHandler[Fiber, Fibers] =
        new ShallowHandler[Fiber, Fibers] {
          def pure[T](v: T) = v
          def apply[T, U, S](m: Fiber[T], f: T => U > (S | Fibers)) =
            f(m.block())
        }
      IOs((v < Fibers)((_: Fiber[T]).block()))
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

  sealed trait State[+T]
  private object State {

    case object Pending
        extends State[Nothing]

    case class Waiters[T](head: T > IOs => Unit, tail: State[T])
        extends State[T]

    extension [T](w: State[T]) {
      def apply(f: T > IOs => Unit): State[T] =
        Waiters(f, w)
      def apply(v: T > IOs): Unit =
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
          val p = new Suspended[List[T]] with (Int => T > IOs => Unit) {
            val size    = l.size
            val results = (new Array[Any](size)).asInstanceOf[Array[T]]
            val pending = new AtomicInteger(size)
            def apply(i: Int): T > IOs => Unit =
              (v: T > IOs) =>
                try {
                  results(i) = (v < IOs).run()
                  if (this.pending.decrementAndGet() == 0) {
                    var i = size - 1
                    var r = List.empty[T]
                    while (i >= 0) {
                      r ::= this.results(i)
                      i -= 1
                    }
                    (this: Fiber[List[T]]).complete(r)
                  }
                } catch {
                  case ex if NonFatal(ex) =>
                    ex.printStackTrace()
                    (this: Fiber[List[T]]).complete(IOs(throw ex))
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

  extension [T](a: Promise[T]) {
    def complete(v: T > IOs): Unit > IOs =
      v { v =>
        IOs {
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
        }
      }
  }

  extension [T](a: Fiber[T]) {

    def join: T > Fibers = a > Fibers

    private[fibers] def map[U](f: T => U): Fiber[U] =
      unwrap(a) match {
        case a: Suspended[_] =>
          val r = new Suspended[U] with (T > IOs => Unit) {
            def apply(v: T > IOs) = v(v => this.complete(f(v)))
          }
          onComplete(r)
          r
        case a =>
          f(a.asInstanceOf[T])
      }

    private[fibers] def flatMap[U](f: T => Fiber[U]): Fiber[U] =
      unwrap(a) match {
        case a: Suspended[_] =>
          val r = new Suspended[U] with (T > IOs => Unit) { self =>
            def apply(v: T > IOs) = v(v => f(v).onComplete((u: U > IOs) => self.complete(u)))
          }
          onComplete(r)
          r
        case a =>
          f(a.asInstanceOf[T])
      }

    private[fibers] def onComplete(f: T > IOs => Unit): Unit =
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

    def block(): T =
      unwrap(a) match {
        case a: Suspended[T] @unchecked =>
          val b = new CountDownLatch(1) with (T > IOs => Unit) with (() => T > IOs) {
            private[this] var result: T > IOs = null.asInstanceOf[T]
            def apply(v: T > IOs) =
              result = v
              countDown()
            def apply() = result
          }
          onComplete(b)
          b.await()
          (b() < IOs).run()
        case a =>
          a.asInstanceOf[T]
      }
  }
}
