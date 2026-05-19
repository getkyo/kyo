package kyo.compat

import com.twitter.concurrent.AsyncQueue
import com.twitter.concurrent.AsyncSemaphore
import com.twitter.concurrent.Permit
import com.twitter.util.Return
import scala.annotation.publicInBinary

/** A bounded FIFO channel built from `AsyncSemaphore` (capacity bound) + `AsyncQueue` (FIFO buffer). Producers acquire a permit, then
  * enqueue the item paired with its permit; consumers dequeue and release the permit. `put`/`take` compose natively with Twitter `Future`
  * and never block a thread. `poll` is a non-blocking peek that returns `None` when the buffer is empty.
  */
final class CChannel[A] @publicInBinary private[compat] (capacity: Int):

    private val capacitySem                    = new AsyncSemaphore(capacity)
    private val queue: AsyncQueue[(A, Permit)] = new AsyncQueue[(A, Permit)]()

    inline def lower: CChannel[A] = this

    inline def put(inline v: A): CIO[Unit] =
        CIO.deferLift {
            capacitySem.acquire().map { permit =>
                val _ = queue.offer((v, permit))
                ()
            }
        }

    inline def take: CIO[A] =
        CIO.deferLift {
            queue.poll().map { case (a, permit) =>
                permit.release()
                a
            }
        }

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

    inline def init[A](inline capacity: Int): CIO[CChannel[A]] =
        CIO.defer(new CChannel[A](capacity))

    inline def lift[A](inline u: CChannel[A]): CChannel[A] = u

end CChannel
