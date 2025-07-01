package kyo

import Hub.*
import java.util.concurrent.CopyOnWriteArraySet

/** A multi-producer, multi-consumer primitive that broadcasts messages to multiple listeners.
  *
  * Hub provides fan-out functionality by delivering each published message to all active listeners. Unlike a regular Channel which delivers
  * messages to a single consumer, Hub enables multiple consumers to receive and process the same messages.
  *
  * Message flow and buffering:
  *   - Publishers send messages to the Hub's main buffer
  *   - A dedicated fiber distributes messages from the Hub buffer to each listener's individual buffer
  *   - Each listener consumes messages from its own buffer at its own pace
  *   - When any listener's buffer becomes full and the Hub's buffer is also full, backpressure is applied to publishers, affecting message
  *     delivery to all listeners
  *
  * @tparam A
  *   The type of messages that can be published through this Hub
  */
final class Hub[A] private[kyo] (
    ch: Channel[A],
    fiber: Fiber[Closed, Unit],
    listeners: CopyOnWriteArraySet[Listener[A]]
)(using initFrame: Frame):

    /** Attempts to offer an element to the Hub without blocking.
      *
      * If the Hub's buffer is full, this operation will return false immediately rather than waiting. The element will be delivered to all
      * active listeners if accepted.
      *
      * @param v
      *   the element to offer
      * @return
      *   true if the element was added successfully, false if the Hub's buffer was full
      */
    def offer(v: A)(using Frame): Boolean < (Sync & Abort[Closed]) = ch.offer(v)

    /** Offers an element to the Hub without returning a result.
      *
      * @param v
      *   the element to offer
      */
    def offerDiscard(v: A)(using Frame): Unit < (Sync & Abort[Closed]) = ch.offerDiscard(v)

    /** Checks if the Hub is empty.
      *
      * @return
      *   true if the Hub is empty, false otherwise
      */
    def empty(using Frame): Boolean < (Sync & Abort[Closed]) = ch.empty

    /** Checks if the Hub is full.
      *
      * @return
      *   true if the Hub is full, false otherwise
      */
    def full(using Frame): Boolean < (Sync & Abort[Closed]) = ch.full

    /** Puts an element into the Hub, blocking if necessary until space is available.
      *
      * This operation will block when both a listener's buffer is full and preventing the Hub from processing, and the Hub's buffer is
      * full. The element will be delivered to all active listeners once accepted.
      *
      * @param v
      *   the element to put
      */
    def put(v: A)(using Frame): Unit < (Async & Abort[Closed]) = ch.put(v)

    /** Puts multiple elements into the Hub as a batch.
      *
      * @param values
      *   The sequence of elements to put
      */
    def putBatch(values: Seq[A])(using Frame): Unit < (Abort[Closed] & Async) = ch.putBatch(values)

    /** Checks if the Hub is closed.
      *
      * @return
      *   true if the Hub is closed, false otherwise
      */
    def closed(using Frame): Boolean < Sync = ch.closed

    /** Closes the Hub and returns any remaining messages.
      *
      * When closed:
      *   - The Hub stops accepting new messages
      *   - All listeners are automatically closed
      *   - Any blocked publishers are unblocked with a Closed failure
      *   - All subsequent operations will fail with Closed
      *
      * @return
      *   a Maybe containing any messages that were in the Hub's buffer at the time of closing. Returns Absent if the close operation fails
      *   (e.g., if the Hub was already closed).
      */
    def close(using frame: Frame): Maybe[Seq[A]] < Sync =
        fiber.interruptDiscard(Result.Failure(Closed("Hub", initFrame))).andThen {
            ch.close.map { r =>
                Sync {
                    val l = Chunk.fromNoCopy(listeners.toArray()).asInstanceOf[Chunk[Listener[A]]]
                    discard(listeners.removeIf(_ => true)) // clear is not available in Scala Native
                    Kyo.foreachDiscard(l)(_.child.close.unit).andThen(r)
                }
            }
        }

    /** Creates a new listener for this Hub with the default buffer size.
      *
      * @return
      *   a new Listener that will receive messages from the Hub
      * @see
      *   [[Hub.DefaultBufferSize]] for the default buffer size used by this method
      */
    def listen(using Frame): Listener[A] < (Sync & Abort[Closed] & Resource) =
        listen(DefaultBufferSize)

    /** Creates a new listener for this Hub with the specified buffer size.
      *
      * @param bufferSize
      *   the size of the buffer for the new listener. When full, it may cause hub backpressure affecting all listeners
      * @return
      *   a new Listener with the specified buffer size
      */
    def listen(bufferSize: Int)(using frame: Frame): Listener[A] < (Sync & Abort[Closed] & Resource) =
        listen(bufferSize, _ => true)

    /** Creates a new listener for this Hub with a custom filter predicate.
      *
      * @param filter
      *   a predicate function that determines which messages this listener receives
      * @return
      *   a new Listener that will only receive messages matching the filter
      * @see
      *   [[Hub.DefaultBufferSize]] for the default buffer size used by this method
      */
    def listen(filter: A => Boolean)(using frame: Frame): Listener[A] < (Sync & Abort[Closed] & Resource) =
        listen(DefaultBufferSize, filter)

    /** Creates a new listener for this Hub with specified buffer size and filter.
      *
      * The listener will only receive messages that pass its filter predicate. Messages that don't match the filter are discarded without
      * consuming space in the listener's buffer. A new listener will only receive messages published after its creation - any messages
      * published before the listener was created are not received.
      *
      * If the Hub is closed when attempting to create a listener, this operation will fail. Listeners created with this method should be
      * properly closed when no longer needed, though they will be automatically closed if the Hub is closed.
      *
      * @param bufferSize
      *   the size of the buffer for the new listener. When full, it will cause backpressure
      * @param filter
      *   a predicate function that determines which messages this listener receives
      * @return
      *   a new Listener that will receive filtered messages from the Hub
      */
    def listen(bufferSize: Int, filter: A => Boolean)(using frame: Frame): Listener[A] < (Sync & Abort[Closed] & Resource) =
        def fail = Abort.fail(Closed("Hub", initFrame))
        closed.map {
            case true => fail
            case false =>
                Sync.Unsafe {
                    val child    = Channel.Unsafe.init[A](bufferSize, Access.SingleProducerMultiConsumer).safe
                    val listener = new Listener[A](this, child, filter)
                    discard(listeners.add(listener))
                    closed.map {
                        case true =>
                            // race condition
                            Sync {
                                discard(listeners.remove(listener))
                                fail
                            }
                        case false =>
                            Resource.acquireRelease(listener)(_.close.unit)
                    }
                }
        }
    end listen

    private[kyo] def remove(listener: Listener[A])(using Frame): Unit < Sync =
        Sync {
            discard(listeners.remove(listener))
        }
end Hub

object Hub:

    /** Default buffer size used for Hub listeners when no explicit size is specified. */
    inline def DefaultBufferSize: Int = 4096

    /** Initializes a new Hub with the default capacity.
      *
      * @tparam A
      *   the type of elements in the Hub
      * @return
      *   a new Hub instance with default buffer size
      * @see
      *   [[Hub.DefaultBufferSize]] for the default capacity value used by this method
      */
    def init[A](using Frame): Hub[A] < (Sync & Resource) =
        init(DefaultBufferSize)

    /** Initializes a new Hub with the specified capacity.
      *
      * @param capacity
      *   the maximum number of elements the Hub can hold
      * @tparam A
      *   the type of elements in the Hub
      * @return
      *   a new Hub instance
      */
    def init[A](capacity: Int)(using Frame): Hub[A] < (Sync & Resource) =
        initWith[A](capacity)(identity)

    /** Uses a new Hub with the given type and capacity.
      *
      * @param capacity
      *   the maximum number of elements the Hub can hold
      * @param f
      *   The function to apply to the new Hub
      * @tparam A
      *   the type of elements in the Hub
      * @return
      *   The result of applying the function
      */
    def initWith[A](capacity: Int)[B, S](f: Hub[A] => B < S)(using Frame): B < (S & Sync & Resource) =
        Sync.Unsafe {
            val channel          = Channel.Unsafe.init[A](capacity, Access.MultiProducerSingleConsumer).safe
            val listeners        = new CopyOnWriteArraySet[Listener[A]]
            def currentListeners = Chunk.fromNoCopy(listeners.toArray()).asInstanceOf[Chunk[Listener[A]]]
            Async.run {
                Loop.foreach {
                    channel.take.map { value =>
                        Abort.recover { error =>
                            bug(s"Hub publishing for value '$value' failed: $error")
                            Loop.continue
                        } {
                            Kyo.foreachDiscard(currentListeners) { listener =>
                                Abort.recover[Throwable](e => bug(s"Hub fiber failed to publish to listener: $e"))(
                                    listener.put(value)
                                )
                            }.andThen(Loop.continue)
                        }
                    }
                }
            }.map { fiber =>
                Resource
                    .acquireRelease(new Hub(channel, fiber, listeners))(_.close.unit)
                    .map(f)
            }
        }

    /** A subscriber to a Hub that receives and processes a filtered stream of messages.
      *
      * Each Listener maintains its own buffer and can process messages independently of other listeners. Messages published to the Hub are
      * evaluated against the Listener's filter predicate - only matching messages are delivered to the Listener's buffer. A Listener only
      * receives messages that were published after its creation.
      *
      * Message processing:
      *   - Messages matching the filter are added to the Listener's buffer
      *   - When the buffer is full, backpressure is applied to the Hub
      *   - The Listener can be closed independently of the Hub
      *
      * @tparam A
      *   the type of messages this Listener receives
      */
    final class Listener[A] private[kyo] (hub: Hub[A], private[kyo] val child: Channel[A], filter: A => Boolean):

        /** Returns the current size of the Listener's buffer.
          *
          * @return
          *   the number of elements currently in the Listener's buffer
          */
        def size(using Frame): Int < (Sync & Abort[Closed]) = child.size

        /** Checks if the Listener's buffer is empty.
          *
          * @return
          *   true if the Listener's buffer is empty, false otherwise
          */
        def empty(using Frame): Boolean < (Sync & Abort[Closed]) = child.empty

        /** Checks if the Listener's buffer is full.
          *
          * @return
          *   true if the Listener's buffer is full, false otherwise
          */
        def full(using Frame): Boolean < (Sync & Abort[Closed]) = child.full

        private[Hub] def put(value: A)(using Frame): Unit < (Async & Abort[Closed]) =
            if !filter(value) then ()
            else child.put(value)

        /** Attempts to retrieve and remove the head of the Listener's buffer without blocking.
          *
          * @return
          *   a Maybe containing the head element if available, or empty if the buffer is empty
          */
        def poll(using Frame): Maybe[A] < (Sync & Abort[Closed]) = child.poll

        /** Takes an element from the Listener's buffer, potentially blocking if the buffer is empty.
          *
          * This operation will block until:
          *   - A message matching the listener's filter becomes available
          *   - The Hub or this Listener is closed (resulting in a Closed failure)
          *
          * @return
          *   the next element from the Listener's buffer that matches this listener's filter
          * @throws Closed
          *   if either the Hub or this Listener has been closed
          */
        def take(using Frame): A < (Async & Abort[Closed]) = child.take

        /** Takes [[n]] elements from the Listener's buffer, blocking until enough elements are present. Note that if enough elements are
          * not added to the buffer it can block indefinitely.
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
        def closed(using Frame): Boolean < Sync = child.closed

        /** Closes the Listener and removes it from the Hub.
          *
          * @return
          *   a Maybe containing any remaining elements in the Listener's buffer
          */
        def close(using Frame): Maybe[Seq[A]] < Sync =
            hub.remove(this).andThen(child.close)

        /** Stream elements from listener, optionally specifying a maximum chunk size.
          *
          * This streaming operation:
          *   - Continues until the Hub or Listener is closed
          *   - Completes normally when closed (use streamFailing for failure behavior)
          *   - Only emits messages that match this listener's filter
          *   - Respects backpressure from downstream consumers
          *
          * @param maxChunkSize
          *   Maximum number of elements to include in each chunk. If not specified, chunks will be limited only by buffer capacity or
          *   available elements
          * @return
          *   An asynchronous stream of elements from the listener that completes when closed
          */
        def stream(maxChunkSize: Int = Stream.DefaultChunkSize)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Async] =
            child.streamUntilClosed(maxChunkSize)

        /** Like stream, but fails when listener is already closed.
          *
          * @param maxChunkSize
          *   Maximum number of elements to take for each chunk
          *
          * @return
          *   Asynchronous stream of elements from listener
          */
        def streamFailing(maxChunkSize: Int = Stream.DefaultChunkSize)(using Tag[Emit[Chunk[A]]], Frame): Stream[A, Abort[Closed] & Async] =
            child.stream(maxChunkSize)

        /** Takes up to max elements from the Listener's buffer without blocking.
          *
          * @param max
          *   Maximum number of elements to take
          * @return
          *   Chunk containing up to max elements
          */
        def drainUpTo(max: Int)(using Frame): Chunk[A] < (Sync & Abort[Closed]) = child.drainUpTo(max)
    end Listener
end Hub
