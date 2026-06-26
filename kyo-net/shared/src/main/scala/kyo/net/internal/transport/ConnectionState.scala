package kyo.net.internal.transport

/** The named lifecycle state of a connection, held in one atomic cell and advanced by single-CAS
  * transitions (the kyo Queue/Gate lifecycle idiom).
  *
  * The lifecycle is one named state in one atomic cell. Naming the states makes each transition a
  * single CAS one carrier wins: the loser re-reads the winner's state rather than acting on a stale
  * field, so a check-one-field-act-on-another race is unrepresentable.
  *
  *   - [[Created]]: built, pumps not yet started.
  *   - [[Established]]: pumps running, normal I/O.
  *   - [[Upgrading]]: a STARTTLS detach won; the fd is kept open and NOT torn down (the connection is
  *     closed to its own pumps yet its socket lives on for the TLS upgrade).
  *   - [[Closing]]: a close was initiated; the outbound side is draining before teardown.
  *   - [[Closed]]: terminal; the handle has been released exactly once.
  */
private[kyo] enum ConnectionState derives CanEqual:
    case Created
    case Established
    case Upgrading
    case Closing
    case Closed
end ConnectionState
