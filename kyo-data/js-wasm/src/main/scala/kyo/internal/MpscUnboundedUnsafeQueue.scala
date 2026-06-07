package kyo.internal

final private[kyo] class MpscUnboundedUnsafeQueue[A](chunkSize: Int)
    extends UnboundedUnsafeQueueBase[A](Math.max(2, chunkSize))

private[kyo] object MpscUnboundedUnsafeQueue
