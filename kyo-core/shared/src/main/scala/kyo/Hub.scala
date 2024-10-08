package kyo

import Hub.*
import java.util.concurrent.CopyOnWriteArraySet

/** A Hub is a multi-producer, multi-consumer channel that broadcasts messages to all listeners.
  *
  * @tparam A
  *   the type of elements in the Hub
  */
class Hub[A] private[kyo] (
    ch: Channel[A],
    fiber: Fiber[Nothing, Unit],
    listeners: CopyOnWriteArraySet[Channel[A]]
)(using initFrame: Frame):

    /** Returns the current size of the Hub.
      *
      * @return
      *   the number of elements currently in the Hub
      */
    def size(using Frame): Int < IO = ch.size

    /** Attempts to offer an element to the Hub without blocking.
      *
      * @param v
      *   the element to offer
      * @return
      *   true if the element was added, false otherwise
      */
    def offer(v: A)(using Frame): Boolean < IO = ch.offer(v)

    /** Offers an element to the Hub without returning a result.
      *
      * @param v
      *   the element to offer
      */
    def offerDiscard(v: A)(using Frame): Unit < IO = ch.offerDiscard(v)

    /** Checks if the Hub is empty.
      *
      * @return
      *   true if the Hub is empty, false otherwise
      */
    def empty(using Frame): Boolean < IO = ch.empty

    /** Checks if the Hub is full.
      *
      * @return
      *   true if the Hub is full, false otherwise
      */
    def full(using Frame): Boolean < IO = ch.full

    /** Creates a fiber that puts an element into the Hub.
      *
      * @param v
      *   the element to put
      * @return
      *   a Fiber that, when run, will put the element into the Hub
      */
    def putFiber(v: A)(using Frame): Fiber[Nothing, Unit] < IO = ch.putFiber(v)

    /** Puts an element into the Hub, potentially blocking if the Hub is full.
      *
      * @param v
      *   the element to put
      */
    def put(v: A)(using Frame): Unit < Async = ch.put(v)

    /** Checks if the Hub is closed.
      *
      * @return
      *   true if the Hub is closed, false otherwise
      */
    def closed(using Frame): Boolean < IO = ch.closed

    /** Closes the Hub and all its listeners.
      *
      * @return
      *   a Maybe containing any remaining elements in the Hub
      */
    def close(using frame: Frame): Maybe[Seq[A]] < IO =
        fiber.interruptDiscard(Result.Panic(Closed("Hub", initFrame, frame))).andThen {
            ch.close.map { r =>
                IO {
                    val array = listeners.toArray()
                    listeners.removeIf(_ => true)
                    Loop.indexed { idx =>
                        if idx == array.length then Loop.done
                        else
                            array(idx).asInstanceOf[Channel[A]].close
                                .map(_ => Loop.continue)
                    }.andThen(r)
                }
            }
        }

    /** Creates a new listener for this Hub with default buffer size.
      *
      * @return
      *   a new Listener
      */
    def listen(using Frame): Listener[A] < IO =
        listen(0)

    /** Creates a new listener for this Hub with specified buffer size.
      *
      * @param bufferSize
      *   the size of the buffer for the new listener
      * @return
      *   a new Listener
      */
    def listen(bufferSize: Int)(using frame: Frame): Listener[A] < IO =
        def fail = IO(throw Closed("Hub", initFrame, frame))
        closed.map {
            case true => fail
            case false =>
                Channel.init[A](bufferSize).map { child =>
                    IO {
                        listeners.add(child)
                        closed.map {
                            case true =>
                                // race condition
                                IO {
                                    listeners.remove(child)
                                    fail
                                }
                            case false =>
                                new Listener[A](this, child)
                        }
                    }
                }
        }
    end listen

    private[kyo] def remove(child: Channel[A])(using Frame): Unit < IO =
        IO {
            listeners.remove(child)
            ()
        }
end Hub

object Hub:

    /** Initializes a new Hub with the specified capacity.
      *
      * @param capacity
      *   the maximum number of elements the Hub can hold
      * @tparam A
      *   the type of elements in the Hub
      * @return
      *   a new Hub instance
      */
    def init[A](capacity: Int)(using Frame): Hub[A] < IO =
        Channel.init[A](capacity).map { ch =>
            IO {
                val listeners = new CopyOnWriteArraySet[Channel[A]]
                Async.run {
                    Loop.foreach {
                        ch.take.map { v =>
                            IO {
                                val puts =
                                    listeners.toArray
                                        .toList.asInstanceOf[List[Channel[A]]]
                                        .map(child => Abort.run[Throwable](child.put(v)))
                                Async.parallel(puts).map(_ => Loop.continue)
                            }
                        }
                    }
                }.map { fiber =>
                    new Hub(ch, fiber, listeners)
                }
            }
        }

    /** A Listener represents a subscriber to a Hub.
      *
      * @tparam A
      *   the type of elements the Listener receives
      */
    class Listener[A] private[kyo] (hub: Hub[A], child: Channel[A]):

        /** Returns the current size of the Listener's buffer.
          *
          * @return
          *   the number of elements currently in the Listener's buffer
          */
        def size(using Frame): Int < IO = child.size

        /** Checks if the Listener's buffer is empty.
          *
          * @return
          *   true if the Listener's buffer is empty, false otherwise
          */
        def empty(using Frame): Boolean < IO = child.empty

        /** Checks if the Listener's buffer is full.
          *
          * @return
          *   true if the Listener's buffer is full, false otherwise
          */
        def full(using Frame): Boolean < IO = child.full

        /** Attempts to retrieve and remove the head of the Listener's buffer without blocking.
          *
          * @return
          *   a Maybe containing the head element if available, or empty if the buffer is empty
          */
        def poll(using Frame): Maybe[A] < IO = child.poll

        /** Creates a fiber that takes an element from the Listener's buffer.
          *
          * @return
          *   a Fiber that, when run, will take an element from the Listener's buffer
          */
        def takeFiber(using Frame): Fiber[Nothing, A] < IO = child.takeFiber

        /** Takes an element from the Listener's buffer, potentially blocking if the buffer is empty.
          *
          * @return
          *   the next element from the Listener's buffer
          */
        def take(using Frame): A < Async = child.take

        /** Checks if the Listener is closed.
          *
          * @return
          *   true if the Listener is closed, false otherwise
          */
        def closed(using Frame): Boolean < IO = child.closed

        /** Closes the Listener and removes it from the Hub.
          *
          * @return
          *   a Maybe containing any remaining elements in the Listener's buffer
          */
        def close(using Frame): Maybe[Seq[A]] < IO =
            hub.remove(child).andThen(child.close)

    end Listener
end Hub
