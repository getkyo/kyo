package kyo.internal

import kyo.*

/** The transport-level error type for the forked-worker transport: a broken aeron session, a dead
  * worker process, an undecodable frame. Distinct from `CompilerError.Fatal`, which is a typed
  * op-level failure carried in-band as `Response.Failed`. The forked backend maps a `TransportError`
  * to `CompilerError.Fatal` at its boundary so the op surface stays `Abort[CompilerError]`.
  */
final private[kyo] case class TransportError(message: String) derives CanEqual
