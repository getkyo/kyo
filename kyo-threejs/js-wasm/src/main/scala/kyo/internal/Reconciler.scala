package kyo.internal

import kyo.*
import kyo.Three.Ast
import kyo.kernel.ContextEffect
import scala.scalajs.js

/** The scene-graph reconciler: materializes a pure [[Three]] AST into live three.js objects, holds a
  * 1:1 node-identity -> live-object map, patches one bound prop on a `Bound.Ref` emission (never a scene
  * rebuild), and reconciles a keyed `Foreach` so a reorder reuses GPU buffers.
  *
  * Every GL resource is acquired via `Scope.acquireRelease(create)(_.dispose())` under a per-element
  * child scope for reactive/foreach children, so removing a key from a `Foreach` or swapping a `Reactive`
  * subtree disposes that element's GL resources exactly once (the per-element scope closes on removal).
  * Surviving elements' per-element scopes are registered with the mount scope and close on mount close.
  * No GL resource leaks; no double-dispose. The reconciler runs on the mount's single drain fiber, so
  * the identity map and per-element scope map are single-writer / single-reader and need no cross-fiber
  * synchronization.
  */
private[kyo] object Reconciler:

    /** The live binding of one materialized AST node to the three.js object it created, plus its live
      * children. Identity-keyed by the immutable AST node's reference identity, so two structurally-equal
      * nodes in different positions are distinct live objects.
      */
    final private[kyo] class Live(
        val obj: js.Dynamic, // the live three.js Object3D (FFI handle)
        val node: Three,
        val children: Chunk[Live]
    )

    /** The per-mount state: the identity map, per-element scope map, and the keyed-region caches.
      * Created per mount, owned by the mount's drain fiber.
      */
    final private[kyo] class Mounted:
        // Single-owner per mount: only ever touched on the mount's single drain fiber; not shared
        // across fibers, so an identity map needs no concurrency control here.
        // Uses an IdentityKey wrapper so HashMap uses reference identity (eq + System.identityHashCode)
        // rather than structural equality from the case-class-derived .equals/.hashCode; two structurally-
        // equal sibling nodes map to two distinct live entries, preserving the 1:1 invariant.
        private[kyo] val live: scala.collection.mutable.Map[IdentityKey, Live] =
            new scala.collection.mutable.HashMap[IdentityKey, Live]()

        // Per-element scopes for reactive/foreach children. Keyed by the root Live node's identity.
        // When a key is removed from a Foreach or a Reactive swaps, the corresponding scope closes,
        // disposing the element's GL resources exactly once. Touched only on the mount drain fiber.
        // Single-owner: same invariant as `live`.
        private[kyo] val elemScopes: scala.collection.mutable.Map[IdentityKey, Scope.Finalizer.Awaitable] =
            new scala.collection.mutable.HashMap[IdentityKey, Scope.Finalizer.Awaitable]()

        // The structural reactive regions of this mount, keyed by their holder node's identity and held in
        // materialize order. NOT a once-computed cache: a Reactive/Foreach can be materialized INSIDE
        // another region's content, which happens strictly AFTER the outer region first reconciles, so the
        // set of regions grows as the scene fills and shrinks when an element holding one is disposed.
        // Registered by `record` (every region holder passes through it, root or nested) and retired by
        // `removeFromLive`. Single-owner: same invariant as `live`.
        private[kyo] val regions: scala.collection.mutable.LinkedHashMap[IdentityKey, ReactiveRegion] =
            new scala.collection.mutable.LinkedHashMap[IdentityKey, ReactiveRegion]()

        // The live-mount hook invoked for every element materialized inside a reactive/foreach region,
        // run under that element's own scope so any Bound.Ref observe fibers it forks dispose with the
        // element. The default no-op covers the headless toImage path, which fills reactive props once
        // (fillBoundRefsOnce) instead of forking observe fibers. The live mount installs the real hook.
        private[kyo] var subscribeElement: Live => Unit < (Async & Scope) = (_: Live) => ()

        // The live-mount hook invoked once for each region, right after its first fill: it forks the
        // watcher that re-reconciles the region on every later change. Run under the scope of the element
        // the region was materialized in, so a nested region's watcher is interrupted when the element
        // holding it goes away. The default no-op covers the headless toImage path, which fills each
        // region once from its signal's current value and runs no watcher at all.
        private[kyo] var subscribeRegion: ReactiveRegion => Unit < (Async & Scope) = (_: ReactiveRegion) => ()
        // The pointer drops THIS mount has already reported, keyed by declaration site and event kind, so a
        // dead object re-hit on every click and every drag across it is reported once instead of forever. It
        // lives on the mount for the same reason ThreeBackend's drop set does: a page that re-hydrates, a
        // second embed, or a suite that mounts scene after scene all replay the SAME declaration sites, and a
        // set shared across mounts would swallow the next mount's genuine drop as a duplicate of the last
        // one's. A guard against silence that itself goes silent is worse than no guard, because a quiet log
        // then reads as proof that nothing was dropped.
        private[kyo] val reportedPointerSites: scala.collection.mutable.Set[String] =
            new scala.collection.mutable.HashSet[String]()

        // The per-mount hooks a path-indexing backend installs; DEFAULT no-op, so the
        // client-local runMount + headless toImage paths maintain no server index.
        private[kyo] var reindexRegion: (ReactiveRegion, Chunk[(String, Live)]) => Unit = (_, _) => () // re-index on splice
        private[kyo] var pathForLive: Live => Maybe[Seq[String]]                        = _ => Absent  // the click producer's inverse
    end Mounted

    // Private wrapper so HashMap uses reference identity (== on the node ref),
    // not structural equality from the case-class-derived .equals/.hashCode.
    // java.util.IdentityHashMap is not available on Scala.js; this wrapper achieves
    // the same semantics using eq and System.identityHashCode (both available on Scala.js).
    final private[kyo] class IdentityKey(val node: Three):
        override def equals(o: Any): Boolean =
            o.isInstanceOf[IdentityKey] && (o.asInstanceOf[IdentityKey].node eq node)
        override def hashCode: Int = java.lang.System.identityHashCode(node)
    end IdentityKey

    /** Maps a loaded [[Three.Ast.Texture]] handle to the live three.js texture the `Three.texture` loader
      * created, so the material materialize can set `material.map` from the pure handle. The `Three.texture`
      * loader registers each loaded texture here under the ambient `Scope` and drops it on dispose; a
      * material with an `Absent` or unresolved `map` materializes untextured (no GL leak).
      */
    private[kyo] object TextureRegistry:
        // Single-owner: each url is written once by its loading fiber under the ambient Scope and read once
        // at material materialize on the mount drain fiber; no contended cross-fiber access.
        private val byUrl: scala.collection.mutable.Map[String, js.Dynamic] =
            new scala.collection.mutable.HashMap[String, js.Dynamic]()

        /** Records the live texture for a url (called by the `Three.texture` loader on a successful load). */
        private[kyo] def register(url: String, tex: js.Dynamic)(using AllowUnsafe): Unit =
            val _ = byUrl.update(url, tex)

        /** Drops a url's live texture on scope close (called by the loader's release). */
        private[kyo] def remove(url: String)(using AllowUnsafe): Unit =
            val _ = byUrl.remove(url)

        /** The live texture for a handle, or `Absent` when the handle was never loaded through `Three.texture`. */
        private[kyo] def resolve(handle: Three.Ast.Texture)(using AllowUnsafe): Maybe[js.Dynamic] =
            handle match
                case Three.Ast.Texture.FromUrl(url) => Maybe.fromOption(byUrl.get(url))
    end TextureRegistry

    /** Materializes a node and its subtree into live objects, registering each GL resource under the
      * ambient `Scope` for disposal and recording the identity binding.
      */
    def materialize(node: Three, mounted: Mounted)(using Frame): Live < (Async & Scope & Abort[ThreeException]) =
        // Unsafe: every `record` call below mutates the three.js object graph (attaches children) and the
        // mounted identity map synchronously with no suspension; `Sync.Unsafe.defer` lifts that FFI side
        // effect into the row. Safe because materialize runs only on the mount's single drain fiber under
        // the mount Scope (no concurrent access to the live map), and each created GL resource is registered
        // for disposal on Scope close by the ThreeFacadeOps make* calls.
        node match
            case s: Ast.Scene =>
                for
                    obj      <- ThreeFacadeOps.makeScene(s)
                    children <- materializeAll(s.children, mounted)
                    live     <- Sync.Unsafe.defer(record(mounted, s, obj, children, attachChildren = true))
                yield live
            case g: Ast.Group =>
                for
                    obj      <- ThreeFacadeOps.makeGroup(g)
                    children <- materializeAll(g.children, mounted)
                    live     <- Sync.Unsafe.defer(record(mounted, g, obj, children, attachChildren = true))
                yield live
            case m: Ast.Mesh =>
                for
                    geom <- ThreeFacadeOps.makeGeometry(m.geometry)
                    mat  <- ThreeFacadeOps.makeMaterial(m.material)
                    obj  <- ThreeFacadeOps.makeMesh(geom, mat, m)
                    live <- Sync.Unsafe.defer(record(mounted, m, obj, Chunk.empty, attachChildren = false))
                yield live
            case l: Ast.Light =>
                ThreeFacadeOps.makeLight(l).flatMap(obj =>
                    Sync.Unsafe.defer(record(mounted, l, obj, Chunk.empty, attachChildren = false))
                )
            case c: Ast.Camera =>
                ThreeFacadeOps.makeCamera(c).flatMap(obj =>
                    Sync.Unsafe.defer(record(mounted, c, obj, Chunk.empty, attachChildren = false))
                )
            case c: Ast.Custom[?] =>
                ThreeFacadeOps.makeCustom(c).flatMap(obj =>
                    Sync.Unsafe.defer(record(mounted, c, obj, Chunk.empty, attachChildren = false))
                )
            case r: Ast.Reactive =>
                // The live container is an empty Group; the reactive subtree is materialized into it by
                // the mount's subscribe loop. Here we only create the holder and record it.
                ThreeFacadeOps.makeHolder().flatMap(obj =>
                    Sync.Unsafe.defer(record(mounted, r, obj, Chunk.empty, attachChildren = false))
                )
            case f: Ast.Foreach[?] =>
                ThreeFacadeOps.makeHolder().flatMap(obj =>
                    Sync.Unsafe.defer(record(mounted, f, obj, Chunk.empty, attachChildren = false))
                )
            case c: Ast.Controls =>
                // Controls renders no object of its own (it drives the camera); materialize an empty holder
                // so the node is recorded in the live map. The mount pipeline reads the recorded Controls
                // node to bind a live OrbitControls instance over the camera and canvas (ThreeMount).
                ThreeFacadeOps.makeHolder().flatMap(obj =>
                    Sync.Unsafe.defer(record(mounted, c, obj, Chunk.empty, attachChildren = false))
                )
            case e: Ast.Embed =>
                // Unreachable: Ast.Embed is private[kyo] and its only factory (Three.embed) returns it
                // upcast to UI.Ast.BackendNode, so no scene's `children: Chunk[Three]` can ever hold one;
                // ThreeBackend.mount dispatches an Embed to hostMountPipeline(e.scene, ...), materializing
                // e.scene, never e itself. Kept as a defensive panic for the sealed-match exhaustivity.
                Abort.panic(new IllegalStateException(s"Reconciler.materialize: unreachable Ast.Embed: $e"))
        end match
    end materialize

    private def materializeAll(nodes: Chunk[Three], mounted: Mounted)(using
        Frame
    ): Chunk[Live] < (Async & Scope & Abort[ThreeException]) =
        Kyo.foreach(nodes)(materialize(_, mounted))

    private def record(mounted: Mounted, node: Three, obj: js.Dynamic, children: Chunk[Live], attachChildren: Boolean)(using
        AllowUnsafe
    ): Live =
        if attachChildren then children.foreach(c => ThreeFacadeOps.attachUnsafe(obj, c.obj))
        val liveNode = new Live(obj, node, children)
        val key      = new IdentityKey(node)
        mounted.live.update(key, liveNode)
        // Every region holder is created here, whether it sits in the root scene or deep inside another
        // region's content, so this is the ONE place a region can come into existence. Registering it here
        // is what makes a nested region a real region rather than an empty Group nobody ever fills.
        node match
            case _: Ast.Reactive | _: Ast.Foreach[?] =>
                mounted.regions.update(key, new ReactiveRegion(liveNode, node))
            case _ => ()
        end match
        liveNode
    end record

    /** The region whose holder is `live`, or `Absent` when `live` is not a region holder. */
    private[kyo] def regionOf(mounted: Mounted, live: Live): Maybe[ReactiveRegion] =
        Maybe.fromOption(mounted.regions.get(new IdentityKey(live.node))).filter(_.holder eq live)

    /** Applies one targeted FFI mutation to a single live object (the `Bound.Ref` emission path); touches
      * exactly this object.
      */
    def patchProp(live: Live, set: js.Dynamic => Unit)(using AllowUnsafe): Unit =
        // Unsafe: a single property mutation on a live three.js object the reconciler owns.
        set(live.obj)

    /** Materializes a node under a fresh per-element child scope, registering the child scope with the
      * outer mount scope so the element disposes on mount close if not already removed. When an element
      * is removed from a `Foreach` or a `Reactive` swaps, the element's scope closes and disposes its GL
      * resources exactly once; the mount-scope registration is a no-op for an already-closed scope.
      */
    private[kyo] def materializeInElemScope(node: Three, mounted: Mounted)(using
        Frame
    ): Live < (Async & Scope & Abort[ThreeException]) =
        // Unsafe: constructing the per-element Scope.Finalizer that owns this element's GL resources.
        // AllowUnsafe is injected by Sync.Unsafe.defer; no additional import needed inside the block.
        Sync.Unsafe.defer(Scope.Finalizer.Awaitable.Unsafe.init(1)).map { elemFinalizer =>
            // Register with the outer mount scope: closes elemFinalizer on mount close (no-op if already
            // closed by an earlier removal). This ensures surviving elements dispose on mount teardown.
            Scope.ensure(elemFinalizer.close(Absent).andThen(elemFinalizer.await)).andThen {
                // Shadow the outer Scope with this element's finalizer so every GL resource acquired
                // inside materialize (via acquireGl -> Scope.acquireRelease) registers on elemFinalizer,
                // not the mount scope. The mount scope only holds the outer-close registration above.
                ContextEffect.handle(Tag[Scope], elemFinalizer: Scope.Finalizer, _ => elemFinalizer: Scope.Finalizer)(
                    materialize(node, mounted).map { live =>
                        // Record the per-element scope so diffKeyed and reactiveStep can close it on
                        // removal, then run the mount's element hook UNDER this element's scope so any
                        // Bound.Ref observe fibers it forks for the new subtree dispose with the element,
                        // and finally activate any structural region the new subtree brought with it.
                        Sync.Unsafe.defer(mounted.elemScopes.update(new IdentityKey(live.node), elemFinalizer))
                            .andThen(mounted.subscribeElement(live))
                            .andThen(activateRegions(live, mounted))
                            .andThen(live)
                    }
                )
            }
        }

    /** Fills and subscribes every structural region a freshly materialized element brought with it, so a
      * region inside a region's content behaves exactly like one in the root scene.
      *
      * The recursion into deeper nesting is free: filling one of these regions materializes ITS content
      * through `materializeInElemScope`, which lands back here for whatever regions that content declares.
      * It terminates because the AST is a finite value, and each fill descends one level into it.
      *
      * Only the regions this element actually introduced are activated (a holder registered by the
      * `materialize` call just above). A region already registered from an earlier materialize keeps the
      * state and the watcher it already has.
      */
    private def activateRegions(live: Live, mounted: Mounted)(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        Sync.Unsafe.defer(regionsIn(live, mounted)).map { fresh =>
            Kyo.foreachDiscard(fresh) { region =>
                fillRegionOnce(region, mounted).andThen(mounted.subscribeRegion(region))
            }
        }

    /** The regions held by `live`'s subtree, in materialize order. Stops at a region holder rather than
      * descending into its content: at this point the holder is childless (its content is spliced in by the
      * fill that comes next), and that content's own regions activate through their own materialize.
      */
    private def regionsIn(live: Live, mounted: Mounted)(using AllowUnsafe): Chunk[ReactiveRegion] =
        var buf = Chunk.empty[ReactiveRegion]
        def walk(l: Live): Unit =
            regionOf(mounted, l) match
                case Present(region) => buf = buf.appended(region)
                case Absent          => l.children.foreach(walk)
        walk(live)
        buf
    end regionsIn

    /** Removes a live node and its full subtree from `mounted.live`, retiring every structural region the
      * subtree holds. Called on removal before closing the per-element scope, so the live map never holds
      * stale entries for disposed objects.
      *
      * The walk descends THROUGH a region holder into that region's current children, which are not in
      * `Live.children` (a holder is materialized childless and its content is spliced in afterwards).
      * Without that hop, disposing an element that contains a region would leave the region's content in the
      * live map forever, and both `ThreeMount.onFrameClosures` and `Raycasting.interactiveTargets` enumerate
      * that map: a disposed mesh's `onFrame` would keep running every frame, and a click could still hit an
      * object that is no longer in the scene. Retiring the region itself matters just as much: `regionFor`
      * resolves a wire op by matching `holderPath`, and a dead region left in the map would shadow the fresh
      * one that replaces it at the same path.
      *
      * An entry is removed only while it is STILL the one recorded for that node identity. The map is keyed
      * by AST node identity, and a node the caller hoisted into a `val` and rendered into two consecutive
      * emissions is materialized twice under ONE identity, with the fresh live object recorded before the
      * prior one is disposed. Removing by key alone would then delete the entry belonging to the object that
      * is currently on screen.
      */
    private def removeFromLive(live: Live, mounted: Mounted)(using AllowUnsafe): Unit =
        val key = new IdentityKey(live.node)
        if mounted.live.get(key).exists(_ eq live) then
            discard(mounted.live.remove(key))
            discard(mounted.elemScopes.remove(key))
        regionOf(mounted, live) match
            case Present(region) =>
                region.prevKeyed.foreach { case (_, child) => removeFromLive(child, mounted) }
                discard(mounted.regions.remove(key))
            case Absent => ()
        end match
        live.children.foreach(c => removeFromLive(c, mounted))
    end removeFromLive

    /** Closes the per-element scope for `removedLive` (disposing its GL resources exactly once) and
      * retires its `mounted.live` entries. Returns the close+await effect so the caller can sequence it.
      */
    private[kyo] def disposeElemScope(removedLive: Live, mounted: Mounted)(using
        Frame
    ): Unit < (Async & Sync) =
        // Unsafe: removing the per-element scope and retiring the live-map entries is a synchronous mutation
        // of the mounted state with no suspension; `Sync.Unsafe.defer` lifts it into the row. Safe because it
        // runs on the mount's single drain fiber, so no other fiber observes the map mid-update.
        Sync.Unsafe.defer {
            val key = new IdentityKey(removedLive.node)
            val fin = Maybe.fromOption(mounted.elemScopes.remove(key))
            removeFromLive(removedLive, mounted)
            fin
        }.map {
            case Present(fin) => fin.close(Absent).andThen(fin.await)
            case Absent       => ()
        }

    /** The outcome of a keyed diff: the live children the holder must now carry, in order, and the prior
      * live objects that are no longer carried and must be disposed.
      *
      * The two are separated because DISPOSAL MUST HAPPEN AFTER THE RELINK, never before. A retired object
      * stays attached to the holder until [[replaceHolderChildren]] detaches it, so disposing first opens a
      * window in which the scene graph still holds an object whose GPU resources are gone. That window is
      * real: the reconcile and the render run on different fibers (the mount's drain fiber and the frame
      * loop), a dispose awaits its element scope's finalizers, and kyo's JS scheduler hands every resumed
      * task back through a macrotask, which is exactly where the browser runs its animation-frame callbacks.
      * So a frame CAN render in between. Nothing visibly breaks when it does, which is the dangerous part:
      * three.js frees the GPU buffers on dispose but keeps the CPU-side attribute arrays, so the next frame
      * silently re-uploads them, and those new buffers are then orphaned against a scope that is already
      * closed. A wasted upload and a leak, once per changed key per tick, seen by no one.
      *
      * Returning the retired set instead of disposing it makes the order impossible to get wrong: a caller
      * cannot relink without first holding the objects it owes a disposal to.
      */
    final private[kyo] case class KeyedDiff(lives: Chunk[Live], retired: Chunk[Live])

    /** Reconciles a keyed `Foreach` against the prior keyed live children, materializing what changed and
      * reporting what must be disposed once the caller has relinked.
      *
      * A key whose ITEM is unchanged reuses its live object: its GPU buffers survive a reorder, or an
      * insertion or removal elsewhere in the set, which is what keying exists for. A key whose item
      * CHANGED is rebuilt from the new item, because the item is the only thing that subtree was ever
      * rendered from: reusing the live object there would leave the scene showing the OLD item forever
      * while the signal says otherwise. That is the whole update path for foreach content, since a foreach
      * child's own `Bound.Ref` props are never reached by the prop-patch walk (`Ast.Foreach.children` is
      * always empty), so nothing else would ever correct it.
      *
      * What is retired is decided by LIVE IDENTITY, not by key. Under a duplicate key the reuse lookup is
      * last-wins, so only ONE of the duplicates' live objects is carried forward; retiring by key would
      * spare the other, which then gets detached by the relink and is never disposed and never retired from
      * the identity map. Identity retires exactly the objects that are not carried, duplicates included.
      */
    def diffKeyed(
        prev: Chunk[(String, Live)],
        prevItems: Map[String, Any],
        next: Chunk[KeyedEntry],
        mounted: Mounted
    )(using Frame): KeyedDiff < (Async & Scope & Abort[ThreeException]) =
        val prevByKey: scala.collection.mutable.Map[String, Live] =
            new scala.collection.mutable.HashMap[String, Live]()
        prev.foreach { case (k, v) => prevByKey.update(k, v) }
        // The live object for `entry` when it survives UNCHANGED, else Absent (a new key, or one whose
        // item moved, both of which must materialize). Compared with `equals` rather than `==`, which
        // works for the erased item type with no CanEqual evidence in scope, exactly as watchDistinct does.
        def reusable(entry: KeyedEntry): Maybe[Live] =
            if prevItems.get(entry.key).exists(_.equals(entry.item)) then Maybe.fromOption(prevByKey.get(entry.key))
            else Absent
        // The live objects actually carried forward. `Live` is a plain class, so a HashSet of them is an
        // IDENTITY set, which is the point: it names the objects that survive, not the keys that do.
        val carried: scala.collection.mutable.Set[Live] =
            new scala.collection.mutable.HashSet[Live]()
        next.foreach(entry => reusable(entry).foreach(live => discard(carried.add(live))))
        val retired = prev.collect { case (_, live) if !carried.contains(live) => live }
        Kyo.foreach(next) { entry =>
            reusable(entry) match
                case Present(live) => live: Live < (Async & Scope & Abort[ThreeException])
                case Absent        => materializeInElemScope(entry.node, mounted)
        }.map(lives => KeyedDiff(lives, retired))
    end diffKeyed

    /** Disposes the live objects a diff retired, once the caller has relinked the holder so none of them is
      * attached any more. See [[KeyedDiff]] for why this cannot run before the relink.
      */
    private def disposeRetired(retired: Chunk[Live], mounted: Mounted)(using Frame): Unit < (Async & Sync) =
        Kyo.foreachDiscard(retired)(disposeElemScope(_, mounted))

    /** A structural reactive region: the empty holder `Live` whose child subtree is driven by the
      * `Reactive`/`Foreach` node's signal, plus the keyed live children from the last reconcile. The
      * mount forks one reconcile fiber per region; the keyed state is single-writer (touched only by
      * that fiber, and by the sequential startup fill), so it needs no cross-fiber synchronization.
      */
    final private[kyo] class ReactiveRegion(val holder: Live, val node: Three):
        // Single-owner per region: the last reconcile's keyed live children, read to diff the
        // next emission so an unchanged key reuses its live object.
        private[kyo] var prevKeyed: Chunk[(String, Live)] = Chunk.empty
        // The item each key was last rendered from, the change detector diffKeyed reads to tell a key that
        // merely survived from one whose content actually moved. Empty for a Reactive region (it holds a
        // single subtree, swapped whole on every emission, so there is nothing to diff per key).
        private[kyo] var prevItems: Map[String, Any] = Map.empty
        // The DRIVING value this region last reconciled from: what watchDistinct dedups against. Set by the
        // fill so the watcher that starts right after it does not re-run the reconcile it just did. Absent
        // until the first fill, which is what lets a watcher started WITHOUT a prior fill do the first
        // reconcile itself. Not the same value `prevItems` holds: a Reactive with a server drive is driven
        // by its raw pre-render data, not by the rendered subtree (see runReactiveRegion).
        private[kyo] var lastDriver: Maybe[Any] = Absent
        // This region's holder's data-kyo-path, STAMPED by ThreeBackend's
        // buildPathIndex before any post-mount splice; empty (Absent-equivalent) in the client-local/
        // headless paths that maintain no server index. regionFor + the reindex hook read it.
        private[kyo] var holderPath: Seq[String] = Seq.empty
    end ReactiveRegion

    /** Every structural region (`Ast.Reactive`, `Ast.Foreach`) currently live in the mount, in materialize
      * order: the ones the root scene declared, plus the ones materialized inside another region's content,
      * minus the ones whose element has been disposed.
      */
    def reactiveRegions(mounted: Mounted): Chunk[ReactiveRegion] =
        Chunk.from(mounted.regions.values)

    /** Watches one structural reactive region forever: on every GENUINE change to the region's driving
      * data, re-reconciles the holder's children. The region's CURRENT value is already reconciled before
      * this loop starts, by [[fillRegionOnce]] (the root scene's regions are filled by the mount, a nested
      * region's by the [[activateRegions]] that materialized it), so this loop only handles subsequent
      * changes; see [[watchDistinct]] for how it is seeded to know that. A `Reactive`
      * swaps its single subtree (disposing the prior subtree exactly once); a `Foreach` diffs by key
      * (positional when unkeyed) so an unchanged element reuses its live object (GPU buffers survive).
      * New objects materialize under a per-element scope registered with the mount scope; removed
      * elements dispose their GL resources immediately and retire their `mounted.live` entries.
      *
      * Dedups via [[watchDistinct]] rather than a bare `Loop.foreach(signal.next...)`: a `SignalRef`'s
      * `next` is a wakeup hint, not a change guarantee. Under an ABA write (read v1, a writer installs a
      * fresh promise for v2, the watcher registers, the writer sets back to v1, the promise completes with
      * v1) `next` can re-wake carrying a value `equals` to the last one observed, and re-running `step` on
      * an unchanged value would needlessly dispose and re-materialize live GL resources; `watchDistinct`
      * suppresses those equal re-wakes. A constant signal takes the same path harmlessly: its `next` parks
      * (a constant has no next value), so the watcher suspends after the first `step` instead of spinning.
      * A `Foreach`'s own `signal: Signal[Chunk[A]]` carries the raw, un-rendered data, so it dedups
      * directly. A `Reactive` built via `render`/`when` carries its raw pre-render data alongside in
      * `serverDrive.dataSignal`; dedup runs on THAT (the rendered `Three` itself wraps a fresh `onClick`
      * closure on every render, so two renders of the SAME underlying value are never `equals`, making the
      * rendered value unusable as a dedup key). The raw `Three.reactive(Signal[Three])` constructor carries
      * no pre-render source (its `serverDrive` is `Absent`), but its `signal` holds the caller's own `Three`
      * values directly with no per-render `onClick` remapping, so dedup runs on `r.signal` itself: two
      * `equals` `Three` values re-wake without re-materializing, while a genuinely different `Three` value
      * still fires `step`.
      */
    def runReactiveRegion(region: ReactiveRegion, mounted: Mounted)(using
        Frame
    ): Unit < (Async & Scope & Abort[ThreeException]) =
        region.node match
            case r: Ast.Reactive =>
                val step = reactiveStep(region, mounted)
                r.serverDrive match
                    case Present(sd) => watchDistinct(sd.dataSignal, region, mounted)(_ => r.signal.current.map(step))
                    case Absent      => watchDistinct(r.signal, region, mounted)(step)
            case f: Ast.Foreach[a] =>
                val step = foreachStep(region, f, mounted)
                watchDistinct(f.signal, region, mounted)(step)
            case _ => ()

    /** Waits for `signal` to emit a value that is not `equals` to the last one this region reconciled from
      * before running `step`, then repeats forever. Compares via the plain `equals` method (not `==`) so
      * this works for any `A`, including an erased/existential type parameter with no `CanEqual[A, A]`
      * evidence in scope. See [[runReactiveRegion]] for why this dedup is needed.
      *
      * It starts from the region's `lastDriver`, which is the value the fill already reconciled from, so the
      * watcher does not immediately redo the fill's work. That is not merely wasteful: a re-run would
      * re-materialize the region's whole subtree, and every region nested in that subtree would be built,
      * filled and re-run again in turn, so the cost doubles per level of nesting. A region started with no
      * prior fill has `lastDriver` Absent and reconciles once, immediately, as it must.
      *
      * A RETIRED region stops watching instead of reconciling, and that check cannot be skipped just because
      * the watcher is scoped to its element. The watcher of a nested region is forked under that element's
      * scope, so disposing the element interrupts it, but interruption is ASYNCHRONOUS: if the region's
      * signal emits in the window between the disposal and the interrupt landing, the watcher would wake and
      * reconcile a region that is no longer in the scene. It would materialize into a holder nothing is
      * attached to, put the dead objects back into `mounted.live` (where `onFrameClosures` would keep running
      * a disposed mesh every frame and `Raycasting.interactiveTargets` could still hit it), and register the
      * fresh GL resources for release on an element scope that is ALREADY CLOSED, which is a no-op: those
      * buffers would never be freed. The same aliveness test [[fillReactiveRegionsOnce]] applies across a
      * fill pass applies here across the disposal race.
      */
    private def watchDistinct[A](signal: Signal[A], region: ReactiveRegion, mounted: Mounted)(
        step: A => Unit < (Async & Scope & Abort[ThreeException])
    )(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        def loop(last: Maybe[Any]): Unit < (Async & Scope & Abort[ThreeException]) =
            signal.currentWith { cur =>
                if last.exists(_.equals(cur)) then signal.nextWith(_ => ()).andThen(loop(last))
                else
                    Sync.defer(regionOf(mounted, region.holder).nonEmpty).map { alive =>
                        if alive then step(cur).andThen(loop(Present(cur)))
                        else Kyo.unit // retired: the element holding this region is gone, so stop watching.
                    }
            }
        loop(region.lastDriver)
    end watchDistinct

    /** Reconciles every region once from its signal's current value: the deterministic single-fill the
      * mount runs at startup so the first rendered frame is already populated, and the seam the live
      * tests assert against.
      */
    def fillReactiveRegionsOnce(mounted: Mounted)(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        Kyo.foreachDiscard(reactiveRegions(mounted)) { region =>
            // A region can be RETIRED by an earlier region's reconcile in this same pass: a foreach that
            // dropped an item disposes that element, and with it every region nested inside it. Reconciling
            // one now would materialize fresh content into a holder that is no longer in the scene and put
            // those dead objects back into the live map, where `onFrameClosures` and `interactiveTargets`
            // would keep finding them. Whatever is no longer registered is no longer filled.
            Sync.defer(regionOf(mounted, region.holder).nonEmpty).map { alive =>
                if alive then fillRegionOnce(region, mounted) else Kyo.unit
            }
        }

    /** Reconciles ONE region from its signal's current value: the single-region unit that both the startup
      * fill and the activation of a freshly materialized nested region run.
      *
      * Records the DRIVING value it reconciled from (`lastDriver`), which is the value the region's watcher
      * dedups against, so the watcher started right after this does not immediately repeat the same
      * reconcile. The driving value is not always the value `step` consumes: a `Reactive` carrying a server
      * drive is driven by its raw pre-render data, because two renders of the same value are never `equals`
      * (each wraps a fresh handler closure). It is read here from exactly the signal `runReactiveRegion`
      * watches, so the two cannot disagree about what "unchanged" means.
      */
    private[kyo] def fillRegionOnce(region: ReactiveRegion, mounted: Mounted)(using
        Frame
    ): Unit < (Async & Scope & Abort[ThreeException]) =
        region.node match
            case r: Ast.Reactive =>
                // The erased driver's CanEqual[Any, Any] bound is not auto-derived under strict equality;
                // widened explicitly here, exactly as Three.render's own erasure of dataSignal does.
                given CanEqual[Any, Any] = CanEqual.derived
                val driver: Signal[Any] = r.serverDrive match
                    case Present(sd) => sd.dataSignal
                    case Absent      => r.signal.map(node => node: Any)
                driver.current.map { d =>
                    Sync.defer(region.lastDriver = Present(d))
                        .andThen(r.signal.current.map(reactiveStep(region, mounted)))
                }
            case f: Ast.Foreach[a] =>
                f.signal.current.map { items =>
                    Sync.defer(region.lastDriver = Present(items))
                        .andThen(foreachStep(region, f, mounted)(items))
                }
            case _ => ()

    /** The per-emission reconcile for a `Reactive` region: materializes the new subtree under a fresh
      * per-element scope, swaps it into the holder, and only THEN disposes the prior subtree, exactly once.
      *
      * The order is the point: the prior subtree stays attached to the holder until the swap, so disposing
      * it first would leave the scene graph holding an object whose GPU resources are gone, which a frame
      * can render (see [[KeyedDiff]]).
      */
    private def reactiveStep(region: ReactiveRegion, mounted: Mounted)(using
        Frame
    ): Three => Unit < (Async & Scope & Abort[ThreeException]) =
        next =>
            // The prior subtree, read before the swap overwrites it (present on every emission after the
            // first fill).
            Sync.defer(region.prevKeyed.map(_._2)).map { prior =>
                materializeInElemScope(next, mounted).map { fresh =>
                    replaceHolderChildren(region, Chunk(("reactive", fresh)), Map.empty, mounted)
                        .andThen(disposeRetired(prior, mounted))
                }
            }

    /** The per-emission reconcile for a `Foreach` region: key the elements, diff against the prior
      * keyed live children (so a key whose item is unchanged reuses its object and keeps its GL buffers),
      * relink the reconciled set in order, and only THEN dispose whatever the diff retired.
      */
    private def foreachStep[A](region: ReactiveRegion, f: Ast.Foreach[A], mounted: Mounted)(using
        Frame
    ): Chunk[A] => Unit < (Async & Scope & Abort[ThreeException]) =
        items =>
            val entries = keyEntries(f, items)
            diffKeyed(region.prevKeyed, region.prevItems, entries, mounted).map { diff =>
                replaceHolderChildren(region, entries.map(_.key).zip(diff.lives), itemsByKey(entries), mounted)
                    .andThen(disposeRetired(diff.retired, mounted))
            }

    /** Pairs each `Foreach` element with its key (the keyed accessor, or its index when unkeyed), the
      * item itself, and the node that item renders to.
      */
    private def keyEntries[A](f: Ast.Foreach[A], items: Chunk[A]): Chunk[KeyedEntry] =
        items.zipWithIndex.map { case (item, i) =>
            KeyedEntry(f.key.fold(i.toString)(_(item)), item, f.render(i, item))
        }

    /** The item each key carries in an emission, the state `diffKeyed` compares the NEXT emission against.
      * A duplicate key keeps the last item, matching the last-wins reuse the keyed live children already
      * take for a duplicate.
      */
    private def itemsByKey(entries: Chunk[KeyedEntry]): Map[String, Any] =
        entries.foldLeft(Map.empty[String, Any])((acc, entry) => acc.updated(entry.key, entry.item))

    /** Detaches every current child of the holder, attaches the reconciled keyed set in order, and
      * records the keyed live children plus the items they were rendered from, so the next emission
      * diffs against them (keyed reuse). Removed children are disposed by the caller before this runs;
      * only the scene-graph relink and state update happen here.
      */
    private def replaceHolderChildren(
        region: ReactiveRegion,
        keyedLives: Chunk[(String, Live)],
        keyedItems: Map[String, Any],
        mounted: Mounted
    )(using
        Frame
    ): Unit < Sync =
        // Unsafe: the keyed re-link is a synchronous scene-graph relink on objects the reconciler owns.
        Sync.Unsafe.defer {
            val holder      = region.holder.obj
            val childrenArr = holder.children.asInstanceOf[js.Array[js.Dynamic]]
            val snapshot    = childrenArr.toArray
            snapshot.foreach(child => ThreeFacadeOps.detachUnsafe(holder, child))
            keyedLives.foreach { case (_, l) => ThreeFacadeOps.attachUnsafe(holder, l.obj) }
            // Re-index BEFORE overwriting prevKeyed, so the hook can diff old-vs-new
            // keyed sets from region.prevKeyed (still the PRIOR set at this point) against keyedLives (the
            // NEW set). ThreeBackend installs this to write byPath/byLive (un-index removed keys, index
            // spliced keys under holderPath :+ key [+ i...]); no-op in client-local/headless mode.
            mounted.reindexRegion(region, keyedLives)
            region.prevKeyed = keyedLives
            region.prevItems = keyedItems
        }

    /** Materializes a fresh mount root, creating the per-mount `Mounted` state. */
    def mount(root: Three)(using Frame): (Live, Mounted) < (Async & Scope & Abort[ThreeException]) =
        val mounted = new Mounted
        materialize(root, mounted).map(live => (live, mounted))
    end mount

    // ---- Wire-driven seams: the twins of reactiveStep/foreachStep for a server-pushed
    // ReplaceSubtree op, sharing the SAME diffKeyed/materializeInElemScope/disposeElemScope primitives.
    // The caller (ThreeBackend's drain loop) MUST run these under the AMBIENT MOUNT Scope (never a
    // transient Scope.run), so a freshly-spliced element's materializeInElemScope -> Scope.ensure
    // attaches to mount teardown, not an immediately-closing local scope.

    /** Finds the structural region whose holder is stamped with `path` (by ThreeBackend's
      * buildPathIndex). Wire-path and holder agree BY CONSTRUCTION: `path` is the same node path the
      * server's structural discovery walk assigns to this region.
      */
    private[kyo] def regionFor(mounted: Mounted, path: Seq[String]): Maybe[ReactiveRegion] =
        Maybe.fromOption(reactiveRegions(mounted).find(_.holderPath == path))

    /** The wire-driven twin of `foreachStep`'s body: reconciles `region` against a server-decoded keyed
      * set instead of the local signal's next value, relinking before it disposes what the diff retired.
      */
    private[kyo] def foreachReplace(region: ReactiveRegion, entries: Chunk[KeyedEntry], mounted: Mounted)(using
        Frame
    ): Unit < (Async & Scope & Abort[ThreeException]) =
        diffKeyed(region.prevKeyed, region.prevItems, entries, mounted).map { diff =>
            replaceHolderChildren(region, entries.map(_.key).zip(diff.lives), itemsByKey(entries), mounted)
                .andThen(disposeRetired(diff.retired, mounted))
        }

    /** The wire-driven twin of `reactiveStep`'s body: swaps `region`'s subtree to a server-decoded node
      * instead of the local signal's next value, disposing the prior subtree only after the swap.
      */
    private[kyo] def reactiveReplace(region: ReactiveRegion, next: Three, mounted: Mounted)(using
        Frame
    ): Unit < (Async & Scope & Abort[ThreeException]) =
        Sync.defer(region.prevKeyed.map(_._2)).map { prior =>
            materializeInElemScope(next, mounted).map { fresh =>
                replaceHolderChildren(region, Chunk(("reactive", fresh)), Map.empty, mounted)
                    .andThen(disposeRetired(prior, mounted))
            }
        }

end Reconciler
