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
      * mount's `onClick`/`ReplaceSubtree` dispatch reads, the
      * `Reconciler.Mounted` the reconcile seams operate on, and the bounded replace-op queue the drain
      * fiber reads.
      */
    final private[kyo] class Live(
        val byPath: scala.collection.mutable.Map[Seq[String], Reconciler.Live],
        val byLive: scala.collection.mutable.Map[Reconciler.IdentityKey, Seq[String]],
        val mounted: Reconciler.Mounted,
        val replaceQueue: Channel[(Seq[String], String)]
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
                                mounted.reindexRegion = (region, keyed) => reindexRegion(mounted, byPath, byLive, region, keyed)
                                mounted.pathForLive = live => Maybe.fromOption(byLive.get(new Reconciler.IdentityKey(live.node)))
                                (byPath, byLive)
                            }.map { (byPath, byLive) => stateRef.set(Present((mounted, byPath, byLive))) }
                    )
                    state <- stateRef.get
                    live <- state match
                        case Present((mounted, byPath, byLive)) =>
                            (for
                                replaceQueue <- Channel.init[(Seq[String], String)](256)
                                _            <- Fiber.init(drainLoop(replaceQueue, mounted)).unit
                                live = new Live(byPath, byLive, mounted, replaceQueue)
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
                Abort.panic(new IllegalStateException(s"ThreeBackend.mount: not a Three.Ast.Embed: $node"))

    // Unsafe: applyByKey mutates the live three.js object's FFI facade directly; the ambient
    // AllowUnsafe comes from Sync.Unsafe.defer's own context function.
    def patch(path: Seq[String], key: String, encoded: String)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            liveFor(path).foreach(live => applyByKey(live.obj, key, encoded))
        }

    // Unsafe: the channel offer is a non-suspending enqueue; the ambient AllowUnsafe comes from
    // Sync.Unsafe.defer's own context function.
    def replaceSubtree(path: Seq[String], encoded: String)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            backendLiveFor(path).foreach(live => discard(live.replaceQueue.unsafe.offer((path, encoded))))
        }

    private def drainLoop(queue: Channel[(Seq[String], String)], mounted: Reconciler.Mounted)(using Frame): Unit < (Async & Scope) =
        Loop.foreach(
            Abort.runPartial[Closed](queue.take).map {
                case Result.Success((p, enc)) =>
                    Abort.run[ThreeException](applyReplace(p, enc, mounted)).map(_.fold(
                        _ => Loop.continue,
                        err => Log.error(s"ThreeBackend.replaceSubtree failed: ${err.getMessage}").andThen(Loop.continue),
                        panic =>
                            if panic.isInstanceOf[Interrupted] then Loop.done
                            else Log.error("ThreeBackend.replaceSubtree panicked", panic).andThen(Loop.continue)
                    ))
                case Result.Failure(_) => Loop.done
            }
        )

    private def applyReplace(path: Seq[String], encoded: String, mounted: Reconciler.Mounted)(using
        Frame
    ): Unit < (Async & Scope & Abort[ThreeException]) =
        Reconciler.regionFor(mounted, path) match
            case Present(region) => region.node match
                    case f: Three.Ast.Foreach[?] =>
                        Reconciler.foreachReplace(region, f.decodeKeyed(encoded), mounted)
                    case r: Three.Ast.Reactive =>
                        r.decodeSubtree(encoded) match
                            case Present(next) => Reconciler.reactiveReplace(region, next, mounted)
                            case Absent        => Kyo.unit
                    case _ => Kyo.unit
            case Absent => Kyo.unit // unknown path: forward-compatible no-op

    private def applyByKey(obj: js.Dynamic, key: String, encoded: String)(using Frame, AllowUnsafe): Unit =
        key match
            case "material.color"    => discard(obj.material.color.set(decodeInt(encoded).toDouble))
            case "material.emissive" => discard(obj.material.emissive.set(decodeInt(encoded).toDouble))
            case "color"             => discard(obj.color.set(decodeInt(encoded).toDouble))
            case "groundColor"       => discard(obj.groundColor.set(decodeInt(encoded).toDouble))
            case "material.opacity"  => obj.material.opacity = decodeDouble(encoded); obj.material.transparent = decodeDouble(encoded) < 1.0
            case "material.metalness" => obj.material.metalness = decodeDouble(encoded)
            case "material.roughness" => obj.material.roughness = decodeDouble(encoded)
            case "intensity"          => obj.intensity = decodeDouble(encoded)
            case "position"           => val v = decodeVec3(encoded); discard(obj.position.set(v.x, v.y, v.z))
            case "rotation"           => val v = decodeVec3(encoded); discard(obj.rotation.set(v.x, v.y, v.z))
            case "scale"              => val v = decodeVec3(encoded); discard(obj.scale.set(v.x, v.y, v.z))
            case "lookAt"             => val v = decodeVec3(encoded); discard(obj.lookAt(v.x, v.y, v.z))
            case other                => () // unknown key: drop (forward-compatible no-op)

    private def decodeInt(s: String)(using Frame): Int         = Json.decode[Int](s).getOrElse(0)
    private def decodeDouble(s: String)(using Frame): Double   = Json.decode[Double](s).getOrElse(0.0)
    private def decodeVec3(s: String)(using Frame): Three.Vec3 = Json.decode[Three.Vec3](s).getOrElse(Three.Vec3.zero)

    // The per-mount registry, keyed by the mount's OWN root path (the Embed's data-kyo-path). A patch/
    // replaceSubtree targets some DESCENDANT path; backendLiveFor resolves the longest registered root
    // that is a prefix of the target path (the nearest enclosing mount), mirroring DomBackend's own
    // backendForPath ancestor walk. Single-owner: only ever touched on the page's main fiber (a mount
    // registers/unregisters, a patch/replaceSubtree only reads); no concurrency control needed.
    private val mounts: scala.collection.mutable.Map[Seq[String], Live] =
        new scala.collection.mutable.HashMap[Seq[String], Live]()
    private def registerMount(root: Seq[String], live: Live)(using AllowUnsafe): Unit = mounts.update(root, live)
    private def unregisterMount(root: Seq[String])(using AllowUnsafe): Unit           = discard(mounts.remove(root))
    private def backendLiveFor(path: Seq[String]): Maybe[Live] =
        Maybe.fromOption(mounts.view.filterKeys(root => path.startsWith(root)).toSeq.sortBy(-_._1.size).headOption.map(_._2))
    private def liveFor(path: Seq[String]): Maybe[Reconciler.Live] =
        backendLiveFor(path).flatMap(live => Maybe.fromOption(live.byPath.get(path)))

    // exposes {patch, replaceSubtree} on window.__kyoBackends[root], the SAME registry
    // HtmlRenderer.clientJs's inline WS listener reads (via backendForPath) to route a server-pushed
    // SetProp/ReplaceSubtree to this mount. Without this, a pushed op resolves the backend's OWN
    // Backend.lookup(key) fine (the FIRST mount dispatch) but has no JS-callable handle for a LATER
    // patch to reach, since window.__kyoBackends and the Scala-side Backend registry are two
    // different registries serving two different call sites (mount-time key dispatch vs. later
    // path-addressed prop/structural pushes).
    private def registerJsHandle(root: Seq[String])(using AllowUnsafe): Unit =
        given Frame = Frame.internal
        val w       = dom.window.asInstanceOf[js.Dynamic]
        if js.isUndefined(w.__kyoBackends) then w.__kyoBackends = js.Dynamic.literal()
        // Unsafe: each wrapper fires the underlying effect as a DETACHED fiber (the page-to-kyo
        // boundary idiom this module already uses at every @JSExportTopLevel entry): a synchronous
        // JS callback cannot await an Async effect, so it starts one and returns immediately.
        val handle = js.Dynamic.literal(
            patch = (p: js.Array[String], key: String, encoded: String) =>
                discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(patch(Seq.from(p), key, encoded)).unit)),
            replaceSubtree = (p: js.Array[String], encoded: String) =>
                discard(Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(replaceSubtree(Seq.from(p), encoded)).unit))
        )
        w.__kyoBackends.asInstanceOf[js.Dictionary[js.Any]].update(root.mkString("."), handle)
    end registerJsHandle

    private def unregisterJsHandle(root: Seq[String])(using AllowUnsafe): Unit =
        val w = dom.window.asInstanceOf[js.Dynamic]
        if !js.isUndefined(w.__kyoBackends) then
            discard(w.__kyoBackends.asInstanceOf[js.Dictionary[js.Any]].remove(root.mkString(".")))
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
      * prior set at this call point — `replaceHolderChildren` calls this before overwriting it), then
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
