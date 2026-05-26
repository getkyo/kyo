package kyo.compat

import cats.effect.IO
import cats.effect.std.Queue

/** Underlying carrier is `cats.effect.std.Queue[IO, A]`, a bounded FIFO async queue. cats-effect has no `Frame` / `Trace` to propagate.
  * `lift` and `lower` are identity since the carrier is already a native CE queue. CE `Queue` has no shutdown semantic, so `close` is not
  * exposed on the compat surface.
  */
opaque type CChannel[A] = Queue[IO, A]

object CChannel:

    /** Allocates a bounded FIFO channel of the given capacity. */
    inline def init[A](inline capacity: Int): CIO[CChannel[A]] =
        CIO.lift(Queue.bounded[IO, A](capacity))

    /** Wraps a native `cats.effect.std.Queue` as a `CChannel`. Identity on the carrier. */
    inline def lift[A](inline u: Queue[IO, A]): CChannel[A] = u

    extension [A](inline self: CChannel[A])

        /** Unwraps to the native `cats.effect.std.Queue`. Identity on the carrier. */
        inline def lower: Queue[IO, A] = self

        /** Enqueues `v`; suspends when the channel is full. */
        inline def put(inline v: A): CIO[Unit] = CIO.lift(self.offer(v))

        /** Dequeues the next element; suspends when the channel is empty. */
        inline def take: CIO[A] = CIO.lift(self.take)

        /** Non-blocking dequeue: `Some(a)` if an element is available, `None` otherwise. */
        inline def poll: CIO[Option[A]] = CIO.lift(self.tryTake)

    end extension

end CChannel
