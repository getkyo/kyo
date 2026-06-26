package kyo.internal

import kyo.*

/** The id-carrying wire envelope `kyo.Exchange` sends and receives over the aeron `Topic`.
  *
  * Exchange uses one wire type for both directions; `Envelope` pairs the Exchange-owned correlation
  * id with either a `Request` (host -> worker) or a `Response` (worker -> host). It derives
  * [[Compiler.AsMessage]], so `Topic.publish[Envelope]`/`Topic.stream[Envelope]` carry it natively,
  * with the id stamped and read by the Exchange `encode`/`decode` callbacks. The request and reply
  * ride separate `aeron:ipc` uris, so a host never reads its own request back.
  */
private[kyo] enum Envelope derives CanEqual, Compiler.AsMessage:
    case Req(id: Int, request: Request)
    case Resp(id: Int, response: Response)
