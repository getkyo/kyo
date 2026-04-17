package kyo.internal.transport

import kyo.*

/** Result of a synchronous (non-blocking) write attempt on a socket handle.
  *
  * Drives the WritePump state machine: Done -> take next span from channel, Partial -> await writable then retry with remaining bytes,
  * Error -> teardown.
  */
private[kyo] enum WriteResult derives CanEqual:
    /** All bytes written successfully. */
    case Done

    /** Partial write — socket send buffer was full. Caller should awaitWritable, then retry with remaining. */
    case Partial(remaining: Span[Byte])

    /** Unrecoverable write error — connection should be closed. */
    case Error
end WriteResult
