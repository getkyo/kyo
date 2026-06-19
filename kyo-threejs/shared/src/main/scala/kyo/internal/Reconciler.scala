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

        // The structural reactive regions, built once after materialize and cached so each carries its
        // own keyed-reuse state across emissions (one region instance per holder for the mount's life).
        private[kyo] var regions: Maybe[Chunk[ReactiveRegion]] = Absent

        // The live-mount hook invoked for every element materialized inside a reactive/foreach region,
        // run under that element's own scope so any Bound.Ref observe fibers it forks dispose with the
        // element. The default no-op covers the headless toImage path, which fills reactive props once
        // (fillBoundRefsOnce) instead of forking observe fibers. The live mount installs the real hook.
        private[kyo] var subscribeElement: Live => Unit < (Async & Scope) = (_: Live) => ()
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
        mounted.live.update(new IdentityKey(node), liveNode)
        liveNode
    end record

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
                        // Bound.Ref observe fibers it forks for the new subtree dispose with the element.
                        Sync.Unsafe.defer(mounted.elemScopes.update(new IdentityKey(live.node), elemFinalizer))
                            .andThen(mounted.subscribeElement(live))
                            .andThen(live)
                    }
                )
            }
        }

    /** Removes a live node and its full subtree from `mounted.live`. Called on removal before closing
      * the per-element scope, so the live map never holds stale entries for disposed objects.
      */
    private def removeFromLive(live: Live, mounted: Mounted)(using AllowUnsafe): Unit =
        val _ = mounted.live.remove(new IdentityKey(live.node))
        live.children.foreach(c => removeFromLive(c, mounted))

    /** Closes the per-element scope for `removedLive` (disposing its GL resources exactly once) and
      * retires its `mounted.live` entries. Returns the close+await effect so the caller can sequence it.
      */
    private[kyo] def disposeElemScope(removedLive: Live, mounted: Mounted)(using
        Frame
    ): Unit < (Async & Sync) =
        Sync.Unsafe.defer {
            val key = new IdentityKey(removedLive.node)
            val fin = Maybe.fromOption(mounted.elemScopes.remove(key))
            removeFromLive(removedLive, mounted)
            fin
        }.map {
            case Present(fin) => fin.close(Absent).andThen(fin.await)
            case Absent       => ()
        }

    /** Reconciles a keyed `Foreach`: each key present in both the prior and next lists reuses its live
      * object (the GPU buffers survive); a removed key disposes its GL resources exactly once (the
      * per-element scope closes) and retires its `mounted.live` entry; a new key materializes under a
      * fresh per-element scope.
      */
    def diffKeyed(
        prev: Chunk[(String, Live)],
        next: Chunk[(String, Three)],
        mounted: Mounted
    )(using Frame): Chunk[Live] < (Async & Scope & Abort[ThreeException]) =
        val prevByKey: scala.collection.mutable.Map[String, Live] =
            new scala.collection.mutable.HashMap[String, Live]()
        prev.foreach { case (k, v) => prevByKey.update(k, v) }
        val nextKeySet: scala.collection.mutable.Set[String] =
            new scala.collection.mutable.HashSet[String]()
        next.foreach { case (k, _) => nextKeySet.add(k) }
        // Dispose removed keys: present in prev but absent from next.
        val removed = prev.filter { case (k, _) => !nextKeySet.contains(k) }
        Kyo.foreachDiscard(removed) { case (_, removedLive) =>
            disposeElemScope(removedLive, mounted)
        }.andThen {
            // Materialize new keys; reuse live objects for keys present in both prev and next.
            Kyo.foreach(next) { case (key, node) =>
                Maybe.fromOption(prevByKey.get(key)) match
                    case Present(reused) => reused: Live < (Async & Scope & Abort[ThreeException])
                    case Absent          => materializeInElemScope(node, mounted)
            }
        }
    end diffKeyed

    /** A structural reactive region: the empty holder `Live` whose child subtree is driven by the
      * `Reactive`/`Foreach` node's signal, plus the keyed live children from the last reconcile. The
      * mount forks one reconcile fiber per region; the keyed state is single-writer (touched only by
      * that fiber, and by the sequential startup fill), so it needs no cross-fiber synchronization.
      */
    final private[kyo] class ReactiveRegion(val holder: Live, val node: Three):
        // Single-owner per region: the last reconcile's keyed live children, read to diff the
        // next emission so an unchanged key reuses its live object.
        private[kyo] var prevKeyed: Chunk[(String, Live)] = Chunk.empty
    end ReactiveRegion

    /** The structural reactive regions (`Ast.Reactive`, `Ast.Foreach`) of the mount, built once from the
      * live map and cached on the `Mounted` so each region keeps its keyed-reuse state across emissions
      * (the fill, the change watcher, and every test seam share the same instances).
      */
    def reactiveRegions(mounted: Mounted): Chunk[ReactiveRegion] =
        mounted.regions match
            case Present(cached) => cached
            case Absent =>
                var buf = Chunk.empty[ReactiveRegion]
                mounted.live.values.foreach { live =>
                    live.node match
                        case _: Ast.Reactive   => buf = buf.appended(new ReactiveRegion(live, live.node))
                        case _: Ast.Foreach[?] => buf = buf.appended(new ReactiveRegion(live, live.node))
                        case _                 => ()
                }
                mounted.regions = Present(buf)
                buf
    end reactiveRegions

    /** Watches one structural reactive region forever: on every change to the region's signal,
      * re-reconciles the holder's children. The signal's CURRENT value is filled synchronously at
      * startup by [[fillReactiveRegionsOnce]]; this loop only handles subsequent changes. A `Reactive`
      * swaps its single subtree (disposing the prior subtree exactly once); a `Foreach` diffs by key
      * (positional when unkeyed) so an unchanged element reuses its live object (GPU buffers survive).
      * New objects materialize under a per-element scope registered with the mount scope; removed
      * elements dispose their GL resources immediately and retire their `mounted.live` entries.
      */
    def runReactiveRegion(region: ReactiveRegion, mounted: Mounted)(using
        Frame
    ): Unit < (Async & Scope & Abort[ThreeException]) =
        region.node match
            case r: Ast.Reactive =>
                val step = reactiveStep(region, mounted)
                Loop.foreach(r.signal.next.map(step).andThen(Loop.continue))
            case f: Ast.Foreach[a] =>
                val step = foreachStep(region, f, mounted)
                Loop.foreach(f.signal.next.map(step).andThen(Loop.continue))
            case _ => ()

    /** Reconciles every region once from its signal's current value: the deterministic single-fill the
      * mount runs at startup so the first rendered frame is already populated, and the seam the live
      * tests assert against.
      */
    def fillReactiveRegionsOnce(mounted: Mounted)(using Frame): Unit < (Async & Scope & Abort[ThreeException]) =
        Kyo.foreachDiscard(reactiveRegions(mounted)) { region =>
            region.node match
                case r: Ast.Reactive   => r.signal.current.map(reactiveStep(region, mounted))
                case f: Ast.Foreach[a] => f.signal.current.map(foreachStep(region, f, mounted))
                case _                 => ()
        }

    /** The per-emission reconcile for a `Reactive` region: disposes the prior subtree exactly once,
      * materializes the new subtree under a fresh per-element scope, and swaps it into the holder.
      */
    private def reactiveStep(region: ReactiveRegion, mounted: Mounted)(using
        Frame
    ): Three => Unit < (Async & Scope & Abort[ThreeException]) =
        next =>
            // Dispose the prior subtree (present on every emission after the first fill).
            Kyo.foreachDiscard(region.prevKeyed) { case (_, prevLive) =>
                disposeElemScope(prevLive, mounted)
            }.andThen {
                materializeInElemScope(next, mounted).map { fresh =>
                    replaceHolderChildren(region, Chunk(("reactive", fresh)))
                }
            }

    /** The per-emission reconcile for a `Foreach` region: key the elements, diff against the prior
      * keyed live children (so an unchanged key reuses its object and keeps its GL buffers), then
      * relink the reconciled set in order.
      */
    private def foreachStep[A](region: ReactiveRegion, f: Ast.Foreach[A], mounted: Mounted)(using
        Frame
    ): Chunk[A] => Unit < (Async & Scope & Abort[ThreeException]) =
        items =>
            val keyed = keyEntries(f, items)
            diffKeyed(region.prevKeyed, keyed, mounted).map { lives =>
                replaceHolderChildren(region, keyed.map(_._1).zip(lives))
            }

    /** Pairs each `Foreach` element with its key (the keyed accessor, or its index when unkeyed) and the
      * node the element renders to.
      */
    private def keyEntries[A](f: Ast.Foreach[A], items: Chunk[A]): Chunk[(String, Three)] =
        items.zipWithIndex.map { case (item, i) =>
            val key = f.key.fold(i.toString)(_(item))
            (key, f.render(i, item))
        }

    /** Detaches every current child of the holder, attaches the reconciled keyed set in order, and
      * records the keyed live children so the next emission diffs against them (keyed reuse).
      * Removed children are disposed by the caller before this runs; only the scene-graph relink
      * and state update happen here.
      */
    private def replaceHolderChildren(region: ReactiveRegion, keyedLives: Chunk[(String, Live)])(using
        Frame
    ): Unit < Sync =
        // Unsafe: the keyed re-link is a synchronous scene-graph relink on objects the reconciler owns.
        Sync.Unsafe.defer {
            val holder      = region.holder.obj
            val childrenArr = holder.children.asInstanceOf[js.Array[js.Dynamic]]
            val snapshot    = childrenArr.toArray
            snapshot.foreach(child => ThreeFacadeOps.detachUnsafe(holder, child))
            keyedLives.foreach { case (_, l) => ThreeFacadeOps.attachUnsafe(holder, l.obj) }
            region.prevKeyed = keyedLives
        }

    /** Materializes a fresh mount root, creating the per-mount `Mounted` state. */
    def mount(root: Three)(using Frame): (Live, Mounted) < (Async & Scope & Abort[ThreeException]) =
        val mounted = new Mounted
        materialize(root, mounted).map(live => (live, mounted))
    end mount

end Reconciler
