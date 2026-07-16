package kyo.net.internal.transport

import kyo.*

/** Why a connection's pumps tore the connection down, threaded from the pump that observed the end through `closeFn` to the connection's
  * close-cause home so the cause is recorded, never dropped. Aligned with [[ReadOutcome]]: an orderly peer FIN, a local shutdown, an
  * authenticated TLS close_notify, a transport failure carrying its typed [[kyo.net.NetException]] leaf, or the channel closing under the pump.
  */
private[kyo] enum TeardownCause derives CanEqual:
    case PeerFin
    case LocalShutdown
    case CleanClose
    case Failed(cause: kyo.net.NetException)
    case ChannelClosed
end TeardownCause
