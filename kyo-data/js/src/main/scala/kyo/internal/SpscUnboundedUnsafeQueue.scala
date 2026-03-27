package kyo.internal

final private[kyo] class SpscUnboundedUnsafeQueue[A](chunkSize: Int)
    extends UnboundedUnsafeQueueBase[A](Math.max(8, chunkSize))

private[kyo] object SpscUnboundedUnsafeQueue
