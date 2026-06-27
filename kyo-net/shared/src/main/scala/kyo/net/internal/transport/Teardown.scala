package kyo.net.internal.transport

/** The named teardown state of a connection, advanced by single-CAS transitions and gated by a
  * per-backend "can I release now?" predicate.
  *
  * Unifies the four per-backend exactly-once teardown dances into one machine: the fd is closed
  * exactly once and resources are freed exactly once, by whichever carrier observes the predicate
  * true with the close requested. The predicate is injected per backend (io_uring in-flight count
  * == 0; poller guard holders == 0; NIO key flushed; JS destroyed), the Go increfAndClose /
  * decref-to-zero and Netty DelayedClose/canCloseNow shape.
  *
  * The `ReleaseRequested -> AwaitingInFlight` gate waits on the WRITE-side drain and the in-flight
  * count ONLY, NEVER on the inbound (read-side) channel draining: the fd close is prompt, and the
  * in-memory inbound channel is independent of the fd (a live consumer drains it separately). The
  * release MUST NOT gate on the inbound channel draining: a pooled connection with no reader would
  * then never release, leaking the peer-FIN'd fd in CLOSE_WAIT.
  *
  *   - [[Live]]: no close requested.
  *   - [[ReleaseRequested]]: close requested; parked waiters being unblocked.
  *   - [[AwaitingInFlight]]: write-side drained; awaiting the in-flight count to reach zero.
  *   - [[Released]]: terminal; the fd is closed exactly once, resources freed exactly once.
  */
private[kyo] enum TeardownState derives CanEqual:
    case Live
    case ReleaseRequested
    case AwaitingInFlight
    case Released
end TeardownState
