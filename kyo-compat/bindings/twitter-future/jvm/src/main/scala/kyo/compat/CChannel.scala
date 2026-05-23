package kyo.compat

import com.twitter.concurrent.AsyncQueue
import com.twitter.concurrent.AsyncSemaphore
import com.twitter.concurrent.Permit
import com.twitter.util.Return

/** A bounded FIFO channel implemented as a `final class` combining `com.twitter.concurrent.AsyncSemaphore` (capacity bound) +
  * `com.twitter.concurrent.AsyncQueue` (FIFO buffer). Twitter Future has no native bounded channel primitive, so the structure is
  * hand-rolled. Producers acquire a permit, then enqueue the item paired with its permit; consumers dequeue and release the permit.
  * `put`/`take` compose natively with Twitter `Future` and never block a thread. `poll` is a non-blocking peek that returns `None` when the
  * buffer is empty.
  */
final class CChannel[A](capacity: Int):

    private val capacitySem                    = new AsyncSemaphore(capacity)
    private val queue: AsyncQueue[(A, Permit)] = new AsyncQueue[(A, Permit)]()

    /** Identity on the carrier; provided for surface uniformity with the opaque-type bindings. */
    inline def lower: CChannel[A] = this

    /** Enqueues `v`; suspends when the channel is full. */
    inline def put(inline v: A): CIO[Unit] =
        CIO.deferLift {
            capacitySem.acquire().map { permit =>
                val _ = queue.offer((v, permit))
                ()
            }
        }

    /** Dequeues the next element; suspends when the channel is empty. */
    inline def take: CIO[A] =
        CIO.deferLift {
            queue.poll().map { case (a, permit) =>
                permit.release()
                a
            }
        }

    /** Non-blocking dequeue: `Some(a)` if an element is available, `None` otherwise. */
    inline def poll: CIO[Option[A]] =
        CIO.defer {
            if queue.size > 0 then
                val f = queue.poll()
                if f.isDefined then
                    f.poll match
                        case Some(Return((a, permit))) =>
                            permit.release()
                            Some(a)
                        case _ => None
                else None
                end if
            else None
        }

end CChannel

object CChannel:

    /** Allocates a bounded FIFO channel of the given capacity. */
    inline def init[A](inline capacity: Int): CIO[CChannel[A]] =
        CIO.defer(new CChannel[A](capacity))

    /** Wraps an existing `CChannel` instance. Identity on the carrier. */
    inline def lift[A](inline u: CChannel[A]): CChannel[A] = u

end CChannel
