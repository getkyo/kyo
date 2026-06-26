package kyo.net.internal.transport

import kyo.*

/** Result of a synchronous (non-blocking) write attempt on a socket handle.
  *
  * Drives the WritePump state machine: Done -> take next span from channel, Partial -> await writable then retry with remaining bytes,
  * TailPartial -> park on write-tail backpressure bound then retry, Error -> teardown.
  */
private[kyo] enum WriteResult derives CanEqual:
    /** All bytes written successfully. */
    case Done

    /** Partial write: socket send buffer was full. `remaining` is the FULL original span; `offset` is the count of head bytes already sent.
      * The pump re-presents the SAME span with the advanced offset (no re-slice); the driver sends `[offset, remaining.size)`. The pump
      * parks on socket writability (AwaitingWritable) until the kernel send buffer has room.
      */
    case Partial(remaining: Span[Byte], offset: Int)

    /** Tail-bound partial write: the write-backpressure tail has reached the high-water mark (the send queue is full, not the kernel socket
      * buffer). `remaining` is the FULL original span; `offset` is the count of head bytes already sent. The pump parks on the tail draining
      * below the low-water mark (Backpressured) until the drain path releases the waiter via the handle slot.
      */
    case TailPartial(remaining: Span[Byte], offset: Int)

    /** Unrecoverable write error: connection should be closed. */
    case Error
end WriteResult
