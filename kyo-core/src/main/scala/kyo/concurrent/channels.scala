package kyo.concurrent

import kyo.core._
import kyo.ios._
import org.jctools.queues.MpmcUnboundedXaddArrayQueue

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import scala.annotation.tailrec

import queues._
import fibers._
import atomics._

object channels {

  trait Channel[T] { self =>
    def size: Int > IOs
    def offer(v: T): Boolean > IOs
    def poll: Option[T] > IOs
    def isEmpty: Boolean > IOs
    def isFull: Boolean > IOs
  }

  object Channels {

    trait Unbounded[T] extends Channel[T] {
      def offer(v: T): Boolean > IOs
      def poll: Option[T] > IOs
      def put(v: T): Unit > IOs
    }

    trait Blocking[T] extends Channel[T] {

      def putFiber(v: T): Fiber[Unit] > IOs
      def takeFiber: Fiber[T] > IOs

      def put(v: T): Unit > (IOs | Fibers) =
        putFiber(v)(_.join)
      def take: T > (IOs | Fibers) =
        takeFiber(_.join)
    }

    def makeBounded[T](capacity: Int, access: Access = Access.Mpmc): Channel[T] > IOs =
      Queues.makeBounded[T](capacity, access) { q =>
        new Channel[T] {
          def size        = q.size
          def offer(v: T) = q.offer(v)
          def poll        = q.poll
          def isEmpty     = q.isEmpty
          def isFull      = q.isFull
        }
      }

    def makeDropping[T](capacity: Int, access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.makeBounded[T](capacity, access) { q =>
        new Unbounded[T] {
          def size        = q.size
          def offer(v: T) = q.offer(v)
          def poll        = q.poll
          def put(v: T)   = q.offer(v).unit
          def isEmpty     = q.isEmpty
          def isFull      = q.isFull
        }
      }

    def makeSliding[T](capacity: Int, access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.makeBounded[T](capacity, access) { q =>
        new Unbounded[T] {
          def size        = q.size
          def offer(v: T) = q.offer(v)
          def poll        = q.poll
          def put(v: T) =
            IOs {
              @tailrec def loop: Unit = {
                val u = q.unsafe
                if (u.offer(v)) ()
                else {
                  u.poll()
                  loop
                }
              }
              loop
            }
          def isEmpty = q.isEmpty
          def isFull  = q.isFull
        }
      }

    def makeUnbounded[T](access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.makeUnbounded[T](access) { q =>
        new Unbounded[T] {
          def size        = q.size
          def put(v: T)   = q.add(v)
          def offer(v: T) = q.offer(v)
          def poll        = q.poll
          def isEmpty     = q.isEmpty
          def isFull      = false
        }
      }

    def makeBlocking[T](capacity: Int, access: Access = Access.Mpmc): Blocking[T] > IOs =
      Queues.makeBounded[T](capacity, access) { queue =>
        new Blocking[T] {

          val q     = queue.unsafe
          val takes = MpmcUnboundedXaddArrayQueue[Promise[T]](8)
          val puts  = MpmcUnboundedXaddArrayQueue[(T, Promise[Unit])](8)

          def size    = queue.size
          def isEmpty = queue.isEmpty
          def isFull  = queue.isFull
          def offer(v: T) =
            IOs {
              try q.offer(v)
              finally flush()
            }
          def poll =
            IOs {
              try q.poll()
              finally flush()
            }
          def putFiber(v: T): Fiber[Unit] > IOs =
            IOs {
              try {
                if (q.offer(v)) {
                  Fibers.done(())
                } else {
                  val p = Fibers.unsafePromise[Unit]
                  puts.add((v, p))
                  p
                }
              } finally flush()
            }
          def takeFiber: Fiber[T] > IOs =
            IOs {
              try {
                q.poll() match {
                  case Some(v) =>
                    Fibers.done(v)
                  case None =>
                    val p = Fibers.unsafePromise[T]
                    takes.add(p)
                    p
                }
              } finally flush()
            }

          @tailrec private def flush(): Unit = {
            var loop = false
            if (!q.isEmpty && !takes.isEmpty()) {
              loop = true
              val p = takes.poll()
              if (p != null.asInstanceOf[Promise[T]]) {
                q.poll() match {
                  case None =>
                  case Some(v) =>
                    if (!p.unsafeComplete(v) && !q.offer(v)) {
                      val p = Fibers.unsafePromise[Unit]
                      puts.add((v, p))
                    }
                }
              }
            }
            if (!q.isFull && !puts.isEmpty()) {
              loop = true
              val t = puts.poll()
              if (t != null.asInstanceOf[Promise[Unit]]) {
                val (v, p) = t
                if (q.offer(v)) {
                  p.unsafeComplete(())
                } else {
                  puts.add((v, p))
                }
              }
            }
            if (loop) flush()
          }
        }
      }
  }
}
