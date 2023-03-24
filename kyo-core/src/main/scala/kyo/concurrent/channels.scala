package kyo.concurrent

import kyo.core._
import kyo.ios._
import org.jctools.queues.MpmcUnboundedXaddArrayQueue

import java.util.concurrent.Executors
import scala.annotation.tailrec

import queues._
import fibers._

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

    def bounded[T](capacity: Int, access: Access = Access.Mpmc): Channel[T] > IOs =
      Queues.bounded[T](capacity, access) { q =>
        new Channel[T] {
          val size        = q.size
          def offer(v: T) = q.offer(v)
          val poll        = q.poll
          val isEmpty     = q.isEmpty
          val isFull      = q.isFull
        }
      }

    def dropping[T](capacity: Int, access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.bounded[T](capacity, access) { q =>
        new Unbounded[T] {
          val size        = q.size
          def offer(v: T) = q.offer(v)
          val poll        = q.poll
          def put(v: T)   = q.offer(v).unit
          val isEmpty     = q.isEmpty
          val isFull      = q.isFull
        }
      }

    def sliding[T](capacity: Int, access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.bounded[T](capacity, access) { q =>
        new Unbounded[T] {
          val size        = q.size
          def offer(v: T) = q.offer(v)
          val poll        = q.poll
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
          val isEmpty = q.isEmpty
          val isFull  = q.isFull
        }
      }

    def unbounded[T](access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.unbounded[T](access) { q =>
        new Unbounded[T] {
          val size        = q.size
          def put(v: T)   = q.add(v)
          def offer(v: T) = q.offer(v)
          val poll        = q.poll
          val isEmpty     = q.isEmpty
          val isFull      = false
        }
      }

    def blocking[T](capacity: Int, access: Access = Access.Mpmc): Blocking[T] > IOs =
      Queues.bounded[T](capacity, access) { queue =>
        new Blocking[T] {

          val q     = queue.unsafe
          val takes = MpmcUnboundedXaddArrayQueue[Promise[T]](8)
          val puts  = MpmcUnboundedXaddArrayQueue[(T, Promise[Unit])](8)

          val size    = queue.size
          val isEmpty = queue.isEmpty
          val isFull  = queue.isFull
          def offer(v: T) =
            IOs {
              try q.offer(v)
              finally flush()
            }
          val poll =
            IOs {
              try q.poll()
              finally flush()
            }
          def putFiber(v: T): Fiber[Unit] > IOs =
            IOs {
              try {
                if (q.offer(v)) {
                  Fibers.value(())
                } else {
                  val p = Fibers.unsafePromise[Unit]
                  puts.add((v, p))
                  p
                }
              } finally {
                flush()
              }
            }
          val takeFiber: Fiber[T] > IOs =
            IOs {
              try {
                q.poll() match {
                  case Some(v) =>
                    Fibers.value(v)
                  case None =>
                    val p = Fibers.unsafePromise[T]
                    takes.add(p)
                    p
                }
              } finally {
                flush()
              }
            }

          @tailrec private def flush(): Unit = {
            var loop = false
            if (!q.isEmpty && !takes.isEmpty()) {
              loop = true
              val p = takes.poll()
              if (p != null.asInstanceOf[Promise[T]]) {
                q.poll() match {
                  case None =>
                    takes.add(p)
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
              if (t != null) {
                val (v, p) = t
                if (q.offer(v)) {
                  p.unsafeComplete(())
                } else {
                  puts.add(t)
                }
              }
            }
            if (loop) flush()
          }
        }
      }
  }
}
