package kyo.compat

import zio.*

/** Underlying carrier is `zio.Queue[A]`, a bounded FIFO async queue. Operations propagate ZIO `Trace` through `(using inline trace: Trace)`
  * on every entry point. `lift` and `lower` are identity since the carrier is already a native ZIO queue. `close`/`shutdown` are not
  * exposed on the compat surface; the channel lives until the owning fiber terminates.
  */
opaque type CChannel[A] = Queue[A]

object CChannel:

    /** Allocates a bounded FIFO channel of the given capacity. */
    inline def init[A](inline capacity: Int)(using inline trace: Trace): CIO[CChannel[A]] =
        CIO.lift(Queue.bounded[A](capacity))

    /** Wraps a native `zio.Queue` as a `CChannel`. Identity on the carrier. */
    inline def lift[A](inline u: Queue[A]): CChannel[A] = u

    extension [A](inline self: CChannel[A])

        /** Unwraps to the native `zio.Queue`. Identity on the carrier. */
        inline def lower: Queue[A] = self

        /** Enqueues `v`; suspends when the channel is full. */
        inline def put(inline v: A)(using inline trace: Trace): CIO[Unit] =
            CIO.lift(self.offer(v).unit)

        /** Dequeues the next element; suspends when the channel is empty. */
        inline def take(using inline trace: Trace): CIO[A] =
            CIO.lift(self.take)

        /** Non-blocking dequeue: `Some(a)` if an element is available, `None` otherwise. */
        inline def poll(using inline trace: Trace): CIO[Option[A]] =
            CIO.lift(self.poll)
    end extension

end CChannel
