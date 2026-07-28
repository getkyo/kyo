package kyo.net.internal.transport

import kyo.*
import kyo.net.NetException

/** The typed result of one recv, parsed BEFORE the read pump sees it, so a zero-length read is
  * never overloaded across distinct end-states.
  *
  * A zero-length read is not one signal: `recv == 0` is a peer FIN, EAGAIN (would-block) is "no
  * bytes yet", and a local `shutdown(SHUT_RD)` also surfaces as `recv == 0`. Parsing the recv into
  * exactly one of these cases BEFORE delivery makes the read pump's match exhaustive: EAGAIN re-arms
  * (it is NOT EOF), a peer FIN and a local shutdown are distinct end reasons, and a TLS close_notify
  * is its own clean close. `LocalShutdown` is set only from a known local half-close transition,
  * never inferred from `recv == 0`, so a peer FIN and a self-inflicted shutdown stay distinguishable.
  *
  *   - [[Bytes]]: `n > 0`, deliver the span.
  *   - [[WouldBlock]]: EAGAIN, re-arm; NOT EOF (residual is handled by the consumer-paced re-dispatch).
  *   - [[PeerFin]]: `recv == 0` with no local shutdown: an orderly peer EOF.
  *   - [[LocalShutdown]]: `recv == 0` AFTER our own `shutdown(SHUT_RD)` (a known local transition).
  *   - [[CleanClose]]: a TLS close_notify was consumed (RFC 8446 6.1).
  *   - [[Failed]]: a hard error, carried as a typed [[NetException]].
  */
private[kyo] enum ReadOutcome derives CanEqual:
    case Bytes(span: Span[Byte])
    case WouldBlock
    case PeerFin
    case LocalShutdown
    case CleanClose
    case Failed(cause: NetException)
end ReadOutcome
