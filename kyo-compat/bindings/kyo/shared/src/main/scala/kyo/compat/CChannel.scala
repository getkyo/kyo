package kyo.compat

import kyo.*

/** Underlying carrier is `kyo.Channel[A]`, a bounded FIFO queue. `lift` and `lower` are identity since the carrier is already a kyo-native
  * channel. `put`/`take`/`poll` discharge `Abort[Closed]` as panic, because the CIO surface never closes the channel. Note that
  * `kyo.Channel` rounds capacity up to the next power of two on the JVM; pass exact powers when the precise bound matters.
  */
opaque type CChannel[A] = Channel[A]

object CChannel:

    /** Allocates a bounded FIFO channel of the given capacity. */
    inline def init[A](inline capacity: Int)(using inline frame: Frame): CIO[CChannel[A]] =
        CIO.lift(Channel.initUnscoped[A](capacity))

    /** Wraps a native `kyo.Channel` as a `CChannel`. Identity on the carrier. */
    inline def lift[A](inline u: Channel[A]): CChannel[A] = u

    extension [A](inline self: CChannel[A])

        /** Unwraps to the native `kyo.Channel`. Identity on the carrier. */
        inline def lower: Channel[A] = self

        /** Enqueues `v`; suspends when the channel is full. */
        inline def put(inline v: A)(using inline frame: Frame): CIO[Unit] = CIO.lift(Channel.put(self.lower)(v))

        /** Dequeues the next element; suspends when the channel is empty. */
        inline def take(using inline frame: Frame): CIO[A] = CIO.lift(Channel.take(self.lower))

        /** Non-blocking dequeue: `Some(a)` if an element is available, `None` otherwise. */
        inline def poll(using inline frame: Frame): CIO[Option[A]] = CIO.lift(Channel.poll(self.lower).map(_.toOption))

    end extension

end CChannel
