package kyo.internal

import kyo.*
import scala.scalajs.js

/** Loads an image into a GPU texture via the three.js `TextureLoader`, the curated factory behind a
  * material's optional `map` field.
  *
  * The asynchronous `TextureLoader.load(url, onLoad, onProgress, onError)` callback API is bridged to a
  * fiber-completing effect via `Promise.init`; the success callback yields the live three.js texture, which
  * is `Scope.acquireRelease`-registered so scope teardown disposes the GPU resource by cascade, preventing
  * GL leaks; the error callback surfaces a typed [[kyo.ThreeException.AssetLoadFailed]] in the `Abort` row,
  * never a silent drop. The pure [[kyo.Three.Ast.Texture.FromUrl]] handle the loader returns carries the
  * `url`; the live texture is recorded in [[kyo.internal.Reconciler.TextureRegistry]] so the
  * [[kyo.internal.Reconciler]] materializes it onto a material's `map` at mount without the user touching
  * the bare case class.
  *
  * A synchronous throw from the loader (e.g. `document.createElementNS` missing in a headless Node
  * environment) is caught and converted to `AssetLoadFailed` so no exception escapes the `Abort` row.
  */
private[kyo] object TextureLoader:

    /** Loads the image at `url`, registering the live GPU texture under `Scope` and returning the pure
      * [[kyo.Three.Ast.Texture]] handle the material `map` references.
      */
    def load(url: String)(using Frame): Three.Ast.Texture < (Async & Scope & Abort[ThreeException]) =
        for
            tex <- runLoad(url)
            _   <- register(url, tex)
        yield Three.Ast.Texture.FromUrl(url)

    /** Bridges the `TextureLoader.load` callback API to a fiber-completing effect; the error callback maps
      * to the typed `AssetLoadFailed` leaf. A synchronous throw (such as the `document.createElementNS`
      * call that `ImageLoader` performs, which fails in a headless Node environment) is caught and routed
      * to `AssetLoadFailed` so no exception escapes the `Abort` row.
      */
    private def runLoad(url: String)(using Frame): js.Dynamic < (Async & Abort[ThreeException]) =
        // Unsafe: Promise.init creates an IOPromise; callbacks complete it from inside loader callbacks.
        Promise.init[js.Dynamic, Abort[ThreeException]].map { p =>
            try
                val loader = js.Dynamic.newInstance(ThreeFacade.TextureLoader)()
                loader.load(
                    url,
                    (tex: js.Dynamic) =>
                        // Unsafe: callback runs outside kyo effect machinery; AllowUnsafe required.
                        import AllowUnsafe.embrace.danger
                        p.unsafe.completeDiscard(Result.succeed(tex))
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

    /** Registers the live texture for disposal on scope close and records it in the reconciler's registry
      * so the material materialize resolves it by `url`.
      */
    private def register(url: String, tex: js.Dynamic)(using Frame): Unit < (Sync & Scope) =
        Scope.acquireRelease(
            // Unsafe: record the loaded live texture for the reconciler to resolve at material materialize.
            Sync.Unsafe.defer(Reconciler.TextureRegistry.register(url, tex))
        ) { _ =>
            // Unsafe: dispose the GPU texture and drop it from the resolution registry on scope close.
            Sync.Unsafe.defer { val _ = tex.dispose(); Reconciler.TextureRegistry.remove(url) }
        }

    private def asThrowable(err: js.Dynamic): Throwable =
        // Wraps an Error-like js.Dynamic from the loader callback as a Throwable.
        new RuntimeException(String.valueOf(err))

end TextureLoader
