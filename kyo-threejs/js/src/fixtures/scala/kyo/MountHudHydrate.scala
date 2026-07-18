package kyo

import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry for the live-mount HUD browser test (`ThreeEmbedBrowserTest`): rebuilds the
  * SAME `fixture.MountHudScene` tree the server rendered (so `data-kyo-path` matches the SSR markup by
  * construction) and hydrates it onto the already-SSR'd DOM through the public `UI.runHydrate` entry.
  * That both attaches the embedded `Three.embed` canvas to its live mount and brings the page's
  * client-owned HUD to life, so the frame counter fed by `Three.Mount.renders` and the Capture button
  * that calls `Three.Mount.readPixels` run in the browser, where the mount actually exists.
  *
  * Lives in package `kyo` (like the other hydrate entries) because it uses the `private[kyo]`
  * `Frame.internal`, the zero-derivation frame an entry point needs since package `kyo` non-test code
  * cannot auto-derive one.
  */
object MountHudHydrate:

    @JSExportTopLevel("hydrateMountHud")
    def hydrateMountHud(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope stays
        // open for the mount's frame loop, the HUD's region fibers, and the mount observer.
        import AllowUnsafe.embrace.danger
        val held: Unit < Async =
            Scope.run {
                for
                    tree <- fixture.MountHudScene.ui
                    _    <- UI.runHydrate(tree)
                    _    <- Async.never
                yield ()
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(held).unit)
    end hydrateMountHud

end MountHudHydrate
