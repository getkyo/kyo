package kyo

import scala.scalajs.js.annotation.JSExportTopLevel

/** The client hydrate entry for `UIOwnershipHydrateBrowserTest`: rebuilds the SAME `fixture.OwnershipScene`
  * tree the server rendered (so `data-kyo-path` matches the SSR markup by construction) and brings the
  * browser's half of the page to life through the public `UI.runHydrate` entry.
  *
  * Lives in the `fixtures` configuration (`kyo-ui/js/src/fixtures`), not a separate project. That
  * configuration links its own small ESModule whose only root is this export, so DCE keeps just the
  * kyo-ui runtime the browser page needs and drops the test suites. `UIOwnershipHydrateBrowserTest`
  * serves that link and imports this entry.
  *
  * After hydrating, it writes values only the BROWSER can produce: `fromBrowser` into the client-owned
  * region's signal, which the browser must render because it owns that region, and `clientClobber` into the
  * SERVER-owned region's signal, which the browser must NOT render because it does not. The two writes
  * happen AFTER `runHydrate` so the subscriptions are already live and the page shows what each side
  * actually drives rather than what it happened to be seeded with.
  *
  * Lives in package `kyo` (like the kyo-threejs hydrate entries) because it uses the `private[kyo]`
  * `Frame.internal`, the zero-derivation frame an entry point needs since package `kyo` non-test code
  * cannot auto-derive one.
  */
object UIOwnershipHydrate:

    @JSExportTopLevel("hydrateUIOwnership")
    def hydrateUIOwnership(): Unit =
        given Frame = Frame.internal
        // Unsafe: the page-to-kyo boundary; runs hydration on a detached fiber whose ambient Scope stays
        // open for the client-owned regions' fibers and the local event drain.
        import AllowUnsafe.embrace.danger
        val held: Unit < Async =
            Scope.run {
                for
                    serverRef <- Signal.initRef("from-server")
                    clientRef <- Signal.initRef("server-default")
                    tree = fixture.OwnershipScene.ui(serverRef, clientRef)
                    _ <- UI.runHydrate(tree)
                    _ <- clientRef.set(fixture.OwnershipScene.fromBrowser)
                    _ <- serverRef.set(fixture.OwnershipScene.clientClobber)
                    _ <- Async.never // park so the region fibers and the event drain stay live
                yield ()
            }
        val _ = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(held).unit)
    end hydrateUIOwnership

end UIOwnershipHydrate
