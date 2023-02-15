package kyo.concurrent

import kyo.core._
import kyo.ios._

import queues._
import fibers._
import atomics._

object channels {

  trait Channel[T] { self =>

    def offer(v: T): Boolean > IOs
    def poll: Option[T] > IOs

    def dropping: Channel.Unbounded[T] > IOs =
      new Channel.Unbounded[T] {
        def offer(v: T): Boolean > IOs =
          self.offer(v)
        def poll: Option[T] > IOs =
          self.poll
        def put(v: T): Unit > IOs =
          self.offer(v).unit
      }

    def sliding: Channel.Unbounded[T] > IOs =
      new Channel.Unbounded[T] {
        def offer(v: T): Boolean > IOs =
          self.offer(v)
        def poll: Option[T] > IOs =
          self.poll
        def put(v: T): Unit > IOs =
          self.offer(v) {
            case true =>
              ()
            case false =>
              self.poll(_ => put(v))
          }
      }

    def blocking: Channel.Blocking[T] > IOs =
      for {
        takes <- Queue.unbounded[Promise[T]]()
        puts  <- Queue.unbounded[(T, Promise[Unit])]()
      } yield {
        new Channel.Blocking[T] {
          def offer(v: T): Boolean > IOs =
            takes.poll {
              case Some(p) =>
                p.complete(v) {
                  case true =>
                    true
                  case false =>
                    offer(v)
                }
              case None =>
                self.offer(v)
            }
          def poll: Option[T] > IOs =
            self.poll {
              case v: Some[T] =>
                def loop: Option[T] > IOs =
                  puts.poll {
                    case Some(t) =>
                      offer(t._1) {
                        case true =>
                          t._2.complete(()) {
                            case true =>
                              v
                            case false =>
                              loop
                          }
                        case false =>
                          puts.add(t) { _ =>
                            v
                          }
                      }
                    case None =>
                      v
                  }
                loop
              case None =>
                None
            }
          def putFiber(v: T): Fiber[Unit] > IOs =
            offer(v) {
              case true =>
                Fibers.done(())
              case false =>
                for {
                  p <- Fibers.promise[Unit]
                  _ <- puts.add((v, p))
                } yield p
            }
          def takeFiber: Fiber[T] > IOs =
            poll {
              case Some(v) =>
                Fibers.done(v)
              case None =>
                for {
                  p <- Fibers.promise[T]
                  _ <- takes.add(p)
                } yield p
            }
        }
      }
  }

  object Channel {

    def bounded[T](size: Int): Channel[T] > IOs =
      Queue.bounded[T](size)(bounded)

    def bounded[T](q: Queue[T]): Channel[T] > IOs =
      new Channel[T] {
        def offer(v: T): Boolean > IOs =
          q.offer(v)
        def poll: Option[T] > IOs =
          q.poll
      }

    def unbounded[T](): Unbounded[T] > IOs =
      Queue.unbounded[T]()(unbounded)

    def unbounded[T](q: UnboundedQueue[T]): Unbounded[T] =
      new Unbounded[T] {
        def put(v: T): Unit > IOs =
          q.add(v)
        def offer(v: T): Boolean > IOs =
          q.offer(v)
        def poll: Option[T] > IOs =
          q.poll
      }

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
  }
}
