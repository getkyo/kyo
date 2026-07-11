package kyo.internal

import kyo.*
import org.scalajs.dom
import scala.scalajs.js

/** The kyo-threejs client [[Backend]]: mounts a `Three.Ast.Embed` node into its host canvas, patches
  * one scalar bound prop per `Bound.Ref` emission (`patch`), and re-materializes a structural region
  * (a `render`/`foreach`/`foreachKeyed`) from a server-pushed `ReplaceSubtree` snapshot
  * (`replaceSubtree`). Registers itself into the client [[Backend]] registry the first
  * time it is touched (every `Three.embed` call touches it via `Three.Ast.Embed`'s construction), so
  * `DomBackend.fireHostMounts`'s generic `Backend.lookup("three")` dispatch resolves it with no
  * client-side wiring beyond building the tree.
  */
private[kyo] object ThreeBackend extends Backend:

    def key: String = "three"

    // Self-registers on first touch, mirroring how DomBackend pre-registers inside its own mountInto.
    Backend.register(this)

    /** A no-op call whose only purpose is to force this object's initializer (the registration above)
      * to run. `Three.Ast.Embed`'s constructor calls this so every embed usage guarantees registration
      * before any later dispatch path looks this backend up by key.
      */
    private[kyo] def ensureRegistered(): Unit = ()

    /** The per-mount live state: the bidirectional `data-kyo-path` <-> `Reconciler.Live` index this
      * mount's `onClick`/`ReplaceSubtree` dispatch reads, the `Reconciler.Mounted` the reconcile seams
      * operate on, and the per-path latest-snapshot buffer + wakeup the drain fiber reads.
      */
    final private[kyo] class Live(
        val byPath: scala.collection.mutable.Map[Seq[String], Reconciler.Live],
        val byLive: scala.collection.mutable.Map[Reconciler.IdentityKey, Seq[String]],
        val mounted: Reconciler.Mounted,
        // The latest un-applied `ReplaceSubtree` snapshot PER path, drained as a batch on each wakeup.
        // Each op is a COMPLETE subtree snapshot, so a newer op for a path fully supersedes an older one:
        // overwriting the slot keeps only the newest (never the stale drop-newest a bounded queue caused),
        // and the drain applies each path's latest exactly once. Insertion-ordered so distinct paths apply
        // in first-enqueued order.
        val pending: scala.collection.mutable.LinkedHashMap[Seq[String], String],
        // A capacity-1 wakeup: `replaceSubtree` offers one (deduped) after updating a pending slot; the
        // drain fiber parks on it and drains all pending slots when it fires.
        val wakeup: Channel[Unit]
    ) extends Backend.Live

    def mount(node: UI, host: dom.Element, path: Seq[String])(using Frame): Backend.Live < (Async & Scope) =
        node match
            case e: Three.Ast.Embed =>
                for
                    stateRef <- AtomicRef.init(
                        Maybe.empty[(
                            Reconciler.Mounted,
                            scala.collection.mutable.Map[Seq[String], Reconciler.Live],
                            scala.collection.mutable.Map[Reconciler.IdentityKey, Seq[String]]
                        )]
                    )
                    _ <- ThreeMount.hostMountPipeline(
                        e.scene,
                        e.camera,
                        e.frames,
                        host,
                        (rootLive, mounted) =>
                            // Unsafe: Sync.Unsafe.defer's block already carries an ambient AllowUnsafe
                            // (the context function it is defined against); buildPathIndex/reindexRegion
                            // resolve it from there.
                            Sync.Unsafe.defer {
                                val byPath = scala.collection.mutable.Map.empty[Seq[String], Reconciler.Live]
                                val byLive = scala.collection.mutable.Map.empty[Reconciler.IdentityKey, Seq[String]]
                                buildPathIndex(rootLive, path :+ "0", mounted, byPath, byLive)
                                // Index the render camera (recorded into mounted.live by the mount pipeline)
                                // at path :+ "1", matching Embed.backendChildren(1), so a server-driven
                                // camera SetProp (its lookAt/position) resolves the live camera object.
                                mounted.live.get(new Reconciler.IdentityKey(e.camera)).foreach { camLive =>
                                    byPath.update(path :+ "1", camLive)
                                    byLive.update(new Reconciler.IdentityKey(e.camera), path :+ "1")
                                }
                                mounted.reindexRegion = (region, keyed) => reindexRegion(mounted, byPath, byLive, region, keyed)
                                mounted.pathForLive = live => Maybe.fromOption(byLive.get(new Reconciler.IdentityKey(live.node)))
                                (byPath, byLive)
                            }.map { (byPath, byLive) => stateRef.set(Present((mounted, byPath, byLive))) }
                    )
                    state <- stateRef.get
                    live <- state match
                        case Present((mounted, byPath, byLive)) =>
                            (for
                                wakeup <- Channel.init[Unit](1)
                                pending = scala.collection.mutable.LinkedHashMap.empty[Seq[String], String]
                                live    = new Live(byPath, byLive, mounted, pending, wakeup)
                                _ <- Fiber.init(drainLoop(live)).unit
                                // Unsafe: register/unregister mutate the mounts map + window.__kyoBackends;
                                // safe because the whole surface is JS-only (single event-loop thread) and
                                // each access is synchronous inside Sync.Unsafe.defer, so no two fibers interleave.
                                _ <- Sync.Unsafe.defer(registerMount(path, live))
                                _ <- Sync.Unsafe.defer(registerJsHandle(path))
                                _ <- Scope.ensure(Sync.Unsafe.defer {
                                    unregisterMount(path)
                                    unregisterJsHandle(path)
                                })
                            yield (live: Backend.Live)): Backend.Live < (Async & Scope)
                        case Absent =>
                            // hostMountPipeline's own pipeline failed before onMounted ran (e.g. no WebGL
                            // context); it already logged the failure (Log.error) per its existing
                            // swallow-and-log contract. mount's row carries no Abort, so it still returns
                            // a (harmless, unregistered) Live: every later patch/replaceSubtree for this
                            // path resolves Absent from backendLiveFor and is a silent no-op.
                            (new Backend.Live {}: Backend.Live): Backend.Live < (Async & Scope)
                yield live
            case _ =>
                // A bare Three node (not an Embed) reached the mount seam: it was placed directly in a UI
                // tree (UI.div(Three.mesh(...))) instead of embedded via Three.embed(scene, camera). Contain
                // it here (Log.error + an inert Live) so the rest of the page still mounts, matching the
                // seam's swallow-and-log contract; a panic would escape fireHostMounts and abort the whole
                // page mount.
                Log.error("a Three node must be embedded via Three.embed(scene, camera) to appear in a UI tree")
                    .andThen(new Backend.Live {}: Backend.Live)

    // Unsafe: applyByKey mutates the live three.js object's FFI facade directly; the ambient
    // AllowUnsafe comes from Sync.Unsafe.defer's own context function.
    def patch(path: Seq[String], key: String, encoded: String)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            liveFor(path).foreach(live => applyByKey(live.obj, key, encoded))
        }

    // Unsafe: the pending-slot update and wakeup offer are non-suspending; the ambient AllowUnsafe comes
    // from Sync.Unsafe.defer's own context function.
    def replaceSubtree(path: Seq[String], encoded: String)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            backendLiveFor(path).foreach(live => enqueueReplace(live, path, encoded))
        }

    /** Buffers the LATEST `ReplaceSubtree` snapshot for `path` and wakes the drain fiber. Each op is a
      * complete subtree snapshot, so overwriting the slot coalesces to the newest (the newest is never
      * dropped, unlike a bounded queue's drop-on-full); a single capacity-1 wakeup schedules the drain.
      */
    private[kyo] def enqueueReplace(live: Live, path: Seq[String], encoded: String)(using Frame, AllowUnsafe): Unit =
        live.pending.update(path, encoded)
        discard(live.wakeup.unsafe.offer(()))
    end enqueueReplace

    /** Snapshots and clears the pending slots as one batch (the latest snapshot per path, in first-
      * enqueued order). Single JS event-loop thread, so no op can arrive mid-drain and be lost.
      */
    private[kyo] def takePending(live: Live)(using AllowUnsafe): Seq[(Seq[String], String)] =
        val batch = live.pending.toSeq
        live.pending.clear()
        batch
    end takePending

    private def drainLoop(live: Live)(using Frame): Unit < (Async & Scope) =
        Loop.foreach(
            Abort.runPartial[Closed](live.wakeup.take).map {
                case Result.Success(_) =>
                    Sync.Unsafe.defer(takePending(live)).map { batch =>
                        Kyo.foreachDiscard(batch) { case (p, enc) =>
                            Abort.run[ThreeException](applyReplace(p, enc, live.mounted)).map(_.fold(
                                _ => Kyo.unit,
                                err => Log.error(s"ThreeBackend.replaceSubtree failed: ${err.getMessage}"),
                                panic =>
                                    if panic.isInstanceOf[Interrupted] then Kyo.unit
                                    else Log.error("ThreeBackend.replaceSubtree panicked", panic)
                            ))
                        }.andThen(Loop.continue)
                    }
                case Result.Failure(_) => Loop.done
            }
        )

    private[kyo] def applyReplace(path: Seq[String], encoded: String, mounted: Reconciler.Mounted)(using
        Frame
    ): Unit < (Async & Scope & Abort[ThreeException]) =
        Reconciler.regionFor(mounted, path) match
            case Present(region) => region.node match
                    case f: Three.Ast.Foreach[?] =>
                        f.decodeKeyed(encoded) match
                            case Present(keyed) => Reconciler.foreachReplace(region, keyed, mounted)
                            case Absent         => Kyo.unit // bad wire value: forward-compatible no-op
                    case r: Three.Ast.Reactive =>
                        r.decodeSubtree(encoded) match
                            case Present(next) => Reconciler.reactiveReplace(region, next, mounted)
                            case Absent        => Kyo.unit // absent drive or bad wire value: no-op
                    case _ => Kyo.unit
            case Absent => Kyo.unit // unknown path: forward-compatible no-op

    private[kyo] def applyByKey(obj: js.Dynamic, key: String, encoded: String)(using Frame, AllowUnsafe): Unit =
        key match
            case "material.color"    => decodeInt(encoded).foreach(i => discard(obj.material.color.set(i.toDouble)))
            case "material.emissive" => decodeInt(encoded).foreach(i => discard(obj.material.emissive.set(i.toDouble)))
            case "color"             => decodeInt(encoded).foreach(i => discard(obj.color.set(i.toDouble)))
            case "groundColor"       => decodeInt(encoded).foreach(i => discard(obj.groundColor.set(i.toDouble)))
            case "material.opacity" => decodeDouble(encoded).foreach { d =>
                    obj.material.opacity = d
                    obj.material.transparent = d < 1.0
                }
            case "material.metalness" => decodeDouble(encoded).foreach(d => obj.material.metalness = d)
            case "material.roughness" => decodeDouble(encoded).foreach(d => obj.material.roughness = d)
            case "intensity"          => decodeDouble(encoded).foreach(d => obj.intensity = d)
            case "position"           => decodeVec3(encoded).foreach(v => discard(obj.position.set(v.x, v.y, v.z)))
            case "rotation"           => decodeVec3(encoded).foreach(v => discard(obj.rotation.set(v.x, v.y, v.z)))
            case "scale"              => decodeVec3(encoded).foreach(v => discard(obj.scale.set(v.x, v.y, v.z)))
            case "lookAt"             => decodeVec3(encoded).foreach(v => discard(obj.lookAt(v.x, v.y, v.z)))
            case other                => () // unknown key: drop (forward-compatible no-op)

    // On a decode failure these SKIP (Absent) rather than snapping to a meaningful default: a bad wire
    // value leaves the last-good value in place instead of painting black (0) or the origin (Vec3.zero),
    // matching the unknown-key no-op above.
    private def decodeInt(s: String)(using Frame): Maybe[Int]         = Json.decode[Int](s).toMaybe
    private def decodeDouble(s: String)(using Frame): Maybe[Double]   = Json.decode[Double](s).toMaybe
    private def decodeVec3(s: String)(using Frame): Maybe[Three.Vec3] = Json.decode[Three.Vec3](s).toMaybe

    // The per-mount registry, keyed by the mount's OWN root path (the Embed's data-kyo-path). A patch/
    // replaceSubtree targets some DESCENDANT path; backendLiveFor resolves the longest registered root
    // that is a prefix of the target path (the nearest enclosing mount), a longest-prefix match over the
    // registered roots. Race-free by construction: the whole surface is JS-only, so every
    // fiber (including the detached patch/replaceSubtree fibers) runs on the single JS event-loop thread,
    // and each access is synchronous inside a Sync.Unsafe.defer block (no suspension mid-op); no two
    // fibers can interleave, so no concurrency control is needed.
    private val mounts: scala.collection.mutable.Map[Seq[String], Live] =
        new scala.collection.mutable.HashMap[Seq[String], Live]()
    private def registerMount(root: Seq[String], live: Live)(using AllowUnsafe): Unit = mounts.update(root, live)
    private def unregisterMount(root: Seq[String])(using AllowUnsafe): Unit           = discard(mounts.remove(root))
    private def backendLiveFor(path: Seq[String]): Maybe[Live] =
        Maybe.fromOption(mounts.view.filterKeys(root => path.startsWith(root)).toSeq.sortBy(-_._1.size).headOption.map(_._2))
    private def liveFor(path: Seq[String]): Maybe[Reconciler.Live] =
        backendLiveFor(path).flatMap(live => Maybe.fromOption(live.byPath.get(path)))

    // Exposes {patch, replaceSubtree} on window.__kyoBackends[root], the SAME registry
    // HtmlRenderer.clientJs's inline WS listener reads (via backendRootPath/__kyoBackendOp) to route a
    // server-pushed SetProp/ReplaceSubtree to this mount. Without this, a pushed op resolves the backend's
    // OWN Backend.lookup(key) fine (the FIRST mount dispatch) but has no JS-callable handle for a LATER
    // patch to reach, since window.__kyoBackends and the Scala-side Backend registry are two different
    // registries serving two different call sites (mount-time key dispatch vs. later path-addressed prop/
    // structural pushes). Registration goes through the inline clientJs's __kyoBackendsRegister, which both
    // installs the handle and FLUSHES any ops buffered for this root before it registered (the startup-race
    // buffer): a server signal that changes between WS-open and this island's registration is not lost.
    private def registerJsHandle(root: Seq[String])(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        val w       = dom.window.asInstanceOf[js.Dynamic]
        // Unsafe: each wrapper fires the underlying effect as a DETACHED fiber (the page-to-kyo
        // boundary idiom this module already uses at every @JSExportTopLevel entry): a synchronous
        // JS callback cannot await an Async effect, so it starts one and returns immediately.
        val handle = js.Dynamic.literal(
            patch = (p: js.Array[String], key: String, encoded: String) =>
                discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(patch(Seq.from(p), key, encoded)).unit)),
            replaceSubtree = (p: js.Array[String], encoded: String) =>
                discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(replaceSubtree(Seq.from(p), encoded)).unit))
        )
        val rootKey = root.mkString(".")
        if js.typeOf(w.__kyoBackendsRegister) == "function" then
            // Server-push page: the inline clientJs owns the registry + startup buffer. Register through it
            // so any ops buffered before this island mounted flush in arrival order.
            discard(w.__kyoBackendsRegister(rootKey, handle))
        else
            // Client-local runMount page: no inline clientJs, so no server pushes and no buffer. Write the
            // handle directly, initializing the registry if this is the first backend mount.
            if js.isUndefined(w.__kyoBackends) then w.__kyoBackends = js.Dynamic.literal()
            w.__kyoBackends.asInstanceOf[js.Dictionary[js.Any]].update(rootKey, handle)
        end if
    end registerJsHandle

    private def unregisterJsHandle(root: Seq[String])(using AllowUnsafe): Unit =
        val w       = dom.window.asInstanceOf[js.Dynamic]
        val rootKey = root.mkString(".")
        if !js.isUndefined(w.__kyoBackends) then
            discard(w.__kyoBackends.asInstanceOf[js.Dictionary[js.Any]].remove(rootKey))
        // Drop any never-flushed startup buffer for this root so a torn-down mount leaves nothing behind.
        if !js.isUndefined(w.__kyoBackendsPending) then
            discard(w.__kyoBackendsPending.asInstanceOf[js.Dictionary[js.Any]].remove(rootKey))
    end unregisterJsHandle

    // ---- The FFI-free path->Live index plumbing --------------------------------------------

    /** Walks the live tree from `live` (assigned `path`), indexing every node by its `data-kyo-path`
      * equivalent. Mirrors the SERVER's path assignment exactly: a container's materialized children
      * (`Reconciler.Live.children`, built 1:1 from the AST's own `children`, the same list
      * `backendChildren` projects) are index-addressed (`path :+ i`); a structural region
      * (`Ast.Reactive`/`Ast.Foreach`) is stamped at its OWN holder path (`region.holderPath = path`,
      * matching how the server's `ReactiveUI.normalize` places the structural child at the carrier's
      * own path) and its CURRENT keyed children (`region.prevKeyed`, already filled by
      * `fillReactiveRegionsOnce` before this runs) are indexed under it: a `Foreach`'s keyed entries at
      * `path :+ key` (matching the wire's keyed decode), a `Reactive`'s single entry AT `path` itself
      * (path-transparent: the `"reactive"` `prevKeyed` literal is diff bookkeeping, not a path
      * segment).
      */
    private[kyo] def buildPathIndex(
        live: Reconciler.Live,
        path: Seq[String],
        mounted: Reconciler.Mounted,
        byPath: scala.collection.mutable.Map[Seq[String], Reconciler.Live],
        byLive: scala.collection.mutable.Map[Reconciler.IdentityKey, Seq[String]]
    )(using AllowUnsafe): Unit =
        byPath.update(path, live)
        byLive.update(new Reconciler.IdentityKey(live.node), path)
        live.node match
            case _: Three.Ast.Reactive =>
                Reconciler.reactiveRegions(mounted).find(_.holder eq live).foreach { region =>
                    region.holderPath = path
                    region.prevKeyed.foreach { case (_, childLive) => buildPathIndex(childLive, path, mounted, byPath, byLive) }
                }
            case _: Three.Ast.Foreach[?] =>
                Reconciler.reactiveRegions(mounted).find(_.holder eq live).foreach { region =>
                    region.holderPath = path
                    region.prevKeyed.foreach { case (key, childLive) => buildPathIndex(childLive, path :+ key, mounted, byPath, byLive) }
                }
            case _ =>
                live.children.zipWithIndex.foreach { case (child, i) => buildPathIndex(child, path :+ i.toString, mounted, byPath, byLive) }
        end match
    end buildPathIndex

    /** Re-indexes `region` after a splice: un-indexes its PRIOR keyed set (`region.prevKeyed`, still the
      * prior set at this call point (`replaceHolderChildren` calls this before overwriting it), then
      * re-indexes the NEW keyed set under the SAME path scheme `buildPathIndex` uses (a `Foreach`'s
      * entries at `holderPath :+ key`, a `Reactive`'s single entry AT `holderPath`). A key that survives
      * the splice is un-indexed then immediately re-indexed at the SAME path (a no-op on `byPath`/
      * `byLive`, since Foreach paths are key-addressed, not position-addressed, so a reorder never moves
      * a surviving key's path); a dropped key stays un-indexed; a spliced key is freshly indexed,
      * recursively covering its own nested descendants (`buildPathIndex`'s own recursion).
      */
    private def reindexRegion(
        mounted: Reconciler.Mounted,
        byPath: scala.collection.mutable.Map[Seq[String], Reconciler.Live],
        byLive: scala.collection.mutable.Map[Reconciler.IdentityKey, Seq[String]],
        region: Reconciler.ReactiveRegion,
        keyed: Chunk[(String, Reconciler.Live)]
    )(using AllowUnsafe): Unit =
        val isReactive = region.node.isInstanceOf[Three.Ast.Reactive]
        region.prevKeyed.foreach { case (_, oldLive) => unindexSubtree(oldLive, byPath, byLive) }
        keyed.foreach { case (key, newLive) =>
            val childPath = if isReactive then region.holderPath else region.holderPath :+ key
            buildPathIndex(newLive, childPath, mounted, byPath, byLive)
        }
    end reindexRegion

    /** Un-indexes `live` and every STATICALLY-nested descendant (`live.children`, the same tree
      * `buildPathIndex`'s catch-all recurses), removing both the `byPath` and `byLive` entries.
      */
    private def unindexSubtree(
        live: Reconciler.Live,
        byPath: scala.collection.mutable.Map[Seq[String], Reconciler.Live],
        byLive: scala.collection.mutable.Map[Reconciler.IdentityKey, Seq[String]]
    )(using AllowUnsafe): Unit =
        val key = new Reconciler.IdentityKey(live.node)
        byLive.get(key).foreach(p => discard(byPath.remove(p)))
        discard(byLive.remove(key))
        live.children.foreach(child => unindexSubtree(child, byPath, byLive))
    end unindexSubtree

end ThreeBackend
