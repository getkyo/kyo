package kyo.concurrent

import kyo.core._
import kyo.ios._

import queues._
import fibers._
import atomics._
import org.jctools.queues.MpmcUnboundedXaddArrayQueue
import java.util.concurrent.atomic.AtomicLong

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
          val puts  = MpmcUnboundedXaddArrayQueue[(T, Promise[Unit])](8)
          val state = AtomicLong() // > 0: puts, < 0: takes
          def offer(v: T): Boolean > IOs =
            IOs {
              val s = state.get()
              if (s == 0) {
                q.offer(v)
              } else if (s < 0) {
                if (state.compareAndSet(s, s + 1)) {
                  var p = takes.poll()
                  while (p == null.asInstanceOf[Promise[T]]) {
                    p = takes.poll()
                  }
                  p.complete(v) {
                    case true  => true
                    case false => offer(v)
                  }
                } else {
                  offer(v)
                }
              } else {
                false
              }
            }
          def poll: Option[T] > IOs =
            IOs {
              q.poll() match {
                case null => None
                case v =>
                  def loop(pending: (T, Promise[Unit])): Option[T] > IOs = {
                    val s = state.get()
                    if (s > 0) {
                      if (state.compareAndSet(s, s - 1)) {
                        var t = pending
                        while (t == null) {
                          t = puts.poll()
                        }
                        val (v2, p) = t
                        if (q.offer(v2)) {
                          p.complete(())(_ => Some(v))
                        } else {
                          puts.add(t)
                          Some(v)
                        }
                      } else {
                        loop(pending)
                      }
                    } else {
                      Some(v)
                    }
                  }
                  loop(null)
              }
            }
          def putFiber(v: T): Fiber[Unit] > IOs =
            offer(v) {
              case true =>
                Fibers.done(())
              case false =>
                IOs {
                  val s = state.get()
                  if (s >= 0 && state.compareAndSet(s, s + 1)) {
                    val p = Fibers.unsafePromise[Unit]
                    puts.add((v, p))
                    p
                  } else {
                    putFiber(v)
                  }
                }
            }
          def takeFiber: Fiber[T] > IOs =
            poll {
              case Some(v) =>
                Fibers.done(v)
              case None =>
                IOs {
                  val s = state.get()
                  if (s <= 0 && state.compareAndSet(s, s - 1)) {
                    val p = Fibers.unsafePromise[T]
                    takes.add(p)
                    p
                  } else {
                    takeFiber
                  }
                }
            }

          def isEmpty = queue.isEmpty
          def isFull  = queue.isFull
        }
      }
  }
}
