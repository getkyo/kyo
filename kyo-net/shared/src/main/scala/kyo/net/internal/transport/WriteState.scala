package kyo.net.internal.transport

import kyo.*

/** The named write-pump state, held in one atomic cell and advanced by single-CAS transitions.
  *
  * The write-pump mode is one immutable state holding the partial-write tail inline, in one atomic
  * cell. Only one transition out of each state wins the CAS, so a second writable that races a take
  * cannot run with a cleared `pending`: there is no separate `pending` field to clear, only a state
  * to CAS, and the loser observes the winner's state. Both `WriteResult.Partial` (socket-full park)
  * and `WriteResult.TailPartial` (tail high-water park) carry the full span and offset inline in
  * their respective states.
  *
  *   - [[Idle]]: awaiting the next channel take.
  *   - [[Flushing]]: a span is being written; `pending`/`offset` is the outstanding tail.
  *   - [[AwaitingWritable]]: a partial write parked on socket writability.
  *   - [[Backpressured]]: parked on the TLS pending-cipher / raw tail high-water bound.
  *   - [[TornDown]]: terminal; teardown has failed any parked waiter.
  */
private[kyo] enum WriteState derives CanEqual:
    case Idle
    case Flushing(pending: Span[Byte], offset: Int)
    case AwaitingWritable(pending: Span[Byte], offset: Int)
    case Backpressured(pending: Span[Byte], offset: Int)
    case TornDown
end WriteState
