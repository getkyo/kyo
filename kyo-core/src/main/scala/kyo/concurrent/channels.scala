package kyo.concurrent

import kyo.core._
import kyo.ios._

import queues._
import fibers._
import atomics._
import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec

object channels {

  trait Channel[T] { self =>
    def offer(v: T): Boolean > IOs
    def poll: Option[T] > IOs
    def isEmpty: Boolean > IOs
    def isFull: Boolean > IOs
  }

  object Channel {

    trait Unbounded[T] extends Channel[T] {
      def offer(v: T): Boolean > IOs
      def poll: Option[T] > IOs
      def put(v: T): Unit > IOs
    }

    trait Blocking[T] extends Channel[T] {
      def put(v: T): Unit > (IOs | Fibers) =
        putFiber(v)(_.join)
      def take: T > (IOs | Fibers) =
        takeFiber(_.join)
      def putFiber(v: T): Fiber[Unit] > IOs
      def takeFiber: Fiber[T] > IOs
    }

    def bounded[T](size: Int): Channel[T] > IOs =
      Queue.bounded[T](size)(bounded)

    def bounded[T](q: Queue[T] > IOs): Channel[T] > IOs =
      q { q =>
        new Channel[T] {
          def offer(v: T) = q.offer(v)
          def poll        = q.poll
          def isEmpty     = q.isEmpty
          def isFull      = q.isFull
        }
      }

    def dropping[T](capacity: Int): Unbounded[T] > IOs =
      Queue.bounded[T](capacity)(dropping)

    def dropping[T](q: Queue[T] > IOs): Unbounded[T] > IOs =
      q { q =>
        new Unbounded[T] {
          def offer(v: T) = q.offer(v)
          def poll        = q.poll
          def put(v: T)   = q.offer(v).unit
          def isEmpty     = q.isEmpty
          def isFull      = q.isFull
        }
      }

    def sliding[T](capacity: Int): Unbounded[T] > IOs =
      Queue.bounded[T](capacity)(sliding)

    def sliding[T](q: Queue[T] > IOs): Unbounded[T] > IOs =
      q { q =>
        new Unbounded[T] {
          def offer(v: T) = q.offer(v)
          def poll        = q.poll
          def put(v: T) =
            q.offer(v) {
              case true =>
                ()
              case false =>
                q.poll(_ => put(v))
            }
          def isEmpty = q.isEmpty
          def isFull  = q.isFull
        }
      }

    def unbounded[T](): Unbounded[T] > IOs =
      Queue.unbounded[T]()(unbounded)

    def unbounded[T](q: UnboundedQueue[T] > IOs): Unbounded[T] > IOs =
      q { q =>
        new Unbounded[T] {
          def put(v: T)   = q.add(v)
          def offer(v: T) = q.offer(v)
          def poll        = q.poll
          def isEmpty     = q.isEmpty
          def isFull      = false
        }
      }

    def blocking[T](capacity: Int): Blocking[T] > IOs =
      Queue.bounded[T](capacity)(blocking)

    def blocking[T](queue: Queue[T] > IOs): Blocking[T] > IOs =
      queue { queue =>
        new Blocking[T] {
          val q     = queue.unsafe
          val takes = MpmcUnboundedXaddArrayQueue[Promise[T]](8)
          val puts  = MpmcUnboundedXaddArrayQueue[Promise[Unit]](8)
          val state = AtomicLong() // > 0: puts, < 0: takes

          def offer(v: T) =
            IOs(unsafeOffer(v))
          def poll =
            IOs(Option(unsafePoll()))
          def putFiber(v: T): Fiber[Unit] > IOs =
            IOs(unsafePut(v))
          def takeFiber: Fiber[T] > IOs =
            IOs(unsafeTake())
          def isEmpty = queue.isEmpty
          def isFull  = queue.isFull

          @tailrec private def unsafeOffer(v: T): Boolean =
            val s = state.get()
            if (s < 0) {
              if (state.compareAndSet(s, s + 1)) {
                var p = takes.poll()
                while (p == null.asInstanceOf[Promise[T]]) {
                  p = takes.poll()
                }
                if (p.unsafeComplete(v)) {
                  true
                } else {
                  unsafeOffer(v)
                }
              } else {
                unsafeOffer(v)
              }
            } else {
              q.offer(v)
            }

          private def _unsafePut(v: T) = unsafePut(v)
          @tailrec private def unsafePut(v: T): Fiber[Unit] =
            if (unsafeOffer(v)) {
              Fibers.done(())
            } else {
              val s = state.get()
              if (s >= 0 && state.compareAndSet(s, s + 1)) {
                val p = Fibers.unsafePromise[Unit]
                puts.add(p)
                p.transform(_ => _unsafePut(v))
              } else {
                unsafePut(v)
              }
            }

          private def unsafePoll(): T = {
            q.poll() match {
              case null =>
                null.asInstanceOf[T]
              case v =>
                def loop: T = {
                  val s = state.get()
                  if (s > 0) {
                    if (state.compareAndSet(s, s - 1)) {
                      var p = puts.poll()
                      while (p == null.asInstanceOf[AnyRef]) {
                        p = puts.poll()
                      }
                      if (p.unsafeComplete(())) {
                        v
                      } else {
                        loop
                      }
                    } else {
                      loop
                    }
                  } else {
                    v
                  }
                }
                loop
            }
          }

          @tailrec private def unsafeTake(): Fiber[T] =
            val v = unsafePoll()
            if (v != null) {
              Fibers.done(v)
            } else {
              val s = state.get()
              if (s <= 0 && state.compareAndSet(s, s - 1)) {
                val p = Fibers.unsafePromise[T]
                takes.add(p)
                p
              } else {
                unsafeTake()
              }
            }
        }
      }
  }
}
