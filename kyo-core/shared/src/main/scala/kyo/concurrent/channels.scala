package kyo.concurrent

import kyo._
import kyo.ios._
import org.jctools.queues.MpmcUnboundedXaddArrayQueue

import java.util.concurrent.Executors
import scala.annotation.tailrec

import queues._
import fibers._

object channels {

  trait Channel[T] { self =>
    def size: Int > IOs
    def offer[S](v: T > S): Boolean > (IOs with S)
    def poll: Option[T] > IOs
    def isEmpty: Boolean > IOs
    def isFull: Boolean > IOs
  }

  object Channels {

    trait Unbounded[T] extends Channel[T] {
      def offer[S](v: T > S): Boolean > (IOs with S)
      def poll: Option[T] > IOs
      def put[S](v: T > S): Unit > (IOs with S)
    }

    trait Blocking[T] extends Channel[T] {

      def putFiber[S](v: T > S): Fiber[Unit] > (IOs with S)
      def takeFiber: Fiber[T] > IOs

      def put[S](v: T > S): Unit > (S with IOs with Fibers) =
        putFiber(v).map(_.join)
      def take: T > (IOs with Fibers) =
        takeFiber.map(_.join)
    }

    def bounded[T](capacity: Int, access: Access = Access.Mpmc): Channel[T] > IOs =
      Queues.bounded[T](capacity, access).map { q =>
        new Channel[T] {
          val size               = q.size
          def offer[S](v: T > S) = q.offer(v)
          val poll               = q.poll
          val isEmpty            = q.isEmpty
          val isFull             = q.isFull
        }
      }

    def dropping[T](capacity: Int, access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.bounded[T](capacity, access).map { q =>
        new Unbounded[T] {
          val size               = q.size
          def offer[S](v: T > S) = q.offer(v)
          val poll               = q.poll
          def put[S](v: T > S)   = q.offer(v).unit
          val isEmpty            = q.isEmpty
          val isFull             = q.isFull
        }
      }

    def sliding[T](capacity: Int, access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.bounded[T](capacity, access).map { q =>
        new Unbounded[T] {
          val size               = q.size
          def offer[S](v: T > S) = q.offer(v)
          val poll               = q.poll
          def put[S](v: T > S) =
            IOs[Unit, S] {
              @tailrec def loop(v: T): Unit = {
                val u = q.unsafe
                if (u.offer(v)) ()
                else {
                  u.poll()
                  loop(v)
                }
              }
              v.map(loop(_))
            }
          val isEmpty = q.isEmpty
          val isFull  = q.isFull
        }
      }

    def unbounded[T](access: Access = Access.Mpmc): Unbounded[T] > IOs =
      Queues.unbounded[T](access).map { q =>
        new Unbounded[T] {
          val size               = q.size
          def put[S](v: T > S)   = q.add(v)
          def offer[S](v: T > S) = q.offer(v)
          val poll               = q.poll
          val isEmpty            = q.isEmpty
          val isFull             = false
        }
      }

    def blocking[T](capacity: Int, access: Access = Access.Mpmc): Blocking[T] > IOs =
      Queues.bounded[T](capacity, access).map { queue =>
        new Blocking[T] {

          val q     = queue.unsafe
          val takes = new MpmcUnboundedXaddArrayQueue[Promise[T]](8)
          val puts  = new MpmcUnboundedXaddArrayQueue[(T, Promise[Unit])](8)

          val size    = queue.size
          val isEmpty = queue.isEmpty
          val isFull  = queue.isFull
          def offer[S](v: T > S) =
            v.map { v =>
              IOs[Boolean, S] {
                try q.offer(v)
                finally flush()
              }
            }
          val poll =
            IOs {
              try q.poll()
              finally flush()
            }
          def putFiber[S](v: T > S): Fiber[Unit] > (IOs with S) =
            v.map { v =>
              IOs[Fiber[Unit], S] {
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
