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
  * no mirror for is a silent no-op. A `HostPayload.Structural(op, regionId)` routes to
  * `writeStructural`, which feeds the structural inbox the keyed reconciler observes (the keyed splice
  * path is wired separately); the inbox carries each op paired with its `regionId` so the reconciler
  * splices into the right `foreach`/`reactive` holder rather than the host root.
  *
  * The mirror map grows and shrinks as structural ops splice and remove region children: an inserted
  * region child reconstitutes its own per-slot mirrors and `registerMirrors` adds them under the
  * child's node ids (`regionId#key`, the stable per-key id the server emits its prop pushes against),
  * so a `foreach` child's bound prop (a cube's index-driven position) updates over the channel exactly
  * like a static node's; `unregisterMirrors` drops them on removal. The map is single-owner on the
  * island mount fiber (the structural drain and the inbound WS receiver both run there), so the
  * mutable map needs no cross-fiber synchronization, the same invariant as the reconciler's live map.
  */
final private[kyo] class HostChannel private (
    mirrors: scala.collection.mutable.Map[(String, String), SignalRef[Any]],
    inbox: SignalRef[Chunk[(String, StructuralOp)]]
):

    /** Writes one prop mirror, driving exactly one downstream patchProp. A write for an unknown
      * `(nodeId, slot)` is a silent no-op. The work is a `SignalRef.set` (a `< Sync`); the wider
      * `Async & Scope` row callers expect is a widening of this pure-Sync write.
      */
    def writeProp(nodeId: String, slot: String, value: HostValue)(using Frame): Unit < Sync =
        Maybe.fromOption(mirrors.get((nodeId, slot))) match
            case Present(ref) => ref.set(rawValue(value))
            case Absent       => Kyo.unit

    /** Adds a spliced region child's per-slot mirrors to the map under its node ids, so a subsequent
      * `Prop(childNodeId, slot, value)` the server emits for that child routes to its mirror. Idempotent
      * per key: a re-insert overwrites the same entries.
      */
    private[kyo] def registerMirrors(entries: Seq[((String, String), SignalRef[Any])])(using Frame): Unit < Sync =
        Sync.defer(entries.foreach { case (k, ref) => mirrors.update(k, ref) })

    /** Drops every mirror whose node id is `nodeId` or a descendant (`nodeId.` prefix), on removal of a
      * region child, so the map holds no stale entry for a disposed subtree.
      */
    private[kyo] def unregisterMirrors(nodeId: String)(using Frame): Unit < Sync =
        Sync.defer {
            val stale = mirrors.keys.filter { case (id, _) => id == nodeId || id.startsWith(nodeId + ".") }.toList
            stale.foreach(mirrors.remove)
        }

    /** Appends one `(regionId, op)` structural splice instruction to the inbox the keyed reconciler
      * observes. The `regionId` names the host-subtree holder the op targets.
      */
    def writeStructural(regionId: String, op: StructuralOp)(using Frame): Unit < Sync =
        inbox.updateAndGet(_.appended((regionId, op))).unit

    /** Applies one inbound payload synchronously (the `< Sync` path the JS WS-callback receiver runs
      * via `evalOrThrow`). A `Prop` writes its mirror; a `Structural` appends to the inbox. A `Boot`
      * envelope is only ever the page-load island payload consumed by `readHostInit`, never delivered
      * over the live channel, so it is a no-op here.
      */
    private[kyo] def apply(payload: HostPayload)(using Frame): Unit < Sync =
        payload match
            case HostPayload.Prop(nodeId, slot, value) => writeProp(nodeId, slot, value)
            case HostPayload.Structural(op, regionId)  => writeStructural(regionId, op)
            case _: HostPayload.Boot                   => Kyo.unit

    /** The structural inbox the keyed reconciler observes (read by the later structural phase). Each
      * entry pairs a `regionId` (the target holder node id) with the op to apply there.
      */
    private[kyo] def structuralInbox: SignalRef[Chunk[(String, StructuralOp)]] = inbox

    /** The mirror map snapshot, exposed for tests and the reconciler-observe wiring. */
    private[kyo] def slotMirrors: Map[(String, String), SignalRef[Any]] = mirrors.toMap

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
        Signal.initRef[Chunk[(String, StructuralOp)]](Chunk.empty).map { inbox =>
            val mirrors = collectMirrors(init.scene)
            new HostChannel(mirrors, inbox)
        }

    // Walks the reconstituted scene collecting every (nodeId, slot) -> mirror SignalRef into a mutable
    // map (the boot mirrors; the structural splice path adds and drops region-child mirrors later). A
    // reconstituted Bound.Ref always wraps a SignalRef (ThreeBridge.reconstitute allocates one per
    // slot), so the Signal -> SignalRef cast is total here; it is internal and never reaches a
    // user-facing signature.
    private def collectMirrors(scene: Three): scala.collection.mutable.Map[(String, String), SignalRef[Any]] =
        val m = scala.collection.mutable.HashMap.empty[(String, String), SignalRef[Any]]
        ThreeBridge.mirrorRefs(ThreeBridge.rootId, scene).foreach { case (nodeId, slot, sig) =>
            m.update((nodeId, slot), sig.asInstanceOf[SignalRef[Any]])
        }
        m
    end collectMirrors
end HostChannel
