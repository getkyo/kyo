package kyo.internal

import kyo.*

/** The serializable, FFI-free wire payload for a host-node delta pushed over the kyo-ui
  * WebSocket as the body of an [[HtmlOp.HostUpdate]]. Opaque to kyo-ui: the transport carries
  * it by `data-kyo-path` and never interprets it; an external host renderer (kyo-threejs)
  * encodes its own feed variants and decodes them in its client island.
  *
  * The leaves drive the feed-by-signal-id path: the server feeds DATA addressed by a string
  * signal id and the client island writes a mirror `SignalRef` keyed by that id. `SignalUpdate`
  * carries a scalar/prop feed: a signal id and the `Json.encode`d string of the `Schema`-serialized
  * fed value `A`, decoded client-side with the same `Schema[A]` the island shares. `SignalChunk`
  * carries a structural feed: a signal id and the `Json.encode`d string of a `Schema`-serialized
  * `Chunk[A]` snapshot, which the client decodes and writes to a mirror `SignalRef[Chunk[A]]` so its
  * own keyed reconciler diffs locally. Every leaf is `Schema`-serializable and carries no
  * `js.Dynamic` and no closure, so the wire stays typed Scala (the wire never carries a function or a
  * raw three.js object).
  */
sealed private[kyo] trait HostPayload derives CanEqual, Schema

private[kyo] object HostPayload:
    // The feed-by-signal-id leaf (design 02-design-r2 D-002): a scalar prop feed addressed by a string
    // signal id. The carried value is an opaque `Json.encode`d string of the `Schema`-serialized fed
    // value `A`, decoded client-side with the same `Schema[A]` the island shares. The server feeds
    // DATA by signal id and the client writes a mirror `SignalRef[A]` the existing forkBoundRef/patchProp
    // path already patches; the string `encoded` keeps the leaf total for any `A: Schema`.
    final case class SignalUpdate(signalId: String, encoded: String) extends HostPayload
    // The structural feed-by-signal-id leaf (design 02-design-r2 D-002, DY-03): a server-fed `Chunk[A]`
    // addressed by a string signal id. The carried value is an opaque `Json.encode`d string of the
    // `Schema`-serialized `Chunk[A]`, decoded client-side with the same `Schema[A]` the island shares.
    // The server feeds the whole collection snapshot by signal id and the client writes a mirror
    // `SignalRef[Chunk[A]]`; the client's OWN `foreachKeyed` reconciler then diffs the snapshot locally
    // (an unchanged key reuses its live object, the GPU buffers survive).
    final case class SignalChunk(signalId: String, encoded: String) extends HostPayload
end HostPayload
