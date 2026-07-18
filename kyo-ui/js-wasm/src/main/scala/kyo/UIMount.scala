package kyo

import kyo.internal.DomBackend

extension (ui: UI.type)

    /** Mount a UI into the page body. */
    def runMount(u: UI)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u)

    /** Mount a UI into a specific DOM element by CSS selector. */
    def runMount(u: UI, selector: String)(using Frame): Unit < (Async & Scope) =
        DomBackend.mount(u, selector)

    /** Hydrate an already-SSR'd page client-side: walk `u` (rebuilt from the same builder the server
      * rendered, so `data-kyo-path` matches by construction), attach each `Three.embed` canvas to its live
      * mount so server-pushed prop and structural ops reach the live scene, and subscribe every
      * `Element.clientOwned` region so the browser drives the state only it can hold. Unlike `runMount`,
      * which renders a fresh tree into the DOM, this attaches to the DOM the server already SSR'd, touching
      * no `innerHTML`.
      *
      * THE AMBIENT SCOPE MUST OUTLIVE THE PAGE. This returns as soon as the mounts are dispatched and the
      * client-owned regions are subscribed; the backend finalizers and the region fibers it forks are all
      * owned by the ambient `Scope`, so closing that scope tears them down. `Scope.run(UI.runHydrate(tree))`
      * therefore subscribes the regions and immediately interrupts them, leaving a page whose client-owned
      * regions never update. Hold the scope open for the life of the page, as `runMount` does internally:
      *
      * {{{
      * Scope.run {
      *     for
      *         tree <- MyPage.ui
      *         _    <- UI.runHydrate(tree)
      *         _    <- Async.never
      *     yield ()
      * }
      * }}}
      */
    def runHydrate(u: UI)(using Frame): Unit < (Async & Scope) =
        DomBackend.hydrateBackendNodes(u)

    /** Injects a [[kyo.Stylesheet]] into the live document (`<head>` `<style>`), client-side.
      * JS-only. Idempotent: a second call with the same rendered CSS text is a no-op and does
      * not append duplicate rules. Reuses the existing kyo-ui document stylesheet (the same
      * `<style>` element `runMount` appends per-element rules to), so a mounted app's authored
      * stylesheet and its per-element styles share one sheet.
      */
    def runStylesheet(sheet: Stylesheet)(using Frame): Unit < Sync =
        DomBackend.injectStylesheet(sheet)

end extension
