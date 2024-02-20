package kyo

import org.jctools.queues.MpmcUnboundedXaddArrayQueue

import scala.annotation.tailrec

import Flat.unsafe.unchecked

abstract class Channel[T] { self =>

  def size: Int < IOs

  def offer(v: T): Boolean < IOs

  def offerUnit(v: T): Unit < IOs

  def poll: Option[T] < IOs

  def isEmpty: Boolean < IOs

  def isFull: Boolean < IOs

  def putFiber(v: T): Fiber[Unit] < IOs

  def takeFiber: Fiber[T] < IOs

  def put(v: T): Unit < Fibers =
    putFiber(v).map(_.get)

  def take: T < Fibers =
    takeFiber.map(_.get)

  def isClosed: Boolean < IOs

  def close: Option[Seq[T]] < IOs
}

object Channels {

  private val placeholder = Fibers.unsafeInitPromise[Unit]
  private val closed      = IOs.fail("Channel closed!")

  def init[T](
      capacity: Int,
      access: Access = kyo.Access.Mpmc
  ): Channel[T] < IOs =
    Queues.init[T](capacity, access).map { queue =>
      IOs {
        new Channel[T] {

          val u     = queue.unsafe
          val takes = new MpmcUnboundedXaddArrayQueue[Promise[T]](8)
          val puts  = new MpmcUnboundedXaddArrayQueue[(T, Promise[Unit])](8)

          def size    = op(u.size())
          def isEmpty = op(u.isEmpty())
          def isFull  = op(u.isFull())

          def offer(v: T) =
            op {
              try u.offer(v)
              finally flush()
            }
          def offerUnit(v: T) =
            op {
              try {
                u.offer(v)
                ()
              } finally flush()
            }
          val poll =
            op {
              try u.poll()
              finally flush()
            }

          def putFiber(v: T) =
            op {
              try {
                if (u.offer(v)) {
                  Fibers.value(())
                } else {
                  val p = Fibers.unsafeInitPromise[Unit]
                  puts.add((v, p))
                  p
                }
              } finally {
                flush()
              }
            }

          val takeFiber =
            op {
              try {
                u.poll() match {
                  case Some(v) =>
                    Fibers.value(v)
                  case None =>
                    val p = Fibers.unsafeInitPromise[T]
                    takes.add(p)
                    p
                }
              } finally {
                flush()
              }
            }

          /*inline*/
          def op[T]( /*inline*/ v: => T): T < IOs =
            IOs[T, Any] {
              if (u.isClosed()) {
                closed
              } else {
                v
              }
            }

          def isClosed = queue.isClosed

          def close =
            IOs[Option[Seq[T]], Any] {
              u.close() match {
                case None =>
                  None
                case r: Some[Seq[T]] =>
                  def dropTakes(): Unit < IOs =
                    takes.poll() match {
                      case null => ()
                      case p =>
                        p.interrupt.map(_ => dropTakes())
                    }
                  def dropPuts(): Unit < IOs =
                    puts.poll() match {
                      case null => ()
                      case (_, p) =>
                        p.interrupt.map(_ => dropPuts())
                    }
                  dropTakes()
                    .andThen(dropPuts())
                    .andThen(r)
              }
            }

          @tailrec private def flush(): Unit = {
            // This method ensures that all values are processed
            // and handles interrupted fibers by discarding them.
            val queueSize  = u.size()
            val takesEmpty = takes.isEmpty()
            val putsEmpty  = puts.isEmpty()

            if (queueSize > 0 && !takesEmpty) {
              // Attempt to transfer a value from the queue to
              // a waiting consumer (take).
              val p = takes.poll()
              if (p != null.asInstanceOf[Promise[T]]) {
                u.poll() match {
                  case None =>
                    // If the queue has been emptied before the
                    // transfer, requeue the consumer's promise.
                    takes.add(p)
                  case Some(v) =>
                    if (!p.unsafeComplete(v) && !u.offer(v)) {
                      // If completing the take fails and the queue
                      // cannot accept the value back, enqueue a
                      // placeholder put operation to preserve the value.
                      val placeholder = Fibers.unsafeInitPromise[Unit]
                      puts.add((v, placeholder))
                    }
                }
              }
              flush()
            } else if (queueSize < capacity && !putsEmpty) {
              // Attempt to transfer a value from a waiting
              // producer (put) to the queue.
              val t = puts.poll()
              if (t != null) {
                val (v, p) = t
                if (u.offer(v)) {
                  // Complete the put's promise if the value is
                  // successfully enqueued. If the fiber became
                  // interrupted, the completion will be ignored.
                  p.unsafeComplete(())
                } else {
                  // If the queue becomes full before the transfer,
                  // requeue the producer's operation.
                  puts.add(t)
                }
              }
              flush()
            } else if (queueSize == 0 && !putsEmpty && !takesEmpty) {
              // Directly transfer a value from a producer to a
              // consumer when the queue is empty.
              val t = puts.poll()
              if (t != null) {
                val (v, p) = t
                val p2     = takes.poll()
                if (p2 != null && p2.unsafeComplete(v)) {
                  // If the transfer is successful, complete
                  // the put's promise. If the consumer's fiber
                  // became interrupted, the completion will be
                  // ignored.
                  p.unsafeComplete(())
                } else {
                  // If the transfer to the consumer fails, requeue
                  // the producer's operation.
                  puts.add(t)
                }
              }
              flush()
            }
          }
        }
      }
    }
}
