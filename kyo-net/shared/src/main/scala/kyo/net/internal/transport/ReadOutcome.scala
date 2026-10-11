package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException

/** The typed result of one recv, parsed BEFORE the read pump sees it, so a zero-length read is
  * never overloaded across distinct end-states.
  *
  * A zero-length read is not one signal: `recv == 0` is a peer FIN, while EAGAIN (would-block) is
  * "no bytes yet". Parsing the recv into exactly one of these cases BEFORE delivery makes the read
  * pump's match exhaustive: EAGAIN re-arms (it is NOT EOF), and a TLS close_notify is its own clean
  * close.
  *
  *   - [[ReadOutcome.Bytes]]: `n > 0`, deliver the span.
  *   - [[ReadOutcome.WouldBlock]]: EAGAIN, re-arm; NOT EOF (residual is handled by the consumer-paced re-dispatch).
  *   - [[ReadOutcome.PeerFin]]: `recv == 0`: an orderly peer EOF.
  *   - [[ReadOutcome.CleanClose]]: a TLS close_notify was consumed (RFC 8446 6.1).
  *   - [[ReadOutcome.Failed]]: a hard error, carried as a typed [[NetException]].
  */
private[kyo] enum ReadOutcome derives CanEqual:
    case Bytes(span: Span[Byte])
    case WouldBlock
    case PeerFin
    case CleanClose
    case Failed(cause: NetException)
end ReadOutcome
