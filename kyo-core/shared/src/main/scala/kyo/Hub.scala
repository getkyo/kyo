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
    fiber: Fiber[Closed, Unit],
    listeners: CopyOnWriteArraySet[Channel[A]]
)(using initFrame: Frame):

    /** Returns the current size of the Hub.
      *
      * @return
      *   the number of elements currently in the Hub
      */
    def size(using Frame): Int < (IO & Abort[Closed]) = ch.size

    /** Attempts to offer an element to the Hub without blocking.
      *
      * @param v
      *   the element to offer
      * @return
      *   true if the element was added, false otherwise
      */
    def offer(v: A)(using Frame): Boolean < (IO & Abort[Closed]) = ch.offer(v)

    /** Offers an element to the Hub without returning a result.
      *
      * @param v
      *   the element to offer
      */
    def offerDiscard(v: A)(using Frame): Unit < (IO & Abort[Closed]) = ch.offerDiscard(v)

    /** Checks if the Hub is empty.
      *
      * @return
      *   true if the Hub is empty, false otherwise
      */
    def empty(using Frame): Boolean < (IO & Abort[Closed]) = ch.empty

    /** Checks if the Hub is full.
      *
      * @return
      *   true if the Hub is full, false otherwise
      */
    def full(using Frame): Boolean < (IO & Abort[Closed]) = ch.full

    /** Creates a fiber that puts an element into the Hub.
      *
      * @param v
      *   the element to put
      * @return
      *   a Fiber that, when run, will put the element into the Hub
      */
    def putFiber(v: A)(using Frame): Fiber[Closed, Unit] < IO = ch.putFiber(v)

    /** Puts an element into the Hub, potentially blocking if the Hub is full.
      *
      * @param v
      *   the element to put
      */
    def put(v: A)(using Frame): Unit < (Async & Abort[Closed]) = ch.put(v)

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
        fiber.interruptDiscard(Result.Panic(Closed("Hub", initFrame))).andThen {
            ch.close.map { r =>
                IO {
                    val array = listeners.toArray()
                    discard(listeners.removeIf(_ => true))
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
    def listen(using Frame): Listener[A] < (IO & Abort[Closed] & Resource) =
        listen(0)

    /** Creates a new listener for this Hub with specified buffer size.
      *
      * @param bufferSize
      *   the size of the buffer for the new listener
      * @return
      *   a new Listener
      */
    def listen(bufferSize: Int)(using frame: Frame): Listener[A] < (IO & Abort[Closed] & Resource) =
        def fail = Abort.fail(Closed("Hub", initFrame))
        closed.map {
            case true => fail
            case false =>
                Channel.init[A](bufferSize).map { child =>
                    IO {
                        discard(listeners.add(child))
                        closed.map {
                            case true =>
                                // race condition
                                IO {
                                    discard(listeners.remove(child))
                                    fail
                                }
                            case false =>
                                Resource.acquireRelease(new Listener[A](this, child)): listener =>
                                    listener.close.unit
                        }
                    }
                }
        }
    end listen

    private[kyo] def remove(child: Channel[A])(using Frame): Unit < IO =
        IO {
            discard(listeners.remove(child))
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
        initWith[A](capacity)(identity)

    /** Uses a new Hub with the given type and capacity.
      * @param capacity
      *   the maximum number of elements the Hub can hold
      * @param f
      *   The function to apply to the new Hub
      * @tparam A
      *   the type of elements in the Hub
      * @return
      *   The result of applying the function
      */
    def initWith[A](capacity: Int)[B, S](f: Hub[A] => B < S)(using Frame): B < (S & IO) =
        Channel.init[A](capacity).map { ch =>
            IO {
                val listeners = new CopyOnWriteArraySet[Channel[A]]
                Async.run {
                    Loop.foreach {
                        ch.take.map { v =>
                            IO {
                                val puts =
                                    listeners.toArray()
                                        .toList.asInstanceOf[List[Channel[A]]]
                                        .map(child => Abort.run[Throwable](child.put(v)))
                                Async.parallelUnbounded(puts).map(_ => Loop.continue)
                            }
                        }
                    }
                }.map { fiber =>
                    f(new Hub(ch, fiber, listeners))
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
        def size(using Frame): Int < (IO & Abort[Closed]) = child.size

        /** Checks if the Listener's buffer is empty.
          *
          * @return
          *   true if the Listener's buffer is empty, false otherwise
          */
        def empty(using Frame): Boolean < (IO & Abort[Closed]) = child.empty

        /** Checks if the Listener's buffer is full.
          *
          * @return
          *   true if the Listener's buffer is full, false otherwise
          */
        def full(using Frame): Boolean < (IO & Abort[Closed]) = child.full

        /** Attempts to retrieve and remove the head of the Listener's buffer without blocking.
          *
          * @return
          *   a Maybe containing the head element if available, or empty if the buffer is empty
          */
        def poll(using Frame): Maybe[A] < (IO & Abort[Closed]) = child.poll

        /** Creates a fiber that takes an element from the Listener's buffer.
          *
          * @return
          *   a Fiber that, when run, will take an element from the Listener's buffer
          */
        def takeFiber(using Frame): Fiber[Closed, A] < IO = child.takeFiber

        /** Takes an element from the Listener's buffer, potentially blocking if the buffer is empty.
          *
          * @return
          *   the next element from the Listener's buffer
          */
        def take(using Frame): A < (Async & Abort[Closed]) = child.take

        /** Takes [[n]] elements from the Listener's buffer, semantically blocking until enough elements are present. Note that if enough
          * elements are not added to the buffer it can block indefinitely.
          *
          * @return
          *   Chunk of [[n]] elements
          */
        def takeExactly(n: Int)(using Frame): Chunk[A] < (Abort[Closed] & Async) =
            child.takeExactly(n)

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

        /** Stream elements from listener, optionally specifying a maximum chunk size. In the absence of [[maxChunkSize]], chunk sizes will
          * be limited only by buffer capacity or the number of buffered elements at a given time. (Chunks can still be larger than buffer
          * capacity.) Stops streaming when listener is closed.
          *
          * @param maxChunkSize
          *   Maximum number of elements to take for each chunk
          *
          * @return
          *   Asynchronous stream of elements from listener
          */
        def stream(maxChunkSize: Int = Int.MaxValue)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Async] =
            child.streamUntilClosed(maxChunkSize)

        /** Like stream, but fails when listener is closed.
          *
          * @param maxChunkSize
          *   Maximum number of elements to take for each chunk
          *
          * @return
          *   Asynchronous stream of elements from listener
          */
        def streamFailing(maxChunkSize: Int = Int.MaxValue)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Abort[Closed] & Async] =
            child.stream(maxChunkSize)

    end Listener
end Hub
