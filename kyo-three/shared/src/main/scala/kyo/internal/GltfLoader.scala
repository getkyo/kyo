package kyo.internal

import kyo.*
import scala.scalajs.js

/** Loads a glTF/GLB into a [[kyo.Three]] subtree via the three.js `GLTFLoader`.
  *
  * The asynchronous `GLTFLoader.load(url, onLoad, onProgress, onError)` callback API is bridged to a
  * fiber-completing effect via `Promise.init`: the success callback yields the loaded scene wrapped as a
  * `Three.Ast.Custom` subtree plus a named-node index and clip names; the error callback surfaces a typed
  * [[kyo.ThreeException.AssetLoadFailed]] in the `Abort` row, never a silent drop or an escaped throw.
  * The loaded GPU resources register under `Scope` for disposal on scope close.
  *
  * A synchronous throw from the loader (e.g. a missing global in a headless environment) is caught and
  * converted to `AssetLoadFailed` so no exception escapes the `Abort` row.
  *
  * The `loadFromJson` seam accepts a raw glTF JSON string and drives `GLTFLoader.parse` directly, bypassing
  * network I/O; it is the entry point tests use in the Node environment where `fetch` is unavailable or
  * unreliable for local paths.
  */
private[kyo] object GltfLoader:

    /** Loads the glTF at `url`, returning the parsed [[kyo.Asset.Gltf]] handle. */
    def load(url: String)(using Frame): Asset.Gltf < (Async & Scope & Abort[ThreeException]) =
        for
            gltf  <- runLoad(url)
            asset <- registerForDisposal(gltf)
        yield asset

    /** Parses a raw glTF JSON string in-memory via `GLTFLoader.parse`, returning the parsed
      * [[kyo.Asset.Gltf]] handle. The parse path requires no network I/O and no DOM, making it
      * usable in Node test environments.
      */
    private[kyo] def loadFromJson(json: String)(using Frame): Asset.Gltf < (Async & Scope & Abort[ThreeException]) =
        for
            gltf  <- runParse(json)
            asset <- registerForDisposal(gltf)
        yield asset

    /** Bridges the `GLTFLoader.load` callback API to a fiber-completing effect; the error callback maps to
      * the typed `AssetLoadFailed` leaf. A synchronous throw from the loader is caught and routed to
      * `AssetLoadFailed` so no exception escapes the `Abort` row.
      */
    private def runLoad(url: String)(using Frame): js.Dynamic < (Async & Abort[ThreeException]) =
        // Unsafe: Promise.init creates an IOPromise; callbacks complete it from inside loader callbacks.
        Promise.init[js.Dynamic, Abort[ThreeException]].map { p =>
            try
                val loader = js.Dynamic.newInstance(GltfFacade.GLTFLoader)()
                loader.load(
                    url,
                    (gltf: js.Dynamic) =>
                        // Unsafe: callback runs outside kyo effect machinery; AllowUnsafe required.
                        import AllowUnsafe.embrace.danger
                        p.unsafe.completeDiscard(Result.succeed(gltf))
                    ,
                    (_: js.Dynamic) => (),
                    (err: js.Dynamic) =>
                        // Unsafe: callback runs outside kyo effect machinery; AllowUnsafe required.
                        import AllowUnsafe.embrace.danger
                        p.unsafe.completeDiscard(Result.fail(ThreeException.AssetLoadFailed(url, asThrowable(err))))
                )
            catch
                case t: Throwable =>
                    // Unsafe: synchronous throw converted to typed failure; AllowUnsafe required.
                    import AllowUnsafe.embrace.danger
                    p.unsafe.completeDiscard(Result.fail(ThreeException.AssetLoadFailed(url, t)))
            end try
            p.get
        }

    /** Bridges `GLTFLoader.parse(json, path, onLoad, onError)` to a fiber-completing effect. The parse
      * fires `onLoad` asynchronously (microtask) even though it does no I/O. A synchronous throw is caught
      * and routed to `AssetLoadFailed`.
      */
    private def runParse(json: String)(using Frame): js.Dynamic < (Async & Abort[ThreeException]) =
        // Unsafe: Promise.init creates an IOPromise; callbacks complete it from inside parse callbacks.
        Promise.init[js.Dynamic, Abort[ThreeException]].map { p =>
            try
                val loader = js.Dynamic.newInstance(GltfFacade.GLTFLoader)()
                loader.parse(
                    json,
                    "",
                    (gltf: js.Dynamic) =>
                        // Unsafe: callback runs outside kyo effect machinery; AllowUnsafe required.
                        import AllowUnsafe.embrace.danger
                        p.unsafe.completeDiscard(Result.succeed(gltf))
                    ,
                    (err: js.Dynamic) =>
                        // Unsafe: callback runs outside kyo effect machinery; AllowUnsafe required.
                        import AllowUnsafe.embrace.danger
                        p.unsafe.completeDiscard(Result.fail(ThreeException.AssetLoadFailed("<inline>", asThrowable(err))))
                )
            catch
                case t: Throwable =>
                    // Unsafe: synchronous throw converted to typed failure; AllowUnsafe required.
                    import AllowUnsafe.embrace.danger
                    p.unsafe.completeDiscard(Result.fail(ThreeException.AssetLoadFailed("<inline>", t)))
            end try
            p.get
        }

    /** Registers the loaded GPU resources for disposal and wraps the scene as an [[kyo.Asset.Gltf]]. */
    private def registerForDisposal(gltf: js.Dynamic)(using Frame): Asset.Gltf < (Sync & Scope) =
        Scope.acquireRelease(Sync.Unsafe.defer(gltf)) { g =>
            // Unsafe: dispose the loaded subtree's GPU resources by traversing and calling .dispose().
            Sync.Unsafe.defer(disposeSubtree(g.scene))
        }.map(g => toAsset(g))

    /** Reads the loaded scene off the GLTFLoader result and wraps it as an [[kyo.Asset.Gltf]]; the live
      * FFI reads run inside `Sync.Unsafe.defer` so they stay sequenced as an effect.
      */
    private def toAsset(gltf: js.Dynamic)(using Frame): Asset.Gltf < Sync =
        // Unsafe: reads the loaded scene and animations off the live GLTFLoader result.
        Sync.Unsafe.defer {
            val (root, nodes, clips) = toSubtree(gltf)
            Asset.Gltf(root, nodes, clips)
        }

    /** Wraps the loaded scene as a `Three.Ast.Custom` subtree, indexes the named sub-nodes, and collects
      * the animation clip names.
      */
    private def toSubtree(
        gltf: js.Dynamic
    )(using Frame, AllowUnsafe): (Three.Ast.Custom[js.Dynamic], Map[String, Three.Ast.Custom[js.Dynamic]], Chunk[String]) =
        val scene = gltf.scene
        val root  = Three.custom((s: js.Dynamic) => s)(scene)
        val names = collectNamed(scene)
        val clips = animationNames(gltf)
        (root, names, clips)
    end toSubtree

    /** Traverses the loaded scene collecting name -> `Custom` wrapper pairs. Nodes with an empty or absent
      * name are skipped; when two nodes share a name, the last-visited wins.
      */
    private def collectNamed(scene: js.Dynamic)(using Frame, AllowUnsafe): Map[String, Three.Ast.Custom[js.Dynamic]] =
        val buf = scala.collection.mutable.Map.empty[String, Three.Ast.Custom[js.Dynamic]]
        val _ = scene.traverse((obj: js.Dynamic) =>
            Maybe(obj.name.asInstanceOf[String]).filter(_.nonEmpty).foreach { name =>
                buf.update(name, Three.custom((o: js.Dynamic) => o)(obj))
            }
        )
        buf.toMap
    end collectNamed

    /** Reads the animation clip names from the loaded glTF result. The `animations` field is always an
      * array (possibly empty) on the result object.
      */
    private def animationNames(gltf: js.Dynamic)(using AllowUnsafe): Chunk[String] =
        val anims = gltf.animations.asInstanceOf[js.Array[js.Dynamic]]
        Chunk.from(anims.iterator.map(_.name.asInstanceOf[String]))
    end animationNames

    private def disposeSubtree(scene: js.Dynamic)(using AllowUnsafe): Unit =
        // Unsafe: traverse the loaded subtree disposing each geometry and material.
        val _ = scene.traverse((obj: js.Dynamic) => disposeObject(obj))

    private def disposeObject(obj: js.Dynamic)(using AllowUnsafe): Unit =
        // Unsafe: dispose a single loaded object's GPU resources when present.
        if !js.isUndefined(obj.geometry) then discard(obj.geometry.dispose())
        if !js.isUndefined(obj.material) then discard(obj.material.dispose())
    end disposeObject

    private def asThrowable(err: js.Dynamic): Throwable =
        // Wraps an Error-like js.Dynamic from the loader callback as a Throwable.
        new RuntimeException(String.valueOf(err))

end GltfLoader
