package kyo.net.internal.posix

/** The named half-close state of a posix connection's read side, written by the loop carrier only.
  *
  * The half-close is one immutable state, written by the loop carrier alone. The public
  * `Connection.CloseReason` is a total function of this state, so the close reason is derived from one
  * consistent value rather than a torn read of independent flags.
  *
  *   - [[Open]]: no half-close observed.
  *   - [[PeerHalfClosePending]]: a FIN/EOF edge was seen but the terminal `recv == 0` not yet delivered.
  *   - [[PeerCleanClose]]: the peer's close_notify was consumed (orderly, RFC 8446 6.1).
  *   - [[PeerEof]]: a bare TCP FIN with no close_notify (a truncation).
  */
private[kyo] enum HalfCloseState derives CanEqual:
    case Open
    case PeerHalfClosePending
    case PeerCleanClose
    case PeerEof
end HalfCloseState
