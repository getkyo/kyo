package kyo.net.internal.transport

import kyo.net.NetException

/** How [[IoDriver.release]] lets go of a handle. `Graceful` closes it after the bytes it owed reached the kernel, so the peer reads them and
  * then the end of the stream. `Abortive` drops what is still owed: no close_notify, and `SO_LINGER {1, 0}` (or the platform's equivalent)
  * so the peer sees a reset rather than a FIN that would pass the truncated stream off as complete.
  */
private[kyo] enum ReleaseMode derives CanEqual:
    case Graceful
    case Abortive(cause: NetException)
end ReleaseMode
