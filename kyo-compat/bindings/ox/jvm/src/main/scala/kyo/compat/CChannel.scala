package kyo.compat

import java.util.concurrent.LinkedBlockingQueue

/** Underlying carrier is `java.util.concurrent.LinkedBlockingQueue[A]`, a bounded FIFO queue. `lift` and `lower` are identity since the
  * carrier is already a native blocking queue. `put`/`take` block the calling thread when full/empty (the native Ox idiom); `poll` is
  * non-blocking and returns `None` when empty.
  */
opaque type CChannel[A] = LinkedBlockingQueue[A]

object CChannel:

    /** Allocates a bounded FIFO channel of the given capacity. */
    inline def init[A](inline capacity: Int): CIO[CChannel[A]] =
        CIO.defer(new LinkedBlockingQueue[A](capacity))

    /** Wraps a native `LinkedBlockingQueue` as a `CChannel`. Identity on the carrier. */
    inline def lift[A](inline u: LinkedBlockingQueue[A]): CChannel[A] = u

    extension [A](inline self: CChannel[A])

        /** Unwraps to the native `LinkedBlockingQueue`. Identity on the carrier. */
        inline def lower: LinkedBlockingQueue[A] = self

        /** Enqueues `v`; blocks the calling thread when the channel is full. */
        inline def put(inline v: A): CIO[Unit] = CIO.defer(self.put(v))

        /** Dequeues the next element; blocks the calling thread when the channel is empty. */
        inline def take: CIO[A] = CIO.defer(self.take())

        /** Non-blocking dequeue: `Some(a)` if an element is available, `None` otherwise. */
        inline def poll: CIO[Option[A]] = CIO.defer(Option(self.poll()))

    end extension

end CChannel
