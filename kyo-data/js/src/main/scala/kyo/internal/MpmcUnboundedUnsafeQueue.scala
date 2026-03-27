package kyo.internal

final private[kyo] class MpmcUnboundedUnsafeQueue[A](chunkSize: Int, maxPooledChunks: Int = 2)
    extends UnboundedUnsafeQueueBase[A](Math.max(8, chunkSize))

private[kyo] object MpmcUnboundedUnsafeQueue
