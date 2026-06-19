package kyo

import kyo.internal.HostInit
import kyo.internal.HostPayload
import kyo.internal.HostValue
import kyo.internal.StructuralOp

/** The per-host client channel a server `HostUpdate` writes into. It holds one mirror `SignalRef`
  * per bound prop slot of the reconstituted scene plus a structural inbox, and is built once per
  * `[data-kyo-host]` under the island mount's ambient Scope (held open for the page lifetime, the
  * persistent-channel invariant: the mount runs once, server pushes flow through the channel, never
  * a re-mount).
  *
  * A `HostPayload.Prop(nodeId, slot, value)` routes to `writeProp`, which sets the matching mirror;
  * the client reconciler's `forkBoundRef` fiber observes that mirror and applies exactly one targeted
  * `patchProp` to the live three.js object. A payload for a `(nodeId, slot)` the channel has
  * no mirror for is a silent no-op. A `HostPayload.Structural` routes to `writeStructural`, which
  * feeds the structural inbox the keyed reconciler observes (the keyed splice path is wired
  * separately).
  */
final private[kyo] class HostChannel private (
    mirrors: Map[(String, String), SignalRef[Any]],
    inbox: SignalRef[Chunk[StructuralOp]]
):

    /** Writes one prop mirror, driving exactly one downstream patchProp. A write for an unknown
      * `(nodeId, slot)` is a silent no-op. The work is a `SignalRef.set` (a `< Sync`); the wider
      * `Async & Scope` row callers expect is a widening of this pure-Sync write.
      */
    def writeProp(nodeId: String, slot: String, value: HostValue)(using Frame): Unit < Sync =
        mirrors.get((nodeId, slot)) match
            case Some(ref) => ref.set(rawValue(value))
            case None      => Kyo.unit

    /** Appends one structural splice instruction to the inbox the keyed reconciler observes. */
    def writeStructural(op: StructuralOp)(using Frame): Unit < Sync =
        inbox.updateAndGet(_.appended(op)).unit

    /** Applies one inbound payload synchronously (the `< Sync` path the JS WS-callback receiver runs
      * via `evalOrThrow`). A `Prop` writes its mirror; a `Structural` appends to the inbox.
      */
    private[kyo] def apply(payload: HostPayload)(using Frame): Unit < Sync =
        payload match
            case HostPayload.Prop(nodeId, slot, value) => writeProp(nodeId, slot, value)
            case HostPayload.Structural(op)            => writeStructural(op)

    /** The structural inbox the keyed reconciler observes (read by the later structural phase). */
    private[kyo] def structuralInbox: SignalRef[Chunk[StructuralOp]] = inbox

    /** The mirror map, exposed for tests and the reconciler-observe wiring. */
    private[kyo] def slotMirrors: Map[(String, String), SignalRef[Any]] = mirrors

    private def rawValue(hv: HostValue): Any =
        hv match
            case HostValue.V3(x, y, z) => Vec3(x, y, z)
            case HostValue.Col(rgb)    => Color(rgb)
            case HostValue.Num(v)      => v
end HostChannel

private[kyo] object HostChannel:

    /** Builds the channel for one host from its reconstituted boot init. The init's scene carries one
      * mirror `SignalRef` per reactive prop slot (allocated by `ThreeBridge.reconstitute`); this walk
      * recovers them keyed by `(nodeId, slot)`, matching the node-id scheme the server emits a
      * `HostPayload.Prop` against, and allocates the structural inbox.
      */
    def init(init: HostInit)(using Frame): HostChannel < (Async & Scope) =
        Signal.initRef[Chunk[StructuralOp]](Chunk.empty).map { inbox =>
            val mirrors = collectMirrors(init.scene)
            new HostChannel(mirrors, inbox)
        }

    // Walks the reconstituted scene collecting every (nodeId, slot) -> mirror SignalRef. A
    // reconstituted Bound.Ref always wraps a SignalRef (ThreeBridge.reconstitute allocates one per
    // slot), so the Signal -> SignalRef cast is total here; it is internal and never reaches a
    // user-facing signature.
    private def collectMirrors(scene: Three): Map[(String, String), SignalRef[Any]] =
        ThreeBridge.mirrorRefs(ThreeBridge.rootId, scene).map { case (nodeId, slot, sig) =>
            (nodeId, slot) -> sig.asInstanceOf[SignalRef[Any]]
        }.toMap
end HostChannel
