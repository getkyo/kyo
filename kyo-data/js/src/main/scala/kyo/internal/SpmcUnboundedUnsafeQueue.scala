package kyo.internal

final private[kyo] class SpmcUnboundedUnsafeQueue[A](chunkSize: Int)
    extends UnboundedUnsafeQueueBase[A](Math.max(8, chunkSize))

private[kyo] object SpmcUnboundedUnsafeQueue
