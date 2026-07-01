package kyo.internal

import kyo.*

/** The transport-level error type for the forked-worker transport: a broken aeron session, a dead
  * worker process, an undecodable frame. The forked backend maps a `TransportError` to a typed
  * `CompilerTransportException` at its boundary so the op surface stays `Abort[CompilerException]`,
  * distinct from a worker-side op failure carried in-band as `Response.Failed`.
  */
final private[kyo] case class TransportError(message: String) derives CanEqual
